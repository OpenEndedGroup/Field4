package afield.ai.core

import com.anthropic.client.AnthropicClient
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.services.blocking.MessageService
import com.fasterxml.jackson.databind.ObjectMapper
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where a [TurnContext]'s parts go on the Anthropic wire: stable instructions in cached system blocks,
 * volatile ones on the newest message only, and the stored history keeping just the durable prompt —
 * checked on the serialised request bodies of two consecutive turns through a fake client.
 */
class ContextPlacementTest {

    /** The SDK's own mapper, reached reflectively as in AnthropicToolImageTest (it is internal to the SDK). */
    private val mapper: ObjectMapper =
        Class.forName("com.anthropic.core.ObjectMappers").getMethod("jsonMapper").invoke(null) as ObjectMapper

    private val sent = mutableListOf<MessageCreateParams>()

    private val reply: Message = mapper.readValue(
        """{"id":"msg_1","type":"message","role":"assistant","model":"claude-test",
           "content":[{"type":"text","text":"done","citations":null}],
           "stop_reason":"end_turn","stop_sequence":null,
           "usage":{"input_tokens":120,"output_tokens":3,"cache_read_input_tokens":80,"cache_creation_input_tokens":40}}""",
        Message::class.java
    )

    private val client: AnthropicClient = run {
        val messages = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(MessageService::class.java)) { _, method, args ->
            if (method.name == "create" && args?.firstOrNull() is MessageCreateParams) {
                sent += args[0] as MessageCreateParams
                reply
            } else throw UnsupportedOperationException(method.name)
        } as MessageService
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(AnthropicClient::class.java)) { _, method, _ ->
            if (method.name == "messages") messages else throw UnsupportedOperationException(method.name)
        } as AnthropicClient
    }

    private fun body(params: MessageCreateParams): String = mapper.writeValueAsString(params._body())

    private val tc = TurnContext(
        listOf(
            ContextFragment("core.contract", "STABLE-CONTRACT"),
            ContextFragment("guides", "\n\nVOLATILE-CATALOGUE", stability = Stability.VOLATILE),
            ContextFragment("core.request", "\n\nDURABLE-REQUEST", FragmentRetention.DURABLE, Stability.VOLATILE),
        ),
        rest = "the user's words"
    )

    @Test
    fun ephemeralPartsAreSentButNeverStored() {
        val bridge = AnthropicBridge(client = client, model = "claude-test")
        val state = bridge.startThread("k", "", "THREAD-INSTRUCTIONS")
        val first = bridge.send(state, tc.legacyPrompt(), ctx = ToolExecutionContext(), turnContext = tc)
        bridge.send(state, tc.legacyPrompt(), ctx = ToolExecutionContext(), turnContext = tc)
        assertEquals(2, sent.size)

        val turn1 = mapper.readTree(body(sent[0]))
        val system = turn1["system"].map { it["text"].asText() }
        assertEquals(listOf("THREAD-INSTRUCTIONS", "STABLE-CONTRACT"), system)
        assertTrue(turn1["system"].last().has("cache_control"), "the stable system block carries a breakpoint")
        assertTrue(turn1.has("cache_control"), "automatic caching for the conversation tail")
        assertTrue(turn1["tools"].last().has("cache_control"), "the last tool definition carries a breakpoint")
        val newest = turn1["messages"].last().toString()
        assertTrue("VOLATILE-CATALOGUE" in newest && "DURABLE-REQUEST" in newest && "the user's words" in newest, newest)
        assertFalse("STABLE-CONTRACT" in newest)

        // Turn 2 resends turn 1's message from history: durable text only.
        val turn2 = mapper.readTree(body(sent[1]))
        val storedFirst = turn2["messages"][0].toString()
        assertTrue("DURABLE-REQUEST" in storedFirst && "the user's words" in storedFirst, storedFirst)
        assertFalse("VOLATILE-CATALOGUE" in storedFirst, storedFirst)
        assertFalse("STABLE-CONTRACT" in storedFirst, storedFirst)

        assertEquals(80L, first.cacheReadTokens)
        assertEquals(40L, first.cacheWriteTokens)
        val report = first.contextReport!!
        assertEquals("system+prefix", report.placement)
        assertEquals(listOf("core.contract", "guides", "core.request"), report.fragments.map { it.source })
    }

    @Test
    fun withoutATurnContextTheRequestIsExactlyTheOldOne() {
        val bridge = AnthropicBridge(client = client, model = "claude-test")
        val state = bridge.startThread("k", "", "THREAD-INSTRUCTIONS")
        bridge.send(state, tc.legacyPrompt(), ctx = ToolExecutionContext())
        val json = mapper.readTree(body(sent.single()))
        assertEquals(listOf("THREAD-INSTRUCTIONS"), json["system"].map { it["text"].asText() })
        assertFalse(json.has("cache_control"))
        assertFalse(json.toString().contains("cache_control"))
        val newest = json["messages"].last().toString()
        assertTrue("STABLE-CONTRACT" in newest && "VOLATILE-CATALOGUE" in newest, newest)
    }

    @Test
    fun theDurablePromptOfAChatTurnIsJustTheRequest() {
        val chat = TurnContext(
            buildInstructionFragments(InstructionMode.Chat, KOTLIN_SCRIPT, "print primes", null, null, ToolExecutionContext()),
            "print primes"
        )
        assertEquals("print primes", chat.durablePrompt())
        assertTrue(chat.allEphemeralText().startsWith("You are an IDE-embedded assistant inside Field"))
        assertFalse(chat.allEphemeralText().contains("User request: print primes"))
    }

    @Test
    fun theContextReportRendersAsATable() {
        val md = tc.report("instructions").copy(inputTokens = 1000, cacheReadTokens = 600).toMarkdown()
        assertTrue(md.contains("| `guides` | this turn only | volatile |"), md)
        assertTrue(md.contains("| `core.request` | stored | volatile |"), md)
        assertTrue(md.contains("600 from cache"), md)
    }
}
