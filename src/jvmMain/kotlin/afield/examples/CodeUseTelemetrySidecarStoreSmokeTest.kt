package afield.examples

import afield.CodeUseSemanticAnalyzeResult
import afield.CodeUseSemanticUseRow
import afield.CodeUseTelemetryService
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

/*
FIELD_TEST
id: examples.code-use.telemetry-sidecar-store
runner: jvm-main
entrypoint: afield.examples.CodeUseTelemetrySidecarStoreSmokeTest
tags: [smoke, code-use, telemetry, sidecar, sqlite]
timeoutSec: 20
expect:
  exitCode: 0
  stdoutContains:
    - "CodeUseTelemetrySidecarStoreSmokeTest ok"
*/
object CodeUseTelemetrySidecarStoreSmokeTest {
    private val json = Json { encodeDefaults = true }

    @JvmStatic
    fun main(args: Array<String>) {
        val tempDir = Files.createTempDirectory("afield-code-use-sidecar-store")
        val database = tempDir.resolve("field-code-use.sqlite")
        val analyzeCalls = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/health") { exchange ->
            exchange.respondJson("""{"ok":true,"backend":"k2-standalone","workspaceRoot":"${System.getProperty("user.dir")}"}""")
        }
        server.createContext("/analyze") { exchange ->
            analyzeCalls.incrementAndGet()
            exchange.requestBody.use { it.readBytes() }
            exchange.respondJson(
                json.encodeToString(
                    CodeUseSemanticAnalyzeResult(
                        success = true,
                        backend = "k2-standalone",
                        message = "fake sidecar resolved one use",
                        sourceName = "SidecarStoreSmoke.kts",
                        resolvedUses = listOf(
                            CodeUseSemanticUseRow(
                                symbolName = "afield.FakeK2.hot",
                                callableId = "afield.FakeK2.hot",
                                kind = "fun",
                                packageName = "afield",
                                sourceFile = "src/jvmMain/kotlin/afield/FakeK2.kt",
                                line = 12,
                                useKind = "call",
                                snippetLine = 1,
                                snippetColumn = 1,
                                confidence = "k2-resolved",
                                evidence = "FakeK2.hot()"
                            )
                        )
                    )
                )
            )
        }
        server.start()
        try {
            System.setProperty("afield.codeUse.path", database.toString())
            System.setProperty("afield.codeUse.semanticSidecar.url", "http://127.0.0.1:${server.address.port}")

            val context = CodeUseTelemetryService.CodeUseContext(
                document = "code-use-sidecar-smoke",
                documentPath = tempDir.resolve("code-use-sidecar-smoke").toString(),
                boxUid = "box-code-use-sidecar-smoke",
                boxName = "Code Use Sidecar Smoke",
                property = "code",
                mode = "compile:user-code",
                strand = "__main__"
            )
            val snippetId = CodeUseTelemetryService.newSnippetKey()
            CodeUseTelemetryService.recordCompileResult(
                snippetId = snippetId,
                source = "FakeK2.hot()",
                sourceName = "SidecarStoreSmoke.kts",
                context = context,
                success = true,
                diagnostics = emptyList(),
                elapsedMs = 0.1,
                classpathHash = "sidecar-smoke"
            )

            val summary = waitForSummary {
                it.enabled && it.compileEvents >= 1 && it.analyzerBackend == "k2-sidecar"
            }
            require(summary.analyzerBackend == "k2-sidecar") { "Store analyzer did not report sidecar backend: $summary" }
            waitForK2Rows(database.toString(), snippetId)
            val early = CodeUseTelemetryService.earlyDocumentSymbols("code-use-sidecar-smoke", earlyWindowMs = 60_000, limit = 10)
            require(
                early.any {
                    it.symbolName == "afield.FakeK2.hot" &&
                        it.confidence == "k2-resolved" &&
                        it.earlyUseCount == 1L
                }
            ) {
                "Early-document symbols should use persisted k2-resolved edges: $early"
            }
            require(analyzeCalls.get() >= 1) { "Fake sidecar was not called" }
            println("CodeUseTelemetrySidecarStoreSmokeTest ok")
        } finally {
            server.stop(0)
        }
    }

    private fun waitForSummary(predicate: (afield.CodeUseSummary) -> Boolean): afield.CodeUseSummary {
        val deadline = System.currentTimeMillis() + 5_000
        var latest = CodeUseTelemetryService.summary()
        while (System.currentTimeMillis() < deadline) {
            latest = CodeUseTelemetryService.summary()
            if (predicate(latest)) return latest
            Thread.sleep(50)
        }
        error("Timed out waiting for code-use telemetry summary: $latest")
    }

    private fun waitForK2Rows(databasePath: String, snippetId: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val rows = DriverManager.getConnection("jdbc:sqlite:$databasePath").use { conn ->
                conn.prepareStatement(
                    """
                    SELECT sy.fq_name, sy.callable_id, su.confidence
                    FROM symbol_uses su
                    JOIN symbols sy ON sy.id = su.symbol_id
                    WHERE su.snippet_id = ?
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, snippetId)
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    Triple(
                                        rs.getString("fq_name").orEmpty(),
                                        rs.getString("callable_id").orEmpty(),
                                        rs.getString("confidence").orEmpty()
                                    )
                                )
                            }
                        }
                    }
                }
            }
            if (rows.any { it.first == "afield.FakeK2.hot" && it.second == "afield.FakeK2.hot" && it.third == "k2-resolved" }) {
                return
            }
            Thread.sleep(50)
        }
        error("Timed out waiting for k2-resolved symbol_use rows")
    }

    private fun HttpExchange.respondJson(body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
