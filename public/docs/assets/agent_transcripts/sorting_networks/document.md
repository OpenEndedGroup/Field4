# Field Document: sorting_networks

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. Bubble against odd-even merge

<!-- field-box id="sorting_topology" index="0" -->

```edn [field-box id="sorting_topology" role="properties"]
{"id" "sorting_topology" "index" 0 "name" "Bubble against odd-even merge" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Bubble against odd-even merge" "_tombstone" false "frame" #afield/rect [60.0 60.0 420.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sorting_topology_code_.kt" "lines" 33 "characters" 929 "sha256" "31a4bc0f187535673519d6a079a2fd7d269e8ac2efd0db6193730068951b5794"}]}
```

```kotlin [field-property box="sorting_topology" name="code" path="sorting_topology_code_.kt"]
import afield.graphics.FLine

val bubble = SortingNetwork.bubble(10)
val merge = SortingNetwork.oddEvenMerge(10)

fun shift(lines: List<FLine>, dx: Double, dy: Double) = lines.map { it + vec(dx, dy, 0) }

fun panel(x: Double, y: Double, w: Double, h: Double, color: Vec4) = fline {
    moveTo(x, y)
    lineTo(x + w, y)
    lineTo(x + w, y + h)
    lineTo(x, y + h)
    close()
    filled = true
    stroked = true
    thickness = 1.2
    this.color = color
}

val frames = listOf(
    panel(30.0, 40.0, 1030.0, 390.0, vec(0.08, 0.14, 0.20, 0.55)),
    panel(30.0, 500.0, 1030.0, 390.0, vec(0.18, 0.11, 0.07, 0.52)),
)

lines(
    frames +
        shift(bubble.toFLines(wireSpacing = 34.0, layerSpacing = 46.0, margin = 42.0), 70.0, 95.0) +
        shift(merge.toFLines(wireSpacing = 34.0, layerSpacing = 46.0, margin = 42.0), 70.0, 555.0),
    channel = "sorting-topology",
    w = 1120.0,
    h = 940.0,
    fitToSize = true
)
```

## 2. Disorder collapse across layers

<!-- field-box id="sorting_disorder" index="1" -->

```edn [field-box id="sorting_disorder" role="properties"]
{"id" "sorting_disorder" "index" 1 "name" "Disorder collapse across layers" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Disorder collapse across layers" "_tombstone" false "frame" #afield/rect [1460.0 60.0 420.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sorting_disorder_code_.kt" "lines" 73 "characters" 2431 "sha256" "ce64966b9413aaefa4ecc89eabc67acf85a623fc2ab7b097dec8e97e526eeebb"}]}
```

```kotlin [field-property box="sorting_disorder" name="code" path="sorting_disorder_code_.kt"]
import afield.darkMode
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
        x.set(progress(trace)); y.set(trace)
        mode = ScatterMode.lines
        line { color(hue); width = 1.0 }
        opacity = 0.2
        showlegend = false
    }
    scatter {
        name = "$label, mean"
        x.set(progress(mean)); y.set(mean)
        mode = ScatterMode.`lines+markers`
        line { color(hue); width = 3.0 }
        marker { size = 7; color(hue) }
    }
}

display("sorting-disorder", Plotly.plot {
    network("bubble", bubbleTraces, bubbleProfile, "#94d9ff")
    network("odd-even merge", mergeTraces, mergeProfile, "#ffba61")
    layout {
        title = "Disorder left after each layer"
        xaxis { title = "fraction of the network's layers applied" }
        yaxis { title = "inversions / most possible" }
        height = 480
    }
}.darkMode())
```

## 3. Starting networks and schedules

<!-- field-box id="sorting_anneal_regimes" index="2" -->

```edn [field-box id="sorting_anneal_regimes" role="properties"]
{"id" "sorting_anneal_regimes" "index" 2 "name" "Starting networks and schedules" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Starting networks and schedules" "_tombstone" false "frame" #afield/rect [1460.0 960.0 420.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sorting_anneal_regimes_code_.kt" "lines" 109 "characters" 3759 "sha256" "4018a9abb53def38c5178e50ea49879897e9f3690328c560a9fcf7f659aa3a7a"}]}
```

```kotlin [field-property box="sorting_anneal_regimes" name="code" path="sorting_anneal_regimes_code_.kt"]
import afield.darkMode
import space.kscience.plotly.*
import space.kscience.plotly.models.ScatterMode

data class RunConfig(
    val family: String,
    val initial: SortingNetwork,
    val initialTemp: Double,
    val finalTemp: Double,
    val seed: Long,
    val hue: String,
)

data class RunResult(
    val config: RunConfig,
    val counts: List<Pair<Int, Double>>,
    val depths: List<Pair<Int, Double>>,
    val best: SortingNetwork,
)

val merge = SortingNetwork.oddEvenMerge(8)
val mergeDense =
    SortingNetwork(
        8,
        merge.comparators +
            listOf(
                SortingComparator(0, 1),
                SortingComparator(2, 3),
                SortingComparator(4, 5),
                SortingComparator(6, 7),
            ),
    )

val configs =
    listOf(
        Triple("bubble", SortingNetwork.bubble(8), "#8ad6ff"),
        Triple("merge+", mergeDense, "#ffb857"),
        Triple("merge", merge, "#94ffad"),
    ).flatMapIndexed { familyIndex, (family, initial, hue) ->
        listOf(0.7 to 0.05, 1.5 to 0.02, 3.2 to 0.01).flatMapIndexed { tempIndex, (startTemp, endTemp) ->
            (0 until 3).map { seed ->
                RunConfig(
                    family = family,
                    initial = initial,
                    initialTemp = startTemp,
                    finalTemp = endTemp,
                    seed = (familyIndex * 100 + tempIndex * 10 + seed).toLong(),
                    hue = hue,
                )
            }
        }
    }

val iterations = 2_000
val progressEvery = 80

val runs = configs.map { config ->
    val countTrace = mutableListOf<Pair<Int, Double>>()
    val depthTrace = mutableListOf<Pair<Int, Double>>()
    val best =
        SortingNetwork.anneal(
            n = 8,
            initialNetwork = config.initial,
            iterations = iterations,
            initialTemp = config.initialTemp,
            finalTemp = config.finalTemp,
            seed = config.seed,
            progressEvery = progressEvery,
        ) { iter, net ->
            countTrace += iter to net.comparators.size.toDouble()
            depthTrace += iter to net.depth().toDouble()
        }
    RunResult(
        config = config,
        counts = if (countTrace.lastOrNull()?.first == iterations) countTrace else countTrace + (iterations to best.comparators.size.toDouble()),
        depths = if (depthTrace.lastOrNull()?.first == iterations) depthTrace else depthTrace + (iterations to best.depth().toDouble()),
        best = best,
    )
}

// One faint line per run, coloured by its starting network.
fun traces(title: String, pick: (RunResult) -> List<Pair<Int, Double>>) = Plotly.plot {
    for (run in runs) scatter {
        x.set(pick(run).map { it.first }); y.set(pick(run).map { it.second })
        mode = ScatterMode.lines
        line { color(run.config.hue); width = 1.4 }
        opacity = 0.5
        showlegend = false
    }
    layout { this.title = title; xaxis { this.title = "iteration" }; height = 300 }
}.darkMode()

display("sorting-anneal-counts", traces("Best so far: comparators") { it.counts })
display("sorting-anneal-depths", traces("Best so far: depth") { it.depths })

// Where each run finished, one marker per run, grouped by starting network.
display("sorting-anneal-results", Plotly.plot {
    for ((family, group) in runs.groupBy { it.config.family }) scatter {
        name = family
        x.set(group.map { it.best.comparators.size }); y.set(group.map { it.best.depth() })
        mode = ScatterMode.markers
        marker { size = 12; color(group.first().config.hue) }
    }
    layout {
        title = "Where each run finished"
        xaxis { title = "comparators" }; yaxis { title = "depth" }
        height = 360
    }
}.darkMode())
```

## 4. One annealing run

<!-- field-box id="sorting_anneal" index="3" -->

```edn [field-box id="sorting_anneal" role="properties"]
{"id" "sorting_anneal" "index" 3 "name" "One annealing run" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "One annealing run" "_tombstone" false "frame" #afield/rect [60.0 960.0 420.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sorting_anneal_code_.kt" "lines" 41 "characters" 1511 "sha256" "ce670047a1601224255ba3569f5baf0c55d9bee8ba152799fd6d31496ae90915"}]}
```

```kotlin [field-property box="sorting_anneal" name="code" path="sorting_anneal_code_.kt"]
import afield.darkMode
import afield.graphics.FLine
import space.kscience.plotly.*
import space.kscience.plotly.models.ScatterMode

val baseline = SortingNetwork.bubble(8)
val history = mutableListOf<Pair<Int, SortingNetwork>>()

val best = SortingNetwork.anneal(
    n = 8,
    iterations = 8_000,
    seed = 7L,
    progressEvery = 120
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
    fitToSize = true
)

// The best network so far, measured two ways, against the iteration it was found at.
fun progress(title: String, hue: String, measure: (SortingNetwork) -> Int) = Plotly.plot {
    scatter {
        x.set(history.map { it.first }); y.set(history.map { measure(it.second) })
        mode = ScatterMode.lines
        line { color(hue); width = 2.0 }
    }
    layout { this.title = title; xaxis { this.title = "iteration" }; height = 280; width = 1220 }
}.darkMode()

display("sorting-anneal-count", progress("Best so far: comparators", "#8ee9ff") { it.comparators.size })
display("sorting-anneal-depth", progress("Best so far: depth", "#8fffa8") { it.depth() })
```

