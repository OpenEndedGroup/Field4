package afield.geometry.path

import afield.math.linalg.Vec3
import afield.math.linalg.vec
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [bsplineFrom], open and closed. */
class BSplineFromTest {
    private val square = listOf(vec(0.0, 0.0, 0.0), vec(1.0, 0.0, 0.0), vec(1.0, 1.0, 0.0), vec(0.0, 1.0, 0.0))

    private fun FLine.points(): List<Vec3> = nodes.map { Vec3(it.to) }

    private fun assertNear(expected: Vec3, actual: Vec3, message: String) =
        assertTrue(abs(expected.x - actual.x) < 1e-5f && abs(expected.y - actual.y) < 1e-5f && abs(expected.z - actual.z) < 1e-5f, "$message: expected $expected, got $actual")

    /** Open curves are clamped: they start on the first control point and end on the last. */
    @Test
    fun openCurveIsClamped() {
        val pts = bsplineFrom(square, degree = 3, samples = 40).points()
        assertEquals(40, pts.size)
        assertNear(square.first(), pts.first(), "start")
        assertNear(square.last(), pts.last(), "end")
    }

    /**
     * A closed cubic over a square's corners. It used to throw "List sizes must match": the wrap was
     * written `controlPoints + controlPoints.take(degree)`, and on a List<Vec3> that `+` is a pointwise sum.
     */
    @Test
    fun closedCurveIsASmoothLoop() {
        val pts = bsplineFrom(square, degree = 3, samples = 64, closed = true).points()
        assertEquals(64, pts.size)
        assertNear(pts.first(), pts.last(), "a loop ends where it starts")
        // At a knot a uniform cubic sits at (P0 + 4 P1 + P2) / 6.
        assertNear(vec(5.0 / 6.0, 1.0 / 6.0, 0.0), pts.first(), "first sample")
        // It stays strictly inside the square; mid-span it reaches (P0 + 23 P1 + 23 P2 + P3) / 48, x = 23 / 24.
        for (p in pts) assertTrue(p.x > 0.03f && p.x < 0.97f && p.y > 0.03f && p.y < 0.97f, "inside the hull: $p")
        assertEquals(23f / 24f, pts.maxOf { it.x }, 2e-3f, "furthest reach")
        // Smooth through the join: the step into the end and the step out of the start point the same way.
        val outX = pts[1].x - pts[0].x
        val outY = pts[1].y - pts[0].y
        val inX = pts[63].x - pts[62].x
        val inY = pts[63].y - pts[62].y
        val cos = (outX * inX + outY * inY) / (kotlin.math.hypot(outX, outY) * kotlin.math.hypot(inX, inY))
        assertTrue(cos > 0.98f, "tangent continuous across the join (cos = $cos)")
    }
}
