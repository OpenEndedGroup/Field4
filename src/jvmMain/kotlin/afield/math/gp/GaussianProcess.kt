@file:afield.runtime.completion.CompletionCategory("Math")
package afield.math.gp

// In memory of David J. C. MacKay.

import kotlin.math.*

/**
 * Exact Gaussian-process regression: fit scalar observations (values and, optionally, slopes) and query the posterior mean, variance and joint covariance.
 *
 * [GaussianProcess] factors the training covariance once and reuses that Cholesky factor for every
 * prediction. [GpPrediction] is a single mean and variance with an [interval][GpPrediction.interval];
 * [GpFunctional] names a value or a derivative to query jointly; [GpDerivativeObservation] trains on a
 * measured slope. [GpNumerics] holds the jitter and size limits, and the jitter actually used is
 * reported back as `jitterUsed`. `logMarginalLikelihood` is the evidence that the fitters in
 * `GpFit.kt` maximise.
 */

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

/** The posterior gradient at one point: its mean vector and full covariance between axes. */
class GpGradient internal constructor(private val mu: DoubleArray, private val cov: Array<DoubleArray>) {
    val mean: DoubleArray get() = mu.copyOf()
    val covariance: Array<DoubleArray> get() = Array(cov.size) { cov[it].copyOf() }
    val variance: DoubleArray get() = DoubleArray(mu.size) { cov[it][it] }
}

/** Predictive distribution of each held-out observation (including its own noise). */
data class GpLeaveOneOut(val mean: DoubleArray, val variance: DoubleArray, val standardizedResidual: DoubleArray)

/**
 * A linear functional of the latent function: its value at [point], or its partial derivative
 * along input [axis] there. Derivatives need a [GpDifferentiableKernel].
 */
class GpFunctional private constructor(point: DoubleArray, val axis: Int?) {
    private val p = point.copyOf()
    val point: DoubleArray get() = p.copyOf()
    internal val raw: DoubleArray get() = p
    internal val code: Int get() = axis ?: -1
    companion object {
        fun value(vararg point: Double) = GpFunctional(point, null)
        fun derivative(axis: Int, vararg point: Double): GpFunctional {
            require(axis in point.indices) { "Derivative axis $axis is outside a ${point.size}D point" }
            return GpFunctional(point, axis)
        }
    }
}

/** A measured slope ∂f/∂x[axis] = [value] at [point]; train on these alongside values. */
class GpDerivativeObservation(point: DoubleArray, val axis: Int, val value: Double, val noiseStd: Double = 0.0) {
    private val p = point.copyOf()
    val point: DoubleArray get() = p.copyOf()
    init {
        require(axis in p.indices && value.isFinite())
        gpNonnegative(noiseStd, "derivative noise standard deviation")
    }
    companion object {
        /** A 1D slope. */
        fun slope(x: Double, value: Double, noiseStd: Double = 0.0) = GpDerivativeObservation(doubleArrayOf(x), 0, value, noiseStd)
    }
}

/**
 * Exact dense GP regression in any finite input dimension, with 1D/2D/3D conveniences below.
 * Fit once; the training Cholesky is reused by predictions. No matrix inverse is formed.
 * Samples come from [posterior], whose full covariance retains correlations between queries.
 * Training data may include slopes ([GpDerivativeObservation]); values and slopes can be queried
 * jointly ([posterior] of [GpFunctional]s, [gradient], [derivative]).
 * Inputs and outputs are copied. Kernel/mean closures must not change after fitting. The slope of
 * a mean function is taken by central difference; the default zero mean has none.
 */
class GaussianProcess private constructor(
    val dimensions: Int,
    val kernel: GpKernel,
    private val priorMean: (DoubleArray) -> Double,
    private val x: Array<DoubleArray>,
    private val axes: IntArray,
    private val y: DoubleArray,
    private val noise: DoubleArray,
    val numerics: GpNumerics
) {
    /** Training values and slopes together. */
    val trainingSize: Int get() = x.size
    private val lower: Array<DoubleArray>
    private val alpha: DoubleArray
    /** Added training-diagonal variance in output-squared units; zero when no repair was needed. */
    val jitterUsed: Double
    /** Log p(observations | kernel, mean, noise), including any reported numerical jitter. */
    val logMarginalLikelihood: Double

    init {
        if (axes.any { it >= 0 }) differentiable()
        val centered = DoubleArray(y.size) { y[it] - meanOf(x[it], axes[it]) }
        require(centered.all { it.isFinite() }) { "Non-finite centered observations" }
        val k = Array(x.size) { DoubleArray(x.size) }
        for (i in x.indices) {
            for (j in 0..i) { val c = cov(x[i], axes[i], x[j], axes[j]); k[i][j] = c; k[j][i] = c }
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

    internal val hasDerivativeObservations: Boolean get() = axes.any { it >= 0 }
    internal fun observationData(): Triple<Array<DoubleArray>, DoubleArray, DoubleArray> =
        Triple(Array(x.size) { x[it].copyOf() }, y.copyOf(), noise.copyOf())
    internal fun solveResidual(residual: DoubleArray): DoubleArray { require(residual.size == trainingSize); return gpSolve(lower, residual) }

    private fun differentiable(): GpDifferentiableKernel = kernel as? GpDifferentiableKernel
        ?: throw IllegalArgumentException("Derivatives need a GpDifferentiableKernel (rbf, matern 1.5/2.5, rationalQuadratic, periodic, linear, constant and their sums/products)")
    private fun checkedMean(point: DoubleArray): Double = priorMean(point).also { require(it.isFinite()) { "Non-finite mean" } }
    private fun meanOf(point: DoubleArray, axis: Int): Double {
        if (axis < 0) return checkedMean(point)
        val h = 1e-5 * max(1.0, abs(point[axis]))
        val up = point.copyOf().also { it[axis] += h }
        val down = point.copyOf().also { it[axis] -= h }
        return (checkedMean(up) - checkedMean(down)) / (2 * h)
    }
    private fun checkedKernel(a: DoubleArray, b: DoubleArray): Double {
        val ab = kernel.covariance(a, b)
        val ba = kernel.covariance(b, a)
        require(ab.isFinite() && ba.isFinite()) { "Non-finite kernel covariance" }
        require(abs(ab - ba) <= numerics.covarianceTolerance * max(max(abs(ab), abs(ba)), 1e-300)) { "Kernel must be symmetric" }
        return ab * 0.5 + ba * 0.5
    }
    /** Covariance between two functionals; an axis of −1 is the value itself. */
    private fun cov(a: DoubleArray, ia: Int, b: DoubleArray, ib: Int): Double {
        if (ia < 0 && ib < 0) return checkedKernel(a, b)
        val k = differentiable()
        val c = when {
            ia < 0 -> k.covarianceGradient(a, b, ib)
            ib < 0 -> k.covarianceGradient(b, a, ia)
            else -> k.covarianceHessian(a, b, ia, ib)
        }
        require(c.isFinite()) { "Non-finite kernel derivative" }
        return c
    }
    private fun point(p: DoubleArray): DoubleArray {
        require(p.size == dimensions && p.all { it.isFinite() }) { "Expected $dimensions finite input coordinates" }
        return p.copyOf()
    }
    private fun cross(p: DoubleArray, axis: Int = -1): DoubleArray = DoubleArray(x.size) { cov(x[it], axes[it], p, axis) }
    private fun checkedVariance(v: Double, scale: Double): Double {
        require(v.isFinite() && v >= -numerics.covarianceTolerance * max(scale, 1e-300)) { "Negative posterior variance $v; kernel is indefinite or fit is ill-conditioned" }
        return max(0.0, v)
    }
    private fun predictFunctional(p: DoubleArray, axis: Int): GpPrediction {
        val k = cross(p, axis)
        val v = gpForward(lower, k)
        val prior = cov(p, axis, p, axis)
        return GpPrediction(meanOf(p, axis) + gpDot(k, alpha), checkedVariance(prior - gpDot(v, v), abs(prior)))
    }

    /** A point prediction; pass one, two or three coordinates (or *aDoubleArray). */
    fun predict(vararg coordinates: Double): GpPrediction = predictFunctional(point(coordinates), -1)
    operator fun invoke(vararg coordinates: Double): Double = mean(*coordinates)
    /** Mean-only prediction avoids a triangular solve, O(n) kernel work. */
    fun mean(vararg coordinates: Double): Double { val p = point(coordinates); return checkedMean(p) + gpDot(cross(p), alpha) }
    fun variance(vararg coordinates: Double): Double = predict(*coordinates).variance
    /** The latent slope ∂f/∂x[axis] at a point, with its uncertainty. */
    fun derivative(axis: Int, vararg coordinates: Double): GpPrediction {
        require(axis in 0 until dimensions)
        return predictFunctional(point(coordinates), axis)
    }
    /** The latent gradient at a point, with the covariance between its components. */
    fun gradient(vararg coordinates: Double): GpGradient {
        val p = point(coordinates)
        val joint = posterior(List(dimensions) { GpFunctional.derivative(it, *p) })
        return GpGradient(joint.mean, joint.covariance)
    }
    /** Future measurement uncertainty adds noiseStd²; fitting noise never appears here implicitly. */
    fun predictObservation(point: DoubleArray, noiseStd: Double): GpPrediction {
        gpNonnegative(noiseStd, "prediction noise standard deviation")
        val p = predict(*point)
        val variance = p.variance + noiseStd * noiseStd
        require(variance.isFinite())
        return GpPrediction(p.mean, variance)
    }
    /** Cross-covariance between two latent values, including off-diagonal posterior correlations. */
    fun covariance(a: DoubleArray, b: DoubleArray): Double = covariance(GpFunctional.value(*a), GpFunctional.value(*b))
    /** Posterior covariance between any two functionals (values or slopes). */
    fun covariance(a: GpFunctional, b: GpFunctional): Double {
        val pa = point(a.raw); val pb = point(b.raw)
        return cov(pa, a.code, pb, b.code) - gpDot(gpForward(lower, cross(pa, a.code)), gpForward(lower, cross(pb, b.code)))
    }
    /** Marginals in O(m n²) time and O(n) working storage, without an m×m query covariance. */
    fun marginals(points: Array<DoubleArray>): List<GpPrediction> = points.map { predict(*it) }
    fun marginals(xs: DoubleArray): List<GpPrediction> { require(dimensions == 1); return xs.map { predict(it) } }
    /** Full latent joint distribution. Query order is preserved; repeated points are allowed. */
    fun posterior(points: Array<DoubleArray>): GpJoint = posterior(points.map { GpFunctional.value(*it) })
    fun posterior(xs: DoubleArray): GpJoint { require(dimensions == 1); return posterior(Array(xs.size) { doubleArrayOf(xs[it]) }) }
    /** The joint slope ∂f/∂x[axis] at every point, e.g. a 1D derivative curve with coherent draws. */
    fun derivativePosterior(points: Array<DoubleArray>, axis: Int = 0): GpJoint =
        posterior(points.map { GpFunctional.derivative(axis, *it) })
    fun derivativePosterior(xs: DoubleArray): GpJoint { require(dimensions == 1); return derivativePosterior(Array(xs.size) { doubleArrayOf(xs[it]) }) }
    /** One joint over any mix of values and slopes, in the order given. */
    fun posterior(functionals: List<GpFunctional>): GpJoint {
        require(functionals.size <= numerics.maxJointSize) { "Joint size ${functionals.size} exceeds ${numerics.maxJointSize}; use marginals for a large variance map" }
        val q = functionals.map { point(it.raw) }
        val ax = IntArray(q.size) { functionals[it].code }
        val m = DoubleArray(q.size)
        val v = Array(q.size) { i -> val k = cross(q[i], ax[i]); m[i] = meanOf(q[i], ax[i]) + gpDot(k, alpha); gpForward(lower, k) }
        val c = Array(q.size) { DoubleArray(q.size) }
        var referenceScale = 0.0
        for (i in q.indices) for (j in 0..i) {
            val prior = cov(q[i], ax[i], q[j], ax[j])
            val value = prior - gpDot(v[i], v[j])
            c[i][j] = if (i == j) checkedVariance(value, abs(prior)) else value
            c[j][i] = c[i][j]
            if (i == j) referenceScale = max(referenceScale, abs(prior))
        }
        return GpJoint(m, c, numerics.samplingTolerance, numerics.covarianceTolerance * referenceScale)
    }
    /** Add observations and refit, preserving the original mean/kernel/noise contract and any slopes. */
    fun condition(points: Array<DoubleArray>, values: DoubleArray, noiseStd: DoubleArray = DoubleArray(values.size),
                  derivatives: List<GpDerivativeObservation> = emptyList()): GaussianProcess {
        val v = axes.indices.filter { axes[it] < 0 }
        val d = axes.indices.filter { axes[it] >= 0 }
        return fit(Array(v.size) { x[v[it]] } + points, DoubleArray(v.size) { y[v[it]] } + values, kernel,
            DoubleArray(v.size) { noise[v[it]] } + noiseStd, priorMean, numerics, dimensions,
            d.map { GpDerivativeObservation(x[it], axes[it], y[it], noise[it]) } + derivatives)
    }
    /** Exact leave-one-out identities from the Cholesky; does not refit n times. Values first, then slopes. */
    fun leaveOneOut(): GpLeaveOneOut {
        val means = DoubleArray(x.size); val vars = DoubleArray(x.size); val residuals = DoubleArray(x.size)
        for (i in x.indices) {
            val unit = DoubleArray(x.size); unit[i] = 1.0
            val v = gpSolve(lower, unit)
            val precision = v[i]
            vars[i] = 1 / precision; means[i] = y[i] - alpha[i] / precision
            residuals[i] = (y[i] - means[i]) / sqrt(vars[i])
        }
        return GpLeaveOneOut(means, vars, residuals)
    }
    companion object {
        /**
         * Empty observations are a prior. Noise is always a standard deviation, in output units
         * (output per input unit for slopes). A fit with only slopes must pass [dimensions].
         */
        fun fit(
            points: Array<DoubleArray>, values: DoubleArray,
            kernel: GpKernel = GpKernels.rbf(), noiseStd: DoubleArray = DoubleArray(values.size),
            mean: (DoubleArray) -> Double = { 0.0 }, numerics: GpNumerics = GpNumerics(),
            dimensions: Int = points.firstOrNull()?.size ?: 1,
            derivatives: List<GpDerivativeObservation> = emptyList()
        ): GaussianProcess {
            require(dimensions > 0 && points.size == values.size && values.size == noiseStd.size)
            require(points.size + derivatives.size <= numerics.maxTrainingSize) { "Training size exceeds ${numerics.maxTrainingSize}" }
            require(values.all { it.isFinite() }) { "Observations must be finite" }
            require(points.all { it.size == dimensions && it.all { v -> v.isFinite() } }) { "Input dimension mismatch or non-finite coordinates" }
            require(derivatives.all { it.point.size == dimensions && it.point.all { v -> v.isFinite() } }) { "Derivative observation dimension mismatch" }
            noiseStd.forEach { gpNonnegative(it, "observation noise standard deviation") }
            val allPoints = points.map { it.copyOf() } + derivatives.map { it.point }
            val allAxes = IntArray(points.size) { -1 } + IntArray(derivatives.size) { derivatives[it].axis }
            val allValues = values + DoubleArray(derivatives.size) { derivatives[it].value }
            val allNoise = noiseStd + DoubleArray(derivatives.size) { derivatives[it].noiseStd }
            // Redundant exact observations are harmless; incompatible exact constraints are not.
            val exact = HashMap<List<Double>, Double>()
            val keep = allPoints.indices.filter { i ->
                if (allNoise[i] != 0.0) true else {
                    val key = allPoints[i].map { if (it == 0.0) 0.0 else it } + allAxes[i].toDouble()
                    val previous = exact.putIfAbsent(key, allValues[i])
                    require(previous == null || previous == allValues[i]) { "Conflicting noiseless observations at ${key.dropLast(1)}" }
                    previous == null
                }
            }
            return GaussianProcess(dimensions, kernel, mean, Array(keep.size) { allPoints[keep[it]] },
                IntArray(keep.size) { allAxes[keep[it]] }, DoubleArray(keep.size) { allValues[keep[it]] },
                DoubleArray(keep.size) { allNoise[keep[it]] }, numerics)
        }
        fun prior(dimensions: Int = 1, kernel: GpKernel = GpKernels.rbf(), mean: (DoubleArray) -> Double = { 0.0 }, numerics: GpNumerics = GpNumerics()): GaussianProcess =
            fit(emptyArray(), doubleArrayOf(), kernel, mean = mean, numerics = numerics, dimensions = dimensions)
    }
}

/** 1D observations may be unsorted, and repeated noisy observations remain independent evidence. */
fun gp1D(xs: DoubleArray, values: DoubleArray, kernel: GpKernel = GpKernels.rbf(), noiseStd: Double = 0.0,
         mean: (Double) -> Double = { 0.0 }, numerics: GpNumerics = GpNumerics(),
         slopes: List<GpDerivativeObservation> = emptyList()): GaussianProcess =
    gp1D(xs, values, DoubleArray(values.size) { noiseStd }, kernel, mean, numerics, slopes)
fun gp1D(xs: DoubleArray, values: DoubleArray, noiseStd: DoubleArray, kernel: GpKernel = GpKernels.rbf(),
         mean: (Double) -> Double = { 0.0 }, numerics: GpNumerics = GpNumerics(),
         slopes: List<GpDerivativeObservation> = emptyList()): GaussianProcess =
    GaussianProcess.fit(Array(xs.size) { doubleArrayOf(xs[it]) }, values, kernel, noiseStd, { mean(it[0]) }, numerics, 1, slopes)
