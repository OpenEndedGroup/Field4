package afield.graphics.color

import kotlin.test.Test
import kotlin.test.assertEquals

/** `hsl` must be the colour CSS draws for the same numbers: HSL is defined on sRGB-encoded values. */
class HslTest {
    private fun assertColour(expected: List<Double>, actual: afield.math.linalg.Vec4, what: String) {
        val got = listOf(actual.x, actual.y, actual.z, actual.w).map { it.toDouble() }
        expected.zip(got).forEach { (e, g) -> assertEquals(e, g, 1.0 / 255.0, "$what: expected $expected, got $got") }
    }

    @Test
    fun midGreyIsHalfNotGammaLifted() {
        assertColour(listOf(0.5, 0.5, 0.5, 1.0), hsl(0, 0, 0.5), "hsl(0, 0, 0.5)")
    }

    @Test
    fun orangeMatchesCss() {
        // CSS hsl(30, 100%, 50%) is rgb(255, 128, 0).
        assertColour(listOf(1.0, 128.0 / 255.0, 0.0, 1.0), hsl(30, 1, 0.5), "hsl(30, 1, 0.5)")
    }

    @Test
    fun skyBlueMatchesCss() {
        // CSS hsl(205, 96%, 68%) is rgb(95, 186, 252).
        assertColour(listOf(95 / 255.0, 186 / 255.0, 252 / 255.0, 0.5), hsla(205, 0.96, 0.68, 0.5), "hsla(205, 0.96, 0.68, 0.5)")
    }

    @Test
    fun turnsAndDegreesAgreeWithCssHsla() {
        for (degrees in 0 until 360 step 15) for (s in listOf(0.2, 0.7, 1.0)) for (l in listOf(0.15, 0.5, 0.8)) {
            val css = cssHsla(degrees, s, l)
            val c = css.let { listOf(it.x, it.y, it.z, it.w).map { v -> v.toDouble() } }
            assertColour(c, hsl(degrees / 360.0, s, l), "hsl(${degrees / 360.0}, $s, $l)")
            if (degrees > 1) assertColour(c, hsl(degrees, s, l), "hsl($degrees, $s, $l)")
        }
    }

    @Test
    fun hueSetHslIsCssHsl() {
        val set = hueSetHsl(4, saturation = 0.8, lightness = 0.6)
        set.forEachIndexed { i, c ->
            val css = cssHsla(i * 90, 0.8, 0.6)
            assertColour(listOf(css.x, css.y, css.z, css.w).map { it.toDouble() }, c, "hueSetHsl[$i]")
        }
    }
}
