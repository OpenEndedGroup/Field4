package afield.ai.models

import com.openai.models.responses.ResponseFunctionToolCall
import com.openai.models.responses.ResponseInputImage
import com.openai.models.responses.ResponseInputItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The context-window arithmetic behind the local model's stateless history; see [StatelessHistory]. */
class StatelessHistoryTest {
    private fun user(text: String) = StatelessHistory.userText(text)
    private fun call(id: String) = ResponseInputItem.ofFunctionCall(
        ResponseFunctionToolCall.builder().callId(id).name("RunBoxRequest").arguments("{}").build()
    )
    private fun output(id: String, text: String) = ResponseInputItem.ofFunctionCallOutput(
        ResponseInputItem.FunctionCallOutput.builder().callId(id).output(text).build()
    )
    private fun text(item: ResponseInputItem): String = when {
        item.isMessage() -> item.asMessage().content().mapNotNull { it.inputText().orElse(null)?.text() }.joinToString("|")
        item.isFunctionCallOutput() -> item.asFunctionCallOutput().output().asString()
        else -> item.toString()
    }

    /** No removed call may leave its output behind, and the current turn is never touched. */
    @Test
    fun turnsAreDroppedWholeAndTheCurrentTurnIsKept() {
        val seed = user("seed")
        val history = mutableListOf(seed,
            user("turn 1"), call("a"), output("a", "x".repeat(1000)),
            user("turn 2"), call("b"), output("b", "y".repeat(1000)),
            user("turn 3"), call("c"))
        val current = history.indexOfFirst { text(it) == "turn 3" }
        val removed = StatelessHistory.dropOldestTurns(history, seedCount = 1, protectFrom = current) { StatelessHistory.chars(history) > 1100 }
        assertEquals(6, removed)
        assertSame(seed, history[0])
        assertEquals(StatelessHistory.DROPPED_NOTE, text(history[1]))
        assertEquals("turn 3", text(history[2]))
        assertTrue(history.none { it.isFunctionCallOutput() && it.asFunctionCallOutput().callId() in setOf("a", "b") })

        // A second fit drops nothing it must keep and adds no second note.
        StatelessHistory.dropOldestTurns(history, seedCount = 1, protectFrom = 2) { true }
        assertEquals(listOf("seed", StatelessHistory.DROPPED_NOTE, "turn 3"), history.take(3).map(::text))
    }

    @Test fun settlingSwapsThePromptAndShrinksOldOutputsAndImages() {
        val full = user("24k of instructions\n\nmake an Ulam spiral")
        val durable = user("make an Ulam spiral")
        val picture = ResponseInputItem.ofMessage(ResponseInputItem.Message.builder()
            .role(ResponseInputItem.Message.Role.USER)
            .addInputTextContent("The ViewBoxOutputRequest tool returned 1 image(s)")
            .addContent(ResponseInputImage.builder().imageUrl("data:image/png;base64,AAAA").detail(ResponseInputImage.Detail.HIGH).build())
            .build())
        val history = mutableListOf(user("seed"), full, call("a"), output("a", "z".repeat(5000)), picture, output("b", "short"))
        StatelessHistory.settle(history, from = 1, replace = full to durable)
        assertSame(durable, history[1])
        assertTrue(text(history[3]).length < 1500 && "call the tool again" in text(history[3]))
        assertEquals("short", text(history[5]))
        assertTrue(history[4].asMessage().content().none { it.isInputImage() })
        assertTrue("omitted" in text(history[4]))
    }

    @Test fun theServersRefusalGivesItsWindow() {
        val llama = StatelessHistory.contextOverflow(RuntimeException("wrapped", IllegalStateException(
            "400: request (65833 tokens) exceeds the available context size (65536 tokens), try increasing it")))
        assertNotNull(llama)
        assertEquals(65833, llama.requestedTokens)
        assertEquals(65536, llama.availableTokens)
        val vllm = StatelessHistory.contextOverflow(IllegalStateException(
            "This model's maximum context length is 32768 tokens. However, you requested 40000 tokens in the messages"))
        assertEquals(32768, vllm?.availableTokens)
        assertNull(StatelessHistory.contextOverflow(IllegalStateException("503: Loading model")))
        assertEquals(48_982, StatelessHistory.inputBudget(65_536, 10_000))
    }
}
