@file:afield.CompletionCategory("Visualization")

package afield

import afield.graphics.FLine
import afield.graphics.linesToHTML
import afield.stdlib.FLineIntersection
import afield.stdlib.Vec4
import afield.stdlib.color
import afield.stdlib.filled
import afield.stdlib.fline
import afield.stdlib.hoverColor
import afield.stdlib.label
import afield.stdlib.layoutText
import afield.stdlib.onClick
import afield.stdlib.stroked
import afield.stdlib.textWidth
import afield.stdlib.thickness
import afield.stdlib.toFLines
import afield.stdlib.typeface
import afield.stdlib.vec
import java.awt.Font
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin

/**
 * Plots — the structure, the scales, and the one renderer for them.
 *
 * [Chart] is to a plot what [Table] is to a grid: build it, and it arrives on screen in the repo's
 * style with no markup in the way. It implements [ToHTML], so `display(channel, chart)`, `chart.h`
 * and a box whose last expression is a `Chart` all render through [Chart.toHTML]; [Box.showChart]
 * is the terse form.
 *
 * ### Why this exists
 *
 * The same argument [Table] was written for, one step further along. Measured on `d7d8dccc2`,
 * sixteen files under `src/` draw their own axes and ticks across six unrelated media — AWT
 * `Graphics2D` (`FunctionsPlotGallery`, `DoughDspPlotGallery`), SVG strings (`stats.kt`,
 * `ValueVisualizer`, the `Pat*` galleries), raster pixels (`AlifeGallery`, `SonicPiPatternProbe`),
 * JS inside an iframe (`Functions.kt`, `FunctionGraphBrowser`), RectSL rectangles (`RectSL.kt`), and
 * FLines (`MorphoSearchFLines`, `AbletonSetDebugDrawing`) — and share no code at all.
 *
 * Worse than the drawing is the *arithmetic*. Heckbert's nice-number tick algorithm had been written
 * **five** times over — `RectSL.kt:3138`, `FunctionsPlotGallery.kt:369`, `DoughDspPlotGallery.kt:575`,
 * and twice more as JavaScript in `Functions.kt:3383` and `FunctionGraphBrowser.kt:487` — every copy
 * `private` or embedded in a string literal, so no caller could reach one and two of them could
 * silently disagree about where a gridline goes. [niceTicks] is that algorithm, once, in public.
 *
 * ### What is actually new here
 *
 * Field already had most of the pieces: `List<FLine>.fitInto`, `planeFigureHtml`'s window→frame
 * mapping, and `MorphoSearchFLines.plotFrame`'s FLine gridlines. What it did not have — and says so
 * in a comment at `MorphoSearchFLines.kt:51` — is **an axis with text on it**, because
 * `showPlaneGrid` has nowhere to put a label. So the labels here are real glyph outlines
 * ([labelLines]), which is what makes this a chart rather than a grid.
 *
 * ### Marks pick themselves up
 *
 * `Box.lines` switches to the interactive SVG renderer the moment any line carries
 * [FLine.onClick] or [FLine.hoverColor] (`someSkiko.kt:37`). Point and bar marks set both, so
 * hovering a bar and clicking a datum work *by construction* rather than by configuration — see
 * [Chart.pickable] for the escape hatch and the cost.
 *
 * ### The part that is not formatting
 *
 * [Chart.expect], exactly as in [Table]. A plot in a doc or a live box usually carries a claim —
 * "no residual exceeds 1e-6", "every voice stayed inside the guard band" — and a reader is
 * otherwise asked to verify it by eye. State the claim and it is *drawn* as a band, the data outside
 * it recolours, the footnote says how many points broke it, and [Chart.deviations] hands the same
 * list to a test.
 */

// ==============================================================================================
// Scales and ticks
// ==============================================================================================

/** Which arithmetic a [Scale] uses between data space and pixel space. */
enum class ScaleKind { LINEAR, LOG }

/**
 * One axis: a data interval, a pixel interval, and the arithmetic between them.
 *
 * [pixelHi] may be *less* than [pixelLo] and usually is for a y axis, because y grows **downwards**
 * in Field's 2D outputs — a y scale is built as `Scale(lo, hi, plotBottom, plotTop)` and the
 * inversion is the whole reason to have the type rather than a pair of closures.
 *
 * A [ScaleKind.LOG] scale requires a strictly positive domain and refuses to be built without one,
 * rather than emitting `NaN` pixels that a renderer would silently drop. A *datum* that is
 * non-positive on a log scale still maps to `NaN` — that point is genuinely not representable — and
 * the mark builders drop those rather than clamping them, because clamping would draw a lie.
 */
class Scale(
    /** The low end of the data interval. */
    val lo: Double,
    /** The high end of the data interval. */
    val hi: Double,
    /** The pixel [lo] maps to. */
    val pixelLo: Double,
    /** The pixel [hi] maps to. May be less than [pixelLo]; see the class note on y. */
    val pixelHi: Double,
    /** Linear by default. */
    val kind: ScaleKind = ScaleKind.LINEAR,
) {
    init {
        require(lo.isFinite() && hi.isFinite()) { "a Scale needs a finite domain; got $lo..$hi" }
        require(pixelLo.isFinite() && pixelHi.isFinite()) {
            "a Scale needs a finite pixel range; got $pixelLo..$pixelHi"
        }
        require(kind != ScaleKind.LOG || (lo > 0.0 && hi > 0.0)) {
            "a LOG scale needs a strictly positive domain; got $lo..$hi"
        }
    }

    private fun forward(v: Double) = if (kind == ScaleKind.LOG) log10(v) else v

    private fun inverse(u: Double) = if (kind == ScaleKind.LOG) 10.0.pow(u) else u

    private val d0 = forward(lo)
    private val d1 = forward(hi)

    /** Where [value] lands, in pixels. `NaN` when the value has no place on this scale. */
    fun toPixel(value: Double): Double {
        val u = forward(value)
        if (!u.isFinite()) return Double.NaN
        if (d1 == d0) return (pixelLo + pixelHi) / 2.0
        return pixelLo + (u - d0) / (d1 - d0) * (pixelHi - pixelLo)
    }

    /** The inverse of [toPixel] — what data value a pixel names. The thing a click needs. */
    fun toData(pixel: Double): Double {
        if (pixelHi == pixelLo) return lo
        return inverse(d0 + (pixel - pixelLo) / (pixelHi - pixelLo) * (d1 - d0))
    }

    /** Round tick values covering the domain: [niceTicks] when linear, [logTicks] when log. */
    fun ticks(target: Int = 6): List<Double> = when (kind) {
        ScaleKind.LINEAR -> niceTicks(lo, hi, target)
        ScaleKind.LOG -> logTicks(lo, hi, max(2, target * 2))
    }

    /** How far apart [ticks] came out, for [tickLabel]'s benefit. Zero when there is one tick. */
    fun tickStep(target: Int = 6): Double {
        val t = ticks(target)
        return if (t.size >= 2) t[1] - t[0] else 0.0
    }

    override fun toString(): String =
        "Scale($kind, $lo..$hi -> $pixelLo..$pixelHi)"
}

/**
 * Heckbert's "nice numbers": round [x] to one of 1, 2, 5 or 10 times a power of ten.
 *
 * @param round pick the *nearest* nice number rather than the smallest one that is at least [x].
 *   Tick selection wants `false` for the range and `true` for the step, which is the algorithm.
 */
fun niceNumber(x: Double, round: Boolean): Double {
    if (x <= 0.0 || !x.isFinite()) return 0.0
    val exponent = floor(log10(x))
    val fraction = x / 10.0.pow(exponent)
    val nice = if (round) {
        when {
            fraction < 1.5 -> 1.0
            fraction < 3.0 -> 2.0
            fraction < 7.0 -> 5.0
            else -> 10.0
        }
    } else {
        when {
            fraction <= 1.0 -> 1.0
            fraction <= 2.0 -> 2.0
            fraction <= 5.0 -> 5.0
            else -> 10.0
        }
    }
    return nice * 10.0.pow(exponent)
}

/**
 * Round tick values covering `[lo, hi]`, about [target] of them — the one copy of the algorithm
 * that five files had each written privately.
 *
 * Every returned value is snapped to the chosen step, so a 0.1 step gives `0.3` and not
 * `0.30000000000000004`; a caller that formats these with [tickLabel] therefore gets short labels
 * without doing any rounding of its own. A degenerate range (`hi <= lo`, or either end infinite)
 * returns a single tick rather than throwing, because an axis over constant data still wants a line
 * and a number on it.
 */
fun niceTicks(lo: Double, hi: Double, target: Int = 6): List<Double> {
    if (!lo.isFinite() || !hi.isFinite()) return emptyList()
    if (hi <= lo) return listOf(lo)
    val step = niceNumber(niceNumber(hi - lo, false) / max(1, target - 1), true)
    if (step <= 0.0 || !step.isFinite()) return listOf(lo, hi)
    val first = ceil(lo / step - 1e-9).toLong()
    val last = floor(hi / step + 1e-9).toLong()
    if (last < first || last - first > 10_000L) return listOf(lo, hi)
    // Snap to the step's own precision: `k * step` alone reintroduces the binary error the step
    // was chosen to avoid, and a tick reading "0.30000000000000004" is a formatting bug forever.
    val places = min(12, max(0, ceil(-log10(step)).toInt() + 1))
    val quantum = 10.0.pow(places)
    return (first..last).map { k -> round(k * step * quantum) / quantum }
}

/**
 * Decade ticks covering `[lo, hi]`, with 1/2/5 mantissas while the span is small enough to fit.
 *
 * Both ends must be strictly positive; anything else gives an empty list, which is what a caller
 * asking for log ticks over data that includes zero should get.
 */
fun logTicks(lo: Double, hi: Double, maxTicks: Int = 12): List<Double> {
    if (!(lo > 0.0) || !(hi > 0.0) || hi < lo) return emptyList()
    val first = floor(log10(lo)).toInt()
    val last = ceil(log10(hi)).toInt()
    if (last - first > 400) return emptyList()
    val mantissas = if (last - first <= 2) listOf(1.0, 2.0, 5.0) else listOf(1.0)
    val all = buildList {
        for (decade in first..last) {
            val base = 10.0.pow(decade)
            mantissas.forEach { m ->
                val v = m * base
                if (v >= lo * (1 - 1e-9) && v <= hi * (1 + 1e-9)) add(v)
            }
        }
    }
    if (all.size <= maxTicks) return all
    // Too many decades for the space: keep every nth, always keeping the first.
    val stride = ceil(all.size.toDouble() / maxTicks).toInt().coerceAtLeast(1)
    return all.filterIndexed { i, _ -> i % stride == 0 }
}

/**
 * A tick's label: just enough decimals for [step], trailing zeros trimmed, and never `-0`.
 *
 * Falls back to scientific notation for magnitudes a fixed-point rendering would flatten to `0` —
 * the same reason [defaultCellText] does, and for the same kind of number: a tick at `1e-9` on a
 * residual plot must not read `0`.
 */
fun tickLabel(value: Double, step: Double = 0.0): String {
    if (!value.isFinite()) return value.toString()
    val magnitude = abs(value)
    if (magnitude != 0.0 && (magnitude < 1e-4 || magnitude >= 1e6)) {
        return String.format(Locale.US, "%.1e", value)
    }
    val absStep = abs(step)
    val decimals =
        if (absStep <= 0.0 || !absStep.isFinite()) 0
        else max(0, -floor(log10(absStep)).toInt()).coerceAtMost(6)
    var s = String.format(Locale.US, "%.${decimals}f", value)
    if (decimals > 0 && '.' in s) s = s.trimEnd('0').trimEnd('.')
    if (s == "-0") s = "0"
    return s
}

// ==============================================================================================
// Marks
// ==============================================================================================

/** What a [ChartMark] draws. */
enum class MarkKind {
    /** A dot per datum, each individually pickable while under [Chart.pickLimit]. */
    POINTS,

    /** A polyline through the data in the order given. */
    LINE,

    /** [LINE], plus a filled region down to a baseline. */
    AREA,

    /** One bar per category, at integer x positions. */
    BARS,

    /** Bars at bin centres on a numeric x axis. */
    HISTOGRAM,

    /** A horizontal reference line across the whole plot. */
    HRULE,

    /** A vertical reference line down the whole plot. */
    VRULE,

    /** A shaded horizontal band between two y values. */
    BAND,
}

/**
 * One layer of a [Chart]: what to draw, the numbers to draw it from, and how it looks.
 *
 * Built by [Chart.points], [Chart.line] and friends rather than directly — they are what fill in the
 * defaults and pick the series colour — but public and readable, so a test can assert on the data a
 * chart is *about* to draw instead of on its markup.
 */
class ChartMark internal constructor(
    /** What this mark draws. */
    val kind: MarkKind,
    /** The series name: the legend entry, and the head of a datum's hover label. */
    val label: String,
    /** Data-space x per datum. Integer positions `0 until n` for [MarkKind.BARS]. */
    val x: DoubleArray,
    /** Data-space y per datum. For [MarkKind.BAND] and [MarkKind.HRULE] the upper edge. */
    val y: DoubleArray,
    /** The lower edge, for [MarkKind.AREA], [MarkKind.BARS], [MarkKind.HISTOGRAM] and [MarkKind.BAND]. */
    val baseline: DoubleArray?,
    /** Bar/bin width in data units; 0 means "derive it from the spacing". */
    val binWidth: Double,
    /** Colour, RGBA in `0..1`. Defaults to this mark's slot in [signalPalette]. */
    var color: Vec4,
    /** Dot radius, in pixels. */
    var size: Double,
    /** Stroke width, in pixels. */
    var thickness: Double,
) {
    /** How many data points this mark has. */
    val count: Int get() = min(x.size, y.size)

    /** Whether a reader can hover and click individual data of this mark. */
    val isPickable: Boolean get() = kind == MarkKind.POINTS || kind == MarkKind.BARS || kind == MarkKind.HISTOGRAM

    override fun toString(): String = "ChartMark($kind, \"$label\", $count)"
}

/** One datum of one mark, as reported to [Chart.onPick] when a reader clicks it. */
data class ChartPick(
    /** The [ChartMark.label] of the series clicked. */
    val series: String,
    /** Which datum of that series, by index. */
    val index: Int,
    /** Its data-space x. For a bar, its slot. */
    val x: Double,
    /** Its data-space y. */
    val y: Double,
    /** The category name, for a bar; `null` otherwise. */
    val category: String?,
    /** What the interactive SVG renderer reported about the hit itself. */
    val hit: FLineIntersection,
)

/** One datum that failed a [Chart.expect] check: which series and index, its value, and the reason. */
data class ChartDeviation(
    val series: String,
    val index: Int,
    val x: Double,
    val y: Double,
    val because: String?,
)

// ==============================================================================================
// Chart
// ==============================================================================================

/** Muted grey for axes, ticks and notes — the plot counterpart of [TABLE_MUTED]. */
val CHART_MUTED: Vec4 = vec(0.53, 0.53, 0.53, 1.0)

/** The gridline colour: the same grey at a tenth of the weight, so data always wins. */
val CHART_GRID: Vec4 = vec(0.53, 0.53, 0.53, 0.22)

/** What a failed [Chart.expect] recolours to, matching [CellRole.BAD]'s intent. */
val CHART_BAD: Vec4 = vec(1.0, 0.42, 0.42, 1.0)

/**
 * Marks, axes, and the claims made about them.
 *
 * ```
 * Chart(title = "residual per iteration", xLabel = "iteration", yLabel = "|r|")
 *     .line("residual", iterations, residuals)
 *     .points("residual", iterations, residuals)
 *     .expect(max = 1e-6, because = "converged")
 * ```
 *
 * Everything is in pixels by the time it leaves: [toFLines] lays the whole figure out inside
 * [width] × [height] and hands back `List<FLine>`, which is the currency `Box.lines`, `drawToSVG`,
 * `planeFigureHtml` and every FLine consumer already speak. Nothing downstream needs to know a chart
 * was involved.
 *
 * As in [Table], a decorator that names a series names it with a *string*, so a typo would otherwise
 * be a silent no-op; they throw listing the real series names instead. Also as in [Table], drawing
 * is bounded — see [maxPoints] — so a `Chart` over something enormous is still safe to display.
 */
class Chart(
    /** Drawn above the plot in the first palette colour. */
    var title: String? = null,
    /** Drawn under the x axis, centred. */
    var xLabel: String? = null,
    /** Drawn to the left of the y axis, rotated a quarter turn. */
    var yLabel: String? = null,
    /** A footnote under the figure. Deviation counts are appended to it, as in [Table.note]. */
    var note: String? = null,
) : ToHTML {

    /** Figure width in pixels. */
    var width: Double = 640.0

    /** Figure height in pixels. */
    var height: Double = 380.0

    /** The x axis's arithmetic. */
    var xKind: ScaleKind = ScaleKind.LINEAR

    /** The y axis's arithmetic. */
    var yKind: ScaleKind = ScaleKind.LINEAR

    /** An explicit x domain, or `null` to take it from the data. */
    var xDomain: Pair<Double, Double>? = null

    /** An explicit y domain, or `null` to take it from the data. */
    var yDomain: Pair<Double, Double>? = null

    /** About how many x ticks to aim for. */
    var xTickTarget: Int = 6

    /** About how many y ticks to aim for. */
    var yTickTarget: Int = 5

    /** Whether to draw the series legend. Off automatically when there is one unnamed series. */
    var legend: Boolean = true

    /** Whether to draw gridlines at the ticks. */
    var grid: Boolean = true

    /**
     * Whether point, bar and bin marks are individually hoverable and clickable.
     *
     * `true` by default, and it is what routes the figure through the interactive SVG renderer
     * (`someSkiko.kt:37`) instead of Skia's — so hover and click work with no configuration at all.
     * The cost is one `<g>` and one registered callback per datum, which is why [pickLimit] exists;
     * turn it off for a figure whose points are scenery rather than data.
     */
    var pickable: Boolean = true

    /**
     * Above this many data in one mark, that mark stops being individually pickable and is drawn as
     * a single line — the dots are still there, they just share one path.
     *
     * A per-datum `<g>` is a few hundred bytes of metadata and a live callback registration, so a
     * ten-thousand-point scatter would be megabytes of SVG that no reader is going to click.
     */
    var pickLimit: Int = 400

    /**
     * How many data of one mark are drawn before the rest are elided, and the footnote says so —
     * the plot counterpart of [Table.maxRows].
     */
    var maxPoints: Int = 20_000

    /**
     * What a click on a datum calls. `null` means the datum's own label is printed, which is the
     * behaviour that makes a fresh chart worth clicking before anyone has written a handler.
     */
    var onPick: ((ChartPick) -> Unit)? = null

    private val markList = mutableListOf<ChartMark>()
    private val expectations = mutableListOf<Expectation>()
    private var categories: List<String>? = null

    /** The layers added so far, in draw order. */
    val marks: List<ChartMark> get() = markList

    /** The category names on the x axis, when a [bars] mark put them there. */
    val xCategories: List<String>? get() = categories

    private class Expectation(
        val series: String?,
        val min: Double,
        val max: Double,
        val because: String?,
        val ok: ((Double, Double) -> Boolean)?,
    ) {
        /** A range claim draws itself as a band; a predicate has no shape to draw. */
        val drawable: Boolean get() = ok == null && (min.isFinite() || max.isFinite())

        fun holds(x: Double, y: Double): Boolean = ok?.invoke(x, y) ?: (y >= min && y <= max)
    }

    // ── construction ──────────────────────────────────────────────────────────────

    private fun nextColor(): Vec4 = hexColor(signalPalette[markList.size % signalPalette.size])

    private fun add(mark: ChartMark): Chart = apply { markList += mark }

    private fun seriesNames(): List<String> = markList.map { it.label }.distinct()

    private fun requireSeries(name: String?) {
        if (name == null) return
        require(markList.any { it.label == name }) {
            "no series named `$name` in this chart; series are ${seriesNames()}. " +
                "Add the marks before the expectation that talks about them."
        }
    }

    /** A dot per datum. Pickable, so this is what makes a chart clickable. */
    fun points(
        label: String,
        x: List<Number>,
        y: List<Number>,
        color: Vec4? = null,
        size: Double = 3.0,
    ): Chart = add(
        ChartMark(
            MarkKind.POINTS, label, x.toDoubles(), y.toDoubles(), null, 0.0,
            color ?: nextColor(), size, 1.0
        )
    )

    /** [points] against the sample index. */
    fun points(label: String, y: List<Number>, color: Vec4? = null, size: Double = 3.0): Chart =
        points(label, y.indices.map { it }, y, color, size)

    /** A polyline through the data in the order given. */
    fun line(
        label: String,
        x: List<Number>,
        y: List<Number>,
        color: Vec4? = null,
        thickness: Double = 1.6,
    ): Chart = add(
        ChartMark(
            MarkKind.LINE, label, x.toDoubles(), y.toDoubles(), null, 0.0,
            color ?: nextColor(), 0.0, thickness
        )
    )

    /** [line] against the sample index — the shape `showSignals` draws. */
    fun line(label: String, y: List<Number>, color: Vec4? = null, thickness: Double = 1.6): Chart =
        line(label, y.indices.map { it }, y, color, thickness)

    /** A [line] with the region down to [base] filled at a fifth of the alpha. */
    fun area(
        label: String,
        x: List<Number>,
        y: List<Number>,
        base: Number = 0.0,
        color: Vec4? = null,
    ): Chart {
        val xs = x.toDoubles()
        return add(
            ChartMark(
                MarkKind.AREA, label, xs, y.toDoubles(),
                DoubleArray(xs.size) { base.toDouble() }, 0.0,
                color ?: nextColor(), 0.0, 1.6
            )
        )
    }

    /**
     * One bar per category, which puts the category names on the x axis.
     *
     * Only one bars mark can own the x axis; a second one with different categories throws rather
     * than drawing bars against labels that are not theirs.
     */
    fun bars(
        label: String,
        categories: List<String>,
        values: List<Number>,
        color: Vec4? = null,
        base: Number = 0.0,
    ): Chart {
        require(categories.isNotEmpty()) { "a bars mark needs at least one category" }
        require(categories.size == values.size) {
            "bars got ${categories.size} categories and ${values.size} values"
        }
        val existing = this.categories
        require(existing == null || existing == categories) {
            "this chart's x axis already carries the categories $existing; a second bars mark " +
                "cannot relabel it with $categories"
        }
        this.categories = categories
        val xs = DoubleArray(categories.size) { it.toDouble() }
        return add(
            ChartMark(
                MarkKind.BARS, label, xs, values.toDoubles(),
                DoubleArray(xs.size) { base.toDouble() }, 0.7,
                color ?: nextColor(), 0.0, 1.0
            )
        )
    }

    /** [bars] written inline: `bars("cost", "gates" to 12, "depth" to 4)`. */
    fun bars(label: String, vararg values: Pair<String, Number>, color: Vec4? = null): Chart =
        bars(label, values.map { it.first }, values.map { it.second }, color)

    /**
     * [values] binned and drawn as bars on a numeric x axis.
     *
     * @param bins how many bins; `0` asks for Sturges' rule, which is the choice that makes a
     *   histogram of forty numbers look like forty numbers rather than like a bar chart.
     */
    fun histogram(
        label: String,
        values: List<Number>,
        bins: Int = 0,
        color: Vec4? = null,
    ): Chart {
        val data = values.toDoubles().filter { it.isFinite() }
        require(data.isNotEmpty()) { "a histogram needs at least one finite value" }
        val lo = data.minOrNull()!!
        val hi = data.maxOrNull()!!
        val n = if (bins > 0) bins else max(1, ceil(log10(data.size.toDouble()) / log10(2.0) + 1).toInt())
        val binW = if (hi > lo) (hi - lo) / n else 1.0
        val counts = IntArray(n)
        data.forEach { v ->
            val slot = if (hi > lo) ((v - lo) / binW).toInt().coerceIn(0, n - 1) else 0
            counts[slot]++
        }
        val centres = DoubleArray(n) { lo + (it + 0.5) * binW }
        return add(
            ChartMark(
                MarkKind.HISTOGRAM, label, centres,
                DoubleArray(n) { counts[it].toDouble() },
                DoubleArray(n) { 0.0 }, binW * 0.92,
                color ?: nextColor(), 0.0, 1.0
            )
        )
    }

    /** A horizontal reference line at [y], across the whole plot. */
    fun rule(y: Number, label: String = "", color: Vec4 = CHART_MUTED): Chart = add(
        ChartMark(
            MarkKind.HRULE, label, doubleArrayOf(0.0), doubleArrayOf(y.toDouble()), null, 0.0,
            color, 0.0, 1.0
        )
    )

    /** A vertical reference line at [x], down the whole plot. */
    fun vrule(x: Number, label: String = "", color: Vec4 = CHART_MUTED): Chart = add(
        ChartMark(
            MarkKind.VRULE, label, doubleArrayOf(x.toDouble()), doubleArrayOf(0.0), null, 0.0,
            color, 0.0, 1.0
        )
    )

    /** A shaded horizontal band between [lo] and [hi]. */
    fun band(lo: Number, hi: Number, label: String = "", color: Vec4 = CHART_GRID): Chart = add(
        ChartMark(
            MarkKind.BAND, label, doubleArrayOf(0.0), doubleArrayOf(max(lo.toDouble(), hi.toDouble())),
            doubleArrayOf(min(lo.toDouble(), hi.toDouble())), 0.0, color, 0.0, 1.0
        )
    )

    // ── claims ────────────────────────────────────────────────────────────────────

    /**
     * States that every datum's y lies in `[min, max]`.
     *
     * Drawn, not just checked: the accepted range becomes a band across the plot with its edges
     * ruled, so a reader sees the claim and the data in the same glance. Data outside it takes
     * [CHART_BAD], is counted in the footnote, and appears in [deviations].
     *
     * @param series restrict the claim to one series by name, or `null` for all of them. The marks
     *   must already be added; a name that does not exist throws.
     */
    fun expect(
        min: Double = Double.NEGATIVE_INFINITY,
        max: Double = Double.POSITIVE_INFINITY,
        series: String? = null,
        because: String? = null,
    ): Chart {
        require(min <= max) { "expect got min=$min above max=$max" }
        require(min.isFinite() || max.isFinite()) { "expect needs at least one finite bound" }
        requireSeries(series)
        expectations += Expectation(series, min, max, because, null)
        return this
    }

    /**
     * States a claim about every datum, given its x and y — the general form, with no band to draw.
     */
    fun expect(
        series: String? = null,
        because: String? = null,
        ok: (x: Double, y: Double) -> Boolean,
    ): Chart {
        requireSeries(series)
        expectations += Expectation(
            series, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, because, ok
        )
        return this
    }

    /**
     * Every datum that failed an [expect] check — the same list the drawing recolours, so a test can
     * assert on the claim the figure makes rather than on its geometry.
     */
    val deviations: List<ChartDeviation>
        get() = buildList {
            markList.forEach { mark ->
                if (!mark.kind.carriesData) return@forEach
                for (i in 0 until mark.count) {
                    val bx = mark.x[i]
                    val by = mark.y[i]
                    expectations.forEach { e ->
                        if ((e.series == null || e.series == mark.label) && !e.holds(bx, by)) {
                            add(ChartDeviation(mark.label, i, bx, by, e.because))
                        }
                    }
                }
            }
        }

    private fun failed(mark: ChartMark, index: Int): String? {
        val bx = mark.x[index]
        val by = mark.y[index]
        expectations.forEach { e ->
            if ((e.series == null || e.series == mark.label) && !e.holds(bx, by)) {
                return e.because ?: "outside the expected range"
            }
        }
        return null
    }

    private fun footnote(): String? {
        val failures = deviations.size
        val base = note?.takeIf { it.isNotBlank() }
        return when {
            failures == 0 -> base
            base == null -> "$failures point${if (failures == 1) "" else "s"} outside expectation"
            else -> "$base — $failures point${if (failures == 1) "" else "s"} outside expectation"
        }
    }

    // ── domains ───────────────────────────────────────────────────────────────────

    /** The x domain that will actually be drawn: [xDomain] if set, else derived from the data. */
    fun resolvedXDomain(): Pair<Double, Double> {
        xDomain?.let { return padDegenerate(it.first, it.second, xKind) }
        if (categories != null) return -0.6 to (categories!!.size - 0.4)
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        markList.forEach { mark ->
            if (mark.kind == MarkKind.HRULE || mark.kind == MarkKind.BAND) return@forEach
            val half = mark.binWidth / 2.0
            for (i in 0 until mark.count) {
                val v = mark.x[i]
                if (!v.isFinite()) continue
                lo = min(lo, v - half); hi = max(hi, v + half)
            }
        }
        if (lo > hi) return if (xKind == ScaleKind.LOG) 1.0 to 10.0 else 0.0 to 1.0
        return padDegenerate(lo, hi, xKind)
    }

    /** The y domain that will actually be drawn: [yDomain] if set, else derived from the data. */
    fun resolvedYDomain(): Pair<Double, Double> {
        yDomain?.let { return padDegenerate(it.first, it.second, yKind) }
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        fun see(v: Double) {
            if (!v.isFinite()) return
            if (yKind == ScaleKind.LOG && v <= 0.0) return
            lo = min(lo, v); hi = max(hi, v)
        }
        markList.forEach { mark ->
            if (mark.kind == MarkKind.VRULE) return@forEach
            for (i in 0 until mark.count) see(mark.y[i])
            mark.baseline?.forEach { see(it) }
        }
        // A drawn expectation is part of the figure's argument, so the band has to be visible even
        // when every point is comfortably inside it — otherwise the claim is cropped away.
        expectations.forEach { if (it.drawable) { see(it.min); see(it.max) } }
        if (lo > hi) return if (yKind == ScaleKind.LOG) 1.0 to 10.0 else 0.0 to 1.0
        if (yKind == ScaleKind.LINEAR && hi > lo) {
            val slack = (hi - lo) * 0.06
            return lo - slack to hi + slack
        }
        return padDegenerate(lo, hi, yKind)
    }

    // ── rendering ─────────────────────────────────────────────────────────────────

    /**
     * The whole figure, in pixels, as lines — the one output everything else is built on.
     *
     * Drawing order is background to foreground: bands, gridlines, the plot frame, then marks in the
     * order they were added, then axes and text on top. Interactive marks carry [FLine.onClick],
     * [FLine.hoverColor] and [FLine.label], so handing this straight to `Box.lines` gets the
     * interactive renderer without asking for it.
     */
    fun toFLines(): List<FLine> {
        val out = mutableListOf<FLine>()
        val (dx0, dx1) = resolvedXDomain()
        val (dy0, dy1) = resolvedYDomain()

        val xTicks = tickValuesX(dx0, dx1)
        val yTicks = tickValuesY(dy0, dy1)
        val yStep = if (yTicks.size >= 2) yTicks[1] - yTicks[0] else 0.0
        val xStep = if (xTicks.size >= 2) xTicks[1] - xTicks[0] else 0.0

        val yTickText = yTicks.map { tickLabel(it, yStep) }
        val xTickText = categories ?: xTicks.map { tickLabel(it, xStep) }

        val padLeft = 12.0 + (if (yLabel != null) LABEL_SIZE + 6.0 else 0.0) +
            (yTickText.maxOfOrNull { measure(it) } ?: 0.0) + 8.0
        val padRight = 12.0 + (xTickText.lastOrNull()?.let { measure(it) / 2.0 } ?: 0.0)
        val padTop = 10.0 + (if (title != null) TITLE_SIZE + 12.0 else 0.0) +
            (if (drawLegend()) LABEL_SIZE + 8.0 else 0.0)
        val padBottom = 12.0 + LABEL_SIZE + 8.0 + (if (xLabel != null) LABEL_SIZE + 6.0 else 0.0) +
            (if (footnote() != null) LABEL_SIZE + 8.0 else 0.0)

        val plot = Rect(
            padLeft, padTop,
            max(24.0, width - padLeft - padRight),
            max(24.0, height - padTop - padBottom)
        )
        val sx = Scale(dx0, dx1, plot.x, plot.x + plot.w, xKind)
        val sy = Scale(dy0, dy1, plot.y + plot.h, plot.y, yKind)

        // Bands first: they are the background the data is judged against.
        markList.filter { it.kind == MarkKind.BAND }.forEach { out += bandLines(it, plot, sy) }
        expectations.filter { it.drawable }.forEach { out += expectationLines(it, plot, sy, dy0, dy1) }

        if (grid) {
            yTicks.forEach { v ->
                val py = sy.toPixel(v)
                if (py.isFinite()) out += gridLine(plot.x, py, plot.x + plot.w, py)
            }
            if (categories == null) {
                xTicks.forEach { v ->
                    val px = sx.toPixel(v)
                    if (px.isFinite()) out += gridLine(px, plot.y, px, plot.y + plot.h)
                }
            }
        }

        markList.forEach { mark ->
            out += when (mark.kind) {
                MarkKind.BAND -> emptyList()
                MarkKind.HRULE -> ruleLines(mark, plot, sy, horizontal = true)
                MarkKind.VRULE -> ruleLines(mark, plot, sx, horizontal = false)
                MarkKind.LINE -> lineLines(mark, sx, sy)
                MarkKind.AREA -> areaLines(mark, sx, sy)
                MarkKind.POINTS -> pointLines(mark, sx, sy)
                MarkKind.BARS, MarkKind.HISTOGRAM -> barLines(mark, sx, sy, plot)
            }
        }

        out += axisLines(plot, sx, sy, xTicks, yTicks, xTickText, yTickText)
        out += chromeLines(plot)
        return out
    }

    /** The same HTML `Box.lines` would display for [toFLines] — how `.h` and `display` work. */
    override fun toHTML(): String = linesToHTML(toFLines(), width, height)

    /** A plain-text summary; the picture is the point, so this is for a log or a failure message. */
    fun toText(): String = buildString {
        title?.let { appendLine(it) }
        val (x0, x1) = resolvedXDomain()
        val (y0, y1) = resolvedYDomain()
        appendLine("x ${tickLabel(x0)}..${tickLabel(x1)} ($xKind)  y ${tickLabel(y0)}..${tickLabel(y1)} ($yKind)")
        markList.forEach { appendLine("  ${it.kind.name.lowercase()} \"${it.label}\" ${it.count} points") }
        footnote()?.let { appendLine(it) }
    }

    override fun toString(): String =
        "Chart(${markList.size} marks, ${markList.sumOf { it.count }} points)"

    // ── rendering internals ───────────────────────────────────────────────────────

    private fun tickValuesX(lo: Double, hi: Double): List<Double> =
        categories?.indices?.map { it.toDouble() }
            ?: Scale(lo, hi, 0.0, 1.0, xKind).ticks(xTickTarget)

    private fun tickValuesY(lo: Double, hi: Double): List<Double> =
        Scale(lo, hi, 0.0, 1.0, yKind).ticks(yTickTarget)

    private fun drawLegend(): Boolean =
        legend && markList.count { it.label.isNotEmpty() && it.kind.carriesData } > 1

    private fun gridLine(x0: Double, y0: Double, x1: Double, y1: Double): FLine = fline {
        moveTo(x0, y0); lineTo(x1, y1)
        color = Vec4(CHART_GRID)
        thickness = 1.0
    }

    private fun bandLines(mark: ChartMark, plot: Rect, sy: Scale): List<FLine> {
        val top = sy.toPixel(mark.y[0])
        val bottom = sy.toPixel(mark.baseline?.get(0) ?: mark.y[0])
        if (!top.isFinite() || !bottom.isFinite()) return emptyList()
        return listOf(filledRect(plot.x, min(top, bottom), plot.w, abs(bottom - top), mark.color))
    }

    private fun expectationLines(
        e: Expectation,
        plot: Rect,
        sy: Scale,
        dy0: Double,
        dy1: Double,
    ): List<FLine> {
        val lo = if (e.min.isFinite()) e.min else min(dy0, dy1)
        val hi = if (e.max.isFinite()) e.max else max(dy0, dy1)
        val pLo = sy.toPixel(lo)
        val pHi = sy.toPixel(hi)
        if (!pLo.isFinite() || !pHi.isFinite()) return emptyList()
        val out = mutableListOf<FLine>()
        out += filledRect(
            plot.x, min(pLo, pHi), plot.w, max(1.0, abs(pLo - pHi)),
            vec(CHART_BAD.x, CHART_BAD.y, CHART_BAD.z, 0.09)
        ).also { it.label = e.because ?: "expected range" }
        // Rule the edges the claim actually names. An unbounded side has no edge to draw, which is
        // the visual difference between "at most 1e-6" and "between 0 and 1e-6".
        if (e.min.isFinite()) out += edgeRule(plot, sy.toPixel(e.min), e.because)
        if (e.max.isFinite()) out += edgeRule(plot, sy.toPixel(e.max), e.because)
        return out
    }

    private fun edgeRule(plot: Rect, py: Double, because: String?): FLine = fline {
        moveTo(plot.x, py); lineTo(plot.x + plot.w, py)
        color = vec(CHART_BAD.x, CHART_BAD.y, CHART_BAD.z, 0.55)
        thickness = 1.0
    }.also { it.label = because ?: "expected range" }

    private fun ruleLines(mark: ChartMark, plot: Rect, scale: Scale, horizontal: Boolean): List<FLine> {
        val p = scale.toPixel(if (horizontal) mark.y[0] else mark.x[0])
        if (!p.isFinite()) return emptyList()
        val f = fline {
            if (horizontal) { moveTo(plot.x, p); lineTo(plot.x + plot.w, p) }
            else { moveTo(p, plot.y); lineTo(p, plot.y + plot.h) }
            color = Vec4(mark.color)
            thickness = mark.thickness
        }
        if (mark.label.isNotEmpty()) f.label = mark.label
        return listOf(f)
    }

    private fun lineLines(mark: ChartMark, sx: Scale, sy: Scale): List<FLine> {
        val f = FLine()
        var open = false
        var drawn = 0
        for (i in 0 until min(mark.count, maxPoints)) {
            val px = sx.toPixel(mark.x[i])
            val py = sy.toPixel(mark.y[i])
            if (!px.isFinite() || !py.isFinite()) { open = false; continue }
            if (!open) { f.moveTo(px, py); open = true } else f.lineTo(px, py)
            drawn++
        }
        if (drawn == 0) return emptyList()
        f.color = Vec4(mark.color)
        f.thickness = mark.thickness
        f.label = mark.label
        return listOf(f)
    }

    private fun areaLines(mark: ChartMark, sx: Scale, sy: Scale): List<FLine> {
        val n = min(mark.count, maxPoints)
        val fill = FLine()
        val top = mutableListOf<Pair<Double, Double>>()
        for (i in 0 until n) {
            val px = sx.toPixel(mark.x[i])
            val py = sy.toPixel(mark.y[i])
            if (px.isFinite() && py.isFinite()) top += px to py
        }
        if (top.isEmpty()) return emptyList()
        val baseY = sy.toPixel(mark.baseline?.getOrNull(0) ?: 0.0)
        if (!baseY.isFinite()) return lineLines(mark, sx, sy)
        top.forEachIndexed { i, (px, py) -> if (i == 0) fill.moveTo(px, py) else fill.lineTo(px, py) }
        fill.lineTo(top.last().first, baseY)
        fill.lineTo(top.first().first, baseY)
        fill.close()
        fill.color = vec(mark.color.x, mark.color.y, mark.color.z, mark.color.w * 0.22)
        fill.filled = true
        fill.stroked = false
        return listOf(fill) + lineLines(mark, sx, sy)
    }

    private fun pointLines(mark: ChartMark, sx: Scale, sy: Scale): List<FLine> {
        val n = min(mark.count, maxPoints)
        val individually = pickable && n <= pickLimit
        val out = mutableListOf<FLine>()
        val shared = FLine()
        var drewShared = false
        for (i in 0 until n) {
            val px = sx.toPixel(mark.x[i])
            val py = sy.toPixel(mark.y[i])
            if (!px.isFinite() || !py.isFinite()) continue
            val why = failed(mark, i)
            val colour = if (why != null) CHART_BAD else mark.color
            val radius = if (why != null) mark.size + 1.5 else mark.size
            if (individually) {
                val dot = FLine()
                dot.circle(px, py, radius)
                dot.color = Vec4(colour)
                dot.filled = true
                dot.stroked = false
                decorate(dot, mark, i, why)
                out += dot
            } else {
                shared.circle(px, py, radius)
                drewShared = true
            }
        }
        if (drewShared) {
            shared.color = Vec4(mark.color)
            shared.filled = true
            shared.stroked = false
            shared.label = mark.label
            out += shared
        }
        return out
    }

    private fun barLines(mark: ChartMark, sx: Scale, sy: Scale, plot: Rect): List<FLine> {
        val n = min(mark.count, maxPoints)
        if (n == 0) return emptyList()
        val slot = if (mark.binWidth > 0.0) mark.binWidth
        else if (n > 1) abs(mark.x[1] - mark.x[0]) * 0.8 else 1.0
        val out = mutableListOf<FLine>()
        for (i in 0 until n) {
            val left = sx.toPixel(mark.x[i] - slot / 2.0)
            val right = sx.toPixel(mark.x[i] + slot / 2.0)
            val top = sy.toPixel(mark.y[i])
            val base = sy.toPixel(mark.baseline?.getOrNull(i) ?: 0.0)
            if (!left.isFinite() || !right.isFinite() || !top.isFinite() || !base.isFinite()) continue
            val why = failed(mark, i)
            val colour = if (why != null) CHART_BAD else mark.color
            val y0 = min(top, base).coerceIn(plot.y, plot.y + plot.h)
            val y1 = max(top, base).coerceIn(plot.y, plot.y + plot.h)
            val bar = filledRect(min(left, right), y0, abs(right - left), max(1.0, y1 - y0), colour)
            if (pickable && n <= pickLimit) decorate(bar, mark, i, why)
            out += bar
        }
        return out
    }

    /**
     * Give one datum's geometry its hover colour, its click callback and its label.
     *
     * This is the whole interactive story: `Box.lines` sees a line with [FLine.onClick] set and takes
     * its interactive branch, so nothing else in the chart has to know that clicking exists.
     */
    private fun decorate(line: FLine, mark: ChartMark, index: Int, why: String?) {
        val category = categories?.getOrNull(index)
        val text = buildString {
            append(if (mark.label.isEmpty()) "datum" else mark.label)
            append(": ")
            append(category ?: tickLabel(mark.x[index]))
            append(" → ")
            append(tickLabel(mark.y[index]))
            why?.let { append("  (").append(it).append(")") }
        }
        line.label = text
        line.hoverColor = vec(1.0, 1.0, 1.0, 0.95)
        val handler = onPick
        line.onClick = { hit ->
            val pick = ChartPick(mark.label, index, mark.x[index], mark.y[index], category, hit)
            if (handler != null) handler(pick) else println(text)
        }
    }

    private fun axisLines(
        plot: Rect,
        sx: Scale,
        sy: Scale,
        xTicks: List<Double>,
        yTicks: List<Double>,
        xText: List<String>,
        yText: List<String>,
    ): List<FLine> {
        val out = mutableListOf<FLine>()
        val axis = fline {
            moveTo(plot.x, plot.y); lineTo(plot.x, plot.y + plot.h); lineTo(plot.x + plot.w, plot.y + plot.h)
            color = Vec4(CHART_MUTED)
            thickness = 1.0
        }
        out += axis

        yTicks.forEachIndexed { i, v ->
            val py = sy.toPixel(v)
            if (!py.isFinite()) return@forEachIndexed
            out += fline {
                moveTo(plot.x - 4.0, py); lineTo(plot.x, py)
                color = Vec4(CHART_MUTED); thickness = 1.0
            }
            out += labelLines(yText.getOrElse(i) { "" }, plot.x - 8.0, py + LABEL_SIZE * 0.34, Anchor.RIGHT)
        }
        xTicks.forEachIndexed { i, v ->
            val px = sx.toPixel(v)
            if (!px.isFinite()) return@forEachIndexed
            out += fline {
                moveTo(px, plot.y + plot.h); lineTo(px, plot.y + plot.h + 4.0)
                color = Vec4(CHART_MUTED); thickness = 1.0
            }
            out += labelLines(
                xText.getOrElse(i) { "" }, px, plot.y + plot.h + 6.0 + LABEL_SIZE, Anchor.CENTRE
            )
        }
        return out
    }

    private fun chromeLines(plot: Rect): List<FLine> {
        val out = mutableListOf<FLine>()
        title?.let {
            out += labelLines(
                it, plot.x, 10.0 + TITLE_SIZE, Anchor.LEFT,
                size = TITLE_SIZE, colour = hexColor(signalPalette[0])
            )
        }
        xLabel?.let {
            out += labelLines(
                it, plot.x + plot.w / 2.0,
                plot.y + plot.h + 6.0 + LABEL_SIZE * 2.0 + 6.0, Anchor.CENTRE
            )
        }
        yLabel?.let {
            // Rotated a quarter turn about its own centre, then translated — one transform on the
            // finished glyph lines, never a re-layout, per `FLine.transform`'s contract.
            val cy = plot.y + plot.h / 2.0
            val lines = labelLines(it, 0.0, 0.0, Anchor.CENTRE)
            out += lines.map { line -> line.transform { p -> vec(p.y + 4.0 + LABEL_SIZE, -p.x + cy, p.z) } }
        }
        footnote()?.let {
            out += labelLines(it, plot.x, height - 6.0, Anchor.LEFT)
        }
        if (drawLegend()) {
            var x = plot.x + plot.w
            val y = padTopForLegend()
            markList.reversed().forEach { mark ->
                if (mark.label.isEmpty() || !mark.kind.carriesData) return@forEach
                val w = measure(mark.label)
                out += labelLines(mark.label, x, y, Anchor.RIGHT, colour = mark.color)
                x -= w + 6.0
                out += filledRect(x - 8.0, y - LABEL_SIZE * 0.55, 7.0, 7.0, mark.color)
                x -= 16.0
            }
        }
        return out
    }

    private fun padTopForLegend(): Double =
        10.0 + (if (title != null) TITLE_SIZE + 12.0 else 0.0) + LABEL_SIZE

    private fun measure(text: String): Double =
        runCatching { textWidth(text, labelTypeface()) }.getOrElse { text.length * LABEL_SIZE * 0.6 }
}

/** Whether a mark kind carries data a claim can be made about, or is pure decoration. */
private val MarkKind.carriesData: Boolean
    get() = this == MarkKind.POINTS || this == MarkKind.LINE || this == MarkKind.AREA ||
        this == MarkKind.BARS || this == MarkKind.HISTOGRAM

// ==============================================================================================
// Text, geometry and colour helpers
// ==============================================================================================

/** Tick and legend text size in pixels. */
private const val LABEL_SIZE = 11.0

/** Title text size in pixels. */
private const val TITLE_SIZE = 13.0

/** Where a label's given position sits relative to the text. */
private enum class Anchor { LEFT, CENTRE, RIGHT }

private fun labelTypeface() = typeface(Font.MONOSPACED, LABEL_SIZE)

/**
 * A string as glyph outlines, placed with its baseline at ([x], [y]).
 *
 * Three deliberate choices, each of which has been got wrong before in this repo:
 *
 *  - `toFLines()`, **plural and stroked** — never one filled `toFLine()`, whose triangulator fans
 *    dozens of disjoint contours from a shared origin and draws a starburst where a word should be.
 *  - placed with [FLine.transform], which copies control points too. Offsetting `node.to` alone
 *    leaves every cubic handle at the origin and smears each letter into a streak.
 *  - wrapped in a `runCatching`, returning no lines on failure: a headless machine with no fonts
 *    should get an unlabelled chart, not an exception in the middle of a figure.
 */
private fun labelLines(
    text: String,
    x: Double,
    y: Double,
    anchor: Anchor,
    size: Double = LABEL_SIZE,
    colour: Vec4 = CHART_MUTED,
): List<FLine> {
    if (text.isBlank()) return emptyList()
    return runCatching {
        val face = typeface(Font.MONOSPACED, size)
        val layout = layoutText(text, face)
        val dx = when (anchor) {
            Anchor.LEFT -> x
            Anchor.CENTRE -> x - layout.advanceWidth / 2.0
            Anchor.RIGHT -> x - layout.advanceWidth
        }
        layout.toFLines(color = colour, thickness = 0.85, filled = false, stroked = true)
            .map { line -> line.transform { p -> vec(p.x + dx, p.y + y, p.z) } }
    }.getOrElse { emptyList() }
}

/** A filled axis-aligned rectangle as one closed line. */
private fun filledRect(x: Double, y: Double, w: Double, h: Double, colour: Vec4): FLine = fline {
    moveTo(x, y); lineTo(x + w, y); lineTo(x + w, y + h); lineTo(x, y + h); close()
    color = Vec4(colour)
    filled = true
    stroked = false
}

/**
 * Append a closed circle as real geometry, rather than setting [afield.stdlib.pointed].
 *
 * Measured in `spatial_field.kt:331`: Skia's SVG export has no circle primitive, so a `pointed`
 * node is written out as quadratic Béziers at roughly **5 kB per dot** — a 90-point cloud came to
 * 458 kB for one panel. A twelve-segment polygon is a few hundred bytes, and unlike a `pointed`
 * node it is a line of its own, so it can carry its own hover colour and click callback.
 */
private fun FLine.circle(cx: Double, cy: Double, r: Double, segments: Int = 12) {
    for (i in 0 until segments) {
        val a = i * 2.0 * Math.PI / segments
        val px = cx + r * cos(a)
        val py = cy + r * sin(a)
        if (i == 0) moveTo(px, py) else lineTo(px, py)
    }
    close()
}

/**
 * `#rrggbb` or `#rrggbbaa` as a [Vec4] in `0..1`.
 *
 * Here so a chart's series colours can come from [signalPalette] — the same list [Table.accent]
 * colours its columns from — and a table and a plot of the same quantities therefore agree on which
 * colour means which series by construction rather than by anyone remembering to.
 */
fun hexColor(hex: String): Vec4 {
    val s = hex.removePrefix("#")
    require(s.length == 6 || s.length == 8) { "expected #rrggbb or #rrggbbaa, got `$hex`" }
    fun byte(i: Int) = s.substring(i * 2, i * 2 + 2).toInt(16) / 255.0
    return vec(byte(0), byte(1), byte(2), if (s.length == 8) byte(3) else 1.0)
}

private fun List<Number>.toDoubles(): DoubleArray = DoubleArray(size) { this[it].toDouble() }

/**
 * Widen a range that has no width, so a scale over constant data still has two distinct ends.
 *
 * A zero-width domain makes every datum land on the same pixel, which reads as "no data" rather
 * than as "all the same" — and on a log scale it makes the tick list empty.
 */
private fun padDegenerate(lo: Double, hi: Double, kind: ScaleKind): Pair<Double, Double> {
    var a = min(lo, hi)
    var b = max(lo, hi)
    if (kind == ScaleKind.LOG) {
        if (!(b > 0.0)) return 1.0 to 10.0
        if (!(a > 0.0)) a = b / 100.0
        if (b / a < 1.0000001) return a / 2.0 to b * 2.0
        return a to b
    }
    if (b - a > 1e-300 && b > a) return a to b
    val slack = if (abs(b) > 0.0) abs(b) * 0.5 else 1.0
    return b - slack to b + slack
}

// ==============================================================================================
// Entry points
// ==============================================================================================

/**
 * A [Chart] of named series against a shared x — shaped like [table] and `showSignals`, so the same
 * data can be a grid or a plot by changing one word.
 *
 * `chart("sin" to a, "cos" to b)` draws two lines against the sample index.
 */
fun chart(
    vararg series: Pair<String, List<Number>>,
    x: List<Number>? = null,
    title: String? = null,
    xLabel: String? = null,
    yLabel: String? = null,
    note: String? = null,
): Chart {
    require(series.isNotEmpty()) { "a Chart needs at least one series" }
    val c = Chart(title, xLabel, yLabel, note)
    series.forEach { (name, values) ->
        if (x == null) c.line(name, values) else c.line(name, x, values)
    }
    return c
}

/**
 * Put a [Chart] on the screen and hand it back — the plot counterpart of [Box.showTable].
 *
 * The chart is already displayed on return; it comes back only so a caller can read
 * [Chart.deviations] or keep decorating a copy. Do not `display` it again.
 */
fun Box.showChart(chart: Chart, channel: String = "output"): Chart {
    display(channel, chart)
    return chart
}

/** Builds a [Chart] from named series with [chart] and displays it. */
fun Box.showChart(
    vararg series: Pair<String, List<Number>>,
    channel: String = "output",
    x: List<Number>? = null,
    title: String? = null,
    xLabel: String? = null,
    yLabel: String? = null,
    note: String? = null,
): Chart = showChart(chart(*series, x = x, title = title, xLabel = xLabel, yLabel = yLabel, note = note), channel)
