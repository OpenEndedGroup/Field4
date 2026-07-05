package afield.examples

import afield.CodeUseSemanticAnalyzeRequest
import afield.CodeUseTelemetryService
import java.nio.file.Files
import java.sql.DriverManager

/*
FIELD_TEST
id: examples.code-use.telemetry-store
runner: jvm-main
entrypoint: afield.examples.CodeUseTelemetrySmokeTest
tags: [smoke, code-use, telemetry, sqlite]
timeoutSec: 20
expect:
  exitCode: 0
  stdoutContains:
    - "CodeUseTelemetrySmokeTest ok"
*/
object CodeUseTelemetrySmokeTest {

    @JvmStatic
    fun main(args: Array<String>) {
        val tempDir = Files.createTempDirectory("afield-code-use-telemetry")
        val database = tempDir.resolve("field-code-use.sqlite")
        System.setProperty("afield.codeUse.path", database.toString())
        System.setProperty("afield.codeUse.semanticSidecar.url", "")

        val snippetId = CodeUseTelemetryService.newSnippetKey()
        val source = """
            val n = listOf(1, 2, 3).sum()
            println("telemetry smoke ${'$'}n")
            CodeUseTelemetryService.summary()
        """.trimIndent()
        val context = CodeUseTelemetryService.CodeUseContext(
            document = "code-use-smoke",
            documentPath = tempDir.resolve("code-use-smoke").toString(),
            boxUid = "box-code-use-smoke",
            boxName = "Code Use Smoke",
            property = "code",
            mode = "execute",
            strand = "__main__",
            parentIds = listOf("root"),
            preambleHash = CodeUseTelemetryService.sha256("preamble")
        )

        CodeUseTelemetryService.recordCompileResult(
            snippetId = snippetId,
            source = source,
            sourceName = "CodeUseTelemetrySmoke.kts",
            context = context.copy(mode = "compile"),
            success = true,
            diagnostics = listOf("INFO: clean"),
            elapsedMs = 1.25,
            classpathHash = "classpath-smoke"
        )
        CodeUseTelemetryService.recordEvaluationResult(
            snippetId = snippetId,
            context = context.copy(mode = "evaluate"),
            success = true,
            runtimeClass = "afield.examples.CodeUseTelemetrySmokeScript",
            returnType = "kotlin.Unit",
            elapsedMs = 0.75
        )
        CodeUseTelemetryService.recordLaunchLifecycle(
            snippetId = snippetId,
            source = source,
            context = context,
            lifecycle = "execute-start",
            watermark = 7
        )
        CodeUseTelemetryService.recordLaunchLifecycle(
            snippetId = snippetId,
            source = "",
            context = context,
            lifecycle = "execute-finished",
            watermark = 7,
            returnType = "kotlin.Unit"
        )
        val duplicateSiteSnippetId = CodeUseTelemetryService.newSnippetKey()
        CodeUseTelemetryService.recordCompileResult(
            snippetId = duplicateSiteSnippetId,
            source = source,
            sourceName = "CodeUseTelemetrySmokeDuplicateSite.kts",
            context = context.copy(mode = "compile:user-code"),
            success = true,
            diagnostics = emptyList(),
            elapsedMs = 0.1,
            classpathHash = "classpath-smoke"
        )
        val compileFailureId = CodeUseTelemetryService.newSnippetKey()
        CodeUseTelemetryService.recordCompileResult(
            snippetId = compileFailureId,
            source = "missingGrowthPoint(",
            sourceName = "CodeUseTelemetryCompileFailure.kts",
            context = context.copy(mode = "compile"),
            success = false,
            diagnostics = listOf("ERROR: unresolved reference: missingGrowthPoint"),
            elapsedMs = 0.25
        )
        val importOnlyId = CodeUseTelemetryService.newSnippetKey()
        val importOnlySource = """
            import afield.*
            import afield.nano.*
            import afield.stdlib.*
            import afield.four.synth.*
            import afield.stdlib.Vec2
            import afield.stdlib.Vec3
            import afield.stdlib.Vec4
            import afield.stdlib.Quat
            import afield.Box
            import afield.four.util.*
            import afield.graphics.*
        """.trimIndent()
        CodeUseTelemetryService.recordCompileResult(
            snippetId = importOnlyId,
            source = importOnlySource,
            sourceName = "CodeUseTelemetryImportsOnly.kts",
            context = context.copy(mode = "compile:imports-or-preamble"),
            success = true,
            diagnostics = emptyList(),
            elapsedMs = 0.1
        )
        val ambiguousSourceIndexId = CodeUseTelemetryService.newSnippetKey()
        CodeUseTelemetryService.recordCompileResult(
            snippetId = ambiguousSourceIndexId,
            source = "sin(0.0)",
            sourceName = "CodeUseTelemetryAmbiguousSourceIndex.kts",
            context = context.copy(mode = "compile:user-code"),
            success = true,
            diagnostics = emptyList(),
            elapsedMs = 0.1
        )
        val runtimeFailureId = CodeUseTelemetryService.newSnippetKey()
        CodeUseTelemetryService.recordLaunchLifecycle(
            snippetId = runtimeFailureId,
            source = """error("runtime growth point")""",
            context = context.copy(mode = "execute"),
            lifecycle = "execute-start",
            watermark = 9
        )
        CodeUseTelemetryService.recordLaunchLifecycle(
            snippetId = runtimeFailureId,
            source = "",
            context = context.copy(mode = "execute"),
            lifecycle = "execute-exception",
            watermark = 9,
            returnType = IllegalStateException::class.java.name,
            exception = IllegalStateException("runtime growth point")
        )

        val summary = waitForSummary {
            it.enabled && it.snippets >= 6 && it.compileEvents >= 5 && it.evalEvents >= 1 && it.launchEvents >= 4
        }
        require(summary.enabled) { summary.message }
        require(summary.droppedEvents == 0L) { "Unexpected dropped events: ${summary.droppedEvents}" }
        require(summary.droppedAnalysisJobs == 0L) { "Unexpected dropped analysis jobs: ${summary.droppedAnalysisJobs}" }
        require(summary.analyzerBackend == "source-index") { "Unexpected analyzer backend: ${summary.analyzerBackend}" }
        require(summary.analyzerMessage.isNotBlank()) { "Missing analyzer status message: $summary" }
        require(summary.sourceBlobs >= 1) { "Expected at least one source blob: $summary" }
        require(analysisRowCount(database.toString(), importOnlyId) == 0) {
            "Import-only/preamble snippet produced analyzer rows"
        }
        val ambiguousReasons = waitForUnresolvedReasons(database.toString(), ambiguousSourceIndexId)
        require(symbolUseRowCount(database.toString(), ambiguousSourceIndexId) == 0) {
            "Ambiguous source-index snippet produced resolved symbol rows"
        }
        require(ambiguousReasons.any { it.startsWith("ambiguous-source-index-match") }) {
            "Ambiguous source-index snippet did not produce an ambiguity row: $ambiguousReasons"
        }
        require(sourceIndexMatchRowCount(database.toString()) == 0) {
            "Legacy source-index-match confidence rows should not be written"
        }

        val snippet = CodeUseTelemetryService.recentSnippets(20).firstOrNull { it.id == snippetId }
            ?: error("Telemetry snippet was not queryable")
        require(snippet.sourceHash == CodeUseTelemetryService.sha256(source)) {
            "Unexpected source hash: ${snippet.sourceHash}"
        }
        require(snippet.document == "code-use-smoke") { snippet.document }
        require(snippet.boxName == "Code Use Smoke") { snippet.boxName }
        require(snippet.lastCompileSuccess == true) { "Compile result was not recorded: $snippet" }
        require(snippet.lastEvalSuccess == true) { "Eval result was not recorded: $snippet" }
        require(snippet.lastReturnType == "kotlin.Unit") { snippet.lastReturnType }

        val lifecycles = CodeUseTelemetryService.recentLaunches(20)
            .filter { it.snippetId == snippetId }
            .map { it.lifecycle }
            .toSet()
        require("execute-start" in lifecycles && "execute-finished" in lifecycles) {
            "Missing launch lifecycle records: $lifecycles"
        }
        val failures = CodeUseTelemetryService.failures(20)
        require(failures.any { it.snippetId == compileFailureId && it.kind == "compile" && "missingGrowthPoint" in it.diagnosticsJson }) {
            "Missing compile failure row: $failures"
        }
        require(failures.any { it.snippetId == runtimeFailureId && it.lifecycle == "execute-exception" && "runtime growth point" in it.exceptionJson }) {
            "Missing runtime exception failure row: $failures"
        }

        val telemetrySource = java.nio.file.Paths.get("src/jvmMain/kotlin/afield/CodeUseTelemetryService.kt")
        val summaryLine = Files.readAllLines(telemetrySource).indexOfFirst { it.contains("fun summary()") } + 1
        require(summaryLine > 0) { "Could not locate CodeUseTelemetryService.summary source line" }
        val (target, callers) = CodeUseTelemetryService.callersForSourceLine(
            sourcePath = telemetrySource.toString(),
            line = summaryLine,
            limit = 10
        )
        require(target?.symbolName?.contains("summary") == true) { "Unexpected source target: $target" }
        require(callers.any { it.snippetId == snippetId && it.lastReturnType == "kotlin.Unit" }) {
            "Missing source-line caller row: target=$target callers=$callers"
        }
        val resolvedSymbols = waitForSymbols {
            it.any { row -> row.fqName.contains("summary") && row.useCount >= 1 && row.uniqueSnippetCount >= 1 }
        }
        require(resolvedSymbols.any { it.fqName.contains("summary") && it.useCount >= 1 }) {
            "Analyzer did not populate summary symbol use: $resolvedSymbols"
        }

        val references = CodeUseTelemetryService.referencesForCode(
            """CodeUseTelemetryService.summary()""",
            limit = 5
        )
        require(references.any { it.symbolName.contains("summary") && it.sourceFile.endsWith("CodeUseTelemetryService.kt") }) {
            "Missing references-for-code row: $references"
        }

        val related = CodeUseTelemetryService.relatedSourceForCode(
            """CodeUseTelemetryService.summary()""",
            symbolLimit = 5,
            callersPerSymbol = 5,
            sourceRadiusLines = 8
        )
        require(
            related.any {
                it.symbolName.contains("summary") &&
                    it.sourceExcerpt.contains("fun summary()") &&
                    it.useCount >= 1 &&
                    it.peerCallers.any { caller -> caller.snippetId == snippetId }
            }
        ) {
            "Missing related source bundle row: $related"
        }

        val semantic = CodeUseTelemetryService.semanticAnalyzeCode(
            CodeUseSemanticAnalyzeRequest(
                code = """CodeUseTelemetryService.summary()""",
                sourceName = "CodeUseTelemetrySmokeAgent.kt",
                limit = 5,
                callersPerSymbol = 5,
                sourceRadiusLines = 8
            )
        )
        require(semantic.success) { "Semantic analyze fallback failed: $semantic" }
        require(semantic.backend == "source-index-fallback") { "Unexpected semantic backend: $semantic" }
        require(semantic.references.any { it.symbolName.contains("summary") }) {
            "Missing semantic references fallback row: $semantic"
        }
        require(semantic.relatedSources.any { it.sourceExcerpt.contains("fun summary()") }) {
            "Missing semantic related source fallback row: $semantic"
        }
        val ambiguousReferences = CodeUseTelemetryService.referencesForCode("""sin(0.0)""", limit = 20)
        require(ambiguousReferences.none { it.symbolName.substringAfterLast('.') == "sin" }) {
            "Ambiguous simple-name reference query should not produce guessed sin source refs: $ambiguousReferences"
        }

        val hot = CodeUseTelemetryService.hotSourceSymbols("all", 20)
        val hotSummary = hot.firstOrNull { it.symbolName.contains("summary") }
        require(hotSummary != null) { "Missing hot source symbol row: $hot" }
        require(hotSummary.confidence != "resolved" && "source-index" in hotSummary.confidence) {
            "Source-index hot row should not be reported as semantic resolved: $hotSummary"
        }
        require(hotSummary.useCount == 1L && hotSummary.uniqueSnippetCount >= 2L) {
            "Hot source symbols should count distinct code sites, not raw snippet observations: $hotSummary"
        }

        val early = CodeUseTelemetryService.earlyDocumentSymbols("code-use-smoke", earlyWindowMs = 60_000, limit = 20)
        require(early.any { it.symbolName.contains("summary") }) { "Missing early document symbol row: $early" }
        require(database.toFile().exists()) { "SQLite database was not created at $database" }

        println("CodeUseTelemetrySmokeTest ok")
    }

    private fun waitForSummary(predicate: (afield.CodeUseSummary) -> Boolean): afield.CodeUseSummary {
        val deadline = System.currentTimeMillis() + 5_000
        var latest = CodeUseTelemetryService.summary()
        while (System.currentTimeMillis() < deadline) {
            latest = CodeUseTelemetryService.summary()
            if (predicate(latest)) return latest
            Thread.sleep(50)
        }
        error("Timed out waiting for code-use telemetry writes: $latest")
    }

    private fun waitForSymbols(predicate: (List<afield.CodeUseSymbolRow>) -> Boolean): List<afield.CodeUseSymbolRow> {
        val deadline = System.currentTimeMillis() + 5_000
        var latest = CodeUseTelemetryService.symbols(50)
        while (System.currentTimeMillis() < deadline) {
            latest = CodeUseTelemetryService.symbols(50)
            if (predicate(latest)) return latest
            Thread.sleep(50)
        }
        error("Timed out waiting for code-use analyzer symbols: $latest")
    }

    private fun analysisRowCount(databasePath: String, snippetId: String): Int =
        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { conn ->
            conn.prepareStatement(
                """
                SELECT
                    (SELECT COUNT(*) FROM symbol_uses WHERE snippet_id = ?) +
                    (SELECT COUNT(*) FROM unresolved_uses WHERE snippet_id = ?) AS row_count
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, snippetId)
                ps.setString(2, snippetId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt("row_count") else 0
                }
            }
        }

    private fun symbolUseRowCount(databasePath: String, snippetId: String): Int =
        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { conn ->
            conn.prepareStatement("SELECT COUNT(*) AS row_count FROM symbol_uses WHERE snippet_id = ?").use { ps ->
                ps.setString(1, snippetId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt("row_count") else 0
                }
            }
        }

    private fun sourceIndexMatchRowCount(databasePath: String): Int =
        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { conn ->
            conn.prepareStatement("SELECT COUNT(*) AS row_count FROM symbol_uses WHERE confidence = 'source-index-match'").use { ps ->
                ps.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt("row_count") else 0
                }
            }
        }

    private fun waitForUnresolvedReasons(databasePath: String, snippetId: String): List<String> {
        val deadline = System.currentTimeMillis() + 5_000
        var latest = unresolvedReasons(databasePath, snippetId)
        while (System.currentTimeMillis() < deadline) {
            latest = unresolvedReasons(databasePath, snippetId)
            if (latest.isNotEmpty()) return latest
            Thread.sleep(50)
        }
        error("Timed out waiting for unresolved analyzer rows: $latest")
    }

    private fun unresolvedReasons(databasePath: String, snippetId: String): List<String> =
        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { conn ->
            conn.prepareStatement("SELECT reason FROM unresolved_uses WHERE snippet_id = ? ORDER BY id").use { ps ->
                ps.setString(1, snippetId)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(rs.getString("reason").orEmpty())
                    }
                }
            }
        }
}
