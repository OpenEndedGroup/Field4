# AField / Field·4 — Repo Guide for Agents

A corrective paragraph in this file is a bug report against a tool, and it stays only until the
tool enforces it. When you add a "do not", name the tool change that would retire it. Histories
of rules that became code live in
[`dev_docs/agents/AGENT_SURFACE_TRUTHFULNESS.md`](dev_docs/agents/AGENT_SURFACE_TRUTHFULNESS.md).

This file must stay under the 32 KiB that Codex loads from a project instruction file
(`tools/test_agents_md.py` fails past it). Put detail in
[`dev_docs/agents/AGENTS_DETAIL.md`](dev_docs/agents/AGENTS_DETAIL.md), which holds the long
form of sections 2–8, and keep the rule and a link here.

---

## 1. What this is

**Field·4** is a Kotlin live-coding environment for graphics and exploratory data visualization:

- **Backend (JVM/Kotlin)**: a Ktor HTTPS + WebSocket server (`afield.app.server.Server`) that manages
  documents, runs user code, and optionally drives Vulkan/OpenXR rendering via Field 4.
- **Frontend (Kotlin/JS)**: a browser UI (canvas + Monaco editor) talking to the server over a
  small JSON RPC protocol defined in `src/commonMain`.
- **Persistence (.edn)**: documents live on disk as `metadata.edn` plus sidecar files for large
  text properties (Kotlin, GLSL, JS, YAML).

Top-level layout:

- `build.gradle.kts`, `settings.gradle.kts`: Kotlin Multiplatform build (JVM + JS), LWJGL/Vulkan
  deps, webpack bundling.
- `src/`: the JVM + JS application (`commonMain` / `jvmMain` / `jsMain`).
- `public/`: built/static assets and docs (`public/docs/`, offline viewer assets).
- `dev_docs/agents/transcripts/`, `dev_docs/agents/demos/`, `templates/`: authored examples and templates;
  generated transcript documents live under `public/docs/assets/agent_transcripts/`.
- `python/`: Python that ships with Field, appended to `sys.path` in Python boxes — `fieldcv`
  (OpenCV/mediapipe detectors sharing numpy memory with Kotlin through `afield.lang.python.NumpyShared`;
  optional `cv` dependency group, see `python/fieldcv/README.md`).
- `dev_docs/`: internal design notes, grouped in topical folders (`agents/`, `animation/`,
  `workspace/`, `physics/`, `testing/`, ...).
- `field-user-dir/`: local user workspace artifacts — mutable user data.

---

## 2. Run it

- `./field.sh` builds JS and runs the JVM server (wraps `./gradlew runJvm`); open
  `https://localhost:9092/index.html?file=firstDocument`. It stays in the foreground under a tty or
  an agent it senses (`CLAUDECODE`, `CLAUDE_CODE_ENTRYPOINT`, `CODEX_SANDBOX*`); if it misses
  yours, set `AFIELD_FIELD_SH_NO_TERMINAL=1`. `--offline` (or `AFIELD_OFFLINE=1`) builds from the
  dependency cache; the build refuses SNAPSHOT and dynamic versions so that cannot stall.
- Workspace: `./field.sh <path>` (also `-w` / `--workspace`), or
  `./gradlew runJvm --args="--workspace <path>"`. With no argument it is whatever this checkout
  used last (`.afield-local/last-workspace.txt`, then `~/.afield/last-workspace.txt`);
  `--print-workspace` says which without launching. A path to a document means "the workspace
  above it, and open that".
- Document: `--document <name>` (launcher `-d`). The launcher rejects one that does not exist,
  because Field *creates* an unknown document. `field.ps1` takes the same flags but its parser has
  never been run.
- Ports: `PORT` (default `9092`, HTTPS with a self-signed dev cert). A loopback HTTP mirror
  listens on `AFIELD_HTTP_PORT`, else `PORT + 10000` (`off` disables it). Keystore:
  `AFIELD_KEYSTORE_PATH`/`_PASS`/`_ALIAS`; `./gradlew generateLocalKeystore`.
- Stop a Field you started with `kill -INT` on the PID listening on its port
  (`lsof -nP -iTCP:<port> -sTCP:LISTEN -t`); it saves pending edits and exits in about 2 s.

Long form: [`AGENTS_DETAIL.md` §2](dev_docs/agents/AGENTS_DETAIL.md#2-run-it).

For box-code authoring and review, use the compact, prompt-ready
[Field box-code house style](dev_docs/agents/BOX_CODE_HOUSE_STYLE.md).

---

## 3. Verify changes

Python tooling runs through the repo `uv` environment; the entrypoints (`field_backdoor.py`,
`field_session.py`, `run_field_tests.py`, `field_mcp.py`, `run_ui_regressions.py`,
`build_zensical_docs.py`) re-exec under the repo `.venv` when their dependencies are missing.
Screenshots use the JVM-owned Playwright: never gate them on ambient `python`/`pip` or a Python
`playwright` import.

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
| Drive or probe frontend UI | `... tools/field_backdoor.py --json --with-playwright --command 'page-eval --body --file probe.js' --command 'screenshot-window /tmp/out.png'` |
| Feel benchmark (`dev_docs/testing/FEEL_BENCHMARKS.md`) | `uv run --project . --group backdoor python tools/feel_bench.py run` (then `... compare`) |
| Docs native prerequisites | `./gradlew buildDocsNativePrerequisites` |
| Docs HTML only | `FIELD_DOCS_RUNNER_MODE=never uv run --project . --group docs python tools/build_zensical_docs.py --clean` |
| Docs author loop | `FIELD_DOCS_RUNNER_MODE=incremental uv run --project . --group docs python tools/build_zensical_docs.py` |
| Recapture one page | `FIELD_DOCS_RUNNER_MODE=always uv run --project . --group docs python tools/build_zensical_docs.py --page <page.md>` (repeatable) |
| Runner/API change | `FIELD_DOCS_RUNNER_MODE=strict uv run --project . --group docs python tools/build_zensical_docs.py` |
| Full recapture | `FIELD_DOCS_RUNNER_MODE=always uv run --project . --group docs python tools/build_zensical_docs.py --clean` |
| Offline asset ownership | `uv run python tools/check_offline_assets.py` |

The smoke and docs runners pick the first free port from 9477 (never 9092); pass `--port` /
`FIELD_DOCS_RUNNER_PORT` only to pin one. The UI runner uses 9490 upward; the web-publication
refresh (`tools/docs_publications.py sync`, which the lander runs) uses 9620–9639. Per-test smoke
timeouts come from each `FIELD_TEST` annotation's `timeoutSec`. Runner details are in
[`FIELD_BACKDOOR.md`](dev_docs/agents/FIELD_BACKDOOR.md#suite-runner-details).

**One verification at a time per checkout.** The Python wrappers serialize on
`.field-backdoor/verification.lock`, and raw `./gradlew` honours it too (`WAIT gradle:
verification lock held by ...`; `AFIELD_VERIFICATION_LOCK=fail` to stop instead, `=off` to opt
out). Two builds in one checkout corrupt shared Kotlin class output.

Accepted reds live only in `dev_docs/testing/expected_test_failures.json` (JVM) and
`dev_docs/testing/expected_smoke_failures.json` (smoke), enforced in both directions. Never
describe a red as expected only in prose, and never use wildcards in either register.

### Landing on `main`

Never merge into `main`, `git pull`, or push `main` yourself. Commit on your topic branch, run
`uv run python tools/land.py precheck` (names conflicts with `main` and runs only the guards your
diff can trip), then `uv run python tools/land.py enqueue` and stop: the lander merges,
regenerates generated artifacts, runs the full guard and fast-forwards `main`, one branch at a
time. A failure comes back as a bounce with its reason; fix the branch and enqueue again. For the
outcome, run the printed `tools/land.py wait <id>` as a background command (exit 0 landed, 1
bounced) instead of polling. `land.py mine` lists every attempt for a branch, `retract` takes a
queued item back out, `enqueue --push` pushes `main` once it lands, `sync [--push]` lands
`origin/main` through the queue, and `doc` queues a refresh of `public/field4/`. Bring `main`
into your branch with `tools/merge_regen.py main`, which regenerates generated files. The hooks
only report; do not use `--no-verify`. Long form:
[`AGENTS_DETAIL.md`](dev_docs/agents/AGENTS_DETAIL.md#landing-on-main).

### Scheduled routines

The overnight loops (self-improvement, nightly CI, the feel benchmark, the weekly What's New, the
idea scout, the worktree reaper) are defined in `routines/<name>/ROUTINE.md`; their per-machine
journals stay on the orphan `nightly/journal` and `nightly/ci-journal` branches. A scheduler
holds only a stub prompt pointing at `main`'s copy, so change a routine by landing an edit there.
`tools/routines.py status|install|run|check` (`routines/README.md`); `/routines.html` in Field.

### Gradle hygiene

- Use the wrapper: `./gradlew compileKotlinJvm` (Windows: `.\gradlew compileKotlinJvm`).
- Never use machine-wide `killall` as Gradle cleanup; it destroys unrelated IDE, Field, and
  sibling-worktree processes. `./gradlew --stop` is the checkout-aware daemon cleanup.
- A failed or stalled Gradle invocation is a failed verification. Record its first concrete
  error and inspect the owning daemon/lock before changing process state; only re-run after a
  specific repair or environment change.
- An isolated `GRADLE_USER_HOME` / `--project-cache-dir` is a fallback for a confirmed cache-lock
  conflict you cannot clear without disrupting the user's work; it is much slower.

### The docs/test loop

- Fix code, API, docs and test-runner problems the docs/test pipeline exposes autonomously: run
  the smallest relevant gate once, read its first concrete failure, make a specific repair,
  re-run that gate, then finish with one full pass. An unchanged retry is a diagnostic
  comparison, never evidence of green.
- Runner modes: `incremental` reuses valid captures, `strict` also invalidates them when runner
  logic changes, `always` regenerates, `never` skips capture.
- A docs screenshot showing the red Field error marker, or empty/incorrect output, is a real
  failure: get the compiler/runtime error from the backdoor or runner and fix the snippet or the
  runtime. If probes stop reaching `is-running` after a timeout, restart the managed Field JVM.
- Captures marked `capture-profile="linux-nvidia"` or `"macos-electron"` are retained except
  where they can be regenerated; a missing retained asset fails the build
  ([long form](dev_docs/agents/AGENTS_DETAIL.md#the-docstest-loop)).
- **A capture exercises the real code, or it does not exist.** Never fake, synthesize, hardcode,
  mock, or hand-edit a capture — no CPU stand-in for a GPU render, no hand-written string for a
  computed value, no scripted placeholder for a live panel. If the real capture will not come
  out: fix the code, fix the capture system (hook / `drive` script / framing / timing), or omit
  the capture and say why. A one-off picture for yourself never enters the pipeline. See
  `dev_docs/DOCS_RUNNER_GPU_CAPTURES.md` → "Principle".

---

## 4. Backdoor essentials

For work the user expects to see in Field's Electron app, begin with the MCP `field_status` and
`field_electron_tabs` tools and check `serverWorkspace`, the selected tab and its URL before any
document or box operation; then `field_electron_inspect`, `field_electron_screenshot`,
`field_electron_act` and `field_electron_console`. Label an independent browser capture as a
separate browser view.

Prefer `tools/field_backdoor.py` to driving a browser by hand. Full reference:
[`dev_docs/agents/FIELD_BACKDOOR.md`](dev_docs/agents/FIELD_BACKDOOR.md); cheat sheet:
[`AGENT_QUICK_REF.md`](dev_docs/agents/AGENT_QUICK_REF.md); MCP bridge:
[`FIELD_MCP.md`](dev_docs/agents/FIELD_MCP.md).

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 \
  --command 'new-box --name Hello' \
  --command 'run @last --value "println(\"hello world\")"'
```

- **Ports.** With no `--port` the backdoor takes the first free one from 9477 and starts Field
  itself (workspace under `build/field-automation-workspace`), or reuses a server only when it
  proves the same checkout with no newer source. The user's Field on 9092 takes `--attach-existing`.
- **Session lifetime.** A scratch session lasts one `field_backdoor.py` process: put dependent
  commands in one invocation. Use `--document <name>` when state must span invocations.
- **Workspaces.** Starting the server: `--workspace <parent>` with a relative `--document <name>`.
  Attaching: pass exactly `serverWorkspace` or no `--workspace` at all.
- **A real document in the user's Field.** Begin with
  `--attach-existing --document <name> --command status` and check `serverWorkspace`,
  `documentName`, `sessionKey` and a plausible `boxCount` — never `requestedWorkspace`, which
  echoes your argument. Never point a mutating session at a document the user is working in;
  copy it.
- **Verdicts.** Use `--json` (`ok`, `data`, `events`, `timedOut`). `ok` for `run`, `launch`,
  `continue`, `launch-batch` is the box's own verdict (diagnostics in `data.errors`);
  `--no-fail-on-error` waives it. `open <name>` refuses an unknown name; `--create` makes one.
- **Captures.** Use `--with-playwright` *before* the run that creates output DOM, and
  `editor-open <box> --property code` before a run whose inline visualizers you will capture;
  check `captureWarnings`. Inspect the actual PNG/MP4 (`clip` is in CSS pixels).
- **JVM entrypoints.** Use `run-jvm-main <entrypoint>` (or `run_field_tests.py --id`), never a
  hand-built `java` line; `AgentJvmMainLauncher` refuses to start without `requiredAgentJvmArgs()`.
- **External browsers** use the HTTP mirror: `http://localhost:19477/index.html?file=<doc>` for 9477.
- **Runtime values**: the MCP tool `field_debug_probe` samples a symbol at a `src/` file and line
  or in a running box ([`FIELD_MCP.md#runtime-debug-probe`](dev_docs/agents/FIELD_MCP.md#runtime-debug-probe)).
- **Semantic automation** belongs in `OperationTranscript` macro YAML
  ([`OPERATION_TRANSCRIPT.md`](dev_docs/agents/OPERATION_TRANSCRIPT.md); examples in
  `dev_docs/agents/transcripts/`).
- **Logs.** Every run writes `.field-backdoor/logs/<session>.jsonl`; `status` reports where.

Long form: [`AGENTS_DETAIL.md` §4](dev_docs/agents/AGENTS_DETAIL.md#4-backdoor-essentials).

---

## 5. Architecture

### Source sets

- `src/commonMain` — shared RPC and dispatch. `Messages.kt` is the tiny message bus (`handlers`,
  `sendWithReply`, `handle`, `expect fun _send`); `rpc.kt` holds the `@Serializable` payload
  types. If a type crosses the wire, define it here.
- `src/jvmMain` — backend; each folder is its package. `afield/app/server/Server.kt` (Ktor server,
  `/ws`, session/document state, handlers), `afield/document/graph/Boxes.kt` (the `Box` model and
  property conventions), `afield/runtime/execution/execution.kt` and
  `afield/runtime/kotlin/KotlinInterface.kt` (compile/run live Kotlin, route output to the UI).
  Subsystems: persistence (`afield/document/persistence/`: `saving.kt`, `edn.kt`,
  `FileWatchManager.kt`), history (`afield/document/history/history.kt`, UI in
  `src/commonMain/resources/history.html`), the live-coding library split by domain
  (`afield/{math,geometry,graphics,audio,media,simulation,data,ui,...}/`), Vulkan/VR
  (`afield/graphics/vulkan/`), extra runtimes (`afield/lang/{python,clojure,remotejs,...}/`) and
  server features (`afield/app/server/`).
- `src/jsMain` — frontend. `afield/app/client/Main.kt` (canvas, selection, editor, commands),
  `afield/app/client/Message.kt` (WebSocket client on `ws(s)://<host>/ws` + JSON decode + dispatch),
  `afield/app/client/Messages_helpers.kt` (`@JsExport` wrappers). Static assets loaded by `src/commonMain/resources/index.html`: Monaco
  under `src/jsMain/resources/monaco/`, fonts and helpers under `src/jsMain/resources/`.
- There is no separate chat bundle (no `src/jsChatMain`): guide/shared chat is mounted from the main frontend and
  proxied by `afield/server/chat.kt` when the optional Flask chat server runs.

### RPC

Messages are `kotlinx.serialization` JSON in the `afield.app.protocol.Message` envelope. Both sides send via
`_send(...)` (JVM actual in `Server.kt`, JS actual in `Message.kt`); `handle(message)` looks up
`handlers[message.name]`, many of which `afield.app.server.Server.server()` installs. Replies:
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

- `afield.runtime.lifecycle.Runloop` (`src/jvmMain/kotlin/afield/Runloop.kt`) advances the coarse
  frame counter and runs `preRun` / `main.frame()` / `postRun` once per tick: UI, box/runtime
  bookkeeping, graphics setup, low-rate control. It is not sample-accurate and must not advance
  oscillator phase, filter state, delay lines, envelopes or other DSP state.
- `FieldAudioEngine` (`src/jvmMain/kotlin/afield/audio/synth/FieldAudioEngine.kt`) owns a separate
  audio thread that renders the synth `Signal` graph and owns its state progression.
- Treat a `Signal` graph as audio-thread-owned. Never sample it from `Runloop`,
  `GraphBindingRuntime`, docs/preview helpers or inspectors for a display value; publish a snapshot
  from the real render path. If audio dies or clicks, look for Runloop-side code touching synth
  objects before assuming a DSP bug
  ([long form](dev_docs/agents/AGENTS_DETAIL.md#runloop-vs-audio-loop)).

### Box graph, inherited properties, and `fromContext()`

- Boxes form a persistent, live-synced graph. `children`/`parents` (`Boxes.kt`), edited by
  actions such as `-connect`/`-disconnect` (the backdoor's `connect <parent> <child>...` matches
  the frontend `g` action), drive both UI structure and where values come from.
- "Exported" properties are usually inherited: `Inheritance<T>`
  (`afield/document/properties/stdlib_properties.kt`) resolves a missing property by walking up
  `parents`; `prop<T>(name)` / `rootProp<T>(name)` (`afield/document/properties/props.kt`) generate
  new inherited properties. Shader properties (`fragment`, `vertex`, `compute`, `task`, `mesh`) are
  built this way (`afield/graphics/shader/shaders.kt`), which is why shader graphs compose through
  box connections.
- `fromContext<T>()` (in `afield.document.context`, `src/jvmMain/kotlin/afield/document/context/Mesh_context.kt`)
  finds the nearest `T` by searching the executing `Box` (`Box.context`), its `properties`, then
  each ancestor generation in turn (properties, then script variables `box.ki.context.vars`). The
  asking box's own script variables are skipped unless `includeLocalVariables = true`, or
  `val r = fromContext<R>()` would find its own previous answer on every later run. Use
  `fromContextWithNameOrNull<T>("name")` for a specific named value. A missing `Renderer` is
  usually topology, not timing: the renderer parent was not launched, the box is not connected
  beneath it, or the lookup happened after an async boundary. Fix the graph or capture the
  `Renderer` before `start(...)`; never solve it with sleeps.

### Outputs, slideshows, canvas

- Each box has an output area of resizable panels, one per channel. `display("channel", html)`
  (`afield/outputs.kt`) sets one; `print`/`println` are separate, inline editor output.
  `displayOpen/Close/Clear(...)` send `output-control`; `logs(...)`, `tree(...)`, `column(...)`
  embed iframes; the controls DSL (`slider`, `checkbox`, `colorPicker`, `syncToProperty`, ...)
  round-trips through `sliders.js` and the `*-update` handlers in `Server.kt`.
- `Box.slideShow { step(...) { ... } }` (`afield/SlideShow.kt`; `dev_docs/SLIDESHOW_FEATURE.md`)
  builds guided steps with notes, box selection and annotations; `withChat(...)` mounts the
  shared chat UI, proxied by `afield/server/chat.kt`.
- The drawing layer (`drawingLayer.kt`, Alt+D) is an Excalidraw scene in Field world coordinates,
  stored in the root box's `drawing` property; box code reads and writes it through `notation`
  (`afield/ui/notation/`, `dev_docs/NOTATION.md`).
- The canvas camera lives in `CanvasCamera` (`canvasCamera.kt`), one per split pane;
  `applyViewportTranslation` in `Main.kt` is its only writer (elsewhere use `moveCanvasCamera`).
  Turn screen measurements into world ones through a pane (`CanvasCamera.paneAt`/`paneOf`, then
  `worldX`/`worldY`/`worldRect`), and use `pane.rect` for "the visible canvas", never
  `window.innerWidth/innerHeight`. Split panes: `dev_docs/workspace/SPLIT_PANES_PLAN.md`.
- Build new floating panels on `FieldPanel` (`src/jsMain/kotlin/afield/fieldPanel.kt`).

Long form of all of these:
[`AGENTS_DETAIL.md` §5](dev_docs/agents/AGENTS_DETAIL.md#5-architecture).

### FLine

`afield.geometry.path.FLine` (`afield/graphics/FLine.kt`) is the core drawn line/path type, with
geometry in `FLineGeometry.kt` (sampling, offsets, boolean ops, smoothing) and
`FLinesAndJavaShapes.kt` (round-trips through `java.awt.geom`). It renders to SVG/HTML
(`afield/graphics/someSkiko.kt`, Skia `SVGCanvas`; `Box.lines(...)` displays it) and to Vulkan
(`afield/graphics/StandardDrawing.kt` streams into `afield.graphics.vulkan.buffers.DynamicallyWriteable`).

---

## 6. Vulkan graphics (Field 4)

The Vulkan stack under `src/jvmMain/kotlin/afield/four/` is built on LWJGL (Vulkan, VMA,
shaderc, SPIRV-Cross) with optional OpenXR targets for VR. `Renderer.kt` owns
instance/device/queues/swapchain and the frame loop; `util/Options.kt` holds global toggles. The
file-by-file map is `src/VULKAN_GRAPHICS_SYSTEM.md`. On macOS `Options.doVulkanSDK()` picks the
SDK loader and driver: KosmicKrisp by default on Apple silicon with macOS 26+, MoltenVK elsewhere
or with `AFIELD_VULKAN_DRIVER=moltenvk`, which the IOSurface features need
(`dev_docs/KOSMICKRISP.md`). `/welcome#vulkan` shows what was found.

**Read the worked lessons before writing box-level graphics code, and before the engine
source.** The box-facing API (`moduleHere`, `Mesh`, `ComputeMesh`, `RenderToTexture`,
`fromContext()`, `prop<>`) reads very differently from the Vulkan plumbing, and code inferred from
`Renderer.kt` compiles against the engine but is not how a box is written.
[`public/docs/assets/canonical_graphics/LESSONS.md`](public/docs/assets/canonical_graphics/LESSONS.md)
indexes ~33 lessons that run against a live renderer, maps each to its Kotlin and shader files
and parent chain, and has a reverse index from API symbol to lesson. Prefer adapting a lesson to
inventing a call sequence. The prose walkthrough is
[`public/docs/afield_four_graphics.md`](public/docs/afield_four_graphics.md).

Renderer access is graph topology. For automation: run
`dev_docs/agents/transcripts/graphics_renderer_launch.yaml`, wait with
`wait-native-renderer agent_graphics_context --frames 3`, create the example box, connect
`agent_graphics_context -> example_box`, then run it. A box beneath the renderer already has
`renderer`, so do not look it up; only code that starts coroutine work with `start(...)` captures
it first (`val r = renderer`). `./gradlew checkBoxIdioms` flags the redundant lookup.

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
  sidecar read from disk). Some legacy template/example files use a simpler subset
  (plain vectors for frames, inline code strings); the loader is deliberately permissive.
- Sidecar naming is `<boxId>_<propertyName>_.<extension>`, mapped by `Box.ioSeparateProperties`
  (`Boxes.kt`): `code` → `kt`; `fragment|vertex|compute|task|mesh` → `glsl`;
  `customization` → `js`; `metadata` → `yaml`. `FileWatchManager` reloads externally
  edited sidecars into live sessions (`dev_docs/FileWatchManager.md`).
- History: `File.safeSave(...)` (`saving.kt`) writes `backups/`; `HistoryManager` (`history.kt`)
  writes `versions.edn` — `transitions` (`{fromIndex,toIndex,timestamp}`) and `names` (version
  index → friendly name).

---

## 8. Docs authoring

- Zensical site: sources in `public/docs/` (Markdown; notebooks supported), output in
  `public/field4/`. Build commands are in the table in section 3.
- **What goes on a page:** [`dev_docs/agents/DOCS_WRITING_RULES.md`](dev_docs/agents/DOCS_WRITING_RULES.md).
  In short: write for the user, lead with the common case, introduce code before showing it and
  write it the way a box is written, look at every capture at column width, one page per topic
  (work that extends a page goes onto that page), link whatever a reader can open.
- **`!!! note` vs `??? aside`.** `!!! note` / `warning` / `info` / `tip` is for a reader using the
  thing on the page, and stays open in the text column. `??? aside "Title"` is commentary about
  how the page or its figures came to be, for us and the next agent; it renders collapsed and
  floats into the right margin. The test: would this be worth reading to someone who does not
  care how the page was made? If not, it is an aside.
- Docs inside Field: `/field-docs/` serves this checkout's `public/field4/` when built, else
  proxies the published site (`dev_docs/DOCS_IFRAME_MODE.md`); docs pages drive Field through
  `window.FieldAPI`.
- Human notes on the docs are a revision queue: `tools/docs_notes.py` (`public/docs/docs_notes.md`).
- The public changelog `public/docs/changelog.md` is written from `main`'s history:
  `tools/docs_changelog.py status|brief|check`; how to write one is
  `dev_docs/agents/DOCS_CHANGELOG.md`.
- The offline viewer's vendored assets under `public/` and `dev_docs/` are generated copies;
  refresh with `uv run python tools/sync_offline_assets.py --target public --target dev_docs --no-docs`.

Long form (session runner, aside mechanics, offline viewer, `exportToDocumentation`):
[`AGENTS_DETAIL.md` §8](dev_docs/agents/AGENTS_DETAIL.md#8-docs-authoring).

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
