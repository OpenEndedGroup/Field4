package afield.ai.tools

import afield.runtime.completion.KotlinDefinitionRecord
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs [WorkspaceSymbolSearchEval] on this checkout and writes its table to
 * `build/reports/ai4/workspace_symbol_search_eval.txt` (also printed to the test's stdout):
 *
 *     ./gradlew jvmTest -x checkTestTreeWrites --tests 'afield.ai.tools.WorkspaceSymbolSearchEvalTest'
 *     cat build/reports/ai4/workspace_symbol_search_eval.txt
 *
 * Refresh the cases from the logs with `uv run --project . python tools/ai4_symbol_search_cases.py`.
 */
class WorkspaceSymbolSearchEvalTest {
    @Test
    fun theShippedRankerDoesNotFallBelowTheLegacyOne() {
        val root = Path.of("").toAbsolutePath()
        val index = WorkspaceSymbolSearchEval.buildIndex(root)
        val labeller = WorkspaceSymbolSearchEval.Labeller(index)
        val all = WorkspaceSymbolSearchEval.loadCases(root.resolve(WorkspaceSymbolSearchEval.FIXTURE)).map(labeller::label)
        val runs = WorkspaceSymbolSearchEval.standardRuns(index, all)
        val report = WorkspaceSymbolSearchEval.report(all, runs, "legacy" to "current",
            *runs.map { it.name }.filter { it.startsWith("- ") }.map { it to "current" }.toTypedArray())
        val spotlight = buildString {
            appendLine()
            appendLine("The searches that prompted this work (2026-11-06), top five now, with any note:")
            listOf("Vector2f.rotate", "quat", "FLine text", "clearOutput", "displaySize", "runChildren").forEach { q ->
                val r = rankWorkspaceSymbols(index, q, 5)
                appendLine("  \"$q\": ${r.definitions.joinToString { d -> d.name + (d.visibility?.let { " ($it)" } ?: "") }}")
                if (r.note.isNotBlank()) appendLine("      note: ${r.note}")
            }
        }
        val out = root.resolve(WorkspaceSymbolSearchEval.REPORT)
        Files.createDirectories(out.parent)
        Files.writeString(out, report + spotlight)
        println(report + spotlight)

        val labelled = all.indices.filter { all[it].labels.isNotEmpty() }
        assertTrue(labelled.size >= 80, "only ${labelled.size} labelled cases: the fixture or the labeller broke")
        fun mrr(name: String) = WorkspaceSymbolSearchEval.score(labelled.map { i -> runs.first { it.name == name }.ranks[i] }).mrr
        val legacy = mrr("legacy")
        val current = mrr("current")
        assertTrue(current >= legacy, "shipped ranker MRR $current fell below legacy $legacy")
        // The figure measured when these terms landed (2026-11-06), less slack for source drift.
        assertTrue(current >= MEASURED_CURRENT_MRR - 0.05, "shipped ranker MRR $current is far below the measured $MEASURED_CURRENT_MRR")
    }

    /** [SymbolRankingOptions.LEGACY] must be the frozen ranker, or "legacy" in the table means nothing. */
    @Test
    fun legacyOptionsReproduceTheFrozenRanker() {
        val root = Path.of("").toAbsolutePath()
        val index = WorkspaceSymbolSearchEval.buildIndex(root)
        val lines = SourceLines(index.root)
        val queries = WorkspaceSymbolSearchEval.loadCases(root.resolve(WorkspaceSymbolSearchEval.FIXTURE)).map { it.query }.distinct().take(60)
        fun names(defs: List<KotlinDefinitionRecord>) = defs.map { "${it.filePath}:${it.line}:${it.name}" }
        queries.forEach { q ->
            assertEquals(
                names(LegacyWorkspaceSymbolRanker.rank(index.definitions, q, 10, lines::line)),
                names(rankWorkspaceSymbols(index, q, 10, SymbolRankingOptions.LEGACY, lines).definitions),
                "legacy options diverge for \"$q\""
            )
        }
    }

    companion object {
        const val MEASURED_CURRENT_MRR = 0.564
    }
}
