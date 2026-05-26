package afield

import afield.stdlib.Vec2
import afield.stdlib.Vec3
import kotlinx.html.*
import kotlinx.html.stream.createHTML
import space.kscience.plotly.*
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

fun Plot.darkMode(): Plot {
    layout {
        title {
            x = 0.05
            font {
                color("#f2f5fa")
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
        }
        yaxis {
            gridcolor("#283442")
            linecolor("#000000")
            zerolinecolor("#283442")
            color("#aaaaaa")
            tickangle = 20.0
            zerolinewidth = 3
        }
    }

    if (this.layout.title?.isEmpty() != false) {
        layout {
            margin {
                t = 30
                l = 30
                b = 30
                r = 30
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
