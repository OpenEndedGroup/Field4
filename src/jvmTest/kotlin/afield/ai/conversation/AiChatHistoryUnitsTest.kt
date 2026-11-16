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
    fun aResumedParseMatchesAFreshOne() {
        val runModel = "<<run-model:{\"provider\":\"openai\",\"requestedModel\":\"m\"}>>\n"
        for (start in listOf("", "stray text before any marker\n", runModel)) {
            val full = start + history + "\n<<you>>\nNow red\n<<progress-open>>\nWaiting for model\n<<ai>>\nPainting it red, done."
            var previous: AiChatParse? = null
            val cache = AiChatUnitCache()
            // Every prefix, as a stream delivers it: resuming the last parse must agree with parsing from scratch.
            for (end in 1..full.length) {
                val text = full.substring(0, end)
                val resumed = parseAiChatHistory(text, previous)
                assertEquals(parseAiChatBlocks(text), resumed.blocks, "prefix of length $end (start ${start.take(12)})")
                // And the cached units, as the panel renders them, agree with an uncached render.
                assertEquals(aiChatHistoryUnits(text, null), aiChatHistoryUnits(resumed.blocks, null, cache = cache), "units at $end")
                previous = resumed
            }
        }
        // A different transcript does not reuse the old one's blocks.
        val first = parseAiChatHistory(history)
        assertEquals(parseAiChatBlocks("<<you>>\nOther"), parseAiChatHistory("<<you>>\nOther", first).blocks)
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
