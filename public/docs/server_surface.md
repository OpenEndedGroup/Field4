# Field's server surface

Field has a **web server** inside it, and that server is how Field's own editor talks to
Field. When you open a document, the window you are looking at is a browser, and everything
you do in it, from typing in a box to dragging one to running code, travels to Field's JVM
over HTTP and a WebSocket.

That server listens on your machine. This page says what can reach it, and what stops a web page
you are visiting from driving your editor. You do not need it to use Field, but read it if you run
Field on a shared network: in a studio, at a university, or on conference wifi.

## In short

Field's server binds `0.0.0.0`, which means **any machine on your network can reach it**, and
it does not authenticate anything. It also tells every website you have open that they may
call it, because its CORS policy is `anyHost()`. Each of those has a feature depending on it.
An opt-in switch, `AFIELD_API_REQUIRE_SAME_SITE=1`, blocks other websites but not other machines.
On a network you do not trust, your firewall protects you; Field does not.

## What to do

<div class="field-data-table" markdown>

| if you are… | do this |
| --- | --- |
| on a network you do not fully trust | treat Field as an open door and rely on the network: a firewall on Field's port, or don't run it there |
| worried about websites you visit | set `AFIELD_API_REQUIRE_SAME_SITE=1`. It closes cross-site HTTP writes **and** cross-site WebSocket upgrades |
| running automation or the agent backdoor | those send no browser `Origin`, so the switch does not affect them, and neither does it stop such a request from another machine |
| adding a route or a `/ws` command to Field | add it to `dev_docs/server_surface_inventory.md`; the build checks the list against the source |

</div>

The rest of this page explains which defences exist, what each one stops, and what none of them
stop.

## Where a request came from, not who sent it

Three different questions are easy to confuse:

<div class="field-data-table" markdown>

| question | what answers it in Field | what it stops |
| --- | --- | --- |
| which **machine** is this from? | `isLoopbackDocsClient`, on the socket's remote address | stops: another device on the wifi |
| which **website** is this from? | `isSameSiteRequest`, on `Host`, `Sec-Fetch-Site` and `Origin` | stops: a page open in *your* browser |
| **who** is this? | nothing — there is no `install(Authentication)` in the tree | stops: nothing |

</div>

Field has no login, no token on most routes, and no per-request credential. Every defence below
is about where a request came from, which is why a loopback check on its own is weaker than it
sounds.

### Why "loopback only" is weak

A check that the request came from `127.0.0.1` sounds like it means "this came from me", but your
browser is on `127.0.0.1` too. So when a page on `evil.example` runs this:

```diffstatic {data-lang=javascript}
fetch('https://localhost:9092/api/docs-annotations', {method: 'POST', body: …})
```

the request that arrives at Field comes from your browser, from loopback, and a loopback
check waves it through. The page cannot *read* the reply without Field's permission, which is
what CORS is for, but a request the attacker sends for its side effect, a write or a
shutdown, does not need the reply at all.

A Chromium page served from `http://127.0.0.1:9493` is a foreign origin, and from there:

- `GET /api/instance-info` and `GET /api/welcome-changelog` are readable. The first returns the
  absolute path of your checkout, the absolute path of the workspace your documents live in, the
  moment the server started, and its process id
  (`Server.kt:3599#get("/api/instance-info") {`). The second returns your recent commit
  messages.
- `POST /api/shutdown-instance` stops the server.

So the defences that matter are the ones that ask which website a request came from.

### What `GET /api/instance-info` returns

This is the whole response from a running Field, with only the home directory rewritten to `~`:

```diffstatic {data-lang=json}
{
  "repoRoot": "~/field4-worktrees/2026-09-13-3-instance-info-exposure-docs",
  "startedAtEpochMs": 1789290604492,
  "pid": 47573,
  "workspace": "~/field4-worktrees/2026-09-13-3-instance-info-exposure-docs/build/field-automation-workspace"
}
```

`repoRoot`, `startedAtEpochMs` and `pid` say which checkout is running, when it started and which
process it is, so that a tool attaching to a Field already on a port can tell it from another
checkout's.

`workspace` (`Server.kt:3608#workspace = displayWorkspacePath(home),`) is the directory this
server resolves document names against, so a client attaching to a server it did not launch can
find that server's documents. This Field was started with a throwaway `--workspace`; an ordinary
install reports something like `~/Documents/AFieldWorkspace` (`./field.sh --print-workspace` tells
you yours).

A page on another origin gets that whole body: the same request with
`Origin: https://evil.example` comes back `200` with `Access-Control-Allow-Origin: *`, which tells
the browser to hand the response to the calling page. So a website you visit can read the path to
your documents, and `AFIELD_API_REQUIRE_SAME_SITE=1` does not stop it, because that switch only
inspects requests that change something.

## Defence 1 — CORS

Ktor's CORS plugin decides which websites may read Field's replies. This is Field's
configuration:

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
The offline document viewer needs that: it posts a document back to
`POST /api/import-offline-doc` from whatever origin its bundle was served from, and
`/api/import-offline-doc` is the one path explicitly exempted from the same-site guard below.

The only thing this configuration stops is a cross-origin `PUT`, which is not on the list and
never reaches a handler. That protection comes from the list, not from a guard: adding
`allowMethod(HttpMethod.Put)` for one endpoint would remove it from all of them.

??? aside "Pinned by"
    `FieldApiCorsExposureTest.theCorsConfigurationIsExactlyWhatWeThinkItIs`.

## Defence 2 — the read/write split

`AFIELD_API_REQUIRE_SAME_SITE=1` (or `-Dafield.api.requireSameSite=1`) turns on an
application-wide interceptor that refuses cross-site requests that change something, across
Field's whole API.

It is off by default, because turning it on can break routes your own tooling relies on. Field
prints a warning line at startup either way.

It inspects `POST`, `PUT` and `DELETE` only. A `GET` goes through untouched, because a WebSocket
upgrade is a `GET`, and so is every ordinary page load. Reads are still an exposure:
`GET /api/instance-info` returns the paths of your checkout and your workspace,
`GET /api/welcome-changelog` returns your commit messages, and `GET /api/source` and
`GET /api/workspace-search` return source text. Only the documentation routes under Defence 5
guard their reads; the same-site switch covers none of them.

Some of Field's routes, such as `/welcome` and `/lazy-value-fragment`, are declared with no HTTP
verb and answer all of them. The switch covers them like any other route, because it inspects the
request's method rather than the route's declaration.

A request with no `Origin` header at all is not refused: the switch blocks other websites, not
other machines.

??? aside "Pinned by"
    `FieldApiWriteGuardTest`, including a test that asserts the no-`Origin` limitation, and
    `ServerSurfaceLedgerTest`, which counts the read routes and the verbless ones. Each is listed
    with its guard in `dev_docs/server_surface_inventory.md`.

## Defence 3 — the DNS-rebinding check

Refusing any request whose `Origin` does not match its `Host` sounds enough: the browser sets
both from the same URL, and a page cannot forge either. It does not stop this attack:

1. You visit `field.attacker.example`. Its DNS record has a **one-second TTL** and initially
   resolves to the attacker's real server.
2. The page loads, and its script sits waiting.
3. The DNS record is changed to resolve to `127.0.0.1`.
4. The script fetches `https://field.attacker.example:9092/api/…`. Your browser re-resolves
   the name, gets `127.0.0.1`, and **connects to Field on your own machine**, while still
   believing it is talking to the same origin the page came from.

Now `Origin` is `https://field.attacker.example`, `Host` is `field.attacker.example`, and the
browser reports `Sec-Fetch-Site: same-origin`. They match, but the origin is not Field.

So Field checks first that a browser request's `Host` names `localhost` or a literal IP address.
It resolves no names, since the attacker controls the DNS. An attacker cannot get a browser to send
`Host: localhost` for a page served from their own domain, because the browser sets `Host` from the
URL.

Then, in this order:

- **`Sec-Fetch-Site` is preferred**, because the browser sets it and a page cannot: it is a
  forbidden header name, so no `fetch` option touches it. `same-origin` passes. `none` passes,
  because a typed URL or a bookmark has no initiator and is not a cross-site request at all.
  `cross-site`, `same-site` and anything unrecognised are refused.
- **`same-site` is refused too, not just `cross-site`.** `http://localhost:9493` is a different
  *origin* from `https://localhost:9092` but the same *site*, which is exactly the shape a
  page served from another port on your own machine has.
- For a client too old to send `Sec-Fetch-Site`, `Origin` is compared with the
  already-validated `Host`. `Origin: null`, a sandboxed iframe, has no authority and cannot
  match.
- **No `Origin` at all is allowed.** That is `curl`, a script, or Field's own native clients.
  Refusing it would break every non-browser client Field has.

So this guard stops web pages and nothing else. A request from another machine sends no `Origin`,
and passes.

??? aside "Pinned by"
    `DocsAnnotationSecurityTest` over the predicate, and `DocsAnnotationRouteSecurityTest` through the real
    routes, including the rebinding shape: `Host == Origin` plus `Sec-Fetch-Site: same-origin`.

## Defence 4 — the WebSocket upgrade guard

A WebSocket starts as an HTTP `GET`, so:

- **CORS does not govern a WebSocket handshake at all.** A page can open a WebSocket to any
  origin it likes; the browser will not preflight it and will not withhold the connection.
- **The same-site interceptor skips `GET`**, because reads and page loads have to go through.

Together those would let a foreign page open Field's command channel even with
`AFIELD_API_REQUIRE_SAME_SITE=1` on. So the command channels apply the same origin check inside
the upgrade, and close the connection with a WebSocket policy-violation code:

```diffstatic {data-lang=kotlin}
webSocket("/ws") {
    if (refuseCrossSiteFieldWebSocket("/ws")) return@webSocket
    …
}
```

Field's WebSocket channels:

| channel | role | guard |
| --- | --- | --- |
| `/ws` | the main editor command channel | same-site, when the switch is on |
| `/controllers/ws` | remote-control commands | same-site, when the switch is on |
| `/welcome-events` | server-to-client refresh notifications; incoming data ignored | none needed |

The handshake itself succeeds. The guard runs in the handler, so the client gets
`HTTP/1.1 101 Switching Protocols` and then a close frame with code `1008` before any message is
processed. A foreign page's `onopen` may fire, followed immediately by `onclose`, but no command
reaches a handler.

??? aside "Pinned by"
    `FieldWebSocketWriteGuardTest`, route-level tests: the default still allows a cross-site upgrade,
    the switch closes one with a policy violation, Field's own browser still connects, and a native client
    sending no browser-origin headers still connects.

## Defence 5 — route isolation for the documentation-annotation feature

Two groups of routes carry guards of their own rather than relying on the global switch. They are the annotation notebook
(`GET`/`POST`/`PUT`/`DELETE /api/docs-annotations…`) and the documentation revision pass
(`/api/docs-improvement/pass…` and `/docs-pass-site/…`), which runs coding agents with write
access to your checkout, commits, and can fast-forward `main`.

Both refuse a request that is not from loopback or not same-site. The annotation routes have
two opt-in hatches, `AFIELD_DOCS_ANNOTATIONS_ALLOW_REMOTE` and
`AFIELD_DOCS_ANNOTATIONS_ALLOW_CROSS_SITE`, for taking notes from a tablet or another site. The
revision pass ignores both, so neither can let another device or another website start agents.

The same file's documentation *mirror* is `GET /field-docs/{...}`, which fetches
`openendedgroup.com` and serves it back from Field's own origin so an injected annotation
panel can talk to Field without CORS. The cost is that every script in the mirrored page is
same-origin with Field, and could call Field's other, unguarded `/api/…` routes. Restricting the
mirror to loopback keeps other machines from using it that way.

## What stays open

None of the defences above closes these two.

**Another host on your network.** The HTTPS connector binds `0.0.0.0` so that the visionOS
shell and other devices can reach Field. Ktor's CORS plugin does not run for a request with no
`Origin` header, and the same-site check allows one. So `curl` from any machine on the same wifi
reaches almost every route. Closing this needs authentication or a narrower bind address, and a
narrower bind breaks the headset. Your network is what protects you here.

**The `/ws` command surface, by default.** The code with the most reach is not behind an HTTP route
at all. The file proxy, the H2 database explorer, the box-execution commands and the agent
backdoor are commands sent over `/ws`. A second set of "raw" channels on the same socket receives
whole frames: the H2 explorer, the file browser, the log stream, the spreadsheet, the three.js and
Metal viewers, and the RemoteJS bridge. `dev_docs/server_surface_inventory.md` lists every one.

Among them, `launch-all`, `launch-batch` and `launch-fragment` run the code in your document, in
Field's JVM, with Field's privileges. Nothing sandboxes a `/ws` frame from that, and the guard in
front of it is off by default. If you change one setting after reading this page, set
`AFIELD_API_REQUIRE_SAME_SITE=1`.

## What the switch refuses, request by request

With `AFIELD_API_REQUIRE_SAME_SITE=1` on, this is what a real Field answers over its TLS socket.
The write probes target a path that does not exist, so `403` means the guard refused and `404`
means the request got past the guard and reached routing.

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

The rebinding guard works on the WebSocket path as well as the HTTP one. With the switch on, a
foreign origin can still read the checkout's path, as the `GET /api/instance-info` row shows.

## The route inventory

`dev_docs/server_surface_inventory.md` lists every read route with its guard, every route that
answers all methods, every static directory mount, every `/ws` command with the file it lives in,
and every raw channel. The build checks it against the source in both directions.
