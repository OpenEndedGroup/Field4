@file:afield.runtime.completion.CompletionCategory("Visualization")

package afield.ui.visualization

import afield.app.diagnostics.named
import afield.app.protocol.payload
import afield.app.server.flag
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.runtime.completion.CompletionCategory
import afield.runtime.execution.plus
import afield.runtime.lifecycle.end
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem
import afield.runtime.lifecycle.then
import afield.ui.notation.double
import afield.ui.notation.long
import afield.ui.notation.string
import afield.ui.output.histogram
import afield.ui.output.logs
import afield.ui.output.md

import afield.audio.synth.*
import afield.data.table.div
import afield.document.graph.Box
import afield.document.graph.box
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import afield.runtime.lifecycle.map
import afield.ui.notation.with
import afield.ui.output.templates.filter
import afield.math.linalg.Vec2
import afield.math.linalg.Vec3
import kotlinx.html.*
import kotlinx.html.stream.createHTML
import space.kscience.plotly.*
import space.kscience.plotly.models.Dash
import space.kscience.plotly.models.ScatterMode
import space.kscience.plotly.models.ViolinPoints
import space.kscience.plotly.models.XAnchor
import space.kscience.plotly.models.YAnchor
import space.kscience.plotly.models.ShapeLayer
import space.kscience.plotly.models.ShapeType
import space.kscience.dataforge.meta.asValue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

//
// This is the DEFAULT chart path in Field. `charts.kt`'s FLine `Chart` exists only for figures that
// must compose with other FLine geometry, be pickable through Field's interactive SVG, or draw an
// expectation band; anything else belongs here. Both capture headlessly through the docs runner —
// `public/docs/dough_dsp_sound.md` has a dozen `diffstatic_runner` fences over `showSignals`.
//
/**
 * Plotly helpers — the short way to get a chart out of a box, wrapping `space.kscience.plotly`.
 *
 * `linePlot`, [scatter], [scatterSized] and [violin] cover the common shapes (lists of numbers,
 * `Vec2`, `Vec3`) and hand back a `Plot` already run through [darkMode], the repo dark theme with
 * data-first margins. [showSignals] goes one step further: pass `"label" to values` pairs and it
 * traces them against a shared x with the [signalPalette], optionally min-max normalized and with
 * dashed reference marks, then displays the plot into a channel of a live `Box` — it is already on
 * screen when it returns, so do not display it again.
 *
 * [toHtml] renders a `Plot` into an HTML fragment via [PlotlyIntegration]. That fragment calls
 * `Plotly.react` in the browser, so the page has to have the Plotly library loaded already;
 * without it the fragment logs to the console and draws nothing.
 */

/**
 * A dark-mode line plot of one or more lists of numbers against their own sample index — the fastest
 * way to look at a signal. Each list becomes its own hairline trace, so lists of different lengths
 * are fine: they simply run out at different x.
 *
 * The `Plot` is returned, **not** displayed — hand it to `display` yourself, or use [showSignals],
 * which labels the traces and puts them on screen in one call. [darkMode] has already run, and it
 * sized the top margin for an *untitled* plot, so a title set after this returns gets a slim band;
 * see [darkMode].
 */
@JvmName("linePlot_scalar")
fun linePlot(vararg data: List<Number>): Plot {
    return Plotly.plot {
        data.forEach {
            trace {
                x.set(it.mapIndexed { index, _ -> index })
                y.set(it)
                line.width = 1.0
            }
        }
    }.darkMode()
}

/**
 * A dark-mode line plot of one or more `Vec2` polylines, using each point's own x and y — the variant
 * to reach for when x is a real abscissa (time, frequency, arc length) rather than a sample index.
 * Points are joined in list order, so this draws a path and will happily double back on itself.
 *
 * Same conventions as the scalar overload: one hairline trace per list, [darkMode] already applied,
 * and the `Plot` returned rather than displayed.
 */
@JvmName("linePlot_vec2")
fun linePlot(vararg data: List<Vec2>): Plot {
    return Plotly.plot {
        data.forEach {
            trace {
                x.set(it.map { it.x })
                y.set(it.map { it.y })
                line.width = 1.0
            }
        }
    }.darkMode()
}

/**
 * A dark-mode markers-only scatter of one or more `Vec2` lists, at a fixed 3px marker — for point
 * clouds where the order of the points means nothing and a connecting line would lie about it.
 *
 * Reach for [scatterSized] instead when a third number per point should drive the marker size.
 * [darkMode] is already applied and the `Plot` is returned, not displayed.
 */
fun scatter(vararg data: List<Vec2>): Plot {
    return Plotly.plot {
        data.forEach {
            scatter {
                x.set(it.map { it.x })
                y.set(it.map { it.y })
                mode = ScatterMode.markers
                marker {
                    size = 3.0
                }
            }
        }
    }.darkMode()
}

/**
 * A dark-mode scatter of `Vec3` lists in which x and y place the marker and **z is the marker size** —
 * a bubble chart, for carrying a third quantity per point.
 *
 * The z values go to Plotly as marker sizes with no normalisation and no `sizeref`, so they are read
 * as diameters in pixels: a z of 0.5 is invisible and a z of 400 swallows the chart. Scale z yourself.
 * [darkMode] is applied and the `Plot` is returned, not displayed.
 */
fun scatterSized(vararg data: List<Vec3>): Plot {
    return Plotly.plot {
        data.forEach {
            scatter {
                x.set(it.map { it.x })
                y.set(it.map { it.y })
                mode = ScatterMode.markers
                marker {
                    sizesList = it.map { it.z }
                }
            }
        }
    }.darkMode()
}

/**
 * A dark-mode violin plot, one violin per list of numbers, for comparing whole distributions rather
 * than sequences — order within a list is irrelevant here, only the spread of values.
 *
 * Each violin carries its inner box and its mean line, and draws individual points only for outliers.
 * The traces are left unnamed, so name them yourself when you plot more than one or the
 * legend will be useless. [darkMode] is applied and the `Plot` is returned, not displayed.
 */
fun violin(vararg data: List<Number>): Plot {
    return Plotly.plot {
        data.forEach {
            violin {
                y.set(it)
                name = ""
                box.visible = true
                meanline.visible = true
                points = ViolinPoints.outliers
            }
        }
    }.darkMode()
}

/**
 * Named violins side by side, each with an optional reference value drawn as a dashed line across
 * its own violin — for "what was asked for against what came out" over several groups: a target
 * radius against the measured radii, a set point against the readings.
 *
 * Pass `"label" to values` pairs; each pair becomes one violin at category `label`, coloured in
 * [signalPalette] order, with its inner box and mean line as in the unnamed overload. [references],
 * when given, holds one number per group in the same order (a NaN skips that group) and is drawn in
 * [referenceColor] as a dashed horizontal segment spanning that violin's slot only, so four groups
 * with four different targets read as four comparisons rather than one shared threshold. [yRange]
 * pins the value axis; [yTitle] names it.
 *
 * Every value is serialised into the page, so thin a very large sample (tens of thousands of points
 * per group) before plotting; the shape survives decimation long before Plotly's payload does.
 * [darkMode] is applied and the `Plot` is returned, not displayed.
 */
@JvmName("violin_named")
fun violin(
    vararg groups: Pair<String, List<Number>>,
    references: List<Number> = emptyList(),
    referenceColor: String = "#ffe066",
    yRange: ClosedFloatingPointRange<Double>? = null,
    yTitle: String? = null,
): Plot {
    require(references.isEmpty() || references.size == groups.size) {
        "violin got ${groups.size} groups and ${references.size} references; give one per group or none"
    }
    return Plotly.plot {
        groups.forEachIndexed { i, (label, values) ->
            violin {
                x.set(List(values.size) { label })
                y.set(values)
                name = label
                box.visible = true
                meanline.visible = true
                points = ViolinPoints.outliers
                line.color(signalPalette[i % signalPalette.size])
            }
        }
        layout {
            showlegend = false
            yaxis {
                yTitle?.let { title = it }
                yRange?.let { range = it }
            }
            references.forEachIndexed { i, ref ->
                val v = ref.toDouble()
                if (v.isNaN()) return@forEachIndexed
                figure {
                    type = ShapeType.line
                    xref = "x"
                    yref = "y"
                    x0 = (i - 0.45).asValue()
                    x1 = (i + 0.45).asValue()
                    y0 = v.asValue()
                    y1 = v.asValue()
                    line {
                        color(referenceColor)
                        width = 1.5
                        dash = Dash.dash
                    }
                }
            }
        }
    }.darkMode()
}

/** A labelled x-span for [showSignals]' `regions`: a translucent band from [from] to [to]. */
data class SignalRegion(val label: String, val from: Number, val to: Number, val color: String = "#ffe066")

/** Cyan/orange-led dark-mode palette for signal traces (leads cyan, then orange). */
val signalPalette: List<String> = listOf("#7ad9ff", "#ff9e4a", "#8fff9a", "#ffe066", "#c58cff", "#ff7a9e")

/**
 * Put one or more named signals on the screen as a dark-mode Plotly line plot, and return the [Plot].
 *
 * The terse "these signals, on screen, now" helper: pass `"label" to values` pairs and it traces
 * them against a shared x (sample index by default, or [x]), applies the [signalPalette] and the
 * repo dark theme, and displays into [channel]. This collapses the usual
 * `Plotly.plot { trace {...}; trace {...} }.darkMode(); display(plot)` boilerplate to one call.
 *
 * - [x]: shared x values for every series (e.g. frequency in Hz); defaults to the sample index.
 * - [marks]: vertical dashed reference lines, `"label" to xPosition` (e.g. a spectral centroid or
 *   rolloff, or a regime boundary), spanning the full y-range, drawn in [markColor].
 * - [normalize]: min-max each series into `[0,1]` — use when the signals live on very different
 *   scales (e.g. a centroid in Hz alongside an RMS in `[0,1]`) and you only care about their shape.
 * - [regions]: translucent bands over x-spans, each labelled at the top — where a detector fired,
 *   where a motif occurs. Drawn under the traces.
 * - [width] / [height]: the figure size in CSS pixels; the default is Plotly's 700 × 450.
 * - [floatingLegend]: put the legend inside the plot's upper right on a translucent dark panel
 *   instead of outside its right edge, so a wide figure is all data area. It can cover data
 *   there, which is why it is not the default; use it when the upper right is quiet.
 *
 * The plot is already displayed on return; the [Plot] is returned only for further tweaking (do not
 * `display` it again).
 */
fun Box.showSignals(
    vararg series: Pair<String, List<Number>>,
    x: List<Number>? = null,
    channel: String = "output",
    title: String? = null,
    xTitle: String? = null,
    yTitle: String? = null,
    marks: List<Pair<String, Number>> = emptyList(),
    markColor: String = "#ffe066",
    normalize: Boolean = false,
    regions: List<SignalRegion> = emptyList(),
    width: Int? = null,
    height: Int? = null,
    floatingLegend: Boolean = false,
): Plot {
    fun shape(values: List<Number>): List<Number> {
        if (!normalize) return values
        val ds = values.map { it.toDouble() }
        // NaN marks a gap; it must not decide the range, or every point normalizes to NaN.
        val lo = ds.filter { !it.isNaN() }.minOrNull() ?: 0.0
        val hi = ds.filter { !it.isNaN() }.maxOrNull() ?: 1.0
        val span = (hi - lo).takeIf { it > 1e-12 } ?: 1.0
        return ds.map { (it - lo) / span }
    }
    val plot = Plotly.plot {
        series.forEachIndexed { i, (label, values) ->
            trace {
                name = label
                this.x.set(x ?: values.indices.toList())
                this.y.set(shape(values))
                line.width = 1.4
                line.color(signalPalette[i % signalPalette.size])
            }
        }
        if (marks.isNotEmpty()) {
            val allY = series.flatMap { shape(it.second).map { n -> n.toDouble() } }.filter { !it.isNaN() }
            val ymin = allY.minOrNull() ?: 0.0
            val ymax = allY.maxOrNull() ?: 1.0
            marks.forEach { (label, mx) ->
                trace {
                    name = label
                    this.x.set(listOf(mx, mx))
                    this.y.set(listOf(ymin, ymax))
                    line.width = 1.2
                    line.color(markColor)
                    line.dash = Dash.dash
                }
            }
        }
        layout {
            title?.let { this.title = it }
            xaxis { xTitle?.let { this.title = it } }
            yaxis { yTitle?.let { this.title = it } }
            width?.let { this.width = it }
            height?.let { this.height = it }
            for (r in regions) {
                figure {
                    type = ShapeType.rect
                    layer = ShapeLayer.below
                    xref = "x"
                    yref = "paper"
                    x0 = r.from.asValue()
                    x1 = r.to.asValue()
                    y0 = 0.asValue()
                    y1 = 1.asValue()
                    fillcolor(r.color)
                    opacity = 0.28
                    line { this.width = 0 }
                }
                annotation {
                    text = r.label
                    xref = "x"
                    yref = "paper"
                    this.x = ((r.from.toDouble() + r.to.toDouble()) / 2).asValue()
                    y = 1.0.asValue()
                    yanchor = YAnchor.bottom
                    showarrow = false
                    font { color(r.color); size = 12 }
                }
            }
        }
    }.darkMode()
    if (floatingLegend) plot.layout {
        legend {
            xanchor = XAnchor.right
            yanchor = YAnchor.top
            this.x = 0.99
            y = 0.99
            bgcolor("rgba(17,17,17,0.92)")
            bordercolor("rgba(170,170,170,0.35)")
            borderwidth = 1
        }
        margin { r = 16 }
    }
    display(channel, plot)
    return plot
}

/**
 * Put a spectrogram of [audio] on the screen as a Plotly heatmap with time and frequency axes, and
 * return the [Plot].
 *
 * Frequencies run from 0 to [maxHz], so a low [maxHz] zooms in on bass. [fftSize] sets the
 * frequency resolution, `sampleRate / fftSize` Hz per bin (8192 at 48 kHz is 5.9 Hz, fine enough
 * to separate a bass note from the tone an octave under it), and [hopSize] the time step. Colour is
 * level in dB relative to the loudest bin, over a [rangeDb] range.
 */
fun Box.showSpectrogram(
    audio: FloatArray,
    sampleRate: Int = 48_000,
    maxHz: Double = 600.0,
    fftSize: Int = 8192,
    hopSize: Int = 1024,
    rangeDb: Double = 70.0,
    channel: String = "output",
    title: String? = null,
): Plot {
    val spec = afield.audio.analysis.stft(audio, fftSize = fftSize, hopSize = hopSize, sampleRate = sampleRate.toDouble())
    val bins = (0 until spec.numBins).filter { spec.binFrequency(it) <= maxHz }
    val mags = spec.magnitudes()
    val peak = mags.maxOf { m -> bins.maxOf { m[it] } }.coerceAtLeast(1e-12)
    val db = bins.map { b -> mags.map { m -> (20.0 * kotlin.math.log10((m[b] / peak).coerceAtLeast(1e-9))).coerceAtLeast(-rangeDb) } }
    val plot = Plotly.plot {
        heatmap {
            x.set((0 until spec.numFrames).map { spec.frameTime(it) })
            y.set(bins.map { spec.binFrequency(it) })
            z.set(db)
            zmin = -rangeDb
            zmax = 0.0
            colorscale = space.kscience.dataforge.meta.Value.of("Viridis")
        }
        layout {
            if (title != null) this.title = title
            xaxis { this.title = "seconds" }
            yaxis { this.title = "Hz" }
        }
    }.darkMode()
    display(channel, plot)
    return plot
}

/**
 * Restyle a `Plot` into the repo's dark theme and hand back the same instance — near-black paper and
 * plot backgrounds, muted grid and tick colours, tick labels held horizontal, a vertical legend just
 * outside the plot's right edge, and data-first margins that reclaim most of the ~100px Plotly
 * reserves up top for a title.
 *
 * It mutates the receiver rather than copying, so the `Plot` you get back is the one you passed in.
 * The top margin is decided *at the moment you call it*, from whether a title is already set: 46px if
 * so, 22px if not. Set the title first, or a title added afterwards is squeezed into the slim band.
 * Axis `automargin` stays on, so tick labels and axis titles still get the room they need. Everything
 * else in this file already calls this for you — you only need it on a `Plot` you built by hand.
 *
 * The legend sits **outside** the plot area, against its right edge, so it cannot cover data.
 * Until 2026-07-30 it was pinned *inside* at the lower right, where it hid the tail of every series
 * that descended to the right — 11 published panels across 6 pages were losing data to it. Plotly's
 * autoMargin reserves the width, so a plot with long trace labels gets a narrower data area rather
 * than a clipped legend; keep the labels short if the data area matters more.
 */
fun Plot.darkMode(): Plot {
    val hasTitle = !this.layout.title.isNullOrEmpty()
    layout {
        title {
            x = 0.05
            font {
                color("#f2f5fa")
                size = 15
            }
        }
        // The legend sits OUTSIDE the plot area, to its right. Until 2026-07-30 it was pinned inside
        // at the lower right (yanchor top, y 0.2), where it silently covered the tail of any series
        // descending to the right — a decaying error curve, or the right tail of a null histogram,
        // which is exactly the shape you most want to read. The upper right is no better, only
        // differently wrong: rising traces end there. No position inside the frame is safe for a
        // shared helper.
        //
        // Above the frame does not work either, because these legends are *vertical*. `orientation`
        // is deliberately not set here: this plotly-kt serialises the Kotlin enum by name, emitting
        // `orientation: "horizontal"` where Plotly.js accepts only "h" or "v", so it is rejected and
        // the default vertical stack is drawn. Every committed figure on the site shows that stack,
        // including ones with 11 entries — far too tall for a top band. Setting the property at all
        // only puts an invalid attribute in the payload, so it is gone.
        //
        // x = 1.02 in paper coordinates is just past the plot area's right edge; Plotly's autoMargin
        // then reserves the width the legend needs on top of the `margin.r` below, which is why that
        // number does not have to know how long the labels are.
        legend {
            yanchor = YAnchor.top
            xanchor = XAnchor.left
            y = 1.0
            x = 1.02
        }
        paper_bgcolor("rgb(17,17,17)")
        plot_bgcolor("rgb(17,17,17)")
        // Both axes carried `tickangle = 20.0` from `30461ba4f` onwards. **That is a real trade, not
        // an oversight**: a positive tick angle improves the chances that large dense labels — long
        // numbers, many ticks, a narrow figure — do not collide with each other.
        //
        // It is set to 0 here because the cost lands on the labels a reader needs most. Iosevka's `1`
        // has a prominent flag and no base serif, so on a 20 degree tilt it reads as a `7`, and its
        // `0` is slashed, so a tilted `0` stops reading as a disambiguated zero. On a normalised
        // axis those two glyphs are exactly the endpoints: a plot whose own y title said "of 1"
        // appeared to top out at 7.
        //
        // What it gives up turns out to be nothing this helper can reach, and that was measured
        // rather than assumed. On a NUMERIC axis Plotly already defends against collision twice
        // before an angle would matter: it picks the tick *count* from the axis width, and it
        // *abbreviates* large magnitudes with an SI suffix. Asked for the densest axis this helper
        // can be given — 96 000 six-digit sample indices in a 1460 px panel — it emitted
        // `0 20k 40k 60k 80k`, five short labels using about a quarter of their slot each. Across
        // the four densest committed figures (`streaming_features`, `filter_chains`,
        // `one_spectrum`, `resonator_bank`) the worst case was five labels at roughly 40 px in a
        // 160 px slot. There is no published plot where the 20 degrees was saving anything.
        //
        // So the angle only earns its keep on labels Plotly can neither thin nor abbreviate — long
        // categorical strings, dates — and `showSignals` draws neither: its x is a sample index or
        // a caller's numeric list. If a caller ever does hit collisions, the escalations in order
        // are `dtick`/`nticks` for that plot, then `tickangle` on that plot's `xaxis` ONLY (a y tick
        // is a short number stacked vertically, where rotation buys nothing and costs legibility),
        // then Plotly's `"auto"`, which rotates only when it judges the labels crowded.
        xaxis {
            gridcolor("#283442")
            linecolor("#000000")
            zerolinecolor("#283442")
            color("#aaaaaa")
            tickangle = 0.0
            zerolinewidth = 3
            automargin = true
        }
        yaxis {
            gridcolor("#283442")
            linecolor("#000000")
            zerolinecolor("#283442")
            color("#aaaaaa")
            tickangle = 0.0
            zerolinewidth = 3
            automargin = true
        }
        // Data-first margins: Plotly reserves ~100px up top for a title by default.
        // Give the title only a slim band (or almost none when untitled) so the plot
        // area gets the space. Axis `automargin` above still grows the left/bottom
        // gutters as needed, so tick labels and axis titles never clip.
        // `r` stays slim because the legend is not competing for it: sitting outside the plot area
        // it goes through Plotly's autoMargin, which expands the right gutter past this figure by
        // however much the labels actually need (`margin.autoexpand` defaults on).
        margin {
            t = if (hasTitle) 46 else 22
            b = 34
            l = 44
            r = 24
        }
    }
    return this
}

/**
 * Render a `Plot` to an inline HTML fragment: a plain wrapper `div` around the plot's own `div`, which
 * carries a fresh random id and a script that calls `Plotly.react` on it. Not an iframe and not a whole document — this is exactly what a box
 * output does with a `Plot`, so the result drops straight into the editor's output pane.
 *
 * The host page must already have the Plotly library loaded. If it does not, the script logs
 * "Plotly not loaded" to the browser console and you are left with an empty div: no exception, no
 * placeholder. The traces and layout are serialised into that script as JavaScript literals, so a
 * non-finite value survives as a bare NaN or Infinity token — legal JavaScript, so Plotly receives a
 * real NaN and leaves a gap in the line there, but *not* legal JSON, so never route this payload
 * through a JSON parser. The id is a new UUID on every call, so two calls give two independent charts.
 */
@OptIn(ExperimentalUuidApi::class)
fun Plot.toHtml(): String {

    val p = PlotlyIntegration()
    var plot = this
    with(p) {
        var frag: String = createHTML().div {
            renderPlot(plot, Uuid.random().toString(), PlotlyConfig() {
                responsive = true
            })
        }
        return frag
    }
}

/**
 * The `PlotlyRenderer` behind [toHtml]: it emits a bare `div` plus a `Plotly.react` call against it,
 * rather than the self-contained page plotly-kt's own renderers produce. That is what makes the output
 * embeddable in a box output or a docs page that already loads Plotly.
 *
 * Rarely worth using directly — [toHtml] is the front door, and [renderFragment] is here for a
 * `PlotlyFragment` holding several plots at once.
 */
public class PlotlyIntegration : PlotlyRenderer {
    /**
     * Emit `plot` into the surrounding `kotlinx.html` flow as a div with id `plotId`, followed by an
     * inline script handing the trace data, layout and `config` to `Plotly.react`. Returns the plot
     * unchanged.
     *
     * The script is guarded on `Plotly` being defined, so a page without the library logs to the
     * console rather than throwing. Everything is serialised as JavaScript literals, which is what
     * lets NaN through intact — see [toHtml].
     */
    override fun FlowContent.renderPlot(plot: Plot, plotId: String, config: PlotlyConfig): Plot {
        div {
            id = plotId
            script {
                unsafe {
                    //language=JavaScript
                    +"""
                        if(typeof Plotly !== "undefined"){
                            Plotly.react(
                                    '$plotId',
                                    ${plot.data.toJsonString()},
                                    ${plot.layout.toJsonString()},
                                    ${config.toJsonString()}
                                );       
                        } else {
                            console.error("Plotly not loaded")
                        }
                    """.trimIndent()
                }
            }
        }
        return plot
    }

    /**
     * Render a whole `PlotlyFragment` — a piece of `kotlinx.html` that may hold several plots and
     * ordinary markup between them — to an HTML string, with every plot inside it going through this
     * renderer. The result is wrapped in a single enclosing div; the same "Plotly must already be
     * loaded" caveat applies as for [toHtml].
     */
    fun renderFragment(fragment: PlotlyFragment): String = createHTML().div {
        with(fragment) {
            render(this@PlotlyIntegration)
        }
    }
}
