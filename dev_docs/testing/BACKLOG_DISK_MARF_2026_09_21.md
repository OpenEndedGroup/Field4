# Backlog: run disk budgets and Marf clock registration

Follow-up to `BACKLOG_COMPLETION_CAPTURES_2026_09_21.md`, based on main `61b7de482`.

## Disk safeguards

The existing smoke/docs environment monitor now observes the checkout volume on
every sample, including on non-macOS systems. It admits the first action only when
free space covers floor + reserve + planned whole-run growth, and protects floor +
reserve throughout the run. Defaults: 8 GiB floor, zero reserve, zero supplied growth
estimate. The configuration variables and limitations are in `TESTING_STRATEGY.md`.

Reports preserve initial/minimum free bytes and the largest observed volume loss.
They explicitly do not attribute that loss to Field, call it a checkout footprint,
or call it reclaimable space. This matters because the nightly journal's 2026-09-21
measurement was about 22 GiB of volume loss versus 6.82 GiB in its three worktrees.
The reaper's planned-cost help now names whole-run growth, not just checkout size.
No cleanup, permission change, worktree deletion, or machine-wide process action
was added or performed.

Evidence:

- 34 environment/budget tests passed, including 12 new cases: admission versus
  ongoing reserve, nonfinite configuration, failed disk probes, cross-platform
  enforcement, recovery not erasing an interruption, and smoke/docs refusals before
  server startup. `/tmp/field4-disk-budget-tests.log`.
- Real runner, intentionally impossible `FIELD_VERIFY_DISK_PLANNED_GIB=1000000`:
  exit **2**, **NOT RUN**, zero test failures, no listener on port 9585. It measured
  11.09 GiB available and refused before starting Field, without allocating that
  requested budget. `/tmp/field4-disk-budget-refusal.log` and
  `build/disk-budget/refused.json`.
- The subsequent real Marf run used the normal 8-GiB floor, admitted successfully,
  and reported 11,882,999,808 initial/minimum free bytes with zero observed volume
  loss. Free space increased during the run; this is not a reclaim claim.

This is sampled protection, not a filesystem reservation or a guarantee against
sudden concurrent exhaustion. Raw Gradle, standalone backdoor, and UI-regression
commands remain outside this smoke/docs monitor.

## Marf field clocks

Moved the existing YAML, unchanged, from `agents/artifacts/marf_field_clocks/` to
`agents/transcripts/marf_field_clocks.yaml`. Registered it in `EXECUTE`, added its
gallery entry, generated its starting-point bundle, and regenerated the completion
manifest and guard census. Counts are now 144 transcripts, 81 in EXECUTE, and 139
materialized starting points. The original real screenshots remain untouched.
Final review also corrected the gallery introduction's pre-existing four-versus-five
exclusion count: `mega_texture_wall` needs an external renderer and supplied images.
The commit hook rechecks the index and citation contracts for that prose correction.

The first documentation gate correctly failed: `displayClearAll` was still listed
as compiled-but-never-evaluated, but the newly registered CPU-only transcript now
executes it. Removed that stale debt row; the focused API-coverage gate then passed.
This is execution coverage, not a new assertion about browser channel clearing.

Actual registered execution:

```text
DEMO_TRANSCRIPT_ONLY=marf_field_clocks AFIELD_DEMO_TRANSCRIPT_VERBOSE=1
  tools/run_field_tests.py --refresh-catalog --id examples.demo-transcript.exec
  --port 9585 --no-reap --results build/marf-field-clocks/results.json
```

Run through the repository uv backdoor environment: **PASS**, 26.5 s, seven
operations applied, three successful box outputs, zero error outputs. The runner
explicitly reported **FILTERED**: this exercised one transcript, not all 144.
Log: `/tmp/field4-marf-clocks-execute.log`. Its owned server stopped at completion;
port 9585 was free afterward.

## Final verification

`./gradlew --no-daemon docsGates checkPythonTools` passed in **5m 58s** after the
ledger repair. Fresh citation/documentation tests: **106 passed**. Python tooling:
**620 passed / 621 run, one skipped**. Transcript/index/layout, generated-reference,
completion-fixture, source-partition, and tracked-file-write guards passed.
Log: `/tmp/field4-disk-marf-final-gates.log`.

The full JVM suite and full smoke catalog were not rerun for this Python-policy and
transcript-registration batch; the preceding batch's full certification remains
recorded separately. No new browser or native screenshots were published.
