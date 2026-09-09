@file:afield.CompletionCategory("Simulation")
package afield.kratos

import afield.Colormaps
import afield.RasterImage
import afield.rectsl.PLOT_GLYPHS
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * A [StudyLandscape] and a [StudyOptimisation], as a picture.
 *
 * A sweep's result is a table of numbers and a table of numbers is easy to be wrong about. What a
 * study is *for* is the shape — where the valley runs, whether the optimum is interior or pinned to
 * an edge, whether the search found the same place the grid did. All of that is one glance and none
 * of it is a summary line.
 *
 * A CPU raster on purpose, through [RasterImage]: no AWT, no window, no GPU, so the same code makes
 * the figure inside a Field box, inside a `jvm-main` verification run and inside a unit test. Text
 * is [PLOT_GLYPHS], the repository's 3x5 dot-matrix font — one font, in one place, rather than a
 * third copy of the same table. Its alphabet is upper case, digits and a handful of punctuation, so
 * labels are upper-cased on the way in and anything outside that alphabet draws as a blank —
 * [drawable] is how a caller checks a label before the figure disappoints it.
 *
 * # Reading the landscape panel
 *
 * Axis 0 runs up the left edge, axis 1 along the bottom, which is the `[row][col]` reading of
 * [StudyLandscape.matrix]. Cells are drawn as cells — no bilinear smoothing — because a study's
 * resolution is a real property of the answer and a smoothed picture invents a landscape between
 * sample points that nobody solved. Infeasible points are hatched grey, not coloured zero. The
 * grid's best cell gets a white ring; an optimiser's best, when one is supplied, gets a cross, so
 * agreement between the two methods is visible rather than asserted.
 */
object KratosStudyFigure {

    private const val BACKGROUND = 0xFF0F1115.toInt()
    private const val PANEL = 0xFF16191F.toInt()
    private const val RULE = 0xFF2A2F38.toInt()
    private const val LABEL = 0xFFDCE1E8.toInt()
    private const val DIM = 0xFF8A94A6.toInt()
    private const val MARKER = 0xFFFFFFFF.toInt()
    private const val INFEASIBLE = 0xFF3A3F49.toInt()
    private const val SEARCH = 0xFF63D2FF.toInt()
    private const val CONTROL = 0xFFFF9F5A.toInt()

    /** The landscape alone, as pixels — the form a test can count distinct colours in. */
    fun landscapeRaster(
        landscape: StudyLandscape,
        optimisation: StudyOptimisation<*>? = null,
        width: Int = 900,
        height: Int = 560,
        colormap: String = "viridis",
    ): RasterImage {
        val pixels = IntArray(width * height) { BACKGROUND }
        drawLandscape(pixels, width, 0, 0, width, height, landscape, optimisation, colormap)
        return RasterImage(pixels, width, height)
    }

    /** The landscape alone, as PNG bytes. */
    fun landscape(
        landscape: StudyLandscape,
        optimisation: StudyOptimisation<*>? = null,
        width: Int = 900,
        height: Int = 560,
        colormap: String = "viridis",
    ): ByteArray = landscapeRaster(landscape, optimisation, width, height, colormap).png()

    /** The convergence panel alone, as pixels. */
    fun convergenceRaster(
        optimisation: StudyOptimisation<*>,
        gridBest: Double? = null,
        width: Int = 900,
        height: Int = 420,
    ): RasterImage {
        val pixels = IntArray(width * height) { BACKGROUND }
        drawConvergence(pixels, width, 0, 0, width, height, optimisation, gridBest)
        return RasterImage(pixels, width, height)
    }

    /** The convergence panel alone, as PNG bytes. */
    fun convergence(
        optimisation: StudyOptimisation<*>,
        gridBest: Double? = null,
        width: Int = 900,
        height: Int = 420,
    ): ByteArray = convergenceRaster(optimisation, gridBest, width, height).png()

    /**
     * Both panels stacked: the landscape the grid measured, and the search that walked it.
     *
     * One image rather than two because the claim being made is a *comparison* — "the search reached
     * the place the grid found, in a fifth of the solves" — and a claim spread over two files is one
     * a reader has to assemble themselves.
     */
    fun sheetRaster(
        landscape: StudyLandscape,
        optimisation: StudyOptimisation<*>? = null,
        width: Int = 980,
        landscapeHeight: Int = 560,
        convergenceHeight: Int = 360,
        colormap: String = "viridis",
    ): RasterImage {
        val height = landscapeHeight + (if (optimisation == null) 0 else convergenceHeight)
        val pixels = IntArray(width * height) { BACKGROUND }
        drawLandscape(pixels, width, 0, 0, width, landscapeHeight, landscape, optimisation, colormap)
        if (optimisation != null) {
            drawConvergence(
                pixels, width, 0, landscapeHeight, width, convergenceHeight,
                optimisation, landscape.best?.objective,
            )
        }
        return RasterImage(pixels, width, height)
    }

    /** [sheetRaster], as PNG bytes. */
    fun sheet(
        landscape: StudyLandscape,
        optimisation: StudyOptimisation<*>? = null,
        width: Int = 980,
        landscapeHeight: Int = 560,
        convergenceHeight: Int = 360,
        colormap: String = "viridis",
    ): ByteArray =
        sheetRaster(landscape, optimisation, width, landscapeHeight, convergenceHeight, colormap).png()

    // ------------------------------------------------------------------------------------------
    // The landscape
    // ------------------------------------------------------------------------------------------

    private fun drawLandscape(
        pixels: IntArray, stride: Int, x0: Int, y0: Int, w: Int, h: Int,
        landscape: StudyLandscape, optimisation: StudyOptimisation<*>?, colormap: String,
    ) {
        require(landscape.dimension in 1..2) {
            "a landscape figure draws one or two axes; this study has ${landscape.dimension}"
        }
        val title = "${landscape.objectiveName} - ${landscape.goal.name}".uppercase()
        text(pixels, stride, x0 + 18, y0 + 14, title, LABEL, 2)
        val caption = "%d/%d FEASIBLE  %.1f S OF SOLVER"
            .format(landscape.feasible.size, landscape.samples.size,
                landscape.samples.sumOf { it.solveMs } / 1000.0)
        text(pixels, stride, x0 + 18, y0 + 36, caption, DIM, 1)

        // Room for the y tick labels on the left, the colour bar on the right, x ticks below.
        val left = x0 + 96
        val right = x0 + w - 108
        val top = y0 + 58
        val bottom = y0 + h - 54
        fillRect(pixels, stride, left, top, right, bottom, PANEL)

        if (landscape.dimension == 1) {
            drawProfile(pixels, stride, left, top, right, bottom, landscape, optimisation)
            axisCaption(pixels, stride, x0, w, bottom, landscape.axes[0].label, landscape.objectiveName)
            return
        }

        val rows = landscape.axes[0]
        val cols = landscape.axes[1]
        val (low, high) = landscape.range()
        val span = (high - low).takeIf { it > 1e-15 } ?: 1.0
        val lut = IntArray(512) { argb(Colormaps.sample(colormap, it / 511.0)) }

        // Cell (r, c): axis 0 up the left edge, so row 0 sits at the BOTTOM.
        val cellW = (right - left).toDouble() / cols.size
        val cellH = (bottom - top).toDouble() / rows.size
        for (r in 0 until rows.size) for (c in 0 until cols.size) {
            val sample = landscape.at(r, c)
            val px0 = left + (c * cellW).roundToInt()
            val px1 = left + ((c + 1) * cellW).roundToInt()
            val py1 = bottom - (r * cellH).roundToInt()
            val py0 = bottom - ((r + 1) * cellH).roundToInt()
            val value = sample.objective
            if (value == null) {
                fillRect(pixels, stride, px0, py0, px1, py1, INFEASIBLE)
                hatch(pixels, stride, px0, py0, px1, py1, DIM)
            } else {
                val t = ((value - low) / span).coerceIn(0.0, 1.0)
                fillRect(pixels, stride, px0, py0, px1, py1, lut[(t * 511).roundToInt()])
            }
        }
        frame(pixels, stride, left, top, right, bottom, RULE)

        fun px(c: Double) = left + (c + 0.5) * cellW
        fun py(r: Double) = bottom - (r + 0.5) * cellH

        // Where the grid says the optimum is.
        landscape.best?.let { winner ->
            val r = nearest(rows.values, winner.coordinates[0])
            val c = nearest(cols.values, winner.coordinates[1])
            ring(pixels, stride, px(c.toDouble()).roundToInt(), py(r.toDouble()).roundToInt(),
                (minOf(cellW, cellH) * 0.42).roundToInt().coerceAtLeast(4), MARKER)
        }
        // And where the search says it is, in continuous coordinates rather than snapped to a cell.
        optimisation?.best?.let { winner ->
            val fr = fractional(rows.values, winner.coordinates[0])
            val fc = fractional(cols.values, winner.coordinates[1])
            cross(pixels, stride, px(fc).roundToInt(), py(fr).roundToInt(), 9, SEARCH)
        }

        // Ticks. Every label the font can draw, thinned to fit.
        val yEvery = tickStride(rows.size, (bottom - top) / 26)
        for (r in 0 until rows.size step yEvery) {
            val y = py(r.toDouble()).roundToInt()
            hLine(pixels, stride, left - 6, left, y, RULE)
            text(pixels, stride, left - 10 - textWidth(number(rows.values[r]), 1), y - 3,
                number(rows.values[r]), DIM, 1)
        }
        val xEvery = tickStride(cols.size, (right - left) / 44)
        for (c in 0 until cols.size step xEvery) {
            val x = px(c.toDouble()).roundToInt()
            vLine(pixels, stride, x, bottom, bottom + 6, RULE)
            text(pixels, stride, x - textWidth(number(cols.values[c]), 1) / 2, bottom + 10,
                number(cols.values[c]), DIM, 1)
        }
        text(pixels, stride, left, bottom + 28, cols.label.uppercase(), LABEL, 1)
        text(pixels, stride, x0 + 18, top - 12, rows.label.uppercase(), LABEL, 1)

        colorBar(pixels, stride, right + 22, top, right + 46, bottom, lut, low, high)
    }

    /** A one-axis study: the objective as a line, with the infeasible points marked on the floor. */
    private fun drawProfile(
        pixels: IntArray, stride: Int, left: Int, top: Int, right: Int, bottom: Int,
        landscape: StudyLandscape, optimisation: StudyOptimisation<*>?,
    ) {
        val axis = landscape.axes[0]
        val (low, high) = landscape.range()
        val span = (high - low).takeIf { it > 1e-15 } ?: 1.0
        fun px(i: Int) = left + (right - left) * (if (axis.size == 1) 0.5 else i / (axis.size - 1.0))
        fun py(v: Double) = bottom - (bottom - top) * ((v - low) / span) * 0.92 - (bottom - top) * 0.04

        var previous: Pair<Int, Int>? = null
        for (i in 0 until axis.size) {
            val sample = landscape.at(i)
            val x = px(i).roundToInt()
            val value = sample.objective
            if (value == null) {
                vLine(pixels, stride, x, bottom - 6, bottom, INFEASIBLE)
                previous = null
                continue
            }
            val y = py(value).roundToInt()
            previous?.let { line(pixels, stride, it.first, it.second, x, y, SEARCH) }
            dot(pixels, stride, x, y, 2, MARKER)
            previous = x to y
        }
        text(pixels, stride, left + 6, top + 6, "${number(high)} MAX", DIM, 1)
        text(pixels, stride, left + 6, bottom - 16, "${number(low)} MIN", DIM, 1)
        landscape.best?.let { winner ->
            ring(pixels, stride, px(nearest(axis.values, winner.coordinates[0])).roundToInt(),
                py(winner.objective!!).roundToInt(), 7, MARKER)
        }
        optimisation?.best?.let { winner ->
            winner.objective?.let { v ->
                cross(pixels, stride, px(nearest(axis.values, winner.coordinates[0])).roundToInt(),
                    py(v).roundToInt(), 8, CONTROL)
            }
        }
    }

    private fun axisCaption(
        pixels: IntArray, stride: Int, x0: Int, w: Int, bottom: Int, xLabel: String, yLabel: String,
    ) {
        text(pixels, stride, x0 + 96, bottom + 20, xLabel.uppercase(), LABEL, 1)
        text(pixels, stride, x0 + 18, bottom + 34, yLabel.uppercase(), DIM, 1)
    }

    // ------------------------------------------------------------------------------------------
    // Convergence
    // ------------------------------------------------------------------------------------------

    private fun drawConvergence(
        pixels: IntArray, stride: Int, x0: Int, y0: Int, w: Int, h: Int,
        optimisation: StudyOptimisation<*>, gridBest: Double?,
    ) {
        text(pixels, stride, x0 + 18, y0 + 12, "BEST SO FAR PER EVALUATION", LABEL, 2)
        val left = x0 + 96
        val right = x0 + w - 108
        val top = y0 + 40
        val bottom = y0 + h - 48
        fillRect(pixels, stride, left, top, right, bottom, PANEL)
        frame(pixels, stride, left, top, right, bottom, RULE)

        val series = buildList {
            add(Triple("SEARCH", optimisation.trace(optimisation.run), SEARCH))
            optimisation.control?.let { add(Triple("RANDOM", optimisation.trace(it), CONTROL)) }
        }.filter { it.second.isNotEmpty() && it.second.any { v -> v.isFinite() } }
        if (series.isEmpty()) {
            text(pixels, stride, left + 12, top + 12, "NO FINITE EVALUATIONS", DIM, 1)
            return
        }

        val values = series.flatMap { it.second.toList() }.filter { it.isFinite() } +
            listOfNotNull(gridBest)
        var low = values.min()
        var high = values.max()
        if (high - low < 1e-12) { low -= 0.5; high += 0.5 }
        val pad = (high - low) * 0.08
        low -= pad; high += pad
        val longest = series.maxOf { it.second.size }

        fun px(i: Int) = left + (right - left) * (if (longest <= 1) 0.5 else i / (longest - 1.0))
        fun py(v: Double) = bottom - (bottom - top) * ((v - low) / (high - low))

        gridBest?.let { g ->
            val y = py(g).roundToInt()
            dashedHLine(pixels, stride, left, right, y, DIM)
            text(pixels, stride, left + 6, y - 10, "GRID BEST ${number(g)}", DIM, 1)
        }
        for ((label, trace, colour) in series) {
            var previous: Pair<Int, Int>? = null
            for (i in trace.indices) {
                if (!trace[i].isFinite()) continue
                val x = px(i).roundToInt()
                val y = py(trace[i]).roundToInt().coerceIn(top, bottom)
                previous?.let { line(pixels, stride, it.first, it.second, x, y, colour) }
                previous = x to y
            }
            previous?.let { dot(pixels, stride, it.first, it.second, 3, colour) }
            val index = series.indexOfFirst { it.first == label }
            val finished = trace.lastOrNull { it.isFinite() }
            text(pixels, stride, left + 10, top + 8 + index * 12,
                "$label ${if (finished == null) "-" else number(finished)}", colour, 1)
        }

        for (k in 0..4) {
            val v = low + (high - low) * k / 4.0
            val y = py(v).roundToInt()
            hLine(pixels, stride, left - 6, left, y, RULE)
            text(pixels, stride, left - 10 - textWidth(number(v), 1), y - 3, number(v), DIM, 1)
        }
        for (k in 0..4) {
            val i = ((longest - 1) * k / 4.0).roundToInt()
            val x = px(i).roundToInt()
            vLine(pixels, stride, x, bottom, bottom + 6, RULE)
            text(pixels, stride, x - textWidth("$i", 1) / 2, bottom + 10, "$i", DIM, 1)
        }
        text(pixels, stride, left, bottom + 26, "EVALUATIONS", LABEL, 1)
    }

    // ------------------------------------------------------------------------------------------
    // Raster primitives
    // ------------------------------------------------------------------------------------------

    private fun colorBar(
        pixels: IntArray, stride: Int, left: Int, top: Int, right: Int, bottom: Int,
        lut: IntArray, low: Double, high: Double,
    ) {
        for (y in top until bottom) {
            val t = 1.0 - (y - top).toDouble() / (bottom - top - 1).coerceAtLeast(1)
            val colour = lut[(t * (lut.size - 1)).roundToInt().coerceIn(0, lut.size - 1)]
            hLine(pixels, stride, left, right, y, colour)
        }
        frame(pixels, stride, left, top, right, bottom, RULE)
        text(pixels, stride, right + 6, top - 3, number(high), DIM, 1)
        text(pixels, stride, right + 6, bottom - 6, number(low), DIM, 1)
    }

    private fun argb(c: afield.Color4): Int {
        fun ch(x: Double): Int = (x.coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt()
        return (0xFF shl 24) or (ch(c.r) shl 16) or (ch(c.g) shl 8) or ch(c.b)
    }

    private fun set(pixels: IntArray, stride: Int, x: Int, y: Int, colour: Int) {
        if (x < 0 || y < 0 || x >= stride) return
        val at = y * stride + x
        if (at < 0 || at >= pixels.size) return
        pixels[at] = colour
    }

    private fun fillRect(pixels: IntArray, stride: Int, x0: Int, y0: Int, x1: Int, y1: Int, colour: Int) {
        for (y in y0 until y1) for (x in x0 until x1) set(pixels, stride, x, y, colour)
    }

    private fun frame(pixels: IntArray, stride: Int, x0: Int, y0: Int, x1: Int, y1: Int, colour: Int) {
        hLine(pixels, stride, x0, x1, y0, colour); hLine(pixels, stride, x0, x1, y1 - 1, colour)
        vLine(pixels, stride, x0, y0, y1, colour); vLine(pixels, stride, x1 - 1, y0, y1, colour)
    }

    private fun hLine(pixels: IntArray, stride: Int, x0: Int, x1: Int, y: Int, colour: Int) {
        for (x in minOf(x0, x1)..maxOf(x0, x1)) set(pixels, stride, x, y, colour)
    }

    private fun dashedHLine(pixels: IntArray, stride: Int, x0: Int, x1: Int, y: Int, colour: Int) {
        for (x in minOf(x0, x1)..maxOf(x0, x1)) if ((x / 5) % 2 == 0) set(pixels, stride, x, y, colour)
    }

    private fun vLine(pixels: IntArray, stride: Int, x: Int, y0: Int, y1: Int, colour: Int) {
        for (y in minOf(y0, y1)..maxOf(y0, y1)) set(pixels, stride, x, y, colour)
    }

    private fun line(pixels: IntArray, stride: Int, x0: Int, y0: Int, x1: Int, y1: Int, colour: Int) {
        val steps = maxOf(abs(x1 - x0), abs(y1 - y0)).coerceAtLeast(1)
        for (s in 0..steps) {
            val f = s.toDouble() / steps
            val x = (x0 + (x1 - x0) * f).roundToInt()
            val y = (y0 + (y1 - y0) * f).roundToInt()
            set(pixels, stride, x, y, colour)
            set(pixels, stride, x, y + 1, colour)
        }
    }

    private fun dot(pixels: IntArray, stride: Int, x: Int, y: Int, radius: Int, colour: Int) {
        for (dy in -radius..radius) for (dx in -radius..radius) {
            if (dx * dx + dy * dy <= radius * radius) set(pixels, stride, x + dx, y + dy, colour)
        }
    }

    private fun ring(pixels: IntArray, stride: Int, x: Int, y: Int, radius: Int, colour: Int) {
        for (dy in -radius..radius) for (dx in -radius..radius) {
            val d = dx * dx + dy * dy
            if (d <= radius * radius && d >= (radius - 2) * (radius - 2)) set(pixels, stride, x + dx, y + dy, colour)
        }
    }

    private fun cross(pixels: IntArray, stride: Int, x: Int, y: Int, arm: Int, colour: Int) {
        for (d in -arm..arm) {
            set(pixels, stride, x + d, y, colour); set(pixels, stride, x + d, y + 1, colour)
            set(pixels, stride, x, y + d, colour); set(pixels, stride, x + 1, y + d, colour)
        }
    }

    /** Diagonal hatching, so an infeasible cell reads as "no answer" and not as "a dark answer". */
    private fun hatch(pixels: IntArray, stride: Int, x0: Int, y0: Int, x1: Int, y1: Int, colour: Int) {
        for (y in y0 until y1) for (x in x0 until x1) if ((x + y) % 6 == 0) set(pixels, stride, x, y, colour)
    }

    /** [PLOT_GLYPHS] at [scale] pixels per dot, top-left at ([x], [y]). Upper-cased for the font. */
    private fun text(pixels: IntArray, stride: Int, x: Int, y: Int, label: String, colour: Int, scale: Int) {
        var cursor = x
        for (ch in label.uppercase()) {
            val glyph = PLOT_GLYPHS[ch] ?: PLOT_GLYPHS[' ']!!
            for (row in glyph.indices) for (col in glyph[row].indices) {
                if (glyph[row][col] != '#') continue
                fillRect(pixels, stride, cursor + col * scale, y + row * scale,
                    cursor + (col + 1) * scale, y + (row + 1) * scale, colour)
            }
            cursor += 4 * scale
        }
    }

    private fun textWidth(label: String, scale: Int): Int = label.length * 4 * scale

    /**
     * A number short enough for a tick, in the font's alphabet.
     *
     * Three significant figures, no exponent — [PLOT_GLYPHS] has no `E` for a mantissa to lean on
     * that would not be confused with the letter, and a study's axes are physical quantities in SI
     * whose useful values are not 1e-17.
     */
    private fun number(value: Double): String {
        if (!value.isFinite()) return "-"
        if (value == 0.0) return "0"
        val magnitude = floor(log10(abs(value))).toInt()
        val digits = (2 - magnitude).coerceIn(0, 6)
        val rounded = "%.${digits}f".format(value)
        return if (rounded.contains('.')) rounded.trimEnd('0').trimEnd('.') else rounded
    }

    /** Index of the axis value nearest [value]. */
    private fun nearest(values: List<Double>, value: Double): Int =
        values.indices.minByOrNull { abs(values[it] - value) } ?: 0

    /** Fractional index of [value] between the axis's samples, for a point that is not on the grid. */
    private fun fractional(values: List<Double>, value: Double): Double {
        if (values.size < 2) return 0.0
        val first = values.first()
        val stepSize = (values.last() - first) / (values.size - 1)
        if (abs(stepSize) < 1e-15) return 0.0
        return ((value - first) / stepSize).coerceIn(0.0, values.size - 1.0)
    }

    /** Label every nth tick, so a 41-point axis does not overprint itself. */
    private fun tickStride(count: Int, room: Int): Int {
        if (room <= 0) return count.coerceAtLeast(1)
        return ((count + room - 1) / room).coerceAtLeast(1)
    }

    /** Whether every character of [label] is in the dot-matrix font, ignoring case. */
    fun drawable(label: String): Boolean = label.uppercase().all { PLOT_GLYPHS[it] != null }
}
