/*
N-queens: one variable per row, its value the column of that row's queen. Columns must differ,
and two rows a apart may not have queens a columns apart. Annealed Gibbs, one sweep per frame.
*/
val n = 12
val queens = DiscreteEnergy(n, n)
queens.allDifferent((0 until n).toList())
for (a in 0 until n) for (b in a + 1 until n) queens.pairCost(a, b) { x, y ->
    if (kotlin.math.abs(x - y) == b - a) 1.0 else 0.0
}

val s = EnergyState(queens).reset(kotlin.random.Random(4))
val gibbs = GibbsAnnealing(queens, sweeps = 200, startTemperature = 1.0, endTemperature = 0.05, seed = 4)
fun board() =
    "<div style='font:14px monospace;color:#ccc'>sweep ${gibbs.sweep}, ${s.energy.toInt()} attacks</div>" +
        "<div style='display:grid;grid-template-columns:repeat($n,20px);grid-auto-rows:20px;width:max-content'>" +
        (0 until n * n).joinToString("") { i ->
            val r = i / n
            val c = i % n
            "<div style='background:${if ((r + c) % 2 == 0) "#333" else "#222"};color:#ffcf6b;text-align:center;font:15px monospace'>" +
                (if (s.assignment[r] == c) "♛" else "") +
                "</div>"
        } +
        "</div>"

frames {
    while (s.energy > 0 && gibbs.step(s)) {
        display("board", board())
        frame()
    }
    GreedyMoves(queens).settle(s)
    display("board", board())
}
