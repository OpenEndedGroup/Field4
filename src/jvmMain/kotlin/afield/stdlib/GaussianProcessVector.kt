@file:afield.CompletionCategory("Math")
package afield.stdlib

/**
 * A vector-valued GP with separable covariance k(input,input') * outputCovariance[axis,axis'].
 * Identity output covariance gives independent coordinates; a PSD matrix gives correlated outputs.
 * This uses exact scalar inference on lifted (input, output-index) observations, so costs are cubic
 * in observationCount * outputDimensions. Use scalar models for large independent-output datasets.
 */
class GpVectorProcess private constructor(val inputDimensions: Int, val outputDimensions: Int, val process: GaussianProcess) {
    private fun lift(point: DoubleArray, axis: Int): DoubleArray {
        require(point.size == inputDimensions)
        return point.copyOf(inputDimensions + 1).also { it[inputDimensions] = axis.toDouble() }
    }
    fun mean(vararg point: Double): DoubleArray = DoubleArray(outputDimensions) { process.mean(*lift(point, it)) }
    fun variance(vararg point: Double): DoubleArray = DoubleArray(outputDimensions) { process.variance(*lift(point, it)) }
    fun covariance(a: DoubleArray, b: DoubleArray): Array<DoubleArray> = Array(outputDimensions) { i ->
        DoubleArray(outputDimensions) { j -> process.covariance(lift(a, i), lift(b, j)) }
    }
    fun posterior(points: Array<DoubleArray>): GpVectorJoint {
        require(points.size <= process.numerics.maxJointSize / outputDimensions) { "Vector query exceeds scalar joint size limit" }
        return GpVectorJoint(outputDimensions, process.posterior(Array(points.size * outputDimensions) { i -> lift(points[i / outputDimensions], i % outputDimensions) }))
    }
    companion object {
        fun fit(points: Array<DoubleArray>, values: Array<DoubleArray>, kernel: GpKernel = GpKernels.rbf(),
                noiseStd: Array<DoubleArray> = Array(values.size) { DoubleArray(values[it].size) },
                outputCovariance: Array<DoubleArray> = Array(values.firstOrNull()?.size ?: 3) { i -> DoubleArray(values.firstOrNull()?.size ?: 3) { j -> if (i == j) 1.0 else 0.0 } },
                mean: (DoubleArray) -> DoubleArray = { DoubleArray(outputCovariance.size) },
                inputDimensions: Int = points.firstOrNull()?.size ?: 1, numerics: GpNumerics = GpNumerics()): GpVectorProcess {
            val d = outputCovariance.size
            require(inputDimensions > 0 && d > 0 && outputCovariance.all { it.size == d && it.all { v -> v.isFinite() } })
            val b = Array(d) { outputCovariance[it].copyOf() }
            for (i in 0 until d) for (j in 0 until d) require(absDifference(b[i][j], b[j][i]) <= numerics.covarianceTolerance * maxOf(1e-300, kotlin.math.abs(b[i][j]), kotlin.math.abs(b[j][i]))) { "Output covariance must be symmetric" }
            gpPsdFactor(b, numerics.samplingTolerance, 0.0) // reject an indefinite output covariance before fitting
            require(points.size == values.size && noiseStd.size == values.size)
            require(points.all { it.size == inputDimensions } && values.all { it.size == d } && noiseStd.all { it.size == d })
            require(points.size <= numerics.maxTrainingSize / d)
            val lifted = GpKernel { a, c -> kernel.covariance(a.copyOf(inputDimensions), c.copyOf(inputDimensions)) * b[a[inputDimensions].toInt()][c[inputDimensions].toInt()] }
            val gp = GaussianProcess.fit(
                Array(points.size * d) { i -> points[i / d].copyOf(inputDimensions + 1).also { it[inputDimensions] = (i % d).toDouble() } },
                DoubleArray(values.size * d) { values[it / d][it % d] }, lifted,
                DoubleArray(values.size * d) { noiseStd[it / d][it % d] },
                { p -> val m = mean(p.copyOf(inputDimensions)); require(m.size == d); m[p[inputDimensions].toInt()] }, numerics, inputDimensions + 1
            )
            return GpVectorProcess(inputDimensions, d, gp)
        }
        private fun absDifference(a: Double, b: Double) = kotlin.math.abs(a - b)
    }
}

/** Input-major joint: (point0-axis0, point0-axis1, ..., point1-axis0, ...). */
class GpVectorJoint internal constructor(val dimensions: Int, val joint: GpJoint) {
    val size: Int get() = joint.size / dimensions
    val mean: Array<DoubleArray> get() { val m = joint.mean; return Array(size) { i -> m.copyOfRange(i * dimensions, (i + 1) * dimensions) } }
    val variance: Array<DoubleArray> get() { val v = joint.variance; return Array(size) { i -> v.copyOfRange(i * dimensions, (i + 1) * dimensions) } }
    fun covarianceAt(i: Int, j: Int = i): Array<DoubleArray> {
        require(i in 0 until size && j in 0 until size)
        return Array(dimensions) { a -> DoubleArray(dimensions) { b -> joint.covarianceAt(i * dimensions + a, j * dimensions + b) } }
    }
    fun sample(seed: Long): Array<DoubleArray> { val s = joint.sample(seed); return Array(size) { s.copyOfRange(it * dimensions, (it + 1) * dimensions) } }
    /** Variance of the scalar projection direction·value at one input. Direction need not be unit. */
    fun projectedVariance(index: Int, direction: DoubleArray): Double {
        require(direction.size == dimensions && direction.all { it.isFinite() })
        val c = covarianceAt(index)
        var v = 0.0
        for (i in direction.indices) for (j in direction.indices) v += direction[i] * c[i][j] * direction[j]
        return maxOf(0.0, v)
    }
}
