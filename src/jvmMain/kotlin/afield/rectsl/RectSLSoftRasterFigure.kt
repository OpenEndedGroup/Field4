package afield.rectsl

import afield.util.PngEncoder
import kotlin.math.abs

/**
 * A soft raster, its exact reference, and the difference between them — as a picture.
 *
 * A bias is a number, and this project's own experience is that a number without a picture beside it
 * is a number nobody checks. The panel that matters here is the third one: `|soft - exact|`, on a heat
 * ramp with its own peak printed in the corner. A reader who has never opened this file can see from
 * it *where* the bias lives — a thin fringe along an oblique edge, a bright bar down a union seam, a
 * whole feature glowing when it is thinner than the band — which is the same conclusion the numbers
 * reach and reaches it faster.
 *
 * A CPU raster on purpose, for the reason [RectSLFitFigure] gives: per-pixel coverage *is* the data
 * here, so there is nothing a GPU would add except a device, a window and a render target. There is
 * no text primitive anywhere near this code, so the labels are [PLOT_GLYPHS], the same 3×5 dot-matrix
 * font RectSL's own plotting words use.
 */
object RectSLSoftRasterFigure {

    /** The palette, dark like the rest of Field's captures. */
    private const val BACKGROUND = 0xFF0F1115.toInt()
    private const val RULE = 0xFF2A2F38.toInt()
    private const val LABEL = 0xFFDCE1E8.toInt()

    /** One panel of the figure: a coverage image, a label, and how to colour it. */
    data class Panel(
        /** Printed top-left, in the dot-matrix font — digits, `-`, `.` and lower-case letters only. */
        val label: String,
        /** Row-major values in `0..1`. */
        val values: DoubleArray,
        /**
         * True to draw on a black-red-yellow-white heat ramp instead of grey.
         *
         * Reserved for difference panels. A difference drawn in grey next to two grey images reads as
         * a third picture of the scene, which is exactly the wrong thing for a reader to conclude.
         */
        val heat: Boolean = false,
        /**
         * Multiplied into the value before colouring, so a small difference is visible.
         *
         * Printed into the label by [renderPanels] rather than applied silently: a difference panel
         * scaled by 8 with no note is a lie about the size of the bias.
         */
        val gain: Double = 1.0
    ) {
        override fun equals(other: Any?): Boolean =
            other is Panel && label == other.label && heat == other.heat && gain == other.gain &&
                values.contentEquals(other.values)

        override fun hashCode(): Int =
            31 * (31 * (31 * label.hashCode() + values.contentHashCode()) + heat.hashCode()) + gain.hashCode()
    }

    /**
     * The three-panel figure for one scene and one raster spec: soft, exact, and their difference.
     *
     * The difference panel's gain is chosen from the data — `1/peak`, so the worst pixel is white —
     * and the peak is printed in the label, which is what stops the auto-gain from flattering.
     */
    fun renderBias(field: SceneAdField, spec: SoftRasterSpec, time: Double, scale: Int = 2): ByteArray {
        val soft = softRaster(field, spec, time)
        val exact = exactRaster(field, spec, time)
        val diff = DoubleArray(soft.size) { abs(soft[it] - exact[it]) }
        val peak = diff.maxOrNull() ?: 0.0
        return renderPanels(
            listOf(
                Panel("soft ${"%.2f".format(spec.betaInPixels)}px", soft),
                Panel("exact area", exact),
                Panel("diff peak ${"%.4f".format(peak)}", diff, heat = true, gain = if (peak > 0.0) 1.0 / peak else 1.0)
            ),
            spec.width, spec.height, scale
        )
    }

    /** The soft raster alone, at [scale] screen pixels per raster pixel. */
    fun render(field: SceneAdField, spec: SoftRasterSpec, time: Double, scale: Int = 2): ByteArray =
        renderPanels(
            listOf(Panel("soft ${"%.2f".format(spec.betaInPixels)}px", softRaster(field, spec, time))),
            spec.width, spec.height, scale
        )

    /**
     * Lays [panels] out in a row, each [width] x [height] raster pixels at [scale] screen pixels per
     * pixel, with a one-pixel rule between them and the label drawn over the top-left corner.
     *
     * @throws IllegalArgumentException when a panel is not `width * height` long — a silently
     *         truncated panel would render as a picture with a black band, which reads as data.
     */
    fun renderPanels(panels: List<Panel>, width: Int, height: Int, scale: Int = 2): ByteArray {
        require(panels.isNotEmpty()) { "a figure needs at least one panel" }
        require(scale >= 1) { "scale must be at least 1, got $scale" }
        panels.forEach {
            require(it.values.size == width * height) {
                "panel '${it.label}' has ${it.values.size} values, not ${width * height}"
            }
            // A label with an unmapped character renders as a gap, which looks like a design choice
            // rather than a bug. Refuse it here instead, where the caller can see why.
            val unmapped = it.label.uppercase().filter { ch -> PLOT_GLYPHS[ch] == null }.toSet()
            require(unmapped.isEmpty()) {
                "panel label '${it.label}' uses ${unmapped.joinToString()} , which PLOT_GLYPHS has no " +
                    "glyph for — it would render as blank space"
            }
        }
        val pad = 8
        val panelW = width * scale
        val panelH = height * scale
        val labelHeight = 5 * 2 + 6
        val outW = pad + panels.size * (panelW + pad)
        val outH = pad + labelHeight + panelH + pad
        val pixels = IntArray(outW * outH) { BACKGROUND }

        panels.forEachIndexed { index, panel ->
            val left = pad + index * (panelW + pad)
            val top = pad + labelHeight
            for (j in 0 until panelH) {
                for (i in 0 until panelW) {
                    val v = panel.values[(j / scale) * width + (i / scale)] * panel.gain
                    pixels[(top + j) * outW + left + i] = if (panel.heat) heat(v) else grey(v)
                }
            }
            // A rule round each panel, so a black panel is still visibly a panel.
            for (i in -1..panelW) {
                pixels.setSafe(outW, outH, left + i, top - 1, RULE)
                pixels.setSafe(outW, outH, left + i, top + panelH, RULE)
            }
            for (j in -1..panelH) {
                pixels.setSafe(outW, outH, left - 1, top + j, RULE)
                pixels.setSafe(outW, outH, left + panelW, top + j, RULE)
            }
            drawText(pixels, outW, outH, panel.label, left, pad, 2, LABEL)
        }
        return PngEncoder.encode(pixels, outW, outH)
    }

    /** A `0..1` value as an opaque grey. */
    private fun grey(v: Double): Int {
        val level = (v.coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt()
        return (0xFF shl 24) or (level shl 16) or (level shl 8) or level
    }

    /**
     * A `0..1` value on a black-red-yellow-white ramp.
     *
     * Chosen over a perceptual colormap deliberately: this panel is read for *where is it bright*, and
     * a ramp whose luminance rises monotonically from black answers that at a glance even in a
     * thumbnail.
     */
    private fun heat(v: Double): Int {
        val u = v.coerceIn(0.0, 1.0)
        val r = (255.0 * (u * 3.0).coerceAtMost(1.0)).toInt()
        val g = (255.0 * ((u - 1.0 / 3.0) * 3.0).coerceIn(0.0, 1.0)).toInt()
        val b = (255.0 * ((u - 2.0 / 3.0) * 3.0).coerceIn(0.0, 1.0)).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * [PLOT_GLYPHS] at [scale] pixels per dot, top-left at ([x], [y]).
     *
     * The text is upper-cased first, because [PLOT_GLYPHS] carries `A`–`Z` and no lower case, and an
     * unmapped character falls back to a blank. The first version of this figure passed lower-case
     * labels and rendered a panel captioned with nothing but its number — which is exactly the kind of
     * defect only looking at the picture finds.
     */
    private fun drawText(
        pixels: IntArray,
        width: Int,
        height: Int,
        text: String,
        x: Int,
        y: Int,
        scale: Int,
        colour: Int
    ) {
        var cursor = x
        for (ch in text.uppercase()) {
            val glyph = PLOT_GLYPHS[ch] ?: PLOT_GLYPHS[' ']!!
            for (row in glyph.indices) {
                for (col in glyph[row].indices) {
                    if (glyph[row][col] != '#') continue
                    for (dy in 0 until scale) {
                        for (dx in 0 until scale) {
                            pixels.setSafe(width, height, cursor + col * scale + dx, y + row * scale + dy, colour)
                        }
                    }
                }
            }
            cursor += 4 * scale
        }
    }

    private fun IntArray.setSafe(width: Int, height: Int, x: Int, y: Int, colour: Int) {
        if (x < 0 || y < 0 || x >= width || y >= height) return
        this[y * width + x] = colour
    }
}
