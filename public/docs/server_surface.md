# What Field Exposes, and What Stops It

Field is not just an application. It is an application with a **web server** inside it, and
that server is how Field's own editor talks to Field. When you open a document, the window
you are looking at is a browser, and everything you do in it — typing in a box, dragging a
box, running code — travels to Field's JVM over HTTP and a WebSocket.

That is a good architecture. It is also, unavoidably, a *listening socket on your machine*,
and this page is the honest answer to the question that follows: **what can reach it, and
what stops a web page you happen to be visiting from driving your editor?**

You do not need to do anything with this page to use Field. It is here because anyone running
Field on a laptop on a shared network — a studio, a university, a conference wifi — deserves
to know the shape of what they are running, and because "read the fourteen thousand lines"
was the only previous answer.

## The one-paragraph version

Field's server binds `0.0.0.0`, which means **any machine on your network can reach it**, and
it does not authenticate anything. It also tells every website you have open that they may
call it, because its CORS policy is `anyHost()`. Both of those are deliberate, both have a
feature depending on them, and there is an opt-in switch —
`AFIELD_API_REQUIRE_SAME_SITE=1` — that closes the *website* half but not the *network* half.
If you are on a network you do not trust, the thing that protects you is the network, not
Field.

The rest of this page is what that means precisely, which defences exist, and what each one
actually stops.

## The trust boundary, drawn properly

Three different questions get confused with each other constantly, and almost every mistake
in this area comes from answering one when you meant another:

| question | what answers it in Field | what it stops |
| --- | --- | --- |
| which **machine** is this from? | `isLoopbackDocsClient`, on the socket's remote address | stops: another device on the wifi |
| which **website** is this from? | `isSameSiteRequest`, on `Host`, `Sec-Fetch-Site` and `Origin` | stops: a page open in *your* browser |
| **who** is this? | nothing — there is no `install(Authentication)` in the tree | stops: nothing |

The third row is the one to sit with. Field has no login, no token on most routes, no
per-request credential. Everything below is a defence about *where a request came from*, not
*who sent it* — and that distinction is why a loopback check, on its own, is much weaker than
it sounds.

### Why "loopback only" is not the defence it reads as

This is the single most important idea on the page, and it was learned the expensive way.

A check that the request came from `127.0.0.1` sounds like it means "this came from me". It
does not. **Your browser is on `127.0.0.1` too.** So when a page on `evil.example` runs

```diffstatic {data-lang=javascript}
fetch('https://localhost:9092/api/docs-annotations', {method: 'POST', body: …})
```

the request that arrives at Field comes from your browser, from loopback, and a loopback
check waves it through. The page cannot *read* the reply without Field's permission — that is
what CORS is for — but for anything whose effect is the point rather than the answer, not
reading the reply costs the attacker nothing.

On 2026-08-11 this was measured rather than argued, with a real Chromium page served from
`http://127.0.0.1:9493` — a genuinely foreign origin:

- `GET /api/instance-info` and `GET /api/welcome-changelog` were **read** by that page. The
  first returns the absolute path of your checkout; the second returns your recent commit
  messages.
- `POST /api/shutdown-instance` from that page **stopped the server**.

So the defences that matter are the ones that ask *which website*, and there are four.

## Defence 1 — CORS, and the `anyHost()` that is deliberately there

Ktor's CORS plugin decides which websites may read Field's replies. Field's configuration is
five lines, and it is pinned literally by a test so that changing it has to be deliberate:

```diffstatic {data-lang=kotlin}
install(CORS) {
    allowHeader(HttpHeaders.ContentType)
    allowMethod(HttpMethod.Get)
    allowMethod(HttpMethod.Post)
    allowMethod(HttpMethod.Delete)
    anyHost()
}
```

`anyHost()` means *any website you have open in any tab* may call Field and read the answer.
That is not an oversight; it is load-bearing. The offline document viewer posts a document
back to `POST /api/import-offline-doc` from whatever origin its bundle was served from, and
`/api/import-offline-doc` is the one path explicitly exempted from the same-site guard below
for exactly that reason.

Two things this configuration *does* stop, and they are worth knowing because they are the
only two:

- **`PUT` is not allow-listed.** A cross-origin `PUT` never reaches a handler. That is
  configuration rather than a guard — it protects every `PUT` by accident, and a future
  developer adding `allowMethod(HttpMethod.Put)` for an unrelated endpoint would silently
  remove it from all of them. A test says so.
- **`X-Field-Docs-Improvement-Token` is not allow-listed.** The documentation-improvement
  endpoint can spawn a coding agent with write access to your checkout. It requires that
  header, and because the header is not CORS-safelisted and not in `allowHeader`, a web
  page's preflight fails before the request is sent. `anyHeader()` in that block would
  quietly convert an agent-spawning endpoint into something a web page can reach.

??? aside "Pinned by"
    `FieldApiCorsExposureTest.theCorsConfigurationIsExactlyWhatWeThinkItIs`, and
    `theImprovementTokenHeaderIsNotReachableFromAWebPage` for the second bullet.

## Defence 2 — the read/write split, and the switch that closes the writes

`AFIELD_API_REQUIRE_SAME_SITE=1` (or `-Dafield.api.requireSameSite=1`) turns on an
application-wide interceptor that **refuses cross-site state-changing requests** across
Field's whole API.

It is **off by default**, and the reason is uncomfortable but honest: turning a refusal on
across three dozen routes at once is how you discover, at four in the morning, which of them
your own tooling was quietly relying on. So the default is the old behaviour and the switch
is documented, rather than the other way around. Field prints a loud warning line at startup
either way.

What it inspects is **mutating methods only** — `POST`, `PUT`, `DELETE`. A `GET` goes
through untouched, which is deliberate (a WebSocket upgrade is a `GET`, and so is every
ordinary page load) and is also why reads need saying separately:

> A read is not a lesser exposure than a write. It is a different one. `GET /api/instance-info`
> leaks the path of your work, `GET /api/welcome-changelog` leaks your commit messages, and
> `GET /api/source` and `GET /api/workspace-search` reach source text. **33 of Field's 36 read
> routes have no guard at all**, and the same-site switch does not cover them.

And a thirteen-route wrinkle found on 2026-08-22, because the switch's read/write split is
only as good as knowing which routes are which. Thirteen of Field's routes are declared
`route("/welcome") { handle { … } }` — a route that names **no** HTTP verb, and therefore
answers all of them. Twelve serve one HTML page each and are `get { }` written the short way;
the thirteenth, `/lazy-value-fragment`, takes four query parameters into a value renderer.
They are not a new hole — the switch covers them exactly as it covers any other route, since
it inspects the *request's* method rather than the route's declaration — but they were absent
from the inventory that counts Field's write surface, so that count read 40 when the truth was
53. It now reads 53, and the thirteen are listed with their guards in
`dev_docs/server_surface_inventory.md`.

**The limitation, stated plainly:** a request carrying no `Origin` header at all is **not** refused. The
switch closes the browser half of the problem and does nothing about the network half.

??? aside "Pinned by"
    `FieldApiWriteGuardTest` — seven tests, one of which exists purely to assert that limitation — and
    `ServerSurfaceLedgerTest`, which is where the read routes are counted.

## Defence 3 — the DNS-rebinding check, which is the subtle one

Suppose Field refuses any request whose `Origin` does not match its `Host`. That sounds
airtight: the browser sets both, from the same URL, and a page cannot forge either.

Here is the attack it does not stop.

1. You visit `field.attacker.example`. Its DNS record has a **one-second TTL** and initially
   resolves to the attacker's real server.
2. The page loads, and its script sits waiting.
3. The DNS record is changed to resolve to `127.0.0.1`.
4. The script fetches `https://field.attacker.example:9092/api/…`. Your browser re-resolves
   the name, gets `127.0.0.1`, and **connects to Field on your own machine** — while still
   believing it is talking to the same origin the page came from.

Every signal now agrees, and every one of them is a lie: `Origin` is
`https://field.attacker.example`, `Host` is `field.attacker.example`, they match perfectly,
and the browser reports `Sec-Fetch-Site: same-origin`. Equality proves same-origin. **It does
not prove that the origin was Field.**

The fix is small and comes first, before any of the other signals are consulted: for any
browser-shaped request, `Host` must name **`localhost` or a literal IP address**. No name is
resolved — resolution is the attacker's move, so the check refuses to make it. An attacker
cannot get a browser to send `Host: localhost` for a page served from their own domain,
because the browser sets `Host` from the URL that was navigated to.

Only after that does the rest run, and the order matters:

- **`Sec-Fetch-Site` is preferred**, because the browser sets it and a page cannot: it is a
  forbidden header name, so no `fetch` option touches it. `same-origin` passes. `none` passes,
  because a typed URL or a bookmark has no initiator and is not a cross-site request at all.
  `cross-site`, `same-site` and anything unrecognised are refused.
- **`same-site` being refused is the interesting one.** `http://localhost:9493` is a different
  *origin* from `https://localhost:9092` but the same *site* — and that is precisely the
  origin the 2026-08-11 attack was measured from.
- For a client too old to send `Sec-Fetch-Site`, `Origin` is compared with the
  already-validated `Host`. `Origin: null` — a sandboxed iframe — has no authority and cannot
  match.
- **No `Origin` at all is allowed.** That is `curl`, a script, Field's own native clients.
  Nobody's ambient browser credentials are being borrowed, so it is not what this guard is
  about — and refusing it would break every non-browser client Field has.

That last point is the limitation, stated plainly: this guard closes the *browser* half. It
does nothing about a request from another machine, because such a request simply sends no
`Origin` and is therefore, correctly, not a cross-site browser request.

??? aside "Pinned by"
    `DocsAnnotationSecurityTest` over the predicate, and `DocsAnnotationRouteSecurityTest` through the real
    routes. The rebinding row was watched fail first: `Host == Origin` plus `Sec-Fetch-Site: same-origin`
    returned 200 until the literal-host allow-list was moved ahead of those signals.

## Defence 4 — the WebSocket upgrade guard, and why it needed one of its own

A WebSocket begins life as an HTTP `GET`. That has two consequences, and together they left a
hole open until 2026-08-19:

- **CORS does not govern a WebSocket handshake at all.** A page can open a WebSocket to any
  origin it likes; the browser will not preflight it and will not withhold the connection.
- **The same-site interceptor skips `GET`**, on purpose, because reads and page loads must go
  through.

So `AFIELD_API_REQUIRE_SAME_SITE=1` could be fully on and a foreign page could still open
Field's command channel and start sending commands. The startup banner said the switch
refused "the browser half", and for the two WebSocket command channels that sentence was
false.

The fix applies the same origin policy *inside* the upgrade, and closes the connection with a
WebSocket policy-violation code rather than an HTTP status:

```diffstatic {data-lang=kotlin}
webSocket("/ws") {
    if (refuseCrossSiteFieldWebSocket("/ws")) return@webSocket
    …
}
```

Field's three channels, and their roles:

| channel | role | guard |
| --- | --- | --- |
| `/ws` | the main editor command channel | same-site, when the switch is on |
| `/controllers/ws` | remote-control commands | same-site, when the switch is on |
| `/welcome-events` | server-to-client refresh notifications; incoming data ignored | none needed |

One detail worth being precise about, because it is visible from the attacking side: the
handshake **succeeds**. The guard runs in the handler, so the client gets
`HTTP/1.1 101 Switching Protocols` and *then* a close frame with code `1008` before any
message is processed. A foreign page's `onopen` may therefore fire, followed immediately by
`onclose`. No command ever reaches a handler, which is the property that matters — but
"the upgrade is refused" would be the wrong mental model to debug from.

??? aside "Pinned by"
    `FieldWebSocketWriteGuardTest` — four route-level tests: the default still allows a cross-site upgrade,
    the switch closes one with a policy violation, Field's own browser still connects, and a native client
    sending no browser-origin headers still connects.

## Defence 5 — route isolation for the documentation-annotation feature

Four routes carry guards of their own rather than relying on the global switch, because their
exposure is specific and understood. They are the annotation notebook
(`POST`/`PUT`/`DELETE /api/docs-annotations…`) and `POST /api/docs-improvement`, which can
spawn a coding agent with write access to your checkout.

That last one is guarded by **five independent conditions, each separately sufficient** to
stop it: the feature must be switched on, a random per-run token must match (compared with
`MessageDigest.isEqual`), the request must be from loopback, it must be same-site, and the
target directory must exist and be a directory. Its token header is deliberately not
CORS-allow-listed, so a web page's preflight fails before any of that is reached.

The same file's documentation *mirror* — `GET /field-docs/{...}`, which fetches
`openendedgroup.com` and serves it back from Field's own origin so an injected annotation
panel can talk to Field without CORS — is loopback-only for a reason worth spelling out: the
trick's cost is that **every script in the mirrored page is same-origin with Field**, and
Field has dozens of other unguarded `/api/…` routes. A hostile or compromised documentation
page would be able to call all of them. Restricting the mirror to loopback takes that whole
same-origin position off the local network.

## The two things that are open, and are open on purpose

Nothing above closes these. Both are deliberate, both cost something real to close, and
pretending otherwise would make this page useless.

**Another host on your network.** The HTTPS connector binds `0.0.0.0` so that the visionOS
shell and other devices can reach Field — a real, used workflow. Ktor's CORS plugin does not
run at all for a request with no `Origin` header, and the same-site check deliberately allows
such a request. So `curl` from any machine on the same wifi reaches essentially every route,
today. Closing this needs authentication or a narrower bind address, and a narrower bind
breaks the headset. **It is your network that is protecting you here.**

**The `/ws` command surface, by default.** This is the part most likely to surprise, and it
is the finding that reordered this page.

Field's HTTP routes get all the attention — 89 of them, 53 state-changing, all inventoried
and tested. But the code with the most reach is not behind an HTTP route at all. The file
proxy, the H2 database explorer, the box-execution commands and the agent backdoor declare
**no HTTP routes between them**. They are entries in a message-dispatch table reached over
`/ws`, and there are **105 of them** — about twice the HTTP write surface. A second, smaller
table on the same socket carries **9 more** "raw" channels, which receive the whole frame and
parse it themselves: the H2 explorer, the file browser, the log stream, the spreadsheet, the
three.js and Metal viewers, and the RemoteJS bridge. Those nine went uncounted by any census until
2026-08-22.

Among them, `launch-all`, `launch-batch` and `launch-fragment` run the code in your document,
in Field's JVM, with Field's privileges. There is no sandbox between a `/ws` frame and that.

So the most privileged surface Field has is behind the door that got its **first** guard last,
on 2026-08-19 — and that guard is off by default. If you take one action after reading this
page, `AFIELD_API_REQUIRE_SAME_SITE=1` is it.

## Measured, not asserted: 2026-08-20, a real Field with the switch on

Everything above is also checked by tests, but a test can share a misconception with the code
it tests. So this table is a real Field on port 9603, started with
`AFIELD_API_REQUIRE_SAME_SITE=1`, probed over its real TLS socket. The write probes target a
path that does not exist, so `403` means the guard refused and `404` means the request got
past the guard and reached routing — which separates "refused" from "allowed" without changing
any state.

| probe | result |
| --- | --- |
| `POST`, `Origin: https://evil.example`, `Sec-Fetch-Site: cross-site` | **403** refused |
| `POST`, `Host` = `Origin` = `field.attacker.example`, `Sec-Fetch-Site: same-origin` | **403** refused — the rebinding shape |
| `POST`, `Origin: http://localhost:9493`, `Sec-Fetch-Site: same-site` | **403** refused — same site, different origin |
| `POST`, `Origin: null` (sandboxed iframe) | **403** refused |
| `POST`, `Host` and `Origin` both `localhost:9603`, same-origin | **404** allowed through |
| `POST` with no `Origin` at all (plain `curl`) | **404** allowed through — *the limitation* |
| `GET /api/instance-info`, `Origin: https://evil.example`, cross-site | **200 with a body** |
| `/ws` upgrade, cross-site | `101`, then close `1008` |
| `/ws` upgrade, `Host` = `Origin` = `field.attacker.example` | `101`, then close `1008` |
| `/ws` upgrade, no `Origin` (native client) | open, and a command reached a handler |

Two rows are the ones to take away. The **second-to-last pair** shows the rebinding guard
working on the WebSocket path as well as the HTTP one. And the `GET` row is the read gap, live:
with the switch fully on, a foreign origin still read the absolute path of the checkout.

## What to actually do

| if you are… | do this |
| --- | --- |
| on a network you do not fully trust | treat Field as an open door and rely on the network: a firewall on Field's port, or don't run it there |
| worried about websites you visit | set `AFIELD_API_REQUIRE_SAME_SITE=1`. It closes cross-site HTTP writes **and** cross-site WebSocket upgrades |
| running automation or the agent backdoor | those send no browser `Origin`, so the switch does not affect them. That is the intended behaviour, and also the limitation |
| adding a route or a `/ws` command to Field | `dev_docs/server_surface_inventory.md` is the ledger, and the build will make you edit it |

## Where the guarantees actually live

Six test files, and they are a better statement of intent than any prose including this page.
Three start a real Ktor application; three read source.

| file | what it pins |
| --- | --- |
| `FieldApiCorsExposureTest` | the CORS block literally; the write-route → guard table; that the token header is not allow-listed |
| `FieldApiWriteGuardTest` | `AFIELD_API_REQUIRE_SAME_SITE`, including its limitation |
| `FieldWebSocketWriteGuardTest` | the upgrade guard, on the default and on the switch |
| `DocsAnnotationRouteSecurityTest` | the four guarded routes, through the real CORS configuration |
| `DocsAnnotationSecurityTest` | the guard predicates as pure functions |
| `ServerSurfaceLedgerTest` | the read routes, the thirteen all-method routes, the static mounts, all 105 `/ws` commands and the 9 raw `/ws` channels, against the ledger page |

For the full route-by-route inventory — every read route with its guard, every all-method
route, every static directory mount, every one of the 105 commands with the file it lives in,
and the 9 raw channels — see `dev_docs/server_surface_inventory.md`. That page is asserted
against the source in both directions, so a new door cannot appear without the build saying
so.

One caveat on how much that promise is worth, since the 2026-08-22 pass is the reason four of
those categories exist: a census can only refuse a door it has a *shape* for. Both censuses
were looking for a verb call with a quoted path, and Field declares routes three other ways —
verbless, pathless, and nested inside another `route(…)`. Floors and landmarks catch a census
that shrank; they cannot catch a category never counted. What caught these was a second,
independently written census that disagreed on the totals, and both are now in the build.
