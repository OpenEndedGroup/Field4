package afield.ai.conversation

import afield.ai.tools.Commentary

import afield.ai.diagnostics.SilentLog
import afield.ai.diagnostics.log
import afield.ai.models.AiModelSettings
import afield.ai.models.AiProviderRuntimeConfig
import afield.ai.models.AnthropicBridge
import afield.ai.models.LlmProgressUpdate
import afield.ai.models.OpenAiIdeCodexBridge
import afield.ai.models.ScriptedLlmBridge
import afield.ai.models.fakeAnthropicClient
import afield.ai.tools.ToolExecutionContext
import afield.runtime.languages.KOTLIN_SCRIPT

import afield.app.protocol.AiAssist
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.*

/**
 * A message sent to a box's chat while its turn runs reaches that turn: queued in its [AiTurnInbox], handed
 * to the model after the next tool round on both provider wires, shown in the transcript where the model
 * read it, and returned at the end of the turn when the model never got to it.
 */
class AiTurnInboxTest {
    private fun pending(text: String, images: List<String> = emptyList()) =
        AiTurnInbox.Pending(text, text, images, AiAssist(uid = "b", prompt = "code", extraPrompt = text, kind = "general",
            images = images, queueIfBusy = true))

    @Test fun drainingDeliversInOrderAndClosingReturnsWhatWasNeverDelivered() {
        val inbox = AiTurnInbox()
        val delivered = mutableListOf<String>()
        inbox.onDelivered = { taken -> delivered += taken.map { it.shown } }
        assertTrue(inbox.drainForModel().isEmpty())
        assertTrue(delivered.isEmpty(), "nothing waiting is not a delivery")

        assertTrue(inbox.offer(pending("use red")))
        assertTrue(inbox.offer(pending("and make it bigger")))
        assertEquals("<<queued>>\nuse red\n<<queued>>\nand make it bigger\n", inbox.queuedMarkdown())
        assertEquals(listOf("use red", "and make it bigger"), inbox.drainForModel().map { it.shown })
        assertEquals(listOf("use red", "and make it bigger"), delivered)
        assertEquals("", inbox.queuedMarkdown())

        assertTrue(inbox.offer(pending("actually, stop")))
        assertEquals(listOf("actually, stop"), inbox.close().map { it.shown })
        assertFalse(inbox.offer(pending("too late")), "a closed inbox refuses, so the sender starts a turn of its own")
        assertTrue(inbox.close().isEmpty())
    }

    @Test fun undeliveredMessagesBecomeOneFollowUpRequest() {
        val combined = AiTurnInbox.combined(listOf(pending("use red", listOf("data:image/png;base64,AA")), pending("and bigger")))!!
        assertEquals("use red\n\nand bigger", combined.extraPrompt)
        assertEquals(listOf("data:image/png;base64,AA"), combined.images)
        assertEquals("general", combined.kind)
        assertTrue(combined.queueIfBusy)
        assertNull(AiTurnInbox.combined(emptyList()))
    }

    @Test fun theModelIsToldTheMessageArrivedMidTask() {
        val text = AiTurnInbox.modelText(listOf(pending("stop after this box")))
        assertTrue(text.startsWith("[The user sent this message while you were working"), text)
        assertTrue(text.endsWith("stop after this box"), text)
    }

    @Test fun aDeliveredMessageSitsInTheTranscriptWhereTheModelReadIt() {
        val live = AiLiveConversation()
        live.text("I'll start with the compute box.")
        live.progress(LlmProgressUpdate("Running EditFile", phase = "tool"))
        live.user("use red, not blue")
        live.text("Switching to red.")
        val before = "<<you>>\nmake particles\n"
        val rendered = live.render(before, null, live = false, finalText = "Switching to red. Done.")
        val order = listOf("I'll start", "Running EditFile", "<<you>>\nuse red, not blue", "Done.").map { rendered.indexOf(it) }
        assertTrue(order.all { it >= 0 } && order == order.sorted(), rendered)
        // The session tree still names the turn by the request that started it.
        assertEquals("make particles", AiSessionTree.userText(rendered))
    }

    data class Probe(val value: String = "test")

    @Test fun openAiSendsTheMessageAfterTheRoundsToolOutputs() {
        val mapper = ObjectMapper()
        val bodies = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val url = "http://127.0.0.1:${server.address.port}/v1"
        server.createContext("/v1/responses") { exchange ->
            bodies += exchange.requestBody.readAllBytes().decodeToString()
            val output = if (bodies.size == 1) """[{"type":"function_call","id":"fc_1","call_id":"call_1","name":"Probe","arguments":"{\"value\":\"test\"}","status":"completed"}]"""
            else """[{"type":"message","id":"msg_2","role":"assistant","status":"completed","content":[{"type":"output_text","text":"Done in red.","annotations":[]}]}]"""
            val body = """{"id":"resp_${bodies.size}","object":"response","created_at":1,"model":"gpt-6-astra","status":"completed","output":$output,"parallel_tool_calls":false,"tool_choice":"auto","tools":[],"error":null,"incomplete_details":null,"instructions":null,"metadata":{},"temperature":1.0,"top_p":1.0}"""
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        val client = OpenAIOkHttpClient.builder().apiKey("test").baseUrl(url).maxRetries(0).build()
        try {
            val bridge = OpenAiIdeCodexBridge(
                runtimeConfig = AiProviderRuntimeConfig("openai", "test", "fixture", url, AiModelSettings()),
                client = client, model = "gpt-6-astra", log = SilentLog)
            // The person writes while the tool runs.
            bridge.registerTool(Probe::class.java) { ctx, _ -> ctx.inbox!!.offer(pending("use red, not blue")); "observed" }
            val inbox = AiTurnInbox()
            val delivered = mutableListOf<String>()
            inbox.onDelivered = { taken -> delivered += taken.map { it.shown } }
            val state = OpenAiIdeCodexBridge.ThreadState("local", "conv", "fixture", previousResponseId = "previous")
            val result = bridge.send(state, "Make particles", ctx = ToolExecutionContext(inbox = inbox))
            assertEquals("Done in red.", result.text)
            assertEquals(2, bodies.size)
            assertFalse("use red" in bodies[0])
            val input = mapper.readTree(bodies[1])["input"]
            assertEquals("function_call_output", input[0]["type"].asText(), "the call's output stays paired and first")
            assertEquals("user", input[1]["role"].asText())
            assertTrue("use red, not blue" in input[1]["content"].toString(), input.toString())
            assertEquals(listOf("use red, not blue"), delivered)
            assertTrue(inbox.close().isEmpty())
        } finally {
            client.close(); server.stop(0)
        }
    }

    @Test fun anthropicSendsTheMessageAsATextBlockAfterTheToolResults() {
        val mapper = Class.forName("com.anthropic.core.ObjectMappers").getMethod("jsonMapper").invoke(null) as ObjectMapper
        fun reply(json: String): Message = mapper.readValue(json, Message::class.java)
        val toolUse = reply("""{"id":"msg_1","type":"message","role":"assistant","model":"claude-test",
            "content":[{"type":"tool_use","id":"toolu_1","name":"Commentary","input":{"text":"working"}}],
            "stop_reason":"tool_use","stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}""")
        val done = reply("""{"id":"msg_2","type":"message","role":"assistant","model":"claude-test",
            "content":[{"type":"text","text":"Done in red.","citations":null}],
            "stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}""")
        val sent = mutableListOf<MessageCreateParams>()
        val client = fakeAnthropicClient { params -> sent += params; if (sent.size == 1) toolUse else done }

        val bridge = AnthropicBridge(client = client, model = "claude-test", log = SilentLog)
        val inbox = AiTurnInbox()
        // Already waiting when the first round's tool finishes.
        inbox.offer(pending("use red, not blue"))
        val result = bridge.send(bridge.startThread("k", ""), "Make particles", ctx = ToolExecutionContext(inbox = inbox))
        assertEquals("Done in red.", result.text)
        assertEquals(2, sent.size)
        val last = mapper.readTree(mapper.writeValueAsString(sent[1]._body()))["messages"].last()
        assertEquals("user", last["role"].asText())
        assertEquals(listOf("tool_result", "text"), last["content"].map { it["type"].asText() })
        assertTrue("use red, not blue" in last["content"][1]["text"].asText())
    }

    @Test fun aScriptedTurnRecordsWhatItWasHandedBetweenRounds() {
        val bridge = ScriptedLlmBridge(listOf(
            ScriptedLlmBridge.Step.Call("Commentary", """{"text":"working"}"""),
            ScriptedLlmBridge.Step.Final("done")))
        val inbox = AiTurnInbox()
        inbox.offer(pending("use red"))
        IdeChatHelpers(bridge).chatGeneral(bridge.startThread("k", ""), "", KOTLIN_SCRIPT, "hi", inbox = inbox)
        assertEquals(1, bridge.interjections.size)
        assertTrue(bridge.interjections.single().endsWith("use red"))
        assertTrue(inbox.close().isEmpty())
    }
}
