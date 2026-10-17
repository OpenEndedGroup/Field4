import space.kscience.plotly.*
import space.kscience.plotly.models.ScatterMode

val baseline = SortingNetwork.bubble(8)
val history = mutableListOf<Pair<Int, SortingNetwork>>()

val best = SortingNetwork.anneal(
    n = 8,
    iterations = 8_000,
    seed = 7L,
    progressEvery = 120,
) { iter, net ->
        history += iter to net
    }

fun shift(lines: List<FLine>, dx: Double, dy: Double) = lines.map { it + vec(dx, dy, 0) }

// The starting network on the left, the one the search came back with on the right.
lines(
    shift(baseline.toFLines(wireSpacing = 26.0, layerSpacing = 26.0, margin = 28.0), 0.0, 0.0) +
        shift(best.toFLines(wireSpacing = 26.0, layerSpacing = 26.0, margin = 28.0), 460.0, 0.0),
    channel = "sorting-anneal",
    w = 1220.0,
    h = 300.0,
    fitToSize = true,
)

// The best network so far, measured two ways, against the iteration it was found at.
fun progress(title: String, hue: String, measure: (SortingNetwork) -> Int) =
    Plotly.plot {
            scatter {
                x.set(history.map { it.first })
                y.set(history.map { measure(it.second) })
                mode = ScatterMode.lines
                line {
                    color(hue)
                    width = 2.0
                }
            }
            layout {
                this.title = title
                xaxis { this.title = "iteration" }
                height = 280
                width = 1220
            }
        }
        .darkMode()

display("sorting-anneal-count", progress("Best so far: comparators", "#8ee9ff") { it.comparators.size })
display("sorting-anneal-depth", progress("Best so far: depth", "#8fffa8") { it.depth() })
