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

main before this round: bf8e431dcbeee8a942072a216bc66e74e223eb60

Branches in the round, each merged as its own commit:

## codex/presentation-studio (948faae79, by Marc)
Complete presentation teaching workflow and starter
electron/field-preload.js                                             |   12 +
 electron/main.js                                                      |   53 ++
 electron/presentation-cues.js                                         |   53 ++
 electron/presentation-preload.js                                      |   23 +
 electron/presentation.js                                              |  351 +++++++++
 public/docs/assets/madp_tetra/ATTRIBUTION.md                          |    1 -
 public/docs/presentation_studio.md                                    |   67 ++
 public/docs/reference/machine-learning.md                             |   14 +-
 public/docs/reference/workspace.md                                    |    2 +-
 public/zensical.yml                                                   |    2 +
 src/commonMain/resources/bootstrapMonaco.js                           |   32 +
 src/commonMain/resources/fieldSuggest.js                              |    9 +-
 src/commonMain/resources/presentationTsx.js                           |  115 +++
 src/jsMain/kotlin/afield/app/editor/BootstrapMonacoRuntime.kt         |    9 +-
 src/jvmMain/kotlin/afield/ai/retrieval/SemanticRetrievalService.kt    |   48 ++
 src/jvmMain/kotlin/afield/app/server/fileproxy.kt                     |   15 +
 tools/presentation/.gitignore                                         |    2 +
 tools/presentation/README.md                                          |   30 +
 tools/presentation/example/.gitignore                                 |    8 +
 tools/presentation/example/README.md                                  |    7 +
 tools/presentation/example/field-control.html                         |    4 +
 tools/presentation/example/field-demo/document.md                     |    5 +
 tools/presentation/example/field-demo/guides/teaching.md              |   10 +
 tools/presentation/example/field-demo/macros/reveal_wave.yaml         |   32 +
 tools/presentation/example/field-demo/metadata.edn                    |    1 +
 tools/presentation/example/field-demo/present_live_field_code_.kt     |    1 +
 tools/presentation/example/field-demo/present_teaching_guide_code_.kt |   30 +
 tools/presentation/example/field-presenter.html                       |    8 +
 tools/presentation/example/field-youtube.html                         |    6 +
 tools/presentation/example/package-lock.json                          | 1621 +++++++++++++++++++++++++++++++++++++++++
 tools/presentation/example/package.json                               |   14 +
 tools/presentation/example/presentation.json                          |   23 +
 tools/presentation/example/src/field-control.ts                       |   37 +
 tools/presentation/example/src/field-presenter.ts                     |   96 +++
 tools/presentation/example/src/project-query.d.ts                     |    4 +
 tools/presentation/example/src/project.ts                             |    8 +
 tools/presentation/example/src/scenes/browser.tsx                     |    9 +
 tools/presentation/example/src/scenes/close.tsx                       |    9 +
 tools/presentation/example/src/scenes/field.tsx                       |    9 +
 tools/presentation/example/src/scenes/intro.tsx                       |   16 +
 tools/presentation/example/src/scenes/video.tsx                       |    9 +
 tools/presentation/example/tsconfig.json                              |    9 +
 tools/presentation/new-talk.mjs                                       |   26 +
 tools/presentation/package-lock.json                                  | 1618 ++++++++++++++++++++++++++++++++++++++++
 tools/presentation/package.json                                       |   18 +
 tools/presentation/rehearse.mjs                                       |  110 +++
 tools/presentation/server.mjs                                         |   72 ++
 tools/presentation/tests/presentation-cues.test.mjs                   |   54 ++
 tools/presentation/tests/typescript-service.test.mjs                  |   29 +
 tools/presentation/typescript-service.mjs                             |   99 +++
 50 files changed, 4829 insertions(+), 11 deletions(-)
Its commits (the branch is all of them, not only the tip; its whole change is `git diff bf8e431dcbee...948faae7972e`):
948faae79 Complete presentation teaching workflow and starter
af03192fa Merge branch 'main' into codex/presentation-studio
8e88f7aa2 Add Field presentation studio with TSX editing

Failing tests (first): DocsFenceTreatmentTest.everyPublishedFenceCarriesTheDiffstaticTreatment
## DocsFenceTreatmentTest.everyPublishedFenceCarriesTheDiffstaticTreatment
java.lang.AssertionError: 3 code fence(s) render as raw Markdown instead of diffstatic.
Replace the opening fence; the content does not change:
  public/docs/presentation_studio.md:9   ```sh  ->  ```diffstatic {data-lang=sh}
  public/docs/presentation_studio.md:20   ```sh  ->  ```diffstatic {data-lang=sh}
  public/docs/presentation_studio.md:45   ```json  ->  ```diffstatic {data-lang=json}
If a fence must stay raw — it documents fence syntax, say — add its page to
DocsFenceTreatmentTest.allowlist with the reason.
java.lang.AssertionError: 3 code fence(s) render as raw Markdown instead of diffstatic.
Replace the opening fence; the content does not change:
  public/docs/presentation_studio.md:9   ```sh  ->  ```diffstatic {data-lang=sh}
  public/docs/presentation_studio.md:20   ```sh  ->  ```diffstatic {data-lang=sh}
  public/docs/presentation_studio.md:45   ```json  ->  ```diffstatic {data-lang=json}
If a fence must stay raw — it documents fence syntax, say — add its page to
DocsFenceTreatmentTest.allowlist with the reason.

	at org.junit.Assert.fail(Assert.java:89)
	at kotlin.test.junit.JUnitAsserter.fail(JUnitSupport.kt:56)
	at kotlin.test.Asserter.assertTrue(Assertions.kt:1558)
	at kotlin.test.Asserter.assertTrue(Assertions.kt:1568)
	at kotlin.test.AssertionsKt__AssertionsKt.assertTrue(Assertions.kt:44)
	at kotlin.test.AssertionsKt.assertTrue(Unknown Source)
	at afield.tools.docs.DocsFenceTreatmentTest.everyPublishedFenceCarriesTheDiffstaticTreatment(DocsFenceTreatmentTest.kt:111)
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
Ran 2 tests in 0.162s

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
  tools.test_build_zensical_run                     13 passed /  13 run  (5.2 s)
  tools.test_build_zensical_workspace_markdown       6 passed /   6 run  (0.0 s)
  tools.test_canonical_graphics_index                9 passed /   9 run  (0.2 s)
  tools.test_capture_transaction                    13 passed /  13 run  (0.1 s)
  tools.test_check_box_idioms                       22 passed /  22 run  (0.3 s)
  tools.test_check_doc_assets                       19 passed /  19 run  (1.4 s)
  tools.test_check_doc_offline_links                16 passed /  16 run  (5.6 s)
  tools.test_check_doc_site_links                   44 passed /  45 run, 1 skipped  (1.1 s)
  tools.test_check_module_imports                   14 passed /  14 run  (1.2 s)
  tools.test_check_native_selfcontained             20 passed /  20 run  (0.2 s)
  tools.test_check_offline_assets                   13 passed /  13 run  (1.2 s)
  tools.test_check_staged_generated_artifacts       41 passed /  41 run  (2.3 s)
  tools.test_check_study_tools                       7 passed /   7 run  (0.1 s)
  tools.test_check_test_tree_writes                 32 passed /  32 run  (3.2 s)
  tools.test_completion_sidecar                     13 passed /  13 run  (5.1 s)
  tools.test_conversation_demos                      9 passed /   9 run  (1.5 s)
  tools.test_conversation_packagers                  4 passed /   4 run  (1.2 s)
  tools.test_demo_build_scripts                      3 passed /   3 run  (0.1 s)
  tools.test_discover_field_tests                   16 passed /  16 run  (2.2 s)
  tools.test_docs_capture_audit                     34 passed /  34 run  (0.1 s)
  tools.test_docs_duplicate_embeds                   3 passed /   3 run  (0.4 s)
  tools.test_docs_hooks_capture_sources              3 passed /   3 run  (0.0 s)
  tools.test_docs_hooks_citations                   14 passed /  14 run  (0.0 s)
  tools.test_docs_hooks_fence_markers                5 passed /   5 run  (0.0 s)
  tools.test_docs_hooks_inline_code                  9 passed /   9 run  (0.0 s)
  tools.test_docs_hooks_repo_source_links           12 passed /  12 run  (0.0 s)
  tools.test_docs_native_prerequisites              36 passed /  36 run  (0.0 s)
  tools.test_docs_notes                             66 passed /  66 run  (28.8 s)
  tools.test_docs_runner_completion_backend         19 passed /  19 run  (1.6 s)
  tools.test_docs_runner_failure_reporting          27 passed /  27 run  (0.5 s)
  tools.test_docs_runner_graphics_launch             7 passed /   7 run  (0.2 s)
  tools.test_docs_runner_ui_clip                     5 passed /   5 run  (0.0 s)
  tools.test_docs_runner_window_outputs              4 passed /   4 run  (0.0 s)
  tools.test_docs_voice_detectors                   14 passed /  14 run  (70.5 s)
  tools.test_execution_receipts                      4 passed /   4 run  (0.0 s)
  tools.test_expected_test_failures                  9 passed /   9 run  (0.0 s)
  tools.test_export_particle_video_docs              2 passed /   2 run  (0.0 s)
  tools.test_field_agent                             3 passed /   3 run  (0.0 s)
  tools.test_field_backdoor                        123 passed / 123 run  (2.4 s)
  tools.test_field_inspection                        5 passed /   5 run  (0.0 s)
  tools.test_field_kit                              17 passed /  17 run  (0.4 s)
  tools.test_field_live_session_reaping              4 passed /   4 run  (0.0 s)
  tools.test_field_python_env                        4 passed /   4 run  (0.0 s)
  tools.test_field_sh_launcher                      38 passed /  39 run, 1 skipped  (13.8 s)
  tools.test_fieldcv                                15 passed /  15 run  (1.4 s)
  tools.test_git_isolation                           2 passed /   2 run  (7.1 s)
  tools.test_guard_corpora                           5 passed /   5 run  (0.3 s)
  tools.test_inventory_box_code                     13 passed /  13 run  (0.0 s)
  tools.test_land                                   31 passed /  31 run  (63.5 s)
  tools.test_land_precheck                          13 passed /  13 run  (1.0 s)
  tools.test_learned_shader_build                    7 passed /   7 run  (0.0 s)
  tools.test_loc_history                            22 passed /  24 run, 2 skipped  (2.0 s)
  tools.test_merge_regen                             6 passed /   6 run  (2.0 s)
  tools.test_native_download_cache                   3 passed /   3 run  (0.0 s)
  tools.test_neural_docs_gating                      6 passed /   6 run  (0.0 s)
  tools.test_open_dlss_nr                            6 passed /   6 run  (0.0 s)
  tools.test_oscquery_docs_ports                     3 passed /   3 run  (0.0 s)
  tools.test_particle_tracker                        4 passed /   4 run  (0.0 s)
  tools.test_prune_gradle_daemon_logs                2 passed /   2 run  (0.0 s)
  tools.test_ray_tracing_docs                       10 passed /  10 run  (0.1 s)
  tools.test_reap_merged_worktrees                  75 passed /  75 run  (46.8 s)
  tools.test_refactor                               57 passed /  57 run  (8.7 s)
  tools.test_refactor_pass                          20 passed /  20 run  (8.1 s)
  tools.test_refactor_session                       11 passed /  11 run  (9.1 s)
  tools.test_refactor_symbols                       15 passed /  15 run  (11.7 s)
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
  tools.test_verification_environment               37 passed /  37 run  (2.9 s)
  tools.test_verification_lock_gradle                7 passed /   7 run  (0.0 s)
  tools.test_verification_runtime                   19 passed /  19 run  (1.5 s)
  tools.test_whisper_worker                          2 passed /   2 run  (0.2 s)
  TOTAL                                            1318 passed / 1322 run, 4 skipped

> Task :checkBoxIdioms
warning: `VIRTUAL_ENV=/Users/marc/field4/.venv` does not match the project environment path `.venv` and will be ignored; use `--active` to target the active environment instead
check_box_idioms: clean (4 file(s))

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
tree-write guard: baseline of 13046 tracked file(s), 0 already dirty -> /Users/marc/field4-worktrees/lander/build/test-tree-writes/baseline.json

> Task :citationGates
WARNING: Using incubator modules: jdk.incubator.vector
citationGates: running ServerSurfaceLedgerTest
citationGates: [1/14] ServerSurfaceLedgerTest SUCCESS in 3.0s
citationGates: running CloseNodeCitationDriftTest
citationGates: [2/14] CloseNodeCitationDriftTest SUCCESS in 1.8s
citationGates: running CitationContractTest
citationGates: [3/14] CitationContractTest SUCCESS in 3.7s
citationGates: running DerivedArithmeticContractTest
citationGates: [4/14] DerivedArithmeticContractTest SUCCESS in 4.5s
citationGates: running DocsFenceTreatmentTest

DocsFenceTreatmentTest > everyPublishedFenceCarriesTheDiffstaticTreatment FAILED
    java.lang.AssertionError at DocsFenceTreatmentTest.kt:111
citationGates: [5/14] DocsFenceTreatmentTest FAILURE in 0.3s
citationGates: running DocsGatesWiringTest
citationGates: [6/14] DocsGatesWiringTest SUCCESS in 0.1s
citationGates: running DocumentedApiEvaluationTest
citationGates: [7/14] DocumentedApiEvaluationTest SUCCESS in 8.6s
citationGates: running GateInputDeclarationTest
citationGates: [8/14] GateInputDeclarationTest SUCCESS in 0.1s
citationGates: running ImpossibilityClaimContractTest
citationGates: [9/14] ImpossibilityClaimContractTest SUCCESS in 7.9s
citationGates: running LanguagesCategoryTest
citationGates: [10/14] LanguagesCategoryTest SUCCESS in 1.1s
citationGates: running PersonalBoundaryTest
citationGates: [11/14] PersonalBoundaryTest SUCCESS in 6.6s
citationGates: running ProseNumeralContractTest
citationGates: [12/14] ProseNumeralContractTest SUCCESS in 5.1s
citationGates: running SmallLanguageAnatomyTest
citationGates: [13/14] SmallLanguageAnatomyTest SUCCESS in 3.5s
citationGates: running TranscriptStackAutoCompileTest
citationGates: [14/14] TranscriptStackAutoCompileTest SUCCESS in 19.7s

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

BUILD FAILED in 6m 43s
32 actionable tasks: 9 executed, 23 up-to-date

  A generated artifact is stale after this merge.
  It was not your commit that broke it and no hook ran on the clone that did:
  .git/hooks is per-clone and Git never transports it.

  Reproduce:  ./gradlew checkKDocAttachment checkPythonTools checkBoxIdioms citationGates
  The failing task names the artifact and the command that regenerates it.
  Regenerate and commit that, or `main` is red until someone else finds it.

  Set FIELD_SKIP_MERGE_GUARD=1 to skip this check.

merge_regen: a guard failed on the batch

land: 2026-10-22T07:55:11+02:00 red; asking the fixer (up to 15 min) about codex/presentation-studio

What the fixer did on recent reds (for calibration):
- fixed: PointAccumulation.kt still called the old target.apply() that tour-gpu-alt-enter replaced with +target, so I switched both calls and compileKotlinJvm passes.
- declined: The WebRTC branch adds unlisted /ws commands and HTTP routes that need a guard decision, has a transcript box that fails to compile, and causes three more failures (DocumentedApiEvaluation, KDoc attachment, Python tools), which is too much for one small fix.
- declined: no verdict line
- declined: no verdict line
- fixed: The branch added a raw ```kotlin fence in three_output.md; switching it to the diffstatic treatment the test asks for makes DocsFenceTreatmentTest pass.
- declined: compileKotlinJvm ran out of heap after the Kotlin daemon failed and Gradle fell back to its 2 GiB daemon, so there is no source error to fix; retry the round after ./gradlew --stop and clearing the stale index.lock.
- declined: no verdict line
- fixed: The branch added reference/output.md without a nav entry in public/zensical.yml, and adding that entry makes LanguagesCategoryTest pass.

This replay's checkout cannot build: the `gradle` command is not available, so verify by reading the check's source and rerunning any `tools/` Python check that covers it.
