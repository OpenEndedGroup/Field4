package afield.four.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WindowDisplayTest {

    @Test
    fun primaryVideoModeExplainsMissingMonitorInsteadOfDereferencingIt() {
        val failure = assertFailsWith<IllegalStateException> {
            requirePrimaryVideoMode(primaryMonitor = { 0L }, videoMode = { "unused" })
        }
        assertTrue(failure.message.orEmpty().contains("No usable display"), failure.message)
        assertTrue(failure.message.orEmpty().contains("no primary monitor"), failure.message)
    }

    @Test
    fun primaryVideoModeExplainsMissingModeAndReturnsAUsableOne() {
        val failure = assertFailsWith<IllegalStateException> {
            requirePrimaryVideoMode(primaryMonitor = { 42L }, videoMode = { null as String? })
        }
        assertTrue(failure.message.orEmpty().contains("No usable display"), failure.message)
        assertTrue(failure.message.orEmpty().contains("video mode"), failure.message)

        assertEquals("mode", requirePrimaryVideoMode(primaryMonitor = { 42L }, videoMode = { "mode" }))
    }
}
