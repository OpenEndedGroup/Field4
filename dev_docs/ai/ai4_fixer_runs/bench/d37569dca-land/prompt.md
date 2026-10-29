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

main before this round: dba69727ae8424b559948501d097383c8d26a2c8

Branches in the round, each merged as its own commit:

## handoffs/code-20261015-2234 (6a33f6ebd, by Marc)
Rewrite selectors: under= means any ancestor; param= is refused in favour of role=
.../2026-10-16-make-the-keyframe-editor-s-simplify-slider-keep-linked-lanes.md     | 10 ++++++++++
 public/docs/chart.md                                                               |  8 ++++----
 public/docs/functions_examples.md                                                  | 15 +++++----------
 public/docs/interactive_guides.md                                                  |  3 +--
 public/docs/keyframe_editor.md                                                     |  4 ++--
 src/jvmMain/kotlin/afield/animation/keyframes/keyframes.kt                         | 29 +++++++++++++++++++++++++++++
 src/jvmMain/kotlin/afield/examples/visualization/ChartGallery.kt                   | 30 ++++++++++++++++++++++++++++++
 src/jvmMain/kotlin/afield/math/functions/FunctionRewriteDsl.kt                     | 12 +++++++++---
 src/jvmMain/kotlin/afield/math/functions/Functions.kt                              | 17 ++++++++++-------
 src/jvmMain/kotlin/afield/ui/slides/SlideShow.kt                                   | 29 ++++++++++++++++++-----------
 src/jvmTest/kotlin/afield/animation/keyframes/KeyframeEvaluatorTest.kt             | 21 +++++++++++++++++++++
 src/jvmTest/kotlin/afield/math/functions/ConstantParameterDomainTest.kt            | 24 ++++++++++++++++++++++++
 src/jvmTest/kotlin/afield/math/functions/FunctionsGraphRewriteTest.kt              | 14 ++++++++++++++
 src/jvmTest/kotlin/afield/ui/visualization/ChartTest.kt                            | 12 ++++++++++++
 14 files changed, 189 insertions(+), 39 deletions(-)
Its commits (the branch is all of them, not only the tip; its whole change is `git diff dba69727ae84...6a33f6ebd9e0`):
6a33f6ebd Rewrite selectors: under= means any ancestor; param= is refused in favour of role=
6473cad03 A constant used as a parameter no longer narrows the function it configures
730eadca2 KeyframeEvaluator.simplifyTogether: simplify linked lanes onto the same key times
6cf29f6cf Slideshow: redraw the open note once the running code has declared its template values
796220e12 chart.md: a filled toFLine() renders correctly; move text with FLine.transform
8d1176f0d ChartTest fails when the committed chart-gallery figures are stale

What :checkKDocAttachment printed when it failed:
> Task :checkKDocAttachment FAILED

The end of the landing log:
=== tools.test_verification_runtime
...................
----------------------------------------------------------------------
Ran 19 tests in 1.435s

OK

=== tools.test_whisper_worker
..
----------------------------------------------------------------------
Ran 2 tests in 0.189s

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
  tools.test_conversation_demos                      8 passed /   8 run
  tools.test_demo_build_scripts                      3 passed /   3 run
  tools.test_discover_field_tests                   16 passed /  16 run
  tools.test_docs_capture_audit                     34 passed /  34 run
  tools.test_docs_duplicate_embeds                   3 passed /   3 run
  tools.test_docs_hooks_citations                   14 passed /  14 run
  tools.test_docs_hooks_fence_markers                3 passed /   3 run
  tools.test_docs_hooks_inline_code                  9 passed /   9 run
  tools.test_docs_hooks_repo_source_links           12 passed /  12 run
  tools.test_docs_native_prerequisites              36 passed /  36 run
  tools.test_docs_notes                             66 passed /  66 run
  tools.test_docs_runner_completion_backend         19 passed /  19 run
  tools.test_docs_runner_failure_reporting          27 passed /  27 run
  tools.test_docs_runner_graphics_launch             7 passed /   7 run
  tools.test_docs_runner_ui_clip                     5 passed /   5 run
  tools.test_docs_runner_window_outputs              2 passed /   2 run
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
  tools.test_inventory_box_code                      8 passed /   8 run
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
  TOTAL                                            1270 passed / 1274 run, 4 skipped

> Task :checkBoxIdioms
warning: `VIRTUAL_ENV=/Users/marc/field4/.venv` does not match the project environment path `.venv` and will be ignored; use `--active` to target the active environment instead
public/docs/interactive_guides.md:29: note [box-alias] `guide` is the box this code runs in, already the implicit receiver; unless it earns its line, write `foo` for `guide.foo` and `thisBox` where the box is an argument
public/docs/interactive_guides.md:99: note [box-alias] `guide` is the box this code runs in, already the implicit receiver; unless it earns its line, write `foo` for `guide.foo` and `thisBox` where the box is an argument
public/docs/interactive_guides.md:144: note [box-alias] `host` is the box this code runs in, already the implicit receiver; unless it earns its line, write `foo` for `host.foo` and `thisBox` where the box is an argument
public/docs/interactive_guides.md:210: note [box-alias] `host` is the box this code runs in, already the implicit receiver; unless it earns its line, write `foo` for `host.foo` and `thisBox` where the box is an argument
public/docs/interactive_guides.md:265: note [box-alias] `host` is the box this code runs in, already the implicit receiver; unless it earns its line, write `foo` for `host.foo` and `thisBox` where the box is an argument
check_box_idioms: clean (8 file(s))

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
tree-write guard: baseline of 10971 tracked file(s), 0 already dirty -> /Users/marc/field4-worktrees/lander/build/test-tree-writes/baseline.json

> Task :citationGates
WARNING: Using incubator modules: jdk.incubator.vector
citationGates: running ServerSurfaceLedgerTest
citationGates: [1/14] ServerSurfaceLedgerTest SUCCESS in 3.2s
citationGates: running CloseNodeCitationDriftTest
citationGates: [2/14] CloseNodeCitationDriftTest SUCCESS in 1.5s
citationGates: running CitationContractTest
citationGates: [3/14] CitationContractTest SUCCESS in 3.4s
citationGates: running DerivedArithmeticContractTest
citationGates: [4/14] DerivedArithmeticContractTest SUCCESS in 4.9s
citationGates: running DocsFenceTreatmentTest
citationGates: [5/14] DocsFenceTreatmentTest SUCCESS in 0.2s
citationGates: running DocsGatesWiringTest
citationGates: [6/14] DocsGatesWiringTest SUCCESS in 0.1s
citationGates: running DocumentedApiEvaluationTest
citationGates: [7/14] DocumentedApiEvaluationTest SUCCESS in 8.6s
citationGates: running GateInputDeclarationTest
citationGates: [8/14] GateInputDeclarationTest SUCCESS in 0.1s
citationGates: running ImpossibilityClaimContractTest
citationGates: [9/14] ImpossibilityClaimContractTest SUCCESS in 10.8s
citationGates: running LanguagesCategoryTest
citationGates: [10/14] LanguagesCategoryTest SUCCESS in 1.3s
citationGates: running PersonalBoundaryTest
citationGates: [11/14] PersonalBoundaryTest SUCCESS in 6.2s
citationGates: running ProseNumeralContractTest
citationGates: [12/14] ProseNumeralContractTest SUCCESS in 6.7s
citationGates: running SmallLanguageAnatomyTest
citationGates: [13/14] SmallLanguageAnatomyTest SUCCESS in 0.8s
citationGates: running TranscriptStackAutoCompileTest
citationGates: [14/14] TranscriptStackAutoCompileTest SUCCESS in 5.6s

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

BUILD FAILED in 6m 46s
32 actionable tasks: 9 executed, 23 up-to-date

  A generated artifact is stale after this merge.
  It was not your commit that broke it and no hook ran on the clone that did:
  .git/hooks is per-clone and Git never transports it.

  Reproduce:  ./gradlew checkKDocAttachment checkPythonTools checkBoxIdioms citationGates
  The failing task names the artifact and the command that regenerates it.
  Regenerate and commit that, or `main` is red until someone else finds it.

  Set FIELD_SKIP_MERGE_GUARD=1 to skip this check.

merge_regen: a guard failed on the batch

land: 2026-10-16T02:55:23+02:00 red; asking the fixer (up to 15 min) about handoffs/code-20261015-2234

What the fixer did on recent reds (for calibration):
- declined: no verdict line
- declined: no verdict line
- fixed: A new handoff's title tripped the impossibility-claim gate with no evidence, so I added a citation to keyframer.kt's camera function, the code the handoff is about, in the file's header lines.
- declined: checkBoxIdioms flags three listOf calls in matrix_profile.md that should be collection literals, but the same code is in the transcript YAML and its packaged assets (document.md, metadata.edn, three .kt files), so the fix needs a re-package across more than six files rather than editing the page.
- declined: checkBoxIdioms flags four listOf calls in matrix_profile.md whose source is the matrix_profile transcript YAML and its packaged assets, so the fix needs a transcript edit and re-package across more than six files, not a hand edit of the page.
- fixed: The branch put a KDoc block on a function parameter in dir.kt, which checkKDocAttachment rejects, so I turned it into a plain line comment.
- fixed: The branch added the set-viewports WebSocket handler to Server.kt without adding its row and updating Server.kt's count in dev_docs/server_surface_inventory.md.
- fixed: The branch put @Synchronized on the same line as markLane in CoroutineProfiling.kt, which made checkKDocAttachment drop its KDoc, so I moved the annotation onto its own line.

This replay's checkout cannot build: the `gradle` command is not available, so verify by reading the check's source and rerunning any `tools/` Python check that covers it.
