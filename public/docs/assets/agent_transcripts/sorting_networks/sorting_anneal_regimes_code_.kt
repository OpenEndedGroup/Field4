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
val mergeDense = SortingNetwork(
    8,
    merge.comparators +
        [
            SortingComparator(0, 1),
            SortingComparator(2, 3),
            SortingComparator(4, 5),
            SortingComparator(6, 7),
        ],
)

val configs = [
    Triple("bubble", SortingNetwork.bubble(8), "#8ad6ff"),
    Triple("merge+", mergeDense, "#ffb857"),
    Triple("merge", merge, "#94ffad"),
].flatMapIndexed { familyIndex, (family, initial, hue) ->
    [0.7 to 0.05, 1.5 to 0.02, 3.2 to 0.01].flatMapIndexed { tempIndex, (startTemp, endTemp) ->
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
    val best = SortingNetwork.anneal(
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
        counts =
            if (countTrace.lastOrNull()?.first == iterations) countTrace
            else countTrace + (iterations to best.comparators.size.toDouble()),
        depths =
            if (depthTrace.lastOrNull()?.first == iterations) depthTrace
            else depthTrace + (iterations to best.depth().toDouble()),
        best = best,
    )
}

// One faint line per run, coloured by its starting network.
fun traces(title: String, pick: (RunResult) -> List<Pair<Int, Double>>) =
    Plotly.plot {
            for (run in runs) scatter {
                x.set(pick(run).map { it.first })
                y.set(pick(run).map { it.second })
                mode = ScatterMode.lines
                line {
                    color(run.config.hue)
                    width = 1.4
                }
                opacity = 0.5
                showlegend = false
            }
            layout {
                this.title = title
                xaxis { this.title = "iteration" }
                height = 300
            }
        }
        .darkMode()

display("sorting-anneal-counts", traces("Best so far: comparators") { it.counts })
display("sorting-anneal-depths", traces("Best so far: depth") { it.depths })

// Where each run finished, one marker per run, grouped by starting network.
display(
    "sorting-anneal-results",
    Plotly.plot {
            for ((family, group) in runs.groupBy { it.config.family }) scatter {
                name = family
                x.set(group.map { it.best.comparators.size })
                y.set(group.map { it.best.depth() })
                mode = ScatterMode.markers
                marker {
                    size = 12
                    color(group.first().config.hue)
                }
            }
            layout {
                title = "Where each run finished"
                xaxis { title = "comparators" }
                yaxis { title = "depth" }
                height = 360
            }
        }
        .darkMode(),
)
