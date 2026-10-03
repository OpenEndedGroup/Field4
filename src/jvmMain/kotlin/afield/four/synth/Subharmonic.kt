@file:afield.CompletionCategory("Synthesis")
package afield.four.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Subharmonic synthesis: follow the pitch of a roughly monophonic input and generate a new tone
 * at a fraction of it — an octave down at `division = 2.0`, an octave and a fifth at `3.0`, a
 * fifth at `1.5`.
 *
 * Three trackers share one output stage ([SubharmonicTracker]):
 *
 *  * [SubharmonicZeroCross] times the gap between positive-going zero crossings. Cheap and
 *    instant, and it believes every crossing, so harmonics and noise that add crossings make it
 *    jump.
 *  * [SubharmonicPLL] is a second-order phase-locked loop with a multiplying phase detector. It
 *    owns a continuous phase and frequency estimate, so the output glides instead of jumping,
 *    but its error signal scales with the input's level and carries a ripple at twice the input
 *    frequency.
 *  * [SubharmonicQuadPLL] first splits the input into a quadrature pair with [HilbertPair], so it
 *    can measure the phase error directly — independent of level and free of that ripple — and
 *    adds a frequency discriminator that pulls the loop onto a new note quickly.
 *
 * None of them divides the input's wrapped phase (`sin(phase / 2)` jumps by half a cycle every
 * time the phase wraps). Each keeps its own oscillator advancing at the tracked rate divided by
 * [SubharmonicTracker.division], so `division` can be any number, changed every sample.
 *
 * A tracker is a per-sample object: call [SubharmonicTracker.process] on each sample, or put it in
 * a signal graph with [Subharmonic]. It returns the generated subharmonic only; mix the input back
 * in yourself.
 */
abstract class SubharmonicTracker(
    val sampleRate: Double,
    /** The ratio between the tracked frequency and the generated one. Any value, any time. */
    var division: Double = 2.0,
) {
    protected val twoPi = 2.0 * PI

    /**
     * Several divisions at once, each with its own gain and phase. Empty (the default) plays the
     * single [division]. `listOf(SubVoice(2.0, 0.7), SubVoice(3.0, 0.2), SubVoice(4.0, 0.1))` is
     * the octave, octave-and-fifth and two-octave stack.
     */
    var voices: List<SubVoice> = emptyList()
        set(value) { field = value; voicePhases = DoubleArray(value.size) }

    private var voicePhases = DoubleArray(0)
    private var subPhase = 0.0

    /** The tracked input frequency in radians per sample; 0 before anything has been tracked. */
    protected var omega = 0.0

    /** The amplitude the generated tone is scaled by, following the input's level. */
    protected var envelope = 0.0

    /** The input frequency the tracker currently believes in, in Hz (0 before it has one). */
    val trackedHz: Double get() = omega * sampleRate / twoPi

    /** The level the generated tone currently has before any voice gains. */
    val level: Double get() = envelope

    /** Update [omega] and [envelope] from one input sample. */
    protected abstract fun track(x: Double)

    /** Forget the tracked pitch, phases and level. */
    open fun reset() {
        omega = 0.0; envelope = 0.0; subPhase = 0.0
        voicePhases.fill(0.0)
    }

    /** Process one input sample and return the generated subharmonic. */
    fun process(input: Float): Float {
        track(input.toDouble())
        if (omega == 0.0) return 0f
        if (voices.isEmpty()) {
            subPhase = wrap(subPhase + omega / division)
            return (sin(subPhase) * envelope).toFloat()
        }
        var y = 0.0
        for (v in voices.indices) {
            voicePhases[v] = wrap(voicePhases[v] + omega / voices[v].division)
            y += voices[v].gain * sin(voicePhases[v])
        }
        return (y * envelope).toFloat()
    }

    /** Run every sample of [input] through [process]. */
    fun process(input: FloatArray): FloatArray = FloatArray(input.size) { process(input[it]) }

    protected fun wrap(p: Double): Double {
        var x = p
        while (x >= twoPi) x -= twoPi
        while (x < 0.0) x += twoPi
        return x
    }

    protected fun smoothing(seconds: Double) = exp(-1.0 / (seconds * sampleRate))
}

/** One division in a [SubharmonicTracker.voices] stack. */
data class SubVoice(val division: Double, val gain: Double = 1.0)

// ═══════════════════════════════════════════════════════════════════
//  Zero-crossing tracker
// ═══════════════════════════════════════════════════════════════════

/**
 * Tracks pitch by timing the interval between positive-going zero crossings.
 *
 * A crossing interval outside `minInputHz..maxInputHz` is ignored; one inside is smoothed into the
 * estimate over [trackingTimeSeconds]. The level follows the rectified input over
 * [amplitudeTimeSeconds]. It produces nothing until the first accepted interval.
 *
 * It needs a clean input. Every extra crossing a harmonic or a breath of noise adds is a new,
 * shorter "period", so feed it something lowpassed below the second harmonic of the lowest note.
 */
class SubharmonicZeroCross(
    sampleRate: Double = 48_000.0,
    division: Double = 2.0,
    minInputHz: Double = 40.0,
    maxInputHz: Double = 400.0,
    trackingTimeSeconds: Double = 0.03,
    amplitudeTimeSeconds: Double = 0.02,
) : SubharmonicTracker(sampleRate, division) {
    private val minPeriod = (sampleRate / maxInputHz).toInt()
    private val maxPeriod = (sampleRate / minInputHz).toInt()
    private val trackingSamples = trackingTimeSeconds * sampleRate
    private val ampSmoothing = smoothing(amplitudeTimeSeconds)

    private var previousInput = 0.0
    private var samplesSinceCrossing = 0

    override fun track(x: Double) {
        samplesSinceCrossing++
        if (previousInput <= 0.0 && x > 0.0) {
            val period = samplesSinceCrossing
            if (period in minPeriod..maxPeriod) {
                val measured = twoPi / period
                // one update per crossing, so the smoothing spans `period` samples, not one
                val keep = exp(-period / trackingSamples)
                omega = if (omega == 0.0) measured else keep * omega + (1.0 - keep) * measured
            }
            samplesSinceCrossing = 0
        }
        previousInput = x
        envelope = ampSmoothing * envelope + (1.0 - ampSmoothing) * abs(x)
    }

    override fun reset() {
        super.reset(); previousInput = 0.0; samplesSinceCrossing = 0
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Phase-locked loop
// ═══════════════════════════════════════════════════════════════════

/**
 * A second-order phase-locked loop with a multiplying phase detector.
 *
 * The loop keeps an estimate `φ̂` of the input's phase. Each sample it multiplies the input by
 * `cos φ̂`: for an input `A sin θ` that is `A/2 · sin(θ − φ̂)`, which is the phase error, plus
 * `A/2 · sin(θ + φ̂)`, a ripple at twice the input frequency that the loop only partly averages
 * away. The error nudges the frequency estimate (the integral path, gain `ωn²`) and the phase
 * (the proportional path, gain `2ζωn`), where `ωn` comes from [bandwidthHz] and `ζ` from
 * [damping].
 *
 * [bandwidthHz] is the trade: wide follows quick pitch changes, narrow holds steady through noise.
 * Because the error is proportional to `A`, the effective bandwidth also scales with the input's
 * level; these gains assume a peak level of about 1. The estimate starts at [initialHz] and is
 * clamped to `minHz..maxHz`.
 */
class SubharmonicPLL(
    sampleRate: Double = 48_000.0,
    division: Double = 2.0,
    val minHz: Double = 35.0,
    val maxHz: Double = 350.0,
    val bandwidthHz: Double = 8.0,
    val damping: Double = 0.707,
    envelopeTimeSeconds: Double = 0.02,
    val initialHz: Double = 100.0,
) : SubharmonicTracker(sampleRate, division) {
    private val minOmega = twoPi * minHz / sampleRate
    private val maxOmega = twoPi * maxHz / sampleRate
    private val wn = twoPi * bandwidthHz / sampleRate
    private val kp = 2.0 * damping * wn
    private val ki = wn * wn
    private val envCoeff = smoothing(envelopeTimeSeconds)

    /** The loop's estimate of the input phase. */
    private var phase = 0.0

    init { reset() }

    override fun track(x: Double) {
        envelope = envCoeff * envelope + (1.0 - envCoeff) * abs(x)
        val error = x * cos(phase)
        omega = (omega + ki * error).coerceIn(minOmega, maxOmega)
        phase = wrap(phase + omega + kp * error)
    }

    override fun reset() {
        super.reset()
        phase = 0.0
        omega = (twoPi * initialHz / sampleRate).coerceIn(minOmega, maxOmega)
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Quadrature PLL
// ═══════════════════════════════════════════════════════════════════

/**
 * A pair of allpass chains whose outputs stay 90° apart: [i] and [q] are the in-phase and
 * quadrature parts of the analytic signal, so `atan2(i, q)` is the input's instantaneous phase and
 * `hypot(i, q)` its instantaneous amplitude.
 *
 * Eight second-order allpass sections in two paths, with Olli Niemitalo's coefficients and the
 * one-sample delay on the first path. At 48 kHz the two outputs are within a degree of 90° apart
 * from 30 Hz to 5 kHz (88.4° at 20 Hz), which covers every input a subharmonic tracker should see.
 * Both outputs have the input's magnitude; neither is a delayed copy of it.
 */
class HilbertPair {
    private val a = doubleArrayOf(0.6923878, 0.9360654322959, 0.9882295226860, 0.9987488452737).map { it * it }
    private val b = doubleArrayOf(0.4021921162426, 0.8561710882420, 0.9722909545651, 0.9952884791278).map { it * it }
    private val stA = Array(4) { DoubleArray(4) }   // x1, x2, y1, y2 per section
    private val stB = Array(4) { DoubleArray(4) }
    private var aDelayed = 0.0

    /** In-phase output: lags [q] by 90°. */
    var i = 0.0; private set

    /** Quadrature output. */
    var q = 0.0; private set

    fun process(x: Double) {
        val outA = chain(x, a, stA)
        q = chain(x, b, stB)
        i = aDelayed
        aDelayed = outA
    }

    fun reset() {
        stA.forEach { it.fill(0.0) }; stB.forEach { it.fill(0.0) }
        aDelayed = 0.0; i = 0.0; q = 0.0
    }

    private fun chain(x: Double, coeffs: List<Double>, st: Array<DoubleArray>): Double {
        var v = x
        for (s in coeffs.indices) {
            val c = coeffs[s]; val z = st[s]
            val y = c * (v + z[3]) - z[1]
            z[1] = z[0]; z[0] = v
            z[3] = z[2]; z[2] = y
            v = y
        }
        return v
    }
}

/**
 * A phase-locked loop driven by the analytic signal rather than the raw input.
 *
 * [HilbertPair] turns the input into `i ≈ A sin θ` and `q ≈ A cos θ`. Then
 * `(i cos φ̂ − q sin φ̂) / A = sin(θ − φ̂)` is the phase error itself: there is no double-frequency
 * term to ripple the estimate, and dividing by `A` makes the loop's bandwidth the same at every
 * level. The same pair gives the level as `A = hypot(i, q)`, which does not ripple either, so the
 * generated tone is not amplitude-modulated at the input frequency the way a rectified follower
 * makes it.
 *
 * Two additions make it practical on music:
 *
 *  * **A frequency discriminator.** The phase advance between successive analytic samples is the
 *    input's instantaneous frequency. Steering `ω` toward it with a time constant of
 *    [acquisitionTimeSeconds] (a frequency-locked loop) brings the PLL to a new note in a few tens
 *    of milliseconds, where the phase loop alone would slip cycles for much longer. It acts only
 *    while the loop is out of phase: once it is locked, harmonics in the input bias the
 *    instantaneous frequency, and leaving the discriminator on drags the estimate off the note.
 *    Set it to 0 to hear the loop without it.
 *  * **A gate.** Below [gateLevel] there is no pitch worth following, so the loop holds its last
 *    frequency instead of chasing noise, and the level decays over [releaseTimeSeconds].
 */
class SubharmonicQuadPLL(
    sampleRate: Double = 48_000.0,
    division: Double = 2.0,
    val minHz: Double = 35.0,
    val maxHz: Double = 350.0,
    val bandwidthHz: Double = 8.0,
    val damping: Double = 0.707,
    val acquisitionTimeSeconds: Double = 0.02,
    val gateLevel: Double = 0.01,
    attackTimeSeconds: Double = 0.005,
    releaseTimeSeconds: Double = 0.06,
    val initialHz: Double = 100.0,
) : SubharmonicTracker(sampleRate, division) {
    private val minOmega = twoPi * minHz / sampleRate
    private val maxOmega = twoPi * maxHz / sampleRate
    private val wn = twoPi * bandwidthHz / sampleRate
    private val kp = 2.0 * damping * wn
    private val ki = wn * wn
    private val kf = if (acquisitionTimeSeconds > 0.0) 1.0 - smoothing(acquisitionTimeSeconds) else 0.0
    private val attack = smoothing(attackTimeSeconds)
    private val release = smoothing(releaseTimeSeconds)
    private val hilbert = HilbertPair()

    private val lockCoeff = smoothing(0.01)

    private var phase = 0.0
    private var lastI = 0.0
    private var lastQ = 0.0

    /** `cos` of the phase error, smoothed over 10 ms: 1 when the loop sits on the input's phase. */
    private var lock = 0.0

    /** True while the input is above [gateLevel] and the loop is in phase with it. */
    var locked = false; private set

    init { reset() }

    override fun track(x: Double) {
        hilbert.process(x)
        val i = hilbert.i; val q = hilbert.q
        val mag = sqrt(i * i + q * q)

        val target = if (mag > gateLevel) mag else 0.0
        val c = if (target > envelope) attack else release
        envelope = c * envelope + (1.0 - c) * target

        val gateOpen = mag > gateLevel
        if (gateOpen) {
            val c = cos(phase); val s = sin(phase)
            val error = (i * c - q * s) / mag             // sin(θ − φ̂)
            lock = lockCoeff * lock + (1.0 - lockCoeff) * ((q * c + i * s) / mag)   // cos(θ − φ̂), smoothed
            if (kf > 0.0 && lock < LOCK_THRESHOLD && lastI * lastI + lastQ * lastQ > gateLevel * gateLevel) {
                // phase of z_n · conj(z_{n-1}), with z = q + j·i
                val advance = atan2(i * lastQ - q * lastI, q * lastQ + i * lastI)
                if (advance > 0.0) omega += kf * (advance - omega)
            }
            omega = (omega + ki * error).coerceIn(minOmega, maxOmega)
            phase = wrap(phase + omega + kp * error)
        } else {
            phase = wrap(phase + omega)
        }
        locked = gateOpen && lock >= LOCK_THRESHOLD
        lastI = i; lastQ = q
    }

    private companion object {
        /** Smoothed `cos(θ − φ̂)` above which the loop counts as in phase and the discriminator rests. */
        const val LOCK_THRESHOLD = 0.9
    }

    override fun reset() {
        super.reset()
        hilbert.reset()
        phase = 0.0; lastI = 0.0; lastQ = 0.0; locked = false; lock = 0.0
        omega = (twoPi * initialHz / sampleRate).coerceIn(minOmega, maxOmega)
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Graph integration
// ═══════════════════════════════════════════════════════════════════

/**
 * A [SubharmonicTracker] as a node in a signal graph: `bass.through(Subharmonic(SubharmonicPLL()))`
 * is the generated subharmonic of `bass`. The tracker's sample rate has to match the graph's.
 *
 * [division], when given, is read every sample and written to the tracker's
 * [SubharmonicTracker.division], so the ratio can be sequenced or modulated by another signal.
 */
class Subharmonic(val tracker: SubharmonicTracker, val division: Signal? = null) : SignalProcessor() {
    private var inBuf = FloatArray(0)
    private var divBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        require(abs(ctx.sampleRate - tracker.sampleRate) < 0.5) {
            "Subharmonic: tracker built for ${tracker.sampleRate} Hz, graph runs at ${ctx.sampleRate} Hz"
        }
        if (inBuf.size < numFrames) { inBuf = FloatArray(numFrames); divBuf = FloatArray(numFrames) }
        input.render(inBuf, numFrames, ctx)
        val div = division
        div?.render(divBuf, numFrames, ctx)
        for (n in 0 until numFrames) {
            if (div != null) tracker.division = divBuf[n].toDouble()
            output[n] = tracker.process(inBuf[n])
        }
    }
}

/** Recorded audio as a signal: plays [samples] once from the start, then silence. */
class SamplesSignal(val samples: FloatArray) : Signal() {
    private var position = 0

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        for (n in 0 until numFrames) {
            output[n] = if (position < samples.size) samples[position] else 0f
            position++
        }
    }
}
