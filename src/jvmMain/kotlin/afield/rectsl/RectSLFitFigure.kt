package afield.rectsl

import afield.Color4
import afield.util.PngEncoder

/**
 * A fit, as a picture, with its counterfactual beside it.
 *
 * A residual is a number and a number is easy to be wrong about. The strongest evidence this project
 * produces is a rendered image with the *before* in it: three stacked bands — where the rect was
 * asked to be, where the seed parameters actually put it, and where the solved parameters put it —
 * so the reader can see the miss and then see it closed, in one glance, without taking anyone's word
 * for the arithmetic.
 *
 * This is a CPU raster on purpose. The GPU path renders a RectSL scene beautifully and needs a
 * device, a window and a render target; this needs to run in a unit test and inside a Field box, and
 * what it draws — the actual rects, sampled from the actual tracks, at the actual times — is the
 * same truth. Nothing here is synthesized: every rectangle on the canvas is one [SampledRect]'s
 * worth of geometry, and if the fit had not worked the third band would show it.
 *
 * There is no text primitive anywhere near this code (RectSL has none either — its axis labels are
 * grids of rects), so the band labels are drawn from [PLOT_GLYPHS], the same 3×5 dot-matrix font the
 * plotting words use for tick numerals.
 */
object RectSLFitFigure {

    /** One band of the figure: a pair of position tracks, strobed across time in one colour. */
    data class Band(
        val label: String,
        val x: Track<Double>,
        val y: Track<Double>,
        val colour: Color4,
        /** A measured number to print beside the label — the figure should carry its own evidence. */
        val note: String = "",
        /** False for a band that shows only the markers, which is what "target" means. */
        val strobe: Boolean = true,
        /**
         * The rect's rotation in radians at a time, or null for an axis-aligned strobe.
         *
         * A **function of time** rather than a [Track] on purpose: the pair most worth drawing here
         * is a [HeadingTrack] beside the lowered `Fn` of that same heading, and the second of those
         * is not a track at all. `track::sample` and `fn::eval` both fit.
         *
         * It matters that a figure can show this at all. A heading is the one channel where a
         * lowering can be wrong by half a turn while every number it produces stays plausible, and
         * half a turn is something an eye catches instantly and a tolerance does not.
         */
        val rotation: ((Double) -> Double)? = null
    )

    /** A place the animation was asked to pass through, drawn as an outlined square on every band. */
    data class Marker(val time: Double, val x: Double, val y: Double)

    /** The figure's palette, dark like the rest of Field's captures. */
    private const val BACKGROUND = 0xFF0F1115.toInt()
    private const val PANEL = 0xFF16191F.toInt()
    private const val RULE = 0xFF2A2F38.toInt()
    private const val LABEL = 0xFFDCE1E8.toInt()
    private const val MARKER = 0xFFFFFFFF.toInt()

    /**
     * Renders [bands] as stacked panels over the wall, strobing each at [times] and marking
     * [markers] on all of them.
     *
     * @return PNG bytes, ready for a file or for `display()` in a Field box.
     */
    fun render(
        bands: List<Band>,
        markers: List<Marker>,
        wall: WallSpec,
        rectSize: Vec2d,
        times: List<Double>,
        width: Int = 980,
        bandHeight: Int = 250,
        pad: Int = 14,
        // How much more than the wall to show. A solved animation is under no obligation to stay
        // inside the wall, and a figure that clips the answer is worse than one that is not to
        // scale — so the default shows a fifth more, with the wall's own bounds drawn as a rule.
        viewScale: Double = 1.2
    ): ByteArray {
        require(bands.isNotEmpty()) { "a figure needs at least one band" }
        require(times.isNotEmpty()) { "a figure needs at least one strobe time" }
        val height = pad + bands.size * (bandHeight + pad)
        val pixels = IntArray(width * height) { BACKGROUND }

        bands.forEachIndexed { index, band ->
            val top = pad + index * (bandHeight + pad)
            drawBand(pixels, width, top, bandHeight, pad, band, markers, wall, rectSize, times, viewScale)
        }
        return PngEncoder.encode(pixels, width, height)
    }

    private fun drawBand(
        pixels: IntArray,
        width: Int,
        top: Int,
        bandHeight: Int,
        pad: Int,
        band: Band,
        markers: List<Marker>,
        wall: WallSpec,
        rectSize: Vec2d,
        times: List<Double>,
        viewScale: Double
    ) {
        val left = pad
        val right = width - pad
        val bottom = top + bandHeight
        fillRect(pixels, width, left, top, right, bottom, PANEL)

        // The wall, letterboxed into the panel so the aspect is honest.
        val panelW = (right - left).toDouble()
        val panelH = (bottom - top).toDouble()
        val scale = minOf(panelW / (wall.width * viewScale), panelH / (wall.height * viewScale))
        val cx = (left + right) / 2.0
        val cy = (top + bottom) / 2.0
        fun px(x: Double): Double = cx + x * scale
        fun py(y: Double): Double = cy - y * scale

        // Centre rules, so a reader can see where the origin is without a legend, and the wall's
        // own edge, so an excursion past it is visibly an excursion.
        drawHLine(pixels, width, left + 1, right - 1, py(0.0).toInt(), RULE)
        drawVLine(pixels, width, px(0.0).toInt(), top + 1, bottom - 1, RULE)
        outlineRect(
            pixels, width,
            px(-wall.width / 2.0).toInt(), py(wall.height / 2.0).toInt(),
            px(wall.width / 2.0).toInt(), py(-wall.height / 2.0).toInt(),
            left + 1, top + 1, right - 1, bottom - 1,
            RULE, 1
        )

        val n = times.size
        if (band.strobe) times.forEachIndexed { i, t ->
            // Older instants fainter, so the direction of travel reads without arrows.
            val fade = 0.25 + 0.75 * (if (n <= 1) 1.0 else i.toDouble() / (n - 1))
            val x = band.x.sample(t)
            val y = band.y.sample(t)
            val halfW = rectSize.x * 0.5 * scale
            val halfH = rectSize.y * 0.5 * scale
            val rotation = band.rotation?.invoke(t)
            if (rotation == null) {
                fillRectClipped(
                    pixels, width,
                    (px(x) - halfW).toInt(), (py(y) - halfH).toInt(),
                    (px(x) + halfW).toInt(), (py(y) + halfH).toInt(),
                    left + 1, top + 1, right - 1, bottom - 1,
                    blend(band.colour, fade)
                )
            } else {
                fillRotatedRectClipped(
                    pixels, width,
                    px(x), py(y), halfW, halfH, rotation,
                    left + 1, top + 1, right - 1, bottom - 1,
                    blend(band.colour, fade)
                )
                // A nose pip, ahead of the body along its own local +x. Without it the glyph is a
                // rectangle, and a rectangle is *symmetric under a half turn* — which is precisely
                // the error a heading lowering makes when it loses a quadrant, so the one picture
                // that has to show that error would be the one picture that cannot. The pip is what
                // makes "pointing backwards" visible, to a reader and to a pixel diff alike.
                val nose = kotlin.math.max(halfH * 1.6, 3.0)
                fillRotatedRectClipped(
                    pixels, width,
                    px(x) + (halfW + nose) * kotlin.math.cos(rotation),
                    py(y) - (halfW + nose) * kotlin.math.sin(rotation),
                    nose, nose, rotation,
                    left + 1, top + 1, right - 1, bottom - 1,
                    blend(band.colour, fade)
                )
            }
        }

        for (marker in markers) {
            val halfW = rectSize.x * 0.5 * scale + 4.0
            val halfH = rectSize.y * 0.5 * scale + 4.0
            outlineRect(
                pixels, width,
                (px(marker.x) - halfW).toInt(), (py(marker.y) - halfH).toInt(),
                (px(marker.x) + halfW).toInt(), (py(marker.y) + halfH).toInt(),
                left + 1, top + 1, right - 1, bottom - 1,
                MARKER, 2
            )
        }

        drawText(pixels, width, band.label.uppercase(), left + 10, top + 10, 3, LABEL)
        if (band.note.isNotEmpty()) {
            drawText(
                pixels, width, band.note.uppercase(),
                left + 10 + 4 * 3 * (band.label.length + 2), top + 10, 3, blend(band.colour, 0.95)
            )
        }
    }

    private fun blend(colour: Color4, alpha: Double): Int {
        val a = (alpha.coerceIn(0.0, 1.0) * 255.0).toInt()
        val r = (colour.r.coerceIn(0.0, 1.0) * 255.0).toInt()
        val g = (colour.g.coerceIn(0.0, 1.0) * 255.0).toInt()
        val b = (colour.b.coerceIn(0.0, 1.0) * 255.0).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Source-over composite, so a strobe reads as overlapping translucent rects. */
    private fun over(dst: Int, src: Int) : Int {
        val sa = (src ushr 24) and 0xFF
        if (sa == 255) return src
        if (sa == 0) return dst
        val inv = 255 - sa
        fun mix(shift: Int): Int =
            (((src ushr shift) and 0xFF) * sa + ((dst ushr shift) and 0xFF) * inv) / 255
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private fun fillRect(pixels: IntArray, width: Int, x0: Int, y0: Int, x1: Int, y1: Int, colour: Int) {
        val height = pixels.size / width
        for (y in y0.coerceAtLeast(0) until y1.coerceAtMost(height)) {
            for (x in x0.coerceAtLeast(0) until x1.coerceAtMost(width)) {
                pixels[y * width + x] = colour
            }
        }
    }

    private fun fillRectClipped(
        pixels: IntArray, width: Int,
        x0: Int, y0: Int, x1: Int, y1: Int,
        clipX0: Int, clipY0: Int, clipX1: Int, clipY1: Int,
        colour: Int
    ) {
        for (y in maxOf(y0, clipY0) until minOf(y1, clipY1)) {
            for (x in maxOf(x0, clipX0) until minOf(x1, clipX1)) {
                val i = y * width + x
                pixels[i] = over(pixels[i], colour)
            }
        }
    }

    /**
     * A rect rotated by [angle] radians about ([cx], [cy]), filled by half-plane test.
     *
     * Screen y grows downward while a RectSL rotation is counter-clockwise in world space, so the
     * rotation is applied with the sine negated — the same handedness flip [drawBand]'s `py` makes
     * for positions. Get it wrong and every dart mirrors about the horizontal, which
     * `RectSLHeadingFigureTest` would catch as a heading pointing the wrong side of its own path.
     *
     * No anti-aliasing, because the figure is read by a test as well as by a person and a hard edge
     * is a countable one.
     */
    private fun fillRotatedRectClipped(
        pixels: IntArray, width: Int,
        cx: Double, cy: Double, halfW: Double, halfH: Double, angle: Double,
        clipX0: Int, clipY0: Int, clipX1: Int, clipY1: Int,
        colour: Int
    ) {
        val cos = kotlin.math.cos(angle)
        val sin = -kotlin.math.sin(angle)
        val reach = kotlin.math.hypot(halfW, halfH)
        val x0 = maxOf((cx - reach).toInt(), clipX0)
        val x1 = minOf((cx + reach).toInt() + 1, clipX1)
        val y0 = maxOf((cy - reach).toInt(), clipY0)
        val y1 = minOf((cy + reach).toInt() + 1, clipY1)
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val dx = x + 0.5 - cx
                val dy = y + 0.5 - cy
                // Rotate the sample point back into the rect's own frame, then test the two axes.
                val local = dx * cos + dy * sin
                val across = -dx * sin + dy * cos
                if (kotlin.math.abs(local) > halfW || kotlin.math.abs(across) > halfH) continue
                val i = y * width + x
                pixels[i] = over(pixels[i], colour)
            }
        }
    }

    private fun outlineRect(
        pixels: IntArray, width: Int,
        x0: Int, y0: Int, x1: Int, y1: Int,
        clipX0: Int, clipY0: Int, clipX1: Int, clipY1: Int,
        colour: Int, thickness: Int
    ) {
        for (t in 0 until thickness) {
            drawHLineClipped(pixels, width, x0 + t, x1 - t, y0 + t, clipX0, clipY0, clipX1, clipY1, colour)
            drawHLineClipped(pixels, width, x0 + t, x1 - t, y1 - 1 - t, clipX0, clipY0, clipX1, clipY1, colour)
            drawVLineClipped(pixels, width, x0 + t, y0 + t, y1 - t, clipX0, clipY0, clipX1, clipY1, colour)
            drawVLineClipped(pixels, width, x1 - 1 - t, y0 + t, y1 - t, clipX0, clipY0, clipX1, clipY1, colour)
        }
    }

    private fun drawHLine(pixels: IntArray, width: Int, x0: Int, x1: Int, y: Int, colour: Int) {
        val height = pixels.size / width
        if (y < 0 || y >= height) return
        for (x in x0.coerceAtLeast(0) until x1.coerceAtMost(width)) pixels[y * width + x] = colour
    }

    private fun drawVLine(pixels: IntArray, width: Int, x: Int, y0: Int, y1: Int, colour: Int) {
        val height = pixels.size / width
        if (x < 0 || x >= width) return
        for (y in y0.coerceAtLeast(0) until y1.coerceAtMost(height)) pixels[y * width + x] = colour
    }

    private fun drawHLineClipped(
        pixels: IntArray, width: Int, x0: Int, x1: Int, y: Int,
        clipX0: Int, clipY0: Int, clipX1: Int, clipY1: Int, colour: Int
    ) {
        if (y < clipY0 || y >= clipY1) return
        for (x in maxOf(x0, clipX0) until minOf(x1, clipX1)) pixels[y * width + x] = colour
    }

    private fun drawVLineClipped(
        pixels: IntArray, width: Int, x: Int, y0: Int, y1: Int,
        clipX0: Int, clipY0: Int, clipX1: Int, clipY1: Int, colour: Int
    ) {
        if (x < clipX0 || x >= clipX1) return
        for (y in maxOf(y0, clipY0) until minOf(y1, clipY1)) pixels[y * width + x] = colour
    }

    /** [PLOT_GLYPHS] at [scale] pixels per dot, top-left at ([x], [y]). */
    private fun drawText(
        pixels: IntArray, width: Int, text: String, x: Int, y: Int, scale: Int, colour: Int
    ) {
        var cursor = x
        for (ch in text) {
            val glyph = PLOT_GLYPHS[ch] ?: PLOT_GLYPHS[' ']!!
            for (row in glyph.indices) {
                for (col in glyph[row].indices) {
                    if (glyph[row][col] != '#') continue
                    fillRect(
                        pixels, width,
                        cursor + col * scale, y + row * scale,
                        cursor + (col + 1) * scale, y + (row + 1) * scale,
                        colour
                    )
                }
            }
            cursor += 4 * scale
        }
    }
}
