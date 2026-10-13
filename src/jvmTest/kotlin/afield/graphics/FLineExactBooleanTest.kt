package afield.graphics

import afield.stdlib.Vec3
import field.graphics.FLinesAndJavaShapes
import java.awt.geom.Area
import java.awt.geom.FlatteningPathIterator
import java.awt.geom.Path2D
import java.awt.geom.PathIterator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Measures the curve-preserving booleans ([booleanExact]) and offsets ([byOffsettingExact]) against
 * the java.awt.geom.Area / sampled paths they sit beside, on a fixed corpus. Error is a symmetric
 * Hausdorff distance to a reference built from the operands flattened to 1e-5 of their size; the
 * numbers printed here are the ones quoted in dev_docs/fline_exact_booleans.md.
 */
class FLineExactBooleanTest {

    companion object {
        fun circle(cx: Float, cy: Float, r: Float): FLine {
            val k = 0.5522847f * r
            return FLine().moveTo(cx + r, cy)
                .cubicTo(Vec3(cx + r, cy + k, 0f), Vec3(cx + k, cy + r, 0f), Vec3(cx, cy + r, 0f))
                .cubicTo(Vec3(cx - k, cy + r, 0f), Vec3(cx - r, cy + k, 0f), Vec3(cx - r, cy, 0f))
                .cubicTo(Vec3(cx - r, cy - k, 0f), Vec3(cx - k, cy - r, 0f), Vec3(cx, cy - r, 0f))
                .cubicTo(Vec3(cx + k, cy - r, 0f), Vec3(cx + r, cy - k, 0f), Vec3(cx + r, cy, 0f))
                .close()
        }

        /** the same circle traced the other way (a hole when appended to a counter-clockwise outline). */
        fun circleCW(cx: Float, cy: Float, r: Float): FLine {
            val k = 0.5522847f * r
            return FLine().moveTo(cx + r, cy)
                .cubicTo(Vec3(cx + r, cy - k, 0f), Vec3(cx + k, cy - r, 0f), Vec3(cx, cy - r, 0f))
                .cubicTo(Vec3(cx - k, cy - r, 0f), Vec3(cx - r, cy - k, 0f), Vec3(cx - r, cy, 0f))
                .cubicTo(Vec3(cx - r, cy + k, 0f), Vec3(cx - k, cy + r, 0f), Vec3(cx, cy + r, 0f))
                .cubicTo(Vec3(cx + k, cy + r, 0f), Vec3(cx + r, cy + k, 0f), Vec3(cx + r, cy, 0f))
                .close()
        }

        fun roundedRect(x: Float, y: Float, w: Float, h: Float, r: Float): FLine {
            val k = 0.5522847f * r
            return FLine().moveTo(x + r, y)
                .lineTo(x + w - r, y)
                .cubicTo(Vec3(x + w - r + k, y, 0f), Vec3(x + w, y + r - k, 0f), Vec3(x + w, y + r, 0f))
                .lineTo(x + w, y + h - r)
                .cubicTo(Vec3(x + w, y + h - r + k, 0f), Vec3(x + w - r + k, y + h, 0f), Vec3(x + w - r, y + h, 0f))
                .lineTo(x + r, y + h)
                .cubicTo(Vec3(x + r - k, y + h, 0f), Vec3(x, y + h - r + k, 0f), Vec3(x, y + h - r, 0f))
                .lineTo(x, y + r)
                .cubicTo(Vec3(x, y + r - k, 0f), Vec3(x + r - k, y, 0f), Vec3(x + r, y, 0f))
                .close()
        }

        fun rect(x: Float, y: Float, w: Float, h: Float) =
            FLine().moveTo(x, y).lineTo(x + w, y).lineTo(x + w, y + h).lineTo(x, y + h).close()

        /** an "O": outer ring with a counter-wound inner hole, like a font glyph. */
        fun ring(cx: Float, cy: Float, r0: Float, r1: Float): FLine {
            val f = circle(cx, cy, r0)
            f.nodes.addAll(circleCW(cx, cy, r1).nodes)
            return f
        }

        /** a glyph-like blob: a closed loop of cubics with an S-shaped waist and a sharp corner. */
        fun blob(): FLine = FLine().moveTo(0f, 0f)
            .cubicTo(Vec3(40f, -30f, 0f), Vec3(90f, 10f, 0f), Vec3(100f, 50f, 0f))
            .cubicTo(Vec3(110f, 90f, 0f), Vec3(60f, 70f, 0f), Vec3(50f, 110f, 0f))
            .cubicTo(Vec3(40f, 150f, 0f), Vec3(-20f, 120f, 0f), Vec3(-10f, 80f, 0f))
            .cubicTo(Vec3(0f, 50f, 0f), Vec3(-30f, 30f, 0f), Vec3(0f, 0f, 0f))
            .close()

        fun star(cx: Float, cy: Float, r0: Float, r1: Float, n: Int): FLine {
            val f = FLine()
            for (i in 0 until 2 * n) {
                val r = if (i % 2 == 0) r0 else r1
                val a = PI * i / n
                val x = cx + r * cos(a).toFloat(); val y = cy + r * sin(a).toFloat()
                if (i == 0) f.moveTo(x, y) else f.lineTo(x, y)
            }
            return f.close()
        }

        /** polylines of a shape flattened to [flat]. */
        fun polylines(shape: java.awt.Shape, flat: Double): List<DoubleArray> {
            val it = FlatteningPathIterator(shape.getPathIterator(null), flat, 20)
            val out = ArrayList<DoubleArray>(); var cur = ArrayList<Double>()
            val c = DoubleArray(6); var sx = 0.0; var sy = 0.0
            fun flush() { if (cur.size >= 4) out.add(cur.toDoubleArray()); cur = ArrayList() }
            while (!it.isDone) {
                when (it.currentSegment(c)) {
                    PathIterator.SEG_MOVETO -> { flush(); sx = c[0]; sy = c[1]; cur.add(c[0]); cur.add(c[1]) }
                    PathIterator.SEG_LINETO -> { cur.add(c[0]); cur.add(c[1]) }
                    PathIterator.SEG_CLOSE -> { cur.add(sx); cur.add(sy); flush() }
                }
                it.next()
            }
            flush()
            return out
        }

        fun polyShape(f: FLine, flat: Double): Path2D.Double {
            val p = Path2D.Double(Path2D.WIND_NON_ZERO)
            for (pl in polylines(FLinesAndJavaShapes.flineToJavaShape(f), flat)) {
                p.moveTo(pl[0], pl[1]); var i = 2
                while (i < pl.size) { p.lineTo(pl[i], pl[i + 1]); i += 2 }
                p.closePath()
            }
            return p
        }

        fun distToPolys(x: Double, y: Double, ps: List<DoubleArray>): Double {
            var best = Double.MAX_VALUE
            for (pl in ps) {
                var i = 0
                while (i + 3 < pl.size) {
                    val ax = pl[i]; val ay = pl[i + 1]; val bx = pl[i + 2]; val by = pl[i + 3]
                    val ex = bx - ax; val ey = by - ay; val l2 = ex * ex + ey * ey
                    val t = if (l2 == 0.0) 0.0 else (((x - ax) * ex + (y - ay) * ey) / l2).coerceIn(0.0, 1.0)
                    best = min(best, hypot(ax + t * ex - x, ay + t * ey - y))
                    i += 2
                }
            }
            return best
        }

        /** symmetric Hausdorff distance between the outlines of [result] and [reference] polylines. */
        fun hausdorff(result: FLine, reference: List<DoubleArray>, flat: Double): Double {
            val mine = polylines(FLinesAndJavaShapes.flineToJavaShape(result), flat)
            var e = 0.0
            for (pl in mine) { var i = 0; while (i < pl.size) { e = max(e, distToPolys(pl[i], pl[i + 1], reference)); i += 2 } }
            for (pl in reference) { var i = 0; while (i < pl.size) { e = max(e, distToPolys(pl[i], pl[i + 1], mine)); i += 2 } }
            return e
        }

        /**
         * max distance from points on [result]'s segments (evaluated exactly, 24 per segment) to the
         * nearest operand curve (Newton-refined): how far the result strays from the true outline.
         */
        fun offOutline(result: FLine, inputs: List<FLine>): Double {
            val segs = inputs.flatMap { it.exactContours().flatten() }
            var e = 0.0
            for (c in result.exactContours()) for (b in c) for (k in 0..24) {
                val t = k / 24.0; val x = b.x(t); val y = b.y(t)
                var best = Double.MAX_VALUE
                for (s in segs) {
                    if (x < s.minX() - best || x > s.maxX() + best || y < s.minY() - best || y > s.maxY() + best) continue
                    val u = FLineExact.nearestT(s, x, y)
                    best = min(best, hypot(s.x(u) - x, s.y(u) - y))
                }
                e = max(e, best)
            }
            return e
        }

        fun referenceBoolean(a: FLine, b: FLine, op: FLineBooleanOp, flat: Double): List<DoubleArray> {
            val a1 = Area(polyShape(a, flat)); val a2 = Area(polyShape(b, flat))
            when (op) {
                FLineBooleanOp.Union -> a1.add(a2)
                FLineBooleanOp.Intersect -> a1.intersect(a2)
                FLineBooleanOp.Subtract -> a1.subtract(a2)
                FLineBooleanOp.Xor -> a1.exclusiveOr(a2)
            }
            return polylines(a1, flat)
        }

        val corpus: List<Triple<String, FLine, FLine>> by lazy {
            listOf(
                Triple("circles", circle(0f, 0f, 100f), circle(80f, 30f, 70f)),
                Triple("rounded rects", roundedRect(0f, 0f, 200f, 120f, 30f), roundedRect(120f, 60f, 160f, 140f, 45f)),
                Triple("ring and bar", ring(0f, 0f, 100f, 60f), rect(-130f, -20f, 260f, 40f)),
                Triple("blob and circle", blob(), circle(70f, 60f, 45f)),
                Triple("tangent circles", circle(0f, 0f, 50f), circle(100f, 0f, 50f)),
                Triple("shared edge", rect(0f, 0f, 100f, 100f), rect(100f, 20f, 80f, 60f)),
                Triple("same circle", circle(0f, 0f, 60f), circle(0f, 0f, 60f)),
                Triple("nested circles", circle(0f, 0f, 100f), circle(10f, 0f, 40f)),
            )
        }
    }

    data class Row(val case: String, val op: FLineBooleanOp, val exact: Boolean, val reason: String?,
                   val segsOld: Int, val segsNew: Int, val errOld: Double, val errNew: Double,
                   val offOld: Double, val offNew: Double, val size: Double)

    fun measure(): List<Row> {
        val rows = ArrayList<Row>()
        for ((name, a, b) in corpus) {
            val bb = Area(FLinesAndJavaShapes.flineToJavaShape(a)).also { it.add(Area(FLinesAndJavaShapes.flineToJavaShape(b))) }.bounds2D
            val size = max(bb.width, bb.height)
            val flat = size * 1e-5
            for (op in FLineBooleanOp.values()) {
                val ref = referenceBoolean(a, b, op, flat)
                val old = FLineExact.awt(a, b, op)
                val res = a.booleanExact(b, op)
                rows.add(Row(name, op, res.exact, res.fallbackReason, old.segmentCount2D(), res.line.segmentCount2D(),
                    if (ref.isEmpty() && old.segmentCount2D() == 0) 0.0 else hausdorff(old, ref, flat),
                    if (ref.isEmpty() && res.line.segmentCount2D() == 0) 0.0 else hausdorff(res.line, ref, flat),
                    offOutline(old, listOf(a, b)), offOutline(res.line, listOf(a, b)), size))
            }
        }
        return rows
    }

    @Test
    fun exactBooleansKeepCurvesAndAreAtLeastAsAccurate() {
        val rows = measure()
        println("| case | op | exact | segments AWT -> exact | off true outline AWT -> exact | Hausdorff to 1e-5 reference AWT -> exact |")
        println("| --- | --- | --- | --- | --- | --- |")
        for (r in rows) println("| ${r.case} | ${r.op} | ${if (r.exact) "yes" else "no: ${r.reason}"} | ${r.segsOld} -> ${r.segsNew} | " +
            "%.1e -> %.1e | %.1e -> %.1e |".format(r.offOld / r.size, r.offNew / r.size, r.errOld / r.size, r.errNew / r.size))
        for (r in rows) {
            assertTrue(r.exact, "${r.case} ${r.op} fell back: ${r.reason}")
            assertTrue(r.errNew <= 2e-4 * r.size, "${r.case} ${r.op}: exact error ${r.errNew} on size ${r.size}")
            assertTrue(r.offNew <= 1e-6 * r.size, "${r.case} ${r.op}: exact result strays ${r.offNew} off the operands")
            assertTrue(r.segsNew <= r.segsOld, "${r.case} ${r.op}: ${r.segsNew} segments vs AWT ${r.segsOld}")
        }
    }

    /** seeded random blobs: smooth closed cubic loops with 3-7 segments, some near-tangent pairs. */
    private fun randomBlob(rnd: kotlin.random.Random, cx: Double, cy: Double, r: Double): FLine {
        val n = 3 + rnd.nextInt(5)
        val pts = (0 until n).map { i ->
            val a = 2 * PI * (i + rnd.nextDouble(-0.3, 0.3)) / n
            val rr = r * rnd.nextDouble(0.5, 1.2)
            doubleArrayOf(cx + rr * cos(a), cy + rr * sin(a))
        }
        val f = FLine().moveTo(pts[0][0], pts[0][1])
        for (i in 0 until n) {
            val p0 = pts[i]; val p1 = pts[(i + 1) % n]; val pm = pts[(i + n - 1) % n]; val p2 = pts[(i + 2) % n]
            val c1x = p0[0] + (p1[0] - pm[0]) / 6; val c1y = p0[1] + (p1[1] - pm[1]) / 6
            val c2x = p1[0] - (p2[0] - p0[0]) / 6; val c2y = p1[1] - (p2[1] - p0[1]) / 6
            f.cubicTo(c1x, c1y, c2x, c2y, p1[0], p1[1])
        }
        return f.close()
    }

    @Test
    fun randomCorpusAgreesWithTheReference() {
        val rnd = kotlin.random.Random(20261013)
        var exactCount = 0; var total = 0; var worstExactOff = 0.0; var worstAwtOff = 0.0
        var worstExactArea = 0.0; var worstAwtArea = 0.0; var segsAwt = 0; var segsExact = 0
        val reasons = ArrayList<String>()
        repeat(60) { k ->
            val a = randomBlob(rnd, 0.0, 0.0, 100.0)
            val b = randomBlob(rnd, rnd.nextDouble(-80.0, 80.0), rnd.nextDouble(-80.0, 80.0), rnd.nextDouble(30.0, 110.0))
            for (op in FLineBooleanOp.values()) {
                total++
                val ref = Area(polyShape(a, 1e-4)).also { r ->
                    val o = Area(polyShape(b, 1e-4))
                    when (op) { FLineBooleanOp.Union -> r.add(o); FLineBooleanOp.Intersect -> r.intersect(o)
                        FLineBooleanOp.Subtract -> r.subtract(o); FLineBooleanOp.Xor -> r.exclusiveOr(o) }
                }
                val refArea = FLineExact.pathIteratorArea(ref.getPathIterator(null))
                val old = FLineExact.awt(a, b, op)
                val res = a.booleanExact(b, op)
                if (res.exact) exactCount++ else reasons.add("#$k $op: ${res.fallbackReason}")
                fun areaOf(f: FLine) = FLineExact.pathIteratorArea(FlatteningPathIterator(Area(polyShape(f, 1e-4)).getPathIterator(null), 1e-4, 20))
                worstAwtArea = max(worstAwtArea, abs(areaOf(old) - refArea) / 40000.0)
                worstAwtOff = max(worstAwtOff, offOutline(old, listOf(a, b)) / 200.0)
                segsAwt += old.segmentCount2D()
                if (res.exact) {
                    worstExactArea = max(worstExactArea, abs(areaOf(res.line) - refArea) / 40000.0)
                    worstExactOff = max(worstExactOff, offOutline(res.line, listOf(a, b)) / 200.0)
                    segsExact += res.line.segmentCount2D()
                } else segsExact += old.segmentCount2D()
            }
        }
        println("random corpus: $exactCount / $total exact; segments AWT $segsAwt, exact-or-fallback $segsExact")
        println("worst off-outline (fraction of 200): AWT %.1e, exact %.1e".format(worstAwtOff, worstExactOff))
        println("worst area error (fraction of 200^2): AWT %.1e, exact %.1e".format(worstAwtArea, worstExactArea))
        reasons.forEach { println("fallback $it") }
        assertTrue(exactCount >= total * 9 / 10, "only $exactCount / $total exact")
        assertTrue(worstExactOff < 1e-6 && worstExactArea < 1e-4, "exact results off: $worstExactOff / $worstExactArea")
    }

    @Test
    fun cutPiecesAreSubcurvesOfTheOperands() {
        // every output cubic must lie on one of the input outlines (no re-fitting, no flattening)
        val (_, a, b) = corpus[0]
        val inputs = polylines(FLinesAndJavaShapes.flineToJavaShape(a), 1e-4) + polylines(FLinesAndJavaShapes.flineToJavaShape(b), 1e-4)
        val u = a.booleanExact(b, FLineBooleanOp.Union)
        assertTrue(u.exact)
        val pts = polylines(FLinesAndJavaShapes.flineToJavaShape(u.line), 1e-3)
        var worst = 0.0
        for (pl in pts) { var i = 0; while (i < pl.size) { worst = max(worst, distToPolys(pl[i], pl[i + 1], inputs)); i += 2 } }
        assertTrue(worst < 1e-3, "union strays $worst from the inputs")
        assertTrue(u.line.nodes.count { it is FLine.CubicTo } >= 6, "union of two circles keeps its cubics")
    }

    @Test
    fun exactFlagOnTheOldEntryPoints() {
        val a = circle(0f, 0f, 100f); val b = circle(80f, 30f, 70f)
        assertTrue(abs(a.byUnionWith(b, exact = true).area2D() - a.booleanExact(b, FLineBooleanOp.Union).line.area2D()) < 1e-3)
        assertTrue(a.byDifferenceWith(b, exact = true).segmentCount2D() > 0)
        assertTrue(a.byIntersectionWith(b, exact = true).segmentCount2D() > 0)
        assertTrue(a.byExclusiveOrWith(b, exact = true).segmentCount2D() > 0)
    }
}
