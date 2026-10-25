@file:afield.runtime.completion.CompletionCategory("Synthesis")
package afield.audio.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * An oscillator bank that follows a tracked phase: [TrackedPhaseBank] renders a list of
 * [PhaseVoice]s, each advancing at a ratio of the pitch a [PhaseTracker] reports.
 *
 * Each voice keeps its own continuous phase accumulator, so fractional and irrational ratios stay
 * continuous, and a [PhaseRenderer] turns that phase into a sample (a sine by default).
 */

private const val TWO_PI = 2.0 * PI

/**
 * Rendering function for one bank voice.
 *
 * The core bank only promises that the accumulated phase is continuous and wrapped to [0,2π).
 * Renderers that make sharp corners/discontinuities are responsible for their own
 * anti-aliasing. Sine is therefore the conservative default.
 */
fun interface PhaseRenderer {
    fun render(phase: Double): Double

    companion object {
        val SINE = PhaseRenderer { phase -> sin(phase) }
        val COSINE = PhaseRenderer { phase -> cos(phase) }
    }
}


/**
 * One trajectory in a [TrackedPhaseBank].
 *
 * [ratio] scales tracked phase velocity.
 * [gain] is linear amplitude before the bank's optional input-level following.
 * [phaseOffset] is a fixed output phase rotation, in radians.
 * [lock] mixes stable pitch motion with full PLL-corrected phase motion:
 *
 *      advance = omega + lock * (phaseAdvance - omega)
 *
 * [renderer] maps the resulting wrapped phase to a sample.
 *
 * Runtime [phase] is deliberately part of the voice rather than the tracker. Fractional
 * and irrational ratios require an independent continuous accumulator.
 */
class PhaseVoice(
    var ratio: Double,
    var gain: Double = 1.0,
    var phaseOffset: Double = 0.0,
    var lock: Double = 1.0,
    var renderer: PhaseRenderer = PhaseRenderer.SINE,
) {
    var phase: Double = 0.0
        private set

    var lastOutput: Double = 0.0
        internal set

    internal fun advance(by: Double) {
        phase = wrapTrackedPhase(phase + by)
    }

    fun reset(phase: Double = 0.0) {
        this.phase = wrapTrackedPhase(phase)
        lastOutput = 0.0
    }

    override fun toString(): String =
        "PhaseVoice(ratio=$ratio, gain=$gain, lock=$lock, phase=${"%.3f".format(phase)})"
}


/**
 * Fan one tracked phase state out into many independently accumulated trajectories.
 *
 * The bank does not require ownership of a tracker; [render] can consume state produced
 * elsewhere. Supplying [tracker] merely enables the convenience [process] method.
 *
 * [followInputLevel] preserves the current subharmonic behaviour: the whole bank follows
 * the analytic amplitude of the source. Turn it off for a constant-amplitude oscillator
 * bank driven only by source phase/pitch.
 *
 * [nyquistFadeStart] and [nyquistFadeEnd] are fractions of Nyquist. A sine partial begins
 * fading at the former and reaches zero at the latter. This protects ordinary harmonic
 * and ratio banks from folding. It does NOT magically band-limit custom renderers that
 * generate additional harmonics internally.
 */
class TrackedPhaseBank(
    val tracker: PhaseTracker? = null,
    val voices: MutableList<PhaseVoice> = mutableListOf(),
    var followInputLevel: Boolean = true,
    var outputGain: Double = 1.0,
    val nyquistFadeStart: Double = 0.85,
    val nyquistFadeEnd: Double = 0.98,
) {
    init {
        require(nyquistFadeStart in 0.0..1.0)
        require(nyquistFadeEnd in 0.0..1.0)
        require(nyquistFadeEnd > nyquistFadeStart)
    }

    /**
     * Analyze [input] with the owned tracker and render the mono sum.
     */
    fun process(input: Float): Float {
        val t = tracker
            ?: error("TrackedPhaseBank.process(input) requires a tracker; use render(state) otherwise")
        return render(t.process(input)).toFloat()
    }

    /**
     * Render all voices from one already-computed tracked state and return their mono sum.
     *
     * No allocation occurs here.
     */
    fun render(state: TrackedPhaseState): Double {
        val levelScale =
            if (followInputLevel) state.level
            else 1.0

        var sum = 0.0

        for (n in voices.indices) {
            sum += renderVoice(voices[n], state)
        }

        return sum * levelScale * outputGain
    }

    /**
     * Render each voice separately into caller-owned [out].
     *
     * Returns the sum of the output slots, including [outputGain] and source-level following,
     * so the caller can route/spatialize individual voices without running the tracker again.
     */
    fun renderVoices(
        state: TrackedPhaseState,
        out: DoubleArray,
    ): Double {
        require(out.size >= voices.size) {
            "Need at least ${voices.size} output slots, got ${out.size}"
        }

        val levelScale =
            if (followInputLevel) state.level
            else 1.0

        var sum = 0.0

        for (n in voices.indices) {
            val y =
                renderVoice(voices[n], state) *
                    levelScale * outputGain

            out[n] = y
            sum += y
        }

        return sum
    }

    fun reset() {
        tracker?.reset()
        for (voice in voices) {
            voice.reset()
        }
    }

    private fun renderVoice(
        voice: PhaseVoice,
        state: TrackedPhaseState,
    ): Double {
        if (!voice.ratio.isFinite() || !voice.gain.isFinite() || !voice.lock.isFinite() ||
            !voice.phaseOffset.isFinite()) {
            voice.lastOutput = 0.0
            return 0.0
        }
        val correctedPart =
            state.phaseAdvance - state.omega

        val baseAdvance =
            state.omega +
                voice.lock * correctedPart

        voice.advance(voice.ratio * baseAdvance)

        val phaseForRender =
            wrapTrackedPhase(voice.phase + voice.phaseOffset)

        val aliasProtection =
            nyquistGain(
                frequencyHz = abs(voice.ratio * baseAdvance) * state.sampleRate / (2.0 * PI),
                sampleRate = state.sampleRate,
                fadeStart = nyquistFadeStart,
                fadeEnd = nyquistFadeEnd,
            )

        val y =
            voice.gain *
                aliasProtection *
                voice.renderer.render(phaseForRender)

        voice.lastOutput = y
        return y
    }

    companion object {
        /**
         * Ordinary integer harmonic series.
         *
         * Example:
         *
         *      val bank = TrackedPhaseBank(
         *          tracker = QuadPhaseTracker(),
         *          voices = TrackedPhaseBank.harmonics(16) { n -> 1.0 / n }
         *      )
         */
        fun harmonics(
            count: Int,
            lock: Double = 1.0,
            gain: (harmonic: Int) -> Double = { n -> 1.0 / n },
        ): MutableList<PhaseVoice> {
            require(count >= 0)
            return MutableList(count) { index ->
                val n = index + 1
                PhaseVoice(
                    ratio = n.toDouble(),
                    gain = gain(n),
                    lock = lock,
                )
            }
        }

        /**
         * Build a bank from ratio/gain pairs.
         */
        fun ratios(
            vararg ratioAndGain: Pair<Double, Double>,
            lock: Double = 1.0,
        ): MutableList<PhaseVoice> =
            ratioAndGain.mapTo(mutableListOf()) { (ratio, gain) ->
                PhaseVoice(
                    ratio = ratio,
                    gain = gain,
                    lock = lock,
                )
            }

        /**
         * A compact undertone/overtone lattice useful as a first listening test.
         */
        fun rationalLattice(
            lock: Double = 1.0,
        ): MutableList<PhaseVoice> =
            ratios(
                1.0 / 2.0 to 0.24,
                2.0 / 3.0 to 0.18,
                3.0 / 4.0 to 0.14,
                1.0 to 0.22,
                4.0 / 3.0 to 0.12,
                3.0 / 2.0 to 0.10,
                2.0 to 0.08,
                lock = lock,
            )
    }
}


/**
 * Smoothly fade a sine partial before Nyquist.
 */
private fun nyquistGain(
    frequencyHz: Double,
    sampleRate: Double,
    fadeStart: Double,
    fadeEnd: Double,
): Double {
    val nyquist = sampleRate * 0.5
    if (nyquist <= 0.0) return 0.0

    val x = frequencyHz / nyquist

    if (x <= fadeStart) return 1.0
    if (x >= fadeEnd) return 0.0

    val t =
        ((x - fadeStart) / (fadeEnd - fadeStart))
            .coerceIn(0.0, 1.0)

    // 1 - smoothstep(t)
    return 1.0 - t * t * (3.0 - 2.0 * t)
}
