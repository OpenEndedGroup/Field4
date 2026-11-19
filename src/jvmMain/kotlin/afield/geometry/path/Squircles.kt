package afield.geometry.path

import afield.math.linalg.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/**
 * Squircles: rounded squares and rounded rectangles whose corners are smoother than a circular arc.
 *
 * Every construction here is set by one number, the **gauge**: on a squircle of unit radius,
 * the point halfway round a quadrant sits at `(gauge, gauge)`. A circle has gauge `sqrt(0.5)`
 * (0.707), a square has gauge 1. The superellipse `|x|^n + |y|^n = 1` has gauge `0.5^(1/n)`
 * ([squircleGauge], [squircleExponent]). Sharing the gauge lets the different constructions be
 * compared shape for shape.
 *
 * The constructions ([SquircleStyle]) follow the Linebender wiki's survey of squircles
 * (https://linebender.org/wiki/curves/squircle/): the superellipse sampled exactly, one cubic per
 * quadrant, Chromium's two cubics per quadrant (CSS `corner-shape: superellipse()`), Figma's
 * corner smoothing (an arc flanked by two cubics) and clothoid corners (an arc flanked by two Euler
 * spirals). They differ in how smoothly curvature changes: [squircleCurvatureProfile] gives it
 * exactly, so the differences can be plotted.
 */

/** The gauge (quadrant midpoint coordinate, unit radius) of the superellipse `|x|^n + |y|^n = 1`: `0.5^(1/n)`. */
fun squircleGauge(n: Double): Double = 0.5.pow(1 / n)

/** The superellipse exponent whose gauge is [gauge]: `ln 0.5 / ln gauge`. */
fun squircleExponent(gauge: Double): Double = ln(0.5) / ln(gauge)

/**
 * How a squircle's corner is built. All take the same gauge.
 *
 * - [Superellipse]: `|x|^n + |y|^n = 1` sampled adaptively; G(ceil(n) - 1) where the corner meets
 *   the axes, analytic everywhere else. Gauge from `sqrt(0.5)` up.
 * - [SuperellipseCubic]: one cubic per quadrant through the exact midpoint. G1 only: its
 *   curvature at the axes is not the superellipse's zero.
 * - [Chromium]: two cubics per quadrant, Chromium's fit for CSS `corner-shape: superellipse()`.
 *   Exact at the midpoint, G1 where the cubics meet the axes.
 * - [Figma]: a circular arc flanked by two cubics that leave the straight sides with zero
 *   curvature (Figma's corner smoothing). Curvature jumps where the cubics meet the arc. Without a
 *   flat side it covers gauges 0.707 to 0.854; above that it grows straight sides.
 * - [Clothoid]: a circular arc flanked by two Euler spirals whose curvature rises linearly from
 *   zero: G2 throughout. Without a flat side it covers gauges 0.707 to about 0.790; above that it
 *   grows straight sides.
 */
enum class SquircleStyle { Superellipse, SuperellipseCubic, Chromium, Figma, Clothoid }

/**
 * A closed squircle centred on [center], radii [rx] by [ry], with corners of [style] at [gauge].
 *
 * The four quadrants are one unit quadrant turned by quarter turns and then stretched to [rx] by
 * [ry]; stretching keeps every construction's continuity.
 *
 * @param gauge quadrant midpoint on a unit squircle, at least `sqrt(0.5)` (a circle), below 1
 */
fun squircleFrom(center: Vec2, rx: Double, ry: Double = rx, gauge: Double = squircleGauge(4.0), style: SquircleStyle = SquircleStyle.Superellipse): FLine {
    val pieces = squircleQuadrant(style, gauge)
    val out = FLine()
    for (q in 0 until 4) emitQuadrant(out, pieces, q, center.x.toDouble(), center.y.toDouble(), rx, ry, first = q == 0)
    out.close()
    return out
}

/**
 * A rectangle with straight sides and squircle corners: the rounded-rectangle helper for corners
 * smoother than an arc. The corner fits any aspect ratio, because the sides stay flat and only
 * the corners curve.
 *
 * @param radius how far each corner reaches along the sides; clamped to half the shorter side
 * @param gauge corner midpoint on a unit corner, as in [squircleFrom]
 */
fun squircleRectFrom(x: Double, y: Double, w: Double, h: Double, radius: Double, gauge: Double = squircleGauge(4.0), style: SquircleStyle = SquircleStyle.Superellipse): FLine {
    val r = radius.coerceIn(0.0, min(w, h) / 2)
    val pieces = squircleQuadrant(style, gauge)
    val out = FLine()
    val cx = x + w / 2; val cy = y + h / 2
    val sx = doubleArrayOf(1.0, -1.0, -1.0, 1.0); val sy = doubleArrayOf(1.0, 1.0, -1.0, -1.0)
    for (q in 0 until 4) {
        emitQuadrant(out, pieces, q, cx + sx[q] * (w / 2 - r), cy + sy[q] * (h / 2 - r), r, r, first = q == 0)
    }
    out.close()
    return out
}

/**
 * Exact curvature along one unit quadrant of a squircle, from `(1, 0)` to `(0, 1)`: each point is
 * (arc length, curvature). Where two pieces meet, both curvatures appear at the same arc length, so
 * a jump plots as a vertical step.
 */
fun squircleCurvatureProfile(style: SquircleStyle, gauge: Double, samplesPerPiece: Int = 200): List<Vec2> {
    val out = ArrayList<Vec2>()
    var s0 = 0.0
    for (p in squircleQuadrant(style, gauge)) {
        val ss = p.samples(samplesPerPiece)
        var s = s0
        for (i in ss.indices) {
            if (i > 0) s += hypot(ss[i].x - ss[i - 1].x, ss[i].y - ss[i - 1].y)
            out.add(Vec2(s.toFloat(), ss[i].k.toFloat()))
        }
        s0 = s
    }
    return out
}

/**
 * The largest radial deviation of [line] from the superellipse `|x/rx|^n + |y/ry|^n = 1` centred
 * on [center], as a fraction of the radius: `max |(|x/rx|^n + |y/ry|^n)^(1/n) - 1|` over points
 * [samples] evenly along the line.
 */
fun FLine.superellipseDeviation(n: Double, center: Vec2 = Vec2(0f, 0f), rx: Double = 1.0, ry: Double = rx, samples: Int = 2000): Double {
    val c = cursor()
    val total = c.lengthD().toDouble()
    var worst = 0.0
    for (i in 0..samples) {
        c.setD((total * i / samples).toFloat())
        val p = c.position()
        val r = (abs((p.x - center.x) / rx).pow(n) + abs((p.y - center.y) / ry).pow(n)).pow(1 / n)
        worst = max(worst, abs(r - 1))
    }
    return worst
}

// ---- unit quadrant: (1, 0) heading +y round to (0, 1) heading -x, about the origin ----

private class QSample(val x: Double, val y: Double, val k: Double)

private sealed class QPiece {
    abstract fun samples(m: Int): List<QSample>
    abstract fun emit(line: FLine, map: (Double, Double) -> Pair<Double, Double>)

    class Cubic(val p: DoubleArray) : QPiece() {
        fun at(t: Double): QSample {
            val u = 1 - t
            val x = u * u * u * p[0] + 3 * u * u * t * p[2] + 3 * u * t * t * p[4] + t * t * t * p[6]
            val y = u * u * u * p[1] + 3 * u * u * t * p[3] + 3 * u * t * t * p[5] + t * t * t * p[7]
            val dx = 3 * (u * u * (p[2] - p[0]) + 2 * u * t * (p[4] - p[2]) + t * t * (p[6] - p[4]))
            val dy = 3 * (u * u * (p[3] - p[1]) + 2 * u * t * (p[5] - p[3]) + t * t * (p[7] - p[5]))
            val ddx = 6 * (u * (p[4] - 2 * p[2] + p[0]) + t * (p[6] - 2 * p[4] + p[2]))
            val ddy = 6 * (u * (p[5] - 2 * p[3] + p[1]) + t * (p[7] - 2 * p[5] + p[3]))
            val v = hypot(dx, dy)
            return QSample(x, y, if (v < 1e-12) 0.0 else (dx * ddy - dy * ddx) / (v * v * v))
        }
        override fun samples(m: Int) = (0..m).map { at(it.toDouble() / m) }
        override fun emit(line: FLine, map: (Double, Double) -> Pair<Double, Double>) {
            val (ax, ay) = map(p[2], p[3]); val (bx, by) = map(p[4], p[5]); val (ex, ey) = map(p[6], p[7])
            line.cubicTo(ax, ay, 0, bx, by, 0, ex, ey, 0)
        }
    }

    /** Dense points with their exact curvature (arcs, spirals, the sampled superellipse). */
    class Sampled(val pts: List<QSample>) : QPiece() {
        override fun samples(m: Int) = pts
        override fun emit(line: FLine, map: (Double, Double) -> Pair<Double, Double>) {
            for (s in pts.drop(1)) { val (x, y) = map(s.x, s.y); line.lineTo(x, y) }
        }
    }
}

private fun emitQuadrant(out: FLine, pieces: List<QPiece>, q: Int, cx: Double, cy: Double, rx: Double, ry: Double, first: Boolean) {
    val map = { x: Double, y: Double ->
        var a = x; var b = y
        repeat(q) { val t = a; a = -b; b = t }
        Pair(cx + a * rx, cy + b * ry)
    }
    val (sx, sy) = map(1.0, 0.0)
    if (first) out.moveTo(sx, sy) else out.lineTo(sx, sy)
    for (p in pieces) p.emit(out, map)
}

private fun squircleQuadrant(style: SquircleStyle, gauge: Double): List<QPiece> {
    require(gauge >= sqrt(0.5) - 1e-9 && gauge < 1) { "squircle gauge must be in [sqrt(0.5), 1), was $gauge" }
    return when (style) {
        SquircleStyle.Superellipse -> listOf(superellipseQuadrant(squircleExponent(gauge)))
        SquircleStyle.SuperellipseCubic -> {
            // midpoint of (1,0),(1,h),(h,1),(0,1) is ((4 + 3h) / 8, same): through (gauge, gauge)
            val h = (8 * gauge - 4) / 3
            listOf(QPiece.Cubic(doubleArrayOf(1.0, 0.0, 1.0, h, h, 1.0, 0.0, 1.0)))
        }
        SquircleStyle.Chromium -> chromiumQuadrant(squircleExponent(gauge))
        SquircleStyle.Figma -> figmaQuadrant(gauge)
        SquircleStyle.Clothoid -> clothoidQuadrant(gauge)
    }
}

/** `|x|^n + |y|^n = 1` from (1,0) to (0,1), bisected until each chord is within 1e-5 of the curve. */
private fun superellipseQuadrant(n: Double): QPiece {
    fun pt(t: Double): QSample {
        val c = cos(t).coerceAtLeast(0.0); val s = sin(t).coerceAtLeast(0.0)
        val x = c.pow(2 / n); val y = s.pow(2 / n)
        // implicit F = x^n + y^n; κ = (Fy² Fxx + Fx² Fyy) / (Fx² + Fy²)^(3/2)
        val fx = n * x.pow(n - 1); val fy = n * y.pow(n - 1)
        val fxx = n * (n - 1) * x.pow(n - 2); val fyy = n * (n - 1) * y.pow(n - 2)
        val g = hypot(fx, fy)
        val k = if (x < 1e-12 || y < 1e-12) (if (n > 2) 0.0 else 1.0) else (fy * fy * fxx + fx * fx * fyy) / (g * g * g)
        return QSample(x, y, k)
    }
    val out = ArrayList<QSample>()
    fun rec(t0: Double, a: QSample, t1: Double, b: QSample, depth: Int) {
        val tm = (t0 + t1) / 2
        val m = pt(tm)
        val dx = b.x - a.x; val dy = b.y - a.y
        val dev = abs((m.x - a.x) * dy - (m.y - a.y) * dx) / max(hypot(dx, dy), 1e-15)
        if (depth < 18 && (depth < 6 || dev > 1e-5 || hypot(dx, dy) > 0.02)) {
            rec(t0, a, tm, m, depth + 1); rec(tm, m, t1, b, depth + 1)
        } else out.add(b)
    }
    val a = pt(0.0)
    out.add(a)
    rec(0.0, a, PI / 2, pt(PI / 2), 0)
    return QPiece.Sampled(out)
}

/** Chromium's two-cubic half-corner fit (developer.chrome.com/blog/implementing-corner-shape), mirrored across the diagonal. */
private fun chromiumQuadrant(k: Double): List<QPiece> {
    val p0 = 1.2430920942724248; val p1 = 2.010479023614843; val p2 = 0.32922901179443753
    val p3 = 0.2823023142212073; val p4 = 1.3473704261055421; val p5 = 2.9149468637949814; val p6 = 0.9106507102917086
    val s = log2(k)
    val slope = p0 + (p6 - p0) * 0.5 * (1 + tanh(p5 * (s - p1)))
    val base = 1 / (1 + exp(slope * p1))
    val logistic = 1 / (1 + exp(slope * (p1 - s)))
    val a = (logistic - base) / (1 - base)
    val b = p2 * exp(-p3 * s.pow(p4))
    val h = 0.5.pow(1 / k)
    // Chromium's half runs (0,1) -> (h,h); ours runs (1,0) -> (h,h) -> (0,1), so mirror the first half.
    val first = doubleArrayOf(1.0, 0.0, 1.0, a, h + b, h - b, h, h)
    val second = doubleArrayOf(h, h, h - b, h + b, a, 1.0, 0.0, 1.0)
    return listOf(QPiece.Cubic(first), QPiece.Cubic(second))
}

private fun line(x0: Double, y0: Double, x1: Double, y1: Double): QPiece =
    QPiece.Sampled(listOf(QSample(x0, y0, 0.0), QSample(x1, y1, 0.0)))

private fun arc(cx: Double, cy: Double, r: Double, a0: Double, a1: Double, m: Int = 64): QPiece =
    QPiece.Sampled((0..m).map { i -> val a = a0 + (a1 - a0) * i / m; QSample(cx + r * cos(a), cy + r * sin(a), 1 / r) })

/**
 * Figma's corner smoothing (the construction in figma-squircle), corner at (1,1): radius `R`,
 * smoothing `ξ`, the corner reaching `p = (1 + ξ) R` along each side. The arc's midpoint is
 * `1 - R (1 - 1/sqrt 2)`, which fixes R from the gauge; ξ is as large as fits, at most 1.
 */
private fun figmaQuadrant(gauge: Double): List<QPiece> {
    val r = (1 - gauge) / (1 - 1 / sqrt(2.0))
    val xi = min(1.0, 1 / r - 1)
    val p = (1 + xi) * r
    val deg = PI / 180
    val arcMeasure = 90 * (1 - xi)
    val arcSection = sin(arcMeasure / 2 * deg) * r * sqrt(2.0)
    val alpha = (90 - arcMeasure) / 2
    val p3ToP4 = r * tan(alpha / 2 * deg)
    val beta = 45 * xi
    val c = p3ToP4 * cos(beta * deg)
    val d = c * tan(beta * deg)
    val b = (p - arcSection - c - d) / 3
    val a = 2 * b
    // first side travels +y along x = 1 from (1, 1 - p); "inward" is -x
    val sy = 1 - p
    val out = ArrayList<QPiece>()
    if (sy > 1e-12) out.add(line(1.0, 0.0, 1.0, sy))
    out.add(QPiece.Cubic(doubleArrayOf(1.0, sy, 1.0, sy + a, 1.0, sy + a + b, 1 - d, sy + a + b + c)))
    val cx = 1 - r; val cy = 1 - r
    val a0 = atan2(sy + a + b + c - cy, 1 - d - cx)
    out.add(arc(cx, cy, r, a0, PI / 2 - a0))
    out.add(QPiece.Cubic(doubleArrayOf(sy + a + b + c, 1 - d, sy + a + b, 1.0, sy + a, 1.0, sy, 1.0)))
    if (sy > 1e-12) out.add(line(sy, 1.0, 0.0, 1.0))
    return out
}

/**
 * Straight, Euler spiral (curvature rising linearly 0 -> 1/R while turning φ), arc, mirrored spiral,
 * straight. Below a gauge of about 0.790 there is no straight part and φ is found by bisection;
 * above it φ = 45° and the straight part grows.
 */
private fun clothoidQuadrant(gauge: Double): List<QPiece> {
    // integrate a corner of arc radius 1 from (0,0) heading +y; returns samples (unscaled), half-turn index
    fun corner(phi: Double, m: Int = 600): Pair<List<QSample>, Int> {
        val lc = 2 * phi; val la = PI / 2 - 2 * phi; val total = 2 * lc + la
        fun kappa(s: Double) = when {
            s < lc -> s / lc
            s > lc + la -> (total - s) / lc
            else -> 1.0
        }
        fun theta(s: Double) = when { // heading turned so far
            s < lc -> s * s / (2 * lc)
            s <= lc + la -> phi + (s - lc)
            else -> PI / 2 - (total - s) * (total - s) / (2 * lc)
        }
        val pts = ArrayList<QSample>()
        var x = 0.0; var y = 0.0
        pts.add(QSample(0.0, 0.0, if (lc > 0) 0.0 else 1.0))
        val ds = total / m
        for (i in 1..m) {
            // Simpson on the heading over [s0, s1]
            val s0 = (i - 1) * ds; val s1 = i * ds; val sm = (s0 + s1) / 2
            fun dir(s: Double) = PI / 2 + theta(s)
            x += ds / 6 * (cos(dir(s0)) + 4 * cos(dir(sm)) + cos(dir(s1)))
            y += ds / 6 * (sin(dir(s0)) + 4 * sin(dir(sm)) + sin(dir(s1)))
            pts.add(QSample(x, y, kappa(s1)))
        }
        return pts to m / 2
    }
    // displacement end = (-X, Y) with X == Y; midpoint (-Xm, Ym)
    fun gaugeNoFlat(phi: Double): Double { val (p, h) = corner(phi, 200); return 1 - (-p[h].x) / (-p.last().x) }
    val maxPhi = PI / 4
    val phi: Double
    val flat: Double
    if (gauge >= gaugeNoFlat(maxPhi)) {
        phi = maxPhi
        val (p, h) = corner(phi, 200)
        flat = 1 - (1 - gauge) * (-p.last().x) / (-p[h].x)
    } else {
        var lo = 0.0; var hi = maxPhi
        repeat(50) { val mid = (lo + hi) / 2; if (gaugeNoFlat(mid) < gauge) lo = mid else hi = mid }
        phi = (lo + hi) / 2; flat = 0.0
    }
    val (pts, _) = corner(phi)
    val lambda = (1 - flat) / (-pts.last().x)
    val out = ArrayList<QPiece>()
    if (flat > 1e-12) out.add(line(1.0, 0.0, 1.0, flat))
    out.add(QPiece.Sampled(pts.map { QSample(1 + lambda * it.x, flat + lambda * it.y, it.k / lambda) }))
    if (flat > 1e-12) out.add(line(flat, 1.0, 0.0, 1.0))
    return out
}
