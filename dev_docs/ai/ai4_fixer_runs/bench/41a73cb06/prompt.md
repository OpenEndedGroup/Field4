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

main before this round: e033ac0d5a2de9fb0edb0a968ef4b5a20b495502

Branches in the round, each merged as its own commit:

## nightly/2026-10-18/3-external-edit-guide (ce6a46b52, by Marc)
docs: Editing a document from outside Field
 public/docs/editing_from_outside.md                                | 116 +++++++++++++++++++++++++++++++++++++++++++++
 public/zensical.yml                                                |   3 ++
 src/jvmMain/kotlin/afield/document/persistence/FileWatchManager.kt |  37 +++++++++++++--
 src/jvmMain/kotlin/afield/examples/SidecarReeditSmokeTest.kt       |  89 ++++++++++++++++++++++++++++++++++
 4 files changed, 242 insertions(+), 3 deletions(-)

Failing tests (first): DocsFenceTreatmentTest.everyPublishedFenceCarriesTheDiffstaticTreatment
## DocsFenceTreatmentTest.everyPublishedFenceCarriesTheDiffstaticTreatment
java.lang.AssertionError: 1 code fence(s) render as raw Markdown instead of diffstatic.
Replace the opening fence; the content does not change:
  public/docs/editing_from_outside.md:16   ``` (no language)  ->  ```diffstatic {data-lang=text}
If a fence must stay raw — it documents fence syntax, say — add its page to
DocsFenceTreatmentTest.allowlist with the reason.



The end of the landing log:
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
import afield.ui.layout.*
import afield.ui.notation.*
import afield.ui.output.*
import afield.ui.output.templates.*
import afield.ui.slides.*
import afield.ui.visualization.*
import kotlin.math.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

[compile] clojure_box_parity (4 box(es)) …
[90m[3m[debug,kotlin][0m >|> rewriting AST analysis - '58d493cb-85e3-4730-b9fe-6fa7ca752b34'
[90m[3m[debug,kotlin][0m   child (named): REFERENCE_EXPRESSION listOf class org.jetbrains.kotlin.psi.KtNameReferenceExpression 184 -> 190 null
[90m[3m[info,di][0m   runAll precache time is 5265 ms
[90m[3m[info,di][0m   >|> runall at `macroRegistration` - '0565b6f7-b09a-4fd1-942c-52341b556467'
[90m[3m[info,di][0m   <|< - '0565b6f7-b09a-4fd1-942c-52341b556467'
[90m[3m[debug,kotlin][0m   child (named): REFERENCE_EXPRESSION println class org.jetbrains.kotlin.psi.KtNameReferenceExpression 217 -> 224 null
[90m[3m[debug,kotlin][0m   applying edits
[90m[3m[debug,kotlin][0m   final string is:
// The language root stays Kotlin; its `mainLanguage` makes its children Clojure.
// Its `speed` property and this variable are read by the box "values from the parent".
val palette = listOf("tomato", "teal", "gold")
_printlnHere(__line__+1, "the root stays Kotlin")

[90m[3m[debug,kotlin][0m <|< - '58d493cb-85e3-4730-b9fe-6fa7ca752b34'
[90m[3m[info,macros][0m macro-expanded source for box 'Clojure root -- children of this box run as Clojure' (sent to the Kotlin compiler):
┌────────────────────────────────────────────────────────────
│ // The language root stays Kotlin; its `mainLanguage` makes its children Clojure.
│ // Its `speed` property and this variable are read by the box "values from the parent".
│ val palette = listOf("tomato", "teal", "gold")
│ _printlnHere(__line__+1, "the root stays Kotlin")
│ 
└────────────────────────────────────────────────────────────
[90m[3m[info,attic][0m checkAtticAndExecute for 'Clojure root -- children of this box run as Clojure' (cparity_root): empty=true, same=false, currentLen=0, wasLen=-1
[90m[3m[info,attic][0m attic is empty for 'Clojure root -- children of this box run as Clojure' — skipping
[90m[3m[info,kotlin,compile][0m         compile=11.1s
[90m[3m[info,kotlin,evaluate][0m         evaluate=158ms
[90m[3m[info,ki,startup][0m         reset preamble cache #4 box='values from the parent' (cparity_context)
[90m[3m[info,ki,startup][0m         complete #4 box='values from the parent' (cparity_context) box is no longer offline
[90m[3m[info,ki,startup][0m       <|< - '582296e0-14ab-4efa-b257-62351bcbdef6'
[90m[3m[info,kotlin,compile][0m       compile=11.5s
[90m[3m[info,kotlin,evaluate][0m       evaluate=51.5ms
[90m[3m[info,ki,startup][0m       reset preamble cache #3 box='a slider and a checkbox, with Clojure handlers' (cparity_controls)
[90m[3m[info,ki,startup][0m       complete #3 box='a slider and a checkbox, with Clojure handlers' (cparity_controls) box is no longer offline
[90m[3m[info,ki,startup][0m     <|< - '98061b02-ac6d-465e-a101-4dd711a3d00d'
[90m[3m[info,kotlin,compile][0m     compile=11.8s
[90m[3m[info,kotlin,evaluate][0m     evaluate=73.2ms
[90m[3m[info,ki,startup][0m     reset preamble cache #2 box='an error is marked where it happened' (cparity_error)
[90m[3m[info,ki,startup][0m     complete #2 box='an error is marked where it happened' (cparity_error) box is no longer offline
[90m[3m[info,ki,startup][0m   <|< - 'a3d660ea-815c-4a8b-81bc-826f6aa219be'
[90m[3m[info,kotlin,compile][0m   compile=12.1s
[90m[3m[info,kotlin,perf][0m 		 compile request engine=KotlinInterface2 filePrefix=cparity_root source=cparity_root__code_$1 kind=user-code length=260 preview=// The language root stays Kotlin; its `mainLanguage` makes its children Clojure. | // Its `speed` property and this var...
[90m[3m[info,kotlin,perf][0m 		 compile code // The language root stays Kotlin; its `mainLanguage` makes its children Clojure.
// Its `speed` property and this variable are read by the box "values from the parent".
val palette = listOf("tomato", "teal", "gold")
_printlnHere(3+1, "the root stays Kotlin")

[90m[3m[info,kotlin,evaluate][0m   evaluate=184ms
[90m[3m[info,ki,startup][0m   reset preamble cache #1 box='Clojure root -- children of this box run as Clojure' (cparity_root)
[90m[3m[info,ki,startup][0m   complete #1 box='Clojure root -- children of this box run as Clojure' (cparity_root) box is no longer offline
[90m[3m[info,ki,startup][0m <|< - '7b60f65d-0b98-4394-b7a0-b50727582dde'
[90m[3m[info,kotlin,compile][0m compile=2.51s
[compile]   cparity_root: PASS
[compile]   cparity_error: code_language=Clojure, not Kotlin — skipped
[compile]   cparity_controls: code_language=Clojure, not Kotlin — skipped
[compile]   cparity_context: code_language=Clojure, not Kotlin — skipped
[compile] PASS clojure_box_parity
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
tree-write guard: baseline of 11629 tracked file(s), 0 already dirty -> /Users/marc/field4-worktrees/lander/build/test-tree-writes/baseline.json

> Task :citationGates
WARNING: Using incubator modules: jdk.incubator.vector
citationGates: running ServerSurfaceLedgerTest
citationGates: [1/14] ServerSurfaceLedgerTest SUCCESS in 14.1s
citationGates: running CloseNodeCitationDriftTest
citationGates: [2/14] CloseNodeCitationDriftTest SUCCESS in 8.4s
citationGates: running CitationContractTest
citationGates: [3/14] CitationContractTest SUCCESS in 12.4s
citationGates: running DerivedArithmeticContractTest
citationGates: [4/14] DerivedArithmeticContractTest SUCCESS in 11.2s
citationGates: running DocsFenceTreatmentTest

DocsFenceTreatmentTest > everyPublishedFenceCarriesTheDiffstaticTreatment FAILED
    java.lang.AssertionError at DocsFenceTreatmentTest.kt:111
citationGates: [5/14] DocsFenceTreatmentTest FAILURE in 0.9s
citationGates: running DocsGatesWiringTest
citationGates: [6/14] DocsGatesWiringTest SUCCESS in 0.2s
citationGates: running DocumentedApiEvaluationTest
citationGates: [7/14] DocumentedApiEvaluationTest SUCCESS in 20.0s
citationGates: running GateInputDeclarationTest
citationGates: [8/14] GateInputDeclarationTest SUCCESS in 0.4s
citationGates: running ImpossibilityClaimContractTest
citationGates: [9/14] ImpossibilityClaimContractTest SUCCESS in 25.5s
citationGates: running LanguagesCategoryTest
citationGates: [10/14] LanguagesCategoryTest SUCCESS in 15.4s
citationGates: running PersonalBoundaryTest
citationGates: [11/14] PersonalBoundaryTest SUCCESS in 10.8s
citationGates: running ProseNumeralContractTest
citationGates: [12/14] ProseNumeralContractTest SUCCESS in 5.3s
citationGates: running SmallLanguageAnatomyTest
citationGates: [13/14] SmallLanguageAnatomyTest SUCCESS in 1.0s
citationGates: running TranscriptStackAutoCompileTest
citationGates: [14/14] TranscriptStackAutoCompileTest SUCCESS in 1.9s

113 tests completed, 1 failed

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

BUILD FAILED in 15m 18s
36 actionable tasks: 13 executed, 23 up-to-date

  A generated artifact is stale after this merge.
  It was not your commit that broke it and no hook ran on the clone that did:
  .git/hooks is per-clone and Git never transports it.

  Reproduce:  ./gradlew checkKDocAttachment checkAgentTranscriptIndex checkAgentTranscriptLayouts checkAgentDemos checkPythonTools checkBoxIdioms checkBoxCodeCompiles citationGates
  The failing task names the artifact and the command that regenerates it.
  Regenerate and commit that, or `main` is red until someone else finds it.

  Set FIELD_SKIP_MERGE_GUARD=1 to skip this check.

merge_regen: a guard failed on the batch

land: 2026-10-18T13:31:28+02:00 red; asking the fixer (up to 15 min) about nightly/2026-10-18/1-clojure-box-parity, nightly/2026-10-18/2-reactive-props-edges, nightly/2026-10-18/3-external-edit-guide

What the fixer did on recent reds (for calibration):
- declined: The branch rewrote box code in frame_history_reprojection and pattern_condense build.yaml without regenerating five box sidecars, and resyncing them is well over the 60-line limit and needs the build scripts re-run.
- fixed: The branch's KDoc rewrite removed three citation anchors in DataFramePluginSpike.kt and left the Function conversation copy stale; I re-anchored the citations and re-ran the packager, and both tests pass, but the missing tools/check_kdoc_mirror.py red was already on main and is not fixed.
- declined: checkPythonTools fails only because tools/check_kdoc_mirror.py is missing, a red already on main that neither box-code-readability branch touches.
- fixed: The KDoc rewrite of DataFramePluginSpike.kt removed three citation anchors used by dev_docs/DATAFRAME_PLUGIN.md, so I pointed them at the matching lines of the new KDoc and CitationContractTest passes.
- declined: checkPythonTools fails on two stale conversation pages from different branches, stackauto from box-code-readability-33/34 and the Function page from docs/kdoc-blast's FunctionStudyTools.kt KDoc, so no single-branch fix turns it green.
- declined: no verdict line
- declined: The red has two causes from two branches: checkKDocAttachment flags a stacked KDoc in FabricatePassGraph.kt (codex/box-code-readability-41) and one in StdlibReferenceGenerator.kt (docs/kdoc-symbol-links-2), while docs/kdoc-symbol-links-2 also removed three citation anchors from DataFramePluginSpike.
- fixed: The branch put a new AnchorEntry KDoc directly below the TypeAnchors KDoc, so the guard saw two stacked blocks; moving the TypeAnchors block above its class makes checkKDocAttachment pass.

This replay's checkout cannot build: the `gradle` command is not available, so verify by reading the check's source and rerunning any `tools/` Python check that covers it.
