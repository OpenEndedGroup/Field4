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
| Complete compiler-warning audit | `./gradlew --no-daemon --rerun-tasks warningGates` | Force-compiles the JVM, JS, JVM-test, and standalone default-completion source sets so incremental caching cannot hide warnings. |
| Complete deterministic repository certification | `./gradlew certify` | Runs the full `check` graph with continuation enabled, so JVM/JS tests, documentation contracts, generated-source drift guards, and Python tool tests all receive a verdict even when an independent task fails. Benchmark/measurement fixtures remain excluded. |
| Generated artifacts and documentation contracts before commit | `uv run --project . --frozen python tools/check_staged_generated_artifacts.py` | Selects every generated-artifact or citation/documentation guard affected by staged paths and rejects a worktree/index mismatch. Install it as the shared pre-commit and post-merge hooks with `uv run --project . --frozen python tools/install_git_hooks.py`; both hooks use this same pinned interpreter. |
| All artifact and documentation-contract guards | `uv run --project . --frozen python tools/check_staged_generated_artifacts.py --all` | Runs every registered guard with Gradle `--continue`, so independent failures are reported in one pass. |
| Tests left the tree clean | `./gradlew jvmTest` | `checkTestTreeWrites` runs automatically after every `Test` task and fails on any write into a tracked path, including a rewrite whose bytes did not change. |
| Opt-in JVM measurements | `./gradlew jvmMeasurements -i` | Slow, non-asserting measurement fixtures regenerate publishable timing/search evidence. |
| Backdoor can start/attach Field | `uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 --command status` | Field server, websocket, scratch setup, and logs are reachable. |
| One runtime snippet | `uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 --command 'new-box --name Test' --command 'run @last --file path/to/snippet.kt'` | Live-code execution, stdout, display events, and runtime errors. |
| FIELD_TEST catalog | `uv run --project . --group backdoor python tools/run_field_tests.py --refresh-catalog` | Annotated examples and smoke tests still execute. |
| Browser UI regressions | `uv run --project . --group backdoor python tools/run_ui_regressions.py --port 9490 --timeout 45` | Monaco, command palette, panels, controller outputs, focus, and screenshots. |
| One UI regression | `uv run --project . --group backdoor python tools/run_ui_regressions.py --id macro-transcript-panel --port 9680 --timeout 45` | Focused browser workflow while iterating. |
| One browser screenshot smoke | `uv run --project . --group backdoor python tools/field_backdoor.py --json --with-playwright --port 9477 --command 'new-box --name Visual' --command 'run @last --value "display(\"main\", \"<div>hello</div>\")"' --command 'screenshot-box @last /tmp/field-smoke.png'` | Server-owned Playwright can render and capture a box. |
| Docs native prerequisite preparation | `./gradlew buildDocsNativePrerequisites` | Builds Ceres and TetGen, checks Manifold and JEP, and reuses checksummed source archives from a clean-safe cache shared by worktrees. |
| Docs without captures | `FIELD_DOCS_RUNNER_MODE=never uv run --project . --group docs python tools/build_zensical_docs.py --clean` | Zensical content renders without starting Field. |
| Docs incremental captures | `FIELD_DOCS_RUNNER_MODE=incremental FIELD_DOCS_RUNNER_PORT=9477 uv run --project . --group docs python tools/build_zensical_docs.py` | Changed/missing examples recapture through Field. |
| Docs strict recapture | `FIELD_DOCS_RUNNER_MODE=strict FIELD_DOCS_RUNNER_PORT=9477 uv run --project . --group docs python tools/build_zensical_docs.py` | Runner/API changes invalidate cached captures. |
| Full docs recapture | `FIELD_DOCS_RUNNER_MODE=always FIELD_DOCS_RUNNER_PORT=9477 uv run --project . --group docs python tools/build_zensical_docs.py --clean` | Every docs screenshot/video is regenerated. |
| Capture freshness audit | `uv run --project . --group docs python tools/docs_capture_audit.py --repeat 2` | Separates identical/jitter, reproducible layout reflow, stale renders, and nondeterministic captures; `layout` remains a failing, non-adoptable verdict. |
| Offline asset check | `uv run python tools/check_offline_assets.py` | Canonical offline viewer assets are present and generated copies are not tracked. |
| Field Audio core | `./gradlew fieldAudioCoreTest fieldAudioDocsCheck` | Opt-in service tests and contract drift check; no worker environment or model checkpoint. |
| Field Audio analysis | `./gradlew fieldAudioAnalysisTest` | Opt-in Python 3.10 librosa/Basic Pitch environment and import check. |
| Field Audio AudioCraft import | `./gradlew fieldAudioAudioCraftImportTest` | Opt-in pinned PyTorch/AudioCraft environment; checkpoint access is forced offline. |
| Field Audio model smoke | `FIELD_AUDIO_ALLOW_MODEL_DOWNLOAD=1 ./gradlew fieldAudioModelSmoke` | Explicit heavyweight MusicGen-Style checkpoint download/load. |

Full-recapture runtime is a measured capacity input, not a fixed one-hour promise. On 2026-09-04,
`docs_capture_audit.py --inventory` counted 531 runner-managed assets. A quiet two-run audit of the
DataFrame page rewrote three captures in 77.5s and 78.1s (about 26s per rewritten asset); even the
historical best whole-corpus rate of 7.5s per asset now exceeds an hour at this corpus size. Nightly
planning therefore uses a four-hour envelope and must remeasure rather than presenting a partial
run as a completed full recapture.

Use custom high ports for automation. Avoid the default `9092` unless you
explicitly want to attach to a developer-owned Field.

Incremental capture keys hash capture-affecting syntax from
`public/docs_hooks.py`, not its diagnostic prose, logging calls, or
failure-report-only helpers. Those helpers have focused Python tests; editing a
post-mortem no longer invalidates every successful capture, while operational
runner edits still change the key.

Run the command matrix **serially within one checkout**. The smoke, UI, and docs
entrypoints acquire `.field-backdoor/verification.lock`; if another supported
verification command is active, they print its owner and wait with heartbeats.
This is intentional: all three compile into or execute from the same Gradle/Kotlin
outputs, and concurrent builds can corrupt incremental-compiler backups and class
directories. Since 2026-09-28 raw `./gradlew ...` honours the lock too
(`settings.gradle.kts`): while another live process holds it, a build prints
`WAIT gradle: verification lock held by {...}` every 15 s and continues when it is
released (up to `AFIELD_VERIFICATION_LOCK_WAIT_SEC`, default 1800). Builds the owner
itself spawns carry `AFIELD_VERIFICATION_LOCK_OWNER=<owner pid>` and go straight
through. `AFIELD_VERIFICATION_LOCK=fail` stops immediately instead of waiting,
`AFIELD_VERIFICATION_LOCK=off` disables the check, IDE sync never waits, and a lock
file naming a dead (or reused) pid is ignored as stale.

Smoke `expect.outputContains` / `outputNotContains` inspect emitted text only:
`output` and `peer-output` HTML, `set-output` values, and notification text. Source
echoes, topology updates, output controls, and batch envelopes are not output
evidence. Use `eventKinds` to assert a protocol event rather than its rendered
content. A substring check establishes presence, not a row-to-value relationship;
pair it with a behavioral assertion when that relationship is the claim.

### Continuous verification prerequisites

The smoke and docs capture runners check the checkout volume's free space on every
15-second sample, on all operating systems. The default protected floor is 8 GiB.
Configure `FIELD_VERIFY_DISK_FLOOR_GIB`, `FIELD_VERIFY_DISK_RESERVE_GIB` (default 0),
and `FIELD_VERIFY_DISK_PLANNED_GIB` (default 0) with finite non-negative GiB values.
Before the first action, free space must cover **floor + reserve + planned growth**;
throughout execution it must cover **floor + reserve**. Planned growth is charged
once per runner invocation, not once per capture. Zero planned growth means no
growth estimate was supplied, not that the run is expected to allocate nothing.

Budget the expected **whole-run volume loss**, including build outputs, downloads,
temporary files and concurrent writers, not merely the checkout footprint. The
2026-09-21 nightly observed about 22 GiB of volume loss against 6.82 GiB in its three
worktrees: a six-GiB worktree estimate was not a sufficient run budget. The monitor
records initial/minimum free bytes and maximum observed volume loss in the environment
report; these are shared-volume measurements, never an attribution to Field or an
estimate of reclaimable space. Sampling cannot prevent a sudden loss between probes
or reserve actual filesystem blocks. A low/unknown disk reading refuses new work or
interrupts an owned capture/test; it never deletes anything. Raw Gradle, standalone
backdoor and UI-regression commands are not covered by this smoke/docs monitor.

The smoke and docs capture runners sample power and display state every 15 seconds,
including while starting Field and between tests. Long verification requires AC or
at least 80% battery. A native-window capture or `graphicsContext` smoke also needs
an online primary display; standalone JVM smokes opening a `Window` declare
`requiresDisplay: true` in their `FIELD_TEST` annotation. Headless Playwright
captures (`box`, `window`, `editor`, `ui`) do not need a physical display merely
because they capture a browser. Optional GPU compute without a window is not
automatically classified as display-dependent.

Unknown macOS probe results fail closed. Other operating systems are explicitly
reported as unsupported by the power/display probe (disk checks still apply); they
are not claimed certified by a macOS check. A closed laptop lid with a working external primary display is
not rejected solely because of the lid state.

Missing prerequisites yield **NOT RUN**, and observed loss during a test yields
**INTERRUPTED**, even if the prerequisite returns before the test finishes. Neither
is a pass, an accepted product failure, or a completed coverage-ledger entry. Smoke
reports include `environmentIncomplete`, `complete`, and environment observations;
an environment-incomplete run exits 2 (ordinary test failures still exit 1).
Independent headless cases continue when only the display is absent.

The monitor also records observation gaps over 45 seconds, with matching macOS
Sleep/Wake log entries when available. A gap can be sleep, scheduling delay, or a
clock change: it is not labelled sleep without evidence, and is never silently
subtracted to produce a guessed active runtime. Interrupted smoke timings are
`durationMs: null`, with a separate `observedWallMs`.

Docs record each affected page/block in `build/docs-environment.json`, continue
other fences, and finish nonzero if anything was not run or interrupted. Interrupted
captures lose their cache certification; existing images are not evidence of a new
pass. HTML-only `FIELD_DOCS_RUNNER_MODE=never` builds do not require these capture
prerequisites. Warm-session requests are checked before and after their bounded
request; the normal per-process path also interrupts its owned child during the
request. No monitor changes power settings or kills an unrelated Field process.

The Field Audio rows are deliberately not dependencies of `check` or the docs builder. Field's
published reference is the committed `public/docs/field_audio_service_reference.md`; ordinary docs builds
use it unchanged even when the private submodule is not initialized. Refreshing that snapshot is
an explicit integration operation: `./gradlew fieldAudioDocsRefresh`, followed by
`./gradlew fieldAudioDocsCheck`. The model smoke refuses to run unless
`FIELD_AUDIO_ALLOW_MODEL_DOWNLOAD=1` is present.

## Testing Layers

### 1. Compile and Static Sanity

Use `./gradlew compileKotlinJvm` for ordinary Kotlin/JVM compile confidence.
For frontend-sensitive work, remember that the UI is Kotlin/JS plus resource
JavaScript; a compile pass does not prove browser behavior.

For a warning census, use `./gradlew --no-daemon --rerun-tasks warningGates`.
The `--rerun-tasks` on the aggregate forces Field's three audited source sets;
the task separately force-compiles `tools/code-use-semantic-sidecar`, whose own
Gradle settings make it invisible to root compile tasks. That sidecar is the
default completion backend, so a warning count that omits it is incomplete.
The task prints its remaining boundary explicitly: Gradle caches Kotlin DSL
script compilation independently of `clean` and `--rerun-tasks`, and offers no
supported force-recompile switch, so this is a Kotlin **source** warning census;
it does not certify compiler diagnostics from `build.gradle.kts` itself.

Gradle process ownership and daemon cleanup rules live in `AGENTS.md`.
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

When `--port` is omitted, the catalog chooses the first free automation port starting
at `9477`, reports it, records it in `results.json`, starts a fresh Field there, and
shuts down the process group afterward. It checks both Field's HTTPS port and the
default loopback HTTP mirror. Passing `--port` makes that choice exact: ownership and
source-identity checks fail closed rather than silently moving it. The automatic path
never selects the developer-owned `9092` port. Gallery/video smoke artifacts are redirected to
`build/field-tests/generated-doc-assets/`; only the docs runner writes the tracked
captures under `public/docs/assets/`.

Unfiltered catalog runs record each completed verdict in
`build/field-tests/coverage-state.json`. The next run puts tests with no recorded
verdict first, followed by the least-recently-completed tests. Thus stopping a
long run does not make every later partial run recheck the same catalog prefix
while leaving the tail stale. Targeted `--id`, `--tag`, and `--runner` runs retain
catalog order and do not rewrite this full-catalog ledger. Use `--catalog-order`
for an unfiltered diagnostic run that deliberately preserves discovery order.
Progress and `results.json` report the unseen count and oldest-verdict age at the
start of a coverage-ordered run.

When an image expectation names a concrete output path, the runner deletes the
old output before launch and waits for the new file to become stable. It does not
also sleep for `waitSec`: the artifact is the readiness signal. `waitSec` remains
the bounded settle mechanism for tests that have no concrete file evidence.

Artifacts:
- catalog: `build/field-tests/catalog.json`
- results: `build/field-tests/results.json`
- coverage ledger: `build/field-tests/coverage-state.json`
- generated snippets: `build/field-tests/generated/`
- captures: `build/field-tests/captures/`

Supported runner shapes:
- `jvm-main` for standalone smoke entrypoints
- `backdoor-box` for scratch-box snippets

### Deliberately accepted JVM test failures

`./gradlew check` normally requires every JVM test to pass. If preserving a
known production defect as a red test is an explicit decision, add its exact
Gradle selector, reason, and durable documentation pointer to
`dev_docs/testing/expected_test_failures.json`. Ordinary `jvmTest` excludes only
those exact selectors; `expectedFailureTests` runs them separately, and
`checkExpectedTestFailures` accepts the run only when every registered test
still fails. A registered test that passes, is renamed, or does not execute makes
the gate fail because the register is stale. Any test absent from the register
still fails through ordinary `jvmTest`.

The register is intentionally empty while `main` has no accepted red. Wildcards
are forbidden: each entry must name one test, explain why its failure remains
intentional, and point to a repository document that owns the decision.

Smoke tests have the same two-sided contract in
`dev_docs/testing/expected_smoke_failures.json`. `tools/run_field_tests.py` reports a registered,
still-failing smoke as `XFAIL`; a registered smoke that passes, disappears, is platform-skipped, or
fails before its assertions run makes the smoke gate fail. Targeted runs enforce entries among the
selected ids, while an unfiltered catalog run checks the whole register.

Documentation-contract tests run through `citationGates`, which is a dependency
of both `check` and `docsGates`. They are excluded from broad `jvmTest` so they run
once, with their real Markdown, JavaScript, and build-script inputs declared;
therefore a docs-only edit cannot inherit a stale JVM-test result.

### Tests must not write into the working tree

A test renders into a temp directory and inspects it there. `proofDir()` and
`docsAssetDir()` in `src/jvmTest/kotlin/afield/TestProofDir.kt` are how; their KDoc
carries the history.

`checkTestTreeWrites` enforces it. Every `Test` task depends on
`recordTestTreeBaseline`, which snapshots the tree, and is `finalizedBy` the check,
which snapshots it again and fails on the difference. `finalizedBy` because a red
test must not be able to skip the guard.

Three rounds of this bug shipped before the guard existed, each found by a human
noticing a dirty `git status` and fixed one class at a time:

1. Relative-path proof writes (`File("sparkline-proof")`,
   `File("chebyshev-proof.svg")`), which resolved against whatever tree the suite ran
   in and left junk in the checkout and in every nightly agent worktree.
2. `ArraySparklineVisualizerTest`, the same thing in a class the first fix missed.
3. `ValueVisualizerDocCaptureTest` and `VectorAlgebraDocCaptureTest`, which wrote
   their docs-capture PNGs into `public/docs/assets/…` on every `./gradlew jvmTest`.
   Fixed 2026-09-04.

It is a tree diff, not a scan of the test sources, so it does not care how a test
names its destination: `File(root, computeName())` is caught as well as a literal.
A static scan cannot promise that here — `GateInputDeclarationTest` counts
non-literal `File(root, x)` sites precisely because it cannot see through them.

It reads mtimes as well as `git status`, because a content diff is only half the
problem. On the third case above, all five `vector_algebra` PNGs were rewritten on
every run and only two differed in bytes; the other three unconditional writes into
committed paths produced no diff at all, and would have stayed invisible for as long
as that renderer stayed deterministic. Such a row is reported as `rewritten with
identical bytes`.

Regenerating a committed artifact from a test is legitimate, and is an explicit
request. `tools/check_test_tree_writes.py` holds the register of variables that
authorize it — `AFIELD_DOCS_ASSETS=1`, `FIELD_CITATIONS_WRITE=1`, the
directory-valued `AFIELD_PROOF_DIR`, and the six `-Dafield.*` system properties the
RectSL, tessellation and physics harnesses use — together with the paths each one
may write. A regenerate run that writes somewhere else still fails, and so does one
whose variable is not in the register. That is the safe direction: the cost is one
loud failure in a run somebody deliberately asked for, rather than a hole nobody
sees. Add the variable to `ESCAPES` when adding such a gate to a test;
`AFIELD_ALLOW_TREE_WRITES=path,path` is the way through a one-off in the meantime,
and it still has to name its paths.

The system properties need forwarding because the check runs in its own process:
`checkTestTreeWrites` passes every `-Dafield.*` on to it, mirroring what
`build.gradle.kts` already does for the test workers.

Two things the guard does not claim. It cannot tell a test write from an edit you
made while the suite was running, so it says so in the failure and asks for a
re-run. And when no `Test` task executed — the ordinary `UP-TO-DATE` case — it
prints that it observed nothing rather than reporting a pass, because a green there
would be a verdict about a run that did not happen. That also means the documented
regenerate commands need `--rerun`: an unchanged classpath leaves `jvmTest`
`UP-TO-DATE`, and setting an environment variable invalidates nothing.

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

When `FIELD_DOCS_RUNNER_PORT` is unset, the docs runner chooses and logs the first
free automation port starting at `9477`, checking both Field's HTTPS port and its
loopback HTTP mirror. Set `FIELD_DOCS_RUNNER_PORT=<high-port>` when the port itself is
part of the contract; an explicit choice remains exact and rejects a foreign or
source-stale Field rather than moving. The runner shuts down its own process group at
the end either way.

Treat broken docs captures as real failures. If a screenshot is blank, red,
tiny, stale, or visually wrong, inspect the backdoor events/logs and fix the
example or capture path rather than hand-editing generated assets.

Each capture and shared-server start gets one attempt. A failed invocation is evidence, not a vote:
diagnose it and change the code, environment, or command before running again.

**Warm-session capture** (moved from `AGENTS.md`, 2026-09-28). Opt-in, default OFF: set
`FIELD_DOCS_RUNNER_SESSION=1` to route eligible non-graphics captures through one persistent
`tools/field_session.py` daemon (JVM + WebSocket + Playwright page attached once) instead of a
fresh `field_backdoor.py` process per capture. It is safe: each attempt fast-fails and falls back
to the authoritative per-process path, and a circuit breaker (`SESSION_MAX_FAILURES=1`) stops the
daemon on the first miss so the warm daemon and a per-process fallback never share one Field.
Each capture creates its box at the origin and deletes it after the screenshot (the `delete-box`
backdoor command), so boxes never accumulate and one never-re-reset scratch doc serves an
unbounded run. Measured ~2.0–2.4x on a forth+fline build (56 captures: 321–376 s → 153–159 s,
pixel-identical). Still default OFF pending a full 236-capture clean-build validation; the
residual risk is a slow graphics/SVG capture false-missing and handing off to the per-process
path, which can hang on a Field already degraded by heavy cumulative graphics captures.
Graphics-context and video captures always use the per-process path.

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

Every shipped demo transcript under `dev_docs/agents/transcripts/` is now
executed (or, for graphics demos, compiled) end-to-end by the FIELD_TEST
`examples.demo-transcript.exec`
(`src/jvmMain/kotlin/afield/examples/DemoTranscriptExecSmokeTest.kt`).
**"Shipping a demo" means its box bodies run/compile with no diagnostics in CI**,
not just that the `code` string round-trips — `OperationTranscriptMacroSmokeTest`
only *applies* ops and diffs `code`, which is how `fn_differentiable_fit_demo`
shipped with two compile errors on 2026-07-18. When you add a transcript, add its
name to `EXECUTE` or `COMPILE_ONLY` in that harness (see the transcript README's
"Verified in CI" section).

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
- the scenario's preconditions and assertions have not yet been made deterministic enough for
  permanent coverage
- you need to inspect events/logs before deciding on permanent coverage

## Failure Triage

1. Check the first concrete failure, not the last cascade.
2. For backdoor failures, read JSON `events`, `timedOut`, and the newest
   `.field-backdoor/logs/*.managed-server.log`.
3. For UI regression failures, inspect `latest-failure.png` and
   `build/ui-regressions/results.json`.
4. For docs failures, inspect the generated screenshot/video and backdoor logs. After identifying a
   cause or changing an explicit precondition, use the smallest affected page/mode to verify it.
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
