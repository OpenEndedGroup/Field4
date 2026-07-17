# Field Backdoor for Agents

`tools/field_backdoor.py` is the preferred way for agents to run Field code and inspect the result without using the browser UI.
For the full testing decision tree, start with `dev_docs/testing/TESTING_STRATEGY.md`.

Use it when you need to:
- run a snippet and capture stdout
- capture compile errors or runtime exceptions
- create a temporary box and iterate on its code
- create a temporary `renderer` parent so child test boxes get a valid inherited `renderer` context for `afield.four`
- attach to a real document and mutate boxes over the websocket protocol
- open the real browser DOM for the current scratch or named session through Field's Playwright instance
- keep Playwright attached before a run when later screenshots or DOM checks depend on output areas created by that run
- get machine-readable results for later automation

Use the sidecar-file workflow when you want persistent workspace edits on disk. Use the backdoor when you want execution, output capture, and fast verification.

For semantic automation, use transcript macros. `OperationTranscript` is the single canonical format for Field document operations (`macro write` / `macro run`); the backdoor is the transport and capture harness around it and does not carry its own document-op language. Capture is done with the backdoor's own commands — `screenshot-box` / `screenshot-editor` / `screenshot-window` / `native-screenshot`, and `output` / `wait-output` — run after the macro against the boxes it created.

## What It Does

From the repository root, `tools/field_backdoor.py` will:
- check `https://localhost:9092/health`
- start `./gradlew runJvm` if Field is not already running
- connect to `wss://localhost:9092/ws`
- open a scratch document by default
- run commands either interactively or in batch mode
- write a unique session log under `.field-backdoor/logs/`

If it starts Field itself, it also writes a matching managed-server log in the same directory.
The session logs store truncated previews of large websocket payloads rather than full multi-megabyte HTML/SVG blobs, so they stay usable during visual-test runs.

A Field that the backdoor launches also gets **remote debugging (JDWP) enabled on port
5005** — it sets `AFIELD_JDWP_PORT=5005` in the child env (honoring an explicit override,
including `off`). This is what makes the runtime debug probe (`field_debug_probe`, see
[`FIELD_MCP.md`](./FIELD_MCP.md#runtime-debug-probe)) work out of the box against
backdoor-launched Field. JDWP is off by default for a plain `./gradlew runJvm`.

## Minimum Hello World

Run this from the repo root:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 \
  --command 'new-box --name Hello' \
  --command 'run @last --value "println(\"hello world\")"'
```

What this does:
1. starts or attaches to Field
2. opens scratch
3. creates one box
4. sets `code`
5. runs the box
6. returns JSON containing the output event

## First Command to Run

Before doing anything more involved, run:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 --command status
```

This tells you:
- whether Field was already running
- whether this session started its own server
- the unique session `logFile`
- the `managedLog` path if it launched Field
- whether you are in scratch mode

## Phase-1 Smoke Suite

Run the current `FIELD_TEST` catalog through the backdoor with:

```bash
uv run --project . --group backdoor python tools/run_field_tests.py --refresh-catalog
```

That regenerates `build/field-tests/catalog.json` and then invokes `uv run --project . --group backdoor python tools/field_backdoor.py --json` once per test.
For the phase-1 `jvm-main` entries, the backdoor command it uses is `run-jvm-main`, which asks the running Field JVM to execute the annotated entrypoint and return `exitCode`, `stdout`, and `stderr`.
Do not pass `--timeout` to `tools/run_field_tests.py`; that flag belongs to `tools/field_backdoor.py`, `run-jvm-main`, and the UI regression runner. Smoke-test timeouts are configured with `timeoutSec` in the `FIELD_TEST` annotation.
By default, `run-jvm-main` omits the full launched JVM command/classpath from the JSON payload because it is huge and rarely useful in agent transcripts. Add `--include-command` to the `run-jvm-main` command only when debugging launch or classpath construction.

## Browser UI Regression Suite

Use `tools/run_ui_regressions.py` when the behavior depends on the browser UI,
Monaco, command palette actions, Field panels, or retained screenshots:

```bash
uv run --project . --group backdoor python tools/run_ui_regressions.py --port 9490 --timeout 45
```

The runner uses `tools/field_backdoor.py --with-playwright` internally and must
run through the repo uv project with the `backdoor` group. By default it prints
per-test `RUN`, heartbeat, `PASS`, and `FAIL` lines; use
`--heartbeat-interval <seconds>` to adjust telemetry, `--id <test-id>` for a
focused run, and `--json` when stdout must stay machine-readable. Screenshots
are retained under `build/ui-regressions/artifacts/<test-id>/`, with
`latest-pass.png`, `latest-failure.png`, and up to 5 recent passing screenshots.

The `backdoor` uv group does not install Python Playwright, and it does not
need to. Browser automation for screenshots and UI regressions is provided by
Field's JVM/server-owned Playwright dependency; the Python backdoor only sends
websocket commands to that server. A failed `import playwright` in Python is not
a browser-smoke blocker.

The long-term coverage checklist is `dev_docs/testing/UI_REGRESSION_ROADMAP.md`.

For docs work, treat the smoke suite and the Zensical docs runner as one loop:

```bash
uv run --project . --group backdoor python tools/run_field_tests.py --refresh-catalog
FIELD_DOCS_RUNNER_MODE=always FIELD_DOCS_RUNNER_PORT=9477 \
  uv run --project . --group docs python tools/build_zensical_docs.py --clean
```

When you only need to rebuild the HTML around already-generated captures, use:

```bash
FIELD_DOCS_RUNNER_MODE=never \
  uv run --project . --group docs python tools/build_zensical_docs.py --clean
```

Public docs pages should use Field's code-fence renderers, not plain Markdown code fences, for user-facing examples:

- Use `diffstatic {data-lang=kotlin}` for static Field/Kotlin examples.
- Use `diffstatic {data-lang=bash}` for visible command examples.
- Use `diffstatic_runner {data-lang=kotlin ...}` when the block should execute or produce a screenshot/video.
- Keep plain fences only for literal Markdown examples or internal `dev_docs` prose where the rendered public style is irrelevant.

## uv Dependency Recipes

The docs and backdoor Python tools are designed to be run through `uv` from
the repository root. Install `uv` if that command is missing, but do not install
Zensical, MkDocs, Playwright, or backdoor dependencies into an ad hoc virtualenv.

The root `pyproject.toml` defines two dependency groups:

- `backdoor`: `websockets` and `PyYAML` for `tools/field_backdoor.py`.
- `docs`: Zensical, the configured docs extensions, `pillow` for docs hooks, and the backdoor dependencies. The docs group intentionally includes the backdoor dependencies because `public/docs_hooks.py` launches `tools/field_backdoor.py` with the same Python interpreter that builds the docs.

Neither group needs the Python `playwright` package for Field browser captures.
Use `--with-playwright` through the backdoor or `tools/run_ui_regressions.py`;
those commands target the server-owned Playwright browser in the running Field
JVM.

Use:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 --command status
```

and:

```bash
FIELD_DOCS_RUNNER_MODE=always FIELD_DOCS_RUNNER_PORT=9477 uv run --project . --group docs python tools/build_zensical_docs.py --clean
```

Use this no-capture form when you only need to rebuild HTML around assets that already exist on disk:

```bash
FIELD_DOCS_RUNNER_MODE=never uv run --project . --group docs python tools/build_zensical_docs.py --clean
```

That wrapper preprocesses runnable docs fences with `public/docs_hooks.py`,
writes generated sources under `public/.zensical-generated/`, writes
`public/zensical.generated.yml`, and then runs `python -m zensical build` from
the same uv-managed interpreter.

If either step exposes a compile error, runtime error, empty capture, or obviously broken screenshot/video, fix the underlying code or documentation and rerun.
If a timed-out snippet leaves later probes stuck before they emit `is-running`, restart the managed Field JVM and retry the repro once from a clean server before chasing secondary failures.

## Interactive Mode

Start the REPL:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py
```

Then type commands like:

```text
new-box --name Hello
run @last --value "println(\"hello world\")"
```

Useful when you want to iterate on one scratch session.

## Batch Mode

Batch mode is better for agents because it is deterministic and easy to script:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json \
  --command 'new-box --name Demo' \
  --command 'set @last code "println(\"one\")"' \
  --command 'run @last'
```

Each `--command` runs in order. `@last` means “the most recently created box in this session”.

## Transcript Macros

> **The canonical automation format is documented in full in
> [`OPERATION_TRANSCRIPT.md`](./OPERATION_TRANSCRIPT.md)** — the complete op vocabulary
> (`create edit move connect execute launch stop wait await save delete drive`), the
> loud-`launch` contract, output/error observability, and the build → run → capture →
> save → reopen lifecycle. This section is just the backdoor command surface for it.
> The `drive` op scripts the live UI through Playwright (keypresses/clicks) and records an
> `.mp4`/still — the basis for the docs `capture="ui"` fence (see `DOCS_RUNNER_GPU_CAPTURES.md`).

Use `macro` or `transcript` commands when you want durable semantic automation:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 \
  --command 'macro list' \
  --command 'macro run my_macro --selection @last'
```

Write a macro from a YAML file:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 \
  --command 'macro write cleanup_demo --file /tmp/cleanup_demo.yaml' \
  --command 'macro run cleanup_demo'
```

Macro files use the same `operations:` shape written by `OperationTranscript`:

```yaml
operations:
  - create:
      box: source
      name: Source Box
      frame: {x: 40.0, y: 40.0, w: 180.0, h: 100.0}
  - edit:
      box: source
      property: name
      from: Before
      to: After
```

Curated reusable examples live in `dev_docs/agents/transcripts/`. Start there before inventing a new automation shape:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 \
  --command 'macro write agent_hello_display --file dev_docs/agents/transcripts/hello_display.yaml' \
  --command 'macro run agent_hello_display'
```

For blank-canvas or multi-box construction macros, prefer stable box ids and run without `--selection`; selection remapping is intended for single-box edit macros.

Useful commands:
- `macro state`
- `macro list`
- `macro read <name>`
- `macro write <name> --file <path>`
- `macro run <name> [--selection <uid>]`
- `macro undo <name> [--selection <uid>]`
- `macro page --offset 0 --limit 80`
- `macro save-selection <name> --indices 3,4,5`
- `macro undo-selection --indices 3,4,5`

## Prefer `--file -` or `--file` for Larger Snippets

For agents, `--file -` is usually the best option because it avoids shell escaping completely.

Read code from stdin with a heredoc:

```bash
cat <<'EOF' | uv run --project . --group backdoor python tools/field_backdoor.py --json \
  --command 'new-box --name StdinDemo' \
  --command 'run @last --file -'
println("hello from stdin")
EOF
```

`--file -` means “read stdin”. The backdoor reads stdin once and reuses that same content if `--file -` appears again later in the same invocation.

If the code already exists on disk, pass a real path:

```bash
cat > /tmp/demo.kt <<'EOF'
println("hello from a file")
EOF

uv run --project . --group backdoor python tools/field_backdoor.py --json \
  --command 'new-box --name FileDemo' \
  --command 'run @last --file /tmp/demo.kt'
```

This avoids shell-escaping problems.

## Graphics Context Mode

Use `--graphics-context` when the snippet needs a live `afield.four.Renderer` inherited from a parent box:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --graphics-context --port 9477 \
  --command 'new-box --name GraphicsSmoke' \
  --command 'run @last --value "println(renderer)"'
```

This mode:
- opens the target scratch or named document as usual
- creates and launches a box called `renderer`
- runs setup code that creates a `Window`, creates a `Renderer`, and stores both on inherited box properties
- writes an inherited `preamble` onto the parent box so child boxes can compile `renderer` and `window` references
- waits briefly for the render loop to start
- automatically connects later `new-box` boxes in that backdoor session as children of `renderer`

That makes inherited context variables such as `renderer` and `window` valid in child test snippets, which is the preferred setup for testing `afield.four` from the backdoor or from `graphicsContext: true` smoke/docs runners. Prefer scratch when you want that setup to stay disposable.

This is a topology contract. A graphics snippet does not have a usable `Renderer` unless it is running in a box connected beneath the launched renderer parent. For OperationTranscript-driven automation, use `dev_docs/agents/transcripts/graphics_renderer_launch.yaml`, then create the example box and connect `agent_graphics_context -> example_box` before running the example.

After running the renderer transcript, use `wait-native-renderer agent_graphics_context --frames 3` to prove that the native `afield.four` window exists and has advanced a few frames. Do not wait on `println(...)` output as a renderer readiness signal; output routing and render-loop liveness are different systems.

For launched or coroutine-based graphics code, capture context before the async boundary:

```kotlin
import afield.four.Renderer
import afield.four.modules.fromContext

val renderer: Renderer = fromContext()

val renderScene: suspend () -> Unit = {
    // Pass renderer explicitly to helpers called from here.
}
start(renderScene)
```

If you see `must pass in a value for context parameter afield.four.Renderer`, debug the box graph first: confirm the renderer box was launched, its render loop is running, and the example box is a child of it. Do not paper over this with longer sleeps or display/window screenshot fallbacks.

### Documents that already have a window box (do NOT use `--graphics-context`)

`--graphics-context` is only for snippets/documents that lack their own renderer. If the document already contains a **window box** (one that creates its own `Window`/`Renderer` and calls `start(renderer::run)`), do not add `--graphics-context` — you'd get a second, conflicting renderer. Instead just **launch the window box, then the graphics box**:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 \
  --command 'open my_doc' \
  --command 'launch <window-box-uid> --file window.kt' \
  --command 'launch <graphics-box-uid> --file graphics.kt' \
  --command 'sleep 20'
```

Three things that will otherwise waste your time (hard-won):

- **`launch`/`run <uid>` always sends `--value` — empty if you omit it — which overrides the box's stored code.** So a bare `launch <uid>` runs an *empty* box (`codeLength: 0`, no pump, no output). Always pass `--file`/`--value` (or drive stored code via an OperationTranscript `launch` op with no inline code). This is the #1 cause of "I launched it but nothing happened."
- **`launch`, not `run`, gives you a message pump.** The window box's `start(renderer::run)` render loop only keeps ticking while the box stays launched; a one-shot `run` executes the fragment and stops. Confirm the pump with `wait-native-renderer --frames 5` (do not wait on `println` output — render-loop liveness is a different system).
- **Renderers are disposed on document detach** (`SessionInformation.cleanup()` → `disposeBoxRenderer` per box, releasing the native GLFW/Vulkan surface). So (a) keep the backdoor session attached — a trailing `sleep` — for as long as you need frames, and (b) do not `open` a *different* document over a session whose current document has a **live** renderer (e.g. one a human GUI is actively rendering): the detach will tear that renderer down. Devices are per-`Renderer` (each `Renderer` owns its own `VkInstance`/`VkDevice`), so this is per-document teardown, not a shared-device bug.

## Scratch vs Named Documents

Scratch is the default:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --command status
```

Attach to a real document:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --document firstDocument --command snapshot
```

Or explicitly open one from the REPL/batch command list:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json \
  --command 'open firstDocument' \
  --command 'boxes'
```

Rules:
- scratch is for disposable experiments
- `--document <name>` is for a saved workspace document
- **`--document` is resolved relative to the workspace root** (session key = `{workspace}/{document}`). Passing an *absolute* path yields a bogus `.../AFieldWorkspace//abs/path` and loads an empty document, after which box-referencing ops are skipped or CLI `run`/`launch` silently create empty boxes. To reopen a document produced by the transcript `save` op, save it *into* a workspace dir and open it by its relative name (or set `--workspace` to the save location's parent).
- if Field is already running, the backdoor attaches to that process rather than changing its startup options
- `playwright-open` always targets the backdoor's current attached session, so it is the reliable way to get a browser onto a scratch document
- `--with-playwright` opens or reuses Playwright immediately after the backdoor attaches to its current session, and again after any later `open ...` command
- `editor-open <box> --property code` selects a box in the Playwright page and prepares Monaco without taking a screenshot; use it before `run` or `launch` when inline editor visualizers must render into the live editor

## Commands You Will Actually Use

### Create a Box

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --command 'new-box --name Test'
```

### Set Code on a Box

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json \
  --command 'new-box --name Test' \
  --command 'set @last code "println(\"updated\")"'
```

### Connect Boxes

This is the backdoor equivalent of selecting the parent first and pressing `g` in the frontend:

```text
connect <parent-uid> <child-uid> [more-child-uids...]
```

The first uid is the parent. Every remaining uid becomes a child of that parent.

Example:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json \
  --command 'connect parent-box-id child-box-id'
```

To remove the same edge:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json \
  --command 'disconnect parent-box-id child-box-id'
```

### Open Playwright on the Current Session

Use this when you need real DOM state or screenshots from the current backdoor session:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --command 'playwright-open'
```

This command:
- reuses Field's existing Playwright page if one is already open
- otherwise starts the server-owned Playwright browser
- navigates that page onto the current attached session
- works for scratch sessions by attaching with the session key, not by opening a fresh named document
- may take longer the first time on a machine because Playwright can download browser binaries on first use

This is a JVM-side Playwright launch. Do not treat missing Python Playwright as
evidence that this command cannot run.

For agent/test flows, this Playwright browser is headless by default, so it should not steal desktop focus while it collects DOM state or screenshots. If you want to watch the browser while debugging, set `AFIELD_PLAYWRIGHT_VISIBLE=true` or JVM property `-Dafield.playwright.visible=true` before starting Field.

If you know a run will create output areas that you want to inspect or capture later, prefer `--with-playwright` so the browser is attached before the run:

```bash
cat <<'EOF' | uv run --project . --group backdoor python tools/field_backdoor.py --json \
  --with-playwright \
  --command 'new-box --name Visual' \
  --command 'run @last --file -'
display("main", "<div id=\"hello\">hello from DOM</div>")
EOF
```

If you open Playwright only after `run`, existing output-area DOM may not exist in that page yet. `playwright-open` is still useful when you want to defer browser startup, but `--with-playwright` is the default for screenshot and visual-test flows.
The screenshot commands now wait for the Playwright page to be connected and for the target box/output DOM to appear before capture, which makes immediate `run -> screenshot` flows more reliable. That wait does not recreate output areas that were never rendered in that browser, so `--with-playwright` is still the default when the run itself creates the visual state you want to capture.

The JSON result from `playwright-open` includes the effective `url`, whether it created or reused the browser/page, and the `sessionKey`.

Use `page-eval` when you need DOM state from the current Playwright page without relying on screenshots:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --with-playwright \
  --command 'page-eval "document.title"'
```

The expression runs in the page and returns JSON-serializable data under `data.result`. Keep inline expressions tiny. If the JavaScript contains spaces, quotes, braces, or selectors, put it in a file and pass `--file`; otherwise the backdoor command parser and shell quoting become the thing you are debugging.

Use `input-key` when you need a real Playwright keyboard event instead of a
synthetic DOM event. For focused controls, pass a CSS selector so the key is
sent to the intended element:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --with-playwright \
  --command 'input-key press Escape --selector .afield-palette-input --wait-ms 250'
```

For expression mode, the file contents must be one JavaScript expression:

```bash
cat >/tmp/field_probe.js <<'EOF'
Array.from(document.querySelectorAll("iframe")).map((frame) => ({
  src: frame.getAttribute("src"),
  rect: frame.getBoundingClientRect().toJSON?.() || {
    x: frame.getBoundingClientRect().x,
    y: frame.getBoundingClientRect().y,
    width: frame.getBoundingClientRect().width,
    height: frame.getBoundingClientRect().height
  }
}))
EOF

uv run --project . --group backdoor python tools/field_backdoor.py --json --with-playwright \
  --command 'page-eval --file /tmp/field_probe.js'
```

For multi-line statement bodies, pass `--body --file -` and use `return`:

```bash
cat <<'EOF' | uv run --project . --group backdoor python tools/field_backdoor.py --json --with-playwright \
  --command 'page-eval --body --file -'
const widgets = [...document.querySelectorAll(".quick-input-widget")];
return widgets.map(w => ({
  text: w.innerText,
  visible: getComputedStyle(w).visibility !== "hidden"
}));
EOF
```

Return plain JSON-friendly values. DOM nodes are summarized, `undefined` returns `null`, and cyclic objects fail with a serialization error. If you use `--body` and forget `return`, the result will be `null` even if the probe did useful work.

### Wait Between Commands

Use `sleep` when the current scratch session needs a beat before the next command:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json \
  --command 'new-box --name Demo' \
  --command 'run @last --value "display(\"main\", \"<div>hello</div>\")"' \
  --command 'sleep 1.0' \
  --command 'screenshot-box @last /tmp/demo.png'
```

### Capture Screenshots for Docs or Visual Tests

Prefer `--with-playwright` for screenshot flows so the browser is attached before any `run` that creates output areas. Use `playwright-open` manually only when you intentionally want to delay browser startup.

Minimal browser screenshot smoke:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --with-playwright --port 9477 \
  --command 'new-box --name Visual' \
  --command 'run @last --value "display(\"main\", \"<div>hello</div>\")"' \
  --command 'screenshot-box @last /tmp/field-smoke.png'
```

Whole-window capture with the editor open on a specific box:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --with-playwright \
  --command 'screenshot-window /tmp/with-editor.png --uid @last --with-editor --open-outputs'
```

Whole-window capture without the editor:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --with-playwright \
  --command 'screenshot-window /tmp/without-editor.png --uid @last --open-outputs'
```

Editor-only capture for inline Monaco visualizers:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --with-playwright \
  --command 'screenshot-editor @last /tmp/editor.png --padding 10'
```

Clipped capture of just one box, with its output areas opened and a little padding:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --with-playwright \
  --command 'screenshot-box @last /tmp/box.png --padding 16'
```

What each command does:
- `screenshot-window` captures the entire browser viewport.
- `screenshot-window --with-editor` selects the target box, switches Monaco to the requested property, and captures the full window with the editor visible.
- `screenshot-window` without `--with-editor` clears selection and hides Monaco before capture.
- `screenshot-editor` selects the target box, shows Monaco, and saves only the visible editor pane. Use this for inline histogram, history, piano-roll, or other editor decorations.
- For inline Monaco visualizers, the reliable sequence is `editor-open -> run/launch -> optional sleep -> screenshot-editor`.
- `screenshot-box` hides Monaco, opens nested/output disclosures for the target box, computes a padded clip, and saves only that region.
- `screenshot-box --open-outputs` is accepted for symmetry with `screenshot-window`, but box screenshots already open nested/output disclosures internally.
- Use `screenshot-box --no-open-outputs` only when you deliberately want the collapsed box shell.
- `--open-outputs` tells `screenshot-window` to open all output disclosures on the target box before capture.
- `--no-pan` disables the automatic centering step if you want the current viewport as-is.

Important capture habits:
- Use a fresh scratch session or fresh custom port for each final asset when captures overlap on the canvas. Multiple boxes created at the same default position can ghost into later screenshots or affect crop/viewport calculations.
- Do not trust the numeric `clip` alone. Browser screenshots can be device-scale, so a `clip` width of `564` CSS pixels may produce a `1128` pixel-wide PNG. Inspect the actual PNG when visual correctness matters.
- If an output iframe looks empty or too small, probe the parent page, not just the iframe document. Check the iframe rect, its `.disclosure-content` parent rect/style, and `data-afield-intrinsic-width` / `data-afield-intrinsic-height`. A full iframe document can still render as `0 x 0` if its disclosure was measured while closed.
- If a docs example needs output areas, attach Playwright before running it with `--with-playwright`; output DOM that predates the browser page might not be capturable later.

End-to-end example for documentation capture:

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

All three commands return JSON with:
- `ok`
- `path`
- `uid`
- `documentName`
- `scratch`
- `sessionKey`
- `viewport`
- `clip` for `screenshot-box` and `screenshot-editor`

### Record Video Clips

Video recording uses CDP screencast to capture frames from the Playwright page, then encodes them to H.264/MP4 with ffmpeg. The view is prepared the same way as for screenshots (pan, select, open outputs), then frames are captured for the requested duration.

Record the whole window:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --with-playwright \
  --command 'new-box --name AnimBox' \
  --command 'launch @last --value "var t=0.0; loop { t+=0.016; display(\"main\",\"<div style=padding:24px;background:hsl(\${(t*60)%360},70%,60%)>frame \$t</div>\") }"' \
  --command 'sleep 1' \
  --command 'record-window /tmp/anim-window.mp4 --uid @last --open-outputs --duration 5 --fps 30' \
  --command 'stop @last'
```

Record with the editor visible:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --with-playwright \
  --command 'record-window /tmp/with-editor.mp4 --uid @last --with-editor --duration 5'
```

Record only the editor pane:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --with-playwright \
  --command 'record-editor @last /tmp/editor.mp4 --padding 10 --duration 5'
```

Record just a box and its outputs:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --with-playwright \
  --command 'record-box @last /tmp/box.mp4 --padding 16 --duration 3 --fps 30'
```

What each command does:
- `record-window` captures the entire viewport for the requested duration. Same `--uid`, `--with-editor`, `--open-outputs`, `--no-pan` flags as `screenshot-window`.
- `record-editor` captures just the Monaco editor pane for the requested duration.
- `record-box` hides Monaco, opens outputs, computes a padded clip, and records just that region.
- `--duration <seconds>` sets the recording length (default 5).
- `--fps <rate>` sets the target frame rate (default 30). The actual rate depends on CDP screencast throughput.
- Output is H.264/MP4 with `faststart` for web-ready playback. Requires `ffmpeg`; Field resolves `AFIELD_FFMPEG`, `AFIELD_FFMPEG_PATH`, `FFMPEG`, common platform paths, then `PATH`.

All three commands return JSON with:
- `ok`, `path`, `uid`, `documentName`, `scratch`, `sessionKey`
- `durationSec`, `frameCount`
- `clip` for `record-box` and `record-editor`

### Run a Box

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json \
  --command 'new-box --name Test' \
  --command 'run @last --value "println(\"run output\")"'
```

`run` waits for the execution strand to finish and returns the events produced during that command.

> Note: `run`/`launch <uid>` always **send** the `--value` (empty if you omit it), which overrides the box's stored code — you'll see `codeLength: 0` and no output. To run a box's *stored* code (e.g. after loading a saved document), use a transcript `execute`/`launch` op with no inline `code:` (the stored-code fallback), not bare CLI `run`. See [`OPERATION_TRANSCRIPT.md`](./OPERATION_TRANSCRIPT.md#execute-vs-launch).

### Launch a Box

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json \
  --command 'new-box --name Test' \
  --command 'launch @last --value "while(true) { Thread.sleep(1000) }" --timeout 5'
```

`launch` waits until the box appears to be running or times out.

### Stop a Box

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json \
  --command 'stop @last --timeout 5'
```

### Read Captured Output

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json \
  --command 'new-box --name Test' \
  --command 'run @last --value "println(\"hi\")"' \
  --command 'output @last --kind all'
```

Use `output` when you want the accumulated output buffer for a box. For the immediate result of `run` or `launch`, inspect the `events` array returned by that command.

## How to Read Results

In `--json` mode, each command prints one object:

```json
{
  "ok": true,
  "command": "run @last --value \"println(\\\"hello\\\")\"",
  "data": { "...": "..." },
  "events": [ "...message events..." ],
  "timedOut": false
}
```

Read it like this:
- `ok`: overall command success
- `data`: command-specific result
- `events`: the websocket events caused by that command
- `timedOut`: command-specific timeout flag

For code execution, the important evidence is usually in `events`.
For `run-jvm-main`, the important evidence is usually `data.exitCode`, `data.stdout`, `data.stderr`, and `data.durationMs`; the full Java command/classpath is hidden unless the command includes `--include-command`.

## Logs

Every run writes a unique JSONL log:

```text
.field-backdoor/logs/<session-id>.jsonl
```

If the backdoor launched Field itself, it also writes:

```text
.field-backdoor/logs/<session-id>.managed-server.log
```

Always check `status` first if you need the exact paths:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --command status
```

The session log records:
- arguments
- health checks
- server spawn/stop
- websocket send/receive traffic
- command dispatch and results
- exceptions and tracebacks

The logs are disposable. If `.field-backdoor/logs/` grows large enough to threaten the build machine, it is safe to remove old session logs and rerun the failing command.

## Rules of Thumb for Agents

- Prefer `tools/field_backdoor.py` when you need to prove runtime behavior.
- Prefer `--json` in automation.
- Prefer `--file -` or `--file <path>` over `--value` for larger code blocks.
- Prefer scratch sessions unless you specifically need a saved document.
- Read `events`, not just `data`, when checking output or errors.
- Check `.field-backdoor/logs/` when behavior is confusing or a command fails.
- Avoid `output @last` on very noisy live visualizers unless you actually need the full accumulated payload; `events` plus screenshots are usually the better signal.
