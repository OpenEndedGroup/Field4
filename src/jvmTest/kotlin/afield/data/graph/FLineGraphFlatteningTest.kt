package afield.data.graph

import afield.geometry.path.FLine
import afield.math.linalg.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins the cubic flattening in `flineGraph`, which measures control points against the chord. */
class FLineGraphFlatteningTest {
    @Test
    fun straightCubicIsOneEdge() {
        val line = FLine().moveTo(0f, 0f).cubicTo(Vec3(1f, 0f, 0f), Vec3(2f, 0f, 0f), Vec3(3f, 0f, 0f))
        val g = line.toFLineGraph(epsilon = 1e-4f, cubicFlatness = 1e-3f)
        assertEquals(2, g.vertices.size)
        assertEquals(1, g.edges.size)
    }

    @Test
    fun curvedCubicSubdividesMoreWhenFlatnessIsTighter() {
        val line = FLine().moveTo(0f, 0f).cubicTo(Vec3(0f, 2f, 0f), Vec3(3f, 2f, 0f), Vec3(3f, 0f, 0f))
        val coarse = line.toFLineGraph(epsilon = 1e-4f, cubicFlatness = 1f).vertices.size
        val fine = line.toFLineGraph(epsilon = 1e-4f, cubicFlatness = 1e-3f).vertices.size
        assertTrue(fine > coarse, "fine=$fine coarse=$coarse")
        assertTrue(coarse > 2, "a bulge of 1.5 must split at flatness 1, got $coarse")
    }

    @Test
    fun loopCubicWithCoincidentEndsStillSubdivides() {
        // The chord has zero length, so flatness is the control points' distance to the start.
        val line = FLine().moveTo(0f, 0f).cubicTo(Vec3(2f, 2f, 0f), Vec3(-2f, 2f, 0f), Vec3(0f, 0f, 0f))
        val g = line.toFLineGraph(epsilon = 1e-4f, cubicFlatness = 1e-2f)
        assertTrue(g.vertices.size > 4, "got ${g.vertices.size}")
        g.vertices.forEach { assertTrue(it.y >= -1e-4f && it.y <= 1.6f, "vertex $it off the curve's hull") }
    }
}
