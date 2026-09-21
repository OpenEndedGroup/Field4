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

//
// REACH FOR PLOTLY FIRST — see the file KDoc below. `pick` here means
// `FLine.onClick`/`hoverColor` giving a hit record with the element and local `(u, v)`. If a figure
// needs none of the three, it should be a Plotly plot.
//
/**
 * Plots — the structure, the scales, and the one renderer for them.
 *
 * [Chart] is to a plot what [Table] is to a grid: build it, and it arrives on screen in the repo's
 * style with no markup in the way. It implements [ToHTML], so `display(channel, chart)`, `chart.h`
 * and a box whose last expression is a `Chart` all render through [Chart.toHTML]; [Box.showChart]
 * is the terse form.
 *
 * ### This file or `plotly.kt`
 *
 * `plotly.kt` — `showSignals`, `linePlot`, `scatter`, `violin` — is the default, and better at being
 * a chart: real text rather than glyph geometry, a plot that reflows to its panel instead of being
 * fitted and centred, and log, subplot and box axes this file does not carry. Reach for a [Chart]
 * for the three things Plotly cannot do: composing a plot with other FLine geometry in one drawing
 * under a shared transform, picking, and [Chart.expect].
 *
 * Axis labels here are real glyph geometry ([labelLines]), filled and with their counters intact,
 * because `showPlaneGrid` has nowhere to put text. [niceTicks] is Heckbert's nice-number tick
 * algorithm in public. `dev_docs/chart_primitive.md` holds the Plotly comparison capture and the
 * survey of what each medium in the repo was drawing for itself.
 *
 * ### Marks pick themselves up
 *
 * `Box.lines` switches to the interactive SVG renderer the moment any line carries
 * [FLine.onClick] or [FLine.hoverColor] (`someSkiko.kt`). Point and bar marks set both, so hovering
 * a bar and clicking a datum need no configuration — see [Chart.pickable] for the escape hatch and
 * the cost.
 *
 * ### Chart.expect
 *
 * Exactly as in [Table]. A plot in a doc or a live box usually carries a claim — "no residual
 * exceeds 1e-6", "every voice stayed inside the guard band". State the claim and it is *drawn* as a
 * band, the data outside it recolours, the footnote says how many points broke it, and
 * [Chart.deviations] hands the same list to a test.
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
    // Heckbert's *tight* pairing, not his loose one. The loose variant rounds the span up first
    // (`niceNumber(hi - lo, false)`) because it goes on to widen the axis to `floor(lo/step)*step ..
    // ceil(hi/step)*step`; every private copy in this repo does the first half and then clips to the
    // caller's range instead of widening, which loses ticks — over -3..3 asking for six it can only
    // offer three. Clipping is the right call for a chart whose domain the caller chose, so the step
    // is derived from the real span to match.
    val step = niceNumber((hi - lo) / max(1, target - 1), true)
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
fun tickLabel(value: Double, step: Double = 0.0): String =
    tickLabel(value, step, scientific = needsScientific(value))

/**
 * Whether a fixed-point rendering of [value] would be unreadable — too small to survive four
 * decimals, or too long to fit under a tick.
 */
private fun needsScientific(value: Double): Boolean {
    val magnitude = abs(value)
    return magnitude != 0.0 && (magnitude < 1e-4 || magnitude >= 1e6)
}

/**
 * The labels for a whole axis, in **one** notation.
 *
 * Deciding per value put `100000` and `1.0e+06` next to each other on the same log axis, which reads
 * as two different quantities. One tick needing scientific notation puts the whole axis in it.
 */
fun tickLabels(values: List<Double>, step: Double = 0.0): List<String> {
    val scientific = values.any { needsScientific(it) }
    return values.map { tickLabel(it, step, scientific) }
}

private fun tickLabel(value: Double, step: Double, scientific: Boolean): String {
    if (!value.isFinite()) return value.toString()
    if (scientific) {
        // Zero has no exponent worth printing, and "0.0e+00" under a tick is noise.
        return if (value == 0.0) "0" else String.format(Locale.US, "%.1e", value)
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
    /**
     * Colour, RGBA in `0..1`. Defaults to the next unused slot in [signalPalette] — *next unused*,
     * so passing a colour explicitly leaves the slot for the following mark rather than burning it.
     */
    var color: Vec4,
    /** Dot radius, in pixels. */
    var size: Double,
    /** Stroke width, in pixels. */
    var thickness: Double,
) {
    /** How many data points this mark has. */
    val count: Int get() = min(x.size, y.size)

    /** Whether a reader can hover and click individual data of this mark. */
    val isPickable: Boolean get() =
        kind == MarkKind.POINTS || kind == MarkKind.BARS || kind == MarkKind.HISTOGRAM ||
            false

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

/**
 * What a failed [Chart.expect] recolours to, matching [CellRole.BAD]'s intent.
 *
 * A saturated red rather than the soft `#ff6b6b` this was, and saturated *because* of the
 * measurement: at `vec(1.0, 0.42, 0.42)` it sat CIEDE2000 **12.24** from [signalPalette] slot 5,
 * the pink, so a six-series chart recoloured its failing data to a colour a reader could not tell
 * from its sixth series — with being visible the entire point of `expect`. This renders `#e60000`
 * and is **29.32** from the nearest slot (slot 1's orange, with the pink now at 29.40), further
 * apart than the palette's own weakest internal pair at 23.81, and still 45.72 off the figure
 * background. `ChartGallery.checkPaletteSeparation` holds it there.
 *
 * The palette itself did not move, so [Table.accent] and `showSignals` are untouched. Note that
 * `--field-danger` `#ff5050` was not an option: it is 16.59 from the same pink.
 */
val CHART_BAD: Vec4 = vec(0.9, 0.0, 0.0, 1.0)

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

    /**
     * Figure width in pixels.
     *
     * The default aspect is chosen to sit close to a box output panel's, because a Chart is FIXED
     * geometry: the output area fits it uniformly and centres it, so any mismatch between this
     * aspect and the panel's shows up as empty margin. A Plotly plot has no such problem -- it is
     * HTML and reflows to whatever shape it is given. That asymmetry is structural, not a bug, and
     * it is one of the reasons the general chart path should be Plotly. Set both fields when you
     * know the shape you are drawing into.
     */
    var width: Double = 900.0

    /** Figure height in pixels. See [width] on why the default aspect is panel-shaped. */
    var height: Double = 420.0

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
     * Whether the figure is kept inside the frame it declared. On, and there is a published figure
     * behind it.
     *
     * Two things, one switch, because they are one property and they failed together: mark geometry
     * is clipped to [plotRect], and [title] and [note] are wrapped to [width].
     *
     * A `Chart` maps every datum it is given into figure pixels, and until this existed it *drew*
     * whatever came out, however far outside the axes that landed; a [note] was one unwrapped line
     * however long it was. Neither is the harmless overdraw it sounds like: [toHTML] hands the lines
     * to `linesToHTML`, which stamps their own union bounds onto the wrapper, and a box output panel
     * or a docs capture then fits **those** bounds. So geometry outside the frame does not get
     * cropped — it shrinks everything else. Measured, both of them, on one published page:
     *
     *  - a cross section 2 × 3 across, charted into a 0.24 × 0.24 window with an explicit
     *    [xDomain]/[yDomain], put its outline 5137 px outside a 780 × 440 frame, and the axes ended up
     *    an illegible stamp at **10%** of the declared size;
     *  - that same figure's 142-character [note] measured 1197 px on one line against 711 px of room,
     *    and drew the whole figure at **63%**.
     *
     * That is `public/docs/tetgen.md`'s `sharp_edge.png`, and five gates were green over it.
     *
     * Set it `false` for exactly that behaviour back. It is a switch and not a fact for two reasons:
     * a caller composing a chart with other FLine geometry under a shared transform sometimes wants
     * the overflow, and the before half of the captures in `dev_docs/chart_clip_and_note.md` is this
     * flag turned off, so the counterfactual stays runnable instead of being a screenshot of history.
     */
    var clip: Boolean = true

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

    /**
     * How many palette slots have been handed out — *not* how many marks there are.
     *
     * These were the same number until a mark given an explicit colour was found to consume a slot
     * anyway, so the next automatic mark skipped one. Two ways that bit: a caller who colours one
     * series by hand and lets the rest default lands the next series on slot 1 rather than slot 0,
     * which is how `tetgen.md` got a hand-picked `#ffb859` next to the palette's `#ff9e4a`; and
     * `DataFrameCharts.chart` with `points = true` adds two marks per column — the dots explicitly
     * in the line's own colour — so column *n* took slot `2n` and a three-column frame went
     * cyan/green/purple while `Table.accent` on the same frame went cyan/orange/green, in flat
     * contradiction of the promise those two make to agree.
     */
    private var paletteSlotsUsed = 0

    private fun nextColor(): Vec4 =
        hexColor(signalPalette[paletteSlotsUsed++ % signalPalette.size])

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
     *
     * `distinct` matters here, and the reason is the commonest way to draw a series: a `line` mark
     * *and* a `points` mark over the same numbers, so the trend reads and the samples are clickable.
     * Those are two marks and one datum, and without the dedupe the figure's own footnote said "8
     * points outside expectation" over four visibly red dots.
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
        }.distinct()

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
                // Symmetric with [resolvedYDomain], which has always done this and which x had not.
                // A non-positive datum is not representable on a log axis; letting it into the domain
                // sends `padDegenerate` down its `a = b / 100` path, and every genuinely small
                // positive datum then maps to the left of the frame instead of onto the axis.
                if (xKind == ScaleKind.LOG && v <= 0.0) continue
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
        val l = layout()
        val plot = l.plot
        val sx = l.x
        val sy = l.y
        val xTicks = l.xTicks
        val yTicks = l.yTicks
        val xTickText = l.xTickText
        val yTickText = l.yTickText
        val dy0 = sy.lo
        val dy1 = sy.hi

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
                MarkKind.LINE -> lineLines(mark, sx, sy, plot)
                MarkKind.AREA -> areaLines(mark, sx, sy, plot)
                MarkKind.POINTS -> pointLines(mark, sx, sy, plot)
                MarkKind.BARS, MarkKind.HISTOGRAM -> barLines(mark, sx, sy, plot)
            }
        }

        out += axisLines(plot, sx, sy, xTicks, yTicks, xTickText, yTickText)
        out += chromeLines(l)
        return out
    }

    /** The same HTML `Box.lines` would display for [toFLines] — how `.h` and `display` work. */
    override fun toHTML(): String = linesToHTML(toFLines(), width, height)

    /** Title, both domains with their kinds, and one line per mark, for a log or a failure message. */
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

    /** Everything the drawing needs that the domains and the label widths decide between them. */
    internal class Layout(
        val plot: Rect,
        val x: Scale,
        val y: Scale,
        val xTicks: List<Double>,
        val yTicks: List<Double>,
        val xTickText: List<String>,
        val yTickText: List<String>,
        /** [title], wrapped to the figure width. Empty when there is no title. */
        val titleLines: List<String>,
        /** [footnote], wrapped to the figure width. Empty when there is no footnote. */
        val footnoteLines: List<String>,
    )

    /**
     * The plot area, in figure pixels — the rectangle the axes bound and mark geometry is clipped to.
     *
     * "Clipped to" is a fact about the drawing and not a hope about the data: every mark kind is cut
     * against this rectangle before it becomes an [FLine], by [clip], which is on by default. Set
     * [clip] `false` and the sentence above stops being true, which is the whole point of the switch.
     * The axis labels, the title and the footnote live *outside* this rectangle and inside [width] ×
     * [height]; those are the only things a figure draws beyond it.
     *
     * Public because the insets are *derived*, not configured: they come from the widest tick label,
     * whether there is a title, a legend, an axis label, a footnote. A caller overlaying an
     * annotation, or a test asking whether the tick labels drew, has no other way to know where the
     * frame ended up.
     */
    fun plotRect(): Rect = layout().plot

    /**
     * Domains, ticks, label widths, insets, scales — computed together because each one needs the
     * one before it. The ticks come first: they depend only on the domain, and the *widest tick
     * label* is what decides the left inset, which in turn decides both the pixel range the scales
     * map onto and the width the title and the footnote are wrapped to.
     */
    internal fun layout(): Layout {
        val (dx0, dx1) = resolvedXDomain()
        val (dy0, dy1) = resolvedYDomain()

        val xTicks = tickValuesX(dx0, dx1)
        val yTicks = tickValuesY(dy0, dy1)
        val xStep = if (xTicks.size >= 2) xTicks[1] - xTicks[0] else 0.0
        val yStep = if (yTicks.size >= 2) yTicks[1] - yTicks[0] else 0.0

        val yTickText = tickLabels(yTicks, yStep)
        val xTickText = categories ?: tickLabels(xTicks, xStep)

        val padLeft = 12.0 + (if (yLabel != null) LABEL_SIZE + 6.0 else 0.0) +
            (yTickText.maxOfOrNull { measure(it) } ?: 0.0) + 8.0
        val padRight = 12.0 + (xTickText.lastOrNull()?.let { measure(it) / 2.0 } ?: 0.0)

        // Both start at the left edge of the plot and run right, so the room they have is the same
        // and it is known as soon as `padLeft` is. Wrapping cannot feed back into `padLeft`, only
        // into `padTop` and `padBottom`, which is why this is one pass and not a fixed point.
        val textRoom = if (clip) width - padLeft - 12.0 else Double.MAX_VALUE
        val titleLines = title?.let { wrapToWidth(it, textRoom, TITLE_SIZE, MAX_TITLE_LINES) } ?: emptyList()
        val footnoteLines = footnote()?.let { wrapToWidth(it, textRoom, LABEL_SIZE, MAX_NOTE_LINES) } ?: emptyList()

        val padTop = 10.0 + (if (titleLines.isNotEmpty()) titleLines.size * TITLE_SIZE + 12.0 else 0.0) +
            (if (drawLegend()) LABEL_SIZE + 8.0 else 0.0)
        val padBottom = 12.0 + LABEL_SIZE + 8.0 + (if (xLabel != null) LABEL_SIZE + 6.0 else 0.0) +
            (if (footnoteLines.isNotEmpty()) footnoteLines.size * LABEL_SIZE + 8.0 else 0.0)

        val plot = Rect(
            padLeft, padTop,
            max(24.0, width - padLeft - padRight),
            max(24.0, height - padTop - padBottom)
        )
        return Layout(
            plot,
            Scale(dx0, dx1, plot.x, plot.x + plot.w, xKind),
            Scale(dy0, dy1, plot.y + plot.h, plot.y, yKind),
            xTicks, yTicks, xTickText, yTickText, titleLines, footnoteLines
        )
    }

    private fun tickValuesX(lo: Double, hi: Double): List<Double> =
        categories?.indices?.map { it.toDouble() }
            ?: Scale(lo, hi, 0.0, 1.0, xKind).ticks(xTickTarget)

    private fun tickValuesY(lo: Double, hi: Double): List<Double> =
        Scale(lo, hi, 0.0, 1.0, yKind).ticks(yTickTarget)

    private fun drawLegend(): Boolean = legend && legendEntries().size > 1

    private fun gridLine(x0: Double, y0: Double, x1: Double, y1: Double): FLine = fline {
        moveTo(x0, y0); lineTo(x1, y1)
        color = Vec4(CHART_GRID)
        thickness = 1.0
    }

    private fun bandLines(mark: ChartMark, plot: Rect, sy: Scale): List<FLine> {
        val top = sy.toPixel(mark.y[0])
        val bottom = sy.toPixel(mark.baseline?.get(0) ?: mark.y[0])
        if (!top.isFinite() || !bottom.isFinite()) return emptyList()
        val (y0, y1) = clipSpan(min(top, bottom), max(top, bottom), plot.y, plot.y + plot.h)
            ?: return emptyList()
        return listOf(filledRect(plot.x, y0, plot.w, y1 - y0, mark.color))
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
        val (bandTop, bandBottom) = clipSpan(min(pLo, pHi), max(pLo, pHi), plot.y, plot.y + plot.h)
            ?: return emptyList()
        out += filledRect(
            plot.x, bandTop, plot.w, max(1.0, bandBottom - bandTop),
            vec(CHART_BAD.x, CHART_BAD.y, CHART_BAD.z, 0.09)
        ).also { it.label = e.because ?: "expected range" }
        // Rule the edges the claim actually names. An unbounded side has no edge to draw, which is
        // the visual difference between "at most 1e-6" and "between 0 and 1e-6". An edge that falls
        // off the axis has nowhere to be drawn *truthfully*, so it is not drawn at all.
        if (e.min.isFinite()) out += edgeRule(plot, sy.toPixel(e.min), e.because)
        if (e.max.isFinite()) out += edgeRule(plot, sy.toPixel(e.max), e.because)
        return out
    }

    private fun edgeRule(plot: Rect, py: Double, because: String?): List<FLine> {
        if (!py.isFinite()) return emptyList()
        if (clip && (py < plot.y || py > plot.y + plot.h)) return emptyList()
        return listOf(
            fline {
                moveTo(plot.x, py); lineTo(plot.x + plot.w, py)
                color = vec(CHART_BAD.x, CHART_BAD.y, CHART_BAD.z, 0.55)
                thickness = 1.0
            }.also { it.label = because ?: "expected range" }
        )
    }

    private fun ruleLines(mark: ChartMark, plot: Rect, scale: Scale, horizontal: Boolean): List<FLine> {
        val p = scale.toPixel(if (horizontal) mark.y[0] else mark.x[0])
        if (!p.isFinite()) return emptyList()
        // A rule is a claim about a value *on the axis*. Off the axis it has no honest position, and
        // drawing it anyway is what put geometry outside the frame; drop it instead.
        if (clip) {
            val lo = if (horizontal) plot.y else plot.x
            val hi = if (horizontal) plot.y + plot.h else plot.x + plot.w
            if (p < lo || p > hi) return emptyList()
        }
        val f = fline {
            if (horizontal) { moveTo(plot.x, p); lineTo(plot.x + plot.w, p) }
            else { moveTo(p, plot.y); lineTo(p, plot.y + plot.h) }
            color = Vec4(mark.color)
            thickness = mark.thickness
        }
        if (mark.label.isNotEmpty()) f.label = mark.label
        return listOf(f)
    }

    /**
     * The pixel runs of one mark: a new run wherever a datum is non-representable.
     *
     * A `NaN` — a non-positive value on a log axis, a gap a caller put in on purpose — has always
     * broken the polyline rather than being bridged across, because bridging draws a line through
     * data that is not there. [clipPolyline] then splits these runs again wherever they leave
     * [plotRect], for the same reason: the segment between two off-window points did not happen
     * inside the window.
     */
    private fun pixelRuns(mark: ChartMark, sx: Scale, sy: Scale): List<List<DoubleArray>> {
        val runs = mutableListOf<MutableList<DoubleArray>>()
        var current: MutableList<DoubleArray>? = null
        for (i in 0 until min(mark.count, maxPoints)) {
            val px = sx.toPixel(mark.x[i])
            val py = sy.toPixel(mark.y[i])
            if (!px.isFinite() || !py.isFinite()) { current = null; continue }
            if (current == null) { current = mutableListOf(); runs += current }
            current.add(doubleArrayOf(px, py))
        }
        return runs
    }

    private fun lineLines(mark: ChartMark, sx: Scale, sy: Scale, plot: Rect): List<FLine> {
        val runs = pixelRuns(mark, sx, sy).flatMap { if (clip) clipPolyline(it, plot) else listOf(it) }
        if (runs.isEmpty()) return emptyList()
        // One FLine with several subpaths, not several FLines: a clipped series is still one series,
        // and one legend entry, and one thing to hover. That is the same choice `tetgen.md`'s fence
        // made when it emitted a NaN between disjoint runs.
        val f = FLine()
        runs.forEach { run ->
            run.forEachIndexed { i, p -> if (i == 0) f.moveTo(p[0], p[1]) else f.lineTo(p[0], p[1]) }
        }
        f.color = Vec4(mark.color)
        f.thickness = mark.thickness
        f.label = mark.label
        return listOf(f)
    }

    private fun areaLines(mark: ChartMark, sx: Scale, sy: Scale, plot: Rect): List<FLine> {
        val runs = pixelRuns(mark, sx, sy)
        if (runs.isEmpty()) return emptyList()
        val baseY = sy.toPixel(mark.baseline?.getOrNull(0) ?: 0.0)
        if (!baseY.isFinite()) return lineLines(mark, sx, sy, plot)
        val out = mutableListOf<FLine>()
        // The fill is a *polygon*, so Liang–Barsky on its edges would leave it open where it crossed
        // the window. Sutherland–Hodgman against the same rectangle keeps it closed, which is what a
        // filled region needs and a polyline does not.
        runs.forEach { top ->
            val polygon = buildList {
                addAll(top)
                add(doubleArrayOf(top.last()[0], baseY))
                add(doubleArrayOf(top.first()[0], baseY))
            }
            val clipped = if (clip) clipPolygon(polygon, plot) else polygon
            if (clipped.size < 3) return@forEach
            val fill = FLine()
            clipped.forEachIndexed { i, p -> if (i == 0) fill.moveTo(p[0], p[1]) else fill.lineTo(p[0], p[1]) }
            fill.close()
            fill.color = vec(mark.color.x, mark.color.y, mark.color.z, mark.color.w * 0.22)
            fill.filled = true
            fill.stroked = false
            out += fill
        }
        return out + lineLines(mark, sx, sy, plot)
    }

    private fun pointLines(mark: ChartMark, sx: Scale, sy: Scale, plot: Rect): List<FLine> {
        val n = min(mark.count, maxPoints)
        val individually = pickable && n <= pickLimit
        val out = mutableListOf<FLine>()
        val shared = FLine()
        var drewShared = false
        for (i in 0 until n) {
            val px = sx.toPixel(mark.x[i])
            val py = sy.toPixel(mark.y[i])
            if (!px.isFinite() || !py.isFinite()) continue
            // A dot is dropped whole rather than cut: half a disc hanging off the axis reads as a
            // rendering artefact, and every other plotting library in the world drops it too.
            if (clip && !plot.holds(px, py)) continue
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
            // y was already clamped here before the clip layer existed; x was not, and a `coerceIn`
            // on a bar wholly off the axis leaves a one-pixel smear on the frame rather than nothing,
            // so both axes go through `clipSpan`, which drops a disjoint bar instead of flattening it.
            val (y0, y1) = clipSpan(min(top, base), max(top, base), plot.y, plot.y + plot.h) ?: continue
            val (x0, x1) = clipSpan(min(left, right), max(left, right), plot.x, plot.x + plot.w) ?: continue
            val bar = filledRect(x0, y0, x1 - x0, max(1.0, y1 - y0), colour)
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

    private fun chromeLines(l: Layout): List<FLine> {
        val plot = l.plot
        val out = mutableListOf<FLine>()
        l.titleLines.forEachIndexed { i, line ->
            out += labelLines(
                line, plot.x, 10.0 + TITLE_SIZE * (i + 1), Anchor.LEFT,
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
        // Bottom-anchored, so a one-line footnote sits exactly where it always did and the extra
        // lines of a wrapped one grow *upwards* into the room `layout` reserved for them.
        val footLines = l.footnoteLines
        footLines.forEachIndexed { i, line ->
            out += labelLines(line, plot.x, height - 6.0 - (footLines.size - 1 - i) * LABEL_SIZE, Anchor.LEFT)
        }
        if (drawLegend()) {
            var x = plot.x + plot.w
            val y = padTopForLegend(l.titleLines.size)
            legendEntries().reversed().forEach { (name, colour) ->
                val w = measure(name)
                out += labelLines(name, x, y, Anchor.RIGHT, colour = colour)
                x -= w + 6.0
                out += filledRect(x - 8.0, y - LABEL_SIZE * 0.55, 7.0, 7.0, colour)
                x -= 16.0
            }
        }
        return out
    }

    /**
     * One entry per *series*, not per mark.
     *
     * A `line` plus a `points` mark over the same numbers is the ordinary way to draw a clickable
     * trend, and it was putting the same name in the legend twice with two different colours. First
     * mark of a name wins the swatch, which is the one that was drawn first and so the one underneath.
     */
    private fun legendEntries(): List<Pair<String, Vec4>> {
        val seen = LinkedHashMap<String, Vec4>()
        markList.forEach { mark ->
            if (mark.label.isEmpty() || !mark.kind.carriesData) return@forEach
            seen.putIfAbsent(mark.label, mark.color)
        }
        return seen.map { (k, v) -> k to v }
    }

    private fun padTopForLegend(titleLineCount: Int): Double =
        10.0 + (if (titleLineCount > 0) titleLineCount * TITLE_SIZE + 12.0 else 0.0) + LABEL_SIZE

    private fun measure(text: String): Double = chartLabelWidth(text)

    // ── clipping ──────────────────────────────────────────────────────────────────

    /**
     * [a0]..[a1] intersected with [lo]..[hi]; `null` when they are disjoint, identity when [clip] is
     * off. A degenerate span that lies inside survives as a degenerate span, because a bar whose
     * value equals its baseline is a real zero and still gets its one-pixel line.
     */
    private fun clipSpan(a0: Double, a1: Double, lo: Double, hi: Double): Pair<Double, Double>? {
        if (!clip) return a0 to a1
        if (a1 < lo || a0 > hi) return null
        return max(a0, lo) to min(a1, hi)
    }
}

/** Whether a mark kind carries data a claim can be made about, or is pure decoration. */
private val MarkKind.carriesData: Boolean
    get() = this == MarkKind.POINTS || this == MarkKind.LINE || this == MarkKind.AREA ||
        this == MarkKind.BARS || this == MarkKind.HISTOGRAM

// ==============================================================================================
// Text, geometry and colour helpers
// ==============================================================================================

/** Tick and legend text size in pixels. */
private const val LABEL_SIZE = 14.0

/** Title text size in pixels. */
private const val TITLE_SIZE = 18.0

/** Where a label's given position sits relative to the text. */
private enum class Anchor { LEFT, CENTRE, RIGHT }

/**
 * How many lines a wrapped [Chart.note] may occupy before the rest is elided.
 *
 * Four lines of [LABEL_SIZE] is 56 px, 13% of the default 420 px figure. Past that the footnote has
 * stopped annotating the plot and started competing with it, and the honest thing is to say so with
 * an ellipsis — the same bargain [Chart.maxPoints] strikes with too much data.
 */
private const val MAX_NOTE_LINES = 4

/** How many lines a wrapped [Chart.title] may occupy. A title needing a third line is a note. */
private const val MAX_TITLE_LINES = 2

/**
 * One line of chart label text, measured in figure pixels.
 *
 * `internal` rather than private because the wrapping in [Chart.note] is only worth anything if a
 * test can measure the *unwrapped* width it exists to avoid — the counterfactual half of
 * `ChartGallery`'s frame gate. Falls back to a monospace estimate on a machine with no fonts,
 * exactly as [labelLines] does, so it never throws in the middle of a figure.
 */
internal fun chartLabelWidth(text: String, size: Double = LABEL_SIZE): Double =
    runCatching { textWidth(text, typeface(Font.MONOSPACED, size)) }
        .getOrElse { text.length * size * 0.6 }

/**
 * Greedy word wrap by *measured* width, capped at [maxLines] with an ellipsis on the overflow.
 *
 * Measured and not counted, even though the face is monospace, because the fallback in
 * [chartLabelWidth] is an estimate and the whole point of this function is that the result fits.
 *
 * A single word too wide for a line of its own is hard-broken. That is not a nicety: a 200-character
 * URL in a note is exactly the input that produced the defect this exists to fix, and a wrap that
 * politely leaves it on one line has not wrapped anything.
 */
private fun wrapToWidth(text: String, maxWidth: Double, size: Double, maxLines: Int): List<String> {
    if (text.isBlank()) return emptyList()
    if (maxWidth <= 0.0 || chartLabelWidth(text, size) <= maxWidth) return listOf(text)

    val lines = mutableListOf<String>()
    var line = ""
    fun push() { if (line.isNotEmpty()) { lines += line; line = "" } }
    text.split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { word ->
        var rest = word
        while (chartLabelWidth(rest, size) > maxWidth) {
            push()
            var cut = rest.length - 1
            while (cut > 1 && chartLabelWidth(rest.substring(0, cut), size) > maxWidth) cut--
            lines += rest.substring(0, cut)
            rest = rest.substring(cut)
        }
        val candidate = if (line.isEmpty()) rest else "$line $rest"
        if (chartLabelWidth(candidate, size) <= maxWidth) line = candidate else { push(); line = rest }
    }
    push()

    if (lines.size <= maxLines) return lines
    val kept = lines.take(maxLines).toMutableList()
    var last = kept[maxLines - 1]
    while (last.isNotEmpty() && chartLabelWidth("$last …", size) > maxWidth) last = last.dropLast(1)
    kept[maxLines - 1] = "$last …"
    return kept
}

// ── clipping ───────────────────────────────────────────────────────────────────────────────────
//
// In *pixel* space against `Chart.plotRect`, not in data space against the domain, for two reasons
// worth stating once. A log scale is not affine, so a data-space box and a pixel-space box are not
// the same box. And bars, bands and rules are positioned in pixels from the plot rectangle to begin
// with, so a pixel-space clip is the only one that can be applied to all of them uniformly.

/** Whether ([x], [y]) is inside this rectangle, edges included. */
private fun Rect.holds(x: Double, y: Double): Boolean =
    x >= this.x && x <= this.x + w && y >= this.y && y <= this.y + h

/**
 * Liang–Barsky: the part of the segment ([x0], [y0]) → ([x1], [y1]) inside [r], or `null`.
 *
 * Returned as `[x, y, x, y]` rather than as objects because this runs once per segment per mark and
 * a 20,000-point series would otherwise allocate 80,000 pairs to draw one line.
 *
 * Lifted from the workaround in `public/docs/tetgen.md`'s fence (`9f7ffc4f2`), which is where this
 * algorithm was first written in this repo, in a page, because the library would not do it.
 */
private fun clipSegment(x0: Double, y0: Double, x1: Double, y1: Double, r: Rect): DoubleArray? {
    val dx = x1 - x0
    val dy = y1 - y0
    val edge = doubleArrayOf(-dx, dx, -dy, dy)
    val slack = doubleArrayOf(x0 - r.x, (r.x + r.w) - x0, y0 - r.y, (r.y + r.h) - y0)
    var t0 = 0.0
    var t1 = 1.0
    for (k in 0..3) {
        if (edge[k] == 0.0) {
            // Parallel to this edge: either wholly on the right side of it or wholly off.
            if (slack[k] < 0.0) return null
        } else {
            val t = slack[k] / edge[k]
            if (edge[k] < 0.0) { if (t > t1) return null; if (t > t0) t0 = t }
            else { if (t < t0) return null; if (t < t1) t1 = t }
        }
    }
    return doubleArrayOf(x0 + t0 * dx, y0 + t0 * dy, x0 + t1 * dx, y0 + t1 * dy)
}

/**
 * A polyline clipped to [r], as the runs that survive.
 *
 * Runs and not one path: a series that leaves the window and comes back did not travel in a straight
 * line across the outside, and joining the two visible pieces would draw a chord that is not in the
 * data. Consecutive clipped segments that meet are joined, so a series entirely inside comes back as
 * exactly one run with its original vertices.
 */
private fun clipPolyline(points: List<DoubleArray>, r: Rect): List<List<DoubleArray>> {
    if (points.isEmpty()) return emptyList()
    if (points.size == 1) return if (r.holds(points[0][0], points[0][1])) listOf(points) else emptyList()
    val runs = mutableListOf<MutableList<DoubleArray>>()
    for (i in 0 until points.size - 1) {
        val a = points[i]
        val b = points[i + 1]
        val seg = clipSegment(a[0], a[1], b[0], b[1], r)
        if (seg == null) continue
        // Continue the run in progress only if this piece starts exactly where that one ended. If it
        // does not, the series went outside in between and this is a new run.
        val head = runs.lastOrNull()?.last()
        val continues = head != null && abs(head[0] - seg[0]) <= 1e-9 && abs(head[1] - seg[1]) <= 1e-9
        val run = if (continues) runs.last() else mutableListOf(doubleArrayOf(seg[0], seg[1])).also { runs += it }
        run.add(doubleArrayOf(seg[2], seg[3]))
    }
    return runs
}

/**
 * Sutherland–Hodgman: [points], as a closed polygon, clipped to [r].
 *
 * A filled region cannot use [clipPolyline] — cutting its edges leaves it open where it crossed the
 * window and the triangulator then fills a shape nobody drew. Clipping the polygon against each of
 * the four half-planes in turn keeps it closed, which is the property the fill depends on. [r] is
 * convex, which is the algorithm's one precondition.
 */
private fun clipPolygon(points: List<DoubleArray>, r: Rect): List<DoubleArray> {
    var out = points
    for (k in 0..3) {
        if (out.size < 3) return emptyList()
        fun inside(p: DoubleArray) = when (k) {
            0 -> p[0] >= r.x
            1 -> p[0] <= r.x + r.w
            2 -> p[1] >= r.y
            else -> p[1] <= r.y + r.h
        }
        fun crossing(a: DoubleArray, b: DoubleArray): DoubleArray {
            val denominator = if (k < 2) b[0] - a[0] else b[1] - a[1]
            if (denominator == 0.0) return a
            val t = when (k) {
                0 -> (r.x - a[0]) / denominator
                1 -> (r.x + r.w - a[0]) / denominator
                2 -> (r.y - a[1]) / denominator
                else -> (r.y + r.h - a[1]) / denominator
            }
            return doubleArrayOf(a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1]))
        }
        val next = mutableListOf<DoubleArray>()
        for (i in out.indices) {
            val a = out[i]
            val b = out[(i + 1) % out.size]
            val aIn = inside(a)
            if (aIn) next += a
            if (aIn != inside(b)) next += crossing(a, b)
        }
        out = next
    }
    return out
}

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
        // Filled, not stroked. `toFLines` is per-glyph and honours holes by default, so a filled
        // glyph keeps its counters -- the `o` stays an `o`. Stroking an outline at label size draws
        // a hollow letterform standing in for filled type, which is what made these labels read as
        // outlines beside a Plotly plot's real text.
        layout.toFLines(color = colour, thickness = 0.0, filled = true, stroked = false)
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
// Small multiples
// ==============================================================================================


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
