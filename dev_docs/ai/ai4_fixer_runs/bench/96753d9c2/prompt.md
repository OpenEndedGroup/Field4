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

main before this round: 2be5ed1b1b8549297e1b0a58e14d2e776e2f636b

Branches in the round, each merged as its own commit:

## claude/genetic-algorithm-water-bug-cb6eff (d8e5d78e7, by Marc)
simulation(creatures): move the water-bug creature GA into afield, with a pluggable ContactModel
 dev_docs/agents/transcripts/README.md                                       |    3 +
 dev_docs/agents/transcripts/evolving_water_bugs.yaml                        |  223 +++
 dev_docs/physics/CREATURE_EVOLUTION.md                                      |   77 +
 public/docs/agent_transcripts.md                                            |   14 +
 public/docs/assets/agent_transcripts/evolving_water_bugs/AGENTS.md          |  129 ++
 public/docs/assets/agent_transcripts/evolving_water_bugs/document.md        |  210 +++
 .../assets/agent_transcripts/evolving_water_bugs/ground_crawler_code_.kt    |   88 ++
 public/docs/assets/agent_transcripts/evolving_water_bugs/metadata.edn       |    1 +
 public/docs/assets/agent_transcripts/evolving_water_bugs/water_bug_code_.kt |   92 ++
 src/jvmMain/kotlin/afield/examples/DemoTranscriptExecSmokeTest.kt           |    5 +
 src/jvmMain/kotlin/afield/simulation/creatures/Creatures.kt                 | 2924 +++++++++++++++++++++++++++++++++++
 src/jvmTest/kotlin/afield/simulation/creatures/CreatureEvolutionTest.kt     |   93 ++
 tools/completion-corpus/boxes/manifest.json                                 |    2 +
 13 files changed, 3861 insertions(+)

Failing tests (first): PersonalBoundaryTest.no
## PersonalBoundaryTest.no committed file cites a path inside the private tree
java.lang.AssertionError: committed files cite paths inside src/personal/:
  dev_docs/physics/CREATURE_EVOLUTION.md → src/personal/kotlin/organic

A citation is evidence a reader is meant to be able to open, and nobody else can open
this one. State the finding in terms of afield.* and cite the afield.* code it applies
to; if the only evidence is private, the claim belongs in a commit message or the
nightly journal rather than in a guarded document.



The end of the landing log:
 preamble, extra lines are import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
import afield.media.browser.PuppetBrowser
var Box.browserControl by Inheritance<PuppetBrowser>() /**/
val Once.browserControl by Onceing<PuppetBrowser>() /**/
val Why.browserControl by Whying() /**/
var Box.window by Inheritance<Window>() /*from:youtube_vr_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:youtube_vr_renderer*/ /**/
val Why.window by Whying() /*from:youtube_vr_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:youtube_vr_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:youtube_vr_renderer*/ /**/
val Why.renderer by Whying() /*from:youtube_vr_renderer*/ /**/, executing
[90m[3m[info,kotlin,perf][0m 		 compile cache miss reason=not compiled before filePrefix=youtube_vr_panel property=preamble
[90m[3m[info,kotlin,perf][0m 		 compile request engine=KotlinInterface2 filePrefix=youtube_vr_panel source=youtube_vr_panel__preamble_$1 kind=imports-or-preamble length=701 preview=import afield.graphics.vulkan.util.Window | import afield.graphics.vulkan.Renderer | import afield.media.browser.PuppetB...
[90m[3m[info,kotlin,perf][0m 		 compile code import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
import afield.media.browser.PuppetBrowser
var Box.browserControl by Inheritance<PuppetBrowser>() /**/
val Once.browserControl by Onceing<PuppetBrowser>() /**/
val Why.browserControl by Whying() /**/
var Box.window by Inheritance<Window>() /*from:youtube_vr_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:youtube_vr_renderer*/ /**/
val Why.window by Whying() /*from:youtube_vr_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:youtube_vr_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:youtube_vr_renderer*/ /**/
val Why.renderer by Whying() /*from:youtube_vr_renderer*/ /**/
[90m[3m[info,kotlin,compile][0m compile=124ms
[90m[3m[info,kotlin,evaluate][0m evaluate=6.08ms
 finished 
[90m[3m[info,attic][0m checkAtticAndExecute for 'YouTube — interactive Chromium panel' (youtube_vr_panel): empty=true, same=false, currentLen=0, wasLen=-1
[90m[3m[info,attic][0m attic is empty for 'YouTube — interactive Chromium panel' — skipping
[90m[3m[info,kotlin,perf][0m 		 compile request engine=KotlinInterface2 filePrefix=youtube_vr_panel source=youtube_vr_panel__code_$2 kind=imports-or-preamble length=2271 preview=import afield.graphics.vulkan.Target | import afield.media.browser.FieldUiBrowserTexture | import afield.media.browser.P...
[90m[3m[info,kotlin,perf][0m 		 compile code import afield.graphics.vulkan.Target
import afield.media.browser.FieldUiBrowserTexture
import afield.media.browser.PuppetBrowser
import afield.media.browser.PuppetBrowserVrPanel
import afield.media.browser.PuppetBrowserWindowInput

// Publish browser controls to connected boxes.
Unit

// The producer is a full Chromium page, not an embed: consent, account state, player,
// navigation and audio all behave as they do in an ordinary browser.
val browser = FieldUiBrowserTexture(
    renderer = renderer,
    url = "https://www.youtube.com/watch?v=aqz-KE-bpKQ",
    width = 1280,
    height = 720,
    // Linux: Chromium's shared-texture export never fills the buffer (electron#53271).
    cpuBitmapFallback = FieldUiBrowserTexture.cpuBitmapFallbackRequired,
)
browserControl = browser

// Import the newest cross-process frame before either eye samples the resident texture.
renderer.renderList.preTargets["youtube-browser-upload"] = Target { command, _ ->
    browser.prepareSampledImagesFor(command)
}

val stereo = SideBySideStereoTarget(renderer, openXR = renderer.openXRHmdTarget)
val panel = PuppetBrowserVrPanel(
    renderer = renderer,
    stereo = stereo,
    browser = browser,
    widthMeters = 1.6f,
    distanceMeters = 1.7f,
    name = "youtube-browser-panel",
)
panel.build()
panel.enableDesktopInteraction()

// The controller owns pointer input. Once the panel has focus, physical keyboard/IME
// events from Field's Window remain available for search boxes and other text fields.
val keyboard = PuppetBrowserWindowInput(browser, renderer.window) { _, _ -> null }
keyboard.takeFocus()

// Desktop mirror of the same stereo texture that can be handed to OpenXR eye swapchains.
stereo.packed.blitToScreen(
    renderer = renderer,
    target = renderer.renderList,
    name = "youtube-stereo-screen",
)

var announced = false
loop {
    if (!announced && browser.pageReady) {
        announced = true
        _printlnHere(54+1, 
            "YouTube is live on a ${browser.spec.width}x${browser.spec.height} " +
                "texture; trigger accepts consent and controls playback",
        )
    }
    frame()
}
    .end {
        renderer.renderList.preTargets.remove("youtube-browser-upload")
        keyboard.close()
        browser.close()
    }

[90m[3m[info,kotlin,compile][0m compile=62.6ms
[compile]   youtube_vr_panel: PASS
[compile] PASS youtube_vr_browser
BoxCodeCompileGuard ok — 231 transcript(s) compiled

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
tree-write guard: baseline of 12288 tracked file(s), 0 already dirty -> /Users/marc/field4-worktrees/lander/build/test-tree-writes/baseline.json

> Task :citationGates
WARNING: Using incubator modules: jdk.incubator.vector
citationGates: running ServerSurfaceLedgerTest
citationGates: [1/14] ServerSurfaceLedgerTest SUCCESS in 6.4s
citationGates: running CloseNodeCitationDriftTest
citationGates: [2/14] CloseNodeCitationDriftTest SUCCESS in 3.0s
citationGates: running CitationContractTest
citationGates: [3/14] CitationContractTest SUCCESS in 7.8s
citationGates: running DerivedArithmeticContractTest
citationGates: [4/14] DerivedArithmeticContractTest SUCCESS in 8.0s
citationGates: running DocsFenceTreatmentTest
citationGates: [5/14] DocsFenceTreatmentTest SUCCESS in 0.8s
citationGates: running DocsGatesWiringTest
citationGates: [6/14] DocsGatesWiringTest SUCCESS in 0.3s
citationGates: running DocumentedApiEvaluationTest
citationGates: [7/14] DocumentedApiEvaluationTest SUCCESS in 13.0s
citationGates: running GateInputDeclarationTest
citationGates: [8/14] GateInputDeclarationTest SUCCESS in 0.1s
citationGates: running ImpossibilityClaimContractTest
citationGates: [9/14] ImpossibilityClaimContractTest SUCCESS in 11.8s
citationGates: running LanguagesCategoryTest
citationGates: [10/14] LanguagesCategoryTest SUCCESS in 1.9s
citationGates: running PersonalBoundaryTest

PersonalBoundaryTest > no committed file cites a path inside the private tree FAILED
    java.lang.AssertionError at PersonalBoundaryTest.kt:353
citationGates: [11/14] PersonalBoundaryTest FAILURE in 11.0s
citationGates: running ProseNumeralContractTest
citationGates: [12/14] ProseNumeralContractTest SUCCESS in 8.7s
citationGates: running SmallLanguageAnatomyTest
citationGates: [13/14] SmallLanguageAnatomyTest SUCCESS in 2.1s
citationGates: running TranscriptStackAutoCompileTest
citationGates: [14/14] TranscriptStackAutoCompileTest SUCCESS in 1.0s

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

BUILD FAILED in 19m 14s
34 actionable tasks: 11 executed, 23 up-to-date

  A generated artifact is stale after this merge.
  It was not your commit that broke it and no hook ran on the clone that did:
  .git/hooks is per-clone and Git never transports it.

  Reproduce:  ./gradlew checkKDocAttachment checkAgentTranscriptIndex checkPythonTools checkBoxIdioms checkBoxCodeCompiles citationGates
  The failing task names the artifact and the command that regenerates it.
  Regenerate and commit that, or `main` is red until someone else finds it.

  Set FIELD_SKIP_MERGE_GUARD=1 to skip this check.

merge_regen: a guard failed on the batch

land: 2026-10-19T01:01:00+02:00 red; asking the fixer (up to 15 min) about claude/genetic-algorithm-water-bug-cb6eff, claude/field-documentation-guides-bffa51

What the fixer did on recent reds (for calibration):
- declined: no verdict line
- fixed: The new principal_curves page teaches pointsFLine and widthFLines, which nothing in CI ran, so I added a jvmTest calling both and DocumentedApiEvaluationTest now passes.
- fixed: The branch added the GLSL helper cameraPosition to the open_dlss_nr cubes shader on a published page, and it needed a COMPILE_ONLY_REMAINDER row like its sibling objectPoint; with that row DocumentedApiEvaluationTest passes.
- declined: The log shows only Gradle's generic non-zero exit for regenerateAgentTranscriptStartingPoints; the actual error (likely from the new polyvector_explorer transcript) was not captured, and a ~13-minute rerun to find it leaves no time to make and check a fix.
- declined: regenerateAgentTranscriptStartingPoints exited 1 without printing the actual error (most likely from the new polyvector_explorer transcript), so there is nothing specific to fix without a long rerun.
- fixed: The new polyvector page teaches byCreatingParallelCurves, byCreatingNormalLines and byWaving, which nothing in CI ran, so I added a jvmTest in PolyvectorTest calling all three and DocumentedApiEvaluationTest now passes.
- fixed: The new relaunchedVideoTargetReady KDoc was inserted between videoCapturePreparation's KDoc and its function, stacking two blocks; moving the original block back above videoCapturePreparation makes checkKDocAttachment pass.
- fixed: The branch put its new transcript's index entry in a section marked compile-only, though CI runs that transcript, and used listOf where the idiom check wants a list literal; I moved the entry, swapped in the literal, and both checks now pass.

This replay's checkout cannot build: the `gradle` command is not available, so verify by reading the check's source and rerunning any `tools/` Python check that covers it.
