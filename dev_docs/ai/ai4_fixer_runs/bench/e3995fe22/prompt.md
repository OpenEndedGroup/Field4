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
- At most 6 files and 60 changed lines.
- The fix must belong to ONE branch below: the one whose change the failure follows from.

End your reply with exactly one line, and nothing after it:
FIXER-VERDICT: {"verdict": "fix" or "decline", "culprit": "<branch name>" or null, "reason": "<one sentence>"}

main before this round: 6205d63932698806809c37f40db67a208d73cdbf

Branches in the round, each merged as its own commit:

## claude/canvas-flight-filming (079c5ef06, by Marc)
Merge main into claude/canvas-flight-filming; list the filming /ws commands
 dev_docs/GUARD_CORPORA.md                                                   |   4 +-
 dev_docs/agents/FIELD_BACKDOOR.md                                           |  14 ++
 dev_docs/agents/transcripts/docs_capture/canvas_flight.yaml                 |  71 ++++++++
 dev_docs/kdoc_worklist.md                                                   |   7 +-
 dev_docs/server_surface_inventory.md                                        |  14 +-
 public/docs/assets/filming_the_canvas/flight.mp4                            | Bin 0 -> 1682769 bytes
 public/docs/filming_the_canvas.md                                           | 158 ++++++++++++++++++
 public/docs/logging.md                                                      |   2 +-
 public/docs/moving_around_the_canvas.md                                     |   3 +
 public/docs/reference/index.md                                              |  12 +-
 public/docs/reference/visualization.md                                      |  54 +++++-
 public/docs/reference/workspace.md                                          | 150 +++++++++++------
 public/zensical.yml                                                         |   3 +
 src/commonMain/kotlin/afield/app/canvas/bookmarkData.kt                     | 101 ++++++++++++
 src/commonMain/kotlin/afield/app/canvas/canvasFlight.kt                     | 177 ++++++++++++++++++++
 src/commonMain/kotlin/afield/app/protocol/rpc.kt                            | 109 ++++++++++++
 src/commonMain/resources/field.css                                          |  18 ++
 src/commonMain/resources/threejs_viewer.html                                |  56 ++++++-
 src/jsMain/kotlin/afield/app/canvas/bookmarks.kt                            | 120 +++++---------
 src/jsMain/kotlin/afield/app/canvas/canvasFlightClient.kt                   | 321 ++++++++++++++++++++++++++++++++++++
 src/jsMain/kotlin/afield/app/client/Main.kt                                 |  13 ++
 src/jvmMain/kotlin/afield/app/server/agent_backdoor.kt                      |  51 ++++++
 src/jvmMain/kotlin/afield/document/graph/Boxes.kt                           |   1 +
 src/jvmMain/kotlin/afield/tools/automation/operation_transcript.kt          |   4 +-
 src/jvmMain/kotlin/afield/tools/docs/canvasFlightRecording.kt               | 313 +++++++++++++++++++++++++++++++++++
 src/jvmMain/kotlin/afield/tools/docs/pageFilm.kt                            | 114 +++++++++++++
 src/jvmMain/kotlin/afield/tools/docs/screenshot.kt                          |  61 +++++++
 src/jvmMain/kotlin/afield/ui/film/canvasFlight.kt                           | 174 +++++++++++++++++++
 src/jvmTest/kotlin/afield/app/canvas/CanvasFlightTimelineTest.kt            | 190 +++++++++++++++++++++
 .../src/main/kotlin/afield/tools/codeuse/AaCompletion.kt                    |   1 +
 tools/field_backdoor.py                                                     | 172 ++++++++++++++++++-
 tools/run_ui_regressions.py                                                 |  23 +++
 tools/test_field_backdoor.py                                                |   2 +
 tools/ui_regressions/canvas-flight-check.js                                 |  48 ++++++
 tools/ui_regressions/canvas-flight-model.kt                                 |  12 ++
 tools/ui_regressions/canvas-flight-plan.kt                                  |  20 +++
 tools/ui_regressions/canvas-flight-setup.js                                 |  45 +++++
 tools/ui_regressions/canvas-flight-title.kt                                 |   7 +
 38 files changed, 2499 insertions(+), 146 deletions(-)

The end of the landing log:
...................
----------------------------------------------------------------------
Ran 19 tests in 1.617s

OK

=== tools.test_whisper_worker
..
----------------------------------------------------------------------
Ran 2 tests in 2.384s

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
  tools.test_check_box_idioms                       22 passed /  22 run
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
  tools.test_conversation_packagers                  4 passed /   4 run
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
  tools.test_field_backdoor                        121 passed / 121 run
  tools.test_field_inspection                        5 passed /   5 run
  tools.test_field_kit                              17 passed /  17 run
  tools.test_field_live_session_reaping              4 passed /   4 run
  tools.test_field_python_env                        4 passed /   4 run
  tools.test_field_sh_launcher                      38 passed /  39 run, 1 skipped
  tools.test_fieldcv                                15 passed /  15 run
  tools.test_guard_corpora                           5 passed /   5 run
  tools.test_inventory_box_code                     13 passed /  13 run
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
  tools.test_run_python_tests                        1 passed /   1 run
  tools.test_runner_fence_image_widths               2 passed /   2 run
  tools.test_studies_page                            3 passed /   3 run
  tools.test_sync_field_audio_docs                   2 passed /   2 run
  tools.test_verification_environment               37 passed /  37 run
  tools.test_verification_lock_gradle                7 passed /   7 run
  tools.test_verification_runtime                   19 passed /  19 run
  tools.test_whisper_worker                          2 passed /   2 run
  TOTAL                                            1294 passed / 1298 run, 4 skipped

> Task :checkBoxIdioms FAILED
warning: `VIRTUAL_ENV=/Users/marc/field4/.venv` does not match the project environment path `.venv` and will be ignored; use `--active` to target the active environment instead
check_box_idioms: 3 finding(s) in 1 file(s) (re-run with --fix)
public/docs/filming_the_canvas.md:29: [list-literal] write the list as a collection literal, `[a, b, c]`
public/docs/filming_the_canvas.md:61: [list-literal] write the list as a collection literal, `[a, b, c]`
public/docs/filming_the_canvas.md:105: [list-literal] write the list as a collection literal, `[a, b, c]`

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
tree-write guard: baseline of 12461 tracked file(s), 0 already dirty -> /Users/marc/field4-worktrees/lander/build/test-tree-writes/baseline.json

> Task :citationGates
WARNING: Using incubator modules: jdk.incubator.vector
citationGates: running ServerSurfaceLedgerTest
citationGates: [1/14] ServerSurfaceLedgerTest SUCCESS in 5.0s
citationGates: running CloseNodeCitationDriftTest
citationGates: [2/14] CloseNodeCitationDriftTest SUCCESS in 1.9s
citationGates: running CitationContractTest
citationGates: [3/14] CitationContractTest SUCCESS in 3.4s
citationGates: running DerivedArithmeticContractTest
citationGates: [4/14] DerivedArithmeticContractTest SUCCESS in 4.7s
citationGates: running DocsFenceTreatmentTest
citationGates: [5/14] DocsFenceTreatmentTest SUCCESS in 0.3s
citationGates: running DocsGatesWiringTest
citationGates: [6/14] DocsGatesWiringTest SUCCESS in 0.1s
citationGates: running DocumentedApiEvaluationTest
citationGates: [7/14] DocumentedApiEvaluationTest SUCCESS in 8.4s
citationGates: running GateInputDeclarationTest
citationGates: [8/14] GateInputDeclarationTest SUCCESS in 0.1s
citationGates: running ImpossibilityClaimContractTest
citationGates: [9/14] ImpossibilityClaimContractTest SUCCESS in 8.1s
citationGates: running LanguagesCategoryTest
citationGates: [10/14] LanguagesCategoryTest SUCCESS in 1.1s
citationGates: running PersonalBoundaryTest
citationGates: [11/14] PersonalBoundaryTest SUCCESS in 7.0s
citationGates: running ProseNumeralContractTest
citationGates: [12/14] ProseNumeralContractTest SUCCESS in 5.6s
citationGates: running SmallLanguageAnatomyTest
citationGates: [13/14] SmallLanguageAnatomyTest SUCCESS in 0.7s
citationGates: running TranscriptStackAutoCompileTest
citationGates: [14/14] TranscriptStackAutoCompileTest SUCCESS in 0.7s

> Task :checkTestTreeWrites
warning: `VIRTUAL_ENV=/Users/marc/field4/.venv` does not match the project environment path `.venv` and will be ignored; use `--active` to target the active environment instead
tree-write guard: citationGates left the working tree as it found it.

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':checkBoxIdioms' (registered in build file 'build.gradle.kts').
> Process 'command 'uv'' finished with non-zero exit value 1

* Try:
> Run with --stacktrace option to get the stack trace.
> Run with --info or --debug option to get more log output.
> Run with --scan to get full insights from a Build Scan (powered by Develocity).
> Get more help at https://help.gradle.org.

BUILD FAILED in 8m 2s
33 actionable tasks: 10 executed, 23 up-to-date

  A generated artifact is stale after this merge.
  It was not your commit that broke it and no hook ran on the clone that did:
  .git/hooks is per-clone and Git never transports it.

  Reproduce:  ./gradlew checkKDocAttachment checkAgentTranscriptIndex checkPythonTools checkBoxIdioms citationGates
  The failing task names the artifact and the command that regenerates it.
  Regenerate and commit that, or `main` is red until someone else finds it.

  Set FIELD_SKIP_MERGE_GUARD=1 to skip this check.

merge_regen: a guard failed on the batch

land: 2026-10-19T18:52:33+02:00 red; asking the fixer (up to 15 min) about claude/canvas-flight-filming

What the fixer did on recent reds (for calibration):
- declined: regenerateAgentTranscriptStartingPoints exited 1 without printing the actual error (most likely from the new polyvector_explorer transcript), so there is nothing specific to fix without a long rerun.
- fixed: The new polyvector page teaches byCreatingParallelCurves, byCreatingNormalLines and byWaving, which nothing in CI ran, so I added a jvmTest in PolyvectorTest calling all three and DocumentedApiEvaluationTest now passes.
- fixed: The new relaunchedVideoTargetReady KDoc was inserted between videoCapturePreparation's KDoc and its function, stacking two blocks; moving the original block back above videoCapturePreparation makes checkKDocAttachment pass.
- fixed: The branch put its new transcript's index entry in a section marked compile-only, though CI runs that transcript, and used listOf where the idiom check wants a list literal; I moved the entry, swapped in the literal, and both checks now pass.
- fixed: CREATURE_EVOLUTION.md cited a path under src/personal/; I reworded it to drop the private path, and PersonalBoundaryTest now passes.
- declined: checkPythonTools failed with only a generic uv exit 1 in the log, and the filtered re-run of the suite showed only deliberate capture-failure messages, so no failing test or branch slip was found to fix.
- declined: The reds come from at least two branches: canvas-flight-filming's two unlisted /ws commands (which also need a guard decision) and eagle-flow-topology's never-run videoFrames API, plus two task failures the log shows no error for, so no single-branch fix is possible.
- fixed: TemporalSession.kt put `@Volatile var revision = 0L; private set` on one line, which made the KDoc extractor drop its comment; splitting the annotation onto its own line makes checkKDocAttachment pass.

This replay's checkout cannot build: the `gradle` command is not available, so verify by reading the check's source and rerunning any `tools/` Python check that covers it.
