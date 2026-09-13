# AField / Field·4 — Repo Guide for Agents

This repository builds **Field·4**, a Kotlin-based live-coding environment for graphics + exploratory data visualization:
- **Backend (JVM/Kotlin)**: a Ktor HTTPS + WebSocket server (`afield.Server`) that manages documents, runs user code, and (optionally) drives Vulkan/OpenXR rendering via `afield.four`.
- **Frontend (Kotlin/JS)**: a browser UI (canvas + Monaco editor) that talks to the server over a small JSON RPC protocol defined in `src/commonMain`.
- **Persistence (.edn)**: documents live on disk as `metadata.edn` plus optional sidecar files for large text properties (Kotlin, GLSL, JS, YAML).

---

## Quickstart (Dev)

- Run Field (builds JS + runs JVM server): `./field.sh` (wraps `./gradlew runJvm`)
  - `field.sh` decides whether to run in the foreground or open a detached GUI terminal (for a
    Finder/Explorer double-click) by checking `[ -t 0 ]` — no tty on stdin. A coding agent's shell
    tool also has no tty, so it looks the same; `field.sh` senses common agent env vars
    (`CLAUDECODE`, `CLAUDE_CODE_ENTRYPOINT`, `CODEX_SANDBOX*`) and runs in the foreground for those.
    That sensing is best-effort — if it ever misses your agent and pops a terminal window instead,
    set `AFIELD_FIELD_SH_NO_TERMINAL=1` explicitly.
- Open in browser: `https://localhost:9092/index.html?file=firstDocument`
- Change workspace directory (documents root):
  - CLI arg parsed by `option(...)`: `--workspace <path>`
  - Example: `./gradlew runJvm --args="--workspace /tmp/AFieldWorkspace"`
  - Through the launcher: `./field.sh /tmp/AFieldWorkspace` (or `-w`/`--workspace`). The launcher
    resolves a relative path against your shell's cwd rather than the checkout, refuses a
    non-existent directory before spending a build on it, and takes a path to a document (a folder
    holding a `metadata.edn`, or the file itself) as "the workspace above this, and open that
    document". `./field.sh --print-workspace` answers "which one would I get?" without launching
    anything.
  - With no argument the workspace is whatever was used last, recorded machine-wide in
    `~/.afield/last-workspace.txt` — so a sibling clone or worktree may have been what last wrote
    it. `field.sh` prints the workspace it resolved, and where that came from, before it builds.
- Open a document directly, instead of the welcome page and instead of whatever was open last:
  - CLI arg parsed by `option(...)`: `--document <name>`, resolved against `--workspace`; a path
    is reduced to its last segment, so both spellings agree. `/app-url` — the route the launcher
    curls for the Electron window's URL — prefers it over both the welcome page and the
    last-opened document.
  - Through the launcher: `./field.sh -d <name>`, or `--document`/`--document=`. A path works too
    (`./field.sh -d <...>/myDoc`, or as the bare argument), and then names the workspace as well.
    The launcher rejects a document that does not exist, which matters more than it does for a
    workspace: Field *creates* an unknown document, so the alternative to an error is an empty new
    document under the typo'd name.
  - `field.ps1` accepts the same flags on Windows; that parser has never been run (see the note in
    `dev_docs/launcher_workspace_verification.md`).
- Ports and HTTPS:
  - `PORT` env var overrides default `9092`
  - Server uses HTTPS by default (self-signed dev cert)
  - For local automation, Field also exposes a loopback-only HTTP mirror at `AFIELD_HTTP_PORT` if set, otherwise `PORT + 10000` when that fits in the valid port range. Example: `PORT=9477` gives `http://localhost:19477/...`.
  - Set `AFIELD_HTTP_PORT=off` to disable the HTTP mirror, or set `AFIELD_HTTP_PORT=<port>` to choose an explicit loopback HTTP port.
  - Keystore env vars: `AFIELD_KEYSTORE_PATH`, `AFIELD_KEYSTORE_PASS`, `AFIELD_KEYSTORE_ALIAS`
  - Generate dev keystore: `./gradlew generateLocalKeystore` (writes `config/field4-local.p12`)

### Python / uv recipes for agents

Use `uv` for agent-facing Python tooling so the docs runner and the backdoor share the same dependency environment. This matters because `public/docs_hooks.py` runs `tools/field_backdoor.py` with the same Python interpreter that is building the docs.

Do not use ambient `python`, `pip`, or a Python `playwright` import as the gate for browser screenshots. Field's screenshot and UI-regression path uses the repo `uv` environment plus the JVM/server-owned Playwright instance. The root `pyproject.toml` intentionally does not need the Python Playwright package for `tools/field_backdoor.py --with-playwright`, `tools/run_ui_regressions.py`, or docs captures.

As a safety net, the agent-facing entrypoints (`tools/field_backdoor.py`, `tools/field_session.py`, `tools/run_field_tests.py`, and `tools/field_mcp.py`) explicitly self-heal the interpreter before importing their optional dependencies: if the invoking Python lacks them, the entrypoint transparently re-execs under the repo `.venv` (or `uv run`). Importing `field_backdoor` as a library never restarts the importing process. So a bare `python tools/field_backdoor.py ...` no longer dies on a missing dependency. Running through `uv run --group backdoor` is still preferred and avoids the re-exec hop.

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
FIELD_DOCS_RUNNER_MODE=incremental FIELD_DOCS_RUNNER_PORT=9477 \
  uv run --project . --group docs python tools/build_zensical_docs.py --skip-build
cd public && uv run --project .. --group docs zensical serve -f zensical.generated.yml
```

Docs/test intent matrix:

The consolidated testing guide is `dev_docs/testing/TESTING_STRATEGY.md`; use
the matrix below as the quick command crib.

| Situation | Command | Intent |
| --- | --- | --- |
| Backdoor sanity check | `uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 --command status` | Verify the agent can start or attach to an isolated Field. |
| Complete source-warning audit | `./gradlew --no-daemon --rerun-tasks warningGates` | Force all root source passes plus the standalone default-completion backend into one warning census; the task explicitly excludes cached Kotlin DSL script diagnostics. |
| Smoke catalog | `uv run --project . --group backdoor python tools/run_field_tests.py --refresh-catalog` | Exercise annotated Field examples through the backdoor. |
| Browser UI regressions | `uv run --project . --group backdoor python tools/run_ui_regressions.py --port 9490 --timeout 45` | Run Playwright-backed UI workflows and retain screenshots under `build/ui-regressions/artifacts/`. |
| One browser screenshot smoke | `uv run --project . --group backdoor python tools/field_backdoor.py --json --with-playwright --port 9477 --command 'new-box --name Visual' --command 'run @last --value "display(\"main\", \"<div>hello</div>\")"' --command 'screenshot-box @last /tmp/field-smoke.png'` | Capture a real browser screenshot without relying on Python Playwright. |
| Docs native prerequisite preparation | `./gradlew buildDocsNativePrerequisites` | Build Ceres and TetGen, check Manifold and JEP, and reuse checksummed downloads across worktrees and `clean`. |
| Docs HTML only | `FIELD_DOCS_RUNNER_MODE=never uv run --project . --group docs python tools/build_zensical_docs.py --clean` | Rebuild Zensical docs without starting Field or recapturing assets. |
| Docs author loop | `FIELD_DOCS_RUNNER_MODE=incremental FIELD_DOCS_RUNNER_PORT=9477 uv run --project . --group docs python tools/build_zensical_docs.py` | Reuse valid captures and run only changed or missing ones. |
| Runner/API change | `FIELD_DOCS_RUNNER_MODE=strict FIELD_DOCS_RUNNER_PORT=9477 uv run --project . --group docs python tools/build_zensical_docs.py` | Invalidate captures when runner code or transcript behavior changes. |
| Full recapture | `FIELD_DOCS_RUNNER_MODE=always FIELD_DOCS_RUNNER_PORT=9477 uv run --project . --group docs python tools/build_zensical_docs.py --clean` | Regenerate every screenshot and video through Field. |
| Offline asset ownership | `uv run python tools/check_offline_assets.py` | Verify canonical Monaco/font/offline assets exist and generated copies are not tracked. |

Run these verification entrypoints one at a time in a checkout. The smoke, UI,
and docs wrappers serialize themselves with `.field-backdoor/verification.lock`
and print the current owner while waiting. Do not run raw `./gradlew` compile/test
tasks concurrently with them: raw Gradle does not take that Python lock, and two
builds can corrupt shared Kotlin class output and incremental-compiler backups.

### Gradle hygiene for agents

- On Windows, agents should use the checked-in Gradle wrapper directly for compile checks:

```powershell
.\gradlew compileKotlinJvm
```

- Use the same wrapper form for other Gradle tasks, replacing `compileKotlinJvm` with the task name, for example `.\gradlew runJvm`.
- Never use machine-wide `killall` as Gradle cleanup; it destroys unrelated IDE, Field, and sibling-worktree processes.
- On Unix-like systems, use `./gradlew compileKotlinJvm` unless a task-specific note says otherwise.
- A failed or stalled Gradle invocation is a failed verification. Record its first concrete error and inspect the owning daemon/lock before changing process state. `./gradlew --stop` is the checkout-aware daemon cleanup; only run another verification after a specific repair or environment change.
- Use an isolated `GRADLE_USER_HOME` / `--project-cache-dir` only as a fallback when there is a confirmed cache-lock conflict that cannot be cleared without disrupting the user's running work. The isolated path is expected to be much slower because it loses the warm project caches.

## Field Backdoor (Preferred for Agents)

- When you need to verify Field behavior by actually running code, prefer `uv run --project . --group backdoor python tools/field_backdoor.py` over driving the browser manually.
- When you need screenshots or DOM inspection, use `--with-playwright` or `tools/run_ui_regressions.py` through `uv run --project . --group backdoor`. Do not skip this because the current shell's Python environment lacks the `playwright` package; browser automation is provided by Field's JVM Playwright dependency and controlled through the backdoor.
- When the thing you want to verify is a JVM entrypoint or smoke-style example, prefer the backdoor's `run-jvm-main` command (or `tools/run_field_tests.py` for `FIELD_TEST` entrypoints) over ad hoc `gradlew` `JavaExec`, temporary init scripts, or custom classpath launchers. Use transient scratch boxes for snippet-level checks and screenshots. Both paths apply the required JVM args (`requiredAgentJvmArgs()`: module opens, native-access/Unsafe declarations, first-thread handling, vector module, and LWJGL stack size) for you — a hand-rolled `java -cp … afield.AgentJvmMainLauncher …` line will silently miss one and fail confusingly (e.g. `module java.base does not "opens java.net"`).
- The backdoor attaches to an existing Field at `https://localhost:9092` or starts Field itself using the Gradle wrapper command above, opens a scratch document by default, and lets you create boxes, set code/shaders, run or launch them, stop them, and capture outputs/errors as JSON.
- For agent automation, do not rely on the default port. Pass a custom high port such as `--port 9477` so the backdoor starts or reuses an isolated agent-owned Field instead of silently attaching to a stale dev server on `9092`.
- Reuse the same custom port for one verification session, and pick a new custom port when you want a guaranteed fresh Field JVM. A scratch session lasts for one `field_backdoor.py` process, not for the lifetime of the port: put dependent commands in one invocation. A later process on the same port opens a different scratch document and cannot reuse earlier box uids. Use `--document <name>` when automation state must span invocations. When this session **starts** the server, pass `--workspace <parent-directory>` with the relative `--document <name>`: the workspace becomes that server's, and `--document` is resolved relative to it. When you **attach** to a server you did not start (`--attach-existing`), the workspace is already fixed and `--workspace` cannot change it — pass the server's own workspace as reported by `serverWorkspace`, or omit `--workspace` and make `--document` relative to it. A disagreeing `--workspace` is now a hard error rather than silently ignored. This avoids burning time and tokens debugging old code loaded in some unrelated existing server.
- When using an external browser or Playwright connector directly, prefer Field's loopback HTTP mirror instead of the self-signed HTTPS URL. For a backdoor session on `--port 9477`, navigate external tools to `http://localhost:19477/index.html?file=<document>`. The backdoor's own HTTP/WebSocket client and server-owned Playwright already handle the self-signed HTTPS certificate.
- Use `--json` for agent automation. The result object includes `ok`, `data`, `events`, and `timedOut`.
- Use scratch sessions for disposable experiments. When attaching to a real saved document in the user's already-running Field, begin with `--attach-existing --document <name> --command status` and check the **four fields that can disagree with you**: `serverWorkspace` (read back from the server, `null` if the server is too old to report it — then you have not verified anything), `documentName` (also read back from the server, not your argument), `sessionKey` (the absolute path the server will actually write, so `serverWorkspace` + `/` + the document name), and a plausible `boxCount`. Do **not** treat `requestedWorkspace` as verification: it is your own `--workspace` argument echoed back, it is `null` when you passed none, and until 2026-09-12 it was the field called `workspace` that this list told you to verify — which is why an agent could write five documents into a different checkout while believing it had checked. `--attach-existing` bypasses only source freshness: it still requires the same checkout and refuses to start a missing server. Full contract in [`dev_docs/agents/AGENT_SURFACE_TRUTHFULNESS.md`](dev_docs/agents/AGENT_SURFACE_TRUTHFULNESS.md).
- Every run writes a unique log file under `.field-backdoor/logs/`. `status` reports both the session log and the managed server log path.
- Session logs are debugging artifacts, not durable state. They now store truncated previews of very large websocket payloads, and it is safe to clear `.field-backdoor/logs/` if runaway visualizer output fills the disk during automation.
- `run-jvm-main` omits the full launched JVM command/classpath from JSON output by default to keep agent context small. Add `--include-command` only when debugging launch/classpath construction.
- To inspect a **live variable's value at runtime**, use the MCP tool `field_debug_probe` (see [`dev_docs/agents/FIELD_MCP.md#runtime-debug-probe`](dev_docs/agents/FIELD_MCP.md#runtime-debug-probe)). It installs a JDWP event-thread breakpoint and samples a symbol at a `src/` file+line (source mode) or in a running box (box mode) — e.g. `{"file":"Server.kt","line":1758,"symbol":"now"}`. Only the hitting thread is paused while JDI reads the value, and it is resumed immediately; high-hit probes can still perturb that thread. Backdoor-launched Field enables the required JDWP port (5005) automatically; a bare `./gradlew runJvm` does not, and the probe says so with an actionable error.
- For semantic document automation, prefer `OperationTranscript` macro YAML over one-off command chains. Reusable agent examples live in `dev_docs/agents/transcripts/`; write them into the current document with `macro write <name> --file <path>`, then run them with `macro run <name>`.
- For `afield.four` graphics automation, treat renderer access as graph topology. Run `dev_docs/agents/transcripts/graphics_renderer_launch.yaml`, wait with `wait-native-renderer agent_graphics_context --frames 3`, create the example box, connect `agent_graphics_context -> example_box`, then run the example. If code starts coroutine work with `start(...)`, resolve `val renderer: Renderer = fromContext()` before `start(...)` and pass it into async helpers explicitly.
- **Before writing `afield.four` graphics code, read the worked lessons.** `public/docs/assets/canonical_graphics/` is a snapshot of a real Field document holding ~33 lessons that run against a live renderer — cameras, compute shaders and compute particles, texturing, glTF, render-to-texture, ping-pong buffers, blur kernels, shader `#include` and Lygia, printf and texture-probe debugging, a GPU radix sort, Slang, and a DLA system. Every file there is named by box uid, so start from [`public/docs/assets/canonical_graphics/LESSONS.md`](public/docs/assets/canonical_graphics/LESSONS.md): it maps each lesson to its Kotlin and shader files, records the parent chain the lesson needs in order to run, and carries a reverse index from Field API symbol (`RenderToTexture`, `LoadedTexture`, `computeModuleHere`, `GpuRadixSort`, …) to the lessons that use it. Prefer adapting a lesson over inventing a call sequence. The prose walkthrough with captured images is [`public/docs/afield_four_graphics.md`](public/docs/afield_four_graphics.md).

Minimum hello world:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 \
  --command 'new-box --name Hello' \
  --command 'run @last --value "println(\"hello world\")"'
```

Recommended agent workflow:

1. Pick a custom high port and start with `uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 --command status` to see whether it started Field, whether `managedServer` is true, and where the logs are.
2. If a port is owned by an unexpected, foreign, or source-stale server, stop that verification and choose a dedicated free port. The backdoor now rejects unsafe reuse instead of silently attaching.
3. For quick execution checks, stay in scratch mode and put the dependent `new-box`, `connect`, `disconnect`, `set`, `run`, `launch`, `stop`, and `output` commands in one `field_backdoor.py` invocation.
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
  --command 'record-window /tmp/anim-window.mp4 --uid @last --open-outputs --duration 5 --fps 30 --frame-synchronous --restart-target' \
  --command 'record-box @last /tmp/anim-box.mp4 --duration 3 --fps 30 --frame-synchronous --restart-target' \
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
- Add `--frame-synchronous` to capture exactly `duration × fps` consecutive Field frames. The recorder
  waits for each browser paint and screenshot before Field advances again, so capture speed cannot change
  the movie's state sequence.
- Add `--restart-target` with frame-synchronous capture to relaunch the selected box after the recorder is
  installed. This starts seeded animations at frame zero; docs video fences use both flags automatically.
- Without `--frame-synchronous`, recording remains real-time for ad-hoc clips of ambient or interactive UI.
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
Do not pass `--timeout` to `tools/run_field_tests.py`; per-test timeouts come from `timeoutSec` in the `FIELD_TEST` annotation. The backdoor and UI regression runner have their own `--timeout` flags, but the smoke catalog runner does not.
The smoke runner defaults to dedicated port `9477` (not developer port `9092`),
prints per-test heartbeats, applies an outer process watchdog, and kills the whole
child process group if a completed backdoor command refuses to exit. Use another
high `--port` only when you need a separate automation session.
Unfiltered runs persist completed verdicts in
`build/field-tests/coverage-state.json` and schedule unseen, then
least-recently-completed tests first. This keeps successive partial runs moving
through the catalog instead of repeatedly covering the same prefix. Targeted
`--id`/`--tag`/`--runner` runs keep catalog order; `--catalog-order` opts an
unfiltered diagnostic run out of coverage ordering.
The smoke runner **prewarms one persistent Field** on `--port` for the whole run and tears it down once at the end, so tests do not cold-boot the JVM per test. It never reuses a prior run's server: a same-checkout server is recycled for a clean boot, while a foreign or unidentifiable server makes the run fail with an instruction to choose another port. `--no-prewarm` is diagnostic only and gives up that clean-run guarantee.

To iterate on one or a few tests instead of the whole catalog, filter by `--id <catalog-id>` (repeatable; the `id:` from a `FIELD_TEST` block, e.g. `rectsl.language.workout`), `--tag smoke`, or `--runner jvm-main`. This is the correct way to verify a single `jvm-main` smoke — do **not** reach for a raw `java`/`gradlew JavaExec` launcher (see the JVM-args note under *Field Backdoor* above).

Prewarm launches the full Field build, including the JS bundle. A `:compileDevelopmentExecutableKotlinJs` internal compiler error is a build failure, not a skippable transient; keep its log. If inspection proves the owning Kotlin/Gradle daemon stale, `./gradlew --stop` is the repair, and the next invocation is a new verification after that state change.

Browser UI regression runner:

```bash
uv run --project . --group backdoor python tools/run_ui_regressions.py --port 9490 --timeout 45
```

This runs browser-level regressions through `tools/field_backdoor.py` and the server-owned Playwright browser. It prints `RUN`, heartbeat, `PASS`, and `FAIL` lines by default; use `--heartbeat-interval <seconds>` to tune long-run telemetry and `--json` when machine-readable stdout matters. Screenshots are retained under `build/ui-regressions/artifacts/<test-id>/`, with `latest-pass.png`, `latest-failure.png`, and the most recent 5 passing screenshots in `passes/`. A passing screenshot must show meaningful evidence of the tested state; transient palettes, menus, toasts, and panels should still be visible in the final or a supplemental capture.

Target one regression while iterating:

```bash
uv run --project . --group backdoor python tools/run_ui_regressions.py \
  --id macro-transcript-panel --port 9680 --timeout 45
```

Current testing layers and commands are summarized in `dev_docs/testing/TESTING_STRATEGY.md`. Browser UI coverage details are tracked in `dev_docs/testing/UI_REGRESSION_ROADMAP.md`: command palette, editor sync, insert stack mark, output areas, `/controllers` lines/control widgets, screenshot selection, history panel, macro/transcript panel, inline visualizers, and focus/keyboard contracts.

Deliberately accepted JVM reds live only in
`dev_docs/testing/expected_test_failures.json`; accepted smoke reds live only in
`dev_docs/testing/expected_smoke_failures.json`. Both registers are enforced in both
directions: an unregistered failure is ordinary failure, while a registered test passing or
disappearing fails its gate. Never describe a red as expected only in prose, and never use wildcard
selectors or ids in either register.

## Autonomous Docs/Test Loop

- Agents are expected to make code changes, API changes, documentation fixes, and test-runner fixes autonomously when the docs/test pipeline exposes a real problem.
- Run the smallest relevant gate once, inspect its first concrete failure, and make a specific repair. Re-run the affected gate after the change, then finish with one full verification pass. An unchanged retry is diagnostic comparison only and never evidence that the system is green.
- Use `FIELD_DOCS_RUNNER_MODE=incremental` for fast local iteration against an already-clean cache.
- Use `FIELD_DOCS_RUNNER_MODE=strict` when runner logic changes should invalidate cached captures through `public/docs_hooks.py` digest changes.
- Use `FIELD_DOCS_RUNNER_MODE=always` when you need every screenshot/video to be regenerated regardless of cache state.
- Use `FIELD_DOCS_RUNNER_MODE=never` when you want the docs build to skip all capture execution entirely and just use whatever screenshot/video files already exist on disk.
- The docs runner reuses a server only when its identity proves the same checkout and no runtime source changed after it started. A foreign, old, or stale server fails closed; use a dedicated high `FIELD_DOCS_RUNNER_PORT` rather than taking over another run's process.
- When docs screenshots show the red Field error marker or empty/incorrect output, treat that as a real failure: capture the compiler/runtime error from the backdoor/test runner, fix the snippet or runtime, and rerun the docs page.
- If a backdoor or smoke-suite run times out and later probes stop reaching `is-running`, restart the managed Field JVM before trusting subsequent failures; a wedged snippet can poison later manual debugging runs.
- **A capture exercises the real code, or it does not exist.** Every screenshot/video/example in the docs pipeline is a test that drives the real Field path and embeds the real result. **Never fake, synthesize, hardcode, mock, or hand-edit a capture** to make it "look right" — no CPU stand-in for a GPU render, no hand-written string for a value the code should compute, no scripted placeholder for a live panel. If the real capture won't come out, the only acceptable moves are: (1) fix the code, (2) fix the capture system (hook/`drive` script/framing/timing), or (3) omit the capture and say why. An honest gap beats a convincing fake — a faked capture stops testing anything, rots silently, and misrepresents the product. If a completion/panel can't be captured reliably, that is a real defect in the feature or the capture system; fix one or both. (Want a one-off picture for yourself? Take a screenshot by hand — it never enters the pipeline.) See `dev_docs/DOCS_RUNNER_GPU_CAPTURES.md` → "Principle".

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
  - Supporting docs: `dev_docs/SLIDESHOW_FEATURE.md`, `dev_docs/SLIDESHOW_ANNOTATIONS.md`, `dev_docs/INTERACTIVE_GUIDES.md`
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

**To write box-level graphics code, read the worked lessons before the engine source.** The
`afield.four` API is designed for live coding, so the box-facing surface (`moduleHere`, `Mesh`,
`ComputeMesh`, `RenderToTexture`, `fromContext()`, `prop<>`) reads very differently from the
Vulkan plumbing beneath it, and inferring usage from `Renderer.kt` tends to produce code that
compiles against the engine but is not how a box is written.
- `public/docs/assets/canonical_graphics/LESSONS.md` — index of ~33 worked lessons in
  `public/docs/assets/canonical_graphics/`: lesson name → Kotlin and shader files, the parent
  chain each needs, and a Field-API-symbol reverse index. Generated and guarded.
- `public/docs/afield_four_graphics.md` — the same curriculum as prose, with captured
  native-window images.

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

## Documentation (Zensical + Read-Only EDN Viewer)

### Zensical site (human docs)

- Source content: `public/docs/` (Markdown; notebooks supported)
- Output: `public/field4/`
- Typical loop:
  - Build docs: `FIELD_DOCS_RUNNER_MODE=never uv run --project . --group docs python tools/build_zensical_docs.py --clean`
  - Fast incremental docs build: `FIELD_DOCS_RUNNER_MODE=incremental FIELD_DOCS_RUNNER_PORT=9477 uv run --project . --group docs python tools/build_zensical_docs.py`
  - Strict docs build that re-runs screenshots even after runner changes: `FIELD_DOCS_RUNNER_MODE=strict FIELD_DOCS_RUNNER_PORT=9477 uv run --project . --group docs python tools/build_zensical_docs.py`
  - Full recapture build that bypasses the screenshot/video cache entirely: `FIELD_DOCS_RUNNER_MODE=always FIELD_DOCS_RUNNER_PORT=9477 uv run --project . --group docs python tools/build_zensical_docs.py --clean`
  - No-capture docs build that reuses existing assets without starting Field: `FIELD_DOCS_RUNNER_MODE=never uv run --project . --group docs python tools/build_zensical_docs.py --clean`
  - Experimental warm-session capture path (opt-in, default OFF): set `FIELD_DOCS_RUNNER_SESSION=1` to route eligible non-graphics captures through one persistent `tools/field_session.py` daemon (JVM + WebSocket + Playwright page attached once) instead of a fresh `field_backdoor.py` process per capture. It is safe: each attempt fast-fails and falls back to the authoritative per-process path, and a circuit breaker (`SESSION_MAX_FAILURES=1`) stops the daemon on the first miss so the warm daemon and a per-process fallback never share one Field. Each capture creates its box at the origin and deletes it after the screenshot (via the `delete-box` backdoor command), so boxes never accumulate and one never-re-reset scratch doc serves an unbounded run. Measured ~2.0–2.4x on a forth+fline build (56 captures: 321–376s → 153–159s, pixel-identical). Still default OFF pending a full 236-capture clean-build validation; the residual risk is a slow graphics/SVG capture false-missing and handing off to the per-process path, which can hang on a Field already degraded by heavy cumulative graphics captures. Graphics-context and video captures always use the per-process path.
  - Serve built site standalone: `cd public && python3 -m http.server 8000`
  - Browse: `http://localhost:8000/field4/` — or just open Field's documentation pane, which serves `public/field4/` at `/field-docs/` once it is built

### `!!! note` vs `??? aside` — who a callout is written for

Two different things had been sharing one syntax, and only one of them is for a reader.

- **`!!! note` / `!!! warning` / `!!! info` / `!!! tip`** — something a reader needs in order to use the thing on the page. An API that does not behave as its name suggests, a statistic whose floor is not zero, a security caveat, the guarantee a solver does and does not give. Stays in the text column, open, styled as a callout.
- **`??? aside "Title"`** — commentary about how the page (or the code behind a figure) came to be, written for us and for the next agent: what an earlier cut of a clip got wrong and what measurement proved it, what the docs build cannot capture, which declaration no test reaches, which claim CI now pins. Renders collapsed everywhere, and at ≥ 82em it leaves the text column entirely and sits in the right margin, Tufte-style, at about a third of the measure.

The test that separates them: **would this still be worth reading by someone who does not care how the page was made?** If no, it is an aside. Keep writing them — the record of a mistake is how the mistake stops recurring — just do not spend a reader's attention on them.

Mechanics: `pymdownx.details` supplies `???` (see `public/zensical.yml`), and `aside` is a custom admonition type styled in `public/docs/stylesheets/extra.css` (search `??? aside`). The margin placement is a float with a negative right margin, so the browser stacks neighbouring asides and re-flows them when one is opened; there is no JS. Body indentation is the usual four spaces.

### Docs inside Field (documentation iframe)

- Field can show documentation in an iframe overlay (resizable, switchable from the command palette).
- Frontend toggle command: `switch-to-docs-iframe` (`src/jsMain/kotlin/afield/commands.kt`).
- Default URL + iframe wiring: `src/commonMain/resources/bootstrapMonaco.js` (defaults to `/field-docs/`, Field's own mirror).
- `/field-docs/…` serves **`public/field4/` — this checkout's built site — when the page is there**, and proxies `https://openendedgroup.com/field4/` when it is not. So the pane shows the docs you are editing, and works offline. `AFIELD_DOCS_MIRROR_SOURCE=local|upstream` and `AFIELD_DOCS_MIRROR_DIR` override it; served by `src/jvmMain/kotlin/afield/server/docsAnnotations.kt`, and both paths inject the annotation panel. See `dev_docs/DOCS_IFRAME_MODE.md`.
- Bridge API for docs pages: `src/commonMain/resources/docsIframeApi.js` (injected into the iframe; exposes `window.FieldAPI.*` to run actions / navigate / execute boxes).

### Exporting docs from a live Field document

- `src/jvmMain/kotlin/afield/docs/doc.kt` contains `exportToDocumentation(...)`, which walks boxes with metadata and emits docs-ready Markdown plus captured assets (screenshots/video).

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
- If a commit obliges everyone who pulls it to *do* something — clean the build, re-run a generator, install a tool — end its message with a `Field-Notice:` trailer stating that, in one line, in the imperative. Field's **Check for updates** command shows those trailers verbatim to anyone whose update check spans the commit (`src/jvmMain/kotlin/afield/internal/upstreamcheck.kt`). Ordinary commits should not carry one: the notice only carries weight while it stays rare.
