package afield.graphics

import afield.Rect
import afield.stdlib.Vec2
import afield.stdlib.Vec3
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Measuring, locating, trimming and crossing an [FLine] — the operations Paper.js puts on
 * `Path` / `CurveLocation` and Skia on `SkPathMeasure`, in one place and under one vocabulary.
 *
 * Two parameterisations run through all of it:
 *  - **distance** (`d`), in drawing units along the line, from 0 to [length]. This is the one to use
 *    when you want things evenly spaced: [locationAt], [trim], [splitAt].
 *  - **`t`**, the cursor's node parameter: `t = i + u` means fraction `u` of the way (by curve
 *    parameter, not by distance) along the segment that ends at node `i + 1`. It is what
 *    [FLine.cursor]`.setT` takes, and what [FLine.closestPoint] returns.
 *
 * Every FLineLocation carries both, so you can go from one to the other.
 */
private const val EPS = 1e-9

/**
 * A point on an [FLine], with the local frame a live-coder usually wants next.
 *
 * @property point where on the line
 * @property tangent unit direction of travel (zero vector only on a fully degenerate line)
 * @property normal [tangent] turned a quarter turn in the xy plane: `(-tangent.y, tangent.x, 0)`.
 *   With y pointing down (Field's canvas) that is to the right of the direction of travel; with y
 *   up it is to the left.
 * @property curvature signed curvature in the xy plane, `1 / radius`. Positive when the line turns
 *   towards [normal]; 0 on straight segments.
 * @property distance distance along the line from its start, in drawing units
 * @property t the cursor node parameter of this point (see the file overview)
 * @property segment index of the node that ends the segment this point is on
 */
data class FLineLocation(
    val point: Vec3,
    val tangent: Vec3,
    val normal: Vec3,
    val curvature: Float,
    val distance: Float,
    val t: Float,
    val segment: Int,
)

/**
 * One place where two lines (or one line with itself) cross.
 *
 * @property point the crossing point
 * @property t the cursor node parameter on the line the method was called on
 * @property otherT the cursor node parameter on the other line (on the same line, for [selfCrossings])
 */
data class FLineCrossing(val point: Vec3, val t: Float, val otherT: Float)

/** How [contains] decides "inside" when contours overlap or nest — the SVG / canvas `fill-rule`. */
enum class FLineFillRule {
    /** inside when the winding number is not zero: overlapping same-direction contours stay filled. */
    NonZero,

    /** inside when the contour is crossed an odd number of times: every nested contour toggles. */
    EvenOdd,
}

/** one drawn segment of a line as a cubic (lines are degree-elevated), ending at node [index]. */
internal class FLineSeg(val index: Int, val p0: Vec3, val p1: Vec3, val p2: Vec3, val p3: Vec3, val straight: Boolean) {
    fun at(u: Double): Vec3 {
        val v = 1 - u
        val a = v * v * v; val b = 3 * v * v * u; val c = 3 * v * u * u; val d = u * u * u
        return Vec3(
            (a * p0.x + b * p1.x + c * p2.x + d * p3.x).toFloat(),
            (a * p0.y + b * p1.y + c * p2.y + d * p3.y).toFloat(),
            (a * p0.z + b * p1.z + c * p2.z + d * p3.z).toFloat(),
        )
    }

    /** first derivative, componentwise, as doubles */
    fun d1(u: Double): DoubleArray {
        val v = 1 - u
        fun c(a: Float, b: Float, cc: Float, d: Float) = 3 * v * v * (b - a) + 6 * v * u * (cc - b) + 3 * u * u * (d - cc)
        return doubleArrayOf(c(p0.x, p1.x, p2.x, p3.x), c(p0.y, p1.y, p2.y, p3.y), c(p0.z, p1.z, p2.z, p3.z))
    }

    fun d2(u: Double): DoubleArray {
        val v = 1 - u
        fun c(a: Float, b: Float, cc: Float, d: Float) = 6 * v * (cc - 2 * b + a) + 6 * u * (d - 2 * cc + b)
        return doubleArrayOf(c(p0.x, p1.x, p2.x, p3.x), c(p0.y, p1.y, p2.y, p3.y), c(p0.z, p1.z, p2.z, p3.z))
    }

    /** the sub-curve on `[u0, u1]`, by de Casteljau. */
    fun sub(u0: Double, u1: Double): Array<Vec3> {
        fun lerp(a: Vec3, b: Vec3, s: Double) = Vec3(
            (a.x + (b.x - a.x) * s).toFloat(), (a.y + (b.y - a.y) * s).toFloat(), (a.z + (b.z - a.z) * s).toFloat()
        )
        fun splitRight(q: Array<Vec3>, s: Double): Array<Vec3> {
            val a = lerp(q[0], q[1], s); val b = lerp(q[1], q[2], s); val c = lerp(q[2], q[3], s)
            val ab = lerp(a, b, s); val bc = lerp(b, c, s)
            return arrayOf(lerp(ab, bc, s), bc, c, q[3])
        }
        fun splitLeft(q: Array<Vec3>, s: Double): Array<Vec3> {
            val a = lerp(q[0], q[1], s); val b = lerp(q[1], q[2], s); val c = lerp(q[2], q[3], s)
            val ab = lerp(a, b, s); val bc = lerp(b, c, s)
            return arrayOf(q[0], a, ab, lerp(ab, bc, s))
        }
        var q = arrayOf(p0, p1, p2, p3)
        if (u0 > 0) q = splitRight(q, u0)
        if (u1 < 1) q = splitLeft(q, if (u0 >= 1) 0.0 else (u1 - u0) / (1 - u0))
        return q
    }
}

/**
 * Every drawn segment of this line, `Close` included (as the straight run back to its subpath
 * start); `MoveTo` jumps are not segments. A leading non-`MoveTo` node places the pen, exactly as the
 * renderer reads it.
 */
internal fun FLine.drawnSegments(): List<FLineSeg> {
    val out = ArrayList<FLineSeg>()
    for (i in 1 until nodes.size) {
        val n = nodes[i]
        val a = Vec3(endPointOf(i - 1))
        when (n) {
            is FLine.MoveTo -> {}
            is FLine.CubicTo -> out.add(FLineSeg(i, a, Vec3(n.c1), Vec3(n.c2), Vec3(n.to), false))
            // a Close is the straight run back to its subpath start, which endPointOf resolves
            is FLine.LineTo, is FLine.Close -> {
                val b = Vec3(endPointOf(i))
                val c1 = Vec3(a.x + (b.x - a.x) / 3, a.y + (b.y - a.y) / 3, a.z + (b.z - a.z) / 3)
                val c2 = Vec3(a.x + 2 * (b.x - a.x) / 3, a.y + 2 * (b.y - a.y) / 3, a.z + 2 * (b.z - a.z) / 3)
                out.add(FLineSeg(i, a, c1, c2, b, true))
            }
            else -> {}
        }
    }
    return out
}

/**
 * The total drawn length of this line, in drawing units — `MoveTo` jumps do not count, `Close`
 * segments do. Paper.js `path.length`, Skia `SkPathMeasure.getLength` summed over contours.
 */
fun FLine.length(): Float = if (nodes.size < 2) 0f else cursor().lengthD()

/** the location at cursor parameter [t], with [distance] already known (or computed if `null`). */
private fun FLine.locationAtT(t: Float, distance: Float?): FLineLocation? {
    val segs = drawnSegments()
    if (segs.isEmpty()) return null
    val tt = t.coerceIn(0f, (nodes.size - 1).toFloat())
    var nodeEnd = tt.toInt() + 1
    var u = (tt - (nodeEnd - 1)).toDouble()
    if (nodeEnd > nodes.size - 1) { nodeEnd = nodes.size - 1; u = 1.0 }
    // a t that lands in a MoveTo jump snaps to the nearest drawn segment
    val seg = segs.firstOrNull { it.index == nodeEnd } ?: run {
        val after = segs.firstOrNull { it.index > nodeEnd }
        val before = segs.lastOrNull { it.index < nodeEnd }
        if (after != null && (before == null || u >= 0.5)) { u = 0.0; after } else { u = 1.0; before!! }
    }
    val p = seg.at(u)
    var d1 = seg.d1(u)
    // a degenerate end (control point on the end point) has zero derivative: step inside a little
    if (hypot(hypot(d1[0], d1[1]), d1[2]) < EPS) d1 = seg.d1(if (u < 0.5) 1e-4 else 1 - 1e-4)
    if (hypot(hypot(d1[0], d1[1]), d1[2]) < EPS) d1 = doubleArrayOf(
        (seg.p3.x - seg.p0.x).toDouble(), (seg.p3.y - seg.p0.y).toDouble(), (seg.p3.z - seg.p0.z).toDouble()
    )
    val d2 = seg.d2(u)
    val len = hypot(hypot(d1[0], d1[1]), d1[2])
    val tan = if (len < EPS) Vec3() else Vec3((d1[0] / len).toFloat(), (d1[1] / len).toFloat(), (d1[2] / len).toFloat())
    val k = if (seg.straight || len < EPS) 0.0 else (d1[0] * d2[1] - d1[1] * d2[0]) / (len * len * len)
    val segT = (seg.index - 1 + u).toFloat()
    val dist = distance ?: cursor().setT(segT).d
    return FLineLocation(p, tan, Vec3(-tan.y, tan.x, 0f), k.toFloat(), dist, segT, seg.index)
}

/**
 * The location [distance] units along this line (clamped to `0..length()`), with its tangent,
 * normal and curvature. Paper.js `getLocationAt` / `getPointAt` + `getTangentAt` + `getNormalAt` +
 * `getCurvatureAt`; Skia `SkPathMeasure.getPosTan`. `null` for a line with nothing drawn in it.
 *
 * ```
 * val loc = circle.locationAt(25f)
 * marker(loc.point); line(loc.point, loc.point + loc.normal * 20f)
 * ```
 */
fun FLine.locationAt(distance: Float): FLineLocation? {
    if (drawnSegments().isEmpty()) return null
    val c = cursor()
    val total = c.lengthD()
    val d = distance.coerceIn(0f, total)
    c.setD(d)
    return locationAtT(c.t, d)
}

/** [locationAt] by fraction of the [length]: 0 is the start, 1 the end, 0.5 half way by distance. */
fun FLine.locationAtFraction(fraction: Float): FLineLocation? = locationAt(fraction.coerceIn(0f, 1f) * length())

/**
 * The location on this line nearest to [point] — Paper.js `getNearestLocation`. Exact on straight
 * segments; on cubics, a coarse sample followed by Newton refinement.
 */
fun FLine.nearestLocation(point: Vec3): FLineLocation? {
    val segs = drawnSegments()
    if (segs.isEmpty()) return null
    var bestSeg = segs[0]; var bestU = 0.0; var bestD = Double.MAX_VALUE
    fun dist2(s: FLineSeg, u: Double): Double {
        val p = s.at(u); val dx = (p.x - point.x).toDouble(); val dy = (p.y - point.y).toDouble(); val dz = (p.z - point.z).toDouble()
        return dx * dx + dy * dy + dz * dz
    }
    for (s in segs) {
        val n = if (s.straight) 1 else 32
        for (k in 0..n) {
            var u = k.toDouble() / n
            if (s.straight) {
                val ax = (s.p3.x - s.p0.x).toDouble(); val ay = (s.p3.y - s.p0.y).toDouble(); val az = (s.p3.z - s.p0.z).toDouble()
                val l2 = ax * ax + ay * ay + az * az
                u = if (l2 < EPS) 0.0 else (((point.x - s.p0.x) * ax + (point.y - s.p0.y) * ay + (point.z - s.p0.z) * az) / l2).coerceIn(0.0, 1.0)
            } else {
                // Newton on f(u) = (B(u) - p) . B'(u)
                repeat(6) {
                    val p = s.at(u); val d1 = s.d1(u); val d2 = s.d2(u)
                    val rx = (p.x - point.x).toDouble(); val ry = (p.y - point.y).toDouble(); val rz = (p.z - point.z).toDouble()
                    val f = rx * d1[0] + ry * d1[1] + rz * d1[2]
                    val df = d1[0] * d1[0] + d1[1] * d1[1] + d1[2] * d1[2] + rx * d2[0] + ry * d2[1] + rz * d2[2]
                    if (abs(df) > EPS) u = (u - f / df).coerceIn(0.0, 1.0)
                }
            }
            val d = dist2(s, u)
            if (d < bestD) { bestD = d; bestSeg = s; bestU = u }
        }
    }
    return locationAtT((bestSeg.index - 1 + bestU).toFloat(), null)
}

/**
 * The piece of this line between [from] and [to] units along it (both clamped to `0..length()`;
 * swapped if reversed). Cubics are cut exactly, so the result draws on top of the original. Skia
 * `SkPathMeasure.getSegment`, Processing-style "draw the first 30% of a path" animations.
 */
fun FLine.trim(from: Float, to: Float): FLine {
    val total = length()
    var a = min(from, to).coerceIn(0f, total)
    val b = max(from, to).coerceIn(0f, total)
    if (b - a <= 0f) {
        val p = locationAt(a)?.point ?: return FLine()
        return FLine().moveTo(p)
    }
    var piece: FLine = this
    if (a > 0f) piece = cursor().setD(a).split().second ?: return FLine()
    else piece = this.copy()
    val keep = b - a
    if (keep >= piece.length() - 1e-4f) return piece
    return piece.cursor().setD(keep).split().first ?: FLine()
}

/**
 * This line cut in two at [distance] units along it: `first` runs from the start to the cut,
 * `second` from the cut to the end. Both are always non-null (one may be a single `moveTo`), and
 * `first.length() + second.length()` is the original [length]. Paper.js `splitAt`.
 */
fun FLine.splitAt(distance: Float): Pair<FLine, FLine> {
    val total = length()
    return Pair(trim(0f, distance), trim(distance, total))
}

/** the real roots in `(0,1)` of `a u^2 + b u + c`. */
private fun unitQuadraticRoots(a: Double, b: Double, c: Double): List<Double> {
    val out = ArrayList<Double>(2)
    if (abs(a) < 1e-12) {
        if (abs(b) > 1e-12) out.add(-c / b)
    } else {
        val disc = b * b - 4 * a * c
        if (disc >= 0) {
            val s = sqrt(disc)
            out.add((-b + s) / (2 * a)); out.add((-b - s) / (2 * a))
        }
    }
    return out.filter { it > 0 && it < 1 }
}

/**
 * The axis-aligned 2D bounds of what this line actually draws — tight on cubics (it finds their
 * extrema), unlike [FLine.fast2DBounds], which encloses the control points. `MoveTo` points with
 * nothing drawn from them still count, as in Paper.js `bounds`. A line with no nodes has an empty
 * `Rect(0, 0, 0, 0)`.
 */
fun FLine.tightBounds2D(): Rect {
    var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
    fun add(p: Vec3) { x0 = min(x0, p.x.toDouble()); y0 = min(y0, p.y.toDouble()); x1 = max(x1, p.x.toDouble()); y1 = max(y1, p.y.toDouble()) }
    nodes.forEach { n -> n.toOrNull?.let { add(it) } }
    for (s in drawnSegments()) {
        add(s.p0); add(s.p3)
        if (s.straight) continue
        // B'(u)/3 = A u^2 + B u + C per axis
        fun roots(a: Float, b: Float, c: Float, d: Float) =
            unitQuadraticRoots((-a + 3 * b - 3 * c + d).toDouble(), (2 * (a - 2 * b + c)).toDouble(), (b - a).toDouble())
        roots(s.p0.x, s.p1.x, s.p2.x, s.p3.x).forEach { add(s.at(it)) }
        roots(s.p0.y, s.p1.y, s.p2.y, s.p3.y).forEach { add(s.at(it)) }
    }
    if (x0 > x1) return Rect(0.0, 0.0, 0.0, 0.0)
    return Rect(x0, y0, x1 - x0, y1 - y0)
}

/**
 * The signed area this line encloses in the xy plane, exact on cubics. Each subpath is closed back
 * to its start if it is not already, and subpaths add: a counter wound the other way subtracts.
 * Positive when the contours run counter-clockwise with y pointing **up** — which is clockwise on
 * screen, where y points down. Paper.js `path.area`.
 *
 * [signedArea2D] is the older chord-only version; this one follows the curves.
 */
fun FLine.area2D(): Float {
    // 0.5 * integral (x y' - y x') du, by 3-point Gauss-Legendre: exact for a cubic (degree 5).
    val gx = doubleArrayOf(0.5 - sqrt(0.15), 0.5, 0.5 + sqrt(0.15))
    val gw = doubleArrayOf(5.0 / 18, 8.0 / 18, 5.0 / 18)
    var area = 0.0
    var start: Vec3? = null
    var pen: Vec3? = null
    fun chord(a: Vec3, b: Vec3) { area += 0.5 * (a.x.toDouble() * b.y - b.x.toDouble() * a.y) }
    val segs = drawnSegments().associateBy { it.index }
    for (i in nodes.indices) {
        val n = nodes[i]
        if (i == 0 || n is FLine.MoveTo) {
            if (start != null && pen != null) chord(pen!!, start!!)
            start = Vec3(endPointOf(i)); pen = Vec3(start!!)
            continue
        }
        val s = segs[i] ?: continue
        if (s.straight) chord(s.p0, s.p3) else {
            for (k in 0..2) {
                val p = s.at(gx[k]); val d = s.d1(gx[k])
                area += 0.5 * gw[k] * (p.x * d[1] - p.y * d[0])
            }
        }
        pen = Vec3(s.p3)
    }
    if (start != null && pen != null) chord(pen!!, start!!)
    return area.toFloat()
}

/**
 * Whether this line winds clockwise **on screen** (y pointing down, as on Field's canvas and in SVG
 * / Paper.js) — i.e. [area2D] is positive. A line with no area answers `false`.
 */
fun FLine.isClockwise(): Boolean = area2D() > 0f

/** intersections of two segment cubics by recursive bounding-box subdivision, refined to [tol]. */
private fun crossSegs(a: FLineSeg, b: FLineSeg, tol: Double, out: MutableList<Triple<Double, Double, Vec3>>) {
    if (a.straight && b.straight) {
        val rx = (a.p3.x - a.p0.x).toDouble(); val ry = (a.p3.y - a.p0.y).toDouble()
        val sx = (b.p3.x - b.p0.x).toDouble(); val sy = (b.p3.y - b.p0.y).toDouble()
        val den = rx * sy - ry * sx
        if (abs(den) < 1e-12) return // parallel or collinear: no isolated crossing
        val qx = (b.p0.x - a.p0.x).toDouble(); val qy = (b.p0.y - a.p0.y).toDouble()
        val u = (qx * sy - qy * sx) / den
        val v = (qx * ry - qy * rx) / den
        val e = 1e-9
        if (u >= -e && u <= 1 + e && v >= -e && v <= 1 + e) out.add(Triple(u.coerceIn(0.0, 1.0), v.coerceIn(0.0, 1.0), a.at(u.coerceIn(0.0, 1.0))))
        return
    }
    var budget = 20000
    fun box(q: Array<Vec3>): DoubleArray = doubleArrayOf(
        q.minOf { it.x }.toDouble(), q.minOf { it.y }.toDouble(), q.maxOf { it.x }.toDouble(), q.maxOf { it.y }.toDouble()
    )
    fun rec(ua: Double, ub: Double, va: Double, vb: Double, depth: Int) {
        if (budget-- <= 0) return
        val ba = box(a.sub(ua, ub)); val bb = box(b.sub(va, vb))
        if (ba[0] > bb[2] + tol || bb[0] > ba[2] + tol || ba[1] > bb[3] + tol || bb[1] > ba[3] + tol) return
        val sa = max(ba[2] - ba[0], ba[3] - ba[1]); val sb = max(bb[2] - bb[0], bb[3] - bb[1])
        if ((sa < tol && sb < tol) || depth > 48) {
            val u = (ua + ub) / 2; val v = (va + vb) / 2
            out.add(Triple(u, v, a.at(u))); return
        }
        val um = (ua + ub) / 2; val vm = (va + vb) / 2
        if (sa >= tol && sb >= tol) {
            rec(ua, um, va, vm, depth + 1); rec(ua, um, vm, vb, depth + 1)
            rec(um, ub, va, vm, depth + 1); rec(um, ub, vm, vb, depth + 1)
        } else if (sa >= tol) {
            rec(ua, um, va, vb, depth + 1); rec(um, ub, va, vb, depth + 1)
        } else {
            rec(ua, ub, va, vm, depth + 1); rec(ua, ub, vm, vb, depth + 1)
        }
    }
    rec(0.0, 1.0, 0.0, 1.0, 0)
}

/** merge the cluster of hits subdivision leaves around each real crossing. */
private fun dedupe(hits: List<FLineCrossing>, radius: Float): List<FLineCrossing> {
    val out = ArrayList<FLineCrossing>()
    for (h in hits.sortedBy { it.t }) {
        if (out.none { it.point.distance(h.point) <= radius }) out.add(h)
    }
    return out
}

/**
 * Every point where this line crosses [other] in the xy plane, with the parameter on both lines —
 * Paper.js `getIntersections`, Skia `SkPathOps` building block. Straight segments are intersected
 * exactly; cubics by subdivision to [tolerance] drawing units. Hits closer than `4 * tolerance` are
 * one crossing. Sorted by [FLineCrossing.t]. Overlapping collinear runs report no isolated crossing.
 */
fun FLine.crossings(other: FLine, tolerance: Float = 0.01f): List<FLineCrossing> {
    val tol = tolerance.coerceAtLeast(1e-5f).toDouble()
    val hits = ArrayList<FLineCrossing>()
    val bs = other.drawnSegments()
    for (a in drawnSegments()) for (b in bs) {
        val raw = ArrayList<Triple<Double, Double, Vec3>>()
        crossSegs(a, b, tol, raw)
        raw.forEach { (u, v, p) -> hits.add(FLineCrossing(p, (a.index - 1 + u).toFloat(), (b.index - 1 + v).toFloat())) }
    }
    return dedupe(hits, (4 * tol).toFloat())
}

/**
 * Every point where this line crosses itself, as pairs of parameters `t < otherT` — Paper.js
 * `getCrossings()` on one path. Neighbouring segments meeting at their shared node, and a closed
 * subpath meeting itself at its start, are joins, not crossings, and are not reported.
 */
fun FLine.selfCrossings(tolerance: Float = 0.01f): List<FLineCrossing> {
    val tol = tolerance.coerceAtLeast(1e-5f).toDouble()
    val segs = drawnSegments()
    val hits = ArrayList<FLineCrossing>()
    val joinRadius = 4 * tol
    for (i in segs.indices) for (j in i + 1 until segs.size) {
        val a = segs[i]; val b = segs[j]
        val raw = ArrayList<Triple<Double, Double, Vec3>>()
        crossSegs(a, b, tol, raw)
        for ((u, v, p) in raw) {
            // a join: a's end is b's start, or b's end is a's start (a closed contour), and the hit is there
            val atJoin = (a.p3.distance(b.p0) <= joinRadius && p.distance(b.p0) <= joinRadius * 4) ||
                (b.p3.distance(a.p0) <= joinRadius && p.distance(a.p0) <= joinRadius * 4)
            if (atJoin) continue
            hits.add(FLineCrossing(p, (a.index - 1 + u).toFloat(), (b.index - 1 + v).toFloat()))
        }
    }
    return dedupe(hits, (4 * tol).toFloat())
}

/**
 * Whether [point] is inside this line under [rule], following its curves (cubics are flattened
 * finely), with every subpath treated as closed. The one-argument [contains] is the non-zero rule on
 * chords only; use this when the outline is curved or when a counter should be a hole regardless of
 * winding direction ([FLineFillRule.EvenOdd]).
 */
fun FLine.contains(point: Vec2, rule: FLineFillRule): Boolean {
    var winding = 0
    var crossings = 0
    fun edge(ax: Double, ay: Double, bx: Double, by: Double) {
        val px = point.x.toDouble(); val py = point.y.toDouble()
        if ((ay <= py) == (by <= py)) return
        val xAt = ax + (py - ay) * (bx - ax) / (by - ay)
        if (xAt > px) { crossings++; winding += if (by > ay) 1 else -1 }
    }
    var start: Vec3? = null
    var pen: Vec3? = null
    val segs = drawnSegments().associateBy { it.index }
    fun closeOff() { val s = start; val p = pen; if (s != null && p != null) edge(p.x.toDouble(), p.y.toDouble(), s.x.toDouble(), s.y.toDouble()) }
    for (i in nodes.indices) {
        if (i == 0 || nodes[i] is FLine.MoveTo) { closeOff(); start = Vec3(endPointOf(i)); pen = Vec3(start!!); continue }
        val s = segs[i] ?: continue
        val n = if (s.straight) 1 else 64
        var prev = s.p0
        for (k in 1..n) {
            val q = if (k == n) s.p3 else s.at(k.toDouble() / n)
            edge(prev.x.toDouble(), prev.y.toDouble(), q.x.toDouble(), q.y.toDouble())
            prev = q
        }
        pen = Vec3(s.p3)
    }
    closeOff()
    return when (rule) {
        FLineFillRule.NonZero -> winding != 0
        FLineFillRule.EvenOdd -> crossings % 2 == 1
    }
}
