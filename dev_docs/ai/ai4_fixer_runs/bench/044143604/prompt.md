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

main before this round: 4ba9ceab976be477ed2bc7ccf9819bbfe8fd230b

Branches in the round, each merged as its own commit:

## claude/physical-media-expansion-stages-d589a3 (64acf46d0, by Marc)
Paint stereo: sketch(gesture) lays a sketch's strokes out along its world arc
 native/field-paint/.gitignore                         |   1 +
 native/field-paint/src/lib.rs                         |   6 +-
 native/field-paint/src/tools.rs                       |  59 +++++++++++++
 native/field-paint/vendor/paint/VENDORED.md           |  22 +++++
 native/field-paint/vendor/paint/src/bristle.rs        | 109 +++++++++++++++++++----
 native/field-paint/vendor/paint/src/canvas.rs         |  17 ++++
 native/field-paint/vendor/paint/src/field/bristles.rs |  21 ++++-
 native/field-paint/vendor/paint/src/field/mod.rs      |  54 ++++++++++++
 native/field-paint/vendor/paint/src/field/warp.rs     | 159 ++++++++++++++++++++++++++++++++++
 native/field-paint/vendor/paint/src/graphite.rs       |  39 ++++++++-
 native/field-paint/vendor/paint/src/rag.rs            |  40 ++++++---
 src/jvmMain/kotlin/afield/paint/PaintCanvas.kt        | 271 +++++++++++++++++++++++++++++++++++++++++++++++++++-------
 src/jvmMain/kotlin/afield/paint/PaintNative.kt        |  10 +++
 src/jvmMain/kotlin/afield/paint/PaintTools.kt         |   7 +-
 src/jvmMain/kotlin/afield/paint/StereoWarp.kt         | 125 +++++++++++++++++++++++++++
 src/jvmMain/kotlin/afield/paint/TimedGesture.kt       |  27 +++++-
 src/jvmTest/kotlin/afield/paint/StereoTest.kt         | 188 ++++++++++++++++++++++++++++++++++++++++
 17 files changed, 1082 insertions(+), 73 deletions(-)

The end of the landing log:

=== 2026-10-12T19:06:56+02:00 batch-20261012-190655-186554: 1 branch(es) onto main 4ba9ceab976be477ed2bc7ccf9819bbfe8fd230b
merge_regen: merging claude/physical-media-expansion-stages-d589a3 64acf46d0
Automatic merge went well; stopped before committing as requested
warning: `VIRTUAL_ENV=/Users/marc/field4/.venv` does not match the project environment path `.venv` and will be ignored; use `--active` to target the active environment instead
generated-artifact guard (staged): affects KDoc attachment, citation and documentation contracts. Not run here; landing runs it. To check now: ./gradlew --continue checkKDocAttachment citationGates
merge_regen: one regeneration and one guard for 1 branch(es)
merge_regen: regenerating citation and documentation contracts (citationGates --tests afield.docs.ProseNumeralContractTest --rerun)

> Configure project :
Mac OS X, aarch64
Mac OS X

> Task :kmpPartiallyResolvedDependenciesChecker
> Task :checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :generateLinalgBuildProfile UP-TO-DATE
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
> Task :jvmProcessResources UP-TO-DATE
> Task :processJvmMainResources SKIPPED
> Task :jvmTestProcessResources UP-TO-DATE
> Task :processJvmTestResources SKIPPED
> Task :prepareSmileNative UP-TO-DATE

> Task :recordTestTreeBaseline
warning: `VIRTUAL_ENV=/Users/marc/field4/.venv` does not match the project environment path `.venv` and will be ignored; use `--active` to target the active environment instead
tree-write guard: baseline of 10512 tracked file(s), 0 already dirty -> /Users/marc/field4-worktrees/lander/build/test-tree-writes/baseline.json

> Task :compileKotlinJvm
> Task :compileJvmMainJava
> Task :jvmMainClasses
> Task :jvmJar
> Task :compileTestKotlinJvm
> Task :compileJvmTestJava NO-SOURCE
> Task :jvmTestClasses

> Task :citationGates
WARNING: Using incubator modules: jdk.incubator.vector
citationGates: running ProseNumeralContractTest
citationGates: [1/14] ProseNumeralContractTest SUCCESS in 13.7s

> Task :checkTestTreeWrites
warning: `VIRTUAL_ENV=/Users/marc/field4/.venv` does not match the project environment path `.venv` and will be ignored; use `--active` to target the active environment instead
tree-write guard: citationGates left the working tree as it found it.

BUILD SUCCESSFUL in 1m 43s
28 actionable tasks: 9 executed, 19 up-to-date
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.5.1/userguide/configuration_cache_enabling.html
merge_regen: regenerating dev_docs/GUARD_CORPORA.md
guard_corpora: dev_docs/GUARD_CORPORA.md already current
merge_regen: running the generated-artifact guards over 4ba9ceab9..HEAD
generated-artifact guard: checking KDoc attachment, citation and documentation contracts (changed by 4ba9ceab976be477ed2bc7ccf9819bbfe8fd230b..HEAD)

> Configure project :
Mac OS X, aarch64
Mac OS X

> Task :kmpPartiallyResolvedDependenciesChecker
> Task :checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :generateLinalgBuildProfile UP-TO-DATE
> Task :compileKotlinJvm UP-TO-DATE
> Task :compileJvmMainJava UP-TO-DATE
> Task :jvmProcessResources UP-TO-DATE
> Task :processJvmMainResources SKIPPED
> Task :jvmMainClasses UP-TO-DATE
> Task :prepareSmileNative UP-TO-DATE

> Task :checkKDocAttachment
KDocAttachmentGuard: scanned 2145 file(s), 27476 KDoc block(s)
KDocAttachmentGuard: 1 mis-attached KDoc block(s):
  src/jvmTest/kotlin/afield/paint/StereoTest.kt:81: DROPPED — line 87 puts an annotation and the declaration together (`@Test fun aStrokeRecedingInDepthRunsOutAtTheSameWorldLengthI…`); the extractor skips the whole line. Break the annotation onto its own line

Each of these is either dropped or attached to the WRONG declaration, and a dropped
block's prose is published under whatever symbol parses next. Fix by either
  - moving the block so the very next line is a declaration
    (KDocExtractor.parseDeclaration reads: class / interface / object / fun / val / var /
    typealias, after modifiers and after annotations *on their own line*), or
  - teaching KDocExtractor.parseDeclaration the form it cannot see, or
  - deleting the block if it documents nothing.
See dev_docs/kdoc_reference_contract.md.

> Task :checkKDocAttachment FAILED
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
tree-write guard: baseline of 10512 tracked file(s), 0 already dirty -> /Users/marc/field4-worktrees/lander/build/test-tree-writes/baseline.json

> Task :citationGates
WARNING: Using incubator modules: jdk.incubator.vector
citationGates: running CitationContractTest
citationGates: [1/14] CitationContractTest SUCCESS in 10.0s
citationGates: running DerivedArithmeticContractTest
citationGates: [2/14] DerivedArithmeticContractTest SUCCESS in 10.0s
citationGates: running DocsFenceTreatmentTest
citationGates: [3/14] DocsFenceTreatmentTest SUCCESS in 0.4s
citationGates: running DocsGatesWiringTest
citationGates: [4/14] DocsGatesWiringTest SUCCESS in 0.2s
citationGates: running DocumentedApiEvaluationTest
citationGates: [5/14] DocumentedApiEvaluationTest SUCCESS in 18.2s
citationGates: running GateInputDeclarationTest
citationGates: [6/14] GateInputDeclarationTest SUCCESS in 0.1s
citationGates: running ImpossibilityClaimContractTest
citationGates: [7/14] ImpossibilityClaimContractTest SUCCESS in 20.1s
citationGates: running LanguagesCategoryTest
citationGates: [8/14] LanguagesCategoryTest SUCCESS in 2.6s
citationGates: running PersonalBoundaryTest
citationGates: [9/14] PersonalBoundaryTest SUCCESS in 13.7s
citationGates: running ProseNumeralContractTest
citationGates: [10/14] ProseNumeralContractTest SUCCESS in 14.1s
citationGates: running SmallLanguageAnatomyTest
citationGates: [11/14] SmallLanguageAnatomyTest SUCCESS in 1.2s
citationGates: running TranscriptStackAutoCompileTest
citationGates: [12/14] TranscriptStackAutoCompileTest SUCCESS in 7.4s
citationGates: running CloseNodeCitationDriftTest
citationGates: [13/14] CloseNodeCitationDriftTest SUCCESS in 0.0s
citationGates: running ServerSurfaceLedgerTest
citationGates: [14/14] ServerSurfaceLedgerTest SUCCESS in 5.6s

> Task :checkTestTreeWrites
warning: `VIRTUAL_ENV=/Users/marc/field4/.venv` does not match the project environment path `.venv` and will be ignored; use `--active` to target the active environment instead
tree-write guard: citationGates left the working tree as it found it.

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':checkKDocAttachment' (registered in build file 'build.gradle.kts').
> Process 'command '/Users/marc/.field4/lander-gradle-home/jdks/graalvm_community-25-aarch64-os_x.2/graalvm-community-openjdk-25.0.2+10.1/Contents/Home/bin/java'' finished with non-zero exit value 1

* Try:
> Run with --stacktrace option to get the stack trace.
> Run with --info or --debug option to get more log output.
> Run with --scan to get full insights from a Build Scan (powered by Develocity).
> Get more help at https://help.gradle.org.

BUILD FAILED in 1m 53s
29 actionable tasks: 6 executed, 23 up-to-date

  A generated artifact is stale after this merge.
  It was not your commit that broke it and no hook ran on the clone that did:
  .git/hooks is per-clone and Git never transports it.

  Reproduce:  ./gradlew checkKDocAttachment citationGates
  The failing task names the artifact and the command that regenerates it.
  Regenerate and commit that, or `main` is red until someone else finds it.

  Set FIELD_SKIP_MERGE_GUARD=1 to skip this check.

merge_regen: a guard failed on the batch

land: 2026-10-12T19:10:38+02:00 red; asking the fixer (up to 15 min) about claude/physical-media-expansion-stages-d589a3

What the fixer did on recent reds (for calibration):
- fixed: The branch rewrote the open_dlss_nr transcript box code without regenerating tools/completion-corpus/boxes/manifest.json; regenerating it with AFIELD_HARVEST_WRITE=1 makes checkCompletionCorpusFixtures pass.
- declined: AgentDemoRegistrationTest now reads .gitignore, and the only correct fix is declaring it as a citationGates input in build.gradle.kts, which the fixer may not edit, while an EXEMPT row would hide a real undeclared input.

This replay's checkout cannot build: the `gradle` command is not available, so verify by reading the check's source and rerunning any `tools/` Python check that covers it.
