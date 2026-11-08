@file:afield.runtime.completion.CompletionCategory("Visualization")

package afield.ui.output

import afield.document.graph.Box
import afield.media.image.Img
import afield.media.image.resize
import afield.media.image.showImgGrid
import afield.media.image.toImg
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Shape-aware ASCII rendering: an [Img] turned into a grid of characters chosen for the *shape* of
 * what falls in each cell, not only its brightness, after Alex Harri's essay
 * [ASCII characters are not pixels](https://alexharri.com/blog/ascii-rendering).
 *
 * Each cell is summarised by six numbers, the mean brightness inside six staggered circles (two
 * columns of three). Every glyph of the charset is rasterised in the same font and summarised the
 * same way, so picking a character is a nearest-neighbour search in six dimensions. Two contrast
 * steps sharpen edges before the search: a global exponent on each cell's vector relative to its own
 * largest component, and a directional one that compares each inner circle with a circle just
 * outside the cell in the same direction.
 */

/** The printable ASCII characters, space to tilde, the default charset for [ShapeAscii]. */
const val ASCII_PRINTABLE: String =
    " !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~"

/**
 * A charset rasterised in one monospaced font, ready to turn images into characters with [convert].
 *
 * ```
 * val ascii = ShapeAscii()
 * val frame = ascii.convert(photo, cols = 80)
 * showAscii("brightness" to ascii.convert(photo, 80, shape = false), "shape" to frame)
 * ```
 *
 * Building one rasterises every glyph once (a few milliseconds); keep it in a `val` and reuse it.
 * [convert] caches matches by quantised shape vector, so a live loop gets faster as it runs.
 * Not thread-safe.
 */
class ShapeAscii(
    /** The characters to choose from; each must be one UTF-16 unit. Space should be among them. */
    val glyphs: String = ASCII_PRINTABLE,
    /** The font size in points the glyphs are rasterised (and [AsciiFrame.toImg] draws) at. */
    val fontSize: Int = 16,
    /** The font family; a monospaced one, or cells and glyphs will not line up. */
    val fontName: String = Font.MONOSPACED,
) {
    /** The font glyphs are rasterised in. */
    val font: Font = Font(fontName, Font.PLAIN, fontSize)

    /** A cell's width in pixels: the advance of `M`. */
    val cellWidth: Int

    /** A cell's height in pixels: the font's line height. */
    val cellHeight: Int

    private val ascent: Int
    private val inner: Array<IntArray>
    private val outer: Array<IntArray>
    private val glyphVectors: Array<DoubleArray>
    private val coverage: DoubleArray
    private val byCoverage: IntArray
    private val cache = HashMap<Int, Int>()

    init {
        require(glyphs.isNotEmpty()) { "ShapeAscii needs at least one glyph" }
        val probe = BufferedImage(1, 1, BufferedImage.TYPE_BYTE_GRAY).createGraphics()
        val fm = probe.getFontMetrics(font)
        cellWidth = max(2, fm.charWidth('M'))
        cellHeight = max(2, fm.height)
        ascent = fm.ascent
        probe.dispose()
        inner = Array(6) { circleOffsets(INNER[it * 2], INNER[it * 2 + 1]) }
        outer = Array(6) {
            val cx = INNER[it * 2]; val cy = INNER[it * 2 + 1]
            circleOffsets(0.5 + (cx - 0.5) * OUTER_PUSH, 0.5 + (cy - 0.5) * OUTER_PUSH)
        }
        val raw = Array(glyphs.length) { rasterVector(glyphs[it]) }
        val norm = DoubleArray(6) { d -> raw.maxOf { it.first[d] }.takeIf { it > 0 } ?: 1.0 }
        glyphVectors = Array(raw.size) { g -> DoubleArray(6) { raw[g].first[it] / norm[it] } }
        val maxCov = raw.maxOf { it.second }.takeIf { it > 0 } ?: 1.0
        coverage = DoubleArray(raw.size) { raw[it].second / maxCov }
        byCoverage = coverage.indices.sortedBy { coverage[it] }.toIntArray()
    }

    /** The normalised six-component shape vector of glyph [index] (each component in `0..1`). */
    fun glyphVector(index: Int): DoubleArray = glyphVectors[index].copyOf()

    /** The ink coverage of glyph [index], normalised so the densest glyph is 1. */
    fun glyphCoverage(index: Int): Double = coverage[index]

    /** Rows of [cols] cells that keep [img]'s aspect ratio, given this font's cell shape. */
    fun rowsFor(img: Img, cols: Int): Int =
        max(1, (cols.toDouble() * img.height / img.width * cellWidth / cellHeight).roundToInt())

    /**
     * Turn [img] into [cols] columns of characters. Brightness (the mean of the colour channels) is
     * read as ink: bright pixels become dense glyphs, as for light text on a dark panel; set
     * [invert] for dark-on-light pictures.
     *
     * With [shape] false each cell takes the glyph whose coverage is nearest its mean brightness, the
     * classic ramp. With [shape] true it takes the glyph nearest its six-circle vector, after
     * [directional] contrast (exponent per inner circle against the circle outside it) and then
     * [contrast] (exponent on each component relative to the cell's largest). 1 leaves a step off;
     * 2 to 4 sharpens edges.
     */
    fun convert(
        img: Img,
        cols: Int,
        shape: Boolean = true,
        contrast: Double = 1.0,
        directional: Double = 1.0,
        invert: Boolean = false,
        rows: Int = rowsFor(img, cols),
    ): AsciiFrame {
        require(cols >= 1 && rows >= 1) { "need at least one cell, got ${cols}x$rows" }
        val w = cols * cellWidth
        val h = rows * cellHeight
        val src = img.resize(w, h)
        val lum = FloatArray(w * h)
        for (i in 0 until w * h) {
            var s = 0f
            val n = if (src.channels == 4) 3 else src.channels
            for (c in 0 until n) s += src.data[i * src.channels + c]
            val v = (s / n).coerceIn(0f, 1f)
            lum[i] = if (invert) 1f - v else v
        }
        val out = IntArray(cols * rows)
        val v = DoubleArray(6)
        val e = DoubleArray(6)
        for (cy in 0 until rows) for (cx in 0 until cols) {
            val x0 = cx * cellWidth
            val y0 = cy * cellHeight
            out[cy * cols + cx] = if (!shape) {
                var s = 0.0
                for (y in 0 until cellHeight) for (x in 0 until cellWidth) s += lum[(y0 + y) * w + x0 + x]
                nearestCoverage(s / (cellWidth * cellHeight))
            } else {
                for (k in 0 until 6) {
                    v[k] = sample(lum, w, h, x0, y0, inner[k])
                    e[k] = if (directional != 1.0) sample(lum, w, h, x0, y0, outer[k]) else 0.0
                }
                enhance(v, e, contrast, directional)
                nearestShape(v)
            }
        }
        return AsciiFrame(this, cols, rows, out)
    }

    /**
     * Apply the two contrast steps to a cell vector [v] in place, given the outer samples [e]:
     * directional first (`v = (v / m)^d * m` with `m = max(v, e)` per component), then global
     * (`v = (v / m)^g * m` with `m` the vector's largest component).
     */
    fun enhance(v: DoubleArray, e: DoubleArray, contrast: Double, directional: Double) {
        if (directional != 1.0) for (k in v.indices) {
            val m = max(v[k], e[k])
            if (m > 0) v[k] = (v[k] / m).pow(directional) * m
        }
        if (contrast != 1.0) {
            val m = v.max()
            if (m > 0) for (k in v.indices) v[k] = (v[k] / m).pow(contrast) * m
        }
    }

    /** The glyph index whose shape vector is nearest [v] (squared Euclidean), through a quantised cache. */
    fun nearestShape(v: DoubleArray): Int {
        var key = 0
        for (k in 0 until 6) key = (key shl 5) or (v[k] * 31).roundToInt().coerceIn(0, 31)
        return cache.getOrPut(key) { bruteNearest(v) }
    }

    /** The glyph index nearest [v] by exhaustive search, no cache: the reference [nearestShape] approximates. */
    fun bruteNearest(v: DoubleArray): Int {
        var best = 0
        var bestD = Double.MAX_VALUE
        for (g in glyphVectors.indices) {
            val gv = glyphVectors[g]
            var d = 0.0
            for (k in 0 until 6) { val t = gv[k] - v[k]; d += t * t }
            if (d < bestD) { bestD = d; best = g }
        }
        return best
    }

    private fun nearestCoverage(b: Double): Int {
        var lo = 0
        var hi = byCoverage.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (coverage[byCoverage[mid]] < b) lo = mid + 1 else hi = mid
        }
        val a = byCoverage[lo]
        if (lo == 0) return a
        val p = byCoverage[lo - 1]
        return if (b - coverage[p] < coverage[a] - b) p else a
    }

    private fun circleOffsets(cx: Double, cy: Double): IntArray {
        val r = RADIUS * cellWidth
        val px = cx * cellWidth
        val py = cy * cellHeight
        val pts = ArrayList<Int>()
        val x0 = (px - r).toInt() - 1
        val y0 = (py - r).toInt() - 1
        for (y in y0..(py + r).toInt() + 1) for (x in x0..(px + r).toInt() + 1) {
            val dx = x + 0.5 - px; val dy = y + 0.5 - py
            if (dx * dx + dy * dy <= r * r) { pts += x; pts += y }
        }
        return pts.toIntArray()
    }

    private fun sample(lum: FloatArray, w: Int, h: Int, x0: Int, y0: Int, offsets: IntArray): Double {
        var s = 0.0
        var i = 0
        while (i < offsets.size) {
            val x = (x0 + offsets[i]).coerceIn(0, w - 1)
            val y = (y0 + offsets[i + 1]).coerceIn(0, h - 1)
            s += lum[y * w + x]
            i += 2
        }
        return s / (offsets.size / 2)
    }

    private fun rasterVector(ch: Char): Pair<DoubleArray, Double> {
        val img = BufferedImage(cellWidth, cellHeight, BufferedImage.TYPE_BYTE_GRAY)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.font = font
        g.drawString(ch.toString(), 0, ascent)
        g.dispose()
        val lum = FloatArray(cellWidth * cellHeight)
        val raster = img.raster
        var total = 0.0
        for (y in 0 until cellHeight) for (x in 0 until cellWidth) {
            val l = raster.getSample(x, y, 0) / 255f
            lum[y * cellWidth + x] = l
            total += l
        }
        val v = DoubleArray(6) { sample(lum, cellWidth, cellHeight, 0, 0, inner[it]) }
        return v to total / (cellWidth * cellHeight)
    }

    /** Draw [frame]'s characters as an image, light on [background], one cell per character. */
    internal fun draw(frame: AsciiFrame, ink: Color, background: Color): Img {
        val img = BufferedImage(frame.cols * cellWidth, frame.rows * cellHeight, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = background
        g.fillRect(0, 0, img.width, img.height)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = ink
        g.font = font
        for (y in 0 until frame.rows) for (x in 0 until frame.cols) {
            val c = frame.char(x, y)
            if (c != ' ') g.drawString(c.toString(), x * cellWidth, y * cellHeight + ascent)
        }
        g.dispose()
        return img.toImg(3)
    }

    private companion object {
        /** Centres of the six inner circles, (x, y) in cell units: two columns, staggered vertically. */
        val INNER = doubleArrayOf(
            0.28, 0.20, 0.72, 0.13,
            0.28, 0.53, 0.72, 0.47,
            0.28, 0.87, 0.72, 0.80,
        )

        /** Circle radius as a fraction of the cell width. */
        const val RADIUS = 0.24

        /** How far from the cell centre an outer circle sits, as a multiple of its inner circle's offset. */
        const val OUTER_PUSH = 2.6
    }
}

/**
 * The characters [ShapeAscii.convert] chose: [cols] × [rows] glyph indices into [ascii]'s charset.
 * [text] gives it as lines, [toImg] draws it in the font it was matched against, and inside a
 * [charGrid] block `drawAscii(frame)` writes it into the grid.
 */
class AsciiFrame internal constructor(
    /** The charset and font the characters were matched against. */
    val ascii: ShapeAscii,
    /** Characters across. */
    val cols: Int,
    /** Characters down. */
    val rows: Int,
    /** Glyph indices into `ascii.glyphs`, row-major. */
    val indices: IntArray,
) {
    /** The character at column [x], row [y]. */
    fun char(x: Int, y: Int): Char = ascii.glyphs[indices[y * cols + x]]

    /** The frame as [rows] lines of [cols] characters. */
    fun text(): String = (0 until rows).joinToString("\n") { y -> String(CharArray(cols) { char(it, y) }) }

    /** Draw the characters as an image in [ascii]'s font, [ink] on [background]. */
    fun toImg(ink: Color = Color(0xE8, 0xE8, 0xE8), background: Color = Color(0x16, 0x16, 0x1A)): Img =
        ascii.draw(this, ink, background)

    override fun toString(): String = text()
}

/**
 * Write [frame] into this char grid from its top-left corner, each character looked up in the grid's
 * charset (pass `glyphs = ascii.glyphs` to [charGrid] so they all exist). Cells past the grid's edge
 * are dropped.
 */
fun CharGridScope.drawAscii(frame: AsciiFrame, color: String? = null) {
    for (y in 0 until frame.rows) for (x in 0 until frame.cols) write(x, y, glyphIndex(frame.char(x, y)), color, null)
}

/**
 * Show ASCII frames side by side as images, each captioned, drawn in the font they were matched
 * against (so what you see is exactly what was chosen).
 */
fun Box.showAscii(vararg panels: Pair<String, AsciiFrame>, channel: String = "main", columns: Int = panels.size, cellSide: Int = 420) {
    showImgGrid(*panels.map { it.first to it.second.toImg() }.toTypedArray(), channel = channel, columns = columns, cellSide = cellSide)
}
