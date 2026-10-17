@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.geometry.spatial

import afield.math.linalg.Vec2
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Optimal transport between point sets: which source goes to which target, or how much of each source
 * goes to each target, at the least total cost. Three solvers, from exact and small to approximate and
 * large:
 *
 * - [assignment] (the Hungarian method) answers the one-to-one question exactly, for two equally
 *   sized sets. It is `O(n³)` over an `n × n` cost matrix, so it is for hundreds of points, maybe a
 *   few thousand. Flow matching's minibatch coupling and the FLine trajectory matcher use it.
 * - [matchPoints] (BSP-OT, in `bspot.kt`) answers the same one-to-one question approximately, for 2D
 *   clouds of a million points a side.
 * - [sinkhorn] answers a softer question exactly: it transports *mass*, so the sets may differ in
 *   size and carry weights, and every source may split between several targets. The answer is a
 *   transport plan rather than a pairing. A small [sinkhorn] `epsilon` makes the plan nearly a
 *   permutation and its cost nearly the [assignment] cost; a large one blurs it.
 *
 * #### Entropic transport
 *
 * [sinkhorn] solves the entropy-regularised problem: minimise `Σ πᵢⱼ Cᵢⱼ + ε Σ πᵢⱼ (ln πᵢⱼ − 1)` over
 * plans `π` whose rows sum to the source weights `a` and whose columns sum to the target weights `b`.
 * The entropy term makes the problem strictly convex, so the answer is unique, smooth in the inputs,
 * and found by alternately rescaling rows and columns (Cuturi 2013). This implementation keeps the
 * scalings as log-domain dual potentials `f`, `g`, so a small `ε` does not underflow, and
 * `πᵢⱼ = exp((fᵢ + gⱼ − Cᵢⱼ) / ε)`.
 *
 * `ε` is in the units of the cost. With squared-distance costs it is an area: `ε = 0.01` on points in
 * a unit square blurs over roughly `√0.01 = 0.1`. Each iteration is `O(n·m)`, and smaller `ε` needs
 * more of them; [multiscaleSinkhorn] solves a coarse version first and starts the fine one from it.
 */

/**
 * The exact one-to-one assignment for a square [cost] matrix by the Hungarian method: `result[i]` is
 * the column row `i` is matched to, and the total of `cost[i][result[i]]` is the least possible.
 * `O(n³)`; an empty matrix gives an empty result.
 */
fun assignment(cost: Array<DoubleArray>): IntArray = HungarianAssignment.solve(cost)

/**
 * The exact one-to-one matching of [from] onto [to] that minimises total squared distance:
 * `result[i]` is the index in [to] that `from[i]` goes to. The two lists must be the same size.
 * For more than a few thousand points use [matchPoints], which is approximate and `O(n log n)`.
 */
fun assignment(from: List<Vec2>, to: List<Vec2>): IntArray {
    require(from.size == to.size) { "assignment needs two sets of the same size, got ${from.size} and ${to.size}" }
    return HungarianAssignment.solve(squaredDistanceCost(from, to))
}

/**
 * The Hungarian method for square cost matrices, `O(n³)`. [assignment] is the public way in; this
 * object is the one implementation that flow matching, the FLine trajectory matcher and the BSP-OT
 * tests share.
 */
object HungarianAssignment {
    /** `result[i]` is the column assigned to row `i`; see [assignment]. */
    fun solve(cost: Array<DoubleArray>): IntArray {
        val n = cost.size
        if (n == 0) return IntArray(0)
        require(cost.all { it.size == n }) { "Hungarian solver expects a square matrix" }

        val u = DoubleArray(n + 1)
        val v = DoubleArray(n + 1)
        val p = IntArray(n + 1)
        val way = IntArray(n + 1)

        for (i in 1..n) {
            p[0] = i
            var j0 = 0
            val minv = DoubleArray(n + 1) { Double.POSITIVE_INFINITY }
            val used = BooleanArray(n + 1)

            do {
                used[j0] = true
                val i0 = p[j0]
                var delta = Double.POSITIVE_INFINITY
                var j1 = 0

                for (j in 1..n) {
                    if (!used[j]) {
                        val cur = cost[i0 - 1][j - 1] - u[i0] - v[j]
                        if (cur < minv[j]) {
                            minv[j] = cur
                            way[j] = j0
                        }
                        if (minv[j] < delta) {
                            delta = minv[j]
                            j1 = j
                        }
                    }
                }

                for (j in 0..n) {
                    if (used[j]) {
                        u[p[j]] += delta
                        v[j] -= delta
                    } else {
                        minv[j] -= delta
                    }
                }
                j0 = j1
            } while (p[j0] != 0)

            do {
                val j1 = way[j0]
                p[j0] = p[j1]
                j0 = j1
            } while (j0 != 0)
        }

        val assignment = IntArray(n) { -1 }
        for (j in 1..n) {
            if (p[j] != 0) assignment[p[j] - 1] = j - 1
        }
        return assignment
    }
}

/**
 * The answer [sinkhorn] gives: a transport plan, the dual potentials that generate it, and how well it
 * met its constraints.
 */
class TransportPlan internal constructor(
    /** `plan[i][j]` is how much of source `i` goes to target `j`. Rows sum to the source weights, columns to the target weights, and the whole plan to 1. */
    val plan: Array<DoubleArray>,
    /** The source weights the plan was solved for, normalised to sum to 1. */
    val sourceWeights: DoubleArray,
    /** The target weights, normalised to sum to 1. */
    val targetWeights: DoubleArray,
    /** The dual potential on each source; the plan is `exp((f[i] + g[j] − cost[i][j]) / ε)`. */
    val f: DoubleArray,
    /** The dual potential on each target. */
    val g: DoubleArray,
    /** The regularisation the plan was solved at. */
    val epsilon: Double,
    /** The transport cost `Σ plan[i][j] · cost[i][j]`, without the entropy term. */
    val cost: Double,
    /** The regularised objective `cost + ε Σ π (ln π − 1)` that was minimised. */
    val objective: Double,
    /** Row-then-column sweeps run. */
    val iterations: Int,
    /** Whether both marginal errors fell to the tolerance before the iteration limit. */
    val converged: Boolean,
    /** The largest difference between a row sum and its source weight. */
    val rowError: Double,
    /** The largest difference between a column sum and its target weight. */
    val columnError: Double,
) {
    /** The number of sources (rows). */
    val sources: Int get() = plan.size

    /** The number of targets (columns). */
    val targets: Int get() = if (plan.isEmpty()) 0 else plan[0].size

    /**
     * Where each source goes on average: the plan-weighted mean of the targets it sends mass to. With
     * a small `ε` this is close to the one target it is matched with; with a large one it is pulled
     * toward the middle of everything it spreads over.
     */
    fun barycentricMap(to: List<Vec2>): List<Vec2> {
        require(to.size == targets) { "barycentricMap needs the ${targets} targets the plan was solved for, got ${to.size}" }
        return plan.mapIndexed { i, row ->
            val w = sourceWeights[i]
            if (w <= 0.0) Vec2(Float.NaN, Float.NaN)
            else {
                var x = 0.0
                var y = 0.0
                for (j in row.indices) {
                    x += row[j] * to[j].x
                    y += row[j] * to[j].y
                }
                Vec2((x / w).toFloat(), (y / w).toFloat())
            }
        }
    }

    /** [barycentricMap] for points of any dimension: `to[j]` is target `j`'s coordinates. */
    fun barycentricMap(to: Array<DoubleArray>): Array<DoubleArray> {
        require(to.size == targets) { "barycentricMap needs the ${targets} targets the plan was solved for, got ${to.size}" }
        val d = if (to.isEmpty()) 0 else to[0].size
        return Array(sources) { i ->
            val w = sourceWeights[i]
            DoubleArray(d) { k ->
                if (w <= 0.0) Double.NaN else plan[i].indices.sumOf { j -> plan[i][j] * to[j][k] } / w
            }
        }
    }

    /** For each source, the target that receives the most of it. */
    fun strongest(): IntArray = IntArray(sources) { i ->
        var best = 0
        for (j in 1 until targets) if (plan[i][j] > plan[i][best]) best = j
        best
    }

    override fun toString(): String =
        "TransportPlan(${sources}×${targets}, ε=$epsilon, cost=${"%.6g".format(cost)}, " +
            "${if (converged) "converged" else "not converged"} in $iterations, error=${"%.2g".format(max(rowError, columnError))})"
}

/**
 * Entropy-regularised optimal transport by log-domain Sinkhorn iteration; see the file overview.
 *
 * [cost] is `n × m`. [sourceWeights] (size `n`) and [targetWeights] (size `m`) are how much mass sits
 * at each point; they are normalised, so only their proportions matter, and the default is uniform.
 * Iteration stops when both marginals are within [tolerance] of the weights, checked every
 * [checkEvery] sweeps, or after [maxIterations]. [initialF] and [initialG] warm-start the dual
 * potentials, which is how [multiscaleSinkhorn] reuses a coarse solve.
 */
fun sinkhorn(
    cost: Array<DoubleArray>,
    epsilon: Double,
    sourceWeights: DoubleArray? = null,
    targetWeights: DoubleArray? = null,
    maxIterations: Int = 10_000,
    tolerance: Double = 1e-9,
    checkEvery: Int = 20,
    initialF: DoubleArray? = null,
    initialG: DoubleArray? = null,
): TransportPlan {
    val n = cost.size
    val m = if (n == 0) 0 else cost[0].size
    require(epsilon > 0.0) { "epsilon must be positive, got $epsilon" }
    require(n > 0 && m > 0) { "sinkhorn needs at least one source and one target" }
    require(cost.all { it.size == m }) { "cost must be a rectangular n × m matrix" }
    require(checkEvery >= 1) { "checkEvery must be at least 1" }

    val a = normalisedWeights(sourceWeights, n, "sourceWeights")
    val b = normalisedWeights(targetWeights, m, "targetWeights")
    val logA = DoubleArray(n) { ln(max(a[it], MIN_MASS)) }
    val logB = DoubleArray(m) { ln(max(b[it], MIN_MASS)) }

    val f = initialF?.also { require(it.size == n) { "initialF must have $n entries" } }?.copyOf() ?: DoubleArray(n)
    val g = initialG?.also { require(it.size == m) { "initialG must have $m entries" } }?.copyOf() ?: DoubleArray(m)
    val invEps = 1.0 / epsilon
    val work = DoubleArray(max(n, m))

    var iteration = 0
    while (true) {
        // rows to a, then columns to b; each a stabilised log-sum-exp over the plan's exponent
        for (i in 0 until n) {
            var top = Double.NEGATIVE_INFINITY
            for (j in 0 until m) {
                val v = (f[i] + g[j] - cost[i][j]) * invEps
                work[j] = v
                if (v > top) top = v
            }
            var sum = 0.0
            for (j in 0 until m) sum += exp(work[j] - top)
            f[i] += epsilon * (logA[i] - (top + ln(sum)))
        }
        for (j in 0 until m) {
            var top = Double.NEGATIVE_INFINITY
            for (i in 0 until n) {
                val v = (f[i] + g[j] - cost[i][j]) * invEps
                work[i] = v
                if (v > top) top = v
            }
            var sum = 0.0
            for (i in 0 until n) sum += exp(work[i] - top)
            g[j] += epsilon * (logB[j] - (top + ln(sum)))
        }
        iteration++

        if (iteration % checkEvery == 0 || iteration >= maxIterations) {
            val plan = Array(n) { i -> DoubleArray(m) { j -> exp((f[i] + g[j] - cost[i][j]) * invEps) } }
            var rowError = 0.0
            for (i in 0 until n) rowError = max(rowError, abs(plan[i].sum() - a[i]))
            var columnError = 0.0
            for (j in 0 until m) columnError = max(columnError, abs((0 until n).sumOf { plan[it][j] } - b[j]))
            val converged = max(rowError, columnError) <= tolerance
            if (converged || iteration >= maxIterations) {
                var transport = 0.0
                var entropy = 0.0
                for (i in 0 until n) for (j in 0 until m) {
                    val p = plan[i][j]
                    if (p > 0.0) {
                        transport += p * cost[i][j]
                        entropy += p * (ln(p) - 1.0)
                    }
                }
                return TransportPlan(plan, a, b, f, g, epsilon, transport, transport + epsilon * entropy,
                    iteration, converged, rowError, columnError)
            }
        }
    }
}

/**
 * [sinkhorn] between two 2D point sets with squared-distance cost, so `epsilon` is an area in the
 * points' units. The sets may differ in size; the weights default to uniform.
 */
fun sinkhorn(
    from: List<Vec2>,
    to: List<Vec2>,
    epsilon: Double,
    sourceWeights: DoubleArray? = null,
    targetWeights: DoubleArray? = null,
    maxIterations: Int = 10_000,
    tolerance: Double = 1e-9,
): TransportPlan = sinkhorn(squaredDistanceCost(from, to), epsilon, sourceWeights, targetWeights, maxIterations, tolerance)

/**
 * The Sinkhorn divergence `OTε(μ, ν) − (OTε(μ, μ) + OTε(ν, ν)) / 2` between two weighted 2D point
 * sets, with squared-distance cost: a distance-like measure of how different two distributions are
 * that, unlike the regularised cost alone, is zero when they are the same and does not reward
 * blurring. It costs three [sinkhorn] solves.
 */
fun sinkhornDivergence(
    from: List<Vec2>,
    to: List<Vec2>,
    epsilon: Double,
    sourceWeights: DoubleArray? = null,
    targetWeights: DoubleArray? = null,
    maxIterations: Int = 10_000,
    tolerance: Double = 1e-9,
): Double {
    val xy = sinkhorn(squaredDistanceCost(from, to), epsilon, sourceWeights, targetWeights, maxIterations, tolerance)
    val xx = sinkhorn(squaredDistanceCost(from, from), epsilon, sourceWeights, sourceWeights, maxIterations, tolerance)
    val yy = sinkhorn(squaredDistanceCost(to, to), epsilon, targetWeights, targetWeights, maxIterations, tolerance)
    return xy.objective - 0.5 * (xx.objective + yy.objective)
}

/**
 * [sinkhorn] for larger point sets, coarse to fine. Each set is reduced to a pyramid of
 * farthest-point-sampled centres, each centre carrying the weight of the points nearest it, halving
 * until about [minSize] remain; the coarsest pair is solved first, and each finer solve starts from
 * the potentials of the one before, copied from each point's centre. The finest level is the full
 * problem, so the answer is the same plan [sinkhorn] would reach; what changes is how many sweeps it
 * takes to get there. Points are rows of coordinates of any dimension, with squared-distance cost.
 */
fun multiscaleSinkhorn(
    from: Array<DoubleArray>,
    to: Array<DoubleArray>,
    epsilon: Double,
    sourceWeights: DoubleArray? = null,
    targetWeights: DoubleArray? = null,
    minSize: Int = 64,
    maxIterationsPerLevel: Int = 5_000,
    tolerance: Double = 1e-9,
): TransportPlan {
    require(from.isNotEmpty() && to.isNotEmpty()) { "multiscaleSinkhorn needs at least one source and one target" }
    require(minSize >= 1) { "minSize must be at least 1" }
    val pyramidFrom = transportPyramid(from, normalisedWeights(sourceWeights, from.size, "sourceWeights"), minSize, seed = 0)
    val pyramidTo = transportPyramid(to, normalisedWeights(targetWeights, to.size, "targetWeights"), minSize, seed = 1)

    // both pyramids end at the full sets; drop the coarsest levels of the deeper one
    val levels = min(pyramidFrom.size, pyramidTo.size)
    val fromLevels = pyramidFrom.takeLast(levels)
    val toLevels = pyramidTo.takeLast(levels)

    var f: DoubleArray? = null
    var g: DoubleArray? = null
    var result: TransportPlan? = null
    for (level in 0 until levels) {
        val x = fromLevels[level]
        val y = toLevels[level]
        val finest = level == levels - 1
        result = sinkhorn(
            squaredDistanceCost(x.points, y.points), epsilon, x.weights, y.weights,
            maxIterationsPerLevel, if (finest) tolerance else max(1e-6, tolerance), initialF = f, initialG = g
        )
        if (!finest) {
            // each finer point starts from its centre's potential
            val fineFrom = fromLevels[level + 1]
            val fineTo = toLevels[level + 1]
            val coarseF = result.f
            val coarseG = result.g
            f = DoubleArray(fineFrom.points.size) { coarseF[fineFrom.parent[it]] }
            g = DoubleArray(fineTo.points.size) { coarseG[fineTo.parent[it]] }
        }
    }
    return result!!
}

/** The `n × m` matrix of squared distances from each of [from] to each of [to]. */
fun squaredDistanceCost(from: List<Vec2>, to: List<Vec2>): Array<DoubleArray> = Array(from.size) { i ->
    val p = from[i]
    DoubleArray(to.size) { j ->
        val dx = (p.x - to[j].x).toDouble()
        val dy = (p.y - to[j].y).toDouble()
        dx * dx + dy * dy
    }
}

/** [squaredDistanceCost] for points of any dimension, one row of coordinates per point. */
fun squaredDistanceCost(from: Array<DoubleArray>, to: Array<DoubleArray>): Array<DoubleArray> = Array(from.size) { i ->
    DoubleArray(to.size) { j -> squaredDistance(from[i], to[j]) }
}

private const val MIN_MASS = 1e-300

private fun normalisedWeights(weights: DoubleArray?, size: Int, name: String): DoubleArray {
    if (weights == null) return DoubleArray(size) { 1.0 / size }
    require(weights.size == size) { "$name has ${weights.size} entries for $size points" }
    require(weights.all { it >= 0.0 && it.isFinite() }) { "$name must be finite and non-negative" }
    val total = weights.sum()
    require(total > 0.0) { "$name must not all be zero" }
    return DoubleArray(size) { weights[it] / total }
}

private fun squaredDistance(p: DoubleArray, q: DoubleArray): Double {
    require(p.size == q.size) { "points of different dimension: ${p.size} and ${q.size}" }
    var s = 0.0
    for (k in p.indices) {
        val t = p[k] - q[k]
        s += t * t
    }
    return s
}

/** One level of a [multiscaleSinkhorn] pyramid; `parent[i]` is the index, one level coarser, of point `i`'s centre. */
private class TransportLevel(val points: Array<DoubleArray>, val weights: DoubleArray, val parent: IntArray)

/** Coarse to fine, ending with the input itself. */
private fun transportPyramid(points: Array<DoubleArray>, weights: DoubleArray, minSize: Int, seed: Int): List<TransportLevel> {
    val fineToCoarse = mutableListOf(TransportLevel(points, weights, IntArray(0)))
    var current = fineToCoarse.last()
    var levelSeed = seed
    while (current.points.size / 2 >= minSize) {
        val k = current.points.size / 2
        val centres = farthestPointSample(current.points, k, levelSeed++)
        val centrePoints = Array(k) { current.points[centres[it]].copyOf() }
        val parent = IntArray(current.points.size)
        val centreWeights = DoubleArray(k)
        for (i in current.points.indices) {
            var best = 0
            var bestDistance = Double.POSITIVE_INFINITY
            for (c in 0 until k) {
                val d = squaredDistance(current.points[i], centrePoints[c])
                if (d < bestDistance) {
                    bestDistance = d
                    best = c
                }
            }
            parent[i] = best
            centreWeights[best] += current.weights[i]
        }
        // the finer level records its parents in this coarser one
        fineToCoarse[fineToCoarse.lastIndex] = TransportLevel(current.points, current.weights, parent)
        current = TransportLevel(centrePoints, centreWeights, IntArray(0))
        fineToCoarse += current
    }
    return fineToCoarse.reversed()
}

/** `k` indices into [points], each the farthest from those already chosen, starting from a seeded one. */
private fun farthestPointSample(points: Array<DoubleArray>, k: Int, seed: Int): IntArray {
    val n = points.size
    val chosen = IntArray(k)
    chosen[0] = ((seed % n) + n) % n
    val nearest = DoubleArray(n) { squaredDistance(points[it], points[chosen[0]]) }
    for (t in 1 until k) {
        var far = 0
        for (i in 1 until n) if (nearest[i] > nearest[far]) far = i
        chosen[t] = far
        for (i in 0 until n) nearest[i] = min(nearest[i], squaredDistance(points[i], points[far]))
    }
    return chosen
}
