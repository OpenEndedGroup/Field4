import java.util.Base64

/*
Grow the flower for 300 steps, cut it in one of thirteen ways, and follow it for 1,600 steps after
the cut, four times each with a different random update mask. Each cut is shown just after it is
made and at the end of its first run, with how its four runs ended; the chart shows the median
error against steps since the cut for six of them. Takes about 15 seconds on a 10-core machine.
*/
val target = GrowingNca.pretrainedTarget("flower")
val cuts = [
    "disc r 4, top" to { n: GrowingNca -> n.damage(32.0, 26.0, 4.0) },
    "disc r 8, top" to { n: GrowingNca -> n.damage(32.0, 26.0, 8.0) },
    "disc r 12, top" to { n: GrowingNca -> n.damage(32.0, 26.0, 12.0) },
    "disc r 16, top" to { n: GrowingNca -> n.damage(32.0, 26.0, 16.0) },
    "disc r 8, centre" to { n: GrowingNca -> n.damage(32.0, 32.0, 8.0) },
    "disc r 12, centre" to { n: GrowingNca -> n.damage(32.0, 32.0, 12.0) },
    "square 16, centre" to { n: GrowingNca -> n.damage { x, y -> x in 24 until 40 && y in 24 until 40 } },
    "stem" to { n: GrowingNca -> n.damage { x, y -> y >= 36 && x in 26..38 } },
    "left half" to { n: GrowingNca -> n.damage { x, _ -> x < 32 } },
    "right half" to { n: GrowingNca -> n.damage { x, _ -> x >= 32 } },
    "top half" to { n: GrowingNca -> n.damage { _, y -> y < 32 } },
    "bottom half" to { n: GrowingNca -> n.damage { _, y -> y >= 32 } },
    "only the seed left" to { n: GrowingNca -> n.damage { x, y -> !(x in 31..33 && y in 31..33) } },
]
// steps since the cut at which the error is recorded
val at = [1, 10, 25, 50, 100, 200, 400, 800, 1600]

fun img(n: GrowingNca) =
    "<img src='data:image/png;base64,${Base64.getEncoder().encodeToString(n.png(scale = 2))}' width='80'>"

// the target moved by (dx, dy) cells
fun shifted(dx: Int, dy: Int) =
    FloatArray(target.size) { i ->
        val x = i / 4 % 64 - dx
        val y = i / 4 / 64 - dy
        if (x in 0 until 64 && y in 0 until 64) target[(y * 64 + x) * 4 + i % 4] else 0f
    }
val moves = (-4..4).flatMap { dx -> (-4..4).map { dy -> Triple(dx, dy, shifted(dx, dy)) } }

// healed: back to the uncut error; moved: the same, against the target moved (dx, dy) cells (y down);
// second growth: alpha far from the picture; died: nothing alive; otherwise scarred
fun outcome(n: GrowingNca): String {
    if (n.liveCells() == 0) return "died"
    if (n.strayAlpha(target) > 50) return "second growth"
    if (n.loss(target) < 1e-4) return "healed"
    val best = moves.minBy { n.loss(it.third) }
    return if (n.loss(best.third) < 1e-4) "moved (${best.first}, ${best.second})" else "scarred"
}

// One run: grow 300 steps, cut, follow the error. Returns the error curve, how it ended, and the
// pictures just after the cut and at the end.
fun run(cut: (GrowingNca) -> Unit, seed: Int): Triple<List<Double>, String, String> {
    val nca = GrowingNca.pretrained("flower", random = kotlin.random.Random(seed))
    nca.step(300)
    cut(nca)
    var pictures = img(nca)
    val curve = mutableListOf<Double>()
    var done = 0
    for (s in at) {
        nca.step(s - done)
        done = s
        curve += nca.loss(target)
    }
    pictures += img(nca)
    return Triple(curve, outcome(nca), pictures)
}

// The 52 runs are independent, so they run in parallel, one per core.
val started = System.nanoTime()
val jobs = cuts.indices.flatMap { c -> (1..4).map { seed -> c to seed } }
val runs = jobs.parallelStream().map { (c, seed) -> run(cuts[c].second, seed) }.toList()
println("${runs.size} runs in ${((System.nanoTime() - started) / 1e9).f1} s")

val figures = mutableListOf<String>()
val curves = mutableListOf<Pair<String, List<Number>>>()
for ((c, cut) in cuts.withIndex()) {
    val mine = runs.slice(c * 4 until c * 4 + 4)
    val median = at.indices.map { i -> mine.map { it.first[i] }.sorted().let { (it[1] + it[2]) / 2 } }
    curves += cut.first to median
    val tally =
        mine
            .groupingBy { it.second.substringBefore(" (") }
            .eachCount()
            .entries
            .joinToString(", ") { "${it.key} ${it.value}" }
    val shifts = mine.map { it.second }.filter { it.startsWith("moved") }.joinToString("") { " " + it }
    println("${cut.first}: $tally$shifts; median error at 1,600 steps ${"%.1e".format(median.last())}")
    figures += "${mine[0].third}<br>**${cut.first}**<br>$tally"
}

// six of the thirteen curves, so the legend fits on one line
val charted = ["disc r 4, top", "disc r 12, top", "disc r 12, centre", "stem", "bottom half", "only the seed left"]
val recovery = chart(
    *curves.filter { it.first in charted }.toTypedArray(),
    x = at,
    title = "Recovery after a cut (median of four runs)",
    xLabel = "steps since the cut",
    yLabel = "error against the target",
)
recovery.xKind = ScaleKind.LOG
recovery.yKind = ScaleKind.LOG
recovery.width = 1100.0
// the figures five to a row, then the chart beneath them as SVG
val rows = figures.chunked(5).joinToString("\n\n") { FieldMarkdown.row(it) }
markdown("main", rows + "\n\n" + drawToSVG(recovery.toFLines(), recovery.width, recovery.height), width = 1150)
