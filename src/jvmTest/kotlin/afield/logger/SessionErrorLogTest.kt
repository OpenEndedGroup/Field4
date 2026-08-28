package afield.logger

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which stderr lines are worth privileging.
 *
 * The lines below are the ones that went missing: Ktor answered `GET /api/source` with a
 * `NoClassDefFoundError`, slf4j-simple wrote the whole trace to `System.err`, and because nothing on
 * that path passes through the log dispatcher it never entered the pinned console's scrollback ring
 * — so the next repaint cleared it, and the terminal kept no scrollback of its own for a region
 * under DECSTBM. It was on screen for an instant and then unrecoverable.
 */
class SessionErrorLogTest {

    private val ktorError =
        "[eventLoopGroupProxy-4-9] ERROR io.ktor.server.Application - Unhandled: GET - /api/source"

    @Test
    fun `an slf4j ERROR line opens a record`() {
        assertTrue(SessionErrorLog.startsRecord(ktorError))
    }

    @Test
    fun `a bare throwable header opens a record`() {
        assertTrue(SessionErrorLog.startsRecord("java.lang.NoClassDefFoundError: afield/SourceFileResponse"))
        assertTrue(SessionErrorLog.startsRecord("java.lang.IllegalStateException"))
        assertTrue(SessionErrorLog.startsRecord("kotlin.KotlinNullPointerException: nope"))
    }

    /**
     * The throwable arrives on the line *after* the slf4j line. Treating it as a fresh record would
     * pin the header and drop the stack trace that says where it came from.
     */
    @Test
    fun `the throwable under an slf4j line continues the same record`() {
        assertTrue(SessionErrorLog.continuesRecord("java.lang.NoClassDefFoundError: afield/SourceFileResponse"))
    }

    @Test
    fun `stack frames, causes and the elision tail all continue a record`() {
        assertTrue(SessionErrorLog.continuesRecord("\tat afield.Server\$Companion.invoke(Server.kt:4363)"))
        assertTrue(SessionErrorLog.continuesRecord("    at io.ktor.server.routing.RoutingNode.invoke(RoutingNode.kt:127)"))
        assertTrue(SessionErrorLog.continuesRecord("Caused by: java.lang.ClassNotFoundException: afield.SourceFileResponse"))
        assertTrue(SessionErrorLog.continuesRecord("Suppressed: java.io.IOException: closed"))
        assertTrue(SessionErrorLog.continuesRecord("\t... 66 more"))
    }

    /** Ordinary chatter must not be dressed up as a failure, or the panel means nothing. */
    @Test
    fun `ordinary stderr chatter is not a failure`() {
        val chatter = listOf(
            "[debug,output-backpressure] session=69524f9c event=ack pending=0/8 buffered=0/4",
            "SLF4J: Actual binding is of type [org.slf4j.simple.SimpleLoggerFactory]",
            "warning: something happened",
            "",
            "  indented continuation prose that is not a stack frame",
        )
        for (line in chatter) {
            assertFalse(SessionErrorLog.startsRecord(line), "should not open a record: $line")
            assertFalse(SessionErrorLog.continuesRecord(line), "should not continue a record: $line")
        }
    }

    /** `Error`/`Exception` has to be the *type*, not a word in a sentence. */
    @Test
    fun `prose mentioning an error is not a throwable header`() {
        assertFalse(SessionErrorLog.startsRecord("could not open the file, see Error above"))
        assertFalse(SessionErrorLog.startsRecord("Exception handling is configured"))
    }
}
