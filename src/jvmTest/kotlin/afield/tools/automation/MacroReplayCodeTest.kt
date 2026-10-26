package afield.tools.automation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A replayed `execute` / `launch` runs the box as it is now. Before 2026-10-24 every recorded
 * launch carried a copy of the box's code and replay ran the copy, so a macro recorded before
 * `target.apply()` became `+target` kept launching the old line and failed to compile, however
 * the boxes had been fixed since.
 */
class MacroReplayCodeTest {

    private val live = "val a = 1\nprintln(a)\n+target\nplaylist = 7.0"

    @Test
    fun aWholeBoxLaunchRunsTheBoxsCurrentCodeNotTheRecordedCopy() {
        val stale = "val a = 1\nprintln(a)\ntarget.apply()\nplaylist = 7.0"
        val resolved = MacroReplayCode.resolve("launch", mapOf("box" to "b", "code" to stale, "type" to "launch", "line" to 0), live)
        assertEquals(live, resolved.code)
        assertNotNull(resolved.note, "a replay that runs something other than what was recorded says so")
    }

    @Test
    fun anUnchangedLaunchRunsQuietly() {
        val resolved = MacroReplayCode.resolve("launch", mapOf("box" to "b", "code" to live), live)
        assertEquals(live, resolved.code)
        assertNull(resolved.note)
    }

    @Test
    fun aLaunchOfAnEmptyBoxFallsBackToTheOpsCode() {
        assertEquals("println(1)", MacroReplayCode.resolve("launch", mapOf("code" to "println(1)"), "").code)
        assertEquals("", MacroReplayCode.resolve("launch", mapOf<String, Any?>(), null).code)
    }

    @Test
    fun codeSourceRecordedRunsTheOpsCodeAsWritten() {
        val resolved = MacroReplayCode.resolve("launch", mapOf("code" to "println(1)", "codeSource" to "recorded"), live)
        assertEquals("println(1)", resolved.code)
    }

    @Test
    fun anAuthoredFragmentRunsItsSnippet() {
        // A transcript's probe: not in the box, run in its context.
        val resolved = MacroReplayCode.resolve("execute", mapOf("code" to "println(state)", "type" to "fragment"), live)
        assertEquals("println(state)", resolved.code)
        assertEquals(0, resolved.line)
    }

    @Test
    fun aBareExecuteRunsTheWholeCurrentProperty() {
        assertEquals(live, MacroReplayCode.resolve("execute", mapOf("box" to "b"), live).code)
    }

    @Test
    fun aRecordedFragmentIsFoundWhereItNowIs() {
        // Recorded on line 0; two lines were added above it since.
        val resolved = MacroReplayCode.resolve("execute", mapOf("recordedCode" to "println(a)", "line" to 0, "type" to "fragment"), live)
        assertEquals("println(a)", resolved.code)
        assertEquals(1, resolved.line)
        assertNotNull(resolved.note)
    }

    @Test
    fun aRecordedFragmentThatWasEditedRunsTheCurrentLinesAtItsPlace() {
        val resolved = MacroReplayCode.resolve(
            "execute", mapOf("recordedCode" to "target.apply()", "line" to 2, "type" to "fragment", "singleLine" to true), live
        )
        assertEquals("+target", resolved.code)
        assertEquals(2, resolved.line)
        assertNotNull(resolved.note)
    }

    @Test
    fun aRecordedFragmentPastTheEndOfAShorterBoxRunsItsLastLines() {
        val resolved = MacroReplayCode.resolve("execute", mapOf("recordedCode" to "x\ny", "line" to 40), "one\ntwo\nthree")
        assertEquals("two\nthree", resolved.code)
        assertEquals(1, resolved.line)
    }

    @Test
    fun aRecordedFragmentLaunchIsResolvedLikeAFragment() {
        val resolved = MacroReplayCode.resolve("launch", mapOf("recordedCode" to "println(a)", "line" to 1, "type" to "fragment"), live)
        assertEquals("println(a)", resolved.code)
        assertEquals(1, resolved.line)
        assertNull(resolved.note)
    }
}
