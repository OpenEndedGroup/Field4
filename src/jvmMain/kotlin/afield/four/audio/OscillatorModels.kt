@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package afield.four.audio

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

private const val OSC_TWO_PI = (2.0 * PI).toFloat()

/**
 * Scalar complex phasor reference implementation.
 *
 * This mirrors the Oscillators README building block: `z = z * w`, where the
 * real and imaginary parts provide cosine and sine waveforms.
 */
class Phasor(
    frequency: Float,
    private val sampleRate: Float,
    initialPhaseRadians: Float = 0.0f,
    private val direction: Float = 1.0f
) {
    private var omega = 0.0f
    private var wRe = 1.0f
    private var wIm = 0.0f

    var frequency: Float = frequency
        private set

    var real: Float = cos(initialPhaseRadians)
        private set

    var imag: Float = sin(initialPhaseRadians)
        private set

    val cosine: Float
        get() = real

    val sine: Float
        get() = imag

    init {
        require(sampleRate > 0.0f) { "sampleRate must be > 0" }
        require(direction == 1.0f || direction == -1.0f) { "direction must be +1 or -1" }
        setFrequency(frequency)
    }

    fun setFrequency(frequency: Float) {
        require(frequency >= 0.0f) { "frequency must be >= 0" }
        this.frequency = frequency
        omega = direction * OSC_TWO_PI * frequency / sampleRate
        wRe = cos(omega)
        wIm = sin(omega)
    }

    fun update() {
        val nextRe = real * wRe - imag * wIm
        val nextIm = real * wIm + imag * wRe
        real = nextRe
        imag = nextIm
    }

    fun stabilize() {
        val mag = sqrt(real * real + imag * imag)
        if (mag > 0.0f) {
            real /= mag
            imag /= mag
        } else {
            real = 1.0f
            imag = 0.0f
        }
    }
}

class Oscillator(
    frequency: Float,
    var amplitude: Float = 1.0f,
    sampleRate: Float,
    initialPhaseRadians: Float = 0.0f
) {
    val phasor = Phasor(frequency, sampleRate, initialPhaseRadians)

    var frequency: Float
        get() = phasor.frequency
        set(value) = phasor.setFrequency(value)

    fun updateCosine(): Float {
        val out = amplitude * phasor.cosine
        phasor.update()
        return out
    }

    fun updateSine(): Float {
        val out = amplitude * phasor.sine
        phasor.update()
        return out
    }

    fun renderCosine(dest: FloatArray, offset: Int = 0, length: Int = dest.size - offset) {
        require(offset >= 0 && length >= 0 && offset + length <= dest.size) { "bad render range" }
        for (i in 0 until length) {
            dest[offset + i] = updateCosine()
        }
    }
}

/**
 * Scalar resonator reference implementation.
 *
 * Uses the same two-stage EWMA and split complex state as [ResonatorBank], but
 * as one object for readability and API parity with the upstream README.
 */
open class Resonator(
    frequency: Float,
    val alpha: Float,
    val beta: Float = alpha,
    protected val sampleRate: Float
) {
    protected val phasor = Phasor(frequency, sampleRate, direction = -1.0f)
    protected var rRe = 0.0f
    protected var rIm = 0.0f
    protected var rrRe = 0.0f
    protected var rrIm = 0.0f

    val frequency: Float
        get() = phasor.frequency

    val real: Float
        get() = rrRe

    val imag: Float
        get() = rrIm

    val power: Float
        get() = rrRe * rrRe + rrIm * rrIm

    val amplitude: Float
        get() = sqrt(power)

    val phase: Float
        get() = atan2(rrIm, rrRe)

    val timeConstant: Float
        get() = if (alpha > 0.0f) 1.0f / (sampleRate * alpha) else 0.0f

    init {
        require(sampleRate > 0.0f) { "sampleRate must be > 0" }
        require(alpha in 0.0f..1.0f) { "alpha must be in [0, 1]" }
        require(beta in 0.0f..1.0f) { "beta must be in [0, 1]" }
    }

    open fun update(sample: Float) {
        rRe = rRe * (1.0f - alpha) + phasor.real * alpha * sample
        rIm = rIm * (1.0f - alpha) + phasor.imag * alpha * sample
        rrRe = rrRe * (1.0f - beta) + rRe * beta
        rrIm = rrIm * (1.0f - beta) + rIm * beta
        phasor.update()
    }

    fun update(samples: FloatArray, offset: Int = 0, length: Int = samples.size - offset, stride: Int = 1) {
        require(offset >= 0 && length >= 0 && stride > 0) { "bad update range" }
        var i = 0
        var idx = offset
        while (i < length) {
            require(idx in samples.indices) { "sample index out of range" }
            update(samples[idx])
            i += stride
            idx += stride
        }
        stabilize()
    }

    open fun reset() {
        rRe = 0.0f
        rIm = 0.0f
        rrRe = 0.0f
        rrIm = 0.0f
    }

    fun stabilize() {
        phasor.stabilize()
    }
}

class TrackingResonator(
    naturalFrequency: Float,
    alpha: Float,
    beta: Float = alpha,
    sampleRate: Float,
    val gamma: Float = trackingResonatorGammaHeuristic(naturalFrequency, sampleRate)
) : Resonator(naturalFrequency, alpha, beta, sampleRate) {
    private val naturalFrequency = naturalFrequency
    private var previousRe = 0.0f
    private var previousIm = 0.0f
    private var resonantOmega = -OSC_TWO_PI * naturalFrequency / sampleRate
    private var naturalOmega = resonantOmega
    private var sigma = 1.0f
    private var omSigma = 0.0f
    private var accumulatedPower = 0.0001f

    var trackingPowerThreshold: Float = 0.001f / 1000.0f
        private set

    val resonantFrequency: Float
        get() = -resonantOmega * sampleRate / OSC_TWO_PI

    val instantaneousFrequency: Float
        get() = resonantFrequency + deltaPhase * sampleRate / OSC_TWO_PI

    var deltaPhase: Float = 0.0f
        private set

    init {
        require(gamma in 0.0f..1.0f) { "gamma must be in [0, 1]" }
    }

    override fun update(sample: Float) {
        super.update(sample)

        val deltaRe = rrRe * previousRe + rrIm * previousIm
        val deltaIm = rrIm * previousRe - rrRe * previousIm
        deltaPhase = atan2(deltaIm, deltaRe)

        val trackedOmega = resonantOmega - gamma * deltaPhase
        resonantOmega = if (power >= trackingPowerThreshold) trackedOmega else naturalOmega
        phasor.setFrequency(-resonantOmega * sampleRate / OSC_TWO_PI)

        accumulatedPower = omSigma * accumulatedPower + sigma * power
        trackingPowerThreshold = kotlin.math.max(0.001f, accumulatedPower) / 1000.0f
        previousRe = rrRe
        previousIm = rrIm
    }

    fun setPowerTimeConstant(tau: Float = 1.0f) {
        require(tau > 0.0f) { "tau must be > 0" }
        val sampleDuration = 1.0f / sampleRate
        sigma = (1.0 - exp((-sampleDuration / tau).toDouble())).toFloat()
        omSigma = 1.0f - sigma
    }

    override fun reset() {
        super.reset()
        previousRe = 0.0f
        previousIm = 0.0f
        deltaPhase = 0.0f
        naturalOmega = -OSC_TWO_PI * naturalFrequency / sampleRate
        resonantOmega = naturalOmega
        phasor.setFrequency(naturalFrequency)
        accumulatedPower = 0.0001f
        trackingPowerThreshold = 0.001f / 1000.0f
    }
}
