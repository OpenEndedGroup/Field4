# Backlog: docs guards and capture fallback, 2026-09-22

This batch addresses W1, W2 and W6 from `nightly/journal:SCRATCHPAD.md`.

- **W1:** `DocumentedApiEvaluationTest` now recognizes whole API identifiers in prose,
  inline code, qualified names and calls. It does not confuse prefixes, suffixes or
  identifiers containing Unicode letters with the named API. A fixture pins those
  distinctions and the real `marksOf` page is a witness. The census expanded from
  597 names / 574 evaluated to 638 names / 609 evaluated. Six newly visible
  compile-only names have explicit reasons in the bidirectional ledger:
  `computeMemoryBarrier`, `flowSmoothVelocity`, `flowStrokeClip`, `takeFocus`,
  `cvtColor`, and the scene-local callback `modelWorld`. This repairs detection;
  it does not claim to add runtime coverage for those six names.
- **W2:** Continuing docs builds embed available saved media when recapture fails.
  This covers screenshots, video, audio and the independent still/video outputs
  of a UI drive. Capture failures remain recorded; fail-fast still raises, and
  missing media is not embedded. Four regression tests cover the variants;
  reverting the implementation produces six failing subcases. The focused
  docs-tool suite passes all 121 tests.
- **W6:** The developer index now links to `GPU_PROBE.md` beside the GPU capture guide.

Already landed when inspected: CI #115's declared output kind (`c8d3bae6b`), and
W4's `gpu_probe_bench` entries in both demo and execution registries. Neither was
reimplemented. W3's ray-tracing excerpt guards and W5's remaining GPU probe ports
were not addressed in this batch.

## Verification

Final `./gradlew docsGates checkPythonTools`: **BUILD SUCCESSFUL in 5m 1s**,
17 tasks executed, 24 up-to-date, on main `77b51a301` plus this patch.
Citation suite: 107 tests, zero failures/errors/skips; the API guard passes all
five tests. The tracked-file write guard also passes. Log:
`/tmp/field-backlog-final-confirmation.log`.

- `checkPythonTools`: 664 tests passed in the combined verification run.
- The initial `jvmTest --tests ...DocumentedApiEvaluationTest` selected the wrong
  task; this class belongs to `citationGates`.
- The first widened census correctly failed on the six previously unledgered
  names. Concurrent documentation merges also caused identical-byte tracked-file
  rewrites, so its tree-write guard was red.
- An overlapping full verification was stopped. A subsequent run collided with
  another session's citation result writer: `NoSuchFileException` for
  `in-progress-results-generic.bin`; the other daemon reported `EOFException`.
  Neither is counted as a passing citation run. Python tests and the tree-write
  guard passed in that run.
- The other session subsequently passed all citation assertions but its file guard
  caught the backlog note created by this session during its run. The final pass
  includes that note in its baseline; no files were edited during that pass.

No full JVM suite, live graphics recapture or deployment is claimed. Changes are
left uncommitted; unrelated documentation merges were preserved.
