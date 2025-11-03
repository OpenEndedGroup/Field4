package afield

import afield.stdlib.Vec2
import kotlinx.html.*
import kotlinx.html.stream.createHTML
import space.kscience.plotly.*
import space.kscience.plotly.models.ScatterMode
import space.kscience.plotly.models.ViolinPoints
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid


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

fun Plot.darkMode(): Plot {
    layout {
        title {
            x = 0.05
            font {
                color("#f2f5fa")
            }
        }
        paper_bgcolor("rgb(17,17,17)")
        plot_bgcolor("rgb(17,17,17)")
        xaxis {
            gridcolor("#283442")
            linecolor("#506784")
            zerolinecolor("#283442")
            zerolinewidth = 2
        }
        yaxis {
            gridcolor("#283442")
            linecolor("#506784")
            zerolinecolor("#283442")
            zerolinewidth = 2
        }
    }

    if (this.layout.title?.isEmpty() != false) {
        layout {
            margin {
                t = 40
                l = 50
                b = 50
                r = 50
            }
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
