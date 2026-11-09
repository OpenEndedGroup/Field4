import afield.geometry.leaper.*

/*
How much of the board each leaper can cover without crossing itself: the share of the n×n
squares its longest uncrossed path visits, for n = 4..9, on each surface. A hollow dot is a
best-found value (the search ran out of budget), a filled one is proved.
*/
val sizes = 4..9
val nodeBudget = 200_000L
val leapers = [Leaper.KNIGHT, Leaper.CAMEL, Leaper.ZEBRA, Leaper.GIRAFFE, Leaper.ANTELOPE]
val colours = [
    vec(0.98, 0.85, 0.3, 1.0),
    vec(0.45, 0.8, 1.0, 1.0),
    vec(0.95, 0.45, 0.45, 1.0),
    vec(0.6, 0.9, 0.5, 1.0),
    vec(0.8, 0.6, 1.0, 1.0),
]
val panelW = 150.0
val panelH = 100.0
val out = ArrayList<FLine>()
val table = StringBuilder(
    "| surface | leaper | " +
        sizes.joinToString(" | ") { "$it×$it" } +
        " |\n|---|---|" +
        sizes.joinToString("") { "---|" } +
        "\n",
)
BoardTopology.entries.forEachIndexed { p, t ->
    val ox = p * (panelW + 30.0)
    out += fline {
        moveTo(ox, 0.0)
        lineTo(ox, panelH)
        lineTo(ox + panelW, panelH)
        color = vec(1.0, 1.0, 1.0, 0.5)
        thickness = 0.8
    }
    out += leaperLabel(t.name.lowercase(), ox + 4.0, -6.0, 6.0)
    out += leaperLabel("100%", ox - 22.0, 2.0, 4.0)
    out += leaperLabel("n = ${sizes.first}", ox, panelH + 9.0, 4.0)
    out += leaperLabel("${sizes.last}", ox + panelW - 4.0, panelH + 9.0, 4.0)
    leapers.forEachIndexed { k, leaper ->
        val results = sizes.map { n ->
            LeaperBoard(n, n, leaper, t).bestUncrossedPath(nodeBudget = nodeBudget, rounds = 100, nodesPerRound = 4_000)
        }
        val pts = results.mapIndexed { i, r ->
            val share = (if (r.best == null) 0.0 else r.length + 1.0) / (r.board.cells)
            Triple(ox + i * panelW / (sizes.count() - 1), panelH * (1 - share), r)
        }
        out += fline {
            pts.forEachIndexed { i, (x, y, _) -> if (i == 0) moveTo(x, y) else lineTo(x, y) }
            color = colours[k]
            thickness = 1.2
        }
        pts.forEach { (x, y, r) ->
            out += fline {
                circle(x, y, 2.2)
                color = colours[k]
                filled = r.exhaustive
                thickness = 1.0
            }
        }
        table.append(
            "| ${t.name.lowercase()} | ${leaper.name} | " +
                results.joinToString(" | ") { "${it.length}${if (it.exhaustive) "" else "?"}" } +
                " |\n",
        )
    }
}
leapers.forEachIndexed { k, leaper -> out += leaperLabel(leaper.name, k * 70.0, panelH + 24.0, 5.0, colours[k]) }
lines(out, w = 900.0, h = 260.0, fitToSize = true)
markdown(
    table.toString() +
        "\nMoves in the longest path found; ? = not proved (best of a $nodeBudget-step search and 100 rounds of ruin and recreate).",
)
