package afield.ai.conversation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The chat panel rebuilds only the units whose HTML changed (outputareas2.js, renderChatUnits). That is only
 * cheap if appending to a transcript leaves the earlier units byte-identical, and only correct if the units
 * add up to what [aiChatHistoryHtml] renders.
 */
class AiChatHistoryUnitsTest {
    private val history = listOf(
        "<<you>>\nMake a sphere",
        "<<ai>>\nHere is one:\n```kotlin\nval s = sphere()\n```",
        "<<progress>>\nRead box",
        "<<progress>>\nEditFile {\"file\": \"a.kt\"}\nchanged",
        "<<ai>>\nDone, see `s`.",
    ).joinToString("\n")

    @Test
    fun unitsJoinToTheWholeThread() {
        val units = aiChatHistoryUnits(history, null)
        assertTrue(units.size >= 4, "expected one unit per turn or run, got $units")
        assertEquals("<div class=\"ai-chat-thread\">${units.joinToString("")}</div>", aiChatHistoryHtml(history, null))
    }

    @Test
    fun streamingChangesOnlyTheTail() {
        val before = aiChatHistoryUnits("$history\n<<you>>\nNow red", null)
        val after = aiChatHistoryUnits("$history\n<<you>>\nNow red\n<<ai>>\nPaint", null)
        val streamed = aiChatHistoryUnits("$history\n<<you>>\nNow red\n<<ai>>\nPainting it red", null)
        assertEquals(before, after.dropLast(1))
        assertEquals(after.dropLast(1), streamed.dropLast(1))
    }

    @Test
    fun aQuestionClosesOnceAnswered() {
        val asked = "<<you>>\nGo\n<<progress>>\nAskUserQuestion\nKeep it?"
        val open = aiChatHistoryUnits(asked, null)
        val answered = aiChatHistoryUnits("$asked\n<<you>>\nYes", null)
        assertEquals(aiChatHistoryHtml(asked, null), "<div class=\"ai-chat-thread\">${open.joinToString("")}</div>")
        assertEquals(open.size + 1, answered.size)
    }
}
