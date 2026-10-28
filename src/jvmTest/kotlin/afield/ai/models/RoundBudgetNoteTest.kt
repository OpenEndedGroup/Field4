package afield.ai.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoundBudgetNoteTest {
    /** 2026-10-28: a local model read for 17 of 24 rounds and hit the cap before its code compiled. */
    @Test
    fun theBudgetAppearsFromHalfwayAndPressesNearTheEnd() {
        assertNull(OpenAiIdeCodexBridge.roundBudgetNote(11, 24))
        assertEquals("Tool round 12 of 24: 12 left in this turn. Leave rounds to run what you write and look at it.",
            OpenAiIdeCodexBridge.roundBudgetNote(12, 24))
        val late = OpenAiIdeCodexBridge.roundBudgetNote(22, 24)!!
        assertTrue(late.startsWith("Tool round 22 of 24: 2 left") && "Run and check" in late, late)
    }
}
