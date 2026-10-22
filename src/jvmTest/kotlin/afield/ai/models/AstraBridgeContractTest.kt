package afield.ai.models

import afield.ai.tools.DescribeToolsRequest

import afield.ai.conversation.AiBoxConversation
import afield.ai.conversation.AiLiveConversation
import afield.ai.conversation.ContextFragment
import afield.ai.conversation.IdeChatHelpers
import afield.ai.conversation.ModelResponseFailure
import afield.ai.conversation.Stability
import afield.ai.conversation.TurnContext
import afield.ai.tools.CarriesToolImages
import afield.ai.tools.ToolImage
import afield.runtime.languages.KOTLIN_SCRIPT

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

    @Test
    fun localProviderKeepsHistoryAndImagesWithoutHostedConversations() = withServer("local") { server, requests, bridge ->
        server.createContext("/v1/responses") { exchange ->
            requests.add(mapper.readTree(exchange.requestBody))
            val body = finalResponse("resp_local_${requests.size}").toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val state = bridge.startThread("local.kt", "val marker = 731", instructions = "Inspect the supplied image.")
        assertTrue(state.conversationId.startsWith("local:"))
        bridge.send(state, "Inspect", imageDataUrls = listOf("data:image/png;base64,aW1hZ2U="), vectorStoreIds = listOf("hosted-only"))
        bridge.send(state, "Continue")
        val firstInput = requests.first()["input"].toString()
        assertTrue(firstInput.contains("input_image"))
        assertTrue(firstInput.contains("val marker = 731"))
        val resumed = requests.last()["input"].toString()
        assertTrue(resumed.contains("Inspected roof."))
        for (request in requests) {
            assertFalse(request.has("conversation"))
            assertFalse(request.has("previous_response_id"))
            assertFalse(request.has("reasoning"))
            assertFalse(request.has("context_management"))
            assertFalse(request["tools"].toString().contains("file_search"))
        }
    }

    private fun reply(exchange: com.sun.net.httpserver.HttpExchange, body: String, status: Int = 200, streaming: Boolean = false) {
        val bytes = if (streaming) ("data: " + mapper.writeValueAsString(mapOf(
            "type" to "response.completed", "sequence_number" to 0, "response" to mapper.readTree(body)
        )) + "\n\n").toByteArray() else body.toByteArray()
        exchange.responseHeaders.add("Content-Type", if (streaming) "text/event-stream" else "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private val contextError = """{"error":{"code":400,"type":"exceed_context_size_error",
        "message":"request (65833 tokens) exceeds the available context size (65536 tokens), try increasing it"}}"""

    @Test
    fun localContextOverflowShrinksHistoryAndKeepsTheCurrentImagePrompt() {
        for (streaming in listOf(false, true)) withServer("local") { server, requests, bridge ->
            server.createContext("/v1/responses") { exchange ->
                requests.add(mapper.readTree(exchange.requestBody))
                if (requests.size == 5) reply(exchange, contextError, 400)
                else reply(exchange, finalResponse("resp_${requests.size}"), streaming = requests.last().path("stream").asBoolean())
            }
            val state = bridge.startThread("context.kt", "SEED_FILE", "SEED_INSTRUCTIONS")
            repeat(4) { bridge.send(state, "OLD_EXCHANGE_$it " + "x".repeat(8_000)) }
            val progress = mutableListOf<OpenAiIdeCodexBridge.ProgressUpdate>()
            val result = bridge.send(state, "CURRENT_TASK", imageDataUrls = listOf("data:image/png;base64,Y3VycmVudA=="),
                onTextDelta = if (streaming) { _ -> } else null, onProgress = { progress += it })
            assertEquals("Inspected roof.", result.text.trim())
            assertEquals(6, requests.size)
            val original = requests[4]["input"].toString()
            val recovered = requests[5]["input"].toString()
            assertTrue(recovered.length < original.length)
            for (kept in listOf("SEED_FILE", "SEED_INSTRUCTIONS", "CURRENT_TASK", "data:image/png;base64,Y3VycmVudA==")) {
                assertTrue(recovered.contains(kept), "Recovery lost $kept")
            }
            assertTrue(recovered.contains("context window was exceeded"))
            assertFalse(recovered.contains("OLD_EXCHANGE_0"))
            assertEquals(requests[4]["tools"], requests[5]["tools"])
            assertEquals(requests[4]["max_output_tokens"], requests[5]["max_output_tokens"])
            assertTrue(progress.any { it.title.startsWith("Local model context full") })
            bridge.send(state, "NEXT_TASK")
            assertFalse(requests.last()["input"].toString().contains("OLD_EXCHANGE_0"), "Shortened history must persist")
        }
    }

    @Test
    fun localContextRecoveryDoesNotReplayToolsOrLosePendingCallOutputs() {
        for (forcedFinal in listOf(false, true)) withServer("local") { server, requests, bridge ->
            var executions = 0
            bridge.registerTool(InspectPanel::class.java) { _, _ -> executions++; mapOf("marker" to "COMPLETED_WORK") }
            server.createContext("/v1/responses") { exchange ->
                requests.add(mapper.readTree(exchange.requestBody))
                when (requests.size) {
                    2 -> reply(exchange, response("resp_call", """[{"type":"function_call","id":"fc_context",
                        "call_id":"call_context","name":"InspectPanel","arguments":"{}","status":"completed"}]"""))
                    3 -> reply(exchange, contextError, 400)
                    else -> reply(exchange, finalResponse("resp_${requests.size}"))
                }
            }
            val state = bridge.startThread("context-tools.kt", "", "Keep completed work")
            bridge.send(state, "OLD_TASK " + "x".repeat(12_000))
            bridge.send(state, "CURRENT_INSPECTION", maxToolRounds = if (forcedFinal) 0 else 3)
            assertEquals(4, requests.size)
            assertEquals(if (forcedFinal) 0 else 1, executions)
            fun pending(index: Int) = requests[index]["input"].filter { it["type"]?.asText() == "function_call_output" }
            assertEquals(pending(2), pending(3), "Recovery must retain the exact already-computed outputs")
            val recovered = requests[3]["input"]
            assertTrue(recovered.any { it["type"]?.asText() == "function_call" && it["call_id"]?.asText() == "call_context" })
            assertTrue(recovered.toString().contains("CURRENT_INSPECTION"))
            assertEquals(requests[2]["tool_choice"], requests[3]["tool_choice"])
            assertTrue(recovered.toString().length < requests[2]["input"].toString().length)
        }
    }

    @Test
    fun localContextRecoveryIsBoundedAndLeavesFailedHistoryIntact() = withServer("local") { server, requests, bridge ->
        var rejecting = false
        server.createContext("/v1/responses") { exchange ->
            requests.add(mapper.readTree(exchange.requestBody))
            if (rejecting) reply(exchange, contextError, 400) else reply(exchange, finalResponse("resp_${requests.size}"))
        }
        val state = bridge.startThread("bounded-context.kt", "", "Keep instructions")
        repeat(8) { bridge.send(state, "OLDER_$it " + "x".repeat(8_000)) }
        rejecting = true
        val failure = assertFails { bridge.send(state, "CURRENT_TASK") }
        assertTrue(failure.message.orEmpty().contains("after two history reductions"))
        assertEquals(11, requests.size, "One request plus at most two context retries")
        rejecting = false
        bridge.send(state, "Continue")
        assertTrue(requests.last()["input"].toString().contains("OLDER_0"), "Failed recovery must not discard saved history")
    }

    @Test
    fun unrelatedBadRequestsAndHostedContextErrorsAreNotRetried() {
        for (provider in listOf("local", "openrouter")) withServer(provider) { server, requests, bridge ->
            server.createContext("/v1/responses") { exchange ->
                requests.add(mapper.readTree(exchange.requestBody))
                if (requests.size == 1) reply(exchange, finalResponse("resp_warm"))
                else reply(exchange, if (provider == "local") """{"error":{"message":"unsupported tool schema","code":"invalid_request_error"}}""" else contextError, 400)
            }
            val state = bridge.startThread("no-retry.kt", "", "Keep instructions")
            bridge.send(state, "OLD_TASK " + "x".repeat(8_000))
            assertFails { bridge.send(state, "CURRENT_TASK") }
            assertEquals(2, requests.size)
        }
    }

    @Test
    fun localContextStreamErrorsRecoverOnlyBeforeVisibleText() {
        for (partial in listOf(false, true)) withServer("local") { server, requests, bridge ->
            server.createContext("/v1/responses") { exchange ->
                requests.add(mapper.readTree(exchange.requestBody))
                if (requests.size != 2) {
                    reply(exchange, finalResponse("resp_${requests.size}"), streaming = requests.last().path("stream").asBoolean())
                } else {
                    fun event(type: String, vararg fields: Pair<String, Any?>) = "data: " + mapper.writeValueAsString(
                        mapOf("type" to type, "sequence_number" to 0) + fields.toMap()) + "\n\n"
                    val prefix = if (!partial) "" else
                        event("response.created", "response" to mapper.readTree(response("resp_partial", "[]"))) +
                        event("response.output_item.added", "output_index" to 0, "item" to mapOf("type" to "message", "id" to "msg_partial", "role" to "assistant", "status" to "in_progress", "content" to emptyList<Any>())) +
                        event("response.content_part.added", "item_id" to "msg_partial", "output_index" to 0, "content_index" to 0, "part" to mapOf("type" to "output_text", "text" to "", "annotations" to emptyList<Any>())) +
                        event("response.output_text.delta", "item_id" to "msg_partial", "output_index" to 0, "content_index" to 0, "delta" to "VISIBLE_PARTIAL")
                    val body = prefix + event("error", "code" to "context_length_exceeded", "message" to "maximum context length exceeded", "param" to null)
                    exchange.responseHeaders.add("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
                    exchange.responseBody.use { it.write(body.toByteArray()) }
                }
            }
            val state = bridge.startThread("stream-context.kt", "", "Keep instructions")
            bridge.send(state, "OLD_TASK " + "x".repeat(8_000))
            val visible = StringBuilder()
            if (partial) {
                val failure = assertFails { bridge.send(state, "CURRENT_TASK", onTextDelta = { visible.append(it) }) }
                assertTrue(failure.message.orEmpty().contains("partial answer is retained"))
                assertEquals("VISIBLE_PARTIAL", visible.toString())
                assertEquals(2, requests.size, "Visible partial answers must not be replayed automatically")
            } else {
                assertEquals("Inspected roof.", bridge.send(state, "CURRENT_TASK", onTextDelta = { visible.append(it) }).text.trim())
                assertEquals(3, requests.size)
            }
        }
    }

    @Test
    fun localEmptyCompletedAnswersRecoverWithoutSavingTheDiscardedReasoning() {
        for (streaming in listOf(false, true)) withServer("local") { server, requests, bridge ->
            server.createContext("/v1/responses") { exchange ->
                requests.add(mapper.readTree(exchange.requestBody))
                val body = if (requests.size == 2) response("resp_reasoning_only", """[
                    {"type":"reasoning","id":"rs_exhausted","summary":[{"type":"summary_text","text":"DISCARDED_THINKING"}]}
                ]""") else finalResponse("resp_${requests.size}")
                reply(exchange, body, streaming = requests.last().path("stream").asBoolean())
            }
            val state = bridge.startThread("empty-answer.kt", "", "Keep instructions")
            bridge.send(state, "OLDER_TASK " + "x".repeat(8_000))
            val progress = mutableListOf<OpenAiIdeCodexBridge.ProgressUpdate>()
            val result = bridge.send(state, "CURRENT_TASK", onTextDelta = if (streaming) { _ -> } else null,
                onProgress = { progress += it })
            assertEquals("Inspected roof.", result.text.trim())
            assertEquals(3, requests.size)
            assertTrue(progress.any { it.title.startsWith("Local model returned no answer") })
            assertFalse(requests.last()["input"].toString().contains("DISCARDED_THINKING"))
            assertTrue(requests.last()["input"].toString().length < requests[1]["input"].toString().length)
        }
    }

    @Test
    fun localEmptyAnswerWithoutRoomToReduceFailsClearlyAndRefusalsAreNotRetried() {
        for (refusing in listOf(false, true)) withServer("local") { server, requests, bridge ->
            server.createContext("/v1/responses") { exchange ->
                requests.add(mapper.readTree(exchange.requestBody))
                reply(exchange, response("resp_empty", if (refusing) """[
                    {"type":"message","id":"msg_refused","role":"assistant","status":"completed",
                     "content":[{"type":"refusal","refusal":"Cannot assist with this request."}]}
                ]""" else "[]"))
            }
            val state = bridge.startThread("fresh-empty.kt", "", "")
            val failure = assertFails { bridge.send(state, "CURRENT_TASK") }
            assertTrue(failure.message.orEmpty().contains(if (refusing) "Cannot assist" else "no answer or tool call"))
            assertEquals(1, requests.size)
        }
    }

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
    fun localResponsesMayOmitTokenBreakdowns() {
        val variants = listOf(
            "",
            ",\"input_tokens_details\":{\"cached_tokens\":3}",
            ",\"input_tokens_details\":{},\"output_tokens_details\":{}",
            ",\"input_tokens_details\":{\"cached_tokens\":3},\"output_tokens_details\":{\"reasoning_tokens\":2}"
        )
        variants.forEach { details ->
            withServer("openrouter") { server, _, bridge ->
                server.createContext("/v1/responses") { exchange ->
                    val body = finalResponse("resp_local").trimEnd().dropLast(1) +
                        ",\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"total_tokens\":15$details}}"
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
                    exchange.responseBody.use { it.write(body.toByteArray()) }
                }
                val state = bridge.startThread("local.kt", "")
                assertEquals("Inspected roof.", bridge.send(state, "Inspect roof.").text)
            }
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
        // Nothing chains onto the forced final request, so its stop is input, which keeps the cached prefix.
        assertFalse(requests[1].has("instructions"))
        assertTrue(requests[1]["input"].single { it["role"]?.asText() == "developer" }.toString().contains("For this response only"))
        assertEquals("call_one", requests[1]["input"].single { it["type"]?.asText() == "function_call_output" }["call_id"].asText())
        assertFalse(requests[0]["tools"].any { it["type"].asText() == "tool_search" }, "Program catalogue must not also require provider discovery")
        assertTrue(requests[0]["tools"].any { it["name"]?.asText() == "DescribeToolsRequest" })
    }

    /**
     * The house-machine-11 shape (2026-10-05): a turn hits its tool-round cap, the forced final request
     * (tool_choice none, "without calling tools") also compacts the conversation, and the next two turns,
     * chained onto that response, answer "tool execution is still disabled here" with no calls. The next
     * turn must branch from the response that made the pending calls, carrying their "not run" outputs and
     * the forced answer as plain assistant text, and nothing the forced request was sent or reasoned.
     */
    @Test
    fun aCappedTurnCanResumeToolsWithoutCarryingTheStopInstruction() {
        for (provider in listOf("openai", "openrouter", "local")) withServer(provider) { server, requests, bridge ->
            var executions = 0
            bridge.registerTool(InspectPanel::class.java) { _, _ -> executions++; mapOf("checked" to true) }
            server.createContext("/v1/responses") { exchange ->
                requests.add(mapper.readTree(exchange.requestBody))
                val n = requests.size
                val body = when (n) {
                    1, 3 -> response("resp_$n", """[{
                        "type":"function_call","id":"fc_$n","call_id":"call_$n",
                        "name":"InspectPanel","arguments":"{}","status":"completed"
                    }]""")
                    2 -> response("resp_2", """[
                        {"type":"compaction","id":"cmp_forced","encrypted_content":"protocol-fixture"},
                        {"type":"reasoning","id":"rs_forced","summary":[{"type":"summary_text","text":"FORCED-REASONING: no tools now."}]},
                        {"type":"message","id":"msg_forced","role":"assistant","status":"completed",
                         "content":[{"type":"output_text","text":"Inspected roof.","annotations":[]}]}
                    ]""")
                    else -> finalResponse("resp_$n")
                }
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }
            fun items(i: Int) = requests[i]["input"].takeIf { it.isArray }?.toList().orEmpty()
            fun notRun(i: Int) = items(i).filter { it["type"]?.asText() == "function_call_output" && it["call_id"]?.asText() == "call_1" }

            val state = OpenAiIdeCodexBridge.ThreadState("cap", "conv_test", "test", previousResponseId = "resp_legacy")
            assertTrue(bridge.send(state, "Inspect", maxToolRounds = 0).hitToolRoundCap)
            assertEquals(0, executions, "An aborted call must never execute")
            assertEquals("none", requests[1]["tool_choice"].asText())
            val resumed = bridge.threadStateFromJson(bridge.threadStateToJson(state))
            assertTrue(resumed.lastTurnHitToolRoundCap, "The cap survives a persisted thread")
            assertEquals(listOf(OpenAiIdeCodexBridge.PendingToolCall("call_1", "InspectPanel")), resumed.cappedTurnContinuation?.pendingCalls)
            assertEquals("Inspected roof.", resumed.cappedTurnContinuation?.finalText?.trim())

            val result = bridge.send(resumed, "Continue", maxToolRounds = 2)
            assertFalse(result.hitToolRoundCap)
            assertEquals(1, executions)
            assertEquals(4, requests.size)
            assertFalse(resumed.lastTurnHitToolRoundCap)
            assertEquals(null, resumed.cappedTurnContinuation)

            // The next turn continues from before the forced final request, never from it.
            if (provider == "openai") assertEquals("resp_1", requests[2]["previous_response_id"].asText())
            for (i in listOf(2, 3)) {
                assertEquals("auto", requests[i]["tool_choice"].asText())
                assertFalse(requests[i].has("instructions"), "The turn-scoped stop must not be sent on subsequent ordinary requests")
                assertFalse(requests[i]["input"].toString().contains("For this response only"))
                assertFalse(requests[i]["input"].toString().contains("FORCED-REASONING"), "The forced request's reasoning stays on its branch")
                assertFalse(requests[i]["input"].toString().contains("cmp_forced"))
            }
            // Its input: the pending call's "not run" output, the forced answer as plain assistant text, then
            // the prompt, which says once that the tool budget has reset.
            val turn = items(2)
            val output = notRun(2).single()
            assertTrue(output["output"].asText().contains("max_tool_rounds_reached"))
            val answer = turn.single { it["role"]?.asText() == "assistant" }
            assertEquals("Inspected roof.", answer["content"].asText().trim())
            assertTrue(turn.indexOf(output) < turn.indexOf(answer) && turn.indexOf(answer) < turn.lastIndex)
            assertTrue(turn.last().toString().contains("That limit has reset") && turn.last().toString().contains("Continue"))
            assertFalse(requests[0]["input"].toString().contains("That limit has reset"))

            // The turn after that is an ordinary one: chained onto its predecessor, no note, nothing repeated.
            bridge.send(resumed, "And the walls", maxToolRounds = 2)
            assertEquals(5, requests.size)
            // Stateless history replays the previous turn's prompt, note and all, but adds no new one.
            assertEquals(if (provider == "openai") 1 else 2,
                requests[4]["input"].toString().split("That limit has reset").size)
            if (provider == "openai") {
                assertEquals("resp_4", requests[4]["previous_response_id"].asText())
                assertEquals("And the walls\n", requests[4]["input"].asText())
            } else assertEquals(1, notRun(4).size, "Stateless history holds the continuation once")
        }
    }

    @Test
    fun aTurnThatFailsBeforeItsFirstResponseSendsTheCappedContinuationAgain() = withServer { server, requests, bridge ->
        bridge.registerTool(InspectPanel::class.java) { _, _ -> error("must not run") }
        server.createContext("/v1/responses") { exchange ->
            requests.add(mapper.readTree(exchange.requestBody))
            val (status, body) = when (requests.size) {
                1 -> 200 to response("resp_pending", """[{"type":"function_call","id":"fc_test","call_id":"call_one","name":"InspectPanel","arguments":"{}","status":"completed"}]""")
                3 -> 400 to """{"error":{"message":"Bad request","type":"invalid_request_error","code":"invalid_request"}}"""
                else -> 200 to finalResponse("resp_${requests.size}")
            }
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        val state = OpenAiIdeCodexBridge.ThreadState("retry-cap", "conv_test", "test", previousResponseId = "resp_old")
        assertTrue(bridge.send(state, "Inspect", maxToolRounds = 0).hitToolRoundCap)
        assertFails { bridge.send(state, "Continue") }
        assertEquals("resp_pending", state.previousResponseId)
        assertTrue(state.lastTurnHitToolRoundCap)
        bridge.send(state, "Continue")
        assertEquals(requests[2]["input"], requests.last()["input"])
        assertEquals("resp_pending", requests.last()["previous_response_id"].asText())
        assertTrue(requests.last()["input"].any { it["type"]?.asText() == "function_call_output" && it["call_id"]?.asText() == "call_one" })
        assertFalse(state.lastTurnHitToolRoundCap)
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
