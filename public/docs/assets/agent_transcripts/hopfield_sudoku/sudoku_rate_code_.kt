/*
Held-out puzzles at five difficulties, solved on the CPU: mean-field descent, then greedy
single-cell moves and annealed Gibbs search, up to four chains, stopping at the energy
threshold fitted from the training grids.
*/
val model = sudokuModel
val test = kotlin.random.Random(99)
val blanks = [30, 40, 45, 50, 55]
val rates = mutableListOf<Double>()
val steps = mutableListOf<Double>()
for (b in blanks) {
    var ok = 0
    var s = 0
    repeat(10) { k ->
        val (p, _) = model.generate(test, b)
        val r = model.solve(p, seed = k.toLong())
        if (model.isSolution(p, r.grid)) ok++
        s += r.descentSteps
    }
    rates += ok / 10.0
    steps += s / 10.0
    println("$b blanks: $ok/10 solved, ${s / 10} descent steps on average")
}
val xs = blanks.map { it.toDouble() }
display(
    "steps to settle",
    Chart(title = "harder puzzles take more steps", xLabel = "blanks", yLabel = "mean descent steps")
        .line("steps", xs, steps)
        .points("steps", xs, steps),
)
display(
    "solve rate",
    Chart(title = "solve rate", xLabel = "blanks", yLabel = "fraction solved")
        .line("solved", xs, rates)
        .points("solved", xs, rates),
)
