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
        assertEquals(0f, p.point(0f, 0f).length())
        p.widthMeters = -3f
        p.bendDegrees = 540f
        p.validate()
        assertTrue(p.point(0f, 0f).x.isFinite())
    }

    @Test fun independentLayoutsAllowOverlapsSignedRadiiAndMultipleTurns() {
        val origin = afield.math.linalg.Vector3f()
        val p = StereoVideoScreenLayout(angleOffsetDegrees = 720f, radiusMeters = -4f, fill = 6f)
            .placement(0, 8, origin, 2.5f, 0f, .9f)
        assertEquals(4f, p.center.z, 1e-5f)
        assertEquals(270f, p.bendDegrees, 1e-5f)
        assertEquals((4 * 1.5 * Math.PI).toFloat(), p.widthMeters, 1e-5f)
        val collapsed = StereoVideoScreenLayout(radiusMeters = 0f, fill = 0f)
            .placement(0, 8, origin, 2.5f, 0f, .9f)
        assertEquals(0f, collapsed.widthMeters)
        assertEquals(0f, collapsed.point(0f, 0f).length())
    }

    @Test fun painterOrderUsesCurrentViewAndStableTies() {
        val positions = listOf(-1f, -5f, -3f, -3f).map { z -> StereoVideoPlacement().apply { center.set(0f, 0f, z) } }
        val front = afield.math.linalg.Matrix4f()
        assertEquals(listOf(1, 2, 3, 0), stereoVideoBackToFront(positions, front, front))
        val back = afield.math.linalg.Matrix4f().rotateY(Math.PI.toFloat())
        assertEquals(listOf(0, 2, 3, 1), stereoVideoBackToFront(positions, back, back))
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
