/*
The same 81 × 9 energy with nothing stated: its costs are learned from 3000 solved grids by
pseudolikelihood, relabelling the digits at random each time. Its same-digit image should match
the stated one, and it should solve the same puzzle.
*/
val rnd = kotlin.random.Random(1)
// solved grids by randomized backtracking, digits 0..8
fun solvedGrid(): IntArray {
    val g = IntArray(81) { -1 }
    fun ok(c: Int, d: Int) =
        (0 until 9).none { m ->
            g[c / 9 * 9 + m] == d || g[m * 9 + c % 9] == d || g[(c / 27 * 3 + m / 3) * 9 + c % 9 / 3 * 3 + m % 3] == d
        }
    fun fill(c: Int): Boolean {
        if (c == 81) return true
        for (d in (0 until 9).shuffled(rnd)) if (ok(c, d)) {
            g[c] = d
            if (fill(c + 1)) return true
            g[c] = -1
        }
        return false
    }
    fill(0)
    return g
}
val grids = List(3000) { solvedGrid() }

val learned = DiscreteEnergy(81, 9)
val learner = PseudolikelihoodLearner(
    learned,
    rate = 0.2f,
    l1 = 1e-4f,
    augment = { g, r ->
        val relabel = (0 until 9).shuffled(r)
        IntArray(81) { relabel[g[it]] }
    },
)
val t0 = System.currentTimeMillis()
repeat(10) { learner.epoch(grids) }
println("learned from ${grids.size} grids in ${System.currentTimeMillis() - t0} ms, loss ${learner.loss(grids)}")

showHeatmap(
    learned.sameValueImage(),
    colormap = "magma",
    smooth = false,
    flipY = false,
    width = 405,
    channel = "learned: same-digit cost",
)

// solve the stated puzzle with the learned energy, stopping at the training solutions' energy
val target = grids.maxOf { learned.energy(it) } + 1e-3
for (c in 0 until 81) if (sudokuEnergy.fixed[c] >= 0) learned.fix(c, sudokuEnergy.fixed[c])
val s = EnergyState(learned)
val steps = MeanFieldDescent(learned).settle(s)
GreedyMoves(learned).settle(s)
val scale = learned.constraintScale() // the learned constraints' strength sets the temperatures
for (attempt in 0 until 3) if (s.energy > target) {
    GibbsAnnealing(learned, 5000, 0.6 * scale, 0.15 * scale, seed = attempt.toLong()).settle(s)
    GreedyMoves(learned).settle(s)
}
println(
    "learned energy: $steps mean-field steps; the board breaks ${sudokuEnergy.energy(s.assignment)} stated constraints",
)
display("learned solve", sudokuBoard(s, "solved by the learned energy"))
