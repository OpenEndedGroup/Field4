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

main before this round: 35c998efa6177c9c7bc78b9bc8e62d19562eaf11

Branches in the round, each merged as its own commit:

## configurable-agent (f1d8c0627, by Marc)
Choose the coding agent: claude or codex, one setting for every tool
public/docs/docs_notes.md                               |  3 ++-
 public/docs/landing_queue.md                            | 28 +++++++++++++++++++---
 src/commonMain/resources/docs-pass.html                 | 14 +++++++----
 src/jvmMain/kotlin/afield/server/docsPass.kt            |  2 +-
 src/jvmMain/kotlin/afield/server/terminal.kt            | 23 ++++++++++++++----
 src/jvmTest/kotlin/afield/server/TerminalCommandTest.kt | 19 +++++++++++++++
 tools/docs_notes.py                                     |  9 ++++---
 tools/field_agent.py                                    | 74 +++++++++++++++++++++++++++++++++++++++++++++++++++++++++
 tools/land.py                                           | 60 +++++++++++++++++++++-------------------------
 tools/test_docs_notes.py                                |  3 ++-
 tools/test_field_agent.py                               | 63 ++++++++++++++++++++++++++++++++++++++++++++++++
 11 files changed, 247 insertions(+), 51 deletions(-)
Its commits (the branch is all of them, not only the tip; its whole change is `git diff 35c998efa617...f1d8c0627fb9`):
f1d8c0627 Choose the coding agent: claude or codex, one setting for every tool

The end of the landing log:
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
> Task :jsProcessResources
> Task :jsMainClasses
> Task :compileDevelopmentExecutableKotlinJs UP-TO-DATE
> Task :jsDevelopmentExecutableCompileSync
> Task :kotlinStoreYarnLock UP-TO-DATE

> Task :jsBrowserDevelopmentWebpack
Critical dependency: the request of a dependency is an expression
asset afield.mjs 18.7 MiB [compared for emit] [big] (name: main)
asset node_modules_ws_browser_js.js 1.73 KiB [compared for emit]
runtime modules 8.36 KiB 10 modules
modules by path ./kotlin/*.mjs 10.7 MiB
  ./kotlin/afield.mjs 5.07 MiB [built] [code generated]
  ./kotlin/kotlin-kotlin-stdlib.mjs 1.29 MiB [built] [code generated]
  ./kotlin/kotlinx-serialization-kotlinx-serialization-core.mjs 324 KiB [built] [code generated]
  ./kotlin/kotlinx-serialization-kotlinx-serialization-json.mjs 331 KiB [built] [code generated]
  ./kotlin/kotlin-extensions.mjs 294 bytes [built] [code generated]
  ./kotlin/kotlinx-html.mjs 817 KiB [built] [code generated]
  ./kotlin/kotlin-css.mjs 863 KiB [built] [code generated]
  ./kotlin/kotlinx-browser.mjs 2.83 KiB [built] [code generated]
  ./kotlin/kotlin-web.mjs 10.6 KiB [built] [code generated]
  ./kotlin/ktor-ktor-client-core.mjs 429 KiB [built] [code generated]
  + 12 modules
./kotlin/ lazy strict namespace object 160 bytes [built] [code generated]
../../node_modules/ws/browser.js 176 bytes [built] [code generated]
webpack 5.108.1 compiled with 1 warning in 1802 ms

> Task :jvmProcessResources UP-TO-DATE
> Task :processJvmMainResources SKIPPED
> Task :jvmMainClasses UP-TO-DATE
> Task :jvmJar
> Task :jvmTestProcessResources UP-TO-DATE
> Task :processJvmTestResources SKIPPED
> Task :prepareSmileNative UP-TO-DATE

> Task :recordTestTreeBaseline
warning: `VIRTUAL_ENV=/Users/marc/field4/.venv` does not match the project environment path `.venv` and will be ignored; use `--active` to target the active environment instead
tree-write guard: baseline of 10656 tracked file(s), 0 already dirty -> /Users/marc/field4-worktrees/lander/build/test-tree-writes/baseline.json

> Task :compileTestKotlinJvm
> Task :compileJvmTestJava NO-SOURCE
> Task :jvmTestClasses

> Task :citationGates
WARNING: Using incubator modules: jdk.incubator.vector
citationGates: running ProseNumeralContractTest
citationGates: [1/14] ProseNumeralContractTest SUCCESS in 7.1s

> Task :checkTestTreeWrites
warning: `VIRTUAL_ENV=/Users/marc/field4/.venv` does not match the project environment path `.venv` and will be ignored; use `--active` to target the active environment instead
tree-write guard: citationGates left the working tree as it found it.

BUILD SUCCESSFUL in 24s
28 actionable tasks: 10 executed, 18 up-to-date
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.5.1/userguide/configuration_cache_enabling.html
merge_regen: regenerating dev_docs/GUARD_CORPORA.md
guard_corpora: rewrote the generated block in dev_docs/GUARD_CORPORA.md
warning: `VIRTUAL_ENV=/Users/marc/field4/.venv` does not match the project environment path `.venv` and will be ignored; use `--active` to target the active environment instead
generated-artifact guard (staged): affects citation and documentation contracts. Not run here; landing runs it. To check now: ./gradlew --continue citationGates
merge_regen: committed the regenerated artifacts
merge_regen: running the generated-artifact guards over 35c998efa..HEAD (skipping checkCodebaseReference: just regenerated)
generated-artifact guard: checking KDoc attachment, box-code idioms, citation and documentation contracts (changed by 35c998efa6177c9c7bc78b9bc8e62d19562eaf11..HEAD)

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
KDocAttachmentGuard: scanned 2160 file(s), 27584 KDoc block(s)
KDocAttachmentGuard: 1 mis-attached KDoc block(s):
  src/jvmMain/kotlin/afield/server/terminal.kt:134: STACKED — a second KDoc block follows at line 135 with no declaration between them, so this one is discarded

Each of these is either dropped or attached to the WRONG declaration, and a dropped
block's prose is published under whatever symbol parses next. Fix by either
  - moving the block so the very next line is a declaration
    (KDocExtractor.parseDeclaration reads: class / interface / object / fun / val / var /
    typealias, after modifiers and after annotations *on their own line*), or
  - teaching KDocExtractor.parseDeclaration the form it cannot see, or
  - deleting the block if it documents nothing.
See dev_docs/kdoc_reference_contract.md.

> Task :checkKDocAttachment FAILED

> Task :checkBoxIdioms
warning: `VIRTUAL_ENV=/Users/marc/field4/.venv` does not match the project environment path `.venv` and will be ignored; use `--active` to target the active environment instead
check_box_idioms: clean (2 file(s))

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
tree-write guard: baseline of 10656 tracked file(s), 0 already dirty -> /Users/marc/field4-worktrees/lander/build/test-tree-writes/baseline.json

> Task :citationGates
WARNING: Using incubator modules: jdk.incubator.vector
citationGates: running CitationContractTest
citationGates: [1/14] CitationContractTest SUCCESS in 4.8s
citationGates: running DerivedArithmeticContractTest
citationGates: [2/14] DerivedArithmeticContractTest SUCCESS in 4.9s
citationGates: running DocsFenceTreatmentTest
citationGates: [3/14] DocsFenceTreatmentTest SUCCESS in 0.2s
citationGates: running DocsGatesWiringTest
citationGates: [4/14] DocsGatesWiringTest SUCCESS in 0.1s
citationGates: running DocumentedApiEvaluationTest
citationGates: [5/14] DocumentedApiEvaluationTest SUCCESS in 9.9s
citationGates: running GateInputDeclarationTest
citationGates: [6/14] GateInputDeclarationTest SUCCESS in 0.1s
citationGates: running ImpossibilityClaimContractTest
citationGates: [7/14] ImpossibilityClaimContractTest SUCCESS in 10.7s
citationGates: running LanguagesCategoryTest
citationGates: [8/14] LanguagesCategoryTest SUCCESS in 1.1s
citationGates: running PersonalBoundaryTest
citationGates: [9/14] PersonalBoundaryTest SUCCESS in 6.0s
citationGates: running ProseNumeralContractTest
citationGates: [10/14] ProseNumeralContractTest SUCCESS in 5.5s
citationGates: running SmallLanguageAnatomyTest
citationGates: [11/14] SmallLanguageAnatomyTest SUCCESS in 0.7s
citationGates: running TranscriptStackAutoCompileTest
citationGates: [12/14] TranscriptStackAutoCompileTest SUCCESS in 4.3s
citationGates: running CloseNodeCitationDriftTest
citationGates: [13/14] CloseNodeCitationDriftTest SUCCESS in 0.0s
citationGates: running ServerSurfaceLedgerTest
citationGates: [14/14] ServerSurfaceLedgerTest SUCCESS in 3.6s

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

BUILD FAILED in 57s
30 actionable tasks: 7 executed, 23 up-to-date

  A generated artifact is stale after this merge.
  It was not your commit that broke it and no hook ran on the clone that did:
  .git/hooks is per-clone and Git never transports it.

  Reproduce:  ./gradlew checkKDocAttachment checkBoxIdioms citationGates
  The failing task names the artifact and the command that regenerates it.
  Regenerate and commit that, or `main` is red until someone else finds it.

  Set FIELD_SKIP_MERGE_GUARD=1 to skip this check.

merge_regen: a guard failed on the batch

land: 2026-10-13T16:17:11+02:00 red; asking the fixer (up to 15 min) about configurable-agent

What the fixer did on recent reds (for calibration):
- fixed: The branch rewrote the open_dlss_nr transcript box code without regenerating tools/completion-corpus/boxes/manifest.json; regenerating it with AFIELD_HARVEST_WRITE=1 makes checkCompletionCorpusFixtures pass.
- declined: AgentDemoRegistrationTest now reads .gitignore, and the only correct fix is declaring it as a citationGates input in build.gradle.kts, which the fixer may not edit, while an EXEMPT row would hide a real undeclared input.
- fixed: StereoTest.kt put @Test and the fun on one line, which made the KDoc guard drop the block above it; splitting the annotation onto its own line makes checkKDocAttachment pass.
- fixed: The branch added the GET /api/docs-search route without its row in server_surface_inventory.md; adding the row makes ServerSurfaceLedgerTest pass.
- fixed: GesturePaintReplayTest.kt put @Test and the fun on one line in two tests, dropping their KDoc; splitting the annotation onto its own line makes checkKDocAttachment pass.

This replay's checkout cannot build: the `gradle` command is not available, so verify by reading the check's source and rerunning any `tools/` Python check that covers it.
