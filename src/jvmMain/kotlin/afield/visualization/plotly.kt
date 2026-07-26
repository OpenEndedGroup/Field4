@file:afield.CompletionCategory("Visualization")

package afield

import afield.stdlib.Vec2
import afield.stdlib.Vec3
import kotlinx.html.*
import kotlinx.html.stream.createHTML
import space.kscience.plotly.*
import space.kscience.plotly.models.Dash
import space.kscience.plotly.models.LegendOrientation
import space.kscience.plotly.models.ScatterMode
import space.kscience.plotly.models.SizeMode
import space.kscience.plotly.models.ViolinPoints
import space.kscience.plotly.models.XAnchor
import space.kscience.plotly.models.YAnchor
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid


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
): Plot {
    fun shape(values: List<Number>): List<Number> {
        if (!normalize) return values
        val ds = values.map { it.toDouble() }
        val lo = ds.minOrNull() ?: 0.0
        val hi = ds.maxOrNull() ?: 1.0
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
            val allY = series.flatMap { shape(it.second).map { n -> n.toDouble() } }
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
        }
    }.darkMode()
    display(channel, plot)
    return plot
}

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
        legend {
            orientation = LegendOrientation.horizontal
            yanchor = YAnchor.top
            xanchor= XAnchor.right
            y = 0.2
            x = 1.0
        }
        paper_bgcolor("rgb(17,17,17)")
        plot_bgcolor("rgb(17,17,17)")
        xaxis {
            gridcolor("#283442")
            linecolor("#000000")
            zerolinecolor("#283442")
            color("#aaaaaa")
            tickangle = 20.0
            zerolinewidth = 3
            automargin = true
        }
        yaxis {
            gridcolor("#283442")
            linecolor("#000000")
            zerolinecolor("#283442")
            color("#aaaaaa")
            tickangle = 20.0
            zerolinewidth = 3
            automargin = true
        }
        // Data-first margins: Plotly reserves ~100px up top for a title by default.
        // Give the title only a slim band (or almost none when untitled) so the plot
        // area gets the space. Axis `automargin` above still grows the left/bottom
        // gutters as needed, so tick labels and axis titles never clip.
        margin {
            t = if (hasTitle) 46 else 22
            b = 34
            l = 44
            r = 24
        }
    }
    return this
}

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

public class PlotlyIntegration : PlotlyRenderer {
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

    fun renderFragment(fragment: PlotlyFragment): String = createHTML().div {
        with(fragment) {
            render(this@PlotlyIntegration)
        }
    }
}
