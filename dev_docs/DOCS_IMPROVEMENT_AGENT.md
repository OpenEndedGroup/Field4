# The documentation annotation layer, and the coding agent behind it

Two features live in `src/jvmMain/kotlin/afield/server/docsAnnotations.kt` and
`src/commonMain/resources/docsAnnotations.js`.

The first is benign and pleasant: `/field-docs/…` mirrors the published documentation
through Field's own origin and injects a note-taking panel into it. Select a sentence,
press `Ctrl+Shift+A`, type a note, and it is stored in `~/.afield/docs-annotations.json`
by default (or the file selected by `AFIELD_DOCS_ANNOTATIONS_STORE`), with an anchor good
enough to find the sentence again after the page is rebuilt.

The second is not benign. `POST /api/docs-improvement` takes the unresolved notes, turns
them into a prompt, and runs

```
codex exec --sandbox workspace-write "<the prompt>"
```

in a source checkout. It is an autonomous coding agent, with write access, steered by
text that arrived over HTTP.

This page is about the second one: what it can do, what stops it, and how to turn it on.

## Threat model

Field's HTTPS connector binds `0.0.0.0` (`Server.kt`, `httpsHost`). That is deliberate —
browsers and the visionOS shell load `/welcome` and `/index.html` from other devices on
the local network — and it is not going to change. The consequence is that **every route
Field installs is reachable from the local network**, and a route that must not be has to
say so itself.

Until 2026-08-11 this one did not. The attack was three requests and no credential:

1. `POST /api/docs-annotations` with a note containing whatever instructions you like;
2. `POST /api/docs-improvement`;
3. wait.

The agent ran in `Paths.get(".")` — whatever directory `field.sh` was launched from — and
nothing recorded which one that was, so even after the fact you could not tell from the
logs which tree had been edited.

**The boundary now drawn is "this machine's own user", not "this machine".** A process
running as you can reach loopback, read the token out of the environment or out of the
log, and in any case already has every access the coding agent would be handed. Nothing
here pretends otherwise.

### The second attack, which the first pass reasoned its way to and did not close

The 2026-08-11 pass wrote the correct sentence down in three places — *loopback does not
stop a cross-site POST, because your own browser is on loopback* — applied it to step 2 of
the attack above, and did not apply it to **step 1**, one screen up the same file. So from
2026-08-11 to 2026-08-12 this worked from any page you had open:

```js
fetch("https://localhost:9092/api/docs-annotations", {
  method: "POST",
  headers: {"Content-Type": "application/json"},
  body: JSON.stringify({annotation: {
    pageUrl: "https://openendedgroup.com/field4/index.html",
    note: "…whatever you would like the coding agent to be told…",
  }}),
});
```

`application/json` is not a CORS-safelisted content type, so the browser preflights — and
the preflight *passes*, because `Content-Type` is exactly what `allowHeader` allows, `Post`
and `Delete` are exactly what `allowMethod` allows, and `anyHost()` covers the origin. The
route's only guard was the loopback check, and the attacking page is in a browser that is
on loopback. `DELETE` was reachable the same way. `PUT …/resolve` was not, but only because
`Put` happens to be missing from `allowMethod` — one line of configuration in another file,
not a defence.

That is a write to the *prompt*. A hostile page could pre-load instructions and simply wait
for you to press the button yourself, for your own reasons, with a note in the batch you
did not write. `GET /api/docs-improvement` was readable cross-site too, and it returns
`workingDirectory` and up to 120 KB of the agent's log tail.

Closed 2026-08-12; see the next section.

## Annotations are same-site as well as loopback-only

Two questions now, because they are two different questions:

| question | what answers it | what it stops |
| --- | --- | --- |
| which **machine**? | `isLoopbackDocsClient`, on `request.local.remoteAddress` | another device on the Wi-Fi |
| which **website**? | `isSameSiteRequest`, on a literal/localhost `Host`, then `Sec-Fetch-Site` and `Origin` | a page in *your* browser, including DNS rebinding |

For any browser-shaped request, `Host` must first name `localhost` or a literal IP address.
No name is resolved. This closes the DNS-rebinding shape in which a page keeps
`Origin == Host == field.attacker.example`, reports `Sec-Fetch-Site: same-origin`, and changes
only the address its hostname resolves to. Equality proves same-origin; it does not prove that
the origin named Field.

After that check, `isSameSiteRequest` prefers **`Sec-Fetch-Site`**, which the browser sets and a page cannot
— it is a forbidden header name, so no `fetch` option touches it. `same-origin` passes;
`none` passes, because a typed URL or a bookmark has no initiator and is not a CSRF;
`cross-site`, `same-site` and anything unrecognised are refused. `same-site` being refused
is the interesting one: `http://localhost:9493` is a different *origin* from
`https://localhost:9092` but the same *site*, and it is precisely the origin the 2026-08-11
session measured a successful attack from.

For a client too old to send it, `Origin` is compared with the already-validated `Host`.
Both are set by the browser from the same URL, so they agree for a same-origin request.
`Origin: null` — a sandboxed iframe — has no authority and cannot match.

**No `Origin` at all is allowed.** That is `curl`, a script, the visionOS shell's own
fetches; nobody's ambient credentials are being borrowed, so it is not the thing this guard
is for, and on these routes loopback is still the boundary. It does mean the `curl` recipes
on this page keep working.

`AFIELD_DOCS_ANNOTATIONS_ALLOW_CROSS_SITE=1` re-opens it, with a warning at startup, and —
like the remote hatch — never opens the spawn.

The `/field-docs/…` mirror deliberately keeps the machine guard and *not* the site guard: it
is reached by navigation, and a link to a page from another site is an ordinary way to
arrive at it.

### Verified on a real Field, port 9492, 2026-08-12

| request | result |
| --- | --- |
| `POST /api/docs-annotations`, `Origin: https://somewhere-else.example`, `Sec-Fetch-Site: cross-site` | **403**, "…are same-site only…" |
| `DELETE /api/docs-annotations/{id}`, same headers | **403** |
| `PUT /api/docs-annotations/{id}/resolve`, `Sec-Fetch-Site: cross-site` | **403** |
| `GET /api/docs-improvement`, same headers | **403**, no `workingDirectory` in the body |
| `POST` and `DELETE` with `Origin: https://localhost:9492`, `Sec-Fetch-Site: same-origin` | **200** — the panel is unaffected |
| `GET /api/docs-annotations` with no `Origin` at all (plain `curl`) | **200** |
| `POST` from `https://192.168.2.125:9492`, the machine's own LAN address | **403**, the *loopback* refusal |
| `GET /field-docs/index.html` | **200**, and still injects `docsAnnotations.js` |

The human's `~/.afield/docs-annotations.json` was checked before and after: 14 notes, no
note from the cross-site attempt, and the same-origin probe cleaned up after itself.

## Turning it on

The agent is off unless asked for. Off means `POST /api/docs-improvement` returns 403
with a message naming the flag; `GET` still answers, with `"enabled": false`, so a UI can
grey the control out rather than offer a button that always fails.

```bash
# minimum: enable it. Field mints a random token and prints it once at startup.
AFIELD_DOCS_IMPROVEMENT=1 ./field.sh

# or, equivalently, on this JVM's command line (a -D flag beats the environment)
JAVA_OPTS=-Dafield.docsImprovement.enabled=true ./field.sh

# a stable token, and an explicit checkout, which is what you want if you do this often
export AFIELD_DOCS_IMPROVEMENT=1
export AFIELD_DOCS_IMPROVEMENT_TOKEN=$(openssl rand -hex 24)
export AFIELD_DOCS_IMPROVEMENT_DIR=/Users/you/field4
./field.sh
```

At startup Field logs exactly one line saying which of these it is doing — the directory
the agent would run in, and the token — or one saying the feature is disabled and how to
enable it. If you cannot find that line, the server you are talking to is not the server
you think it is.

Launching a run:

```bash
curl -sk -X POST https://localhost:9092/api/docs-improvement \
  -H 'Content-Type: application/json' \
  -H "X-Field-Docs-Improvement-Token: $AFIELD_DOCS_IMPROVEMENT_TOKEN" \
  -d '{"tags":["docs"]}'
```

Checking the posture before you do, which is the point of reporting the directory on
`GET` as well as after a run:

```bash
curl -sk https://localhost:9092/api/docs-improvement
# {"running":false,"status":"idle", … ,"enabled":true,"workingDirectory":"/Users/you/field4"}
```

## The contract

| variable | default | what it does |
| --- | --- | --- |
| `afield.docsImprovement.enabled` (`-D`) | unset | turns the agent-spawning route on. Beats the environment variable when both are set. |
| `AFIELD_DOCS_IMPROVEMENT` | unset | the same switch, from the environment. `1`, `true`, `yes`, `on`; anything else — **including the empty string** — is off. |
| `AFIELD_DOCS_IMPROVEMENT_TOKEN` | a fresh random 24-byte hex value, printed once | the value of `X-Field-Docs-Improvement-Token`. Compared with `MessageDigest.isEqual`. |
| `AFIELD_DOCS_IMPROVEMENT_DIR` | the server's own working directory, canonicalised | the checkout the agent may edit. Must exist and be a directory, or the run refuses with a sentence. |
| `AFIELD_DOCS_ANNOTATIONS_ALLOW_REMOTE` | unset | lets **annotation** requests come from other devices. Browser URLs must still use a literal IP (or also opt into cross-site annotations for a custom hostname). Logged as a warning at startup. Never affects the spawn. |
| `AFIELD_DOCS_ANNOTATIONS_ALLOW_CROSS_SITE` | unset | lets **annotation** requests come from other *websites*. A different axis from the row above, and the same rule: warned about at startup, never affects the spawn. |
| `AFIELD_DOCS_ANNOTATIONS_STORE` (`-Dafield.docsAnnotations.store`) | `~/.afield/docs-annotations.json` | selects the annotation notebook. Relative paths are resolved from Field's working directory, so automation can use `.field-backdoor/docs-annotations.json` without touching the human's global notes. The property wins over the environment. |
| `AFIELD_API_REQUIRE_SAME_SITE` (`-Dafield.api.requireSameSite`) | unset | refuses cross-site HTTP writes and browser upgrades to Field's two command WebSockets, not just this feature. Browser URLs must name `localhost` or a literal IP. Off by default; see the last section for what it does and — mostly — does not close. |

Five independent conditions guard the spawn, each separately sufficient to stop the
attack above:

- **it is off** unless enabled;
- **the token** must match;
- **the peer must be loopback**, decided on `call.request.local.remoteAddress`;
- **the request must be same-site**, decided on `Sec-Fetch-Site` and `Origin`/`Host`
  (added 2026-08-12; see below for why, given the token already exists);
- **the directory** must resolve to a real directory, and is logged before the process
  starts.

### Why the token exists even though the route is already loopback-only

Because **your own browser is on loopback**. Loopback-only stops another *machine*; it does
nothing about a page you are visiting in a tab. Field installs Ktor's CORS plugin with
`anyHost()` and `allowMethod(Post)`, so `https://somewhere-else.example/` can issue

```js
fetch("https://localhost:9092/api/docs-improvement", {method: "POST", …})
```

and the browser will *send* it. CORS only stops that page reading the reply — the coding
agent would already have started. That is a textbook CSRF, and the loopback check is
exactly the wrong shape of defence against it.

A custom request header is the right shape. `X-Field-Docs-Improvement-Token` is not a
CORS-safelisted header, so the browser must preflight, and the preflight fails: Field's
CORS config allows `Content-Type` and nothing else. A cross-site page therefore cannot
send the header at all, never mind guess it.

Since 2026-08-12 this route also asks `isSameSiteRequest` before anything else, which is
belt and braces on purpose: the argument above is correct, and it depends entirely on six
lines of CORS configuration in a different file staying exactly as they are. The route's
own check does not. The annotation hatch is forced shut for this call, for the same reason
`refuseDocsImprovementLaunch` never reads the remote hatch — a variable whose name says
"annotations" must not turn out to have opened the spawn.

The consequence, and it is deliberate: **the token is never served over HTTP.** Not from
an endpoint, not inlined into a page — because with `anyHost()`, anything this origin
would hand its own pages it would also hand that other website. It lives in the
environment and in the startup log. The welcome page's "Improve annotated docs" button
prompts for it once and caches it in `localStorage`, which *is* origin-scoped; a 401 drops
the cached copy and asks again, which is what you want given Field mints a fresh token on
every restart unless you set `AFIELD_DOCS_IMPROVEMENT_TOKEN` yourself.

### Two details in the loopback check are load-bearing and easy to undo by accident.

It reads `call.request.local`, not `call.request.origin`. `origin` is the connection
point as rewritten by `XForwardedHeaders`/`ForwardedHeaders`, out of a header the client
sends. Field does not install those plugins today; the guard is written so that it would
not silently become bypassable if someone did.

It uses `InetAddress.ofLiteral`, not `getByName`, so it can never perform a name lookup.
`isLoopbackDocsClient("localhost")` is therefore **false**, on purpose: resolving a name
would make an access-control decision depend on a DNS answer. There is a test whose only
job is to keep that false.

## Annotations are loopback-only too

Not because storing a string is dangerous, but because annotation text is the *input* to
the agent's prompt. Whoever can write a note can steer a run the human later launches.

That sentence was written on 2026-08-11 and is where the second attack came from: it
answered *which machine* and stopped there, when the thing it names — steering a run — is
just as available to a page in your own browser. Both questions are asked now; see
"Annotations are same-site as well as loopback-only", above.

## And so is the mirror, for a different reason

`/field-docs/…` fetches openendedgroup.com and serves it back **from Field's own origin**.
That is the whole trick: it is what lets the injected panel `fetch('/api/docs-annotations')`
without CORS. The price of the trick is that every script in the mirrored page is
same-origin with Field, and Field has around fifty other `/api/…` routes with no
authentication on them at all. A hostile or compromised documentation page would be able
to call all of them — read your workspace, execute box code, the lot.

Nothing about this is new; it has been true since the proxy was written, and the
documentation site is the human's own. It is written down here because it is the largest
remaining consequence of the design, and because it is invisible from the route's five
lines of code.

The mitigation taken is the cheap one. A remote reader gains nothing from the mirror that
they would not get from the real site — the *only* thing the proxy adds is the annotation
panel, and the panel is loopback-only — so the mirror is loopback-only too. That takes the
entire same-origin surface off the local network at the cost of one redirect of effort for
a tablet.

What is *not* done: a Content-Security-Policy on the proxied response. The obvious ones
(`sandbox`, a restrictive `connect-src`) also break the panel, which needs same-origin
access to Field. Doing this properly means serving the panel from a separate origin and
talking to Field over `postMessage`, which is a redesign rather than a hardening pass.

**The cost of both, stated plainly:** if you browse the docs on a tablet, or at your Mac's
LAN address rather than `localhost`, you now get a refusal instead of the mirror and your
notes. The refusal says what to do. `AFIELD_DOCS_ANNOTATIONS_ALLOW_REMOTE=1` restores the
old behaviour for the mirror and the annotations together — they were never really
independent — and never for the spawn.

## Redirects cannot walk the proxy off the docs site

The path checks constrain the URL the proxy *asks* for. `docsProxyClient` follows
redirects, so they say nothing about where the bytes came from. The final
`HttpResponse.uri()` is therefore checked against the same predicate, and a response that
arrived from outside `https://openendedgroup.com/field4/` is refused with a 502 and a log
line. This is only reachable if the documentation origin itself starts redirecting
somewhere strange — which is precisely when you want the mirror to stop rather than
faithfully follow.

## What is not solved

- **The default store is still one global file.** `~/.afield/docs-annotations.json` remains
  shared by the main tree, every worktree and every window so this change does not silently
  orphan existing notes. Automation and worktrees can instead set
  `AFIELD_DOCS_ANNOTATIONS_STORE=.field-backdoor/docs-annotations.json`; Field resolves it
  against that checkout and prints the resulting path at startup. Two JVMs pointed at the
  same file can still lose a note because the lock is per-JVM, so use distinct paths when
  they may write concurrently. `recordedInCheckout` remains attribution, not locking.
- **Prompt injection is mitigated, not removed.** With the endpoint gated, an attacker
  can no longer both plant the text and pull the trigger. A note you did not write can
  still end up in a run you did launch — the notes are yours, so this is mostly a
  statement about not annotating pages you do not trust.
- **The token is visible to your own processes**, in the environment and in the log. See
  the threat model: that is the boundary, deliberately.
- **`0.0.0.0` is still `0.0.0.0`.** Narrowing it would break the visionOS shell and
  remote browser clients, so the binding stayed and the routes grew guards. If a future
  Field wants a loopback-only mode, that is a server-wide switch and a different change.
- **The rest of the API is still unauthenticated, and that is the largest thing on this
  page.** 49 of 53 mutating HTTP routes have no route-specific guard, and `0.0.0.0` plus "Ktor's
  CORS plugin does not run at all for a request with no `Origin`" means `curl` from any
  host on the Wi-Fi reaches every one of them. Demonstrated 2026-08-12 rather than
  asserted: `GET https://192.168.2.125:9492/api/instance-info` — the machine's own LAN
  address, so a non-loopback peer — returned **200**. Nothing on this branch changes that.
  See the last section.
- **`CORS { anyHost() }` applies to Field's whole API.** Measured rather than asserted;
  it has its own section below.
- **The editor's docs iframe** (`Cmd+/`, `bootstrapMonaco.js`, default `/field-docs/`)
  follows the mirror's loopback rule. Opening Field at `localhost` — the normal case, and
  what `field.sh` prints — is unaffected. Opening it at the machine's LAN address means
  the iframe now needs `AFIELD_DOCS_ANNOTATIONS_ALLOW_REMOTE=1`.

---

# The other hole, measured: an unauthenticated API on `0.0.0.0` behind `anyHost()`

Still not fixed, and mostly still not fixable by a nightly branch. Written down with
evidence, and now with an **asserted table** rather than a number, because a number in a
worklog decays and this one should not:
`FieldApiCorsExposureTest.everyMutatingRouteAndItsGuardIsPinned` fails when a route is
added, when one is removed, and — the case a count could never see — when one quietly
loses its guard.

**49 of 53 state-changing HTTP routes have no route-specific access control**, and there is no
`install(Authentication)` anywhere in the tree.

That was 36 of 40 until 2026-08-22, and the difference is not thirteen new routes — it is
thirteen that the census could not see. A `route("/welcome") { handle { … } }` names no HTTP
verb and answers every one of them, and the discovery regex was looking for a verb call with a
quoted path. Twelve of the thirteen serve one HTML page each; the thirteenth,
`/lazy-value-fragment`, takes four query parameters into a value renderer. Demonstrated rather
than argued: an unguarded verbless route planted in `Server.kt` left the census
**BUILD SUCCESSFUL** on `d7d8dccc2`. See `server_surface_inventory.md:165### All-method routes`
for the thirteen, and for the open question of whether they should simply be `get { }`.

## Three exposures, which are worth not conflating

|  | reached by | closed by | status |
| --- | --- | --- | --- |
| **another website, HTTP** | a page in your browser, via `anyHost()` | `AFIELD_API_REQUIRE_SAME_SITE=1`, or per-route guards | switchable, **off by default** |
| **another website, WebSocket** | a page that opens `/ws` or `/controllers/ws` | `AFIELD_API_REQUIRE_SAME_SITE=1` on the upgrade | switchable, **off by default** |
| **another host** | `curl` from the Wi-Fi, via `httpsHost = "0.0.0.0"` | authentication, or a narrower bind | **open** |

The third row is the one to read twice. Ktor's CORS plugin does not run for a request
with no `Origin` header, and `isSameSiteRequest` treats such a request as same-site on
purpose — refusing it would break every non-browser client Field has, including the `curl`
recipes on this page. So `AFIELD_API_REQUIRE_SAME_SITE`, switched fully on, closes **none**
of the network exposure. `FieldApiWriteGuardTest.theGuardDoesNothingAboutTheNetworkAndThisTestSaysSoOutLoud`
asserts precisely that, so the limitation cannot decay into a claim.

Field says so itself now, once, at startup, whichever way the switch is set. If you cannot
find that line, the server you are talking to is not the server you think it is.

## What it is

`Server.kt` installs Ktor's CORS plugin for the whole application:

```kotlin
install(CORS) {
    allowHeader(HttpHeaders.ContentType)
    allowMethod(HttpMethod.Get); allowMethod(HttpMethod.Post); allowMethod(HttpMethod.Delete)
    anyHost()
}
```

`anyHost()` means *any website you have open in any tab* may call Field. The surface is
**53 state-changing HTTP routes**: 38 with literal paths (33 POST, 3 DELETE, 2 PUT), the
wildcard `POST /field-audio/{...}` and `PUT /field-audio/{...}` routes that the old
literal-only census missed, and — since 2026-08-22 — the 13 verbless
`route(…) { handle { … } }` routes that answer every method without naming one.
`PUT` is excluded by CORS, since only GET/POST/DELETE are
allow-listed — which is configuration, not a guard, and is not what `PUT …/resolve` relies
on any more. The application-wide same-site switch covers both Field Audio writes because
its interceptor is not restricted to `/api`; it remains off by default.

Four of the 53 are guarded, all of them in `docsAnnotations.kt`, and the table that says so
is asserted in `FieldApiCorsExposureTest`:

| route | guard |
| --- | --- |
| `POST /api/docs-annotations` | loopback + same-site |
| `PUT /api/docs-annotations/{id}/resolve` | loopback + same-site |
| `DELETE /api/docs-annotations/{id}` | loopback + same-site |
| `POST /api/docs-improvement` | loopback + same-site + enabled&token |
| `POST /field-audio/{...}` | none (covered only when the application switch is on) |
| `PUT /field-audio/{...}` | none (covered only when the application switch is on; also absent from CORS) |
| **the other 34** | **none** |

WebSockets are a separate census because their HTTP `GET` upgrade bypasses both CORS and
the write interceptor's mutating-method branch:

| route | role | guard |
| --- | --- | --- |
| `/ws` | main editor command channel | same-site when `AFIELD_API_REQUIRE_SAME_SITE=1` |
| `/controllers/ws` | remote control commands | same-site when `AFIELD_API_REQUIRE_SAME_SITE=1` |
| `/welcome-events` | server-to-client refresh notifications; incoming data ignored | none |

So `AFIELD_API_REQUIRE_SAME_SITE=1` refuses cross-site **HTTP writes**, including Field
Audio, and cross-site browser upgrades to both WebSocket command surfaces. It still permits
native/LAN clients that send no browser origin headers and is not authentication.

## What was measured, 2026-08-11, real Chromium, real foreign origin

An attacker page served from `http://127.0.0.1:9493` — a different origin from
`https://localhost:9492` by scheme, host and port — against a real Field:

| probe | result |
| --- | --- |
| `GET /api/instance-info` | **read**: `{"repoRoot":"/Users/marc/field4-worktrees/…","pid":4508}` |
| `GET /api/welcome-changelog` | **read**: the human's recent commit messages, in full |
| `POST /api/docs-improvement` **with** the token header | `TypeError: Failed to fetch` — preflight refused |
| `POST /api/docs-improvement` without it | reached the server, refused there, `401` |
| `POST /api/shutdown-instance` | **`200` — and Field stopped.** Listener before: `4508`. After: none. |

A web page you visit can stop your Field. It can also read the path of the checkout you
have open and what you have been committing.

## The one experiment that decides the fix

I was about to write "removing `anyHost()` only stops the *reads*, because CORS never
prevents a simple request being *sent*" — which is true of the browser model and **false
for Ktor**. So I measured it instead: `anyHost()` deleted, nothing else changed.

| probe | with `anyHost()` | without it |
| --- | --- | --- |
| `GET /api/instance-info` | read | `Failed to fetch` |
| `GET /api/welcome-changelog` | read | `Failed to fetch` |
| `POST /api/shutdown-instance` | **server stopped** | `Failed to fetch`; **server survived** |
| direct `curl` with `Origin: https://evil.example` | handled | `403 Forbidden`, handler never ran |
| `/health`, and a request with a matching `Origin` | `200` | `200` |

Ktor's CORS plugin is a **server-side gate**, not header decoration: it rejects a
disallowed origin before the handler. So removing `anyHost()` closes reads *and* writes,
and leaves same-origin use — which is all of Field's own UI — untouched.

## Why it is still there

One consumer, found by tracing rather than guessing.
`offline-viewer.js:878#/api/import-offline-doc` POSTs to
`${localBase}/api/import-offline-doc` to transfer a document into a running Field, and
`OFFLINE_DEPLOYMENT.md:34#/api/import-offline-doc` documents exactly that. It is the only cross-origin
`fetch` at a Field host in the whole tree; the viewer's other Field URLs are top-level
navigations, which CORS does not govern.

Ktor's CORS is a `RouteScopedPlugin`, so "install it only on that route" looks like the
answer — but the application-level plugin intercepts first and would 403 the request
before the route-scoped one ran. Verifying that ordering, and deciding what happens to a
viewer hosted at `file://` (`Origin: null`), is the actual work.

## What was taken instead of narrowing `anyHost()`

The 2026-08-11 recommendation was to replace `anyHost()` with an `AFIELD_ALLOWED_ORIGINS`
allow-list, empty by default. That is still a reasonable end state and it is still not
what was done, for a reason worth recording: it makes the *default* posture depend on
correctly enumerating every origin the offline viewer might be served from, including
`file://` (`Origin: null`), and getting that wrong breaks a feature silently at the exact
moment somebody is trying to import a document.

What 2026-08-12 did instead was cheaper and pushes the decision to where the knowledge is:

- **per-route guards** on the four routes whose exposure is specific and understood — the
  annotation and improvement routes, above;
- **`AFIELD_API_REQUIRE_SAME_SITE`**, an application-wide refusal of cross-site HTTP writes
  and command WebSocket browser upgrades, with `/api/import-offline-doc` exempted, **off by
  default**. **As of 2026-08-20 it refuses cross-site *reads* too — see "The read half" below.
  Until then it did not, which meant the mitigation chosen here did not cover the exposure
  measured three sections above.** Same shape as everything else
  here — safe default, documented hatch — except that "safe" points the other way: the
  default is the old behaviour, because turning a refusal on across 36 routes is how you
  find out at 4am which of them the human's own tooling was quietly using;
- **one loud startup line**, unconditionally, naming the exposure and the switch.

Verified live on port 9492 with the switch on, 2026-08-12: a cross-site
`POST /api/preferences` is refused **403** with a message naming the variable; the same
route same-origin is unaffected; the `OPTIONS` preflight for `/api/import-offline-doc`
still returns **200** with `Access-Control-Allow-Origin: *`; and `GET /api/instance-info`
with no `Origin` still returns **200**, which is the limitation, not an oversight.

An allow-list on `anyHost()` remains the better long-term answer for the browser HTTP half.
Neither it nor this switch touches the network half.

## The read half, closed 2026-08-20 — and why it was open for eight days

The experiment above measured that a foreign origin could **read** `/api/instance-info` and
`/api/welcome-changelog`, and the switch taken instead of narrowing `anyHost()` refused only
writes. So the document's own measurement and the document's own mitigation did not line up,
and nothing in the tree made that visible: `FieldApiWriteGuardTest.readsAndPreflightsArePassedThrough`
**asserted that cross-site reads passed**, which turned the gap into a written guarantee.

`nightly/2026-08-20/3-server-surface-threat-model` found it the only way it was going to be
found — by driving a real Field with the switch fully on rather than reading the code — and
counted the surface: **31 of 34 read routes had no guard of any kind**.

Why reads went missing is structural rather than careless, and worth stating because it will
happen again in the next area organised this way: every census here counts *mutating* routes
(`everyMutatingRouteAndItsGuardIsPinned` is in the name), the interceptor skipped `GET`/`HEAD`
by construction, and a route has to opt *in* to a guard. So **"unguarded" was the default for
reads, and there was nowhere that the decision "this read needs no guard" was recorded.**
`fieldApiSelfGuardedReadPrefixes` and `fieldApiReadNeedsSameSite` in `Server.kt` are where
that decision now lives.

A read is not a lesser exposure than a write. It is a different one — `/api/instance-info`
leaks the path of your work, `/api/welcome-changelog` your commit messages, `/api/source` and
`/api/workspace-search` source text — and `anyHost()` is what makes the *body* readable rather
than merely the route reachable.

### The three shapes still allowed, which are exemptions and not leftovers

Each has a test whose job is to go red if the guard eats it, because eating a feature is the
likeliest failure of a switch like this:

- **`Sec-Fetch-Mode: navigate`.** A cross-site GET navigation sends `Sec-Fetch-Site: cross-site`
  and **no `Origin`**, so `isSameSiteRequest` already calls it cross-site and would refuse it.
  What makes it safe is the destination, not the origin: the body lands in a Field-origin
  document that the initiating page cannot read. Refusing would 403 every link into Field —
  including the `Open ↗` links the docs pages are built around — and close nothing. Note this
  matches what "Why it is still there" already observed about the offline viewer: its other
  Field URLs are top-level navigations, which CORS does not govern either.
- **`Sec-Fetch-Mode: websocket`.** Upgrades are GETs, and `refuseCrossSiteFieldWebSocket`
  applies this same policy at the upgrade, where it can close with `1008` and a reason a client
  can read instead of a bare 403.
- **The routes that guard themselves** (`/api/docs-annotations`, `/api/docs-improvement`).
  `DOCS_ANNOTATIONS_ALLOW_CROSS_SITE` is a deliberate opt-in hatch that *permits* cross-site
  annotation reads. A blanket refusal here would have closed it while its own switch still
  claimed to hold it open — a guard reaching behind another guard's back.

`OPTIONS` still passes untouched, or the preflight would never reach the CORS plugin.

### Verified live on port 9605, 2026-08-20, same build both ways

| probe | switch **off** | switch **on** |
| --- | --- | --- |
| `GET /api/instance-info`, `Origin: https://evil.example`, `Sec-Fetch-Mode: cors` | **200**, `{"repoRoot":"/Users/marc/field4-worktrees/read-guard-posture",…}` | **403** "refusing cross-site **reads**" |
| `GET /api/welcome-changelog`, same headers | 200 | **403** |
| `GET /api/instance-info`, same-origin | 200 | 200 |
| `GET /api/instance-info`, `Sec-Fetch-Mode: navigate`, cross-site | 200 | **200** — the exemption |
| `GET /api/instance-info`, plain `curl`, no `Origin` | 200 | **200** — the limitation |
| `POST /api/instance-info`, cross-site | 403 | 403 "refusing cross-site **writes**" |

The counterfactual is the same binary with the environment variable unset, so the switch is
what closed it. Red-first on the unit side, control 0 of 10: restoring the `GET`/`HEAD` skip
turns 5 red; removing the `navigate`, `websocket` and self-guarded exemptions turns exactly 1
red each, and each named the test predicted for it.

### What this still does not do

The same two exposures the write half leaves standing, unchanged:

- **The network half.** `curl` from the LAN sends no `Origin`, `isSameSiteRequest` calls that
  same-site on purpose, and every read refused to a website remains open to any host on the
  network. `aClientWithNoOriginIsStillUnaffectedAndThatIsTheLimitation` asserts this out loud.
- **The default.** Still permissive, for the reason this document already gives about 36 routes
  at 4am. So on a default Field, the reads above are still readable by any page you have open;
  the switch is what closes them. An allow-list replacing `anyHost()` remains the better
  long-term answer for the browser half, and would close reads without an opt-in — the blocker
  recorded above (enumerating the offline viewer's origins, including `file://` → `Origin: null`)
  is unchanged.

## Where the tests are

Six files, 59 tests. Four of them start a real Ktor application; two read source.

- `src/jvmTest/kotlin/afield/server/DocsAnnotationRouteSecurityTest.kt` — **15 tests**,
  route-level, through the real CORS configuration. This file exists because of a measured
  gap: delete the guard *call* at the head of `post("/api/docs-annotations")` and the 19
  tests that existed before it **all still pass**, while 6 of the 12 route tests that
  existed then go red. Testing a predicate is not testing a route. The DNS-rebinding row
  was also watched red first: `Host == Origin` plus `Sec-Fetch-Site: same-origin` returned
  200 until the literal/localhost host allow-list ran before those signals.
- `src/jvmTest/kotlin/afield/server/FieldApiWriteGuardTest.kt` — **7 tests** over
  `AFIELD_API_REQUIRE_SAME_SITE`, including one whose entire job is to assert the
  *limitation*: a request with no `Origin` is not refused, so the switch does nothing about
  the network.
- `src/jvmTest/kotlin/afield/server/FieldApiReadGuardTest.kt` — **10 tests** over the read
  half, added 2026-08-20. It opens by asserting its own premise (that the guard refuses
  *anything*), so the six tests that assert a `200` cannot pass vacuously if the switch is
  ever unwired; it reproduces the measured leak including a check that the refusal does not
  itself echo the checkout path; and three of its tests exist to catch the guard eating one of
  the deliberate exemptions.
- `src/jvmTest/kotlin/afield/server/FieldWebSocketWriteGuardTest.kt` — **4 route-level
  tests** proving that the default remains compatible, the switch closes a foreign browser
  upgrade with a policy violation, Field's own browser still connects, and a native client
  without browser-origin headers remains compatible.
- `src/jvmTest/kotlin/afield/server/DocsAnnotationSecurityTest.kt` — **18 tests** over the
  guard functions as pure functions of a `DocsAccessPolicy` value, which is what makes
  reverting one and re-running a two-minute experiment.
- `src/jvmTest/kotlin/afield/server/FieldApiCorsExposureTest.kt` — **5 tests** pinning
  decisions rather than behaviour: the CORS block literally, the route→guard table, the
  fact that `X-Field-Docs-Improvement-Token` is not CORS-allow-listed, and that adding
  `PUT` to `allowMethod` widens the surface. The fifth inventories every WebSocket and
  distinguishes the two unguarded command channels from the notification-only channel.

**If you change a guard, revert it and watch the suite before trusting the green.** Every
count in those files was observed, not predicted.
