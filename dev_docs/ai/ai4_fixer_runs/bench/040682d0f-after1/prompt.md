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

main before this round: dec98aa7b1423d78133280e54805ce402bc112e1

Branches in the round, each merged as its own commit:

## handoffs/code-20261015-2234 (097a518da, by Marc)
Merge branch 'main' into handoffs/code-20261015-2234
.../2026-10-16-decide-unify-or-rename-the-two-shader-include-mechanisms.md  |  15 ++++
 .../handoffs/2026-10-16-give-kotlin-morpho-and-nano-boxes-line-notes.md     |  23 +++++
 dev_docs/handoffs/2026-10-16-make-slugbuffer-teardown-order-independent.md  |  25 ++++++
 .../2026-10-16-stop-uniform-block-classes-shadowing-imported-types.md       |  18 ++++
 ...26-10-16-type-fieldml-callback-parameters-in-named-records-and-arity-.md |  28 ++++++
 dev_docs/kdoc_worklist.md                                                   |  10 +--
 public/docs/afield_four_graphics.md                                         |   6 +-
 .../assets/canonical_graphics/0e01f4b0-4b65-40db-a8b0-cd6cde4048d6_code_.kt |   2 +-
 public/docs/assets/canonical_graphics/metadata.edn                          |   2 +-
 public/docs/assets/rectsl/inverse-animation-api.png                         | Bin 187549 -> 138333 bytes
 public/docs/assets/rectsl/inverse-animation-fit-box.png                     | Bin 174627 -> 174835 bytes
 public/docs/assets/rectsl/inverse-animation-free-param.png                  | Bin 487752 -> 490497 bytes
 public/docs/clojure.md                                                      |   4 -
 public/docs/forth_examples.md                                               |   2 +-
 public/docs/path_traced_reconstruction.md                                   |   4 +-
 public/docs/python.md                                                       |  15 ++--
 public/docs/rectsl_inverse_animation.md                                     |  44 ++++-----
 public/docs/reference/function.md                                           |  38 ++++----
 public/docs/reference/graphics.md                                           |  20 ++---
 public/docs/reference/imaging.md                                            |   6 +-
 public/docs/reference/index.md                                              |  10 +--
 public/docs/reference/languages.md                                          | 175 ++++++++++++++++++------------------
 public/docs/reference/workspace.md                                          |  60 ++++++-------
 public/docs/stacksl_cheatsheet.md                                           |   3 +-
 public/docs/vision_geometry.md                                              |   5 +-
 src/jvmMain/kotlin/afield/examples/VisionGeometryPreviewGallery.kt          |  23 +++--
 src/jvmMain/kotlin/afield/graphics/scene/simpleGLTFSupport.kt               |  12 ++-
 src/jvmMain/kotlin/afield/graphics/shader/ShaderSPIRVUtils.kt               |  14 +++
 src/jvmMain/kotlin/afield/lang/clojure/ClojureInterface.kt                  |  16 +++-
 src/jvmMain/kotlin/afield/lang/forth/ForthInterpreter.kt                    |  32 +++++--
 src/jvmMain/kotlin/afield/lang/python/PythonInterface.kt                    |  24 ++++-
 src/jvmMain/kotlin/afield/lang/rectsl/RectSLFit.kt                          |  36 +++++++-
 src/jvmMain/kotlin/afield/lang/rectsl/RectSLFn.kt                           |  37 +++++++-
 src/jvmMain/kotlin/afield/lang/stacksl/StackSL.kt                           |   6 ++
 src/jvmMain/kotlin/afield/math/functions/FunctionFit.kt                     |  18 ++++
 src/jvmMain/kotlin/afield/media/reconstruction/PathTracedReconstruction.kt  |   4 +
 src/jvmMain/kotlin/afield/runtime/execution/execution.kt                    |  15 ++++
 src/jvmTest/kotlin/afield/lang/clojure/ClojureHostTest.kt                   |   7 ++
 src/jvmTest/kotlin/afield/lang/forth/ForthShadowingTest.kt                  |  21 +++++
 src/jvmTest/kotlin/afield/lang/rectsl/RectSLFitTest.kt                      |  11 +++
 src/jvmTest/kotlin/afield/lang/stacksl/StackSLBalanceTest.kt                |  11 +++
 src/jvmTest/kotlin/afield/runtime/execution/LanguageRootHintTest.kt         |  18 ++++
 42 files changed, 579 insertions(+), 241 deletions(-)
Its commits (the branch is all of them, not only the tip; its whole change is `git diff dec98aa7b142...097a518dad5c`):
097a518da Merge branch 'main' into handoffs/code-20261015-2234
a44fab872 PathTracedReconstruction.install takes over a pathTracingHere tracer's standalone passes
f5da12b12 Move the include-mechanism question to decisions
2a9648399 Warn once when a shader calls debugPrintfEXT with no printf layer
d323ef9b9 glTF primitives get modelView(camera), so a camera loop keeps the node transform
ec131a287 Refile uniform-block shadowing with the code location
4351c9dfe RectSL lowers the bounce easings
0659a1298 Refile SlugBuffer teardown with analysis
9d09ed9bb fitGradient refuses a lowered track with declared free parameters
c36883941 RectSL fitParameters runs until it settles (up to 5000 steps) and reports converged
eff59ee3b Forth warns when a definition replaces a builtin, and forget restores it
5f50adfc6 A compile error in a language root says its own code is Kotlin
5cd1e86dd Python startup refuses a jep native library that does not match the shipped jar
3a12721ed An active VIRTUAL_ENV or CONDA_PREFIX outranks the interpreter /welcome saved
f83c55a12 Clojure flushes output after each form, so a bare print sits beside its own form

Failing tests (first): PersonalBoundaryTest.no
## PersonalBoundaryTest.no committed file imports a package outside the public roots
java.lang.AssertionError: committed files import packages outside PUBLIC_IMPORT_ROOTS:
  src/jvmMain/kotlin/afield/lang/python/PythonInterface.kt → importlib

If these are private packages under src/personal/, the code does not belong in a
committed file: take the lesson, express it against afield.*, and leave the private
names behind. If one is a genuine new public dependency, add its root to
PUBLIC_IMPORT_ROOTS with a note saying what it is.



What :checkKDocAttachment printed when it failed:
KDocAttachmentGuard: scanned 2214 file(s), 27846 KDoc block(s)
KDocAttachmentGuard: 2 mis-attached KDoc block(s):
  src/jvmMain/kotlin/afield/graphics/scene/simpleGLTFSupport.kt:238: STACKED — a second KDoc block follows at line 267 with no declaration between them, so this one is discarded
  src/jvmMain/kotlin/afield/math/functions/FunctionFit.kt:271: STACKED — a second KDoc block follows at line 302 with no declaration between them, so this one is discarded
Each of these is either dropped or attached to the WRONG declaration, and a dropped
block's prose is published under whatever symbol parses next. Fix by either
  - moving the block so the very next line is a declaration
    (KDocExtractor.parseDeclaration reads: class / interface / object / fun / val / var /
    typealias, after modifiers and after annotations *on their own line*), or
  - teaching KDocExtractor.parseDeclaration the form it cannot see, or
  - deleting the block if it documents nothing.
See dev_docs/kdoc_reference_contract.md.
> Task :checkKDocAttachment FAILED

The end of the landing log:
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
  TOTAL                                            1275 passed / 1279 run, 4 skipped

> Task :checkBoxIdioms
warning: `VIRTUAL_ENV=/Users/marc/field4/.venv` does not match the project environment path `.venv` and will be ignored; use `--active` to target the active environment instead
check_box_idioms: clean (15 file(s))

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
tree-write guard: baseline of 10997 tracked file(s), 0 already dirty -> /Users/marc/field4-worktrees/lander/build/test-tree-writes/baseline.json

> Task :citationGates
WARNING: Using incubator modules: jdk.incubator.vector
citationGates: running ServerSurfaceLedgerTest
citationGates: [1/14] ServerSurfaceLedgerTest SUCCESS in 3.5s
citationGates: running CloseNodeCitationDriftTest
citationGates: [2/14] CloseNodeCitationDriftTest SUCCESS in 1.5s
citationGates: running CitationContractTest
citationGates: [3/14] CitationContractTest SUCCESS in 3.3s
citationGates: running DerivedArithmeticContractTest
citationGates: [4/14] DerivedArithmeticContractTest SUCCESS in 4.9s
citationGates: running DocsFenceTreatmentTest
citationGates: [5/14] DocsFenceTreatmentTest SUCCESS in 0.3s
citationGates: running DocsGatesWiringTest
citationGates: [6/14] DocsGatesWiringTest SUCCESS in 0.1s
citationGates: running DocumentedApiEvaluationTest
citationGates: [7/14] DocumentedApiEvaluationTest SUCCESS in 8.8s
citationGates: running GateInputDeclarationTest
citationGates: [8/14] GateInputDeclarationTest SUCCESS in 0.1s
citationGates: running ImpossibilityClaimContractTest
citationGates: [9/14] ImpossibilityClaimContractTest SUCCESS in 10.8s
citationGates: running LanguagesCategoryTest
citationGates: [10/14] LanguagesCategoryTest SUCCESS in 1.4s
citationGates: running PersonalBoundaryTest

PersonalBoundaryTest > no committed file imports a package outside the public roots FAILED
    java.lang.AssertionError at PersonalBoundaryTest.kt:326
citationGates: [11/14] PersonalBoundaryTest FAILURE in 7.0s
citationGates: running ProseNumeralContractTest
citationGates: [12/14] ProseNumeralContractTest SUCCESS in 5.7s
citationGates: running SmallLanguageAnatomyTest
citationGates: [13/14] SmallLanguageAnatomyTest SUCCESS in 0.6s
citationGates: running TranscriptStackAutoCompileTest
citationGates: [14/14] TranscriptStackAutoCompileTest SUCCESS in 4.2s

113 tests completed, 1 failed

> Task :citationGates FAILED

> Task :checkTestTreeWrites
warning: `VIRTUAL_ENV=/Users/marc/field4/.venv` does not match the project environment path `.venv` and will be ignored; use `--active` to target the active environment instead
tree-write guard: citationGates left the working tree as it found it.

FAILURE: Build completed with 2 failures.

1: Task failed with an exception.
-----------
* What went wrong:
Execution failed for task ':checkKDocAttachment' (registered in build file 'build.gradle.kts').
> Process 'command '/Users/marc/.field4/toolchains/graalvm-community-25.4.4.1.1+1.1/Contents/Home/bin/java'' finished with non-zero exit value 1

* Try:
> Run with --stacktrace option to get the stack trace.
> Run with --info or --debug option to get more log output.
> Run with --scan to get full insights from a Build Scan (powered by Develocity).
> Get more help at https://help.gradle.org.
==============================================================================

2: Task failed with an exception.
-----------
* What went wrong:
Execution failed for task ':citationGates'.
> There were failing tests. See the report at: file:///Users/marc/field4-worktrees/lander/build/reports/tests/citationGates/index.html

* Try:
> Run with --scan to get full insights from a Build Scan (powered by Develocity).
==============================================================================

BUILD FAILED in 9m 35s
32 actionable tasks: 9 executed, 23 up-to-date

  A generated artifact is stale after this merge.
  It was not your commit that broke it and no hook ran on the clone that did:
  .git/hooks is per-clone and Git never transports it.

  Reproduce:  ./gradlew checkKDocAttachment checkPythonTools checkBoxIdioms citationGates
  The failing task names the artifact and the command that regenerates it.
  Regenerate and commit that, or `main` is red until someone else finds it.

  Set FIELD_SKIP_MERGE_GUARD=1 to skip this check.

merge_regen: a guard failed on the batch

land: 2026-10-16T08:01:39+02:00 red; asking the fixer (up to 15 min) about handoffs/code-20261015-2234

What the fixer did on recent reds (for calibration):
- declined: checkBoxIdioms flags four listOf calls in matrix_profile.md whose source is the matrix_profile transcript YAML and its packaged assets, so the fix needs a transcript edit and re-package across more than six files, not a hand edit of the page.
- fixed: The branch put a KDoc block on a function parameter in dir.kt, which checkKDocAttachment rejects, so I turned it into a plain line comment.
- fixed: The branch added the set-viewports WebSocket handler to Server.kt without adding its row and updating Server.kt's count in dev_docs/server_surface_inventory.md.
- fixed: The branch put @Synchronized on the same line as markLane in CoroutineProfiling.kt, which made checkKDocAttachment drop its KDoc, so I moved the annotation onto its own line.
- fixed: The branch put staleCommittedFigures and its doc comment between Raster's existing doc comment and the class, leaving two doc comments stacked, so I moved Raster's comment back above the class.
- fixed: The branch put the new diverged property and its doc comment between runPostIntegration's doc comment and the function, with @Volatile on the declaration line, so I moved that doc comment back and broke the annotation onto its own line.
- fixed: The branch put SONIC_PI_PERIOD_SEARCH and patternPeriod between patternEventsForCycles and its doc comment, leaving two doc comments stacked, so I moved that doc comment back above the function.
- fixed: The branch changed a StackSL cheat-sheet comment from 'wrong:' to 'refused:' without regenerating stacksl_words.md, so I regenerated it with tools/stacksl_conversation_documents.py and the --check now passes.

This replay's checkout cannot build: the `gradle` command is not available, so verify by reading the check's source and rerunning any `tools/` Python check that covers it.
