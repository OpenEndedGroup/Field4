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
            "afield.docs.ImpossibilityClaimContractTest",
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
    }
}
