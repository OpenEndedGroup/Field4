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
    @Test
    fun panelChatHelperHonoursExplicitBudgetAndPreservesDefault() {
        for ((requested, expected) in listOf(null to 24, 48 to 48, 100 to 64, 0 to 1)) {
            val bridge = ScriptedLlmBridge(listOf(ScriptedLlmBridge.Step.Final("done")))
            val helper = IdeChatHelpers(bridge)
            val state = bridge.startThread("budget", "", "")
            if (requested == null) helper.chatGeneral(state, "", KOTLIN_SCRIPT, "inspect")
            else helper.chatGeneral(state, "", KOTLIN_SCRIPT, "inspect", maxToolRounds = requested)
            assertEquals(expected, bridge.received.single().maxToolRounds)
        }
    }

    data class InspectPanel(val panelId: String = "roof")
    data class UnusedPanelTool(val unused: Int = 0)

    @Test
    fun discoveredToolsRemainCallableAcrossCompactionAndPersistedContinuation() = withServer(programs = false) { server, requests, bridge ->
        bridge.registerTool(InspectPanel::class.java) { _, _ -> mapOf("checked" to true) }
        bridge.registerTool(UnusedPanelTool::class.java) { _, _ -> error("not requested") }
        server.createContext("/v1/responses") { exchange ->
            requests.add(mapper.readTree(exchange.requestBody))
            val body = if (requests.size == 1) response("resp_discovered", """[
                {"type":"tool_search_output","id":"tso_test","execution":"server","status":"completed","call_id":null,
                 "tools":[{"type":"function","name":"InspectPanel","description":"Inspect a panel","parameters":{"type":"object"},"defer_loading":true}]},
                {"type":"function_call","id":"fc_test","call_id":"call_one","name":"InspectPanel","arguments":"{}","status":"completed"}
            ]""") else if (requests.size == 2) response("resp_compacted", """[
                {"type":"compaction","id":"cmp_test","encrypted_content":"protocol-fixture"},
                {"type":"message","id":"msg_test","role":"assistant","status":"completed","content":[{"type":"output_text","text":"Checked.","annotations":[]}]}
            ]""") else finalResponse("resp_continued")
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        val state = OpenAiIdeCodexBridge.ThreadState("discovery", "conv_test", "test", previousResponseId = "resp_seed")
        bridge.send(state, "Inspect the panel")
        assertEquals(setOf("InspectPanel"), state.loadedTools)
        val restored = bridge.threadStateFromJson(bridge.threadStateToJson(state))
        bridge.send(restored, "Continue after compaction")
        assertEquals(3, requests.size)
        fun deferred(index: Int, name: String) = requests[index]["tools"].first { it["name"]?.asText() == name }["defer_loading"]?.asBoolean() == true
        assertTrue(deferred(0, "InspectPanel"))
        assertFalse(deferred(1, "InspectPanel"))
        assertFalse(deferred(2, "InspectPanel"))
        assertTrue(deferred(2, "UnusedPanelTool"), "Only discovered schemas become eager")
        assertEquals("resp_compacted", requests[2]["previous_response_id"].asText())
        assertTrue(bridge.threadStateFromJson("""{"localThreadId":"old","conversationId":"old","fileKey":"old"}""").loadedTools.isEmpty())
    }

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

    private fun withServer(provider: String = "openai", programs: Boolean = true, block: (HttpServer, MutableList<JsonNode>, OpenAiIdeCodexBridge) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = CopyOnWriteArrayList<JsonNode>()
        val baseUrl = "http://127.0.0.1:${server.address.port}/v1"
        val client = OpenAIOkHttpClient.builder().apiKey("protocol-test").baseUrl(baseUrl).maxRetries(0).build()
        val priorPrograms = System.getProperty("afield.ai4.toolPrograms")
        System.setProperty("afield.ai4.toolPrograms", programs.toString())
        val bridge = OpenAiIdeCodexBridge(
            runtimeConfig = AiProviderRuntimeConfig(provider, "protocol-test", "test", baseUrl, AiModelSettings()),
            client = client,
            model = Ai4ModelDefaults.FLAGSHIP_MODEL
        )
        server.start()
        try { block(server, requests, bridge) } finally {
            server.stop(0); client.close()
            if (priorPrograms == null) System.clearProperty("afield.ai4.toolPrograms") else System.setProperty("afield.ai4.toolPrograms", priorPrograms)
        }
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
    @Test
    fun failedResponseBodyRetriesIdenticalToolOutputsWithoutRerunningTools() = withServer { server, requests, bridge ->
        var toolExecutions = 0
        bridge.registerTool(InspectPanel::class.java) { _, _ -> toolExecutions++; mapOf("volume" to 42) }
        server.createContext("/v1/responses") { exchange ->
            requests.add(mapper.readTree(exchange.requestBody))
            val body = when (requests.size) {
                1 -> response("resp_tool", """[{"type":"function_call","id":"fc_test","call_id":"call_one","name":"InspectPanel","arguments":"{}","status":"completed"}]""")
                2 -> response("resp_failed", "[]").replace("\"status\":\"completed\"", "\"status\":\"failed\"")
                    .replace("\"error\":null", "\"error\":{\"code\":\"rate_limit_exceeded\",\"message\":\"Please try again in 1ms\"}")
                else -> finalResponse("resp_recovered")
            }
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        val state = OpenAiIdeCodexBridge.ThreadState("retry", "conv_test", "test", previousResponseId = "resp_old")
        val progress = mutableListOf<OpenAiIdeCodexBridge.ProgressUpdate>()
        bridge.send(state, "Inspect", onProgress = { progress += it })
        assertEquals(3, requests.size)
        assertEquals(requests[1], requests[2])
        assertEquals(1, toolExecutions)
        assertEquals("resp_recovered", state.previousResponseId)
        assertTrue(progress.any { it.title.startsWith("Provider busy") })
    }

    @Test
    fun unchangedFileAndInstructionContextAreNotRepeatedButChangesAreSent() = withServer { server, requests, bridge ->
        server.createContext("/v1/responses") { exchange ->
            requests.add(mapper.readTree(exchange.requestBody))
            val body = finalResponse("resp_${requests.size}")
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        val state = OpenAiIdeCodexBridge.ThreadState("dedup", "conv_test", "test", initialFileText = "initial source", previousResponseId = "resp_seed")
        fun context(prompt: String, guidance: String) = TurnContext(
            listOf(ContextFragment("test.guidance", guidance, stability = Stability.STABLE)), prompt)
        bridge.send(state, "one", fileText = "initial source", turnContext = context("one", "stable guidance"))
        bridge.send(state, "two", fileText = "initial source", turnContext = context("two", "stable guidance"))
        bridge.send(state, "three", fileText = "changed source", turnContext = context("three", "changed guidance"))
        assertFalse(requests[0]["input"].toString().contains("initial source"))
        assertTrue(requests[0]["input"].toString().contains("stable guidance"))
        assertEquals("two\n", requests[1]["input"].asText())
        assertFalse(requests[1]["input"].toString().contains("stable guidance"))
        assertTrue(requests[2]["input"].toString().contains("changed source"))
        assertTrue(requests[2]["input"].toString().contains("changed guidance"))
        assertTrue(requests.none { it.has("instructions") }, "Ordinary turns preserve the shared cache prefix")
        requests.forEach {
            assertEquals("compaction", it["context_management"][0]["type"].asText())
            assertEquals(96000, it["context_management"][0]["compact_threshold"].asInt())
        }
    }

    @Test
    fun forcedFinalKeepsToolsStableAndDisablesCallingThem() = withServer { server, requests, bridge ->
        bridge.registerTool(InspectPanel::class.java) { _, _ -> error("must not run") }
        server.createContext("/v1/responses") { exchange ->
            requests.add(mapper.readTree(exchange.requestBody))
            val body = if (requests.size == 1) response("resp_pending", """[{"type":"function_call","id":"fc_test","call_id":"call_one","name":"InspectPanel","arguments":"{}","status":"completed"}]""") else finalResponse("resp_final")
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        val state = OpenAiIdeCodexBridge.ThreadState("final", "conv_test", "test", previousResponseId = "resp_old")
        bridge.send(state, "Inspect", maxToolRounds = 0)
        assertEquals(requests[0]["tools"], requests[1]["tools"])
        assertEquals("none", requests[1]["tool_choice"].asText())
        assertTrue(requests[1]["instructions"].asText().contains("For this response only"))
        assertFalse(requests[1]["input"].any { it["role"]?.asText() == "developer" })
        assertFalse(requests[0]["tools"].any { it["type"].asText() == "tool_search" }, "Program catalogue must not also require provider discovery")
        assertTrue(requests[0]["tools"].any { it["name"]?.asText() == "DescribeToolsRequest" })
    }

    @Test
    fun aCappedTurnCanResumeToolsWithoutCarryingTheStopInstruction() {
        for (provider in listOf("openai", "openrouter")) withServer(provider) { server, requests, bridge ->
            var executions = 0
            bridge.registerTool(InspectPanel::class.java) { _, _ -> executions++; mapOf("checked" to true) }
            server.createContext("/v1/responses") { exchange ->
                requests.add(mapper.readTree(exchange.requestBody))
                val n = requests.size
                val body = if (n == 1 || n == 3) response("resp_$n", """[{
                    "type":"function_call","id":"fc_$n","call_id":"call_$n",
                    "name":"InspectPanel","arguments":"{}","status":"completed"
                }]""") else finalResponse("resp_$n")
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }
            val state = OpenAiIdeCodexBridge.ThreadState("cap", "conv_test", "test", previousResponseId = "resp_legacy")
            assertTrue(bridge.send(state, "Inspect", maxToolRounds = 0).hitToolRoundCap)
            assertEquals(0, executions, "An aborted call must never execute")
            val resumed = bridge.threadStateFromJson(bridge.threadStateToJson(state))
            val result = bridge.send(resumed, "Continue", maxToolRounds = 2)
            assertFalse(result.hitToolRoundCap)
            assertEquals(1, executions)
            assertEquals(4, requests.size)
            for (i in listOf(2, 3)) {
                assertEquals("auto", requests[i]["tool_choice"].asText())
                assertFalse(requests[i].has("instructions"), "The turn-scoped stop must not be sent on subsequent ordinary requests")
                assertFalse(requests[i]["input"].toString().contains("For this response only"))
            }
            if (provider == "openai") assertEquals("resp_2", requests[2]["previous_response_id"].asText())
            else {
                val aborted = requests[2]["input"].single { it["call_id"]?.asText() == "call_1" && it["type"]?.asText() == "function_call_output" }
                assertTrue(aborted["output"].asText().contains("max_tool_rounds_reached"))
                assertTrue(requests[2]["input"].toString().contains("Inspected roof."))
            }
        }
    }

    @Test
    fun streamingHttp429And503RecoverWithoutFallingBackToNonStreaming() = withServer { server, requests, bridge ->
        val times = CopyOnWriteArrayList<Long>()
        server.createContext("/v1/responses") { exchange ->
            times += System.nanoTime()
            requests.add(mapper.readTree(exchange.requestBody))
            val body = if (requests.size == 1) {
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.responseHeaders.add("Retry-After", "1.5")
                """{"error":{"message":"Temporarily busy","type":"rate_limit_error","code":"rate_limit_exceeded"}}"""
            } else if (requests.size == 2) {
                exchange.responseHeaders.add("Content-Type", "application/json")
                """{"error":{"message":"Temporary server failure","type":"server_error","code":"internal_error"}}"""
            } else {
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                "data: " + mapper.writeValueAsString(mapOf("type" to "response.completed", "sequence_number" to 0, "response" to mapper.readTree(finalResponse("resp_stream")))) + "\n\n"
            }
            exchange.sendResponseHeaders(when (requests.size) { 1 -> 429; 2 -> 503; else -> 200 }, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        val state = OpenAiIdeCodexBridge.ThreadState("stream-retry", "conv_test", "test", previousResponseId = "resp_old")
        bridge.send(state, "Inspect", onTextDelta = {})
        assertEquals(3, requests.size)
        assertTrue(requests.all { it["stream"].asBoolean() })
        assertEquals(requests[0], requests[1])
        assertEquals(requests[1], requests[2])
        assertTrue((times[1] - times[0]) / 1_000_000 >= 1500)
        assertEquals("resp_stream", state.previousResponseId)
    }

    @Test
    fun midStreamRateLimitRetriesOnlyTheUnfinishedRequestWithVisibleBoundary() = withServer { server, requests, bridge ->
        var inspections = 0
        var nextTools = 0
        bridge.registerTool(InspectPanel::class.java) { _, _ -> inspections++; mapOf("checked" to true) }
        bridge.registerTool(UnusedPanelTool::class.java) { _, _ -> nextTools++; mapOf("continued" to true) }
        server.createContext("/v1/responses") { exchange ->
            requests.add(mapper.readTree(exchange.requestBody))
            fun event(type: String, vararg fields: Pair<String, Any?>) = "data: " + mapper.writeValueAsString(
                mapOf("type" to type, "sequence_number" to 0) + fields.toMap()) + "\n\n"
            fun completed(id: String, output: String) = event("response.completed", "response" to mapper.readTree(response(id, output)))
            val body = when (requests.size) {
                1 -> completed("resp_checked", """[{"type":"function_call","id":"fc_one","call_id":"call_one","name":"InspectPanel","arguments":"{}","status":"completed"}]""")
                2 -> event("response.created", "response" to mapper.readTree(response("resp_interrupted", "[]"))) +
                    event("response.output_item.added", "output_index" to 0, "item" to mapOf("type" to "message", "id" to "msg_partial", "role" to "assistant", "status" to "in_progress", "content" to emptyList<Any>())) +
                    event("response.content_part.added", "item_id" to "msg_partial", "output_index" to 0, "content_index" to 0, "part" to mapOf("type" to "output_text", "text" to "", "annotations" to emptyList<Any>())) +
                    event("response.output_text.delta", "item_id" to "msg_partial", "output_index" to 0, "content_index" to 0, "delta" to "I will now check") +
                    event("response.output_item.added", "output_index" to 1, "item" to mapOf("type" to "function_call", "id" to "fc_discard", "call_id" to "call_discard", "name" to "UnusedPanelTool", "arguments" to "", "status" to "in_progress")) +
                    event("error", "code" to "rate_limit_exceeded", "message" to "Rate limit reached. Please try again in 1ms.", "param" to null)
                3 -> completed("resp_next", """[{"type":"function_call","id":"fc_two","call_id":"call_two","name":"UnusedPanelTool","arguments":"{}","status":"completed"}]""")
                else -> event("response.completed", "response" to mapper.readTree(finalResponse("resp_done")))
            }
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        val transcript = AiLiveConversation()
        val progress = mutableListOf<OpenAiIdeCodexBridge.ProgressUpdate>()
        val state = OpenAiIdeCodexBridge.ThreadState("midstream", "conv_test", "test", previousResponseId = "resp_old")
        bridge.send(state, "Check panels", onTextDelta = transcript::text, onProgress = {
            progress += it
            transcript.progress(it)
        })
        assertEquals(4, requests.size)
        assertEquals(requests[1], requests[2], "Retry must preserve the exact completed tool output and previous response")
        assertEquals("resp_checked", requests[2]["previous_response_id"].asText())
        assertEquals(1, inspections, "Previously completed tool must not execute twice")
        assertEquals(1, nextTools, "Partial function call must be discarded, not executed")
        assertEquals("resp_done", state.previousResponseId)
        val rendered = transcript.render("", null, live = false)
        assertTrue(rendered.contains("I will now check"))
        assertTrue(rendered.contains("Answer interrupted by a temporary provider limit"))
        assertTrue(progress.any { it.title.startsWith("Provider busy") })
        assertFalse(requests.last().toString().contains("call_discard"))
    }

    @Test
    fun terminalStreamEventsKeepTheirReasonAndNeverDispatchUnfinishedTools() {
        for (kind in listOf("error", "response.failed", "response.incomplete")) withServer { server, requests, bridge ->
            var executions = 0
            bridge.registerTool(InspectPanel::class.java) { _, _ -> executions++; mapOf("checked" to true) }
            val reason = if (kind == "response.incomplete") "max_output_tokens" else "insufficient_quota"
            server.createContext("/v1/responses") { exchange ->
                requests.add(mapper.readTree(exchange.requestBody))
                val unfinished = mapper.readTree(response("resp_rejected", """[{"type":"function_call","id":"fc_unfinished","call_id":"call_unfinished","name":"InspectPanel","arguments":"{}","status":"in_progress"}]""")) as com.fasterxml.jackson.databind.node.ObjectNode
                unfinished.put("status", if (kind == "response.incomplete") "incomplete" else "failed")
                if (kind == "response.incomplete") unfinished.set<JsonNode>("incomplete_details", mapper.valueToTree(mapOf("reason" to reason)))
                else unfinished.set<JsonNode>("error", mapper.valueToTree(mapOf("code" to reason, "message" to "Account quota exhausted")))
                val event = if (kind == "error") mapOf("type" to kind, "sequence_number" to 0, "code" to reason, "message" to "Account quota exhausted", "param" to null)
                    else mapOf("type" to kind, "sequence_number" to 0, "response" to unfinished)
                val body = "data: ${mapper.writeValueAsString(event)}\n\n"
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }
            val state = OpenAiIdeCodexBridge.ThreadState(kind, "conv_test", "test", previousResponseId = "resp_retained")
            val failure = assertFails { bridge.send(state, "Check", onTextDelta = {}, onProgress = {}) }
            val causes = generateSequence(failure) { it.cause }.take(12).toList()
            assertTrue(causes.any { it is ModelResponseFailure && it.reason == reason }, "$kind must preserve its reason")
            assertEquals(1, requests.size, "$kind must not retry an incomplete answer or exhausted quota")
            assertEquals(0, executions)
            assertEquals("resp_retained", state.previousResponseId)
        }
    }

    @Test
    fun progressReportsActualToolsRatherThanGeneratedReasoningSummaries() = withServer { server, requests, bridge ->
        bridge.registerTool(InspectPanel::class.java) { _, _ -> mapOf("checked" to true) }
        server.createContext("/v1/responses") { exchange ->
            requests.add(mapper.readTree(exchange.requestBody))
            val body = if (requests.size == 1) response("resp_plan", """[
                {"type":"reasoning","id":"rs_test","summary":[{"type":"summary_text","text":"SPECULATIVE_STATUS_NOT_EXECUTION"}]},
                {"type":"function_call","id":"fc_one","call_id":"call_one","name":"InspectPanel","arguments":"{}","status":"completed"}
            ]""") else finalResponse("resp_done")
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        val progress = mutableListOf<OpenAiIdeCodexBridge.ProgressUpdate>()
        bridge.send(OpenAiIdeCodexBridge.ThreadState("progress", "conv_test", "test", previousResponseId = "resp_old"),
            "Inspect panel", onProgress = { progress += it })
        assertFalse(progress.any { "SPECULATIVE_STATUS" in it.title + it.body })
        assertTrue(progress.any { it.title == "Running InspectPanel" && it.phase == "tool" })
        assertTrue(progress.any { it.title.startsWith("InspectPanel finished") })
        assertTrue(progress.any { it.modelIdentity?.responseModel == "gpt-6-astra" })
    }

    @Test
    fun requestLedgerCountsRetriesAndUsageWithoutRecordingConversationContent() {
        val dir = java.nio.file.Files.createTempDirectory("ai-usage-test")
        val previous = System.getProperty("afield.ai.usageLog.path")
        System.setProperty("afield.ai.usageLog.path", dir.toString())
        try {
            for (streaming in listOf(false, true)) withServer { server, requests, bridge ->
                server.createContext("/v1/responses") { exchange ->
                    requests.add(mapper.readTree(exchange.requestBody))
                    val limited = requests.size == 1
                    val response = mapper.readTree(finalResponse("resp_ledger_$streaming")) as com.fasterxml.jackson.databind.node.ObjectNode
                    response.set<JsonNode>("usage", mapper.readTree("""{"input_tokens":123,"output_tokens":17,"total_tokens":140,
                        "input_tokens_details":{"cached_tokens":100},"output_tokens_details":{"reasoning_tokens":7}}"""))
                    val body = if (limited) """{"error":{"type":"rate_limit_exceeded","code":"rate_limit_exceeded","message":"Limit 500000, Used 499999, Requested 123. PRIVATE_PROMPT"}}"""
                        else if (streaming) "data: " + mapper.writeValueAsString(mapOf("type" to "response.completed", "sequence_number" to 0, "response" to response)) + "\n\n"
                        else response.toString()
                    exchange.responseHeaders.add("Content-Type", if (streaming && !limited) "text/event-stream" else "application/json")
                    exchange.responseHeaders.add("x-request-id", "req_ledger")
                    exchange.responseHeaders.add("retry-after", "0.001")
                    exchange.sendResponseHeaders(if (limited) 429 else 200, body.toByteArray().size.toLong())
                    exchange.responseBody.use { it.write(body.toByteArray()) }
                }
                bridge.send(OpenAiIdeCodexBridge.ThreadState("ledger", "conv_test", "afield://ledger-doc/box/code", previousResponseId = "resp_seed"),
                    "PRIVATE_PROMPT", onTextDelta = if (streaming) { _ -> } else null)
                assertEquals(2, requests.size)
            }
            val text = java.nio.file.Files.list(dir).use { files -> files.toList().joinToString("") { java.nio.file.Files.readString(it) } }
            val events = text.lineSequence().filter { it.isNotBlank() }.map { mapper.readTree(it) }.toList()
            assertEquals(4, events.count { it["event"].asText() == "attempt_started" })
            assertEquals(2, events.count { it["event"].asText() == "retry_wait" })
            val usage = events.filter { it["event"].asText() == "response_usage" }
            assertEquals(2, usage.size)
            assertEquals(246, usage.sumOf { it["inputTokens"].asInt() })
            assertEquals(200, usage.sumOf { it["cachedInputTokens"].asInt() })
            assertEquals(34, usage.sumOf { it["outputTokens"].asInt() })
            assertEquals(14, usage.sumOf { it["reasoningTokens"].asInt() })
            val failures = events.filter { it["event"].asText() == "attempt_finished" && !it["success"].asBoolean() }
            assertEquals(2, failures.size)
            assertTrue(failures.all { it["rateLimited"].asBoolean() && it["httpStatus"].asInt() == 429 })
            assertTrue(failures.all { it["requestedTokens"].asInt() == 123 && it["responseHeaders"]["x-request-id"].asText() == "req_ledger" })
            assertTrue(events.all { it["pid"].asLong() == ProcessHandle.current().pid() && it["checkout"].asText().isNotBlank() })
            assertFalse(text.contains("PRIVATE_PROMPT"))
            assertFalse(text.contains("protocol-test"))
        } finally {
            if (previous == null) System.clearProperty("afield.ai.usageLog.path") else System.setProperty("afield.ai.usageLog.path", previous)
            dir.toFile().deleteRecursively()
        }
    }

}
