@file:afield.CompletionCategory("Imaging")

package afield.stdlib

import afield.ToHTML
import afield.util.PngEncoder
import java.awt.image.BufferedImage
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * CPU image / computer-vision family for the Field stdlib — the raster counterpart to the
 * time-domain [timeseries.kt] and frequency-domain [spectral.kt] families.
 *
 * Field's stated target domains include computer vision, but until now the stdlib had no image
 * layer at all: no convolution, no gradients, no pyramids, no features, no warps. This file (plus
 * `imaging_filters.kt`, `imaging_features.kt`, `imaging_geometry.kt`, `imaging_field.kt`) closes
 * that gap with a small, honest, dependency-free core.
 *
 * The whole family is built on one value type, [Img]:
 *
 * ```
 * val photo  = imgFromFile("cat.png")        // 3-channel sRGB-ish floats in 0..1
 * val gray   = photo.luma()                  // 1 channel
 * val edges  = gray.sobelMagnitude()         // 1 channel
 * val canny  = gray.canny(0.08, 0.20)        // 1 channel binary mask
 * canny.toFLines()                           // .. straight into Field's drawing layer
 * ```
 *
 * Design choices, all deliberate:
 *
 * - **Float channels, not bytes.** Every operation is float math in `0..1` (or unbounded, for
 *   gradients and Laplacians, which legitimately go negative). Nothing quantises mid-pipeline, so
 *   a 12-stage chain does not accumulate rounding steps. Clamping happens once, on output.
 * - **Immutable-by-convention value type.** Ops return a new [Img]; the backing [FloatArray] is
 *   public (`data`) so a hot inner loop in a box can write it directly if it wants to.
 *   Interleaved channel-major-last layout (`data[(y*width + x)*channels + c]`), the same layout
 *   `BufferedImage`/GL/Vulkan uploads want.
 * - **1, 3 or 4 channels.** 1 = scalar/gray/mask, 3 = RGB, 4 = RGBA. Nothing else; a "12-channel
 *   feature tensor" is a different (and non-existent) problem.
 * - **No external dependency.** Pure Kotlin plus `javax.imageio` for load/save and
 *   `afield.util.PngEncoder` for the AWT-free path used by box output.
 * - **Live-codeable.** [Img] implements [ToHTML], so evaluating an `Img` in a box (or `img.h`)
 *   shows the actual picture as an inline thumbnail, not `Img@3f1a2b`.
 *
 * Out of scope on purpose: GPU work (that is `afield.four` / StackSL / RectSL), video decoding,
 * and anything needing a model file.
 */

// ==============================================================================================
// Img — the value type
// ==============================================================================================

/**
 * A CPU raster image: `width * height * channels` floats, interleaved, row-major, origin
 * **top-left** (the image-processing convention, matching `BufferedImage` and PNG).
 *
 * Values are conventionally in `0..1` for colour data, but nothing enforces that — gradient,
 * Laplacian and flow images are signed and unbounded, and [toBufferedImage] clamps only at the
 * moment of conversion.
 *
 * @property width  pixels across, >= 1
 * @property height pixels down, >= 1
 * @property channels 1 (scalar/gray), 3 (RGB) or 4 (RGBA)
 * @property data interleaved samples, `data[(y * width + x) * channels + c]`
 */
class Img(
    val width: Int,
    val height: Int,
    val channels: Int,
    val data: FloatArray
) : ToHTML {

    init {
        require(width >= 1 && height >= 1) { "Img must be at least 1x1, got ${width}x$height" }
        require(channels == 1 || channels == 3 || channels == 4) {
            "Img supports 1 (gray), 3 (RGB) or 4 (RGBA) channels, got $channels"
        }
        require(data.size == width * height * channels) {
            "Img data must be width*height*channels = ${width * height * channels}, got ${data.size}"
        }
    }

    /** Number of pixels (not samples). */
    val pixelCount: Int get() = width * height

    /** `true` when this is a single-channel scalar image (gray / mask / gradient / distance). */
    val isScalar: Boolean get() = channels == 1

    // ---- element access ------------------------------------------------------------------

    /** Flat index of channel [c] of pixel `(x, y)`. No bounds checking; see [at]. */
    fun index(x: Int, y: Int, c: Int = 0): Int = (y * width + x) * channels + c

    /** Sample at `(x, y)`, channel [c]; throws if out of bounds. */
    fun at(x: Int, y: Int, c: Int = 0): Float {
        require(x in 0 until width && y in 0 until height) { "($x, $y) outside ${width}x$height" }
        require(c in 0 until channels) { "channel $c outside 0..${channels - 1}" }
        return data[index(x, y, c)]
    }

    /** Write [v] at `(x, y)`, channel [c]; throws if out of bounds. Mutates in place. */
    fun set(x: Int, y: Int, c: Int, v: Float) {
        require(x in 0 until width && y in 0 until height) { "($x, $y) outside ${width}x$height" }
        require(c in 0 until channels) { "channel $c outside 0..${channels - 1}" }
        data[index(x, y, c)] = v
    }

    /**
     * Sample with an out-of-range policy — the workhorse every filter uses at the image border.
     * @param border how coordinates outside the image are resolved, see [Border].
     */
    fun get(x: Int, y: Int, c: Int = 0, border: Border = Border.CLAMP): Float {
        val cc = if (c < channels) c else channels - 1
        if (x in 0 until width && y in 0 until height) return data[index(x, y, cc)]
        return when (border) {
            Border.ZERO -> 0f
            Border.CLAMP -> data[index(x.coerceIn(0, width - 1), y.coerceIn(0, height - 1), cc)]
            Border.REFLECT -> data[index(reflectIndex(x, width), reflectIndex(y, height), cc)]
            Border.WRAP -> data[index(Math.floorMod(x, width), Math.floorMod(y, height), cc)]
        }
    }

    /** An independent copy. */
    fun copy(): Img = Img(width, height, channels, data.copyOf())

    /** A same-shape image of zeros (optionally a different channel count). */
    fun zerosLike(channels: Int = this.channels): Img =
        Img(width, height, channels, FloatArray(width * height * channels))

    /** `true` if [other] has the same width, height and channel count. */
    fun sameShapeAs(other: Img): Boolean =
        width == other.width && height == other.height && channels == other.channels

    // ---- bulk maps ------------------------------------------------------------------------

    /** Apply [f] to every sample (all channels), returning a new image. */
    inline fun map(f: (Float) -> Float): Img {
        val out = FloatArray(data.size)
        for (i in data.indices) out[i] = f(data[i])
        return Img(width, height, channels, out)
    }

    /** Apply [f] to every *pixel* given `(x, y, channel, value)`, returning a new image. */
    inline fun mapIndexed(f: (x: Int, y: Int, c: Int, v: Float) -> Float): Img {
        val out = FloatArray(data.size)
        var i = 0
        for (y in 0 until height) for (x in 0 until width) for (c in 0 until channels) {
            out[i] = f(x, y, c, data[i]); i++
        }
        return Img(width, height, channels, out)
    }

    /** Element-wise combination of two same-shape images. */
    inline fun zip(other: Img, f: (Float, Float) -> Float): Img {
        require(sameShapeAs(other)) {
            "shape mismatch: ${width}x$height x$channels vs ${other.width}x${other.height} x${other.channels}"
        }
        val out = FloatArray(data.size)
        for (i in data.indices) out[i] = f(data[i], other.data[i])
        return Img(width, height, channels, out)
    }

    operator fun plus(other: Img): Img = zip(other) { a, b -> a + b }
    operator fun minus(other: Img): Img = zip(other) { a, b -> a - b }
    operator fun times(other: Img): Img = zip(other) { a, b -> a * b }
    operator fun times(k: Number): Img = map { it * k.toFloat() }
    operator fun plus(k: Number): Img = map { it + k.toFloat() }
    operator fun unaryMinus(): Img = map { -it }

    /** Absolute value of every sample. */
    fun abs(): Img = map { kotlin.math.abs(it) }

    /** Clamp every sample into `[lo, hi]` (default the display range `0..1`). */
    fun clamp(lo: Float = 0f, hi: Float = 1f): Img = map { it.coerceIn(lo, hi) }

    // ---- reductions -------------------------------------------------------------------------

    /** Smallest sample over all channels. */
    fun min(): Float = data.min()

    /** Largest sample over all channels. */
    fun max(): Float = data.max()

    /** Arithmetic mean of every sample. */
    fun mean(): Float {
        var s = 0.0
        for (v in data) s += v
        return (s / data.size).toFloat()
    }

    /** Sum of every sample — the "DC" of the image; convolution with a normalised kernel preserves it. */
    fun sum(): Double {
        var s = 0.0
        for (v in data) s += v
        return s
    }

    /** Population standard deviation of every sample. */
    fun stdDev(): Float {
        val m = mean().toDouble()
        var s = 0.0
        for (v in data) { val d = v - m; s += d * d }
        return sqrt(s / data.size).toFloat()
    }

    override fun toString(): String =
        "Img(${width}x$height x$channels, range ${"%.3f".format(min())}..${"%.3f".format(max())})"

    /**
     * Inline thumbnail for the value visualizer: the actual pixels, PNG-encoded and inlined as a
     * data URI, plus a one-line caption. Scalar images are shown grayscale; anything outside `0..1`
     * is auto-normalised for display only (and the caption says so).
     */
    override fun toHTML(): String {
        val maxSide = 240
        val scale = min(1.0, maxSide.toDouble() / max(width, height))
        val tw = max(1, (width * scale).roundToInt())
        val th = max(1, (height * scale).roundToInt())
        val lo = min()
        val hi = max()
        val needsNorm = lo < -1e-4f || hi > 1f + 1e-4f
        val shown = if (needsNorm) normalized() else this
        val thumb = if (tw == width && th == height) shown else shown.resize(tw, th)
        val b64 = Base64.getEncoder().encodeToString(thumb.toPngBytes())
        val kind = when (channels) { 1 -> "gray"; 3 -> "rgb"; else -> "rgba" }
        val note = if (needsNorm) " · normalised for display" else ""
        return """
            <div style="font-family:monospace;font-size:11px;line-height:1.4">
              <img src="data:image/png;base64,$b64" width="$tw" height="$th"
                   style="image-rendering:pixelated;border:1px solid #3a3f4b;border-radius:3px;display:block"/>
              <div style="color:#8a90a0;margin-top:3px">
                Img ${width}&times;$height $kind · range ${"%.3f".format(lo)}&hellip;${"%.3f".format(hi)}$note
              </div>
            </div>
        """.trimIndent()
    }

    companion object {
        /** All-zero image of the given shape. */
        fun zeros(width: Int, height: Int, channels: Int = 1): Img =
            Img(width, height, channels, FloatArray(width * height * channels))

        /** Constant-valued image. */
        fun filled(width: Int, height: Int, channels: Int = 1, value: Float): Img =
            Img(width, height, channels, FloatArray(width * height * channels) { value })

        /**
         * Build an image from a per-pixel function of `(x, y)` returning one value per channel.
         * The classic way to make a synthetic test image:
         * ```
         * val ramp = Img.generate(64, 64) { x, _ -> x / 63f }
         * ```
         */
        inline fun generate(width: Int, height: Int, crossinline f: (x: Int, y: Int) -> Float): Img {
            val out = FloatArray(width * height)
            var i = 0
            for (y in 0 until height) for (x in 0 until width) out[i++] = f(x, y)
            return Img(width, height, 1, out)
        }

        /** Build a 3-channel image from a per-pixel function returning `Triple(r, g, b)`. */
        inline fun generateRGB(
            width: Int,
            height: Int,
            crossinline f: (x: Int, y: Int) -> Triple<Float, Float, Float>
        ): Img {
            val out = FloatArray(width * height * 3)
            var i = 0
            for (y in 0 until height) for (x in 0 until width) {
                val (r, g, b) = f(x, y)
                out[i++] = r; out[i++] = g; out[i++] = b
            }
            return Img(width, height, 3, out)
        }
    }
}

/** How a filter resolves coordinates that fall outside the image. */
enum class Border {
    /** Out-of-range reads return `0`. */
    ZERO,

    /** Out-of-range reads clamp to the nearest edge pixel (the usual default). */
    CLAMP,

    /** Out-of-range reads mirror back into the image (no seam in the derivative). */
    REFLECT,

    /** Out-of-range reads wrap around (tileable images). */
    WRAP
}

/**
 * Mirror [i] back into `0 until n` (reflect-101 style, edge pixel not duplicated).
 *
 * Internal rather than private so the SIMD convolution passes can resolve a whole row's border
 * once instead of once per sample — see [convolveSeparable].
 */
internal fun reflectIndex(i: Int, n: Int): Int {
    if (n == 1) return 0
    var v = i
    val period = 2 * n - 2
    v = Math.floorMod(v, period)
    return if (v < n) v else period - v
}

// ==============================================================================================
// Construction / conversion
// ==============================================================================================

/**
 * Convert a [BufferedImage] to an [Img] with samples in `0..1`.
 *
 * @param channels 1 (luma via [RGB_TO_LUMA]), 3 (RGB) or 4 (RGBA); `null` picks 4 if the source has
 *   an alpha channel, otherwise 3.
 */
fun BufferedImage.toImg(channels: Int? = null): Img {
    val hasAlpha = colorModel.hasAlpha()
    val ch = channels ?: if (hasAlpha) 4 else 3
    require(ch == 1 || ch == 3 || ch == 4) { "channels must be 1, 3 or 4, got $ch" }
    val out = FloatArray(width * height * ch)
    var i = 0
    for (y in 0 until height) for (x in 0 until width) {
        val argb = getRGB(x, y)
        val r = ((argb shr 16) and 0xff) / 255f
        val g = ((argb shr 8) and 0xff) / 255f
        val b = (argb and 0xff) / 255f
        val a = ((argb ushr 24) and 0xff) / 255f
        when (ch) {
            1 -> out[i++] = RGB_TO_LUMA[0] * r + RGB_TO_LUMA[1] * g + RGB_TO_LUMA[2] * b
            3 -> { out[i++] = r; out[i++] = g; out[i++] = b }
            else -> { out[i++] = r; out[i++] = g; out[i++] = b; out[i++] = a }
        }
    }
    return Img(width, height, ch, out)
}

/**
 * Convert to a [BufferedImage] for AWT drawing / `ImageIO`. Samples are clamped to `0..1` and
 * quantised to 8 bits at this point (and only at this point). A 1-channel image becomes gray RGB.
 */
fun Img.toBufferedImage(): BufferedImage {
    val type = if (channels == 4) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
    val out = BufferedImage(width, height, type)
    for (y in 0 until height) for (x in 0 until width) {
        val i = index(x, y, 0)
        val r: Int; val g: Int; val b: Int; var a = 255
        when (channels) {
            1 -> { val v = q8(data[i]); r = v; g = v; b = v }
            3 -> { r = q8(data[i]); g = q8(data[i + 1]); b = q8(data[i + 2]) }
            else -> { r = q8(data[i]); g = q8(data[i + 1]); b = q8(data[i + 2]); a = q8(data[i + 3]) }
        }
        out.setRGB(x, y, (a shl 24) or (r shl 16) or (g shl 8) or b)
    }
    return out
}

private fun q8(v: Float): Int = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)

/**
 * Encode straight to PNG bytes with **no AWT** (via [PngEncoder]) — the path a box uses to put a
 * picture in its own output, and the path [Img.toHTML] uses for its thumbnail.
 */
fun Img.toPngBytes(): ByteArray {
    val px = IntArray(pixelCount)
    for (p in 0 until pixelCount) {
        val i = p * channels
        val r: Int; val g: Int; val b: Int; var a = 255
        when (channels) {
            1 -> { val v = q8(data[i]); r = v; g = v; b = v }
            3 -> { r = q8(data[i]); g = q8(data[i + 1]); b = q8(data[i + 2]) }
            else -> { r = q8(data[i]); g = q8(data[i + 1]); b = q8(data[i + 2]); a = q8(data[i + 3]) }
        }
        px[p] = (a shl 24) or (r shl 16) or (g shl 8) or b
    }
    return PngEncoder.encode(px, width, height)
}

/** Write this image to [path] as a PNG (creating parent directories). Returns the file. */
fun Img.savePng(path: String): File {
    val f = File(path)
    f.parentFile?.mkdirs()
    f.writeBytes(toPngBytes())
    return f
}

/** Load an image from a file path. @param channels see [BufferedImage.toImg]. */
fun imgFromFile(path: String, channels: Int? = null): Img {
    val f = File(path)
    require(f.isFile) { "no such image file: ${f.absolutePath}" }
    val bi = ImageIO.read(f) ?: error("ImageIO could not decode ${f.absolutePath}")
    return bi.toImg(channels)
}

/** Decode an image from in-memory encoded bytes (PNG/JPEG/… — whatever `ImageIO` handles). */
fun imgFromBytes(bytes: ByteArray, channels: Int? = null): Img {
    val bi = ImageIO.read(bytes.inputStream()) ?: error("ImageIO could not decode ${bytes.size} bytes")
    return bi.toImg(channels)
}

/**
 * Wrap a plain `FloatArray` of scalars as a 1-channel image — the bridge from the rest of the
 * stdlib (a heightfield, a spectrogram frame stack, a `DoubleArray` reduction) into imaging.
 */
fun floatsToImg(values: FloatArray, width: Int, height: Int): Img {
    require(values.size == width * height) { "expected ${width * height} values, got ${values.size}" }
    return Img(width, height, 1, values.copyOf())
}

/** [floatsToImg] for doubles. */
fun doublesToImg(values: DoubleArray, width: Int, height: Int): Img {
    require(values.size == width * height) { "expected ${width * height} values, got ${values.size}" }
    return Img(width, height, 1, FloatArray(values.size) { values[it].toFloat() })
}

// ==============================================================================================
// Sampling / geometry (resolution, not transforms — see imaging_geometry.kt for warps)
// ==============================================================================================

/** Nearest-neighbour sample at continuous coordinates. */
fun Img.sampleNearest(x: Double, y: Double, c: Int = 0, border: Border = Border.CLAMP): Float =
    get(Math.round(x).toInt(), Math.round(y).toInt(), c, border)

/**
 * Bilinear sample at continuous coordinates — pixel centres sit at integer coordinates, so
 * `sampleBilinear(3.0, 4.0) == at(3, 4)` exactly.
 */
fun Img.sampleBilinear(x: Double, y: Double, c: Int = 0, border: Border = Border.CLAMP): Float {
    val x0 = floor(x).toInt()
    val y0 = floor(y).toInt()
    val fx = (x - x0).toFloat()
    val fy = (y - y0).toFloat()
    val v00 = get(x0, y0, c, border)
    val v10 = get(x0 + 1, y0, c, border)
    val v01 = get(x0, y0 + 1, c, border)
    val v11 = get(x0 + 1, y0 + 1, c, border)
    val top = v00 + (v10 - v00) * fx
    val bot = v01 + (v11 - v01) * fx
    return top + (bot - top) * fy
}

/** All channels bilinearly sampled at once. */
fun Img.sampleBilinearAll(x: Double, y: Double, border: Border = Border.CLAMP): FloatArray =
    FloatArray(channels) { sampleBilinear(x, y, it, border) }

/** Rectangular sub-image. Coordinates are clamped into range; the result is at least 1x1. */
fun Img.crop(x: Int, y: Int, w: Int, h: Int): Img {
    require(w >= 1 && h >= 1) { "crop size must be >= 1x1, got ${w}x$h" }
    val x0 = x.coerceIn(0, width - 1)
    val y0 = y.coerceIn(0, height - 1)
    val ww = w.coerceAtMost(width - x0)
    val hh = h.coerceAtMost(height - y0)
    val out = FloatArray(ww * hh * channels)
    var i = 0
    for (yy in 0 until hh) for (xx in 0 until ww) for (c in 0 until channels) {
        out[i++] = data[index(x0 + xx, y0 + yy, c)]
    }
    return Img(ww, hh, channels, out)
}

/** Grow the image by [pad] pixels on every side, filling from [border]. */
fun Img.pad(pad: Int, border: Border = Border.CLAMP): Img {
    require(pad >= 0) { "pad must be >= 0, got $pad" }
    if (pad == 0) return copy()
    val w = width + 2 * pad
    val h = height + 2 * pad
    val out = FloatArray(w * h * channels)
    var i = 0
    for (y in 0 until h) for (x in 0 until w) for (c in 0 until channels) {
        out[i++] = get(x - pad, y - pad, c, border)
    }
    return Img(w, h, channels, out)
}

/**
 * Resize to `newWidth x newHeight`.
 *
 * Downscaling uses **area averaging** (each output pixel is the mean of the input pixels it
 * covers), which is the right anti-aliased answer and what `resize` should do by default;
 * upscaling uses bilinear interpolation. Set [areaAverage] to `false` to force bilinear both ways.
 */
fun Img.resize(newWidth: Int, newHeight: Int, areaAverage: Boolean = true): Img {
    require(newWidth >= 1 && newHeight >= 1) { "resize target must be >= 1x1, got ${newWidth}x$newHeight" }
    if (newWidth == width && newHeight == height) return copy()
    val shrinking = newWidth < width || newHeight < height
    return if (areaAverage && shrinking) resizeArea(newWidth, newHeight) else resizeBilinear(newWidth, newHeight)
}

private fun Img.resizeBilinear(nw: Int, nh: Int): Img {
    val out = FloatArray(nw * nh * channels)
    val sx = if (nw == 1) 0.0 else (width - 1).toDouble() / (nw - 1)
    val sy = if (nh == 1) 0.0 else (height - 1).toDouble() / (nh - 1)
    var i = 0
    for (y in 0 until nh) for (x in 0 until nw) for (c in 0 until channels) {
        out[i++] = sampleBilinear(x * sx, y * sy, c)
    }
    return Img(nw, nh, channels, out)
}

private fun Img.resizeArea(nw: Int, nh: Int): Img {
    val out = FloatArray(nw * nh * channels)
    val sx = width.toDouble() / nw
    val sy = height.toDouble() / nh
    var i = 0
    for (y in 0 until nh) {
        val y0 = (y * sy).toInt().coerceIn(0, height - 1)
        val y1 = max(y0 + 1, ((y + 1) * sy).toInt().coerceAtMost(height))
        for (x in 0 until nw) {
            val x0 = (x * sx).toInt().coerceIn(0, width - 1)
            val x1 = max(x0 + 1, ((x + 1) * sx).toInt().coerceAtMost(width))
            val n = (y1 - y0) * (x1 - x0)
            for (c in 0 until channels) {
                var s = 0.0
                for (yy in y0 until y1) for (xx in x0 until x1) s += data[index(xx, yy, c)]
                out[i++] = (s / n).toFloat()
            }
        }
    }
    return Img(nw, nh, channels, out)
}

/** Resize by a scale factor (both axes). See [resize]. */
fun Img.scaleBy(factor: Double, areaAverage: Boolean = true): Img {
    require(factor > 0.0) { "scale factor must be > 0, got $factor" }
    return resize(max(1, (width * factor).roundToInt()), max(1, (height * factor).roundToInt()), areaAverage)
}

/** Mirror left-right. */
fun Img.flipX(): Img = mapIndexed { x, y, c, _ -> data[index(width - 1 - x, y, c)] }

/** Mirror top-bottom. */
fun Img.flipY(): Img = mapIndexed { x, y, c, _ -> data[index(x, height - 1 - y, c)] }

/** Rotate 90 degrees clockwise (width and height swap). */
fun Img.rotate90(): Img {
    val out = FloatArray(data.size)
    var i = 0
    for (y in 0 until width) for (x in 0 until height) for (c in 0 until channels) {
        out[i++] = data[index(y, height - 1 - x, c)]
    }
    return Img(height, width, channels, out)
}

/** Transpose (x and y swap). */
fun Img.transpose(): Img {
    val out = FloatArray(data.size)
    var i = 0
    for (y in 0 until width) for (x in 0 until height) for (c in 0 until channels) {
        out[i++] = data[index(y, x, c)]
    }
    return Img(height, width, channels, out)
}

// ==============================================================================================
// Channels and colour
// ==============================================================================================

/** ITU-R BT.709 luma weights — the perceptual RGB→gray mix used throughout the family. */
val RGB_TO_LUMA: FloatArray = floatArrayOf(0.2126f, 0.7152f, 0.0722f)

/** Extract channel [c] as a 1-channel image. */
fun Img.channel(c: Int): Img {
    require(c in 0 until channels) { "channel $c outside 0..${channels - 1}" }
    val out = FloatArray(pixelCount)
    for (p in 0 until pixelCount) out[p] = data[p * channels + c]
    return Img(width, height, 1, out)
}

/** Split into one 1-channel image per channel. */
fun Img.split(): List<Img> = (0 until channels).map { channel(it) }

/**
 * Merge 1-channel images into a multi-channel image. All inputs must share a shape and the count
 * must be 1, 3 or 4.
 */
fun mergeChannels(vararg parts: Img): Img {
    require(parts.isNotEmpty()) { "mergeChannels needs at least one image" }
    val n = parts.size
    require(n == 1 || n == 3 || n == 4) { "mergeChannels takes 1, 3 or 4 images, got $n" }
    val w = parts[0].width
    val h = parts[0].height
    parts.forEachIndexed { i, p ->
        require(p.channels == 1) { "mergeChannels part $i must be 1-channel, got ${p.channels}" }
        require(p.width == w && p.height == h) {
            "mergeChannels part $i is ${p.width}x${p.height}, expected ${w}x$h"
        }
    }
    val out = FloatArray(w * h * n)
    for (p in 0 until w * h) for (c in 0 until n) out[p * n + c] = parts[c].data[p]
    return Img(w, h, n, out)
}

/**
 * Perceptual grayscale (BT.709 luma) as a 1-channel image. A 1-channel input is returned as a copy;
 * an RGBA input ignores alpha.
 */
fun Img.luma(): Img {
    if (channels == 1) return copy()
    val out = FloatArray(pixelCount)
    for (p in 0 until pixelCount) {
        val i = p * channels
        out[p] = RGB_TO_LUMA[0] * data[i] + RGB_TO_LUMA[1] * data[i + 1] + RGB_TO_LUMA[2] * data[i + 2]
    }
    return Img(width, height, 1, out)
}

/** Replicate a 1-channel image into 3 channels (the inverse direction of [luma]). */
fun Img.toRGB(): Img {
    if (channels == 3) return copy()
    if (channels == 4) return mergeChannels(channel(0), channel(1), channel(2))
    val out = FloatArray(pixelCount * 3)
    for (p in 0 until pixelCount) {
        val v = data[p]; out[p * 3] = v; out[p * 3 + 1] = v; out[p * 3 + 2] = v
    }
    return Img(width, height, 3, out)
}

/** Add an opaque (or [alpha]-valued) alpha channel. */
fun Img.toRGBA(alpha: Float = 1f): Img {
    val rgb = toRGB()
    val out = FloatArray(pixelCount * 4)
    for (p in 0 until pixelCount) {
        out[p * 4] = rgb.data[p * 3]
        out[p * 4 + 1] = rgb.data[p * 3 + 1]
        out[p * 4 + 2] = rgb.data[p * 3 + 2]
        out[p * 4 + 3] = alpha
    }
    return Img(width, height, 4, out)
}

/** sRGB transfer function applied to a linear-light value. */
fun linearToSrgb(v: Float): Float =
    if (v <= 0.0031308f) 12.92f * v else 1.055f * v.toDouble().pow(1.0 / 2.4).toFloat() - 0.055f

/** Inverse sRGB transfer function: encoded value → linear light. */
fun srgbToLinear(v: Float): Float =
    if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

/**
 * Decode sRGB-encoded samples to linear light. Blurring, downsampling and averaging are only
 * *correct* in linear light; `img.toLinear().gaussianBlur(2.0).toSrgb()` is the physically right
 * blur, and visibly different from blurring the encoded values.
 */
fun Img.toLinear(): Img = mapIndexed { _, _, c, v -> if (channels == 4 && c == 3) v else srgbToLinear(v) }

/** Encode linear-light samples back to sRGB. Inverse of [toLinear]. */
fun Img.toSrgb(): Img = mapIndexed { _, _, c, v -> if (channels == 4 && c == 3) v else linearToSrgb(v) }

/**
 * RGB → HSV, in place of the channels: `h` in `0..1` (hue angle / 360), `s` in `0..1`, `v` in
 * `0..1`. Requires a 3- or 4-channel image; alpha is dropped.
 */
fun Img.rgbToHsv(): Img {
    require(channels >= 3) { "rgbToHsv needs an RGB or RGBA image, got $channels channel(s)" }
    val out = FloatArray(pixelCount * 3)
    for (p in 0 until pixelCount) {
        val i = p * channels
        val r = data[i]; val g = data[i + 1]; val b = data[i + 2]
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val h = when {
            d < 1e-7f -> 0f
            mx == r -> ((g - b) / d).let { if (it < 0) it + 6f else it } / 6f
            mx == g -> ((b - r) / d + 2f) / 6f
            else -> ((r - g) / d + 4f) / 6f
        }
        out[p * 3] = h
        out[p * 3 + 1] = if (mx <= 1e-7f) 0f else d / mx
        out[p * 3 + 2] = mx
    }
    return Img(width, height, 3, out)
}

/** HSV → RGB, the inverse of [rgbToHsv]. */
fun Img.hsvToRgb(): Img {
    require(channels == 3) { "hsvToRgb needs a 3-channel HSV image, got $channels" }
    val out = FloatArray(pixelCount * 3)
    for (p in 0 until pixelCount) {
        val i = p * 3
        val h = (data[i] - floor(data[i].toDouble()).toFloat()) * 6f
        val s = data[i + 1].coerceIn(0f, 1f)
        val v = data[i + 2]
        val sector = floor(h.toDouble()).toInt()
        val f = h - sector
        val p1 = v * (1f - s)
        val q = v * (1f - s * f)
        val t = v * (1f - s * (1f - f))
        val (r, g, b) = when (sector % 6) {
            0 -> Triple(v, t, p1)
            1 -> Triple(q, v, p1)
            2 -> Triple(p1, v, t)
            3 -> Triple(p1, q, v)
            4 -> Triple(t, p1, v)
            else -> Triple(v, p1, q)
        }
        out[i] = r; out[i + 1] = g; out[i + 2] = b
    }
    return Img(width, height, 3, out)
}

// ==============================================================================================
// Statistics: normalisation, histograms, thresholding
// ==============================================================================================

/**
 * Linearly rescale so the sample range becomes `[lo, hi]` (default `0..1`). A constant image maps
 * to the midpoint rather than dividing by zero.
 */
fun Img.normalized(lo: Float = 0f, hi: Float = 1f): Img {
    val mn = min()
    val mx = max()
    val d = mx - mn
    if (d < 1e-12f) return map { (lo + hi) / 2f }
    val k = (hi - lo) / d
    return map { lo + (it - mn) * k }
}

/**
 * Robust normalisation: map the [pLow]/[pHigh] percentiles of the sample distribution to `0..1`
 * and clamp. Far more useful than [normalized] on noisy gradient/response images, where one hot
 * pixel otherwise squashes everything else to black.
 */
fun Img.normalizedPercentile(pLow: Double = 0.01, pHigh: Double = 0.99): Img {
    require(pLow in 0.0..1.0 && pHigh in 0.0..1.0 && pLow < pHigh) {
        "percentiles must satisfy 0 <= pLow < pHigh <= 1, got $pLow / $pHigh"
    }
    val sorted = data.copyOf()
    sorted.sort()
    val lo = sorted[((sorted.size - 1) * pLow).toInt().coerceIn(0, sorted.size - 1)]
    val hi = sorted[((sorted.size - 1) * pHigh).toInt().coerceIn(0, sorted.size - 1)]
    if (hi - lo < 1e-12f) return map { 0.5f }
    val k = 1f / (hi - lo)
    return map { ((it - lo) * k).coerceIn(0f, 1f) }
}

/**
 * Histogram of a 1-channel image over `[lo, hi]` with [bins] equal-width buckets. Values outside
 * the range land in the first/last bucket.
 */
fun Img.histogram(bins: Int = 256, lo: Float = 0f, hi: Float = 1f): IntArray {
    require(channels == 1) { "histogram expects a 1-channel image; call .luma() or .channel(c) first" }
    require(bins >= 2) { "bins must be >= 2, got $bins" }
    require(hi > lo) { "hi must exceed lo, got $lo..$hi" }
    val out = IntArray(bins)
    val k = bins / (hi - lo)
    for (v in data) out[(((v - lo) * k).toInt()).coerceIn(0, bins - 1)]++
    return out
}

/**
 * Histogram equalisation of a 1-channel image: remap intensities through the cumulative histogram
 * so the output distribution is (approximately) flat. Classic contrast enhancement.
 */
fun Img.equalize(bins: Int = 256): Img {
    require(channels == 1) { "equalize expects a 1-channel image; call .luma() first" }
    val mn = min()
    val mx = max()
    if (mx - mn < 1e-12f) return copy()
    val h = histogram(bins, mn, mx)
    val cdf = DoubleArray(bins)
    var acc = 0.0
    for (i in 0 until bins) { acc += h[i]; cdf[i] = acc }
    val total = acc
    val cdfMin = cdf.first { it > 0.0 }
    val denom = (total - cdfMin).coerceAtLeast(1.0)
    val k = bins / (mx - mn)
    return map { v ->
        val b = (((v - mn) * k).toInt()).coerceIn(0, bins - 1)
        (((cdf[b] - cdfMin) / denom).toFloat()).coerceIn(0f, 1f)
    }
}

/**
 * Otsu's method: the intensity threshold in `[lo, hi]` that maximises between-class variance —
 * i.e. the best two-way split of a bimodal histogram, computed rather than guessed.
 *
 * @return the threshold value, in the same units as the samples.
 */
fun Img.otsuThreshold(bins: Int = 256): Float {
    require(channels == 1) { "otsuThreshold expects a 1-channel image; call .luma() first" }
    val mn = min()
    val mx = max()
    if (mx - mn < 1e-12f) return mn
    val h = histogram(bins, mn, mx)
    val total = pixelCount.toDouble()
    var sumAll = 0.0
    for (i in 0 until bins) sumAll += i * h[i].toDouble()
    var sumB = 0.0
    var wB = 0.0
    var best = 0.0
    var bestBin = 0
    for (i in 0 until bins) {
        wB += h[i]
        if (wB == 0.0) continue
        val wF = total - wB
        if (wF == 0.0) break
        sumB += i * h[i].toDouble()
        val mB = sumB / wB
        val mF = (sumAll - sumB) / wF
        val between = wB * wF * (mB - mF) * (mB - mF)
        if (between > best) { best = between; bestBin = i }
    }
    // Threshold at the upper edge of the chosen bin.
    return mn + (bestBin + 1).toFloat() * (mx - mn) / bins
}

/** Binarise: samples `> threshold` become 1, everything else 0. Result is 1-channel. */
fun Img.threshold(threshold: Float): Img {
    val src = if (channels == 1) this else luma()
    return src.map { if (it > threshold) 1f else 0f }
}

/** Binarise at the [otsuThreshold]. */
fun Img.thresholdOtsu(bins: Int = 256): Img {
    val src = if (channels == 1) this else luma()
    return src.threshold(src.otsuThreshold(bins))
}

/**
 * Adaptive (local-mean) threshold: each pixel is compared to the mean of a `(2*radius+1)` square
 * neighbourhood, minus [bias]. Handles uneven illumination that defeats a global threshold.
 */
fun Img.thresholdAdaptive(radius: Int = 8, bias: Float = 0.02f): Img {
    val src = if (channels == 1) this else luma()
    val mean = src.boxBlur(radius)
    return src.zip(mean) { v, m -> if (v > m - bias) 1f else 0f }
}

/** Mean absolute difference per sample against [other] — a blunt but honest similarity measure. */
fun Img.meanAbsDiff(other: Img): Double {
    require(sameShapeAs(other)) { "meanAbsDiff needs matching shapes" }
    var s = 0.0
    for (i in data.indices) s += abs(data[i] - other.data[i])
    return s / data.size
}

/** Peak signal-to-noise ratio in dB against [other], assuming a `0..1` signal range. */
fun Img.psnr(other: Img): Double {
    require(sameShapeAs(other)) { "psnr needs matching shapes" }
    var s = 0.0
    for (i in data.indices) { val d = (data[i] - other.data[i]).toDouble(); s += d * d }
    val mse = s / data.size
    if (mse <= 0.0) return Double.POSITIVE_INFINITY
    return 10.0 * kotlin.math.log10(1.0 / mse)
}
