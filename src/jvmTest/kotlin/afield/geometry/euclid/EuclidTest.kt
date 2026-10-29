package afield.geometry.euclid

import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EuclidTest {
    private fun p(x: Number, y: Number) = point2(x, y)
    private fun near(a: Point2, b: Point2, tol: Double = 1e-9) =
        assertTrue((a - b).length < tol, "expected $b, got $a")
    private fun nearSet(actual: List<Point2>, vararg expected: Point2) {
        assertEquals(expected.size, actual.size, "got $actual")
        expected.forEach { e -> assertTrue(actual.any { (it - e).length < 1e-9 }, "missing $e in $actual") }
    }

    @Test fun segmentsCrossTouchAndMiss() {
        nearSet(Segment2(p(0, 0), p(2, 2)).intersect(Segment2(p(0, 2), p(2, 0))).points, p(1, 1))
        nearSet(Segment2(p(0, 0), p(1, 0)).intersect(Segment2(p(1, 0), p(1, 5))).points, p(1, 0))
        assertTrue(Segment2(p(0, 0), p(1, 0)).intersect(Segment2(p(2, -1), p(2, 1))).isEmpty)
    }

    @Test fun collinearOverlapsAreShapes() {
        val o = Segment2(p(0, 0), p(4, 0)).intersect(Segment2(p(6, 0), p(2, 0)))
        assertEquals(listOf<Shape2>(Segment2(p(2, 0), p(4, 0))), o.overlaps)
        nearSet(Segment2(p(0, 0), p(4, 0)).intersect(Segment2(p(4, 0), p(9, 0))).points, p(4, 0))
        val r = Line2(p(0, 0), p(1, 0)).intersect(Ray2(p(3, 0), p(-2, 0))).overlaps.single() as Ray2
        near(r.origin, p(3, 0)); assertTrue(r.direction.x < 0)
        assertTrue(Segment2(p(0, 0), p(1, 0)).intersect(Segment2(p(0, 1), p(1, 1))).isEmpty)
    }

    @Test fun raysAndLines() {
        assertTrue(Ray2(p(0, 0), p(1, 0)).intersect(Line2(p(-1, -5), p(0, 1))).isEmpty)
        nearSet(Ray2(p(0, 0), p(1, 0)).intersect(Line2(p(3, -5), p(0, 1))).points, p(3, 0))
        assertTrue(Line2(p(0, 0), p(1, 1)).intersect(Line2(p(0, 1), p(2, 2))).isEmpty)
    }

    @Test fun lineAndCircle() {
        val c = Circle2(p(0, 0), 5.0)
        nearSet(Line2(p(-10, 3), p(1, 0)).intersect(c).points, p(-4, 3), p(4, 3))
        nearSet(Line2(p(-10, 5), p(1, 0)).intersect(c).points, p(0, 5)) // tangent
        nearSet(Segment2(p(0, 0), p(0, 10)).intersect(c).points, p(0, 5))
        assertTrue(Segment2(p(-1, 0), p(1, 0)).intersect(c).isEmpty) // inside
    }

    @Test fun circles() {
        nearSet(Circle2(p(0, 0), 5.0).intersect(Circle2(p(8, 0), 5.0)).points, p(4, 3), p(4, -3))
        nearSet(Circle2(p(0, 0), 2.0).intersect(Circle2(p(5, 0), 3.0)).points, p(2, 0)) // outer touch
        nearSet(Circle2(p(0, 0), 5.0).intersect(Circle2(p(2, 0), 3.0)).points, p(5, 0)) // inner touch
        assertTrue(Circle2(p(0, 0), 1.0).intersect(Circle2(p(0, 0), 2.0)).isEmpty)
        assertEquals(listOf<Shape2>(Circle2(p(0, 0), 1.0)), Circle2(p(0, 0), 1.0).intersect(Circle2(p(0, 0), 1.0)).overlaps)
    }

    @Test fun arcsFilterAndOverlap() {
        val upper = Arc2(p(0, 0), 5.0, 0.0, PI)
        nearSet(Line2(p(-10, 3), p(1, 0)).intersect(upper).points, p(-4, 3), p(4, 3))
        assertTrue(Line2(p(-10, -3), p(1, 0)).intersect(upper).isEmpty)
        val cw = Arc2(p(0, 0), 5.0, 0.0, -PI) // lower half, clockwise
        nearSet(Line2(p(-10, -3), p(1, 0)).intersect(cw).points, p(-4, -3), p(4, -3))
        // two arcs of one circle share a quarter
        val shared = upper.intersect(Arc2(p(0, 0), 5.0, PI / 2, PI)).overlaps.single() as Arc2
        assertEquals(PI / 2, shared.start, 1e-12); assertEquals(PI / 2, shared.sweep, 1e-12)
        // touching only at an end
        nearSet(upper.intersect(Arc2(p(0, 0), 5.0, PI, PI / 2)).points, p(-5, 0))
    }

    @Test fun closestPointsCircleToCircle() {
        val (a, b) = Circle2(p(0, 0), 1.0).closestPoints(Circle2(p(10, 0), 2.0))
        near(a, p(1, 0)); near(b, p(8, 0))
        val (c, d) = Circle2(p(0, 0), 10.0).closestPoints(Circle2(p(3, 0), 2.0)) // contained
        near(c, p(10, 0)); near(d, p(5, 0))
        assertEquals(7.0, Circle2(p(0, 0), 1.0).distanceTo(Circle2(p(10, 0), 2.0)), 1e-12)
    }

    @Test fun closestPointsWithArcsAndSegments() {
        val upper = Arc2(p(0, 0), 1.0, 0.0, PI)
        val (a, b) = upper.closestPoints(Circle2(p(0, -10), 1.0))
        assertEquals(1.0, a.length, 1e-12); assertTrue(a.y >= -1e-12) // an arc end
        assertEquals(sqrt(1.0 + 100.0) - 1, (b - a).length, 1e-9)
        val (s, t) = Segment2(p(-5, 3), p(5, 3)).closestPoints(Circle2(p(0, 0), 1.0))
        near(s, p(0, 3)); near(t, p(0, 1))
        // segment inside a circle: nearest is its far end
        val (u, v) = Segment2(p(0, 0), p(0.5, 0)).closestPoints(Circle2(p(0, 0), 1.0))
        near(u, p(0.5, 0)); near(v, p(1, 0))
        val (x, y) = Segment2(p(0, 0), p(1, 0)).closestPoints(Segment2(p(3, 1), p(3, 5)))
        near(x, p(1, 0)); near(y, p(3, 1))
        near(Ray2(p(0, 0), p(1, 0)).closestPoint(p(-3, 4)), p(0, 0))
    }

    @Test fun constructions() {
        val cc = assertNotNull(Circle2.through(p(1, 0), p(0, 1), p(-1, 0)))
        near(cc.center, p(0, 0)); assertEquals(1.0, cc.radius, 1e-12)
        assertNull(Circle2.through(p(0, 0), p(1, 1), p(2, 2)))
        val inc = assertNotNull(Circle2.inscribed(p(0, 0), p(3, 0), p(0, 4)))
        near(inc.center, p(1, 1)); assertEquals(1.0, inc.radius, 1e-12)
        assertEquals(2, Circle2.throughWithRadius(p(-1, 0), p(1, 0), 2.0).size)
        near(Line2(p(0, 0), p(1, 0)).reflect(p(2, 3)), p(2, -3))
        val bis = Line2.angleBisectors(Line2(p(0, 0), p(1, 0)), Line2(p(0, 0), p(0, 1)))
        assertEquals(0.0, bis[0].signedDistance(p(5, 5)), 1e-12)
        assertEquals(4, Circle2.tangentToLines(Line2(p(0, 0), p(1, 0)), Line2(p(0, 0), p(0, 1)), 1.0).size)
    }

    @Test fun tangents() {
        val c = Circle2(p(0, 0), 1.0)
        val pts = c.tangentPointsFrom(p(2, 0))
        assertEquals(2, pts.size)
        pts.forEach { assertEquals(0.0, (it - c.center) dot (it - p(2, 0)), 1e-12) }
        assertEquals(4, c.commonTangents(Circle2(p(5, 0), 1.0)).size)
        assertEquals(3, c.commonTangents(Circle2(p(2, 0), 1.0)).size) // touching
        assertEquals(2, c.commonTangents(Circle2(p(1, 0), 1.0)).size) // crossing
        assertEquals(0, c.commonTangents(Circle2(p(0.1, 0), 0.5)).size) // nested
        for (seg in c.commonTangents(Circle2(p(5, 1), 2.0))) {
            assertEquals(1.0, seg.line.distanceTo(c.center), 1e-9)
            assertEquals(2.0, seg.line.distanceTo(p(5, 1)), 1e-9)
        }
    }

    @Test fun arcConstructionsGoThroughTheirPoints() {
        val a = assertNotNull(Arc2.through(p(1, 0), p(0, -1), p(-1, 0)))
        assertTrue(a.sweep < 0) // via the bottom
        near(a.startPoint, p(1, 0)); near(a.endPoint, p(-1, 0))
        val t = assertNotNull(Arc2.tangentTo(p(0, 0), p(1, 0), p(0, 2)))
        near(t.center, p(0, 1)); near(t.endPoint, p(0, 2)); assertTrue(t.sweep > 0)
        assertTrue(a.toFLine().nodes.size >= 3)
    }

    @Test fun degenerateShapesBehaveAsPoints() {
        nearSet(Segment2(p(1, 1), p(1, 1)).intersect(Line2(p(0, 1), p(1, 0))).points, p(1, 1))
        assertTrue(Circle2(p(0, 0), 0.0).intersects(Segment2(p(-1, 0), p(1, 0))))
    }

    @Test fun nearParallelLinesMeetFarAway() {
        val m = Line2(p(0, 0), p(1, 0)).intersect(Line2(p(0, 1), p(1, -1e-6))).points.single()
        assertEquals(1e6, m.x, 1e-3)
    }
}
