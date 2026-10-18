package afield.ai.conversation

import afield.ai.diagnostics.SilentLog
import afield.ai.models.AiModelSettings
import afield.ai.models.AiProviderRuntimeConfig
import afield.ai.models.AnthropicBridge
import afield.ai.models.OpenAiIdeCodexBridge
import afield.ai.models.fakeAnthropicClient
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.*

/**
 * A tool that throws an [Error] must not poison its conversation. On 2026-10-18 (house-machine-17b) a box
 * tool's StackOverflowError escaped the tool loop, the turn ended with the model's function call unanswered,
 * and the provider refused every later turn: "No tool output found for function call call_N24b…". A stack
 * overflow is now the call's output, as an exception is. An OutOfMemoryError still ends the turn, but leaves
 * the next turn an output for every call the provider holds, so that turn is accepted.
 *
 * The fake providers here refuse a request the way the real ones do, when it follows calls without outputs.
 */
class ToolErrorThreadTest {
    data class Layout(val depth: Int = 0)
    data class Exhaust(val size: Int = 0)
    data class Probe(val value: String = "test")

    private val mapper = ObjectMapper()

    /** Recursion with no base case, as the house tool's `showServiceLayout$file` was. */
    private fun descend(depth: Int): Int = descend(depth + 1) + 1

    /**
     * A Responses API fake that keeps each response's function calls and answers 400 to a request whose
     * `previous_response_id` names a response with a call the request gives no output for.
     */
    private class FakeResponses(private val replies: List<String>) {
        val bodies = mutableListOf<JsonNode>()
        val refusals = mutableListOf<String>()
        private val callsByResponse = mutableMapOf<String, List<String>>()
        private val mapper = ObjectMapper()

        fun run(register: (OpenAiIdeCodexBridge) -> Unit, block: (OpenAiIdeCodexBridge) -> Unit) {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val url = "http://127.0.0.1:${server.address.port}/v1"
            server.createContext("/v1/responses") { exchange ->
                val body = mapper.readTree(exchange.requestBody.readAllBytes())
                bodies.add(body)
                val previous = body["previous_response_id"]?.asText()
                val answered = body["input"]?.takeIf { it.isArray }?.filter { it["type"]?.asText() == "function_call_output" }
                    ?.map { it["call_id"].asText() }.orEmpty().toSet()
                val missing = callsByResponse[previous].orEmpty().filter { it !in answered }
                val (status, text) = if (missing.isNotEmpty()) {
                    val message = "No tool output found for function call ${missing.first()}."
                    refusals += message
                    400 to """{"error":{"message":"$message","type":"invalid_request_error","param":"input","code":null}}"""
                } else {
                    val id = "resp_${bodies.size}"
                    val output = replies[callsByResponse.size]
                    callsByResponse[id] = mapper.readTree(output).filter { it["type"].asText() == "function_call" }.map { it["call_id"].asText() }
                    200 to """{"id":"$id","object":"response","created_at":1,"model":"gpt-6-astra","status":"completed","output":$output,"parallel_tool_calls":true,"tool_choice":"auto","tools":[],"error":null,"incomplete_details":null,"instructions":null,"metadata":{},"temperature":1.0,"top_p":1.0}"""
                }
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, text.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(text.toByteArray()) }
            }
            server.start()
            val client = OpenAIOkHttpClient.builder().apiKey("test").baseUrl(url).maxRetries(0).build()
            try {
                val bridge = OpenAiIdeCodexBridge(runtimeConfig = AiProviderRuntimeConfig("openai", "test", "fixture", url, AiModelSettings()),
                    client = client, model = "gpt-6-astra", log = SilentLog)
                register(bridge)
                block(bridge)
            } finally { client.close(); server.stop(0) }
        }
    }

    private fun call(id: String, name: String) =
        """{"type":"function_call","id":"fc_$id","call_id":"$id","name":"$name","arguments":"{}","status":"completed"}"""
    private fun text(t: String) =
        """{"type":"message","id":"msg_${t.hashCode()}","role":"assistant","status":"completed","content":[{"type":"output_text","text":"$t","annotations":[]}]}"""

    private fun outputsOf(body: JsonNode): Map<String, String> = body["input"].filter { it["type"]?.asText() == "function_call_output" }
        .associate { it["call_id"].asText() to it["output"].asText() }

    @Test fun aStackOverflowInAToolIsTheCallsOutputAndTheNextTurnSucceeds() {
        val fake = FakeResponses(listOf(
            "[${call("call_N24b", "Layout")}]",
            "[${text("The layout tool overflowed its stack.")}]",
            "[${text("Second turn answered.")}]"))
        fake.run(register = { it.registerTool(Layout::class.java) { _, args -> descend(args.depth) } }) { bridge ->
            val state = OpenAiIdeCodexBridge.ThreadState("local", "conv", "fixture", previousResponseId = "previous")
            val first = bridge.send(state, "Show the service layout")
            assertEquals("The layout tool overflowed its stack.", first.text)
            val output = mapper.readTree(outputsOf(fake.bodies[1]).getValue("call_N24b"))
            assertTrue(output["error"].asBoolean())
            val message = output["message"].asText()
            assertTrue(message.startsWith("Tool failed: StackOverflowError at "), message)
            assertTrue("descend" in message, "names the frame it overflowed in: $message")
            assertNull(state.suspendedRound, "every call was answered within the turn")

            assertEquals("Second turn answered.", bridge.send(state, "Try again").text)
            assertEquals("resp_2", fake.bodies[2]["previous_response_id"].asText())
            assertEquals(emptyList(), fake.refusals)
        }
    }

    @Test fun anOutOfMemoryErrorEndsTheTurnButLeavesTheNextTurnAnOutputForEveryCall() {
        val fake = FakeResponses(listOf(
            "[${call("call_1", "Probe")},${call("call_2", "Exhaust")},${call("call_3", "Probe")}]",
            "[${text("Recovered.")}]"))
        fake.run(register = {
            it.registerTool(Probe::class.java) { _, _ -> "probed" }
            it.registerTool(Exhaust::class.java) { _, _ -> throw OutOfMemoryError("Java heap space") }
        }) { bridge ->
            val state = OpenAiIdeCodexBridge.ThreadState("local", "conv", "fixture", previousResponseId = "previous")
            assertFailsWith<OutOfMemoryError> { bridge.send(state, "Measure everything") }
            assertEquals(1, fake.bodies.size, "the turn ended at the error, before any follow-up request")
            assertEquals("resp_1", state.previousResponseId)
            assertEquals(listOf("call_1", "call_2", "call_3"), state.suspendedRound!!.outputs.map { it.callId })

            assertEquals("Recovered.", bridge.send(state, "What happened?").text)
            val outputs = outputsOf(fake.bodies[1])
            assertEquals("probed", mapper.readTree(outputs.getValue("call_1")).asText(), "a call that ran keeps its output")
            for (id in listOf("call_2", "call_3")) {
                val notRun = mapper.readTree(outputs.getValue(id))
                assertTrue(notRun["aborted"].asBoolean(), id)
                assertTrue("OutOfMemoryError" in notRun["message"].asText(), notRun.toString())
            }
            assertTrue("What happened?" in fake.bodies[1]["input"].last()["content"].toString())
            assertEquals(emptyList(), fake.refusals)
            assertNull(state.suspendedRound)
        }
    }

    @Test fun anthropicAnswersAStackOverflowAndOpensTheTurnAfterAnOutOfMemoryErrorWithItsResults() {
        val anthropic = Class.forName("com.anthropic.core.ObjectMappers").getMethod("jsonMapper").invoke(null) as ObjectMapper
        fun reply(content: String, stop: String) = anthropic.readValue("""{"id":"msg","type":"message","role":"assistant","model":"claude-test",
            "content":$content,"stop_reason":"$stop","stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}""", Message::class.java)
        fun toolUse(id: String, name: String) = reply("""[{"type":"tool_use","id":"$id","name":"$name","input":{}}]""", "tool_use")
        fun done(t: String) = reply("""[{"type":"text","text":"$t","citations":null}]""", "end_turn")
        val replies = ArrayDeque(listOf(toolUse("toolu_1", "Layout"), done("Overflowed."), toolUse("toolu_2", "Exhaust"), done("Recovered.")))
        val sent = mutableListOf<JsonNode>()
        val client = fakeAnthropicClient { params: MessageCreateParams ->
            val messages = anthropic.readTree(anthropic.writeValueAsString(params._body()))["messages"]
            sent.add(messages)
            // Every tool_use must be answered by a tool_result in the message after it, as the Messages API requires.
            messages.forEachIndexed { i, message ->
                val uses = message["content"].filter { it["type"]?.asText() == "tool_use" }.map { it["id"].asText() }
                val results = messages.get(i + 1)?.get("content")?.filter { it["type"]?.asText() == "tool_result" }?.map { it["tool_use_id"].asText() }.orEmpty()
                check(results.containsAll(uses)) { "tool_use ids were found without tool_result blocks: ${uses - results.toSet()}" }
            }
            replies.removeFirst()
        }
        val bridge = AnthropicBridge(client = client, model = "claude-test", log = SilentLog)
        bridge.registerTool(Layout::class.java) { _, args -> descend(args.depth) }
        bridge.registerTool(Exhaust::class.java) { _, _ -> throw OutOfMemoryError("Java heap space") }
        val state = bridge.startThread("k", "")

        assertEquals("Overflowed.", bridge.send(state, "Show the layout").text)
        val result = sent[1].last()["content"][0]
        assertEquals("toolu_1", result["tool_use_id"].asText())
        assertTrue(result["is_error"].asBoolean())
        assertTrue("Tool failed: StackOverflowError" in result.toString(), result.toString())

        assertFailsWith<OutOfMemoryError> { bridge.send(state, "Now measure it") }
        assertEquals(listOf("toolu_2"), state.suspendedRound!!.outputs.map { it.callId })
        assertEquals("Recovered.", bridge.send(state, "What happened?").text)
        val opening = sent.last().last()["content"]
        assertEquals(listOf("tool_result", "text"), opening.map { it["type"].asText() })
        assertTrue("OutOfMemoryError" in opening[0].toString())
        assertNull(state.suspendedRound)
    }
}
