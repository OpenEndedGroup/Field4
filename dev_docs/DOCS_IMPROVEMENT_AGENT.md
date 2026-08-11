# The documentation annotation layer, and the coding agent behind it

Two features live in `src/jvmMain/kotlin/afield/server/docsAnnotations.kt` and
`src/commonMain/resources/docsAnnotations.js`.

The first is benign and pleasant: `/field-docs/…` mirrors the published documentation
through Field's own origin and injects a note-taking panel into it. Select a sentence,
press `Ctrl+Shift+A`, type a note, and it is stored in `~/.afield/docs-annotations.json`
with an anchor good enough to find the sentence again after the page is rebuilt.

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
| `AFIELD_DOCS_ANNOTATIONS_ALLOW_REMOTE` | unset | lets **annotation** requests come from other devices. Logged as a warning at startup. Never affects the spawn. |

Four independent conditions guard the spawn, each separately sufficient to stop the
attack above:

- **it is off** unless enabled;
- **the token** must match;
- **the peer must be loopback**, decided on `call.request.local.remoteAddress`;
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

- **The store is one global file.** `~/.afield/docs-annotations.json` is shared by every
  Field on the machine — the main tree, every worktree, every window. Notes now carry
  `recordedInCheckout` so you can at least tell which one wrote them, but two Fields
  writing at once can still lose a note: the lock around the file is per-JVM, and the
  improvement agent is explicitly told to edit that same file while it runs. Making the
  store per-checkout would fix it and would orphan every note already written, so it is
  left as a decision rather than taken.
- **Prompt injection is mitigated, not removed.** With the endpoint gated, an attacker
  can no longer both plant the text and pull the trigger. A note you did not write can
  still end up in a run you did launch — the notes are yours, so this is mostly a
  statement about not annotating pages you do not trust.
- **The token is visible to your own processes**, in the environment and in the log. See
  the threat model: that is the boundary, deliberately.
- **`0.0.0.0` is still `0.0.0.0`.** Narrowing it would break the visionOS shell and
  remote browser clients, so the binding stayed and the routes grew guards. If a future
  Field wants a loopback-only mode, that is a server-wide switch and a different change.
- **`CORS { anyHost() }` applies to Field's whole API**, not just to this corner of it.
  Every one of the ~50 unauthenticated `/api/…` routes is therefore readable and (for
  GET/POST/DELETE with a simple content type) writable from any website you visit while
  Field is running. This branch's token defends *one* route from that. It is the largest
  thing found while working here that is out of scope for a docs-endpoint branch, and it
  is worth a look of its own.
- **The editor's docs iframe** (`Cmd+/`, `bootstrapMonaco.js`, default `/field-docs/`)
  follows the mirror's loopback rule. Opening Field at `localhost` — the normal case, and
  what `field.sh` prints — is unaffected. Opening it at the machine's LAN address means
  the iframe now needs `AFIELD_DOCS_ANNOTATIONS_ALLOW_REMOTE=1`.

## Where the tests are

`src/jvmTest/kotlin/afield/server/DocsAnnotationSecurityTest.kt`. Fifteen tests; ten of
them were watched to fail against the pre-change behaviour before being trusted. The
guards are pure functions over a `DocsAccessPolicy` value precisely so that reverting one
and re-running the suite is a two-minute experiment. If you change a guard, do that again
rather than trusting the green.
