import afield.geometry.leaper.*
import kotlin.random.Random

/*
Past 7×7 a proof takes minutes (35 on 8×8 needs 337 million search steps), so this box uses
ruin and recreate: cut a random tail off the best path so far and regrow it with a short
search, 1000 times. It is not a proof, and where it ends depends on the seed, so each board
gets four seeds; the best is drawn, against the known optimum, 35 and 47.
*/
val seeds = 1..4
val cell = 10.0
var x = 0.0
val tiles = ArrayList<FLine>()
for (n in [8, 9]) {
    val board = LeaperBoard(n, n)
    val runs = seeds.map { s -> board.longUncrossedPath(random = Random(s)) }
    val best = runs.maxBy { it.length }
    val known = UNCROSSED_KNIGHT_PATH_MAXIMA[n - 1]
    tiles += best.best!!.uncrossedTile(cell, x, 0.0)
    tiles += leaperLabel("$n×$n  ${best.length} of $known", x, -4.0)
    x += n * cell + 16.0
    println(
        "$n×$n: seeds $seeds reach ${runs.map { it.length }} moves (known optimum $known), ${runs.sumOf { it.millis }} ms in all",
    )
}
lines(tiles, w = 700.0, h = 360.0, fitToSize = true)
