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

main before this round: 26724ca25cb8cc126c6cf9b4f6a6ce2744e1a800

Branches in the round, each merged as its own commit:

## claude/demo-timeline-harvest (73948cd20, by Marc)
Completion corpus: give the harvest graph a root and a session
src/jvmMain/kotlin/afield/runtime/completion/CompletionCorpus.kt            | 25 +++++++++++++++++-
 .../kotlin/afield/runtime/completion/CompletionCorpusBoxGraphTest.kt        | 51 +++++++++++++++++++++++++++++++++++++
 tools/completion-corpus/boxes/demo_document__demo_brightness.preamble.kt    |  3 +++
 tools/completion-corpus/boxes/demo_document__demo_draw.preamble.kt          |  3 +++
 tools/completion-corpus/boxes/demo_document__demo_flower.preamble.kt        |  3 +++
 tools/completion-corpus/boxes/demo_document__demo_leaf.preamble.kt          |  3 +++
 tools/completion-corpus/boxes/demo_document__demo_metrics.preamble.kt       |  3 +++
 tools/completion-corpus/boxes/demo_document__demo_notes.preamble.kt         |  3 +++
 tools/completion-corpus/boxes/demo_document__demo_rosette.preamble.kt       |  3 +++
 tools/completion-corpus/boxes/demo_document__demo_spiral.preamble.kt        |  3 +++
 tools/completion-corpus/boxes/demo_document__demo_timeline.preamble.kt      |  3 +++
 tools/completion-corpus/boxes/manifest.json                                 | 10 ++++----
 12 files changed, 107 insertions(+), 6 deletions(-)
Its commits (the branch is all of them, not only the tip; its whole change is `git diff 26724ca25cb8...73948cd20b9a`):
73948cd20 Completion corpus: give the harvest graph a root and a session

What :checkKDocAttachment printed when it failed:
> Task :checkKDocAttachment FAILED

The end of the landing log:
OK

=== tools.test_whisper_worker
..
----------------------------------------------------------------------
Ran 2 tests in 0.195s

OK

Python tool test suites:
  tools.test_agent_check_parse                       2 passed /   2 run  (0.0 s)
  tools.test_agents_md                               4 passed /   4 run  (0.0 s)
  tools.test_ai4_conversation_review                 4 passed /   4 run  (0.0 s)
  tools.test_ai_chat_persistence                     0 passed /   0 run  (0.0 s)
  tools.test_ai_tool_usage                           5 passed /   5 run  (0.0 s)
  tools.test_ai_usage                                3 passed /   3 run  (0.0 s)
  tools.test_automation_workspace                   10 passed /  10 run  (0.0 s)
  tools.test_backdoor_managed_startup               20 passed /  20 run  (0.1 s)
  tools.test_build_dependency_rules                  3 passed /   3 run  (0.0 s)
  tools.test_build_zensical_run                     18 passed /  18 run  (7.5 s)
  tools.test_build_zensical_workspace_markdown       6 passed /   6 run  (0.0 s)
  tools.test_canonical_graphics_index                9 passed /   9 run  (0.3 s)
  tools.test_capture_transaction                    13 passed /  13 run  (0.1 s)
  tools.test_check_box_idioms                       22 passed /  22 run  (0.3 s)
  tools.test_check_doc_assets                       19 passed /  19 run  (1.4 s)
  tools.test_check_doc_offline_links                16 passed /  16 run  (5.8 s)
  tools.test_check_doc_site_links                   44 passed /  45 run, 1 skipped  (1.1 s)
  tools.test_check_module_imports                   14 passed /  14 run  (1.4 s)
  tools.test_check_native_selfcontained             20 passed /  20 run  (0.2 s)
  tools.test_check_offline_assets                   13 passed /  13 run  (1.1 s)
  tools.test_check_staged_generated_artifacts       41 passed /  41 run  (5.2 s)
  tools.test_check_study_tools                       7 passed /   7 run  (0.1 s)
  tools.test_check_test_tree_writes                 32 passed /  32 run  (3.8 s)
  tools.test_completion_sidecar                     13 passed /  13 run  (5.1 s)
  tools.test_conversation_demos                      9 passed /   9 run  (1.5 s)
  tools.test_conversation_packagers                  4 passed /   4 run  (1.3 s)
  tools.test_demo_build_scripts                      3 passed /   3 run  (0.1 s)
  tools.test_discover_field_tests                   16 passed /  16 run  (2.0 s)
  tools.test_docs_capture_audit                     34 passed /  34 run  (0.1 s)
  tools.test_docs_duplicate_embeds                   3 passed /   3 run  (0.4 s)
  tools.test_docs_hooks_capture_sources              3 passed /   3 run  (0.0 s)
  tools.test_docs_hooks_citations                   14 passed /  14 run  (0.0 s)
  tools.test_docs_hooks_fence_markers                5 passed /   5 run  (0.0 s)
  tools.test_docs_hooks_inline_code                  9 passed /   9 run  (0.0 s)
  tools.test_docs_hooks_repo_source_links           12 passed /  12 run  (0.0 s)
  tools.test_docs_native_prerequisites              39 passed /  39 run  (0.0 s)
  tools.test_docs_notes                             68 passed /  68 run  (30.3 s)
  tools.test_docs_runner_completion_backend         19 passed /  19 run  (1.6 s)
  tools.test_docs_runner_failure_reporting          27 passed /  27 run  (0.5 s)
  tools.test_docs_runner_graphics_launch             7 passed /   7 run  (0.3 s)
  tools.test_docs_runner_inputs                      1 passed /   1 run  (0.6 s)
  tools.test_docs_runner_ui_clip                     5 passed /   5 run  (0.0 s)
  tools.test_docs_runner_window_outputs              4 passed /   4 run  (0.0 s)
  tools.test_docs_voice_detectors                   15 passed /  15 run  (78.7 s)
  tools.test_execution_receipts                      4 passed /   4 run  (0.0 s)
  tools.test_expected_test_failures                  9 passed /   9 run  (0.0 s)
  tools.test_export_particle_video_docs              2 passed /   2 run  (0.0 s)
  tools.test_feel_bench                              5 passed /   5 run  (0.1 s)
  tools.test_field_agent                             3 passed /   3 run  (0.0 s)
  tools.test_field_backdoor                        123 passed / 123 run  (2.7 s)
  tools.test_field_inspection                        5 passed /   5 run  (0.0 s)
  tools.test_field_kit                              17 passed /  17 run  (0.4 s)
  tools.test_field_live_session_reaping              4 passed /   4 run  (0.0 s)
  tools.test_field_python_env                        4 passed /   4 run  (0.0 s)
  tools.test_field_sh_launcher                      38 passed /  39 run, 1 skipped  (14.3 s)
  tools.test_fieldcv                                15 passed /  15 run  (1.7 s)
  tools.test_git_isolation                           2 passed /   2 run  (7.6 s)
  tools.test_guard_corpora                           5 passed /   5 run  (0.4 s)
  tools.test_inventory_box_code                     13 passed /  13 run  (0.0 s)
  tools.test_land                                   32 passed /  32 run  (77.0 s)
  tools.test_land_precheck                          13 passed /  13 run  (1.2 s)
  tools.test_learned_shader_build                    7 passed /   7 run  (0.0 s)
  tools.test_loc_history                            22 passed /  24 run, 2 skipped  (2.4 s)
  tools.test_local_ai_monitor                        6 passed /   6 run  (0.0 s)
  tools.test_merge_regen                             6 passed /   6 run  (2.6 s)
  tools.test_native_download_cache                   3 passed /   3 run  (0.0 s)
  tools.test_neural_docs_gating                      6 passed /   6 run  (0.0 s)
  tools.test_offline_viewer_js                       4 passed /   4 run  (0.6 s)
  tools.test_open_dlss_nr                            6 passed /   6 run  (0.0 s)
  tools.test_oscquery_docs_ports                     3 passed /   3 run  (0.0 s)
  tools.test_particle_tracker                        4 passed /   4 run  (0.0 s)
  tools.test_prune_gradle_daemon_logs                2 passed /   2 run  (0.0 s)
  tools.test_ray_tracing_docs                       10 passed /  10 run  (0.1 s)
  tools.test_reap_merged_worktrees                  80 passed /  80 run  (54.7 s)
  tools.test_refactor                               57 passed /  57 run  (10.5 s)
  tools.test_refactor_pass                          20 passed /  20 run  (9.1 s)
  tools.test_refactor_session                       11 passed /  11 run  (10.2 s)
  tools.test_refactor_symbols                       15 passed /  15 run  (14.0 s)
  tools.test_repository_root_inventory               4 passed /   4 run  (0.3 s)
  tools.test_run_field_tests_native_prerequisites    2 passed /   2 run  (0.0 s)
  tools.test_run_field_tests_ordering               24 passed /  24 run  (0.0 s)
  tools.test_run_field_tests_output                  8 passed /   8 run  (0.0 s)
  tools.test_run_field_tests_port_ownership         14 passed /  14 run  (0.1 s)
  tools.test_run_field_tests_reaper                 34 passed /  34 run  (0.0 s)
  tools.test_run_python_tests                        6 passed /   6 run  (3.3 s)
  tools.test_runner_fence_image_widths               2 passed /   2 run  (0.1 s)
  tools.test_studies_page                            3 passed /   3 run  (0.0 s)
  tools.test_sync_field_audio_docs                   2 passed /   2 run  (0.0 s)
  tools.test_verification_environment               37 passed /  37 run  (3.2 s)
  tools.test_verification_lock_gradle                7 passed /   7 run  (0.0 s)
  tools.test_verification_runtime                   19 passed /  19 run  (1.4 s)
  tools.test_whisper_worker                          2 passed /   2 run  (0.2 s)
  TOTAL                                            1355 passed / 1359 run, 4 skipped

> Task :checkBoxIdioms
warning: `VIRTUAL_ENV=/Users/marc/field4/.venv` does not match the project environment path `.venv` and will be ignored; use `--active` to target the active environment instead
check_box_idioms: clean (2 file(s))

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
tree-write guard: baseline of 13279 tracked file(s), 0 already dirty -> /Users/marc/field4-worktrees/lander/build/test-tree-writes/baseline.json

> Task :citationGates
WARNING: Using incubator modules: jdk.incubator.vector
citationGates: running ServerSurfaceLedgerTest
citationGates: [1/14] ServerSurfaceLedgerTest SUCCESS in 3.6s
citationGates: running CloseNodeCitationDriftTest
citationGates: [2/14] CloseNodeCitationDriftTest SUCCESS in 1.4s
citationGates: running CitationContractTest
citationGates: [3/14] CitationContractTest SUCCESS in 3.6s
citationGates: running DerivedArithmeticContractTest
citationGates: [4/14] DerivedArithmeticContractTest SUCCESS in 5.2s
citationGates: running DocsFenceTreatmentTest
citationGates: [5/14] DocsFenceTreatmentTest SUCCESS in 0.4s
citationGates: running DocsGatesWiringTest
citationGates: [6/14] DocsGatesWiringTest SUCCESS in 0.1s
citationGates: running DocumentedApiEvaluationTest
citationGates: [7/14] DocumentedApiEvaluationTest SUCCESS in 9.3s
citationGates: running GateInputDeclarationTest
citationGates: [8/14] GateInputDeclarationTest SUCCESS in 0.1s
citationGates: running ImpossibilityClaimContractTest
citationGates: [9/14] ImpossibilityClaimContractTest SUCCESS in 8.3s
citationGates: running LanguagesCategoryTest
citationGates: [10/14] LanguagesCategoryTest SUCCESS in 1.3s
citationGates: running PersonalBoundaryTest
citationGates: [11/14] PersonalBoundaryTest SUCCESS in 7.2s
citationGates: running ProseNumeralContractTest
citationGates: [12/14] ProseNumeralContractTest SUCCESS in 4.9s
citationGates: running SmallLanguageAnatomyTest
citationGates: [13/14] SmallLanguageAnatomyTest SUCCESS in 0.6s
citationGates: running TranscriptStackAutoCompileTest
citationGates: [14/14] TranscriptStackAutoCompileTest SUCCESS in 0.5s

> Task :checkTestTreeWrites
warning: `VIRTUAL_ENV=/Users/marc/field4/.venv` does not match the project environment path `.venv` and will be ignored; use `--active` to target the active environment instead
tree-write guard: citationGates left the working tree as it found it.

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':checkKDocAttachment' (registered in build file 'build.gradle.kts').
> Process 'command '/Users/marc/.field4/toolchains/graalvm-community-25.4.4.1.1+1.1/Contents/Home/bin/java'' finished with non-zero exit value 1

* Try:
> Run with --stacktrace option to get the stack trace.
> Run with --info or --debug option to get more log output.
> Run with --scan to get full insights from a Build Scan (powered by Develocity).
> Get more help at https://help.gradle.org.

BUILD FAILED in 7m 10s
32 actionable tasks: 9 executed, 23 up-to-date

  A generated artifact is stale after this merge.
  It was not your commit that broke it and no hook ran on the clone that did:
  .git/hooks is per-clone and Git never transports it.

  Reproduce:  ./gradlew checkKDocAttachment checkPythonTools checkBoxIdioms citationGates
  The failing task names the artifact and the command that regenerates it.
  Regenerate and commit that, or `main` is red until someone else finds it.

  Set FIELD_SKIP_MERGE_GUARD=1 to skip this check.

merge_regen: a guard failed on the batch

land: 2026-10-25T11:41:42-04:00 red; asking the fixer (up to 15 min) about claude/demo-timeline-harvest

What the fixer did on recent reds (for calibration):
- fixed: The branch added reference/output.md without a nav entry in public/zensical.yml, and adding that entry makes LanguagesCategoryTest pass.
- fixed: The branch added three raw sh/json fences in presentation_studio.md, and switching them to the diffstatic treatment makes DocsFenceTreatmentTest pass.
- fixed: The layers branch built the set-visibility-animated payload with JsonObjectBuilder.put calls in route-declaring Server.kt, and an equivalent JsonObject(mapOf(...)) makes ServerSurfaceLedgerTest pass; the OfflineViewerJsTest reds are separate, caused by this machine's broken Homebrew node (missing li
- declined: OfflineViewerJsTest fails because this machine's Homebrew node can't load libsimdjson.33.dylib after a simdjson upgrade (dyld abort, exit -6); no branch caused it, and `brew reinstall node` fixes it.
- declined: test_sigterm_to_the_lander_stops_its_build hit a race where it read build.json after the lander created it but before the JSON was written; no branch in the round touched the lander, so retry, and fix it by writing build.json atomically or polling until it parses.
- fixed: The branch's kits.md example and KitPackagesTest fixture declared packages outside afield. (example, demo), and renaming them to afield.example and afield.demo makes PersonalBoundaryTest pass.
- declined: The 5 s subprocess timeout in test_verification_runtime most likely tripped because the host was busy during a 1h22m guard; the module passes in 1.3 s on this same tree and no branch touched the wrapper or the test.
- declined: checkAgentTranscriptLayouts fails on demo_document.yaml#demo_timeline (40x1400 with no layout exception, and a negative position), which main already had at 8c5e71bb4 from commit 93752ed5c; the remotejs-box-parity branch touches neither file, so main is red on its own and the demo-document author mu

This replay's checkout cannot build: the `gradle` command is not available, so verify by reading the check's source and rerunning any `tools/` Python check that covers it.
