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

main before this round: f903419088fba11d6a37a4248fee508976201018

Branches in the round, each merged as its own commit:

## handoffs/code-20261015-2234 (f3248592c, by Marc)
Regenerate transcript bundles and completion-corpus fixtures for the edited transcripts
dev_docs/agents/transcripts/life3d_manifold_path_trace.yaml                 |  32 +++---------
 dev_docs/agents/transcripts/manifold_solids.yaml                            |   6 +--
 dev_docs/agents/transcripts/photo_to_solid_chain.yaml                       |  41 ++++-----------
 dev_docs/agents/transcripts/physics_manifold_path_trace.yaml                |  14 +++---
 dev_docs/agents/transcripts/rectsl_spacetime_path_trace.yaml                |   4 +-
 dev_docs/agents/transcripts/solidsl_gallery.yaml                            |   2 +-
 ...26-10-15-decide-how-a-saved-sourceblock-may-hydrate-inside-its-own-bo.md |  12 +++++
 ...26-10-15-make-patrenderstereo-frame-exact-with-patsynth-transcription.md |  17 +++++++
 public/docs/agent_transcripts.md                                            |   6 +--
 public/docs/assets/agent_transcripts/life3d_manifold_path_trace/document.md |  34 +++----------
 .../life3d_manifold_path_trace/life3d_manifold_trace_code_.kt               |  32 +++---------
 .../docs/assets/agent_transcripts/life3d_manifold_path_trace/metadata.edn   |   2 +-
 public/docs/assets/agent_transcripts/manifold_solids/document.md            |   8 ++-
 .../agent_transcripts/manifold_solids/manifold_levelset_slices_code_.kt     |   6 +--
 public/docs/assets/agent_transcripts/manifold_solids/metadata.edn           |   2 +-
 public/docs/assets/agent_transcripts/photo_to_solid_chain/document.md       |  42 ++++------------
 public/docs/assets/agent_transcripts/photo_to_solid_chain/metadata.edn      |   2 +-
 .../assets/agent_transcripts/photo_to_solid_chain/photo_levelset_code_.kt   |  38 +++-----------
 .../docs/assets/agent_transcripts/physics_manifold_path_trace/document.md   |  16 +++---
 .../docs/assets/agent_transcripts/physics_manifold_path_trace/metadata.edn  |   2 +-
 .../physics_manifold_path_trace/physics_manifold_trace_code_.kt             |  14 +++---
 .../docs/assets/agent_transcripts/rectsl_spacetime_path_trace/document.md   |   6 +--
 .../docs/assets/agent_transcripts/rectsl_spacetime_path_trace/metadata.edn  |   2 +-
 .../rectsl_spacetime_path_trace/rectsl_spacetime_trace_code_.kt             |   4 +-
 public/docs/assets/agent_transcripts/solidsl_gallery/document.md            |   4 +-
 public/docs/assets/agent_transcripts/solidsl_gallery/metadata.edn           |   2 +-
 public/docs/assets/agent_transcripts/solidsl_gallery/refusals_code_.kt      |   2 +-
 public/docs/assets/chain_life3d_render/clusters.png                         | Bin 3943918 -> 3949772 bytes
 public/docs/assets/chain_life3d_render/readout.png                          | Bin 940483 -> 793610 bytes
 public/docs/assets/chain_photo_to_solid/boolean.png                         | Bin 360988 -> 340366 bytes
 public/docs/assets/chain_photo_to_solid/depth.png                           | Bin 480048 -> 414469 bytes
 public/docs/assets/chain_photo_to_solid/levelset.png                        | Bin 143833 -> 117716 bytes
 public/docs/assets/chain_photo_to_solid/plaque.png                          | Bin 397812 -> 378759 bytes
 public/docs/assets/chain_physics_render/drape.png                           | Bin 8586451 -> 8590278 bytes
 public/docs/building_and_testing.md                                         |   9 ++--
 public/docs/chain_life3d_render.md                                          |  28 ++---------
 public/docs/chain_pattern_particles.md                                      |   5 +-
 public/docs/chain_photo_to_solid.md                                         |  18 +------
 public/docs/chain_physics_render.md                                         |  13 ++---
 public/docs/chain_polytope_render.md                                        |  13 +----
 public/docs/chain_rectsl_spacetime.md                                       |   4 +-
 public/docs/installation.md                                                 |   5 +-
 public/docs/keybindings.md                                                  |   6 ++-
 public/docs/manifold.md                                                     |  70 +++++++++-----------------
 public/docs/privacy.md                                                      |   2 +-
 public/docs/staging.md                                                      |   2 +-
 public/docs/tetgen.md                                                       |   4 +-
 public/docs_hooks.py                                                        |  38 +++++++++++++-
 src/commonMain/resources/code-use.html                                      |   8 +--
 src/commonMain/resources/welcome.html                                       |   2 +-
 src/jvmMain/kotlin/afield/ai/models/Ai4Installer.kt                         |   2 +-
 src/jvmMain/kotlin/afield/ai/tools/Ai4RuntimeToolProviders.kt               |   4 +-
 src/jvmMain/kotlin/afield/app/diagnostics/CodeUseTelemetryService.kt        |   4 +-
 src/jvmMain/kotlin/afield/audio/synth/PatSynth.kt                           |  19 +++++--
 src/jvmMain/kotlin/afield/document/workspace/dir.kt                         |  40 ++++++++++++---
 src/jvmMain/kotlin/afield/examples/chain/PhotoToSolidChain.kt               |  98 +-----------------------------------
 src/jvmMain/kotlin/afield/geometry/mesh/mesh_field.kt                       |  10 ++--
 src/jvmMain/kotlin/afield/geometry/mesh/mesh_topology.kt                    |  36 +++++++++----
 src/jvmMain/kotlin/afield/geometry/solid/manifold/ManifoldNative.kt         |  33 ++++--------
 src/jvmMain/kotlin/afield/geometry/solid/manifold/ManifoldSmokeTest.kt      |  38 ++++----------
 src/jvmMain/kotlin/afield/geometry/solid/manifold/Solid.kt                  |  37 ++++----------
 src/jvmMain/kotlin/afield/graphics/scene/fabricated/PathTraceScene.kt       |  23 +++++++--
 src/jvmMain/kotlin/afield/graphics/scene/fabricated/PathTraceTestMain.kt    |   2 +-
 src/jvmMain/kotlin/afield/graphics/text/typography_field.kt                 |   9 ++--
 src/jvmMain/kotlin/afield/graphics/three/Three.kt                           |   4 +-
 src/jvmMain/kotlin/afield/lang/solidsl/SolidExact.kt                        |   6 +--
 src/jvmMain/kotlin/afield/lang/solidsl/SolidField.kt                        |  12 ++---
 src/jvmMain/kotlin/afield/simulation/automata/alife_ca.kt                   |  17 ++++++-
 src/jvmMain/kotlin/afield/simulation/physics/SimdVerlet.kt                  |   3 ++
 src/jvmTest/kotlin/afield/app/settings/PersonalTemplatesTest.kt             |   7 ++-
 src/jvmTest/kotlin/afield/audio/synth/PatSynthOfflineTest.kt                |  25 +++++++++
 src/jvmTest/kotlin/afield/geometry/mesh/MeshCoreTest.kt                     |   6 ++-
 src/jvmTest/kotlin/afield/geometry/mesh/tetgen/TetgenPageFenceDryRunTest.kt |   8 +--
 src/jvmTest/kotlin/afield/geometry/mesh/tetgen/TetgenPageMeasurementTest.kt |   2 +-
 src/jvmTest/kotlin/afield/geometry/mesh/tetgen/TetgenPageProbeTest.kt       |   2 +-
 src/jvmTest/kotlin/afield/geometry/solid/manifold/ManifoldTest.kt           |  55 ++++++++------------
 src/jvmTest/kotlin/afield/graphics/scene/fabricated/PathTraceTriMeshTest.kt |  12 +++++
 src/jvmTest/kotlin/afield/lang/solidsl/SolidSLCrossValidationTest.kt        |   2 +-
 src/jvmTest/kotlin/afield/simulation/automata/Life3dSolidTest.kt            |  60 ++++++++++------------
 tools/completion-corpus/boxes/manifest.json                                 |  12 ++---
 tools/keybindings_defaults_table.py                                         |   7 ++-
 tools/test_verification_environment.py                                      |  43 ++++++++++++++++
 82 files changed, 536 insertions(+), 681 deletions(-)
Its commits (the branch is all of them, not only the tip; its whole change is `git diff f903419088fb...f3248592c675`):
f3248592c Regenerate transcript bundles and completion-corpus fixtures for the edited transcripts
f33d8c2d8 Recapture life3d, photo_to_solid and physics pages after the levelSet, asDensityField and +y-up changes
86d6cc104 Refile the sourceBlock self-box hydration handoff as a decision
e66f01641 Refile the patRenderStereo frame-exactness handoff with what the attempt found
8c09da213 PatSynthLaunch.advanceOffline: do nothing while an audio engine is pulling the launch
482889343 keybindings table: put the regenerate instruction in an Agents aside
d51b36112 physics_manifold_path_trace: simulate +y up so cloth positions feed the tracer unnegated
ff5439653 List a personal template shadowed by a bundled one under a suffixed name instead of hiding it
b1b47249a Docs runner: refuse, naming PID and workspace, instead of shutting down a Field it did not start
4e3835e23 Measure a split-normal TriMesh as its surface: weld attributed meshes before genus/isWatertight
20a7fb1a5 PathTraceScene.triangle: skip and count degenerate triangles instead of throwing
078e4d448 asDensityField: step the blur radius down instead of blurring a sparse volume to nothing
666aa046e Solid.levelSet: always march sequentially; drop the nondeterministic parallel path
827adc97a Say 'local code-use history' instead of 'code-use telemetry' in UI and status strings

What :checkKDocAttachment printed when it failed:
KDocAttachmentGuard: scanned 2197 file(s), 27731 KDoc block(s)
KDocAttachmentGuard: 1 mis-attached KDoc block(s):
  src/jvmMain/kotlin/afield/document/workspace/dir.kt:1324: DROPPED — line 1325 is not a declaration KDocExtractor.parseDeclaration can read (`documentNames: Map<String, String> = emptyMap(),`), so this block slides onto whatever parses next
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
│ val column = Profile.star(6, 0.85, 0.42).extruded(2.4, twistDegrees = twist).at(0.0, 0.0, -1.2)
│ val plate = cuboid(3.6, 3.6, 0.5).at(0.0, 0.0, -1.35)
│ val scene = smoothUnion(column, plate, k = blend) - cylinder(4.0, 0.28, segments = 64)
│ // ---------------------------------------------------------------------------------------
│ 
│ val emitted = scene.toGlsl()
│ 
│ _printlnHere(__line__+1, "$emitted")
│ _printlnHere(__line__+1, )
│ _printlnHere(__line__+1, "A `(Vec3) -> Float` runs on the JVM and nowhere else. An expression tree walks")
│ _printlnHere(__line__+1, "into whatever language you like, and this is the third place this one goes:")
│ _printlnHere(__line__+1, )
│ _printlnHere(__line__+1, emitted.glsl.lines().take(26).joinToString("\n"))
│ _printlnHere(__line__+1, "    ...  (${emitted.glsl.lines().size} lines in total)")
│ _printlnHere(__line__+1, )
│ 
│ // The emitted code is scalar single-assignment on purpose — no vec3 locals, no swizzles,
│ // no loops. Any driver re-vectorises it in the first pass, and what it buys is a grammar
│ // small enough that `SolidSLGlslTest` can parse the shader BACK and run it against the
│ // Kotlin field. 29 shapes, 58,000 sampled points, worst disagreement 6.3e-15. That is a
│ // measurement of the backend's fidelity, not a hope that two tree walks agree.
│ _printlnHere(__line__+1, "step scale ${emitted.stepScale} — ${if (emitted.notes.isEmpty()) "a true distance field, so sphere tracing is exact" else "the field over-estimates distance somewhere"}")
│ emitted.notes.forEach { _printlnHere(__line__+1, "  - $it") }
│ _printlnHere(__line__+1, )
│ 
│ // The simplifier, which only exists because the shape is a value.
│ _printlnHere(__line__+1, "---- what the simplifier can do ----")
│ val wasteful = differenceOf(
│     listOf(scene) + (0 until 40).map { cylinder(3.0, 0.12, segments = 32).at(-8.0 + it * 0.42, 0.0, 0.0) },
│ )
│ _printlnHere(__line__+1, "40 extra bores, most of them nowhere near the part:")
│ _printlnHere(__line__+1, "  ${wasteful.simplificationReport()}")
│ _printlnHere(__line__+1, "  Disjointness is decided from symbolic bounds — nothing sampled, nothing built.")
│ _printlnHere(__line__+1, )
│ 
│ // And the rule that is genuinely strange: a blend of radius zero IS a union, and a union
│ // has an exact CSG form where a smooth blend never will. So simplifying can move an
│ // expression off the level-set fallback and onto the exact path.
│ val noBlend = smoothUnion(column, plate, k = 0.0)
│ _printlnHere(__line__+1, "a blend with k = 0:")
│ _printlnHere(__line__+1, "  ${noBlend.simplificationReport()}")
│ _printlnHere(__line__+1, )
│ 
│ _printlnHere(__line__+1, "---- the whole raymarch fragment, ready to paste ----")
│ _printlnHere(__line__+1, scene.toRaymarchFragment())
└────────────────────────────────────────────────────────────
[90m[3m[info,attic][0m checkAtticAndExecute for 'Solid notation — the same source as a GLSL raymarcher' (glsl_backend): empty=true, same=false, currentLen=0, wasLen=-1
[90m[3m[info,attic][0m attic is empty for 'Solid notation — the same source as a GLSL raymarcher' — skipping
[90m[3m[info,kotlin,perf][0m 		 compile request engine=KotlinInterface2 filePrefix=glsl_backend source=glsl_backend__code_$1 kind=imports-or-preamble length=2948 preview=import afield.lang.solidsl.* | // ---- a shape that is worth ray-marching ----------------------------------------------...
[90m[3m[info,kotlin,perf][0m 		 compile code import afield.lang.solidsl.*

// ---- a shape that is worth ray-marching ----------------------------------------------
// A twisted star column standing in a bored plate, blended where they meet. Change the
// twist and re-run: the printed shader changes with it, because the shader is emitted
// from the expression rather than written beside it.
val twist = 140.0
val blend = 0.18

val column = Profile.star(6, 0.85, 0.42).extruded(2.4, twistDegrees = twist).at(0.0, 0.0, -1.2)
val plate = cuboid(3.6, 3.6, 0.5).at(0.0, 0.0, -1.35)
val scene = smoothUnion(column, plate, k = blend) - cylinder(4.0, 0.28, segments = 64)
// ---------------------------------------------------------------------------------------

val emitted = scene.toGlsl()

_printlnHere(16+1, "$emitted")
_printlnHere(17+1, )
_printlnHere(18+1, "A `(Vec3) -> Float` runs on the JVM and nowhere else. An expression tree walks")
_printlnHere(19+1, "into whatever language you like, and this is the third place this one goes:")
_printlnHere(20+1, )
_printlnHere(21+1, emitted.glsl.lines().take(26).joinToString("\n"))
_printlnHere(22+1, "    ...  (${emitted.glsl.lines().size} lines in total)")
_printlnHere(23+1, )

// The emitted code is scalar single-assignment on purpose — no vec3 locals, no swizzles,
// no loops. Any driver re-vectorises it in the first pass, and what it buys is a grammar
// small enough that `SolidSLGlslTest` can parse the shader BACK and run it against the
// Kotlin field. 29 shapes, 58,000 sampled points, worst disagreement 6.3e-15. That is a
// measurement of the backend's fidelity, not a hope that two tree walks agree.
_printlnHere(30+1, "step scale ${emitted.stepScale} — ${if (emitted.notes.isEmpty()) "a true distance field, so sphere tracing is exact" else "the field over-estimates distance somewhere"}")
emitted.notes.forEach { _printlnHere(31+1, "  - $it") }
_printlnHere(32+1, )

// The simplifier, which only exists because the shape is a value.
_printlnHere(35+1, "---- what the simplifier can do ----")
val wasteful = differenceOf(
    listOf(scene) + (0 until 40).map { cylinder(3.0, 0.12, segments = 32).at(-8.0 + it * 0.42, 0.0, 0.0) },
)
_printlnHere(39+1, "40 extra bores, most of them nowhere near the part:")
_printlnHere(40+1, "  ${wasteful.simplificationReport()}")
_printlnHere(41+1, "  Disjointness is decided from symbolic bounds — nothing sampled, nothing built.")
_printlnHere(42+1, )

// And the rule that is genuinely strange: a blend of radius zero IS a union, and a union
// has an exact CSG form where a smooth blend never will. So simplifying can move an
// expression off the level-set fallback and onto the exact path.
val noBlend = smoothUnion(column, plate, k = 0.0)
_printlnHere(48+1, "a blend with k = 0:")
_printlnHere(49+1, "  ${noBlend.simplificationReport()}")
_printlnHere(50+1, )

_printlnHere(52+1, "---- the whole raymarch fragment, ready to paste ----")
_printlnHere(53+1, scene.toRaymarchFragment())
[90m[3m[info,kotlin,compile][0m compile=133ms
[90m[3m[info,kotlin,evaluate][0m evaluate=15.2ms
[execute] PASS solidsl_gallery (applied=7, output lines=54)
BoxCodeCompileGuard ok — 6 transcript(s) compiled

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
tree-write guard: baseline of 10960 tracked file(s), 0 already dirty -> /Users/marc/field4-worktrees/lander/build/test-tree-writes/baseline.json

> Task :citationGates
WARNING: Using incubator modules: jdk.incubator.vector
citationGates: running ServerSurfaceLedgerTest
citationGates: [1/14] ServerSurfaceLedgerTest SUCCESS in 3.5s
citationGates: running CloseNodeCitationDriftTest
citationGates: [2/14] CloseNodeCitationDriftTest SUCCESS in 3.3s
citationGates: running CitationContractTest
citationGates: [3/14] CitationContractTest SUCCESS in 3.0s
citationGates: running DerivedArithmeticContractTest
citationGates: [4/14] DerivedArithmeticContractTest SUCCESS in 4.6s
citationGates: running DocsFenceTreatmentTest
citationGates: [5/14] DocsFenceTreatmentTest SUCCESS in 0.2s
citationGates: running DocsGatesWiringTest
citationGates: [6/14] DocsGatesWiringTest SUCCESS in 0.1s
citationGates: running DocumentedApiEvaluationTest
citationGates: [7/14] DocumentedApiEvaluationTest SUCCESS in 8.0s
citationGates: running GateInputDeclarationTest
citationGates: [8/14] GateInputDeclarationTest SUCCESS in 0.1s
citationGates: running ImpossibilityClaimContractTest
citationGates: [9/14] ImpossibilityClaimContractTest SUCCESS in 10.1s
citationGates: running LanguagesCategoryTest
citationGates: [10/14] LanguagesCategoryTest SUCCESS in 1.0s
citationGates: running PersonalBoundaryTest
citationGates: [11/14] PersonalBoundaryTest SUCCESS in 5.8s
citationGates: running ProseNumeralContractTest
citationGates: [12/14] ProseNumeralContractTest SUCCESS in 5.3s
citationGates: running SmallLanguageAnatomyTest
citationGates: [13/14] SmallLanguageAnatomyTest SUCCESS in 0.5s
citationGates: running TranscriptStackAutoCompileTest
citationGates: [14/14] TranscriptStackAutoCompileTest SUCCESS in 3.6s

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

BUILD FAILED in 7m 23s
34 actionable tasks: 11 executed, 23 up-to-date

  A generated artifact is stale after this merge.
  It was not your commit that broke it and no hook ran on the clone that did:
  .git/hooks is per-clone and Git never transports it.

  Reproduce:  ./gradlew checkKDocAttachment checkAgentTranscriptIndex checkPythonTools checkBoxIdioms checkBoxCodeCompiles citationGates
  The failing task names the artifact and the command that regenerates it.
  Regenerate and commit that, or `main` is red until someone else finds it.

  Set FIELD_SKIP_MERGE_GUARD=1 to skip this check.

merge_regen: a guard failed on the batch

land: 2026-10-16T00:14:28+02:00 red; asking the fixer (up to 15 min) about handoffs/code-20261015-2234

What the fixer did on recent reds (for calibration):
- fixed: The branch put @Synchronized on the same line as fun setParameter, added smokeSolidCraft without declaring it manual, and its transcript now runs setParameter, which made a COMPILE_ONLY row stale; all three are mechanical one-line fixes and the checks now pass.
- declined: The rename broke three Python checks: the -Dafield.rectsl.headingfigure property was renamed to afield.lang.rectsl.headingfigure in tools/check_test_tree_writes.py (which I may not edit) but not in RectSLHeadingFigureTest.kt, and the function and stackauto conversation pages need their packagers re-
- declined: The branch's rename touched function_conversation.md and stackauto_conversation.md, so checkBoxIdioms now checks their recorded-conversation fences, which use listOf; fixing that means updating the recorded conversation sources and re-running the packagers, not editing the pages by hand.
- declined: no verdict line
- declined: no verdict line
- fixed: A new handoff's title tripped the impossibility-claim gate with no evidence, so I added a citation to keyframer.kt's camera function, the code the handoff is about, in the file's header lines.
- declined: checkBoxIdioms flags three listOf calls in matrix_profile.md that should be collection literals, but the same code is in the transcript YAML and its packaged assets (document.md, metadata.edn, three .kt files), so the fix needs a re-package across more than six files rather than editing the page.
- declined: checkBoxIdioms flags four listOf calls in matrix_profile.md whose source is the matrix_profile transcript YAML and its packaged assets, so the fix needs a transcript edit and re-package across more than six files, not a hand edit of the page.

This replay's checkout cannot build: the `gradle` command is not available, so verify by reading the check's source and rerunning any `tools/` Python check that covers it.
