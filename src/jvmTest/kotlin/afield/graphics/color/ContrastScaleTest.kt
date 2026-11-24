package afield.graphics.color

import afield.math.linalg.Vec4
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Reproduces the numbers in TaishoDS's "accessible colour palette from one colour" post. */
class ContrastScaleTest {
    private val white = Vec4(1f, 1f, 1f, 1f)
    private val nearBlack = colorHex("#0a0a0a")

    private fun same(a: Vec4, b: Vec4) = listOf(a.x - b.x, a.y - b.y, a.z - b.z).all { kotlin.math.abs(it) < 1e-6f }

    private fun rounded8(c: Vec4) = Vec4(
        (c.x * 255f).roundToInt() / 255f, (c.y * 255f).roundToInt() / 255f, (c.z * 255f).roundToInt() / 255f, 1f
    )

    @Test
    fun tailwindBlueStaysExactOn500LightAnd600Dark() {
        val blue = colorHex("#3b82f6")
        val light = contrastScale(blue, white)
        assertEquals(500, light.seedStep)
        assertTrue(same(blue, light[500].color))
        val dark = contrastScale(blue, nearBlack)
        assertEquals(600, dark.seedStep)
        assertTrue(same(blue, dark[600].color))
    }

    @Test
    fun step500ContrastOnWhiteMatchesThePost() {
        // Tailwind 4 500s in their sRGB form. Blue and red are kept as given (the post's 3.70 / 3.82
        // are measured before 8-bit rounding, these hexes measure 3.76 / 3.81 after it); green and
        // yellow are too light for 500, land on 400, and 500 is computed to the 3.6 target.
        val expected = mapOf("#2b7fff" to 3.70, "#fb2c36" to 3.82, "#00c950" to 3.60, "#f0b100" to 3.60)
        val tolerance = mapOf("#2b7fff" to 0.08, "#fb2c36" to 0.03, "#00c950" to 0.02, "#f0b100" to 0.02)
        val report = expected.map { (hex, want) ->
            val got = contrastScale(colorHex(hex), white)[500].achieved
            assertEquals(want, got, tolerance[hex]!!, "$hex step 500")
            "$hex=${"%.3f".format(got)}"
        }
        println("step-500 contrast on white: $report")
        assertEquals(400, contrastScale(colorHex("#00c950"), white).seedStep)
    }

    @Test
    fun yellowRotatesTowardOrangeAsItDarkens() {
        val s = contrastScale(colorHex("#eab308"), white)
        val hues = s.colors.map { colorCoordinates(it, ColorSpace.OKLCH).z }
        println("yellow hues 100..1000: ${hues.map { it.roundToInt() }}")
        assertTrue(hues.first() > hues.last() + 30.0, "hue should fall from 100 to 1000: $hues")
    }

    @Test
    fun sweepOf4096SeedsNeverDropsATextStepBelow4p5() {
        var minComputed = Double.MAX_VALUE
        var min8 = Double.MAX_VALUE
        var monotoneFailures = 0
        for (bg in listOf(white, nearBlack)) for (r in 0 until 16) for (g in 0 until 16) for (b in 0 until 16) {
            val seed = Vec4(r * 17 / 255f, g * 17 / 255f, b * 17 / 255f, 1f)
            val s = contrastScale(seed, bg)
            for (i in 5..9) {
                minComputed = minOf(minComputed, s.swatches[i].achieved)
                min8 = minOf(min8, wcagContrast(rounded8(s.colors[i]), bg))
            }
            if ((1 until 10).any { s.swatches[it].achieved + 1e-3 < s.swatches[it - 1].achieved }) monotoneFailures++
        }
        println("sweep 8192 scales: min text-step contrast computed=${"%.3f".format(minComputed)} 8-bit=${"%.3f".format(min8)} nonMonotone=$monotoneFailures")
        assertTrue(minComputed >= 4.5, "min computed $minComputed")
        assertTrue(min8 >= 4.5, "min 8-bit $min8")
    }
}
