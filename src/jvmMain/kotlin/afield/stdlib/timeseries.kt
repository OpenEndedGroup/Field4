@file:afield.CompletionCategory("Math")

package afield.stdlib

import kotlin.math.*

private const val DISTRIBUTION_EPSILON = 1e-6f

private fun rollingMean(buffer: FloatArray, count: Int): Float {
    if (count <= 0) return 0f
    var sum = 0f
    for (i in 0 until count) sum += buffer[i]
    return sum / count
}

private fun rollingSampleVariance(buffer: FloatArray, count: Int, mean: Float): Float {
    if (count <= 1) return 0f
    var sqSum = 0f
    for (i in 0 until count) {
        val diff = buffer[i] - mean
        sqSum += diff * diff
    }
    return sqSum / (count - 1)
}

private fun rollingRawSecondMoment(buffer: FloatArray, count: Int): Float {
    if (count <= 0) return 0f
    var sumSq = 0f
    for (i in 0 until count) {
        val v = buffer[i]
        sumSq += v * v
    }
    return sumSq / count
}

private fun logGamma(z: Double): Double {
    val p = doubleArrayOf(
        0.99999999999980993,
        676.5203681218851,
        -1259.1392167224028,
        771.32342877765313,
        -176.61502916214059,
        12.507343278686905,
        -0.13857109526572012,
        9.9843695780195716e-6,
        1.5056327351493116e-7
    )

    if (z < 0.5) {
        return ln(PI) - ln(sin(PI * z)) - logGamma(1.0 - z)
    }

    val zm1 = z - 1.0
    var x = p[0]
    for (i in 1 until p.size) {
        x += p[i] / (zm1 + i.toDouble())
    }
    val t = zm1 + p.size - 1.5
    return 0.5 * ln(2.0 * PI) + (zm1 + 0.5) * ln(t) - t + ln(x)
}

private fun regularizedGammaP(a: Double, x: Double): Double {
    if (!a.isFinite() || !x.isFinite() || a <= 0.0) return Double.NaN
    if (x <= 0.0) return 0.0

    val eps = 1e-12
    val maxIter = 512
    val tiny = 1e-300
    val logPrefactor = -x + a * ln(x) - logGamma(a)

    if (x < a + 1.0) {
        var ap = a
        var sum = 1.0 / a
        var delta = sum
        for (i in 1..maxIter) {
            ap += 1.0
            delta *= x / ap
            sum += delta
            if (abs(delta) < abs(sum) * eps) {
                return (sum * exp(logPrefactor)).coerceIn(0.0, 1.0)
            }
        }
        return (sum * exp(logPrefactor)).coerceIn(0.0, 1.0)
    }

    var b = x + 1.0 - a
    var c = 1.0 / tiny
    var d = 1.0 / b.coerceAtLeast(tiny)
    var h = d
    for (i in 1..maxIter) {
        val fi = i.toDouble()
        val an = -fi * (fi - a)
        b += 2.0
        d = an * d + b
        if (abs(d) < tiny) d = tiny
        c = b + an / c
        if (abs(c) < tiny) c = tiny
        d = 1.0 / d
        val delta = d * c
        h *= delta
        if (abs(delta - 1.0) < eps) break
    }
    val q = exp(logPrefactor) * h
    return (1.0 - q).coerceIn(0.0, 1.0)
}

private fun betaContinuedFraction(a: Double, b: Double, x: Double): Double {
    val eps = 3e-14
    val tiny = 1e-300
    val maxIter = 512

    val qab = a + b
    val qap = a + 1.0
    val qam = a - 1.0
    var c = 1.0
    var d = 1.0 - qab * x / qap
    if (abs(d) < tiny) d = tiny
    d = 1.0 / d
    var h = d

    for (m in 1..maxIter) {
        val m2 = 2.0 * m.toDouble()
        var aa = m.toDouble() * (b - m.toDouble()) * x / ((qam + m2) * (a + m2))
        d = 1.0 + aa * d
        if (abs(d) < tiny) d = tiny
        c = 1.0 + aa / c
        if (abs(c) < tiny) c = tiny
        d = 1.0 / d
        h *= d * c

        aa = -(a + m.toDouble()) * (qab + m.toDouble()) * x / ((a + m2) * (qap + m2))
        d = 1.0 + aa * d
        if (abs(d) < tiny) d = tiny
        c = 1.0 + aa / c
        if (abs(c) < tiny) c = tiny
        d = 1.0 / d
        val delta = d * c
        h *= delta

        if (abs(delta - 1.0) < eps) break
    }

    return h
}

private fun regularizedBeta(x: Double, a: Double, b: Double): Double {
    if (!x.isFinite() || !a.isFinite() || !b.isFinite() || a <= 0.0 || b <= 0.0) return Double.NaN
    if (x <= 0.0) return 0.0
    if (x >= 1.0) return 1.0

    val logBt = logGamma(a + b) - logGamma(a) - logGamma(b) + a * ln(x) + b * ln(1.0 - x)
    val bt = exp(logBt)
    val threshold = (a + 1.0) / (a + b + 2.0)
    return if (x < threshold) {
        (bt * betaContinuedFraction(a, b, x) / a).coerceIn(0.0, 1.0)
    } else {
        (1.0 - bt * betaContinuedFraction(b, a, 1.0 - x) / b).coerceIn(0.0, 1.0)
    }
}

private fun cdfToTargetRange(cdf: Float, targetMin: Float, targetMax: Float): Float {
    val p = cdf.coerceIn(0f, 1f)
    return targetMin + p * (targetMax - targetMin)
}

private fun toUnitInterval(value: Float, supportMin: Float, supportMax: Float): Float {
    val range = supportMax - supportMin
    if (range <= 0f) return 0.5f
    return ((value - supportMin) / range).coerceIn(0f, 1f)
}

private fun upperTailEvent(cdf: Float, threshold: Float): Boolean {
    val t = threshold.coerceIn(0f, 1f)
    return (1f - cdf) <= t
}

private fun lowerTailEvent(cdf: Float, threshold: Float): Boolean {
    val t = threshold.coerceIn(0f, 1f)
    return cdf <= t
}

fun interface FloatFilter {
    operator fun invoke(value: Float): Float

    infix fun then(next: FloatFilter): FloatFilter = FloatFilter { value ->
        next(this@FloatFilter(value))
    }
}

class EMAFilter(var alpha: Float) : FloatFilter {
    private var lastValue: Float? = null
    override fun invoke(value: Float): Float {
        val current = lastValue
        return if (current == null) {
            lastValue = value
            value
        } else {
            val next = alpha * value + (1 - alpha) * current
            lastValue = next
            next
        }
    }
}

class AlphaBetaFilter(var alpha: Float, var beta: Float, var dt: Float = 1f) : FloatFilter {
    private var x: Float? = null
    private var v: Float = 0f

    override fun invoke(value: Float): Float {
        if (x == null) {
            x = value
            v = 0f
            return value
        }

        // Predict
        var xk = x!! + v * dt
        var vk = v

        // Update
        val r = value - xk
        xk += alpha * r
        vk += (beta / dt) * r

        x = xk
        v = vk

        return xk
    }
}

class OneEuroFilter(
    var minCutoff: Float = 1.0f,
    var beta: Float = 0.0f,
    var dCutoff: Float = 1.0f
) : FloatFilter {
    private var xPrev: Float? = null
    private var dxPrev: Float = 0f
    private var tPrev: Long = -1L

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1.0f / (2.0f * PI.toFloat() * cutoff)
        return 1.0f / (1.0f + tau / dt)
    }

    override fun invoke(value: Float): Float {
        val t = System.currentTimeMillis()
        if (xPrev == null) {
            xPrev = value
            dxPrev = 0f
            tPrev = t
            return value
        }

        val dt = ((t - tPrev) / 1000.0f).coerceAtLeast(0.001f)
        tPrev = t

        val dx = (value - xPrev!!) / dt
        val edx = alpha(dCutoff, dt) * dx + (1 - alpha(dCutoff, dt)) * dxPrev
        dxPrev = edx

        val cutoff = minCutoff + beta * abs(edx)
        val a = alpha(cutoff, dt)
        val x = a * value + (1 - a) * xPrev!!
        xPrev = x

        return x
    }
}

enum class BiquadType {
    LOWPASS, HIGHPASS, BANDPASS, NOTCH, PEAK, LOWSHELF, HIGHSHELF
}

class BiquadFilter(
    val type: BiquadType,
    var frequency: Float,
    var sampleRate: Float,
    var q: Float = 0.707f,
    var gainDb: Float = 0f
) : FloatFilter {
    private var b0 = 0f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f

    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    init {
        calculateCoefficients()
    }

    fun calculateCoefficients() {
        val w0 = 2 * PI.toFloat() * frequency / sampleRate
        val alpha = sin(w0) / (2 * q)
        val a = 10.0f.pow(gainDb / 40f)

        var a0 = 1f

        when (type) {
            BiquadType.LOWPASS -> {
                b0 = ((1 - cos(w0)) / 2).toFloat()
                b1 = (1 - cos(w0)).toFloat()
                b2 = ((1 - cos(w0)) / 2).toFloat()
                a0 = (1 + alpha).toFloat()
                a1 = (-2 * cos(w0)).toFloat()
                a2 = (1 - alpha).toFloat()
            }
            BiquadType.HIGHPASS -> {
                b0 = ((1 + cos(w0)) / 2).toFloat()
                b1 = (-(1 + cos(w0))).toFloat()
                b2 = ((1 + cos(w0)) / 2).toFloat()
                a0 = (1 + alpha).toFloat()
                a1 = (-2 * cos(w0)).toFloat()
                a2 = (1 - alpha).toFloat()
            }
            BiquadType.BANDPASS -> {
                b0 = alpha.toFloat()
                b1 = 0f
                b2 = (-alpha).toFloat()
                a0 = (1 + alpha).toFloat()
                a1 = (-2 * cos(w0)).toFloat()
                a2 = (1 - alpha).toFloat()
            }
            BiquadType.NOTCH -> {
                b0 = 1f
                b1 = (-2 * cos(w0)).toFloat()
                b2 = 1f
                a0 = (1 + alpha).toFloat()
                a1 = (-2 * cos(w0)).toFloat()
                a2 = (1 - alpha).toFloat()
            }
            BiquadType.PEAK -> {
                b0 = (1 + alpha * a).toFloat()
                b1 = (-2 * cos(w0)).toFloat()
                b2 = (1 - alpha * a).toFloat()
                a0 = (1 + alpha / a).toFloat()
                a1 = (-2 * cos(w0)).toFloat()
                a2 = (1 - alpha / a).toFloat()
            }
            BiquadType.LOWSHELF -> {
                b0 = (a * ((a + 1) - (a - 1) * cos(w0) + 2 * sqrt(a) * alpha)).toFloat()
                b1 = (2 * a * ((a - 1) - (a + 1) * cos(w0))).toFloat()
                b2 = (a * ((a + 1) - (a - 1) * cos(w0) - 2 * sqrt(a) * alpha)).toFloat()
                a0 = ((a + 1) + (a - 1) * cos(w0) + 2 * sqrt(a) * alpha).toFloat()
                a1 = (-2 * ((a - 1) + (a + 1) * cos(w0))).toFloat()
                a2 = ((a + 1) + (a - 1) * cos(w0) - 2 * sqrt(a) * alpha).toFloat()
            }
            BiquadType.HIGHSHELF -> {
                b0 = (a * ((a + 1) + (a - 1) * cos(w0) + 2 * sqrt(a) * alpha)).toFloat()
                b1 = (-2 * a * ((a - 1) + (a + 1) * cos(w0))).toFloat()
                b2 = (a * ((a + 1) + (a - 1) * cos(w0) - 2 * sqrt(a) * alpha)).toFloat()
                a0 = ((a + 1) - (a - 1) * cos(w0) + 2 * sqrt(a) * alpha).toFloat()
                a1 = (2 * ((a - 1) - (a + 1) * cos(w0))).toFloat()
                a2 = ((a + 1) - (a - 1) * cos(w0) - 2 * sqrt(a) * alpha).toFloat()
            }
        }

        b0 /= a0
        b1 /= a0
        b2 /= a0
        a1 /= a0
        a2 /= a0
    }

    override fun invoke(value: Float): Float {
        val y = b0 * value + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = value
        y2 = y1
        y1 = y
        return y
    }
}

fun LowPassFilter(frequency: Float, sampleRate: Float, q: Float = 0.707f) = BiquadFilter(BiquadType.LOWPASS, frequency, sampleRate, q)
fun HighPassFilter(frequency: Float, sampleRate: Float, q: Float = 0.707f) = BiquadFilter(BiquadType.HIGHPASS, frequency, sampleRate, q)
fun BandPassFilter(frequency: Float, sampleRate: Float, q: Float = 0.707f) = BiquadFilter(BiquadType.BANDPASS, frequency, sampleRate, q)
fun BandRejectFilter(frequency: Float, sampleRate: Float, q: Float = 0.707f) = BiquadFilter(BiquadType.NOTCH, frequency, sampleRate, q)

class RollingStats(val windowSize: Int) : FloatFilter {
    private val buffer = FloatArray(windowSize)
    private var index = 0
    private var count = 0

    var mean: Float = 0f
        private set
    var variance: Float = 0f
        private set
    var stdDev: Float = 0f
        private set
    var zScore: Float = 0f
        private set

    override fun invoke(value: Float): Float {
        buffer[index] = value
        index = (index + 1) % windowSize
        if (count < windowSize) count++

        var sum = 0f
        for (i in 0 until count) {
            sum += buffer[i]
        }
        mean = sum / count

        var sqSum = 0f
        for (i in 0 until count) {
            val diff = buffer[i] - mean
            sqSum += diff * diff
        }
        variance = if (count > 1) sqSum / (count - 1) else 0f
        stdDev = sqrt(variance)

        zScore = if (stdDev > 0) (value - mean) / stdDev else 0f

        return value
    }
}

class RollingExponentialStats(
    val windowSize: Int,
    var epsilon: Float = DISTRIBUTION_EPSILON
) : FloatFilter {
    private val buffer = FloatArray(windowSize)
    private var index = 0
    private var count = 0

    var mean: Float = 0f
        private set
    var lambda: Float = 0f
        private set
    var variance: Float = 0f
        private set
    var stdDev: Float = 0f
        private set
    var cdf: Float = 0f
        private set
    var tailProbability: Float = 1f
        private set
    var clippedNegativeCount: Long = 0
        private set

    init {
        require(windowSize >= 2) { "windowSize must be >= 2" }
    }

    override fun invoke(value: Float): Float {
        val x = if (value >= 0f) value else {
            clippedNegativeCount++
            0f
        }

        buffer[index] = x
        index = (index + 1) % windowSize
        if (count < windowSize) count++

        mean = rollingMean(buffer, count)
        lambda = if (mean > epsilon) 1f / mean else 0f
        variance = if (lambda > epsilon) 1f / (lambda * lambda) else 0f
        stdDev = sqrt(variance)

        if (lambda > epsilon) {
            val tail = exp((-lambda * x).toDouble()).toFloat().coerceIn(0f, 1f)
            tailProbability = tail
            cdf = (1f - tail).coerceIn(0f, 1f)
        } else {
            cdf = 0f
            tailProbability = 1f
        }

        return value
    }
}

class ExponentialNormalizer(
    windowSize: Int,
    var targetMin: Float = 0f,
    var targetMax: Float = 1f,
    epsilon: Float = DISTRIBUTION_EPSILON
) : FloatFilter {
    val stats = RollingExponentialStats(windowSize = windowSize, epsilon = epsilon)

    override fun invoke(value: Float): Float {
        stats(value)
        return cdfToTargetRange(stats.cdf, targetMin, targetMax)
    }
}

class ExponentialEventDetector(
    val windowSize: Int,
    var tailProbabilityThreshold: Float = 0.01f,
    var influence: Float = 0f,
    var epsilon: Float = DISTRIBUTION_EPSILON
) : FloatFilter {
    private val buffer = FloatArray(windowSize)
    private var index = 0
    private var count = 0

    var isEvent: Boolean = false
        private set
    var tailProbability: Float = 1f
        private set
    var lambda: Float = 0f
        private set
    var clippedNegativeCount: Long = 0
        private set

    init {
        require(windowSize >= 2) { "windowSize must be >= 2" }
    }

    override fun invoke(value: Float): Float {
        val x = if (value >= 0f) value else {
            clippedNegativeCount++
            0f
        }

        if (count < windowSize) {
            buffer[index] = x
            index = (index + 1) % windowSize
            count++
            isEvent = false
            tailProbability = 1f
            return 0f
        }

        val mean = rollingMean(buffer, windowSize)
        lambda = if (mean > epsilon) 1f / mean else 0f
        if (lambda <= epsilon) {
            isEvent = false
            tailProbability = 1f
            buffer[index] = x
            index = (index + 1) % windowSize
            return 0f
        }

        tailProbability = exp((-lambda * x).toDouble()).toFloat().coerceIn(0f, 1f)
        isEvent = tailProbability <= tailProbabilityThreshold.coerceIn(0f, 1f)

        val sampleToStore = if (isEvent) {
            val prev = buffer[(index - 1 + windowSize) % windowSize]
            val inf = influence.coerceIn(0f, 1f)
            inf * x + (1f - inf) * prev
        } else {
            x
        }

        buffer[index] = sampleToStore
        index = (index + 1) % windowSize

        return if (isEvent) 1f else 0f
    }
}

class RollingGammaStats(
    val windowSize: Int,
    var epsilon: Float = DISTRIBUTION_EPSILON
) : FloatFilter {
    private val buffer = FloatArray(windowSize)
    private var index = 0
    private var count = 0

    var mean: Float = 0f
        private set
    var variance: Float = 0f
        private set
    var stdDev: Float = 0f
        private set
    var shape: Float = 0f
        private set
    var scale: Float = 0f
        private set
    var cdf: Float = 0f
        private set
    var tailProbability: Float = 1f
        private set
    var clippedNegativeCount: Long = 0
        private set

    init {
        require(windowSize >= 2) { "windowSize must be >= 2" }
    }

    override fun invoke(value: Float): Float {
        val x = if (value >= 0f) value else {
            clippedNegativeCount++
            0f
        }

        buffer[index] = x
        index = (index + 1) % windowSize
        if (count < windowSize) count++

        mean = rollingMean(buffer, count)
        variance = rollingSampleVariance(buffer, count, mean)
        stdDev = sqrt(variance)

        if (mean > epsilon && variance > epsilon) {
            shape = (mean * mean / variance).coerceAtLeast(epsilon)
            scale = (variance / mean).coerceAtLeast(epsilon)
            val gx = (x / scale).toDouble().coerceAtLeast(0.0)
            cdf = regularizedGammaP(shape.toDouble(), gx).toFloat().coerceIn(0f, 1f)
            tailProbability = (1f - cdf).coerceIn(0f, 1f)
        } else {
            shape = 0f
            scale = 0f
            cdf = 0f
            tailProbability = 1f
        }

        return value
    }
}

class GammaNormalizer(
    windowSize: Int,
    var targetMin: Float = 0f,
    var targetMax: Float = 1f,
    epsilon: Float = DISTRIBUTION_EPSILON
) : FloatFilter {
    val stats = RollingGammaStats(windowSize = windowSize, epsilon = epsilon)

    override fun invoke(value: Float): Float {
        stats(value)
        return cdfToTargetRange(stats.cdf, targetMin, targetMax)
    }
}

class GammaEventDetector(
    val windowSize: Int,
    var tailProbabilityThreshold: Float = 0.01f,
    var influence: Float = 0f,
    var epsilon: Float = DISTRIBUTION_EPSILON
) : FloatFilter {
    private val buffer = FloatArray(windowSize)
    private var index = 0
    private var count = 0

    var isEvent: Boolean = false
        private set
    var cdf: Float = 0f
        private set
    var tailProbability: Float = 1f
        private set
    var shape: Float = 0f
        private set
    var scale: Float = 0f
        private set
    var clippedNegativeCount: Long = 0
        private set

    init {
        require(windowSize >= 2) { "windowSize must be >= 2" }
    }

    override fun invoke(value: Float): Float {
        val x = if (value >= 0f) value else {
            clippedNegativeCount++
            0f
        }

        if (count < windowSize) {
            buffer[index] = x
            index = (index + 1) % windowSize
            count++
            isEvent = false
            cdf = 0f
            tailProbability = 1f
            return 0f
        }

        val mean = rollingMean(buffer, windowSize)
        val variance = rollingSampleVariance(buffer, windowSize, mean)
        if (mean <= epsilon || variance <= epsilon) {
            isEvent = false
            cdf = 0f
            tailProbability = 1f
            shape = 0f
            scale = 0f
            buffer[index] = x
            index = (index + 1) % windowSize
            return 0f
        }

        shape = (mean * mean / variance).coerceAtLeast(epsilon)
        scale = (variance / mean).coerceAtLeast(epsilon)
        val gx = (x / scale).toDouble().coerceAtLeast(0.0)
        cdf = regularizedGammaP(shape.toDouble(), gx).toFloat().coerceIn(0f, 1f)
        tailProbability = (1f - cdf).coerceIn(0f, 1f)

        isEvent = upperTailEvent(cdf, tailProbabilityThreshold)
        val sampleToStore = if (isEvent) {
            val prev = buffer[(index - 1 + windowSize) % windowSize]
            val inf = influence.coerceIn(0f, 1f)
            inf * x + (1f - inf) * prev
        } else {
            x
        }

        buffer[index] = sampleToStore
        index = (index + 1) % windowSize
        return if (isEvent) 1f else 0f
    }
}

class RollingBetaStats(
    val windowSize: Int,
    var supportMin: Float = 0f,
    var supportMax: Float = 1f,
    var epsilon: Float = 1e-4f
) : FloatFilter {
    private val buffer = FloatArray(windowSize)
    private var index = 0
    private var count = 0

    var mean: Float = 0f
        private set
    var variance: Float = 0f
        private set
    var stdDev: Float = 0f
        private set
    var alpha: Float = 0f
        private set
    var beta: Float = 0f
        private set
    var cdf: Float = 0f
        private set
    var tailProbabilityLow: Float = 0f
        private set
    var tailProbabilityHigh: Float = 1f
        private set
    var clippedToSupportCount: Long = 0
        private set
    var normalizedValue: Float = 0.5f
        private set

    init {
        require(windowSize >= 2) { "windowSize must be >= 2" }
        require(supportMax > supportMin) { "supportMax must be greater than supportMin" }
    }

    override fun invoke(value: Float): Float {
        val unitRaw = toUnitInterval(value, supportMin, supportMax)
        if (unitRaw <= 0f || unitRaw >= 1f) {
            clippedToSupportCount++
        }
        val unit = unitRaw.coerceIn(epsilon, 1f - epsilon)
        normalizedValue = unitRaw

        buffer[index] = unit
        index = (index + 1) % windowSize
        if (count < windowSize) count++

        mean = rollingMean(buffer, count)
        variance = rollingSampleVariance(buffer, count, mean)
        stdDev = sqrt(variance)

        if (variance > epsilon && mean > epsilon && mean < 1f - epsilon) {
            val common = mean * (1f - mean) / variance - 1f
            if (common > epsilon && common.isFinite()) {
                alpha = (mean * common).coerceAtLeast(epsilon)
                beta = ((1f - mean) * common).coerceAtLeast(epsilon)
                cdf = regularizedBeta(
                    unitRaw.toDouble().coerceIn(0.0, 1.0),
                    alpha.toDouble(),
                    beta.toDouble()
                ).toFloat().coerceIn(0f, 1f)
                tailProbabilityLow = cdf
                tailProbabilityHigh = (1f - cdf).coerceIn(0f, 1f)
                return value
            }
        }

        alpha = 0f
        beta = 0f
        cdf = unitRaw
        tailProbabilityLow = cdf
        tailProbabilityHigh = 1f - cdf
        return value
    }
}

class BetaNormalizer(
    windowSize: Int,
    var targetMin: Float = 0f,
    var targetMax: Float = 1f,
    supportMin: Float = 0f,
    supportMax: Float = 1f,
    epsilon: Float = 1e-4f
) : FloatFilter {
    val stats = RollingBetaStats(
        windowSize = windowSize,
        supportMin = supportMin,
        supportMax = supportMax,
        epsilon = epsilon
    )

    override fun invoke(value: Float): Float {
        stats(value)
        return cdfToTargetRange(stats.cdf, targetMin, targetMax)
    }
}

class BetaEventDetector(
    val windowSize: Int,
    var supportMin: Float = 0f,
    var supportMax: Float = 1f,
    var lowerTailThreshold: Float = 0.01f,
    var upperTailThreshold: Float = 0.01f,
    var influence: Float = 0f,
    var epsilon: Float = 1e-4f
) : FloatFilter {
    private val buffer = FloatArray(windowSize)
    private var index = 0
    private var count = 0

    var isEvent: Boolean = false
        private set
    var cdf: Float = 0.5f
        private set
    var alpha: Float = 0f
        private set
    var beta: Float = 0f
        private set
    var clippedToSupportCount: Long = 0
        private set

    init {
        require(windowSize >= 2) { "windowSize must be >= 2" }
        require(supportMax > supportMin) { "supportMax must be greater than supportMin" }
    }

    override fun invoke(value: Float): Float {
        val unitRaw = toUnitInterval(value, supportMin, supportMax)
        if (unitRaw <= 0f || unitRaw >= 1f) clippedToSupportCount++
        val unit = unitRaw.coerceIn(epsilon, 1f - epsilon)

        if (count < windowSize) {
            buffer[index] = unit
            index = (index + 1) % windowSize
            count++
            isEvent = false
            cdf = 0.5f
            return 0f
        }

        val mean = rollingMean(buffer, windowSize)
        val variance = rollingSampleVariance(buffer, windowSize, mean)

        if (variance > epsilon && mean > epsilon && mean < 1f - epsilon) {
            val common = mean * (1f - mean) / variance - 1f
            if (common > epsilon && common.isFinite()) {
                alpha = (mean * common).coerceAtLeast(epsilon)
                beta = ((1f - mean) * common).coerceAtLeast(epsilon)
                cdf = regularizedBeta(unitRaw.toDouble(), alpha.toDouble(), beta.toDouble()).toFloat().coerceIn(0f, 1f)
            } else {
                alpha = 0f
                beta = 0f
                cdf = unitRaw
            }
        } else {
            alpha = 0f
            beta = 0f
            cdf = unitRaw
        }

        val lowEvent = lowerTailEvent(cdf, lowerTailThreshold)
        val highEvent = upperTailEvent(cdf, upperTailThreshold)
        isEvent = lowEvent || highEvent

        val sampleToStore = if (isEvent) {
            val prev = buffer[(index - 1 + windowSize) % windowSize]
            val inf = influence.coerceIn(0f, 1f)
            inf * unit + (1f - inf) * prev
        } else {
            unit
        }

        buffer[index] = sampleToStore
        index = (index + 1) % windowSize

        return when {
            lowEvent -> -1f
            highEvent -> 1f
            else -> 0f
        }
    }
}

class RollingRayleighStats(
    val windowSize: Int,
    var epsilon: Float = DISTRIBUTION_EPSILON
) : FloatFilter {
    private val buffer = FloatArray(windowSize)
    private var index = 0
    private var count = 0

    var sigma: Float = 0f
        private set
    var mean: Float = 0f
        private set
    var variance: Float = 0f
        private set
    var stdDev: Float = 0f
        private set
    var cdf: Float = 0f
        private set
    var tailProbability: Float = 1f
        private set
    var clippedNegativeCount: Long = 0
        private set

    init {
        require(windowSize >= 2) { "windowSize must be >= 2" }
    }

    override fun invoke(value: Float): Float {
        val x = if (value >= 0f) value else {
            clippedNegativeCount++
            0f
        }

        buffer[index] = x
        index = (index + 1) % windowSize
        if (count < windowSize) count++

        val secondMoment = rollingRawSecondMoment(buffer, count)
        sigma = if (secondMoment > epsilon) sqrt(secondMoment / 2f) else 0f
        mean = sigma * sqrt((PI.toFloat()) / 2f)
        variance = ((4f - PI.toFloat()) / 2f) * sigma * sigma
        stdDev = sqrt(variance.coerceAtLeast(0f))

        if (sigma > epsilon) {
            val sigma2 = sigma * sigma
            val exponent = -(x * x) / (2f * sigma2)
            tailProbability = exp(exponent.toDouble()).toFloat().coerceIn(0f, 1f)
            cdf = (1f - tailProbability).coerceIn(0f, 1f)
        } else {
            cdf = 0f
            tailProbability = 1f
        }

        return value
    }
}

class RayleighNormalizer(
    windowSize: Int,
    var targetMin: Float = 0f,
    var targetMax: Float = 1f,
    epsilon: Float = DISTRIBUTION_EPSILON
) : FloatFilter {
    val stats = RollingRayleighStats(windowSize = windowSize, epsilon = epsilon)

    override fun invoke(value: Float): Float {
        stats(value)
        return cdfToTargetRange(stats.cdf, targetMin, targetMax)
    }
}

class RayleighEventDetector(
    val windowSize: Int,
    var tailProbabilityThreshold: Float = 0.01f,
    var influence: Float = 0f,
    var epsilon: Float = DISTRIBUTION_EPSILON
) : FloatFilter {
    private val buffer = FloatArray(windowSize)
    private var index = 0
    private var count = 0

    var isEvent: Boolean = false
        private set
    var sigma: Float = 0f
        private set
    var cdf: Float = 0f
        private set
    var tailProbability: Float = 1f
        private set
    var clippedNegativeCount: Long = 0
        private set

    init {
        require(windowSize >= 2) { "windowSize must be >= 2" }
    }

    override fun invoke(value: Float): Float {
        val x = if (value >= 0f) value else {
            clippedNegativeCount++
            0f
        }

        if (count < windowSize) {
            buffer[index] = x
            index = (index + 1) % windowSize
            count++
            isEvent = false
            cdf = 0f
            tailProbability = 1f
            return 0f
        }

        val secondMoment = rollingRawSecondMoment(buffer, windowSize)
        sigma = if (secondMoment > epsilon) sqrt(secondMoment / 2f) else 0f
        if (sigma <= epsilon) {
            isEvent = false
            cdf = 0f
            tailProbability = 1f
            buffer[index] = x
            index = (index + 1) % windowSize
            return 0f
        }

        val sigma2 = sigma * sigma
        val exponent = -(x * x) / (2f * sigma2)
        tailProbability = exp(exponent.toDouble()).toFloat().coerceIn(0f, 1f)
        cdf = (1f - tailProbability).coerceIn(0f, 1f)
        isEvent = tailProbability <= tailProbabilityThreshold.coerceIn(0f, 1f)

        val sampleToStore = if (isEvent) {
            val prev = buffer[(index - 1 + windowSize) % windowSize]
            val inf = influence.coerceIn(0f, 1f)
            inf * x + (1f - inf) * prev
        } else {
            x
        }

        buffer[index] = sampleToStore
        index = (index + 1) % windowSize
        return if (isEvent) 1f else 0f
    }
}

class ZScoreEventDetector(
    val windowSize: Int,
    var threshold: Float,
    var influence: Float
) : FloatFilter {
    private val buffer = FloatArray(windowSize)
    private var index = 0
    private var count = 0

    var isEvent: Boolean = false
        private set

    override fun invoke(value: Float): Float {
        if (count < windowSize) {
            buffer[index] = value
            index = (index + 1) % windowSize
            count++
            isEvent = false
            return 0f // 0 means no event
        }

        var sum = 0f
        for (i in 0 until windowSize) sum += buffer[i]
        val mean = sum / windowSize

        var sqSum = 0f
        for (i in 0 until windowSize) {
            val diff = buffer[i] - mean
            sqSum += diff * diff
        }
        val stdDev = sqrt(sqSum / windowSize)

        val zScore = if (stdDev > 0) (value - mean) / stdDev else 0f

        if (abs(zScore) > threshold) {
            isEvent = true
            buffer[index] = influence * value + (1 - influence) * buffer[(index - 1 + windowSize) % windowSize]
            index = (index + 1) % windowSize
            return sign(zScore)
        } else {
            isEvent = false
            buffer[index] = value
            index = (index + 1) % windowSize
            return 0f
        }
    }
}

class AdaptiveCompander(
    var attack: Float = 0.01f,
    var release: Float = 0.1f,
    var threshold: Float = 0.5f,
    var ratio: Float = 2.0f,
    var isCompressor: Boolean = true
) : FloatFilter {
    private var envelope = 0f

    override fun invoke(value: Float): Float {
        val absVal = abs(value)
        envelope = if (absVal > envelope) {
            attack * absVal + (1 - attack) * envelope
        } else {
            release * absVal + (1 - release) * envelope
        }

        val gain = if (isCompressor) {
            if (envelope > threshold) {
                threshold + (envelope - threshold) / ratio
            } else {
                envelope
            }
        } else {
            if (envelope < threshold) {
                threshold - (threshold - envelope) * ratio
            } else {
                envelope
            }
        }

        val multiplier = if (envelope > 0) gain / envelope else 1f
        return value * multiplier
    }
}

class AGC(
    var targetLevel: Float = 1.0f,
    var attack: Float = 0.01f,
    var release: Float = 0.001f,
    var maxGain: Float = 10.0f
) : FloatFilter {
    private var currentGain = 1.0f
    private var envelope = 0.0f

    override fun invoke(value: Float): Float {
        val absVal = abs(value)
        envelope = if (absVal > envelope) {
            attack * absVal + (1 - attack) * envelope
        } else {
            release * absVal + (1 - release) * envelope
        }

        if (envelope > 0) {
            val desiredGain = targetLevel / envelope
            currentGain = if (desiredGain > currentGain) {
                currentGain + attack * (desiredGain - currentGain)
            } else {
                currentGain + release * (desiredGain - currentGain)
            }
            currentGain = currentGain.coerceIn(0f, maxGain)
        }

        return value * currentGain
    }
}

class LeakyIntegrator(var leakRate: Float = 0.99f) : FloatFilter {
    private var sum = 0f
    override fun invoke(value: Float): Float {
        sum = sum * leakRate + value
        return sum
    }
}

class RollingNormalizer(val windowSize: Int, var targetMin: Float = -1f, var targetMax: Float = 1f) : FloatFilter {
    private val buffer = FloatArray(windowSize)
    private var index = 0
    private var count = 0

    override fun invoke(value: Float): Float {
        buffer[index] = value
        index = (index + 1) % windowSize
        if (count < windowSize) count++

        var minVal = Float.MAX_VALUE
        var maxVal = -Float.MAX_VALUE
        for (i in 0 until count) {
            if (buffer[i] < minVal) minVal = buffer[i]
            if (buffer[i] > maxVal) maxVal = buffer[i]
        }

        val range = maxVal - minVal
        if (range == 0f) return targetMin + (targetMax - targetMin) / 2f

        val normalized = (value - minVal) / range
        return targetMin + normalized * (targetMax - targetMin)
    }
}

class RollingOneDollarNormalizer(val windowSize: Int) : FloatFilter {
    private val buffer = FloatArray(windowSize)
    private var index = 0
    private var count = 0

    override fun invoke(value: Float): Float {
        buffer[index] = value
        index = (index + 1) % windowSize
        if (count < windowSize) count++

        var sum = 0f
        for (i in 0 until count) {
            sum += buffer[i]
        }
        val mean = sum / count

        var maxDev = 0f
        for (i in 0 until count) {
            val dev = abs(buffer[i] - mean)
            if (dev > maxDev) maxDev = dev
        }

        return if (maxDev > 0f) {
            (value - mean) / maxDev
        } else {
            0f
        }
    }
}

class AdaptiveThresholder(
    val windowSize: Int = 100,
    var stdDevMultiplier: Float = 2.0f,
    var clamp: Boolean = false
) : FloatFilter {
    private val buffer = FloatArray(windowSize)
    private var index = 0
    private var count = 0

    override fun invoke(value: Float): Float {
        buffer[index] = value
        index = (index + 1) % windowSize
        if (count < windowSize) count++

        var sum = 0f
        for (i in 0 until count) {
            sum += buffer[i]
        }
        val mean = sum / count

        var sqSum = 0f
        for (i in 0 until count) {
            val diff = buffer[i] - mean
            sqSum += diff * diff
        }
        val variance = if (count > 1) sqSum / (count - 1) else 0f
        val stdDev = sqrt(variance)

        val rangeHalf = if (stdDev > 0) stdDev * stdDevMultiplier else 1f

        val scaled = (value - mean) / rangeHalf
        return if (clamp) scaled.coerceIn(-1f, 1f) else scaled
    }
}

class DerivativeFilter : FloatFilter {
    private var lastValue: Float? = null
    override fun invoke(value: Float): Float {
        val current = lastValue
        lastValue = value
        return if (current == null) 0f else value - current
    }
}

class IntegralFilter : FloatFilter {
    private var sum = 0f
    override fun invoke(value: Float): Float {
        sum += value
        return sum
    }
}

class DCBlocker(var r: Float = 0.995f) : FloatFilter {
    private var x1 = 0f
    private var y1 = 0f

    override fun invoke(value: Float): Float {
        val y = value - x1 + r * y1
        x1 = value
        y1 = y
        return y
    }
}

class PeakDetector(var decay: Float = 0.99f) : FloatFilter {
    private var peak = 0f
    override fun invoke(value: Float): Float {
        val absVal = abs(value)
        if (absVal > peak) {
            peak = absVal
        } else {
            peak *= decay
        }
        return peak
    }
}

class SchmittTrigger(var lowThreshold: Float, var highThreshold: Float) : FloatFilter {
    private var state = false
    override fun invoke(value: Float): Float {
        if (state && value < lowThreshold) {
            state = false
        } else if (!state && value > highThreshold) {
            state = true
        }
        return if (state) 1f else 0f
    }
}

class MedianFilter(val windowSize: Int) : FloatFilter {
    private val buffer = FloatArray(windowSize)
    private var index = 0
    private var count = 0

    override fun invoke(value: Float): Float {
        buffer[index] = value
        index = (index + 1) % windowSize
        if (count < windowSize) count++

        val sorted = buffer.copyOfRange(0, count).apply { sort() }
        return if (count % 2 == 0) {
            (sorted[count / 2 - 1] + sorted[count / 2]) / 2f
        } else {
            sorted[count / 2]
        }
    }
}

class NoiseGate(var threshold: Float, var attack: Float = 0.01f, var release: Float = 0.1f) : FloatFilter {
    private var envelope = 0f
    private var isOpen = false

    override fun invoke(value: Float): Float {
        val absVal = abs(value)
        envelope = if (absVal > envelope) {
            attack * absVal + (1 - attack) * envelope
        } else {
            release * absVal + (1 - release) * envelope
        }

        if (envelope > threshold) {
            isOpen = true
        } else if (envelope < threshold * 0.5f) { // Hysteresis
            isOpen = false
        }

        return if (isOpen) value else 0f
    }
}

class PIDController(var kp: Float, var ki: Float, var kd: Float) : FloatFilter {
    private var integral = 0f
    private var prevError = 0f
    var setpoint = 0f

    override fun invoke(value: Float): Float {
        val error = setpoint - value
        integral += error
        val derivative = error - prevError
        prevError = error
        return kp * error + ki * integral + kd * derivative
    }
}

class CooldownFilter(val cooldownSamples: Int) : FloatFilter {
    private var samplesSinceLastEvent = cooldownSamples

    override fun invoke(value: Float): Float {
        if (value > 0f) {
            if (samplesSinceLastEvent >= cooldownSamples) {
                samplesSinceLastEvent = 0
                return value
            }
        }
        samplesSinceLastEvent++
        return 0f
    }
}

class StreamingWhitener(var alpha: Float = 0.01f, var epsilon: Float = 1e-5f) : FloatFilter {
    private var mean = 0f
    private var variance = 0f
    private var initialized = false

    override fun invoke(value: Float): Float {
        if (!initialized) {
            mean = value
            variance = 0f
            initialized = true
            return 0f
        }

        val diff = value - mean
        mean += alpha * diff
        variance = (1 - alpha) * variance + alpha * diff * diff

        val stdDev = sqrt(variance)
        return if (stdDev > epsilon) (value - mean) / stdDev else 0f
    }
}

class BimodalThresholder(var alpha: Float = 0.05f, var interpolate: Boolean = false) : FloatFilter {
    private var c1 = 0f
    private var c2 = 0f
    private var initialized = 0

    override fun invoke(value: Float): Float {
        if (initialized == 0) {
            c1 = value
            initialized = 1
            return 0f
        } else if (initialized == 1) {
            if (value != c1) {
                c2 = value
                if (c1 > c2) {
                    val temp = c1
                    c1 = c2
                    c2 = temp
                }
                initialized = 2
            }
            return 0f
        }

        val d1 = abs(value - c1)
        val d2 = abs(value - c2)

        if (d1 < d2) {
            c1 += alpha * (value - c1)
        } else {
            c2 += alpha * (value - c2)
        }

        if (c1 > c2) {
            val temp = c1
            c1 = c2
            c2 = temp
        }

        if (interpolate) {
            val range = c2 - c1
            if (range < 1e-5f) return 0f
            return ((value - c1) / range) * 2f - 1f
        } else {
            return if (d1 < d2) -1f else 1f
        }
    }
}

class PercentileThresholder(val windowSize: Int = 100, var mapToMinusOneToOne: Boolean = true) : FloatFilter {
    private val buffer = FloatArray(windowSize)
    private var index = 0
    private var count = 0

    override fun invoke(value: Float): Float {
        buffer[index] = value
        index = (index + 1) % windowSize
        if (count < windowSize) count++

        var lessThanCount = 0
        for (i in 0 until count) {
            if (buffer[i] < value) {
                lessThanCount++
            }
        }

        val percentile = if (count > 1) lessThanCount.toFloat() / (count - 1) else 0.5f

        return if (mapToMinusOneToOne) {
            percentile * 2f - 1f
        } else {
            percentile
        }
    }
}

class DynamicRangeCompressor(
    var thresholdDb: Float = -20f,
    var ratio: Float = 4f,
    var kneeWidthDb: Float = 10f,
    var attackMs: Float = 5f,
    var releaseMs: Float = 50f,
    var sampleRate: Float = 44100f,
    var makeupGainDb: Float = 0f
) : FloatFilter {
    private var envelope = 0f

    private fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)
    private fun linearToDb(linear: Float): Float = if (linear > 1e-5f) 20f * log10(linear) else -100f

    override fun invoke(value: Float): Float {
        val absVal = abs(value)
        val attackCoeff = exp(-1f / (attackMs * 0.001f * sampleRate))
        val releaseCoeff = exp(-1f / (releaseMs * 0.001f * sampleRate))

        envelope = if (absVal > envelope) {
            attackCoeff * envelope + (1 - attackCoeff) * absVal
        } else {
            releaseCoeff * envelope + (1 - releaseCoeff) * absVal
        }

        val envelopeDb = linearToDb(envelope)

        val overDb = envelopeDb - thresholdDb
        val gainReductionDb = if (overDb < -kneeWidthDb / 2f) {
            0f
        } else if (overDb > kneeWidthDb / 2f) {
            overDb * (1f - 1f / ratio)
        } else {
            val x = overDb + kneeWidthDb / 2f
            (1f - 1f / ratio) * (x * x) / (2f * kneeWidthDb)
        }

        val gainDb = makeupGainDb - gainReductionDb
        val gainLinear = dbToLinear(gainDb)

        return value * gainLinear
    }
}
