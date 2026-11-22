package afield.graphics.nile

import afield.geometry.path.FLine
import afield.graphics.render.color
import afield.graphics.render.filled
import afield.graphics.render.stroked
import afield.math.linalg.vec
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Gezira's stroking kernel: `StrokeBezierPath` turns one open or closed path into the outline of a
 * stroke [width] wide, as more [Bezier]s that the rasteriser fills.
 *
 * Each segment is offset to both sides. A segment whose offset is not close enough to the true
 * offset curve (checked at its middle against [tolerance] pixels) is cut in half and both halves are
 * pushed back, the same recursion as DecomposeBeziers. Joins are bevels and caps are butt, so a
 * smooth path gives an exact outline up to [tolerance] and a sharp corner gives a bevel.
 */
object GeziraStroke {

    /** The unit normal (left of travel, y down) of [b] at [t]; falls back to the chord at a cusp. */
    fun normal(b: Bezier, t: Double): Pair<Double, Double> {
        var dx = b.d(t, 0)
        var dy = b.d(t, 1)
        if (hypot(dx, dy) < 1e-12) {
            // A doubled control point: use the direction to the far end instead.
            val n = b.p.size
            dx = b.p[n - 2] - b.p[0]
            dy = b.p[n - 1] - b.p[1]
        }
        val l = max(1e-300, hypot(dx, dy))
        return dy / l to -dx / l
    }

    /**
     * [b] moved [d] along its normal: each end, and the control points beside it, by the normal at
     * that end (a quadratic's middle point by their average). Approximate; [strokeBezierPath] checks it.
     */
    fun offset(b: Bezier, d: Double): Bezier {
        val (n0x, n0y) = normal(b, 0.0)
        val (n1x, n1y) = normal(b, 1.0)
        val k = b.p.size / 2
        val out = DoubleArray(b.p.size)
        for (i in 0 until k) {
            val (nx, ny) = when {
                k == 3 && i == 1 -> {
                    // Where the two legs, each moved d along its own normal, meet.
                    val k1 = 1.0 + n0x * n1x + n0y * n1y
                    if (k1 < 1e-6) n0x to n0y else (n0x + n1x) / k1 to (n0y + n1y) / k1
                }

                2 * i < k -> n0x to n0y
                else -> n1x to n1y
            }
            out[2 * i] = b.p[2 * i] + d * nx
            out[2 * i + 1] = b.p[2 * i + 1] + d * ny
        }
        return Bezier(out)
    }

    /** How far [offset] of [b] is from the true offset curve, at `t` = 0.25, 0.5 and 0.75. */
    fun offsetError(b: Bezier, d: Double): Double {
        val o = offset(b, d)
        var worst = 0.0
        for (t in doubleArrayOf(0.25, 0.5, 0.75)) {
            val (nx, ny) = normal(b, t)
            val tx = b.at(t, 0) + d * nx
            val ty = b.at(t, 1) + d * ny
            worst = max(worst, hypot(o.at(t, 0) - tx, o.at(t, 1) - ty))
        }
        return worst
    }

    private fun line(ax: Double, ay: Double, bx: Double, by: Double) = Bezier(doubleArrayOf(ax, ay, bx, by))

    private fun reversed(b: Bezier): Bezier {
        val k = b.p.size / 2
        return Bezier(DoubleArray(b.p.size) { i -> b.p[2 * (k - 1 - i / 2) + i % 2] })
    }

    private fun Bezier.sx() = p[0]
    private fun Bezier.sy() = p[1]
    private fun Bezier.ex() = p[p.size - 2]
    private fun Bezier.ey() = p[p.size - 1]

    /**
     * The stroke kernel. The input is one path, segment after segment; the output is closed
     * contours: for an open path one contour (left side, end cap, right side back, start cap), for a
     * closed path two (the left side, and the right side reversed).
     */
    fun strokeBezierPath(width: Double, tolerance: Double = 0.01): Stage<Bezier, Bezier> =
        kernel("StrokeBezierPath") {
            val d = width / 2
            val left = ArrayList<Bezier>()
            val right = ArrayList<Bezier>()
            var startX = Double.NaN
            var startY = Double.NaN
            var endX = Double.NaN
            var endY = Double.NaN
            fun joinTo(side: ArrayList<Bezier>, next: Bezier) {
                val last = side.lastOrNull() ?: return
                if (abs(last.ex() - next.sx()) > 1e-12 || abs(last.ey() - next.sy()) > 1e-12) {
                    side.add(line(last.ex(), last.ey(), next.sx(), next.sy()))
                }
            }
            each { b ->
                if (max(b.maxX() - b.minX(), b.maxY() - b.minY()) > 1e-6 &&
                    (offsetError(b, d) > tolerance || offsetError(b, -d) > tolerance)
                ) {
                    val (l, r) = b.split()
                    push(r)
                    push(l)
                } else {
                    if (startX.isNaN()) {
                        startX = b.sx()
                        startY = b.sy()
                    }
                    endX = b.ex()
                    endY = b.ey()
                    val lo = offset(b, d)
                    val ro = offset(b, -d)
                    joinTo(left, lo)
                    left.add(lo)
                    joinTo(right, ro)
                    right.add(ro)
                }
            }
            end {
                if (left.isEmpty()) return@end
                val closed = abs(startX - endX) < 1e-9 && abs(startY - endY) < 1e-9
                if (closed) {
                    // The join where the closed centre line meets itself.
                    joinTo(left, left.first())
                    joinTo(right, right.first())
                }
                val r = right.asReversed().map { reversed(it) }
                if (closed) {
                    left.forEach { emit(it) }
                    r.forEach { emit(it) }
                } else {
                    left.forEach { emit(it) }
                    emit(line(left.last().ex(), left.last().ey(), r.first().sx(), r.first().sy()))
                    r.forEach { emit(it) }
                    emit(line(r.last().ex(), r.last().ey(), left.first().sx(), left.first().sy()))
                }
            }
        }

    /** Skia's coverage of the same stroke: butt caps, bevel joins, anti-aliased. */
    fun skiaStrokeCoverage(path: List<Bezier>, width: Double, w: Int, h: Int, closed: Boolean): DoubleArray {
        val p = org.jetbrains.skia.Path()
        p.moveTo(path[0].p[0].toFloat(), path[0].p[1].toFloat())
        for (b in path) {
            val q = b.p.map { it.toFloat() }
            when (b.degree) {
                1 -> p.lineTo(q[2], q[3])
                2 -> p.quadTo(q[2], q[3], q[4], q[5])
                else -> p.cubicTo(q[2], q[3], q[4], q[5], q[6], q[7])
            }
        }
        if (closed) p.closePath()
        val paint = org.jetbrains.skia.Paint().apply {
            isAntiAlias = true
            mode = org.jetbrains.skia.PaintMode.STROKE
            strokeWidth = width.toFloat()
            strokeCap = org.jetbrains.skia.PaintStrokeCap.BUTT
            strokeJoin = org.jetbrains.skia.PaintStrokeJoin.BEVEL
        }
        try {
            val rgba = Gezira.skiaRaster(w, h) { it.drawPath(p, paint) }
            return DoubleArray(w * h) { rgba[4 * it + 3].toDouble() }
        } finally {
            runCatching { p.close() }
        }
    }
}

/** One pixel's worth of paint: premultiplied colour and alpha, already scaled by coverage. */
data class Fragment(val x: Int, val y: Int, val r: Float, val g: Float, val b: Float, val a: Float)

/** A premultiplied RGBA colour in `[0, 1]`. */
data class Rgba(val r: Float, val g: Float, val b: Float, val a: Float) {
    companion object {
        /** A straight (not premultiplied) colour, premultiplied. */
        fun of(r: Double, g: Double, b: Double, a: Double = 1.0) =
            Rgba((r * a).toFloat(), (g * a).toFloat(), (b * a).toFloat(), a.toFloat())
    }
}

/**
 * Gezira's paint end of the pipeline: a texture stage that turns coverage spans into fragments, and
 * a compositing kernel that blends them into a canvas.
 *
 * ```
 * canvas.draw(beziers, GeziraComposite.texture { x, y -> ... }, GeziraComposite.OVER)
 * ```
 */
object GeziraComposite {

    /** A blend: source fragment and destination pixel (both premultiplied) to the new destination. */
    fun interface Blend {
        fun blend(s: Rgba, d: Rgba): Rgba
    }

    /** Porter–Duff source-over. */
    val OVER = Blend { s, d ->
        val k = 1 - s.a
        Rgba(s.r + d.r * k, s.g + d.g * k, s.b + d.b * k, s.a + d.a * k)
    }

    /** Multiply (the separable blend mode, with source-over alpha). */
    val MULTIPLY = Blend { s, d ->
        val ks = 1 - s.a
        val kd = 1 - d.a
        Rgba(
            s.r * d.r + s.r * kd + d.r * ks,
            s.g * d.g + s.g * kd + d.g * ks,
            s.b * d.b + s.b * kd + d.b * ks,
            s.a + d.a * ks,
        )
    }

    /** One colour everywhere. */
    fun color(c: Rgba): Stage<Span, Fragment> = texture { _, _ -> c }

    /**
     * Expand each span into one fragment per pixel, coloured by [paint] sampled at the pixel
     * centre and scaled by the span's coverage (Gezira's texture stage applied to coverage).
     */
    fun texture(paint: (Double, Double) -> Rgba): Stage<Span, Fragment> = kernel("Texture") {
        each { s ->
            for (x in s.x until s.x + s.length) {
                val c = paint(x + 0.5, s.y + 0.5)
                val k = s.coverage.toFloat()
                emit(Fragment(x, s.y, c.r * k, c.g * k, c.b * k, c.a * k))
            }
        }
    }

    /** A linear gradient from [a] at `(x0, y0)` to [b] at `(x1, y1)`, clamped at the ends. */
    fun linearGradient(x0: Double, y0: Double, x1: Double, y1: Double, a: Rgba, b: Rgba): (Double, Double) -> Rgba {
        val dx = x1 - x0
        val dy = y1 - y0
        val l2 = dx * dx + dy * dy
        return { x, y ->
            val t = (((x - x0) * dx + (y - y0) * dy) / l2).coerceIn(0.0, 1.0).toFloat()
            Rgba(a.r + t * (b.r - a.r), a.g + t * (b.g - a.g), a.b + t * (b.b - a.b), a.a + t * (b.a - a.a))
        }
    }

    /**
     * The compositing kernel: blend each fragment into [canvas] with [blend], and emit the pixel's
     * new value as a fragment, so the composited result is a stream too.
     */
    fun compositeOver(canvas: Canvas, blend: Blend = OVER): Stage<Fragment, Fragment> = kernel("Composite") {
        each { f ->
            if (f.x in 0 until canvas.w && f.y in 0 until canvas.h) {
                val o = blend.blend(Rgba(f.r, f.g, f.b, f.a), canvas[f.x, f.y])
                canvas[f.x, f.y] = o
                emit(Fragment(f.x, f.y, o.r, o.g, o.b, o.a))
            }
        }
    }
}

/** A premultiplied RGBA image that [GeziraComposite.compositeOver] paints into. */
class Canvas(val w: Int, val h: Int) {
    /** Premultiplied RGBA, row-major, y down. */
    val rgba = FloatArray(4 * w * h)

    operator fun get(x: Int, y: Int): Rgba {
        val i = 4 * (y * w + x)
        return Rgba(rgba[i], rgba[i + 1], rgba[i + 2], rgba[i + 3])
    }

    operator fun set(x: Int, y: Int, c: Rgba) {
        val i = 4 * (y * w + x)
        rgba[i] = c.r
        rgba[i + 1] = c.g
        rgba[i + 2] = c.b
        rgba[i + 3] = c.a
    }

    /**
     * Rasterise [beziers], paint them with [paint] and composite with [blend]: the whole Gezira
     * pipeline from curves to pixels. Returns the trace.
     */
    fun draw(
        beziers: List<Bezier>,
        paint: Stage<Span, Fragment>,
        blend: GeziraComposite.Blend = GeziraComposite.OVER,
    ): Trace = (Gezira.rasterizer then paint then GeziraComposite.compositeOver(this, blend)).trace(beziers)

    /** One filled square per painted pixel, `scale` units wide, in its colour. */
    fun pixels(scale: Double): List<FLine> = (0 until w * h).filter { rgba[4 * it + 3] > 0.002f }.map { i ->
        val x = (i % w) * scale
        val y = (i / w) * scale
        val a = rgba[4 * i + 3]
        FLine().moveTo(x, y).lineTo(x + scale, y).lineTo(x + scale, y + scale).lineTo(x, y + scale).lineTo(x, y).also {
            it.filled = true
            it.stroked = false
            it.color = vec(rgba[4 * i] / a, rgba[4 * i + 1] / a, rgba[4 * i + 2] / a, a)
        }
    }

    /** Largest and mean absolute channel difference from another premultiplied image, over pixels either paints. */
    fun error(other: FloatArray): Pair<Double, Double> {
        var worst = 0.0
        var sum = 0.0
        var n = 0
        for (i in 0 until w * h) {
            if (rgba[4 * i + 3] == 0f && other[4 * i + 3] == 0f) continue
            for (c in 0..3) {
                val d = abs(rgba[4 * i + c] - other[4 * i + c]).toDouble()
                worst = max(worst, d)
                sum += d
                n++
            }
        }
        return worst to (if (n == 0) 0.0 else sum / n)
    }

    /** The smaller of a pixel's alpha and 1, for tests. */
    fun alpha(x: Int, y: Int) = min(1f, rgba[4 * (y * w + x) + 3])
}
