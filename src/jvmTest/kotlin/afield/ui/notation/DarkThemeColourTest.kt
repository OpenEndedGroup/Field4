package afield.ui.notation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DarkThemeColourTest {
    private fun channels(hex: String) = (0..2).map { hex.removePrefix("#").substring(2 * it, 2 * it + 2).toInt(16) }

    private fun maxError(requested: String): Int {
        val shown = DarkThemeColour.shown(DarkThemeColour.compensate(requested))
        return channels(requested).zip(channels(shown)).maxOf { (a, b) -> kotlin.math.abs(a - b) }
    }

    @Test
    fun greyAndGreenComeOutExact() {
        assertTrue(maxError("#808080") <= 1, "grey off by ${maxError("#808080")}")
        assertTrue(maxError("#2f9e44") <= 1, "green off by ${maxError("#2f9e44")}")
    }

    @Test
    fun blueComesOutClose() {
        assertTrue(maxError("#1971c2") <= 6, "blue off by ${maxError("#1971c2")}")
    }

    @Test
    fun nonHexPassesThrough() {
        assertEquals("transparent", DarkThemeColour.compensate("transparent"))
        assertEquals("#1e1e1e", DarkThemeColour.compensate("#1e1e1e"))
    }
}
