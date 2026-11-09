package afield.graphics.scene.video

import afield.graphics.vulkan.targets.OpenXRColorFormats
import kotlin.test.*
import org.lwjgl.vulkan.VK10.*

class StereoVideoPlacementTest {
    @Test
    fun flatScreenHasNativeAspectAndCenter() {
        val p =
            StereoVideoPlacement().apply {
                widthMeters = 3.2f
                aspect = 16f / 9f
                bendDegrees = 0f
            }
        assertEquals(-1.6f, p.point(0f, .5f).x, 1e-5f)
        assertEquals(.9f, p.point(.5f, 0f).y, 1e-5f)
        assertEquals(0f, p.point(.5f, .5f).length(), 1e-5f)
        val center = p.model().transformPosition(p.point(.5f, .5f))
        assertEquals(-2.5f, center.z, 1e-5f)
    }

    @Test
    fun bendPreservesArcWidthAndApproachesFlatContinuously() {
        val p = StereoVideoPlacement().apply { bendDegrees = 90f }
        val left = p.point(0f, .5f)
        val right = p.point(1f, .5f)
        assertEquals(-left.x, right.x, 1e-5f)
        assertEquals(left.z, right.z, 1e-5f)
        assertTrue(left.z > 0f)
        p.bendDegrees = -90f
        assertEquals(-left.z, p.point(0f, .5f).z, 1e-5f)
        p.bendDegrees = .01f
        assertEquals(-1.5f, p.point(0f, .5f).x, 1e-5f)
        p.widthMeters = 0f
        assertFailsWith<IllegalArgumentException> { p.validate() }
    }

    @Test
    fun highPrecisionWinsOverLegacyRuntimePreferenceOrder() {
        val offered = intArrayOf(VK_FORMAT_B8G8R8A8_SRGB, VK_FORMAT_R16G16B16A16_SFLOAT)
        assertEquals(VK_FORMAT_R16G16B16A16_SFLOAT, OpenXRColorFormats.select(offered))
        assertEquals(VK_FORMAT_B8G8R8A8_SRGB, OpenXRColorFormats.select(offered, false))
        assertTrue(OpenXRColorFormats.highPrecision(OpenXRColorFormats.select(offered)))
        assertEquals(VK_FORMAT_B8G8R8A8_SRGB, OpenXRColorFormats.select(offered.take(1).toIntArray()))
        assertFalse(OpenXRColorFormats.highPrecision(VK_FORMAT_B8G8R8A8_SRGB))
        assertFailsWith<IllegalArgumentException> { OpenXRColorFormats.select(intArrayOf()) }
    }

    @Test
    fun colourTagsKeepRangeAndMatrixIndependent() {
        val limited = StereoVideoColorimetry.parameters(1, 1)
        val full = StereoVideoColorimetry.parameters(1, 2)
        assertEquals(.2126f, limited.x)
        assertEquals(.0722f, limited.y)
        assertEquals(0f, limited.z)
        assertEquals(1f, full.z)
        assertEquals(.299f, StereoVideoColorimetry.parameters(6, 1).x)
    }
}
