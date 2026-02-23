package afield.stdlib

import kotlin.math.*

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
                b0 = (1 - cos(w0)) / 2
                b1 = 1 - cos(w0)
                b2 = (1 - cos(w0)) / 2
                a0 = 1 + alpha
                a1 = -2 * cos(w0)
                a2 = 1 - alpha
            }
            BiquadType.HIGHPASS -> {
                b0 = (1 + cos(w0)) / 2
                b1 = -(1 + cos(w0))
                b2 = (1 + cos(w0)) / 2
                a0 = 1 + alpha
                a1 = -2 * cos(w0)
                a2 = 1 - alpha
            }
            BiquadType.BANDPASS -> {
                b0 = alpha
                b1 = 0f
                b2 = -alpha
                a0 = 1 + alpha
                a1 = -2 * cos(w0)
                a2 = 1 - alpha
            }
            BiquadType.NOTCH -> {
                b0 = 1f
                b1 = -2 * cos(w0)
                b2 = 1f
                a0 = 1 + alpha
                a1 = -2 * cos(w0)
                a2 = 1 - alpha
            }
            BiquadType.PEAK -> {
                b0 = 1 + alpha * a
                b1 = -2 * cos(w0)
                b2 = 1 - alpha * a
                a0 = 1 + alpha / a
                a1 = -2 * cos(w0)
                a2 = 1 - alpha / a
            }
            BiquadType.LOWSHELF -> {
                b0 = a * ((a + 1) - (a - 1) * cos(w0) + 2 * sqrt(a) * alpha)
                b1 = 2 * a * ((a - 1) - (a + 1) * cos(w0))
                b2 = a * ((a + 1) - (a - 1) * cos(w0) - 2 * sqrt(a) * alpha)
                a0 = (a + 1) + (a - 1) * cos(w0) + 2 * sqrt(a) * alpha
                a1 = -2 * ((a - 1) + (a + 1) * cos(w0))
                a2 = (a + 1) + (a - 1) * cos(w0) - 2 * sqrt(a) * alpha
            }
            BiquadType.HIGHSHELF -> {
                b0 = a * ((a + 1) + (a - 1) * cos(w0) + 2 * sqrt(a) * alpha)
                b1 = -2 * a * ((a - 1) + (a + 1) * cos(w0))
                b2 = a * ((a + 1) + (a - 1) * cos(w0) - 2 * sqrt(a) * alpha)
                a0 = (a + 1) - (a - 1) * cos(w0) + 2 * sqrt(a) * alpha
                a1 = 2 * ((a - 1) - (a + 1) * cos(w0))
                a2 = (a + 1) - (a - 1) * cos(w0) - 2 * sqrt(a) * alpha
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
