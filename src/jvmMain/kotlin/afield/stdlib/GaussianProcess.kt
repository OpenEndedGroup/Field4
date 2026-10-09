@file:afield.CompletionCategory("Math")
package afield.stdlib

import java.util.Random
import kotlin.math.*

/** A covariance function. Implementations and mean functions must be pure and thread safe. */
fun interface GpKernel {
    fun covariance(a: DoubleArray, b: DoubleArray): Double
    operator fun plus(other: GpKernel): GpKernel = GpKernel { a, b -> covariance(a, b) + other.covariance(a, b) }
    operator fun times(other: GpKernel): GpKernel = GpKernel { a, b -> covariance(a, b) * other.covariance(a, b) }
    operator fun times(variance: Double): GpKernel {
        gpNonnegative(variance, "kernel multiplier")
        return GpKernel { a, b -> variance * covariance(a, b) }
    }
}

/** Covariances in output-squared units. Length scales are in input units, one or one per axis. */
object GpKernels {
    private fun radial(scales: DoubleArray, variance: Double, shape: (Double) -> Double): GpKernel {
        require(scales.isNotEmpty() && scales.all { it.isFinite() && it > 0 }) { "Positive finite length scales required" }
        gpNonnegative(variance, "kernel variance")
        val ls = scales.copyOf()
        return GpKernel { a, b ->
            require(a.size == b.size && (ls.size == 1 || ls.size == a.size)) { "Kernel/input dimension mismatch" }
            var d2 = 0.0
            for (i in a.indices) { val d = (a[i] - b[i]) / ls[if (ls.size == 1) 0 else i]; d2 += d * d }
            if (d2.isInfinite()) 0.0 else variance * shape(d2)
        }
    }
    /** Squared exponential: infinitely mean-square differentiable. */
    fun rbf(lengthScale: Double = 1.0, variance: Double = 1.0): GpKernel = rbf(doubleArrayOf(lengthScale), variance)
    fun rbf(lengthScales: DoubleArray, variance: Double = 1.0): GpKernel = radial(lengthScales, variance) { exp(-0.5 * it) }
    /** Matérn ν = 1/2, 3/2 or 5/2: respectively rough, once or twice mean-square differentiable. */
    fun matern(nu: Double = 1.5, lengthScale: Double = 1.0, variance: Double = 1.0): GpKernel =
        matern(nu, doubleArrayOf(lengthScale), variance)
    fun matern(nu: Double, lengthScales: DoubleArray, variance: Double = 1.0): GpKernel {
        require(nu == 0.5 || nu == 1.5 || nu == 2.5) { "Matérn nu must be 0.5, 1.5 or 2.5" }
        return radial(lengthScales, variance) { d2 ->
            val r = sqrt(2 * nu * d2)
            if (r.isInfinite()) 0.0 else when (nu) { 0.5 -> exp(-r); 1.5 -> (1 + r) * exp(-r); else -> (1 + r + r * r / 3) * exp(-r) }
        }
    }
    /** A scale mixture of RBF kernels; alpha controls the mixture. */
    fun rationalQuadratic(lengthScales: DoubleArray = doubleArrayOf(1.0), alpha: Double = 1.0, variance: Double = 1.0): GpKernel {
        require(alpha.isFinite() && alpha > 0)
        return radial(lengthScales, variance) { (1 + it / (2 * alpha)).pow(-alpha) }
    }
    /** Exactly periodic along every axis. Multiply by an RBF to let cycles evolve. */
    fun periodic(period: Double = 1.0, lengthScale: Double = 1.0, variance: Double = 1.0): GpKernel {
        require(period.isFinite() && period > 0 && lengthScale.isFinite() && lengthScale > 0)
        gpNonnegative(variance, "kernel variance")
        return GpKernel { a, b ->
            require(a.size == b.size)
            var d = 0.0
            for (i in a.indices) { val s = sin(PI * ((a[i] - b[i]) / period)); d += s * s }
            variance * exp(-2 * d / (lengthScale * lengthScale))
        }
    }
    fun constant(variance: Double = 1.0): GpKernel {
        gpNonnegative(variance, "kernel variance")
        return GpKernel { _, _ -> variance }
    }
    /** Random linear functions through [origin], plus a separately composable constant kernel. */
    fun linear(variance: Double = 1.0, origin: DoubleArray = doubleArrayOf(0.0)): GpKernel {
        gpNonnegative(variance, "kernel variance")
        require(origin.isNotEmpty() && origin.all { it.isFinite() })
        val o = origin.copyOf()
        return GpKernel { a, b ->
            require(a.size == b.size && (o.size == 1 || o.size == a.size))
            var d = 0.0
            for (i in a.indices) { val c = o[if (o.size == 1) 0 else i]; d += (a[i] - c) * (b[i] - c) }
            variance * d
        }
    }
    /** Pull a covariance back through a deterministic input map (e.g. circular coordinates). */
    fun warp(kernel: GpKernel, transform: (DoubleArray) -> DoubleArray): GpKernel =
        GpKernel { a, b -> kernel.covariance(transform(a), transform(b)) }
}

/** Numerical regularisation is separate from observation noise, and its actual value is reported. */
data class GpNumerics(
    val relativeJitter: Double = 1e-12,
    val maxRelativeJitter: Double = 1e-6,
    val covarianceTolerance: Double = 1e-10,
    val samplingTolerance: Double = 1e-12,
    val maxTrainingSize: Int = 4096,
    val maxJointSize: Int = 4096
) {
    init {
        require(relativeJitter.isFinite() && relativeJitter > 0)
        require(maxRelativeJitter.isFinite() && maxRelativeJitter >= relativeJitter)
        require(covarianceTolerance.isFinite() && covarianceTolerance > 0 && covarianceTolerance < 1)
        require(samplingTolerance.isFinite() && samplingTolerance > 0 && samplingTolerance < 1)
        require(maxTrainingSize > 0 && maxJointSize > 0)
    }
}

/** Latent function uncertainty, excluding any future measurement noise. */
data class GpPrediction(val mean: Double, val variance: Double) {
    init { require(mean.isFinite() && variance.isFinite() && variance >= 0) }
    val std: Double get() = sqrt(variance)
    fun interval(standardDeviations: Double = 1.96): ClosedFloatingPointRange<Double> {
        gpNonnegative(standardDeviations, "interval width")
        return (mean - standardDeviations * std)..(mean + standardDeviations * std)
    }
}

/** Predictive distribution of each held-out observation (including its own noise). */
data class GpLeaveOneOut(val mean: DoubleArray, val variance: DoubleArray, val standardizedResidual: DoubleArray)

/**
 * Exact dense GP regression in any finite input dimension, with 1D/2D/3D conveniences below.
 * Fit once; the training Cholesky is reused by predictions. No matrix inverse is formed.
 * Samples come from [posterior], whose full covariance retains correlations between queries.
 * Inputs and outputs are copied. Kernel/mean closures must not change after fitting.
 */
class GaussianProcess private constructor(
    val dimensions: Int,
    val kernel: GpKernel,
    private val priorMean: (DoubleArray) -> Double,
    private val x: Array<DoubleArray>,
    private val y: DoubleArray,
    private val noise: DoubleArray,
    val numerics: GpNumerics
) {
    val trainingSize: Int get() = x.size
    private val lower: Array<DoubleArray>
    private val alpha: DoubleArray
    /** Added training-diagonal variance in output-squared units; zero when no repair was needed. */
    val jitterUsed: Double
    /** Log p(observations | kernel, mean, noise), including any reported numerical jitter. */
    val logMarginalLikelihood: Double

    init {
        val centered = DoubleArray(y.size) { y[it] - checkedMean(x[it]) }
        require(centered.all { it.isFinite() }) { "Non-finite centered observations" }
        val k = Array(x.size) { DoubleArray(x.size) }
        for (i in x.indices) {
            for (j in 0..i) { val c = checkedKernel(x[i], x[j]); k[i][j] = c; k[j][i] = c }
            require(k[i][i] >= 0) { "Negative prior variance at observation $i" }
            k[i][i] += noise[i] * noise[i]
            require(k[i][i].isFinite()) { "Observation noise variance overflow" }
        }
        val factor = gpFactorTraining(k, numerics)
        lower = factor.first
        jitterUsed = factor.second
        if (jitterUsed > 0.0 && noise.all { it == 0.0 }) {
            val support = gpPsdFactor(k, numerics.samplingTolerance, 0.0)
            val z = DoubleArray(support.rank)
            for (j in z.indices) {
                val pivot = support.pivots[j]
                var value = centered[pivot]
                for (h in 0 until j) value -= support.rows[pivot][h] * z[h]
                z[j] = value / support.rows[pivot][j]
            }
            val scale = k.indices.maxOf { k[it][it] }
            val tolerance = sqrt(numerics.covarianceTolerance * scale)
            require(centered.indices.all { i ->
                var reconstructed = 0.0
                for (j in z.indices) reconstructed += support.rows[i][j] * z[j]
                abs(centered[i] - reconstructed) <= tolerance
            }) { "Noiseless observations are inconsistent with the numerically resolved kernel support; change the kernel, rescale, or supply measurement noise" }
        }
        alpha = gpSolve(lower, centered)
        logMarginalLikelihood = -0.5 * gpDot(centered, alpha) - lower.indices.sumOf { ln(lower[it][it]) } - y.size * 0.5 * ln(2 * PI)
        require(logMarginalLikelihood.isFinite()) { "Non-finite marginal likelihood; rescale inputs/outputs" }
    }

    internal fun observationData(): Triple<Array<DoubleArray>, DoubleArray, DoubleArray> =
        Triple(Array(x.size) { x[it].copyOf() }, y.copyOf(), noise.copyOf())
    internal fun solveResidual(residual: DoubleArray): DoubleArray { require(residual.size == trainingSize); return gpSolve(lower, residual) }

    private fun checkedMean(point: DoubleArray): Double = priorMean(point).also { require(it.isFinite()) { "Non-finite mean" } }
    private fun checkedKernel(a: DoubleArray, b: DoubleArray): Double {
        val ab = kernel.covariance(a, b)
        val ba = kernel.covariance(b, a)
        require(ab.isFinite() && ba.isFinite()) { "Non-finite kernel covariance" }
        require(abs(ab - ba) <= numerics.covarianceTolerance * max(max(abs(ab), abs(ba)), 1e-300)) { "Kernel must be symmetric" }
        return ab * 0.5 + ba * 0.5
    }
    private fun point(p: DoubleArray): DoubleArray {
        require(p.size == dimensions && p.all { it.isFinite() }) { "Expected $dimensions finite input coordinates" }
        return p.copyOf()
    }
    private fun cross(p: DoubleArray): DoubleArray = DoubleArray(x.size) { checkedKernel(x[it], p) }
    private fun checkedVariance(v: Double, scale: Double): Double {
        require(v.isFinite() && v >= -numerics.covarianceTolerance * max(scale, 1e-300)) { "Negative posterior variance $v; kernel is indefinite or fit is ill-conditioned" }
        return max(0.0, v)
    }
    /** A point prediction; pass one, two or three coordinates (or *aDoubleArray). */
    fun predict(vararg coordinates: Double): GpPrediction {
        val p = point(coordinates)
        val k = cross(p)
        val v = gpForward(lower, k)
        val prior = checkedKernel(p, p)
        return GpPrediction(checkedMean(p) + gpDot(k, alpha), checkedVariance(prior - gpDot(v, v), abs(prior)))
    }
    operator fun invoke(vararg coordinates: Double): Double = mean(*coordinates)
    /** Mean-only prediction avoids a triangular solve, O(n) kernel work. */
    fun mean(vararg coordinates: Double): Double { val p = point(coordinates); return checkedMean(p) + gpDot(cross(p), alpha) }
    fun variance(vararg coordinates: Double): Double = predict(*coordinates).variance
    /** Future measurement uncertainty adds noiseStd²; fitting noise never appears here implicitly. */
    fun predictObservation(point: DoubleArray, noiseStd: Double): GpPrediction {
        gpNonnegative(noiseStd, "prediction noise standard deviation")
        val p = predict(*point)
        val variance = p.variance + noiseStd * noiseStd
        require(variance.isFinite())
        return GpPrediction(p.mean, variance)
    }
    /** Cross-covariance between two latent values, including off-diagonal posterior correlations. */
    fun covariance(a: DoubleArray, b: DoubleArray): Double {
        val pa = point(a); val pb = point(b)
        return checkedKernel(pa, pb) - gpDot(gpForward(lower, cross(pa)), gpForward(lower, cross(pb)))
    }
    /** Marginals in O(m n²) time and O(n) working storage, without an m×m query covariance. */
    fun marginals(points: Array<DoubleArray>): List<GpPrediction> = points.map { predict(*it) }
    fun marginals(xs: DoubleArray): List<GpPrediction> { require(dimensions == 1); return xs.map { predict(it) } }
    /** Full latent joint distribution. Query order is preserved; repeated points are allowed. */
    fun posterior(points: Array<DoubleArray>): GpJoint {
        require(points.size <= numerics.maxJointSize) { "Joint size ${points.size} exceeds ${numerics.maxJointSize}; use marginals for a large variance map" }
        val q = points.map { point(it) }.toTypedArray()
        val m = DoubleArray(q.size)
        val v = Array(q.size) { i -> val k = cross(q[i]); m[i] = checkedMean(q[i]) + gpDot(k, alpha); gpForward(lower, k) }
        val cov = Array(q.size) { DoubleArray(q.size) }
        var referenceScale = 0.0
        for (i in q.indices) for (j in 0..i) {
            val prior = checkedKernel(q[i], q[j])
            val c = prior - gpDot(v[i], v[j])
            cov[i][j] = if (i == j) checkedVariance(c, abs(prior)) else c
            cov[j][i] = cov[i][j]
            if (i == j) referenceScale = max(referenceScale, abs(prior))
        }
        return GpJoint(m, cov, numerics.samplingTolerance, numerics.covarianceTolerance * referenceScale)
    }
    fun posterior(xs: DoubleArray): GpJoint { require(dimensions == 1); return posterior(Array(xs.size) { doubleArrayOf(xs[it]) }) }
    /** Add observations and refit, preserving the original mean/kernel/noise contract. */
    fun condition(points: Array<DoubleArray>, values: DoubleArray, noiseStd: DoubleArray = DoubleArray(values.size)): GaussianProcess =
        fit(x + points, y + values, kernel, noise + noiseStd, priorMean, numerics, dimensions)
    /** Exact leave-one-out identities from the Cholesky; does not refit n times. */
    fun leaveOneOut(): GpLeaveOneOut {
        val means = DoubleArray(x.size); val vars = DoubleArray(x.size); val residuals = DoubleArray(x.size)
        for (i in x.indices) {
            val unit = DoubleArray(x.size); unit[i] = 1.0
            val v = gpForward(lower, unit)
            val precision = gpDot(v, v)
            vars[i] = 1 / precision; means[i] = y[i] - alpha[i] / precision
            residuals[i] = (y[i] - means[i]) / sqrt(vars[i])
        }
        return GpLeaveOneOut(means, vars, residuals)
    }
    companion object {
        /** Empty observations are a prior. Noise is always a standard deviation, in output units. */
        fun fit(
            points: Array<DoubleArray>, values: DoubleArray,
            kernel: GpKernel = GpKernels.rbf(), noiseStd: DoubleArray = DoubleArray(values.size),
            mean: (DoubleArray) -> Double = { 0.0 }, numerics: GpNumerics = GpNumerics(),
            dimensions: Int = points.firstOrNull()?.size ?: 1
        ): GaussianProcess {
            require(dimensions > 0 && points.size == values.size && values.size == noiseStd.size)
            require(points.size <= numerics.maxTrainingSize) { "Training size exceeds ${numerics.maxTrainingSize}" }
            require(values.all { it.isFinite() }) { "Observations must be finite" }
            require(points.all { it.size == dimensions && it.all { v -> v.isFinite() } }) { "Input dimension mismatch or non-finite coordinates" }
            noiseStd.forEach { gpNonnegative(it, "observation noise standard deviation") }
            // Redundant exact observations are harmless; incompatible exact constraints are not.
            val exact = HashMap<List<Double>, Double>()
            val keep = points.indices.filter { i ->
                if (noiseStd[i] != 0.0) true else {
                    val key = points[i].map { if (it == 0.0) 0.0 else it }
                    val previous = exact.putIfAbsent(key, values[i])
                    require(previous == null || previous == values[i]) { "Conflicting noiseless observations at ${key}" }
                    previous == null
                }
            }
            return GaussianProcess(dimensions, kernel, mean, Array(keep.size) { points[keep[it]].copyOf() },
                DoubleArray(keep.size) { values[keep[it]] }, DoubleArray(keep.size) { noiseStd[keep[it]] }, numerics)
        }
        fun prior(dimensions: Int = 1, kernel: GpKernel = GpKernels.rbf(), mean: (DoubleArray) -> Double = { 0.0 }, numerics: GpNumerics = GpNumerics()): GaussianProcess =
            fit(emptyArray(), doubleArrayOf(), kernel, mean = mean, numerics = numerics, dimensions = dimensions)
    }
}

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
private fun gpFactorTraining(k: Array<DoubleArray>, options: GpNumerics): Pair<Array<DoubleArray>, Double> {
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

/** 1D observations may be unsorted, and repeated noisy observations remain independent evidence. */
fun gp1D(xs: DoubleArray, values: DoubleArray, kernel: GpKernel = GpKernels.rbf(), noiseStd: Double = 0.0,
         mean: (Double) -> Double = { 0.0 }, numerics: GpNumerics = GpNumerics()): GaussianProcess =
    gp1D(xs, values, DoubleArray(values.size) { noiseStd }, kernel, mean, numerics)
fun gp1D(xs: DoubleArray, values: DoubleArray, noiseStd: DoubleArray, kernel: GpKernel = GpKernels.rbf(),
         mean: (Double) -> Double = { 0.0 }, numerics: GpNumerics = GpNumerics()): GaussianProcess =
    GaussianProcess.fit(Array(xs.size) { doubleArrayOf(xs[it]) }, values, kernel, noiseStd, { mean(it[0]) }, numerics, 1)

/** Avalanche user seeds before Java Random's 48-bit state to decorrelate nearby integer seeds. */
internal fun gpRandom(seed: Long): Random {
    var z = seed - 7046029254386353131L
    z = (z xor (z ushr 30)) * -4658895280553007687L
    z = (z xor (z ushr 27)) * -7723592293110705685L
    return Random(z xor (z ushr 31))
}
