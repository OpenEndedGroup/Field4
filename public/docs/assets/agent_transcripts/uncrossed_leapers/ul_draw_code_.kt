import afield.geometry.leaper.*

/*
Draw a knight path on the board this box puts on the drawing layer (Alt+D, then the pen),
one stroke through the centres of the squares, and run the box again. It reads the newest
hand-drawn stroke over the board, snaps it to squares and says whether it is a legal
uncrossed path. Until you draw one it checks a demonstration stroke of its own.
*/
val n = 6
val cell = 40.0
val left = frame.x + frame.w + 60.0
val top = frame.y
val board = LeaperBoard(n, n)
// The newest hand-drawn stroke that lies over the board (with a square's slack all round).
val drawn =
    notation.strokes.lastOrNull { m ->
        val b = m.bounds
        m.handDrawn &&
            b.x >= left - cell &&
            b.y >= top - cell &&
            b.x + b.w <= left + (n + 1) * cell &&
            b.y + b.h <= top + (n + 1) * cell
    }
// The demonstration: a1 b3 c5 e4 c3 d5 — the last move crosses the third.
val demoCells = intArrayOf(0, 13, 26, 22, 14, 27)
notation("board") {
    stroke = "#5c7cfa"
    for (i in 0 until n) for (j in 0 until n) if ((i + j) % 2 == 0) {
        rect(left + i * cell, top + j * cell, cell, cell, fill = "#dbe4ff", stroke = "transparent")
    }
    rect(left, top, n * cell, n * cell, fill = "transparent")
    if (drawn == null) {
        // Twelve samples along each move, the way a pen stroke records points.
        fun centreX(c: Int) = left + (c % n + 0.5) * cell
        fun centreY(c: Int) = top + (c / n + 0.5) * cell
        val samples = ArrayList<Vec2>()
        for (i in 0 until demoCells.size - 1) for (k in 0 until 12) {
            val t = k / 12.0
            val from = demoCells[i]
            val to = demoCells[i + 1]
            samples +=
                vec(
                    centreX(from) + (centreX(to) - centreX(from)) * t,
                    centreY(from) + (centreY(to) - centreY(from)) * t,
                )
        }
        samples += vec(centreX(demoCells.last()), centreY(demoCells.last()))
        stroke(samples, name = "demo path", stroke = "#e8590c")
    }
}
val mark = drawn ?: notation.named("demo path")!!
val pts = mark.points
val cells = board.readDrawnPath(
    pts.map { it.x.toDouble() }.toDoubleArray(),
    pts.map { it.y.toDouble() }.toDoubleArray(),
    cell,
    left,
    top,
)
val verdict = board.check(cells)
val names = cells.joinToString(" ") { board.cellName(it) }
val source = if (drawn == null) "the demonstration stroke" else "your stroke"
markdown(
    if (verdict == null)
        "**Legal.** $source visits $names: an uncrossed path of ${cells.size - 1} moves " +
            "(the longest on $n×$n is ${UNCROSSED_KNIGHT_PATH_MAXIMA[n - 1]})."
    else "**Not legal.** $source visits $names: $verdict.",
)
