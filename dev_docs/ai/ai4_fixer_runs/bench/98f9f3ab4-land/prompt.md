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

main before this round: 0999f8289084b0bd96abc34daac96f67501bddcb

Branches in the round, each merged as its own commit:

## claude/field4-timestral-vmo-8f092b (7de31792a, by Marc)
temporal_memory: recapture after takeover hysteresis; split memory plots, zoom covariate
dev_docs/TEMPORAL_MEMORY_EXPERIMENTS.md                           |   103 +
 public/docs/assets/temporal_memory/covariate.png                  |   Bin 0 -> 134731 bytes
 public/docs/assets/temporal_memory/episodes.png                   |   Bin 0 -> 230404 bytes
 public/docs/assets/temporal_memory/forecast.png                   |   Bin 0 -> 208083 bytes
 public/docs/assets/temporal_memory/improvise.png                  |   Bin 0 -> 259561 bytes
 public/docs/assets/temporal_memory/receding.png                   |   Bin 0 -> 195329 bytes
 public/docs/assets/temporal_memory/takeover.png                   |   Bin 0 -> 129972 bytes
 public/docs/temporal_memory.md                                    |   433 ++
 public/zensical.yml                                               |     3 +
 pyproject.toml                                                    |     8 +
 python/fieldtemporal/timestral_worker.py                          |   162 +
 python/fieldtemporal/timestral_worker.py.lock                     |   940 ++++
 src/jvmMain/kotlin/afield/math/oracle/Improviser.kt               |   319 ++
 src/jvmMain/kotlin/afield/math/oracle/Trajectory.kt               |   199 +
 src/jvmMain/kotlin/afield/math/oracle/VariableMarkovOracle.kt     |   241 +
 src/jvmMain/kotlin/afield/math/temporal/TemporalSession.kt        |   321 ++
 src/jvmMain/kotlin/afield/math/temporal/TimestralSession.kt       |   220 +
 src/jvmTest/kotlin/afield/math/oracle/VariableMarkovOracleTest.kt |   149 +
 src/jvmTest/kotlin/afield/math/temporal/RecedingHorizonTest.kt    |   120 +
 src/jvmTest/kotlin/afield/math/temporal/TemporalExperiments.kt    |   140 +
 src/jvmTest/resources/afield/math/oracle/vmo_reference.json       | 12570 ++++++++++++++++++++++++++++++++++++++++++++
 tools/vmo_reference_fixture.py                                    |    72 +
 uv.lock                                                           |    69 +-
 23 files changed, 16068 insertions(+), 1 deletion(-)
Its commits (the branch is all of them, not only the tip; its whole change is `git diff 0999f8289084...7de31792abec`):
7de31792a temporal_memory: recapture after takeover hysteresis; split memory plots, zoom covariate
13e2508bb Add sequence memory (VMO) and Timestral continuation, with a docs page

What :checkKDocAttachment printed when it failed:
KDocAttachmentGuard: scanned 2315 file(s), 29511 KDoc block(s)
KDocAttachmentGuard: 1 mis-attached KDoc block(s):
  src/jvmMain/kotlin/afield/math/temporal/TemporalSession.kt:145: DROPPED — line 146 puts an annotation and the declaration together (`@Volatile var revision = 0L; private set`); the extractor skips the whole line. Break the annotation onto its own line
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
/*graphics-pc-structure:aspectRatio*/ var Structure.aspectRatio by Structure.FromStructure<Any>()
/*graphics-pc-binding:aspectRatio*/ var Mesh.aspectRatio by Mesh.BoundFromStructure<Float>()
/*graphics-pc-binding-group:aspectRatio*/ var MeshGroup.aspectRatio by MeshGroup.BoundFromStructure<Float>()
var Box.outputPositions by Inheritance<FrameBufferGroup>() /*from:pp_compute*/ /**/
val Once.outputPositions by Onceing<FrameBufferGroup>() /*from:pp_compute*/ /**/
val Why.outputPositions by Whying() /*from:pp_compute*/ /**/
var Box.bandEnergy by Inheritance<DoubleArray>() /*from:pp_audio*/ /**/
val Once.bandEnergy by Onceing<DoubleArray>() /*from:pp_audio*/ /**/
val Why.bandEnergy by Whying() /*from:pp_audio*/ /**/
var Box.patternSynth by Inheritance<PatSynthLaunch>() /*from:pp_audio*/ /**/
val Once.patternSynth by Onceing<PatSynthLaunch>() /*from:pp_audio*/ /**/
val Why.patternSynth by Whying() /*from:pp_audio*/ /**/
var Box.window by Inheritance<Window>() /*from:pp_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:pp_renderer*/ /**/
val Why.window by Whying() /*from:pp_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:pp_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:pp_renderer*/ /**/
val Why.renderer by Whying() /*from:pp_renderer*/ /**/, executing
[90m[3m[info,kotlin,perf][0m 		 compile cache miss reason=not compiled before filePrefix=pp_draw property=preamble
[90m[3m[info,kotlin,perf][0m 		 compile request engine=KotlinInterface2 filePrefix=pp_draw source=pp_draw__preamble_$1 kind=imports-or-preamble length=3137 preview=import afield.graphics.vulkan.util.Window | import afield.graphics.vulkan.Renderer | import afield.audio.synth.PatSynthL...
[90m[3m[info,kotlin,perf][0m 		 compile code import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
import afield.audio.synth.PatSynthLaunch
import afield.graphics.vulkan.buffers.*
import afield.document.context.*
import afield.graphics.render.*
import afield.graphics.scene.*
import afield.graphics.shader.*
import arrow.core.left
import arrow.core.right
import afield.graphics.vulkan.buffers.FrameBufferGroup
import java.nio.*
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
/*start*/
/*graphics-structure-type:Positions:Positions*/
class Positions(
    members: List<Introspected.Member> = PositionsLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var pos by Structure.FromStructure<afield.math.linalg.Vector4f>()
}
fun PositionsByteSize(): Long = 16L
fun PositionsLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("pos", 0, PrimitiveType.vec4.left(), 16, false),
    )
fun HasBuffer.asPositions(): Positions =
    asStructure(Positions(), PositionsLayoutMembers())
fun BufferParameters.asPositions(): Positions =
    buffer.asPositions()
/*end*/
/*graphics-pc-structure:inData*/ var Structure.inData by Structure.FromStructure<Any>()
/*graphics-pc-binding:inData*/ var Mesh.inData by Mesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*graphics-pc-binding-group:inData*/ var MeshGroup.inData by MeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*graphics-pc-structure:aspectRatio*/ var Structure.aspectRatio by Structure.FromStructure<Any>()
/*graphics-pc-binding:aspectRatio*/ var Mesh.aspectRatio by Mesh.BoundFromStructure<Float>()
/*graphics-pc-binding-group:aspectRatio*/ var MeshGroup.aspectRatio by MeshGroup.BoundFromStructure<Float>()
var Box.outputPositions by Inheritance<FrameBufferGroup>() /*from:pp_compute*/ /**/
val Once.outputPositions by Onceing<FrameBufferGroup>() /*from:pp_compute*/ /**/
val Why.outputPositions by Whying() /*from:pp_compute*/ /**/
var Box.bandEnergy by Inheritance<DoubleArray>() /*from:pp_audio*/ /**/
val Once.bandEnergy by Onceing<DoubleArray>() /*from:pp_audio*/ /**/
val Why.bandEnergy by Whying() /*from:pp_audio*/ /**/
var Box.patternSynth by Inheritance<PatSynthLaunch>() /*from:pp_audio*/ /**/
val Once.patternSynth by Onceing<PatSynthLaunch>() /*from:pp_audio*/ /**/
val Why.patternSynth by Whying() /*from:pp_audio*/ /**/
var Box.window by Inheritance<Window>() /*from:pp_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:pp_renderer*/ /**/
val Why.window by Whying() /*from:pp_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:pp_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:pp_renderer*/ /**/
val Why.renderer by Whying() /*from:pp_renderer*/ /**/
[90m[3m[info,kotlin,compile][0m compile=578ms
[90m[3m[info,kotlin,evaluate][0m evaluate=92.4ms
 finished 
[90m[3m[info,attic][0m checkAtticAndExecute for 'draw' (pp_draw): empty=true, same=false, currentLen=0, wasLen=-1
[90m[3m[info,attic][0m attic is empty for 'draw' — skipping
[90m[3m[info,kotlin,perf][0m 		 compile request engine=KotlinInterface2 filePrefix=pp_draw source=pp_draw__code_$2 kind=user-code length=638 preview=// Draws two triangles per particle at the positions the compute box wrote. | var module = _moduleHere { | primitiveType...
[90m[3m[info,kotlin,perf][0m 		 compile code // Draws two triangles per particle at the positions the compute box wrote.
var module = _moduleHere {
    primitiveType = triangles
}

var quads = Mesh()
module += "quads" to quads
renderer += "pattern-particles-draw" to module

val N = 24000
quads.inData = { outputPositions }
quads.aspectRatio = { window.width / window.height.toFloat() }
quads.set(N * 2 * 3, 0) {}

// Printed from this box rather than from the compute box, because a docs capture waits on
// the output of the box it is capturing.
_printlnHere(16+1, 
    "$N particles drawn in four rings; bands now " + bandEnergy.joinToString(", ") { "%.3f".format(it) },
)

quads

[90m[3m[info,kotlin,compile][0m compile=240ms
[compile]   pp_draw: PASS
[compile] PASS pattern_particles
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
tree-write guard: baseline of 12446 tracked file(s), 0 already dirty -> /Users/marc/field4-worktrees/lander/build/test-tree-writes/baseline.json

> Task :citationGates
WARNING: Using incubator modules: jdk.incubator.vector
citationGates: running ServerSurfaceLedgerTest
citationGates: [1/14] ServerSurfaceLedgerTest SUCCESS in 4.0s
citationGates: running CloseNodeCitationDriftTest
citationGates: [2/14] CloseNodeCitationDriftTest SUCCESS in 3.4s
citationGates: running CitationContractTest
citationGates: [3/14] CitationContractTest SUCCESS in 4.4s
citationGates: running DerivedArithmeticContractTest
citationGates: [4/14] DerivedArithmeticContractTest SUCCESS in 4.7s
citationGates: running DocsFenceTreatmentTest
citationGates: [5/14] DocsFenceTreatmentTest SUCCESS in 0.4s
citationGates: running DocsGatesWiringTest
citationGates: [6/14] DocsGatesWiringTest SUCCESS in 0.1s
citationGates: running DocumentedApiEvaluationTest
citationGates: [7/14] DocumentedApiEvaluationTest SUCCESS in 9.1s
citationGates: running GateInputDeclarationTest
citationGates: [8/14] GateInputDeclarationTest SUCCESS in 0.6s
citationGates: running ImpossibilityClaimContractTest
citationGates: [9/14] ImpossibilityClaimContractTest SUCCESS in 8.8s
citationGates: running LanguagesCategoryTest
citationGates: [10/14] LanguagesCategoryTest SUCCESS in 1.1s
citationGates: running PersonalBoundaryTest
citationGates: [11/14] PersonalBoundaryTest SUCCESS in 7.0s
citationGates: running ProseNumeralContractTest
citationGates: [12/14] ProseNumeralContractTest SUCCESS in 4.8s
citationGates: running SmallLanguageAnatomyTest
citationGates: [13/14] SmallLanguageAnatomyTest SUCCESS in 0.6s
citationGates: running TranscriptStackAutoCompileTest
citationGates: [14/14] TranscriptStackAutoCompileTest SUCCESS in 1.0s

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

BUILD FAILED in 10m 26s
36 actionable tasks: 13 executed, 23 up-to-date

  A generated artifact is stale after this merge.
  It was not your commit that broke it and no hook ran on the clone that did:
  .git/hooks is per-clone and Git never transports it.

  Reproduce:  ./gradlew checkKDocAttachment checkAgentTranscriptIndex checkAgentTranscriptLayouts checkAgentDemos checkPythonTools checkBoxIdioms checkBoxCodeCompiles citationGates
  The failing task names the artifact and the command that regenerates it.
  Regenerate and commit that, or `main` is red until someone else finds it.

  Set FIELD_SKIP_MERGE_GUARD=1 to skip this check.

merge_regen: a guard failed on the batch

land: 2026-10-19T18:21:56+02:00 red; asking the fixer (up to 15 min) about claude/field4-timestral-vmo-8f092b, claude/eagle-flow-topology, codex/repl-variable-rebinding

What the fixer did on recent reds (for calibration):
- declined: The log shows only Gradle's generic non-zero exit for regenerateAgentTranscriptStartingPoints; the actual error (likely from the new polyvector_explorer transcript) was not captured, and a ~13-minute rerun to find it leaves no time to make and check a fix.
- declined: regenerateAgentTranscriptStartingPoints exited 1 without printing the actual error (most likely from the new polyvector_explorer transcript), so there is nothing specific to fix without a long rerun.
- fixed: The new polyvector page teaches byCreatingParallelCurves, byCreatingNormalLines and byWaving, which nothing in CI ran, so I added a jvmTest in PolyvectorTest calling all three and DocumentedApiEvaluationTest now passes.
- fixed: The new relaunchedVideoTargetReady KDoc was inserted between videoCapturePreparation's KDoc and its function, stacking two blocks; moving the original block back above videoCapturePreparation makes checkKDocAttachment pass.
- fixed: The branch put its new transcript's index entry in a section marked compile-only, though CI runs that transcript, and used listOf where the idiom check wants a list literal; I moved the entry, swapped in the literal, and both checks now pass.
- fixed: CREATURE_EVOLUTION.md cited a path under src/personal/; I reworded it to drop the private path, and PersonalBoundaryTest now passes.
- declined: checkPythonTools failed with only a generic uv exit 1 in the log, and the filtered re-run of the suite showed only deliberate capture-failure messages, so no failing test or branch slip was found to fix.
- declined: The reds come from at least two branches: canvas-flight-filming's two unlisted /ws commands (which also need a guard decision) and eagle-flow-topology's never-run videoFrames API, plus two task failures the log shows no error for, so no single-branch fix is possible.

This replay's checkout cannot build: the `gradle` command is not available, so verify by reading the check's source and rerunning any `tools/` Python check that covers it.
