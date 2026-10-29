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

main before this round: 0336f666f89bd7c159ad0230091cbfcc66cef73e

Branches in the round, each merged as its own commit:

## codex/kit-library (b9095c760, by Marc)
Refresh Welcome documentation capture with collapsed Kits library
build.gradle.kts                                                      |  95 +++++++-
 dev_docs/DOCS_IMPROVEMENT_AGENT.md                                    |  10 +
 dev_docs/kdoc_worklist.md                                             |  23 +-
 dev_docs/server_surface_inventory.md                                  |  12 +-
 dev_docs/workspace/KIT_LIBRARY_VERIFICATION.md                        |  56 +++++
 electron/field-preload.js                                             |   4 +-
 electron/main.js                                                      |   9 +
 public/docs/assets/welcome/welcome-page.png                           | Bin 888687 -> 657710 bytes
 public/docs/funFeatures.md                                            |   4 +-
 public/docs/history_overview.md                                       |   2 +-
 public/docs/installation.md                                           |  19 +-
 public/docs/kits.md                                                   | 224 ++++++++++++++++--
 public/docs/presentation_studio.md                                    |  18 +-
 public/docs/reference/data.md                                         |  48 ++--
 public/docs/reference/index.md                                        |  17 +-
 public/docs/reference/workspace.md                                    | 519 ++++++++++++++++++++++++++++++++----------
 public/zensical.yml                                                   |   4 +-
 src/commonMain/resources/kit-library.css                              |   6 +
 src/commonMain/resources/kit-library.js                               | 147 ++++++++++++
 src/commonMain/resources/welcome.html                                 |  27 +++
 src/jvmMain/kotlin/afield/app/server/KitRoutes.kt                     | 121 ++++++++++
 src/jvmMain/kotlin/afield/app/server/Server.kt                        |  35 ++-
 src/jvmMain/kotlin/afield/app/server/agent_backdoor.kt                |   8 +-
 src/jvmMain/kotlin/afield/app/settings/PersonalLayer.kt               |   4 +
 src/jvmMain/kotlin/afield/app/settings/PersonalReload.kt              |   3 +
 src/jvmMain/kotlin/afield/document/graph/Boxes.kt                     |  27 +--
 src/jvmMain/kotlin/afield/document/persistence/saving.kt              |   4 +-
 src/jvmMain/kotlin/afield/document/workspace/FileManager.kt           |   1 +
 src/jvmMain/kotlin/afield/document/workspace/KitDedicatedHost.kt      |  78 +++++++
 src/jvmMain/kotlin/afield/document/workspace/KitInstances.kt          | 130 +++++++++++
 src/jvmMain/kotlin/afield/document/workspace/KitPackages.kt           | 270 ++++++++++++++++++++++
 src/jvmMain/kotlin/afield/document/workspace/KitRestarts.kt           |  39 ++++
 src/jvmMain/kotlin/afield/document/workspace/KitRuntime.kt            | 114 ++++++++++
 src/jvmMain/kotlin/afield/document/workspace/WorkspaceProjectScope.kt |  58 +++++
 src/jvmMain/kotlin/afield/document/workspace/dir.kt                   |   6 +
 src/jvmMain/kotlin/afield/runtime/completion/KotlinSourceIndex.kt     |   6 +-
 src/jvmTest/kotlin/afield/app/server/FieldApiCorsExposureTest.kt      |  13 +-
 src/jvmTest/kotlin/afield/document/workspace/KitPackagesTest.kt       | 247 ++++++++++++++++++++
 templates/presentation/README.md                                      |  20 +-
 templates/presentation/field-kit.json                                 |  16 ++
 templates/presentation/guides/HANDOFF.md                              |  20 +-
 41 files changed, 2244 insertions(+), 220 deletions(-)
Its commits (the branch is all of them, not only the tip; its whole change is `git diff 0336f666f89b...b9095c760df2`):
b9095c760 Refresh Welcome documentation capture with collapsed Kits library
4a77ba78a Refresh generated kit API references and source line links
adc6760a2 Teach the Welcome kit workflow across presentation documentation
59aab664b Recompile kit extensions when immutable source paths change
cc189515f Add restartable kit packages and a compact Welcome library

Failing tests (first): PersonalBoundaryTest.no
## PersonalBoundaryTest.no committed file declares a package outside the public roots
java.lang.AssertionError: committed files declare packages outside PUBLIC_IMPORT_ROOTS:
  public/docs/kits.md → example
  src/jvmTest/kotlin/afield/document/workspace/KitPackagesTest.kt → demo

A file in a private package is private work in a tracked location, whichever
direction its imports point. If this is a source file, move it under afield. and
rename its package to match. If it is a patch or a quotation of private source,
the code does not belong in a committed file: state the finding against afield.*
and leave the private source where it is.
java.lang.AssertionError: committed files declare packages outside PUBLIC_IMPORT_ROOTS:
  public/docs/kits.md → example
  src/jvmTest/kotlin/afield/document/workspace/KitPackagesTest.kt → demo

A file in a private package is private work in a tracked location, whichever
direction its imports point. If this is a source file, move it under afield. and
rename its package to match. If it is a patch or a quotation of private source,
the code does not belong in a committed file: state the finding against afield.*
and leave the private source where it is.

	at org.junit.Assert.fail(Assert.java:89)
	at kotlin.test.junit.JUnitAsserter.fail(JUnitSupport.kt:56)
	at kotlin.test.Asserter.assertTrue(Assertions.kt:1558)
	at kotlin.test.Asserter.assertTrue(Assertions.kt:1568)
	at kotlin.test.AssertionsKt__AssertionsKt.assertTrue(Assertions.kt:44)
	at kotlin.test.AssertionsKt.assertTrue(Unknown Source)
	at afield.tools.docs.PersonalBoundaryTest.no committed file declares a package outside the public roots(PersonalBoundaryTest.kt:406)
	at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:104)
	at java.base/java.lang.reflect.Method.invoke(Method.java:565)
	at org.junit.runners.model.FrameworkMethod$1.runReflectiveCall(FrameworkMethod.java:59)
	at org.junit.internal.runners.model.ReflectiveCallable.run(ReflectiveCallable.java:12)
	at org.junit.runners.model.FrameworkMethod.invokeExplosively(FrameworkMethod.java:56)
	at org.junit.internal.runners.statements.InvokeMethod.evaluate(InvokeMethod.java:17)
	at org.junit.runners.ParentRunner$3.evaluate(ParentRunner.java:306)
	at org.junit.runners.BlockJUnit4ClassRunner$1.evaluate(BlockJUnit4ClassRunner.java:100)
	at org.junit.runners.ParentRunner.runLeaf(ParentRunner.java:366)
	at org.junit.runners.BlockJUnit4ClassRunner.runChild(BlockJUnit4ClassRunner.java:103)
	at org.junit.runners.BlockJUnit4ClassRunner.runChild(BlockJUnit4ClassRunner.java:63)
	at org.junit.runners.ParentRunner$4.run(ParentRunner.java:331)
	at org.junit.runners.ParentRunner$1.schedule(ParentRunner.java:79)
	at org.junit.runners.ParentRunner.runChildren(ParentRunner.java:329)
	at org.junit.runners.ParentRunner.access$100(ParentRunner.java:66)
	at org.junit.runners.ParentRunner$2.evaluate(ParentRunner.java:293)
	at org.junit.runners.ParentRunner$3.evaluate(ParentRunner.java:306)
	at org.junit.runners.ParentRunner.run(ParentRunner.java:413)
	at org.junit.runner.JUnitCore.run(JUnitCore.java:137)
	at org.junit.runner.JUnitCore.run(JUnitCore.java:115)
	at org.gradle.api.internal.tasks.testing.junit.JUnitTestExecutor.runRequest(JUnitTestExecutor.java:175)
	at org.gradle.api.internal.tasks.testing.junit.JUnitTestExecutor.accept(JUnitTestExecutor.java:84)
	at org.gradle.api.internal.tasks.testing.junit.JUnitTestExecutor.accept(JUnitTestExecutor.java:47)
	at org.gradle.api.internal.tasks.testing.junit.AbstractJUnitTestDefinitionProcessor.processTestDefinition(AbstractJUnitTestDefinitionProcessor.java:65)
	at org.gradle.api.internal.tasks.testing.SuiteTestDefinitionProcessor.processTestDefinition(SuiteTestDefinitionProcessor.java:53)
	at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:104)
	at java.base/java.lang.reflect.Method.invoke(Method.java:565)
	at org.gradle.internal.dispatch.MethodInvocation.invokeOn(MethodInvocation.java:77)
	at org.gradle.internal.dispatch.ReflectionDispatch.dispatch(ReflectionDispatch.java:28)
	at org.gradle.internal.dispatch.ReflectionDispatch.dispatch(ReflectionDispatch.java:19)
	at org.gradle.internal.dispatch.ContextClassLoaderDispatch.dispatch(ContextClassLoaderDispatch.java:33)
	at org.gradle.internal.dispatch.ProxyDispatchAdapter$DispatchingInvocationHandler.invoke(ProxyDispatchAdapter.java:88)
	at jdk.proxy1/jdk.proxy1.$Proxy4.processTestDefinition(Unknown Source)
	at org.gradle.api.internal.tasks.testing.worker.TestWorker$2.run(TestWorker.java:178)
	at org.gradle.api.internal.tasks.testing.worker.TestWorker.executeAndMaintainThreadName(TestWorker.java:126)
	at org.gradle.api.internal.tasks.testing.worker.TestWorker.execute(TestWorker.java:103)
	at org.gradle.api.internal.tasks.testing.worker.TestWorker.execute(TestWorker.java:63)
	at org.gradle.process.internal.worker.child.ActionExecutionWorker.execute(ActionExecutionWorker.java:56)
	at org.gradle.process.internal.worker.child.SystemApplicationClassLoaderWorker.call(SystemApplicationClassLoaderWorker.java:122)
	at org.gradle.process.internal.worker.child.SystemApplicationClassLoaderWorker.call(SystemApplicationClassLoaderWorker.java:72)
	at worker.org.gradle.process.internal.worker.GradleWorkerMain.run(GradleWorkerMain.java:69)
	at worker.org.gradle.process.internal.worker.GradleWorkerMain.main(GradleWorkerMain.java:74)



The end of the landing log:
OK

=== tools.test_whisper_worker
..
----------------------------------------------------------------------
Ran 2 tests in 0.178s

OK

Python tool test suites:
  tools.test_agent_check_parse                       2 passed /   2 run  (0.0 s)
  tools.test_agents_md                               4 passed /   4 run  (0.0 s)
  tools.test_ai_chat_persistence                     0 passed /   0 run  (0.0 s)
  tools.test_ai_tool_usage                           5 passed /   5 run  (0.0 s)
  tools.test_ai_usage                                3 passed /   3 run  (0.0 s)
  tools.test_automation_workspace                   10 passed /  10 run  (0.0 s)
  tools.test_backdoor_managed_startup               20 passed /  20 run  (0.1 s)
  tools.test_build_dependency_rules                  3 passed /   3 run  (0.0 s)
  tools.test_build_zensical_run                     13 passed /  13 run  (5.4 s)
  tools.test_build_zensical_workspace_markdown       6 passed /   6 run  (0.2 s)
  tools.test_canonical_graphics_index                9 passed /   9 run  (3.5 s)
  tools.test_capture_transaction                    13 passed /  13 run  (0.2 s)
  tools.test_check_box_idioms                       22 passed /  22 run  (0.3 s)
  tools.test_check_doc_assets                       19 passed /  19 run  (2.4 s)
  tools.test_check_doc_offline_links                16 passed /  16 run  (11.9 s)
  tools.test_check_doc_site_links                   44 passed /  45 run, 1 skipped  (1.8 s)
  tools.test_check_module_imports                   14 passed /  14 run  (1.4 s)
  tools.test_check_native_selfcontained             20 passed /  20 run  (0.2 s)
  tools.test_check_offline_assets                   13 passed /  13 run  (1.4 s)
  tools.test_check_staged_generated_artifacts       41 passed /  41 run  (2.8 s)
  tools.test_check_study_tools                       7 passed /   7 run  (0.1 s)
  tools.test_check_test_tree_writes                 32 passed /  32 run  (3.8 s)
  tools.test_completion_sidecar                     13 passed /  13 run  (5.1 s)
  tools.test_conversation_demos                      9 passed /   9 run  (1.7 s)
  tools.test_conversation_packagers                  4 passed /   4 run  (1.3 s)
  tools.test_demo_build_scripts                      3 passed /   3 run  (0.1 s)
  tools.test_discover_field_tests                   16 passed /  16 run  (2.3 s)
  tools.test_docs_capture_audit                     34 passed /  34 run  (0.1 s)
  tools.test_docs_duplicate_embeds                   3 passed /   3 run  (0.4 s)
  tools.test_docs_hooks_capture_sources              3 passed /   3 run  (0.0 s)
  tools.test_docs_hooks_citations                   14 passed /  14 run  (0.0 s)
  tools.test_docs_hooks_fence_markers                5 passed /   5 run  (0.0 s)
  tools.test_docs_hooks_inline_code                  9 passed /   9 run  (0.0 s)
  tools.test_docs_hooks_repo_source_links           12 passed /  12 run  (0.0 s)
  tools.test_docs_native_prerequisites              36 passed /  36 run  (0.0 s)
  tools.test_docs_notes                             66 passed /  66 run  (38.3 s)
  tools.test_docs_runner_completion_backend         19 passed /  19 run  (1.7 s)
  tools.test_docs_runner_failure_reporting          27 passed /  27 run  (0.5 s)
  tools.test_docs_runner_graphics_launch             7 passed /   7 run  (0.3 s)
  tools.test_docs_runner_ui_clip                     5 passed /   5 run  (0.0 s)
  tools.test_docs_runner_window_outputs              4 passed /   4 run  (0.0 s)
  tools.test_docs_voice_detectors                   14 passed /  14 run  (90.2 s)
  tools.test_execution_receipts                      4 passed /   4 run  (0.0 s)
  tools.test_expected_test_failures                  9 passed /   9 run  (0.0 s)
  tools.test_export_particle_video_docs              2 passed /   2 run  (0.0 s)
  tools.test_field_agent                             3 passed /   3 run  (0.0 s)
  tools.test_field_backdoor                        123 passed / 123 run  (2.9 s)
  tools.test_field_inspection                        5 passed /   5 run  (0.0 s)
  tools.test_field_kit                              17 passed /  17 run  (0.6 s)
  tools.test_field_live_session_reaping              4 passed /   4 run  (0.0 s)
  tools.test_field_python_env                        4 passed /   4 run  (0.0 s)
  tools.test_field_sh_launcher                      38 passed /  39 run, 1 skipped  (15.7 s)
  tools.test_fieldcv                                15 passed /  15 run  (1.9 s)
  tools.test_git_isolation                           2 passed /   2 run  (8.0 s)
  tools.test_guard_corpora                           5 passed /   5 run  (0.4 s)
  tools.test_inventory_box_code                     13 passed /  13 run  (0.0 s)
  tools.test_land                                   32 passed /  32 run  (74.9 s)
  tools.test_land_precheck                          13 passed /  13 run  (1.0 s)
  tools.test_learned_shader_build                    7 passed /   7 run  (0.0 s)
  tools.test_loc_history                            22 passed /  24 run, 2 skipped  (2.4 s)
  tools.test_merge_regen                             6 passed /   6 run  (2.1 s)
  tools.test_native_download_cache                   3 passed /   3 run  (0.0 s)
  tools.test_neural_docs_gating                      6 passed /   6 run  (0.0 s)
  tools.test_offline_viewer_js                       4 passed /   4 run  (0.6 s)
  tools.test_open_dlss_nr                            6 passed /   6 run  (0.0 s)
  tools.test_oscquery_docs_ports                     3 passed /   3 run  (0.0 s)
  tools.test_particle_tracker                        4 passed /   4 run  (0.0 s)
  tools.test_prune_gradle_daemon_logs                2 passed /   2 run  (0.0 s)
  tools.test_ray_tracing_docs                       10 passed /  10 run  (0.1 s)
  tools.test_reap_merged_worktrees                  80 passed /  80 run  (49.1 s)
  tools.test_refactor                               57 passed /  57 run  (8.5 s)
  tools.test_refactor_pass                          20 passed /  20 run  (8.9 s)
  tools.test_refactor_session                       11 passed /  11 run  (8.7 s)
  tools.test_refactor_symbols                       15 passed /  15 run  (12.5 s)
  tools.test_repository_root_inventory               4 passed /   4 run  (0.3 s)
  tools.test_run_field_tests_native_prerequisites    2 passed /   2 run  (0.0 s)
  tools.test_run_field_tests_ordering               24 passed /  24 run  (0.0 s)
  tools.test_run_field_tests_output                  8 passed /   8 run  (0.0 s)
  tools.test_run_field_tests_port_ownership         14 passed /  14 run  (0.0 s)
  tools.test_run_field_tests_reaper                 34 passed /  34 run  (0.0 s)
  tools.test_run_python_tests                        6 passed /   6 run  (3.2 s)
  tools.test_runner_fence_image_widths               2 passed /   2 run  (0.1 s)
  tools.test_studies_page                            3 passed /   3 run  (0.0 s)
  tools.test_sync_field_audio_docs                   2 passed /   2 run  (0.0 s)
  tools.test_verification_environment               37 passed /  37 run  (3.3 s)
  tools.test_verification_lock_gradle                7 passed /   7 run  (0.0 s)
  tools.test_verification_runtime                   19 passed /  19 run  (1.5 s)
  tools.test_whisper_worker                          2 passed /   2 run  (0.2 s)
  TOTAL                                            1328 passed / 1332 run, 4 skipped

> Task :checkBoxIdioms
warning: `VIRTUAL_ENV=/Users/marc/field4/.venv` does not match the project environment path `.venv` and will be ignored; use `--active` to target the active environment instead
check_box_idioms: clean (9 file(s))

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
tree-write guard: baseline of 13137 tracked file(s), 0 already dirty -> /Users/marc/field4-worktrees/lander/build/test-tree-writes/baseline.json

> Task :citationGates
WARNING: Using incubator modules: jdk.incubator.vector
citationGates: running ServerSurfaceLedgerTest
citationGates: [1/14] ServerSurfaceLedgerTest SUCCESS in 3.5s
citationGates: running CloseNodeCitationDriftTest
citationGates: [2/14] CloseNodeCitationDriftTest SUCCESS in 2.1s
citationGates: running CitationContractTest
citationGates: [3/14] CitationContractTest SUCCESS in 3.4s
citationGates: running DerivedArithmeticContractTest
citationGates: [4/14] DerivedArithmeticContractTest SUCCESS in 4.5s
citationGates: running DocsFenceTreatmentTest
citationGates: [5/14] DocsFenceTreatmentTest SUCCESS in 0.3s
citationGates: running DocsGatesWiringTest
citationGates: [6/14] DocsGatesWiringTest SUCCESS in 0.1s
citationGates: running DocumentedApiEvaluationTest
citationGates: [7/14] DocumentedApiEvaluationTest SUCCESS in 8.7s
citationGates: running GateInputDeclarationTest
citationGates: [8/14] GateInputDeclarationTest SUCCESS in 0.1s
citationGates: running ImpossibilityClaimContractTest
citationGates: [9/14] ImpossibilityClaimContractTest SUCCESS in 7.9s
citationGates: running LanguagesCategoryTest
citationGates: [10/14] LanguagesCategoryTest SUCCESS in 1.0s
citationGates: running PersonalBoundaryTest

PersonalBoundaryTest > no committed file declares a package outside the public roots FAILED
    java.lang.AssertionError at PersonalBoundaryTest.kt:406
citationGates: [11/14] PersonalBoundaryTest FAILURE in 6.6s
citationGates: running ProseNumeralContractTest
citationGates: [12/14] ProseNumeralContractTest SUCCESS in 4.4s
citationGates: running SmallLanguageAnatomyTest
citationGates: [13/14] SmallLanguageAnatomyTest SUCCESS in 0.6s
citationGates: running TranscriptStackAutoCompileTest
citationGates: [14/14] TranscriptStackAutoCompileTest SUCCESS in 0.4s

114 tests completed, 1 failed

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

BUILD FAILED in 7m 31s
32 actionable tasks: 9 executed, 23 up-to-date

  A generated artifact is stale after this merge.
  It was not your commit that broke it and no hook ran on the clone that did:
  .git/hooks is per-clone and Git never transports it.

  Reproduce:  ./gradlew checkKDocAttachment checkPythonTools checkBoxIdioms citationGates
  The failing task names the artifact and the command that regenerates it.
  Regenerate and commit that, or `main` is red until someone else finds it.

  Set FIELD_SKIP_MERGE_GUARD=1 to skip this check.

merge_regen: a guard failed on the batch

land: 2026-10-23T07:24:10-04:00 red; asking the fixer (up to 15 min) about codex/kit-library

What the fixer did on recent reds (for calibration):
- fixed: The branch added a raw ```kotlin fence in three_output.md; switching it to the diffstatic treatment the test asks for makes DocsFenceTreatmentTest pass.
- declined: compileKotlinJvm ran out of heap after the Kotlin daemon failed and Gradle fell back to its 2 GiB daemon, so there is no source error to fix; retry the round after ./gradlew --stop and clearing the stale index.lock.
- declined: no verdict line
- fixed: The branch added reference/output.md without a nav entry in public/zensical.yml, and adding that entry makes LanguagesCategoryTest pass.
- fixed: The branch added three raw sh/json fences in presentation_studio.md, and switching them to the diffstatic treatment makes DocsFenceTreatmentTest pass.
- fixed: The layers branch built the set-visibility-animated payload with JsonObjectBuilder.put calls in route-declaring Server.kt, and an equivalent JsonObject(mapOf(...)) makes ServerSurfaceLedgerTest pass; the OfflineViewerJsTest reds are separate, caused by this machine's broken Homebrew node (missing li
- declined: OfflineViewerJsTest fails because this machine's Homebrew node can't load libsimdjson.33.dylib after a simdjson upgrade (dyld abort, exit -6); no branch caused it, and `brew reinstall node` fixes it.
- declined: test_sigterm_to_the_lander_stops_its_build hit a race where it read build.json after the lander created it but before the JSON was written; no branch in the round touched the lander, so retry, and fix it by writing build.json atomically or polling until it parses.

This replay's checkout cannot build: the `gradle` command is not available, so verify by reading the check's source and rerunning any `tools/` Python check that covers it.
