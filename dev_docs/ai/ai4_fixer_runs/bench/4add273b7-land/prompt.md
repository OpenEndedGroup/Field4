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

main before this round: 2be5ed1b1b8549297e1b0a58e14d2e776e2f636b

Branches in the round, each merged as its own commit:

## claude/contour-trees (be8c911ce, by Marc)
Document merge and contour trees; contour_trees_explorer starting point
dev_docs/agents/transcripts/contour_trees_explorer.yaml               |  56 +++++++
 public/docs/agent_transcripts.md                                      |  10 ++
 public/docs/assets/contour_trees/landscape.png                        | Bin 0 -> 353290 bytes
 public/docs/assets/contour_trees/simplify.png                         | Bin 0 -> 430421 bytes
 public/docs/contour_trees.md                                          |  94 ++++++++++++
 public/zensical.yml                                                   |   1 +
 src/jvmMain/kotlin/afield/examples/DemoTranscriptExecSmokeTest.kt     |   2 +
 src/jvmMain/kotlin/afield/geometry/topology/trees/ContourTrees.kt     | 335 ++++++++++++++++++++++++++++++++++++++++++
 src/jvmTest/kotlin/afield/geometry/topology/trees/ContourTreesTest.kt |  66 +++++++++
 9 files changed, 564 insertions(+)
Its commits (the branch is all of them, not only the tip; its whole change is `git diff 2be5ed1b1b85...be8c911ce54f`):
be8c911ce Document merge and contour trees; contour_trees_explorer starting point
480698381 Merge trees and contour trees of 2D scalar fields

What :checkAgentTranscriptIndex printed when it failed:
KDocAttachmentGuard: scanned 2296 file(s), 29318 KDoc block(s)
KDocAttachmentGuard ok — every KDoc block attaches to a declaration
> Task :checkAgentTranscriptIndex FAILED

What :checkBoxIdioms printed when it failed:
  tools.test_field_agent                             3 passed /   3 run
  tools.test_field_backdoor                        121 passed / 121 run
  tools.test_field_inspection                        5 passed /   5 run
  tools.test_field_kit                              17 passed /  17 run
  tools.test_field_live_session_reaping              4 passed /   4 run
  tools.test_field_python_env                        4 passed /   4 run
  tools.test_field_sh_launcher                      38 passed /  39 run, 1 skipped
  tools.test_fieldcv                                15 passed /  15 run
  tools.test_guard_corpora                           5 passed /   5 run
  tools.test_inventory_box_code                     13 passed /  13 run
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
  TOTAL                                            1293 passed / 1297 run, 4 skipped
> Task :checkBoxIdioms FAILED

The end of the landing log:
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
import afield.ui.layout.*
import afield.ui.notation.*
import afield.ui.output.*
import afield.ui.output.templates.*
import afield.ui.slides.*
import afield.ui.visualization.*
import kotlin.math.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

[execute] PASS contour_trees_explorer (applied=2, output lines=0)
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
tree-write guard: baseline of 12023 tracked file(s), 0 already dirty -> /Users/marc/field4-worktrees/lander/build/test-tree-writes/baseline.json

> Task :citationGates
WARNING: Using incubator modules: jdk.incubator.vector
citationGates: running ServerSurfaceLedgerTest
citationGates: [1/14] ServerSurfaceLedgerTest SUCCESS in 3.5s
citationGates: running CloseNodeCitationDriftTest
citationGates: [2/14] CloseNodeCitationDriftTest SUCCESS in 1.9s
citationGates: running CitationContractTest
citationGates: [3/14] CitationContractTest SUCCESS in 3.6s
citationGates: running DerivedArithmeticContractTest
citationGates: [4/14] DerivedArithmeticContractTest SUCCESS in 4.3s
citationGates: running DocsFenceTreatmentTest
citationGates: [5/14] DocsFenceTreatmentTest SUCCESS in 0.3s
citationGates: running DocsGatesWiringTest
citationGates: [6/14] DocsGatesWiringTest SUCCESS in 0.1s
citationGates: running DocumentedApiEvaluationTest
citationGates: [7/14] DocumentedApiEvaluationTest SUCCESS in 8.4s
citationGates: running GateInputDeclarationTest
citationGates: [8/14] GateInputDeclarationTest SUCCESS in 0.1s
citationGates: running ImpossibilityClaimContractTest
citationGates: [9/14] ImpossibilityClaimContractTest SUCCESS in 7.9s
citationGates: running LanguagesCategoryTest
citationGates: [10/14] LanguagesCategoryTest SUCCESS in 1.4s
citationGates: running PersonalBoundaryTest
citationGates: [11/14] PersonalBoundaryTest SUCCESS in 6.5s
citationGates: running ProseNumeralContractTest
citationGates: [12/14] ProseNumeralContractTest SUCCESS in 4.3s
citationGates: running SmallLanguageAnatomyTest
citationGates: [13/14] SmallLanguageAnatomyTest SUCCESS in 0.6s
citationGates: running TranscriptStackAutoCompileTest
citationGates: [14/14] TranscriptStackAutoCompileTest SUCCESS in 0.4s

> Task :checkTestTreeWrites
warning: `VIRTUAL_ENV=/Users/marc/field4/.venv` does not match the project environment path `.venv` and will be ignored; use `--active` to target the active environment instead
tree-write guard: citationGates left the working tree as it found it.

FAILURE: Build completed with 2 failures.

1: Task failed with an exception.
-----------
* What went wrong:
Execution failed for task ':checkAgentTranscriptIndex' (registered in build file 'build.gradle.kts').
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
Execution failed for task ':checkBoxIdioms' (registered in build file 'build.gradle.kts').
> Process 'command 'uv'' finished with non-zero exit value 1

* Try:
> Run with --stacktrace option to get the stack trace.
> Run with --info or --debug option to get more log output.
> Run with --scan to get full insights from a Build Scan (powered by Develocity).
> Get more help at https://help.gradle.org.
==============================================================================

BUILD FAILED in 11m 58s
34 actionable tasks: 11 executed, 23 up-to-date

  A generated artifact is stale after this merge.
  It was not your commit that broke it and no hook ran on the clone that did:
  .git/hooks is per-clone and Git never transports it.

  Reproduce:  ./gradlew checkKDocAttachment checkAgentTranscriptIndex checkPythonTools checkBoxIdioms checkBoxCodeCompiles citationGates
  The failing task names the artifact and the command that regenerates it.
  Regenerate and commit that, or `main` is red until someone else finds it.

  Set FIELD_SKIP_MERGE_GUARD=1 to skip this check.

merge_regen: a guard failed on the batch

land: 2026-10-19T00:32:14+02:00 red; asking the fixer (up to 15 min) about claude/contour-trees

What the fixer did on recent reds (for calibration):
- fixed: The branch teaches the new Img.drawFLines on a published page but nothing in CI ran it, so I added a small jvmTest in its MorseSmaleTest that runs it, and DocumentedApiEvaluationTest now passes.
- declined: no verdict line
- fixed: The new principal_curves page teaches pointsFLine and widthFLines, which nothing in CI ran, so I added a jvmTest calling both and DocumentedApiEvaluationTest now passes.
- fixed: The branch added the GLSL helper cameraPosition to the open_dlss_nr cubes shader on a published page, and it needed a COMPILE_ONLY_REMAINDER row like its sibling objectPoint; with that row DocumentedApiEvaluationTest passes.
- declined: The log shows only Gradle's generic non-zero exit for regenerateAgentTranscriptStartingPoints; the actual error (likely from the new polyvector_explorer transcript) was not captured, and a ~13-minute rerun to find it leaves no time to make and check a fix.
- declined: regenerateAgentTranscriptStartingPoints exited 1 without printing the actual error (most likely from the new polyvector_explorer transcript), so there is nothing specific to fix without a long rerun.
- fixed: The new polyvector page teaches byCreatingParallelCurves, byCreatingNormalLines and byWaving, which nothing in CI ran, so I added a jvmTest in PolyvectorTest calling all three and DocumentedApiEvaluationTest now passes.
- fixed: The new relaunchedVideoTargetReady KDoc was inserted between videoCapturePreparation's KDoc and its function, stacking two blocks; moving the original block back above videoCapturePreparation makes checkKDocAttachment pass.

This replay's checkout cannot build: the `gradle` command is not available, so verify by reading the check's source and rerunning any `tools/` Python check that covers it.
