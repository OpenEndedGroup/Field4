# Field Backdoor for Agents

`tools/field_backdoor.py` is the preferred way for agents to run Field code and inspect the result without using the browser UI.

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

## Minimum Hello World

Run this from the repo root:

```bash
python3 tools/field_backdoor.py --json \
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
python3 tools/field_backdoor.py --json --command status
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
python3 tools/run_field_tests.py --refresh-catalog
```

That regenerates `build/field-tests/catalog.json` and then invokes `python3 tools/field_backdoor.py --json` once per test.
For the phase-1 `jvm-main` entries, the backdoor command it uses is `run-jvm-main`, which asks the running Field JVM to execute the annotated entrypoint and return `exitCode`, `stdout`, and `stderr`.

For docs work, treat the smoke suite and the MkDocs runner as one loop:

```bash
python3 tools/run_field_tests.py --refresh-catalog
cd public && FIELD_DOCS_RUNNER_MODE=always mkdocs build
```

When you only need to rebuild the HTML around already-generated captures, use:

```bash
cd public && FIELD_DOCS_RUNNER_MODE=never mkdocs build
```

That skips all screenshot/video execution and uses whatever assets already exist on disk.

If either step exposes a compile error, runtime error, empty capture, or obviously broken screenshot/video, fix the underlying code or documentation and rerun.
If a timed-out snippet leaves later probes stuck before they emit `is-running`, restart the managed Field JVM and retry the repro once from a clean server before chasing secondary failures.

## Interactive Mode

Start the REPL:

```bash
python3 tools/field_backdoor.py
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
python3 tools/field_backdoor.py --json \
  --command 'new-box --name Demo' \
  --command 'set @last code "println(\"one\")"' \
  --command 'run @last'
```

Each `--command` runs in order. `@last` means “the most recently created box in this session”.

## Prefer `--file -` or `--file` for Larger Snippets

For agents, `--file -` is usually the best option because it avoids shell escaping completely.

Read code from stdin with a heredoc:

```bash
cat <<'EOF' | python3 tools/field_backdoor.py --json \
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

python3 tools/field_backdoor.py --json \
  --command 'new-box --name FileDemo' \
  --command 'run @last --file /tmp/demo.kt'
```

This avoids shell-escaping problems.

## Graphics Context Mode

Use `--graphics-context` when the snippet needs a live `afield.four.Renderer` inherited from a parent box:

```bash
python3 tools/field_backdoor.py --json --graphics-context --port 9477 \
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

## Scratch vs Named Documents

Scratch is the default:

```bash
python3 tools/field_backdoor.py --json --command status
```

Attach to a real document:

```bash
python3 tools/field_backdoor.py --json --document firstDocument --command snapshot
```

Or explicitly open one from the REPL/batch command list:

```bash
python3 tools/field_backdoor.py --json \
  --command 'open firstDocument' \
  --command 'boxes'
```

Rules:
- scratch is for disposable experiments
- `--document <name>` is for a saved workspace document
- if Field is already running, the backdoor attaches to that process rather than changing its startup options
- `playwright-open` always targets the backdoor's current attached session, so it is the reliable way to get a browser onto a scratch document
- `--with-playwright` opens or reuses Playwright immediately after the backdoor attaches to its current session, and again after any later `open ...` command
- `editor-open <box> --property code` selects a box in the Playwright page and prepares Monaco without taking a screenshot; use it before `run` or `launch` when inline editor visualizers must render into the live editor

## Commands You Will Actually Use

### Create a Box

```bash
python3 tools/field_backdoor.py --json --command 'new-box --name Test'
```

### Set Code on a Box

```bash
python3 tools/field_backdoor.py --json \
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
python3 tools/field_backdoor.py --json \
  --command 'connect parent-box-id child-box-id'
```

To remove the same edge:

```bash
python3 tools/field_backdoor.py --json \
  --command 'disconnect parent-box-id child-box-id'
```

### Open Playwright on the Current Session

Use this when you need real DOM state or screenshots from the current backdoor session:

```bash
python3 tools/field_backdoor.py --json --command 'playwright-open'
```

This command:
- reuses Field's existing Playwright page if one is already open
- otherwise starts the server-owned Playwright browser
- navigates that page onto the current attached session
- works for scratch sessions by attaching with the session key, not by opening a fresh named document
- may take longer the first time on a machine because Playwright can download browser binaries on first use

For agent/test flows, this Playwright browser is headless by default, so it should not steal desktop focus while it collects DOM state or screenshots. If you want to watch the browser while debugging, set `AFIELD_PLAYWRIGHT_VISIBLE=true` or JVM property `-Dafield.playwright.visible=true` before starting Field.

If you know a run will create output areas that you want to inspect or capture later, prefer `--with-playwright` so the browser is attached before the run:

```bash
cat <<'EOF' | python3 tools/field_backdoor.py --json \
  --with-playwright \
  --command 'new-box --name Visual' \
  --command 'run @last --file -'
display("main", "<div id=\"hello\">hello from DOM</div>")
EOF
```

If you open Playwright only after `run`, existing output-area DOM may not exist in that page yet. `playwright-open` is still useful when you want to defer browser startup, but `--with-playwright` is the default for screenshot and visual-test flows.
The screenshot commands now wait for the Playwright page to be connected and for the target box/output DOM to appear before capture, which makes immediate `run -> screenshot` flows more reliable. That wait does not recreate output areas that were never rendered in that browser, so `--with-playwright` is still the default when the run itself creates the visual state you want to capture.

The JSON result from `playwright-open` includes the effective `url`, whether it created or reused the browser/page, and the `sessionKey`.

### Wait Between Commands

Use `sleep` when the current scratch session needs a beat before the next command:

```bash
python3 tools/field_backdoor.py --json \
  --command 'new-box --name Demo' \
  --command 'run @last --value "display(\"main\", \"<div>hello</div>\")"' \
  --command 'sleep 1.0' \
  --command 'screenshot-box @last /tmp/demo.png'
```

### Capture Screenshots for Docs or Visual Tests

Prefer `--with-playwright` for screenshot flows so the browser is attached before any `run` that creates output areas. Use `playwright-open` manually only when you intentionally want to delay browser startup.

Whole-window capture with the editor open on a specific box:

```bash
python3 tools/field_backdoor.py --json --with-playwright \
  --command 'screenshot-window /tmp/with-editor.png --uid @last --with-editor --open-outputs'
```

Whole-window capture without the editor:

```bash
python3 tools/field_backdoor.py --json --with-playwright \
  --command 'screenshot-window /tmp/without-editor.png --uid @last --open-outputs'
```

Editor-only capture for inline Monaco visualizers:

```bash
python3 tools/field_backdoor.py --json --with-playwright \
  --command 'screenshot-editor @last /tmp/editor.png --padding 10'
```

Clipped capture of just one box, with its output areas opened and a little padding:

```bash
python3 tools/field_backdoor.py --json --with-playwright \
  --command 'screenshot-box @last /tmp/box.png --padding 16'
```

What each command does:
- `screenshot-window` captures the entire browser viewport.
- `screenshot-window --with-editor` selects the target box, switches Monaco to the requested property, and captures the full window with the editor visible.
- `screenshot-window` without `--with-editor` clears selection and hides Monaco before capture.
- `screenshot-editor` selects the target box, shows Monaco, and saves only the visible editor pane. Use this for inline histogram, history, piano-roll, or other editor decorations.
- For inline Monaco visualizers, the reliable sequence is `editor-open -> run/launch -> optional sleep -> screenshot-editor`.
- `screenshot-box` hides Monaco, opens nested/output disclosures for the target box, computes a padded clip, and saves only that region.
- `--open-outputs` tells `screenshot-window` to open all output disclosures on the target box before capture.
- `--no-pan` disables the automatic centering step if you want the current viewport as-is.

End-to-end example for documentation capture:

```bash
cat <<'EOF' | python3 tools/field_backdoor.py --json \
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
python3 tools/field_backdoor.py --json --with-playwright \
  --command 'new-box --name AnimBox' \
  --command 'launch @last --value "var t=0.0; loop { t+=0.016; display(\"main\",\"<div style=padding:24px;background:hsl(\${(t*60)%360},70%,60%)>frame \$t</div>\") }"' \
  --command 'sleep 1' \
  --command 'record-window /tmp/anim-window.mp4 --uid @last --open-outputs --duration 5 --fps 30' \
  --command 'stop @last'
```

Record with the editor visible:

```bash
python3 tools/field_backdoor.py --json --with-playwright \
  --command 'record-window /tmp/with-editor.mp4 --uid @last --with-editor --duration 5'
```

Record only the editor pane:

```bash
python3 tools/field_backdoor.py --json --with-playwright \
  --command 'record-editor @last /tmp/editor.mp4 --padding 10 --duration 5'
```

Record just a box and its outputs:

```bash
python3 tools/field_backdoor.py --json --with-playwright \
  --command 'record-box @last /tmp/box.mp4 --padding 16 --duration 3 --fps 30'
```

What each command does:
- `record-window` captures the entire viewport for the requested duration. Same `--uid`, `--with-editor`, `--open-outputs`, `--no-pan` flags as `screenshot-window`.
- `record-editor` captures just the Monaco editor pane for the requested duration.
- `record-box` hides Monaco, opens outputs, computes a padded clip, and records just that region.
- `--duration <seconds>` sets the recording length (default 5).
- `--fps <rate>` sets the target frame rate (default 30). The actual rate depends on CDP screencast throughput.
- Output is H.264/MP4 with `faststart` for web-ready playback. Requires `ffmpeg` installed at `/opt/homebrew/bin/ffmpeg`.

All three commands return JSON with:
- `ok`, `path`, `uid`, `documentName`, `scratch`, `sessionKey`
- `durationSec`, `frameCount`
- `clip` for `record-box` and `record-editor`

### Run a Box

```bash
python3 tools/field_backdoor.py --json \
  --command 'new-box --name Test' \
  --command 'run @last --value "println(\"run output\")"'
```

`run` waits for the execution strand to finish and returns the events produced during that command.

### Launch a Box

```bash
python3 tools/field_backdoor.py --json \
  --command 'new-box --name Test' \
  --command 'launch @last --value "while(true) { Thread.sleep(1000) }" --timeout 5'
```

`launch` waits until the box appears to be running or times out.

### Stop a Box

```bash
python3 tools/field_backdoor.py --json \
  --command 'stop @last --timeout 5'
```

### Read Captured Output

```bash
python3 tools/field_backdoor.py --json \
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
python3 tools/field_backdoor.py --json --command status
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
