package afield.ai.core

import afield.AiAssist
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.*

/**
 * A tool whose result suspends the turn ([SuspendsTurn]) ends it on every bridge: the round's calls run, no
 * further model request is made, and the result carries the suspension. The next turn opens with that round's
 * outputs, ahead of its prompt, so the provider sees every call answered before the continuation.
 */
class TurnSuspensionBridgeTest {
    data class Probe(val value: String = "test")
    data class Awaiting(val success: Boolean = true, val message: String = "Your turn ends here.",
        @get:JsonIgnore override val suspension: TurnSuspension? = TurnSuspension(listOf(AwaitedBuild("box", "study", "job-1")), 0L)) : SuspendsTurn

    private val mapper = ObjectMapper()
    private fun pending(text: String) = AiTurnInbox.Pending(text, text, emptyList(), AiAssist(uid = "b", prompt = "", extraPrompt = text, kind = "general"))

    private fun openAiBridge(provider: String, block: (OpenAiIdeCodexBridge, MutableList<JsonNode>) -> Unit) {
        val bodies = mutableListOf<JsonNode>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val url = "http://127.0.0.1:${server.address.port}/v1"
        server.createContext("/v1/responses") { exchange ->
            bodies.add(mapper.readTree(exchange.requestBody.readAllBytes()))
            val output = if (bodies.size == 1) """[{"type":"message","id":"msg_0","role":"assistant","status":"completed","content":[{"type":"output_text","text":"Started the bake; waiting for it.","annotations":[]}]},
                {"type":"function_call","id":"fc_1","call_id":"call_1","name":"Awaiting","arguments":"{}","status":"completed"},
                {"type":"function_call","id":"fc_2","call_id":"call_2","name":"Probe","arguments":"{\"value\":\"x\"}","status":"completed"}]"""
                else """[{"type":"message","id":"msg_2","role":"assistant","status":"completed","content":[{"type":"output_text","text":"The bake succeeded.","annotations":[]}]}]"""
            val body = """{"id":"resp_${bodies.size}","object":"response","created_at":1,"model":"gpt-6-astra","status":"completed","output":$output,"parallel_tool_calls":true,"tool_choice":"auto","tools":[],"error":null,"incomplete_details":null,"instructions":null,"metadata":{},"temperature":1.0,"top_p":1.0}"""
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        val client = OpenAIOkHttpClient.builder().apiKey("test").baseUrl(url).maxRetries(0).build()
        try {
            val bridge = OpenAiIdeCodexBridge(runtimeConfig = AiProviderRuntimeConfig(provider, "test", "fixture", url, AiModelSettings()),
                client = client, model = "gpt-6-astra", log = SilentLog)
            bridge.registerTool(Awaiting::class.java) { _, _ -> Awaiting() }
            bridge.registerTool(Probe::class.java) { ctx, _ -> ctx.inbox?.offer(pending("how is it going?")); "probed" }
            block(bridge, bodies)
        } finally { client.close(); server.stop(0) }
    }

    @Test fun openAiEndsTheTurnAfterTheSuspendingRoundAndAnswersItsCallsNextTurn() = openAiBridge("openai") { bridge, bodies ->
        val inbox = AiTurnInbox()
        val state = OpenAiIdeCodexBridge.ThreadState("local", "conv", "fixture", previousResponseId = "previous")
        val first = bridge.send(state, "Bake the mesh", ctx = ToolExecutionContext(inbox = inbox))
        assertEquals(1, bodies.size, "no request after the round that suspended the turn")
        assertEquals(listOf("job-1"), first.suspension!!.jobs.map { it.jobId })
        assertEquals("Started the bake; waiting for it.", first.text)
        assertEquals(1, first.rounds)
        assertFalse(first.hitToolRoundCap)
        assertEquals(listOf("call_1", "call_2"), state.suspendedRound!!.outputs.map { it.callId })
        assertEquals("resp_1", state.previousResponseId, "the next turn chains onto the response that made the calls")
        assertEquals(listOf("how is it going?"), inbox.close().map { it.shown }, "a message sent meanwhile is left for a turn of its own")

        val second = bridge.send(state, "The bake has finished.", ctx = ToolExecutionContext())
        assertEquals("The bake succeeded.", second.text)
        assertNull(second.suspension)
        val request = bodies[1]
        assertEquals("resp_1", request["previous_response_id"].asText())
        val input = request["input"]
        assertEquals(listOf("function_call_output", "function_call_output"), input.take(2).map { it["type"].asText() })
        assertEquals(listOf("call_1", "call_2"), input.take(2).map { it["call_id"].asText() })
        assertTrue("Your turn ends here." in input[0]["output"].asText())
        assertEquals("probed", mapper.readTree(input[1]["output"].asText()).asText())
        assertEquals("user", input.last()["role"].asText())
        assertTrue("The bake has finished." in input.last()["content"].toString())
        assertNull(state.suspendedRound, "sent once, then cleared")
    }

    @Test fun theStatelessPathKeepsTheSuspendedRoundInItsOwnHistory() = openAiBridge("openrouter") { bridge, bodies ->
        val state = bridge.startThread("fixture", "")
        assertNotNull(bridge.send(state, "Bake the mesh").suspension)
        assertEquals(1, bodies.size)
        assertNull(state.suspendedRound, "OpenRouter appends the outputs to its local history instead")
        bridge.send(state, "The bake has finished.")
        val types = bodies[1]["input"].map { it["type"]?.asText() ?: "message" }
        val calls = types.indexOf("function_call")
        val outputs = types.indexOf("function_call_output")
        assertTrue(calls in 0 until outputs, types.toString())
        assertEquals(2, types.count { it == "function_call_output" })
        assertTrue("The bake has finished." in bodies[1]["input"].last().toString())
    }

    @Test fun anthropicEndsTheTurnAndOpensTheNextMessageWithTheToolResults() {
        val anthropic = Class.forName("com.anthropic.core.ObjectMappers").getMethod("jsonMapper").invoke(null) as ObjectMapper
        fun reply(json: String): Message = anthropic.readValue(json, Message::class.java)
        val toolUse = reply("""{"id":"msg_1","type":"message","role":"assistant","model":"claude-test",
            "content":[{"type":"text","text":"Started the bake.","citations":null},
                {"type":"tool_use","id":"toolu_1","name":"Awaiting","input":{}}],
            "stop_reason":"tool_use","stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}""")
        val done = reply("""{"id":"msg_2","type":"message","role":"assistant","model":"claude-test",
            "content":[{"type":"text","text":"The bake succeeded.","citations":null}],
            "stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}""")
        val sent = mutableListOf<MessageCreateParams>()
        val client = fakeAnthropicClient { params -> sent += params; if (sent.size == 1) toolUse else done }
        val bridge = AnthropicBridge(client = client, model = "claude-test", log = SilentLog)
        bridge.registerTool(Awaiting::class.java) { _, _ -> Awaiting() }
        val state = bridge.startThread("k", "")

        val first = bridge.send(state, "Bake the mesh")
        assertEquals(1, sent.size, "no request after the round that suspended the turn")
        assertNotNull(first.suspension)
        assertEquals("Started the bake.", first.text)
        assertEquals(listOf("toolu_1"), state.suspendedRound!!.outputs.map { it.callId })

        assertEquals("The bake succeeded.", bridge.send(state, "The bake has finished.").text)
        val body = anthropic.readTree(anthropic.writeValueAsString(sent[1]._body()))["messages"]
        assertEquals("assistant", body[body.size() - 2]["role"].asText(), "the tool_use message, then its results")
        val last = body.last()
        assertEquals("user", last["role"].asText())
        assertEquals(listOf("tool_result", "text"), last["content"].map { it["type"].asText() })
        assertEquals("toolu_1", last["content"][0]["tool_use_id"].asText())
        assertTrue("The bake has finished." in last["content"][1]["text"].asText())
        assertNull(state.suspendedRound)
    }
}
