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

    private fun withServer(provider: String = "openai", block: (HttpServer, MutableList<JsonNode>, OpenAiIdeCodexBridge) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = CopyOnWriteArrayList<JsonNode>()
        val baseUrl = "http://127.0.0.1:${server.address.port}/v1"
        val client = OpenAIOkHttpClient.builder().apiKey("protocol-test").baseUrl(baseUrl).maxRetries(0).build()
        val bridge = OpenAiIdeCodexBridge(
            runtimeConfig = AiProviderRuntimeConfig(provider, "protocol-test", "test", baseUrl, AiModelSettings()),
            client = client,
            model = Ai4ModelDefaults.FLAGSHIP_MODEL
        )
        server.start()
        try { block(server, requests, bridge) } finally { server.stop(0); client.close() }
    }

    @Test
    fun aFreshProviderThreadReceivesTheSavedBoxConversation() = withServer("openrouter") { server, requests, bridge ->
        server.createContext("/v1/responses") { exchange ->
            requests.add(mapper.readTree(exchange.requestBody))
            val body = finalResponse("resp_resumed")
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        val history = "<<you>>\nKeep channel z delayed.\n<<ai>>\nThe delay is 1.25 seconds."
        val state = bridge.startThread("afield://study/source/code", "val duration = 3600.0" + AiBoxConversation.resumeContext(history))
        bridge.send(state, "Keep that delay and change the duration.")
        val sent = requests.single()["input"].toString()
        assertTrue(sent.contains("The delay is 1.25 seconds."))
        assertTrue(sent.contains("Keep that delay and change the duration."))
        assertFalse(requests.single().has("previous_response_id"), "Restart must not depend on a stale provider response ID")
    }

    @Test
    fun directOpenAiDefaultsKeepOtherProvidersAndExplicitChoices() {
        fun resolved(default: String, provider: String, settings: AiModelSettings = AiModelSettings()) =
            Ai4ModelDefaults.configuredModelForDefault(default, settings, provider)
        assertEquals("gpt-6-astra", resolved(Ai4ModelDefaults.FLAGSHIP_MODEL, "openai"))
        assertEquals("gpt-5.6-sol", resolved(Ai4ModelDefaults.FLAGSHIP_MODEL, "openai", AiModelSettings(flagshipModel = "gpt-5.6-sol")))
        assertEquals("openai/gpt-5.6-sol", resolved(Ai4ModelDefaults.FLAGSHIP_MODEL, "openrouter"))
        assertEquals("claude-opus-4-8", resolved(Ai4ModelDefaults.FLAGSHIP_MODEL, "anthropic"))
        assertEquals("gpt-6-luna", resolved(Ai4ModelDefaults.FAST_MODEL, "openai"))
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
        val progress = mutableListOf<OpenAiIdeCodexBridge.ProgressUpdate>()
        val result = bridge.send(state, "Inspect roof", maxToolRounds = 2, onProgress = { progress += it })
        assertEquals("gpt-6-astra", result.modelIdentity?.responseModel)
        assertEquals("openai", result.modelIdentity?.provider)
        assertTrue(progress.any { it.modelIdentity?.responseModel == "gpt-6-astra" })
        assertTrue(result.conversationAdditions.single().contains("<<model:"))
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

    /**
     * Where a TurnContext's parts go on the Responses wire: the stable instructions once, as a developer
     * message the conversation keeps (again only if they change); the small volatile ones in front of each
     * turn's user message; and never an `instructions` parameter, which measurably stopped conversations
     * sharing a prompt-cache prefix.
     */
    @Test
    fun stableInstructionsAreSentOncePerConversationAndVolatileOnesPerTurn() = withServer { server, requests, bridge ->
        server.createContext("/v1/responses") { exchange ->
            requests.add(mapper.readTree(exchange.requestBody))
            val body = finalResponse("resp_${requests.size}")
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        fun tc(stable: String) = TurnContext(listOf(
            ContextFragment("core.contract", stable),
            ContextFragment("guides", "\n\nVOLATILE-CATALOGUE", stability = Stability.VOLATILE),
        ), "the user's words")
        val state = OpenAiIdeCodexBridge.ThreadState("placement", "conv_test", "test", previousResponseId = "resp_old")
        val first = tc("STABLE-ONE")
        bridge.send(state, first.legacyPrompt(), turnContext = first)
        bridge.send(state, first.legacyPrompt(), turnContext = first)
        val changed = tc("STABLE-TWO")
        bridge.send(state, changed.legacyPrompt(), turnContext = changed)

        val inputs = requests.map { it["input"].toString() }
        assertTrue(inputs[0].contains("STABLE-ONE") && inputs[0].contains("\"developer\""), inputs[0])
        assertTrue(inputs[0].contains("the user's words") && inputs[0].contains("VOLATILE-CATALOGUE"))
        assertFalse(inputs[1].contains("STABLE-ONE"), "the conversation already holds them")
        assertTrue(inputs[1].contains("VOLATILE-CATALOGUE"))
        assertTrue(inputs[2].contains("STABLE-TWO") && inputs[2].contains("replace the earlier ones"), inputs[2])
        assertFalse(requests.any { it.has("instructions") || it.has("prompt_cache_key") })

        // Without a TurnContext the request is the one-string prompt, as before.
        bridge.send(state, first.legacyPrompt())
        assertFalse(requests.last().has("instructions"))
        assertTrue(requests.last()["input"].toString().contains("STABLE-ONE"))
    }

    @Test
    fun returnedModelIsPreservedWhenItDiffersFromRequestedModel() = withServer { server, _, bridge ->
        server.createContext("/v1/responses") { exchange ->
            val body = finalResponse("resp_reported").replace("gpt-6-astra", "gpt-4o")
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        val state = OpenAiIdeCodexBridge.ThreadState("identity", "conv_test", "test", previousResponseId = "resp_old")
        val result = bridge.send(state, "Identify response")
        assertEquals("gpt-6-astra", result.modelIdentity?.requestedModel)
        assertEquals("gpt-4o", result.modelIdentity?.responseModel)
        assertEquals("gpt-4o · OpenAI", result.modelIdentity?.label())
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
