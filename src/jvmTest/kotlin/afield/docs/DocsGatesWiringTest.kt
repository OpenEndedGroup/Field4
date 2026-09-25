package afield.docs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Pins the build-graph edge that makes a green `docsGates` include the citation contracts. */
class DocsGatesWiringTest {
    @Test
    fun docsGatesRunsEveryCitationGuardThroughTheFocusedTask() {
        val root = CitationGuard.repoRoot()
        val build = File(root, "build.gradle.kts").readText()
        val expected = listOf(
            "afield.docs.CitationContractTest",
            "afield.docs.SmallLanguageAnatomyTest",
            "afield.graphics.CloseNodeCitationDriftTest",
            "afield.docs.DocsGatesWiringTest",
            "afield.docs.DocsFenceTreatmentTest",
            "afield.server.ServerSurfaceLedgerTest",
            "afield.docs.ProseNumeralContractTest",
            "afield.docs.DerivedArithmeticContractTest",
            "afield.docs.ImpossibilityClaimContractTest",
            "afield.docs.DocumentedApiEvaluationTest",
            "afield.docs.GateInputDeclarationTest",
        )

        expected.forEach { className ->
            assertTrue(
                "\"$className\"" in build,
                "citationGates no longer names $className",
            )
        }
        assertTrue(
            Regex("""tasks\.register\("docsGates"\)[\s\S]*?dependsOn\("checkAgentDemos"\)""").containsMatchIn(build),
            "docsGates no longer checks loadable agent-demo metadata drift",
        )
        assertTrue(
            Regex("""tasks\.register\(\"docsGates\"\)[\s\S]*?dependsOn\(citationGates\)""").containsMatchIn(build),
            "docsGates no longer depends on the focused citationGates task",
        )
        assertTrue(
            Regex("""tasks\.named\(\"check\"\)[\s\S]*?dependsOn\(citationGates\)""").containsMatchIn(build),
            "check no longer depends on citationGates, so docs-only edits can inherit a stale jvmTest result",
        )
        assertTrue(
            Regex("""tasks\.register\(\"certify\"\)[\s\S]*?dependsOn\(\"check\"\)""").containsMatchIn(build),
            "certify no longer runs the complete check graph",
        )
        assertTrue(
            Regex("""tasks\.register\(\"certify\"\)[\s\S]*?setContinueOnFailure\(true\)""").containsMatchIn(build),
            "certify no longer enables Gradle continuation and can hide independent gate failures",
        )
        assertTrue(
            Regex("""jvmTestTask\.configure\s*\{[\s\S]*?citationGuardTestClasses\.forEach\(::excludeTestsMatching\)""")
                .containsMatchIn(build),
            "citation contract tests are no longer excluded from broad jvmTest and would execute twice",
        )
        assertTrue(
            "isFailOnNoMatchingTests = true" in build,
            "a misspelled citation test filter could become a green zero-test run",
        )
        // Measured 2026-08-19: without this input declaration the task goes UP-TO-DATE after a
        // Markdown-only edit and every guard here "passes" without running. A guard that can pass
        // stale is worse than no guard, because it reads as evidence — so the declaration is pinned
        // rather than trusted to survive the next build-file edit.
        assertTrue(
            """withPropertyName("guardedDevDocs")""" in build,
            "citationGates no longer declares dev_docs/**.md as an input, so a Markdown-only edit can " +
                "inherit an UP-TO-DATE pass from every docs guard",
        )
        assertTrue(
            """withPropertyName("publishedDocs")""" in build,
            "citationGates no longer declares public/docs/**.md as an input",
        )
        assertTrue(
            """withPropertyName("publishedDocsReadByJvmTests")""" in build,
            "broad jvmTest can inherit an UP-TO-DATE result after a published Markdown edit",
        )
        assertTrue(
            """withPropertyName("nativeCaptureOrientationWitness")""" in build,
            "broad jvmTest can inherit an UP-TO-DATE result after its committed orientation witness changes",
        )
        // The interpreter half: root-owned gates must run `uv run --project . --frozen python`,
        // so they get the project-pinned interpreter rather than an ambient one.
        assertTrue(
            """"run", "--project", ".", "--frozen", "python"""" in build,
            "root-owned Python gates no longer use the project-pinned interpreter",
        )
        // The executable half, added 2026-09-25. This used to be pinned as the literal
        // `listOf("uv", …)`, which asserted the interpreter and the lookup together. They are
        // separate properties, and the second one was wrong: a bare "uv" resolves against the
        // PATH of whichever process started the Gradle daemon, so the pre-commit guard (which
        // uses the daemon) failed with an opaque "A problem occurred starting process 'command
        // 'uv''" on a commit whose gates had just passed under `--no-daemon`.
        assertTrue(
            "val rootPythonCommand = listOf(resolvedUvExecutable," in build,
            "rootPythonCommand no longer uses the resolved uv, so every Python gate depends " +
                "again on the PATH of whichever shell started the Gradle daemon",
        )
        assertTrue(
            Regex("""System\.getenv\("AFIELD_UV"\)""").containsMatchIn(build),
            "the uv resolver lost its explicit override, leaving no way to point a build at a " +
                "specific uv when probing finds the wrong one",
        )
        assertTrue(
            !Regex("""commandLine\(\s*"python3"""").containsMatchIn(build),
            "a Gradle gate has reintroduced a PATH-dependent bare python3",
        )
        assertTrue(
            !Regex("""listOf\(\s*"uv",\s*"run"""").containsMatchIn(build),
            "a Gradle gate has reintroduced a PATH-dependent bare uv",
        )
        // The same argument, for the two input kinds added on 2026-08-27 after each was measured
        // going UP-TO-DATE over a file its guard reads. Swift is on no JVM classpath;
        // `dev_docs/agents/transcripts/*.yaml` is under dev_docs but is not `**/*.md`, so the
        // guardedDevDocs tree above misses it and `DocumentedApiEvaluationTest`'s whole population
        // comes out of it.
        assertTrue(
            """withPropertyName("guardedNativeSources")""" in build,
            "citationGates no longer declares native/**.swift as an input, so editing a span name in " +
                "PoseLatency.swift can inherit an UP-TO-DATE pass from ProseNumeralContractTest",
        )
        assertTrue(
            """withPropertyName("guardedTranscriptCorpus")""" in build,
            "citationGates no longer declares the transcript yaml corpus as an input, so adding or " +
                "editing a transcript can inherit an UP-TO-DATE pass from DocumentedApiEvaluationTest",
        )
    }

    /**
     * Every `verification` task is reachable from a gate, or is declared manual on purpose.
     *
     * Written 2026-09-25, after a nightly run enumerated the registered tasks and ran the ones
     * nothing reaches. `checkGeneratedLinalg` was registered, grouped, described and wired to
     * nothing; `runDocsSearchRetrievalHarness` could not start a JVM at all, failing on the exact
     * error a comment 1,700 lines above it predicts verbatim. Both had been invisible for as long
     * as nobody typed their names.
     *
     * An unreachable gate is worse than no gate, because a reader counting coverage counts it.
     * So the rule is not "wire everything" — several of these legitimately need a GPU, a model
     * download, or a human's eye — it is that the manual ones are *declared* manual here. Adding
     * a `verification` task now forces a choice between wiring it in and saying why not.
     */
    @Test
    fun everyVerificationTaskIsReachableOrDeclaredManual() {
        val root = CitationGuard.repoRoot()
        val build = File(root, "build.gradle.kts").readText()

        // Deliberately not reachable from `check`/`docsGates`, each for a reason you can check
        // against the task's own `description` in build.gradle.kts.
        val manual = mapOf(
            "certify" to "the continue-on-failure wrapper around check; running it from check would recurse",
            "warningGates" to "forces a full recompile for a warning census; check already compiles once",
            "completionSidecarCompileForWarnings" to "an implementation detail of warningGates",
            "recordTestTreeBaseline" to "a baseline recorder the tree-write guard invokes, not an assertion",
            "runDocsSearchRetrievalHarness" to "prints ranked hits for a human to eyeball; asserts nothing",
            "runKratosStudy" to "sweeps a design space against a real worker; minutes, and not an assertion",
            "runGpuSkinningEvidence" to "renders evidence artifacts through the real Vulkan renderer; needs a display",
            "runSkinningEvidence" to "regenerates a glTF fixture and writes deformation evidence; not an assertion",
            "runGpuSkinningSmoke" to "covered as four.gpu-skinning.smoke in the FIELD_TEST catalog",
            "runPersonalLayerSmokeTest" to "covered as core.personal-layer.smoke in the FIELD_TEST catalog",
            "runSidecarPathParseSmokeTest" to "covered as examples.file-watch.sidecar-path-parse in the catalog",
            "runDocsPanelCodeLinksSmokeTest" to "covered as examples.docs-panel-code-links.smoke in the catalog",
            "smokeCeres" to "needs the Ceres redistributable built first; run it with buildCeresRedistributable",
            "smokeManifold" to "needs a system Manifold (brew install manifold)",
            "fieldAudioAnalysisTest" to "opt-in; installs librosa and Basic Pitch over the network",
            "fieldAudioModelSmoke" to "opt-in; requires FIELD_AUDIO_ALLOW_MODEL_DOWNLOAD=1 and a large checkpoint",
            "fieldAudioAudioCraftImportTest" to "opt-in AudioCraft environment probe",
            "fieldAudioCoreTest" to "needs the services/field-audio-ai submodule checked out",
            "fieldAudioDocsCheck" to "needs the services/field-audio-ai submodule checked out",
            "fieldAudioContractCheck" to "needs the services/field-audio-ai submodule checked out",
            "docsGates" to "a top-level aggregate; it is the thing you run, not something run for you",
            "jvmMeasurements" to
                "opt-in measurement fixtures, excluded from the deterministic check task by contract",
        )

        val registered = Regex("""tasks\.register(?:<[A-Za-z.]+>)?\("([A-Za-z][A-Za-z0-9]*)"\)\s*\{([\s\S]*?)\n\}""")
            .findAll(build)
            .filter { it.groupValues[2].contains("group = \"verification\"") }
            .map { it.groupValues[1] }
            .toSet()

        // `finalizedBy` is wiring too: `checkTestTreeWrites` is reached that way from the test
        // task it audits, and treating only `dependsOn` as reachability reported it as an orphan.
        val reachable = registered.filter { name ->
            Regex("""(?:dependsOn|finalizedBy)\((?:"|)\Q$name\E(?:"|)\)""").containsMatchIn(build)
        }.toSet()

        val unexplained = (registered - reachable - manual.keys).sorted()
        assertTrue(
            unexplained.isEmpty(),
            "these `verification` tasks are reachable from no gate and are not declared manual in " +
                "this test: $unexplained. Wire each into check/docsGates, or add it to `manual` " +
                "above with the reason. A gate nobody runs reads as coverage and is not.",
        )

        val stale = manual.keys.filterNot { it in registered }.sorted()
        assertTrue(
            stale.isEmpty(),
            "these tasks are declared manual here but are no longer registered `verification` " +
                "tasks: $stale. Remove them, so the list keeps describing the build.",
        )
    }

}
