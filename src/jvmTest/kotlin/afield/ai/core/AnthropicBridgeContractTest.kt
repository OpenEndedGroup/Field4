package afield.ai.core

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Local Messages API fixtures for [AnthropicBridge], as AstraBridgeContractTest is for the Responses API. */
class AnthropicBridgeContractTest {
    private val mapper = ObjectMapper()

    private fun message(id: String, stopReason: String, content: String) = """{
        "id":"$id", "type":"message", "role":"assistant", "model":"claude-test",
        "content":$content, "stop_reason":"$stopReason", "stop_sequence":null,
        "usage":{"input_tokens":1,"output_tokens":1}
    }"""

    private fun text(id: String, text: String) =
        message(id, "end_turn", """[{"type":"text","text":"$text","citations":null}]""")

    private fun withServer(block: (HttpServer, MutableList<JsonNode>, AnthropicBridge) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = CopyOnWriteArrayList<JsonNode>()
        val client = AnthropicOkHttpClient.builder().apiKey("protocol-test")
            .baseUrl("http://127.0.0.1:${server.address.port}").maxRetries(0).build()
        // Fixture turns stay out of ~/.afield/logs/ai4_tool_io, where real Anthropic sessions are counted.
        val priorToolIoLog = System.getProperty("afield.ai4.toolIoLog")
        System.setProperty("afield.ai4.toolIoLog", "off")
        server.start()
        try {
            block(server, requests, AnthropicBridge(client = client, model = "claude-test", log = SilentLog))
        } finally {
            server.stop(0); client.close()
            if (priorToolIoLog == null) System.clearProperty("afield.ai4.toolIoLog") else System.setProperty("afield.ai4.toolIoLog", priorToolIoLog)
        }
    }

    /**
     * At maxToolRounds the forced final request keeps the tool definitions and turns calling them off with
     * `tool_choice: none`. Dropping them gets a 400 ("Requests which include `tool_use` or `tool_result`
     * blocks must define tools"), so a capped turn failed instead of summarising, and it changed the tool
     * list the cache breakpoint sits on. The stop is a parameter of that one request, not a message, so the
     * next turn is an ordinary request over the same history.
     */
    @Test
    fun forcedFinalKeepsTheToolsAndSendsToolChoiceNone() = withServer { server, requests, bridge ->
        server.createContext("/v1/messages") { exchange ->
            requests.add(mapper.readTree(exchange.requestBody))
            val body = when (requests.size) {
                1 -> message("msg_1", "tool_use",
                    """[{"type":"tool_use","id":"toolu_1","name":"Commentary","input":{"text":"working"}}]""")
                2 -> text("msg_2", "Summarised at the cap.")
                else -> text("msg_${requests.size}", "Continued.")
            }
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        fun tc(words: String) = TurnContext(listOf(ContextFragment("core.contract", "STABLE-CONTRACT")), words)
        val state = bridge.startThread("cap", "")
        val result = bridge.send(state, tc("Inspect").legacyPrompt(), maxToolRounds = 1, turnContext = tc("Inspect"))
        assertTrue(result.hitToolRoundCap)
        assertEquals("Summarised at the cap.", result.text)
        assertEquals(2, requests.size)

        val (first, forced) = requests
        assertTrue(forced.path("tools").any { it["name"].asText() == "Commentary" },
            "A forced final without tools is a 400 once the history holds tool_use/tool_result blocks")
        assertEquals(first["tools"], forced["tools"], "The forced final sends the tools it ran with, breakpoint and all")
        assertEquals("ephemeral", forced["tools"].last()["cache_control"]["type"].asText())
        assertEquals("none", forced["tool_choice"]["type"].asText())
        assertFalse(first.has("tool_choice"))
        // It answers from the round's call and result, with nothing added to the messages to stop it.
        assertEquals(listOf("user", "assistant", "user"), forced["messages"].map { it["role"].asText() })
        val results = forced["messages"].last()["content"]
        assertEquals(listOf("tool_result"), results.map { it["type"].asText() })
        assertEquals("toolu_1", results.single()["tool_use_id"].asText())

        val next = bridge.send(state, tc("Continue").legacyPrompt(), maxToolRounds = 1, turnContext = tc("Continue"))
        assertFalse(next.hitToolRoundCap)
        assertEquals(3, requests.size)
        val following = requests[2]
        assertFalse(following.has("tool_choice"), "The stop belonged to the forced request alone")
        assertEquals(first["tools"], following["tools"])
        val history = following["messages"]
        assertEquals(listOf("user", "assistant", "user", "assistant", "user"), history.map { it["role"].asText() })
        assertEquals("Summarised at the cap.", history[3]["content"].single()["text"].asText())
        assertEquals(forced["messages"], mapper.createArrayNode().addAll(history.take(3)))
    }
}
