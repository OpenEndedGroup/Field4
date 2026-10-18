import space.kscience.plotly.*
import space.kscience.plotly.models.ScatterMode

val n = 12
val meanSamples = 180
val traceCount = 24
val bubble = SortingNetwork.bubble(n)
val merge = SortingNetwork.oddEvenMerge(n)
val maxInv = n * (n - 1) / 2.0

fun randomPermutation(size: Int): IntArray {
    val xs = (0 until size).toMutableList()
    xs.shuffle()
    return xs.toIntArray()
}

fun inversions(values: IntArray): Int {
    var inv = 0
    for (i in values.indices) for (j in i + 1 until values.size) if (values[i] > values[j]) inv++
    return inv
}

fun profile(net: SortingNetwork): List<Double> {
    val sums = DoubleArray(net.depth() + 1)
    repeat(meanSamples) {
        net.layerTrace(randomPermutation(n)).forEachIndexed { index, snapshot ->
            sums[index] += inversions(snapshot) / maxInv
        }
    }
    return sums.map { it / meanSamples.toDouble() }
}

fun traceFamily(net: SortingNetwork, count: Int): List<List<Double>> = List(count) {
    net.layerTrace(randomPermutation(n)).map { inversions(it) / maxInv }
}

val bubbleTraces = traceFamily(bubble, traceCount)
val mergeTraces = traceFamily(merge, traceCount)
val bubbleProfile = profile(bubble)
val mergeProfile = profile(merge)

// Each network's layers are spread over 0..1, so the two curves compare shape, not depth.
fun progress(values: List<Double>) = values.indices.map { it / (values.size - 1).toDouble() }

// Faint lines for single inputs, a bold line with a marker per layer for the mean.
fun Plot.network(label: String, traces: List<List<Double>>, mean: List<Double>, hue: String) {
    for (trace in traces) scatter {
        x.set(progress(trace))
        y.set(trace)
        mode = ScatterMode.lines
        line {
            color(hue)
            width = 1.0
        }
        opacity = 0.2
        showlegend = false
    }
    scatter {
        name = "$label, mean"
        x.set(progress(mean))
        y.set(mean)
        mode = ScatterMode.`lines+markers`
        line {
            color(hue)
            width = 3.0
        }
        marker {
            size = 7
            color(hue)
        }
    }
}

display(
    "sorting-disorder",
    Plotly.plot {
            network("bubble", bubbleTraces, bubbleProfile, "#94d9ff")
            network("odd-even merge", mergeTraces, mergeProfile, "#ffba61")
            layout {
                title = "Disorder left after each layer"
                xaxis { title = "fraction of the network's layers applied" }
                yaxis { title = "inversions / most possible" }
                height = 480
            }
        }
        .darkMode(),
)
