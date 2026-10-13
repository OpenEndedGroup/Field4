package afield.geometry.path

import afield.lang.remotejs.JsFun

import afield.geometry.path.FLineExactBooleanTest.Companion.blob
import afield.geometry.path.FLineExactBooleanTest.Companion.circle
import afield.geometry.path.FLineExactBooleanTest.Companion.polyShape
import afield.geometry.path.FLineExactBooleanTest.Companion.polylines
import afield.geometry.path.FLineExactBooleanTest.Companion.ring
import afield.geometry.path.FLineExactBooleanTest.Companion.roundedRect
import afield.geometry.path.FLineExactBooleanTest.Companion.star
import afield.math.linalg.Vec3
import afield.geometry.path.java2d.FLinesAndJavaShapes
import java.awt.BasicStroke
import java.awt.geom.Area
import java.awt.geom.FlatteningPathIterator
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Measures [byOffsettingExact] against the sampled [byOffsetting] it sits beside. Error is the
 * largest | distance(point on result, original outline) - |d| | over points evaluated on the
 * result's own segments; for closed shapes the result's area is also compared with the Minkowski
 * reference (the shape grown or shrunk by a round stroke of width 2|d| through java.awt).
 */
class FLineExactOffsetTest {

    private fun distanceError(result: FLine, original: FLine, d: Double): Double {
        val segs = original.exactOpenChains().flatMap { it.first }
        var e = 0.0
        for ((c, _) in result.exactOpenChains()) for (b in c) for (k in 0..24) {
            val t = k / 24.0; val x = b.x(t); val y = b.y(t)
            var best = Double.MAX_VALUE
            for (s in segs) { val u = FLineExact.nearestT(s, x, y); best = min(best, hypot(s.x(u) - x, s.y(u) - y)) }
            e = max(e, abs(best - abs(d)))
        }
        return e
    }

    private fun minkowskiArea(f: FLine, d: Double): Double {
        val shape = Area(polyShape(f, 1e-4))
        val stroke = Area(BasicStroke((2 * abs(d)).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(polyShape(f, 1e-4)))
        val outward = (f.area2D() > 0) == (d > 0)
        if (outward) shape.add(stroke) else shape.subtract(stroke)
        return FLineExact.pathIteratorArea(FlatteningPathIterator(shape.getPathIterator(null), 1e-4, 20))
    }

    private fun areaOf(f: FLine) = FLineExact.pathIteratorArea(FlatteningPathIterator(Area(polyShape(f, 1e-4)).getPathIterator(null), 1e-4, 20))

    data class Row(val case: String, val d: Double, val segsOld: Int, val segsNew: Int, val errOld: Double, val errNew: Double, val areaErr: Double?)

    @Test
    fun exactOffsetsAreCubicAndWithinTolerance() {
        val tol = 0.01
        val closed = listOf(
            Triple("circle r100", circle(0f, 0f, 100f), listOf(20.0, -20.0)),
            Triple("rounded rect", roundedRect(0f, 0f, 200f, 120f, 30f), listOf(15.0, -15.0)),
            Triple("blob", blob(), listOf(10.0, -10.0, 30.0, -25.0)),
            Triple("star", star(0f, 0f, 100f, 45f, 5), listOf(10.0, -8.0, -30.0)),
            Triple("ring glyph", ring(0f, 0f, 100f, 60f), listOf(8.0, -8.0, 25.0)),
        )
        val rows = ArrayList<Row>()
        for ((name, f, ds) in closed) for (d in ds) {
            val old = f.byOffsetting(d.toFloat())
            val new = f.byOffsettingExact(d.toFloat(), tol.toFloat())
            val ref = minkowskiArea(f, d)
            rows.add(Row(name, d, old.segmentCount2D(), new.segmentCount2D(), distanceError(old, f, d), distanceError(new, f, d),
                abs(areaOf(new) - ref) / ref))
        }
        val s = FLine().moveTo(0f, 0f).cubicTo(Vec3(60f, -80f, 0f), Vec3(100f, 80f, 0f), Vec3(160f, 0f, 0f))
            .cubicTo(Vec3(200f, -50f, 0f), Vec3(240f, 40f, 0f), Vec3(280f, 10f, 0f)).lineTo(300f, 80f)
        for (d in listOf(10.0, -10.0)) {
            val old = s.byOffsetting(d.toFloat())
            val new = s.byOffsettingExact(d.toFloat(), tol.toFloat())
            rows.add(Row("open S-curve", d, old.segmentCount2D(), new.segmentCount2D(), distanceError(old, s, d), distanceError(new, s, d), null))
        }
        println("| case | d | segments sampled -> exact | max distance error sampled -> exact | area vs Minkowski |")
        println("| --- | --- | --- | --- | --- |")
        for (r in rows) println("| ${r.case} | ${r.d} | ${r.segsOld} -> ${r.segsNew} | %.3g -> %.3g | %s |".format(r.errOld, r.errNew,
            r.areaErr?.let { "%.1e".format(it) } ?: "(open)"))
        for (r in rows) {
            assertTrue(r.errNew <= tol * 1.5, "${r.case} d=${r.d}: error ${r.errNew}")
            assertTrue(r.segsNew < r.segsOld, "${r.case} d=${r.d}: ${r.segsNew} vs ${r.segsOld} segments")
            r.areaErr?.let { assertTrue(it < 2e-3, "${r.case} d=${r.d}: area off by $it") }
        }
    }

    @Test
    fun circleOffsetIsACircle() {
        val c = circle(0f, 0f, 100f)
        val o = c.byOffsettingExact(25f, 1e-2f)
        assertTrue(o.nodes.count { it is FLine.CubicTo } in 4..8, "circle offset: ${o.nodes.count { it is FLine.CubicTo }} cubics")
        val pl = polylines(FLinesAndJavaShapes.flineToJavaShape(o), 1e-3)
        var worst = 0.0
        for (p in pl) { var i = 0; while (i < p.size) { worst = max(worst, abs(hypot(p[i], p[i + 1]) - 125.0)); i += 2 } }
        // the kappa circle itself is 2.7e-4 r off round; the offset is held to that plus tolerance
        assertTrue(worst < 0.05, "offset circle radius error $worst")
    }

    @Test
    fun joinsAreHonoured() {
        val sq = FLineExactBooleanTest.rect(0f, 0f, 100f, 100f)
        val outward = if (sq.area2D() > 0) 10f else -10f
        val round = sq.byOffsettingExact(outward, join = FLineStrokeJoin.ROUND)
        val miter = sq.byOffsettingExact(outward, join = FLineStrokeJoin.MITER)
        val bevel = sq.byOffsettingExact(outward, join = FLineStrokeJoin.BEVEL)
        assertTrue(abs(areaOf(miter) - 120.0 * 120.0) < 1.0, "miter ${areaOf(miter)}")
        assertTrue(abs(areaOf(bevel) - (120.0 * 120.0 - 4 * 50.0)) < 1.0, "bevel ${areaOf(bevel)}")
        assertTrue(abs(areaOf(round) - (100.0 * 100 + 4 * 100 * 10 + Math.PI * 100)) < 1.0, "round ${areaOf(round)}")
        assertTrue(round.nodes.count { it is FLine.CubicTo } == 4 && round.nodes.count { it is FLine.LineTo } >= 3)
    }
}
