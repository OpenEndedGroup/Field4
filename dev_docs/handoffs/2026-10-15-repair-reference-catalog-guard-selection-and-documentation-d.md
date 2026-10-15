# Repair reference-catalog guard selection and documentation detector failures

- **Handoff:** `33e71cfd-e0ad-4011-a6a4-75ee7ccc51cb` (project)
- **Filed from:** https://openendedgroup.com/field4/how_field_is_built/
- **Filed by:** human, 2026-10-15 15:11
- **Status:** done, 2026-10-15

## Resolution

Repaired root-list parsing, complete-catalog guard expectations, literal Markdown anchors,
labelled KDoc links and trailing block-tag comparison. Reference and Python producers now select
`checkPythonTools`. The complete Gradle gate passed **1,264 Python tests, four skipped**, plus
all **26 standalone parser tests**. All 31 parser-dependent integration tests were exercised.
Plain wrapper calls select GraalVM **25.4.4.1.1**, pinned Kotlin compilation passed, and an
explicit 25.0.2 selection was rejected. See the pilot report and evidence for logs and skip reasons.

## Original report (before repair)

The box-code revision rehearsal's full Python tooling run on base `c483d75fd` exposed nine failures in unchanged reference/documentation checks. None is registered as an accepted failure, and this handoff does not waive them.

1. `tools.test_check_staged_generated_artifacts` reported six failures: macro-producer selection, outside-root selection, and four source-root subtests. `_reference_source_roots()` now returns `src/jvmMain/kotlin` plus comment strings such as `read the source` and excluded file names. Its multiline list regex consumes beyond the generator's new one-line `SOURCE_ROOTS` declaration. Repair extraction against the actual Kotlin declaration and update the old per-package-root expectations to the current complete-catalog contract.
2. `tools.test_docs_voice_detectors` reported two failing `check_anchors` subtests and one failing `check_kdoc_mirror` subtest. Direct diagnostic runs report one `#custom:linkName` anchor in `public/docs/reference/visualization.md`, and overview mismatches for `SolidGradient.kt` in `graphics.md` (93 source lines versus 98 published) and `keyframeBrowsing.kt` in `workspace.md` (one differing line). Determine which are stale generated pages versus detector handling, then repair the owning source/generator/detector.

The relevant guard implementation, guard tests, reference generator and affected reference pages are byte-identical to HEAD in the rehearsal worktree. The pilot edits only a two-box transcript and its generated code, the revision rubric, the new census, and internal evidence. Its new test initially made `dev_docs/GUARD_CORPORA.md` stale; that count was regenerated and all five count tests then passed. This count failure is separate from the nine above.

Evidence and hashes are in `dev_docs/agents/box_code_revision_pilot/evidence.json`; see the report alongside it. The full run reported 1,213 passed, ten failures and 35 skipped before the count repair. The focused census (seven tests), count suite (five), source transcript compile/replay, layouts, idioms and actual runtime rehearsals passed. The full suite is not green.
