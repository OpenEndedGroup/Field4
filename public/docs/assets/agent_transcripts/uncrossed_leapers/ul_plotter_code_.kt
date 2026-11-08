import afield.geometry.leaper.*

// A plotter sheet: the longest uncrossed knight paths on a 6×6 rectangle, cylinder and torus,
// with their labels, as stroke-only SVG (board squares left out, the joined edges kept as dashes).
val n = 6
val cell = 10.0
val sheet = ArrayList<FLine>()
BoardTopology.entries.forEachIndexed { c, t ->
    val board = LeaperBoard(n, n, Leaper.KNIGHT, t)
    val r = board.bestUncrossedPath(nodeBudget = 1_000_000)
    val x = c * (n * cell + 20.0)
    sheet += board.boardLines(cell, x, 0.0)
    sheet += r.best!!.pathLine(cell, x, 0.0)
    sheet += leaperLabel("${t.name.lowercase()}  ${r.length}${if (r.exhaustive) "" else "?"}", x, n * cell + 10.0, 5.0)
}
val svg = plotterSvg(sheet, widthMm = 297.0, heightMm = 120.0, marginMm = 12.0)
val file = java.io.File(System.getProperty("java.io.tmpdir"), "uncrossed_knights.svg")
file.writeText(svg)
display("plotter sheet", svg)
println("${svg.lines().count { it.startsWith("<path") }} pen paths, ${svg.length} bytes -> $file")
