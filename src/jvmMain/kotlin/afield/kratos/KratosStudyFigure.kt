@file:afield.CompletionCategory("Simulation")
package afield.kratos

import afield.Colormaps
import afield.RasterImage
import afield.rectsl.PLOT_GLYPHS
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
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
        scale: ColourScale = ColourScale.LINEAR,
    ): RasterImage {
        val pixels = IntArray(width * height) { BACKGROUND }
        drawLandscape(pixels, width, 0, 0, width, height, landscape, optimisation, colormap, scale)
        return RasterImage(pixels, width, height)
    }

    /** The landscape alone, as PNG bytes. */
    fun landscape(
        landscape: StudyLandscape,
        optimisation: StudyOptimisation<*>? = null,
        width: Int = 900,
        height: Int = 560,
        colormap: String = "viridis",
        scale: ColourScale = ColourScale.LINEAR,
    ): ByteArray = landscapeRaster(landscape, optimisation, width, height, colormap, scale).png()

    /** The convergence panel alone, as pixels. */
    fun convergenceRaster(
        optimisation: StudyOptimisation<*>,
        gridBest: Double? = null,
        width: Int = 900,
        height: Int = 420,
        extra: List<StudyTrace> = emptyList(),
    ): RasterImage {
        val pixels = IntArray(width * height) { BACKGROUND }
        drawConvergence(pixels, width, 0, 0, width, height, optimisation, gridBest, extra)
        return RasterImage(pixels, width, height)
    }

    /** The convergence panel alone, as PNG bytes. */
    fun convergence(
        optimisation: StudyOptimisation<*>,
        gridBest: Double? = null,
        width: Int = 900,
        height: Int = 420,
        extra: List<StudyTrace> = emptyList(),
    ): ByteArray = convergenceRaster(optimisation, gridBest, width, height, extra).png()

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
        scale: ColourScale = ColourScale.LINEAR,
        extra: List<StudyTrace> = emptyList(),
    ): RasterImage {
        val height = landscapeHeight + (if (optimisation == null) 0 else convergenceHeight)
        val pixels = IntArray(width * height) { BACKGROUND }
        drawLandscape(pixels, width, 0, 0, width, landscapeHeight, landscape, optimisation, colormap, scale)
        if (optimisation != null) {
            drawConvergence(
                pixels, width, 0, landscapeHeight, width, convergenceHeight,
                optimisation, landscape.best?.objective, extra,
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
        scale: ColourScale = ColourScale.LINEAR,
        extra: List<StudyTrace> = emptyList(),
    ): ByteArray =
        sheetRaster(landscape, optimisation, width, landscapeHeight, convergenceHeight, colormap,
            scale, extra).png()

    // ------------------------------------------------------------------------------------------
    // The landscape
    // ------------------------------------------------------------------------------------------

    private fun drawLandscape(
        pixels: IntArray, stride: Int, x0: Int, y0: Int, w: Int, h: Int,
        landscape: StudyLandscape, optimisation: StudyOptimisation<*>?, colormap: String,
        scale: ColourScale,
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
        if (scale == ColourScale.STRETCHED) {
            // Right-aligned on the caption line: the row-axis label owns the line below it.
            val note = "STRETCHED COLOUR - READ THE BAR TICKS"
            text(pixels, stride, right - textWidth(note, 1), y0 + 36, note, DIM, 1)
        }

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
                val t = scale.forward(((value - low) / span).coerceIn(0.0, 1.0))
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

        colorBar(pixels, stride, right + 22, top, right + 46, bottom, lut, low, high, scale)
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
        optimisation: StudyOptimisation<*>, gridBest: Double?, extra: List<StudyTrace> = emptyList(),
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
            extra.forEach { add(Triple(it.label.uppercase(), it.values, it.colour)) }
        }.filter { it.second.isNotEmpty() && it.second.any { v -> v.isFinite() } }
        if (series.isEmpty()) {
            text(pixels, stride, left + 12, top + 12, "NO FINITE EVALUATIONS", DIM, 1)
            return
        }

        val values = series.flatMap { it.second.toList() }.filter { it.isFinite() } +
            listOfNotNull(gridBest)
        // A minimisation trace that converges spends its whole life in the bottom pixel row of a
        // linear axis: here the first evaluation is 4.4 K out and the last is 0.001 K out, three and
        // a half decades, so a linear y-axis shows one cliff and then a flat line along the floor.
        // The interesting part of a convergence plot is the part after it has nearly converged.
        val logY = values.all { it > 0.0 } && values.max() / values.min() > 30.0
        var low = if (logY) log10(values.min()) else values.min()
        var high = if (logY) log10(values.max()) else values.max()
        if (high - low < 1e-12) { low -= 0.5; high += 0.5 }
        val pad = (high - low) * 0.08
        low -= pad; high += pad
        val longest = series.maxOf { it.second.size }
        if (logY) text(pixels, stride, right - textWidth("LOG SCALE", 1), y0 + 16, "LOG SCALE", DIM, 1)

        fun axis(v: Double) = if (logY) log10(v) else v
        fun unaxis(a: Double) = if (logY) 10.0.pow(a) else a
        fun px(i: Int) = left + (right - left) * (if (longest <= 1) 0.5 else i / (longest - 1.0))
        fun py(v: Double) = bottom - (bottom - top) * ((axis(v) - low) / (high - low))

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
            val v = unaxis(low + (high - low) * k / 4.0)
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

    /**
     * The ramp, with five ticks carrying the values the colours actually mean.
     *
     * Ticks at even *positions* rather than even values, so a stretched scale shows its own
     * non-linearity in the numbers beside it. A colour scale that is not linear and does not say so
     * is a way of making a shallow feature look deep, which is the exact failure this whole file
     * exists to avoid.
     */
    private fun colorBar(
        pixels: IntArray, stride: Int, left: Int, top: Int, right: Int, bottom: Int,
        lut: IntArray, low: Double, high: Double, scale: ColourScale,
    ) {
        for (y in top until bottom) {
            val position = 1.0 - (y - top).toDouble() / (bottom - top - 1).coerceAtLeast(1)
            val colour = lut[(position * (lut.size - 1)).roundToInt().coerceIn(0, lut.size - 1)]
            hLine(pixels, stride, left, right, y, colour)
        }
        frame(pixels, stride, left, top, right, bottom, RULE)
        for (k in 0..4) {
            val position = k / 4.0
            val value = low + (high - low) * scale.inverse(position)
            val y = (bottom - position * (bottom - top)).roundToInt().coerceIn(top, bottom - 1)
            hLine(pixels, stride, right, right + 4, y, RULE)
            text(pixels, stride, right + 7, y - 3, number(value), DIM, 1)
        }
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

/**
 * One more line for the convergence panel — a second search, a refinement pass, a baseline.
 *
 * [values] is in the objective's own units and sense, which is what [StudyOptimisation.trace]
 * returns, so a caller assembling one from another run does not have to think about the fitness
 * flip. There is one of these rather than a general plotting API because the only thing this panel
 * is for is comparing searches over the same objective.
 */
class StudyTrace(val label: String, val values: DoubleArray, val colour: Int)

/**
 * How a normalised objective becomes a position on the colour ramp.
 *
 * There is a real problem this exists for, and it is not decoration. The objective of the demo
 * study — the miss from a target temperature — has a range of about 5 K across the whole design
 * space but only 0.8 K along its bottom edge, because a short arm cannot get far from its skin
 * whatever its conductivity. On a linear ramp the whole bottom third of the map is one shade of
 * dark purple and the valley running through it is invisible, even though it is measured perfectly
 * well and the closed-form check confirms it to a fraction of a cell.
 *
 * [STRETCHED] is the fix, and the honest form of it: a monotone `log(1 + k·t) / log(1 + k)` that
 * fixes both endpoints and expands the bottom of the range. The figure captions itself when it is
 * used, and the colour bar's ticks carry the true values at even *positions*, so the reader sees
 * the compression in the spacing of the numbers rather than having to take it on trust.
 */
enum class ColourScale {
    /** Position is the value. What a colour bar means by default, and what to prefer. */
    LINEAR,

    /** Logarithmic stretch toward the low end, for a landscape whose interesting part is shallow. */
    STRETCHED;

    /** Normalised value in `0..1` to normalised colour position in `0..1`. */
    fun forward(t: Double): Double = when (this) {
        LINEAR -> t
        STRETCHED -> ln(1.0 + STRETCH * t) / LN_ONE_PLUS_STRETCH
    }

    /** [forward]'s inverse: what value a colour position stands for. */
    fun inverse(position: Double): Double = when (this) {
        LINEAR -> position
        STRETCHED -> (exp(position * LN_ONE_PLUS_STRETCH) - 1.0) / STRETCH
    }

    private companion object {
        /**
         * How hard [STRETCHED] pulls. 24 gives the bottom tenth of the range about a third of the
         * ramp, which is enough to read a shallow valley and not so much that the top of the range
         * collapses into one colour.
         */
        const val STRETCH = 24.0
        val LN_ONE_PLUS_STRETCH = ln(1.0 + STRETCH)
    }
}
