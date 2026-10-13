@file:afield.CompletionCategory("Math")
package afield.math.gp

import kotlin.math.*

/**
 * One lazily extended exact GP draw. Repeated queries return the same value; new locations are
 * drawn conditional on all previous locations. The law is independent of query order, but a seed's
 * realised values are not. Mutable, single-threaded, and dense: use a feature path for large fields.
 */
class GpSamplePath internal constructor(private val model: GaussianProcess, seed: Long, private val maxPoints: Int) {
    private val random = gpRandom(seed)
    private val cache = HashMap<List<Double>, Double>()
    private val basis = ArrayList<DoubleArray>()
    private val lower = ArrayList<DoubleArray>()
    private val normals = ArrayList<Double>()
    init { require(maxPoints > 0) }
    val evaluatedPoints: Int get() = cache.size
    operator fun invoke(vararg coordinates: Double): Double {
        val key = coordinates.map { if (it == 0.0) 0.0 else it }
        cache[key]?.let { return it }
        require(cache.size < maxPoints) { "Sample path reached $maxPoints points; use an RBF feature path for large fields" }
        val p = model.predict(*coordinates)
        val v = DoubleArray(basis.size)
        for (i in basis.indices) {
            var c = model.covariance(coordinates, basis[i])
            for (j in 0 until i) c -= lower[i][j] * v[j]
            v[i] = c / lower[i][i]
        }
        var variance = p.variance - gpDot(v, v)
        val tolerance = model.numerics.covarianceTolerance * max(p.variance, 1e-300)
        require(variance >= -tolerance) { "Sample path conditional covariance lost positive semidefiniteness; use a fixed joint query" }
        variance = max(0.0, variance)
        var value = p.mean
        for (i in v.indices) value += v[i] * normals[i]
        if (variance > tolerance) {
            val z = random.nextGaussian()
            value += sqrt(variance) * z
            basis.add(coordinates.copyOf())
            lower.add(v.copyOf(v.size + 1).also { it[v.size] = sqrt(variance) })
            normals.add(z)
        }
        cache[key] = value
        return value
    }
}

fun GaussianProcess.samplePath(seed: Long, maxPoints: Int = numerics.maxJointSize): GpSamplePath = GpSamplePath(this, seed, maxPoints)

/**
 * Paired sine/cosine random Fourier features for an ARD RBF kernel. This is an explicit finite-rank
 * approximation, never an automatic substitute for exact inference. The same features are used
 * for fitting, uncertainty and Matheron-rule samples. Feature seeds describe a model, sample seeds
 * describe functions from it. Amplitude is a variance, and length scales are in input units.
 */
class GpRbfFeatures(
    lengthScales: DoubleArray, val frequencyCount: Int = 512, val variance: Double = 1.0, val seed: Long = 0L
) {
    private val scales = lengthScales.copyOf()
    val dimensions: Int get() = scales.size
    val featureCount: Int get() = 2 * frequencyCount
    private val frequencies: Array<DoubleArray>
    init {
        require(scales.isNotEmpty() && scales.all { it.isFinite() && it > 0 })
        require(frequencyCount in 1..65536)
        require(variance.isFinite() && variance > 0)
        val rng = gpRandom(seed)
        frequencies = Array(frequencyCount) { DoubleArray(dimensions) { rng.nextGaussian() / scales[it] } }
    }
    private val amplitude = sqrt(variance / frequencyCount)
    internal fun features(point: DoubleArray): DoubleArray {
        require(point.size == dimensions && point.all { it.isFinite() })
        val out = DoubleArray(featureCount)
        for (i in frequencies.indices) {
            val phase = gpDot(point, frequencies[i])
            require(phase.isFinite()) { "Feature phase overflow; rescale coordinates" }
            out[2 * i] = amplitude * cos(phase); out[2 * i + 1] = amplitude * sin(phase)
        }
        return out
    }
    fun fit(points: Array<DoubleArray>, values: DoubleArray, noiseStd: DoubleArray = DoubleArray(values.size),
            mean: (DoubleArray) -> Double = { 0.0 }, numerics: GpNumerics = GpNumerics()): GpFeatureModel {
        val kernel = GpKernel { a, b -> gpDot(features(a), features(b)) }
        val model = GaussianProcess.fit(points, values, kernel, noiseStd, mean, numerics, dimensions)
        return GpFeatureModel(this, model, mean)
    }
    fun prior(mean: (DoubleArray) -> Double = { 0.0 }, numerics: GpNumerics = GpNumerics()): GpFeatureModel =
        fit(emptyArray(), doubleArrayOf(), mean = mean, numerics = numerics)
}

/** Finite-feature posterior: fast callable means/draws, plus the exact covariance of this approximation. */
class GpFeatureModel internal constructor(
    val features: GpRbfFeatures, val process: GaussianProcess, private val meanFunction: (DoubleArray) -> Double
) {
    private val data = process.observationData()
    private val phi = Array(data.first.size) { features.features(data.first[it]) }
    private val centered = DoubleArray(data.second.size) { data.second[it] - meanFunction(data.first[it]) }
    private fun weights(alpha: DoubleArray): DoubleArray = DoubleArray(features.featureCount) { j ->
        var s = 0.0; for (i in phi.indices) s += phi[i][j] * alpha[i]; s
    }
    private val meanWeights = weights(process.solveResidual(centered))
    fun mean(vararg point: Double): Double = meanFunction(point) + gpDot(features.features(point), meanWeights)
    fun predict(vararg point: Double): GpPrediction = process.predict(*point)
    /** Draw a function once, then evaluate it anywhere, in any order, without growing a cache. */
    fun samplePath(seed: Long): GpFeaturePath {
        val rng = gpRandom(seed)
        val w = DoubleArray(features.featureCount) { rng.nextGaussian() }
        // Include the reported numerical jitter in the pseudo-observation variance too.
        val residual = DoubleArray(phi.size) { i ->
            centered[i] - gpDot(phi[i], w) - sqrt(data.third[i] * data.third[i] + process.jitterUsed) * rng.nextGaussian()
        }
        val correction = weights(process.solveResidual(residual))
        for (j in w.indices) w[j] += correction[j]
        return GpFeaturePath(features, w, meanFunction)
    }
}

/** Immutable coefficients; thread safe provided the supplied mean function is thread safe. */
class GpFeaturePath internal constructor(private val features: GpRbfFeatures, private val weights: DoubleArray,
                                        private val mean: (DoubleArray) -> Double) {
    operator fun invoke(vararg point: Double): Double = mean(point) + gpDot(features.features(point), weights)
}
