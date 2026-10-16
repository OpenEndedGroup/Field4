package afield.ai.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals

/** A terminal line inside nested `lblock`s is indented by depth, not by the blocks' UUIDs. */
class StdoutLogIndentTest {
    @Test
    fun `an lblock indent prints as two spaces per level`() {
        val u1 = "0f8e7c3a-1d2b-4c5d-8e9f-0a1b2c3d4e5f"
        val u2 = "1a2b3c4d-5e6f-4a1b-9c8d-7e6f5a4b3c2d"
        assertEquals("", StdoutLog.depthIndent(""))
        assertEquals("  ", StdoutLog.depthIndent("$u1\t"))
        assertEquals("    ", StdoutLog.depthIndent("$u1\t$u2\t"))
    }
}
