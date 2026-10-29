package afield.ai.models

import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.ResponseInputItem.Message.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalTemplateSafeInputTest {
    private fun message(role: Role, text: String) =
        ResponseInputItem.ofMessage(ResponseInputItem.Message.builder().role(role).addInputTextContent(text).build())

    private fun roles(items: List<ResponseInputItem>) = items.map { it.message().get().role() }

    /** 2026-10-28: Qwen's chat template rejected a developer message after the first item, twice, with HTTP 500. */
    @Test
    fun onlyTheLeadingSystemMessageStaysSystem() {
        val input = listOf(
            message(Role.DEVELOPER, "instructions"),
            message(Role.USER, "draw five lines"),
            message(Role.DEVELOPER, "The tool budget for this turn has been reached."),
            message(Role.SYSTEM, "late system"),
        )
        val (safe, moved) = OpenAiIdeCodexBridge.localTemplateSafeInput(input)
        assertEquals(listOf(Role.DEVELOPER, Role.USER, Role.USER, Role.USER), roles(safe))
        assertEquals(2, moved.size)
        assertTrue(moved[0].startsWith("#2 developer: The tool budget"), moved[0])
        assertEquals("The tool budget for this turn has been reached.",
            safe[2].message().get().content().single().inputText().get().text())
    }

    @Test
    fun aCleanInputIsUntouched() {
        val input = listOf(message(Role.DEVELOPER, "instructions"), message(Role.USER, "hi"))
        val (safe, moved) = OpenAiIdeCodexBridge.localTemplateSafeInput(input)
        assertEquals(input, safe)
        assertTrue(moved.isEmpty())
    }
}
