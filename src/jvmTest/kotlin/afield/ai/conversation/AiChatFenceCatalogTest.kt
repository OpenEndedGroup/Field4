package afield.ai.conversation

import afield.ai.tools.ToolExecutionContext
import afield.runtime.languages.KOTLIN_SCRIPT

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiChatFenceCatalogTest {
    @Test
    fun packagedCatalogSuppliesChatInstructions() {
        val mermaid = AiChatFenceCatalog.entries.single { it.language == "mermaid" }
        assertEquals("/mermaid/field-mermaid.js", mermaid.modulePath)
        val chat = buildInstructionFragments(InstructionMode.Chat, KOTLIN_SCRIPT, "draw it", null, null,
            ToolExecutionContext())
        val guidance = chat.single { it.source == "fences.catalogue" }.text
        assertContains(guidance, "`${mermaid.language}` (${mermaid.name})")
        assertContains(guidance, mermaid.purpose)
        assertContains(guidance, mermaid.syntaxGuidance)
        assertContains(guidance, "```mermaid\n")
        mermaid.example.lineSequence().forEach { assertContains(guidance, it) }
        assertFalse(buildInstructionFragments(InstructionMode.General, KOTLIN_SCRIPT, "", null, null,
            ToolExecutionContext()).any { it.source == "fences.catalogue" })
    }

    @Test
    fun mathFenceTeachesTheInlineDelimitersToo() {
        val math = AiChatFenceCatalog.entries.single { it.language == "math" }
        assertEquals("/katex/field-katex.js", math.modulePath)
        assertEquals("renderMathFence", math.exportName)
        val guidance = AiChatFenceCatalog.instructionText()
        assertContains(guidance, "\\( ... \\)")
        assertContains(guidance, "$$ ... $$")
        assertContains(guidance, "```math\n")
    }

    @Test
    fun aSecondEntryIsAdvertisedWithoutEditingThePrompt() {
        val second = AiChatFenceCatalog.Entry("fixture", "Fixture", "Test relationships", "Write nodes and links",
            "a --> b", "/fixture.js", "renderFixture")
        val guidance = AiChatFenceCatalog.instructionText(AiChatFenceCatalog.entries + second)
        assertTrue(guidance.contains("```mermaid\n"))
        assertContains(guidance, "`fixture` (Fixture)")
        assertContains(guidance, "```fixture\n  a --> b\n  ```")
    }
}
