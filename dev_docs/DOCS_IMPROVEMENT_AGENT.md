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

Two details in the loopback check are load-bearing and easy to undo by accident.

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

The cost is real and worth stating: if you browse the docs on a tablet, or at your Mac's
LAN address rather than `localhost`, the panel will show a refusal instead of your notes.
The refusal says what to do. `AFIELD_DOCS_ANNOTATIONS_ALLOW_REMOTE=1` restores the old
behaviour for annotations only.

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

## Where the tests are

`src/jvmTest/kotlin/afield/server/DocsAnnotationSecurityTest.kt`. Fifteen tests; ten of
them were watched to fail against the pre-change behaviour before being trusted. The
guards are pure functions over a `DocsAccessPolicy` value precisely so that reverting one
and re-running the suite is a two-minute experiment. If you change a guard, do that again
rather than trusting the green.
