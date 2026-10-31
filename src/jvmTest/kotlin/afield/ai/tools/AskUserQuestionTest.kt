package afield.ai.tools

import afield.ai.conversation.AiChatBlock
import afield.ai.conversation.AskedQuestion
import afield.ai.conversation.AwaitedBuild
import afield.ai.conversation.TurnSuspension
import afield.ai.conversation.aiChatHistoryHtml
import afield.ai.conversation.parseAiChatBlocks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AskUserQuestionTest {
    @Test
    fun aQuestionSuspendsTheTurnWithItsChoices() {
        val r = askUserQuestion(AskUserQuestionRequest("Keep the old mesh?", listOf(" Keep it ", "Replace it", "Keep it", "")))
        assertTrue(r.success && r.suspended)
        assertEquals(AskedQuestion("Keep the old mesh?", listOf("Keep it", "Replace it")), r.suspension?.question)
        assertTrue(r.suspension!!.jobs.isEmpty())
    }

    @Test
    fun aBlankQuestionOrTooManyChoicesFailsWithoutSuspending() {
        assertNull(askUserQuestion(AskUserQuestionRequest("  ")).suspension)
        val many = askUserQuestion(AskUserQuestionRequest("Which?", (1..7).map { "option $it" }))
        assertFalse(many.success)
        assertNull(many.suspension)
    }

    @Test
    fun mergingKeepsTheQuestionAndTheBuilds() {
        val build = TurnSuspension(listOf(AwaitedBuild("b", "mesh", "j1")), 1_000)
        val merged = TurnSuspension(question = AskedQuestion("Which?")).merge(build)
        assertEquals("Which?", merged.question?.question)
        assertEquals(1, merged.jobs.size)
        assertEquals(1_000, merged.deadlineMs)
    }

    @Test
    fun theQuestionRowRoundTripsThroughTheTranscript() {
        val history = "<<you>>\nmake a mesh\n<<ai>>\nI found an old one.\n" +
            AiChatBlock.questionRow("Keep the **old** mesh?", listOf("Keep it", "Replace it"))
        val q = parseAiChatBlocks(history).last()
        assertTrue(q.isQuestion)
        assertEquals("Keep the **old** mesh?", q.questionText)
        assertEquals(listOf("Keep it", "Replace it"), q.questionChoices)
        val free = parseAiChatBlocks(AiChatBlock.questionRow("What should it be called?", emptyList())).single()
        assertTrue(free.isQuestion && free.questionChoices.isEmpty())
    }

    @Test
    fun onlyAnUnansweredQuestionHasLiveButtons() {
        val asked = "<<you>>\nmake a mesh\n" + AiChatBlock.questionRow("Keep it?", listOf("Yes", "No"))
        val open = aiChatHistoryHtml(asked, null)
        assertTrue("ai-chat-question open" in open, open)
        assertTrue("data-ai-answer=\"Yes\">" in open, open)
        assertFalse("disabled" in open, open)
        assertFalse("ai-chat-round" in open, "a question is never folded into a round: $open")
        val answered = aiChatHistoryHtml(asked + "<<you>>\nYes\n<<ai>>\nKept.", null)
        assertFalse("ai-chat-question open" in answered, answered)
        assertTrue("data-ai-answer=\"Yes\" disabled" in answered, answered)
    }
}
