package afield.runtime.lifecycle

import afield.runtime.lifecycle.BoxInstrumentation.Mode
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The three-way switch: which gates each mode opens, a session value over the preference, and the
 * spellings the preference, the environment and the HTTP API accept. (The environment override is
 * read once per JVM, so it is not exercised here; the test JVM runs without one.)
 */
class BoxInstrumentationTest {

    @AfterTest
    fun reset() {
        BoxInstrumentation.clearSession()
        BoxInstrumentation.applyPreference(Mode.WATCHDOG)
    }

    @Test
    fun eachModeOpensItsOwnGates() {
        BoxInstrumentation.applyPreference(Mode.WATCHDOG)
        assertTrue(BoxInstrumentation.instrumenting)
        assertTrue(BoxInstrumentation.watchdogInterrupts)
        assertTrue(BoxInterrupt.enabled)

        BoxInstrumentation.applyPreference(Mode.INSTRUMENT)
        assertTrue(BoxInstrumentation.instrumenting)
        assertFalse(BoxInstrumentation.watchdogInterrupts)
        assertTrue(BoxInterrupt.enabled)

        BoxInstrumentation.applyPreference(Mode.OFF)
        assertFalse(BoxInstrumentation.instrumenting)
        assertFalse(BoxInstrumentation.watchdogInterrupts)
        assertFalse(BoxInterrupt.enabled)
    }

    @Test
    fun aSessionValueWinsOverThePreferenceUntilCleared() {
        BoxInstrumentation.applyPreference(Mode.OFF)
        BoxInstrumentation.mode = Mode.INSTRUMENT
        assertEquals(Mode.INSTRUMENT, BoxInstrumentation.mode)
        assertEquals("session", BoxInstrumentation.status().source)
        assertEquals("off", BoxInstrumentation.status().preference)

        // Saving a preference later does not disturb the session value...
        BoxInstrumentation.applyPreference(Mode.WATCHDOG)
        assertEquals(Mode.INSTRUMENT, BoxInstrumentation.mode)

        // ...until it is cleared.
        BoxInstrumentation.clearSession()
        assertEquals(Mode.WATCHDOG, BoxInstrumentation.mode)
        assertEquals("preference", BoxInstrumentation.status().source)
    }

    @Test
    fun parseAcceptsKeysAndObviousSynonyms() {
        for (m in Mode.entries) assertEquals(m, Mode.parse(m.key))
        assertEquals(Mode.WATCHDOG, Mode.parse(" On "))
        assertEquals(Mode.INSTRUMENT, Mode.parse("no-watchdog"))
        assertEquals(Mode.OFF, Mode.parse("0"))
        assertNull(Mode.parse(""))
        assertNull(Mode.parse(null))
        assertNull(Mode.parse("sometimes"))
    }
}
