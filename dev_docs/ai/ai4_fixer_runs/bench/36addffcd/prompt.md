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

main before this round: cb1cf4b0c163d4779762c06f1e61b68b81f96ffb

Branches in the round, each merged as its own commit:

## claude/ai4-attach-lines (50fbbd747, by Marc)
AI4: live model progress, line numbers and near-matches for AttachmentRequest, unindexed-symbol warning
 src/jvmMain/kotlin/afield/ai/models/OpenAiIdeCodexBridge.kt        | 47 ++++++++++++++++++++++++++++++++++++++++++++--
 src/jvmMain/kotlin/afield/ai/tools/AgentToolRegistrations.kt       |  3 +++
 src/jvmMain/kotlin/afield/ai/tools/AttachmentExcerpt.kt            | 21 +++++++++++++++++++--
 src/jvmMain/kotlin/afield/ai/tools/Tools.kt                        | 14 ++++++++++++--
 src/jvmMain/kotlin/afield/ai/tools/WorkspaceSearch.kt              |  2 +-
 src/jvmMain/kotlin/afield/ai/tools/WorkspaceSymbolSearchSupport.kt | 36 +++++++++++++++++++++++++++++++++--
 src/jvmTest/kotlin/afield/ai/tools/AttachmentExcerptTest.kt        | 23 +++++++++++++++++++++++
 src/jvmTest/kotlin/afield/ai/tools/UnindexedSymbolFilesTest.kt     | 28 +++++++++++++++++++++++++++
 src/jvmTest/kotlin/afield/ai/tools/WorkspaceSearchTest.kt          |  3 +++
 9 files changed, 168 insertions(+), 9 deletions(-)

Failing tests (first): PersonalBoundaryTest.no
## PersonalBoundaryTest.no committed file declares a package outside the public roots
java.lang.AssertionError: committed files declare packages outside PUBLIC_IMPORT_ROOTS:
  src/jvmTest/kotlin/afield/ai/tools/AttachmentExcerptTest.kt → x

A file in a private package is private work in a tracked location, whichever
direction its imports point. If this is a source file, move it under afield. and
rename its package to match. If it is a patch or a quotation of private source,
the code does not belong in a committed file: state the finding against afield.*
and leave the private source where it is.
java.lang.AssertionError: committed files declare packages outside PUBLIC_IMPORT_ROOTS:
  src/jvmTest/kotlin/afield/ai/tools/AttachmentExcerptTest.kt → x

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
  tools.test_build_zensical_run                     18 passed /  18 run  (7.2 s)
  tools.test_build_zensical_workspace_markdown       6 passed /   6 run  (0.0 s)
  tools.test_canonical_graphics_index                9 passed /   9 run  (0.3 s)
  tools.test_capture_transaction                    17 passed /  17 run  (0.1 s)
  tools.test_check_box_idioms                       22 passed /  22 run  (0.4 s)
  tools.test_check_doc_assets                       19 passed /  19 run  (1.6 s)
  tools.test_check_doc_offline_links                16 passed /  16 run  (7.1 s)
  tools.test_check_doc_site_links                   44 passed /  45 run, 1 skipped  (1.3 s)
  tools.test_check_module_imports                   14 passed /  14 run  (1.5 s)
  tools.test_check_native_selfcontained             20 passed /  20 run  (0.2 s)
  tools.test_check_offline_assets                   13 passed /  13 run  (1.4 s)
  tools.test_check_staged_generated_artifacts       42 passed /  42 run  (3.2 s)
  tools.test_check_study_tools                       7 passed /   7 run  (0.1 s)
  tools.test_check_test_tree_writes                 32 passed /  32 run  (4.1 s)
  tools.test_completion_diff                         8 passed /   8 run  (0.6 s)
  tools.test_completion_sidecar                     13 passed /  13 run  (4.6 s)
  tools.test_conversation_demos                      9 passed /   9 run  (2.4 s)
  tools.test_conversation_packagers                  4 passed /   4 run  (1.7 s)
  tools.test_demo_build_scripts                      3 passed /   3 run  (0.2 s)
  tools.test_discover_field_tests                   16 passed /  16 run  (3.4 s)
  tools.test_docs_capture_audit                     34 passed /  34 run  (0.1 s)
  tools.test_docs_duplicate_embeds                   3 passed /   3 run  (0.5 s)
  tools.test_docs_hooks_capture_sources              3 passed /   3 run  (0.0 s)
  tools.test_docs_hooks_citations                   14 passed /  14 run  (0.0 s)
  tools.test_docs_hooks_fence_markers                5 passed /   5 run  (0.0 s)
  tools.test_docs_hooks_inline_code                  9 passed /   9 run  (0.0 s)
  tools.test_docs_hooks_repo_source_links           12 passed /  12 run  (0.0 s)
  tools.test_docs_native_prerequisites              39 passed /  39 run  (0.0 s)
  tools.test_docs_notes                             68 passed /  68 run  (36.8 s)
  tools.test_docs_runner_completion_backend         21 passed /  21 run  (3.7 s)
  tools.test_docs_runner_failure_reporting          27 passed /  27 run  (0.6 s)
  tools.test_docs_runner_graphics_launch             7 passed /   7 run  (0.4 s)
  tools.test_docs_runner_inputs                      1 passed /   1 run  (1.0 s)
  tools.test_docs_runner_ui_clip                     5 passed /   5 run  (0.0 s)
  tools.test_docs_runner_window_outputs              4 passed /   4 run  (0.0 s)
  tools.test_docs_voice_detectors                   15 passed /  15 run  (96.5 s)
  tools.test_execution_receipts                      4 passed /   4 run  (0.1 s)
  tools.test_expected_test_failures                  9 passed /   9 run  (0.0 s)
  tools.test_export_particle_video_docs              2 passed /   2 run  (0.0 s)
  tools.test_feel_bench                             12 passed /  12 run  (0.1 s)
  tools.test_field_agent                             7 passed /   7 run  (0.0 s)
  tools.test_field_backdoor                        123 passed / 123 run  (3.3 s)
  tools.test_field_inspection                        5 passed /   5 run  (0.0 s)
  tools.test_field_kit                              17 passed /  17 run  (0.6 s)
  tools.test_field_live_session_reaping              4 passed /   4 run  (0.0 s)
  tools.test_field_publication                       5 passed /   5 run  (0.5 s)
  tools.test_field_python_env                        4 passed /   4 run  (0.0 s)
  tools.test_field_sh_launcher                      38 passed /  39 run, 1 skipped  (17.6 s)
  tools.test_fieldcv                                15 passed /  15 run  (1.8 s)
  tools.test_fieldpen                                0 passed /   9 run, 9 skipped  (0.0 s)
  tools.test_git_isolation                           2 passed /   2 run  (10.2 s)
  tools.test_guard_corpora                           5 passed /   5 run  (0.5 s)
  tools.test_inventory_box_code                     13 passed /  13 run  (0.0 s)
  tools.test_land                                   34 passed /  34 run  (92.2 s)
  tools.test_land_precheck                          18 passed /  18 run  (5.8 s)
  tools.test_learned_shader_build                    7 passed /   7 run  (0.0 s)
  tools.test_loc_history                            22 passed /  24 run, 2 skipped  (2.1 s)
  tools.test_local_ai_monitor                        6 passed /   6 run  (0.0 s)
  tools.test_merge_regen                             7 passed /   7 run  (3.5 s)
  tools.test_native_download_cache                   3 passed /   3 run  (0.0 s)
  tools.test_neural_docs_gating                      6 passed /   6 run  (0.0 s)
  tools.test_offline_viewer_js                       5 passed /   5 run  (0.5 s)
  tools.test_open_dlss_nr                            6 passed /   6 run  (0.0 s)
  tools.test_oscquery_docs_ports                     3 passed /   3 run  (0.0 s)
  tools.test_particle_tracker                        4 passed /   4 run  (0.0 s)
  tools.test_prune_gradle_daemon_logs                2 passed /   2 run  (0.0 s)
  tools.test_ray_tracing_docs                       10 passed /  10 run  (0.1 s)
  tools.test_reap_merged_worktrees                  80 passed /  80 run  (63.0 s)
  tools.test_refactor                               57 passed /  57 run  (12.3 s)
  tools.test_refactor_pass                          20 passed /  20 run  (10.4 s)
  tools.test_refactor_session                       11 passed /  11 run  (11.6 s)
  tools.test_refactor_symbols                       15 passed /  15 run  (17.9 s)
  tools.test_repository_root_inventory               4 passed /   4 run  (0.4 s)
  tools.test_run_field_tests_native_prerequisites    2 passed /   2 run  (0.0 s)
  tools.test_run_field_tests_ordering               24 passed /  24 run  (0.0 s)
  tools.test_run_field_tests_output                  8 passed /   8 run  (0.0 s)
  tools.test_run_field_tests_port_ownership         14 passed /  14 run  (0.1 s)
  tools.test_run_field_tests_reaper                 34 passed /  34 run  (0.0 s)
  tools.test_run_python_tests                        6 passed /   6 run  (3.4 s)
  tools.test_runner_fence_image_widths               2 passed /   2 run  (0.1 s)
  tools.test_studies_page                            3 passed /   3 run  (0.0 s)
  tools.test_sync_field_audio_docs                   2 passed /   2 run  (0.0 s)
  tools.test_verification_environment               37 passed /  37 run  (3.4 s)
  tools.test_verification_lock_gradle                7 passed /   7 run  (0.0 s)
  tools.test_verification_runtime                   19 passed /  19 run  (1.3 s)
  tools.test_whisper_worker                          2 passed /   2 run  (0.2 s)
  TOTAL                                            1395 passed / 1408 run, 13 skipped

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
tree-write guard: baseline of 13654 tracked file(s), 0 already dirty -> /Users/marc/field4-worktrees/lander/build/test-tree-writes/baseline.json

> Task :citationGates
WARNING: Using incubator modules: jdk.incubator.vector
citationGates: running ServerSurfaceLedgerTest
citationGates: [1/14] ServerSurfaceLedgerTest SUCCESS in 3.8s
citationGates: running CloseNodeCitationDriftTest
citationGates: [2/14] CloseNodeCitationDriftTest SUCCESS in 2.1s
citationGates: running CitationContractTest
citationGates: [3/14] CitationContractTest SUCCESS in 4.0s
citationGates: running DerivedArithmeticContractTest
citationGates: [4/14] DerivedArithmeticContractTest SUCCESS in 5.7s
citationGates: running DocsFenceTreatmentTest
citationGates: [5/14] DocsFenceTreatmentTest SUCCESS in 0.4s
citationGates: running DocsGatesWiringTest
citationGates: [6/14] DocsGatesWiringTest SUCCESS in 0.1s
citationGates: running DocumentedApiEvaluationTest
citationGates: [7/14] DocumentedApiEvaluationTest SUCCESS in 10.4s
citationGates: running GateInputDeclarationTest
citationGates: [8/14] GateInputDeclarationTest SUCCESS in 0.1s
citationGates: running ImpossibilityClaimContractTest
citationGates: [9/14] ImpossibilityClaimContractTest SUCCESS in 9.1s
citationGates: running LanguagesCategoryTest
citationGates: [10/14] LanguagesCategoryTest SUCCESS in 1.3s
citationGates: running PersonalBoundaryTest

PersonalBoundaryTest > no committed file declares a package outside the public roots FAILED
    java.lang.AssertionError at PersonalBoundaryTest.kt:406
citationGates: [11/14] PersonalBoundaryTest FAILURE in 7.7s
citationGates: running ProseNumeralContractTest
citationGates: [12/14] ProseNumeralContractTest SUCCESS in 5.5s
citationGates: running SmallLanguageAnatomyTest
citationGates: [13/14] SmallLanguageAnatomyTest SUCCESS in 0.7s
citationGates: running TranscriptStackAutoCompileTest
citationGates: [14/14] TranscriptStackAutoCompileTest SUCCESS in 0.8s

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

BUILD FAILED in 8m 35s
32 actionable tasks: 9 executed, 23 up-to-date

  A generated artifact is stale after this merge.
  It was not your commit that broke it and no hook ran on the clone that did:
  .git/hooks is per-clone and Git never transports it.

  Reproduce:  ./gradlew checkKDocAttachment checkPythonTools checkBoxIdioms citationGates
  The failing task names the artifact and the command that regenerates it.
  Regenerate and commit that, or `main` is red until someone else finds it.

  Set FIELD_SKIP_MERGE_GUARD=1 to skip this check.

merge_regen: a guard failed on the batch

land: 2026-10-28T14:58:09+01:00 red; asking the fixer (up to 15 min) about claude/ai4-attach-lines

What the fixer did on recent reds (for calibration):
- fixed: The branch added box_graph_demo.yaml without its required entry in public/docs/agent_transcripts.md, and adding that entry under the execute-verified section makes checkAgentTranscriptIndex pass.
- fixed: The new learned_pen.md page opened four code fences without the diffstatic treatment, and switching those opening lines to diffstatic {data-lang=…} makes DocsFenceTreatmentTest pass.
- fixed: The branch added runaway_box_interrupt.yaml without its required entry in public/docs/agent_transcripts.md, and adding that entry with its Open link under the verified-by = compile section makes checkAgentTranscriptIndex pass.
- fixed: The branch added an uncited 'cannot' claim to SPREADSHEET_CONSTRAINTS.md, and adding the spreadsheet.kt:399#SpreadsheetEvalHelpers citation makes ImpossibilityClaimContractTest pass.
- declined: The branch changed how FileInline loads (a newer, different inline copy now wins over the sidecar), so the document_on_disk.md fence, its prose and its citation all describe the old behaviour and need a rewrite by the author, not a re-anchor.
- declined: confluence_grid was interrupted after 30 s while sleeping in its own pacing loop, a timing failure that the branch's prose-only edit to that transcript cannot have caused.
- declined: checkAgentTranscriptLayouts fails on renderer_browsing/renderer_column/spreadsheet_tour frames that came from earlier main commits (84e89cf6e, 0a037a857), which this branch never touched, so main is red on its own.
- fixed: The branch's new quick-ref page-eval line left out the parser's optional [script] argument, and adding it (plus the script entry in the test's _ROLE table) makes test_quick_ref_usage_lines_match_the_parser pass.

This replay's checkout cannot build: the `gradle` command is not available, so verify by reading the check's source and rerunning any `tools/` Python check that covers it.
