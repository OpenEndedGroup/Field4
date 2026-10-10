@file:afield.CompletionCategory("Math")
package afield.math.gp

import kotlin.math.*

/** One judgement: [winner] was preferred to [loser]. */
data class GpComparison(val winner: Map<String, Double>, val loser: Map<String, Double>)

/**
 * Learns what someone prefers from "A or B?" answers, and proposes the next pair to ask about.
 *
 * ```kotlin
 * val taste = GpPreference("hue" to 0.0..1.0, "weight" to 0.5..6.0)
 * val (a, b) = taste.nextPair()
 * // ...show both, ask...
 * taste.prefer(winner = a, loser = b)
 * println(taste.best)
 * ```
 *
 * The model is the probit preference GP of Chu & Ghahramani (2005): a latent utility `u` with a
 * GP prior (RBF, one [lengthScale] in the unit cube the parameters are rescaled to, prior variance
 * 1), and P(a preferred to b) = Φ((u(a) − u(b)) / (√2 · [noise])). The posterior over `u` at the
 * compared points is not Gaussian; it is replaced by its Laplace approximation — the Gaussian at
 * the mode, found by Newton's method, with the likelihood's curvature there as precision.
 * Utilities are only defined up to an offset: compare [utility] values, not their zero.
 *
 * [nextPair] pits the current favourite (largest posterior mean among points already shown)
 * against the challenger maximising mean + [exploration] · std over seeded random candidates —
 * the "best versus most promising" duel. Before any answer it returns two spread-out points.
 *
 * [lengthScale] and [noise] are fixed, not fitted: with a handful of binary answers the evidence
 * barely constrains them. [noise] is how inconsistent the judge is allowed to be; smaller trusts
 * each click more.
 */
class GpPreference(
    parameters: List<GpRange>,
    val lengthScale: Double = 0.25,
    val noise: Double = 0.1,
    val exploration: Double = 1.0,
    val seed: Long = 1L,
    val candidates: Int = 512,
) {
    constructor(
        vararg ranges: Pair<String, ClosedFloatingPointRange<Double>>,
        lengthScale: Double = 0.25, noise: Double = 0.1, exploration: Double = 1.0, seed: Long = 1L,
    ) : this(ranges.map { GpRange(it.first, it.second.start, it.second.endInclusive) }, lengthScale, noise, exploration, seed)

    val parameters: List<GpRange> = parameters.toList()
    val dimensions: Int get() = parameters.size
    init {
        require(parameters.isNotEmpty() && parameters.map { it.name }.distinct().size == parameters.size)
        require(lengthScale.isFinite() && lengthScale > 0 && noise.isFinite() && noise > 0 && exploration >= 0 && candidates >= 1)
    }

    private val kernel = GpKernels.rbf(lengthScale)
    private val points = ArrayList<DoubleArray>()
    private val pairs = ArrayList<IntArray>()
    private val random = gpRandom(seed)
    private var fitted: Laplace? = null

    /** Every answer so far, in order. */
    val comparisons: List<GpComparison> get() = pairs.map { GpComparison(toNamed(points[it[0]]), toNamed(points[it[1]])) }

    /** Newton iterations the last Laplace fit took (0 before any answer). */
    val newtonIterations: Int get() = laplace()?.iterations ?: 0

    /** Record one answer. */
    fun prefer(winner: Map<String, Double>, loser: Map<String, Double>) {
        val w = index(toUnit(winner)); val l = index(toUnit(loser))
        require(w != l) { "A point cannot be preferred to itself" }
        pairs += intArrayOf(w, l)
        fitted = null
    }

    /** Record one answer by which of a pair won: `choose(pair, aWins = true)`. */
    fun choose(pair: Pair<Map<String, Double>, Map<String, Double>>, aWins: Boolean) =
        if (aWins) prefer(pair.first, pair.second) else prefer(pair.second, pair.first)

    /** Posterior latent utility at [parameters] (Laplace approximation); the prior before any answer. */
    fun utility(parameters: Map<String, Double>): GpPrediction = utilityUnit(toUnit(parameters))

    /** The shown point with the largest posterior mean utility; null before any answer. */
    val best: Map<String, Double>?
        get() {
            val f = laplace() ?: return null
            return toNamed(points[f.mode.indices.maxBy { f.mode[it] }])
        }

    /** Every point shown so far with its posterior mean utility, best first. */
    fun ranking(): List<Pair<Map<String, Double>, Double>> {
        val f = laplace() ?: return emptyList()
        return points.indices.sortedByDescending { f.mode[it] }.map { toNamed(points[it]) to f.mode[it] }
    }

    /** The next two candidates to compare: the favourite, and the most promising challenger. */
    fun nextPair(): Pair<Map<String, Double>, Map<String, Double>> {
        val f = laplace()
        if (f == null) {
            val d = gpSpaceFilling(2, dimensions, random)
            return toNamed(d[0]) to toNamed(d[1])
        }
        val champion = points[f.mode.indices.maxBy { f.mode[it] }]
        val pool = ArrayList<DoubleArray>()
        repeat(candidates) { pool += DoubleArray(dimensions) { random.nextDouble() } }
        repeat(32) { pool += DoubleArray(dimensions) { (champion[it] + 0.1 * random.nextGaussian()).coerceIn(0.0, 1.0) } }
        val challenger = pool.filter { dist(it, champion) > 0.02 }
            .maxBy { val p = utilityUnit(it); p.mean + exploration * p.std }
        return toNamed(champion) to toNamed(challenger)
    }

    // --- Laplace approximation ---------------------------------------------------------------

    private class Laplace(val mode: DoubleArray, val alpha: DoubleArray, val m: Array<DoubleArray>, val iterations: Int)

    private fun utilityUnit(u: DoubleArray): GpPrediction {
        val prior = kernel.covariance(u, u)
        val f = laplace() ?: return GpPrediction(0.0, prior)
        val ks = DoubleArray(points.size) { kernel.covariance(points[it], u) }
        val mean = gpDot(ks, f.alpha)
        var reduce = 0.0
        for (i in ks.indices) { var s = 0.0; for (j in ks.indices) s += f.m[i][j] * ks[j]; reduce += ks[i] * s }
        return GpPrediction(mean, max(0.0, prior - reduce))
    }

    private fun laplace(): Laplace? {
        fitted?.let { return it }
        if (pairs.isEmpty()) return null
        val n = points.size
        val k = Array(n) { i -> DoubleArray(n) { j -> kernel.covariance(points[i], points[j]) } }
        val chol = gpFactorTraining(Array(n) { k[it].copyOf() }, GpNumerics(relativeJitter = 1e-10, maxRelativeJitter = 1e-4)).first
        val s = sqrt(2.0) * noise
        fun objective(f: DoubleArray): Double {
            var ll = 0.0
            for (p in pairs) ll += gpLogNormalCdf((f[p[0]] - f[p[1]]) / s)
            return ll - 0.5 * gpDot(f, gpSolve(chol, f))
        }
        var f = DoubleArray(n)
        var psi = objective(f)
        var w = Array(n) { DoubleArray(n) }
        var iterations = 0
        while (iterations < 100) {
            iterations++
            val g = DoubleArray(n)
            w = Array(n) { DoubleArray(n) }
            for (p in pairs) {
                val a = p[0]; val b = p[1]
                val z = (f[a] - f[b]) / s
                val r = gpInverseMills(z)
                g[a] += r / s; g[b] -= r / s
                val c = r * (z + r) / (s * s)
                w[a][a] += c; w[b][b] += c; w[a][b] -= c; w[b][a] -= c
            }
            // Newton: f ← K (I + W K)⁻¹ (W f + g).
            val wk = gpMatMul(w, k)
            for (i in 0 until n) wk[i][i] += 1.0
            val rhs = DoubleArray(n) { i -> var t = g[i]; for (j in 0 until n) t += w[i][j] * f[j]; t }
            val target = gpMatVec(k, gpLuSolve(wk, rhs))
            var step = 1.0
            var next: DoubleArray
            var nextPsi: Double
            while (true) {
                next = DoubleArray(n) { f[it] + step * (target[it] - f[it]) }
                nextPsi = objective(next)
                if (nextPsi >= psi - 1e-12 || step < 1e-6) break
                step *= 0.5
            }
            val change = (0 until n).maxOf { abs(next[it] - f[it]) }
            f = next; psi = nextPsi
            if (change < 1e-9) break
        }
        // Recompute the curvature at the mode for the predictive covariance.
        w = Array(n) { DoubleArray(n) }
        for (p in pairs) {
            val a = p[0]; val b = p[1]
            val z = (f[a] - f[b]) / s
            val r = gpInverseMills(z)
            val c = r * (z + r) / (s * s)
            w[a][a] += c; w[b][b] += c; w[a][b] -= c; w[b][a] -= c
        }
        // (K + W⁻¹)⁻¹ = W (I + K W)⁻¹, which needs no inverse of the (usually singular) W.
        val ikw = gpMatMul(k, w).also { for (i in 0 until n) it[i][i] += 1.0 }
        val inv = gpLuInverse(ikw)
        val m = gpMatMul(w, inv)
        return Laplace(f, gpSolve(chol, f), m, iterations).also { fitted = it }
    }

    private fun index(u: DoubleArray): Int {
        val i = points.indexOfFirst { dist(it, u) < 1e-9 }
        if (i >= 0) return i
        points += u
        return points.size - 1
    }

    private fun toUnit(p: Map<String, Double>) = DoubleArray(dimensions) { parameters[it].toUnit(p[parameters[it].name] ?: error("Missing parameter '${parameters[it].name}'")) }
    private fun toNamed(u: DoubleArray): Map<String, Double> =
        LinkedHashMap<String, Double>().also { m -> parameters.forEachIndexed { i, r -> m[r.name] = r.fromUnit(u[i]) } }
    private fun dist(a: DoubleArray, b: DoubleArray): Double { var s = 0.0; for (i in a.indices) s += (a[i] - b[i]).pow(2); return sqrt(s) }
}

internal fun gpLogNormalCdf(z: Double): Double =
    if (z > -8.0) ln(gpNormalCdf(z)) else -0.5 * z * z - ln(-z) - 0.5 * ln(2 * PI) + ln(1.0 - 1.0 / (z * z))

private fun gpMatMul(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
    val n = a.size; val m = b[0].size; val inner = b.size
    return Array(n) { i -> DoubleArray(m).also { row -> for (k in 0 until inner) { val v = a[i][k]; if (v != 0.0) for (j in 0 until m) row[j] += v * b[k][j] } } }
}

private fun gpMatVec(a: Array<DoubleArray>, x: DoubleArray) = DoubleArray(a.size) { gpDot(a[it], x) }

/** Gaussian elimination with partial pivoting; [a] is general (not symmetric). */
private fun gpLuSolve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
    val n = b.size
    val m = Array(n) { a[it].copyOf() + b[it] }
    for (c in 0 until n) {
        val p = (c until n).maxBy { abs(m[it][c]) }
        val t = m[c]; m[c] = m[p]; m[p] = t
        require(abs(m[c][c]) > 1e-300) { "Singular system in preference fit" }
        for (r in c + 1 until n) {
            val f = m[r][c] / m[c][c]
            if (f != 0.0) for (j in c..n) m[r][j] -= f * m[c][j]
        }
    }
    val x = DoubleArray(n)
    for (r in n - 1 downTo 0) { var s = m[r][n]; for (j in r + 1 until n) s -= m[r][j] * x[j]; x[r] = s / m[r][r] }
    return x
}

private fun gpLuInverse(a: Array<DoubleArray>): Array<DoubleArray> {
    val n = a.size
    val cols = Array(n) { j -> gpLuSolve(a, DoubleArray(n) { if (it == j) 1.0 else 0.0 }) }
    return Array(n) { i -> DoubleArray(n) { j -> cols[j][i] } }
}
