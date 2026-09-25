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
}
