package afield.ai.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlin.test.*

class AiTurnLifecycleTest {
    data class Probe(val value: String = "test")
    private val mapper = ObjectMapper()

    @Test fun completionFailureAndCancellationHaveExactlyOneTerminalEvent() {
        for (mode in listOf("complete", "fail", "cancel")) {
            val root = Files.createTempDirectory("ai-turn-log")
            val property = "afield.ai4.toolIoLog.path"
            val prior = System.getProperty(property)
            System.setProperty(property, root.toString())
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val url = "http://127.0.0.1:${server.address.port}/v1"
            val client = OpenAIOkHttpClient.builder().apiKey("test").baseUrl(url).maxRetries(0).build()
            var requests = 0
            server.createContext("/v1/responses") { exchange ->
                exchange.requestBody.readAllBytes()
                requests++
                val output = if (requests == 1) """[{"type":"function_call","id":"fc_1","call_id":"call_1","name":"Probe","arguments":"{\"value\":\"test\"}","status":"completed"}]"""
                else """[{"type":"message","id":"msg_2","role":"assistant","status":"completed","content":[{"type":"output_text","text":"Done.","annotations":[]}]}]"""
                val body = if (mode == "fail") """{"error":{"message":"fixture failure","type":"invalid_request_error","code":"bad_request"}}"""
                else """{"id":"resp_$requests","object":"response","created_at":1,"model":"gpt-6-astra","status":"completed","output":$output,"parallel_tool_calls":false,"tool_choice":"auto","tools":[],"error":null,"incomplete_details":null,"instructions":null,"metadata":{},"temperature":1.0,"top_p":1.0}"""
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(if (mode == "fail") 400 else 200, body.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }
            server.start()
            try {
                val bridge = OpenAiIdeCodexBridge(
                    runtimeConfig = AiProviderRuntimeConfig("openai", "test", "fixture", url, AiModelSettings()),
                    client = client, model = "gpt-6-astra", log = SilentLog)
                bridge.registerTool(Probe::class.java) { _, _ ->
                    if (mode == "cancel") throw CancellationException("fixture cancelled")
                    "observed"
                }
                val progress = mutableListOf<OpenAiIdeCodexBridge.ProgressUpdate>()
                val state = OpenAiIdeCodexBridge.ThreadState("local", "conv", "fixture", previousResponseId = "previous")
                if (mode == "complete") bridge.send(state, "Inspect", onProgress = { progress += it })
                else assertFails { bridge.send(state, "Inspect", onProgress = { progress += it }) }
                val records = Files.list(root).use { stream ->
                    stream.toList().flatMap { Files.readAllLines(it).map(mapper::readTree) }
                }
                val terminal = records.filter { it["event"].asText() in setOf("session_end", "session_failed", "session_cancelled") }.single()
                assertEquals(when (mode) { "complete" -> "completed"; "fail" -> "failed"; else -> "cancelled" }, terminal["status"].asText())
                assertTrue(records.any { it["event"].asText() == "model_request" })
                assertTrue(records.any { it["event"].asText() == "model_request_end" })
                assertTrue(progress.any { it.title == "Waiting for model" })
                if (mode != "fail") assertTrue(progress.any { it.title == "Running Probe" })
                if (mode == "complete") {
                    assertTrue(progress.single { it.phase == "tool_result" }.body.isEmpty())
                    assertTrue(terminal.has("modelElapsedMs") && terminal.has("toolElapsedMs"))
                    assertTrue(records.single { it["event"].asText() == "tool_result" }.has("elapsedMs"))
                    assertEquals(2, requests)
                } else assertEquals(1, requests, "Failure/cancellation must not silently restart a turn")
            } finally {
                client.close(); server.stop(0)
                if (prior == null) System.clearProperty(property) else System.setProperty(property, prior)
                root.toFile().deleteRecursively()
            }
        }
    }
}
