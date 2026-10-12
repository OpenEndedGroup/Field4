package afield.graphics

import afield.stdlib.Vec2
import afield.stdlib.Vec3
import field.graphics.FLinesAndJavaShapes
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Property tests for the Paper.js / SkPathMeasure-style surface in FLineMeasure.kt, plus the
 * intersection and area defects found while auditing the older FLine operations.
 */
class FLineMeasureTest {

    /** a circle of radius [r] from four cubics (kappa = 0.5523), counter-clockwise with y up. */
    private fun circle(cx: Float, cy: Float, r: Float): FLine {
        val k = 0.5522847f * r
        return FLine().moveTo(cx + r, cy)
            .cubicTo(Vec3(cx + r, cy + k, 0f), Vec3(cx + k, cy + r, 0f), Vec3(cx, cy + r, 0f))
            .cubicTo(Vec3(cx - k, cy + r, 0f), Vec3(cx - r, cy + k, 0f), Vec3(cx - r, cy, 0f))
            .cubicTo(Vec3(cx - r, cy - k, 0f), Vec3(cx - k, cy - r, 0f), Vec3(cx, cy - r, 0f))
            .cubicTo(Vec3(cx + k, cy - r, 0f), Vec3(cx + r, cy - k, 0f), Vec3(cx + r, cy, 0f))
            .close()
    }

    private fun square(x: Float, y: Float, s: Float) =
        FLine().moveTo(x, y).lineTo(x + s, y).lineTo(x + s, y + s).lineTo(x, y + s).close()

    private fun wiggle(seed: Int): FLine {
        val rnd = Random(seed)
        val f = FLine().moveTo(0f, 0f)
        var x = 0f
        repeat(6) {
            x += 50f
            if (rnd.nextBoolean()) f.lineTo(x, rnd.nextFloat() * 100f)
            else f.cubicTo(
                Vec3(x - 40f, rnd.nextFloat() * 100f, 0f), Vec3(x - 10f, rnd.nextFloat() * 100f, 0f),
                Vec3(x, rnd.nextFloat() * 100f, 0f)
            )
        }
        return f
    }

    @Test
    fun lengthOfASquareAndACircle() {
        assertEquals(400f, square(0f, 0f, 100f).length(), 0.01f)
        assertEquals((2 * PI * 50).toFloat(), circle(0f, 0f, 50f).length(), 0.1f)
        assertEquals(0f, FLine().length())
        assertEquals(0f, FLine().moveTo(3f, 4f).length())
    }

    @Test
    fun locationOnACircleHasItsRadiusAsCurvatureAndPointsInward() {
        val c = circle(0f, 0f, 50f)
        for (f in listOf(0.1f, 0.33f, 0.6f, 0.9f)) {
            val loc = assertNotNull(c.locationAtFraction(f))
            assertEquals(50f, loc.point.length(), 0.05f, "radius at $f")
            assertEquals(1f, loc.tangent.length(), 1e-4f)
            assertEquals(0f, loc.tangent.dot(loc.point), 0.05f * 50f, "tangent perpendicular to radius")
            // counter-clockwise (y up): curvature positive and the normal points to the centre
            assertEquals(1f / 50f, loc.curvature, 0.001f, "curvature at $f")
            assertTrue(loc.normal!!.dot(loc.point) < 0f, "principal normal points inward at $f")
            assertTrue(Vec3(loc.normal2D.x, loc.normal2D.y, 0f).dot(loc.point) * loc.signedCurvature2D < 0f, "normal2D * signedCurvature2D points inward at $f")
            assertEquals(f * c.length(), loc.distance, 0.05f)
        }
    }

    @Test
    fun locationAtIsMonotoneAndStraightSegmentsHaveZeroCurvature() {
        val sq = square(0f, 0f, 100f)
        val loc = assertNotNull(sq.locationAt(150f))
        assertEquals(100f, loc.point.x, 0.01f); assertEquals(50f, loc.point.y, 0.01f)
        assertEquals(0f, loc.curvature)
        // the Close segment is a real segment
        val back = assertNotNull(sq.locationAt(350f))
        assertEquals(0f, back.point.x, 0.01f); assertEquals(50f, back.point.y, 0.01f)
        assertEquals(-1f, back.tangent.y, 1e-4f)

        for (seed in 0 until 10) {
            val w = wiggle(seed)
            var last = -1f
            for (k in 0..40) {
                val l = assertNotNull(w.locationAt(w.length() * k / 40f))
                assertTrue(l.distance >= last - 1e-3f, "distance monotone, seed $seed")
                assertTrue(l.t >= 0f && l.t <= w.nodes.size - 1)
                last = l.distance
            }
        }
    }

    @Test
    fun nearestLocationFindsTheFootOfThePerpendicular() {
        val c = circle(0f, 0f, 50f)
        val n = assertNotNull(c.nearestLocation(Vec3(200f, 200f, 0f)))
        assertEquals(50f / kotlin.math.sqrt(2f), n.point.x, 0.1f)
        assertEquals(50f / kotlin.math.sqrt(2f), n.point.y, 0.1f)
        val s = assertNotNull(square(0f, 0f, 100f).nearestLocation(Vec3(40f, -30f, 0f)))
        assertEquals(40f, s.point.x, 1e-3f); assertEquals(0f, s.point.y, 1e-3f)
        assertEquals(40f, s.distance, 0.01f)
    }

    @Test
    fun splitAtThenJoinIsTheOriginal() {
        for (seed in 0 until 12) {
            val w = wiggle(seed)
            val total = w.length()
            val d = total * (0.1f + 0.8f * Random(seed).nextFloat())
            val (a, b) = w.splitAt(d)
            assertEquals(d, a.length(), 0.05f, "first piece length, seed $seed")
            assertEquals(total, a.length() + b.length(), 0.1f, "lengths add up, seed $seed")
            assertTrue(a.nodes.last().to.distance(b.nodes.first().to) < 1e-3f, "pieces meet")
            // the pieces lie on the original
            for (k in 0..10) {
                val p = assertNotNull(b.locationAtFraction(k / 10f)).point
                val q = assertNotNull(w.nearestLocation(p)).point
                assertTrue(p.distance(q) < 0.05f, "piece leaves the original by ${p.distance(q)}")
            }
        }
    }

    @Test
    fun trimClampsAndKeepsTheMiddle() {
        val sq = square(0f, 0f, 100f)
        val mid = sq.trim(50f, 250f)
        assertEquals(200f, mid.length(), 0.01f)
        assertEquals(50f, mid.nodes.first().to.x, 1e-3f)
        assertEquals(400f, sq.trim(-10f, 1000f).length(), 0.01f)
        assertEquals(200f, sq.trim(250f, 50f).length(), 0.01f, "reversed arguments swap")
        assertEquals(0f, sq.trim(80f, 80f).length())
    }

    @Test
    fun tightBoundsOfACurveIgnoreItsControlPoints() {
        val bump = FLine().moveTo(0f, 0f).cubicTo(Vec3(0f, 100f, 0f), Vec3(100f, 100f, 0f), Vec3(100f, 0f, 0f))
        val tight = bump.tightBounds2D()
        val fast = bump.fast2DBounds()
        assertEquals(100.0, fast.h, 1e-6)
        assertEquals(75.0, tight.h, 1e-4, "the bump peaks at 3/4 of the control height")
        val cb = circle(10f, 20f, 50f).tightBounds2D()
        assertEquals(-40.0, cb.x, 1e-3); assertEquals(100.0, cb.w, 1e-3); assertEquals(100.0, cb.h, 1e-3)
    }

    @Test
    fun areaFollowsCurvesAndSubpaths() {
        val r = 50f
        assertEquals((PI * r * r).toFloat(), circle(0f, 0f, r).area2D(), 0.001f * (PI * r * r).toFloat())
        assertEquals(-1e4f, square(0f, 0f, 100f).byReversing().area2D(), 0.01f)
        // two squares side by side, as two subpaths: the move between them is not an edge
        val two = square(0f, 0f, 100f)
        two.nodes.addAll(square(300f, 0f, 100f).nodes)
        assertEquals(2e4f, two.area2D(), 0.01f)
        // an open L closes back to its start for the purposes of area
        assertEquals(5000f, FLine().moveTo(0f, 0f).lineTo(100f, 0f).lineTo(100f, 100f).area2D(), 0.01f)
        // isClockwise is on-screen (y down): the y-up CCW circle is clockwise there
        assertTrue(circle(0f, 0f, 10f).isClockwise2D())
        assertFalse(circle(0f, 0f, 10f).byReversing().isClockwise2D())
    }

    @Test
    fun legacySignedAreaNoLongerBridgesSubpaths() {
        val two = square(0f, 0f, 100f)
        two.nodes.addAll(square(300f, 0f, 100f).nodes)
        assertEquals(2e4f, two.area2D(), 0.01f)
    }

    @Test
    fun containsWithFillRules() {
        val ring = circle(0f, 0f, 100f)
        ring.nodes.addAll(circle(0f, 0f, 40f).nodes) // same direction: nonzero keeps the hole filled
        assertTrue(ring.contains2D(Vec2(0f, 0f), FLineFillRule.NonZero))
        assertFalse(ring.contains2D(Vec2(0f, 0f), FLineFillRule.EvenOdd))
        assertTrue(ring.contains2D(Vec2(70f, 0f), FLineFillRule.EvenOdd))
        // follows the curve: just inside the circle at 45 degrees, where the chord of a quarter is far inside
        val p = (99f / kotlin.math.sqrt(2f))
        assertTrue(circle(0f, 0f, 100f).contains2D(Vec2(p, p), FLineFillRule.NonZero))
    }

    @Test
    fun crossingsOfALineAndACircle() {
        val line = FLine().moveTo(-200f, 10f).lineTo(200f, 10f)
        val hits = line.crossings2D(circle(0f, 0f, 50f))
        assertEquals(2, hits.size, "$hits")
        val x = kotlin.math.sqrt(50f * 50f - 100f)
        assertEquals(-x, hits[0].point.x, 0.05f); assertEquals(x, hits[1].point.x, 0.05f)
        assertTrue(hits.all { abs(it.point.y - 10f) < 0.02f })
        // t on the line is a real parameter: the cursor puts it at the same place
        val c = line.cursor().setT(hits[0].t).position()
        assertTrue(c.distance(hits[0].point) < 0.05f)
    }

    @Test
    fun crossingsOfTwoCircles() {
        val hits = circle(0f, 0f, 50f).crossings2D(circle(60f, 0f, 50f))
        assertEquals(2, hits.size, "$hits")
        for (h in hits) {
            assertEquals(30f, h.point.x, 0.1f)
            assertEquals(40f, abs(h.point.y), 0.1f)
        }
    }

    @Test
    fun selfCrossingsOfAPolylineFigureEight() {
        val bowtie = FLine().moveTo(0f, 0f).lineTo(100f, 100f).lineTo(100f, 0f).lineTo(0f, 100f).close()
        val hits = bowtie.selfCrossings2D()
        assertEquals(1, hits.size, "$hits")
        assertEquals(50f, hits[0].point.x, 1e-3f); assertEquals(50f, hits[0].point.y, 1e-3f)
        assertEquals(0, square(0f, 0f, 100f).selfCrossings2D().size, "a square's corners are joins")
        assertEquals(0, circle(0f, 0f, 50f).selfCrossings2D().size)
    }

    @Test
    fun aSingleLoopingCubicCrossesItselfOnce() {
        val loop = FLine().moveTo(60f, 200f).cubicTo(Vec3(420f, 40f, 0f), Vec3(0f, 40f, 0f), Vec3(340f, 210f, 0f))
        val hits = loop.selfCrossings2D()
        assertEquals(1, hits.size, "$hits")
        val h = hits[0]
        assertTrue(h.t < h.otherT)
        val c = loop.cursor()
        assertTrue(c.setT(h.t).position().distance(h.point) < 0.05f)
        assertTrue(c.setT(h.otherT).position().distance(h.point) < 0.05f)
        assertEquals(1, loop.selfCrossings2D().map { it.point }.size)
    }

    // ---- defects in the older API, pinned here ----

    @Test
    fun selfCrossings2DSeesStraightSegments() {
        val bowtie = FLine().moveTo(0f, 0f).lineTo(100f, 100f).lineTo(100f, 0f).lineTo(0f, 100f).close()
        assertEquals(1, bowtie.selfCrossings2D().map { it.point }.size)
    }

    @Test
    fun crossings2DReportsEachCrossingOnce() {
        val line = FLine().moveTo(-200f, 10f).lineTo(200f, 10f)
        assertEquals(2, line.crossings2D(circle(0f, 0f, 50f)).size)
    }

    @Test
    fun lineSegmentIntersectionChecksBothSegments() {
        // the second segment stops well short of the first
        val hit = FLinesAndJavaShapes.intersectTwoLineSegments(
            Vec3(0f, 0f, 0f), Vec3(100f, 0f, 0f), Vec3(50f, 10f, 0f), Vec3(50f, 50f, 0f)
        )
        assertEquals(null, hit)
        val a = FLine().moveTo(0f, 0f).lineTo(100f, 0f)
        val b = FLine().moveTo(50f, 10f).lineTo(50f, 50f)
        assertEquals(0, FLinesAndJavaShapes.intersect(a, b).size)
    }

    @Test
    fun intersectYReportsPointsOnTheHorizontal() {
        val c = circle(0f, 0f, 50f)
        val hits = FLinesAndJavaShapes.intersectY(c, 30f)
        assertEquals(2, hits.size, "$hits")
        hits.forEach { assertEquals(30f, it.y, 0.01f); assertEquals(40f, abs(it.x), 0.05f) }
        val diag = FLine().moveTo(0f, 0f).lineTo(10f, 100f)
        val dh = FLinesAndJavaShapes.intersectY(diag, 50f)
        assertEquals(1, dh.size, "a steep line crosses y=50 though its x never reaches 50")
        assertEquals(5f, dh[0].x, 1e-3f)
    }

    @Test
    fun piecesKeepsALeadingRunWithNoMoveTo() {
        val f = FLine()
        f.nodes.add(FLine.LineTo().set(Vec3(0f, 0f, 0f)))
        f.lineTo(10f, 0f)
        f.moveTo(20f, 0f).lineTo(30f, 0f)
        val p = f.pieces()
        assertEquals(2, p.size)
        assertEquals(4, p.sumOf { it.nodes.size })
    }

    @Test
    fun circlePointsAreOnTheCircle() {
        // guards the circle() helper itself
        val c = circle(0f, 0f, 1f)
        for (k in 0 until 16) {
            val a = 2 * PI * k / 16
            val n = assertNotNull(c.nearestLocation(Vec3(cos(a).toFloat() * 2, sin(a).toFloat() * 2, 0f)))
            assertEquals(1f, n.point.length(), 3e-4f)
        }
    }
}
