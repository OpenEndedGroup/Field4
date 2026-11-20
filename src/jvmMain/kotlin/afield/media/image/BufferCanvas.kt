@file:afield.runtime.completion.CompletionCategory("Imaging")

package afield.media.image

import afield.geometry.path.ScalarField2D
import afield.media.image.util.PngEncoder
import afield.ui.visualization.ToHTML
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Base64
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Paint into the memory of a running program, and look at that memory unscaled.
 *
 * A [BufferCanvas] is a two-dimensional *window* onto storage some other code owns and keeps
 * using: a simulation's `FloatArray`, a delay line, a `FloatBuffer` or `ByteBuffer` read back from
 * (or about to be uploaded to) the GPU. It copies nothing. Every edit — [paint], [stroke],
 * [fill], [paste], [blur], [motionBlur], [smudge], [clone] — writes straight into that storage,
 * so the next tick of the owning program sees it.
 *
 * Element `(x, y)` channel `c` lives at `offset + y * rowStride + x * channels + c`, in elements of
 * the buffer's own type. Reads return the stored number itself: a byte buffer gives `0..255`, an
 * int buffer gives the integer, a float gives the float. Nothing is normalised on the way in.
 *
 * [toHTML] (what `.h` and evaluating the canvas in a box show) draws one buffer element as one
 * screen pixel, through the [ValueMap] you set on [map]. The default, [ValueMap.Raw], maps `0..1`
 * to black..white and *marks* rather than hides everything else: below zero is blue, above one is
 * red, NaN and infinities are magenta, and the caption counts each. To see a buffer whose numbers
 * live elsewhere, choose a range yourself with [ValueMap.Linear] or [ValueMap.Signed]; the caption
 * always names the map, so no picture is silently rescaled.
 *
 * ```
 * val u = FloatArray(128 * 128)                   // a simulation's state, used every frame
 * val canvas = bufferCanvas(u, width = 128)
 * canvas.paint(64.0, 64.0, radius = 6.0, value = 1f)
 * canvas.blur(CellRect(40, 40, 48, 48), sigma = 2.0)
 * canvas.h                                        // 128×128 pixels, one per float
 * ```
 *
 * With [wrapX]/[wrapY] edits that cross an edge continue on the far side, which is what a ring
 * buffer or a torus-topology simulation wants. Without them, edits clip at the edge.
 *
 * Credit: the idea comes from "Paint in the memory of other programs",
 * citrons, 2026-04-13 (https://citrons.xyz/journal/2026-04-13).
 */
class BufferCanvas(
    /** Elements across one row. */
    val width: Int,
    /** Rows. */
    val height: Int,
    /** Interleaved values per element: 1 (scalar), 2, 3 or 4 (`vec4`). */
    val channels: Int,
    /** Index of element `(0, 0)` channel 0 in the underlying storage. */
    val offset: Int,
    /** Storage elements between the start of one row and the next; at least `width * channels`. */
    val rowStride: Int,
    private val read: (Int) -> Float,
    private val write: (Int, Float) -> Unit,
    /** Edits crossing the left/right edge continue on the other side. */
    var wrapX: Boolean = false,
    /** Edits crossing the top/bottom edge continue on the other side. */
    var wrapY: Boolean = false,
) : ToHTML {

    init {
        require(width >= 1 && height >= 1) { "BufferCanvas must be at least 1x1, got ${width}x$height" }
        require(channels in 1..4) { "channels must be 1..4, got $channels" }
        require(rowStride >= width * channels) { "rowStride $rowStride is shorter than a row (${width * channels})" }
        require(offset >= 0) { "offset must be >= 0, got $offset" }
    }

    /** How [toHTML] turns stored numbers into pixels. Changing it never touches the buffer. */
    var map: ValueMap = ValueMap.Raw

    /** On-screen pixels per element in [toHTML]; whole numbers only, so pixels stay square. */
    var zoom: Int = 1

    /** Which channel each display colour comes from, for `channels >= 2`; `-1` means none. */
    var display: IntArray = when (channels) {
        1 -> intArrayOf(0, 0, 0)
        2 -> intArrayOf(0, 1, -1)
        else -> intArrayOf(0, 1, 2)
    }

    /** Storage index of `(x, y)` channel [c], after wrapping; `-1` when outside and not wrapping. */
    fun index(x: Int, y: Int, c: Int = 0): Int {
        val xx = if (wrapX) Math.floorMod(x, width) else x
        val yy = if (wrapY) Math.floorMod(y, height) else y
        if (xx !in 0 until width || yy !in 0 until height || c !in 0 until channels) return -1
        return offset + yy * rowStride + xx * channels + c
    }

    /** The stored value at `(x, y)` channel [c]; [Float.NaN] outside a non-wrapping canvas. */
    operator fun get(x: Int, y: Int, c: Int = 0): Float {
        val i = index(x, y, c)
        return if (i < 0) Float.NaN else read(i)
    }

    /** Write [v] at `(x, y)` channel [c]; ignored outside a non-wrapping canvas. */
    operator fun set(x: Int, y: Int, c: Int, v: Float) {
        val i = index(x, y, c)
        if (i >= 0) write(i, v)
    }

    /** Write [v] at `(x, y)`, channel 0. */
    operator fun set(x: Int, y: Int, v: Float) = set(x, y, 0, v)

    /** The whole canvas as a rectangle. */
    val bounds: CellRect get() = CellRect(0, 0, width, height)

    // ---- reading ---------------------------------------------------------------------------

    /**
     * A copy of [r] as an [Img] — the clipboard type. Values are the stored numbers, unscaled.
     * [Img] holds 1, 3 or 4 channels, so a 2-channel canvas copies as 3 with the third zero.
     */
    fun copy(r: CellRect = bounds): Img {
        val outCh = if (channels == 2) 3 else channels
        val data = FloatArray(r.w * r.h * outCh)
        for (y in 0 until r.h) for (x in 0 until r.w) for (c in 0 until channels) {
            data[(y * r.w + x) * outCh + c] = get(r.x + x, r.y + y, c)
        }
        return Img(r.w, r.h, outCh, data)
    }

    /** Smallest and largest finite stored value over [r], all channels; `null` if none is finite. */
    fun range(r: CellRect = bounds): ClosedFloatingPointRange<Float>? {
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        forEach(r) { _, _, c, v -> if (c < channels && v.isFinite()) { lo = min(lo, v); hi = max(hi, v) } }
        return if (lo <= hi) lo..hi else null
    }

    /** Sum of channel [c] over [r]; a cheap way to check that an edit conserved (or changed) mass. */
    fun sum(r: CellRect = bounds, c: Int = 0): Double {
        var s = 0.0
        for (y in r.y until r.y + r.h) for (x in r.x until r.x + r.w) {
            val v = get(x, y, c)
            if (v.isFinite()) s += v
        }
        return s
    }

    // ---- editing ---------------------------------------------------------------------------

    /**
     * A round brush dab at `(cx, cy)` (element coordinates, fractional allowed): each touched
     * element moves toward [value] by `opacity * falloff`, where falloff is 1 inside
     * `radius * hardness` and eases to 0 at [radius]. [value] holds one number per channel; a
     * shorter array repeats its last entry. Pass [channel] to touch only that channel.
     */
    fun paint(
        cx: Double, cy: Double, radius: Double, value: FloatArray,
        opacity: Double = 1.0, hardness: Double = 0.5, channel: Int = -1,
    ) {
        require(radius > 0) { "radius must be > 0, got $radius" }
        val inner = radius * hardness.coerceIn(0.0, 1.0)
        val x0 = (cx - radius).toInt() - 1
        val x1 = (cx + radius).toInt() + 1
        val y0 = (cy - radius).toInt() - 1
        val y1 = (cy + radius).toInt() + 1
        for (y in y0..y1) for (x in x0..x1) {
            val d = hypot(x + 0.5 - cx, y + 0.5 - cy)
            if (d > radius) continue
            val f = if (d <= inner) 1.0 else 1.0 - (d - inner) / (radius - inner)
            val a = (opacity * f).toFloat()
            for (c in 0 until channels) {
                if (channel >= 0 && c != channel) continue
                val i = index(x, y, c)
                if (i < 0) continue
                val target = value[min(c, value.size - 1)]
                write(i, read(i) + (target - read(i)) * a)
            }
        }
    }

    /** [paint] with a single value for every channel. */
    fun paint(cx: Double, cy: Double, radius: Double, value: Float, opacity: Double = 1.0, hardness: Double = 0.5, channel: Int = -1) =
        paint(cx, cy, radius, floatArrayOf(value), opacity, hardness, channel)

    /**
     * Dabs along a polyline of `(x, y)` points, spaced a quarter radius apart so the stroke is
     * continuous; [opacity] applies per dab, so overlapping dabs build up as a real brush does.
     */
    fun stroke(points: List<Pair<Double, Double>>, radius: Double, value: FloatArray, opacity: Double = 0.5, hardness: Double = 0.5, channel: Int = -1) {
        if (points.isEmpty()) return
        val step = max(0.25, radius * 0.25)
        paint(points[0].first, points[0].second, radius, value, opacity, hardness, channel)
        for (k in 1 until points.size) {
            val (ax, ay) = points[k - 1]
            val (bx, by) = points[k]
            val n = max(1, (hypot(bx - ax, by - ay) / step).toInt())
            for (s in 1..n) {
                val t = s.toDouble() / n
                paint(ax + (bx - ax) * t, ay + (by - ay) * t, radius, value, opacity, hardness, channel)
            }
        }
    }

    /** Set every element of [r] to [value] (one number per channel, last entry repeated). */
    fun fill(r: CellRect, value: FloatArray, channel: Int = -1) {
        for (y in r.y until r.y + r.h) for (x in r.x until r.x + r.w) for (c in 0 until channels) {
            if (channel >= 0 && c != channel) continue
            set(x, y, c, value[min(c, value.size - 1)])
        }
    }

    /** [fill] with a single value. */
    fun fill(r: CellRect, value: Float, channel: Int = -1) = fill(r, floatArrayOf(value), channel)

    /**
     * Write a scalar field into [channel], one sample per element: element `(x, y)` gets
     * `field(x0 + (x + 0.5) * cell, y0 + (y + 0.5) * cell)`, the field at the element's centre.
     *
     * This is how a drawn outline becomes something a shader can read. Give it the signed
     * distance of an `FLine` (`line.signedDistance2D()`, or `lines.signedDistance2D()` for several)
     * and a canvas over a mapped GPU buffer, and every cell holds its distance to the shape in the
     * field's own units, negative inside. Divide by [cell] in the shader for distances in cells.
     *
     * Only elements inside [r] are written; nothing else in the buffer changes.
     *
     * ```
     * val wall = bufferCanvas(memory, width = 256, height = 256)
     * wall.sample(outline.signedDistance2D(), x0 = 0.0, y0 = 0.0, cell = 1.0)
     * ```
     */
    fun sample(field: ScalarField2D, x0: Double, y0: Double, cell: Double, channel: Int = 0, r: CellRect = bounds) {
        require(cell > 0) { "cell must be > 0, got $cell" }
        require(channel in 0 until channels) { "channel $channel is not in 0 until $channels" }
        for (y in r.y until r.y + r.h) for (x in r.x until r.x + r.w) {
            val i = index(x, y, channel)
            if (i >= 0) write(i, field(x0 + (x + 0.5) * cell, y0 + (y + 0.5) * cell).toFloat())
        }
    }

    /**
     * Write [image] with its top-left at `(x, y)`, mixing `opacity` of it over what is there.
     * Values go in unscaled: an [Img] copied from this canvas pastes back to the same numbers.
     */
    fun paste(image: Img, x: Int, y: Int, opacity: Double = 1.0) {
        val a = opacity.toFloat()
        for (j in 0 until image.height) for (i in 0 until image.width) for (c in 0 until channels) {
            if (c >= image.channels) continue
            val idx = index(x + i, y + j, c)
            if (idx < 0) continue
            val v = image.data[(j * image.width + i) * image.channels + c]
            write(idx, if (a >= 1f) v else read(idx) + (v - read(idx)) * a)
        }
    }

    /** Copy [from] and paste it at `(toX, toY)` — the clone stamp, rectangular. */
    fun clone(from: CellRect, toX: Int, toY: Int, opacity: Double = 1.0) = paste(copy(from), toX, toY, opacity)

    /**
     * Gaussian blur of [r] in place. Samples just outside [r] feed the blur (wrapping if the
     * canvas wraps) so the region's edge does not darken; only elements inside [r] are written.
     */
    fun blur(r: CellRect = bounds, sigma: Double) {
        require(sigma > 0) { "sigma must be > 0, got $sigma" }
        val k = gaussian(sigma)
        convolveLine(r, k, 1, 0)
        convolveLine(r, k, 0, 1)
    }

    /**
     * Motion blur of [r] in place: each element becomes the mean of [length] samples along the
     * direction `(dx, dy)` (normalised), centred on it — a camera moving during the exposure.
     * A ring buffer viewed with `wrapX = true` and `dy = 0` gets a one-dimensional smear in time.
     */
    fun motionBlur(r: CellRect = bounds, length: Int, dx: Double = 1.0, dy: Double = 0.0) {
        require(length >= 1) { "length must be >= 1, got $length" }
        val n = hypot(dx, dy)
        require(n > 0) { "direction must be non-zero" }
        val ux = dx / n
        val uy = dy / n
        val src = Array(channels) { c -> FloatArray(r.w * r.h) }
        val half = (length - 1) / 2.0
        for (y in 0 until r.h) for (x in 0 until r.w) for (c in 0 until channels) {
            var s = 0.0
            var cnt = 0
            for (t in 0 until length) {
                val o = t - half
                val v = get((r.x + x + ux * o).roundToInt(), (r.y + y + uy * o).roundToInt(), c)
                if (v.isFinite()) { s += v; cnt++ }
            }
            src[c][y * r.w + x] = if (cnt == 0) get(r.x + x, r.y + y, c) else (s / cnt).toFloat()
        }
        for (y in 0 until r.h) for (x in 0 until r.w) for (c in 0 until channels) set(r.x + x, r.y + y, c, src[c][y * r.w + x])
    }

    /**
     * Drag what is under the brush from `(fromX, fromY)` to `(toX, toY)`, as a finger smears wet
     * paint. The brush picks up a [radius]-sized patch where it starts and carries it, laying it
     * down with [strength] (times a falloff to the rim) at every step, while taking up [pickup] of
     * what it passes over, so the carried colour fades into the colours along the way.
     */
    fun smudge(
        fromX: Double, fromY: Double, toX: Double, toY: Double, radius: Double,
        strength: Double = 0.6, pickup: Double = 0.1,
    ) {
        val steps = max(1, (hypot(toX - fromX, toY - fromY) / max(0.5, radius * 0.25)).toInt())
        val ri = radius.toInt() + 1
        val side = 2 * ri + 1
        val held = copy(CellRect(fromX.toInt() - ri, fromY.toInt() - ri, side, side))
        val p = pickup.toFloat()
        for (s in 1..steps) {
            val ox = (fromX + (toX - fromX) * s / steps).toInt() - ri
            val oy = (fromY + (toY - fromY) * s / steps).toInt() - ri
            for (j in 0 until side) for (i in 0 until side) {
                val d = hypot(i - ri.toDouble(), j - ri.toDouble())
                if (d > radius) continue
                val a = (strength * (1.0 - d / radius)).toFloat()
                for (c in 0 until channels) {
                    val hi = (j * side + i) * held.channels + c
                    val idx = index(ox + i, oy + j, c)
                    if (idx < 0 || !held.data[hi].isFinite()) continue
                    val under = read(idx)
                    write(idx, under + (held.data[hi] - under) * a)
                    if (under.isFinite()) held.data[hi] += (under - held.data[hi]) * p
                }
            }
        }
    }

    /** Apply [f] to every element of [r] in place: `f(x, y, channel, value)` returns the new value. */
    fun mapInPlace(r: CellRect = bounds, f: (Int, Int, Int, Float) -> Float) {
        for (y in r.y until r.y + r.h) for (x in r.x until r.x + r.w) for (c in 0 until channels) {
            val i = index(x, y, c)
            if (i >= 0) write(i, f(x, y, c, read(i)))
        }
    }

    private inline fun forEach(r: CellRect, f: (Int, Int, Int, Float) -> Unit) {
        for (y in r.y until r.y + r.h) for (x in r.x until r.x + r.w) for (c in 0 until channels) {
            val i = index(x, y, c)
            if (i >= 0) f(x, y, c, read(i))
        }
    }

    private fun gaussian(sigma: Double): FloatArray {
        val rad = max(1, (sigma * 3).toInt())
        val k = FloatArray(2 * rad + 1) { exp(-((it - rad) * (it - rad)) / (2 * sigma * sigma)).toFloat() }
        val s = k.sum()
        for (i in k.indices) k[i] /= s
        return k
    }

    private fun convolveLine(r: CellRect, k: FloatArray, dx: Int, dy: Int) {
        val rad = k.size / 2
        val out = FloatArray(r.w * r.h * channels)
        for (y in 0 until r.h) for (x in 0 until r.w) for (c in 0 until channels) {
            var s = 0f
            var wsum = 0f
            for (t in k.indices) {
                val o = t - rad
                val v = get(r.x + x + dx * o, r.y + y + dy * o, c)
                if (v.isFinite()) { s += v * k[t]; wsum += k[t] }
            }
            out[(y * r.w + x) * channels + c] = if (wsum > 0f) s / wsum else get(r.x + x, r.y + y, c)
        }
        for (y in 0 until r.h) for (x in 0 until r.w) for (c in 0 until channels) set(r.x + x, r.y + y, c, out[(y * r.w + x) * channels + c])
    }

    // ---- looking ---------------------------------------------------------------------------

    /** Counts of samples [map] could not show as-is, from the last [pixels] call. */
    data class Clipped(val below: Int, val above: Int, val nonFinite: Int)

    /** The last [pixels] call's clip counts. */
    var lastClipped: Clipped = Clipped(0, 0, 0)
        private set

    /** ARGB pixels, one per element, through [map]. Records [lastClipped]. */
    fun pixels(): IntArray {
        val px = IntArray(width * height)
        var below = 0
        var above = 0
        var bad = 0
        for (y in 0 until height) for (x in 0 until width) {
            var rgb = 0
            var flag = 0 // 1 below, 2 above, 3 non-finite
            for (k in 0 until 3) {
                val c = display[k]
                val v = if (c in 0 until channels) get(x, y, c) else 0f
                val m = map.apply(v)
                val f = when {
                    !m.isFinite() -> { flag = 3; 0.0 }
                    m < 0.0 -> { if (flag == 0) flag = 1; 0.0 }
                    m > 1.0 -> { if (flag == 0) flag = 2; 1.0 }
                    else -> m
                }
                rgb = (rgb shl 8) or (f * 255.0).roundToInt().coerceIn(0, 255)
            }
            when (flag) {
                1 -> { below++; rgb = 0x2050ff }
                2 -> { above++; rgb = 0xff3020 }
                3 -> { bad++; rgb = 0xff00ff }
            }
            px[y * width + x] = (0xff shl 24) or rgb
        }
        lastClipped = Clipped(below, above, bad)
        return px
    }

    /** PNG bytes of [pixels], one pixel per element. */
    fun png(): ByteArray = PngEncoder.encode(pixels(), width, height)

    /** A one-line description: shape, layout, map and clip counts. */
    fun caption(): String {
        val r = range()
        val cl = lastClipped
        return buildString {
            append("${width}×$height × $channels ch, stride $rowStride, offset $offset · ")
            append("stored ${r?.let { "${fmt(it.start)} … ${fmt(it.endInclusive)}" } ?: "no finite values"} · ")
            append("map ${map.describe()}")
            if (cl.below > 0) append(" · ${cl.below} below (blue)")
            if (cl.above > 0) append(" · ${cl.above} above (red)")
            if (cl.nonFinite > 0) append(" · ${cl.nonFinite} NaN/∞ (magenta)")
        }
    }

    override fun toHTML(): String {
        val b64 = Base64.getEncoder().encodeToString(png())
        val z = zoom.coerceAtLeast(1)
        return "<figure class='buffer-canvas' style='margin:0'>" +
            "<img src='data:image/png;base64,$b64' width='${width * z}' height='${height * z}' " +
            "style='image-rendering:pixelated;display:block'/>" +
            "<figcaption style='font:11px var(--mono-font, monospace);opacity:.75'>${caption()}</figcaption></figure>"
    }

    override fun toString(): String = "BufferCanvas(${width}x$height, $channels ch, stride $rowStride, offset $offset)"

    private fun fmt(v: Float): String = if (abs(v) >= 1e4 || (abs(v) < 1e-3 && v != 0f)) "%.3e".format(v) else "%.4g".format(v)
}

/** An integer rectangle in element coordinates: top-left `(x, y)`, size `w × h`. */
data class CellRect(val x: Int, val y: Int, val w: Int, val h: Int) {
    init { require(w >= 1 && h >= 1) { "Rect must be at least 1x1, got ${w}x$h" } }
}

/**
 * How [BufferCanvas.toHTML] maps a stored number to a display value in `0..1`. A result outside
 * `0..1` is not clamped silently: the pixel is marked and counted (see [BufferCanvas]).
 */
sealed interface ValueMap {
    /** Map one stored value. */
    fun apply(v: Float): Double

    /** Words for the caption. */
    fun describe(): String

    /** The stored number is the display value: `0..1` is black..white, everything else is marked. */
    object Raw : ValueMap {
        override fun apply(v: Float) = v.toDouble()
        override fun describe() = "raw 0…1"
    }

    /** `lo..hi` to black..white, chosen by you; values outside are marked. */
    data class Linear(val lo: Double, val hi: Double) : ValueMap {
        init { require(hi > lo) { "Linear map needs hi > lo, got $lo..$hi" } }
        override fun apply(v: Float) = (v - lo) / (hi - lo)
        override fun describe() = "linear ${lo}…$hi"
    }

    /** `-m..m` to black..white with zero at mid grey; values outside are marked. */
    data class Signed(val m: Double) : ValueMap {
        init { require(m > 0) { "Signed map needs m > 0, got $m" } }
        override fun apply(v: Float) = 0.5 + v / (2 * m)
        override fun describe() = "signed ±$m (0 = grey)"
    }
}

/**
 * A [BufferCanvas] over [array], [width] elements across. [height] defaults to as many whole rows
 * as fit after [offset]. Edits write into [array] itself.
 */
fun bufferCanvas(
    array: FloatArray, width: Int, channels: Int = 1, offset: Int = 0,
    rowStride: Int = width * channels, height: Int = (array.size - offset) / rowStride,
    wrapX: Boolean = false, wrapY: Boolean = false,
): BufferCanvas {
    require(offset + (height - 1) * rowStride + width * channels <= array.size) { "canvas runs past the end of the array (${array.size})" }
    return BufferCanvas(width, height, channels, offset, rowStride, { array[it] }, { i, v -> array[i] = v }, wrapX, wrapY)
}

/**
 * A [BufferCanvas] over [buffer] (absolute indexing; its position is ignored and never moved).
 * Works for heap and direct buffers, including a mapped GPU staging buffer.
 */
fun bufferCanvas(
    buffer: FloatBuffer, width: Int, channels: Int = 1, offset: Int = 0,
    rowStride: Int = width * channels, height: Int = (buffer.capacity() - offset) / rowStride,
    wrapX: Boolean = false, wrapY: Boolean = false,
): BufferCanvas {
    require(offset + (height - 1) * rowStride + width * channels <= buffer.capacity()) { "canvas runs past the end of the buffer (${buffer.capacity()})" }
    return BufferCanvas(width, height, channels, offset, rowStride, { buffer.get(it) }, { i, v -> buffer.put(i, v) }, wrapX, wrapY)
}

/** How the bytes of a [ByteBuffer] are read as numbers by [bufferCanvas]. */
enum class BufferElement(val bytes: Int) {
    /** Unsigned byte, read as `0..255`. */
    U8(1),
    /** Signed 32-bit integer, read as itself. */
    I32(4),
    /** 32-bit float. */
    F32(4),
}

/**
 * A [BufferCanvas] over raw bytes, interpreted as [type] in [order]. [offset] and [rowStride]
 * count *elements of [type]*, not bytes. A `U8` buffer reads as `0..255`, so pick
 * `ValueMap.Linear(0.0, 255.0)` to see it; writes are rounded and clamped to the type.
 */
fun bufferCanvas(
    bytes: ByteBuffer, width: Int, type: BufferElement, channels: Int = 1, offset: Int = 0,
    rowStride: Int = width * channels, height: Int = (bytes.capacity() / type.bytes - offset) / rowStride,
    order: ByteOrder = ByteOrder.nativeOrder(), wrapX: Boolean = false, wrapY: Boolean = false,
): BufferCanvas {
    val b = bytes.duplicate().order(order)
    val read: (Int) -> Float
    val write: (Int, Float) -> Unit
    when (type) {
        BufferElement.U8 -> {
            read = { (b.get(it).toInt() and 0xff).toFloat() }
            write = { i, v -> b.put(i, v.roundToInt().coerceIn(0, 255).toByte()) }
        }
        BufferElement.I32 -> {
            read = { b.getInt(it * 4).toFloat() }
            write = { i, v -> b.putInt(i * 4, v.roundToInt()) }
        }
        BufferElement.F32 -> {
            read = { b.getFloat(it * 4) }
            write = { i, v -> b.putFloat(i * 4, v) }
        }
    }
    return BufferCanvas(width, height, channels, offset, rowStride, read, write, wrapX, wrapY)
}
