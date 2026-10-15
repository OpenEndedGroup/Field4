package afield.geometry.path

import afield.lang.remotejs.JsFun
import afield.ui.visualization.lines

import afield.ui.visualization.l

import afield.graphics.render.thicken
import afield.graphics.render.thickness
import afield.geometry.path.java2d.FLinesAndJavaShapes

import java.awt.geom.Area
import java.awt.geom.FlatteningPathIterator
import java.awt.geom.PathIterator
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Curve-preserving boolean operations and cubic offsets for [FLine], in the manner of Paper.js.
 *
 * Booleans: every segment of both operands is cut at its exact crossings with every other segment
 * (Bézier–Bézier by bounding-box subdivision refined by Newton, plus endpoint-on-curve projection
 * for coincident edges and T-junctions). Each resulting piece is a sub-curve of an original
 * segment, so cubics stay cubics and straight segments stay [FLine.LineTo]. A piece is kept when
 * the operation's answer differs a hair to its left and to its right (winding numbers of both
 * operands, non-zero rule), oriented with the result on its left, and the kept pieces are chained
 * into closed contours. The result is checked against the java.awt.geom.Area answer by area; on
 * any failure the Area answer is returned instead ([FLineBooleanResult.exact] says which).
 *
 * Offsets: each cubic is approximated by a cubic whose end tangents are the original's and whose
 * handle lengths are fitted to the true offset at the midpoint (Tiller–Hanson curvature scaling
 * where that fit is ill-conditioned), subdivided until the sampled error is within tolerance.
 * Corners get round, miter or bevel joins; closed results are resolved through the boolean engine
 * with a positive-winding rule, which removes the swallowtails at concave corners.
 *
 * All arithmetic is in doubles over the xy plane; z is dropped.
 */

/** The four boolean operations. */
enum class FLineBooleanOp { Union, Intersect, Subtract, Xor }

/**
 * The answer of [FLine.booleanExact]: [line] is the result, [exact] is whether it came from the
 * curve-preserving engine (otherwise from java.awt.geom.Area, for the [fallbackReason] given).
 */
class FLineBooleanResult(val line: FLine, val exact: Boolean, val fallbackReason: String?)

internal class XBez(@JvmField val p: DoubleArray, @JvmField val straight: Boolean) {
    /** the operand segment this piece was cut from, and the parameters it spans there (reversed pieces run ot0 > ot1). */
    var origin: XBez? = null; var ot0 = 0.0; var ot1 = 1.0
    fun x(t: Double): Double { val v = 1 - t; return v * v * v * p[0] + 3 * v * v * t * p[2] + 3 * v * t * t * p[4] + t * t * t * p[6] }
    fun y(t: Double): Double { val v = 1 - t; return v * v * v * p[1] + 3 * v * v * t * p[3] + 3 * v * t * t * p[5] + t * t * t * p[7] }
    fun dx(t: Double): Double { val v = 1 - t; return 3 * v * v * (p[2] - p[0]) + 6 * v * t * (p[4] - p[2]) + 3 * t * t * (p[6] - p[4]) }
    fun dy(t: Double): Double { val v = 1 - t; return 3 * v * v * (p[3] - p[1]) + 6 * v * t * (p[5] - p[3]) + 3 * t * t * (p[7] - p[5]) }
    val x0 get() = p[0]; val y0 get() = p[1]; val x3 get() = p[6]; val y3 get() = p[7]

    /** a usable tangent at t even where the derivative vanishes (handle on its end point). */
    fun tangent(t: Double): DoubleArray {
        var tx = dx(t); var ty = dy(t)
        if (hypot(tx, ty) < 1e-12 * (1 + scale())) {
            val s = if (t < 0.5) t + 1e-4 else t - 1e-4
            tx = dx(s); ty = dy(s)
            if (hypot(tx, ty) < 1e-15) { tx = p[6] - p[0]; ty = p[7] - p[1] }
        }
        val l = hypot(tx, ty).takeIf { it > 0 } ?: 1.0
        return doubleArrayOf(tx / l, ty / l)
    }

    fun scale() = max(abs(p[6] - p[0]) + abs(p[7] - p[1]), abs(p[2] - p[0]) + abs(p[4] - p[6]))

    fun reversed() = XBez(doubleArrayOf(p[6], p[7], p[4], p[5], p[2], p[3], p[0], p[1]), straight).also {
        it.origin = origin; it.ot0 = ot1; it.ot1 = ot0
    }

    fun sub(t0: Double, t1: Double): XBez {
        var q = p
        if (t0 > 0) q = splitAt(q, t0).second
        if (t1 < 1) q = splitAt(q, if (t0 >= 1) 0.0 else (t1 - t0) / (1 - t0)).first
        return XBez(q.copyOf(), straight)
    }

    fun minX() = min(min(p[0], p[2]), min(p[4], p[6])); fun maxX() = max(max(p[0], p[2]), max(p[4], p[6]))
    fun minY() = min(min(p[1], p[3]), min(p[5], p[7])); fun maxY() = max(max(p[1], p[3]), max(p[5], p[7]))

    /** max distance of the handles from the chord. */
    fun flatness(): Double {
        val cx = p[6] - p[0]; val cy = p[7] - p[1]; val l = hypot(cx, cy)
        if (l < 1e-300) return max(hypot(p[2] - p[0], p[3] - p[1]), hypot(p[4] - p[0], p[5] - p[1]))
        val d1 = abs((p[2] - p[0]) * cy - (p[3] - p[1]) * cx) / l
        val d2 = abs((p[4] - p[0]) * cy - (p[5] - p[1]) * cx) / l
        return max(d1, d2)
    }

    companion object {
        fun splitAt(q: DoubleArray, s: Double): Pair<DoubleArray, DoubleArray> {
            fun l(a: Double, b: Double) = a + (b - a) * s
            val ax = l(q[0], q[2]); val ay = l(q[1], q[3]); val bx = l(q[2], q[4]); val by = l(q[3], q[5])
            val cx = l(q[4], q[6]); val cy = l(q[5], q[7])
            val abx = l(ax, bx); val aby = l(ay, by); val bcx = l(bx, cx); val bcy = l(by, cy)
            val mx = l(abx, bcx); val my = l(aby, bcy)
            return doubleArrayOf(q[0], q[1], ax, ay, abx, aby, mx, my) to doubleArrayOf(mx, my, bcx, bcy, cx, cy, q[6], q[7])
        }

        fun line(ax: Double, ay: Double, bx: Double, by: Double) =
            XBez(doubleArrayOf(ax, ay, ax + (bx - ax) / 3, ay + (by - ay) / 3, ax + 2 * (bx - ax) / 3, ay + 2 * (by - ay) / 3, bx, by), true)
    }
}

/** Closed xy contours of a line; open subpaths are closed by a straight segment, as AWT fills them. */
internal fun FLine.exactContours(): List<List<XBez>> {
    val out = ArrayList<List<XBez>>()
    var cur = ArrayList<XBez>()
    fun flush() {
        if (cur.isNotEmpty()) {
            val a = cur.first(); val b = cur.last()
            if (hypot(a.x0 - b.x3, a.y0 - b.y3) > 1e-9 * (1 + abs(a.x0) + abs(a.y0))) cur.add(XBez.line(b.x3, b.y3, a.x0, a.y0))
            else { // snap exactly closed
                b.p[6] = a.x0; b.p[7] = a.y0
            }
            out.add(cur)
        }
        cur = ArrayList()
    }
    for (i in nodes.indices) {
        if (nodes[i] is FLine.MoveTo) { flush(); continue }
        val s = drawnSegmentEndingAt(i) ?: continue
        val b = XBez(doubleArrayOf(s.p0.x.toDouble(), s.p0.y.toDouble(), s.p1.x.toDouble(), s.p1.y.toDouble(),
            s.p2.x.toDouble(), s.p2.y.toDouble(), s.p3.x.toDouble(), s.p3.y.toDouble()), s.straight)
        val extent = max(abs(b.maxX() - b.minX()), abs(b.maxY() - b.minY()))
        if (extent <= 1e-12) continue
        cur.add(b)
        if (nodes[i] is FLine.Close) flush()
    }
    flush()
    return out
}

internal object FLineExact {

    // ---------------- winding ----------------

    /** winding number of [contours] about (px, py): a horizontal ray to +x, counter-clockwise (y up) positive. */
    fun winding(contours: List<List<XBez>>, px: Double, py: Double): Int {
        var w = 0
        for (c in contours) for (b in c) w += windingOf(b, px, py)
        return w
    }

    private fun windingOf(b: XBez, px: Double, py: Double): Int {
        if (py < b.minY() || py > b.maxY()) return 0
        if (b.maxX() < px) return 0
        val p = b.p
        if (b.straight) return crossMono(b, 0.0, 1.0, p[1], p[7], px, py)
        // split into y-monotone pieces at roots of y'
        val d0 = p[3] - p[1]; val d1 = p[5] - p[3]; val d2 = p[7] - p[5]
        val qa = d0 - 2 * d1 + d2; val qb = 2 * (d1 - d0); val qc = d0
        val ts = ArrayList<Double>(4); ts.add(0.0)
        for (r in quadRoots(qa, qb, qc)) if (r > 1e-12 && r < 1 - 1e-12) ts.add(r)
        ts.add(1.0); ts.sort()
        var w = 0
        for (k in 0 until ts.size - 1) w += crossMono(b, ts[k], ts[k + 1], b.y(ts[k]), b.y(ts[k + 1]), px, py)
        return w
    }

    private fun crossMono(b: XBez, s0: Double, s1: Double, ya: Double, yb: Double, px: Double, py: Double): Int {
        val up = ya <= py && py < yb
        val down = yb <= py && py < ya
        if (!up && !down) return 0
        var lo = s0; var hi = s1
        val inc = yb > ya
        for (it in 0 until 64) {
            val m = 0.5 * (lo + hi)
            val ym = b.y(m)
            if ((ym < py) == inc) lo = m else hi = m
            if (hi - lo < 1e-15) break
        }
        val x = b.x(0.5 * (lo + hi))
        if (x <= px) return 0
        return if (up) 1 else -1
    }

    fun quadRoots(a: Double, b: Double, c: Double): List<Double> {
        val scale = max(abs(a), max(abs(b), abs(c)))
        if (scale == 0.0) return emptyList()
        if (abs(a) < 1e-12 * scale) return if (abs(b) < 1e-300) emptyList() else listOf(-c / b)
        val disc = b * b - 4 * a * c
        if (disc < 0) return emptyList()
        val sq = sqrt(disc)
        val q = -0.5 * (b + sign(b).let { if (it == 0.0) 1.0 else it } * sq)
        val r = ArrayList<Double>(2)
        r.add(q / a); if (q != 0.0) r.add(c / q)
        return r
    }

    // ---------------- intersection ----------------

    class Hit(val ia: Int, val ta: Double, val ib: Int, val tb: Double, val x: Double, val y: Double)

    /** parameter of the point of [b] nearest (x, y): coarse samples, golden section about the best, guarded Newton polish. */
    fun nearestT(b: XBez, x: Double, y: Double): Double {
        if (b.straight) {
            val ex = b.x3 - b.x0; val ey = b.y3 - b.y0; val l2 = ex * ex + ey * ey
            return if (l2 == 0.0) 0.0 else (((x - b.x0) * ex + (y - b.y0) * ey) / l2).coerceIn(0.0, 1.0)
        }
        fun d2(t: Double): Double { val dx = b.x(t) - x; val dy = b.y(t) - y; return dx * dx + dy * dy }
        val n = 32
        var best = 0.0; var bd = Double.MAX_VALUE
        for (k in 0..n) { val t = k.toDouble() / n; val d = d2(t); if (d < bd) { bd = d; best = t } }
        var lo = max(0.0, best - 1.0 / n); var hi = min(1.0, best + 1.0 / n)
        val g = (sqrt(5.0) - 1) / 2
        var c = hi - g * (hi - lo); var d = lo + g * (hi - lo)
        var fc = d2(c); var fd = d2(d)
        for (it in 0 until 60) {
            if (fc < fd) { hi = d; d = c; fd = fc; c = hi - g * (hi - lo); fc = d2(c) }
            else { lo = c; c = d; fc = fd; d = lo + g * (hi - lo); fd = d2(d) }
            if (hi - lo < 1e-15) break
        }
        var t = 0.5 * (lo + hi); var ft = d2(t)
        if (bd < ft) { t = best; ft = bd }
        for (it in 0 until 4) { // Newton polish, kept only when it helps
            val fx = b.x(t) - x; val fy = b.y(t) - y; val dx = b.dx(t); val dy = b.dy(t)
            val h = 1e-6
            val ddx = (b.dx(min(1.0, t + h)) - b.dx(max(0.0, t - h))) / (min(1.0, t + h) - max(0.0, t - h))
            val ddy = (b.dy(min(1.0, t + h)) - b.dy(max(0.0, t - h))) / (min(1.0, t + h) - max(0.0, t - h))
            val gp = dx * dx + dy * dy + fx * ddx + fy * ddy
            if (gp <= 0) break
            val nt = (t - (fx * dx + fy * dy) / gp).coerceIn(0.0, 1.0)
            val fn = d2(nt)
            if (fn >= ft) break
            t = nt; ft = fn
        }
        return t
    }

    private fun newton(a: XBez, b: XBez, t0: Double, u0: Double): DoubleArray {
        var t = t0; var u = u0
        for (it in 0 until 40) {
            val fx = a.x(t) - b.x(u); val fy = a.y(t) - b.y(u)
            val j11 = a.dx(t); val j12 = -b.dx(u); val j21 = a.dy(t); val j22 = -b.dy(u)
            val det = j11 * j22 - j12 * j21
            if (abs(det) < 1e-300) break
            val dt = (fx * j22 - fy * j12) / det
            val du = (j11 * fy - j21 * fx) / det
            val nt = (t - dt).coerceIn(0.0, 1.0); val nu = (u - du).coerceIn(0.0, 1.0)
            val done = abs(nt - t) < 1e-15 && abs(nu - u) < 1e-15
            t = nt; u = nu
            if (done) break
        }
        return doubleArrayOf(t, u, hypot(a.x(t) - b.x(u), a.y(t) - b.y(u)))
    }

    private class Budget(var leaves: Int)

    private fun rec(a: XBez, a0: Double, a1: Double, b: XBez, b0: Double, b1: Double, flat: Double, depth: Int,
                    out: MutableList<DoubleArray>, budget: Budget) {
        val pad = flat
        if (a.maxX() + pad < b.minX() || b.maxX() + pad < a.minX() || a.maxY() + pad < b.minY() || b.maxY() + pad < a.minY()) return
        if (budget.leaves <= 0) return
        val fa = a.straight || a.flatness() <= flat; val fb = b.straight || b.flatness() <= flat
        if ((fa && fb) || depth > 50) {
            budget.leaves--
            val r = segSeg(a.x0, a.y0, a.x3, a.y3, b.x0, b.y0, b.x3, b.y3) ?: return
            out.add(doubleArrayOf(a0 + r[0] * (a1 - a0), b0 + r[1] * (b1 - b0)))
            return
        }
        val sizeA = (a.maxX() - a.minX()) + (a.maxY() - a.minY())
        val sizeB = (b.maxX() - b.minX()) + (b.maxY() - b.minY())
        if (!fa && (sizeA >= sizeB || fb)) {
            val (l, r) = XBez.splitAt(a.p, 0.5); val am = 0.5 * (a0 + a1)
            rec(XBez(l, false), a0, am, b, b0, b1, flat, depth + 1, out, budget)
            rec(XBez(r, false), am, a1, b, b0, b1, flat, depth + 1, out, budget)
        } else {
            val (l, r) = XBez.splitAt(b.p, 0.5); val bm = 0.5 * (b0 + b1)
            rec(a, a0, a1, XBez(l, false), b0, bm, flat, depth + 1, out, budget)
            rec(a, a0, a1, XBez(r, false), bm, b1, flat, depth + 1, out, budget)
        }
    }

    /** parameters (s, t) where segment p0p1 meets q0q1, with a little slack at the ends. */
    private fun segSeg(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, dx: Double, dy: Double): DoubleArray? {
        val rx = bx - ax; val ry = by - ay; val sx = dx - cx; val sy = dy - cy
        val den = rx * sy - ry * sx
        if (abs(den) < 1e-300) return null
        val qx = cx - ax; val qy = cy - ay
        val s = (qx * sy - qy * sx) / den
        val t = (qx * ry - qy * rx) / den
        val slack = 1e-6
        if (s < -slack || s > 1 + slack || t < -slack || t > 1 + slack) return null
        return doubleArrayOf(s.coerceIn(0.0, 1.0), t.coerceIn(0.0, 1.0))
    }

    /** Whether the segments of a over ta0..ta1 and b over tb0..tb1 trace the same curve (either direction). */
    private fun sameCurve(a: XBez, ta0: Double, ta1: Double, b: XBez, tb0: Double, tb1: Double, eps: Double): Boolean {
        for (k in 1..5) {
            val f = k / 6.0
            val tx = a.x(ta0 + (ta1 - ta0) * f); val ty = a.y(ta0 + (ta1 - ta0) * f)
            val u = nearestT(b, tx, ty)
            if (u < min(tb0, tb1) - 1e-6 || u > max(tb0, tb1) + 1e-6) return false
            if (hypot(b.x(u) - tx, b.y(u) - ty) > eps) return false
        }
        return true
    }

    /** every crossing of a and b, as (ta, tb, x, y), ends included. */
    fun intersect(a: XBez, b: XBez, eps: Double, overlapOut: BooleanArray? = null): List<DoubleArray> {
        val res = ArrayList<DoubleArray>()
        // endpoint projections: coincident edges and T-junctions
        val endHits = ArrayList<DoubleArray>()
        for ((tA, px, py) in listOf(Triple(0.0, a.x0, a.y0), Triple(1.0, a.x3, a.y3))) {
            val u = nearestT(b, px, py)
            if (hypot(b.x(u) - px, b.y(u) - py) <= eps) endHits.add(doubleArrayOf(tA, u, px, py))
        }
        for ((tB, px, py) in listOf(Triple(0.0, b.x0, b.y0), Triple(1.0, b.x3, b.y3))) {
            val t = nearestT(a, px, py)
            if (hypot(a.x(t) - px, a.y(t) - py) <= eps) endHits.add(doubleArrayOf(t, tB, px, py))
        }
        val endsU = dedupeParams(endHits)
        if (endsU.size >= 2) {
            // overlap? check between the extreme pair along a
            val sorted = endsU.sortedBy { it[0] }
            val f = sorted.first(); val l = sorted.last()
            if (l[0] - f[0] > 1e-9 && sameCurve(a, f[0], l[0], b, f[1], l[1], eps * 4)) {
                overlapOut?.set(0, true)
                return endsU
            }
        }
        res.addAll(endsU)
        val raw = ArrayList<DoubleArray>()
        val flat = max(eps * 10, 1e-9)
        rec(a, 0.0, 1.0, b, 0.0, 1.0, flat, 0, raw, Budget(4000))
        for (r in raw) {
            val n = newton(a, b, r[0], r[1])
            if (n[2] <= eps * 10) res.add(doubleArrayOf(n[0], n[1], 0.5 * (a.x(n[0]) + b.x(n[1])), 0.5 * (a.y(n[0]) + b.y(n[1]))))
        }
        return dedupeParams(res)
    }

    private fun dedupeParams(h: List<DoubleArray>): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        for (x in h) if (out.none { abs(it[0] - x[0]) < 1e-7 && abs(it[1] - x[1]) < 1e-7 }) out.add(x)
        return out
    }

    // ---------------- the planar arrangement ----------------

    class Edge(val bez: XBez, var v0: Int, var v1: Int, val src: Int) { var used = false }

    class Failure(msg: String) : Exception(msg)

    /**
     * Cut every segment of the given operands against every other, keep the pieces across which
     * [inside] changes, and chain them into closed contours (result on the left of travel).
     * [inside] receives the winding number of each operand at a sample point.
     */
    fun resolve(operands: List<List<List<XBez>>>, inside: (IntArray) -> Boolean): List<List<XBez>> {
        val segs = ArrayList<XBez>(); val srcOf = ArrayList<Int>()
        for ((k, op) in operands.withIndex()) for (c in op) for (b in c) { segs.add(b); srcOf.add(k) }
        if (segs.isEmpty()) return emptyList()
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (s in segs) { minX = min(minX, s.minX()); maxX = max(maxX, s.maxX()); minY = min(minY, s.minY()); maxY = max(maxY, s.maxY()) }
        val scale = max(maxX - minX, maxY - minY).coerceAtLeast(1e-9)
        val eps = scale * 2e-7

        // parameters at which each segment must be cut, with the point to snap to
        val cuts = Array(segs.size) { ArrayList<DoubleArray>() } // (t, x, y)
        for (i in segs.indices) for (j in i + 1 until segs.size) {
            val a = segs[i]; val b = segs[j]
            if (a.maxX() + eps < b.minX() || b.maxX() + eps < a.minX() || a.maxY() + eps < b.minY() || b.maxY() + eps < a.minY()) continue
            for (h in intersect(a, b, eps)) {
                val endA = h[0] < 1e-7 || h[0] > 1 - 1e-7
                val endB = h[1] < 1e-7 || h[1] > 1 - 1e-7
                if (endA && endB) continue
                val x = if (endA) a.x(h[0].let { if (it < 0.5) 0.0 else 1.0 }) else if (endB) b.x(if (h[1] < 0.5) 0.0 else 1.0) else h[2]
                val y = if (endA) a.y(h[0].let { if (it < 0.5) 0.0 else 1.0 }) else if (endB) b.y(if (h[1] < 0.5) 0.0 else 1.0) else h[3]
                if (!endA) cuts[i].add(doubleArrayOf(h[0], x, y))
                if (!endB) cuts[j].add(doubleArrayOf(h[1], x, y))
            }
        }

        // vertices, merged by tolerance
        val vx = ArrayList<Double>(); val vy = ArrayList<Double>()
        val grid = HashMap<Long, MutableList<Int>>()
        val cell = eps * 50
        fun key(ix: Long, iy: Long) = ix * 73856093L xor iy * 19349663L
        fun vertex(x: Double, y: Double): Int {
            val ix = Math.floorDiv((x / cell).toLong(), 1L); val iy = (y / cell).toLong()
            for (dx in -1L..1L) for (dy in -1L..1L) grid[key(ix + dx, iy + dy)]?.let { l ->
                for (v in l) if (hypot(vx[v] - x, vy[v] - y) <= eps * 20) return v
            }
            vx.add(x); vy.add(y); val id = vx.size - 1
            grid.getOrPut(key(ix, iy)) { ArrayList() }.add(id)
            return id
        }

        val edges = ArrayList<Edge>()
        for (i in segs.indices) {
            val s = segs[i]
            val cs = cuts[i].sortedBy { it[0] }
            val ts = ArrayList<DoubleArray>()
            ts.add(doubleArrayOf(0.0, s.x0, s.y0))
            for (c in cs) if (c[0] - ts.last()[0] > 1e-9 && c[0] < 1 - 1e-9) ts.add(c)
            ts.add(doubleArrayOf(1.0, s.x3, s.y3))
            for (k in 0 until ts.size - 1) {
                val piece = s.sub(ts[k][0], ts[k + 1][0])
                // snap the piece's ends to the canonical points, carrying the handles along
                val sx = ts[k][1] - piece.p[0]; val sy = ts[k][2] - piece.p[1]
                piece.p[0] += sx; piece.p[1] += sy; piece.p[2] += sx; piece.p[3] += sy
                val ex = ts[k + 1][1] - piece.p[6]; val ey = ts[k + 1][2] - piece.p[7]
                piece.p[6] += ex; piece.p[7] += ey; piece.p[4] += ex; piece.p[5] += ey
                piece.origin = s; piece.ot0 = ts[k][0]; piece.ot1 = ts[k + 1][0]
                val v0 = vertex(piece.p[0], piece.p[1]); val v1 = vertex(piece.p[6], piece.p[7])
                if (v0 == v1 && piece.flatness() < eps * 20) continue
                edges.add(Edge(piece, v0, v1, srcOf[i]))
            }
        }
        for (e in edges) { // exact shared coordinates
            e.bez.p[0] = vx[e.v0]; e.bez.p[1] = vy[e.v0]; e.bez.p[6] = vx[e.v1]; e.bez.p[7] = vy[e.v1]
        }

        // classify
        val kept = ArrayList<Edge>()
        val probe = scale * 1e-6
        val w = IntArray(operands.size)
        for (e in edges) {
            val t = 0.5
            val mx = e.bez.x(t); val my = e.bez.y(t)
            val tg = e.bez.tangent(t)
            val nx = -tg[1]; val ny = tg[0]
            for (k in operands.indices) w[k] = winding(operands[k], mx + nx * probe, my + ny * probe)
            val left = inside(w)
            for (k in operands.indices) w[k] = winding(operands[k], mx - nx * probe, my - ny * probe)
            val right = inside(w)
            if (left == right) continue
            val ke = if (left) e else Edge(e.bez.reversed(), e.v1, e.v0, e.src)
            // a coincident twin (same ends, same middle) is already there
            val dup = kept.any { o ->
                o.v0 == ke.v0 && o.v1 == ke.v1 && hypot(o.bez.x(0.5) - mx, o.bez.y(0.5) - my) <= eps * 50
            }
            if (!dup) kept.add(ke)
        }

        // chain
        val out = HashMap<Int, MutableList<Edge>>()
        for (e in kept) out.getOrPut(e.v0) { ArrayList() }.add(e)
        val loops = ArrayList<List<XBez>>()
        for (start in kept) {
            if (start.used) continue
            val loop = ArrayList<XBez>()
            var cur = start
            var guard = 0
            while (true) {
                cur.used = true; loop.add(cur.bez)
                if (cur.v1 == start.v0) break
                val cands = out[cur.v1]?.filter { !it.used } ?: emptyList()
                if (cands.isEmpty()) throw Failure("open chain at vertex (${vx[cur.v1]}, ${vy[cur.v1]})")
                cur = if (cands.size == 1) cands[0] else {
                    val tin = cur.bez.tangent(1.0)
                    val ain = atan2(tin[1], tin[0])
                    cands.maxBy { c ->
                        val to = c.bez.tangent(0.0)
                        var d = atan2(to[1], to[0]) - ain
                        while (d <= -Math.PI) d += 2 * Math.PI
                        while (d > Math.PI) d -= 2 * Math.PI
                        d // the sharpest left turn
                    }
                }
                if (++guard > kept.size + 1) throw Failure("chain did not close")
            }
            loops.add(rejoin(loop))
        }
        return loops
    }

    /** consecutive pieces cut from one operand segment, contiguous and same way round, become one piece again. */
    private fun rejoin(loop: List<XBez>): List<XBez> {
        fun joins(a: XBez, b: XBez) = a.origin != null && a.origin === b.origin && a.ot1 == b.ot0 &&
            (a.ot1 - a.ot0) * (b.ot1 - b.ot0) > 0
        fun merge(a: XBez, b: XBez): XBez {
            val o = a.origin!!
            val lo = min(a.ot0, b.ot1); val hi = max(a.ot0, b.ot1)
            val m = if (a.ot0 < b.ot1) o.sub(lo, hi) else o.sub(lo, hi).reversed()
            val sx = a.p[0] - m.p[0]; val sy = a.p[1] - m.p[1]
            m.p[0] += sx; m.p[1] += sy; m.p[2] += sx; m.p[3] += sy
            val ex = b.p[6] - m.p[6]; val ey = b.p[7] - m.p[7]
            m.p[6] += ex; m.p[7] += ey; m.p[4] += ex; m.p[5] += ey
            m.origin = o; m.ot0 = a.ot0; m.ot1 = b.ot1
            return m
        }
        val l = ArrayList(loop)
        var i = 0
        while (l.size > 1 && i < l.size) {
            val j = (i + 1) % l.size
            if (joins(l[i], l[j]) && !(j == 0 && l.size == 2 && joins(l[j], l[i]))) {
                val m = merge(l[i], l[j])
                if (j == 0) { l[i] = m; l.removeAt(0); i = max(0, i - 2) } else { l[i] = m; l.removeAt(j) }
            } else i++
        }
        return l
    }

    // ---------------- areas, FLine conversion ----------------

    private val GX = doubleArrayOf(0.5 - sqrt(0.15), 0.5, 0.5 + sqrt(0.15))
    private val GW = doubleArrayOf(5.0 / 18, 8.0 / 18, 5.0 / 18)

    fun area(contours: List<List<XBez>>): Double {
        var a = 0.0
        for (c in contours) for (b in c) for (k in 0..2) a += 0.5 * GW[k] * (b.x(GX[k]) * b.dy(GX[k]) - b.y(GX[k]) * b.dx(GX[k]))
        return a
    }

    fun toFLine(contours: List<List<XBez>>): FLine {
        val f = FLine()
        for (c in contours) {
            if (c.isEmpty()) continue
            f.moveTo(c[0].x0, c[0].y0)
            for ((i, b) in c.withIndex()) {
                val last = i == c.size - 1
                if (last && b.straight) break // the Close draws it
                if (b.straight) f.lineTo(b.x3, b.y3)
                else f.cubicTo(b.p[2], b.p[3], b.p[4], b.p[5], b.p[6], b.p[7])
            }
            f.close()
        }
        return f
    }

    /** absolute area of an AWT shape after fine flattening (the AWT result's own area). */
    fun awtArea(f: FLine, flatness: Double): Double {
        val it = FlatteningPathIterator(FLinesAndJavaShapes.flineToJavaShape_notThickened(f).let { Area(it) }.getPathIterator(null), flatness, 16)
        return pathIteratorArea(it)
    }

    fun pathIteratorArea(it: PathIterator): Double {
        val c = DoubleArray(6); var a = 0.0
        var sx = 0.0; var sy = 0.0; var lx = 0.0; var ly = 0.0
        while (!it.isDone) {
            when (it.currentSegment(c)) {
                PathIterator.SEG_MOVETO -> { a += lx * sy - sx * ly; sx = c[0]; sy = c[1]; lx = sx; ly = sy }
                PathIterator.SEG_LINETO -> { a += lx * c[1] - c[0] * ly; lx = c[0]; ly = c[1] }
                PathIterator.SEG_CLOSE -> { a += lx * sy - sx * ly; lx = sx; ly = sy }
            }
            it.next()
        }
        a += lx * sy - sx * ly
        return abs(0.5 * a)
    }

    fun opPredicate(op: FLineBooleanOp): (IntArray) -> Boolean = when (op) {
        FLineBooleanOp.Union -> { w -> w[0] != 0 || w[1] != 0 }
        FLineBooleanOp.Intersect -> { w -> w[0] != 0 && w[1] != 0 }
        FLineBooleanOp.Subtract -> { w -> w[0] != 0 && w[1] == 0 }
        FLineBooleanOp.Xor -> { w -> (w[0] != 0) != (w[1] != 0) }
    }

    fun awt(a: FLine, b: FLine, op: FLineBooleanOp): FLine {
        val a1 = Area(FLinesAndJavaShapes.flineToJavaShape(a)); val a2 = Area(FLinesAndJavaShapes.flineToJavaShape(b))
        when (op) {
            FLineBooleanOp.Union -> a1.add(a2)
            FLineBooleanOp.Intersect -> a1.intersect(a2)
            FLineBooleanOp.Subtract -> a1.subtract(a2)
            FLineBooleanOp.Xor -> a1.exclusiveOr(a2)
        }
        return FLinesAndJavaShapes.javaShapeToFLine(a1)
    }

    fun boolean(a: FLine, b: FLine, op: FLineBooleanOp): FLineBooleanResult {
        val fallback = { why: String -> FLineBooleanResult(awt(a, b, op), false, why) }
        if (a.thicken != null || a.thickness != null || b.thicken != null || b.thickness != null)
            return fallback("thickened operand (stroke outline) goes through AWT")
        return try {
            val ca = a.exactContours(); val cb = b.exactContours()
            val loops = resolve(listOf(ca, cb), opPredicate(op))
            val exactArea = area(loops)
            val all = ca.flatten() + cb.flatten()
            val ext = if (all.isEmpty()) 1.0 else max(all.maxOf { it.maxX() } - all.minOf { it.minX() }, all.maxOf { it.maxY() } - all.minOf { it.minY() })
            val awtLine = awt(a, b, op)
            val ref = pathIteratorArea(FlatteningPathIterator(FLinesAndJavaShapes.flineToJavaShape(awtLine).getPathIterator(null), ext * 1e-5, 16))
            val tol = 2e-3 * max(ref, ext * ext * 1e-4)
            if (exactArea < -tol) return fallback("result oriented inside out (area $exactArea)")
            if (abs(exactArea - ref) > tol) return fallback("area $exactArea disagrees with AWT's $ref")
            FLineBooleanResult(toFLine(loops), true, null)
        } catch (e: Failure) {
            fallback(e.message ?: "chain failure")
        }
    }

    // ---------------- offsets ----------------

    /** left-of-travel on Field's y-down canvas, as [FLineLocation.normal2D]: (ty, -tx). */
    private fun nrm(t: DoubleArray) = doubleArrayOf(t[1], -t[0])

    fun offsetSegment(s: XBez, d: Double, tol: Double, depth: Int, out: MutableList<XBez>) {
        if (s.straight) {
            val n = nrm(s.tangent(0.5))
            out.add(XBez.line(s.x0 + n[0] * d, s.y0 + n[1] * d, s.x3 + n[0] * d, s.y3 + n[1] * d)); return
        }
        val c = fitOffset(s, d)
        if (depth >= 10 || offsetError(s, c, d) <= tol) { out.add(c); return }
        val (l, r) = XBez.splitAt(s.p, 0.5)
        offsetSegment(XBez(l, false), d, tol, depth + 1, out)
        offsetSegment(XBez(r, false), d, tol, depth + 1, out)
    }

    private fun fitOffset(s: XBez, d: Double): XBez {
        val t0 = s.tangent(0.0); val t3 = s.tangent(1.0)
        val n0 = nrm(t0); val n3 = nrm(t3)
        val q0x = s.x0 + n0[0] * d; val q0y = s.y0 + n0[1] * d
        val q3x = s.x3 + n3[0] * d; val q3y = s.y3 + n3[1] * d
        val h0x = s.p[2] - s.p[0]; val h0y = s.p[3] - s.p[1]
        val h3x = s.p[4] - s.p[6]; val h3y = s.p[5] - s.p[7]
        // Tiller–Hanson / curvature scaling: handle length scales by (1 + d k) on the left-normal convention
        fun kappa(t: Double): Double {
            val dx = s.dx(t); val dy = s.dy(t)
            val h = 1e-5
            val ddx = (s.dx(min(1.0, t + h)) - s.dx(max(0.0, t - h))) / (min(1.0, t + h) - max(0.0, t - h))
            val ddy = (s.dy(min(1.0, t + h)) - s.dy(max(0.0, t - h))) / (min(1.0, t + h) - max(0.0, t - h))
            val sp = hypot(dx, dy); if (sp < 1e-12) return 0.0
            return (dx * ddy - dy * ddx) / (sp * sp * sp)
        }
        var a = 1 + d * kappa(0.0); var b = 1 + d * kappa(1.0)
        // fit both handle lengths to the true offset at t = 1/2
        val n5 = nrm(s.tangent(0.5))
        val mx = s.x(0.5) + n5[0] * d; val my = s.y(0.5) + n5[1] * d
        val rx = (mx - 0.5 * (q0x + q3x)) * 8.0 / 3.0; val ry = (my - 0.5 * (q0y + q3y)) * 8.0 / 3.0
        val det = h0x * h3y - h0y * h3x
        val hl = hypot(h0x, h0y) * hypot(h3x, h3y)
        if (abs(det) > 1e-3 * hl && hl > 0) {
            val fa = (rx * h3y - ry * h3x) / det; val fb = (h0x * ry - h0y * rx) / det
            if (fa > 0 && fb > 0 && fa < 10 * max(1.0, abs(a)) && fb < 10 * max(1.0, abs(b))) { a = fa; b = fb }
        }
        return XBez(doubleArrayOf(q0x, q0y, q0x + a * h0x, q0y + a * h0y, q3x + b * h3x, q3y + b * h3y, q3x, q3y), false)
    }

    /** max over samples of | distance(approximant, original) - |d| |. */
    fun offsetError(s: XBez, c: XBez, d: Double): Double {
        var e = 0.0
        for (k in 1..15) {
            val t = k / 16.0
            val qx = c.x(t); val qy = c.y(t)
            val u = nearestT(s, qx, qy)
            e = max(e, abs(hypot(s.x(u) - qx, s.y(u) - qy) - abs(d)))
        }
        return e
    }

    private fun arc(cx: Double, cy: Double, r: Double, a0: Double, sweep: Double, out: MutableList<XBez>) {
        val n = max(1, ceil(abs(sweep) / (Math.PI / 2) - 1e-9).toInt())
        val step = sweep / n
        val k = 4.0 / 3.0 * tan(step / 4)
        for (i in 0 until n) {
            val s0 = a0 + step * i; val s1 = s0 + step
            val x0 = cx + r * kotlin.math.cos(s0); val y0 = cy + r * kotlin.math.sin(s0)
            val x3 = cx + r * kotlin.math.cos(s1); val y3 = cy + r * kotlin.math.sin(s1)
            out.add(XBez(doubleArrayOf(x0, y0, x0 - k * r * kotlin.math.sin(s0), y0 + k * r * kotlin.math.cos(s0),
                x3 + k * r * kotlin.math.sin(s1), y3 - k * r * kotlin.math.cos(s1), x3, y3), false))
        }
    }

    /** the join from the offset end of [a] to the offset start of [b] around the shared vertex. */
    fun join(vx: Double, vy: Double, tin: DoubleArray, tout: DoubleArray, d: Double, kind: FLineStrokeJoin, miterLimit: Double, out: MutableList<XBez>) {
        val ni = nrm(tin); val no = nrm(tout)
        val ax = vx + ni[0] * d; val ay = vy + ni[1] * d
        val bx = vx + no[0] * d; val by = vy + no[1] * d
        if (hypot(ax - bx, ay - by) < 1e-12 * (1 + abs(d))) return
        val cross = tin[0] * tout[1] - tin[1] * tout[0]
        // the normal (ty, -tx) is to the right in y-up maths; a corner turning left (cross > 0) opens
        // on the right, so positive d is the outer side there
        val outer = cross * d > 0
        if (!outer) { out.add(XBez.line(ax, ay, vx, vy)); out.add(XBez.line(vx, vy, bx, by)); return }
        when (kind) {
            FLineStrokeJoin.BEVEL -> out.add(XBez.line(ax, ay, bx, by))
            FLineStrokeJoin.ROUND -> {
                val a0 = atan2(ay - vy, ax - vx); var sw = atan2(by - vy, bx - vx) - a0
                while (sw <= -Math.PI) sw += 2 * Math.PI
                while (sw > Math.PI) sw -= 2 * Math.PI
                arc(vx, vy, abs(d), a0, sw, out)
            }
            FLineStrokeJoin.MITER -> {
                val cosT = (tin[0] * tout[0] + tin[1] * tout[1]).coerceIn(-1.0, 1.0)
                val half = acos(cosT) / 2
                val ratio = 1 / kotlin.math.cos(half)
                if (ratio > miterLimit) { out.add(XBez.line(ax, ay, bx, by)); return }
                // intersect the two offset tangent lines
                val den = tin[0] * tout[1] - tin[1] * tout[0]
                val s = ((bx - ax) * tout[1] - (by - ay) * tout[0]) / den
                val mx = ax + tin[0] * s; val my = ay + tin[1] * s
                out.add(XBez.line(ax, ay, mx, my)); out.add(XBez.line(mx, my, bx, by))
            }
        }
    }

    /**
     * At an inner corner of an open run, cut the end of [before] and the start of [after] where they
     * cross (searching the last and first few pieces); false when they do not cross.
     */
    private fun trimInner(before: MutableList<XBez>, after: MutableList<XBez>): Boolean {
        if (before.isEmpty() || after.isEmpty()) return false
        val eps = 1e-9 * (1 + before.last().scale())
        for (bi in before.size - 1 downTo max(0, before.size - 6)) for (ai in 0 until min(after.size, 6)) {
            val a = before[bi]; val b = after[ai]
            val hits = intersect(a, b, eps).filter { !(it[0] > 1 - 1e-9 && it[1] < 1e-9) }
            if (hits.isEmpty()) continue
            val h = hits.minBy { (1 - it[0]) + it[1] }
            before[bi] = a.sub(0.0, h[0]).also { it.p[6] = h[2]; it.p[7] = h[3] }
            while (before.size > bi + 1) before.removeAt(before.size - 1)
            after[ai] = b.sub(h[1], 1.0).also { it.p[0] = h[2]; it.p[1] = h[3] }
            repeat(ai) { after.removeAt(0) }
            return true
        }
        return false
    }

    /**
     * Remove the inverted loops of an open offset run: wherever the run crosses itself and the part
     * between the crossing lies nearer the original than |d|, that part is cut out.
     */
    fun removeInvertedLoops(run: List<XBez>, original: List<XBez>, d: Double, tol: Double): List<XBez> {
        var cur = run
        var guard = 0
        outer@ while (guard++ < 64) {
            val eps = 1e-9 * (1 + cur.maxOfOrNull { it.scale() }!!)
            for (i in cur.indices) for (j in i + 1 until cur.size) {
                val a = cur[i]; val b = cur[j]
                if (a.maxX() < b.minX() || b.maxX() < a.minX() || a.maxY() < b.minY() || b.maxY() < a.minY()) continue
                for (h in intersect(a, b, eps)) {
                    if (j == i + 1 && h[0] > 1 - 1e-7 && h[1] < 1e-7) continue
                    // the middle of what lies between the crossing
                    val mid = if (j == i + 1) doubleArrayOf(a.x((h[0] + 1) / 2), a.y((h[0] + 1) / 2))
                    else cur[(i + j) / 2].let { doubleArrayOf(it.x(0.5), it.y(0.5)) }
                    var best = Double.MAX_VALUE
                    for (o in original) { val u = nearestT(o, mid[0], mid[1]); best = min(best, hypot(o.x(u) - mid[0], o.y(u) - mid[1])) }
                    if (best >= abs(d) - tol) continue
                    val na = ArrayList<XBez>()
                    for (k in 0 until i) na.add(cur[k])
                    na.add(a.sub(0.0, h[0]).also { it.p[6] = h[2]; it.p[7] = h[3] })
                    na.add(b.sub(h[1], 1.0).also { it.p[0] = h[2]; it.p[1] = h[3] })
                    for (k in j + 1 until cur.size) na.add(cur[k])
                    cur = na.filter { max(it.maxX() - it.minX(), it.maxY() - it.minY()) > eps }
                    continue@outer
                }
            }
            break
        }
        return cur
    }

    fun offsetContour(c: List<XBez>, d: Double, tol: Double, kind: FLineStrokeJoin, miterLimit: Double, closed: Boolean): List<XBez> {
        val pieces = c.map { s -> ArrayList<XBez>().also { offsetSegment(s, d, tol, 0, it) } }
        val out = ArrayList<XBez>()
        fun corner(p: XBez, s: XBez, before: MutableList<XBez>, after: MutableList<XBez>, into: MutableList<XBez>) {
            val ti = p.tangent(1.0); val to = s.tangent(0.0)
            if (abs(ti[0] * to[1] - ti[1] * to[0]) <= 1e-6 && ti[0] * to[0] + ti[1] * to[1] >= 0) return
            val inner = (ti[0] * to[1] - ti[1] * to[0]) * d < 0
            if (inner && !closed && trimInner(before, after)) return
            join(s.x0, s.y0, ti, to, d, kind, miterLimit, into)
        }
        for (i in c.indices) {
            if (i > 0) corner(c[i - 1], c[i], out, pieces[i], out)
            out.addAll(pieces[i])
        }
        if (closed && c.isNotEmpty()) corner(c.last(), c.first(), out, out, out)
        // make the chain exactly continuous (smooth joins leave float-level gaps)
        for (i in 1 until out.size) {
            val a = out[i - 1]; val b = out[i]
            val gx = a.p[6] - b.p[0]; val gy = a.p[7] - b.p[1]
            b.p[0] += gx; b.p[1] += gy; b.p[2] += gx; b.p[3] += gy
        }
        if (closed && out.size > 1) {
            val a = out.last(); val b = out.first()
            val gx = b.p[0] - a.p[6]; val gy = b.p[1] - a.p[7]
            a.p[6] += gx; a.p[7] += gy; a.p[4] += gx; a.p[5] += gy
        }
        return out
    }
}

/**
 * Boolean [op] of this filled region with [other], keeping the curves: the result's cubics are
 * pieces of the operands' cubics, cut exactly where the outlines cross. Falls back to the
 * java.awt.geom.Area answer when the exact engine cannot produce a result it trusts (see
 * [FLineBooleanResult.fallbackReason]). Both lines are read as filled, non-zero, in the xy plane.
 */
fun FLine.booleanExact(other: FLine, op: FLineBooleanOp): FLineBooleanResult = FLineExact.boolean(this, other, op)

/** the open xy subpaths of a line as cubic chains (not closed). */
internal fun FLine.exactOpenChains(): List<Pair<List<XBez>, Boolean>> {
    val out = ArrayList<Pair<List<XBez>, Boolean>>()
    var cur = ArrayList<XBez>()
    fun flush(closed: Boolean) { if (cur.isNotEmpty()) out.add(cur to closed); cur = ArrayList() }
    for (i in nodes.indices) {
        if (nodes[i] is FLine.MoveTo) { flush(false); continue }
        val s = drawnSegmentEndingAt(i) ?: continue
        val b = XBez(doubleArrayOf(s.p0.x.toDouble(), s.p0.y.toDouble(), s.p1.x.toDouble(), s.p1.y.toDouble(),
            s.p2.x.toDouble(), s.p2.y.toDouble(), s.p3.x.toDouble(), s.p3.y.toDouble()), s.straight)
        if (max(b.maxX() - b.minX(), b.maxY() - b.minY()) > 1e-12) cur.add(b)
        if (nodes[i] is FLine.Close) flush(true)
    }
    flush(false)
    return out
}

/**
 * Offset this line by [distance] with cubic output: each cubic becomes a few cubics within
 * [tolerance] of the true offset (rather than [byOffsetting]'s sampled polyline), straight segments
 * stay straight, and corners get [join] (a miter longer than [miterLimit] × distance becomes a bevel).
 * Positive distance is towards [FLineLocation.normal2D] (left of travel on Field's y-down canvas),
 * as in [byOffsetting]. Closed subpaths are resolved into clean outlines (concave-corner
 * swallowtails and collapsed parts removed); open ones are returned as single offset runs.
 */
fun FLine.byOffsettingExact(
    distance: Float,
    tolerance: Float = 0.01f,
    join: FLineStrokeJoin = FLineStrokeJoin.ROUND,
    miterLimit: Float = 4f,
): FLine {
    val d = distance.toDouble(); val tol = tolerance.toDouble().coerceAtLeast(1e-6)
    val chains = exactOpenChains()
    val closedOffsets = ArrayList<List<XBez>>()
    val result = FLine()
    for ((c, closed) in chains) {
        val o = FLineExact.offsetContour(c, d, tol, join, miterLimit.toDouble(), closed)
        if (o.isEmpty()) continue
        if (closed) closedOffsets.add(o)
        else {
            val run = FLineExact.removeInvertedLoops(o, c, d, tol)
            if (run.isEmpty()) continue
            result.moveTo(run[0].x0, run[0].y0)
            for (b in run) if (b.straight) result.lineTo(b.x3, b.y3) else result.cubicTo(b.p[2], b.p[3], b.p[4], b.p[5], b.p[6], b.p[7])
        }
    }
    if (closedOffsets.isNotEmpty()) {
        val originals = chains.filter { it.second }.map { it.first }
        val sgn = sign(FLineExact.area(originals)).let { if (it == 0.0) 1.0 else it }
        val loops = try {
            FLineExact.resolve(listOf(closedOffsets)) { w -> w[0] * sgn > 0 }
        } catch (e: FLineExact.Failure) {
            closedOffsets // unresolved, still cubic, still the right outline away from the corners
        }
        val f = FLineExact.toFLine(loops)
        for (n in f.nodes) result.nodes.add(n)
    }
    return result
}

/** number of drawn, non-degenerate segments (what the exactness tests count). */
fun FLine.segmentCount2D(): Int = exactOpenChains().sumOf { it.first.size }
