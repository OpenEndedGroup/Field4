# Five follow-on backlog repairs

Base: `433856b32`, branch `codex/backlog-next-five-2026-09-20`. This batch is
isolated from main and does not modify the user's saved documents or running Field.

## Scope

1. **#40 — capture accounting.** The runner separately reports screenshot,
   video, audio and UI-drive operations. A UI drive producing both a still and a
   video counts once, not as two operations or as an ordinary video. Failed or
   interrupted captures do not increase the successful regeneration counters.
2. **#96 — Python imports preflight.** `requires="python:numpy"` (and dotted,
   case-sensitive module names) imports the module in the JEP-capable interpreter,
   never an unrelated builder interpreter. Candidate ordering now follows Field:
   explicit venv/executable, persisted choice, ambient environments, PATH.
   Python docs declare numpy and Plotly where used. Full recapture fails early;
   cached modes warn; `never` and excluded pages don't probe. Use environment
   overrides for docs builds: JVM-only system properties are outside this preflight.
3. **#98 — failed-capture write safety only.** All four runner capture methods
   stage their outputs beside the destination, run their existing validations,
   check the final environment verdict, then promote. Cache/counter updates are
   held until publication. Missing/empty outputs fail even if the child claims
   success. Multi-output drives validate every file before promotion and roll
   back preceding replacements if a later replacement fails. Each individual
   replacement is atomic, not the whole multi-file set across a machine crash.
   Authored snippets' arbitrary side effects/source-image paths are not sandboxed.
   **This does not close the separate four-puppet browser black-render issue:**
   a bad frame that passes the existing visual checks remains a rendering defect.
4. **#91 — multiline source attribution.** Comments within an unfinished
   expression no longer terminate it; `if` conditions require their branch,
   trailing `else` requires its expression, and wrapped file annotations remain
   header material. Live corpus census: **29 → 1** unattributed code lines.
   The remaining standalone enum separator is not falsely claimed as fixed.
   The ratchet is lowered from 32 to 1; tiling, reconstruction, balance and index
   agreement checks remain in force.
5. **#92 — compiler rejection memoization.** A reused source-backed rewrite
   evaluator remembers a rejected compile and its original diagnostic. Missing
   boxes, cancellation, runtime exceptions and self-box re-entry are not negative
   cache entries. `expression.invalidate()` / `evaluator.clearCache()` allow
   retry after repairing the box's imports/dependencies. Fresh evaluators still
   compile afresh; this is per-object lifetime, not a global source cache.

## Verification

- Source-partition regression failed before the repair, then passed with the
  existing partition tests and failure-memo tests. Whole-source census verified
  all files tile, reconstruct and balance; two existing index over-reports remain.
- Python suite before the final production-path tests: 596 tests, one skip, no
  failures. Capture-boundary tests subsequently passed all 11 tests, including
  the actual four ensure methods, staging paths, cache hits, missing output,
  environment interruption and rollback of a partial multi-file publication.
- Final `./gradlew --no-daemon certify`: **PASS in 27m 43s**.
  JVM: 4,095 total, 4,069 passed, 26 skipped, zero failures/errors.
  Python: 600 total, 599 passed, one skipped. All 12 capture-transaction tests
  passed, including retention of recovery files if rollback itself encounters an
  I/O failure. Citation, expected-failure, generated-reference, KDoc attachment,
  source-page, transcript and working-tree-write guards passed. Source pages:
  465 pages / 315,464 lines, verified verbatim against source. Corpus partition:
  1,498 files / 723,884 lines, one unattributed line and two existing index
  over-reports. Log: `/tmp/field4-next-five-certify.log`.
- Live rewrite smokes: self-box guard and two-box success passed. The new real
  compiler-rejection/recovery probe passed after correcting its test harness to
  emit the harness's required completion marker. It proves repeated failures are
  the same cached exception, repairs the idle host with a missing definition,
  verifies rejection remains cached until explicitly cleared, then succeeds.
  Logs: `/tmp/field4-next-five-live-rewrite.log` and
  `/tmp/field4-next-five-live-rewrite-repaired.log`.
- Real Python docs preflight: numpy and Plotly imports passed using this
  worktree's `.venv/bin/python3`, where JEP was resolved. No packages were installed
  into a foreign interpreter. Log: `/tmp/field4-next-five-python-preflight.log`.
  Windows interpreter discovery is covered structurally, not by a Windows run.
- Live docs capture: the authored default Fabricate scene rendered its three
  torii through the real runner. Inspected the resulting 2048 x 1257 PNG. A second
  real capture deliberately threw `EXPECTED failed recapture preservation probe`.
  The first image's SHA-256 and nanosecond mtime were identical before and after:
  `852e799b494734384fcc8319818f3b3ff6e1d40491f98ad2e0d879d5bb286a46`,
  `1789962103852078497`. The cache was unchanged; the runner counted one success
  and one explicitly expected failure with an existing fallback. All staging
  directories were removed. Seven environment samples reported AC/display
  availability with no interruption. Image:
  `build/next-five-docs-1789962075260712000.png`; log:
  `/tmp/field4-next-five-live-docs.log`; local driver:
  `build/verify_docs_next_five.py`. The runner stopped its owned Field afterward.

This is full repository certification plus targeted live checks, **not** a full
smoke catalogue, browser UI-regression run or full-site media recapture. No commit,
merge or push was requested for this follow-on batch, so the changes remain
unstaged in the isolated worktree. Concurrent main-checkout work was left alone.
