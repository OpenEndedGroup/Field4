package afield.ai.diagnostics

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Mute, pin and the LogStream filter share one rule: every tag of the selector is a tag of the channel. */
class ChannelMatchTest {
    @Test
    fun `a selector matches by tags, not by string or substring`() {
        assertTrue(ChannelMatch.matches("info,boxes", "boxes"))
        assertTrue(ChannelMatch.matches("boxes,info", "info,boxes"))
        assertTrue(ChannelMatch.matches("info,boxes,slow", "info, boxes"))
        assertFalse(ChannelMatch.matches("info,kotlinjs", "kotlin"))
        assertFalse(ChannelMatch.matches("info,Boxes", "boxes"))
        assertFalse(ChannelMatch.matches("boxes", "info,boxes"))
        assertFalse(ChannelMatch.matches("info,boxes", ""))
    }
}
