package afield.simulation.agents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Direct tests for the public segment queries in alife_walls.kt, now backed by the geometry.path kernel. */
class AlifeWallsGeometryTest {
    @Test
    fun closestPointOnSegmentClampsAndProjects() {
        val o = DoubleArray(2)
        closestPointOnSegment(1.0, 5.0, 0.0, 0.0, 4.0, 0.0, o)
        assertEquals(1.0, o[0], 1e-12); assertEquals(0.0, o[1], 1e-12)
        closestPointOnSegment(-3.0, 1.0, 0.0, 0.0, 4.0, 0.0, o)
        assertEquals(0.0, o[0], 0.0); assertEquals(0.0, o[1], 0.0)
        closestPointOnSegment(9.0, -1.0, 0.0, 0.0, 4.0, 0.0, o)
        assertEquals(4.0, o[0], 1e-12); assertEquals(0.0, o[1], 1e-12)
        // A post: zero-length wall answers its own point.
        closestPointOnSegment(9.0, 9.0, 2.0, 3.0, 2.0, 3.0, o)
        assertEquals(2.0, o[0], 0.0); assertEquals(3.0, o[1], 0.0)
    }

    @Test
    fun segmentsCrossIsClosed() {
        assertTrue(segmentsCross(0.0, 0.0, 2.0, 2.0, 0.0, 2.0, 2.0, 0.0))   // X
        assertTrue(segmentsCross(0.0, 0.0, 2.0, 0.0, 2.0, 0.0, 3.0, 5.0))   // shared endpoint
        assertTrue(segmentsCross(0.0, 0.0, 2.0, 0.0, 1.0, 0.0, 3.0, 0.0))   // collinear overlap
        assertTrue(segmentsCross(0.0, 0.0, 2.0, 0.0, 1.0, 0.0, 1.0, 4.0))   // T
        assertFalse(segmentsCross(0.0, 0.0, 2.0, 0.0, 3.0, 0.0, 4.0, 0.0))  // collinear apart
        assertFalse(segmentsCross(0.0, 0.0, 2.0, 0.0, 0.0, 1.0, 2.0, 1.0))  // parallel
    }

    @Test
    fun segmentDistance() {
        assertEquals(0.0, segmentDistance(0.0, 0.0, 2.0, 2.0, 0.0, 2.0, 2.0, 0.0), 0.0)
        assertEquals(1.0, segmentDistance(0.0, 0.0, 2.0, 0.0, 0.0, 1.0, 2.0, 1.0), 1e-12)
        assertEquals(5.0, segmentDistance(0.0, 0.0, 1.0, 0.0, 4.0, 4.0, 4.0, 9.0), 1e-12)
        assertEquals(2.0, segmentDistance(0.0, 0.0, 4.0, 0.0, 2.0, 2.0, 2.0, 5.0), 1e-12) // endpoint over interior
    }
}
