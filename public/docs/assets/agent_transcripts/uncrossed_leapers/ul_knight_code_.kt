import afield.geometry.leaper.*

// The longest uncrossed knight path on each board from 3×3 to 7×7, by exhaustive search.
// The table sets each result beside OEIS A003192, the known maxima.
val sizes = 3..7
val cell = 10.0
var x = 0.0
val results = sizes.map { n -> LeaperBoard(n, n).longestUncrossedPath() }
val tiles = results.flatMap { r ->
    val tile = r.best!!.uncrossedTile(cell, x, 0.0) + leaperLabel("${r.board.w}×${r.board.w}  ${r.length}", x, -4.0)
    x += r.board.w * cell + 14.0
    tile
}
lines(tiles, w = 900.0, h = 230.0, fitToSize = true)
markdown(
    "| board | moves found | A003192 | proved | nodes searched | ms |\n|---|---|---|---|---|---|\n" +
        results.joinToString("\n") { r ->
            "| ${r.board.w}×${r.board.w} | ${r.length} | ${UNCROSSED_KNIGHT_PATH_MAXIMA[r.board.w - 1]} | " +
                "${if (r.exhaustive) "yes" else "no"} | ${r.nodes} | ${r.millis} |"
        },
)
