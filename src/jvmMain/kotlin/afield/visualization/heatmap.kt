package afield

import afield.util.PngEncoder
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A colour-mapped raster produced by [heatmapImage]: ARGB [pixels] laid out row-major, [width] ×
 * [height]. Call [png] for PNG bytes ready to `display` or persist.
 */
class RasterImage(val pixels: IntArray, val width: Int, val height: Int) {
    /** Encode to PNG bytes (ARGB, sRGB) via [PngEncoder]. */
    fun png(): ByteArray = PngEncoder.encode(pixels, width, height)
}

/**
 * Render a 2-D [matrix] to a colour-mapped [RasterImage] — the pure, Box-free core of [showHeatmap]
 * (usable from tests, docs harnesses, or any non-Box context).
 *
 * The [matrix] is indexed `matrix[row][col]`: rows map to the vertical (y) axis, columns to the
 * horizontal (x) axis. [flipY] draws row 0 at the *bottom* (the natural spectrogram convention where
 * rows are frequency bins); [transpose] treats the data as `[x][y]` (e.g. a `[frame][bin]`
 * spectrogram → time on x, frequency on y). See [showHeatmap] for the parameter meanings.
 */
fun heatmapImage(
    matrix: Array<DoubleArray>,
    colormap: String = "viridis",
    width: Int = 1200,
    height: Int = -1,
    normalize: Boolean = true,
    vmin: Double = Double.NaN,
    vmax: Double = Double.NaN,
    logScale: Boolean = false,
    smooth: Boolean = true,
    flipY: Boolean = true,
    transpose: Boolean = false,
    overlay: List<Pair<Double, Double>>? = null,
    overlayColor: Int = -0x1, // opaque white (0xFFFFFFFF)
    overlayThickness: Int = 2,
): RasterImage {
    require(matrix.isNotEmpty() && matrix[0].isNotEmpty()) { "heatmap: matrix must be non-empty" }
    val cols0 = matrix[0].size
    require(matrix.all { it.size == cols0 }) { "heatmap: matrix must be rectangular (row lengths differ)" }

    // Logical grid the caller means to see: rows -> y, cols -> x (after an optional transpose).
    val rows = if (transpose) cols0 else matrix.size
    val cols = if (transpose) matrix.size else cols0
    fun raw(r: Int, c: Int): Double = if (transpose) matrix[c][r] else matrix[r][c]
    fun t(v: Double): Double = if (logScale) log10(max(v, 1e-12)) else v

    // Colour-scale bounds, in the (post-logScale) value domain.
    var lo = vmin
    var hi = vmax
    if (lo.isNaN() || hi.isNaN()) {
        if (normalize) {
            var mn = Double.POSITIVE_INFINITY
            var mx = Double.NEGATIVE_INFINITY
            for (r in 0 until rows) for (c in 0 until cols) {
                val v = t(raw(r, c))
                if (v < mn) mn = v
                if (v > mx) mx = v
            }
            if (lo.isNaN()) lo = mn
            if (hi.isNaN()) hi = mx
        } else {
            if (lo.isNaN()) lo = 0.0
            if (hi.isNaN()) hi = 1.0
        }
    }
    val span = (hi - lo).takeIf { it > 1e-12 } ?: 1.0
    fun norm(v: Double): Double = ((t(v) - lo) / span).coerceIn(0.0, 1.0)

    // Precompute the ARGB stops for the whole map, then index by a quantised parameter — this turns
    // per-pixel colormap interpolation into a table lookup (the hot loop is the image, not the grid),
    // so a wide capture stays cheap.
    val lut = IntArray(1024) { Colormaps.sample(colormap, it / 1023.0).toArgb() }
    fun colorOf(v: Double): Int = lut[(norm(v) * 1023.0).roundToInt().coerceIn(0, 1023)]

    val w = width.coerceAtLeast(1)
    val h = (if (height > 0) height else (width.toDouble() * rows / cols).roundToInt()).coerceAtLeast(1)
    val px = IntArray(w * h)

    val gx = (cols - 1).coerceAtLeast(1).toDouble()
    val gy = (rows - 1).coerceAtLeast(1).toDouble()
    for (yp in 0 until h) {
        // Map output row -> grid row; flipY puts grid-row 0 at the bottom of the image.
        val yf = if (h == 1) 0.0 else yp.toDouble() / (h - 1)
        val fy = (if (flipY) 1.0 - yf else yf) * gy
        for (xp in 0 until w) {
            val fx = (if (w == 1) 0.0 else xp.toDouble() / (w - 1)) * gx
            val v = if (smooth) bilinear(::raw, fx, fy, cols, rows) else raw(
                fy.roundToInt().coerceIn(0, rows - 1),
                fx.roundToInt().coerceIn(0, cols - 1),
            )
            px[xp + yp * w] = colorOf(v)
        }
    }

    // Optional bright overlay trace (e.g. a spectral-centroid line over a spectrogram). Points are in
    // normalized image coords with (0,0) at the bottom-left, (1,1) top-right, drawn as a connected
    // polyline `overlayThickness` px wide.
    if (overlay != null && overlay.isNotEmpty()) {
        val half = (overlayThickness - 1).coerceAtLeast(0) / 2
        fun stamp(xp: Int, yp: Int) {
            for (dy in -half..half) for (dx in -half..half) {
                val xx = xp + dx; val yy = yp + dy
                if (xx in 0 until w && yy in 0 until h) px[xx + yy * w] = overlayColor
            }
        }
        fun toPx(p: Pair<Double, Double>): Pair<Int, Int> =
            (p.first.coerceIn(0.0, 1.0) * (w - 1)).roundToInt() to
                ((1.0 - p.second.coerceIn(0.0, 1.0)) * (h - 1)).roundToInt()

        var (px0, py0) = toPx(overlay[0])
        stamp(px0, py0)
        for (i in 1 until overlay.size) {
            val (px1, py1) = toPx(overlay[i])
            // DDA line between consecutive points.
            val steps = max(kotlin.math.abs(px1 - px0), kotlin.math.abs(py1 - py0)).coerceAtLeast(1)
            for (s in 1..steps) {
                val f = s.toDouble() / steps
                stamp((px0 + (px1 - px0) * f).roundToInt(), (py0 + (py1 - py0) * f).roundToInt())
            }
            px0 = px1; py0 = py1
        }
    }
    return RasterImage(px, w, h)
}

/**
 * Terse "put this 2-D field on the screen as a colour-mapped heatmap" helper — the raster
 * counterpart of [showSignals].
 *
 * Collapses the recurring boilerplate (build an ARGB [IntArray], min-max normalise, bilinear-
 * upsample a coarse grid, flip the y-axis, pack the colormap, `PngEncoder.encode`, `display`) that
 * every spectrogram / matrix / field capture was hand-rolling (see `spectral_examples.md`) into one
 * call. The PNG is displayed into [channel] on return (via `display`, which detects PNG bytes and
 * shows an inline image) and also returned so callers can persist or re-`display` it.
 *
 * The [matrix] is indexed `matrix[row][col]`: rows map to the vertical (y) axis, columns to the
 * horizontal (x) axis. By default [flipY] draws row 0 at the *bottom* (the natural convention for a
 * spectrogram whose rows are frequency bins). For a spectrogram whose data is `[frame][bin]` pass
 * [transpose] = true so frames become the x-axis and bins the y-axis.
 *
 * @param matrix          the data, `[row][col]`; must be non-empty and rectangular.
 * @param colormap        a [Colormaps] name — viridis / magma / inferno / plasma / turbo /
 *                        coolwarm / grayscale (default viridis).
 * @param width           output width in pixels; [height] defaults to an aspect-preserving value.
 * @param height          output height in pixels; `<= 0` derives it from the grid aspect ratio.
 * @param normalize       min-max the (optionally log-scaled) values into `[0,1]` before colouring.
 *                        With `false` and no [vmin]/[vmax] the values are assumed already in `[0,1]`.
 * @param vmin,vmax       explicit colour-scale bounds (in the value domain after [logScale]); `NaN`
 *                        means "derive from the data" (when [normalize]) or 0/1 (when not).
 * @param logScale        map each value through `log10(max(v, 1e-12))` before normalising — use for
 *                        magnitude spectra / anything spanning orders of magnitude (a dB-like view).
 * @param smooth          bilinear-upsample the coarse grid so the magnified image is smooth, not
 *                        blocky; `false` gives crisp nearest-neighbour cells.
 * @param flipY           draw row 0 at the bottom (default) rather than the top.
 * @param transpose       treat [matrix] as `[x][y]` instead of `[row][col]` (e.g. a `[frame][bin]`
 *                        spectrogram → time on x, frequency on y).
 * @param overlay         optional bright trace drawn over the heatmap (e.g. a spectral-centroid line);
 *                        points are normalized image coords, `(0,0)` bottom-left, `(1,1)` top-right.
 * @param overlayColor    ARGB colour of the [overlay] trace (default opaque white).
 * @param overlayThickness stroke width of the [overlay] trace in pixels.
 */
fun Box.showHeatmap(
    matrix: Array<DoubleArray>,
    colormap: String = "viridis",
    channel: String = "output",
    width: Int = 1200,
    height: Int = -1,
    normalize: Boolean = true,
    vmin: Double = Double.NaN,
    vmax: Double = Double.NaN,
    logScale: Boolean = false,
    smooth: Boolean = true,
    flipY: Boolean = true,
    transpose: Boolean = false,
    overlay: List<Pair<Double, Double>>? = null,
    overlayColor: Int = -0x1,
    overlayThickness: Int = 2,
): ByteArray {
    val png = heatmapImage(
        matrix, colormap, width, height, normalize, vmin, vmax, logScale, smooth, flipY, transpose,
        overlay, overlayColor, overlayThickness,
    ).png()
    display(channel, png)
    return png
}

/** `List<List<Number>>` convenience — copies into a rectangular `Array<DoubleArray>`. */
fun Box.showHeatmap(
    matrix: List<List<Number>>,
    colormap: String = "viridis",
    channel: String = "output",
    width: Int = 1200,
    height: Int = -1,
    normalize: Boolean = true,
    vmin: Double = Double.NaN,
    vmax: Double = Double.NaN,
    logScale: Boolean = false,
    smooth: Boolean = true,
    flipY: Boolean = true,
    transpose: Boolean = false,
    overlay: List<Pair<Double, Double>>? = null,
    overlayColor: Int = -0x1,
    overlayThickness: Int = 2,
): ByteArray {
    require(matrix.isNotEmpty()) { "showHeatmap: matrix must be non-empty" }
    val arr = Array(matrix.size) { r -> DoubleArray(matrix[r].size) { c -> matrix[r][c].toDouble() } }
    return showHeatmap(
        arr, colormap, channel, width, height, normalize, vmin, vmax, logScale, smooth, flipY, transpose,
        overlay, overlayColor, overlayThickness,
    )
}

/**
 * Show a ready-made ARGB [pixels] raster as an inline image (encode + `display`), returning the PNG
 * bytes. The thin sibling of [showHeatmap] for when you already have a pixel buffer.
 */
fun Box.showImage(pixels: IntArray, width: Int, height: Int, channel: String = "output"): ByteArray {
    require(pixels.size >= width * height) { "showImage: pixels ($width x $height) shorter than $width*$height" }
    val png = PngEncoder.encode(pixels, width, height)
    display(channel, png)
    return png
}

/** Pack a [Color4] (components in `[0,1]`) into an ARGB int for [PngEncoder]. */
private fun Color4.toArgb(): Int {
    fun ch(x: Double): Int = (x.coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt()
    return (ch(a) shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
}

/** Bilinear sample of a `[row][col]` reader at fractional grid coords (fx = col, fy = row). */
private inline fun bilinear(raw: (Int, Int) -> Double, fx: Double, fy: Double, cols: Int, rows: Int): Double {
    val x0 = fx.toInt().coerceIn(0, cols - 1)
    val y0 = fy.toInt().coerceIn(0, rows - 1)
    val x1 = (x0 + 1).coerceAtMost(cols - 1)
    val y1 = (y0 + 1).coerceAtMost(rows - 1)
    val tx = (fx - x0).coerceIn(0.0, 1.0)
    val ty = (fy - y0).coerceIn(0.0, 1.0)
    val top = raw(y0, x0) * (1 - tx) + raw(y0, x1) * tx
    val bot = raw(y1, x0) * (1 - tx) + raw(y1, x1) * tx
    return top * (1 - ty) + bot * ty
}
