import afield.geometry.leaper.*

/*
Closed paths: the path must come back to its first square, and the closing move may not
cross anything either. Up to 7×7 the search is exhaustive; 8×8 to 10×10 use ruin and
recreate. The known maxima are OEIS A157416.
*/
val sizes = 4..10
val cell = 10.0
var x = 0.0
val results = sizes.map { n -> LeaperBoard(n, n).bestUncrossedPath(closed = true, nodeBudget = 3_000_000) }
val tiles = results.flatMap { r ->
    val tile = r.best!!.uncrossedTile(cell, x, 0.0) + leaperLabel("${r.board.w}×${r.board.w}  ${r.length}", x, -4.0)
    x += r.board.w * cell + 14.0
    tile
}
lines(tiles, w = 900.0, h = 200.0, fitToSize = true)
markdown(
    "| board | moves found | A157416 | proved |\n|---|---|---|---|\n" +
        results.joinToString("\n") { r ->
            "| ${r.board.w}×${r.board.w} | ${r.length} | ${UNCROSSED_KNIGHT_CYCLE_MAXIMA[r.board.w - 1]} | ${if (r.exhaustive) "yes" else "no"} |"
        },
)
