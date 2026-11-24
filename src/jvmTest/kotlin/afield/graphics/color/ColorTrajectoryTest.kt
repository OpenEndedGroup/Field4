package afield.graphics.color

import afield.math.linalg.Vec4
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ColorTrajectoryTest {
    @Test
    fun standardCoordinatesRoundTripWithoutClipping() {
        val p3Red = ColorCoordinates(ColorSpace.DISPLAY_P3, 1.0, 0.0, 0.0, 0.4)
        val xyz = p3Red.convert(ColorSpace.XYZ_D65)
        assertEquals(0.486571, xyz.x, 1e-6)
        assertEquals(0.228975, xyz.y, 1e-6)
        assertFalse(p3Red.isInSrgb())
        for (space in ColorSpace.entries) {
            val recovered = p3Red.convert(space).convert(ColorSpace.DISPLAY_P3)
            assertEquals(1.0, recovered.x, 2e-5, space.name)
            assertEquals(0.0, recovered.y, 2e-5, space.name)
            assertEquals(0.0, recovered.z, 2e-5, space.name)
            assertEquals(0.4, recovered.alpha)
        }
        val red = colorCoordinates(colorHex("#f00"))
        assertEquals(0.627955, red.x, 1e-6)
        assertEquals(0.224863, red.y, 1e-6)
        assertEquals(0.125846, red.z, 1e-6)
    }

    @Test
    fun gamutMappingRetainsLightnessAndHue() {
        val outside = ColorCoordinates(ColorSpace.OKLCH, 0.72, 0.45, 146.0)
        val mapped = colorCoordinates(outside.toSrgb(), ColorSpace.OKLCH)
        val clipped = colorCoordinates(outside.toSrgb(ColorGamut.CLIP), ColorSpace.OKLCH)
        assertEquals(outside.x, mapped.x, 1e-5)
        assertEquals(outside.z, mapped.z, 1e-4)
        assertTrue(mapped.y < outside.y)
        assertTrue(abs(clipped.x - mapped.x) > 0.01)
    }

    @Test
    fun rampsRespectTransferCurvesAlphaAndHuePaths() {
        val black = colorHex("#000")
        val white = colorHex("#fff")
        assertEquals(0.5f, mixColor(black, white, 0.5, ColorSpace.SRGB).x)
        assertEquals(0.735357f, mixColor(black, white, 0.5, ColorSpace.LINEAR_SRGB).x, 1e-6f)
        assertEquals("#0000ff80", colorHex(mixColor(colorHex("#f000"), colorHex("#00f"), 0.5)))
        val endpoints = listOf(oklch(0.65, 0.09, 350), oklch(0.65, 0.09, 10))
        val short = colorCoordinates(colorRamp(endpoints, ColorSpace.OKLCH)(0.5), ColorSpace.OKLCH)
        val long = colorCoordinates(colorRamp(endpoints, ColorSpace.OKLCH, HuePath.LONGER)(0.5), ColorSpace.OKLCH)
        assertTrue(short.z < 0.01 || short.z > 359.99)
        assertEquals(180.0, long.z, 0.01)
    }

    @Test
    fun perceptualResamplingKeepsEndpointsAndReducesStepVariation() {
        val path = colorHelix(startLightness = 0.1, endLightness = 0.92, turns = 1.7)
        val regular = path.sample(24)
        val paced = path.byPerceptualDistance(1024).sample(24)
        assertEquals(colorHex(regular.first()), colorHex(paced.first()))
        assertEquals(colorHex(regular.last()), colorHex(paced.last()))
        fun spread(samples: List<Vec4>): Double {
            val steps = samples.zipWithNext { a, b -> deltaEOK(a, b) }
            return steps.max() - steps.min()
        }
        assertTrue(spread(paced) < spread(regular) * 0.5)
    }

    @Test
    fun contrastUsesCompositedColorAndReportsMisses() {
        val black = colorHex("#000")
        val white = colorHex("#fff")
        assertEquals(21.0, wcagContrast(white, black), 1e-12)
        assertEquals(1.0, wcagContrast(colorHex("#fff0"), black), 1e-12)
        val loop = colorRamp(listOf(black, white, black))
        val hit = contrastPalette(loop, black, listOf(4.5)).single()
        assertTrue(hit.reached)
        assertTrue(hit.parameter < 0.5)
        assertEquals(4.5, hit.achieved, 1e-5)
        val soft = colorRamp(listOf(black, colorHex("#fff4")))
        val miss = contrastPalette(soft, black, listOf(7.0)).single()
        assertFalse(miss.reached)
        assertEquals(1.0, miss.parameter)
    }
}
