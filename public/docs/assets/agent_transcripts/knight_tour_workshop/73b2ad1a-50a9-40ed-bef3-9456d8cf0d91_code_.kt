import afield.runtime.execution.begin
import kotlin.math.*
import kotlin.random.Random

// Squares are numbered y * w + x. A tour is an IntArray of every square, starting at s, ending at t.

fun knightNeighbours(w: Int, h: Int): Array<IntArray> {
    val jumps = listOf(1 to 2, 2 to 1, -1 to 2, -2 to 1, 1 to -2, 2 to -1, -1 to -2, -2 to -1)
    return Array(w * h) { i ->
        jumps
            .mapNotNull { (dx, dy) ->
                val x = i % w + dx
                val y = i / w + dy
                if (x in 0 until w && y in 0 until h) y * w + x else null
            }
            .toIntArray()
    }
}

// Every move changes colour, so the endpoints' colours decide whether a tour can exist at all.
fun parityAllows(w: Int, h: Int, s: Int, t: Int): Boolean {
    val colour = { i: Int -> (i % w + i / w) % 2 }
    return if (w * h % 2 == 0) colour(s) != colour(t) else colour(s) == 0 && colour(t) == 0
}

// Every tour from s to t, each exactly once, lazily. Depth-first, Warnsdorff order (random tie-break
// if `random` is given), pruned by "a square still needs a way in and a way out".
fun allKnightTours(w: Int, h: Int, s: Int, t: Int, random: Random? = null): Sequence<IntArray> = sequence {
    if (!parityAllows(w, h, s, t)) return@sequence
    val n = w * h
    val nb = knightNeighbours(w, h)
    val adjacent = Array(n) { a -> BooleanArray(n).also { row -> nb[a].forEach { row[it] = true } } }
    val visited = BooleanArray(n)
    val free = IntArray(n) { nb[it].size } // unvisited neighbours of each square
    val path = IntArray(n)

    fun visit(c: Int) {
        visited[c] = true
        nb[c].forEach { free[it]-- }
    }
    fun unvisit(c: Int) {
        visited[c] = false
        nb[c].forEach { free[it]++ }
    }

    // squares that were next to x but aren't next to y need two free neighbours (in and out); t needs one
    fun stillPossible(x: Int, y: Int) =
        nb[x].all { c ->
            visited[c] || adjacent[y][c] || free[c] >= (if (c == t) 1 else 2)
        }

    suspend fun SequenceScope<IntArray>.extend(len: Int) {
        if (len == n) {
            yield(path.copyOf())
            return
        }
        val x = path[len - 1]
        var moves = nb[x].filter { !visited[it] && (it != t || len == n - 1) }
        // a neighbour with at most one other way out has to be entered now; two of them is a dead end
        val forced = moves.filter { it != t && free[it] <= 1 }
        if (forced.any { free[it] == 0 } || forced.size > 1) return
        if (forced.size == 1) moves = forced
        moves = (if (random != null) moves.shuffled(random) else moves).sortedBy { free[it] }
        for (y in moves) {
            visit(y)
            path[len] = y
            if (stillPossible(x, y)) extend(len + 1)
            unvisit(y)
        }
    }

    visit(s)
    path[0] = s
    extend(1)
}

/*
An endless stream of distinct tours from s to t: a random walk over complete paths from s by
Pósa rotation, yielding each new one that happens to end at t. Not exhaustive. Each rotation
swaps a single edge, and about one rotation in (w * h / 2) lands on t, so neighbours in the
stream differ by a few dozen edges: related, not independent.
Bounded by maxSteps rotations, so a pair of squares with no tour ends the stream instead of hanging it.
*/
fun rotationKnightTours(
    w: Int,
    h: Int,
    s: Int,
    t: Int,
    random: Random = Random.Default,
    maxSteps: Long = 100_000_000,
): Sequence<IntArray> = sequence {
    if (!parityAllows(w, h, s, t)) return@sequence
    val n = w * h
    if (n == 1) {
        yield(intArrayOf(s))
        return@sequence
    }
    val nb = knightNeighbours(w, h)
    val path = IntArray(n)
    val pos = IntArray(n) { -1 }
    var len = 1
    path[0] = s
    pos[s] = 0

    // the end jumps to an earlier square path[i]; reverse everything after i to keep a path
    fun rotate(): Boolean {
        val x = path[len - 1]
        val pivots = nb[x].filter { pos[it] in 0 until len - 2 }
        if (pivots.isEmpty()) return false
        var a = pos[pivots.random(random)] + 1
        var b = len - 1
        while (a < b) {
            val swap = path[a]
            path[a] = path[b]
            path[b] = swap
            pos[path[a]] = a
            pos[path[b]] = b
            a++
            b--
        }
        return true
    }

    var steps = 0L
    while (len < n) {
        if (++steps > maxSteps) return@sequence
        val x = path[len - 1]
        val open = nb[x].filter { pos[it] < 0 }
        if (open.isEmpty()) {
            if (!rotate()) return@sequence
        } else {
            val y = open.minBy { c -> nb[c].count { pos[it] < 0 } + random.nextDouble() }
            path[len] = y
            pos[y] = len
            len++
        }
    }

    val seen = HashSet<Long>()
    while (++steps <= maxSteps) {
        if (path[n - 1] == t && seen.add(path.fold(17L) { h, v -> h * 1_000_003L + v })) yield(path.copyOf())
        if (!rotate()) return@sequence
    }
}

// Independent tours: a fresh randomised search for each one. Not deduplicated, but repeats are
// vanishingly rare from 8×8 up.
fun randomKnightTours(w: Int, h: Int, s: Int, t: Int, random: Random = Random.Default) = generateSequence {
    allKnightTours(w, h, s, t, random).firstOrNull()
}

// ---- draw ----
val cell = 10.0

fun board(w: Int, h: Int) = fline {
    moveTo(0, 0)
    lineTo(w * cell, 0)
    lineTo(w * cell, h * cell)
    lineTo(0, h * cell)
    close()
    filled = true
    stroked = false
    color = vec(1.0, 1.0, 1.0, 0.06)
}

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

// Original straight knight-move polyline; arc data remains available to child views.
fun tourLine(tour: IntArray, w: Int) = fline {
    tour.forEachIndexed { k, i ->
        val x = (i % w + 0.5) * cell
        val y = (i / w + 0.5) * cell
        if (k == 0) moveTo(x, y) else lineTo(x, y)
    }
    thickness = 1.0
    color = vec(0.98, 0.85, 0.3, 1.0)
}

// 8×8, corner (0, 0) to (7, 6). Rows: the exhaustive search in order, the rotation walk, random restarts.
val w = 8
val s = 0
val t = 6 * w + 7
val rows = listOf(
    allKnightTours(w, w, s, t).take(6).toList(),
    rotationKnightTours(w, w, s, t, Random(1)).take(6).toList(),
    randomKnightTours(w, w, s, t, Random(1)).take(6).toList(),
)
// Compute arc geometry once for the existing arc-based child views.
val arcRows = rows.map { row ->
    row.map { tour ->
        val points = tour.map { i -> ((i % w + 0.5) * cell) to ((i / w + 0.5) * cell) }
        points.windowed(3).map { q -> threePointArc(q[0], q[1], q[2]) }
    }
}
// Typed graph properties, inherited by the connected growth view.
prop<List<List<IntArray>>>(knightTourRows) = rows
prop<List<List<List<List<Pair<Double, Double>>>>>>(knightArcRows) = arcRows
prop<Int>(knightBoardWidth) = w
prop<Int>(knightBoardHeight) = w
prop<Double>(knightCellSize) = cell

val gap = 12.0
val tiles = rows.flatMapIndexed { r, row ->
    row.flatMapIndexed { c, tour ->
        listOf(board(w, w), tourLine(tour, w)).map { it + vec(c * (w * cell + gap), r * (w * cell + gap), 0.0) }
    }
}
lines(*tiles.toTypedArray(), fitToSize = true)

// Refresh the connected derived views after publishing all shared properties.
val derivedViewIds = setOf(
    "2685e17b-ada6-4ad7-8de3-b127719b87d2",
    "756af284-5020-45dd-baf4-7b3bc777e681",
    "cc20e577-5142-4a8b-8083-3c737fc5b084",
    "0046694c-4358-43c9-913e-a17d8e55c785",
)
children.filter { it.id in derivedViewIds }.forEach { it.begin() }