package afield.ai.core

import kotlin.test.*

class AiLiveConversationTest {
    @Test fun explanationsStayBetweenTheAttemptsTheyDescribe() {
        val c = AiLiveConversation()
        c.text("I will check the route.")
        c.progress(LlmProgressUpdate("Routing", "Fitting route A", phase = "build"))
        c.progress(LlmProgressUpdate("Check failed", "Radius too small", phase = "build_result"))
        c.text("I will move the light before retrying.")
        c.progress(LlmProgressUpdate("Routing", "Fitting route B", phase = "build"))
        val text = c.render("<<you>>\nPlease route", null)
        val expected = listOf("I will check", "Radius too small", "I will move", "Fitting route B")
        assertEquals(expected.map { text.indexOf(it) }.sorted(), expected.map { text.indexOf(it) })
        assertTrue(expected.all { text.contains(it) })
        assertContains(text, "<<progress-open>>\nRouting\nFitting route B")
        assertFalse(text.contains("Fitting route A"))
    }

    @Test fun changingProgressStaysBoundedWithoutLosingFailures() {
        val c = AiLiveConversation()
        c.progress(LlmProgressUpdate("Check failed", "Retained first failure", phase = "build_result"))
        repeat(100) { c.progress(LlmProgressUpdate("Routing $it", "Same attempt", phase = "build")) }
        val text = c.render("", null)
        assertContains(text, "Retained first failure")
        assertContains(text, "Routing 99")
        assertEquals(2, Regex("<<progress(?:-open)?>>").findAll(text).count())
    }

    @Test fun finalReplyReplacesOnlyItsStreamedCopyAndKeepsEarlierCommentary() {
        val c = AiLiveConversation()
        c.text("Checking now.")
        c.progress(LlmProgressUpdate("Tool finished", phase = "tool_result"))
        c.text("Routes "); c.text("passed.")
        c.progress(LlmProgressUpdate("Summary", "Provider summary"))
        c.progress(LlmProgressUpdate("Completed", phase = "completed"))
        val text = c.render("", null, live = false, finalText = "Routes passed.\n\nChecklist")
        assertContains(text, "Checking now.")
        assertEquals(1, Regex("Routes passed").findAll(text).count())
        assertFalse(text.contains("progress-open"))
    }

    @Test fun interruptedResponseKeepsPartialTextAndFailure() {
        val c = AiLiveConversation()
        c.text("I found a tight corner.")
        val text = c.render("", null, live = false, finalText = "_Connection failed_")
        assertContains(text, "I found a tight corner.")
        assertContains(text, "Connection failed")
    }
}
