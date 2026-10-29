@file:afield.runtime.completion.CompletionCategory("Math")
package afield.math.gp

import kotlin.math.abs

/**
 * Vector-valued Gaussian processes with separable covariance, for fitting curves and fields whose outputs are several coordinates.
 *
 * [GpVectorProcess] lifts each observation to (input, output index) and reuses the exact scalar
 * [GaussianProcess], so cost is cubic in observations times output dimensions. With a
 * [GpDifferentiableKernel] it trains on and answers derivatives through
 * [GpVectorDerivativeObservation] and `derivativePosterior`. [GpVectorJoint] is the joint posterior
 * over a set of points, with per-point means, covariances and samples.
 */

/** A measured vector derivative ∂f/∂x[axis] (every output coordinate) at [point]. */
class GpVectorDerivativeObservation(point: DoubleArray, val axis: Int, value: DoubleArray, val noiseStd: Double = 0.0) {
    private val p = point.copyOf()
    private val v = value.copyOf()
    /** Where the derivative was measured. A copy. */
    val point: DoubleArray get() = p.copyOf()
    /** The measured derivative, one entry per output coordinate. A copy. */
    val value: DoubleArray get() = v.copyOf()
    init {
        require(axis in p.indices && v.all { it.isFinite() })
        gpNonnegative(noiseStd, "derivative noise standard deviation")
    }
}

/**
 * A vector-valued GP with separable covariance k(input,input') * outputCovariance[axis,axis'].
 * Identity output covariance gives independent coordinates; a PSD matrix gives correlated outputs.
 * This uses exact scalar inference on lifted (input, output-index) observations, so costs are cubic
 * in observationCount * outputDimensions. Use scalar models for large independent-output datasets.
 * With a [GpDifferentiableKernel] it also answers, and trains on, input derivatives (velocities).
 */
class GpVectorProcess private constructor(val inputDimensions: Int, val outputDimensions: Int, val process: GaussianProcess) {
    private fun lift(point: DoubleArray, axis: Int): DoubleArray {
        require(point.size == inputDimensions)
        return point.copyOf(inputDimensions + 1).also { it[inputDimensions] = axis.toDouble() }
    }
    /** The posterior mean vector at [point], one entry per output coordinate. */
    fun mean(vararg point: Double): DoubleArray = DoubleArray(outputDimensions) { process.mean(*lift(point, it)) }
    /**
     * The marginal variance of each output coordinate at [point]. Coordinates may be
     * correlated, which this does not say: for that, ask [covariance] or a posterior.
     */
    fun variance(vararg point: Double): DoubleArray = DoubleArray(outputDimensions) { process.variance(*lift(point, it)) }
    /** The output-by-output covariance between the vectors at [a] and [b]. */
    fun covariance(a: DoubleArray, b: DoubleArray): Array<DoubleArray> = Array(outputDimensions) { i ->
        DoubleArray(outputDimensions) { j -> process.covariance(lift(a, i), lift(b, j)) }
    }
    /**
     * The joint posterior over the vectors at [points] — means, covariances and correlated
     * samples together. Limited by the scalar joint size divided by the output dimensions.
     */
    fun posterior(points: Array<DoubleArray>): GpVectorJoint = joint(points, null)
    /** The vector derivative ∂f/∂x[axis] at every point, jointly (e.g. a curve's velocity). */
    fun derivativePosterior(points: Array<DoubleArray>, axis: Int = 0): GpVectorJoint {
        require(axis in 0 until inputDimensions)
        return joint(points, axis)
    }
    private fun joint(points: Array<DoubleArray>, axis: Int?): GpVectorJoint {
        require(points.size <= process.numerics.maxJointSize / outputDimensions) { "Vector query exceeds scalar joint size limit" }
        return GpVectorJoint(outputDimensions, process.posterior(List(points.size * outputDimensions) { i ->
            val p = lift(points[i / outputDimensions], i % outputDimensions)
            if (axis == null) GpFunctional.value(*p) else GpFunctional.derivative(axis, *p)
        }))
    }
    companion object {
        fun fit(points: Array<DoubleArray>, values: Array<DoubleArray>, kernel: GpKernel = GpKernels.rbf(),
                noiseStd: Array<DoubleArray> = Array(values.size) { DoubleArray(values[it].size) },
                outputCovariance: Array<DoubleArray> = Array(values.firstOrNull()?.size ?: 3) { i -> DoubleArray(values.firstOrNull()?.size ?: 3) { j -> if (i == j) 1.0 else 0.0 } },
                mean: (DoubleArray) -> DoubleArray = { DoubleArray(outputCovariance.size) },
                inputDimensions: Int = points.firstOrNull()?.size ?: 1, numerics: GpNumerics = GpNumerics(),
                derivatives: List<GpVectorDerivativeObservation> = emptyList()): GpVectorProcess {
            val d = outputCovariance.size
            require(inputDimensions > 0 && d > 0 && outputCovariance.all { it.size == d && it.all { v -> v.isFinite() } })
            val b = Array(d) { outputCovariance[it].copyOf() }
            for (i in 0 until d) for (j in 0 until d) require(abs(b[i][j] - b[j][i]) <= numerics.covarianceTolerance * maxOf(1e-300, abs(b[i][j]), abs(b[j][i]))) { "Output covariance must be symmetric" }
            gpPsdFactor(b, numerics.samplingTolerance, 0.0) // reject an indefinite output covariance before fitting
            require(points.size == values.size && noiseStd.size == values.size)
            require(points.all { it.size == inputDimensions } && values.all { it.size == d } && noiseStd.all { it.size == d })
            require(derivatives.all { it.point.size == inputDimensions && it.value.size == d })
            require(points.size + derivatives.size <= numerics.maxTrainingSize / d)
            val lifted = liftKernel(kernel, b, inputDimensions)
            val liftedDerivatives = derivatives.flatMap { o ->
                val p = o.point; val v = o.value
                List(d) { axis -> GpDerivativeObservation(p.copyOf(inputDimensions + 1).also { it[inputDimensions] = axis.toDouble() }, o.axis, v[axis], o.noiseStd) }
            }
            val gp = GaussianProcess.fit(
                Array(points.size * d) { i -> points[i / d].copyOf(inputDimensions + 1).also { it[inputDimensions] = (i % d).toDouble() } },
                DoubleArray(values.size * d) { values[it / d][it % d] }, lifted,
                DoubleArray(values.size * d) { noiseStd[it / d][it % d] },
                { p -> val m = mean(p.copyOf(inputDimensions)); require(m.size == d); m[p[inputDimensions].toInt()] }, numerics, inputDimensions + 1,
                liftedDerivatives
            )
            return GpVectorProcess(inputDimensions, d, gp)
        }

        /** k((x, i), (x', j)) = k(x, x') · B[i][j]; derivatives act on x only. */
        private fun liftKernel(kernel: GpKernel, outputs: Array<DoubleArray>, n: Int): GpKernel {
            fun out(p: DoubleArray) = p[n].toInt()
            if (kernel !is GpDifferentiableKernel) return GpKernel { a, b -> kernel.covariance(a.copyOf(n), b.copyOf(n)) * outputs[out(a)][out(b)] }
            return object : GpDifferentiableKernel {
                override fun covariance(a: DoubleArray, b: DoubleArray) = kernel.covariance(a.copyOf(n), b.copyOf(n)) * outputs[out(a)][out(b)]
                override fun covarianceGradient(a: DoubleArray, b: DoubleArray, axis: Int): Double {
                    require(axis < n) { "Derivatives are along input axes only" }
                    return kernel.covarianceGradient(a.copyOf(n), b.copyOf(n), axis) * outputs[out(a)][out(b)]
                }
                override fun covarianceHessian(a: DoubleArray, b: DoubleArray, i: Int, j: Int): Double {
                    require(i < n && j < n) { "Derivatives are along input axes only" }
                    return kernel.covarianceHessian(a.copyOf(n), b.copyOf(n), i, j) * outputs[out(a)][out(b)]
                }
            }
        }
    }
}

/** Input-major joint: (point0-axis0, point0-axis1, ..., point1-axis0, ...). */
class GpVectorJoint internal constructor(val dimensions: Int, val joint: GpJoint) {
    /** How many input points this joint covers, each carrying [dimensions] outputs. */
    val size: Int get() = joint.size / dimensions
    /** The mean vector at each point, outermost index the point. */
    val mean: Array<DoubleArray> get() { val m = joint.mean; return Array(size) { i -> m.copyOfRange(i * dimensions, (i + 1) * dimensions) } }
    /** The marginal variance of each output coordinate at each point. */
    val variance: Array<DoubleArray> get() { val v = joint.variance; return Array(size) { i -> v.copyOfRange(i * dimensions, (i + 1) * dimensions) } }
    /**
     * The output-by-output covariance between points [i] and [j], which defaults to the
     * block at one point: how that point's own coordinates covary.
     */
    fun covarianceAt(i: Int, j: Int = i): Array<DoubleArray> {
        require(i in 0 until size && j in 0 until size)
        return Array(dimensions) { a -> DoubleArray(dimensions) { b -> joint.covarianceAt(i * dimensions + a, j * dimensions + b) } }
    }
    /** One draw: a vector at every point, correlated across both points and coordinates. */
    fun sample(seed: Long): Array<DoubleArray> = split(joint.sample(seed))
    /** The same draw, taking its randomness from [random]; call it repeatedly for independent draws. */
    fun sample(random: java.util.Random): Array<DoubleArray> = split(joint.sample(random))
    /** See [GpJoint.sample]: [spread] scales the departure from the mean. */
    fun sample(seed: Long, spread: Double): Array<DoubleArray> = split(joint.sample(seed, spread))
    private fun split(s: DoubleArray) = Array(size) { s.copyOfRange(it * dimensions, (it + 1) * dimensions) }
    /** Variance of the scalar projection direction·value at one input. Direction need not be unit. */
    fun projectedVariance(index: Int, direction: DoubleArray): Double {
        require(direction.size == dimensions && direction.all { it.isFinite() })
        val c = covarianceAt(index)
        var v = 0.0
        for (i in direction.indices) for (j in direction.indices) v += direction[i] * c[i][j] * direction[j]
        return maxOf(0.0, v)
    }
}
