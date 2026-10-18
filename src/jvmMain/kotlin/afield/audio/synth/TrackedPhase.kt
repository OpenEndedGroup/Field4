@file:afield.runtime.completion.CompletionCategory("Synthesis")
package afield.audio.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pitch-tracked phase for voice banks: a tracker reduces one periodic sound to phase, phase
 * advance, pitch, level and confidence, and a bank gives every voice its own phase accumulator.
 *
 * Each voice advances by
 *
 * ```
 * theta += ratio * (omega + lock * (phaseAdvance - omega))
 * ```
 *
 * This supports undertones, harmonics and irrational ratios without dividing wrapped phase.
 * `lock = 0` follows stable pitch; `lock = 1` also follows the tracker's phase corrections. It does
 * not force a voice onto an absolute input phase, so changing a ratio preserves continuity.
 *
 * QuadPhaseTracker is the analysis half of SubharmonicQuadPLL, sharing its HilbertPair and
 * algorithm; [Subharmonic] keeps its own oscillator and level behaviour.
 * HarmonicPhaseTracker supplies the same state from several partials, including a missing
 * fundamental. Neither tracker separates polyphonic sources.
 *
 * Trackers, states and voices belong to the audio thread. TrackedPhaseAnalysis caches each
 * sample's state for graph fan-out; reading a live tracker from the Runloop must never advance
 * it. Per-sample paths reuse their storage. Custom renderers must handle their own bandwidth;
 * the bank's Nyquist fade protects the fundamental of each sine voice only.
 */

private const val TWO_PI = 2.0 * PI

/**
 * Mutable, reused per-sample state published by a [PhaseTracker].
 *
 * [phase] is the tracker's wrapped phase in [0, 2π).
 * [phaseAdvance] is the actual phase increment used by the tracker on this sample.
 * [omega] is the slower/stabler angular-frequency estimate in radians per sample.
 * [level] is the tracked analytic-signal amplitude.
 * [confidence] is 0..1 and describes phase agreement while the gate is open.
 *
 * A tracker owns this object and mutates it in place. Consumers must not retain snapshots
 * by reference; copy fields explicitly if they need history.
 */
class TrackedPhaseState(
    val sampleRate: Double,
) {
    var phase: Double = 0.0
        internal set

    var phaseAdvance: Double = 0.0
        internal set

    var omega: Double = 0.0
        internal set

    var level: Double = 0.0
        internal set

    var confidence: Double = 0.0
        internal set

    var gateOpen: Boolean = false
        internal set

    var locked: Boolean = false
        internal set

    /** Harmonic power coverage, or phase agreement for the quadrature tracker. */
    var harmonicity: Double = 0.0
        internal set

    /** Number of usable analysis partials on this sample. */
    var activePartials: Int = 0
        internal set

    init { require(sampleRate.isFinite() && sampleRate > 0) }

    internal fun clear() {
        phase = 0.0; phaseAdvance = 0.0; omega = 0.0; level = 0.0
        confidence = 0.0; harmonicity = 0.0; activePartials = 0
        gateOpen = false; locked = false
    }

    internal fun copyFrom(other: TrackedPhaseState) {
        phase = other.phase; phaseAdvance = other.phaseAdvance; omega = other.omega
        level = other.level; confidence = other.confidence; harmonicity = other.harmonicity
        activePartials = other.activePartials; gateOpen = other.gateOpen; locked = other.locked
    }

    val hz: Double
        get() = omega * sampleRate / TWO_PI

    val phaseAdvanceHz: Double
        get() = phaseAdvance * sampleRate / TWO_PI

    override fun toString(): String =
        "TrackedPhaseState(hz=${"%.3f".format(hz)}, level=${"%.4f".format(level)}, " +
            "confidence=${"%.3f".format(confidence)}, locked=$locked)"
}


/**
 * Analysis half of the system. Implementations update one reused [state] for every sample.
 */
interface PhaseTracker {
    val sampleRate: Double
    val state: TrackedPhaseState

    fun process(input: Float): TrackedPhaseState

    fun reset()
}


/** Quadrature analysis shared with [SubharmonicQuadPLL]; use a band-limited monophonic input. */
class QuadPhaseTracker(
    override val sampleRate: Double = 48_000.0,
    val minHz: Double = 35.0,
    val maxHz: Double = 350.0,
    val bandwidthHz: Double = 8.0,
    val damping: Double = 0.707,
    val acquisitionTimeSeconds: Double = 0.02,
    val gateLevel: Double = 0.01,
    attackTimeSeconds: Double = 0.005,
    releaseTimeSeconds: Double = 0.06,
    val initialHz: Double = 100.0,
    val lockThreshold: Double = 0.9,
) : PhaseTracker {

    override val state = TrackedPhaseState(sampleRate)
    private val twoPi = TWO_PI
    private var omega = 0.0
    private var envelope = 0.0

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

    init {
        require(minHz.isFinite() && maxHz.isFinite() && minHz > 0 && maxHz > minHz && maxHz < sampleRate / 2)
        require(initialHz.isFinite() && initialHz > 0)
        require(bandwidthHz.isFinite() && bandwidthHz > 0 && damping.isFinite() && damping > 0)
        require(acquisitionTimeSeconds.isFinite() && acquisitionTimeSeconds >= 0)
        require(gateLevel.isFinite() && gateLevel >= 0)
        reset()
    }

    override fun process(input: Float): TrackedPhaseState {
        val x = if (input.isFinite()) input.toDouble() else 0.0
        hilbert.process(x)
        val i = hilbert.i; val q = hilbert.q
        val mag = sqrt(i * i + q * q)

        val target = if (mag > gateLevel) mag else 0.0
        val c = if (target > envelope) attack else release
        envelope = c * envelope + (1.0 - c) * target

        val gateOpen = mag > gateLevel
        var advance = omega
        if (gateOpen) {
            val c = cos(phase); val s = sin(phase)
            val error = (i * c - q * s) / mag             // sin(θ − φ̂)
            lock = lockCoeff * lock + (1.0 - lockCoeff) * ((q * c + i * s) / mag)   // cos(θ − φ̂), smoothed
            if (kf > 0.0 && lock < lockThreshold && lastI * lastI + lastQ * lastQ > gateLevel * gateLevel) {
                // phase of z_n · conj(z_{n-1}), with z = q + j·i
                val advance = atan2(i * lastQ - q * lastI, q * lastQ + i * lastI)
                if (advance > 0.0) omega += kf * (advance - omega)
            }
            omega = (omega + ki * error).coerceIn(minOmega, maxOmega)
            advance = omega + kp * error
            phase = wrapTrackedPhase(phase + advance)
        } else {
            phase = wrapTrackedPhase(phase + omega)
        }
        locked = gateOpen && lock >= lockThreshold
        lastI = i; lastQ = q
        state.phase = phase
        state.phaseAdvance = advance
        state.omega = omega
        state.level = envelope
        state.gateOpen = gateOpen
        state.locked = locked
        state.confidence = if (gateOpen) lock.coerceIn(0.0, 1.0) else 0.0
        state.harmonicity = state.confidence
        state.activePartials = if (gateOpen) 1 else 0
        return state
    }


    override fun reset() {
        envelope = 0.0
        state.clear()
        hilbert.reset()
        phase = 0.0; lastI = 0.0; lastQ = 0.0; locked = false; lock = 0.0
        omega = (twoPi * initialHz / sampleRate).coerceIn(minOmega, maxOmega)
        state.omega = omega
        state.phaseAdvance = omega
    }
    private fun smoothing(seconds: Double): Double {
        require(seconds.isFinite() && seconds >= 0)
        return if (seconds == 0.0) 0.0 else exp(-1.0 / (seconds * sampleRate))
    }
}

/** Constant-time phase wrapping, including negative ratios. */
internal fun wrapTrackedPhase(phase: Double): Double {
    if (!phase.isFinite()) return 0.0
    val p = phase % (2.0 * PI)
    return if (p < 0.0) p + 2.0 * PI else p
}
