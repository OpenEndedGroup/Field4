@file:afield.CompletionCategory("Math")

package afield.stdlib

import kotlin.math.*

/**
 * Streaming, time-domain filters over `Float` signals — the sample-at-a-time counterpart to the
 * frequency-domain code in `spectral.kt`.
 *
 * Everything here implements the [FloatFilter] fun interface (one `Float` in, one `Float` out)
 * and composes with `then`, so a chain reads as
 * `DCBlocker() then EMAFilter(0.1f) then NoiseGate(0.05f)`. Three families share that interface:
 * smoothers and classic filters ([EMAFilter], [AlphaBetaFilter], [OneEuroFilter], [BiquadFilter]
 * with its [LowPassFilter] / [HighPassFilter] / [BandPassFilter] / [BandRejectFilter]
 * constructors); rolling distribution fits — exponential, gamma, beta and Rayleigh — each
 * paired with a `Normalizer` mapping the fitted CDF into a target range and an `EventDetector`
 * firing on tail probability ([ZScoreEventDetector] instead thresholds on standard deviations
 * from a rolling mean); and utility stages such as [MedianFilter], [SchmittTrigger], [AGC],
 * [PIDController], [LeakyIntegrator] and [DynamicRangeCompressor].
 *
 * Pure Kotlin with no external dependency: the log-gamma, regularized incomplete gamma and
 * regularized incomplete beta functions the distribution fits need are implemented privately at
 * the top of this file. Filters are stateful objects, so use one instance per signal, and the
 * window-based ones allocate their ring buffer up front from `windowSize`.
 */

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

/**
 * One `Float` in, one `Float` out — the shape every filter in this file takes, and the reason they
 * chain. A filter is a stateful object called once per sample, in arrival order: it is not a pure
 * function, and calling it is what advances its state, so never call one twice on the same sample
 * just to read a number back out of it.
 *
 * Almost nothing here knows about time. A sample is a sample, so "smooth over half a second" means
 * picking a coefficient for whatever rate you happen to be feeding it at; the only stages that take
 * a sample rate in Hz are [BiquadFilter] and [DynamicRangeCompressor], and the only one that reads
 * a clock is [OneEuroFilter].
 */
fun interface FloatFilter {
    /** Push one sample through and take this stage's output for it, advancing the filter's state. */
    operator fun invoke(value: Float): Float

    /**
     * Series composition, left to right: `a then b` feeds `a`'s output into `b`, so
     * `DCBlocker() then EMAFilter(0.1f)` takes the offset out first and smooths what is left.
     *
     * The chain borrows both filters rather than copying them, so it shares their state with the
     * originals — build a fresh chain per signal instead of pointing two streams at one filter.
     */
    infix fun then(next: FloatFilter): FloatFilter = FloatFilter { value ->
        next(this@FloatFilter(value))
    }
}

/**
 * Exponential moving average — the cheapest useful smoother, `out = alpha * in + (1 - alpha) * out`.
 * One multiply-add and one field of state, which is why it is so often the first thing in a chain.
 *
 * [alpha] is a per-sample weight in `0..1`, not a time and not a frequency: 1 is a pass-through,
 * 0.1 is heavy smoothing, 0 freezes the output at whatever arrived first. Because it counts samples
 * rather than seconds, the same alpha smooths twice as hard if you double your frame rate. The
 * first sample is passed through untouched to seed the state, so there is no ramp up from zero;
 * values outside `0..1` are accepted without complaint and will overshoot or oscillate.
 */
class EMAFilter(var alpha: Float) : FloatFilter {
    private var lastValue: Float? = null
    /** The smoothed value for [value] — on the very first call, [value] itself. */
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

/**
 * A constant-velocity tracker with fixed gains — a Kalman filter's structure without its
 * bookkeeping. It carries a position *and* a velocity, predicts one step ahead, then corrects both
 * from the residual, so unlike [EMAFilter] it keeps up with a steadily moving signal instead of
 * trailing behind it.
 *
 * [alpha] corrects position and [beta] corrects velocity (both nominally `0..1`; larger trusts the
 * measurement, smaller trusts the model). [dt] is the spacing between samples in whatever unit you
 * want velocity expressed in — nothing here reads a clock, so with irregular arrivals you have to
 * keep `dt` current yourself, and note the velocity gain is `beta / dt`, which blows up as `dt`
 * approaches zero. The first sample seeds the position and comes straight back out with velocity
 * zero. Only the position estimate is returned; the velocity stays private.
 */
class AlphaBetaFilter(var alpha: Float, var beta: Float, var dt: Float = 1f) : FloatFilter {
    private var x: Float? = null
    private var v: Float = 0f

    /** The corrected position estimate for [value]. The internal velocity is updated too, but is not exposed. */
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

/**
 * The 1€ filter (Casiez, Roussel & Vogel): a low-pass whose cutoff rises with the signal's own
 * speed, so a slow-moving hand gets smoothed hard while a fast gesture is barely delayed. The usual
 * pick for pointer, touch or tracker input, where lag is more objectionable than jitter.
 *
 * **It reads the wall clock.** Every call takes `System.currentTimeMillis()` and derives `dt` from
 * the gap since the previous call, floored at 1 ms, which makes this the one filter here that is
 * not deterministic. Pushing a stored array through it in a tight loop yields `dt = 1 ms` and so far
 * heavier smoothing than the same data arriving at, say, 120 Hz. The cutoffs are in Hz, so they only
 * mean what you think when the samples really do arrive in real time. The first sample is passed
 * through to seed the state.
 */
class OneEuroFilter(
    /** Cutoff (Hz) used when the signal is standing still — lower is smoother and laggier. */
    var minCutoff: Float = 1.0f,
    /**
     * How strongly measured speed raises the cutoff: `cutoff = minCutoff + beta * |speed|`. At 0
     * the adaptation is off and this is an ordinary one-pole low-pass at [minCutoff].
     */
    var beta: Float = 0.0f,
    /**
     * Cutoff (Hz) of the low-pass applied to the speed estimate itself. Raise it if the adaptation
     * feels sluggish, lower it if noise in the speed makes the cutoff jitter.
     */
    var dCutoff: Float = 1.0f
) : FloatFilter {
    private var xPrev: Float? = null
    private var dxPrev: Float = 0f
    private var tPrev: Long = -1L

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1.0f / (2.0f * PI.toFloat() * cutoff)
        return 1.0f / (1.0f + tau / dt)
    }

    /**
     * The filtered sample. Reads the clock, so the amount of smoothing depends on *when* you call
     * this, not only on what you pass in.
     */
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

/**
 * Which of the RBJ audio-EQ-cookbook shapes a [BiquadFilter] realises. `LOWPASS`, `HIGHPASS`,
 * `BANDPASS` and `NOTCH` are determined entirely by frequency and Q; `PEAK`, `LOWSHELF` and
 * `HIGHSHELF` are the only three that read `gainDb` — the others accept it and ignore it.
 */
enum class BiquadType {
    LOWPASS, HIGHPASS, BANDPASS, NOTCH, PEAK, LOWSHELF, HIGHSHELF
}

/**
 * A second-order IIR section — two poles, two zeros — run as a direct-form I recursion with
 * coefficients from the RBJ audio-EQ cookbook. This is the machinery behind [LowPassFilter],
 * [HighPassFilter], [BandPassFilter] and [BandRejectFilter], and what to reach for when you want a
 * filter with an actual corner frequency rather than a vague smoothing coefficient.
 *
 * [frequency] and [sampleRate] are both in Hz and only their ratio matters; keep `frequency` well
 * below `sampleRate / 2` or the design degenerates. Two sharp edges: the four tuning parameters are
 * `var` but nothing watches them, so after changing one you must call [calculateCoefficients]
 * yourself; and the recursion has no NaN guard, so one non-finite input lodges in the delay line and
 * every output after it is NaN. The delay line starts at zero, so the first few outputs are the
 * filter's own settling transient rather than signal.
 */
class BiquadFilter(
    /** The response shape. Fixed at construction — build a new filter to change it. */
    val type: BiquadType,
    /** Corner (or centre) frequency in Hz. Call [calculateCoefficients] after changing it. */
    var frequency: Float,
    /**
     * The rate in Hz of the stream you will push through: the reference [frequency] is measured
     * against, and not something the filter can discover for itself.
     */
    var sampleRate: Float,
    /**
     * Resonance. `0.707` is maximally flat (Butterworth); higher peaks at the corner and rings, and
     * for [BiquadType.BANDPASS] / [BiquadType.NOTCH] higher also means a narrower band.
     */
    var q: Float = 0.707f,
    /**
     * Boost (positive) or cut (negative) in dB — read only by [BiquadType.PEAK],
     * [BiquadType.LOWSHELF] and [BiquadType.HIGHSHELF], and silently ignored by the other four.
     */
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

    /**
     * Re-derive the five coefficients from the current [type], [frequency], [sampleRate], [q] and
     * [gainDb]. Run once at construction; run it again yourself after mutating any of those, because
     * nothing else will. It deliberately leaves the delay line alone, so a mid-stream retune stays
     * continuous in the signal but can step the output audibly.
     */
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

    /**
     * One step of `y = b0·x + b1·x₋₁ + b2·x₋₂ − a1·y₋₁ − a2·y₋₂`, shifting the two input and two
     * output delays along afterwards.
     */
    override fun invoke(value: Float): Float {
        val y = b0 * value + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = value
        y2 = y1
        y1 = y
        return y
    }
}

/**
 * A [BiquadFilter] that passes what is below [frequency] (Hz) and rolls off above it at
 * 12 dB/octave. [sampleRate] is the rate of the stream in Hz — both are needed, because only their
 * ratio means anything. [q] defaults to the maximally-flat 0.707; raise it for a resonant bump at
 * the corner.
 */
fun LowPassFilter(frequency: Float, sampleRate: Float, q: Float = 0.707f) = BiquadFilter(BiquadType.LOWPASS, frequency, sampleRate, q)

/**
 * A [BiquadFilter] that passes what is above [frequency] (Hz) and rolls off below it at
 * 12 dB/octave — the steeper, better-specified alternative to [DCBlocker] once you know the corner
 * you actually want.
 */
fun HighPassFilter(frequency: Float, sampleRate: Float, q: Float = 0.707f) = BiquadFilter(BiquadType.HIGHPASS, frequency, sampleRate, q)

/**
 * A [BiquadFilter] that keeps a band around [frequency] (Hz) and rejects both sides of it. With a
 * single biquad, [q] *is* the bandwidth (`bandwidth ≈ frequency / q`), so the 0.707 default is very
 * wide indeed — push it to 4 or 8 for something that isolates a component.
 */
fun BandPassFilter(frequency: Float, sampleRate: Float, q: Float = 0.707f) = BiquadFilter(BiquadType.BANDPASS, frequency, sampleRate, q)

/**
 * A [BiquadFilter] that notches out [frequency] (Hz) and passes everything else — mains hum, a rig's
 * resonance, one offending tone. Higher [q] gives a narrower notch; this is [BiquadType.NOTCH] under
 * a friendlier name.
 */
fun BandRejectFilter(frequency: Float, sampleRate: Float, q: Float = 0.707f) = BiquadFilter(BiquadType.NOTCH, frequency, sampleRate, q)

/**
 * A tap rather than a filter: every sample passes through unchanged, and the window's [mean],
 * [variance], [stdDev] and the current sample's [zScore] are left behind for you to read. Drop it
 * anywhere in a `then` chain to find out what the signal is doing at that point without disturbing
 * it.
 *
 * The statistics cover the last `windowSize` samples *including* the one just pushed, and before the
 * window has filled they cover only what has arrived so far. [variance] is the sample (n−1) variance
 * and stays 0 until a second sample lands; [zScore] is 0 rather than infinite when the window is
 * flat. Everything is recomputed from scratch each sample — O(windowSize) per call, which is fine
 * for hundreds and wasteful for millions.
 */
class RollingStats(val windowSize: Int) : FloatFilter {
    private val buffer = FloatArray(windowSize)
    private var index = 0
    private var count = 0

    /** Arithmetic mean of the window, the sample just pushed included. */
    var mean: Float = 0f
        private set
    /** Sample (n−1) variance of the window; 0 until a second sample has arrived. */
    var variance: Float = 0f
        private set
    /** Square root of [variance]. */
    var stdDev: Float = 0f
        private set
    /**
     * How many standard deviations the sample just pushed sits from [mean] — signed, and 0 whenever
     * [stdDev] is 0. Note the sample is itself in the window it is being compared against.
     */
    var zScore: Float = 0f
        private set

    /** Records [value] into the window, refreshes all four statistics, and hands [value] straight back. */
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

/**
 * Fits an exponential distribution to the last `windowSize` samples by moment matching
 * (`lambda = 1 / mean`) and reports where the newest sample falls within it. A tap: the sample
 * passes through unchanged.
 *
 * The exponential is the right shape for a non-negative, memoryless stream — gaps between events, an
 * intensity that is usually near zero and occasionally large. Negative values are outside its
 * support, so they are clamped to 0 for the fit and counted in [clippedNegativeCount] rather than
 * throwing. [cdf] and [tailProbability] describe the *current* sample against the fitted law, which
 * makes `tailProbability` the "how surprising is this" number: 0.01 means only 1% of the fitted
 * distribution lies this high. While the window mean is at or below [epsilon] there is nothing to
 * fit and the pair degrades to `cdf = 0`, `tailProbability = 1` — nothing is surprising.
 */
class RollingExponentialStats(
    /** How many samples the fit sees. Must be `>= 2`; the ring buffer is allocated up front. */
    val windowSize: Int,
    /**
     * Degeneracy floor. While the fitted mean or rate is at or below this the fit is abandoned
     * rather than dividing by something near zero.
     */
    var epsilon: Float = DISTRIBUTION_EPSILON
) : FloatFilter {
    private val buffer = FloatArray(windowSize)
    private var index = 0
    private var count = 0

    /** Mean of the window after negative clamping — the only statistic an exponential fit needs. */
    var mean: Float = 0f
        private set
    /** Fitted rate `1 / mean`, or 0 while the window mean is at or below [epsilon]. */
    var lambda: Float = 0f
        private set
    /** Variance *of the fitted distribution*, `1 / lambda²` — not the sample variance of the window. */
    var variance: Float = 0f
        private set
    /** Square root of [variance], so also `1 / lambda`. */
    var stdDev: Float = 0f
        private set
    /** `P(X <= x)` under the fit for the sample just pushed, in `0..1`. */
    var cdf: Float = 0f
        private set
    /**
     * `P(X >= x) = 1 - cdf` for the sample just pushed: small means a surprisingly large sample.
     * Sits at 1 during the degenerate case, so a silent signal reports nothing as unusual.
     */
    var tailProbability: Float = 1f
        private set
    /**
     * How many negative samples have been clamped to zero so far. Anything but 0 means the
     * exponential is the wrong model for this signal — take `abs()` first, or use [RollingBetaStats].
     */
    var clippedNegativeCount: Long = 0
        private set

    init {
        require(windowSize >= 2) { "windowSize must be >= 2" }
    }

    /**
     * Clamps [value] to `>= 0`, refits the window, updates [cdf] and [tailProbability] — and returns
     * the original [value], clamping and all left behind in the fit.
     */
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

/**
 * Rescales a non-negative stream by *rank* rather than by magnitude: each sample's fitted exponential
 * CDF, mapped onto `targetMin..targetMax`. What you want when an unbounded intensity has to drive
 * something that expects a bounded control value, and "big for this signal lately" matters more than
 * the units.
 *
 * The output is monotone in the input for a given fit and lands inside `targetMin..targetMax`
 * (`0..1` by default), spending most of its time in the lower part of that range because that is what
 * an exponential does. `windowSize` and `epsilon` are handed to [stats] once at construction; the
 * live knobs afterwards are [targetMin], [targetMax] and `stats.epsilon`.
 */
class ExponentialNormalizer(
    windowSize: Int,
    /** The value a CDF of 0 maps to. */
    var targetMin: Float = 0f,
    /** The value a CDF of 1 maps to. May be set below [targetMin] to invert the mapping. */
    var targetMax: Float = 1f,
    epsilon: Float = DISTRIBUTION_EPSILON
) : FloatFilter {
    /**
     * The fit doing the work — read `stats.lambda`, `stats.tailProbability` or
     * `stats.clippedNegativeCount` to see what the normaliser is basing its answer on.
     */
    val stats = RollingExponentialStats(windowSize = windowSize, epsilon = epsilon)

    /** Pushes [value] through the fit and returns its CDF mapped into `targetMin..targetMax`. */
    override fun invoke(value: Float): Float {
        stats(value)
        return cdfToTargetRange(stats.cdf, targetMin, targetMax)
    }
}

/**
 * Fires when a sample is improbably large for the exponential distribution the preceding window
 * fits — an onset / spike detector for non-negative streams, in the same family as
 * [ZScoreEventDetector] but thresholding tail probability instead of standard deviations.
 *
 * The output is a flag, not a signal: `1f` on the sample that fired and `0f` otherwise ([isEvent]
 * carries the same answer as a Boolean). The window has to fill before anything can fire, so the
 * first `windowSize` samples always return 0 while it warms up. Negatives are clamped to 0 and
 * counted in [clippedNegativeCount]. [influence] is the classic anti-self-poisoning knob: at 0 an
 * event's own sample never enters the window — the previous sample is stored in its place — so one
 * big spike cannot raise the bar against the next one.
 */
class ExponentialEventDetector(
    /**
     * Samples of history behind the fit. Must be `>= 2`, and nothing can fire until this many have
     * arrived.
     */
    val windowSize: Int,
    /**
     * Fire when the sample's upper-tail probability falls to this or below — at 0.01, "in the top 1%
     * of the fitted distribution". Clamped into `0..1` on use.
     */
    var tailProbabilityThreshold: Float = 0.01f,
    /**
     * How much of an event's own sample is written into the window, `0..1` (clamped): 0 keeps events
     * out of the fit entirely, 1 lets them recalibrate it so a sustained shift stops being news.
     */
    var influence: Float = 0f,
    /** Degeneracy floor — while the window mean is at or below this, no events are reported at all. */
    var epsilon: Float = DISTRIBUTION_EPSILON
) : FloatFilter {
    private val buffer = FloatArray(windowSize)
    private var index = 0
    private var count = 0

    /** Whether the most recent sample fired — the same news as a nonzero return from [invoke]. */
    var isEvent: Boolean = false
        private set
    /** Upper-tail probability of the most recent sample; 1 during warm-up and while the fit is degenerate. */
    var tailProbability: Float = 1f
        private set
    /** Fitted exponential rate `1 / mean` over the window, or 0 while degenerate. */
    var lambda: Float = 0f
        private set
    /** How many negative samples have been clamped to zero — anything but 0 means the wrong model for this signal. */
    var clippedNegativeCount: Long = 0
        private set

    init {
        require(windowSize >= 2) { "windowSize must be >= 2" }
    }

    /** `1f` if [value] is an event, `0f` otherwise — including for every sample of the warm-up. */
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

/**
 * Fits a gamma distribution to the last `windowSize` samples by matching mean and variance
 * (`shape = mean²/variance`, `scale = variance/mean`) and reports where the newest sample falls
 * within it. A tap: the sample passes through unchanged.
 *
 * The gamma is the two-parameter generalisation of the exponential, so it fits a non-negative,
 * right-skewed stream whose bulk is *not* pressed up against zero — the magnitude of a movement, an
 * energy, an inter-onset interval. Negative values are clamped to 0 for the fit and counted in
 * [clippedNegativeCount]. [cdf] is the regularized lower incomplete gamma evaluated at `x / scale`,
 * computed in `Double` by this file's own series/continued-fraction implementation, with no external
 * dependency. When either the window mean or its variance is at or below [epsilon] the fit is
 * abandoned: shape and scale go to 0, `cdf` to 0 and `tailProbability` to 1.
 */
class RollingGammaStats(
    /** How many samples the fit sees. Must be `>= 2`; the ring buffer is allocated up front. */
    val windowSize: Int,
    /** Degeneracy floor for the window's mean and variance, and the lower bound clamped onto the fitted shape and scale. */
    var epsilon: Float = DISTRIBUTION_EPSILON
) : FloatFilter {
    private val buffer = FloatArray(windowSize)
    private var index = 0
    private var count = 0

    /** Arithmetic mean of the window after negative clamping — half of the moment-matching fit. */
    var mean: Float = 0f
        private set
    /** Sample (n−1) variance of the window — a genuine sample statistic here, and the other half of the fit. */
    var variance: Float = 0f
        private set
    /** Square root of [variance]. */
    var stdDev: Float = 0f
        private set
    /**
     * Fitted gamma shape `mean²/variance` (the `k` parameter): 1 would be an exponential, larger is
     * more symmetric. 0 while the fit is degenerate.
     */
    var shape: Float = 0f
        private set
    /** Fitted gamma scale `variance/mean` (the `θ` parameter), in the units of your signal. 0 while degenerate. */
    var scale: Float = 0f
        private set
    /** `P(X <= x)` under the fit for the sample just pushed, in `0..1`. */
    var cdf: Float = 0f
        private set
    /** `1 - cdf`: the share of the fitted distribution lying above the current sample. Small means surprisingly large. */
    var tailProbability: Float = 1f
        private set
    /** How many negative samples have been clamped to zero — anything but 0 means a gamma is the wrong model here. */
    var clippedNegativeCount: Long = 0
        private set

    init {
        require(windowSize >= 2) { "windowSize must be >= 2" }
    }

    /**
     * Clamps [value] to `>= 0`, re-matches shape and scale over the window, updates [cdf] and
     * [tailProbability], and returns the original [value] untouched.
     */
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

/**
 * Rescales a non-negative, skewed stream onto `targetMin..targetMax` by its fitted gamma CDF — the
 * two-parameter sibling of [ExponentialNormalizer], and the better pick whenever the signal's bulk
 * sits somewhere away from zero rather than hugging it.
 *
 * Output lands inside `targetMin..targetMax` (`0..1` by default) and is monotone in the input for a
 * given fit. `windowSize` and `epsilon` are forwarded to [stats] at construction; afterwards the
 * live knobs are [targetMin], [targetMax] and `stats.epsilon`.
 */
class GammaNormalizer(
    windowSize: Int,
    /** The value a CDF of 0 maps to. */
    var targetMin: Float = 0f,
    /** The value a CDF of 1 maps to. May be set below [targetMin] to invert the mapping. */
    var targetMax: Float = 1f,
    epsilon: Float = DISTRIBUTION_EPSILON
) : FloatFilter {
    /** The fit doing the work — read `stats.shape` and `stats.scale` to see what distribution the answers came from. */
    val stats = RollingGammaStats(windowSize = windowSize, epsilon = epsilon)

    /** Pushes [value] through the fit and returns its CDF mapped into `targetMin..targetMax`. */
    override fun invoke(value: Float): Float {
        stats(value)
        return cdfToTargetRange(stats.cdf, targetMin, targetMax)
    }
}

/**
 * Fires when a sample lands in the upper tail of the gamma distribution the preceding window fits.
 * The general-purpose distribution-aware onset detector in this file: it copes with the skewed,
 * non-negative streams — magnitudes, energies, `abs()` of a motion signal — where a z-score's
 * assumption of symmetry makes it over-fire on the low side. This is the detector the published
 * `timeseries_examples` page reaches for.
 *
 * Output is a flag: `1f` on the sample that fired, `0f` otherwise, including throughout the
 * `windowSize`-sample warm-up. Negatives are clamped to 0 and counted in [clippedNegativeCount].
 * With [influence] at 0 an event's own sample is kept out of the window, so a spike cannot raise the
 * bar against the next one.
 */
class GammaEventDetector(
    /** Samples of history behind the fit. Must be `>= 2`, and nothing fires until this many have arrived. */
    val windowSize: Int,
    /**
     * Fire when the sample's upper-tail probability falls to this or below — at 0.01, "in the top 1%
     * of the fitted distribution". Clamped into `0..1` on use.
     */
    var tailProbabilityThreshold: Float = 0.01f,
    /**
     * How much of an event's own sample enters the window, `0..1` (clamped): 0 keeps events out of
     * the fit, 1 lets them recalibrate it.
     */
    var influence: Float = 0f,
    /** Degeneracy floor for the window's mean and variance — below it, no events are reported. */
    var epsilon: Float = DISTRIBUTION_EPSILON
) : FloatFilter {
    private val buffer = FloatArray(windowSize)
    private var index = 0
    private var count = 0

    /** Whether the most recent sample fired. */
    var isEvent: Boolean = false
        private set
    /** `P(X <= x)` of the most recent sample under the fit; 0 during warm-up and while degenerate. */
    var cdf: Float = 0f
        private set
    /** `1 - cdf` for the most recent sample — the quantity actually compared with [tailProbabilityThreshold]. */
    var tailProbability: Float = 1f
        private set
    /** Fitted gamma shape over the window, or 0 while the fit is degenerate. */
    var shape: Float = 0f
        private set
    /** Fitted gamma scale over the window, in the units of your signal, or 0 while degenerate. */
    var scale: Float = 0f
        private set
    /** How many negative samples have been clamped to zero — anything but 0 means the wrong model for this signal. */
    var clippedNegativeCount: Long = 0
        private set

    init {
        require(windowSize >= 2) { "windowSize must be >= 2" }
    }

    /** `1f` if [value] is an event, `0f` otherwise — including for every sample of the warm-up. */
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

/**
 * Fits a beta distribution to the last `windowSize` samples by moment matching and reports where the
 * newest one falls within it. A tap: the sample passes through unchanged.
 *
 * The beta is the distribution for a *bounded* quantity, so this one has a support. Values are first
 * mapped from `supportMin..supportMax` onto `0..1` — clipped at both ends, with the clipping counted
 * in [clippedToSupportCount] — then nudged just inside the open interval by [epsilon] before being
 * stored, because a beta has no density at exactly 0 or 1. Unlike its exponential and gamma cousins
 * it is genuinely two-sided: [tailProbabilityLow] and [tailProbabilityHigh] make an unusually *small*
 * value as reportable as an unusually large one. When the moment fit is impossible — a near-constant
 * window, or a mean pressed against either end — it falls back to plain min/max normalisation,
 * leaving `alpha = beta = 0` and `cdf` equal to the sample's position within the support. Note that
 * [epsilon] defaults to 1e-4 here, looser than the 1e-6 the unbounded fits use, because it has to
 * hold samples off both boundaries.
 */
class RollingBetaStats(
    /** How many samples the fit sees. Must be `>= 2`; the ring buffer is allocated up front. */
    val windowSize: Int,
    /** Bottom of your signal's assumed range; anything below maps to 0 and counts as clipped. */
    var supportMin: Float = 0f,
    /**
     * Top of your signal's assumed range; anything above maps to 1 and counts as clipped. Must be
     * strictly greater than [supportMin] at construction.
     */
    var supportMax: Float = 1f,
    /**
     * How far inside the open interval `(0, 1)` stored samples are held, and the degeneracy floor for
     * the moment fit. Defaults to 1e-4, deliberately looser than the other fits in this file.
     */
    var epsilon: Float = 1e-4f
) : FloatFilter {
    private val buffer = FloatArray(windowSize)
    private var index = 0
    private var count = 0

    /** Mean of the window in *normalised* `0..1` units, not in the units of your signal. */
    var mean: Float = 0f
        private set
    /** Sample (n−1) variance of the window, also in normalised units. */
    var variance: Float = 0f
        private set
    /** Square root of [variance]. */
    var stdDev: Float = 0f
        private set
    /** Fitted beta `α`, the shape pulling mass toward 1; 0 when the fit fell back to min/max normalisation. */
    var alpha: Float = 0f
        private set
    /** Fitted beta `β`, the shape pulling mass toward 0; 0 when the fit fell back to min/max normalisation. */
    var beta: Float = 0f
        private set
    /**
     * `P(X <= x)` under the fit for the sample just pushed, in `0..1` — or, when the fit is
     * degenerate, simply that sample's normalised position within the support.
     */
    var cdf: Float = 0f
        private set
    /** Lower-tail probability of the current sample; equal to [cdf]. Small means unusually low for this window. */
    var tailProbabilityLow: Float = 0f
        private set
    /** Upper-tail probability of the current sample, `1 - cdf`. Small means unusually high. */
    var tailProbabilityHigh: Float = 1f
        private set
    /**
     * How many samples have fallen outside `supportMin..supportMax` and been clipped into it. A count
     * that keeps climbing means the support is wrong.
     */
    var clippedToSupportCount: Long = 0
        private set
    /**
     * The sample just pushed, mapped onto `0..1` across the support — clipped there but *before* the
     * epsilon nudge, so unlike the value stored in the window this one can be exactly 0 or 1.
     */
    var normalizedValue: Float = 0.5f
        private set

    init {
        require(windowSize >= 2) { "windowSize must be >= 2" }
        require(supportMax > supportMin) { "supportMax must be greater than supportMin" }
    }

    /**
     * Normalises [value] into the support, refits the window, updates the CDF and both tails, and
     * returns the original [value] unchanged.
     */
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

/**
 * Rescales a bounded stream onto `targetMin..targetMax` by its fitted beta CDF — histogram
 * equalisation with a two-parameter distribution standing in for the histogram. Where
 * [RollingNormalizer] merely stretches the window's extremes apart, this spreads samples out
 * according to how they are actually distributed, so a signal that spends its life bunched in one
 * corner of its range comes out using the whole target range.
 *
 * `supportMin`, `supportMax`, `windowSize` and `epsilon` are handed to [stats] at construction, so to
 * move the support later set `stats.supportMin` / `stats.supportMax` rather than looking for a
 * property here.
 */
class BetaNormalizer(
    windowSize: Int,
    /** The value a CDF of 0 maps to. */
    var targetMin: Float = 0f,
    /** The value a CDF of 1 maps to. May be set below [targetMin] to invert the mapping. */
    var targetMax: Float = 1f,
    supportMin: Float = 0f,
    supportMax: Float = 1f,
    epsilon: Float = 1e-4f
) : FloatFilter {
    /**
     * The fit doing the work. Also where the support lives after construction — and where
     * `clippedToSupportCount` will tell you the support is wrong.
     */
    val stats = RollingBetaStats(
        windowSize = windowSize,
        supportMin = supportMin,
        supportMax = supportMax,
        epsilon = epsilon
    )

    /** Pushes [value] through the fit and returns its CDF mapped into `targetMin..targetMax`. */
    override fun invoke(value: Float): Float {
        stats(value)
        return cdfToTargetRange(stats.cdf, targetMin, targetMax)
    }
}

/**
 * A two-sided event detector for a bounded stream: it fires both when a sample is improbably high for
 * the window's fitted beta and when it is improbably low, and the sign of the output says which.
 * `-1f` for a lower-tail event, `+1f` for an upper-tail one, `0f` for nothing — including for the
 * whole `windowSize`-sample warm-up.
 *
 * That sign matters downstream: [CooldownFilter], for one, only passes positive values, so a
 * lower-tail event fed straight into it vanishes. Values outside `supportMin..supportMax` are clipped
 * into it and counted in [clippedToSupportCount]. As with the other detectors here, [influence] at 0
 * keeps an event's own sample out of the window so a spike cannot raise its own bar. When the moment
 * fit is impossible the detector degrades to thresholding the sample's plain normalised position
 * within the support.
 */
class BetaEventDetector(
    /** Samples of history behind the fit. Must be `>= 2`, and nothing fires until this many have arrived. */
    val windowSize: Int,
    /** Bottom of your signal's assumed range; anything below is clipped to it and counted. */
    var supportMin: Float = 0f,
    /** Top of your signal's assumed range; must be strictly greater than [supportMin] at construction. */
    var supportMax: Float = 1f,
    /**
     * Fire low when the CDF falls to this or below — at 0.01, "in the bottom 1% of the fit". Clamped
     * into `0..1`; set above 0.5 and nearly everything becomes an event.
     */
    var lowerTailThreshold: Float = 0.01f,
    /** Fire high when `1 - cdf` falls to this or below — the top 1% at 0.01. Clamped into `0..1`. */
    var upperTailThreshold: Float = 0.01f,
    /**
     * How much of an event's own sample enters the window, `0..1` (clamped): 0 keeps events out of the
     * fit, 1 lets a sustained shift recalibrate it.
     */
    var influence: Float = 0f,
    /** Boundary nudge for stored samples and degeneracy floor for the moment fit; 1e-4 by default. */
    var epsilon: Float = 1e-4f
) : FloatFilter {
    private val buffer = FloatArray(windowSize)
    private var index = 0
    private var count = 0

    /** Whether the most recent sample fired at either end. It does not say which end — the sign of [invoke]'s return does. */
    var isEvent: Boolean = false
        private set
    /** CDF of the most recent sample under the fit, or its normalised position when degenerate; parked at 0.5 during warm-up. */
    var cdf: Float = 0.5f
        private set
    /** Fitted beta `α` over the window; 0 when the fit fell back to plain normalisation. */
    var alpha: Float = 0f
        private set
    /** Fitted beta `β` over the window; 0 when the fit fell back to plain normalisation. */
    var beta: Float = 0f
        private set
    /** How many samples have been clipped into `supportMin..supportMax` — a climbing count means the support is wrong. */
    var clippedToSupportCount: Long = 0
        private set

    init {
        require(windowSize >= 2) { "windowSize must be >= 2" }
        require(supportMax > supportMin) { "supportMax must be greater than supportMin" }
    }

    /**
     * `-1f` for a lower-tail event, `+1f` for an upper-tail one, `0f` for neither and for the whole
     * warm-up. If both thresholds somehow fire at once, the lower tail wins.
     */
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

/**
 * Fits a Rayleigh distribution to the last `windowSize` samples and reports where the newest one
 * falls within it. A tap: the sample passes through unchanged.
 *
 * Rayleigh is the distribution of the *magnitude* of a two-dimensional Gaussian, which makes it the
 * honest model for speeds, `hypot(dx, dy)` jitter, gradient magnitudes — anywhere you took the length
 * of a noisy 2-D vector. It has a single parameter, estimated here straight from the raw second
 * moment (`sigma = sqrt(mean(x²) / 2)`, the maximum-likelihood estimator), so it needs no variance
 * and settles faster than the gamma fit. Negatives are outside its support: clamped to 0 and counted
 * in [clippedNegativeCount]. Be aware that [mean], [variance] and [stdDev] here are the *fitted*
 * distribution's moments derived from [sigma], not sample statistics of the window.
 */
class RollingRayleighStats(
    /** How many samples the fit sees. Must be `>= 2`; the ring buffer is allocated up front. */
    val windowSize: Int,
    /** Degeneracy floor on the window's second moment and on [sigma] — below it, nothing is reported. */
    var epsilon: Float = DISTRIBUTION_EPSILON
) : FloatFilter {
    private val buffer = FloatArray(windowSize)
    private var index = 0
    private var count = 0

    /** Fitted Rayleigh scale, `sqrt(mean(x²) / 2)`; 0 while the window's second moment is at or below [epsilon]. */
    var sigma: Float = 0f
        private set
    /** Mean *of the fitted distribution*, `sigma * sqrt(π/2)` — derived from [sigma], not averaged from the window. */
    var mean: Float = 0f
        private set
    /** Variance of the fitted distribution, `((4 - π)/2) * sigma²`. */
    var variance: Float = 0f
        private set
    /** Square root of [variance]. */
    var stdDev: Float = 0f
        private set
    /** `P(X <= x) = 1 - exp(-x² / 2sigma²)` for the sample just pushed. */
    var cdf: Float = 0f
        private set
    /** `1 - cdf`: the share of the fitted distribution above the current sample. Small means a surprisingly large magnitude. */
    var tailProbability: Float = 1f
        private set
    /**
     * How many negative samples have been clamped to zero. A magnitude should never be negative, so
     * anything but 0 means the wrong signal is arriving here.
     */
    var clippedNegativeCount: Long = 0
        private set

    init {
        require(windowSize >= 2) { "windowSize must be >= 2" }
    }

    /**
     * Clamps [value] to `>= 0`, re-estimates [sigma] from the window's second moment, updates the
     * fitted moments and the CDF, and returns the original [value] unchanged.
     */
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

/**
 * Rescales a stream of magnitudes onto `targetMin..targetMax` by its fitted Rayleigh CDF. The one to
 * use when the numbers arriving are lengths of 2-D vectors — cursor speed, optical-flow magnitude,
 * gradient strength — because a single-parameter fit adapts on far less history than
 * [GammaNormalizer] needs.
 *
 * Output lands inside `targetMin..targetMax` (`0..1` by default). `windowSize` and `epsilon` go to
 * [stats] at construction; the live knobs afterwards are [targetMin], [targetMax] and `stats.epsilon`.
 */
class RayleighNormalizer(
    windowSize: Int,
    /** The value a CDF of 0 maps to. */
    var targetMin: Float = 0f,
    /** The value a CDF of 1 maps to. May be set below [targetMin] to invert the mapping. */
    var targetMax: Float = 1f,
    epsilon: Float = DISTRIBUTION_EPSILON
) : FloatFilter {
    /** The fit doing the work — `stats.sigma` is the single number the whole mapping rests on. */
    val stats = RollingRayleighStats(windowSize = windowSize, epsilon = epsilon)

    /** Pushes [value] through the fit and returns its CDF mapped into `targetMin..targetMax`. */
    override fun invoke(value: Float): Float {
        stats(value)
        return cdfToTargetRange(stats.cdf, targetMin, targetMax)
    }
}

/**
 * Fires when a magnitude is improbably large for the Rayleigh distribution the preceding window fits.
 * The detector to pair with a speed or vector-length signal: a sudden lunge, a flick, a burst of
 * optical flow.
 *
 * Output is a flag: `1f` on the sample that fired, `0f` otherwise, including throughout the
 * `windowSize`-sample warm-up. Only the upper tail is tested — an unusually *slow* moment is not an
 * event here. Negatives are clamped to 0 and counted in [clippedNegativeCount]; [influence] at 0
 * keeps an event's own sample out of the window so a spike cannot raise its own bar.
 */
class RayleighEventDetector(
    /** Samples of history behind the fit. Must be `>= 2`, and nothing fires until this many have arrived. */
    val windowSize: Int,
    /**
     * Fire when the sample's upper-tail probability falls to this or below — at 0.01, "in the top 1% of
     * the fitted distribution". Clamped into `0..1` on use.
     */
    var tailProbabilityThreshold: Float = 0.01f,
    /**
     * How much of an event's own sample enters the window, `0..1` (clamped): 0 keeps events out of the
     * fit, 1 lets them recalibrate it.
     */
    var influence: Float = 0f,
    /** Degeneracy floor on the window's second moment and on [sigma] — below it, no events are reported. */
    var epsilon: Float = DISTRIBUTION_EPSILON
) : FloatFilter {
    private val buffer = FloatArray(windowSize)
    private var index = 0
    private var count = 0

    /** Whether the most recent sample fired. */
    var isEvent: Boolean = false
        private set
    /** Fitted Rayleigh scale over the window, `sqrt(mean(x²) / 2)`, or 0 while degenerate. */
    var sigma: Float = 0f
        private set
    /** `P(X <= x)` of the most recent sample under the fit; 0 during warm-up and while degenerate. */
    var cdf: Float = 0f
        private set
    /** `1 - cdf` for the most recent sample — the quantity compared with [tailProbabilityThreshold]. */
    var tailProbability: Float = 1f
        private set
    /** How many negative samples have been clamped to zero; a magnitude should never be negative. */
    var clippedNegativeCount: Long = 0
        private set

    init {
        require(windowSize >= 2) { "windowSize must be >= 2" }
    }

    /** `1f` if [value] is an event, `0f` otherwise — including for every sample of the warm-up. */
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

/**
 * The classic smoothed z-score peak detector: it fires when the newest sample sits more than
 * [threshold] standard deviations from the mean of the window, and its output carries the direction —
 * `+1f` above, `-1f` below, `0f` for no event and throughout the warm-up.
 *
 * This is the distribution-free member of the detector family. It assumes only that the window is
 * roughly symmetric, which makes it the right first thing to try on a signal that swings both ways and
 * the wrong one for a skewed non-negative stream, where [GammaEventDetector] earns its keep. Two
 * details worth knowing: the standard deviation here is the population (÷n) one, so it reads slightly
 * smaller than [RollingStats.stdDev] over the same samples; and unlike the distribution detectors this
 * one uses [influence] unclamped, so a value outside `0..1` will corrupt the window rather than being
 * quietly corrected.
 */
class ZScoreEventDetector(
    /** Samples of history behind the mean and standard deviation; nothing fires until this many have arrived. */
    val windowSize: Int,
    /**
     * How many standard deviations from the window mean counts as an event — 3 to 4 is a conventional
     * starting point. The test is strict and on `|z|`, so it fires in both directions.
     */
    var threshold: Float,
    /**
     * How much of an event's own sample enters the window, nominally `0..1` but **not** clamped: 0
     * substitutes the previous sample, so a sustained level shift keeps being reported forever, while 1
     * lets the detector adapt to it and fall silent.
     */
    var influence: Float
) : FloatFilter {
    private val buffer = FloatArray(windowSize)
    private var index = 0
    private var count = 0

    /** Whether the most recent sample fired, in either direction. */
    var isEvent: Boolean = false
        private set

    /**
     * `+1f` or `-1f` for an event above or below the window, `0f` for none — and `0f` for the first
     * `windowSize` samples while the window fills, and whenever the window is perfectly flat.
     */
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

/**
 * An envelope follower plus a static gain curve: it either squashes the loud parts toward [threshold]
 * (compressor) or pushes the quiet parts further down (expander), depending on [isCompressor].
 * Everything is in linear units, which makes this the plain, cheap sibling of
 * [DynamicRangeCompressor] — that one does the same job in dB, with a soft knee and real attack and
 * release times.
 *
 * [attack] and [release] are per-sample smoothing coefficients in `0..1`, not milliseconds: larger is
 * *faster*, and they mean nothing in seconds until you know your own sample rate. [threshold] is a
 * linear amplitude and [ratio] a plain divisor above it. One sharp edge: in the expander branch a
 * large enough [ratio] drives the computed gain negative and the output flips sign, so keep an
 * expander's ratio modest or check what comes out.
 */
class AdaptiveCompander(
    /** Per-sample coefficient (`0..1`) used while the envelope is *rising*; larger reacts faster to a growing signal. */
    var attack: Float = 0.01f,
    /** Per-sample coefficient (`0..1`) used while the envelope is *falling*; larger lets the gain recover faster after a peak. */
    var release: Float = 0.1f,
    /** The linear amplitude where the curve bends — above it for a compressor, below it for an expander. Not dB. */
    var threshold: Float = 0.5f,
    /**
     * How hard the curve bends: as a compressor the excess above [threshold] is divided by this; as an
     * expander the shortfall below it is multiplied by it.
     */
    var ratio: Float = 2.0f,
    /** `true` compresses (pull the loud parts in), `false` expands (push the quiet parts down). Switchable mid-stream. */
    var isCompressor: Boolean = true
) : FloatFilter {
    private var envelope = 0f

    /** Updates the envelope from `abs(value)` and returns [value] scaled by the gain the curve asks for at that envelope. */
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

/**
 * Automatic gain control: follows the signal's envelope and scales the signal so that envelope sits at
 * [targetLevel]. Reach for it when you care about the *shape* of a signal whose absolute scale you
 * cannot predict — a sensor whose range depends on how it was mounted, a control stream you want to
 * hand to something expecting `0..1`.
 *
 * [attack] and [release] are per-sample coefficients in `0..1` (larger = faster) doing double duty:
 * they smooth the envelope, and they smooth the gain's approach to whatever the envelope asks for. The
 * gain is held in `0..maxGain`, which is the only thing stopping it running away: the envelope decays
 * *multiplicatively*, so a gap of exact zeros never quite reaches zero and the gain keeps climbing
 * toward [maxGain] at the [release] rate for as long as the silence lasts. Expect a gap to be followed
 * by a loud sample, and being a pure multiplier it will happily amplify noise up to [targetLevel] if
 * you give it nothing else — gate first with [NoiseGate] if either matters.
 */
class AGC(
    /** The envelope level the gain chases, in the same linear units as your signal. */
    var targetLevel: Float = 1.0f,
    /** Per-sample coefficient (`0..1`) used when the envelope rises and when the gain must increase; larger is faster. */
    var attack: Float = 0.01f,
    /**
     * Per-sample coefficient (`0..1`) used when the envelope falls and when the gain must decrease. The
     * default is deliberately ten times slower than [attack], so the gain backs off gently.
     */
    var release: Float = 0.001f,
    /** Ceiling on the multiplier, so a near-silent stretch cannot be amplified without limit. */
    var maxGain: Float = 10.0f
) : FloatFilter {
    private var currentGain = 1.0f
    private var envelope = 0.0f

    /** Updates the envelope and the smoothed gain, then returns [value] multiplied by that gain. */
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

/**
 * A running sum that forgets: `sum = sum * leakRate + value`. The signal-processing way to ask "how
 * much has been going on lately" — feed it event flags or rectified magnitudes and it climbs with
 * activity, then decays back down when things go quiet.
 *
 * [leakRate] is a per-sample retention factor in `0..1`, so the memory is roughly `1 / (1 - leakRate)`
 * samples: 0.99 remembers about a hundred. Mind the DC gain that same figure implies — a constant input
 * `x` settles at `x / (1 - leakRate)`, which is 100× at the default, so the output is emphatically not
 * on the scale of the input. A [leakRate] of 1 or more never forgets and diverges.
 */
class LeakyIntegrator(var leakRate: Float = 0.99f) : FloatFilter {
    private var sum = 0f
    /** Leaks the accumulator once, adds [value], and returns the new total. Starts from 0 and has no reset. */
    override fun invoke(value: Float): Float {
        sum = sum * leakRate + value
        return sum
    }
}

/**
 * Stretches the last `windowSize` samples onto `targetMin..targetMax` using their own minimum and
 * maximum — the simplest auto-ranging there is, and the one to use when you know nothing about the
 * signal's distribution and just need something bounded. Note the default target is `-1..1`, not `0..1`.
 *
 * Because the extremes come from the window, the output is not stable the way a distribution fit's is:
 * every time a new record arrives the output snaps to the end of the range and everything before it is
 * retroactively rescaled, and one outlier squashes the rest of the range for the next `windowSize`
 * samples. A flat window has no range to stretch, so it returns the midpoint of the target range rather
 * than dividing by zero. Before the window fills, only the samples that have arrived count. If the
 * squashing bothers you, [BetaNormalizer] and [PercentileThresholder] use the shape of the distribution
 * instead of only its ends.
 */
class RollingNormalizer(val windowSize: Int, var targetMin: Float = -1f, var targetMax: Float = 1f) : FloatFilter {
    private val buffer = FloatArray(windowSize)
    private var index = 0
    private var count = 0

    /**
     * Records [value], then returns it rescaled from the window's current min..max onto
     * `targetMin..targetMax` — or the midpoint of that range while the window is flat.
     */
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

/**
 * Centres the stream on its window mean and divides by the largest absolute deviation in that window,
 * so the output lands in `-1..1` with `±1` reached exactly by whichever sample is currently furthest
 * from the mean. A symmetric, zero-centred alternative to [RollingNormalizer]: "which side of typical"
 * stays meaningful, at the price of letting one outlier set the scale for a whole window.
 *
 * A constant window has no deviation to divide by, so the output is 0 rather than NaN, and during
 * warm-up only the samples that have arrived count.
 */
class RollingOneDollarNormalizer(val windowSize: Int) : FloatFilter {
    private val buffer = FloatArray(windowSize)
    private var index = 0
    private var count = 0

    /**
     * Records [value] and returns its deviation from the window mean divided by the window's largest
     * absolute deviation; 0 while the window is constant.
     */
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

/**
 * Despite the name this returns a continuous value, not a decision: the sample's distance from the
 * window mean, measured in units of [stdDevMultiplier] standard deviations. A sample sitting exactly
 * `stdDevMultiplier` sigma above the mean comes out as `+1`, and it is thresholding that downstream —
 * or a [SchmittTrigger] — that turns it into a decision.
 *
 * Think of it as the auto-ranging cousin of [RollingNormalizer] that uses spread rather than extremes,
 * so a lone outlier moves the scale much less. The standard deviation is the sample (n−1) one over the
 * last `windowSize` samples, the current one included; when the window is flat the divisor falls back to
 * 1, which means you get the raw deviation rather than a division by zero. With [clamp] the result is
 * coerced into `-1..1`; without it, it is unbounded.
 */
class AdaptiveThresholder(
    /** Samples of history behind the mean and standard deviation. The buffer is allocated up front. */
    val windowSize: Int = 100,
    /** How many standard deviations map to an output of 1. Larger is a gentler scale. */
    var stdDevMultiplier: Float = 2.0f,
    /** Coerce the output into `-1..1` instead of letting it run past the ends. */
    var clamp: Boolean = false
) : FloatFilter {
    private val buffer = FloatArray(windowSize)
    private var index = 0
    private var count = 0

    /**
     * Records [value] and returns its deviation from the window mean divided by
     * `stdDev * stdDevMultiplier`, optionally clamped to `-1..1`.
     */
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

/**
 * First difference: this sample minus the previous one. The cheapest way to turn something
 * position-shaped into something velocity-shaped, or to find the edges in a slow signal.
 *
 * Per *sample*, not per second — there is no `dt` here, so divide by your frame interval yourself if you
 * want real units. The first sample has nothing to difference against and returns 0. Differencing
 * amplifies high-frequency noise, so smooth first: `EMAFilter(0.2f) then DerivativeFilter()`, not the
 * other way round.
 */
class DerivativeFilter : FloatFilter {
    private var lastValue: Float? = null
    /** The change since the previous sample, or 0 on the first call. */
    override fun invoke(value: Float): Float {
        val current = lastValue
        lastValue = value
        return if (current == null) 0f else value - current
    }
}

/**
 * A plain running total of everything it has ever been given — for accumulating an event count, or for
 * turning a velocity-shaped signal back into a position-shaped one.
 *
 * It never forgets and there is no reset, so any DC offset in the input, however small, walks the output
 * away without bound; over a long-running session that is a real problem rather than a theoretical one.
 * Put a [DCBlocker] in front of it, or use [LeakyIntegrator] and accept a bounded, decaying memory
 * instead.
 */
class IntegralFilter : FloatFilter {
    private var sum = 0f
    /** Adds [value] to the total and returns the new total. */
    override fun invoke(value: Float): Float {
        sum += value
        return sum
    }
}

/**
 * Removes whatever constant offset a signal is riding on, without waiting for a window to fill: a
 * one-pole, one-zero high-pass, `y = x - x₋₁ + r·y₋₁`, parked just above DC. The standard thing to put
 * at the head of a chain when you care how a sensor is *changing* rather than where its zero happens to
 * sit.
 *
 * [r] sets how close to DC the corner is: it lands near `(1 - r) / 2π` cycles per sample, so the 0.995
 * default is about 0.0008 of the sample rate — roughly 0.1 Hz at 120 Hz — and pushing `r` toward 1
 * lowers it further at the cost of a longer settling time. The state starts at zero, so the first output
 * equals the first input and the offset only fades over the next few time constants; `r` at or above 1
 * never decays at all.
 */
class DCBlocker(var r: Float = 0.995f) : FloatFilter {
    private var x1 = 0f
    private var y1 = 0f

    /** One step of `y = x - x₋₁ + r·y₋₁` — the input with its slow-moving offset taken out. */
    override fun invoke(value: Float): Float {
        val y = value - x1 + r * y1
        x1 = value
        y1 = y
        return y
    }
}

/**
 * A peak-hold envelope: it jumps instantly to `abs(value)` whenever that exceeds the current peak, and
 * otherwise decays toward zero by a factor of [decay] each sample. What you want for a level meter, a
 * "how hard was that hit" reading, or the denominator of a hand-rolled normaliser.
 *
 * The output is always non-negative and never below the current sample's magnitude. [decay] is a
 * per-sample retention factor in `0..1` — 0.99 falls to about a third over a hundred samples, 1 holds the
 * all-time maximum forever. There is no attack smoothing whatsoever, so one glitchy sample becomes the
 * peak; feed it through a [MedianFilter] first if the input has isolated spikes.
 */
class PeakDetector(var decay: Float = 0.99f) : FloatFilter {
    private var peak = 0f
    /** The held peak after [value]: its magnitude if that is a new high, otherwise the previous peak decayed once — but never less than the magnitude in hand. */
    override fun invoke(value: Float): Float {
        val absVal = abs(value)
        // The floor is the point. Decaying unconditionally whenever the sample was not a *new* high put the
        // output below the sample it was supposedly enveloping, and made a constant input sawtooth between
        // `A` and `A * decay` forever: A > A is false, so it decayed, and then A > A*decay re-latched.
        peak = maxOf(absVal, peak * decay)
        return peak
    }
}

/**
 * A comparator with hysteresis: it goes high once the input rises above [highThreshold] and only comes
 * back down once the input falls below [lowThreshold], so a signal loitering around a single threshold
 * cannot chatter. Returns `1f` while high and `0f` while low.
 *
 * The gap between the two thresholds is the noise immunity you are buying, so pass
 * `lowThreshold < highThreshold`: with them the wrong way round, a single crossing both sets and clears
 * the state and you get exactly the chatter you were trying to avoid. It starts low, so a signal that
 * begins above [highThreshold] still needs one sample to latch, and both comparisons are strict.
 */
class SchmittTrigger(var lowThreshold: Float, var highThreshold: Float) : FloatFilter {
    private var state = false
    /** `1f` while latched high and `0f` while low, after letting [value] set or clear the latch. */
    override fun invoke(value: Float): Float {
        if (state && value < lowThreshold) {
            state = false
        } else if (!state && value > highThreshold) {
            state = true
        }
        return if (state) 1f else 0f
    }
}

/**
 * Rolling median of the last `windowSize` samples. The spike killer: unlike any moving average it removes
 * an isolated outlier completely instead of spreading it over its neighbourhood, and it leaves real edges
 * where they are instead of smearing them into ramps.
 *
 * Before the window fills, the median is taken over however many samples have arrived — and the parity that
 * decides whether two middle samples are averaged is that *count*, not `windowSize`, so even an odd window
 * can return a value that never appeared in the input until it has filled. Once full, an even `windowSize`
 * always averages and an odd one always returns an actual sample. The cost is a copy and a sort every sample, so
 * keep the window small — this is not the filter to run with a window of ten thousand — and remember it
 * delays the signal by roughly half the window.
 */
class MedianFilter(val windowSize: Int) : FloatFilter {
    private val buffer = FloatArray(windowSize)
    private var index = 0
    private var count = 0

    /** Records [value] and returns the median of the window, averaging the two middle samples when the count is even. */
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

/**
 * Passes the signal through untouched while it is loud enough and hard-zeroes it when it is not. The gate
 * opens when the tracked envelope rises above `threshold` and closes only once the envelope falls below
 * *half* that, so a signal hovering at the threshold does not flutter.
 *
 * Note what it does not do: there is no fade. The sample after the gate shuts is exactly `0f`, which on an
 * audio-rate signal you will hear as a click. It gates on the smoothed envelope but passes the raw sample,
 * which is what you want for quieting an idle sensor. `attack` and `release` are per-sample coefficients in
 * `0..1` (larger = faster), not times, and `threshold` is a linear amplitude compared against the envelope
 * rather than against the sample.
 */
class NoiseGate(var threshold: Float, var attack: Float = 0.01f, var release: Float = 0.1f) : FloatFilter {
    private var envelope = 0f
    private var isOpen = false

    /** [value] unchanged while the gate is open, `0f` while it is shut — after this sample has updated the envelope. */
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

/**
 * A textbook proportional-integral-derivative controller wired up as a filter: it reads the *measured*
 * value and returns the control effort that should push it toward [setpoint]. In a box this is what to
 * reach for when one thing should chase another — a camera trailing a target, a value easing toward the
 * mouse — with overshoot you get to tune rather than inherit.
 *
 * Everything is per sample, with no `dt` anywhere: the integral is a plain sum of errors and the derivative
 * a plain difference, so `ki` and `kd` have to be retuned if your frame rate changes. There is no
 * anti-windup and no output limit, so if the plant cannot keep up the integral term grows without bound and
 * stays there — and no way to clear the accumulated integral short of building a new controller.
 */
class PIDController(var kp: Float, var ki: Float, var kd: Float) : FloatFilter {
    private var integral = 0f
    private var prevError = 0f
    /** Where the controller is trying to get the measured value to. Safe to move mid-stream, though the accumulated integral term does not know it moved. */
    var setpoint = 0f

    /** The control output for a measurement of [value]: `kp·e + ki·Σe + kd·Δe`, where `e = setpoint - value`. */
    override fun invoke(value: Float): Float {
        val error = setpoint - value
        integral += error
        val derivative = error - prevError
        prevError = error
        return kp * error + ki * integral + kd * derivative
    }
}

/**
 * A debouncer for a stream of event flags: it lets a positive sample through, then swallows everything for
 * the next `cooldownSamples` samples. Put it after a detector to turn a burst of flags at one onset into a
 * single flag.
 *
 * Only *positive* values are events. Zero passes through as zero and — the trap — so does a negative one:
 * [BetaEventDetector]'s `-1f` lower-tail events are silently dropped here, so debounce `abs()` of that
 * signal or handle the two directions separately. The very first event is always allowed through (the
 * counter starts full), and the minimum spacing between two accepted events is `cooldownSamples + 1`
 * samples, because the sample that fires does not itself count toward the next cooldown.
 */
class CooldownFilter(val cooldownSamples: Int) : FloatFilter {
    private var samplesSinceLastEvent = cooldownSamples

    /** [value] itself if it is positive and the cooldown has elapsed, otherwise `0f`. */
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

/**
 * A z-score with no window: it tracks the mean and the variance exponentially and returns how far the
 * current sample sits from that running mean, in running standard deviations. The O(1)-memory alternative
 * to [RollingStats] plus arithmetic, and the natural pre-stage for anything that wants a roughly zero-mean,
 * unit-variance input.
 *
 * `alpha` is the per-sample adaptation rate in `0..1` — the effective memory is about `1/alpha` samples, so
 * the 0.01 default tracks over a few hundred. The first sample seeds the mean and returns 0, since there is
 * no spread to divide by yet, and the output is 0 whenever the running standard deviation is at or below
 * `epsilon`, which is also what a constant input produces. One honest quirk: the mean is updated *before*
 * the residual is taken, so the output is systematically `(1 - alpha)` times the z-score you would compute
 * by hand — negligible at small alpha, visible if you push alpha near 1.
 */
class StreamingWhitener(var alpha: Float = 0.01f, var epsilon: Float = 1e-5f) : FloatFilter {
    private var mean = 0f
    private var variance = 0f
    private var initialized = false

    /**
     * The current sample's distance from the running mean in running standard deviations; 0 on the first
     * call and whenever the signal is flat.
     */
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

/**
 * Online two-means clustering of a stream: it keeps two centroids, moves whichever is nearer toward each new
 * sample, and reports which of the two the sample joined. For a signal that really does live at two levels —
 * a switch, a contact sensor, a light that is on or off, a gesture held or not — this finds the two levels
 * for you instead of making you guess a threshold, and it follows them as they drift.
 *
 * The centroids are kept in order, so the answer is `-1f` for the lower cluster and `+1f` for the upper. Set
 * `interpolate` to get the sample's position between them mapped onto `-1..1` instead — a soft membership,
 * and not clamped, so a sample outside the pair reads beyond `±1`. Warm-up is unusual: the first sample seeds
 * *a* centroid — not necessarily the lower one, since the pair is put in order the moment the second value
 * arrives and a lower second value therefore takes the low slot — and the filter returns 0 until a second,
 * *different* value arrives, so a stream that starts with a run of identical samples stays silent for exactly
 * that long. If the two centroids ever converge to within 1e-5, the interpolated output falls back to 0.
 * `alpha` is the per-sample learning rate in `0..1`: small enough to be stable, large enough to follow drift.
 * Keep it there — the nearer centroid moves by `alpha` times its distance to the sample, which cannot carry it
 * past the other one for any `alpha` at or below 2, and the label is decided from the distances *before* that
 * move, so an `alpha` above 2 would report the two clusters the wrong way round.
 */
class BimodalThresholder(var alpha: Float = 0.05f, var interpolate: Boolean = false) : FloatFilter {
    private var c1 = 0f
    private var c2 = 0f
    private var initialized = 0

    /**
     * `-1f` or `+1f` for the lower or upper cluster — or the interpolated position between the centroids when
     * `interpolate` is set — and `0f` until both centroids have been seeded.
     */
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

/**
 * Reports where the current sample ranks within the last `windowSize` samples: the fraction of the *rest* of
 * the window lying strictly below it. (The current sample is in the window but can never count itself, so the
 * denominator is one less than the fill level and a new high reads exactly 1.0.) Completely distribution-free — no mean, no standard deviation, no fitted law — so a
 * heavy-tailed or lopsided signal cannot mislead it, and one wild outlier moves the output by exactly one rank.
 *
 * By default the rank is mapped onto `-1..1`, where 0 is the middle of the window; clear
 * `mapToMinusOneToOne` for a plain `0..1`. Because the comparison is strict, a run of identical samples all
 * read as the *lowest* rank rather than as the middle, and the very first sample reads as the exact middle
 * (0.5, so `0f` after mapping). The rank is recounted over the window every sample, so cost is O(windowSize)
 * per call.
 */
class PercentileThresholder(val windowSize: Int = 100, var mapToMinusOneToOne: Boolean = true) : FloatFilter {
    private val buffer = FloatArray(windowSize)
    private var index = 0
    private var count = 0

    /**
     * The fraction of the rest of the window strictly below [value] — mapped onto `-1..1`, or left as `0..1`
     * when `mapToMinusOneToOne` is false.
     */
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

/**
 * A proper dB-domain compressor: envelope follower, soft knee, attack and release in milliseconds, makeup
 * gain. Where [AdaptiveCompander] works in linear amplitude with per-sample coefficients, this one is
 * specified the way a compressor usually is, which makes it the one to use on anything audio-shaped, or on
 * anything whose timing you would rather state in milliseconds than in samples.
 *
 * Because the times are in milliseconds, [sampleRate] has to match the rate you are actually pushing samples
 * at — it is the only thing converting one into the other. Left at the 44100 default on a 60 Hz control
 * signal, the 5 ms attack becomes 220 samples and so behaves like 3.7 *seconds*, and the 50 ms release like
 * a little over half a minute. Everything else is in dB relative to a full-scale magnitude
 * of 1, and the envelope is floored at −100 dB (a linear 1e-5) so silence cannot ask for infinite gain. The
 * knee is quadratic and centred on the threshold, spanning [kneeWidthDb] in total. Worth knowing for hot
 * loops: the two coefficients are re-derived with an `exp()` each on every sample rather than cached when the
 * times change.
 */
class DynamicRangeCompressor(
    /** Level (dB, full scale = 0) above which gain reduction begins. Negative values are the norm. */
    var thresholdDb: Float = -20f,
    /** Compression ratio above the knee: 4 means 4 dB in over threshold yields 1 dB out. 1 is no compression at all. */
    var ratio: Float = 4f,
    /**
     * Total width (dB) of the soft knee straddling [thresholdDb] — compression eases in across this span
     * instead of switching on abruptly. 0 gives a hard knee.
     */
    var kneeWidthDb: Float = 10f,
    /** How quickly (ms) the envelope follows a rising signal. Converted to a per-sample coefficient using [sampleRate]. */
    var attackMs: Float = 5f,
    /** How quickly (ms) the envelope falls after a peak, and so how quickly the gain recovers. Also converted using [sampleRate]. */
    var releaseMs: Float = 50f,
    /**
     * The rate (Hz) at which samples arrive, which is what gives [attackMs] and [releaseMs] their meaning. The
     * 44100 default is an audio assumption — set it to your frame rate for a control signal.
     */
    var sampleRate: Float = 44100f,
    /** Flat gain (dB) applied after the compression, to win back the headroom it just took. Applied at all levels, reduction or not. */
    var makeupGainDb: Float = 0f
) : FloatFilter {
    private var envelope = 0f

    private fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)
    private fun linearToDb(linear: Float): Float = if (linear > 1e-5f) 20f * log10(linear) else -100f

    /** Updates the envelope, reads the knee curve at that level, and returns [value] scaled by the resulting gain plus [makeupGainDb]. */
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
        val halfKnee = kneeWidthDb / 2f
        val slope = 1f - 1f / ratio
        val gainReductionDb = when {
            // A zero-width knee has no quadratic to evaluate: the `else` branch would compute 0 / (2 * 0) and
            // hand back NaN for any sample sitting exactly on the threshold — which silence does, since the
            // envelope floors at exactly -100 dB.
            kneeWidthDb <= 0f -> if (overDb > 0f) overDb * slope else 0f
            overDb < -halfKnee -> 0f
            overDb > halfKnee -> overDb * slope
            else -> {
                val x = overDb + halfKnee
                slope * (x * x) / (2f * kneeWidthDb)
            }
        }

        val gainDb = makeupGainDb - gainReductionDb
        val gainLinear = dbToLinear(gainDb)

        return value * gainLinear
    }
}
