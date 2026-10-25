package afield.geometry.path

import afield.math.linalg.Vec2
import afield.math.linalg.Vec3
import afield.math.linalg.vec
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the arc, biarc, hull, inflection and blend fixes that the `fline_examples` docs captures exposed:
 * circleThroughTo drew a "D" (swapped circumcenter coefficients), biarcTo drew kinked straight lines,
 * the convex hull ignored cubic bulges, and blending one cubic with another gave two points.
 */
class FLineArcsAndAnalysisTest {

    private fun points(f: FLine) = f.nodes.indices.map { f.endPointOf(it) }

    private fun near(a: Vec3, b: Vec3, tol: Float = 0.5f) = Vec3(a).sub(b).length() < tol

    @Test
    fun arcThroughThreePointsPassesThroughAllThreeAndStaysOnOneCircle() {
        val p1 = vec(60f, 200f, 0f); val p2 = vec(220f, 40f, 0f); val p3 = vec(360f, 200f, 0f)
        val arc = arcThrough3Points(p1, p2, p3, 128)
        val pts = points(arc)
        assertTrue(near(pts.first(), p1), "starts at p1")
        assertTrue(near(pts.last(), p3), "ends at p3")
        assertTrue(pts.any { near(it, p2, 6f) }, "passes through p2")
        // Every point sits between y = 40 and y = 200: it is the arc over the top, not the long way round.
        assertTrue(pts.all { it.y in 39f..201f }, "takes the short way through p2: ${pts.maxOf { it.y }}")
    }

    @Test
    fun arcThroughThreePointsGoesTheLongWayWhenTheMiddlePointSaysSo() {
        // All three on the circle of radius 100 about the origin: 0°, -90°, 90°, so p2 forces the 270° way round.
        val p1 = vec(100f, 0f, 0f); val p2 = vec(0f, -100f, 0f); val p3 = vec(0f, 100f, 0f)
        val pts = points(arcThrough3Points(p1, p2, p3, 128))
        assertTrue(pts.any { near(it, p2, 6f) }, "passes through p2")
        assertTrue(pts.any { it.x < -90f }, "sweeps round through x = -100")
    }

    @Test
    fun biarcMeetsBothTangentsAndIsSmoothAtTheJoin() {
        val f = FLine().moveTo(vec(0f, 0f, 0f))
        f.biarcTo(vec(200f, 0f, 0f), vec(0f, 1f, 0f), vec(0f, -1f, 0f), segments = 64)
        val pts = points(f)
        assertTrue(near(pts.last(), vec(200f, 0f, 0f)), "ends at the end point")
        val startDir = Vec3(pts[1]).sub(pts[0]).normalize()
        val endDir = Vec3(pts.last()).sub(pts[pts.size - 2]).normalize()
        assertTrue(startDir.dot(vec(0f, 1f, 0f)) > 0.99f, "leaves along +y: $startDir")
        assertTrue(endDir.dot(vec(0f, -1f, 0f)) > 0.99f, "arrives along -y: $endDir")
        // A half-circle of radius 100: no straight kinked segments, every point 100 from (100, 0).
        assertTrue(pts.all { abs(Vec3(it).sub(vec(100f, 0f, 0f)).length() - 100f) < 1f }, "is a semicircle")
        // No sharp turn anywhere.
        for (i in 1 until pts.size - 1) {
            val a = Vec3(pts[i]).sub(pts[i - 1]).normalize()
            val b = Vec3(pts[i + 1]).sub(pts[i]).normalize()
            assertTrue(a.dot(b) > 0.95f, "smooth at $i")
        }
    }

    @Test
    fun convexHullWrapsACubicsBulge() {
        val f = FLine().moveTo(vec(0f, 0f, 0f)).cubicTo(vec(0f, 100f, 0f), vec(100f, 100f, 0f), vec(100f, 0f, 0f))
        val hull = f.byConvexHull2D()
        // The curve peaks at y = 75; a hull of the two end points alone would have no height.
        assertTrue(points(hull).maxOf { it.y } > 74f, "hull reaches the bulge")
    }

    @Test
    fun anSCubicSplitsOnceAtItsInflection() {
        val f = FLine().moveTo(vec(0f, 0f, 0f)).cubicTo(vec(0f, 100f, 0f), vec(100f, 100f, 0f), vec(100f, 0f, 0f))
        val s = FLine().moveTo(vec(0f, 0f, 0f)).cubicTo(vec(50f, 100f, 0f), vec(50f, -100f, 0f), vec(100f, 0f, 0f))
        assertEquals(1, f.bySplittingAtInflections().size, "a simple arch has no inflection")
        assertEquals(2, s.bySplittingAtInflections().size, "an S has one")
    }

    @Test
    fun blendingTwoSingleCubicsKeepsTheirShape() {
        val a = FLine().moveTo(vec(0f, 0f, 0f)).cubicTo(vec(0f, 100f, 0f), vec(100f, 100f, 0f), vec(100f, 0f, 0f))
        val flat = FLine().moveTo(vec(0f, 0f, 0f)).lineTo(vec(100f, 0f, 0f))
        val mid = points(a.byBlendingWith(flat, 0.5f))
        assertTrue(mid.size > 10, "more than one point per node: ${mid.size}")
        assertTrue(abs(mid.maxOf { it.y } - 37.5f) < 2f, "half the arch's height: ${mid.maxOf { it.y }}")
    }

    @Test
    fun aShallowCrossingIsReportedOnceAtAnyTolerance() {
        val s = FLine().moveTo(vec(40f, 130f, 0f)).cubicTo(vec(140f, -40f, 0f), vec(260f, 300f, 0f), vec(380f, 130f, 0f))
        val line = FLine().moveTo(vec(40f, 100f, 0f)).lineTo(vec(380f, 160f, 0f))
        for (tol in listOf(0.01f, 0.1f, 1f)) {
            val hits = s.crossings2D(line, tolerance = tol)
            assertEquals(3, hits.size, "tolerance $tol: ${hits.map { it.point }}")
            // each hit is on the straight line, not just near it
            hits.forEach { h -> assertTrue(abs(h.point.y - (100f + (h.point.x - 40f) * 60f / 340f)) < 0.05f, "on the line: ${h.point}") }
        }
    }
}
