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

main before this round: 349070b99b33405bd0481139d6be678d19de9f92

Branches in the round, each merged as its own commit:

## nightly/2026-10-26/4-sheet-constraints (6390d7dcd, by Marc)
spreadsheets: make the temperature sheet tall enough for every row
 build.gradle.kts                                                            |   6 +
 dev_docs/SPREADSHEET.md                                                     |  59 ++++
 dev_docs/SPREADSHEET_CONSTRAINTS.md                                         |  80 ++++++
 dev_docs/agents/transcripts/README.md                                       |   2 +
 dev_docs/agents/transcripts/spreadsheet_constraints.yaml                    |  51 ++++
 dev_docs/agents/transcripts/spreadsheet_tour.yaml                           |  46 +++
 public/docs/agent_transcripts.md                                            |  19 ++
 public/docs/assets/agent_transcripts/spreadsheet_constraints/AGENTS.md      | 129 +++++++++
 public/docs/assets/agent_transcripts/spreadsheet_constraints/document.md    |  70 +++++
 .../spreadsheet_constraints/guides/spreadsheets-solving-for-cells.md        |   6 +
 public/docs/assets/agent_transcripts/spreadsheet_constraints/metadata.edn   |   1 +
 .../spreadsheet_constraints/spreadsheet_constraints_rectangle_code_.kt      |  14 +
 .../spreadsheet_constraints/spreadsheet_constraints_temperature_code_.kt    |  11 +
 public/docs/assets/agent_transcripts/spreadsheet_tour/AGENTS.md             | 129 +++++++++
 public/docs/assets/agent_transcripts/spreadsheet_tour/document.md           |  57 ++++
 .../docs/assets/agent_transcripts/spreadsheet_tour/guides/spreadsheets.md   |   6 +
 public/docs/assets/agent_transcripts/spreadsheet_tour/metadata.edn          |   1 +
 .../agent_transcripts/spreadsheet_tour/spreadsheet_tour_rates_code_.kt      |   4 +
 .../agent_transcripts/spreadsheet_tour/spreadsheet_tour_sheet_code_.kt      |  16 ++
 public/docs/assets/spreadsheets/bill.png                                    | Bin 0 -> 155702 bytes
 public/docs/assets/spreadsheets/errors.png                                  | Bin 0 -> 103279 bytes
 public/docs/assets/spreadsheets/kotlin.png                                  | Bin 0 -> 155942 bytes
 public/docs/assets/spreadsheets/promotion.png                               | Bin 0 -> 210235 bytes
 public/docs/assets/spreadsheets/read.png                                    | Bin 0 -> 123522 bytes
 public/docs/assets/spreadsheets/self.png                                    | Bin 0 -> 102541 bytes
 public/docs/assets/spreadsheets/solve.png                                   | Bin 0 -> 140413 bytes
 public/docs/assets/spreadsheets/temperature.mp4                             | Bin 0 -> 59755 bytes
 public/docs/assets/spreadsheets/temperature.png                             | Bin 0 -> 113415 bytes
 public/docs/assets/spreadsheets/unsatisfiable.png                           | Bin 0 -> 159368 bytes
 public/docs/assets/transcripts/sheet_temperature_setup.yaml                 |  26 ++
 public/docs/funFeatures.md                                                  |   2 +
 public/docs/spreadsheets.md                                                 | 306 ++++++++++++++++++++
 public/zensical.yml                                                         |   3 +
 src/commonMain/resources/spreadsheet.js                                     | 122 +++++++-
 src/jsMain/kotlin/afield/app/client/Main.kt                                 |  20 +-
 src/jvmMain/kotlin/afield/data/table/spreadsheet.kt                         | 537 ++++++++++++++++++++++++++++++------
 src/jvmMain/kotlin/afield/data/table/spreadsheetConstraints.kt              | 370 +++++++++++++++++++++++++
 src/jvmMain/kotlin/afield/examples/DemoTranscriptExecSmokeTest.kt           |   5 +
 src/jvmMain/kotlin/afield/math/optimization/ceres/CeresExpression.kt        |  65 +++++
 src/jvmTest/kotlin/afield/data/table/SpreadsheetConstraintsTest.kt          | 221 +++++++++++++++
 src/jvmTest/kotlin/afield/data/table/SpreadsheetTest.kt                     | 242 ++++++++++++++++
 41 files changed, 2542 insertions(+), 84 deletions(-)

Failing tests (first): ImpossibilityClaimContractTest.everyImpossibilityClaimInDevDocsCarriesItsEvidence, ImpossibilityClaimContractTest.plantingUnmarkedClaimsBesideTheRealCorpusIsDetected
## ImpossibilityClaimContractTest.everyImpossibilityClaimInDevDocsCarriesItsEvidence
java.lang.IllegalStateException: 1 impossibility claim(s) in dev_docs carry no evidence:

  dev_docs/SPREADSHEET_CONSTRAINTS.md:67 [VERDICT via \bcannot\b]
    - **The formula REPL cannot see `internal`.** Functions the formula source calls must be public
    -> no reproducer, citation, conjecture marker or explicit waiver in the claim unit (lines 67-68)
      Add ONE of these, inside the claim's own paragraph/bullet/table row:
        (reproducer: `SomeTest.kt#fun theRefusalIsObserved`)   a @Test or runnable example that demonstrates it
        `SomeFile.kt:120#theSymbol`                            the code that makes it so
        (conjecture, 2026-10-27)                             reasoned, not measured -- and honest about it
        (not a limitation: <why>)                              the detector misfired; say why
      See dev_docs/impossibility_claims.md.
java.lang.IllegalStateException: 1 impossibility claim(s) in dev_docs carry no evidence:

  dev_docs/SPREADSHEET_CONSTRAINTS.md:67 [VERDICT via \bcannot\b]
    - **The formula REPL cannot see `internal`.** Functions the formula source calls must be public
    -> no reproducer, citation, conjecture marker or explicit waiver in the claim unit (lines 67-68)
      Add ONE of these, inside the claim's own paragraph/bullet/table row:
        (reproducer: `SomeTest.kt#fun theRefusalIsObserved`)   a @Test or runnable example that demonstrates it
        `SomeFile.kt:120#theSymbol`                            the code that makes it so
        (conjecture, 2026-10-27)                             reasoned, not measured -- and honest about it
        (not a limitation: <why>)                              the detector misfired; say why
      See dev_docs/impossibility_claims.md.
	at afield.tools.docs.ImpossibilityClaimContractTest.everyImpossibilityClaimInDevDocsCarriesItsEvidence(ImpossibilityClaimContractTest.kt:175)
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

## ImpossibilityClaimContractTest.plantingUnmarkedClaimsBesideTheRealCorpusIsDetected
java.lang.AssertionError: the baseline half of the counterfactual must be clean, or the comparison proves nothing expected:<0> but was:<1>
java.lang.AssertionError: the baseline half of the

The end of the landing log:
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

[execute] PASS spreadsheet_tour (applied=2, output lines=0)
BoxCodeCompileGuard ok — 4 transcript(s) compiled

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
tree-write guard: baseline of 13553 tracked file(s), 0 already dirty -> /Users/marc/field4-worktrees/lander/build/test-tree-writes/baseline.json

> Task :citationGates
WARNING: Using incubator modules: jdk.incubator.vector
citationGates: running ServerSurfaceLedgerTest
citationGates: [1/14] ServerSurfaceLedgerTest SUCCESS in 3.1s
citationGates: running CloseNodeCitationDriftTest
citationGates: [2/14] CloseNodeCitationDriftTest SUCCESS in 2.4s
citationGates: running CitationContractTest
citationGates: [3/14] CitationContractTest SUCCESS in 3.0s
citationGates: running DerivedArithmeticContractTest
citationGates: [4/14] DerivedArithmeticContractTest SUCCESS in 4.3s
citationGates: running DocsFenceTreatmentTest
citationGates: [5/14] DocsFenceTreatmentTest SUCCESS in 0.3s
citationGates: running DocsGatesWiringTest
citationGates: [6/14] DocsGatesWiringTest SUCCESS in 0.1s
citationGates: running DocumentedApiEvaluationTest
citationGates: [7/14] DocumentedApiEvaluationTest SUCCESS in 8.3s
citationGates: running GateInputDeclarationTest
citationGates: [8/14] GateInputDeclarationTest SUCCESS in 0.1s
citationGates: running ImpossibilityClaimContractTest

ImpossibilityClaimContractTest > everyImpossibilityClaimInDevDocsCarriesItsEvidence FAILED
    java.lang.IllegalStateException at ImpossibilityClaimContractTest.kt:175

ImpossibilityClaimContractTest > plantingUnmarkedClaimsBesideTheRealCorpusIsDetected FAILED
    java.lang.AssertionError at ImpossibilityClaimContractTest.kt:516
citationGates: [9/14] ImpossibilityClaimContractTest FAILURE in 3.9s
citationGates: running LanguagesCategoryTest
citationGates: [10/14] LanguagesCategoryTest SUCCESS in 0.9s
citationGates: running PersonalBoundaryTest
citationGates: [11/14] PersonalBoundaryTest SUCCESS in 6.1s
citationGates: running ProseNumeralContractTest
citationGates: [12/14] ProseNumeralContractTest SUCCESS in 4.2s
citationGates: running SmallLanguageAnatomyTest
citationGates: [13/14] SmallLanguageAnatomyTest SUCCESS in 0.5s
citationGates: running TranscriptStackAutoCompileTest
citationGates: [14/14] TranscriptStackAutoCompileTest SUCCESS in 0.4s

114 tests completed, 2 failed

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

BUILD FAILED in 6m 49s
34 actionable tasks: 11 executed, 23 up-to-date

  A generated artifact is stale after this merge.
  It was not your commit that broke it and no hook ran on the clone that did:
  .git/hooks is per-clone and Git never transports it.

  Reproduce:  ./gradlew checkKDocAttachment checkAgentTranscriptIndex checkPythonTools checkBoxIdioms checkBoxCodeCompiles citationGates
  The failing task names the artifact and the command that regenerates it.
  Regenerate and commit that, or `main` is red until someone else finds it.

  Set FIELD_SKIP_MERGE_GUARD=1 to skip this check.

merge_regen: a guard failed on the batch

land: 2026-10-27T12:26:08+01:00 red; asking the fixer (up to 15 min) about codex/presentation-lecture-theme, claude/pen-sense, nightly/2026-10-26/4-sheet-constraints

What the fixer did on recent reds (for calibration):
- declined: test_sigterm_to_the_lander_stops_its_build hit a race where it read build.json after the lander created it but before the JSON was written; no branch in the round touched the lander, so retry, and fix it by writing build.json atomically or polling until it parses.
- fixed: The branch's kits.md example and KitPackagesTest fixture declared packages outside afield. (example, demo), and renaming them to afield.example and afield.demo makes PersonalBoundaryTest pass.
- declined: The 5 s subprocess timeout in test_verification_runtime most likely tripped because the host was busy during a 1h22m guard; the module passes in 1.3 s on this same tree and no branch touched the wrapper or the test.
- declined: checkAgentTranscriptLayouts fails on demo_document.yaml#demo_timeline (40x1400 with no layout exception, and a negative position), which main already had at 8c5e71bb4 from commit 93752ed5c; the remotejs-box-parity branch touches neither file, so main is red on its own and the demo-document author mu
- fixed: The branch put ROOT_ID and its KDoc between defaultDirs's KDoc and its declaration, and moving that KDoc back above defaultDirs makes checkKDocAttachment pass.
- fixed: The branch added box_graph_demo.yaml without its required entry in public/docs/agent_transcripts.md, and adding that entry under the execute-verified section makes checkAgentTranscriptIndex pass.
- fixed: The new learned_pen.md page opened four code fences without the diffstatic treatment, and switching those opening lines to diffstatic {data-lang=…} makes DocsFenceTreatmentTest pass.
- fixed: The branch added runaway_box_interrupt.yaml without its required entry in public/docs/agent_transcripts.md, and adding that entry with its Open link under the verified-by = compile section makes checkAgentTranscriptIndex pass.

This replay's checkout cannot build: the `gradle` command is not available, so verify by reading the check's source and rerunning any `tools/` Python check that covers it.
