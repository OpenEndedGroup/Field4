package afield.graphics.color

import afield.math.linalg.Vec4
import afield.ui.visualization.hexColor
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Reproduces the numbers in TaishoDS's "accessible colour palette from one colour" post. */
class ContrastScaleTest {
    private val white = Vec4(1f, 1f, 1f, 1f)
    private val nearBlack = hexColor("#0a0a0a")

    private fun same(a: Vec4, b: Vec4) = listOf(a.x - b.x, a.y - b.y, a.z - b.z).all { kotlin.math.abs(it) < 1e-6f }

    private fun rounded8(c: Vec4) = Vec4(
        (c.x * 255f).roundToInt() / 255f, (c.y * 255f).roundToInt() / 255f, (c.z * 255f).roundToInt() / 255f, 1f
    )

    @Test
    fun tailwindBlueStaysExactOn500LightAnd600Dark() {
        val blue = hexColor("#3b82f6")
        val light = contrastScale(blue, white)
        assertEquals(500, light.seedStep)
        assertTrue(same(blue, light[500]))
        val dark = contrastScale(blue, nearBlack)
        assertEquals(600, dark.seedStep)
        assertTrue(same(blue, dark[600]))
    }

    @Test
    fun step500ContrastOnWhiteMatchesThePost() {
        // Tailwind 4 500s in their sRGB form. Blue and red are kept as given (the post's 3.70 / 3.82
        // are measured before 8-bit rounding, these hexes measure 3.76 / 3.81 after it); green and
        // yellow are too light for 500, land on 400, and 500 is computed to the 3.6 target.
        val expected = mapOf("#2b7fff" to 3.70f, "#fb2c36" to 3.82f, "#00c950" to 3.60f, "#f0b100" to 3.60f)
        val tolerance = mapOf("#2b7fff" to 0.08f, "#fb2c36" to 0.03f, "#00c950" to 0.02f, "#f0b100" to 0.02f)
        val report = expected.map { (hex, want) ->
            val got = contrastScale(hexColor(hex), white).contrastAt(500)
            assertEquals(want, got, tolerance[hex]!!, "$hex step 500")
            "$hex=${"%.3f".format(got)}"
        }
        println("step-500 contrast on white: $report")
        assertEquals(400, contrastScale(hexColor("#00c950"), white).seedStep)
    }

    @Test
    fun yellowRotatesTowardOrangeAsItDarkens() {
        val s = contrastScale(hexColor("#eab308"), white)
        val hues = s.colors.map { c -> val l = c.let { linearOklchHue(it) }; l }
        println("yellow hues 100..1000: ${hues.map { it.roundToInt() }}")
        assertTrue(hues.first() > hues.last() + 30f, "hue should fall from 100 to 1000: $hues")
    }

    private fun linearOklchHue(c: Vec4): Float {
        // hue of the colour via the public oklch round trip: search is overkill, use atan2 on Oklab
        fun inv(v: Float) = if (v > 0.04045f) Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat() else v / 12.92f
        val r = inv(c.x); val g = inv(c.y); val b = inv(c.z)
        val l = Math.cbrt((0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b).toDouble())
        val m = Math.cbrt((0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b).toDouble())
        val s = Math.cbrt((0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b).toDouble())
        val a = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
        val bb = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
        val h = Math.toDegrees(Math.atan2(bb, a))
        return (if (h < 0) h + 360 else h).toFloat()
    }

    @Test
    fun sweepOf4096SeedsNeverDropsATextStepBelow4p5() {
        var minComputed = Float.MAX_VALUE
        var min8 = Float.MAX_VALUE
        var monotoneFailures = 0
        for (bg in listOf(white, nearBlack)) for (r in 0 until 16) for (g in 0 until 16) for (b in 0 until 16) {
            val seed = Vec4(r * 17 / 255f, g * 17 / 255f, b * 17 / 255f, 1f)
            val s = contrastScale(seed, bg)
            for (i in 5..9) {
                minComputed = minOf(minComputed, s.contrasts[i])
                min8 = minOf(min8, wcagContrast(rounded8(s.colors[i]), bg))
            }
            if ((1 until 10).any { s.contrasts[it] + 1e-3f < s.contrasts[it - 1] }) monotoneFailures++
        }
        println("sweep 8192 scales: min text-step contrast computed=${"%.3f".format(minComputed)} 8-bit=${"%.3f".format(min8)} nonMonotone=$monotoneFailures")
        assertTrue(minComputed >= 4.5f, "min computed $minComputed")
        assertTrue(min8 >= 4.5f, "min 8-bit $min8")
    }
}
