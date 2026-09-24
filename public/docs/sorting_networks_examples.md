# Sorting networks to FLine examples

*Every public declaration used below is listed, with its KDoc, in the codebase reference: [`sorting_networks.kt`](reference/data.md#sorting-networks-kt). Start from [what's already in the box](reference/index.md).*

Sorting networks make an algorithm hold still. The compare-and-swap plan is fixed ahead of time; the data is what moves. That makes them good material for pictures: topology first, then disorder collapse, then search over the topology itself.

## Topology: bubble against odd-even merge

Ten wires each, drawn at the same layer spacing, so the two panels are directly comparable. Bubble is `n * (n - 1) / 2` comparators, 45 at ten wires, arranged in as many columns as ASAP scheduling needs; Batcher's odd-even merge is O(n log²n) comparators in O(log²n) columns.

```diffstatic_runner {data-lang=kotlin screenshot="assets/sorting_networks_examples/topology_compare.png" wait="1.0" capture="box"}
:::static
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

The top panel runs to almost twice the width of the bottom one, and that is the comparison: the difference in cost is readable off the two diagrams before a single input goes through either.

![two wire diagrams stacked in tinted panels, twelve horizontal wires each, comparators drawn as coloured vertical bars with a dot at either end. The blue upper panel's comparators fill its whole width and form a long descending staircase of nested triangles. The orange lower panel's comparators are packed into a little over half the width, in tight columns of near-full-height bars, and the rest of its panel is empty](assets/sorting_networks_examples/topology_compare.png)

## Disorder collapse across layers

A diagram says nothing about how fast a network actually clears disorder. `layerTrace(...)` answers that: it returns the wire array after every parallel layer, so a random permutation can be watched collapsing. Each curve below is one such trace's inversion count divided by 66, the inversions in a fully reversed twelve-wire input.

```diffstatic_runner {data-lang=kotlin screenshot="assets/sorting_networks_examples/disorder_decay.png" wait="1.0" capture="box"}
:::static
import afield.graphics.FLine

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

fun axis() = listOf(
    fline {
        moveTo(70, 580); lineTo(1120, 580)
        moveTo(70, 120); lineTo(70, 580)
        color = vec(1, 1, 1, 0.22)
        thickness = 1.2
    },
    fline {
        for (i in 0..8) {
            val y = 580.0 - i / 8.0 * 430.0
            moveTo(70, y); lineTo(1120, y)
        }
        val maxDepth = maxOf(bubbleProfile.size, mergeProfile.size) - 1
        for (i in 0..maxDepth) {
            val x = 70.0 + i.toDouble() / maxDepth.coerceAtLeast(1).toDouble() * 1050.0
            moveTo(x, 120.0); lineTo(x, 580.0)
        }
        color = vec(1, 1, 1, 0.08)
        thickness = 1.0
    }
)

fun curve(values: List<Double>, color: Vec4, thickness: Double): FLine = fline {
    values.forEachIndexed { i, v ->
        val x = 70.0 + i.toDouble() / (values.size - 1).coerceAtLeast(1).toDouble() * 1050.0
        val y = 580.0 - v * 430.0
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    this.color = color
    this.thickness = thickness
}

fun markers(values: List<Double>, color: Vec4, size: Double): FLine = fline {
    filled = true
    stroked = false
    values.forEachIndexed { i, v ->
        val x = 70.0 + i.toDouble() / (values.size - 1).coerceAtLeast(1).toDouble() * 1050.0
        val y = 580.0 - v * 430.0
        moveTo(x - size, y - size)
        lineTo(x + size, y - size)
        lineTo(x + size, y + size)
        lineTo(x - size, y + size)
        close()
    }
    this.color = color
}

val markers =
    bubbleTraces.map { curve(it, vec(0.58, 0.85, 1.0, 0.12), 1.2) } +
        mergeTraces.map { curve(it, vec(1.0, 0.73, 0.38, 0.12), 1.2) } +
        listOf(
            curve(bubbleProfile, vec(0.58, 0.85, 1.0, 0.95), 3.2),
            curve(mergeProfile, vec(1.0, 0.73, 0.38, 0.95), 3.2),
            markers(bubbleProfile, vec(0.58, 0.85, 1.0, 0.95), 4.5),
            markers(mergeProfile, vec(1.0, 0.73, 0.38, 0.95), 4.5),
        )

lines(
    axis() + markers,
    channel = "sorting-disorder",
    w = 1190.0,
    h = 660.0,
    fitToSize = true
)
```

Faint lines are single permutations, bright lines the mean over 180 of them, and there is one square marker per layer. Both networks have to arrive at zero on the right-hand edge, since that is what being a sorting network means, and the horizontal axis is each network's own depth stretched to the full width, so this compares shape, not duration. Bubble's greater depth shows up as its markers being packed closer together, never as a longer curve.

Odd-even merge front-loads. Its first layer is the steepest segment of either mean, where bubble's first layer barely moves its own; bubble then sags through a long middle and clears the last inversions only in its final layers.

![two bright mean curves with square markers falling from the top left to zero at the right-hand edge, over a spray of faint single-run curves in the same two colours. The orange curve drops sharply at its first step, sags through a long shallow middle, and clears the last of its height at the right. The blue curve barely moves at first, then falls steeply through the middle, and the two cross about four-fifths of the way across. The blue markers are packed much closer together than the orange ones](assets/sorting_networks_examples/disorder_decay.png)

## One annealing run

`SortingNetwork.anneal(...)` reports its best-so-far network through a callback, not just its final answer, so the whole trajectory can be plotted.

```diffstatic_runner {data-lang=kotlin screenshot="assets/sorting_networks_examples/anneal_search.png" wait="1.0" capture="box" timeout="70"}
:::static
import afield.graphics.FLine

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

fun chartFrame(x: Double, y: Double, w: Double, h: Double) = listOf(
    fline {
        moveTo(x, y); lineTo(x + w, y); lineTo(x + w, y + h); lineTo(x, y + h); close()
        filled = true
        stroked = true
        thickness = 1.0
        color = vec(0.10, 0.12, 0.16, 0.55)
    },
    fline {
        moveTo(x, y + h); lineTo(x + w, y + h)
        moveTo(x, y); lineTo(x, y + h)
        color = vec(1, 1, 1, 0.18)
        thickness = 1.0
    }
)

fun plotMetric(
    originX: Double,
    originY: Double,
    width: Double,
    height: Double,
    values: List<Pair<Int, Double>>,
    maxX: Double,
    maxY: Double,
    color: Vec4
): FLine = fline {
    values.forEachIndexed { idx, (iteration, value) ->
        val x = originX + iteration / maxX * width
        val y = originY + height - value / maxY * height
        if (idx == 0) moveTo(x, y) else lineTo(x, y)
    }
    this.color = color
    this.thickness = 2.8
}

val countValues = history.map { it.first to it.second.comparators.size.toDouble() }
val depthValues = history.map { it.first to it.second.depth().toDouble() }
val maxCount = countValues.maxOf { it.second }
val maxDepth = depthValues.maxOf { it.second }
val maxIter = history.maxOf { it.first }.toDouble().coerceAtLeast(1.0)

lines(
    chartFrame(60.0, 320.0, 1100.0, 190.0) +
        chartFrame(60.0, 560.0, 1100.0, 190.0) +
        shift(baseline.toFLines(wireSpacing = 26.0, layerSpacing = 26.0, margin = 28.0), 70.0, 50.0) +
        shift(best.toFLines(wireSpacing = 26.0, layerSpacing = 26.0, margin = 28.0), 660.0, 50.0) +
        listOf(
            plotMetric(60.0, 320.0, 1100.0, 190.0, countValues, maxIter, maxCount, vec(0.56, 0.92, 1.0, 0.95)),
            plotMetric(60.0, 560.0, 1100.0, 190.0, depthValues, maxIter, maxDepth, vec(0.56, 1.0, 0.66, 0.95)),
        ),
    channel = "sorting-anneal",
    w = 1220.0,
    h = 820.0,
    fitToSize = true
)
```

Left is the `bubble(8)` seed, right is the network the run came back with, and the two panels below are the best-so-far comparator count and depth. On this seed both step down twice inside the first third of the run and are flat for the rest of it: the search finds what it is going to find early and spends most of its 8000 iterations confirming it.

![two eight-wire comparator diagrams across the top, the left one visibly denser and wider than the right. Below them two wide panels, one cyan and one green, each holding a single trace that starts flat at the top left, steps down twice inside the first third, and then runs dead flat to the right-hand edge](assets/sorting_networks_examples/anneal_search.png)

## Twenty-seven annealing runs

Three seed topologies, three temperature schedules and three random seeds, all at eight wires and 2000 iterations.

```diffstatic_runner {data-lang=kotlin screenshot="assets/sorting_networks_examples/anneal_regimes.png" wait="1.0" capture="box" timeout="90"}
:::static
import afield.graphics.FLine

data class RunConfig(
    val family: String,
    val initial: SortingNetwork,
    val initialTemp: Double,
    val finalTemp: Double,
    val seed: Long,
    val traceColor: Vec4,
    val pointColor: Vec4,
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
        Triple("bubble", SortingNetwork.bubble(8), vec(0.54, 0.84, 1.0, 0.22)),
        Triple("merge+", mergeDense, vec(1.0, 0.72, 0.34, 0.22)),
        Triple("merge", merge, vec(0.58, 1.0, 0.68, 0.22)),
    ).flatMapIndexed { familyIndex, (family, initial, color) ->
        listOf(0.7 to 0.05, 1.5 to 0.02, 3.2 to 0.01).flatMapIndexed { tempIndex, (startTemp, endTemp) ->
            (0 until 3).map { seed ->
                RunConfig(
                    family = family,
                    initial = initial,
                    initialTemp = startTemp,
                    finalTemp = endTemp,
                    seed = (familyIndex * 100 + tempIndex * 10 + seed).toLong(),
                    traceColor = color,
                    pointColor =
                        when (family) {
                            "bubble" -> vec(0.54, 0.84, 1.0, 0.88)
                            "merge+" -> vec(1.0, 0.72, 0.34, 0.88)
                            else -> vec(0.58, 1.0, 0.68, 0.88)
                        },
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

val maxCount = runs.maxOf { run -> run.counts.maxOf { it.second } }
val maxDepth = runs.maxOf { run -> run.depths.maxOf { it.second } }
val minComparators = runs.minOf { it.best.comparators.size }.toDouble()
val maxComparators = runs.maxOf { it.best.comparators.size }.toDouble()
val minDepth = runs.minOf { it.best.depth() }.toDouble()
val maxDepthPoint = runs.maxOf { it.best.depth() }.toDouble()

fun chartFrame(x: Double, y: Double, w: Double, h: Double) = listOf(
    fline {
        moveTo(x, y); lineTo(x + w, y); lineTo(x + w, y + h); lineTo(x, y + h); close()
        filled = true
        stroked = true
        thickness = 1.0
        color = vec(0.10, 0.12, 0.16, 0.55)
    },
    fline {
        moveTo(x, y + h); lineTo(x + w, y + h)
        moveTo(x, y); lineTo(x, y + h)
        color = vec(1, 1, 1, 0.18)
        thickness = 1.0
    }
)

fun traceCurve(
    values: List<Pair<Int, Double>>,
    originX: Double,
    originY: Double,
    width: Double,
    height: Double,
    maxY: Double,
    color: Vec4,
): FLine = fline {
    values.forEachIndexed { idx, (iteration, value) ->
        val x = originX + iteration / iterations.toDouble() * width
        val y = originY + height - value / maxY * height
        if (idx == 0) moveTo(x, y) else lineTo(x, y)
    }
    this.color = color
    this.thickness = 1.6
}

fun scatterPoints(): List<FLine> =
    runs.map { run ->
        val x = 80.0 + (run.best.comparators.size - minComparators) / (maxComparators - minComparators + 1e-9) * 1080.0
        val y = 880.0 - (run.best.depth() - minDepth) / (maxDepthPoint - minDepth + 1e-9) * 180.0
        fline {
            filled = true
            stroked = false
            moveTo(x - 7, y - 7)
            lineTo(x + 7, y - 7)
            lineTo(x + 7, y + 7)
            lineTo(x - 7, y + 7)
            close()
            color = run.config.pointColor
        }
    }

val countCurves = runs.map { traceCurve(it.counts, 80.0, 80.0, 1080.0, 180.0, maxCount, it.config.traceColor) }
val depthCurves = runs.map { traceCurve(it.depths, 80.0, 340.0, 1080.0, 180.0, maxDepth, it.config.traceColor) }

lines(
    chartFrame(80.0, 80.0, 1080.0, 180.0) +
        chartFrame(80.0, 340.0, 1080.0, 180.0) +
        chartFrame(80.0, 620.0, 1080.0, 260.0) +
        countCurves +
        depthCurves +
        scatterPoints(),
    channel = "sorting-anneal-regimes",
    w = 1240.0,
    h = 940.0,
    fitToSize = true
)
```

Two thirds of the traces in the top two panels are flat lines, and the reason is in the KDoc rather than in the search: `oddEvenMerge(8)` is 19 comparators, which `anneal` lists as the known optimum for eight wires. All nine merge-seeded runs therefore start where the search would like to finish, never move, and land on one another as the single green square at the left of the scatter. `merge+` is that same network with four of its first-layer comparators appended a second time — 23 comparators of which exactly four are provably dead — and its runs spread out across the middle, because they recover different numbers of the four. The bubble-seeded runs start from 28 and sit up and to the right, having spent 2000 iterations without reaching either merge family's depth.

The scatter puts comparator count across the page and depth up it. Across the whole grid the seed topology separates the results almost perfectly by colour, and the temperature schedule barely registers.

![three stacked panels. The top two each hold twenty-seven traces, most of them dead-flat horizontal lines, with a bundle of blue ones wandering and stepping down through the first half before flattening; a green line lies flat along the bottom of both. The third panel is a scatter: one green square alone at the far left on the floor, four orange squares stepping rightward along the same floor, and five blue squares higher and further right, the highest of them near the top right](assets/sorting_networks_examples/anneal_regimes.png)

For more fixed structure with plotted behaviour, continue with [Graph connectivity examples](graph_connectivity_examples.md) and [Boolean networks examples](boolean_networks_examples.md).
