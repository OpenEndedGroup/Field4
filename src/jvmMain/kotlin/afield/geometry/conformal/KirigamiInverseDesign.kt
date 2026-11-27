@file:afield.runtime.completion.CompletionCategory("Math")

package afield.geometry.conformal

import afield.math.autodiff.*
import afield.math.optimization.LeastSquaresOptions
import afield.math.optimization.leastSquaresAD
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Inverse design of a quad kirigami sheet: which cut pattern of a square sheet opens into a target
 * outline. After Choi, Dudte & Mahadevan, "Programming shape using kirigami tessellations", Nature
 * Materials 18, 999–1004 (2019), and the constraint set of Gary Choi's inverse-kirigami-design code
 * (Apache-2.0), re-derived in Kotlin over Field's Levenberg–Marquardt ([leastSquaresAD]).
 *
 * ```kotlin
 * val design = KirigamiInverseDesign(n = 8).solveCircle()
 * design.opened   // every point of the opened sheet, x0 y0 x1 y1 ...
 * design.closed   // the same points in the closed, square cut pattern
 * ```
 *
 * The unknowns are the corners of every tile in the *opened* sheet; corners joined by a hinge are one
 * point. Residuals: equal edge lengths either side of every cut, angle sums of `2π`/`π`/`π/2` at the
 * closed sheet's interior/edge/corner nodes, a square closed outline, the outermost points on the
 * target, no cut closing below `minOpening`, and no tile corner outside `[minCorner, π − minCorner]`.
 * The `kirigami_choi` transcript's inverse-design box calls this class.
 *
 * @param n tiles per side of the sheet.
 * @param minOpening the smallest angle, in radians, any cut may close to in the opened sheet.
 * @param minCorner the sharpest tile corner allowed, in radians; `π − minCorner` is the flattest.
 */
class KirigamiInverseDesign(val n: Int = 8, val minOpening: Double = 0.15, val minCorner: Double = 0.4) {

    /**
     * One cut: tiles [tileA] and [tileB] share the edge pair whose hinge is point [hinge]; [endA] and
     * [endB] are the far ends of the two sides of the cut (points), the same point once the sheet closes.
     */
    data class Cut(val tileA: Int, val tileB: Int, val hinge: Int, val endA: Int, val endB: Int)

    /** Tile `(i, j)` as an index. */
    fun tile(i: Int, j: Int) = i + n * j

    private fun cornerNodes(i: Int, j: Int) = listOf(i to j, i + 1 to j, i + 1 to j + 1, i to j + 1)

    /** Corner slot `4 * tile + corner` to point index. */
    val point: IntArray

    /** How many distinct points (unknowns / 2) the sheet has. */
    val pointCount: Int

    /** Each tile's four points, counter-clockwise from bottom-left. */
    val tiles: List<IntArray>

    /** The standard rotating-squares sheet opened flat, centred on the origin. */
    val standard: DoubleArray

    /** Half-width of [standard]; the target is in these units. */
    val half: Double

    /** Points of [standard] on its outer square, the ones constrained to the target. */
    val boundary: List<Int>

    private val edgePairs = mutableListOf<IntArray>()
    private val edgeTiles = mutableListOf<Pair<Int, Int>>()

    /** Every cut, `2n(n − 1)` of them, ordered so each opens positively in [standard]. */
    val cuts: List<Cut>

    private val rings = mutableListOf<Pair<List<IntArray>, Double>>()
    private val bottomEdges: List<Pair<Int, Int>>
    private val rightEdges: List<Pair<Int, Int>>

    init {
        val parent = IntArray(4 * n * n) { it }
        fun root(a: Int): Int = if (parent[a] == a) a else root(parent[a]).also { parent[a] = it }
        fun join(a: Int, b: Int) {
            parent[root(a)] = root(b)
        }
        // Choi's square unit cell: at node (a, b) the meeting corners pair left-right when a + b is even.
        for (a in 1 until n) for (b in 0..n) for (j in listOf(b - 1, b)) {
            if (j < 0 || j >= n || (a + b) % 2 != 0) continue
            join(4 * tile(a - 1, j) + cornerNodes(a - 1, j).indexOf(a to b), 4 * tile(a, j) + cornerNodes(a, j).indexOf(a to b))
        }
        for (a in 0..n) for (b in 1 until n) for (i in listOf(a - 1, a)) {
            if (i < 0 || i >= n || (a + b) % 2 != 1) continue
            join(4 * tile(i, b - 1) + cornerNodes(i, b - 1).indexOf(a to b), 4 * tile(i, b) + cornerNodes(i, b).indexOf(a to b))
        }
        val ids = (0 until 4 * n * n).map { root(it) }.distinct().withIndex().associate { (k, r) -> r to k }
        point = IntArray(4 * n * n) { ids.getValue(root(it)) }
        pointCount = ids.size
        tiles = (0 until n * n).map { k -> IntArray(4) { point[4 * k + it] } }

        standard = DoubleArray(2 * pointCount)
        for (i in 0 until n) for (j in 0 until n) {
            val turn = if ((i + j) % 2 == 0) -PI / 4 else PI / 4
            cornerNodes(i, j).forEachIndexed { c, (a, b) ->
                val dx = a - (i + 0.5)
                val dy = b - (j + 0.5)
                val p = point[4 * tile(i, j) + c]
                standard[2 * p] = kotlin.math.sqrt(2.0) * (i + 0.5 - n / 2.0) + dx * cos(turn) - dy * sin(turn)
                standard[2 * p + 1] = kotlin.math.sqrt(2.0) * (j + 0.5 - n / 2.0) + dx * sin(turn) + dy * cos(turn)
            }
        }
        half = standard.maxOf { abs(it) }
        boundary = (0 until pointCount).filter { max(abs(standard[2 * it]), abs(standard[2 * it + 1])) > half - 1e-6 }

        for (i in 0 until n) for (j in 0 until n) {
            val k = 4 * tile(i, j)
            if (i + 1 < n) {
                val r = 4 * tile(i + 1, j)
                edgePairs += intArrayOf(point[k + 1], point[k + 2], point[r + 0], point[r + 3])
                edgeTiles += tile(i, j) to tile(i + 1, j)
            }
            if (j + 1 < n) {
                val r = 4 * tile(i, j + 1)
                edgePairs += intArrayOf(point[k + 3], point[k + 2], point[r + 0], point[r + 1])
                edgeTiles += tile(i, j) to tile(i, j + 1)
            }
        }
        cuts = edgePairs.mapIndexed { e, (p, q, r, s) ->
            val (h, a, b) = if (p == r) Triple(p, q, s) else Triple(q, p, r)
            val (ta, tb) = edgeTiles[e]
            val cross =
                (standard[2 * a] - standard[2 * h]) * (standard[2 * b + 1] - standard[2 * h + 1]) -
                    (standard[2 * a + 1] - standard[2 * h + 1]) * (standard[2 * b] - standard[2 * h])
            if (cross > 0) Cut(tb, ta, h, b, a) else Cut(ta, tb, h, a, b)
        }
        for (a in 0..n) for (b in 0..n) {
            val corners = mutableListOf<IntArray>()
            for (i in a - 1..a) for (j in b - 1..b) {
                if (i < 0 || j < 0 || i >= n || j >= n) continue
                val c = cornerNodes(i, j).indexOf(a to b)
                val k = 4 * tile(i, j)
                corners += intArrayOf(point[k + (c + 3) % 4], point[k + c], point[k + (c + 1) % 4])
            }
            rings += corners to corners.size * PI / 2
        }
        bottomEdges = (0 until n).map { tiles[tile(it, 0)][0] to tiles[tile(it, 0)][1] }
        rightEdges = (0 until n).map { tiles[tile(n - 1, it)][1] to tiles[tile(n - 1, it)][2] }
    }

    /** Choi's `shapes/circle.m`: four parabolic arcs through three points each, within 1% of round. */
    fun circleOutline(): List<DoubleArray> {
        val quarter = listOf(doubleArrayOf(kotlin.math.sqrt(0.5), -kotlin.math.sqrt(0.5)), doubleArrayOf(1.0, 0.0), doubleArrayOf(kotlin.math.sqrt(0.5), kotlin.math.sqrt(0.5)))
        return (0 until 4).flatMap { q ->
            val a = q * PI / 2
            val pts = quarter.map { (x, y) -> doubleArrayOf(half * (x * cos(a) - y * sin(a)), half * (x * sin(a) + y * cos(a))) }
            val m = pts.size - 1
            (0 until 60).map { step ->
                val t = step / 60.0 * m
                val w = (0..m).map { i -> (0..m).filter { it != i }.fold(1.0) { acc, j -> acc * (t - j) / (i - j) } }
                doubleArrayOf((0..m).sumOf { w[it] * pts[it][0] }, (0..m).sumOf { w[it] * pts[it][1] })
            }
        }
    }

    /** Signed corner angle at [at] from [next] round to [prev]. */
    fun cornerAngle(x: List<AD>, prev: Int, at: Int, next: Int): AD {
        val ux = x[2 * next] - x[2 * at]
        val uy = x[2 * next + 1] - x[2 * at + 1]
        val vx = x[2 * prev] - x[2 * at]
        val vy = x[2 * prev + 1] - x[2 * at + 1]
        return atan2(ux * vy - uy * vx, ux * vx + uy * vy)
    }

    /** Length of the segment from point [p] to point [q]. */
    fun edgeLength(x: List<AD>, p: Int, q: Int) = sqrt((x[2 * q] - x[2 * p]).sq() + (x[2 * q + 1] - x[2 * p + 1]).sq())

    /** The solved design, its closed cut pattern, and how well it met its constraints. */
    class Design(
        /** The problem this solves. */
        val problem: KirigamiInverseDesign,
        /** Opened points, `x0 y0 x1 y1 ...`. */
        val opened: DoubleArray,
        /** Closed points, levelled and centred. */
        val closed: DoubleArray,
        /** The target outline. */
        val outline: List<DoubleArray>,
        /** The largest absolute residual. */
        val residual: Double,
        /** Levenberg–Marquardt iterations. */
        val iterations: Int,
    ) {
        /** Each tile's corners at [state] ([opened] or [closed]), counter-clockwise. */
        fun tileCorners(state: DoubleArray): List<List<DoubleArray>> =
            problem.tiles.map { t -> t.map { p -> doubleArrayOf(state[2 * p], state[2 * p + 1]) } }
    }

    private class Polar(outline: List<DoubleArray>) {
        val polar = outline.map { kotlin.math.atan2(it[1], it[0]) to hypot(it[0], it[1]) }.sortedBy { it.first }.let { p ->
            listOf(p.last().first - 2 * PI to p.last().second) + p + listOf(p.first().first + 2 * PI to p.first().second)
        }

        fun radius(theta: AD): AD {
            val i = polar.indexOfLast { it.first <= theta.value }.coerceIn(0, polar.size - 2)
            val (t0, r0) = polar[i]
            val (t1, r1) = polar[i + 1]
            return (theta - t0) * ((r1 - r0) / (t1 - t0)) + r0
        }
    }

    /** All residuals for target outline [outline] (as a polar lookup), at points [x]. */
    private fun residuals(x: List<AD>, target: Polar): List<AD> {
        val out = mutableListOf<AD>()
        for (e in edgePairs) out += edgeLength(x, e[0], e[1]) - edgeLength(x, e[2], e[3])
        for ((corners, total) in rings) out += sum(corners.map { cornerAngle(x, it[0], it[1], it[2]) }) - total
        out += sum(bottomEdges.map { (p, q) -> edgeLength(x, p, q) }) - sum(rightEdges.map { (p, q) -> edgeLength(x, p, q) })
        for (p in boundary) out += sqrt(x[2 * p].sq() + x[2 * p + 1].sq()) - target.radius(atan2(x[2 * p + 1], x[2 * p]))
        for (c in cuts) out += relu(minOpening - cornerAngle(x, c.endA, c.hinge, c.endB))
        for (t in tiles) for (c in 0 until 4) {
            val angle = cornerAngle(x, t[(c + 3) % 4], t[c], t[(c + 1) % 4])
            out += relu(minCorner - angle) + relu(angle - (PI - minCorner))
        }
        return out
    }

    /** Solves for a sheet that opens onto [outline] (default: [circleOutline]); about 35 s at `n = 8`. */
    fun solve(outline: List<DoubleArray> = circleOutline(), maxIterations: Int = 200): Design {
        val target = Polar(outline)
        val x0 = DoubleArray(2 * pointCount)
        val tape = ADTape()
        for (p in 0 until pointCount) {
            // The standard sheet squashed onto the target (the role of Choi's conformal map).
            val u = standard[2 * p] / half
            val v = standard[2 * p + 1] / half
            val sx = u * kotlin.math.sqrt(1 - v * v / 2)
            val sy = v * kotlin.math.sqrt(1 - u * u / 2)
            val r = target.radius(tape.variable(kotlin.math.atan2(sy, sx))).value / half
            x0[2 * p] = half * r * sx
            x0[2 * p + 1] = half * r * sy
        }
        val fit = leastSquaresAD(x0, LeastSquaresOptions(maxIterations = maxIterations)) { residuals(it, target) }
        val worst = residuals(ADTape().variables(fit.x), target).maxOf { abs(it.value) }
        return Design(this, fit.x, closedSheet(fit.x), outline, worst, fit.iterations)
    }

    /** Glues the rigid tiles of [solved] back together, cut side to cut side: the closed cut pattern. */
    fun closedSheet(solved: DoubleArray): DoubleArray {
        val pose = DoubleArray(3 * n * n)
        fun corner(k: Int, c: Int) = doubleArrayOf(solved[2 * tiles[k][c]], solved[2 * tiles[k][c] + 1])
        fun moved(k: Int, c: Int): DoubleArray {
            val p = corner(k, c)
            val t = pose[3 * k]
            return doubleArrayOf(cos(t) * p[0] - sin(t) * p[1] + pose[3 * k + 1], sin(t) * p[0] + cos(t) * p[1] + pose[3 * k + 2])
        }
        fun glue(a: Int, ca0: Int, ca1: Int, b: Int, cb0: Int, cb1: Int) {
            val p0 = moved(a, ca0)
            val p1 = moved(a, ca1)
            val q0 = corner(b, cb0)
            val q1 = corner(b, cb1)
            pose[3 * b] = kotlin.math.atan2(p1[1] - p0[1], p1[0] - p0[0]) - kotlin.math.atan2(q1[1] - q0[1], q1[0] - q0[0])
            pose[3 * b + 1] = 0.0
            pose[3 * b + 2] = 0.0
            val r = moved(b, cb0)
            pose[3 * b + 1] = p0[0] - r[0]
            pose[3 * b + 2] = p0[1] - r[1]
        }
        for (i in 1 until n) glue(tile(i - 1, 0), 1, 2, tile(i, 0), 0, 3)
        for (i in 0 until n) for (j in 1 until n) glue(tile(i, j - 1), 3, 2, tile(i, j), 0, 1)
        val sum = DoubleArray(2 * pointCount)
        val count = IntArray(pointCount)
        for (slot in 0 until 4 * n * n) {
            val m = moved(slot / 4, slot % 4)
            val p = point[slot]
            sum[2 * p] += m[0]
            sum[2 * p + 1] += m[1]
            count[p]++
        }
        val closed = DoubleArray(2 * pointCount) { sum[it] / count[it / 2] }
        val (b0, b1) = bottomEdges.first().first to bottomEdges.last().second
        val level = -kotlin.math.atan2(closed[2 * b1 + 1] - closed[2 * b0 + 1], closed[2 * b1] - closed[2 * b0])
        val cx = (0 until pointCount).sumOf { closed[2 * it] } / pointCount
        val cy = (0 until pointCount).sumOf { closed[2 * it + 1] } / pointCount
        for (p in 0 until pointCount) {
            val (x, y) = closed[2 * p] - cx to closed[2 * p + 1] - cy
            closed[2 * p] = cos(level) * x - sin(level) * y
            closed[2 * p + 1] = sin(level) * x + cos(level) * y
        }
        return closed
    }

    /** Each tile's four edge lengths and corner angles, interleaved: what "rigid" holds fixed. */
    fun tileShape(x: List<AD>): List<AD> = tiles.flatMap { t ->
        (0 until 4).flatMap { c -> listOf(edgeLength(x, t[c], t[(c + 1) % 4]), cornerAngle(x, t[(c + 3) % 4], t[c], t[(c + 1) % 4])) }
    }

    /** The mean cut opening angle at [x]. */
    fun meanOpening(x: List<AD>): AD = sum(cuts.map { cornerAngle(x, it.endA, it.hinge, it.endB) }) / cuts.size.toDouble()

    /**
     * [steps] + 1 frames from [Design.closed] to [Design.opened], each opening every cut a step further
     * while holding every tile as close to its shape as it will go. [progress] hears each frame number.
     */
    fun deploy(design: Design, steps: Int = 40, progress: (Int) -> Unit = {}): List<DoubleArray> {
        val shapeOf = tileShape(ADTape().variables(design.opened)).map { it.value }
        val openOf = cuts.map { cornerAngle(ADTape().variables(design.opened), it.endA, it.hinge, it.endB).value }
        val frames = mutableListOf(design.closed.copyOf())
        var x = design.closed.copyOf()
        for (s in 1..steps) {
            progress(s)
            val open = s / steps.toDouble()
            x =
                if (s == steps) design.opened.copyOf()
                else
                    leastSquaresAD(x, LeastSquaresOptions(maxIterations = 30)) { p ->
                        val shape = tileShape(p).mapIndexed { i, v -> v - shapeOf[i] }
                        val cx = sum((0 until pointCount).map { p[2 * it] }) / pointCount.toDouble()
                        val cy = sum((0 until pointCount).map { p[2 * it + 1] }) / pointCount.toDouble()
                        val eased = cuts.mapIndexed { k, c -> (cornerAngle(p, c.endA, c.hinge, c.endB) - openOf[k] * open) * 0.3 }
                        shape + eased + listOf(cx, cy)
                    }.x
            frames += x.copyOf()
        }
        return frames
    }

    /** With every tile and hinge held exactly, asks the closed sheet to open by 0.05 rad; returns how far it opens. */
    fun rigidCheck(closed: DoubleArray): Double {
        val shapeOf = tileShape(ADTape().variables(closed)).map { it.value }
        val nudged =
            leastSquaresAD(closed, LeastSquaresOptions(maxIterations = 50)) { p ->
                tileShape(p).mapIndexed { i, v -> (v - shapeOf[i]) * 1e3 } + listOf(meanOpening(p) - 0.05)
            }.x
        return meanOpening(ADTape().variables(nudged)).value
    }
}
