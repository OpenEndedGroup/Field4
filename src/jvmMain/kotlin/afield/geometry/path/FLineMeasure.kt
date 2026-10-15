package afield.geometry.path

import afield.lang.remotejs.JsFun
import afield.ui.visualization.lines

import afield.app.protocol.Rect
import afield.math.linalg.Vec2
import afield.math.linalg.Vec3
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Measuring, locating, trimming and crossing an [FLine].
 *
 * There is one measurer: [FLine.cursor]. A cursor walks a line by distance (`setD`) or by node
 * parameter (`setT`), and everything it reports about the point under it — position, unit tangent,
 * Frenet frame, curvature — comes from the one segment evaluator in this file ([FLineSeg]: exact
 * cubic first and second derivatives, lines degree-elevated, degenerate ends handled). `location()`
 * hands the lot back as an [FLineLocation].
 *
 * The FLine-level functions here are front doors onto a cursor for the common one-shot questions:
 * [length], [locationAt], [locationAtFraction], [nearestLocation], [trim], [splitAt]. Use a cursor
 * yourself when you walk a line step by step.
 *
 * Two parameterisations run through all of it:
 *  - **distance** (`d`), in drawing units along the line, from 0 to [length]. Use it when you want
 *    things evenly spaced.
 *  - **`t`**, the node parameter: `t = i + u` is fraction `u` of the way (by curve parameter, not by
 *    distance) along the segment that ends at node `i + 1`.
 * Every FLineLocation carries both.
 *
 * Everything whose answer only exists in the xy plane says so in its name: [FLineLocation.normal2D],
 * [FLineLocation.signedCurvature2D], [tightBounds2D], [area2D], [isClockwise2D], [windingNumber2D],
 * [contains2D], [crossings2D], [selfCrossings2D]. Everything else is 3D.
 */
private const val EPS = 1e-9

/**
 * A point on an [FLine] with its local frame.
 *
 * At a node, the frame is the outgoing segment's (the incoming one's at the very end of the line).
 *
 * @property point where on the line
 * @property tangent unit direction of travel. At a degenerate end — a cubic whose control point sits
 *   on its end point, where the derivative vanishes — it is the limiting direction. Zero only on a
 *   segment of zero length.
 * @property normal the unit principal normal: perpendicular to [tangent], towards the centre of
 *   curvature, in whatever plane the line is bending. `null` on straight runs, where there is no
 *   such direction — there is no sensible fallback in 3D, and in 2D you want [normal2D] anyway.
 * @property binormal `tangent × normal`, the axis the line is turning about; `null` where [normal] is.
 * @property curvature unsigned 3D curvature `|r' × r''| / |r'|³`, i.e. `1 / radius`; 0 on straight runs.
 * @property normal2D [tangent]'s xy part turned a quarter turn, `(tangent.y, -tangent.x)`, unit
 *   length. On Field's canvas (y down) that is to the **left** of the direction of travel; with y up,
 *   to the right. It is defined on straight runs too, and is the zero vector only where the line runs
 *   straight along z. This is the normal to offset a 2D drawing along.
 * @property signedCurvature2D curvature of the line's xy projection, positive when it bends towards
 *   [normal2D]; 0 on straight runs.
 * @property distance distance along the line from its start, in drawing units
 * @property t the node parameter of this point (see the file overview)
 * @property segment index of the node that ends the segment this point is on
 */
data class FLineLocation(
    val point: Vec3,
    val tangent: Vec3,
    val normal: Vec3?,
    val binormal: Vec3?,
    val curvature: Float,
    val normal2D: Vec2,
    val signedCurvature2D: Float,
    val distance: Float,
    val t: Float,
    val segment: Int,
)

/**
 * One place where two lines (or one line with itself) cross in the xy plane.
 *
 * @property point the crossing point
 * @property t the node parameter on the line the method was called on
 * @property otherT the node parameter on the other line (on the same line, for [selfCrossings2D])
 */
data class FLineCrossing(val point: Vec3, val t: Float, val otherT: Float)

/** How [contains2D] decides "inside" when contours overlap or nest — the SVG / canvas `fill-rule`. */
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
 * The drawn segment that ends at node [i], as a cubic (`LineTo` and `Close` degree-elevated, a
 * `Close` running back to its subpath start), or `null` for a `MoveTo`, for node 0, or out of range.
 * A leading non-`MoveTo` node places the pen, exactly as the renderer reads it.
 */
internal fun FLine.drawnSegmentEndingAt(i: Int): FLineSeg? {
    if (i < 1 || i >= nodes.size) return null
    val n = nodes[i]
    val a = Vec3(endPointOf(i - 1))
    return when (n) {
        is FLine.CubicTo -> FLineSeg(i, a, Vec3(n.c1), Vec3(n.c2), Vec3(n.to), false)
        is FLine.LineTo, is FLine.Close -> {
            val b = Vec3(endPointOf(i))
            val c1 = Vec3(a.x + (b.x - a.x) / 3, a.y + (b.y - a.y) / 3, a.z + (b.z - a.z) / 3)
            val c2 = Vec3(a.x + 2 * (b.x - a.x) / 3, a.y + 2 * (b.y - a.y) / 3, a.z + 2 * (b.z - a.z) / 3)
            FLineSeg(i, a, c1, c2, b, true)
        }
        else -> null
    }
}

/** Every drawn segment of this line, `Close` included; `MoveTo` jumps are not segments. */
internal fun FLine.drawnSegments(): List<FLineSeg> = (1 until nodes.size).mapNotNull { drawnSegmentEndingAt(it) }

private fun len3(d: DoubleArray) = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])

/**
 * The location at parameter [u] on this segment — the one place Field computes a tangent, normal or
 * curvature. [distance] is passed through (the caller owns the arc-length lookup).
 */
internal fun FLineSeg.locate(u: Double, distance: Float): FLineLocation {
    val p = at(u)
    // A degenerate end (control point on the end point) has a vanishing first derivative there;
    // the frame is its limit, so evaluate just inside. A segment of zero length falls back to its chord.
    var ue = u
    var d1 = d1(ue)
    if (len3(d1) < EPS) { ue = if (u < 0.5) u + 1e-4 else u - 1e-4; d1 = d1(ue) }
    if (len3(d1) < EPS) d1 = doubleArrayOf((p3.x - p0.x).toDouble(), (p3.y - p0.y).toDouble(), (p3.z - p0.z).toDouble())
    val d2 = if (straight) doubleArrayOf(0.0, 0.0, 0.0) else d2(ue)
    val len = len3(d1)
    val tx = if (len < EPS) 0.0 else d1[0] / len
    val ty = if (len < EPS) 0.0 else d1[1] / len
    val tz = if (len < EPS) 0.0 else d1[2] / len
    val cx = d1[1] * d2[2] - d1[2] * d2[1]
    val cy = d1[2] * d2[0] - d1[0] * d2[2]
    val cz = d1[0] * d2[1] - d1[1] * d2[0]
    val k = if (straight || len < EPS) 0.0 else sqrt(cx * cx + cy * cy + cz * cz) / (len * len * len)
    var normal: Vec3? = null
    var binormal: Vec3? = null
    if (k > 1e-9) {
        // the part of r'' perpendicular to the tangent points at the centre of curvature
        val dot = d2[0] * tx + d2[1] * ty + d2[2] * tz
        val nx = d2[0] - dot * tx; val ny = d2[1] - dot * ty; val nz = d2[2] - dot * tz
        val nl = sqrt(nx * nx + ny * ny + nz * nz)
        if (nl > EPS) {
            normal = Vec3((nx / nl).toFloat(), (ny / nl).toFloat(), (nz / nl).toFloat())
            binormal = Vec3(
                (ty * normal.z - tz * normal.y).toFloat(),
                (tz * normal.x - tx * normal.z).toFloat(),
                (tx * normal.y - ty * normal.x).toFloat(),
            )
        }
    }
    val txy = hypot(tx, ty)
    val n2 = if (txy < 1e-7) Vec2(0f, 0f) else Vec2((ty / txy).toFloat(), (-tx / txy).toFloat())
    val lxy = hypot(d1[0], d1[1])
    val k2 = if (straight || lxy < EPS) 0.0 else -cz / (lxy * lxy * lxy)
    return FLineLocation(
        p, Vec3(tx.toFloat(), ty.toFloat(), tz.toFloat()), normal, binormal, k.toFloat(),
        n2, k2.toFloat(), distance, (index - 1 + u).toFloat(), index,
    )
}

/** the node parameter `t` of the point on this line nearest [point], or `null` if nothing is drawn. */
internal fun FLine.nearestSegmentParameter(point: Vec3): Float? {
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
    return (bestSeg.index - 1 + bestU).toFloat()
}

/**
 * The total drawn length of this line, in drawing units — `MoveTo` jumps do not count, `Close`
 * segments do. `cursor().lengthD()`; Paper.js `path.length`, Skia `SkPathMeasure.getLength`.
 */
fun FLine.length(): Float = if (nodes.size < 2) 0f else cursor().lengthD()

/**
 * The location [distance] units along this line (clamped to `0..length()`):
 * `cursor().setD(distance).location()`. Paper.js `getLocationAt` and its `getTangentAt` /
 * `getNormalAt` / `getCurvatureAt` family; Skia `SkPathMeasure.getPosTan`. `null` for a line with
 * nothing drawn in it.
 *
 * ```
 * val loc = circle.locationAt(25f)!!
 * marker(loc.point); line(loc.point, loc.point + loc.normal2D * 20f)
 * ```
 */
fun FLine.locationAt(distance: Float): FLineLocation? {
    val c = cursor()
    return c.setD(distance.coerceIn(0f, c.lengthD())).location()
}

/** [locationAt] by fraction of the [length]: 0 is the start, 1 the end, 0.5 half way by distance. */
fun FLine.locationAtFraction(fraction: Float): FLineLocation? = locationAt(fraction.coerceIn(0f, 1f) * length())

/**
 * The location on this line nearest to [point], by 3D distance: `cursor(point).location()`.
 * Paper.js `getNearestLocation`. `null` for a line with nothing drawn in it.
 */
fun FLine.nearestLocation(point: Vec3): FLineLocation? = cursor().setNearest(point).location()

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
fun FLine.isClockwise2D(): Boolean = area2D() > 0f

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
fun FLine.crossings2D(other: FLine, tolerance: Float = 0.01f): List<FLineCrossing> {
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
fun FLine.selfCrossings2D(tolerance: Float = 0.01f): List<FLineCrossing> {
    val tol = tolerance.coerceAtLeast(1e-5f).toDouble()
    // A single cubic can loop over itself, so each one is first cut where x' or y' is zero: a piece
    // monotone in both x and y cannot cross itself, and the loop becomes a crossing between two
    // pieces. Each piece remembers its parent segment and the u-range it covers.
    class Piece(val seg: FLineSeg, val u0: Double, val u1: Double)
    val pieces = ArrayList<Piece>()
    for (s in drawnSegments()) {
        if (s.straight) { pieces.add(Piece(FLineSeg(s.index, s.p0, s.p1, s.p2, s.p3, true), 0.0, 1.0)); continue }
        fun roots(a: Float, b: Float, c: Float, d: Float) =
            unitQuadraticRoots((-a + 3 * b - 3 * c + d).toDouble(), (2 * (a - 2 * b + c)).toDouble(), (b - a).toDouble())
        val cuts = (listOf(0.0, 1.0) + roots(s.p0.x, s.p1.x, s.p2.x, s.p3.x) + roots(s.p0.y, s.p1.y, s.p2.y, s.p3.y))
            .sorted().fold(ArrayList<Double>()) { acc, u -> if (acc.isEmpty() || u - acc.last() > 1e-6) acc.add(u); acc }
        for (k in 0 until cuts.size - 1) {
            val q = s.sub(cuts[k], cuts[k + 1])
            pieces.add(Piece(FLineSeg(s.index, q[0], q[1], q[2], q[3], false), cuts[k], cuts[k + 1]))
        }
    }
    val hits = ArrayList<FLineCrossing>()
    val joinRadius = 4 * tol
    for (i in pieces.indices) for (j in i + 1 until pieces.size) {
        val pa = pieces[i]; val pb = pieces[j]
        val a = pa.seg; val b = pb.seg
        val raw = ArrayList<Triple<Double, Double, Vec3>>()
        crossSegs(a, b, tol, raw)
        for ((lu, lv, p) in raw) {
            val u = pa.u0 + lu * (pa.u1 - pa.u0)
            val v = pb.u0 + lv * (pb.u1 - pb.u0)
            // a join: a's end is b's start, or b's end is a's start (a closed contour), and the hit is there
            val atJoin = (a.p3.distance(b.p0) <= joinRadius && p.distance(b.p0) <= joinRadius * 4) ||
                (b.p3.distance(a.p0) <= joinRadius && p.distance(a.p0) <= joinRadius * 4)
            if (atJoin) continue
            hits.add(FLineCrossing(p, (a.index - 1 + u).toFloat(), (b.index - 1 + v).toFloat()))
        }
    }
    return dedupe(hits, (4 * tol).toFloat())
}

/** the winding number and the plain crossing count of a rightward ray from [point], following curves. */
private fun FLine.windingAndCrossings2D(point: Vec2): Pair<Int, Int> {
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
    fun closeOff() { val s = start; val p = pen; if (s != null && p != null) edge(p.x.toDouble(), p.y.toDouble(), s.x.toDouble(), s.y.toDouble()) }
    for (i in nodes.indices) {
        if (i == 0 || nodes[i] is FLine.MoveTo) { closeOff(); start = Vec3(endPointOf(i)); pen = Vec3(start!!); continue }
        val s = drawnSegmentEndingAt(i) ?: continue
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
    return winding to crossings
}

/**
 * The winding number of this line about [point] in the xy plane, following its curves (cubics are
 * flattened finely), with every subpath treated as closed back to its `moveTo`; the jump between
 * subpaths is not a segment. Its sign follows the travel direction; non-zero means inside under the
 * non-zero rule. A glyph's counter, wound the other way, cancels to 0 inside the hole.
 */
fun FLine.windingNumber2D(point: Vec2): Int = windingAndCrossings2D(point).first

/**
 * Whether [point] is inside this line in the xy plane under [rule] (non-zero by default), following
 * its curves, with every subpath treated as closed. Use [FLineFillRule.EvenOdd] when a counter should
 * be a hole regardless of its winding direction.
 */
fun FLine.contains2D(point: Vec2, rule: FLineFillRule = FLineFillRule.NonZero): Boolean {
    val (winding, crossings) = windingAndCrossings2D(point)
    return when (rule) {
        FLineFillRule.NonZero -> winding != 0
        FLineFillRule.EvenOdd -> crossings % 2 == 1
    }
}
