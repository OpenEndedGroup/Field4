# Field Testing Strategy

This is the canonical testing map for Field. Start here when deciding how to
verify a change, add regression coverage, or debug a failing automation run.

Always run agent-facing Python tooling through the repo uv project. The
backdoor, UI runner, and docs runner need the same dependency environment.
Browser screenshots and UI regressions do not require the Python `playwright`
package in that environment; they go through `tools/field_backdoor.py` and
Field's JVM/server-owned Playwright instance. If `python -c "import playwright"`
fails, that is not a valid reason to skip `--with-playwright`,
`tools/run_ui_regressions.py`, or docs capture checks.

## Fast Command Matrix

| Situation | Command | What it proves |
| --- | --- | --- |
| Kotlin/JVM compile check | `./gradlew compileKotlinJvm` | Source compiles without starting the full app UI. |
| Backdoor can start/attach Field | `uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 --command status` | Field server, websocket, scratch setup, and logs are reachable. |
| One runtime snippet | `uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 --command 'new-box --name Test' --command 'run @last --file path/to/snippet.kt'` | Live-code execution, stdout, display events, and runtime errors. |
| FIELD_TEST catalog | `uv run --project . --group backdoor python tools/run_field_tests.py --refresh-catalog` | Annotated examples and smoke tests still execute. |
| Browser UI regressions | `uv run --project . --group backdoor python tools/run_ui_regressions.py --port 9490 --timeout 45` | Monaco, command palette, panels, controller outputs, focus, and screenshots. |
| One UI regression | `uv run --project . --group backdoor python tools/run_ui_regressions.py --id macro-transcript-panel --port 9680 --timeout 45` | Focused browser workflow while iterating. |
| One browser screenshot smoke | `uv run --project . --group backdoor python tools/field_backdoor.py --json --with-playwright --port 9477 --command 'new-box --name Visual' --command 'run @last --value "display(\"main\", \"<div>hello</div>\")"' --command 'screenshot-box @last /tmp/field-smoke.png'` | Server-owned Playwright can render and capture a box. |
| Docs without captures | `FIELD_DOCS_RUNNER_MODE=never uv run --project . --group docs python tools/build_zensical_docs.py --clean` | Zensical content renders without starting Field. |
| Docs incremental captures | `FIELD_DOCS_RUNNER_MODE=incremental FIELD_DOCS_RUNNER_PORT=9477 uv run --project . --group docs python tools/build_zensical_docs.py` | Changed/missing examples recapture through Field. |
| Docs strict recapture | `FIELD_DOCS_RUNNER_MODE=strict FIELD_DOCS_RUNNER_PORT=9477 uv run --project . --group docs python tools/build_zensical_docs.py` | Runner/API changes invalidate cached captures. |
| Full docs recapture | `FIELD_DOCS_RUNNER_MODE=always FIELD_DOCS_RUNNER_PORT=9477 uv run --project . --group docs python tools/build_zensical_docs.py --clean` | Every docs screenshot/video is regenerated. |
| Offline asset check | `uv run python tools/check_offline_assets.py` | Canonical offline viewer assets are present and generated copies are not tracked. |

Use custom high ports for automation. Avoid the default `9092` unless you
explicitly want to attach to a developer-owned Field.

Run the command matrix **serially within one checkout**. The smoke, UI, and docs
entrypoints acquire `.field-backdoor/verification.lock`; if another supported
verification command is active, they print its owner and wait with heartbeats.
This is intentional: all three compile into or execute from the same Gradle/Kotlin
outputs, and concurrent builds can corrupt incremental-compiler backups and class
directories. Raw `./gradlew ...` commands do not participate in this Python lock,
so run compile/test tasks before or after these entrypoints, never alongside them.

## Testing Layers

### 1. Compile and Static Sanity

Use `./gradlew compileKotlinJvm` for ordinary Kotlin/JVM compile confidence.
For frontend-sensitive work, remember that the UI is Kotlin/JS plus resource
JavaScript; a compile pass does not prove browser behavior.

Known local Gradle failure modes and daemon cleanup rules live in `AGENTS.md`.
Do not replace runtime checks with Gradle-only checks when the change affects
websocket RPC, live-code execution, Monaco, output areas, or rendering.

### 2. Field Backdoor Runtime Checks

Use `tools/field_backdoor.py` when you need to prove behavior in a real Field
server session without manually driving the browser.

The backdoor can:
- start or attach to Field on a custom port
- create scratch boxes and set properties
- run or launch snippets
- inspect stdout, display events, runtime errors, and running state
- open the server-owned Playwright browser with `--with-playwright`
- capture window, editor, box, native-window screenshots, and videos
- run standalone JVM entrypoints with `run-jvm-main`
- run transcript macros (`macro write` / `macro run`) for semantic document operations

For screenshot and DOM commands, the Python script sends websocket requests to
Field; the browser is owned by the running JVM. Do not install or probe Python
Playwright unless you are working on a separate Python-only browser tool.

Default command:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 --command status
```

Use `--json` for automation, read `events` for output/errors, and inspect
`.field-backdoor/logs/` when behavior is confusing.

Detailed reference: `dev_docs/agents/FIELD_BACKDOOR.md`.

### 3. FIELD_TEST Smoke Catalog

Use the smoke catalog for examples and runtime checks that can be annotated
near their source with a `FIELD_TEST` block.

Run:

```bash
uv run --project . --group backdoor python tools/run_field_tests.py --refresh-catalog
```

`tools/run_field_tests.py` does not accept a top-level `--timeout` flag. Per-test
timeouts come from each `FIELD_TEST` annotation's `timeoutSec` value, and the
runner passes that value through to `tools/field_backdoor.py` for the individual
test execution. The catalog runner adds an outer process watchdog and prints a
heartbeat every 15 seconds by default (`--heartbeat-interval` changes that). If
all command results have arrived but the backdoor wrapper does not exit, the
runner reports that exact condition and terminates the whole child process group
after a short grace period instead of hanging indefinitely.

The catalog defaults to dedicated automation port `9477`, starts a fresh Field for
the run, and shuts down the process group afterward. Pass another high `--port`
when you need isolation from an existing automation session. It never defaults to
the developer-owned `9092` port. Gallery/video smoke artifacts are redirected to
`build/field-tests/generated-doc-assets/`; only the docs runner writes the tracked
captures under `public/docs/assets/`.

When an image expectation names a concrete output path, the runner deletes the
old output before launch and waits for the new file to become stable. It does not
also sleep for `waitSec`: the artifact is the readiness signal. `waitSec` remains
the bounded settle mechanism for tests that have no concrete file evidence.

Artifacts:
- catalog: `build/field-tests/catalog.json`
- results: `build/field-tests/results.json`
- generated snippets: `build/field-tests/generated/`
- captures: `build/field-tests/captures/`

Supported runner shapes:
- `jvm-main` for standalone smoke entrypoints
- `backdoor-box` for scratch-box snippets

Add a `FIELD_TEST` when the behavior is primarily code/runtime correctness,
example health, stdout/event assertions, or a simple capture connected to a
snippet. Keep the annotation explicit and stable; discovery is marker-based,
not filename-based.

Annotation reference: `dev_docs/testing/SMOKE_TEST_ANNOTATION_STRATEGY.md`.

### 4. Browser UI Regression Suite

Use `tools/run_ui_regressions.py` when the risk lives in the frontend workflow:
Monaco, command palette dispatch, focus, keyboard routing, panels, controller
iframes, output resizing, or screenshot capture behavior.

Run:

```bash
uv run --project . --group backdoor python tools/run_ui_regressions.py --port 9490 --timeout 45
```

The runner uses the backdoor plus server-owned Playwright internally. Non-JSON
mode prints `RUN`, heartbeat, `PASS`, and `FAIL` lines; tune progress output
with `--heartbeat-interval <seconds>`. An outer watchdog gives Playwright one
startup allowance plus one overall test budget; it does not multiply the budget
by every setup command. Once all command results arrive, the wrapper has only a
short exit grace before its process group is cleaned up.

Artifacts:
- generated fixtures: `build/ui-regressions/generated/`
- results: `build/ui-regressions/results.json`
- per-test artifacts: `build/ui-regressions/artifacts/<test-id>/`
- newest passing screenshot: `latest-pass.png`
- newest failure screenshot: `latest-failure.png`
- rolling pass history: `passes/` with the most recent 5 passing screenshots

The retained screenshot is test evidence, not a ceremonial artifact. If the
test concerns a transient palette, menu, toast, or panel, leave a representative
instance visible for the final capture (or take a supplemental capture at the
assertion point). The runner rejects missing, undecodable, and implausibly tiny
screenshots; DOM assertions still own the semantic pass/fail decision.

Current coverage includes command palette binding/smoke tests, editor sync,
insert stack mark, output areas, `/controllers` editable/static lines and
control widgets, screenshot selection, history panel, macro/transcript panel,
inline visualizers, and focus/keyboard contracts.

Roadmap and test IDs: `dev_docs/testing/UI_REGRESSION_ROADMAP.md`.

### 5. Zensical Executable Documentation

Public docs are a testing surface. Examples using `diffstatic_runner` execute
through Field and can regenerate screenshots/videos during docs builds.

The supported build path is a uv one-liner. Do not create a manual venv or
install Zensical dependencies by hand:

```bash
FIELD_DOCS_RUNNER_MODE=never uv run --project . --group docs python tools/build_zensical_docs.py --clean
```

For a full evidence rebuild, run:

```bash
FIELD_DOCS_RUNNER_MODE=always FIELD_DOCS_RUNNER_PORT=9477 uv run --project . --group docs python tools/build_zensical_docs.py --clean
```

Zensical currently does not run Python page hooks directly, so the uv command
enters `tools/build_zensical_docs.py` first. The wrapper preprocesses Markdown
through `public/docs_hooks.py`, writes generated sources under
`public/.zensical-generated/`, writes `public/zensical.generated.yml`, and then
runs `python -m zensical build` using the same interpreter. The docs dependency
group intentionally includes the backdoor dependencies because docs captures
launch `tools/field_backdoor.py` from that interpreter.

Modes:
- `FIELD_DOCS_RUNNER_MODE=never`: build HTML only; reuse existing assets
- `FIELD_DOCS_RUNNER_MODE=incremental`: rerun changed or missing captures
- `FIELD_DOCS_RUNNER_MODE=strict`: invalidate captures when runner logic changes
- `FIELD_DOCS_RUNNER_MODE=always`: regenerate every capture

Use `FIELD_DOCS_RUNNER_PORT=<high-port>` for capture runs. The docs runner owns
that port for the entire capture run, replacing a stale/cross-run Field before the
first capture and shutting down the process group at the end. Port `9477` is the
safe default, but an explicit dedicated port makes ownership clearest in logs.

Treat broken docs captures as real failures. If a screenshot is blank, red,
tiny, stale, or visually wrong, inspect the backdoor events/logs and fix the
example or capture path rather than hand-editing generated assets.

### 6. Graphics and Native Rendering Checks

Graphics checks often need more than "the snippet printed something." Use the
backdoor's graphics helpers when renderer topology matters:
- `--graphics-context` for disposable scratch setup with a launched renderer
- transcript macros such as `dev_docs/agents/transcripts/graphics_renderer_launch.yaml`
- `wait-native-renderer <box> --frames 3` to prove frames are advancing
- `native-screenshot <path> --wait-frames 4` for native-window evidence

For coroutine-based graphics examples, resolve contextual dependencies before
leaving the executing context. In practice, capture `val renderer: Renderer =
fromContext()` before `start(...)` and pass it into async helpers explicitly.

### 7. Transcript and Macro Regression Checks

Use transcript macros when a test should describe semantic Field document
operations instead of low-level browser gestures. The backdoor should remain the
transport/capture harness; the transcript should own the durable operation
sequence.

Useful references:
- `dev_docs/agents/transcripts/README.md`
- `dev_docs/agents/TRANSCRIPT_SELF_IMPROVEMENT_LOOPS.md`

The UI regression suite already covers the macro/transcript panel itself. Add
new transcript files when the reusable workflow matters outside one test.

### 8. Offline Viewer and Asset Ownership

The offline viewer has generated copies of Monaco/fonts/docs assets. Verify
ownership with:

```bash
uv run python tools/check_offline_assets.py
```

Build and verify a disposable standalone distribution when changing ownership:

```bash
./build-offline-docs.sh
uv run python tools/check_offline_assets.py --target dist/offline-viewer
```

Do not synchronize generated copies into `public/` or `dev_docs/`; the checker
explicitly rejects those paths if they become tracked. The docs build emits its
own site-local copy under `public/field4/`.

### 9. Specialized Manual References

Some older feature notes are still useful as manual checklists or as source
material for future automation. Treat this guide as the index, and promote
high-value checks from these notes into `FIELD_TEST`, UI regressions, or
executable docs when they start catching real regressions.

Current specialized references:
- `dev_docs/HISTORY_TESTING_GUIDE.md`: history invariants and the remaining manual restore workflows. The browser UI regression suite has automated `history-panel` coverage for the panel smoke path.
- `dev_docs/FileWatchManager_Testing.md`: manual external sidecar edit, internal save, rapid edit, and watcher cleanup scenarios.
- `dev_docs/LAYERS_TESTING.md`: manual layer visibility, active-layer frame persistence, animated transitions, and connection visibility scenarios.
- `dev_docs/VALUE_VISUALIZER.md`: implementation ownership and current verification gaps; public runner-backed examples live in `reading_your_values.md` and `vector_algebra.md`.
- `dev_docs/manual_tests/test-offline-viewer.html`: manual offline viewer fixture.

## Choosing Where to Add Coverage

Add or update a `FIELD_TEST` when:
- the assertion is mostly runtime/code behavior
- stdout, display events, or a simple screenshot are enough evidence
- the test belongs near an example or documented snippet
- the browser UI is incidental

Add or update `tools/run_ui_regressions.py` when:
- the regression depends on real Monaco/editor state
- command palette `this` binding, focus, keyboard behavior, or panel lifecycle matters
- `/controllers`, iframes, resizing, output DOM, or screenshots are the risk
- representative screenshots should be retained across passing runs

Add or update executable docs examples when:
- the behavior is part of user-facing documentation
- the screenshot/video is documentation and regression evidence
- the example should stay visible on the public docs page

Use a one-off backdoor command when:
- you are diagnosing a bug
- the repro is not yet stable enough to promote
- you need to inspect events/logs before deciding on permanent coverage

## Failure Triage

1. Check the first concrete failure, not the last cascade.
2. For backdoor failures, read JSON `events`, `timedOut`, and the newest
   `.field-backdoor/logs/*.managed-server.log`.
3. For UI regression failures, inspect `latest-failure.png` and
   `build/ui-regressions/results.json`.
4. For docs failures, rerun the smallest affected page/mode when possible, then
   inspect the generated screenshot/video and backdoor logs.
5. If a snippet timeout wedges later `is-running` probes, restart the managed
   Field JVM or choose a fresh high port before trusting follow-on failures.
6. If Playwright output DOM is missing, attach with `--with-playwright` before
   the `run` or `launch` that creates the output.
7. If keyboard tests miss their target, use selector-targeted input such as
   `input-key press Escape --selector .afield-palette-input --wait-ms 250`.

## Long-Term Rule

Every regression that costs real debugging time should end up in one of these
three durable places: `FIELD_TEST`, the browser UI regression suite, or an
executable docs example. Keep the one-off command in the bug transcript only
long enough to understand which durable layer owns it.
