package afield.ai.models

import afield.ai.tools.Commentary

import afield.ai.conversation.ContextFragment
import afield.ai.conversation.TurnContext
import afield.ai.diagnostics.SilentLog
import afield.ai.diagnostics.log
import afield.ai.tools.ToolExecutionContext
import afield.ai.tools.ToolFocus
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.core.http.Headers
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.RateLimitException
import com.anthropic.models.messages.Message
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
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
        // The SDK's default retries left on, as anthropicClientFromFieldConfig() leaves them: the bridge turns them off.
        val client = AnthropicOkHttpClient.builder().apiKey("protocol-test")
            .baseUrl("http://127.0.0.1:${server.address.port}").build()
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
     * and phase names, so `tools/ai_usage.py` counts Anthropic turns too. A 429 whose only wait is its `retry-after`
     * header and a 529 overloaded_error are retried as further attempts of one logical request, with a `retry_wait`
     * apiece. Input includes the prompt tokens read from and written to the cache, which Anthropic's `input_tokens`
     * leaves out; a figure the provider did not report is null, not zero.
     */
    @Test
    fun requestLedgerRecordsEveryAttemptWithUsageAndNoConversationContent() {
        val dir = Files.createTempDirectory("ai-usage-anthropic")
        val previousLedger = System.getProperty("afield.ai.usageLog.path")
        val previousToolIoLog = System.getProperty("afield.ai4.toolIoLog")
        val previousCap = System.getProperty("afield.ai.rateLimitMaxDelayMs")
        System.setProperty("afield.ai.usageLog.path", dir.toString())
        System.setProperty("afield.ai4.toolIoLog", "off")
        // The smallest backoff cap, so the two waits take about a second together.
        System.setProperty("afield.ai.rateLimitMaxDelayMs", "250")
        try {
            val sdk = Class.forName("com.anthropic.core.ObjectMappers").getMethod("jsonMapper").invoke(null) as ObjectMapper
            fun reply(json: String): Message = sdk.readValue(json, Message::class.java)
            fun commentary(id: String, usage: String) = reply(message(id, "tool_use",
                """[{"type":"tool_use","id":"toolu_$id","name":"Commentary","input":{"text":"PRIVATE_TOOL_ARGUMENT"}}]""", usage))
            fun error(type: String, text: String) = JsonValue.from(mapOf("type" to "error", "error" to mapOf("type" to type, "message" to text)))
            val replies = ArrayDeque(listOf<() -> Message>(
                // No "try again in" in the message, as in Anthropic's own 429s: the header is the only hint.
                { throw RateLimitException.builder()
                    .headers(Headers.builder().put("request-id", "req_ledger").put("retry-after", "0.001")
                        .put("anthropic-ratelimit-tokens-remaining", "0").build())
                    .body(error("rate_limit_error", "This request would exceed your rate limit. PRIVATE_ERROR")).build() },
                { throw InternalServerException.builder().statusCode(529)
                    .headers(Headers.builder().put("request-id", "req_overloaded").build())
                    .body(error("overloaded_error", "Overloaded. PRIVATE_ERROR")).build() },
                { commentary("msg_1", """{"input_tokens":3,"cache_read_input_tokens":100,"cache_creation_input_tokens":50,"output_tokens":7}""") },
                { commentary("msg_2", """{"input_tokens":4,"cache_read_input_tokens":150,"cache_creation_input_tokens":0,"output_tokens":8}""") },
                { reply(message("msg_3", "end_turn", """[{"type":"text","text":"Done.","citations":null}]""", """{"input_tokens":5,"output_tokens":9}""")) },
                { throw BadRequestException.builder().headers(Headers.builder().put("request-id", "req_bad").build())
                    .body(error("invalid_request_error", "PRIVATE_ERROR")).build() }
            ))
            val client = fakeAnthropicClient { replies.removeFirst()() }
            val bridge = AnthropicBridge(client = client, model = "claude-test", log = SilentLog,
                baseUrl = "https://user:SECRET_KEY@anthropic.test/v1?key=SECRET_KEY")
            val state = bridge.startThread("ledger-key", "")
            val ctx = ToolExecutionContext(focus = ToolFocus("/docs/ledger-doc", afield.document.graph.Box("program"), "code"))

            assertEquals("Done.", bridge.send(state, "PRIVATE_PROMPT", ctx = ctx, maxOutputTokens = 900, maxToolRounds = 2).text)
            assertFailsWith<BadRequestException> { bridge.send(state, "PRIVATE_PROMPT again", ctx = ctx, maxOutputTokens = 900) }
            assertTrue(replies.isEmpty())

            val text = Files.list(dir).use { files -> files.toList().joinToString("") { Files.readString(it) } }
            val events = text.lineSequence().filter { it.isNotBlank() }.map { mapper.readTree(it) }.toList()
            fun of(event: String) = events.filter { it["event"].asText() == event }
            val started = of("attempt_started")
            assertEquals(listOf("initial", "initial", "initial", "followup", "forced_final", "initial"), started.map { it["phase"].asText() })
            assertEquals(listOf(1, 2, 3, 1, 1, 1), started.map { it["attempt"].asInt() })
            val requestIds = started.map { it["requestId"].asText() }
            assertEquals(1, requestIds.take(3).distinct().size, "a retry is another attempt of the same request")
            assertEquals(4, requestIds.distinct().size)
            assertEquals(1, started.take(5).map { it["turnId"].asText() }.distinct().size)
            assertNotEquals(started[0]["turnId"].asText(), started[5]["turnId"].asText())
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

            val retries = of("retry_wait")
            assertEquals(listOf(1, 2), retries.map { it["retry"].asInt() })
            assertTrue(retries.all { it["requestId"].asText() == requestIds[0] && it["delayMs"].asLong() > 0 })

            val usage = of("response_usage")
            assertEquals(listOf("msg_1", "msg_2", "msg_3"), usage.map { it["responseId"].asText() })
            assertEquals(listOf(3, 1, 1), usage.map { it["attempt"].asInt() })
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
            assertEquals(listOf(false, false, true, true, true, false), finished.map { it["success"].asBoolean() })
            val (limited, overloaded, rejected) = finished.filter { !it["success"].asBoolean() }
            assertEquals("RateLimitException", limited["errorType"].asText())
            assertEquals(429, limited["httpStatus"].asInt())
            assertEquals("rate_limit_error", limited["providerCode"].asText())
            assertTrue(limited["rateLimited"].asBoolean())
            assertEquals("req_ledger", limited["responseHeaders"]["request-id"].asText())
            assertEquals("0.001", limited["responseHeaders"]["retry-after"].asText())
            assertEquals("0", limited["responseHeaders"]["anthropic-ratelimit-tokens-remaining"].asText())
            assertEquals("InternalServerException", overloaded["errorType"].asText())
            assertEquals(529, overloaded["httpStatus"].asInt())
            assertEquals("overloaded_error", overloaded["providerCode"].asText())
            assertFalse(overloaded["rateLimited"].asBoolean())
            assertEquals("req_overloaded", overloaded["responseHeaders"]["request-id"].asText())
            assertEquals("BadRequestException", rejected["errorType"].asText())
            assertEquals(400, rejected["httpStatus"].asInt())
            assertEquals("invalid_request_error", rejected["providerCode"].asText())
            assertFalse(rejected["rateLimited"].asBoolean())
            assertEquals("req_bad", rejected["responseHeaders"]["request-id"].asText())

            for (secret in listOf("PRIVATE_PROMPT", "PRIVATE_TOOL_ARGUMENT", "PRIVATE_ERROR", "SECRET_KEY")) assertFalse(secret in text, secret)
        } finally {
            if (previousLedger == null) System.clearProperty("afield.ai.usageLog.path") else System.setProperty("afield.ai.usageLog.path", previousLedger)
            if (previousToolIoLog == null) System.clearProperty("afield.ai4.toolIoLog") else System.setProperty("afield.ai4.toolIoLog", previousToolIoLog)
            if (previousCap == null) System.clearProperty("afield.ai.rateLimitMaxDelayMs") else System.setProperty("afield.ai.rateLimitMaxDelayMs", previousCap)
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * The bridge, not the SDK, retries, so every HTTP request is an attempt in the ledger. [withServer]'s client keeps
     * the SDK's two default retries; were they still in force, the server would see more requests than the ledger has
     * attempts, and the 429 would leave no record. A 429 whose only hint is `retry-after`, a 529 overloaded_error and
     * a 500 with `retry-after-ms` are each waited out for at least what the header asks, even above the backoff cap;
     * a 500 the server marks `x-should-retry: false` is not retried.
     */
    @Test
    fun theBridgeOwnsTheRetriesSoEveryHttpRequestIsAnAttempt() {
        val dir = Files.createTempDirectory("ai-usage-anthropic-retries")
        val previousLedger = System.getProperty("afield.ai.usageLog.path")
        val previousCap = System.getProperty("afield.ai.rateLimitMaxDelayMs")
        System.setProperty("afield.ai.usageLog.path", dir.toString())
        // The smallest backoff cap: a wait of a second or more can only have come from a header.
        System.setProperty("afield.ai.rateLimitMaxDelayMs", "250")
        try {
            withServer { server, requests, bridge ->
                val arrivals = CopyOnWriteArrayList<Long>()
                server.createContext("/v1/messages") { exchange ->
                    arrivals += System.nanoTime()
                    requests.add(mapper.readTree(exchange.requestBody))
                    fun error(type: String, text: String) = """{"type":"error","error":{"type":"$type","message":"$text"}}"""
                    val (status, headers, body) = when (requests.size) {
                        1 -> Triple(429, mapOf("retry-after" to "1"), error("rate_limit_error", "This request would exceed the rate limit for your organization."))
                        2 -> Triple(529, mapOf("x-should-retry" to "true"), error("overloaded_error", "Overloaded"))
                        3 -> Triple(500, mapOf("retry-after-ms" to "600"), error("api_error", "Internal server error"))
                        4 -> Triple(200, emptyMap(), text("msg_recovered", "Recovered."))
                        else -> Triple(500, mapOf("x-should-retry" to "false"), error("api_error", "Internal server error"))
                    }
                    headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(status, body.toByteArray().size.toLong())
                    exchange.responseBody.use { it.write(body.toByteArray()) }
                }
                val state = bridge.startThread("retries", "")
                assertEquals("Recovered.", bridge.send(state, "Inspect").text)
                assertEquals(4, requests.size)
                assertTrue((arrivals[1] - arrivals[0]) / 1_000_000 >= 1000, "retry-after: 1 is a minimum, above the 250 ms cap")
                assertTrue((arrivals[3] - arrivals[2]) / 1_000_000 >= 600, "retry-after-ms: 600 likewise")
                assertFailsWith<InternalServerException> { bridge.send(state, "Again") }
                assertEquals(5, requests.size, "x-should-retry: false is not retried")
            }

            val events = Files.list(dir).use { files -> files.toList().flatMap { Files.readAllLines(it) } }
                .filter { it.isNotBlank() }.map { mapper.readTree(it) }
            fun of(event: String) = events.filter { it["event"].asText() == event }
            val started = of("attempt_started")
            assertEquals(listOf(1, 2, 3, 4, 1), started.map { it["attempt"].asInt() }, "one attempt per HTTP request")
            val finished = of("attempt_finished")
            assertEquals(listOf(429, 529, 500, null, 500), finished.map { it["httpStatus"].takeUnless { n -> n.isNull }?.asInt() })
            assertEquals(listOf("rate_limit_error", "overloaded_error", "api_error", null, "api_error"),
                finished.map { it["providerCode"].takeUnless { n -> n.isNull }?.asText() })
            assertEquals("1", finished[0]["responseHeaders"]["retry-after"].asText())
            assertEquals("600", finished[2]["responseHeaders"]["retry-after-ms"].asText())
            assertEquals("false", finished[4]["responseHeaders"]["x-should-retry"].asText())
            val waits = of("retry_wait")
            assertEquals(listOf(1, 2, 3), waits.map { it["retry"].asInt() })
            assertTrue(waits.all { it["requestId"].asText() == started[0]["requestId"].asText() })
            assertTrue(waits[0]["delayMs"].asLong() >= 1000)
            assertTrue(waits[1]["delayMs"].asLong() < 1000, "the 529 named no wait, so its backoff stays under the cap and jitter")
            assertTrue(waits[2]["delayMs"].asLong() >= 600)
        } finally {
            if (previousLedger == null) System.clearProperty("afield.ai.usageLog.path") else System.setProperty("afield.ai.usageLog.path", previousLedger)
            if (previousCap == null) System.clearProperty("afield.ai.rateLimitMaxDelayMs") else System.setProperty("afield.ai.rateLimitMaxDelayMs", previousCap)
            dir.toFile().deleteRecursively()
        }
    }
}
