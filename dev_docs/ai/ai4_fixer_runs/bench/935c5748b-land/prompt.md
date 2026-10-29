You are the landing queue's fixer for the Field repository. The lander merged the
branches below onto main and the guard failed. You are in the lander's own worktree, on the exact
merged tree that failed; build output is warm, and nothing else is building here. AGENTS.md in this
directory is the repo guide.

Your job is a judgement call: is this failure a small, mechanical slip that one of these branches
obviously made (a count or number not updated, a citation pointing at something that moved or was
renamed, a KDoc in the wrong place, a missing entry in a list a guard checks, a typo in a reference)?

- If yes: fix it with the smallest edit that is plainly right, then re-run the narrowest command that
  reproduces the failing check (one test class, one checker script, one Gradle task) and confirm it
  passes. Do not run the whole guard; the lander does that afterwards.
- If the failure is about behaviour, timing, flakiness, design, or anything you would have to explain
  with "probably", or it needs more than a few lines, do not edit anything; say why. Your diagnosis
  goes into the bounce and is useful on its own.

Hard rules (the lander rejects a fix that breaks them):
- Do not run git commands that change anything: no commit, stash, checkout, reset, merge, or branch.
  The lander commits your edit itself.
- Do not edit dev_docs/testing/expected_*.json, tools/check_*.py, tools/land.py, tools/merge_regen.py,
  the git hooks, or the Gradle build files; do not delete files; do not disable or skip a test.
- Do not clear a finding by widening the guard that made it: no new allowlist entry, exemption, root
  or known-benign site in the check's own source. Change what it flagged. Adding the row or entry a
  ledger check asks for (a missing inventory row, a COMPILE_ONLY entry, a corpus name) is not widening.
- At most 6 files and 60 changed lines.
- The fix must belong to ONE branch below: the one whose change the failure follows from.

End your reply with exactly one line, and nothing after it:
FIXER-VERDICT: {"verdict": "fix" or "decline", "culprit": "<branch name>" or null, "reason": "<one sentence>"}

main before this round: e70ec3e7a0df50a915ddc6bf28121f930a719418

Branches in the round, each merged as its own commit:

## handoffs/code-20261016-1144 (dc4a4713c, by Marc)
Line notes for Kotlin, Morpho and Nano boxes
public/docs/fieldml_host_bridge.md                                          |  2 --
 public/docs/language_extensions.md                                          | 11 +++---
 public/docs/stacksl_examples.md                                             |  5 ++-
 src/commonMain/kotlin/afield/app/protocol/rpc.kt                            | 11 ++++++
 src/commonMain/resources/bootstrapMonaco.js                                 | 12 +++++++
 src/commonMain/resources/stacksl.js                                         | 63 ++++++++++++++++++++++++++++++---
 src/jvmMain/kotlin/afield/app/server/Server.kt                              | 20 +++++++++++
 src/jvmMain/kotlin/afield/lang/fieldml/FieldMLMacroLayer.kt                 |  9 +++++
 src/jvmMain/kotlin/afield/lang/fieldml/core/HostBridge.kt                   | 33 +++++++++++++++++
 src/jvmMain/kotlin/afield/lang/fieldml/type/HostTypes.kt                    | 70 ++++++++++++++++++++++++++++++++-----
 src/jvmMain/kotlin/afield/lang/fieldml/type/Inferencer.kt                   | 49 ++++++++++++++++++++++++--
 src/jvmMain/kotlin/afield/lang/morpho/MorphoInterface.kt                    | 66 +++++++++++++++++++++++++++++++++-
 src/jvmMain/kotlin/afield/lang/nano/NanoInterface.kt                        | 54 +++++++++++++++++++++++++++-
 src/jvmMain/kotlin/afield/runtime/execution/execution.kt                    |  2 ++
 src/jvmMain/kotlin/afield/runtime/kotlin/KotlinLineNotes.kt                 | 56 +++++++++++++++++++++++++++++
 src/jvmTest/kotlin/afield/lang/fieldml/tests/FieldMLAfieldFourReachTest.kt  | 36 ++++++++-----------
 .../kotlin/afield/lang/fieldml/tests/FieldMLLambdaExpectedTypeTest.kt       | 32 +++++++++++++++++
 src/jvmTest/kotlin/afield/lang/fieldml/tests/FieldMLNamedArgumentTest.kt    | 29 ++++-----------
 src/jvmTest/kotlin/afield/lang/fieldml/tests/FieldMLSourceMacroTest.kt      |  2 +-
 src/jvmTest/kotlin/afield/lang/morpho/MorphoLanguageTest.kt                 | 38 ++++++++++++++++++++
 src/jvmTest/kotlin/afield/lang/nano/NanoLineNotesTest.kt                    | 39 +++++++++++++++++++++
 tools/run_ui_regressions.py                                                 | 24 +++++++++++++
 tools/ui_regressions/kotlin-line-notes-check.kt                             | 11 ++++++
 tools/ui_regressions/kotlin-line-notes.js                                   |  6 ++++
 tools/ui_regressions/kotlin-line-notes.kt                                   |  6 ++++
 tools/ui_regressions/stacksl-slot-colour.glsl                               |  2 ++
 tools/ui_regressions/stacksl-slot-colour.js                                 | 15 ++++++++
 tools/ui_regressions/stacksl-slot-colour.kt                                 |  3 ++
 28 files changed, 634 insertions(+), 72 deletions(-)
Its commits (the branch is all of them, not only the tip; its whole change is `git diff e70ec3e7a0df...dc4a4713c90a`):
dc4a4713c Line notes for Kotlin, Morpho and Nano boxes
4fe36a2dc FieldML: type callback parameters in named records and arity-only overloads
44f6dfe96 Colour StackSL slot strings in the editor without a #stacksl header

Failing tests (first): ServerSurfaceLedgerTest.thePerFileCommandCountsOnThePageMatchTheCode, ServerSurfaceLedgerTest.everyWebSocketCommandIsInTheLedgerAndEveryLedgerRowIsACommand
## ServerSurfaceLedgerTest.thePerFileCommandCountsOnThePageMatchTheCode
java.lang.AssertionError: The per-file command count surface and dev_docs/server_surface_inventory.md disagree.

Row shape on the page:  | `<file>` | <count> |

1 present on both but describing different things. Read which cell
moved before editing: a guard going to `none` is a regression rather than a
documentation update, and code that moved file may have changed who can reach it:
  ~ `Server.kt`
      page: 52
      code: 53

The page is the fixture on purpose: a claim about Field's exposure should not be
able to outlive the code it describes. Read what changed before editing the table.

## ServerSurfaceLedgerTest.everyWebSocketCommandIsInTheLedgerAndEveryLedgerRowIsACommand
java.lang.AssertionError: The /ws command surface and dev_docs/server_surface_inventory.md disagree.

Row shape on the page:  | `<command>` | `<file>` |

1 in the CODE but not on the PAGE — add a row, and decide first
whether the new surface needed a guard. Every unguarded entry is reachable by
native clients if LAN binding is enabled; cross-site browser access also requires
disabling the default application-wide origin guard:
  + | `stacksl-regions` | Server.kt |

The page is the fixture on purpose: a claim about Field's exposure should not be
able to outlive the code it describes. Read what changed before editing the table.



The end of the landing log:
Ran 19 tests in 1.482s

OK

=== tools.test_whisper_worker
..
----------------------------------------------------------------------
Ran 2 tests in 0.300s

OK

Python tool test suites:
  tools.test_agent_check_parse                       2 passed /   2 run
  tools.test_agents_md                               4 passed /   4 run
  tools.test_ai_chat_persistence                     0 passed /   0 run
  tools.test_ai_tool_usage                           5 passed /   5 run
  tools.test_ai_usage                                3 passed /   3 run
  tools.test_automation_workspace                   10 passed /  10 run
  tools.test_backdoor_managed_startup               20 passed /  20 run
  tools.test_build_dependency_rules                  3 passed /   3 run
  tools.test_build_zensical_run                     13 passed /  13 run
  tools.test_build_zensical_workspace_markdown       6 passed /   6 run
  tools.test_canonical_graphics_index                9 passed /   9 run
  tools.test_capture_transaction                    13 passed /  13 run
  tools.test_check_box_idioms                       20 passed /  20 run
  tools.test_check_doc_assets                       19 passed /  19 run
  tools.test_check_doc_offline_links                16 passed /  16 run
  tools.test_check_doc_site_links                   44 passed /  45 run, 1 skipped
  tools.test_check_module_imports                   14 passed /  14 run
  tools.test_check_native_selfcontained             20 passed /  20 run
  tools.test_check_offline_assets                   13 passed /  13 run
  tools.test_check_staged_generated_artifacts       41 passed /  41 run
  tools.test_check_study_tools                       7 passed /   7 run
  tools.test_check_test_tree_writes                 32 passed /  32 run
  tools.test_completion_sidecar                     13 passed /  13 run
  tools.test_conversation_demos                      9 passed /   9 run
  tools.test_demo_build_scripts                      3 passed /   3 run
  tools.test_discover_field_tests                   16 passed /  16 run
  tools.test_docs_capture_audit                     34 passed /  34 run
  tools.test_docs_duplicate_embeds                   3 passed /   3 run
  tools.test_docs_hooks_capture_sources              3 passed /   3 run
  tools.test_docs_hooks_citations                   14 passed /  14 run
  tools.test_docs_hooks_fence_markers                5 passed /   5 run
  tools.test_docs_hooks_inline_code                  9 passed /   9 run
  tools.test_docs_hooks_repo_source_links           12 passed /  12 run
  tools.test_docs_native_prerequisites              36 passed /  36 run
  tools.test_docs_notes                             66 passed /  66 run
  tools.test_docs_runner_completion_backend         19 passed /  19 run
  tools.test_docs_runner_failure_reporting          27 passed /  27 run
  tools.test_docs_runner_graphics_launch             7 passed /   7 run
  tools.test_docs_runner_ui_clip                     5 passed /   5 run
  tools.test_docs_runner_window_outputs              4 passed /   4 run
  tools.test_docs_voice_detectors                   14 passed /  14 run
  tools.test_execution_receipts                      4 passed /   4 run
  tools.test_expected_test_failures                  9 passed /   9 run
  tools.test_export_particle_video_docs              2 passed /   2 run
  tools.test_field_agent                             3 passed /   3 run
  tools.test_field_backdoor                        117 passed / 117 run
  tools.test_field_inspection                        5 passed /   5 run
  tools.test_field_kit                              17 passed /  17 run
  tools.test_field_live_session_reaping              4 passed /   4 run
  tools.test_field_python_env                        4 passed /   4 run
  tools.test_field_sh_launcher                      38 passed /  39 run, 1 skipped
  tools.test_fieldcv                                15 passed /  15 run
  tools.test_guard_corpora                           5 passed /   5 run
  tools.test_inventory_box_code                     12 passed /  12 run
  tools.test_land                                   31 passed /  31 run
  tools.test_learned_shader_build                    7 passed /   7 run
  tools.test_loc_history                            22 passed /  24 run, 2 skipped
  tools.test_merge_regen                             6 passed /   6 run
  tools.test_native_download_cache                   3 passed /   3 run
  tools.test_neural_docs_gating                      6 passed /   6 run
  tools.test_open_dlss_nr                            6 passed /   6 run
  tools.test_oscquery_docs_ports                     3 passed /   3 run
  tools.test_particle_tracker                        4 passed /   4 run
  tools.test_prune_gradle_daemon_logs                2 passed /   2 run
  tools.test_ray_tracing_docs                       10 passed /  10 run
  tools.test_reap_merged_worktrees                  73 passed /  73 run
  tools.test_refactor                               57 passed /  57 run
  tools.test_refactor_pass                          20 passed /  20 run
  tools.test_refactor_session                       11 passed /  11 run
  tools.test_refactor_symbols                       15 passed /  15 run
  tools.test_repository_root_inventory               4 passed /   4 run
  tools.test_run_field_tests_native_prerequisites    2 passed /   2 run
  tools.test_run_field_tests_ordering               24 passed /  24 run
  tools.test_run_field_tests_output                  8 passed /   8 run
  tools.test_run_field_tests_port_ownership         14 passed /  14 run
  tools.test_run_field_tests_reaper                 34 passed /  34 run
  tools.test_runner_fence_image_widths               2 passed /   2 run
  tools.test_studies_page                            3 passed /   3 run
  tools.test_sync_field_audio_docs                   2 passed /   2 run
  tools.test_verification_environment               37 passed /  37 run
  tools.test_verification_lock_gradle                7 passed /   7 run
  tools.test_verification_runtime                   19 passed /  19 run
  tools.test_whisper_worker                          2 passed /   2 run
  TOTAL                                            1282 passed / 1286 run, 4 skipped

> Task :checkBoxIdioms
warning: `VIRTUAL_ENV=/Users/marc/field4/.venv` does not match the project environment path `.venv` and will be ignored; use `--active` to target the active environment instead
check_box_idioms: clean (6 file(s))

> Task :kotlinKotlinNpmCachesSetup
> Task :kotlinNodeJsSetup UP-TO-DATE
> Task :kotlinRestoreYarnLock UP-TO-DATE
> Task :kotlinYarnSetup UP-TO-DATE
> Task :jsPackageJson UP-TO-DATE
> Task :jsTestPackageJson UP-TO-DATE
> Task :jsPublicPackageJson UP-TO-DATE
> Task :jsTestPublicPackageJson UP-TO-DATE
> Task :kotlinPackageJsonUmbrella UP-TO-DATE
> Task :rootPackageJson UP-TO-DATE
> Task :kotlinNpmInstall UP-TO-DATE
> Task :compileKotlinJs UP-TO-DATE
> Task :jsProcessResources UP-TO-DATE
> Task :jsMainClasses UP-TO-DATE
> Task :compileDevelopmentExecutableKotlinJs UP-TO-DATE
> Task :jsDevelopmentExecutableCompileSync UP-TO-DATE
> Task :kotlinStoreYarnLock UP-TO-DATE
> Task :jsBrowserDevelopmentWebpack UP-TO-DATE
> Task :jvmJar UP-TO-DATE
> Task :compileTestKotlinJvm UP-TO-DATE
> Task :compileJvmTestJava NO-SOURCE
> Task :jvmTestProcessResources UP-TO-DATE
> Task :processJvmTestResources SKIPPED
> Task :jvmTestClasses UP-TO-DATE

> Task :recordTestTreeBaseline
warning: `VIRTUAL_ENV=/Users/marc/field4/.venv` does not match the project environment path `.venv` and will be ignored; use `--active` to target the active environment instead
tree-write guard: baseline of 11079 tracked file(s), 0 already dirty -> /Users/marc/field4-worktrees/lander/build/test-tree-writes/baseline.json

> Task :citationGates
WARNING: Using incubator modules: jdk.incubator.vector
citationGates: running ServerSurfaceLedgerTest

ServerSurfaceLedgerTest > thePerFileCommandCountsOnThePageMatchTheCode FAILED
    java.lang.AssertionError at ServerSurfaceLedgerTest.kt:772

ServerSurfaceLedgerTest > everyWebSocketCommandIsInTheLedgerAndEveryLedgerRowIsACommand FAILED
    java.lang.AssertionError at ServerSurfaceLedgerTest.kt:772
citationGates: [1/14] ServerSurfaceLedgerTest FAILURE in 5.3s
citationGates: running CloseNodeCitationDriftTest
citationGates: [2/14] CloseNodeCitationDriftTest SUCCESS in 3.0s
citationGates: running CitationContractTest
citationGates: [3/14] CitationContractTest SUCCESS in 4.9s
citationGates: running DerivedArithmeticContractTest
citationGates: [4/14] DerivedArithmeticContractTest SUCCESS in 5.0s
citationGates: running DocsFenceTreatmentTest
citationGates: [5/14] DocsFenceTreatmentTest SUCCESS in 0.3s
citationGates: running DocsGatesWiringTest
citationGates: [6/14] DocsGatesWiringTest SUCCESS in 0.1s
citationGates: running DocumentedApiEvaluationTest
citationGates: [7/14] DocumentedApiEvaluationTest SUCCESS in 8.6s
citationGates: running GateInputDeclarationTest
citationGates: [8/14] GateInputDeclarationTest SUCCESS in 0.1s
citationGates: running ImpossibilityClaimContractTest
citationGates: [9/14] ImpossibilityClaimContractTest SUCCESS in 7.6s
citationGates: running LanguagesCategoryTest
citationGates: [10/14] LanguagesCategoryTest SUCCESS in 0.9s
citationGates: running PersonalBoundaryTest
citationGates: [11/14] PersonalBoundaryTest SUCCESS in 5.7s
citationGates: running ProseNumeralContractTest
citationGates: [12/14] ProseNumeralContractTest SUCCESS in 4.2s
citationGates: running SmallLanguageAnatomyTest
citationGates: [13/14] SmallLanguageAnatomyTest SUCCESS in 0.5s
citationGates: running TranscriptStackAutoCompileTest
citationGates: [14/14] TranscriptStackAutoCompileTest SUCCESS in 0.4s

113 tests completed, 2 failed

> Task :citationGates FAILED

> Task :checkTestTreeWrites
warning: `VIRTUAL_ENV=/Users/marc/field4/.venv` does not match the project environment path `.venv` and will be ignored; use `--active` to target the active environment instead
tree-write guard: citationGates left the working tree as it found it.

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':citationGates'.
> There were failing tests. See the report at: file:///Users/marc/field4-worktrees/lander/build/reports/tests/citationGates/index.html

* Try:
> Run with --scan to get full insights from a Build Scan (powered by Develocity).

BUILD FAILED in 6m 28s
32 actionable tasks: 9 executed, 23 up-to-date

  A generated artifact is stale after this merge.
  It was not your commit that broke it and no hook ran on the clone that did:
  .git/hooks is per-clone and Git never transports it.

  Reproduce:  ./gradlew checkKDocAttachment checkPythonTools checkBoxIdioms citationGates
  The failing task names the artifact and the command that regenerates it.
  Regenerate and commit that, or `main` is red until someone else finds it.

  Set FIELD_SKIP_MERGE_GUARD=1 to skip this check.

merge_regen: a guard failed on the batch

land: 2026-10-16T13:52:09+02:00 red; asking the fixer (up to 15 min) about handoffs/code-20261016-1144

What the fixer did on recent reds (for calibration):
- fixed: The branch put @Synchronized on the same line as markLane in CoroutineProfiling.kt, which made checkKDocAttachment drop its KDoc, so I moved the annotation onto its own line.
- fixed: The branch put staleCommittedFigures and its doc comment between Raster's existing doc comment and the class, leaving two doc comments stacked, so I moved Raster's comment back above the class.
- fixed: The branch put the new diverged property and its doc comment between runPostIntegration's doc comment and the function, with @Volatile on the declaration line, so I moved that doc comment back and broke the annotation onto its own line.
- fixed: The branch put SONIC_PI_PERIOD_SEARCH and patternPeriod between patternEventsForCycles and its doc comment, leaving two doc comments stacked, so I moved that doc comment back above the function.
- fixed: The branch changed a StackSL cheat-sheet comment from 'wrong:' to 'refused:' without regenerating stacksl_words.md, so I regenerated it with tools/stacksl_conversation_documents.py and the --check now passes.
- fixed: The branch left two doc comments stacked (simpleGLTFSupport.kt, FunctionFit.kt) and wrote a Python import inside a Kotlin string that PersonalBoundaryTest took for a Kotlin import; I moved the blocks and reworded the import.
- fixed: Regenerating after this branch created a new reference page, execution.md, with no entry in the hand-maintained nav in public/zensical.yml, so I added the one nav line and citationGates passes.
- fixed: The branch put warnedRadius/effectiveRadius between install's KDoc and install with @Volatile on the declaration line, so I moved the KDoc back above install and broke the annotation onto its own line.

This replay's checkout cannot build: the `gradle` command is not available, so verify by reading the check's source and rerunning any `tools/` Python check that covers it.
