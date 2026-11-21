package afield.graphics.nile

import afield.geometry.path.FLine
import afield.graphics.render.TriangleCoverage
import afield.graphics.render.color
import afield.graphics.render.filled
import afield.graphics.render.stroked
import afield.graphics.render.thickness
import afield.math.linalg.vec
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Path
import org.jetbrains.skia.PathFillMode
import org.jetbrains.skia.Surface
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A Bézier segment of degree 1 to 3, control points packed `x0, y0, x1, y1, ...` in pixel units.
 */
class Bezier(val p: DoubleArray) {
    init {
        require(p.size in 4..8 && p.size % 2 == 0) { "a Bezier has 2 to 4 points, got ${p.size / 2}" }
    }

    /** 1 for a line, 2 for a quadratic, 3 for a cubic. */
    val degree get() = p.size / 2 - 1

    /** The two parts either side of [t], by de Casteljau. */
    fun split(t: Double = 0.5): Pair<Bezier, Bezier> {
        val n = p.size / 2
        val left = DoubleArray(p.size)
        val right = DoubleArray(p.size)
        val w = p.copyOf()
        for (level in 0 until n) {
            left[2 * level] = w[0]
            left[2 * level + 1] = w[1]
            right[2 * (n - 1 - level)] = w[2 * (n - 1 - level)]
            right[2 * (n - 1 - level) + 1] = w[2 * (n - 1 - level) + 1]
            for (i in 0 until n - 1 - level) {
                w[2 * i] += t * (w[2 * i + 2] - w[2 * i])
                w[2 * i + 1] += t * (w[2 * i + 3] - w[2 * i + 1])
            }
        }
        return Bezier(left) to Bezier(right)
    }

    /** The point at [t], component [c] (0 for x, 1 for y). */
    fun at(t: Double, c: Int): Double {
        val w = DoubleArray(p.size / 2) { p[2 * it + c] }
        for (level in w.size - 1 downTo 1) for (i in 0 until level) w[i] = w[i] + t * (w[i + 1] - w[i])
        return w[0]
    }

    /** The derivative at [t], component [c]. */
    fun d(t: Double, c: Int): Double {
        val n = degree
        val w = DoubleArray(n) { n * (p[2 * (it + 1) + c] - p[2 * it + c]) }
        for (level in w.size - 1 downTo 1) for (i in 0 until level) w[i] = w[i] + t * (w[i + 1] - w[i])
        return w[0]
    }

    fun minX() = (0 until p.size / 2).minOf { p[2 * it] }
    fun maxX() = (0 until p.size / 2).maxOf { p[2 * it] }
    fun minY() = (0 until p.size / 2).minOf { p[2 * it + 1] }
    fun maxY() = (0 until p.size / 2).maxOf { p[2 * it + 1] }

    override fun toString() = "Bezier(${p.joinToString { "%.3f".format(it) }})"
}

/**
 * One curve piece's contribution to the pixel `(x, y)` it lies in.
 *
 * [height] is how far the piece descends, `y1 - y0` (signed); every pixel to the right of this one in
 * the row is covered by that much. [area] is the part of this pixel to the right of the piece,
 * signed the same way.
 */
data class EdgeSample(val x: Int, val y: Int, val area: Double, val height: Double)

/** A run of [length] pixels starting at `(x, y)`, all covered by [coverage] in `[0, 1]`. */
data class Span(val x: Int, val y: Int, val length: Int, val coverage: Double)

/**
 * The Gezira rasteriser written as Nile-style kernels:
 * [decomposeBeziers] → [edgeSamples] → [sortX] → [sortY] → [combineEdgeSamples].
 *
 * Gezira fuses decomposition and the edge sample into one kernel; here they are two, so the pieces
 * a curve was cut into are a stream a box can draw. Inside one pixel the piece's area to the right is
 * integrated exactly (three-point Gauss–Legendre is exact for the degree-5 integrand of a cubic), so
 * a curve is not flattened to its chord.
 */
object Gezira {

    /** Below this hull size a piece straddling a pixel edge is emitted rather than split again. */
    const val TINY = 1e-9

    private val GL_T = doubleArrayOf(0.5 - 0.5 * sqrt(0.6), 0.5, 0.5 + 0.5 * sqrt(0.6))
    private val GL_W = doubleArrayOf(5.0 / 18, 8.0 / 18, 5.0 / 18)

    /** True when the control hull of [b] lies in one pixel, so the curve does too. */
    fun fitsOnePixel(b: Bezier): Boolean {
        val cx = floor(b.minX())
        val cy = floor(b.minY())
        return b.maxX() <= cx + 1 && b.maxY() <= cy + 1
    }

    /**
     * Where to cut [b]: at a pixel edge its endpoints lie on opposite sides of, found by bisecting
     * on `t`, with the cut point set exactly onto the edge; otherwise in half. Cutting at pixel edges
     * keeps the number of pieces near the number of pixels the curve crosses; halving alone would
     * home in on each crossing for ~30 levels.
     */
    fun cut(b: Bezier): Pair<Bezier, Bezier> {
        val n = b.p.size
        for (c in 0..1) {
            val a0 = b.p[c]
            val a1 = b.p[n - 2 + c]
            var g = ceil(min(a0, a1))
            if (g == min(a0, a1)) g += 1.0
            if (g >= max(a0, a1)) continue
            var lo = 0.0
            var hi = 1.0
            val rising = a1 > a0
            repeat(60) {
                val mid = 0.5 * (lo + hi)
                if ((b.at(mid, c) < g) == rising) lo = mid else hi = mid
            }
            val (l, r) = b.split(0.5 * (lo + hi))
            l.p[n - 2 + c] = g
            r.p[c] = g
            return l to r
        }
        return b.split()
    }

    /** Cut each curve, pushing both parts back, until each piece fits one pixel. */
    val decomposeBeziers: Stage<Bezier, Bezier> = kernel("DecomposeBeziers") {
        each { b ->
            val tiny = max(b.maxX() - b.minX(), b.maxY() - b.minY()) < TINY
            if (fitsOnePixel(b) || tiny) {
                emit(b)
            } else {
                val (l, r) = cut(b)
                push(r)
                push(l)
            }
        }
    }

    /** The edge sample of a piece that fits one pixel. */
    fun edgeSample(b: Bezier): EdgeSample {
        val px = floor(0.5 * (b.minX() + b.maxX()))
        val py = floor(0.5 * (b.minY() + b.maxY()))
        var area = 0.0
        for (k in 0..2) area += GL_W[k] * (px + 1 - b.at(GL_T[k], 0)) * b.d(GL_T[k], 1)
        val n = b.p.size
        return EdgeSample(px.toInt(), py.toInt(), area, b.p[n - 1] - b.p[1])
    }

    /** One [EdgeSample] per piece; horizontal pieces, which cover nothing, are dropped. */
    val edgeSamples: Stage<Bezier, EdgeSample> = kernel("EdgeSamples") {
        each { b ->
            val s = edgeSample(b)
            if (s.height != 0.0 || s.area != 0.0) emit(s)
        }
    }

    /** Gezira's `SortBy x`. */
    val sortX: Stage<EdgeSample, EdgeSample> = sortBy("SortBy x") { it.x }

    /** Gezira's `SortBy y`, stable, so samples end up by row and then by x. */
    val sortY: Stage<EdgeSample, EdgeSample> = sortBy("SortBy y") { it.y }

    /**
     * Walk the sorted samples a row at a time, adding up each pixel's samples and carrying the sum of
     * heights to the right, and emit a [Span] for each sampled pixel and each gap between them.
     * Coverage is the absolute winding area, clamped to 1 (non-zero fill).
     */
    val combineEdgeSamples: Stage<EdgeSample, Span> = kernel("CombineEdgeSamples") {
        var row = Int.MIN_VALUE
        var x = 0
        var area = 0.0
        var height = 0.0
        var carry = 0.0
        fun flushPixel(scope: KernelScope<EdgeSample, Span>) {
            scope.emit(Span(x, row, 1, min(1.0, abs(carry + area))))
            carry += height
        }
        each { s ->
            if (s.y != row) {
                if (row != Int.MIN_VALUE) flushPixel(this)
                row = s.y
                x = s.x
                area = 0.0
                height = 0.0
                carry = 0.0
            } else if (s.x != x) {
                flushPixel(this)
                val gap = s.x - x - 1
                val c = min(1.0, abs(carry))
                if (gap > 0 && c > 1e-12) emit(Span(x + 1, row, gap, c))
                x = s.x
                area = 0.0
                height = 0.0
            }
            area += s.area
            height += s.height
        }
        end { if (row != Int.MIN_VALUE) flushPixel(this) }
    }

    /** The whole rasteriser. */
    val rasterizer: Stage<Bezier, Span> =
        decomposeBeziers then edgeSamples then sortX then sortY then combineEdgeSamples

    /** Spans painted into a `w × h` coverage grid, row-major, y down; spans outside are clipped. */
    fun coverage(spans: List<Span>, w: Int, h: Int): DoubleArray {
        val c = DoubleArray(w * h)
        for (s in spans) {
            if (s.y !in 0 until h) continue
            for (x in max(0, s.x) until min(w, s.x + s.length)) c[s.y * w + x] = s.coverage
        }
        return c
    }

    /** Run [rasterizer] over [beziers] into a `w × h` coverage grid. */
    fun rasterize(beziers: List<Bezier>, w: Int, h: Int): DoubleArray = coverage(rasterizer(beziers), w, h)

    /** The segments of [line] as [Bezier]s, each subpath closed. Only x and y are used. */
    fun beziers(line: FLine): List<Bezier> {
        val out = ArrayList<Bezier>()
        var sx = 0.0
        var sy = 0.0
        var cx = 0.0
        var cy = 0.0
        var open = false
        fun close() {
            if (open && (cx != sx || cy != sy)) out.add(Bezier(doubleArrayOf(cx, cy, sx, sy)))
            open = false
        }
        for (node in line.nodes) {
            when (node) {
                is FLine.MoveTo -> {
                    close()
                    sx = node.storage[0].x.toDouble()
                    sy = node.storage[0].y.toDouble()
                    cx = sx
                    cy = sy
                    open = true
                }

                is FLine.LineTo -> {
                    val x = node.storage[0].x.toDouble()
                    val y = node.storage[0].y.toDouble()
                    out.add(Bezier(doubleArrayOf(cx, cy, x, y)))
                    cx = x
                    cy = y
                }

                is FLine.CubicTo -> {
                    val x = node.storage[0].x.toDouble()
                    val y = node.storage[0].y.toDouble()
                    out.add(
                        Bezier(
                            doubleArrayOf(
                                cx, cy,
                                node.c1.x.toDouble(), node.c1.y.toDouble(),
                                node.c2.x.toDouble(), node.c2.y.toDouble(),
                                x, y,
                            ),
                        ),
                    )
                    cx = x
                    cy = y
                }

                is FLine.Close -> close()
                else -> {}
            }
        }
        close()
        return out
    }

    /**
     * The segments of a `java.awt.Shape` as [Bezier]s, quadratics kept as quadratics (glyph outlines
     * are made of them, as the tiger's Gezira input is), each subpath closed.
     */
    fun beziers(shape: java.awt.Shape): List<Bezier> {
        val out = ArrayList<Bezier>()
        val it = shape.getPathIterator(null)
        val c = DoubleArray(6)
        var sx = 0.0
        var sy = 0.0
        var cx = 0.0
        var cy = 0.0
        fun close() {
            if (cx != sx || cy != sy) out.add(Bezier(doubleArrayOf(cx, cy, sx, sy)))
            cx = sx
            cy = sy
        }
        while (!it.isDone) {
            when (it.currentSegment(c)) {
                java.awt.geom.PathIterator.SEG_MOVETO -> {
                    close()
                    sx = c[0]
                    sy = c[1]
                    cx = sx
                    cy = sy
                }

                java.awt.geom.PathIterator.SEG_LINETO -> {
                    out.add(Bezier(doubleArrayOf(cx, cy, c[0], c[1])))
                    cx = c[0]
                    cy = c[1]
                }

                java.awt.geom.PathIterator.SEG_QUADTO -> {
                    out.add(Bezier(doubleArrayOf(cx, cy, c[0], c[1], c[2], c[3])))
                    cx = c[2]
                    cy = c[3]
                }

                java.awt.geom.PathIterator.SEG_CUBICTO -> {
                    out.add(Bezier(doubleArrayOf(cx, cy, c[0], c[1], c[2], c[3], c[4], c[5])))
                    cx = c[4]
                    cy = c[5]
                }

                java.awt.geom.PathIterator.SEG_CLOSE -> close()
            }
            it.next()
        }
        close()
        return out
    }

    /** [text] set in [font] (size in pixels) as quadratic glyph outlines, baseline at `(x, y)`. */
    fun glyphs(text: String, x: Double, y: Double, size: Double = 32.0, font: String = java.awt.Font.SERIF): List<Bezier> {
        val f = java.awt.Font(font, java.awt.Font.PLAIN, 1).deriveFont(size.toFloat())
        val frc = java.awt.font.FontRenderContext(null, true, true)
        return beziers(f.createGlyphVector(frc, text).getOutline(x.toFloat(), y.toFloat()))
    }

    /** A circle as four cubics (the usual kappa approximation, within 0.03 % of the radius). */
    fun circle(cx: Double, cy: Double, r: Double): List<Bezier> {
        val k = 0.5522847498 * r
        return listOf(
            doubleArrayOf(cx + r, cy, cx + r, cy + k, cx + k, cy + r, cx, cy + r),
            doubleArrayOf(cx, cy + r, cx - k, cy + r, cx - r, cy + k, cx - r, cy),
            doubleArrayOf(cx - r, cy, cx - r, cy - k, cx - k, cy - r, cx, cy - r),
            doubleArrayOf(cx, cy - r, cx + k, cy - r, cx + r, cy - k, cx + r, cy),
        ).map { Bezier(it) }
    }

    /**
     * Exact coverage of a simple polygon (`x0, y0, x1, y1, ...`) by fanning it into triangles from
     * its first vertex and adding each triangle's [TriangleCoverage.boxCoverage] with the sign of its
     * winding. The reference the kernel pipeline is measured against.
     */
    fun exactPolygonCoverage(poly: DoubleArray, w: Int, h: Int): DoubleArray {
        val c = DoubleArray(w * h)
        val n = poly.size / 2
        for (i in 1 until n - 1) {
            val ax = poly[0]
            val ay = poly[1]
            val bx = poly[2 * i]
            val by = poly[2 * i + 1]
            val qx = poly[2 * i + 2]
            val qy = poly[2 * i + 3]
            val sign = Math.signum(TriangleCoverage.twiceSignedArea(ax, ay, bx, by, qx, qy))
            if (sign == 0.0) continue
            val x0 = max(0, floor(min(ax, min(bx, qx))).toInt())
            val x1 = min(w, ceil(max(ax, max(bx, qx))).toInt())
            val y0 = max(0, floor(min(ay, min(by, qy))).toInt())
            val y1 = min(h, ceil(max(ay, max(by, qy))).toInt())
            for (y in y0 until y1) for (x in x0 until x1) {
                c[y * w + x] += sign * TriangleCoverage.boxCoverage(
                    ax, ay, bx, by, qx, qy, x.toDouble(), y.toDouble(), x + 1.0, y + 1.0,
                )
            }
        }
        for (i in c.indices) c[i] = min(1.0, abs(c[i]))
        return c
    }

    /** [beziers] as a Skia path, a new contour wherever a segment does not start where the last ended. */
    fun skiaPath(beziers: List<Bezier>): Path {
        val path = Path().apply { fillMode = PathFillMode.WINDING }
        var lastX = Double.NaN
        var lastY = Double.NaN
        for (b in beziers) {
            val p = b.p
            if (p[0] != lastX || p[1] != lastY) {
                if (!lastX.isNaN()) path.closePath()
                path.moveTo(p[0].toFloat(), p[1].toFloat())
            }
            when (b.degree) {
                1 -> path.lineTo(p[2].toFloat(), p[3].toFloat())
                2 -> path.quadTo(p[2].toFloat(), p[3].toFloat(), p[4].toFloat(), p[5].toFloat())
                else -> path.cubicTo(
                    p[2].toFloat(), p[3].toFloat(), p[4].toFloat(), p[5].toFloat(), p[6].toFloat(), p[7].toFloat(),
                )
            }
            lastX = p[p.size - 2]
            lastY = p[p.size - 1]
        }
        path.closePath()
        return path
    }

    /** Draw into a `w × h` Skia raster with [draw] and read back premultiplied RGBA in `[0, 1]`. */
    fun skiaRaster(w: Int, h: Int, draw: (org.jetbrains.skia.Canvas) -> Unit): FloatArray {
        val surface = Surface.makeRasterN32Premul(w, h)
        val bitmap = Bitmap()
        try {
            surface.canvas.clear(Color.TRANSPARENT)
            draw(surface.canvas)
            bitmap.allocPixels(ImageInfo.makeN32Premul(w, h))
            check(surface.readPixels(bitmap, 0, 0)) { "Skia declined to read the raster back" }
            val out = FloatArray(4 * w * h)
            for (i in 0 until w * h) {
                // getColor un-premultiplies; premultiply again so the result compares with Canvas.
                val c = bitmap.getColor(i % w, i / w)
                val a = Color.getA(c) / 255f
                out[4 * i] = Color.getR(c) / 255f * a
                out[4 * i + 1] = Color.getG(c) / 255f * a
                out[4 * i + 2] = Color.getB(c) / 255f * a
                out[4 * i + 3] = a
            }
            return out
        } finally {
            runCatching { bitmap.close() }
            runCatching { surface.close() }
        }
    }

    /** Skia's anti-aliased coverage of [beziers] (non-zero fill), read back from an 8-bit alpha. */
    fun skiaCoverage(beziers: List<Bezier>, w: Int, h: Int): DoubleArray {
        val path = skiaPath(beziers)
        try {
            val rgba = skiaRaster(w, h) { it.drawPath(path, Paint().apply { isAntiAlias = true; color = Color.BLACK }) }
            return DoubleArray(w * h) { rgba[4 * it + 3].toDouble() }
        } finally {
            runCatching { path.close() }
        }
    }

    /**
     * Non-zero coverage by point sampling an `n × n` grid in every pixel: each sample row finds where
     * the outline (each curve flattened to [chords] chords) crosses it and fills where the winding
     * number is not zero. Slow, but it measures the *union* of overlapping outlines, which a sum of
     * signed edge areas does not.
     */
    fun supersampledCoverage(beziers: List<Bezier>, w: Int, h: Int, n: Int = 64, chords: Int = 256): DoubleArray {
        val seg = ArrayList<DoubleArray>()
        for (b in beziers) {
            val k = if (b.degree == 1) 1 else chords
            var px = b.p[0]
            var py = b.p[1]
            for (i in 1..k) {
                val t = i.toDouble() / k
                val x = b.at(t, 0)
                val y = b.at(t, 1)
                seg.add(doubleArrayOf(px, py, x, y))
                px = x
                py = y
            }
        }
        val c = DoubleArray(w * h)
        val xs = ArrayList<Pair<Double, Int>>()
        for (row in 0 until h * n) {
            val y = (row + 0.5) / n
            xs.clear()
            for (s in seg) {
                val (x0, y0, x1, y1) = s.toList()
                if ((y0 <= y) == (y1 <= y)) continue
                xs.add((x0 + (y - y0) / (y1 - y0) * (x1 - x0)) to (if (y1 > y0) 1 else -1))
            }
            xs.sortBy { it.first }
            var wind = 0
            for (i in 0 until xs.size - 1) {
                wind += xs[i].second
                if (wind == 0) continue
                // Fill sample columns whose centres lie in [xs[i], xs[i+1]).
                val a = ceil(xs[i].first * n - 0.5).toInt()
                val b = ceil(xs[i + 1].first * n - 0.5).toInt()
                for (col in max(0, a) until min(w * n, b)) c[(row / n) * w + col / n] += 1.0 / (n * n)
            }
        }
        return c
    }

    /** Largest and mean absolute difference between two coverage grids, over pixels either touches. */
    fun error(a: DoubleArray, b: DoubleArray): Pair<Double, Double> {
        var worst = 0.0
        var sum = 0.0
        var n = 0
        for (i in a.indices) {
            if (a[i] == 0.0 && b[i] == 0.0) continue
            val d = abs(a[i] - b[i])
            worst = max(worst, d)
            sum += d
            n++
        }
        return worst to (if (n == 0) 0.0 else sum / n)
    }

    // ---- drawing the streams as FLines, `scale` screen units per pixel, y down ----

    /** One filled square per covered pixel of a `w × h` grid, its alpha the coverage. */
    fun pixels(coverage: DoubleArray, w: Int, scale: Double, r: Double = 1.0, g: Double = 1.0, b: Double = 1.0): List<FLine> =
        coverage.indices.filter { coverage[it] > 0.002 }.map { i ->
            val x = (i % w) * scale
            val y = (i / w) * scale
            FLine().moveTo(x, y).lineTo(x + scale, y).lineTo(x + scale, y + scale).lineTo(x, y + scale).lineTo(x, y).also {
                it.filled = true
                it.stroked = false
                it.color = vec(r, g, b, coverage[i])
            }
        }

    /** The pixel grid over `w × h` pixels, faint. */
    fun grid(w: Int, h: Int, scale: Double): FLine = FLine().also { f ->
        for (x in 0..w) f.moveTo(x * scale, 0.0).lineTo(x * scale, h * scale)
        for (y in 0..h) f.moveTo(0.0, y * scale).lineTo(w * scale, y * scale)
        f.color = vec(1.0, 1.0, 1.0, 0.18)
        f.thickness = 0.5
    }

    /** Each piece as a stroke, alternating two colours so the cuts show. */
    fun pieces(pieces: List<Bezier>, scale: Double): List<FLine> = pieces.mapIndexed { i, b ->
        val p = b.p.map { it * scale }
        FLine().moveTo(p[0], p[1]).also { f ->
            when (b.degree) {
                1 -> f.lineTo(p[2], p[3])
                2 -> f.cubicTo(
                    p[0] + 2.0 / 3 * (p[2] - p[0]), p[1] + 2.0 / 3 * (p[3] - p[1]),
                    p[4] + 2.0 / 3 * (p[2] - p[4]), p[5] + 2.0 / 3 * (p[3] - p[5]),
                    p[4], p[5],
                )

                else -> f.cubicTo(p[2], p[3], p[4], p[5], p[6], p[7])
            }
            f.color = if (i % 2 == 0) vec(1.0, 0.55, 0.15, 1.0) else vec(0.3, 0.75, 1.0, 1.0)
            f.thickness = 2.0
        }
    }

    /** Each piece's endpoints as small ticks, so every cut is visible. */
    fun cuts(pieces: List<Bezier>, scale: Double): FLine = FLine().also { f ->
        for (b in pieces) {
            val x = b.p[0] * scale
            val y = b.p[1] * scale
            f.moveTo(x - 2, y - 2).lineTo(x + 2, y + 2).moveTo(x - 2, y + 2).lineTo(x + 2, y - 2)
        }
        f.color = vec(1.0, 1.0, 1.0, 0.9)
        f.thickness = 1.0
    }

    /**
     * Each edge sample as a bar inside its pixel: its length is the sample's height, its colour its
     * sign (orange descending, blue ascending), its position its area.
     */
    fun samples(samples: List<EdgeSample>, scale: Double): List<FLine> = samples.map { s ->
        val x = (s.x + 1 - min(1.0, abs(s.area) / max(1e-9, abs(s.height)))) * scale
        val y0 = (s.y + 0.5 - 0.5 * abs(s.height)) * scale
        val y1 = (s.y + 0.5 + 0.5 * abs(s.height)) * scale
        FLine().moveTo(x, y0).lineTo(x, y1).also {
            it.color = if (s.height > 0) vec(1.0, 0.55, 0.15, 1.0) else vec(0.3, 0.75, 1.0, 1.0)
            it.thickness = 2.5
        }
    }

    /** Each span as a horizontal bar across its pixels, its alpha its coverage. */
    fun spans(spans: List<Span>, scale: Double): List<FLine> = spans.map { s ->
        val y = (s.y + 0.5) * scale
        FLine().moveTo(s.x * scale + 1, y).lineTo((s.x + s.length) * scale - 1, y).also {
            it.color = if (s.length == 1) vec(1.0, 0.85, 0.3, 0.3 + 0.7 * s.coverage) else vec(0.5, 1.0, 0.6, 0.3 + 0.7 * s.coverage)
            it.thickness = 0.35 * scale
        }
    }

    /** [line] scaled by [scale], drawn as a thin white outline. */
    fun outline(line: FLine, scale: Double): List<FLine> = pieces(beziers(line), scale).onEach {
        it.color = vec(1.0, 1.0, 1.0, 0.85)
        it.thickness = 1.0
    }
}
