/**
 * Uncrossed leaper paths: sequences of leaper moves (a knight is the {1,2} leaper) whose straight
 * segments, drawn between cell centres, never cross or touch except where consecutive moves meet.
 * Finding the longest one on an n×n board is Yarbrough's problem (J. Rec. Math. 1969); the known
 * maxima for the knight are OEIS A003192 (open paths) and A157416 (closed cycles).
 *
 * A [LeaperBoard] is the move graph of one leaper on a w×h board with a [BoardTopology]: on a
 * cylinder or torus a move may wrap round, and two moves cross when their segments cross on the
 * drawn surface (the flat cylinder or torus), which this file tests by translating one segment by
 * the board's periods. [longestUncrossedPath] is a depth-first branch and bound: exhaustive when it
 * finishes inside its budget, and otherwise the best path found, marked as not proved.
 *
 * Pure Kotlin with no Field dependencies; the drawing side is `UncrossedLeaperDrawing.kt`.
 */
package afield.geometry.leaper

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/** How a board's edges join: a plain rectangle, a cylinder (x wraps), or a torus (x and y wrap). */
enum class BoardTopology {
    RECTANGLE,
    CYLINDER,
    TORUS,
    ;

    val wrapsX: Boolean get() = this != RECTANGLE
    val wrapsY: Boolean get() = this == TORUS
}

/**
 * The {a, b} leaper: it moves a squares along one axis and b along the other, in any of the
 * (up to) eight orientations. The knight is {1, 2}.
 */
data class Leaper(val a: Int, val b: Int) {
    init {
        require(a >= 0 && b > 0) { "a leaper needs 0 ≤ a and 0 < b, got {$a, $b}" }
    }

    /** The distinct displacements, eight for a ≠ b with a > 0, four otherwise. */
    val moves: List<Pair<Int, Int>> =
        listOf(a to b, b to a).flatMap { (x, y) ->
            listOf(x to y, -x to y, x to -y, -x to -y)
        }.distinct()

    /** The traditional name if it has one ("knight", "camel", ...), else "{a,b} leaper". */
    val name: String get() = NAMES[min(a, b) to max(a, b)] ?: "{$a,$b} leaper"

    override fun toString() = "$name {$a,$b}"

    companion object {
        val KNIGHT = Leaper(1, 2)
        val CAMEL = Leaper(1, 3)
        val ZEBRA = Leaper(2, 3)
        val GIRAFFE = Leaper(1, 4)
        val ANTELOPE = Leaper(3, 4)
        private val NAMES = mapOf(
            (1 to 2) to "knight",
            (1 to 3) to "camel",
            (2 to 3) to "zebra",
            (1 to 4) to "giraffe",
            (3 to 4) to "antelope",
            (2 to 5) to "gnu",
            (1 to 1) to "fers",
            (0 to 1) to "wazir",
        )
    }
}

/**
 * One move as drawn: from cell [from] to cell [to], with the unwrapped displacement ([dx], [dy])
 * that says which way round a wrapping board it goes. ([x0], [y0]) is [from]'s cell; the segment
 * runs to ([x0] + [dx], [y0] + [dy]), which may lie outside the board when the move wraps.
 */
class LeaperMove(val id: Int, val from: Int, val to: Int, val x0: Int, val y0: Int, val dx: Int, val dy: Int)

/**
 * The move graph of [leaper] on a [w]×[h] board joined by [topology]. Cells are numbered
 * `y * w + x`. [moves] lists every undirected move once; [conflicts] gives, for each move, every
 * other move it may not share a path with because their segments cross or overlap.
 */
class LeaperBoard(val w: Int, val h: Int, val leaper: Leaper = Leaper.KNIGHT, val topology: BoardTopology = BoardTopology.RECTANGLE) {
    init {
        require(w > 0 && h > 0) { "board needs positive size, got $w×$h" }
    }

    val cells: Int get() = w * h
    val moves: List<LeaperMove>

    /** For each cell, the ids of the moves that touch it. */
    val incident: Array<IntArray>

    /** For each move, the moves whose segments cross or overlap it on the drawn surface. */
    val conflicts: Array<IntArray>

    init {
        val list = ArrayList<LeaperMove>()
        val seen = HashSet<List<Int>>()
        for (c in 0 until w * h) {
            val x = c % w
            val y = c / w
            for ((dx, dy) in leaper.moves) {
                var tx = x + dx
                var ty = y + dy
                if (topology.wrapsX) tx = Math.floorMod(tx, w) else if (tx !in 0 until w) continue
                if (topology.wrapsY) ty = Math.floorMod(ty, h) else if (ty !in 0 until h) continue
                val t = ty * w + tx
                if (t == c) continue
                // The same drawn segment seen from its other end is (t, -dx, -dy); keep one copy.
                val key = if (c < t || (c == t)) listOf(c, t, dx, dy) else listOf(t, c, -dx, -dy)
                if (seen.add(key)) list += LeaperMove(list.size, c, t, x, y, dx, dy)
            }
        }
        moves = list
        incident = Array(w * h) { c -> list.filter { it.from == c || it.to == c }.map { it.id }.toIntArray() }
        conflicts = Array(list.size) { i -> list.indices.filter { j -> j != i && movesConflict(list[i], list[j]) }.toIntArray() }
    }

    /** The cell at the other end of [move] from [cell]. */
    fun other(move: Int, cell: Int): Int = moves[move].let { if (it.from == cell) it.to else it.from }

    /** Whether the segments of [p] and [q] cross or overlap anywhere but a shared end, on the drawn surface. */
    fun movesConflict(p: LeaperMove, q: LeaperMove): Boolean {
        val reachX = abs(p.dx) + abs(q.dx) + w
        val reachY = abs(p.dy) + abs(q.dy) + h
        val kx = if (topology.wrapsX) reachX / w + 1 else 0
        val ky = if (topology.wrapsY) reachY / h + 1 else 0
        for (i in -kx..kx) for (j in -ky..ky) {
            val ox = q.x0 + i * w
            val oy = q.y0 + j * h
            if (segmentsConflict(p.x0, p.y0, p.x0 + p.dx, p.y0 + p.dy, ox, oy, ox + q.dx, oy + q.dy)) return true
        }
        return false
    }

    /** "8×8 knight on a torus". */
    override fun toString() = "$w×$h ${leaper.name}${if (topology == BoardTopology.RECTANGLE) "" else " on a ${topology.name.lowercase()}"}"

    /** The cell's (x, y). */
    fun xy(cell: Int): Pair<Int, Int> = (cell % w) to (cell / w)

    /**
     * Each move of [path] as drawn on the board: a list of segments in cell coordinates, already cut
     * where a wrapping move leaves through one edge and comes back through the opposite one. The
     * board's drawn extent is [-0.5, w - 0.5] × [-0.5, h - 0.5].
     */
    fun drawnPieces(path: UncrossedPath): List<List<DoubleArray>> = path.moves.indices.map { k ->
        val m = moves[path.moves[k]]
        val forward = m.from == path.cells[k]
        val (sx, sy) = xy(path.cells[k])
        val dx = if (forward) m.dx else -m.dx
        val dy = if (forward) m.dy else -m.dy
        wrapSegment(sx.toDouble(), sy.toDouble(), (sx + dx).toDouble(), (sy + dy).toDouble())
    }

    /** Cut the unwrapped segment (x0, y0)→(x1, y1) at the board's glued edges and fold each piece back onto the board. */
    fun wrapSegment(x0: Double, y0: Double, x1: Double, y1: Double): List<DoubleArray> {
        val ts = sortedSetOf(0.0, 1.0)
        fun cuts(a: Double, b: Double, period: Int) {
            if (a == b) return
            val lo = min(a, b)
            val hi = max(a, b)
            var k = Math.floorDiv(kotlin.math.floor(lo + 0.5).toInt() - 1, period) * period
            while (k - 0.5 <= hi + period) {
                val s = k - 0.5
                if (s > lo && s < hi) ts += (s - a) / (b - a)
                k += period
            }
        }
        if (topology.wrapsX) cuts(x0, x1, w)
        if (topology.wrapsY) cuts(y0, y1, h)
        val t = ts.toList()
        return (0 until t.size - 1).map { i ->
            val ax = x0 + (x1 - x0) * t[i]
            val ay = y0 + (y1 - y0) * t[i]
            val bx = x0 + (x1 - x0) * t[i + 1]
            val by = y0 + (y1 - y0) * t[i + 1]
            val mx = (ax + bx) / 2
            val my = (ay + by) / 2
            // Which copy of the board the piece's midpoint lies in; floor, so pieces left of or above the board shift too.
            val sx = if (topology.wrapsX) kotlin.math.floor((mx + 0.5) / w) * w else 0.0
            val sy = if (topology.wrapsY) kotlin.math.floor((my + 0.5) / h) * h else 0.0
            doubleArrayOf(ax - sx, ay - sy, bx - sx, by - sy)
        }
    }

    /**
     * Whether visiting [cells] in order is a legal uncrossed path (or cycle when [closed]): every
     * step a leaper move, no cell twice, no two segments crossing. Returns null when legal and the
     * first problem found otherwise. Where two moves join the same pair of cells (small wrapping
     * boards), [moveIds] picks which; by default the first that keeps the path legal is taken.
     */
    fun check(cells: IntArray, closed: Boolean = false, moveIds: IntArray? = null): String? = checkPath(cells, closed, moveIds).second

    /** [check], but returning the path it read alongside the verdict. */
    fun checkPath(cells: IntArray, closed: Boolean = false, moveIds: IntArray? = null): Pair<UncrossedPath?, String?> {
        if (cells.isEmpty()) return null to "empty path"
        if (cells.any { it !in 0 until this.cells }) return null to "cell off the board"
        if (cells.toSet().size != cells.size) {
            val dup = cells.groupBy { it }.entries.first { it.value.size > 1 }.key
            return null to "visits ${cellName(dup)} twice"
        }
        val steps = if (closed) cells.size else cells.size - 1
        val used = ArrayList<Int>()
        val blocked = IntArray(moves.size)
        for (k in 0 until steps) {
            val a = cells[k]
            val b = cells[(k + 1) % cells.size]
            val candidates = moveIds?.let { intArrayOf(it[k]) } ?: incident[a].filter { other(it, a) == b }.toIntArray()
            if (candidates.isEmpty() || candidates.any { other(it, a) != b }) {
                return null to "${cellName(a)}→${cellName(b)} is not a ${leaper.name} move"
            }
            val ok = candidates.firstOrNull { blocked[it] == 0 }
                ?: return null to "${cellName(a)}→${cellName(b)} crosses move ${used.indexOfFirst { u -> candidates[0] in conflicts[u] } + 1}"
            used += ok
            for (c in conflicts[ok]) blocked[c]++
        }
        return UncrossedPath(this, cells.copyOf(), used.toIntArray(), closed) to null
    }

    /** "c3": file letter and rank number, a1 at the board's (0, 0). */
    fun cellName(cell: Int): String {
        val (x, y) = xy(cell)
        return (if (x < 26) ('a' + x).toString() else "x$x:") + (y + 1)
    }

    /** The board's symmetries as cell permutations that also preserve the move graph's drawing. */
    internal fun symmetries(): List<IntArray> {
        val maps = ArrayList<(Int, Int) -> Pair<Int, Int>>()
        maps += { x, y -> x to y }
        maps += { x, y -> (w - 1 - x) to y }
        maps += { x, y -> x to (h - 1 - y) }
        maps += { x, y -> (w - 1 - x) to (h - 1 - y) }
        if (w == h) {
            maps += { x, y -> y to x }
            maps += { x, y -> (w - 1 - y) to x }
            maps += { x, y -> y to (w - 1 - x) }
            maps += { x, y -> (w - 1 - y) to (w - 1 - x) }
        }
        val base = maps.map { f -> IntArray(cells) { c -> f(c % w, c / w).let { (x, y) -> y * w + x } } }
        if (topology == BoardTopology.RECTANGLE) return base
        // Translations along a wrapping axis are symmetries too.
        val tx = if (topology.wrapsX) w else 1
        val ty = if (topology.wrapsY) h else 1
        return base.flatMap { p ->
            (0 until tx).flatMap { i ->
                (0 until ty).map { j ->
                    IntArray(cells) { c ->
                        val q = p[c]
                        val x = (q % w + i) % w
                        val y = (q / w + j) % h
                        y * w + x
                    }
                }
            }
        }
    }

    /** One start cell per orbit of the board's symmetry group: every path can be turned to begin at one. */
    internal fun startCells(): IntArray {
        val sym = symmetries()
        return (0 until cells).filter { c -> sym.all { it[c] >= c } }.toIntArray()
    }
}

/** Whether segment p0→p1 and q0→q1 share any point other than an endpoint they have in common. */
internal fun segmentsConflict(px0: Int, py0: Int, px1: Int, py1: Int, qx0: Int, qy0: Int, qx1: Int, qy1: Int): Boolean {
    if (max(px0, px1) < min(qx0, qx1) || max(qx0, qx1) < min(px0, px1)) return false
    if (max(py0, py1) < min(qy0, qy1) || max(qy0, qy1) < min(py0, py1)) return false
    fun orient(ax: Int, ay: Int, bx: Int, by: Int, cx: Int, cy: Int): Int =
        Integer.signum((bx - ax) * (cy - ay) - (by - ay) * (cx - ax))
    val d1 = orient(px0, py0, px1, py1, qx0, qy0)
    val d2 = orient(px0, py0, px1, py1, qx1, qy1)
    val d3 = orient(qx0, qy0, qx1, qy1, px0, py0)
    val d4 = orient(qx0, qy0, qx1, qy1, px1, py1)
    if (d1 == 0 && d2 == 0) {
        // Collinear: they conflict when they overlap in more than one point.
        val horizontal = px0 != px1
        val a0 = if (horizontal) min(px0, px1) else min(py0, py1)
        val a1 = if (horizontal) max(px0, px1) else max(py0, py1)
        val b0 = if (horizontal) min(qx0, qx1) else min(qy0, qy1)
        val b1 = if (horizontal) max(qx0, qx1) else max(qy0, qy1)
        return min(a1, b1) > max(a0, b0)
    }
    val shared = (px0 == qx0 && py0 == qy0) || (px0 == qx1 && py0 == qy1) || (px1 == qx0 && py1 == qy0) || (px1 == qx1 && py1 == qy1)
    if (shared) {
        // Two segments meeting at an end are fine unless one runs along the other (handled above).
        return false
    }
    if (d1 != d2 && d3 != d4) return true
    // An endpoint lying on the other segment's interior.
    return (d1 == 0) || (d2 == 0) || (d3 == 0) || (d4 == 0)
}

/** A legal uncrossed path: [cells] in order and the [moves] between them (one more move when [closed]). */
class UncrossedPath(val board: LeaperBoard, val cells: IntArray, val moves: IntArray, val closed: Boolean = false) {
    /** The number of moves, the quantity A003192 and A157416 count. */
    val length: Int get() = moves.size

    /** Cells in "a1 b3 c5" notation. */
    fun cellNames(): String = cells.joinToString(" ") { board.cellName(it) }

    /** Total drawn length in cell units: length × √(a² + b²). */
    val drawnLength: Double get() = length * kotlin.math.sqrt((board.leaper.a * board.leaper.a + board.leaper.b * board.leaper.b).toDouble())

    /**
     * The same key for every path that is this one turned, reflected, (on a cylinder or torus)
     * shifted round, or walked the other way, and for a cycle started at any of its squares: two
     * paths have one shape exactly when their keys are equal.
     */
    fun shapeKey(): List<Int> {
        var best: List<Int>? = null
        for (sym in board.symmetries()) {
            val mapped = cells.map { sym[it] }
            val orders = listOf(mapped, mapped.reversed())
            for (o in orders) {
                val starts = if (closed) o.indices else 0..0
                for (s in starts) {
                    val candidate = List(o.size) { o[(it + s) % o.size] }
                    if (best == null || compareLists(candidate, best) < 0) best = candidate
                }
            }
        }
        return best ?: emptyList()
    }

    private fun compareLists(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until minOf(a.size, b.size)) if (a[i] != b[i]) return a[i].compareTo(b[i])
        return a.size.compareTo(b.size)
    }

    override fun toString() = "${if (closed) "cycle" else "path"} of $length moves on $board: ${cellNames()}"
}

/**
 * What a search found: the [best] path, whether the search was [exhaustive] (so [best] is proved
 * longest), how many search [nodes] it expanded, and how long it took.
 */
class UncrossedSearchResult(
    val board: LeaperBoard,
    val best: UncrossedPath?,
    val exhaustive: Boolean,
    val nodes: Long,
    val millis: Long,
    val closed: Boolean,
) {
    val length: Int get() = best?.length ?: 0

    override fun toString() =
        "$board ${if (closed) "cycle" else "path"}: $length moves${if (exhaustive) " (proved longest)" else " (best found, not proved)"}, " +
            "$nodes nodes, $millis ms"
}

/**
 * Search for the longest uncrossed path ([closed] = false) or cycle ([closed] = true) on this board.
 *
 * Depth-first from one start cell per symmetry class, extending only by moves that touch an
 * unvisited cell and cross nothing used so far. A branch is cut when even visiting every cell
 * still reachable from the path's end through uncrossed moves could not beat the best path found;
 * that bound also drops all but one of the reachable cells with a single free move, since a path
 * can end at only one of them. With [nodeBudget] expansions spent before the search space is, the
 * result is the best path found and [UncrossedSearchResult.exhaustive] is false. [random] shuffles
 * move order (null keeps it deterministic); [atLeast] seeds the bound, so a search with
 * `atLeast = k` only looks for paths longer than k. With [stopAt] the search ends at the first path
 * of that many moves, and is reported as exhaustive only if it ended by running out of branches.
 */
fun LeaperBoard.longestUncrossedPath(
    closed: Boolean = false,
    nodeBudget: Long = 2_000_000_000L,
    random: Random? = null,
    atLeast: Int = 0,
    stopAt: Int = Int.MAX_VALUE,
    from: UncrossedPath? = null,
): UncrossedSearchResult {
    val started = System.currentTimeMillis()
    val n = cells
    val visited = BooleanArray(n)
    val blocked = IntArray(moves.size)
    val pathCells = IntArray(n + 1)
    val pathMoves = IntArray(n + 1)
    var bestLength = atLeast
    var bestCells: IntArray? = null
    var bestMoves: IntArray? = null
    var nodes = 0L
    var outOfBudget = false
    var reachedTarget = false
    val queue = IntArray(n)
    val mark = IntArray(n)
    var stamp = 0
    var minCell = 0 // cycles: every cell after the first must be ≥ the first, so each cycle is found from its least cell

    fun use(m: Int) {
        for (c in conflicts[m]) blocked[c]++
    }

    fun unuse(m: Int) {
        for (c in conflicts[m]) blocked[c]--
    }

    // Cells still reachable from `end` through unvisited cells and unblocked moves, less all but one dead end.
    fun reachBound(end: Int, start: Int): Int {
        stamp++
        var head = 0
        var tail = 0
        queue[tail++] = end
        mark[end] = stamp
        var deadEnds = 0
        while (head < tail) {
            val c = queue[head++]
            var degree = 0
            for (m in incident[c]) {
                if (blocked[m] != 0) continue
                val o = other(m, c)
                if (o == end) {
                    degree++
                    continue
                }
                if (visited[o] || o < minCell) continue
                degree++
                if (mark[o] != stamp) {
                    mark[o] = stamp
                    queue[tail++] = o
                }
            }
            if (c != end && degree <= 1) deadEnds++
        }
        val reach = tail - 1
        return if (closed) reach else reach - max(0, deadEnds - 1)
    }

    fun record(len: Int, closingMove: Int) {
        val total = if (closingMove >= 0) len else len - 1
        if (total > bestLength) {
            bestLength = total
            bestCells = pathCells.copyOf(len)
            bestMoves = (if (closingMove >= 0) pathMoves.copyOf(len - 1) + closingMove else pathMoves.copyOf(len - 1))
            if (total >= stopAt) {
                reachedTarget = true
                outOfBudget = true
            }
        }
    }

    fun extend(len: Int, start: Int) {
        if (outOfBudget) return
        if (++nodes > nodeBudget) {
            outOfBudget = true
            return
        }
        val end = pathCells[len - 1]
        if (closed) {
            if (len >= 3) for (m in incident[end]) if (blocked[m] == 0 && other(m, end) == start && m != pathMoves[0]) {
                record(len, m)
                break
            }
            // A cycle through `len` cells has `len` moves; the bound counts the cells that could still join.
            if (len + reachBound(end, start) <= bestLength) return
        } else {
            record(len, -1)
            if (len - 1 + reachBound(end, start) <= bestLength) return
        }
        val options = incident[end].filter { m -> blocked[m] == 0 && other(m, end).let { !visited[it] && it >= minCell } }
        val ordered = if (random != null) options.shuffled(random) else options
        for (m in ordered) {
            if (blocked[m] != 0) continue
            val o = other(m, end)
            visited[o] = true
            pathCells[len] = o
            pathMoves[len - 1] = m
            use(m)
            extend(len + 1, start)
            unuse(m)
            visited[o] = false
            if (outOfBudget) return
        }
    }

    if (from != null) {
        // Grow only paths that begin with `from`: lay its moves down, then search on from its end.
        minCell = 0
        from.cells.forEachIndexed { k, c ->
            visited[c] = true
            pathCells[k] = c
        }
        for (k in 0 until from.cells.size - 1) {
            pathMoves[k] = from.moves[k]
            use(from.moves[k])
        }
        extend(from.cells.size, from.cells[0])
        val path = bestCells?.let { UncrossedPath(this, it, bestMoves!!, closed) }
        return UncrossedSearchResult(this, path, !outOfBudget && !reachedTarget, nodes, System.currentTimeMillis() - started, closed)
    }
    val starts = if (closed) (0 until n).toList().toIntArray() else startCells()
    val orderedStarts = if (random != null) starts.toList().shuffled(random).toIntArray() else starts
    for (s in orderedStarts) {
        if (closed && n - s <= bestLength) continue
        minCell = if (closed) s else 0
        visited[s] = true
        pathCells[0] = s
        extend(1, s)
        visited[s] = false
        if (outOfBudget) break
    }
    val path = bestCells?.let { UncrossedPath(this, it, bestMoves!!, closed) }
    return UncrossedSearchResult(this, path, !outOfBudget && !reachedTarget, nodes, System.currentTimeMillis() - started, closed)
}

/**
 * Ruin and recreate, for boards where the exhaustive search cannot finish. Start from a short
 * bounded search, then for each of [rounds]: turn the best path round (or, for a cycle, start it
 * at another square), cut a random tail off it, and let a search of [nodesPerRound] steps grow a
 * new tail. A regrown path at least as long replaces the best, so the walk can drift across paths
 * of equal length. Reproducible for a fixed [random]; never claims to be exhaustive.
 */
fun LeaperBoard.longUncrossedPath(
    closed: Boolean = false,
    rounds: Int = 1000,
    nodesPerRound: Long = 4_000,
    random: Random = Random(1),
): UncrossedSearchResult {
    val started = System.currentTimeMillis()
    // A first path to work on: short randomised searches until one finds anything (or proves there is nothing).
    var nodes = 0L
    var start: UncrossedPath? = null
    var round = 0
    while (start == null && round < rounds) {
        val first = longestUncrossedPath(closed, nodesPerRound, random)
        nodes += first.nodes
        round++
        if (first.exhaustive) return UncrossedSearchResult(this, first.best, true, nodes, System.currentTimeMillis() - started, closed)
        start = first.best
    }
    var best = start ?: return UncrossedSearchResult(this, null, false, nodes, System.currentTimeMillis() - started, closed)
    repeat(rounds - round) {
        val k = best.cells.size
        val turned = if (closed) {
            val r = random.nextInt(k)
            val cells = IntArray(k) { best.cells[(it + r) % k] }
            val moves = IntArray(k) { best.moves[(it + r) % k] }
            UncrossedPath(this, cells, moves, true)
        } else if (random.nextBoolean()) {
            UncrossedPath(this, best.cells.reversedArray(), best.moves.reversedArray(), false)
        } else best
        val keep = 1 + random.nextInt(maxOf(1, k - 1))
        val prefix = UncrossedPath(this, turned.cells.copyOf(keep), turned.moves.copyOf(keep - 1), false)
        val r = longestUncrossedPath(closed, nodesPerRound, random, atLeast = best.length - 1, from = prefix)
        nodes += r.nodes
        val found = r.best
        if (found != null && found.length >= best.length) best = found
    }
    return UncrossedSearchResult(this, best, false, nodes, System.currentTimeMillis() - started, closed)
}

/**
 * The longest path this board allows within a budget: first the exhaustive search with
 * [nodeBudget] steps, which proves its answer when it finishes, and if it does not, [rounds] of
 * [longUncrossedPath] from [random], keeping whichever path is longer. The result is
 * [UncrossedSearchResult.exhaustive] only when the first search finished.
 */
fun LeaperBoard.bestUncrossedPath(
    closed: Boolean = false,
    nodeBudget: Long = 1_000_000,
    rounds: Int = 1000,
    nodesPerRound: Long = 4_000,
    random: Random = Random(1),
): UncrossedSearchResult {
    val proof = longestUncrossedPath(closed, nodeBudget)
    if (proof.exhaustive) return proof
    val walk = longUncrossedPath(closed, rounds, nodesPerRound, random)
    val best = if (walk.length > proof.length) walk.best else proof.best
    return UncrossedSearchResult(this, best, false, proof.nodes + walk.nodes, proof.millis + walk.millis, closed)
}

/**
 * Snap a drawn polyline ([xs], [ys], in the coordinates `boardLines` used with the same [cell],
 * [x], [y]) to the sequence of cell centres it passes over: a sample within [radius] × [cell] of a
 * centre counts as a visit, and repeated visits in a row collapse to one. Feed the result to
 * [LeaperBoard.check]. A freehand knight move passes no nearer than 0.45 cells to any other centre,
 * so the default radius of 0.3 does not pick up squares a stroke merely flies over.
 */
fun LeaperBoard.readDrawnPath(xs: DoubleArray, ys: DoubleArray, cell: Double = 10.0, x: Double = 0.0, y: Double = 0.0, radius: Double = 0.3): IntArray {
    val out = ArrayList<Int>()
    for (k in xs.indices) {
        val fx = (xs[k] - x) / cell - 0.5
        val fy = (ys[k] - y) / cell - 0.5
        val i = fx.roundToInt()
        val j = fy.roundToInt()
        if (i !in 0 until w || j !in 0 until h) continue
        if (hypot(fx - i, fy - j) > radius) continue
        val c = j * w + i
        if (out.lastOrNull() != c) out += c
    }
    return out.toIntArray()
}

/** OEIS A003192: the longest uncrossed knight's path on an n×n board, n = 1..9 (Knuth proved 47 for n = 9 in 2010). */
val UNCROSSED_KNIGHT_PATH_MAXIMA: List<Int> = listOf(0, 0, 2, 5, 10, 17, 24, 35, 47)

/** OEIS A157416: the longest uncrossed knight's cycle on an n×n board, n = 1..10. */
val UNCROSSED_KNIGHT_CYCLE_MAXIMA: List<Int> = listOf(0, 0, 0, 4, 8, 12, 24, 32, 42, 54)
