package afield.graphics

import afield.stdlib.*
import kotlin.math.abs

/**
 * Smile cubic-spline interpolation along arc-length, applied independently to XYZ.
 */
fun FLine.bySmileSplineInterpolating(
    trainingSamples: Int = 48,
    samples: Int = 200,
    dryWet: Float = 1f,
    wetModulator: ParameterFunction? = null
): FLine {
    val obs = this.sampleByNumber(trainingSamples.coerceAtLeast(2))
    if (obs.size < 2) return this.copy()

    val (tObs, pObs) = prepareArcLengthTrainingData(obs) ?: return this.copy()
    return try {
        val xModel = smileCubicSpline(tObs, DoubleArray(pObs.size) { i -> pObs[i].x.toDouble() })
        val yModel = smileCubicSpline(tObs, DoubleArray(pObs.size) { i -> pObs[i].y.toDouble() })
        val zModel = smileCubicSpline(tObs, DoubleArray(pObs.size) { i -> pObs[i].z.toDouble() })

        val outCount = samples.coerceAtLeast(1)
        val predicted = ArrayList<Vec3>(outCount)
        for (i in 0 until outCount) {
            val t = if (outCount == 1) 0.0 else i.toDouble() / (outCount - 1).toDouble()
            predicted += vec(
                xModel.predict(t).toFloat(),
                yModel.predict(t).toFloat(),
                zModel.predict(t).toFloat()
            )
        }

        val base = this.sampleByNumber(outCount)
        mixSamplesSmile(base, predicted, dryWet, wetModulator).toFLineSmile()
    } catch (_: Throwable) {
        this.copy()
    }
}

/**
 * Smile Gaussian-process interpolation along arc-length, applied independently to XYZ.
 */
fun FLine.bySmileGaussianProcessInterpolating(
    trainingSamples: Int = 48,
    samples: Int = 200,
    lengthScale: Float = 0.15f,
    noise: Float = 1e-3f,
    normalize: Boolean = true,
    tol: Float = 1e-4f,
    maxIter: Int = 500,
    dryWet: Float = 1f,
    wetModulator: ParameterFunction? = null
): FLine {
    val obs = this.sampleByNumber(trainingSamples.coerceAtLeast(2))
    if (obs.size < 2) return this.copy()

    val (tObs, pObs) = prepareArcLengthTrainingData(obs) ?: return this.copy()
    return try {
        val options = SmileGpOptions(
            lengthScale = lengthScale.toDouble(),
            noise = noise.toDouble(),
            normalize = normalize,
            tol = tol.toDouble(),
            maxIter = maxIter
        )

        val xModel = smileGaussianProcess(tObs, DoubleArray(pObs.size) { i -> pObs[i].x.toDouble() }, options)
        val yModel = smileGaussianProcess(tObs, DoubleArray(pObs.size) { i -> pObs[i].y.toDouble() }, options)
        val zModel = smileGaussianProcess(tObs, DoubleArray(pObs.size) { i -> pObs[i].z.toDouble() }, options)

        val outCount = samples.coerceAtLeast(1)
        val predicted = ArrayList<Vec3>(outCount)
        for (i in 0 until outCount) {
            val t = if (outCount == 1) 0.0 else i.toDouble() / (outCount - 1).toDouble()
            predicted += vec(
                xModel.predict(t).toFloat(),
                yModel.predict(t).toFloat(),
                zModel.predict(t).toFloat()
            )
        }

        val base = this.sampleByNumber(outCount)
        mixSamplesSmile(base, predicted, dryWet, wetModulator).toFLineSmile()
    } catch (_: Throwable) {
        this.copy()
    }
}

/**
 * Custom Akima-style interpolation along arc-length, applied independently to XYZ.
 */
fun FLine.byAkimaInterpolating(
    trainingSamples: Int = 48,
    samples: Int = 200,
    dryWet: Float = 1f,
    wetModulator: ParameterFunction? = null
): FLine {
    val obs = this.sampleByNumber(trainingSamples.coerceAtLeast(2))
    if (obs.size < 2) return this.copy()

    val (tObs, pObs) = prepareArcLengthTrainingData(obs) ?: return this.copy()
    return try {
        val xModel = akimaOrFallback(tObs, DoubleArray(pObs.size) { i -> pObs[i].x.toDouble() })
        val yModel = akimaOrFallback(tObs, DoubleArray(pObs.size) { i -> pObs[i].y.toDouble() })
        val zModel = akimaOrFallback(tObs, DoubleArray(pObs.size) { i -> pObs[i].z.toDouble() })

        val outCount = samples.coerceAtLeast(1)
        val predicted = ArrayList<Vec3>(outCount)
        for (i in 0 until outCount) {
            val t = if (outCount == 1) 0.0 else i.toDouble() / (outCount - 1).toDouble()
            predicted += vec(
                xModel.predict(t).toFloat(),
                yModel.predict(t).toFloat(),
                zModel.predict(t).toFloat()
            )
        }

        val base = this.sampleByNumber(outCount)
        mixSamplesSmile(base, predicted, dryWet, wetModulator).toFLineSmile()
    } catch (_: Throwable) {
        this.copy()
    }
}

private interface ScalarPredictor {
    fun predict(x: Double): Double
}

private fun akimaOrFallback(xs: DoubleArray, ys: DoubleArray): ScalarPredictor {
    return if (xs.size >= 5) {
        AkimaInterpolator1D(xs, ys)
    } else {
        val spline = smileCubicSpline(xs, ys)
        object : ScalarPredictor {
            override fun predict(x: Double): Double = spline.predict(x)
        }
    }
}

private class AkimaInterpolator1D(
    private val xs: DoubleArray,
    private val ys: DoubleArray
) : ScalarPredictor {
    private val derivatives: DoubleArray = computeDerivatives(xs, ys)

    init {
        require(xs.size == ys.size) { "AkimaInterpolator1D: xs.size must match ys.size" }
        require(xs.size >= 5) { "AkimaInterpolator1D: requires at least 5 points" }
        for (i in 1 until xs.size) {
            require(xs[i] > xs[i - 1]) { "AkimaInterpolator1D: xs must be strictly increasing" }
        }
    }

    override fun predict(x: Double): Double {
        if (x <= xs.first()) return ys.first()
        if (x >= xs.last()) return ys.last()

        val interval = findInterval(x)
        val x0 = xs[interval]
        val x1 = xs[interval + 1]
        val y0 = ys[interval]
        val y1 = ys[interval + 1]
        val d0 = derivatives[interval]
        val d1 = derivatives[interval + 1]
        val h = x1 - x0
        val dx = x - x0
        val slope = (y1 - y0) / h
        val c = (3.0 * slope - 2.0 * d0 - d1) / h
        val d = (d0 + d1 - 2.0 * slope) / (h * h)
        return y0 + d0 * dx + c * dx * dx + d * dx * dx * dx
    }

    private fun findInterval(x: Double): Int {
        val raw = xs.binarySearch(x)
        return when {
            raw >= 0 -> raw.coerceAtMost(xs.lastIndex - 1)
            else -> {
                val insertion = -raw - 1
                (insertion - 1).coerceIn(0, xs.lastIndex - 1)
            }
        }
    }

    private fun computeDerivatives(xs: DoubleArray, ys: DoubleArray): DoubleArray {
        val n = xs.size
        val m = DoubleArray(n - 1) { i ->
            (ys[i + 1] - ys[i]) / (xs[i + 1] - xs[i])
        }

        fun extSlope(index: Int): Double = when {
            index == -2 -> 3.0 * m[0] - 2.0 * m[1]
            index == -1 -> 2.0 * m[0] - m[1]
            index in 0 until (n - 1) -> m[index]
            index == n - 1 -> 2.0 * m[n - 2] - m[n - 3]
            index == n -> 3.0 * m[n - 2] - 2.0 * m[n - 3]
            else -> error("AkimaInterpolator1D: slope index out of bounds ($index)")
        }

        val d = DoubleArray(n)
        for (i in 0 until n) {
            val mIm2 = extSlope(i - 2)
            val mIm1 = extSlope(i - 1)
            val mI = extSlope(i)
            val mIp1 = extSlope(i + 1)
            val w1 = abs(mIp1 - mI)
            val w2 = abs(mIm1 - mIm2)
            d[i] = if ((w1 + w2) > 1e-12) {
                (w1 * mIm1 + w2 * mI) / (w1 + w2)
            } else {
                0.5 * (mIm1 + mI)
            }
        }
        return d
    }
}

private fun prepareArcLengthTrainingData(points: List<Vec3>): Pair<DoubleArray, List<Vec3>>? {
    if (points.size < 2) return null
    val t = normalizedArcLengthParamsSmile(points)
    val filteredT = ArrayList<Double>(t.size)
    val filteredP = ArrayList<Vec3>(points.size)
    for (i in t.indices) {
        val currentT = t[i]
        if (filteredT.isEmpty() || currentT > filteredT.last() + 1e-12) {
            filteredT += currentT
            filteredP += Vec3(points[i])
        }
    }
    if (filteredT.size < 2) return null
    return filteredT.toDoubleArray() to filteredP
}

private fun normalizedArcLengthParamsSmile(points: List<Vec3>): DoubleArray {
    val n = points.size
    val params = DoubleArray(n)
    if (n == 0) return params

    var total = 0.0
    for (i in 1 until n) {
        total += points[i].distance(points[i - 1]).toDouble()
        params[i] = total
    }

    if (total > 0.0) {
        for (i in params.indices) {
            params[i] /= total
        }
    }

    return params
}

private fun mixSamplesSmile(
    base: List<Vec3>,
    processed: List<Vec3>,
    dryWet: Float,
    modulator: ParameterFunction? = null
): List<Vec3> {
    val count = minOf(base.size, processed.size)
    val out = ArrayList<Vec3>(count)
    for (i in 0 until count) {
        val t = if (count <= 1) 0f else i.toFloat() / (count - 1)
        val wet = modulator?.evaluate(t, processed[i]) ?: dryWet
        val dry = 1f - wet
        out += Vec3(base[i]).mul(dry).add(Vec3(processed[i]).mul(wet))
    }
    return out
}

private fun List<Vec3>.toFLineSmile(): FLine {
    val out = FLine()
    if (isNotEmpty()) {
        out.moveTo(this[0])
        for (i in 1 until size) {
            out.lineTo(this[i])
        }
    }
    return out
}
