import afield.geometry.leaper.*
import afield.runtime.execution.begin
import kotlin.math.*
import kotlin.random.Random

// Longest uncrossed knight paths and the longest closed one on 7×7, published under the knight-tour workshop's
// property names, so the workshop's line and arc views connected below draw them unchanged.
val w = 7
val cell = 10.0
val board = LeaperBoard(w, w)
/*
Row 1: four 24-move paths (the proved maximum) of different shapes, not turns or reflections of
one another: the first four distinct results from seeds 1, 2, 3, ..., each search stopping at its
first 24-move path. Row 2: the 24-move closed path, with its first square repeated at the end
so the closing move is drawn. Forty seeds find just one shape of it.
*/
val paths =
    generateSequence(1) { it + 1 }
        .map { seed -> board.longestUncrossedPath(random = Random(seed), atLeast = 23, stopAt = 24).best!! }
        .distinctBy { it.shapeKey() }
        .take(4)
        .toList()
val cycle = board.longestUncrossedPath(closed = true, random = Random(1), atLeast = 23, stopAt = 24).best!!
val rows = [paths.map { it.cells }, [cycle.cells + cycle.cells[0]]]

// Sample the circumcircle A -> B -> C, choosing the sweep that passes through B.
// Split at B so all three original points occur exactly in the sampled stroke.
fun threePointArc(
    a: Pair<Double, Double>,
    b: Pair<Double, Double>,
    c: Pair<Double, Double>,
): List<Pair<Double, Double>> {
    val (ax, ay) = a
    val bx = b.first - ax
    val by = b.second - ay
    val cx = c.first - ax
    val cy = c.second - ay
    val cross = bx * cy - by * cx
    if (abs(cross) < 1e-9) return listOf(a, b, c)
    val bb = bx * bx + by * by
    val cc = cx * cx + cy * cy
    val ux = (bb * cy - cc * by) / (2 * cross)
    val uy = (bx * cc - cx * bb) / (2 * cross)
    val radius = hypot(ux, uy)
    val start = atan2(-uy, -ux)
    val middle = atan2(by - uy, bx - ux)
    val end = atan2(cy - uy, cx - ux)
    val tau = 2 * PI
    fun positive(angle: Double) = ((angle % tau) + tau) % tau
    val toMiddle = positive(middle - start)
    val toEnd = positive(end - start)
    val sweep = if (toMiddle <= toEnd) toEnd else toEnd - tau
    val firstSweep = if (sweep > 0) toMiddle else toMiddle - tau
    val result = mutableListOf(a)
    fun segment(angle: Double, delta: Double, endpoint: Pair<Double, Double>) {
        val steps = max(1, ceil(abs(delta) / (PI / 48)).toInt())
        for (j in 1 until steps) {
            val theta = angle + delta * j / steps
            result.add((ax + ux + radius * cos(theta)) to (ay + uy + radius * sin(theta)))
        }
        result.add(endpoint)
    }
    segment(start, firstSweep, b)
    segment(middle, sweep - firstSweep, c)
    return result
}

val arcRows = rows.map { row ->
    row.map { tour ->
        val points = tour.map { i -> ((i % w + 0.5) * cell) to ((i / w + 0.5) * cell) }
        points.windowed(3).map { q -> threePointArc(q[0], q[1], q[2]) }
    }
}
prop<List<List<IntArray>>>(knightTourRows) = rows
prop<List<List<List<List<Pair<Double, Double>>>>>>(knightArcRows) = arcRows
prop<Int>(knightBoardWidth) = w
prop<Int>(knightBoardHeight) = w
prop<Double>(knightCellSize) = cell

val gap = 12.0
val tiles =
    [paths, [cycle]].flatMapIndexed { r, row ->
        row.flatMapIndexed { c, path -> path.uncrossedTile(cell, c * (w * cell + gap), r * (w * cell + gap)) }
    }
lines(tiles, fitToSize = true)
println("Published ${paths.size} differently shaped 24-move paths and the 24-move closed path on 7×7.")
children.forEach { it.begin() }
