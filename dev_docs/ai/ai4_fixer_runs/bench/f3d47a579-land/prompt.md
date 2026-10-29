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

main before this round: c086b2623268c1cbfba0cc05a8376c86a951bc6b

Branches in the round, each merged as its own commit:

## nightly/2026-10-13/1-gesture-paint-replay (396a36f5b, by Marc)
field_paint_gesture: list it in FIELD_MCP.md, and unit-test the request it builds
dev_docs/agents/FIELD_MCP.md                                                |   4 +
 dev_docs/agents/transcripts/README.md                                       |   1 +
 dev_docs/agents/transcripts/gesture_paint_replay.yaml                       | 121 ++++++++++++++++++++++++++++
 dev_docs/paint/GESTURE_PAINT_REPLAY.md                                      | 144 +++++++++++++++++++++++++++++++++
 dev_docs/paint/assets/gesture_paint_replay/agent_bake_then_edit.png         | Bin 0 -> 45501 bytes
 dev_docs/paint/assets/gesture_paint_replay/demo_dimensions.png              | Bin 0 -> 60238 bytes
 dev_docs/paint/assets/gesture_paint_replay/pen_pressure_vs_light.png        | Bin 0 -> 38133 bytes
 dev_docs/paint/assets/gesture_paint_replay/recorded_vs_edited.png           | Bin 0 -> 40699 bytes
 public/docs/agent_transcripts.md                                            |  16 ++++
 public/docs/assets/agent_transcripts/gesture_paint_replay/AGENTS.md         | 129 ++++++++++++++++++++++++++++++
 public/docs/assets/agent_transcripts/gesture_paint_replay/document.md       | 114 +++++++++++++++++++++++++++
 .../assets/agent_transcripts/gesture_paint_replay/gpr_dimensions_code_.kt   |  36 +++++++++
 public/docs/assets/agent_transcripts/gesture_paint_replay/gpr_pad_code_.kt  |  48 +++++++++++
 public/docs/assets/agent_transcripts/gesture_paint_replay/metadata.edn      |   1 +
 src/commonMain/resources/arrayControls.js                                   |  49 ++++++++----
 src/commonMain/resources/controllers.js                                     |   2 +
 src/jvmMain/kotlin/afield/examples/DemoTranscriptExecSmokeTest.kt           |   4 +
 src/jvmMain/kotlin/afield/paint/GestureReplayMain.kt                        | 142 +++++++++++++++++++++++++++++++++
 src/jvmMain/kotlin/afield/paint/gestureReplay.kt                            | 149 +++++++++++++++++++++++++++++++++++
 src/jvmMain/kotlin/afield/visualizers.kt                                    | 153 +++++++++++++++++++++++++++++-------
 src/jvmTest/kotlin/afield/paint/GesturePaintReplayTest.kt                   | 150 +++++++++++++++++++++++++++++++++++
 tools/field_mcp.py                                                          |  71 +++++++++++++++++
 tools/test_field_backdoor.py                                                |  30 +++++++
 23 files changed, 1317 insertions(+), 47 deletions(-)
Its commits (the branch is all of them, not only the tip; its whole change is `git diff c086b2623268...396a36f5bf26`):
396a36f5b field_paint_gesture: list it in FIELD_MCP.md, and unit-test the request it builds
0a3e2b502 Demo gesture_paint_replay, the dropped-dimensions page, and a pen take readout
4f0c61fd0 Paint replay: tilt/twist lanes, and field_paint_gesture for agents
0bf31a8fc Paint: replay a gesture pad's take (or its edited keyframe lanes) as a brush movement

What :checkKDocAttachment printed when it failed:
KDocAttachmentGuard: scanned 2155 file(s), 27546 KDoc block(s)
KDocAttachmentGuard: 2 mis-attached KDoc block(s):
  src/jvmTest/kotlin/afield/paint/GesturePaintReplayTest.kt:94: DROPPED — line 95 puts an annotation and the declaration together (`@Test fun anEditedTakePaintsWhereTheEditMovedIt() {`); the extractor skips the whole line. Break the annotation onto its own line
  src/jvmTest/kotlin/afield/paint/GesturePaintReplayTest.kt:128: DROPPED — line 134 puts an annotation and the declaration together (`@Test fun aPensPressurePressesTheHairsWhereThePenPressed() {`); the extractor skips the whole line. Break the annotation onto its own line
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
    }
    step("squares") {
        markdownNote(
            """
            ## Line 3 does the work
            `squares` maps each number to its square.

            [[back]] [[next]]
            """.trimIndent(),
            animated = true
        )
        selectBox(squaresBox, property = "code", line = 3, highlight = 3)
        annotateToLine(id = "line3", targetBox = squaresBox, line = 3, color = "rgba(255, 180, 100, 0.8)", dashed = true)
    }
    step("Your turn") {
        markdownNote(
            """
            ## Your turn
            [Give this guide a star](#custom:star). Stars so far: **{{stars}}**.

            [[home:Start again]] [[finish]]
            """.trimIndent(),
            animated = true
        )
        linkCallback("star") {
            stars += 1
            here.properties["stars"] = stars
            show.goToStep(show.currentStepIndex)
        }
        selectBox(guide)
    }
}

runBlocking { show.start() }
[90m[3m[debug,kotlin][0m <|< - 'fa7e52f9-a6ec-4412-9d4a-803ab2bdbabe'
[90m[3m[info,attic][0m checkAtticAndExecute for 'tour' (sg_tour): empty=true, same=false, currentLen=0, wasLen=-1
[90m[3m[info,attic][0m attic is empty for 'tour' — skipping
[90m[3m[info,kotlin,perf][0m 		 compile request engine=KotlinInterface2 filePrefix=sg_tour source=sg_tour__code_$2 kind=user-code length=1806 preview=//%- | val show = slideShow { | step("Welcome") {
[90m[3m[info,kotlin,perf][0m 		 compile code //%-

val show = slideShow {
    step("Welcome") {
        markdownNote(
            """
            ## A tour of this document
            This guide has four steps. Each one selects the box it is about.

            [[next:Start →]]
            """.trimIndent(),
            animated = true
        )
        selectBox(guide)
    }
    step("numbers") {
        onEnter {
            visits += 1
            guide.properties["visits"] = visits
        }
        markdownNote(
            """
            ## `numbers` makes a list
            The green line points at it. Visits to this step: **{{visits}}**.

            [[back]] [[next]]
            """.trimIndent(),
            animated = true
        )
        selectBox(numbersBox)
        annotate(id = "numbers", targetBox = numbersBox, color = "rgba(128, 255, 164, 0.8)", dashed = false)
    }
    step("squares") {
        markdownNote(
            """
            ## Line 3 does the work
            `squares` maps each number to its square.

            [[back]] [[next]]
            """.trimIndent(),
            animated = true
        )
        selectBox(squaresBox, property = "code", line = 3, highlight = 3)
        annotateToLine(id = "line3", targetBox = squaresBox, line = 3, color = "rgba(255, 180, 100, 0.8)", dashed = true)
    }
    step("Your turn") {
        markdownNote(
            """
            ## Your turn
            [Give this guide a star](#custom:star). Stars so far: **{{stars}}**.

            [[home:Start again]] [[finish]]
            """.trimIndent(),
            animated = true
        )
        linkCallback("star") {
            stars += 1
            here.properties["stars"] = stars
            show.goToStep(show.currentStepIndex)
        }
        selectBox(guide)
    }
}

runBlocking { show.start() }
[90m[3m[info,kotlin,compile][0m compile=323ms
[90m[3m[info,kotlin,evaluate][0m evaluate=33.0ms
[execute] PASS slideshow_guide (applied=6, output lines=4)
BoxCodeCompileGuard ok — 3 transcript(s) compiled

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
tree-write guard: baseline of 10557 tracked file(s), 0 already dirty -> /Users/marc/field4-worktrees/lander/build/test-tree-writes/baseline.json

> Task :citationGates
WARNING: Using incubator modules: jdk.incubator.vector
citationGates: running CitationContractTest
citationGates: [1/14] CitationContractTest SUCCESS in 4.5s
citationGates: running DerivedArithmeticContractTest
citationGates: [2/14] DerivedArithmeticContractTest SUCCESS in 4.5s
citationGates: running DocsFenceTreatmentTest
citationGates: [3/14] DocsFenceTreatmentTest SUCCESS in 0.2s
citationGates: running DocsGatesWiringTest
citationGates: [4/14] DocsGatesWiringTest SUCCESS in 0.1s
citationGates: running DocumentedApiEvaluationTest
citationGates: [5/14] DocumentedApiEvaluationTest SUCCESS in 9.3s
citationGates: running GateInputDeclarationTest
citationGates: [6/14] GateInputDeclarationTest SUCCESS in 0.1s
citationGates: running ImpossibilityClaimContractTest
citationGates: [7/14] ImpossibilityClaimContractTest SUCCESS in 10.1s
citationGates: running LanguagesCategoryTest
citationGates: [8/14] LanguagesCategoryTest SUCCESS in 0.7s
citationGates: running PersonalBoundaryTest
citationGates: [9/14] PersonalBoundaryTest SUCCESS in 5.3s
citationGates: running ProseNumeralContractTest
citationGates: [10/14] ProseNumeralContractTest SUCCESS in 5.0s
citationGates: running SmallLanguageAnatomyTest
citationGates: [11/14] SmallLanguageAnatomyTest SUCCESS in 0.7s
citationGates: running TranscriptStackAutoCompileTest
citationGates: [12/14] TranscriptStackAutoCompileTest SUCCESS in 3.5s
citationGates: running CloseNodeCitationDriftTest
citationGates: [13/14] CloseNodeCitationDriftTest SUCCESS in 0.0s
citationGates: running ServerSurfaceLedgerTest
citationGates: [14/14] ServerSurfaceLedgerTest SUCCESS in 3.4s

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

BUILD FAILED in 1m 1s
32 actionable tasks: 9 executed, 23 up-to-date

  A generated artifact is stale after this merge.
  It was not your commit that broke it and no hook ran on the clone that did:
  .git/hooks is per-clone and Git never transports it.

  Reproduce:  ./gradlew checkKDocAttachment checkAgentTranscriptIndex checkBoxIdioms checkBoxCodeCompiles citationGates
  The failing task names the artifact and the command that regenerates it.
  Regenerate and commit that, or `main` is red until someone else finds it.

  Set FIELD_SKIP_MERGE_GUARD=1 to skip this check.

merge_regen: a guard failed on the batch

land: 2026-10-13T10:53:29+02:00 red; asking the fixer (up to 15 min) about nightly/2026-10-13/3-slideshow-guide, nightly/2026-10-13/2-fline-exact-booleans, nightly/2026-10-13/1-gesture-paint-replay

What the fixer did on recent reds (for calibration):
- fixed: The branch rewrote the open_dlss_nr transcript box code without regenerating tools/completion-corpus/boxes/manifest.json; regenerating it with AFIELD_HARVEST_WRITE=1 makes checkCompletionCorpusFixtures pass.
- declined: AgentDemoRegistrationTest now reads .gitignore, and the only correct fix is declaring it as a citationGates input in build.gradle.kts, which the fixer may not edit, while an EXEMPT row would hide a real undeclared input.
- fixed: StereoTest.kt put @Test and the fun on one line, which made the KDoc guard drop the block above it; splitting the annotation onto its own line makes checkKDocAttachment pass.
- fixed: The branch added the GET /api/docs-search route without its row in server_surface_inventory.md; adding the row makes ServerSurfaceLedgerTest pass.

This replay's checkout cannot build: the `gradle` command is not available, so verify by reading the check's source and rerunning any `tools/` Python check that covers it.
