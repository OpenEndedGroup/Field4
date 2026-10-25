package afield.geometry.path

import afield.lang.remotejs.JsFun

import afield.ui.visualization.l

import afield.math.linalg.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Phase-1 characterization of FLine.Cursor before the merge with FLineMeasure (names migrated to the merged API). */
class CursorCharacterizationTest {
    private fun near(a: Vec3, b: Vec3, eps: Float = 1e-3f) = a.distance(b) < eps

    private fun quarter(r: Float = 100f): FLine {
        val k = 0.5522847f * r
        return FLine().moveTo(r, 0f).cubicTo(Vec3(r, k, 0f), Vec3(k, r, 0f), Vec3(0f, r, 0f))
    }

    @Test
    fun tangentIsUnitLength() {
        val c = quarter().cursor().setT(0.5f)
        val t = assertNotNull(c.tangent())
        assertEquals(1f, t.length(), 1e-3f, "tangent $t")
    }

    @Test
    fun normal2DIsUnitLength() {
        val c = quarter().cursor().setT(0.5f)
        val n = assertNotNull(c.normal2D())
        assertEquals(1f, n.length(), 1e-3f, "normal2 $n")
    }

    @Test
    fun radiusOfCurvaturePointsAtTheCentre() {
        val c = quarter().cursor().setT(0.5f)
        val r = assertNotNull(c.radiusOfCurvature())
        val centre = Vec3(c.position()).add(r)
        assertTrue(centre.length() < 1.0f, "centre $centre (radius vec $r)")
    }

    @Test
    fun radiusOfCurvatureOnAStraightLineIsNullNotThrow() {
        val c = FLine().moveTo(0f, 0f).lineTo(10f, 0f).cursor().setT(0.5f)
        assertEquals(null, c.radiusOfCurvature())
    }

    @Test
    fun degenerateCubicEndHasATangent() {
        val f = FLine().moveTo(0f, 0f).cubicTo(Vec3(0f, 0f, 0f), Vec3(100f, 0f, 0f), Vec3(100f, 100f, 0f))
        val t = f.cursor().setT(0f).tangent()
        assertTrue(t != null && t.length() > 0.5f, "tangent at degenerate start: $t")
    }

    @Test
    fun helixTangentIsPerpendicularToItsRadius() {
        val f = FLine()
        for (i in 0..200) { val a = i * 0.05; val p = Vec3((100 * cos(a)).toFloat(), (100 * sin(a)).toFloat(), (10 * a).toFloat()); if (i == 0) f.moveTo(p) else f.lineTo(p) }
        val c = f.cursor().setT(50.5f)
        val t = assertNotNull(c.tangent()).normalize()
        val p = c.position()
        assertTrue(abs(t.x * p.x + t.y * p.y) / 100f < 0.05f, "t $t p $p")
        assertTrue(t.z > 0.05f)
    }

    @Test
    fun setDRoundTrip() {
        val f = quarter().lineTo(-50f, 100f)
        val c = f.cursor()
        for (d in listOf(0f, 10f, 80f, 157f, 180f)) { c.setD(d); assertEquals(d, c.d, 0.05f, "d=$d") }
    }

    @Test
    fun lengthSkipsMoveToJumps() {
        val f = FLine().moveTo(0f, 0f).lineTo(10f, 0f).moveTo(100f, 0f).lineTo(110f, 0f)
        assertEquals(20f, f.cursor().lengthD(), 1e-3f)
    }

    @Test
    fun splitAtANodeLeavesNoZeroLengthPiece() {
        val f = FLine().moveTo(0f, 0f).lineTo(10f, 0f).lineTo(10f, 10f)
        val (l, r) = f.cursor().setT(1f).split()
        assertEquals(2, l!!.nodes.size, "left ${l.nodes}")
        assertEquals(2, r!!.nodes.size, "right ${r.nodes}")
    }

    @Test
    fun splitAtTheEnds() {
        val f = quarter()
        val c = f.cursor()
        assertEquals(null, c.setD(0f).split().first)
        assertEquals(null, c.setD(c.lengthD()).split().second)
    }

    @Test
    fun splitDKeepsTheCursorTolerance() {
        val f = quarter(1000f)
        val pieces = f.cursor(0.001f).splitD(listOf(100f, 700f, 1200f))
        val lens = pieces.map { it.cursor(0.001f).lengthD() }
        assertEquals(100f, lens[0], 0.05f); assertEquals(600f, lens[1], 0.05f); assertEquals(500f, lens[2], 0.05f)
    }

    @Test
    fun moveToGapPositionAndTangent() {
        val f = FLine().moveTo(0f, 0f).lineTo(10f, 0f).moveTo(100f, 0f).lineTo(100f, 10f)
        val c = f.cursor().setT(1.75f)
        assertTrue(near(c.position(), Vec3(100f, 0f, 0f)), "${c.position()}")
        val t = c.tangent()
        assertTrue(t != null && near(Vec3(t).normalize(), Vec3(0f, 1f, 0f)), "tangent in the gap snapped forward: $t")
    }

    @Test
    fun closedSquareTangentOnTheClose() {
        val f = FLine().moveTo(0f, 0f).lineTo(10f, 0f).lineTo(10f, 10f).close()
        val t = assertNotNull(f.cursor().setT(2.5f).tangent())
        assertTrue(near(Vec3(t).normalize(), Vec3(-sqrt(0.5f), -sqrt(0.5f), 0f)), "$t")
    }

    @Test
    fun curvatureOfACircleArcIsOneOverR() {
        val c = quarter().cursor().setT(0.5f)
        val r = assertNotNull(c.radiusOfCurvature())
        assertEquals(100f, r.length(), 1f) // the kappa quarter-circle is not exactly round
    }

    /** the same quarter circle stood up in the xz plane: the frame must follow it out of xy. */
    @Test
    fun frameOffTheXyPlane() {
        val r = 100f; val k = 0.5522847f * r
        val f = FLine().moveTo(r, 0f, 0f).cubicTo(Vec3(r, 0f, k), Vec3(k, 0f, r), Vec3(0f, 0f, r))
        val loc = assertNotNull(f.cursor().setT(0.5f).location())
        val n = assertNotNull(loc.normal)
        assertTrue(n.dot(Vec3(loc.point).normalize()) < -0.999f, "normal $n should point at the centre")
        assertTrue(abs(abs(assertNotNull(loc.binormal).y) - 1f) < 1e-3f, "binormal ${loc.binormal}")
        assertEquals(1f / 100f, loc.curvature, 2e-4f)
        // the xy projection is a straight line along x; normal2D is still defined
        assertEquals(0f, loc.signedCurvature2D, 1e-6f)
        assertEquals(1f, loc.normal2D.length(), 1e-4f)
    }

    @Test
    fun straightRunsHaveNoPrincipalNormal() {
        val loc = assertNotNull(FLine().moveTo(0f, 0f).lineTo(10f, 0f).cursor().setT(0.3f).location())
        assertEquals(null, loc.normal); assertEquals(null, loc.binormal); assertEquals(0f, loc.curvature)
        // y down on the canvas: left of travel along +x is -y
        assertTrue(near(Vec3(loc.normal2D.x, loc.normal2D.y, 0f), Vec3(0f, -1f, 0f)), "${loc.normal2D}")
    }

    @Test
    fun splittingAClosedSquareKeepsItsLastEdge() {
        val sq = FLine().moveTo(0f, 0f).lineTo(100f, 0f).lineTo(100f, 100f).lineTo(0f, 100f).close()
        val (l, r) = sq.cursor().setT(0.5f).split()
        assertEquals(400f, l!!.length() + r!!.length(), 0.05f)
        assertTrue(near(r.cursor().setT(r.cursor().lengthT().toFloat()).position(), Vec3(0f, 0f, 0f)), "right ends at the square's start")
    }

    @Test
    fun nearestAgreesWithLocation() {
        val c = quarter().cursor().setNearest(Vec3(200f, 200f, 0f))
        val p = c.position()
        assertEquals(100f, p.length(), 0.2f)
        assertEquals(p.x, p.y, 0.5f)
    }
}
