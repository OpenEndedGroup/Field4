@file:afield.CompletionCategory("Graphics")

package afield.stdlib

import jdk.incubator.vector.DoubleVector
import java.util.SplittableRandom
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One-to-one matching between two equally sized 2D point clouds, at a million points a side, using
 * BSP-OT: every source point gets exactly one target and every target is used exactly once, with the
 * total squared travel distance kept small.
 *
 * The exact answer to that problem is an assignment problem, and the Hungarian method that
 * `flowmatching.kt` uses for its minibatches is `O(n³)` time over an `n × n` cost matrix. At a
 * thousand points that is fine. At a million the matrix alone is four terabytes. BSP-OT gives up
 * provable optimality to get to `O(n log n)`:
 *
 * 1. Choose a direction. Split *each* cloud at its own median along it, so both left halves hold
 *    the same number of points, and so do both right halves.
 * 2. Recurse, left with left and right with right, with a new direction each time. A leaf of one
 *    point pairs its source with its target; a leaf of two picks the cheaper of the two pairings.
 * 3. That is one candidate bijection. Make several and merge each new one into the best so far: the
 *    union of two permutations falls apart into disjoint alternating cycles, and on each cycle you
 *    keep whichever orientation is cheaper. Then try two-point swaps along the candidate's edges.
 *
 * The direction in step 1 is what [BspOt2D.Splits] chooses. The default, as in the reference
 * implementation, fits a Gaussian to each of the two subsets and splits along an eigenvector of the
 * linear map that carries one Gaussian onto the other, picking one of the two at random per node.
 * When the subsets differ in shape that axis is the one along which they need to move, and it
 * about halves the excess cost of random directions at eight trees. Below 50 points it stops fitting and splits
 * perpendicular to the parent.
 *
 * The counts are equal by construction, not because the two clouds occupy the same cells, so clouds
 * of very different density still match completely.
 *
 *     val result = matchPoints(from, to)            // two List<Vec2> of the same size
 *     val destination = result.reorder(to)          // destination[i] is where from[i] goes
 *     val halfway = from.indices.map { from[it] + (destination[it] - from[it]) * 0.5f }
 *
 * For raw `FloatArray`s, call [BspOt2D.match] directly. A previous permutation can be passed as
 * `initial` and is improved rather than replaced, which suits matching successive frames of a
 * simulation.
 *
 * #### Cost, measured
 *
 * On an M1 Max with the default four workers and eight trees, a million points a side takes 3 to
 * 4.5 s for two uniform clouds, and 5.7 s from a uniform square onto three discs (7.4 s with random
 * splits, which reach a 5% higher cost). Merging is sequential. Array storage is about `69 + 60 P`
 * bytes a point for `P` workers, roughly 309 MB at a million points and four workers. The SIMD
 * paths (projection, and cost evaluation through a gather) gave no measurable end-to-end gain over
 * `simd = false` when this was first measured; partitioning, cycle walking and the swaps are
 * scalar.
 *
 * #### What it does not give you
 *
 * - **No optimality guarantee, and further from optimal than the paper.** From a uniform square
 *   onto three discs, 1000 points, the total cost is 5.5% above the exact Hungarian answer with 8
 *   trees and 2.9% with 32. Two independent uniform clouds are the hard case: every node's two
 *   subsets have nearly the same Gaussian, so the transport map is close to the identity and its
 *   eigenvectors are noise. At 400 points that lands 26% above exact with 64 trees, where random
 *   splits reach 16%. Leaving those clouds unmatched is 120 times worse. The paper reports
 *   typically under 1% with 64 candidates; it ranks all candidates before merging, which this
 *   version, merging as it goes in bounded memory, does not. The bijection is good enough for a
 *   morph to read as a morph; it is not a solver for the exact assignment.
 * - **2D only, equal sizes only, uniform weights only.** No partial or unbalanced transport.
 * - **Deterministic for a given seed and options, across worker counts.** Not promised bit-identical
 *   across JDKs or CPUs.
 *
 * Algorithm from Baptiste Genest, Nicolas Bonneel, Vincent Nivoliers and David Coeurjolly,
 * "BSP-OT: Sparse transport plans between discrete measures in loglinear time", ACM Transactions on
 * Graphics (SIGGRAPH Asia 2025), https://perso.liris.cnrs.fr/david.coeurjolly/publication/bspot-25/.
 * The Gaussian split follows `gaussianPartialBSPOT` and the merge `MergePlansNoPar` and
 * `BijectiveMatching::swapIfUpgrade` in their MIT-licensed
 * reference implementation, https://github.com/baptiste-genest/BSP-OT, Copyright (c) 2025 the same
 * authors.
 */

/**
 * Matches [from] onto [to] one-to-one with [BspOt2D]; both lists must be the same size.
 *
 * [initial], if given, must be a permutation (for instance the [BspOt2D.Result.targetOfSource] of a
 * previous call); it is copied and improved. [trees] counts *new* candidate bijections either way.
 */
fun matchPoints(
    from: List<Vec2>,
    to: List<Vec2>,
    trees: Int = 16,
    seed: Long = 1L,
    initial: IntArray? = null,
): BspOt2D.Result {
    require(from.size == to.size) { "matchPoints needs two clouds of the same size, got ${from.size} and ${to.size}" }
    return BspOt2D.match(
        FloatArray(from.size) { from[it].x }, FloatArray(from.size) { from[it].y },
        FloatArray(to.size) { to[it].x }, FloatArray(to.size) { to[it].y },
        BspOt2D.Options(trees = trees, seed = seed),
        initial,
    )
}

/**
 * Equal-cardinality, equal-mass 2D BSP-OT with squared Euclidean cost: median BSP proposals split
 * along Gaussian-guided or random directions, merged by alternating cycles and improving two-point
 * swaps.
 *
 * Input `FloatArray`s are copied into doubles, so even `Float.MAX_VALUE` coordinates cannot overflow
 * a squared distance. Proposals are generated by a bounded worker pool and merged on the calling
 * thread in tree order; each tree has its own seed, so the result does not depend on
 * [Options.parallelism].
 */
object BspOt2D {
    private val SPECIES = DoubleVector.SPECIES_PREFERRED

    /** Doubles per SIMD register on this machine. */
    val simdLanes: Int get() = SPECIES.length()

    /** How each BSP node chooses the direction it splits both subsets along. */
    enum class Splits {
        /**
         * The reference implementation's choice for bijections. Fit a Gaussian to each subset and
         * split along an eigenvector, picked at random, of the linear map that optimally transports
         * one Gaussian onto the other. Below [GAUSSIAN_LEAF] points a node splits perpendicular to
         * its parent instead, as the reference does.
         */
        GAUSSIAN,

        /** A uniformly random direction at every node. Cheaper per node, and worse matchings. */
        RANDOM,
    }

    /** Subsets smaller than this stop fitting Gaussians under [Splits.GAUSSIAN]. */
    const val GAUSSIAN_LEAF = 50

    /** Settings for [match]. */
    data class Options(
        /** Number of candidate bijections to build and merge. More lowers cost and costs time. */
        val trees: Int = 16,
        /** How each node picks its split direction. */
        val splits: Splits = Splits.GAUSSIAN,
        /** Master seed; each tree's own seed is drawn from it in tree order. */
        val seed: Long = 1L,
        /** Use the Vector API for projections and costs. `false` is the scalar reference path. */
        val simd: Boolean = true,
        /** Merge each candidate by choosing the cheaper side of every alternating cycle. */
        val cycleMerge: Boolean = true,
        /** Passes of improving two-point swaps per merged candidate. */
        val swapPasses: Int = 1,
        /** Candidate generators running at once, capped at [trees]. `1` runs without an executor. */
        val parallelism: Int = minOf(4, Runtime.getRuntime().availableProcessors()),
    ) {
        init {
            require(parallelism >= 1) { "parallelism must be >= 1" }
            require(trees >= 1) { "trees must be >= 1" }
            require(swapPasses >= 0) { "swapPasses must be >= 0" }
        }
    }

    /** A bijection from sources to targets, and what it cost. */
    class Result(
        /** `targetOfSource[i]` is the unique target assigned to source `i`. */
        val targetOfSource: IntArray,
        /** Sum over sources of squared distance to their target. */
        val squaredCost: Double,
        /** [squaredCost] after each candidate was merged; never increases. */
        val costHistory: DoubleArray,
    ) {
        /** [squaredCost] divided by the number of points, or 0 for an empty match. */
        val meanSquaredDistance: Double
            get() = if (targetOfSource.isEmpty()) 0.0 else squaredCost / targetOfSource.size

        /** [to] rearranged so that element `i` is the target of source `i`. */
        fun <T> reorder(to: List<T>): List<T> {
            require(to.size == targetOfSource.size) { "reorder needs ${targetOfSource.size} targets, got ${to.size}" }
            return List(to.size) { to[targetOfSource[it]] }
        }
    }

    /**
     * Matches source points `(ax[i], ay[i])` onto target points `(bx[j], by[j])`.
     *
     * [initial], if supplied, must be a permutation; it is copied and improved, and [Options.trees]
     * still counts new candidates. No argument is mutated. Each call owns its workspace and worker
     * pool, so concurrent calls are safe. Interrupting the calling thread cancels the workers and
     * throws [CancellationException] with the interrupt status preserved.
     */
    fun match(
        ax: FloatArray, ay: FloatArray, bx: FloatArray, by: FloatArray,
        options: Options = Options(), initial: IntArray? = null,
    ): Result {
        val n = ax.size
        require(ay.size == n && bx.size == n && by.size == n) {
            "All four coordinate arrays must have equal length"
        }
        validateCoordinates(ax); validateCoordinates(ay)
        validateCoordinates(bx); validateCoordinates(by)
        if (initial != null) validatePermutation(initial, n)
        if (n == 0) return Result(IntArray(0), 0.0, DoubleArray(options.trees))
        return Workspace(Coordinates(ax, ay, bx, by, options.simd), options).run(initial)
    }

    private fun validateCoordinates(a: FloatArray) {
        for (v in a) require(v.isFinite()) { "Coordinates must be finite" }
    }

    /** Throws [IllegalArgumentException] unless [p] is a permutation of `0 until n`. */
    fun validatePermutation(p: IntArray, n: Int = p.size) {
        require(p.size == n) { "Wrong permutation length" }
        val seen = BooleanArray(n)
        for (j in p) {
            require(j >= 0 && j < n && !seen[j]) { "Not a permutation" }
            seen[j] = true
        }
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("BSP-OT interrupted")
    }

    private class Cloud(val x: DoubleArray, val y: DoubleArray) {
        val id = IntArray(x.size)
        fun reset(originalX: DoubleArray, originalY: DoubleArray) {
            originalX.copyInto(x); originalY.copyInto(y)
            for (i in id.indices) id[i] = i
        }
        fun swap(i: Int, j: Int, dot: DoubleArray) {
            var d = x[i]; x[i] = x[j]; x[j] = d
            d = y[i]; y[i] = y[j]; y[j] = d
            d = dot[i]; dot[i] = dot[j]; dot[j] = d
            val k = id[i]; id[i] = id[j]; id[j] = k
        }
    }

    // Immutable coordinate snapshot shared by every generator and the merger.
    private class Coordinates(
        axf: FloatArray, ayf: FloatArray, bxf: FloatArray, byf: FloatArray,
        val simd: Boolean,
    ) {
        val n = axf.size
        val ax = DoubleArray(n) { axf[it].toDouble() }
        val ay = DoubleArray(n) { ayf[it].toDouble() }
        val bx = DoubleArray(n) { bxf[it].toDouble() }
        val by = DoubleArray(n) { byf[it].toDouble() }

        fun cost(i: Int, j: Int): Double {
            val dx = ax[i] - bx[j]
            val dy = ay[i] - by[j]
            return dx * dx + dy * dy
        }

        fun evaluate(p: IntArray, out: DoubleArray) {
            var i = 0
            if (simd) {
                val bound = SPECIES.loopBound(n)
                while (i < bound) {
                    val dx = DoubleVector.fromArray(SPECIES, ax, i)
                        .sub(DoubleVector.fromArray(SPECIES, bx, 0, p, i))
                    val dy = DoubleVector.fromArray(SPECIES, ay, i)
                        .sub(DoubleVector.fromArray(SPECIES, by, 0, p, i))
                    dx.mul(dx).add(dy.mul(dy)).intoArray(out, i)
                    i += SPECIES.length()
                }
            }
            while (i < n) { out[i] = cost(i, p[i]); i++ }
        }
    }

    // Builds one candidate bijection into its own scratch arrays.
    private class Generator(val data: Coordinates, val options: Options) {
        val n = data.n
        val a = Cloud(DoubleArray(n), DoubleArray(n))
        val b = Cloud(DoubleArray(n), DoubleArray(n))
        val dot = DoubleArray(n) // projection scratch; A's partition finishes before B's starts
        val proposal = IntArray(n)
        val proposalCosts = DoubleArray(n)
        var rng = SplittableRandom(0)

        fun generate(seed: Long): Generator {
            checkInterrupted()
            rng = SplittableRandom(seed)
            a.reset(data.ax, data.ay); b.reset(data.bx, data.by)
            val angle = rng.nextDouble() * (2.0 * Math.PI)
            split(0, n, cos(angle), sin(angle))
            data.evaluate(proposal, proposalCosts)
            return this
        }

        fun cost(i: Int, j: Int): Double = data.cost(i, j)

        fun project(c: Cloud, lo: Int, hi: Int, ux: Double, uy: Double) {
            var i = lo
            if (options.simd) {
                val end = lo + SPECIES.loopBound(hi - lo)
                while (i < end) {
                    DoubleVector.fromArray(SPECIES, c.x, i).mul(ux)
                        .add(DoubleVector.fromArray(SPECIES, c.y, i).mul(uy))
                        .intoArray(dot, i)
                    i += SPECIES.length()
                }
            }
            while (i < hi) { dot[i] = c.x[i] * ux + c.y[i] * uy; i++ }
        }

        // Randomized three-way quickselect: expected linear work. Equal projections occupy the
        // middle band, so coincident points cannot loop forever.
        fun select(c: Cloud, from: Int, until: Int, kth: Int) {
            var lo = from
            var hi = until
            while (hi - lo > 1) {
                checkInterrupted()
                val pivot = dot[rng.nextInt(lo, hi)]
                var lt = lo
                var i = lo
                var gt = hi
                while (i < gt) {
                    val d = dot[i]
                    when {
                        d < pivot -> { c.swap(i, lt, dot); i++; lt++ }
                        d > pivot -> { gt--; c.swap(i, gt, dot) }
                        else -> i++
                    }
                }
                when {
                    kth < lt -> hi = lt
                    kth >= gt -> lo = gt
                    else -> return
                }
            }
        }

        // The parent's split direction comes in as (px, py); it is what the perpendicular
        // fallback below GAUSSIAN_LEAF turns away from, and the start of the random chain.
        fun split(lo: Int, hi: Int, px: Double, py: Double) {
            checkInterrupted()
            val size = hi - lo
            if (size == 1) { proposal[a.id[lo]] = b.id[lo]; return }
            if (size == 2) {
                val i = a.id[lo]; val j = a.id[lo + 1]
                val u = b.id[lo]; val v = b.id[lo + 1]
                if (cost(i, u) + cost(j, v) <= cost(i, v) + cost(j, u)) {
                    proposal[i] = u; proposal[j] = v
                } else { proposal[i] = v; proposal[j] = u }
                return
            }
            var ux: Double; var uy: Double
            when {
                options.splits == Splits.RANDOM -> {
                    val angle = rng.nextDouble() * (2.0 * Math.PI)
                    ux = cos(angle); uy = sin(angle)
                }
                size < GAUSSIAN_LEAF -> { ux = -py; uy = px }
                else -> {
                    gaussianDirection(lo, hi)
                    ux = dirX; uy = dirY
                }
            }
            val mid = lo + size / 2
            project(a, lo, hi, ux, uy); select(a, lo, hi, mid)
            project(b, lo, hi, ux, uy); select(b, lo, hi, mid)
            split(lo, mid, ux, uy); split(mid, hi, ux, uy)
        }

        // Covariance of c over [lo, hi) into cxx, cxy, cyy. One pass, shifted by the first point
        // so that a cluster far from the origin does not cancel away its own spread.
        var cxx = 0.0; var cxy = 0.0; var cyy = 0.0
        fun covariance(c: Cloud, lo: Int, hi: Int) {
            val x0 = c.x[lo]; val y0 = c.y[lo]
            var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0; var syy = 0.0
            for (i in lo until hi) {
                val x = c.x[i] - x0; val y = c.y[i] - y0
                sx += x; sy += y; sxx += x * x; sxy += x * y; syy += y * y
            }
            val m = (hi - lo).toDouble()
            val mx = sx / m; val my = sy / m
            cxx = sxx / m - mx * mx; cxy = sxy / m - mx * my; cyy = syy / m - my * my
        }

        // An eigenvector, chosen at random, of the W2 transport map between Gaussians fitted to
        // the two subsets, T = A^-1/2 (A^1/2 B A^1/2)^1/2 A^-1/2. Everything is a symmetric 2x2,
        // so the roots are closed form: sqrt(M) = (M + sqrt(det M) I) / sqrt(tr M + 2 sqrt(det M)).
        var dirX = 1.0; var dirY = 0.0
        fun gaussianDirection(lo: Int, hi: Int) {
            covariance(a, lo, hi); var a11 = cxx; val a12 = cxy; var a22 = cyy
            covariance(b, lo, hi); var b11 = cxx; val b12 = cxy; var b22 = cyy
            // Collinear or coincident subsets make A singular; a small relative ridge keeps the
            // inverse root finite without moving the answer for any subset with real spread.
            val ridge = 1e-9 * (a11 + a22 + b11 + b22)
            a11 += ridge; a22 += ridge; b11 += ridge; b22 += ridge

            var s = sqrt(max(0.0, a11 * a22 - a12 * a12))
            var t = sqrt(a11 + a22 + 2 * s)
            val r11 = (a11 + s) / t; val r12 = a12 / t; val r22 = (a22 + s) / t // A^1/2
            val det = r11 * r22 - r12 * r12
            val i11 = r22 / det; val i12 = -r12 / det; val i22 = r11 / det     // A^-1/2

            // C = R B R, then its root S.
            val m11 = r11 * b11 + r12 * b12; val m12 = r11 * b12 + r12 * b22
            val m21 = r12 * b11 + r22 * b12; val m22 = r12 * b12 + r22 * b22
            val c11 = m11 * r11 + m12 * r12; val c12 = m11 * r12 + m12 * r22
            val c22 = m21 * r12 + m22 * r22
            s = sqrt(max(0.0, c11 * c22 - c12 * c12))
            t = sqrt(c11 + c22 + 2 * s)
            val s11 = (c11 + s) / t; val s12 = c12 / t; val s22 = (c22 + s) / t

            // T = Rinv S Rinv.
            val n11 = i11 * s11 + i12 * s12; val n12 = i11 * s12 + i12 * s22
            val n21 = i12 * s11 + i22 * s12; val n22 = i12 * s12 + i22 * s22
            val t11 = n11 * i11 + n12 * i12; val t12 = n11 * i12 + n12 * i22
            val t22 = n21 * i12 + n22 * i22

            var angle = 0.5 * atan2(2 * t12, t11 - t22)
            if (!angle.isFinite()) angle = rng.nextDouble() * Math.PI
            if (rng.nextBoolean()) angle += 0.5 * Math.PI
            dirX = cos(angle); dirY = sin(angle)
            // An axis along which neither subset spreads cannot order anything; take the other.
            val along = spread(a11, a12, a22, dirX, dirY) + spread(b11, b12, b22, dirX, dirY)
            val across = spread(a11, a12, a22, -dirY, dirX) + spread(b11, b12, b22, -dirY, dirX)
            if (along <= 1e-6 * across) { val x = dirX; dirX = -dirY; dirY = x }
        }

        fun spread(m11: Double, m12: Double, m22: Double, x: Double, y: Double) =
            m11 * x * x + 2 * m12 * x * y + m22 * y * y
    }

    // Owns the incumbent bijection and merges candidates into it, sequentially, in tree order.
    private class Workspace(val data: Coordinates, val options: Options) {
        val n = data.n
        val inverse = IntArray(n)
        val next = IntArray(n)
        val visited = BooleanArray(n)
        val costs = DoubleArray(n)
        lateinit var proposal: IntArray
        lateinit var proposalCosts: DoubleArray

        fun cost(i: Int, j: Int): Double = data.cost(i, j)

        fun run(initial: IntArray?): Result {
            checkInterrupted()
            val result = initial?.copyOf() ?: IntArray(n)
            if (initial != null) data.evaluate(result, costs)
            val history = DoubleArray(options.trees)
            val seeds = SplittableRandom(options.seed)
            val workerCount = minOf(options.parallelism, options.trees)

            fun consume(tree: Int, generator: Generator) {
                checkInterrupted()
                // Future.get publishes the worker's writes. This slot stays idle until the merge
                // finishes, because merge mutates its proposal and cost arrays.
                proposal = generator.proposal
                proposalCosts = generator.proposalCosts
                if (tree == 0 && initial == null) {
                    proposal.copyInto(result); proposalCosts.copyInto(costs)
                } else {
                    merge(result)
                }
                history[tree] = sum(costs)
            }

            if (workerCount == 1) {
                val generator = Generator(data, options)
                for (tree in 0 until options.trees) consume(tree, generator.generate(seeds.nextLong()))
            } else {
                val generators = Array(workerCount) { Generator(data, options) }
                val pending = arrayOfNulls<Future<Generator>>(workerCount)
                val pool = Executors.newFixedThreadPool(workerCount) { r ->
                    Thread(r, "bspot-worker").apply { isDaemon = true }
                }
                fun submit(slot: Int) {
                    val seed = seeds.nextLong() // only the caller draws seeds, in tree order
                    pending[slot] = pool.submit(Callable { generators[slot].generate(seed) })
                }
                try {
                    for (slot in 0 until workerCount) submit(slot)
                    for (tree in 0 until options.trees) {
                        val slot = tree % workerCount
                        consume(tree, pending[slot]!!.get())
                        // At most workerCount live candidates; no unbounded queue.
                        if (tree < options.trees - workerCount) submit(slot)
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw CancellationException("BSP-OT interrupted").also { it.initCause(e) }
                } catch (e: ExecutionException) {
                    when (val cause = e.cause ?: e) {
                        is RuntimeException -> throw cause
                        is Error -> throw cause
                        else -> throw IllegalStateException("BSP-OT worker failed", cause)
                    }
                } finally {
                    for (future in pending) future?.cancel(true)
                    pool.shutdownNow()
                }
            }
            return Result(result, history.last(), history)
        }

        fun merge(p: IntArray) {
            checkInterrupted()
            for (i in 0 until n) inverse[p[i]] = i
            if (options.cycleMerge) {
                // The alternating components of two permutations are disjoint cycles. Freeze the
                // successor links before modifying either permutation.
                for (i in 0 until n) next[i] = inverse[proposal[i]]
                visited.fill(false)
                for (start in 0 until n) {
                    if (start and 4095 == 0) checkInterrupted()
                    if (visited[start]) continue
                    var i = start
                    var old = 0.0; var fresh = 0.0
                    do {
                        visited[i] = true
                        old += costs[i]; fresh += proposalCosts[i]
                        i = next[i]
                    } while (i != start)
                    if (improves(fresh, old)) {
                        i = start
                        do {
                            val j = p[i]; p[i] = proposal[i]; proposal[i] = j
                            val c = costs[i]; costs[i] = proposalCosts[i]; proposalCosts[i] = c
                            i = next[i]
                        } while (i != start)
                    }
                }
                for (i in 0 until n) inverse[p[i]] = i
            }
            // The complementary candidate supplies edges to try. Each accepted swap updates the
            // inverse at once, so these steps depend on each other and stay sequential.
            repeat(options.swapPasses) {
                for (i in 0 until n) {
                    if (i and 4095 == 0) checkInterrupted()
                    val target = proposal[i]
                    val other = inverse[target]
                    if (other == i) continue
                    val old = costs[i] + costs[other]
                    val ci = proposalCosts[i]
                    if (ci >= old) continue
                    val oldTarget = p[i]
                    val co = cost(other, oldTarget)
                    if (improves(ci + co, old)) {
                        p[i] = target; p[other] = oldTarget
                        inverse[target] = i; inverse[oldTarget] = other
                        costs[i] = ci; costs[other] = co
                    }
                }
            }
        }

        // A relative margin, so summation noise is never accepted as an improvement.
        fun improves(fresh: Double, old: Double): Boolean =
            fresh < old && old - fresh > 1e-12 * old

        // Compensated total; the costs themselves are evaluated in double precision.
        fun sum(values: DoubleArray): Double {
            var s = 0.0; var correction = 0.0
            for (v in values) {
                val y = v - correction
                val t = s + y
                correction = (t - s) - y
                s = t
            }
            return s
        }
    }
}
