# AField / Field·4 — Repo Guide for Agents

This repository builds **Field·4**, a Kotlin-based live-coding environment for graphics + exploratory data visualization:
- **Backend (JVM/Kotlin)**: a Ktor HTTPS + WebSocket server (`afield.Server`) that manages documents, runs user code, and (optionally) drives Vulkan/OpenXR rendering via `afield.four`.
- **Frontend (Kotlin/JS)**: a browser UI (canvas + Monaco editor) that talks to the server over a small JSON RPC protocol defined in `src/commonMain`.
- **Persistence (.edn)**: documents live on disk as `metadata.edn` plus optional sidecar files for large text properties (Kotlin, GLSL, JS, YAML).

---

## Quickstart (Dev)

- Run Field (builds JS + runs JVM server): `./field.sh` (wraps `./gradlew runJvm`)
- Open in browser: `https://localhost:9092/index.html?file=firstDocument`
- Change workspace directory (documents root):
  - CLI arg parsed by `option(...)`: `--workspace <path>`
  - Example: `./gradlew runJvm --args="--workspace /tmp/AFieldWorkspace"`
- Ports and HTTPS:
  - `PORT` env var overrides default `9092`
  - Server uses HTTPS by default (self-signed dev cert)
  - For local automation, Field also exposes a loopback-only HTTP mirror at `AFIELD_HTTP_PORT` if set, otherwise `PORT + 10000` when that fits in the valid port range. Example: `PORT=9477` gives `http://localhost:19477/...`.
  - Set `AFIELD_HTTP_PORT=off` to disable the HTTP mirror, or set `AFIELD_HTTP_PORT=<port>` to choose an explicit loopback HTTP port.
  - Keystore env vars: `AFIELD_KEYSTORE_PATH`, `AFIELD_KEYSTORE_PASS`, `AFIELD_KEYSTORE_ALIAS`
  - Generate dev keystore: `./gradlew generateLocalKeystore` (writes `config/field4-local.p12`)

### Python / uv recipes for agents

Use `uv` for agent-facing Python tooling so MkDocs and the backdoor share the same dependency environment. This matters because `public/docs_hooks.py` runs `tools/field_backdoor.py` with the same Python interpreter that is running MkDocs.

- Backdoor status:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 --command status
```

- Full Zensical docs build with all Field screenshots/videos regenerated:

```bash
FIELD_DOCS_RUNNER_MODE=always FIELD_DOCS_RUNNER_PORT=9477 \
  uv run --project . --group docs python tools/build_zensical_docs.py --clean
```

- Fast docs build using existing captures:

```bash
FIELD_DOCS_RUNNER_MODE=never \
  uv run --project . --group docs python tools/build_zensical_docs.py --clean
```

- Iterating docs locally:

```bash
cd public && FIELD_DOCS_RUNNER_MODE=incremental FIELD_DOCS_RUNNER_PORT=9477 \
  uv run --project .. --group docs mkdocs serve
```

Docs/test intent matrix:

The consolidated testing guide is `dev_docs/testing/TESTING_STRATEGY.md`; use
the matrix below as the quick command crib.

| Situation | Command | Intent |
| --- | --- | --- |
| Backdoor sanity check | `uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 --command status` | Verify the agent can start or attach to an isolated Field. |
| Smoke catalog | `uv run --project . --group backdoor python tools/run_field_tests.py --refresh-catalog` | Exercise annotated Field examples through the backdoor. |
| Browser UI regressions | `uv run --project . --group backdoor python tools/run_ui_regressions.py --port 9490 --timeout 45` | Run Playwright-backed UI workflows and retain screenshots under `build/ui-regressions/artifacts/`. |
| Docs HTML only | `FIELD_DOCS_RUNNER_MODE=never uv run --project . --group docs python tools/build_zensical_docs.py --clean` | Rebuild Zensical docs without starting Field or recapturing assets. |
| Docs author loop | `FIELD_DOCS_RUNNER_MODE=incremental FIELD_DOCS_RUNNER_PORT=9477 uv run --project . --group docs python tools/build_zensical_docs.py` | Reuse valid captures and run only changed or missing ones. |
| Runner/API change | `FIELD_DOCS_RUNNER_MODE=strict FIELD_DOCS_RUNNER_PORT=9477 uv run --project . --group docs python tools/build_zensical_docs.py` | Invalidate captures when runner code or transcript behavior changes. |
| Full recapture | `FIELD_DOCS_RUNNER_MODE=always FIELD_DOCS_RUNNER_PORT=9477 uv run --project . --group docs python tools/build_zensical_docs.py --clean` | Regenerate every screenshot and video through Field. |
| Offline asset ownership | `uv run python tools/check_offline_assets.py` | Verify canonical Monaco/font/offline assets exist and generated copies are not tracked. |

### Gradle hygiene for agents

- On Windows, agents should use the checked-in Gradle wrapper directly for compile checks:

```powershell
.\gradlew compileKotlinJvm
```

- Use the same wrapper form for other Gradle tasks, replacing `compileKotlinJvm` with the task name, for example `.\gradlew runJvm`.
- Do not preemptively run `killall -9 java` before Gradle interactions. With the Gradle 9 wrapper, try the requested Gradle task normally first and reserve Java cleanup for confirmed hangs, stale Field processes, or lock failures.
- On Unix-like systems, use `./gradlew compileKotlinJvm` unless a task-specific note says otherwise.
- Known local failure mode: if `./gradlew compileKotlinJvm` prints exactly this startup sequence and then sits at high CPU with no further output, treat it as a Gradle daemon hang, not a slow compile:

```text
Starting a Gradle Daemon, 10 busy Daemons could not be reused, use --status for details

> Configure project :
Mac OS X, aarch64
Mac OS X
```

  In this state, do not keep waiting, do not retry `./gradlew --status`, and do not start more Gradle tasks. Run `killall -9 java`, then report the compile as unverified. Prefer a targeted verification path such as `uv run --project . --group backdoor python tools/field_backdoor.py --json --port <custom-port> --command 'run-jvm-main ...'` when it fits the change.
- If Gradle appears hung or reports a lock error, run `killall -9 java` before retrying Gradle.
- Use an isolated `GRADLE_USER_HOME` / `--project-cache-dir` only as a fallback when there is a confirmed cache-lock conflict that cannot be cleared without disrupting the user's running work. The isolated path is expected to be much slower because it loses the warm project caches.

## Field Backdoor (Preferred for Agents)

- When you need to verify Field behavior by actually running code, prefer `uv run --project . --group backdoor python tools/field_backdoor.py` over driving the browser manually.
- When the thing you want to verify is a JVM entrypoint or smoke-style example, prefer the backdoor's `run-jvm-main` command over ad hoc `gradlew` `JavaExec`, temporary init scripts, or custom classpath launchers. Use transient scratch boxes for snippet-level checks and screenshots.
- The backdoor attaches to an existing Field at `https://localhost:9092` or starts Field itself using the Gradle wrapper command above, opens a scratch document by default, and lets you create boxes, set code/shaders, run or launch them, stop them, and capture outputs/errors as JSON.
- For agent automation, do not rely on the default port. Pass a custom high port such as `--port 9477` so the backdoor starts or reuses an isolated agent-owned Field instead of silently attaching to a stale dev server on `9092`.
- Reuse the same custom port for one verification session, and pick a new custom port when you want a guaranteed fresh Field JVM. This avoids burning time and tokens debugging old code loaded in some unrelated existing server.
- When using an external browser or Playwright connector directly, prefer Field's loopback HTTP mirror instead of the self-signed HTTPS URL. For a backdoor session on `--port 9477`, navigate external tools to `http://localhost:19477/index.html?file=<document>`. The backdoor's own HTTP/WebSocket client and server-owned Playwright already handle the self-signed HTTPS certificate.
- Use `--json` for agent automation. The result object includes `ok`, `data`, `events`, and `timedOut`.
- Use scratch sessions for disposable experiments. Use `--document <name>` when you need to attach to a real saved document.
- Every run writes a unique log file under `.field-backdoor/logs/`. `status` reports both the session log and the managed server log path.
- Session logs are debugging artifacts, not durable state. They now store truncated previews of very large websocket payloads, and it is safe to clear `.field-backdoor/logs/` if runaway visualizer output fills the disk during automation.
- `run-jvm-main` omits the full launched JVM command/classpath from JSON output by default to keep agent context small. Add `--include-command` only when debugging launch/classpath construction.
- For semantic document automation, prefer `OperationTranscript` macro YAML over one-off command chains. Reusable agent examples live in `dev_docs/agents/transcripts/`; write them into the current document with `macro write <name> --file <path>`, then run them with `macro run <name>`.
- For `afield.four` graphics automation, treat renderer access as graph topology. Run `dev_docs/agents/transcripts/graphics_renderer_launch.yaml`, wait with `wait-native-renderer agent_graphics_context --frames 3`, create the example box, connect `agent_graphics_context -> example_box`, then run the example. If code starts coroutine work with `start(...)`, resolve `val renderer: Renderer = fromContext()` before `start(...)` and pass it into async helpers explicitly.

Minimum hello world:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 \
  --command 'new-box --name Hello' \
  --command 'run @last --value "println(\"hello world\")"'
```

Recommended agent workflow:

1. Pick a custom high port and start with `uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 --command status` to see whether it started Field, whether `managedServer` is true, and where the logs are.
2. If you unexpectedly connect to an existing server or suspect stale code, do not keep retrying on the same default port. Pick a new custom port and start a fresh managed Field there.
3. For quick execution checks, stay in scratch mode and use `new-box`, `connect`, `disconnect`, `set`, `run`, `launch`, `stop`, and `output`.
4. For larger snippets, prefer `--file -` with a heredoc or pipe. Use `--file <path>` when the code already lives in a file.
5. Use `--with-playwright` when you know a session will need DOM inspection, screenshots, or output-area capture. This opens or reuses Field's server-owned Playwright browser immediately after the backdoor attaches to its current session, and again after any later `open ...` command.
6. Use `playwright-open` manually when you want to defer browser startup until later in the session.
7. Use `editor-open <box> --property code` before `run` or `launch` when you need inline Monaco visualizers like `histogram`, `history`, `stats`, or `pianoRoll` to exist in the editor before a later `screenshot-editor`.
8. Use `run-jvm-main <entrypoint> --arg ...` for annotated/standalone JVM smoke checks when you do not need a scratch box UI. Its JSON result is intentionally compact; add `--include-command` only when you need the full Java invocation.
9. Read the `events` array in JSON output to inspect stdout, `display(...)` output, runtime errors, and running-state transitions.

By default this Playwright browser is headless, so screenshot/DOM tests do not steal desktop focus. Set `AFIELD_PLAYWRIGHT_VISIBLE=true` or JVM property `-Dafield.playwright.visible=true` only when you explicitly want to watch the browser while debugging.

Stdin example:

```bash
cat <<'EOF' | uv run --project . --group backdoor python tools/field_backdoor.py --json \
  --command 'new-box --name Hello' \
  --command 'run @last --file -'
println("hello from stdin")
EOF
```

Connection example:

```text
connect <parent-uid> <child-uid> [more-child-uids...]
```

This matches the frontend `g` action: the first uid is the parent, and the remaining uid(s) become children of that parent.

Playwright example:

```bash
cat <<'EOF' | uv run --project . --group backdoor python tools/field_backdoor.py --json \
  --with-playwright \
  --command 'new-box --name Visual' \
  --command 'run @last --file -'
display("main", "<div id=\"hello\">hello from DOM</div>")
EOF
```

This opens or reuses the server-owned Playwright browser on the current backdoor session, including scratch sessions, before the box runs. It is headless by default, so it should not steal focus while tests run. Use `--with-playwright` whenever a later screenshot or DOM assertion depends on output areas created by that run.
Screenshot commands now wait for the Playwright page to be connected and for the target box/output DOM to appear, but that only helps with normal render races. If the browser was attached after a run, output areas that never existed in that page still cannot be captured retroactively.
The first call on a machine can take longer because Playwright may download browser binaries. If you truly want to attach the browser later, use the explicit `playwright-open` command instead.

Screenshot examples:

```bash
cat <<'EOF' | uv run --project . --group backdoor python tools/field_backdoor.py --json \
  --with-playwright \
  --command 'new-box --name Visual' \
  --command 'run @last --file -' \
  --command 'screenshot-window /tmp/visual-with-editor.png --uid @last --with-editor --open-outputs' \
  --command 'screenshot-window /tmp/visual-without-editor.png --uid @last --open-outputs' \
  --command 'screenshot-editor @last /tmp/visual-editor.png --padding 10' \
  --command 'screenshot-box @last /tmp/visual-box.png --padding 16'
display("main", "<div style=\"padding:24px;background:#ffe08a\">hello screenshot</div>")
EOF
```

Use:
- `screenshot-window <path> --uid <box> --with-editor --open-outputs` for a whole-window capture with that box selected and Monaco visible.
- `screenshot-window <path> --uid <box> --open-outputs` for a whole-window capture of the same state without Monaco.
- `editor-open <box> --property code` before `run`/`launch`, then `screenshot-editor <box> <path> --padding 10`, for inline histogram / history / piano-roll style visualizers.
- `screenshot-box <box> <path> --padding 16` for a clipped image of just that box, with nested/output disclosures opened and a little padding added for documentation or visual diffing. `--open-outputs` is accepted for symmetry, but box screenshots already open outputs internally.

Screenshot and DOM-probe troubleshooting:
- Attach Playwright with `--with-playwright` before the `run` or `launch` that creates output DOM. Attaching after a run cannot recreate output DOM that never existed in that browser page.
- Use one fresh scratch session or fresh custom port per final screenshot/video asset unless the example intentionally needs multiple boxes visible. Overlapping default-position boxes can ghost into later captures or distort crop calculations.
- For `page-eval`, keep inline expressions tiny. Put anything with spaces, quotes, braces, selectors, or multiple statements in a file and call `page-eval --file <path>`. Use `--body` only with an explicit `return`; otherwise the result is `null`.
- Inspect the actual PNG/MP4 before deciding a capture is good. JSON `clip` values are CSS pixels and can be half the generated image dimensions on high-DPI browser contexts.
- If an iframe output is blank or tiny, probe the parent page as well as the iframe document. Check the iframe rect, `.disclosure-content` rect/style, and `data-afield-intrinsic-width` / `data-afield-intrinsic-height`; stale intrinsic sizes can make a full iframe document render into a tiny disclosure.

Video recording examples:

```bash
cat <<'EOF' | uv run --project . --group backdoor python tools/field_backdoor.py --json \
  --with-playwright \
  --command 'new-box --name AnimBox' \
  --command 'launch @last --file -' \
  --command 'sleep 1' \
  --command 'record-window /tmp/anim-window.mp4 --uid @last --open-outputs --duration 5 --fps 30' \
  --command 'record-box @last /tmp/anim-box.mp4 --duration 3 --fps 30' \
  --command 'stop @last'
var t = 0.0
loop {
    t += 0.016
    display("main", "<div style=\"padding:24px;background:hsl(${(t*60)%360},70%,60%)\">frame $t</div>")
}
EOF
```

Use:
- `record-window <path.mp4> --uid <box> --open-outputs --duration 5` for a 5-second H.264 MP4 of the whole window.
- `record-window <path.mp4> --uid <box> --with-editor --duration 5` same but with Monaco editor visible.
- `record-editor <box> <path.mp4> --duration 5 --padding 10` for editor-only video clips.
- `record-box <box> <path.mp4> --duration 3 --padding 16` for a clipped video of just that box and its outputs.
- All record commands accept `--duration <seconds>` (default 5) and `--fps <rate>` (default 30).
- The output is an H.264/MP4 file with `faststart` for web-ready playback.

Detailed reference:
- `dev_docs/agents/FIELD_BACKDOOR.md`
- Medium-term transcript analysis loop:
  `dev_docs/agents/TRANSCRIPT_SELF_IMPROVEMENT_LOOPS.md`

Phase-1 smoke test runner:

```bash
uv run --project . --group backdoor python tools/run_field_tests.py --refresh-catalog
```

This regenerates `build/field-tests/catalog.json`, then runs each discovered `FIELD_TEST` entry one-by-one through `uv run --project . --group backdoor python tools/field_backdoor.py --json`.

Browser UI regression runner:

```bash
uv run --project . --group backdoor python tools/run_ui_regressions.py --port 9490 --timeout 45
```

This runs browser-level regressions through `tools/field_backdoor.py` and the server-owned Playwright browser. It prints `RUN`, heartbeat, `PASS`, and `FAIL` lines by default; use `--heartbeat-interval <seconds>` to tune long-run telemetry and `--json` when machine-readable stdout matters. Screenshots are retained under `build/ui-regressions/artifacts/<test-id>/`, with `latest-pass.png`, `latest-failure.png`, and the most recent 5 passing screenshots in `passes/`.

Target one regression while iterating:

```bash
uv run --project . --group backdoor python tools/run_ui_regressions.py \
  --id macro-transcript-panel --port 9680 --timeout 45
```

Current testing layers and commands are summarized in `dev_docs/testing/TESTING_STRATEGY.md`. Browser UI coverage details are tracked in `dev_docs/testing/UI_REGRESSION_ROADMAP.md`: command palette, editor sync, insert stack mark, output areas, `/controllers` lines/control widgets, screenshot selection, history panel, macro/transcript panel, inline visualizers, and focus/keyboard contracts.

## Autonomous Docs/Test Loop

- Agents are expected to make code changes, API changes, documentation fixes, and test-runner fixes autonomously when the docs/test pipeline exposes a real problem.
- The default repair loop is: run `uv run --project . --group backdoor python tools/run_field_tests.py --refresh-catalog`, run `cd public && FIELD_DOCS_RUNNER_MODE=always FIELD_DOCS_RUNNER_PORT=9477 uv run --project .. --group docs mkdocs build` (or `uv run --project .. --group docs mkdocs serve` while iterating), inspect the first concrete failing example, fix it in code or docs, and rerun until clean.
- Use `FIELD_DOCS_RUNNER_MODE=incremental` for fast local iteration against an already-clean cache.
- Use `FIELD_DOCS_RUNNER_MODE=strict` when runner logic changes should invalidate cached captures through `public/docs_hooks.py` digest changes.
- Use `FIELD_DOCS_RUNNER_MODE=always` when you need every screenshot/video to be regenerated regardless of cache state.
- Use `FIELD_DOCS_RUNNER_MODE=never` when you want MkDocs to skip all capture execution entirely and just use whatever screenshot/video files already exist on disk.
- For graphics-context captures, the docs runner now takes ownership of `FIELD_DOCS_RUNNER_PORT`: if a stale Field is already serving that port, the runner asks it to shut down and starts a fresh runner-owned Field before launching the renderer transcript. Use a dedicated high port for docs runs.
- When docs screenshots show the red Field error marker or empty/incorrect output, treat that as a real failure: capture the compiler/runtime error from the backdoor/test runner, fix the snippet or runtime, and rerun the docs page.
- If a backdoor or smoke-suite run times out and later probes stop reaching `is-running`, restart the managed Field JVM before trusting subsequent failures; a wedged snippet can poison later manual debugging runs.
- Prefer fixing the underlying example or capture behavior rather than hand-editing generated assets.

---

## Repo Layout (Top Level)

- `build.gradle.kts`, `settings.gradle.kts`: Kotlin Multiplatform build (JVM + JS), LWJGL/Vulkan deps, webpack bundling.
- `src/`: source for the JVM + JS application (KMP-style `commonMain`/`jvmMain`/`jsMain`).
- `public/`: built/static assets for distribution + docs (includes `public/docs/` and offline viewer assets).
- `metadata_store/`: example documents/templates (`metadata.edn`) used by the offline viewer and the Python metadata server.
- `templates/`: additional template content and example `.edn` documents.
- `server/`: optional Python services (metadata server + guide chat server) used by some features.
- `dev_docs/`: internal design notes; newer notes are grouped into topical subfolders like `agents/`, `animation/`, `workspace/`, and `physics/`.
- `field-user-dir/`: local user workspace artifacts (gitignored in many setups; treat as mutable user data).

---

## Kotlin Multiplatform Source Sets

### `src/commonMain` (Shared RPC + message dispatch)

- `src/commonMain/kotlin/Messages.kt`: tiny message bus (`handlers`, `sendWithReply`, `handle`, `expect fun _send`).
- `src/commonMain/kotlin/rpc.kt`: RPC payload types (`@Serializable` data classes) shared by JVM + JS.

Rule of thumb: if a type crosses the wire, define it here.

### `src/jvmMain` (Backend / runtime / Vulkan)

Core entry points:
- `src/jvmMain/kotlin/afield/Server.kt`: Ktor server, WebSocket endpoint (`/ws`), session/document state, message handlers.
- `src/jvmMain/kotlin/afield/Boxes.kt`: `Box` model + property conventions (core “document graph”).
- `src/jvmMain/kotlin/afield/execution.kt`, `src/jvmMain/kotlin/afield/KotlinInterface.kt`: compile/run live Kotlin code, route output back to UI.

Key subsystems:
- **Persistence**: `src/jvmMain/kotlin/afield/saving.kt`, `src/jvmMain/kotlin/afield/edn.kt`, `src/jvmMain/kotlin/afield/FileWatchManager.kt`
- **History**: `src/jvmMain/kotlin/afield/history.kt` (+ UI in `src/commonMain/resources/history.html`)
- **Stdlib for live coding**: `src/jvmMain/kotlin/afield/stdlib/` (user-facing helpers, shader glue, math, controls)
- **Vulkan/VR**: `src/jvmMain/kotlin/afield/four/` (LWJGL Vulkan + optional OpenXR)
- **Optional extra runtimes**: `src/jvmMain/kotlin/afield/python/`, `src/jvmMain/kotlin/afield/clojure/`, `src/jvmMain/kotlin/afield/remotejs/`
- **Server-side features** (routing/browsing/templates/chat): `src/jvmMain/kotlin/afield/server/`
- **Grouped support files**: loose JVM helpers now sit in shallow folders such as `src/jvmMain/kotlin/afield/{docs,internal,math,ui,visualization,workspace}/` while staying in the `afield` package.

### `src/jsMain` (Frontend UI)

Core entry points:
- `src/jsMain/kotlin/afield/Main.kt`: main UI (canvas, selection, editor integration, commands).
- `src/jsMain/kotlin/afield/Message.kt`: WebSocket client (`ws(s)://<host>/ws`) + JSON decode + dispatch to `handle(...)`.
- `src/jsMain/kotlin/afield/Messages_helpers.kt`: `@JsExport` wrappers so JS can call `send(...)`.

Static assets loaded by `src/commonMain/resources/index.html`:
- `src/jsMain/resources/monaco/`: Monaco Editor distribution.
- `src/jsMain/resources/*.js|*.css|*.woff2`: fonts, UI helpers, plotly, interact.js, etc.

### Chat UI

- There is no separate `src/jsChatMain` bundle in this checkout. Guide/shared chat UI is mounted from the main frontend and proxied through `src/jvmMain/kotlin/afield/server/chat.kt` when the optional Flask chat server is running.

---

## Runtime Architecture (Backend ↔ Frontend)

### Transport: WebSocket + JSON

- Messages are `kotlinx.serialization` JSON for the envelope `afield.Message` (in `src/commonMain/kotlin/rpc.kt`).
- The server broadcasts by calling `_send(...)` (actual implementation in `src/jvmMain/kotlin/afield/Server.kt`).
- The browser sends by calling `_send(...)` (actual implementation in `src/jsMain/kotlin/afield/Message.kt`).

### Message dispatch

- `handle(message)` consults `handlers[message.name]` (defined in `src/commonMain/kotlin/Messages.kt`).
- Many server behaviors are installed by populating `handlers[...]` in `afield.Server.server()`.
- Reply pattern:
  - `sendWithReply(message, callbackName, handler)` sets `replyTo`
  - Receiver replies with `Message(callbackName)` (often using `message.toName(...)` or constructing explicitly)

When adding a new RPC:
1. Define payload types in `src/commonMain/kotlin/rpc.kt` (`@Serializable`).
2. Add a server handler in `src/jvmMain/kotlin/afield/Server.kt` (or in `src/jvmMain/kotlin/afield/server/*`).
3. Add a frontend sender/receiver in `src/jsMain/kotlin/afield/*` (or JS resources if it’s not in Kotlin/JS).

### Kotlin/JS ↔ plain JS interop

Kotlin/JS `@JsExport` functions from `src/jsMain/kotlin/afield/*` are emitted as exports of the generated `afield.mjs` module. They are **not** safe to assume as bare globals inside `type="module"` scripts in `src/commonMain/resources`.

When a resource JS file is loaded as an ES module, import Kotlin/JS helpers explicitly:

```js
import { send, sendWithReply } from "./afield.mjs";
```

Good examples: `src/commonMain/resources/bootstrapMonaco.js`, `embeddedEditors.js`, `outputareas2.js`, and `outputs.js`.

Do not write module code that calls `send(...)`, `sendWithReply(...)`, `monacoHandleOutput(...)`, etc. as unqualified identifiers unless that file imports them from `./afield.mjs`. Non-module legacy scripts may still rely on globals exposed through `window`, but new module scripts should use imports.

### Runloop vs Audio Loop

Field has more than one “main loop”, and bugs get hard fast when these are conflated:

- `afield.Runloop` (`src/jvmMain/kotlin/afield/Runloop.kt`) is the JVM-side coordination loop started from `Server.kt`.
- `FieldAudioEngine` (`src/jvmMain/kotlin/afield/four/synth/FieldAudioEngine.kt`) owns a separate daemon audio thread and mixes fixed-size audio buffers there.
- The Vulkan renderer also installs `Runloop.preRun` hooks for graphics-side work, but that is still Runloop work, not audio work.

What the Runloop is for:

- Advancing Field’s coarse frame counter (`Runloop.frame`).
- Executing `preRun`, `main.frame()`, and `postRun` hooks once per Field tick.
- Main-thread coordination for UI, box/runtime bookkeeping, websocket-adjacent work, and graphics setup that must happen in step with the app frame.
- Low-rate control updates such as graph/query bookkeeping or scheduling that can tolerate frame-rate timing.

What the Runloop is not for:

- It is not the audio callback.
- It is not sample-accurate.
- It must not become the thing that advances oscillator phase, filter state, delay lines, envelopes, or other stateful DSP internals just because some UI/helper code wants a “current value”.

What the audio loop is for:

- Calling `Signal.render(...)` / `generate(...)` on the real synth graph with the audio engine’s `SynthContext`.
- Owning DSP state progression for oscillators, filters, phase accumulators, feedback paths, and modulation signals.
- Producing the actual mixed PCM buffers that OpenAL queues.

Practical rule that would have caught the recent bug earlier:

- Treat a synth `Signal` graph as audio-thread-owned unless the specific code is explicitly written for multi-context use.
- Do not sample the same stateful synth graph from `Runloop`, `GraphBindingRuntime`, docs/preview helpers, or ad hoc inspectors just to obtain a display/control value.
- If UI/graph code needs to show a current synth value, prefer publishing a snapshot from the real audio render path rather than re-rendering the graph on the Runloop.

Why this matters:

- `Signal` objects often cache per `SynthContext` and per cycle, but many nodes also carry mutable DSP state beyond that cache.
- Rendering the same signal graph from two contexts can still corrupt behavior if node internals are stateful, even when cache keys are correct.
- Our recent `Param.modulate(SineOsc(...))` glitch came from the Runloop-side graph-binding sampler advancing the same modulation oscillator that the audio thread was supposed to own.

Debugging checklist:

- If a value is “supposed to be smooth”, confirm which loop is advancing it.
- If audio dies or sounds discontinuous, check whether any Runloop hook, graph-binding helper, inspector, or warm-up path is touching the same synth objects.
- When in doubt, search for `Runloop.runLoop`, `preRun`, `postRun`, `GraphBindingRuntime`, and `FieldAudioEngine` before assuming a pure DSP bug.

---

## Key Features (User-Facing)

### Box graph, connections, and “exported” properties

- Boxes form a persistent, live-synced graph:
  - Structural connections are `children`/`parents` (`src/jvmMain/kotlin/afield/Boxes.kt`), editable via actions (e.g. `-connect`, `-disconnect`).
  - Connections drive both UI structure and “where values come from” (inheritance over the graph).
- “Exported properties” are usually implemented as **inherited properties**:
  - `Inheritance<T>` (`src/jvmMain/kotlin/afield/stdlib/stdlib.kt`) resolves a missing property by walking up `parents` until it finds a value.
  - `prop<T>(name)` / `rootProp<T>(name)` (`src/jvmMain/kotlin/afield/stdlib/props.kt`) are macros that generate new inherited properties (so a parent can define it, and connected descendants can read it).
  - Shader properties like `fragment`, `vertex`, `compute`, `task`, `mesh` are built this way (`src/jvmMain/kotlin/afield/stdlib/shaders.kt`), which is why shader graphs compose naturally via box connections.

### Context lookup: `fromContext()`

- Field code often relies on **contextual dependency lookup** instead of threading references everywhere. `fromContext<T>()` finds “the nearest `T`” by searching:
  - the currently executing `Box` (`Box.context`)
  - values in that box’s `properties`
  - values in that box’s Kotlin script variables (`box.ki.context.vars`)
  - then walking up `parents` and repeating the same checks
- The helpers live in `afield.four.modules` (see `src/jvmMain/kotlin/afield/four/modules/Mesh.kt`) and are used heavily so constructors can be ergonomic during live coding, e.g. `val renderer: Renderer = fromContext()`.
- Use `fromContextWithNameOrNull<T>("name")` when you need a specific named value rather than “first by type”.
- A missing `Renderer` in graphics code is usually a topology bug, not a timing bug: the renderer parent was not launched, the example box was not connected beneath it, or the code looked up context after crossing an async boundary. Fix the graph or capture `Renderer` before `start(...)`; do not solve this with sleeps.

### Integrated editor + documentation browser

- The main UI is a canvas + **Monaco** editor (`src/jsMain/kotlin/afield/Main.kt`, assets under `src/jsMain/resources/monaco/`, bootstrapped from `src/commonMain/resources/index.html`).
- Field also has a **documentation iframe mode**:
  - Frontend command: `switch-to-docs-iframe` (`src/jsMain/kotlin/afield/commands.kt`).
  - Runtime wiring (show/hide/sizing/default URL): `src/commonMain/resources/bootstrapMonaco.js`.
  - Docs pages can drive Field via an injected `window.FieldAPI` bridge (`src/commonMain/resources/docsIframeApi.js`).

### Outputs (HTML channels, logs, widgets)

- Each box has an **output area** rendered as a set of resizable “disclosure” panels (one per channel).
- To emit HTML into an output disclosure from user code:
  - `display("channel", "<b>hello</b>")` (wired via `HasOutput`/`DefaultOutput` in `src/jvmMain/kotlin/afield/outputs.kt`)
  - Execution installs `displayProxy` to send `set-output` RPC (`src/jvmMain/kotlin/afield/execution.kt` → `Set_output` in `src/commonMain/kotlin/rpc.kt`)
  - Frontend receives `set-output` and inserts HTML (and executes `<script>` tags) via `src/jsMain/kotlin/afield/outputareas.kt` + `src/commonMain/resources/outputareas2.js`
- Textual outputs are separate from `display(...)`:
  - `print/println` and `_print(...)` / `_println(...)` (macros) emit `Message("output")` / `Output` payloads for inline/editor-adjacent output (`src/jvmMain/kotlin/afield/execution.kt`, macro wiring in `src/jvmMain/kotlin/afield/macros/macros.kt`).
- Output area controls:
  - `displayOpen(...)`, `displayClose(...)`, `displayClear(...)`, `displayClearAll()` send `output-control` RPC (`src/jvmMain/kotlin/afield/outputs.kt`; handled in `src/jsMain/kotlin/afield/outputareas.kt`).
- Logging and object browsing inside outputs (iframe embeds):
  - `logs(...)` → `/logs` (`src/jvmMain/kotlin/afield/outputs.kt`, route served in `src/jvmMain/kotlin/afield/Server.kt` from `src/commonMain/resources/logs.html`)
  - `tree(...)` → `/tree`, `column(...)` → `/browse` (Inspector links in `src/jvmMain/kotlin/afield/server/browsing.kt`, HTML in `src/commonMain/resources/tree.html` / `src/commonMain/resources/browser.html`)
- Widgets in outputs:
  - Controls DSL lives in `src/jvmMain/kotlin/afield/outputs.kt` (e.g. `slider`, `vec2Control`, `checkbox`, `stringInput`, `colorPicker`, `controlGroup`, `syncToProperty`)
  - Browser-side interaction + RPC sends live in `src/commonMain/resources/sliders.js`
  - Server receives updates in `src/jvmMain/kotlin/afield/Server.kt` (`slider-update`, `vec2-update`, `checkbox-update`, `string-update`, `color-update`) and runs registered callbacks.

### Slideshows (interactive onboarding) + `withChat`

- The slideshow system is a small DSL for guiding people through a document:
  - Source: `src/jvmMain/kotlin/afield/SlideShow.kt` (entrypoint `Box.slideShow { step(...) { ... } }`)
  - Examples: `src/jvmMain/kotlin/afield/slideshow_example.kt`
  - Supporting docs: `dev_docs/SLIDESHOW_VISUAL_GUIDE.md`, `dev_docs/INTERACTIVE_GUIDES.md`, `dev_docs/SLIDESHOW_ANNOTATIONS_QUICKREF.md`
- What a step can do:
  - Show an anchored note overlay (`note(...)` / `markdownNote(...)`) via `ShowSlideNote` (`src/commonMain/kotlin/rpc.kt`), rendered client-side in `src/jsMain/kotlin/afield/Main.kt` (`show-slide-note` handler).
  - Select a box + switch property + scroll/highlight a line (`selectBox(...)`, `SelectAndEditBox`).
  - Draw annotation lines from the note to boxes / code lines (`annotate(...)`, `annotateToLine(...)` → `SlideAnnotation` list).
  - Use templating in note HTML/markdown: `{{placeholder}}` resolves against host box properties, scripting vars, and global `templates`/`knownValues` (`src/jvmMain/kotlin/afield/SlideShow.kt`).
  - Use link-driven navigation and callbacks:
    - `#slideshow:next|previous|home|stop` (handled by the client and forwarded as `slideshow-command`; server executes against the active `SlideShowInstance` in `src/jvmMain/kotlin/afield/Server.kt`)
    - `#custom:name` triggers a per-step `linkCallback("name") { ... }`
    - Markdown shortcut links like `[[next]]`/`[[back]]` are expanded by `SlideMarkdown` (`src/jvmMain/kotlin/afield/SlideShow.kt`)
- Chat / discussion (“withchat”) for guides:
  - Per-step chat: `SlideStepBuilder.withChat(...)` adds a collapsible “Discussion” section under the slide note overlay (implemented in `src/jsMain/kotlin/afield/Main.kt` chat widgets).
  - Shared chat in outputs: `Box.withChat(...)` mounts the same chat UI into an output channel (`display("chat", ...)`) using `mountSharedChat(...)` exported from `src/jsMain/kotlin/afield/Main.kt`.
  - Server integration: Field proxies chat RPC to the optional Flask chat server via `src/jvmMain/kotlin/afield/server/chat.kt` (`CHAT_SERVER_URL`, default `http://localhost:5002`; server code in `server/guide_chat_server.py`; data in `chat_data/`).

### FLine drawing + rendering (SVG + Vulkan)

- `afield.graphics.FLine` is the core “drawn line / path” type (`src/jvmMain/kotlin/afield/graphics/FLine.kt`), with heavy geometry utilities in:
  - `src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt` (sampling, offsets, boolean ops, smoothing, etc.)
  - `src/jvmMain/kotlin/afield/graphics/FLinesAndJavaShapes.kt` (round-tripping 2D geometry through `java.awt.geom` shapes)
- Rendering targets:
  - **SVG/HTML output**: `src/jvmMain/kotlin/afield/graphics/someSkiko.kt` renders FLines to SVG strings via Skia `SVGCanvas` and `Box.lines(...)` displays them in output channels.
  - **Vulkan**: `src/jvmMain/kotlin/afield/graphics/StandardDrawing.kt` streams line/triangle/point data into `afield.four` buffers (`afield.four.buffers.DynamicallyWriteable`) for GPU rendering.

---

## Vulkan / OpenXR Rendering (`afield.four`)

The Vulkan stack lives under `src/jvmMain/kotlin/afield/four/` and is built on **LWJGL** (Vulkan + VMA + shaderc + SPIRV-Cross), with optional **OpenXR** targets for VR.

Start here:
- `src/jvmMain/kotlin/afield/four/Renderer.kt`: owns instance/device/queues/swapchain, runs frame loop, executes targets/modules.
- `src/jvmMain/kotlin/afield/four/util/Options.kt`: global toggles (validation, VR enablement, vsync, Vulkan SDK hints).

Deeper structure is documented in:
- `src/VULKAN_GRAPHICS_SYSTEM.md` (file-by-file map of `afield.four`)

Practical notes:
- On macOS you may need to point LWJGL at a Vulkan loader; `Options.doVulkanSDK()` sets `Configuration.VULKAN_LIBRARY_NAME` when configured.
- Shader introspection uses the LWJGL SPIRV-Cross (`spvc`) bindings; the old checked-in `bin/spirv-cross-*` binaries are no longer required.

---

## On-Disk Document Format (`.edn`)

### Where documents live

By default the server uses a workspace directory (see `--workspace`); documents are stored as folders under it:
- `<workspace>/<documentName>/metadata.edn` (current state)
- `<workspace>/<documentName>/backups/metadata_backup_00000.edn` … (previous versions)
- `<workspace>/<documentName>/versions.edn` (history transitions + optional user-assigned names)
- `<workspace>/<documentName>/<uid>_<property>_.<ext>` (sidecar files for large text fields)

### `metadata.edn` schema (v2)

The canonical writer/reader is `src/jvmMain/kotlin/afield/saving.kt` (via `SavingContext`), using the EDN implementation in `src/jvmMain/kotlin/afield/edn.kt`.

Conceptually:
- `:version` — currently `2`
- `:boxes` — vector/list of `{ :id "...", :properties { ... } }`
- `:persistentProperties` — optional list of property keys the document expects to persist (merged into `Box.ioPersistentProperties` on load)

Values inside `:properties` are mostly primitives/collections, plus a few custom tagged forms:
- `#afield/rect [x y w h]` — serialized `Rect`
- `#afield/box "some-uid"` — box reference
- `#afield/file-inline {"path" "...", "inline" "..."}`
- `#afield/file "relative/path.ext"` — sidecar reference (read from disk)

Note: some template/example files (e.g. under `metadata_store/`) use a simpler EDN subset (plain vectors for frames, inline strings for code). The loader is intentionally permissive.

### Sidecar file naming

Sidecars follow:
`<boxId>_<propertyName>_.<extension>`

The default mapping is in `Box.ioSeparateProperties` (`src/jvmMain/kotlin/afield/Boxes.kt`), e.g.:
- `code` → `kt`
- `fragment|vertex|compute|task|mesh` → `glsl`
- `customization|remote_js` → `js`
- `metadata` → `yaml`

`FileWatchManager` reloads externally edited sidecars into live sessions; see `src/jvmMain/kotlin/afield/FileWatchManager.kt` and `dev_docs/FileWatchManager.md`.

### History files

- Backups are created by `File.safeSave(...)` (in `src/jvmMain/kotlin/afield/saving.kt`) into `backups/`.
- `versions.edn` is written by `HistoryManager` (`src/jvmMain/kotlin/afield/history.kt`) and contains:
  - `transitions`: list of `{fromIndex,toIndex,timestamp}`
  - `names`: map from version index → friendly name

---

## Documentation (MkDocs + Read-Only EDN Viewer)

### MkDocs site (human docs)

- Config: `public/mkdocs.yml` (Material theme)
- Source content: `public/docs/` (Markdown; notebooks supported via `mkdocs-jupyter`)
- Output: `public/field4/` (`site_dir: field4` in `public/mkdocs.yml`)
- Typical loop:
  - Build docs: `cd public && uv run --project .. --group docs mkdocs build`
  - Fast incremental docs build: `cd public && FIELD_DOCS_RUNNER_MODE=incremental FIELD_DOCS_RUNNER_PORT=9477 uv run --project .. --group docs mkdocs build`
  - Strict docs build that re-runs screenshots even after runner changes: `cd public && FIELD_DOCS_RUNNER_MODE=strict FIELD_DOCS_RUNNER_PORT=9477 uv run --project .. --group docs mkdocs build`
  - Full recapture build that bypasses the screenshot/video cache entirely: `cd public && FIELD_DOCS_RUNNER_MODE=always FIELD_DOCS_RUNNER_PORT=9477 uv run --project .. --group docs mkdocs build`
  - No-capture docs build that reuses existing assets without starting Field: `cd public && FIELD_DOCS_RUNNER_MODE=never uv run --project .. --group docs mkdocs build`
  - Serve built site (matches Field’s default docs iframe URL): `cd public && python3 -m http.server 8000`
  - Browse: `http://localhost:8000/field4/`

### Docs inside Field (documentation iframe)

- Field can show documentation in an iframe overlay (resizable, switchable from the command palette).
- Frontend toggle command: `switch-to-docs-iframe` (`src/jsMain/kotlin/afield/commands.kt`).
- Default URL + iframe wiring: `src/commonMain/resources/bootstrapMonaco.js` (defaults to `http://localhost:8000/field4/index.html`).
- Bridge API for docs pages: `src/commonMain/resources/docsIframeApi.js` (injected into the iframe; exposes `window.FieldAPI.*` to run actions / navigate / execute boxes).

### Exporting docs from a live Field document

- `src/jvmMain/kotlin/afield/docs/doc.kt` contains `exportToDocumentation(...)`, which walks boxes with metadata and emits MkDocs-ready Markdown plus captured assets (screenshots/video).

### Read-only viewer for `.edn` documents (offline viewer)

- Source: `src/commonMain/resources/offline.html`, `src/commonMain/resources/offline-viewer.js`, `src/commonMain/resources/edn-parser.js`
- Canonical vendored assets: `src/jsMain/resources/monaco/min`, `src/jsMain/resources/woff2`, `src/jsMain/resources/iosevka.css`
- Build script: `build-offline-docs.sh` → outputs `dist/offline-viewer/`
- Static copies under `public/` and `dev_docs/` are generated/ignored; refresh them with `uv run python tools/sync_offline_assets.py --target public --target dev_docs --no-docs`.
- Sanity check: `uv run python tools/check_offline_assets.py` verifies the canonical files are present and the generated copies have not become tracked again.
- Embedding docs: `src/commonMain/resources/embed-example.html` shows how to iframe `offline.html?file=...` for read-only document display.

---

## Python Side Services (`server/`)

These are optional and separate from the main Kotlin server:
- `server/metadata_server.py`: serves templates from `metadata_store/`
- `server/guide_chat_server.py`: guide/slideshow chat service

See `server/README.md` for usage.

---

## Working Safely in This Codebase

- Property names are API: keys like `code`, `frame`, `children`, `autorun`, `layers`, etc. are used across JVM, JS, persistence, and UI. When changing them, chase usages across:
  - `src/jvmMain/kotlin/afield/Boxes.kt` (persistence + UI resync sets)
  - `src/commonMain/kotlin/rpc.kt` (wire types)
  - `src/jsMain/kotlin/afield/*` (UI behavior)
- Keep EDN compatibility: prefer additive changes, version gates, or fallbacks in loaders.
- Avoid editing minified/vendor assets unless unavoidable; prefer changing Kotlin/JS or the unminified sources that generate them.

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

When the user types `/graphify`, use the installed graphify skill or instructions before doing anything else.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- Dirty graphify-out/ files are expected after hooks or incremental updates; dirty graph files are not a reason to skip graphify. Only skip graphify if the task is about stale or incorrect graph output, or the user explicitly says not to use it.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
