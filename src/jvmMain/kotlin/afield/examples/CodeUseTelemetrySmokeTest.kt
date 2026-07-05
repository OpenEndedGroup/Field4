package afield.examples

import afield.CodeUseTelemetryService
import java.nio.file.Files

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
            it.enabled && it.snippets >= 3 && it.compileEvents >= 2 && it.evalEvents >= 1 && it.launchEvents >= 4
        }
        require(summary.enabled) { summary.message }
        require(summary.droppedEvents == 0L) { "Unexpected dropped events: ${summary.droppedEvents}" }
        require(summary.sourceBlobs >= 1) { "Expected at least one source blob: $summary" }

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

        val hot = CodeUseTelemetryService.hotSourceSymbols("all", 20)
        require(hot.any { it.symbolName.contains("summary") }) { "Missing hot source symbol row: $hot" }

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
}
