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

main before this round: 94d81abe357fa7d110f4321250b42c94dac871be

Branches in the round, each merged as its own commit:

## nightly/2026-10-15/1-python-box-parity (fac4f8498, by Marc)
python_box_parity: transcript index entry; layout guard reads only box ids
dev_docs/GUARD_CORPORA.md                                                   |  10 +--
 dev_docs/PYTHON_BOX_PARITY.md                                               |  37 +++++++++
 dev_docs/agents/demos/python_box_parity/README.md                           |  13 ++++
 dev_docs/agents/demos/python_box_parity/metadata.edn                        | Bin 0 -> 5658 bytes
 dev_docs/agents/demos/python_box_parity/parity_controls_code_.py            |  19 +++++
 dev_docs/agents/demos/python_box_parity/parity_controls_customization_.js   |  18 +++++
 dev_docs/agents/demos/python_box_parity/parity_error_code_.py               |   8 ++
 dev_docs/agents/demos/python_box_parity/parity_error_customization_.js      |  18 +++++
 dev_docs/agents/demos/python_box_parity/parity_root_code_.kt                |   2 +
 dev_docs/agents/transcripts/README.md                                       |   3 +
 dev_docs/agents/transcripts/python_box_parity.yaml                          |  71 ++++++++++++++++++
 dev_docs/kdoc_worklist.md                                                   |   7 +-
 public/docs/agent_transcripts.md                                            |  13 ++++
 public/docs/assets/agent_transcripts/python_box_parity/AGENTS.md            | 129 ++++++++++++++++++++++++++++++++
 public/docs/assets/agent_transcripts/python_box_parity/document.md          |  89 ++++++++++++++++++++++
 public/docs/assets/agent_transcripts/python_box_parity/metadata.edn         |   1 +
 .../assets/agent_transcripts/python_box_parity/parity_controls_code_.kt     |  19 +++++
 .../docs/assets/agent_transcripts/python_box_parity/parity_error_code_.kt   |   8 ++
 public/docs/assets/agent_transcripts/python_box_parity/parity_root_code_.kt |   2 +
 public/docs/assets/python/controls.png                                      | Bin 0 -> 178389 bytes
 public/docs/assets/python/error_line.png                                    | Bin 0 -> 78115 bytes
 public/docs/python.md                                                       |  57 ++++++++++++++
 public/docs/reference/examples.md                                           |   2 +-
 public/docs/reference/index.md                                              |   9 ++-
 public/docs/reference/languages.md                                          |  40 +++++++---
 src/jvmMain/kotlin/afield/examples/DemoTranscriptExecSmokeTest.kt           |   1 +
 src/jvmMain/kotlin/afield/lang/python/PythonErrors.kt                       |  52 +++++++++++++
 src/jvmMain/kotlin/afield/lang/python/PythonInterface.kt                    | 144 +++++++++++++++++++++++++++++++++++-
 src/jvmMain/kotlin/afield/tools/docs/AgentTranscriptLayoutGuard.kt          |   5 +-
 src/jvmTest/kotlin/afield/lang/python/PythonErrorsTest.kt                   |  89 ++++++++++++++++++++++
 src/jvmTest/kotlin/afield/tools/docs/AgentDemoRegistrationTest.kt           |   1 +
 tools/completion-corpus/boxes/manifest.json                                 | 113 ++++++++++++++--------------
 32 files changed, 894 insertions(+), 86 deletions(-)
Its commits (the branch is all of them, not only the tip; its whole change is `git diff 94d81abe357f...fac4f8498793`):
fac4f8498 python_box_parity: transcript index entry; layout guard reads only box ids
5cab7a890 Merge branch 'main' into nightly/2026-10-15/1-python-box-parity
81357d18a Merge main into python-box-parity: PythonErrors moved to afield.lang.python
abaa6b1d3 python.md: real captures for the error-line and controls fences
a81f2af91 Python box parity: docs, demo transcript and document, audit page
3e67d9cc3 Python boxes: controls as a controlGroup, syntax-error lines, Kotlin-form display
15946b2e3 Python boxes: mark errors on the line that raised; slider/checkbox with Python callbacks

What :checkPythonTools printed when it failed:
  tools.test_field_agent                             3 passed /   3 run
  tools.test_field_backdoor                        117 passed / 117 run
  tools.test_field_inspection                        5 passed /   5 run
  tools.test_field_kit                              17 passed /  17 run
  tools.test_field_live_session_reaping              4 passed /   4 run
  tools.test_field_python_env                        4 passed /   4 run
  tools.test_field_sh_launcher                      38 passed /  39 run, 1 skipped
  tools.test_fieldcv                                15 passed /  15 run
  tools.test_guard_corpora                           4 passed /   5 run, 1 failed, 0 errored
  tools.test_inventory_box_code                     12 passed /  12 run
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
  TOTAL                                            1282 passed / 1288 run, 2 not passing, 4 skipped
> Task :checkPythonTools FAILED

The end of the landing log:
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

[compile] python_box_parity (3 box(es)) …
[90m[3m[debug,kotlin][0m >|> rewriting AST analysis - '91896fc0-184d-4bfb-93c9-fbbfe0826d7d'
[90m[3m[debug,kotlin][0m   child (named): REFERENCE_EXPRESSION println class org.jetbrains.kotlin.psi.KtNameReferenceExpression 81 -> 88 null
[90m[3m[debug,kotlin][0m   applying edits
[90m[3m[debug,kotlin][0m   final string is:
// The language root stays Kotlin; its `mainLanguage` makes its children Python.
_printlnHere(__line__+1, "the root stays Kotlin")

[90m[3m[debug,kotlin][0m <|< - '91896fc0-184d-4bfb-93c9-fbbfe0826d7d'
[90m[3m[info,macros][0m macro-expanded source for box 'Python root -- children of this box run as Python' (sent to the Kotlin compiler):
┌────────────────────────────────────────────────────────────
│ // The language root stays Kotlin; its `mainLanguage` makes its children Python.
│ _printlnHere(__line__+1, "the root stays Kotlin")
│ 
└────────────────────────────────────────────────────────────
[90m[3m[info,attic][0m checkAtticAndExecute for 'Python root -- children of this box run as Python' (parity_root): empty=true, same=false, currentLen=0, wasLen=-1
[90m[3m[info,attic][0m attic is empty for 'Python root -- children of this box run as Python' — skipping
[90m[3m[info,kotlin,compile][0m       compile=168ms
[90m[3m[info,kotlin,perf][0m 		 compile request engine=KotlinInterface2 filePrefix=parity_root source=parity_root__code_$1 kind=user-code length=124 preview=// The language root stays Kotlin; its `mainLanguage` makes its children Python. | _printlnHere(1+1, "the root stays Kot...
[90m[3m[info,kotlin,perf][0m 		 compile code // The language root stays Kotlin; its `mainLanguage` makes its children Python.
_printlnHere(1+1, "the root stays Kotlin")

[90m[3m[info,kotlin,evaluate][0m       evaluate=47.2ms
[90m[3m[info,ki,startup][0m       reset preamble cache #14 box='Python root -- children of this box run as Python' (parity_root)
[90m[3m[info,ki,startup][0m       complete #14 box='Python root -- children of this box run as Python' (parity_root) box is no longer offline
[90m[3m[info,ki,startup][0m     <|< - '5ef32b49-1ea2-4ca9-8d5c-5fd75d8821d9'
[90m[3m[info,kotlin,compile][0m     compile=173ms
[90m[3m[info,kotlin,evaluate][0m     evaluate=21.5ms
[90m[3m[info,ki,startup][0m     reset preamble cache #15 box='an error is marked where it happened' (parity_error)
[90m[3m[info,ki,startup][0m     complete #15 box='an error is marked where it happened' (parity_error) box is no longer offline
[90m[3m[info,ki,startup][0m   <|< - 'd7f49322-a1c1-4c4e-b64f-5106f67a0918'
[90m[3m[info,kotlin,compile][0m compile=93.6ms
[compile]   parity_root: PASS
[compile]   parity_error: code_language=Python, not Kotlin — skipped
[compile]   parity_controls: code_language=Python, not Kotlin — skipped
[compile] PASS python_box_parity
BoxCodeCompileGuard ok — 5 transcript(s) compiled

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
tree-write guard: baseline of 11343 tracked file(s), 0 already dirty -> /Users/marc/field4-worktrees/lander/build/test-tree-writes/baseline.json

> Task :citationGates
WARNING: Using incubator modules: jdk.incubator.vector
citationGates: running ServerSurfaceLedgerTest
citationGates: [1/14] ServerSurfaceLedgerTest SUCCESS in 3.3s
citationGates: running CloseNodeCitationDriftTest
citationGates: [2/14] CloseNodeCitationDriftTest SUCCESS in 1.3s
citationGates: running CitationContractTest
citationGates: [3/14] CitationContractTest SUCCESS in 3.0s
citationGates: running DerivedArithmeticContractTest
citationGates: [4/14] DerivedArithmeticContractTest SUCCESS in 4.0s
citationGates: running DocsFenceTreatmentTest
citationGates: [5/14] DocsFenceTreatmentTest SUCCESS in 0.2s
citationGates: running DocsGatesWiringTest
citationGates: [6/14] DocsGatesWiringTest SUCCESS in 0.1s
citationGates: running DocumentedApiEvaluationTest
citationGates: [7/14] DocumentedApiEvaluationTest SUCCESS in 8.1s
citationGates: running GateInputDeclarationTest
citationGates: [8/14] GateInputDeclarationTest SUCCESS in 0.1s
citationGates: running ImpossibilityClaimContractTest
citationGates: [9/14] ImpossibilityClaimContractTest SUCCESS in 7.4s
citationGates: running LanguagesCategoryTest
citationGates: [10/14] LanguagesCategoryTest SUCCESS in 0.9s
citationGates: running PersonalBoundaryTest
citationGates: [11/14] PersonalBoundaryTest SUCCESS in 5.7s
citationGates: running ProseNumeralContractTest
citationGates: [12/14] ProseNumeralContractTest SUCCESS in 4.1s
citationGates: running SmallLanguageAnatomyTest
citationGates: [13/14] SmallLanguageAnatomyTest SUCCESS in 0.6s
citationGates: running TranscriptStackAutoCompileTest
citationGates: [14/14] TranscriptStackAutoCompileTest SUCCESS in 0.3s

> Task :checkTestTreeWrites
warning: `VIRTUAL_ENV=/Users/marc/field4/.venv` does not match the project environment path `.venv` and will be ignored; use `--active` to target the active environment instead
tree-write guard: citationGates left the working tree as it found it.

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':checkPythonTools' (registered in build file 'build.gradle.kts').
> Process 'command 'uv'' finished with non-zero exit value 1

* Try:
> Run with --stacktrace option to get the stack trace.
> Run with --info or --debug option to get more log output.
> Run with --scan to get full insights from a Build Scan (powered by Develocity).
> Get more help at https://help.gradle.org.

BUILD FAILED in 6m 17s
36 actionable tasks: 13 executed, 23 up-to-date

  A generated artifact is stale after this merge.
  It was not your commit that broke it and no hook ran on the clone that did:
  .git/hooks is per-clone and Git never transports it.

  Reproduce:  ./gradlew checkKDocAttachment checkAgentTranscriptIndex checkAgentTranscriptLayouts checkAgentDemos checkPythonTools checkBoxIdioms checkBoxCodeCompiles citationGates
  The failing task names the artifact and the command that regenerates it.
  Regenerate and commit that, or `main` is red until someone else finds it.

  Set FIELD_SKIP_MERGE_GUARD=1 to skip this check.

merge_regen: a guard failed on the batch

land: 2026-10-17T12:18:55+02:00 red; asking the fixer (up to 15 min) about nightly/2026-10-15/1-python-box-parity

What the fixer did on recent reds (for calibration):
- fixed: The branch rewrote box code in four transcripts without regenerating the completion-corpus manifest, so I regenerated it with AFIELD_HARVEST_WRITE=1 and the check now passes.
- fixed: The branch rewrote box code in two transcripts without regenerating tools/completion-corpus/boxes/manifest.json, so I regenerated it with AFIELD_HARVEST_WRITE=1 and the check now passes.
- fixed: The branch rewrote box code in three transcripts without regenerating tools/completion-corpus/boxes/manifest.json, so I regenerated it with AFIELD_HARVEST_WRITE=1 and checkCompletionCorpusFixtures now passes.
- fixed: The branch touched two doc pages, so checkBoxIdioms covered every fence on them and flagged three listOf(...) calls; I rewrote them as collection literals and the checker is clean.
- fixed: The branch put a KDoc block on the new placeControlIds parameter of corpusMicroscopeHtml, which the guard cannot attach, so I moved it into the function's KDoc as @param and checkKDocAttachment passes.
- fixed: The branch replaced computeMemoryBarrier with computeBarrier in spin_glass and agent_transcripts.md without renaming the matching COMPILE_ONLY_REMAINDER entry, so I renamed it and DocumentedApiEvaluationTest now passes.
- declined: The python-box-parity branch fails three separate guards: a missing hand-written index entry, two control boxes created without frames (likely a runtime behaviour issue), and a demo-corpus measurement mismatch, which together need author judgement rather than a mechanical fix.
- fixed: The branch put KDoc above annotation-and-val lines the extractor drops and left the published conversation copies of the three study-tool files stale; I split the lines and re-ran the packagers, and both checks now pass.

This replay's checkout cannot build: the `gradle` command is not available, so verify by reading the check's source and rerunning any `tools/` Python check that covers it.
