package afield.logger

import afield.ai.core.ConsoleMute
import afield.ai.core.LogEvent
import afield.ai.core.StdoutLog
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The box-resync channel (`info,boxes`) is muted from the console by default. Both console sinks
 * (StdoutLog here; PinnedConsole's sink shares the same [ConsoleMute.isMuted] gate) drop it, while
 * every other channel still prints. Overriding via `-Dafield.log.mute` isn't exercised here because
 * [ConsoleMute] reads it once at class-load; this covers the default that field.sh runs with.
 */
class ConsoleMuteTest {

    @Test
    fun `info,boxes is muted by default but sibling channels are not`() {
        assertTrue(ConsoleMute.isMuted("info,boxes"), "info,boxes should be muted by default")
        assertFalse(ConsoleMute.isMuted("warning,boxes"), "warnings on the boxes subsystem stay visible")
        assertFalse(ConsoleMute.isMuted("info,perf"), "unrelated channels stay visible")
    }

    @Test
    fun `documented spellings require exact case-sensitive whole-channel membership`() {
        val spellings = listOf("info,boxes", "boxes,info", "info,boxes,extra", "info,Boxes", "boxes")
        assertEquals(
            listOf(true, false, false, false, false),
            spellings.map(ConsoleMute::isMuted),
            "muting must not reorder tags, match substrings, or fold case"
        )
    }

    @Test
    fun `StdoutLog drops muted channels and prints the rest`() {
        val printed = captureStdout {
            StdoutLog.log(LogEvent(channel = "info,boxes", message = "resync should be hidden"))
            StdoutLog.log(LogEvent(channel = "info,perf", message = "perf should show"))
        }
        assertFalse(printed.contains("resync should be hidden"), "muted line leaked to stdout")
        assertTrue(printed.contains("perf should show"), "non-muted line was dropped")
    }

    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return buffer.toString(Charsets.UTF_8)
    }
}
