package afield.ai.core

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.core.http.Headers
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.RateLimitException
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.services.blocking.MessageService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Local Messages API fixtures for [AnthropicBridge], as AstraBridgeContractTest is for the Responses API. */
class AnthropicBridgeContractTest {
    private val mapper = ObjectMapper()

    private fun message(id: String, stopReason: String, content: String, usage: String = """{"input_tokens":1,"output_tokens":1}""") = """{
        "id":"$id", "type":"message", "role":"assistant", "model":"claude-test",
        "content":$content, "stop_reason":"$stopReason", "stop_sequence":null,
        "usage":$usage
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
            block(server, requests, AnthropicBridge(client = client, model = "claude-test", log = SilentLog,
                baseUrl = "http://127.0.0.1:${server.address.port}"))
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

    /**
     * Each Messages API call is one attempt in the machine-wide request ledger, with the OpenAI bridge's attribution
     * and phase names, so `tools/ai_usage.py` counts Anthropic turns too. A rate-limited attempt and its retry share
     * one logical request. Input includes the prompt tokens read from and written to the cache, which Anthropic's
     * `input_tokens` leaves out; a figure the provider did not report is null, not zero.
     */
    @Test
    fun requestLedgerRecordsEveryAttemptWithUsageAndNoConversationContent() {
        val dir = Files.createTempDirectory("ai-usage-anthropic")
        val previousLedger = System.getProperty("afield.ai.usageLog.path")
        val previousToolIoLog = System.getProperty("afield.ai4.toolIoLog")
        System.setProperty("afield.ai.usageLog.path", dir.toString())
        System.setProperty("afield.ai4.toolIoLog", "off")
        try {
            val sdk = Class.forName("com.anthropic.core.ObjectMappers").getMethod("jsonMapper").invoke(null) as ObjectMapper
            fun reply(json: String): Message = sdk.readValue(json, Message::class.java)
            fun commentary(id: String, usage: String) = reply(message(id, "tool_use",
                """[{"type":"tool_use","id":"toolu_$id","name":"Commentary","input":{"text":"PRIVATE_TOOL_ARGUMENT"}}]""", usage))
            fun error(type: String, text: String) = JsonValue.from(mapOf("type" to "error", "error" to mapOf("type" to type, "message" to text)))
            val replies = ArrayDeque(listOf<() -> Message>(
                { throw RateLimitException.builder()
                    .headers(Headers.builder().put("request-id", "req_ledger").put("retry-after", "0.001")
                        .put("anthropic-ratelimit-tokens-remaining", "0").build())
                    .body(error("rate_limit_error", "Rate limited, try again in 1ms. PRIVATE_ERROR")).build() },
                { commentary("msg_1", """{"input_tokens":3,"cache_read_input_tokens":100,"cache_creation_input_tokens":50,"output_tokens":7}""") },
                { commentary("msg_2", """{"input_tokens":4,"cache_read_input_tokens":150,"cache_creation_input_tokens":0,"output_tokens":8}""") },
                { reply(message("msg_3", "end_turn", """[{"type":"text","text":"Done.","citations":null}]""", """{"input_tokens":5,"output_tokens":9}""")) },
                { throw BadRequestException.builder().headers(Headers.builder().put("request-id", "req_bad").build())
                    .body(error("invalid_request_error", "PRIVATE_ERROR")).build() }
            ))
            val messages = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(MessageService::class.java)) { _, method, args ->
                if (method.name == "create" && args?.firstOrNull() is MessageCreateParams) replies.removeFirst()()
                else throw UnsupportedOperationException(method.name)
            } as MessageService
            val client = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(AnthropicClient::class.java)) { _, method, _ ->
                if (method.name == "messages") messages else throw UnsupportedOperationException(method.name)
            } as AnthropicClient
            val bridge = AnthropicBridge(client = client, model = "claude-test", log = SilentLog,
                baseUrl = "https://user:SECRET_KEY@anthropic.test/v1?key=SECRET_KEY")
            val state = bridge.startThread("ledger-key", "")
            val ctx = ToolExecutionContext(focus = ToolFocus("/docs/ledger-doc", afield.Box("program"), "code"))

            assertEquals("Done.", bridge.send(state, "PRIVATE_PROMPT", ctx = ctx, maxOutputTokens = 900, maxToolRounds = 2).text)
            assertFailsWith<BadRequestException> { bridge.send(state, "PRIVATE_PROMPT again", ctx = ctx, maxOutputTokens = 900) }
            assertTrue(replies.isEmpty())

            val text = Files.list(dir).use { files -> files.toList().joinToString("") { Files.readString(it) } }
            val events = text.lineSequence().filter { it.isNotBlank() }.map { mapper.readTree(it) }.toList()
            fun of(event: String) = events.filter { it["event"].asText() == event }
            val started = of("attempt_started")
            assertEquals(listOf("initial", "initial", "followup", "forced_final", "initial"), started.map { it["phase"].asText() })
            assertEquals(listOf(1, 2, 1, 1, 1), started.map { it["attempt"].asInt() })
            val requestIds = started.map { it["requestId"].asText() }
            assertEquals(requestIds[0], requestIds[1], "a retry is another attempt of the same request")
            assertEquals(4, requestIds.distinct().size)
            assertEquals(1, started.take(4).map { it["turnId"].asText() }.distinct().size)
            assertNotEquals(started[0]["turnId"].asText(), started[4]["turnId"].asText())
            for (event in events) {
                assertEquals("anthropic", event["provider"].asText())
                assertEquals("https://anthropic.test/v1", event["endpoint"].asText())
                assertEquals("claude-test", event["model"].asText())
                assertEquals("ledger-key", event["fileKey"].asText())
                assertEquals("/docs/ledger-doc", event["documentPath"].asText())
                assertEquals(state.conversationId, event["conversationId"].asText())
                assertEquals(state.localThreadId, event["localThreadId"].asText())
                assertEquals(900, event["maxOutputTokens"].asInt())
                assertEquals(ProcessHandle.current().pid(), event["pid"].asLong())
            }

            val retry = of("retry_wait").single()
            assertEquals(requestIds[0], retry["requestId"].asText())
            assertEquals(1, retry["retry"].asInt())
            assertEquals(2, retry["delayMs"].asInt(), "the hinted 1 ms, times the shared 1.5 margin, rounded up")

            val usage = of("response_usage")
            assertEquals(listOf("msg_1", "msg_2", "msg_3"), usage.map { it["responseId"].asText() })
            assertEquals(listOf(2, 1, 1), usage.map { it["attempt"].asInt() })
            assertTrue(usage.all { it["usageKnown"].asBoolean() && it["responseModel"].asText() == "claude-test" })
            assertEquals(listOf("tool_use", "tool_use", "end_turn"), usage.map { it["stopReason"].asText() })
            assertEquals(listOf(153, 154, 5), usage.map { it["inputTokens"].asInt() })
            assertEquals(listOf(100, 150), usage.take(2).map { it["cachedInputTokens"].asInt() })
            assertEquals(listOf(50, 0), usage.take(2).map { it["cacheWriteTokens"].asInt() })
            assertTrue(usage[2]["cachedInputTokens"].isNull && usage[2]["cacheWriteTokens"].isNull, "unreported, not zero")
            assertEquals(listOf(7, 8, 9), usage.map { it["outputTokens"].asInt() })
            assertEquals(listOf(160, 162, 14), usage.map { it["totalTokens"].asInt() })
            assertTrue(usage.all { it["reasoningTokens"].isNull })

            val finished = of("attempt_finished")
            assertEquals(listOf(false, true, true, true, false), finished.map { it["success"].asBoolean() })
            val (limited, rejected) = finished.filter { !it["success"].asBoolean() }
            assertEquals("RateLimitException", limited["errorType"].asText())
            assertEquals(429, limited["httpStatus"].asInt())
            assertEquals("rate_limit_error", limited["providerCode"].asText())
            assertTrue(limited["rateLimited"].asBoolean())
            assertEquals("req_ledger", limited["responseHeaders"]["request-id"].asText())
            assertEquals("0", limited["responseHeaders"]["anthropic-ratelimit-tokens-remaining"].asText())
            assertEquals("BadRequestException", rejected["errorType"].asText())
            assertEquals(400, rejected["httpStatus"].asInt())
            assertEquals("invalid_request_error", rejected["providerCode"].asText())
            assertFalse(rejected["rateLimited"].asBoolean())
            assertEquals("req_bad", rejected["responseHeaders"]["request-id"].asText())

            for (secret in listOf("PRIVATE_PROMPT", "PRIVATE_TOOL_ARGUMENT", "PRIVATE_ERROR", "SECRET_KEY")) assertFalse(secret in text, secret)
        } finally {
            if (previousLedger == null) System.clearProperty("afield.ai.usageLog.path") else System.setProperty("afield.ai.usageLog.path", previousLedger)
            if (previousToolIoLog == null) System.clearProperty("afield.ai4.toolIoLog") else System.setProperty("afield.ai4.toolIoLog", previousToolIoLog)
            dir.toFile().deleteRecursively()
        }
    }
}
