@file:afield.CompletionCategory("Math")
package afield.math.gp

import java.util.Random
import kotlin.math.*

/** A finite joint Gaussian. Defensive getters keep its cached sampling factor consistent. */
class GpJoint internal constructor(
    private val mu: DoubleArray, private val cov: Array<DoubleArray>,
    private val relativeTolerance: Double, private val absoluteTolerance: Double
) {
    init {
        require(cov.size == mu.size && mu.all { it.isFinite() })
        require(cov.all { row -> row.size == mu.size && row.all { it.isFinite() } })
        val scale = cov.indices.maxOfOrNull { abs(cov[it][it]) } ?: 0.0
        val tolerance = max(relativeTolerance * scale, absoluteTolerance)
        for (i in mu.indices) {
            require(cov[i][i] >= -tolerance) { "Negative joint variance" }
            if (cov[i][i] < 0) cov[i][i] = 0.0
            for (j in 0 until i) {
                require(abs(cov[i][j] - cov[j][i]) <= tolerance) { "Asymmetric joint covariance" }
                val v = cov[i][j] * 0.5 + cov[j][i] * 0.5
                cov[i][j] = v; cov[j][i] = v
            }
        }
    }
    val size: Int get() = mu.size
    val mean: DoubleArray get() = mu.copyOf()
    val variance: DoubleArray get() = DoubleArray(size) { cov[it][it] }
    val covariance: Array<DoubleArray> get() = Array(size) { cov[it].copyOf() }
    fun covarianceAt(i: Int, j: Int): Double = cov[i][j]
    /** Pivoted, rank-revealing factor: no independent jitter is added to samples. */
    private val factor by lazy { gpPsdFactor(cov, relativeTolerance, absoluteTolerance) }
    val samplingRank: Int get() = factor.rank
    /** Maximum unrepresented marginal variance (numerical rank truncation only). */
    val samplingResidualVariance: Double get() = factor.residual
    fun sample(seed: Long): DoubleArray = sample(gpRandom(seed))
    fun sample(random: Random): DoubleArray {
        val f = factor
        val z = DoubleArray(f.rank) { random.nextGaussian() }
        return DoubleArray(size) { i -> var value = mu[i]; for (j in z.indices) value += f.rows[i][j] * z[j]; value }
    }
    /**
     * A draw whose departure from the mean is scaled by [spread]: 0 is the mean, 1 an honest
     * sample, above 1 an exaggerated one. Same seed, same direction of departure.
     */
    fun sample(seed: Long, spread: Double): DoubleArray {
        require(spread.isFinite())
        val draw = sample(seed)
        return DoubleArray(size) { mu[it] + spread * (draw[it] - mu[it]) }
    }
    /** One factorisation, then independent whole-function draws. */
    fun samples(count: Int, seed: Long): Array<DoubleArray> {
        require(count >= 0)
        val rng = gpRandom(seed)
        return Array(count) { sample(rng) }
    }
    /** Reuse standard normal coefficients to couple draws. Pivot/rank changes can still cause discontinuities. */
    fun fromStandardNormal(z: DoubleArray): DoubleArray {
        val f = factor
        require(z.size == f.rank && z.all { it.isFinite() })
        return DoubleArray(size) { i -> var value = mu[i]; for (j in z.indices) value += f.rows[i][j] * z[j]; value }
    }
    /** Distribution of A f: differences, weighted averages, finite-difference slopes, integrals. */
    fun linearTransform(weights: Array<DoubleArray>): GpJoint {
        require(weights.all { it.size == size && it.all { w -> w.isFinite() } })
        val mean = DoubleArray(weights.size) { gpDot(weights[it], mu) }
        val ac = Array(weights.size) { r -> DoubleArray(size) { j -> var s = 0.0; for (i in 0 until size) s += weights[r][i] * cov[i][j]; s } }
        val c = Array(weights.size) { r -> DoubleArray(weights.size) { s -> gpDot(ac[r], weights[s]) } }
        return GpJoint(mean, c, relativeTolerance, absoluteTolerance * weights.maxOfOrNull { it.sumOf { w -> abs(w) }.pow(2) }.orZero())
    }
    /** Distribution of noisy future observations, with independent measurement errors. */
    fun withObservationNoise(noiseStd: DoubleArray): GpJoint {
        require(noiseStd.size == size)
        val c = covariance
        for (i in 0 until size) { gpNonnegative(noiseStd[i], "prediction noise"); c[i][i] += noiseStd[i] * noiseStd[i]; require(c[i][i].isFinite()) }
        return GpJoint(mu.copyOf(), c, relativeTolerance, absoluteTolerance)
    }
}

private fun Double?.orZero(): Double = this ?: 0.0
internal fun gpNonnegative(value: Double, name: String) { require(value.isFinite() && value >= 0) { "$name must be finite and nonnegative" } }
internal fun gpDot(a: DoubleArray, b: DoubleArray): Double { var s = 0.0; for (i in a.indices) s += a[i] * b[i]; return s }
internal fun gpForward(l: Array<DoubleArray>, b: DoubleArray): DoubleArray = DoubleArray(b.size).also { out ->
    for (i in b.indices) { var s = b[i]; for (j in 0 until i) s -= l[i][j] * out[j]; out[i] = s / l[i][i] }
}
internal fun gpSolve(l: Array<DoubleArray>, b: DoubleArray): DoubleArray {
    val out = gpForward(l, b)
    for (i in out.indices.reversed()) { var s = out[i]; for (j in i + 1 until out.size) s -= l[j][i] * out[j]; out[i] = s / l[i][i] }
    return out
}
internal fun gpFactorTraining(k: Array<DoubleArray>, options: GpNumerics): Pair<Array<DoubleArray>, Double> {
    if (k.isEmpty()) return emptyArray<DoubleArray>() to 0.0
    val scale = k.indices.maxOf { k[it][it] }
    require(scale > 0 && scale.isFinite()) { "Training covariance has no positive scale; add measurement noise or use a nonzero kernel" }
    var jitter = 0.0
    while (true) {
        val l = Array(k.size) { DoubleArray(k.size) }
        var ok = true
        outer@ for (i in k.indices) for (j in 0..i) {
            var s = k[i][j] + if (i == j) jitter else 0.0
            for (p in 0 until j) s -= l[i][p] * l[j][p]
            if (!s.isFinite() || (i == j && s <= 0)) { ok = false; break@outer }
            l[i][j] = if (i == j) sqrt(s) else s / l[j][j]
        }
        if (ok) return l to jitter
        val next = if (jitter == 0.0) scale * options.relativeJitter else jitter * 10
        require(next > jitter && next <= scale * options.maxRelativeJitter * (1 + 1e-12)) {
            "GP Cholesky failed: non-positive covariance even at jitter $jitter. Check kernel, scale and observations."
        }
        jitter = next
    }
}
internal data class GpPsdFactor(val rows: Array<DoubleArray>, val rank: Int, val residual: Double, val pivots: IntArray)
/** Complete diagonal pivoting handles duplicate queries and exactly pinned/low-rank distributions. */
internal fun gpPsdFactor(c: Array<DoubleArray>, relativeTolerance: Double, absoluteTolerance: Double): GpPsdFactor {
    val n = c.size
    val l = Array(n) { DoubleArray(n) }
    val residual = DoubleArray(n) { c[it][it] }
    val used = BooleanArray(n)
    val pivots = IntArray(n)
    val scale = residual.maxOrNull() ?: 0.0
    val tolerance = max(relativeTolerance * max(scale, 0.0), absoluteTolerance)
    require(residual.all { it.isFinite() && it >= -tolerance }) { "Indefinite joint covariance" }
    var rank = 0
    while (rank < n) {
        var pivot = -1
        for (i in 0 until n) if (!used[i] && (pivot < 0 || residual[i] > residual[pivot])) pivot = i
        if (pivot < 0 || residual[pivot] <= tolerance) break
        used[pivot] = true
        pivots[rank] = pivot
        val root = sqrt(residual[pivot]); l[pivot][rank] = root
        for (i in 0 until n) if (!used[i]) {
            var v = c[i][pivot]
            for (j in 0 until rank) v -= l[i][j] * l[pivot][j]
            l[i][rank] = v / root
            residual[i] -= l[i][rank] * l[i][rank]
            require(residual[i].isFinite() && residual[i] >= -tolerance) { "Indefinite joint covariance after pivot $rank" }
        }
        residual[pivot] = 0.0
        rank++
    }
    // A matrix can have zero diagonal but a nonzero off-diagonal: do not bless that as PSD.
    for (i in 0 until n) if (!used[i]) for (j in 0..i) if (!used[j]) {
        var r = c[i][j]
        for (p in 0 until rank) r -= l[i][p] * l[j][p]
        require(abs(r) <= sqrt(max(0.0, residual[i])) * sqrt(max(0.0, residual[j])) + tolerance) { "Indefinite residual covariance" }
    }
    return GpPsdFactor(l, rank, max(0.0, residual.maxOrNull() ?: 0.0), pivots.copyOf(rank))
}


/** Avalanche user seeds before Java Random's 48-bit state to decorrelate nearby integer seeds. */
internal fun gpRandom(seed: Long): Random {
    var z = seed - 7046029254386353131L
    z = (z xor (z ushr 30)) * -4658895280553007687L
    z = (z xor (z ushr 27)) * -7723592293110705685L
    return Random(z xor (z ushr 31))
}
