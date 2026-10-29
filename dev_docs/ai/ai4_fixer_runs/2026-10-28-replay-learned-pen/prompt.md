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

main before this round: bf98c24ed

Branches in the round, each merged as its own commit:

## claude/handwriting-pen (04e486575, by Marc)
 python/fieldpen/LICENSE                                                | 202 ++++++++++++++++
 python/fieldpen/NOTICE                                                 |  23 ++
 python/fieldpen/README.md                                              |  88 +++++++
 python/fieldpen/__init__.py                                            |   7 +
 python/fieldpen/data.py                                                |  73 ++++++
 python/fieldpen/lab.html                                               |  69 ++++++
 python/fieldpen/lab.py                                                 |  63 +++++
 python/fieldpen/model.py                                               | 138 +++++++++++
 python/fieldpen/prepare.py                                             |  32 +++
 python/fieldpen/server.py                                              | 118 +++++++++
 python/fieldpen/session.py                                             | 158 ++++++++++++
 python/fieldpen/train.py                                               |  76 ++++++
 src/jvmMain/kotlin/afield/ai/learning/pen/pen.kt                       | 531 +++++++++++++++++++++++++++++++++++++++++
 src/jvmMain/kotlin/afield/examples/DemoTranscriptExecSmokeTest.kt      |   3 +
 src/jvmMain/resources/afield/pen/DISTILL-LICENSE                       | 396 ++++++++++++++++++++++++++++++
 src/jvmMain/resources/afield/pen/distill-b64.json                      |   1 +
 src/jvmTest/kotlin/afield/ai/learning/pen/PenModelTest.kt              |  74 ++++++
 src/jvmTest/resources/afield/pen/distill_reference.json                |   1 +
 src/jvmTest/resources/afield/pen/tiny.fieldpen.json                    |   1 +
 src/jvmTest/resources/afield/pen/tiny_reference.json                   |   1 +
 tools/completion-corpus/boxes/manifest.json                            |   4 +
 tools/fieldpen_reference_fixture.py                                    |  46 ++++
 tools/test_fieldpen.py                                                 | 101 ++++++++
 uv.lock                                                                | 289 +++++++++++++++++++++-
 47 files changed, 3398 insertions(+), 14 deletions(-)
Failing tests (first): DocsFenceTreatmentTest.everyPublishedFenceCarriesTheDiffstaticTreatment
## DocsFenceTreatmentTest.everyPublishedFenceCarriesTheDiffstaticTreatment
java.lang.AssertionError: 4 code fence(s) render as raw Markdown instead of diffstatic.
Replace the opening fence; the content does not change:
  public/docs/learned_pen.md:212   ```json  ->  ```diffstatic {data-lang=json}
  public/docs/learned_pen.md:220   ```bash  ->  ```diffstatic {data-lang=bash}
  public/docs/learned_pen.md:234   ```python  ->  ```diffstatic {data-lang=python}
  public/docs/learned_pen.md:241   ```kotlin  ->  ```diffstatic {data-lang=kotlin}
If a fence must stay raw — it documents fence syntax, say — add its page to
DocsFenceTreatmentTest.allowlist with the reason.
java.lang.AssertionError: 4 code fence(s) render as raw Markdown instead of diffstatic.
Replace the opening fence; the content does not change:
  public/docs/learned_pen.md:212   ```json  ->  ```diffstatic {data-lang=json}
  public/docs/learned_pen.md:220   ```bash  ->  ```diffstatic {data-lang=bash}
  public/docs/learned_pen.md:234   ```python  ->  ```diffstatic {data-lang=python}
  public/docs/learned_pen.md:241   ```kotlin  ->  ```diffstatic {data-lang=kotlin}
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
import afield.app.protocol.*
import afield.app.server.*
import afield.app.settings.*
import afield.audio.ai.*
import afield.audio.analysis.*
import afield.audio.analysis.loris.*
import afield.audio.synth.*
import afield.audio.tone.*
import afield.data.collections.*
import afield.data.formats.*
import afield.data.graph.*
import afield.data.query.datalog.*
import afield.data.query.graph.*
import afield.data.sorting.*
import afield.data.table.*
import afield.document.context.*
import afield.document.graph.*
import afield.document.graph.Box
import afield.document.history.*
import afield.document.persistence.*
import afield.document.properties.*
import afield.document.workspace.*
import afield.geometry.mesh.*
import afield.geometry.path.*
import afield.geometry.polytope.*
import afield.geometry.spatial.*
import afield.graphics.color.*
import afield.graphics.interaction.*
import afield.graphics.paint.*
import afield.graphics.render.*
import afield.graphics.scene.*
import afield.graphics.shader.*
import afield.graphics.text.*
import afield.graphics.texture.*
import afield.graphics.three.*
import afield.graphics.visionos.*
import afield.graphics.vulkan.*
import afield.graphics.vulkan.buffers.*
import afield.graphics.vulkan.targets.*
import afield.graphics.vulkan.util.*
import afield.io.osc.*
import afield.io.process.*
import afield.lang.lambda.*
import afield.lang.nano.*
import afield.lang.python.*
import afield.lang.rectsl.registerRectslStudioTools
import afield.lang.rectsl.renderRectslToImages
import afield.lang.remotejs.*
import afield.lang.rpn.*
import afield.lang.stacksl.*
import afield.lang.stacksl.evolution.*
import afield.math.autodiff.*
import afield.math.control.*
import afield.math.estimation.*
import afield.math.functions.*
import afield.math.gp.*
import afield.math.linalg.*
import afield.math.linalg.Quat
import afield.math.linalg.Vec2
import afield.math.linalg.Vec3
import afield.math.linalg.Vec4
import afield.math.linalg.Vector2f
import afield.math.linalg.Vector2fc
import afield.math.linalg.Vector3f
import afield.math.linalg.Vector3fc
import afield.math.linalg.Vector4f
import afield.math.linalg.Vector4fc
import afield.math.linalg.arena.*
import afield.math.linalg.storage.*
import afield.math.optimization.*
import afield.math.statistics.*
import afield.math.units.*
import afield.media.capture.*
import afield.media.image.*
import afield.media.video.*
import afield.runtime.completion.*
import afield.runtime.execution.*
import afield.runtime.kotlin.*
import afield.runtime.languages.*
import afield.runtime.lifecycle.*
import afield.simulation.agents.*
import afield.simulation.automata.*
import afield.simulation.particles.*
import afield.study.build.*
import afield.study.evidence.*
import afield.study.search.*
import afield.study.source.*
import afield.study.ui.*
import afield.tools.automation.*
import afield.tools.docs.*
import afield.ui.controls.*
import afield.ui.film.*
import afield.ui.layout.*
import afield.ui.notation.*
import afield.ui.output.*
import afield.ui.output.templates.*
import afield.ui.slides.*
import afield.ui.visualization.*
import kotlin.math.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

[execute] PASS learned_pen (applied=4, output lines=0)
BoxCodeCompileGuard ok — 1 transcript(s) compiled

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
tree-write guard: baseline of 13390 tracked file(s), 0 already dirty -> /Users/marc/field4-worktrees/lander/build/test-tree-writes/baseline.json

> Task :citationGates
WARNING: Using incubator modules: jdk.incubator.vector
citationGates: running ServerSurfaceLedgerTest
citationGates: [1/14] ServerSurfaceLedgerTest SUCCESS in 3.3s
citationGates: running CloseNodeCitationDriftTest
citationGates: [2/14] CloseNodeCitationDriftTest SUCCESS in 2.1s
citationGates: running CitationContractTest
citationGates: [3/14] CitationContractTest SUCCESS in 3.5s
citationGates: running DerivedArithmeticContractTest
citationGates: [4/14] DerivedArithmeticContractTest SUCCESS in 4.8s
citationGates: running DocsFenceTreatmentTest

DocsFenceTreatmentTest > everyPublishedFenceCarriesTheDiffstaticTreatment FAILED
    java.lang.AssertionError at DocsFenceTreatmentTest.kt:111
citationGates: [5/14] DocsFenceTreatmentTest FAILURE in 0.3s
citationGates: running DocsGatesWiringTest
citationGates: [6/14] DocsGatesWiringTest SUCCESS in 0.1s
citationGates: running DocumentedApiEvaluationTest
citationGates: [7/14] DocumentedApiEvaluationTest SUCCESS in 9.3s
citationGates: running GateInputDeclarationTest
citationGates: [8/14] GateInputDeclarationTest SUCCESS in 0.1s
citationGates: running ImpossibilityClaimContractTest
citationGates: [9/14] ImpossibilityClaimContractTest SUCCESS in 8.7s
citationGates: running LanguagesCategoryTest
citationGates: [10/14] LanguagesCategoryTest SUCCESS in 1.0s
citationGates: running PersonalBoundaryTest
citationGates: [11/14] PersonalBoundaryTest SUCCESS in 6.7s
citationGates: running ProseNumeralContractTest
citationGates: [12/14] ProseNumeralContractTest SUCCESS in 4.7s
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

BUILD FAILED in 8m 6s
34 actionable tasks: 11 executed, 23 up-to-date

  A generated artifact is stale after this merge.
  It was not your commit that broke it and no hook ran on the clone that did:
  .git/hooks is per-clone and Git never transports it.

  Reproduce:  ./gradlew checkKDocAttachment checkAgentTranscriptIndex checkPythonTools checkBoxIdioms checkBoxCodeCompiles citationGates
  The failing task names the artifact and the command that regenerates it.
  Regenerate and commit that, or `main` is red until someone else finds it.

  Set FIELD_SKIP_MERGE_GUARD=1 to skip this check.

merge_regen: a guard failed on the batch


(Replay note: this checkout's Gradle output is cold, and Gradle is not among your commands in this
replay; confirm with the files and git, and say so in your verdict.)
