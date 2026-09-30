# AField / Field·4 — Repo Guide for Agents

A corrective paragraph in this file is a bug report against a tool, and it stays only until the
tool enforces it. When you add a "do not", name the tool change that would retire it. Histories
of rules that became code live in
[`dev_docs/agents/AGENT_SURFACE_TRUTHFULNESS.md`](dev_docs/agents/AGENT_SURFACE_TRUTHFULNESS.md).

---

## 1. What this is

**Field·4** is a Kotlin live-coding environment for graphics and exploratory data visualization:

- **Backend (JVM/Kotlin)**: a Ktor HTTPS + WebSocket server (`afield.Server`) that manages
  documents, runs user code, and optionally drives Vulkan/OpenXR rendering via `afield.four`.
- **Frontend (Kotlin/JS)**: a browser UI (canvas + Monaco editor) talking to the server over a
  small JSON RPC protocol defined in `src/commonMain`.
- **Persistence (.edn)**: documents live on disk as `metadata.edn` plus sidecar files for large
  text properties (Kotlin, GLSL, JS, YAML).

Top-level layout:

- `build.gradle.kts`, `settings.gradle.kts`: Kotlin Multiplatform build (JVM + JS), LWJGL/Vulkan
  deps, webpack bundling.
- `src/`: the JVM + JS application (`commonMain` / `jvmMain` / `jsMain`).
- `public/`: built/static assets and docs (`public/docs/`, offline viewer assets).
- `metadata_store/`, `templates/`: example and template documents (`metadata.edn`).
- `server/`: optional Python services — `metadata_server.py` serves templates from
  `metadata_store/`, `guide_chat_server.py` backs slideshow chat (see `server/README.md`).
- `dev_docs/`: internal design notes, grouped in topical folders (`agents/`, `animation/`,
  `workspace/`, `physics/`, `testing/`, ...).
- `field-user-dir/`: local user workspace artifacts — mutable user data.

---

## 2. Run it

- `./field.sh` builds JS and runs the JVM server (wraps `./gradlew runJvm`).
  - It runs in the foreground when stdin is a tty, or when it senses an agent
    (`CLAUDECODE`, `CLAUDE_CODE_ENTRYPOINT`, `CODEX_SANDBOX*`); otherwise it opens a detached GUI
    terminal (a Finder/Explorer double-click). If the sensing misses your agent, set
    `AFIELD_FIELD_SH_NO_TERMINAL=1`.
  - `./field.sh --offline` (or `AFIELD_OFFLINE=1`) builds with Gradle `--offline` from the
    dependency cache. The build refuses SNAPSHOT and dynamic repository versions
    (`failOnDynamicVersions` / `failOnChangingVersions`), which is what keeps offline builds from
    stalling on a daily re-check.
- Open `https://localhost:9092/index.html?file=firstDocument`.
- Workspace (the documents root):
  - `--workspace <path>` (parsed by `option(...)`), e.g.
    `./gradlew runJvm --args="--workspace /tmp/AFieldWorkspace"`, or `./field.sh /tmp/AFieldWorkspace`
    (also `-w` / `--workspace`). The launcher resolves a relative path against your shell's cwd,
    refuses a non-existent directory before building, and treats a path to a document (a folder
    holding `metadata.edn`, or the file itself) as "the workspace above this, and open that".
    `./field.sh --print-workspace` answers "which one would I get?" without launching.
  - With no argument the workspace is whatever was used last, recorded machine-wide in
    `~/.afield/last-workspace.txt` — so a sibling clone or worktree may have written it.
    `field.sh` prints the workspace it resolved, and where from, before it builds.
- Open a document directly: `--document <name>` (resolved against the workspace; a path is
  reduced to its last segment). `/app-url` — what the launcher curls for the Electron window —
  prefers it over the welcome page and the last-opened document. Through the launcher:
  `./field.sh -d <name>` (or `--document`/`--document=`, or a document path as the bare argument,
  which names the workspace too). The launcher rejects a document that does not exist, because
  Field *creates* an unknown document.
- `field.ps1` accepts the same flags on Windows; that parser has never been run (see
  `dev_docs/launcher_workspace_verification.md`).
- Ports and HTTPS:
  - `PORT` overrides the default `9092`. The server uses HTTPS with a self-signed dev cert.
  - A loopback-only HTTP mirror listens on `AFIELD_HTTP_PORT` if set, otherwise `PORT + 10000`
    when that fits (`PORT=9477` gives `http://localhost:19477/...`). `AFIELD_HTTP_PORT=off`
    disables it.
  - Keystore: `AFIELD_KEYSTORE_PATH`, `AFIELD_KEYSTORE_PASS`, `AFIELD_KEYSTORE_ALIAS`;
    `./gradlew generateLocalKeystore` writes `config/field4-local.p12`.
- Stopping a Field you started: `kill -INT` the PID listening on its port
  (`lsof -nP -iTCP:<port> -sTCP:LISTEN -t`) — that PID is the Field JVM. It exits in about 2 s,
  first saving any edit the 5 s autosave had not reached; this works for a `./field.sh &` launch
  too (`dev_docs/SIGINT_SHUTDOWN.md`).

---

## 3. Verify changes

Python tooling runs through the repo `uv` environment, so the docs runner and the backdoor share
dependencies (`public/docs_hooks.py` runs `tools/field_backdoor.py` with the interpreter building
the docs). Do not gate browser screenshots on ambient `python`/`pip` or a Python `playwright`
import: screenshots use the JVM-owned Playwright, and `pyproject.toml` deliberately does not
need the Python package. The entrypoints (`field_backdoor.py`, `field_session.py`,
`run_field_tests.py`, `field_mcp.py`, `run_ui_regressions.py`, `build_zensical_docs.py`)
re-exec under the repo `.venv` (or `uv run`) when their dependencies are missing; `uv run`
avoids the hop. Importing them as libraries never restarts the importer.

The consolidated guide is [`dev_docs/testing/TESTING_STRATEGY.md`](dev_docs/testing/TESTING_STRATEGY.md).
The crib:

| Situation | Command |
| --- | --- |
| Backdoor sanity check | `uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 --command status` |
| Python tool suites (`checkPythonTools`) | `uv run --project . --group backdoor python tools/run_python_tests.py` |
| Complete source-warning audit | `./gradlew --no-daemon --rerun-tasks warningGates` |
| Smoke catalog | `uv run --project . --group backdoor python tools/run_field_tests.py --refresh-catalog` |
| One smoke test | `... tools/run_field_tests.py --id <catalog-id>` (also `--tag`, `--runner jvm-main`) |
| Browser UI regressions | `uv run --project . --group backdoor python tools/run_ui_regressions.py` (`--id <regression>` for one) |
| One browser screenshot | `uv run --project . --group backdoor python tools/field_backdoor.py --json --with-playwright --command 'new-box --name Visual' --command 'run @last --value "display(\"main\", \"<div>hello</div>\")"' --command 'screenshot-box @last /tmp/field-smoke.png'` |
| Docs native prerequisites | `./gradlew buildDocsNativePrerequisites` (Ceres, TetGen; checks Manifold and JEP; checksummed downloads shared across worktrees) |
| Docs HTML only | `FIELD_DOCS_RUNNER_MODE=never uv run --project . --group docs python tools/build_zensical_docs.py --clean` |
| Docs author loop | `FIELD_DOCS_RUNNER_MODE=incremental uv run --project . --group docs python tools/build_zensical_docs.py` |
| Runner/API change | `FIELD_DOCS_RUNNER_MODE=strict uv run --project . --group docs python tools/build_zensical_docs.py` |
| Full recapture | `FIELD_DOCS_RUNNER_MODE=always uv run --project . --group docs python tools/build_zensical_docs.py --clean` |
| Offline asset ownership | `uv run python tools/check_offline_assets.py` |

The smoke and docs runners pick the first free port from 9477 (never 9092); pass `--port` /
`FIELD_DOCS_RUNNER_PORT` only to pin one. The UI runner uses 9490 upward. Per-test smoke
timeouts come from each `FIELD_TEST` annotation's `timeoutSec`. Runner details (prewarm,
coverage ordering, artifact retention) are in
[`FIELD_BACKDOOR.md`](dev_docs/agents/FIELD_BACKDOOR.md#suite-runner-details).

**One verification at a time per checkout.** The Python wrappers serialize on
`.field-backdoor/verification.lock`, and raw `./gradlew` honours it too: while another live
process holds it, a build prints `WAIT gradle: verification lock held by ...` and waits
(`AFIELD_VERIFICATION_LOCK=fail` to stop instead, `=off` to opt out). Two builds in one checkout
corrupt shared Kotlin class output and incremental-compiler backups.

Accepted reds live only in `dev_docs/testing/expected_test_failures.json` (JVM) and
`dev_docs/testing/expected_smoke_failures.json` (smoke), enforced in both directions. Never
describe a red as expected only in prose, and never use wildcards in either register.

### Gradle hygiene

- Use the wrapper: `./gradlew compileKotlinJvm` (Windows: `.\gradlew compileKotlinJvm`, and
  likewise `.\gradlew runJvm`).
- Never use machine-wide `killall` as Gradle cleanup; it destroys unrelated IDE, Field, and
  sibling-worktree processes. `./gradlew --stop` is the checkout-aware daemon cleanup.
- A failed or stalled Gradle invocation is a failed verification. Record its first concrete
  error and inspect the owning daemon/lock before changing process state; only re-run after a
  specific repair or environment change.
- An isolated `GRADLE_USER_HOME` / `--project-cache-dir` is a fallback for a confirmed cache-lock
  conflict you cannot clear without disrupting the user's work; it is much slower.

### The docs/test loop

- Make code, API, docs, and test-runner fixes autonomously when the docs/test pipeline exposes a
  real problem. Run the smallest relevant gate once, read its first concrete failure, make a
  specific repair, re-run that gate, then finish with one full pass. An unchanged retry is a
  diagnostic comparison, never evidence of green.
- Runner modes: `incremental` reuses valid captures, `strict` also invalidates them when runner
  logic changes (`public/docs_hooks.py` digests), `always` regenerates, `never` skips capture.
- The docs runner reuses a server only when its identity proves the same checkout and no runtime
  source changed since it started; otherwise it fails closed.
- A docs screenshot showing the red Field error marker, or empty/incorrect output, is a real
  failure: get the compiler/runtime error from the backdoor or runner and fix the snippet or the
  runtime.
- If a run times out and later probes stop reaching `is-running`, restart the managed Field JVM
  before trusting subsequent failures.
- Hardware captures marked `capture-profile="linux-nvidia"` are retained by default in every
  mode. Regenerating them needs Linux, `FIELD_DOCS_CAPTURE_PROFILES=linux-nvidia`, and the GPU
  features; macOS always retains them, and a missing retained asset fails the build. Neural-tool
  pages use committed media and their own scripts (`dev_docs/lighting/NEURAL_TOOLS.md`).
- **A capture exercises the real code, or it does not exist.** Never fake, synthesize, hardcode,
  mock, or hand-edit a capture — no CPU stand-in for a GPU render, no hand-written string for a
  computed value, no scripted placeholder for a live panel. If the real capture will not come
  out: fix the code, fix the capture system (hook / `drive` script / framing / timing), or omit
  the capture and say why. A one-off picture for yourself never enters the pipeline. See
  `dev_docs/DOCS_RUNNER_GPU_CAPTURES.md` → "Principle".

---

## 4. Backdoor essentials

Prefer `tools/field_backdoor.py` to driving a browser by hand. Full reference:
[`dev_docs/agents/FIELD_BACKDOOR.md`](dev_docs/agents/FIELD_BACKDOOR.md) (workflow, capture
signatures and recipes, suite details); cheat sheet:
[`AGENT_QUICK_REF.md`](dev_docs/agents/AGENT_QUICK_REF.md); MCP bridge:
[`FIELD_MCP.md`](dev_docs/agents/FIELD_MCP.md).

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 \
  --command 'new-box --name Hello' \
  --command 'run @last --value "println(\"hello world\")"'
```

- **Ports.** Pass a custom high port and reuse it within one verification session; pick a new
  one for a guaranteed fresh JVM. With no `--port` the backdoor chooses the first free one from
  9477. It starts Field itself (checkout-local workspace under `build/field-automation-workspace`)
  or reuses a server only when it proves the same checkout with no newer source. The user's Field
  on 9092 takes `--attach-existing`.
- **Session lifetime.** A scratch session lasts one `field_backdoor.py` process, not the life of
  the port: put dependent commands in one invocation; a later process cannot reuse earlier box
  uids. Use `--document <name>` when state must span invocations.
- **Workspaces.** When the session *starts* the server, pass `--workspace <parent>` with a
  relative `--document <name>`. When it *attaches*, the workspace is the server's: pass exactly
  `serverWorkspace` or no `--workspace` at all (a disagreeing one is an error).
- **A real document in the user's Field.** Begin with
  `--attach-existing --document <name> --command status` and check `serverWorkspace`,
  `documentName`, `sessionKey` (= `serverWorkspace` + `/` + name) and a plausible `boxCount` —
  never `requestedWorkspace`, which echoes your argument. `--attach-existing` bypasses only source
  freshness. Never point a mutating session at a document the user is working in; copy it.
- **Verdicts.** Use `--json`: results carry `ok`, `data`, `events`, `timedOut`. `ok` for `run`,
  `launch`, `continue`, `launch-batch` is the box's own verdict (diagnostics in `data.errors`);
  `--no-fail-on-error` waives it when the failure is the point. `open <name>` refuses a name the
  server does not have; `--create` makes one.
- **Captures.** Use `--with-playwright` (or `playwright-open`) *before* the run that creates
  output DOM, and `editor-open <box> --property code` before a run whose inline visualizers
  (`histogram`, `history`, `stats`, `pianoRoll`) you will capture; a capture that could not have
  seen the run says so in `captureWarnings`. Screenshot results report `imagePixelWidth`,
  `imagePixelHeight` and `devicePixelRatio`, since `clip` is in CSS pixels. Inspect the actual
  PNG/MP4. The browser is headless unless `AFIELD_PLAYWRIGHT_VISIBLE=true`.
- **JVM entrypoints.** Use `run-jvm-main <entrypoint>` (or `run_field_tests.py --id`) rather than
  a hand-built `java`/`JavaExec` line; they apply `requiredAgentJvmArgs()`, and
  `AgentJvmMainLauncher` refuses to start without them.
- **External browsers** use the HTTP mirror: `http://localhost:19477/index.html?file=<doc>` for a
  session on 9477.
- **Runtime values**: the MCP tool `field_debug_probe` samples a symbol at a `src/` file and line
  or in a running box ([`FIELD_MCP.md#runtime-debug-probe`](dev_docs/agents/FIELD_MCP.md#runtime-debug-probe));
  backdoor-launched Field enables JDWP, a bare `./gradlew runJvm` does not.
- **Semantic automation** belongs in `OperationTranscript` macro YAML
  ([`OPERATION_TRANSCRIPT.md`](dev_docs/agents/OPERATION_TRANSCRIPT.md); examples in
  `dev_docs/agents/transcripts/`): `macro write <name> --file <path>`, then `macro run <name>`.
- **Logs.** Every run writes `.field-backdoor/logs/<session>.jsonl` (plus
  `.managed-server.log` for a server it started); `status` reports both. They are
  byte-budgeted diagnostics and safe to delete. `run-jvm-main` omits the launched command unless
  given `--include-command`.

---

## 5. Architecture

### Source sets

- `src/commonMain` — shared RPC and dispatch. `Messages.kt` is the tiny message bus (`handlers`,
  `sendWithReply`, `handle`, `expect fun _send`); `rpc.kt` holds the `@Serializable` payload
  types. If a type crosses the wire, define it here.
- `src/jvmMain` — backend. `afield/Server.kt` (Ktor server, `/ws`, session/document state,
  handlers), `afield/Boxes.kt` (the `Box` model and property conventions), `afield/execution.kt`
  and `afield/KotlinInterface.kt` (compile/run live Kotlin, route output to the UI). Subsystems:
  persistence (`saving.kt`, `edn.kt`, `FileWatchManager.kt`), history (`history.kt`, UI in
  `src/commonMain/resources/history.html`), the live-coding stdlib (`afield/stdlib/`),
  Vulkan/VR (`afield/four/`), extra runtimes (`afield/python/`, `afield/clojure/`,
  `afield/remotejs/`), server features (`afield/server/`), and grouped helpers in
  `afield/{docs,internal,math,ui,visualization,workspace}/` (still package `afield`).
- `src/jsMain` — frontend. `afield/Main.kt` (canvas, selection, editor, commands),
  `afield/Message.kt` (WebSocket client on `ws(s)://<host>/ws` + JSON decode + dispatch), `afield/Messages_helpers.kt`
  (`@JsExport` wrappers). Static assets loaded by `src/commonMain/resources/index.html`: Monaco
  under `src/jsMain/resources/monaco/`, fonts and helpers under `src/jsMain/resources/`.
- There is no separate chat bundle (no `src/jsChatMain`): guide/shared chat is mounted from the main frontend and
  proxied by `afield/server/chat.kt` when the optional Flask chat server runs.

### RPC

Messages are `kotlinx.serialization` JSON in the `afield.Message` envelope. Both sides send via
`_send(...)` (JVM actual in `Server.kt`, JS actual in `Message.kt`); `handle(message)` looks up
`handlers[message.name]`, many of which `afield.Server.server()` installs. Replies:
`sendWithReply(message, callbackName, handler)` sets `replyTo`, and the receiver answers with
`Message(callbackName)` (often via `message.toName(...)`). A new RPC is (1) payload types in
`rpc.kt`, (2) a server handler in `Server.kt` or `afield/server/*`, (3) a frontend sender or
receiver in `src/jsMain/kotlin/afield/*` (or JS resources).

### Kotlin/JS ↔ plain JS

`@JsExport` functions are exports of the generated `afield.mjs`, not globals. A resource loaded as
`type="module"` must import them — `import { send, sendWithReply } from "./afield.mjs";` (see
`bootstrapMonaco.js`, `embeddedEditors.js`, `outputareas2.js`, `outputs.js`).
`tools/check_module_imports.py` fails on a bare call of an export in a module. Non-module legacy
scripts may still use globals exposed on `window`; new module scripts import.

### Runloop vs audio loop

Field has more than one main loop, and conflating them makes bugs hard fast.

- `afield.Runloop` (`src/jvmMain/kotlin/afield/Runloop.kt`), started from `Server.kt`, advances
  the coarse frame counter (`Runloop.frame`), runs `preRun` / `main.frame()` / `postRun` once per
  tick, and hosts main-thread coordination: UI, box/runtime bookkeeping, websocket-adjacent work,
  graphics setup that must step with the app frame, and low-rate control updates (graph/query
  bookkeeping, scheduling). The Vulkan renderer's `Runloop.preRun` hooks are Runloop work too.
  It is not the audio callback, not sample-accurate, and must not advance oscillator phase,
  filter state, delay lines, envelopes, or other DSP state because UI or helper code wants a
  "current value".
- `FieldAudioEngine` (`src/jvmMain/kotlin/afield/four/synth/FieldAudioEngine.kt`) owns a separate
  daemon audio thread: it calls `Signal.render(...)` / `generate(...)` on the real synth graph with
  its `SynthContext`, owns DSP state progression, and mixes the fixed-size PCM buffers OpenAL
  queues.
- Treat a synth `Signal` graph as audio-thread-owned unless the code is explicitly multi-context.
  Do not sample it from `Runloop`, `GraphBindingRuntime`, docs/preview helpers, or inspectors to
  get a display value; publish a snapshot from the real render path instead. Signals cache per
  `SynthContext` and per cycle, but many nodes carry mutable state beyond that cache, so rendering
  one graph from two contexts corrupts it even with correct cache keys. The
  `Param.modulate(SineOsc(...))` glitch was the Runloop-side graph-binding sampler advancing the
  modulation oscillator the audio thread owned.
- Debugging: if a value should be smooth, find which loop advances it. If audio dies or
  clicks, check whether a Runloop hook, graph-binding helper, inspector, or warm-up path touches
  the same synth objects — search `Runloop.runLoop`, `preRun`, `postRun`, `GraphBindingRuntime`,
  `FieldAudioEngine` before assuming a DSP bug.

### Box graph, inherited properties, and `fromContext()`

- Boxes form a persistent, live-synced graph. `children`/`parents` (`Boxes.kt`), edited by
  actions such as `-connect`/`-disconnect` (the backdoor's `connect <parent> <child>...` matches
  the frontend `g` action), drive both UI structure and where values come from.
- "Exported" properties are usually inherited: `Inheritance<T>` (`afield/stdlib/stdlib.kt`)
  resolves a missing property by walking up `parents`; `prop<T>(name)` / `rootProp<T>(name)`
  (`afield/stdlib/props.kt`) generate new inherited properties. Shader properties (`fragment`,
  `vertex`, `compute`, `task`, `mesh`) are built this way (`afield/stdlib/shaders.kt`), which is
  why shader graphs compose through box connections.
- `fromContext<T>()` (in `afield.four.modules`, `src/jvmMain/kotlin/afield/four/modules/Mesh.kt`)
  finds the nearest `T` by searching the executing `Box` (`Box.context`), its `properties`, its
  script variables (`box.ki.context.vars`), then each parent in turn. Use
  `fromContextWithNameOrNull<T>("name")` for a specific named value. A missing `Renderer` is
  usually topology, not timing: the renderer parent was not launched, the box is not connected
  beneath it, or the lookup happened after an async boundary. Fix the graph or capture the
  `Renderer` before `start(...)`; never solve it with sleeps.

### Outputs

- Each box has an output area of resizable "disclosure" panels, one per channel.
- `display("channel", "<b>hello</b>")` (`HasOutput`/`DefaultOutput` in `afield/outputs.kt`) goes
  through the `displayProxy` installed by execution (`execution.kt` → `Set_output` in `rpc.kt`);
  the frontend inserts the HTML and runs its `<script>` tags (`afield/outputareas.kt` +
  `src/commonMain/resources/outputareas2.js`).
- `print`/`println` and the `_print(...)`/`_println(...)` macros are separate: they emit
  `Message("output")` / `Output` for inline, editor-adjacent output (`execution.kt`, macros in
  `afield/macros/macros.kt`).
- `displayOpen(...)`, `displayClose(...)`, `displayClear(...)`, `displayClearAll()` send
  `output-control` (`outputs.kt`, handled in `outputareas.kt`).
- Iframe embeds: `logs(...)` → `/logs`, `tree(...)` → `/tree`, `column(...)` → `/browse`
  (`afield/server/browsing.kt`; `logs.html`, `tree.html`, `browser.html`).
- Widgets: the controls DSL in `outputs.kt` (`slider`, `vec2Control`, `checkbox`, `stringInput`,
  `colorPicker`, `controlGroup`, `syncToProperty`) → `sliders.js` → `*-update` handlers in
  `Server.kt` (`slider-`, `vec2-`, `checkbox-`, `string-`, `color-update`) → your callbacks.

### Slideshows and `withChat`

- `Box.slideShow { step(...) { ... } }` (`afield/SlideShow.kt`; examples in
  `afield/slideshow_example.kt`; notes in `dev_docs/SLIDESHOW_FEATURE.md`,
  `SLIDESHOW_ANNOTATIONS.md`, `INTERACTIVE_GUIDES.md`).
- A step can show an anchored note (`note(...)` / `markdownNote(...)` via `ShowSlideNote`,
  rendered by the `show-slide-note` handler in `Main.kt`); select a box, switch property and
  highlight a line (`selectBox(...)`, `SelectAndEditBox`); draw annotation lines to boxes or code
  lines (`annotate(...)`, `annotateToLine(...)` → `SlideAnnotation`); and template note text with
  `{{placeholder}}` against host box properties, scripting vars, and global
  `templates`/`knownValues`.
- Navigation: `#slideshow:next|previous|home|stop` links are forwarded as `slideshow-command`
  and executed against the active `SlideShowInstance` in `Server.kt`; `#custom:name` triggers a
  step's `linkCallback("name") { ... }`; `[[next]]`/`[[back]]` are expanded by `SlideMarkdown`.
- Chat: `SlideStepBuilder.withChat(...)` adds a collapsible Discussion under a note;
  `Box.withChat(...)` mounts the same UI into an output channel (`display("chat", ...)`) via
  `mountSharedChat(...)` from `Main.kt`. Field proxies chat RPC through `afield/server/chat.kt` to
  the optional Flask server (`CHAT_SERVER_URL`, default `http://localhost:5002`;
  `server/guide_chat_server.py`; data in `chat_data/`).

### Drawing layer (Excalidraw)

- An Excalidraw scene laid over the whole canvas for notation: `src/jsMain/kotlin/afield/drawingLayer.kt`, toggled by `toggle-drawing-layer` (Alt+D). Off, it is visible but inert; on, it owns mouse and keyboard until toggled back.
- Scene coordinates are Field world coordinates. The canvas camera is `screen = world * mainZoom + mainTranslation` (`mainCanvasTransform()`), so Excalidraw's zoom is `mainZoom` and its scroll is `mainTranslation / mainZoom`. Every writer of `mainTranslation` must call `drawingLayerViewportChanged` (there are four: `applyViewportTranslation`, the wheel and middle-drag pans in `Main.kt`, and bookmark restore).

### Notation: box code ↔ the drawing

- `notation` (the box is the implicit receiver) reads, writes and reacts to the drawing layer's scene: `notation.marks/strokes/arrows/named()/over(box)`, `notation { rect(); arrow(from = box, to = box); stroke(fline) }` (owned by the box, replaced on re-run), `notation.edit/delete/anchor`, `notation.onStroke/onArrow/onText/onChange { }`. Source in `src/jvmMain/kotlin/afield/notation/`; design and the full API in `dev_docs/NOTATION.md`.
- Reading by intersection: `notation.space("score", x = 0.0..8.0, y = 84.0..48.0)` maps a region to its own axes; `vertical(t)` / `horizontal(p)` return `Hit`s (value, span, along, slope, pen pressure); `events` / `between(t0, t1)` / `sounding(t)` for playheads.
- Clients send their scene as `notation-scene`, merged server-side by element version (`NotationScene.merge`). Tests: `NotationTest`, `NotationIntersectionTest`.

### Canvas zoom

- Ctrl/pinch-wheel zooms the box canvas about the cursor (`setCanvasZoom`, 0.1×–4×); **Reset Canvas Zoom** in the palette returns to 100%. Translation stays in screen pixels, so pan deltas and centring are zoom-independent.
- Anything that turns a screen measurement into a world one must divide by `mainZoom`: box drag/resize deltas, `boxWorldRect`, the connection-layer snapshot in `gestures.kt`, `lastMouse` in `commands.kt`. Window-sized SVG layers take `worldViewBox(w, h)`. The background dots are the visual unit for `frame` and scale with zoom; the live grid shader is `src/commonMain/resources/fieldWebglBackground.js` (the inline copy in `Main.kt` is a fallback that does not run when that script has installed), and both take `u_zoom`.
- Not yet zoom-aware: slideshow annotations (`slideshowSupport.kt`), bookmarks (they restore translation, not zoom), and the server's `viewport` rect (still translation + window size).
- One scene per document, as `.excalidraw` JSON in the root box's `drawing` property (inline in `metadata.edn`, since root never writes sidecars). Remote updates merge with Excalidraw's `reconcileElements`.
- The React/Excalidraw bundle is vendored in `src/jsMain/resources/excalidraw/` and lazy-loaded; rebuild it with `cd tools/excalidraw-bundle && npm ci && npm run build`. Probe it with `window.drawingLayerState()`.

### FLine

`afield.graphics.FLine` (`afield/graphics/FLine.kt`) is the core drawn line/path type, with
geometry in `FLineGeometry.kt` (sampling, offsets, boolean ops, smoothing) and
`FLinesAndJavaShapes.kt` (round-trips through `java.awt.geom`). It renders to SVG/HTML
(`afield/graphics/someSkiko.kt`, Skia `SVGCanvas`; `Box.lines(...)` displays it) and to Vulkan
(`afield/graphics/StandardDrawing.kt` streams into `afield.four.buffers.DynamicallyWriteable`).

---

## 6. Graphics (`afield.four`)

The Vulkan stack under `src/jvmMain/kotlin/afield/four/` is built on LWJGL (Vulkan, VMA,
shaderc, SPIRV-Cross via the `spvc` bindings) with optional OpenXR targets for VR.
`Renderer.kt` owns instance/device/queues/swapchain and runs the frame loop;
`util/Options.kt` holds global toggles (validation, VR, vsync, SDK hints). The file-by-file map
is `src/VULKAN_GRAPHICS_SYSTEM.md`. On macOS, `Options.doVulkanSDK()` uses the loader from
`VULKAN_SDK` or the newest `~/VulkanSDK/<version>` and pins `VK_DRIVER_FILES` to that SDK's
KosmicKrisp manifest by default on Apple silicon with macOS 26+ (MoltenVK elsewhere, or with
`AFIELD_VULKAN_DRIVER=moltenvk`, which the IOSurface features still need;
`dev_docs/KOSMICKRISP.md`). `Window` and `Instance` call it themselves, so no entrypoint can skip
it. With no SDK, LWJGL's bundled MoltenVK is used.
`afield.four.util.VulkanProbe` probes loader, driver, layers and SDK in a child JVM and shows them
in the `/welcome` Vulkan panel (`/welcome#vulkan`, `GET /api/vulkan-status[?refresh=1]`), with
per-OS install steps. Shader introspection uses LWJGL's SPIRV-Cross bindings; the old checked-in
`bin/spirv-cross-*` binaries are no longer needed.

**Read the worked lessons before writing box-level graphics code, and before the engine
source.** The box-facing API (`moduleHere`, `Mesh`, `ComputeMesh`, `RenderToTexture`,
`fromContext()`, `prop<>`) reads very differently from the Vulkan plumbing, and code inferred from
`Renderer.kt` compiles against the engine but is not how a box is written.
[`public/docs/assets/canonical_graphics/LESSONS.md`](public/docs/assets/canonical_graphics/LESSONS.md)
indexes ~33 lessons that run against a live renderer — cameras, compute shaders and particles,
texturing, glTF, render-to-texture, ping-pong buffers, blur kernels, shader `#include` and Lygia,
printf and texture-probe debugging, a GPU radix sort, Slang, DLA. Every file there is named by
box uid; the index maps each lesson to its Kotlin and shader files, records the parent chain it
needs, and carries a reverse index from API symbol (`RenderToTexture`, `LoadedTexture`,
`computeModuleHere`, `GpuRadixSort`, ...) to lessons. Prefer adapting a lesson to inventing a
call sequence. The prose walkthrough with captured images is
[`public/docs/afield_four_graphics.md`](public/docs/afield_four_graphics.md).

Renderer access is graph topology. For automation: run
`dev_docs/agents/transcripts/graphics_renderer_launch.yaml`, wait with
`wait-native-renderer agent_graphics_context --frames 3`, create the example box, connect
`agent_graphics_context -> example_box`, then run it. Code that starts coroutine work with
`start(...)` resolves `val renderer: Renderer = fromContext()` first and passes it in.

---

## 7. On-disk document format (`.edn`)

- Documents are folders under the workspace (`--workspace`):
  `<workspace>/<documentName>/metadata.edn` (current state), `backups/metadata_backup_00000.edn`
  ... (previous versions), `versions.edn` (history), and `<uid>_<property>_.<ext>` sidecars.
- `metadata.edn` v2 — canonical reader/writer `afield/saving.kt` (`SavingContext`) over
  `afield/edn.kt`: `:version 2`; `:boxes`, a list of `{ :id "...", :properties { ... } }`;
  optional `:persistentProperties`, merged into `Box.ioPersistentProperties` on load.
- Tagged values inside `:properties`: `#afield/rect [x y w h]`, `#afield/box "uid"`,
  `#afield/file-inline {"path" "...", "inline" "..."}`, `#afield/file "relative/path.ext"` (a
  sidecar read from disk). Template/example files (e.g. `metadata_store/`) use a simpler subset
  (plain vectors for frames, inline code strings); the loader is deliberately permissive.
- Sidecar naming is `<boxId>_<propertyName>_.<extension>`, mapped by `Box.ioSeparateProperties`
  (`Boxes.kt`): `code` → `kt`; `fragment|vertex|compute|task|mesh` → `glsl`;
  `customization|remote_js` → `js`; `metadata` → `yaml`. `FileWatchManager` reloads externally
  edited sidecars into live sessions (`dev_docs/FileWatchManager.md`).
- History: `File.safeSave(...)` (`saving.kt`) writes `backups/`; `HistoryManager` (`history.kt`)
  writes `versions.edn` — `transitions` (`{fromIndex,toIndex,timestamp}`) and `names` (version
  index → friendly name).

---

## 8. Docs authoring

- Zensical site: sources in `public/docs/` (Markdown; notebooks supported), output in
  `public/field4/`. Build commands are in the table in section 3. Serve standalone with
  `cd public && python3 -m http.server 8000` (browse `http://localhost:8000/field4/`), or iterate
  with `tools/build_zensical_docs.py --skip-build` then
  `cd public && uv run --project .. --group docs zensical serve -f zensical.generated.yml`.
- `FIELD_DOCS_RUNNER_SESSION=1` (experimental, default off) routes eligible non-graphics captures
  through one warm `tools/field_session.py` daemon; its safety rules and measurements are in
  `dev_docs/testing/TESTING_STRATEGY.md` (section 5).
- **`!!! note` vs `??? aside`.** `!!! note` / `warning` / `info` / `tip` is for a reader using the
  thing on the page: an API that does not behave as named, a statistic whose floor is not zero, a
  security caveat, a solver's guarantee. It stays open in the text column. `??? aside "Title"` is
  commentary about how the page or its figures came to be, written for us and the next agent —
  what an earlier cut got wrong and what proved it, what the build cannot capture, which claim CI
  now pins. It renders collapsed and, at ≥ 82em, floats into the right margin. The test: would
  this be worth reading to someone who does not care how the page was made? If not, it is an
  aside — keep writing them. Mechanics: `pymdownx.details` supplies `???` (`public/zensical.yml`);
  `aside` is styled in `public/docs/stylesheets/extra.css` (search `??? aside`) as a float with a
  negative right margin, no JS; bodies are indented four spaces.
- Docs inside Field: `switch-to-docs-iframe` (`src/jsMain/kotlin/afield/commands.kt`) shows an
  overlay wired in `bootstrapMonaco.js`, defaulting to `/field-docs/`. That route serves this
  checkout's `public/field4/` when the page is built and proxies `https://openendedgroup.com/field4/`
  otherwise (`AFIELD_DOCS_MIRROR_SOURCE=local|upstream`, `AFIELD_DOCS_MIRROR_DIR`;
  `afield/server/docsAnnotations.kt`; `dev_docs/DOCS_IFRAME_MODE.md`); both inject the annotation
  panel. Docs pages drive Field through `window.FieldAPI` (`docsIframeApi.js`).
- Human notes on the docs are a revision queue: `tools/docs_notes.py` (stdlib; `queue`, `brief
  <page>`, `sync`, `resolve`/`question`/`memory`, and `revise`, which runs one agent per page in
  parallel). Each note is anchored to its Markdown or KDoc lines by `git blame` origin, so line
  numbers stay right after other edits. See `dev_docs/DOCS_NOTES_REVISION.md`.
- `exportToDocumentation(...)` (`afield/docs/doc.kt`) turns boxes with metadata into docs-ready
  Markdown plus captured assets.
- Read-only offline viewer: `offline.html`, `offline-viewer.js`, `edn-parser.js` in
  `src/commonMain/resources`; canonical vendored assets in `src/jsMain/resources/monaco/min`,
  `woff2`, `iosevka.css`. `build-offline-docs.sh` writes `dist/offline-viewer/`. Copies under
  `public/` and `dev_docs/` are generated and ignored — refresh with
  `uv run python tools/sync_offline_assets.py --target public --target dev_docs --no-docs`;
  `embed-example.html` shows iframing `offline.html?file=...`.

---

## 9. Working safely

- Property names are API: `code`, `frame`, `children`, `autorun`, `layers`, ... cross JVM, JS,
  persistence, and UI. When changing one, chase usages through `Boxes.kt` (persistence and UI
  resync sets), `rpc.kt` (wire types), and `src/jsMain/kotlin/afield/*`.
- Keep EDN compatible: additive changes, version gates, or loader fallbacks.
- Avoid editing minified or vendored assets; change the Kotlin/JS or unminified sources that
  generate them.
- Do not cite source by line number here (`tools/test_agents_md.py` fails on a
  `SomeFile.kt:<digits>` reference); lines move, and a stale one sends the next agent to the
  wrong place.
- If a commit obliges everyone who pulls it to *do* something — clean the build, re-run a
  generator, install a tool — end its message with a `Field-Notice:` trailer saying so in one
  imperative line. **Check for updates** shows those trailers verbatim to anyone whose update spans
  the commit (`src/jvmMain/kotlin/afield/internal/upstreamcheck.kt`). Ordinary commits carry none;
  the notice only has weight while it stays rare.
