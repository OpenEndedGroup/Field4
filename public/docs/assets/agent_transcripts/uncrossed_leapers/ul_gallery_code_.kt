import afield.geometry.leaper.*

/*
Five leapers on three surfaces, n×n. A cylinder joins the left and right edges; a torus also
joins top and bottom. A move may leave through a joined edge and come back through the
opposite one, and "uncrossed" is judged on that surface. Dashed blue edges are joined.
*/
val n = 7
val nodeBudget = 1_000_000L
val leapers = [Leaper.KNIGHT, Leaper.CAMEL, Leaper.ZEBRA, Leaper.GIRAFFE, Leaper.ANTELOPE]
val topologies = BoardTopology.entries
val cell = 10.0
val step = n * cell + 16.0
val out = ArrayList<FLine>()
val lengths = leapers.map { ArrayList<String>() }
leapers.forEachIndexed { c, leaper -> out += leaperLabel(leaper.name, 40.0 + c * step, -6.0, 6.0) }
topologies.forEachIndexed { r, t ->
    out += leaperLabel(t.name.lowercase(), 0.0, r * step + n * cell / 2, 5.0)
    leapers.forEachIndexed { c, leaper ->
        val board = LeaperBoard(n, n, leaper, t)
        val result = board.bestUncrossedPath(nodeBudget = nodeBudget, rounds = 300)
        val x = 40.0 + c * step
        val y = r * step
        out += result.best?.uncrossedTile(cell, x, y) ?: board.boardLines(cell, x, y)
        lengths[c] += "${result.length}${if (result.exhaustive) "" else "?"}"
    }
}
lines(out, w = 900.0, h = 520.0, fitToSize = true)
markdown(
    "| $n×$n | rectangle | cylinder | torus |\n|---|---|---|---|\n" +
        leapers.indices.joinToString("\n") { k -> "| ${leapers[k]} | ${lengths[k].joinToString(" | ")} |" } +
        "\n\n? = not proved: the best of a $nodeBudget-step search and 300 rounds of ruin and recreate",
)
