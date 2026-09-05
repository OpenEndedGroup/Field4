package afield.ai.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFails

/** Local protocol fixtures only; live housing/image evidence is recorded separately. */
class AstraBridgeContractTest {
    data class InspectPanel(val panelId: String = "roof")

    private val mapper = ObjectMapper()

    private fun response(id: String, output: String): String = """{
        "id":"$id", "object":"response", "created_at":1,
        "model":"gpt-6-astra", "status":"completed", "output":$output,
        "parallel_tool_calls":false, "tool_choice":"auto", "tools":[],
        "error":null, "incomplete_details":null, "instructions":null,
        "metadata":{}, "temperature":1.0, "top_p":1.0
    }"""

    private fun finalResponse(id: String) = response(id, """[{
        "type":"message", "id":"msg_$id", "role":"assistant", "status":"completed",
        "content":[{"type":"output_text","text":"Inspected roof.","annotations":[]}]
    }]""")

    private fun withServer(block: (HttpServer, MutableList<JsonNode>, OpenAiIdeCodexBridge) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = CopyOnWriteArrayList<JsonNode>()
        val baseUrl = "http://127.0.0.1:${server.address.port}/v1"
        val client = OpenAIOkHttpClient.builder().apiKey("protocol-test").baseUrl(baseUrl).maxRetries(0).build()
        val bridge = OpenAiIdeCodexBridge(
            runtimeConfig = AiProviderRuntimeConfig("openai", "protocol-test", "test", baseUrl, AiModelSettings()),
            client = client,
            model = Ai4ModelDefaults.FLAGSHIP_MODEL
        )
        server.start()
        try { block(server, requests, bridge) } finally { server.stop(0); client.close() }
    }

    @Test
    fun directOpenAiDefaultsKeepOtherProvidersAndExplicitChoices() {
        fun resolved(default: String, provider: String, settings: AiModelSettings = AiModelSettings()) =
            Ai4ModelDefaults.configuredModelForDefault(default, settings, provider)
        assertEquals("gpt-6-astra", resolved(Ai4ModelDefaults.FLAGSHIP_MODEL, "openai"))
        assertEquals("gpt-5.6-sol", resolved(Ai4ModelDefaults.FLAGSHIP_MODEL, "openai", AiModelSettings(flagshipModel = "gpt-5.6-sol")))
        assertEquals("openai/gpt-5.6-sol", resolved(Ai4ModelDefaults.FLAGSHIP_MODEL, "openrouter"))
        assertEquals("claude-opus-4-8", resolved(Ai4ModelDefaults.FLAGSHIP_MODEL, "anthropic"))
        assertEquals("gpt-5.6-luna", resolved(Ai4ModelDefaults.FAST_MODEL, "openai"))
        assertEquals("gpt-5.4", resolved(Ai4ModelDefaults.FRONTIER_FALLBACK_MODEL, "openai"))
    }

    @Test
    fun toolImagesAndCallIdentityReachTheActualSdkRequest() = withServer { server, requests, bridge ->
        bridge.registerTool(InspectPanel::class.java) { _, _ ->
            object : CarriesToolImages {
                override val toolImages = listOf(ToolImage("data:image/png;base64,cHJvdG9jb2w=", "protocol fixture"))
            }
        }
        server.createContext("/v1/responses") { exchange ->
            requests.add(mapper.readTree(exchange.requestBody))
            val body = if (requests.size == 1) response("resp_tool", """[{
                "type":"function_call","id":"fc_distinct_from_call_id","call_id":"call_panel",
                "name":"InspectPanel","arguments":"{\"panelId\":\"roof\"}","status":"completed"
            }]""") else finalResponse("resp_${requests.size}")
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        val state = OpenAiIdeCodexBridge.ThreadState("test", "conv_test", "test", previousResponseId = "resp_old")
        bridge.send(state, "Inspect roof", maxToolRounds = 2)
        assertEquals(2, requests.size)
        requests.forEach { request ->
            assertEquals("gpt-6-astra", request["model"].asText())
            assertEquals("medium", request["reasoning"]["effort"].asText())
            listOf("temperature", "top_p", "top_logprobs").forEach { assertFalse(request.has(it)) }
        }
        val inputs = requests[1]["input"].toList()
        assertEquals("resp_tool", requests[1]["previous_response_id"].asText())
        assertEquals("call_panel", inputs.single { it["type"]?.asText() == "function_call_output" }["call_id"].asText())
        val image = inputs.flatMap { it["content"]?.toList().orEmpty() }
            .single { it["type"]?.asText() == "input_image" }
        assertEquals("data:image/png;base64,cHJvdG9jb2w=", image["image_url"].asText())
        assertEquals("high", image["detail"].asText())
        assertEquals("resp_2", state.previousResponseId)
        bridge.send(state, "Continue", maxToolRounds = 1)
        assertEquals("resp_2", requests[2]["previous_response_id"].asText())
    }

    @Test
    fun accessErrorPreservesCompletedTurnAndNeverSubstitutesModel() = withServer { server, requests, bridge ->
        server.createContext("/v1/responses") { exchange ->
            requests.add(mapper.readTree(exchange.requestBody))
            val body = """{"error":{"message":"No access to gpt-6-astra","type":"invalid_request_error","code":"model_not_found"}}"""
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(404, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        val state = OpenAiIdeCodexBridge.ThreadState("error", "conv_test", "test", previousResponseId = "resp_completed")
        val error = assertFails { bridge.send(state, "Inspect roof", onTextDelta = {}) }
        assertNotNull(error.message)
        assertTrue(requests.isNotEmpty())
        assertTrue(requests.all { it["model"].asText() == "gpt-6-astra" })
        assertEquals("resp_completed", state.previousResponseId)
    }
}
