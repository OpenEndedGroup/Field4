# Verification prerequisites and interruption reporting: 2026-09-20

Implements journal items #110 and #108 in the existing
`codex/backlog-renderer-errors-2026-09-20` worktree. These changes are part of the
same pending landing as the renderer-error, capture-ownership, capture-watchdog,
and smoke-output repairs documented alongside this note.

## Contract

- Smoke and docs capture runners share a read-only macOS environment monitor.
  Power/display samples are taken every 15 seconds, including during Field startup.
  Long verification requires AC power or at least 80% battery. Unknown macOS probe
  results fail closed; other platforms are explicitly recorded as unsupported.
- Native-window captures and graphics-context smokes require an online primary
  display. Eleven standalone JVM tests that directly create native windows now
  declare `requiresDisplay: true`. Headless browser work does not inherit a native
  display requirement. An external primary display works with a closed laptop lid.
- A missing prerequisite is `NOT RUN`; observed loss during execution is
  `INTERRUPTED`, even if it recovers before the action completes. Neither is an
  accepted product failure, a pass, or a completed smoke coverage-ledger entry.
  Missing display access still permits independent headless cases to run.
- An observation gap over 45 seconds records the raw wall interval and available
  matching Sleep/Wake log lines. Clock rollback also invalidates the action. The
  monitor does not assume that every gap was sleep or invent an active-runtime
  subtraction. Interrupted smoke results have `durationMs: null` and a separate
  `observedWallMs`.
- Smoke reports include environment observations and an incomplete count; an
  environment-incomplete run exits 2 unless an ordinary failure also requires 1.
  Docs write `build/docs-environment.json` and fail final certification if any
  capture was not run or interrupted, even with continue-on-error enabled.
- An interrupted docs capture loses its cache certification. The owned capture
  child and shared server are stopped. A refusal before startup does not acquire
  ownership of an existing listener. No power settings or unrelated processes are
  changed.

Sampling cannot establish that no short-lived transition occurred between samples.
The per-process watchdog checks throughout execution; warm-session requests are
checked before/after their bounded request. This is not hardware validation on
Windows/Linux or a guarantee of immediate cancellation inside a warm request.

## Verification and remaining landing work

Deterministic tests exercise the 80% boundary, unknown probes, external-display
semantics, display loss/recovery, power loss/recovery, a simulated 63-minute gap,
clock rollback, sleep-log filtering, owned-child cancellation, native/headless
classification, docs cache invalidation and final failure, smoke result
classification, and cleanup of an interrupted prewarm. The catalogue test checks
tracked direct-window creators for explicit display declarations.

Pre-AC full Python suite: **578 passed / 579 run, one skipped**, including all
21 environment tests. The skip is the built-site integration test because this
worktree has no built site. `git diff --check` passed. An earlier full-suite run
exposed a constructor-bypass fixture missing the new callback field; the fixture
was repaired before this final pass.

The real CLI preflight at **37% battery, disconnected from AC** reported
`NOT RUN docs.logging.mute_is_exact`: zero passes, zero product failures, one
environment-incomplete result, `complete: false`, and no certified duration. No
Field listener was started on port 9573. This checks the real refusal path, not
the happy execution path. The retained invocation predates the final addition of
top-level epoch timestamps to the environment report.

Local evidence:

- `/tmp/field4-environment-real-preflight.log`
- `build/field-tests/environment-preflight.json`
- `build/field-tests/environment-preflight.environment.json`
- `/tmp/field4-environment-python-final.log`

## AC-power continuation

AC power was confirmed before resuming. Final startup review found that the docs
constructor eagerly started Field before the capture wrapper could check power.
Startup now occurs inside that wrapper, after the checkpoint; a constructor-level
regression test pins the ordering. The focused environment suite then passed all
22 tests.

- Fresh smoke run: **2 passed**, `docs.fabricate.default-scene` and
  `docs.logging.mute_is_exact`; zero product failures or environment-incomplete
  results. `build/field-tests/environment-ac-results.json` retains the continuous
  observations. Log: `/tmp/field4-environment-ac-smokes.log`.
- The actual docs `RunnerState.capture_with_environment` / `ensure_screenshot`
  path regenerated the default Fabricate scene using its existing authored fence
  and snippet. Only image/cache/log destinations were redirected into build-local
  paths. Completion sidecar was disabled because this fence does not use it.
  Inspected the fresh 2048 x 1257 PNG with three rendered torii. Four samples
  recorded AC power and an online primary display, with no observation gaps or
  interrupted outcomes. This tests one real docs capture, not a full-site recapture.
  Evidence: `/tmp/field4-environment-ac-docs.log`, `build/docs-environment.json`,
  `build/environment-docs-1789957899375308000.png`, and local driver
  `build/verify_docs_environment.py`.

Earlier batch checks (56 live smokes and 46 JVM tests) are recorded in
`BACKLOG_SMOKE_OUTPUT_2026_09_20.md`. The AC continuation also passed the 15 capture
and logging JVM tests, `checkCodebaseReference`, `checkKDocAttachment`,
`citationGates`, and the tree-write guard; log:
`/tmp/field4-environment-ac-jvm-guards.log`. The Table filter in that invocation
used its directory rather than its package, so did not rerun Table tests; the
combined-tree full suite must cover `afield.TableTest`.
Combined-tree verification/landing is still pending at this checkpoint. Nothing
has been pushed.

The backlog commit is `1856f9311`. Integrating main at `fd2f05da3` also brings its
new `four.gpu-skinning.smoke` into the display-declaration census: it creates a
native Window even for compute verification, so it is explicitly marked
`requiresDisplay: true` as part of integration.

## Combined-tree certification

The combined tree with main `fd2f05da3`, including the GPU-skinning declaration,
passed **`./gradlew --no-daemon certify`** in 26m 58s:

- JVM: **4,091 tests, zero failures/errors, 26 skipped** (4,065 passed), across
  409 suites. This includes all 31 `afield.TableTest` tests.
- Python: **579 passed / 580 run, one built-site-dependent skip**, including all
  22 environment tests and the expanded display-declaration census.
- All 136 materialized transcripts, demo/index/layout guards, completion corpus,
  source/reference/documentation/citation guards passed. The expected-failure
  register had zero registered and zero observed failures.
- `checkTestTreeWrites` confirmed that the JVM, expected-failure, and citation
  suites left the tree as they found it. Browser JS tests had no test sources;
  this is not a browser UI-regression run or a full smoke catalogue/recapture.
- Two redundant-conversion warnings in main's `WeightInterpolationTest.kt` were
  reported and left outside this batch. This is not a warning-free-build claim.

Log: `/tmp/field4-backlog-combined-certify.log`. The long completion tests were
observed doing CPU work in two distinct corpus passes, not inferred to be asleep
from elapsed time. No unchanged retry or test exclusion was used.

Final fresh live checks on the same combined runtime: **3 passed**, zero failures
or environment-incomplete outcomes: `docs.fabricate.default-scene`,
`docs.logging.mute_is_exact`, and `four.gpu-skinning.smoke`. Evidence:
`/tmp/field4-backlog-combined-smokes.log` and
`build/field-tests/backlog-combined-results.json`. The runner shut down its owned
Field and left the main checkout's running Field alone.

This is the verified integration tree prepared for the user-authorized landing
on main. The unrelated untracked `dev_docs/agents/demos/marf_demo/` in main is not
part of this batch; neither branches nor worktrees are removed, and no push is
requested.
