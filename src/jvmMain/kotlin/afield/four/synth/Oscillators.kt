package afield.four.synth

import java.util.concurrent.ThreadLocalRandom
import kotlin.math.*

// ═══════════════════════════════════════════════════════════════════
//  Phasor – raw phase ramp [0, 1)
// ═══════════════════════════════════════════════════════════════════

/**
 * Outputs a rising ramp from 0 to 1 at the given [freq].
 * Useful as a building block for custom wave-shaping.
 */
class Phasor(
    val freq: Param = Param(440f),
    initialPhase: Double = 0.0
) : Signal() {
    private var phase: Double = initialPhase
    private var freqBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (freqBuf.size < numFrames) freqBuf = FloatArray(numFrames)
        freq.render(freqBuf, numFrames, ctx)
        val invSr = ctx.inverseSampleRate.toDouble()
        for (i in 0 until numFrames) {
            output[i] = phase.toFloat()
            phase += freqBuf[i].toDouble() * invSr
            phase -= floor(phase)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  SineOsc – sine wave oscillator
// ═══════════════════════════════════════════════════════════════════


class SineOsc(
    val freq: Param = Param(440f),
    /** Phase modulation input (radians). */
    val phaseMod: Param = Param(0f)
) : Signal() {
    private var phase: Double = 0.0
    private var freqBuf = FloatArray(0)
    private var pmBuf   = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (freqBuf.size < numFrames) {
            freqBuf = FloatArray(numFrames)
            pmBuf   = FloatArray(numFrames)
        }
        freq.render(freqBuf, numFrames, ctx)
        phaseMod.render(pmBuf, numFrames, ctx)
        val twoPi = 2.0 * PI
        val invSr = ctx.inverseSampleRate.toDouble()
        for (i in 0 until numFrames) {
            output[i] = sin(twoPi * phase + pmBuf[i].toDouble()).toFloat()
            phase += freqBuf[i].toDouble() * invSr
            phase -= floor(phase)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  PolyBLEP helpers (shared by Saw, Square, Pulse, Tri)
// ═══════════════════════════════════════════════════════════════════

/**
 * Polynomial bandlimited step (PolyBLEP) correction.
 * Smooths discontinuities to suppress aliasing.
 */
private fun polyBlep(t: Double, dt: Double): Double {
    if (t < dt) {
        val x = t / dt
        return x + x - x * x - 1.0
    }
    if (t > 1.0 - dt) {
        val x = (t - 1.0) / dt
        return x * x + x + x + 1.0
    }
    return 0.0
}

// ═══════════════════════════════════════════════════════════════════
//  SawOsc – antialiased sawtooth
// ═══════════════════════════════════════════════════════════════════


class SawOsc(
    val freq: Param = Param(440f)
) : Signal() {
    private var phase: Double = 0.0
    private var freqBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (freqBuf.size < numFrames) freqBuf = FloatArray(numFrames)
        freq.render(freqBuf, numFrames, ctx)
        val invSr = ctx.inverseSampleRate.toDouble()
        for (i in 0 until numFrames) {
            val dt = abs(freqBuf[i].toDouble()) * invSr
            // naive saw: 2*phase - 1
            var sample = 2.0 * phase - 1.0
            sample -= polyBlep(phase, dt)
            output[i] = sample.toFloat()
            phase += dt
            phase -= floor(phase)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  SquareOsc – antialiased square wave
// ═══════════════════════════════════════════════════════════════════


class SquareOsc(
    val freq: Param = Param(440f)
) : Signal() {
    private var phase: Double = 0.0
    private var freqBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (freqBuf.size < numFrames) freqBuf = FloatArray(numFrames)
        freq.render(freqBuf, numFrames, ctx)
        val invSr = ctx.inverseSampleRate.toDouble()
        for (i in 0 until numFrames) {
            val dt = abs(freqBuf[i].toDouble()) * invSr
            var sample = if (phase < 0.5) 1.0 else -1.0
            sample += polyBlep(phase, dt)
            sample -= polyBlep((phase + 0.5) % 1.0, dt)
            output[i] = sample.toFloat()
            phase += dt
            phase -= floor(phase)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  PulseOsc – variable-width pulse
// ═══════════════════════════════════════════════════════════════════


class PulseOsc(
    val freq: Param = Param(440f),
    /** Pulse width 0…1 (0.5 = square). */
    val width: Param = Param(0.5f)
) : Signal() {
    private var phase: Double = 0.0
    private var freqBuf  = FloatArray(0)
    private var widthBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (freqBuf.size < numFrames) {
            freqBuf  = FloatArray(numFrames)
            widthBuf = FloatArray(numFrames)
        }
        freq.render(freqBuf, numFrames, ctx)
        width.render(widthBuf, numFrames, ctx)
        val invSr = ctx.inverseSampleRate.toDouble()
        for (i in 0 until numFrames) {
            val dt = abs(freqBuf[i].toDouble()) * invSr
            val pw = widthBuf[i].toDouble().coerceIn(0.01, 0.99)
            var sample = if (phase < pw) 1.0 else -1.0
            sample += polyBlep(phase, dt)
            sample -= polyBlep((phase + (1.0 - pw)) % 1.0, dt)
            output[i] = sample.toFloat()
            phase += dt
            phase -= floor(phase)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  TriOsc – antialiased triangle
// ═══════════════════════════════════════════════════════════════════


class TriOsc(
    val freq: Param = Param(440f)
) : Signal() {
    private var phase: Double = 0.0
    private var integrator: Double = 0.0
    private var freqBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (freqBuf.size < numFrames) freqBuf = FloatArray(numFrames)
        freq.render(freqBuf, numFrames, ctx)
        val invSr = ctx.inverseSampleRate.toDouble()
        for (i in 0 until numFrames) {
            val f = abs(freqBuf[i].toDouble())
            val dt = f * invSr
            // PolyBLEP square
            var sq = if (phase < 0.5) 1.0 else -1.0
            sq += polyBlep(phase, dt)
            sq -= polyBlep((phase + 0.5) % 1.0, dt)
            // leaky integration → triangle
            integrator += sq * dt * 4.0
            integrator *= 0.999  // leak to prevent DC drift
            output[i] = integrator.toFloat()
            phase += dt
            phase -= floor(phase)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  SuperSaw – classic stacked detuned saw bank
// ═══════════════════════════════════════════════════════════════════

class SuperSaw(
    val freq: Param = Param(110f),
    /** Maximum spread of the detuned bank in cents. */
    val detuneCents: Param = Param(20f),
    /** 0 = only center voice, 1 = full detuned bank. */
    val blend: Param = Param(0.85f),
    /** Shapes how strongly the outer voices pull away from center. */
    val detuneCurve: Param = Param(1.4f),
    /** 0 = aligned phases, 1 = evenly spread initial phases. */
    val phaseSpread: Param = Param(1f),
    val voiceCount: Int = 7,
    val normalize: Boolean = true
) : Signal() {
    private val safeVoiceCount = voiceCount.coerceAtLeast(1)
    private val centerIndex = safeVoiceCount / 2
    private val phases = DoubleArray(safeVoiceCount) { 0.0 }
    private var initializedPhases = false

    private var freqBuf = FloatArray(0)
    private var detuneBuf = FloatArray(0)
    private var blendBuf = FloatArray(0)
    private var curveBuf = FloatArray(0)
    private var phaseSpreadBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (freqBuf.size < numFrames) {
            freqBuf = FloatArray(numFrames)
            detuneBuf = FloatArray(numFrames)
            blendBuf = FloatArray(numFrames)
            curveBuf = FloatArray(numFrames)
            phaseSpreadBuf = FloatArray(numFrames)
        }

        freq.render(freqBuf, numFrames, ctx)
        detuneCents.render(detuneBuf, numFrames, ctx)
        blend.render(blendBuf, numFrames, ctx)
        detuneCurve.render(curveBuf, numFrames, ctx)
        phaseSpread.render(phaseSpreadBuf, numFrames, ctx)

        if (!initializedPhases) {
            initializePhases(phaseSpreadBuf.firstOrNull() ?: 1f)
            initializedPhases = true
        }

        val invSr = ctx.inverseSampleRate.toDouble()

        for (i in 0 until numFrames) {
            val hz = kotlin.math.abs(freqBuf[i].toDouble()).coerceAtLeast(0.0)
            val cents = detuneBuf[i].coerceAtLeast(0f)
            val curve = curveBuf[i].coerceAtLeast(0.1f)
            val mix = blendBuf[i].coerceIn(0f, 1f)

            var center = 0.0
            var sides = 0.0
            var sideCount = 0

            for (voice in 0 until safeVoiceCount) {
                val detuneUnit = detuneUnitForVoice(voice, curve)
                val voiceCents = cents * detuneUnit
                val voiceFreq = hz * centsToRatio(voiceCents.toDouble())
                val dt = voiceFreq * invSr
                val phase = phases[voice]

                var sample = 2.0 * phase - 1.0
                sample -= polyBlep(phase, dt)

                if (voice == centerIndex) {
                    center = sample
                } else {
                    sides += sample
                    sideCount++
                }

                var nextPhase = phase + dt
                nextPhase -= floor(nextPhase)
                phases[voice] = nextPhase
            }

            val sideSignal = if (sideCount > 0) sides / sideCount else 0.0
            val mixed = center * (1f - mix) + sideSignal * mix
            val norm = if (normalize) 0.9 else 1.0
            output[i] = (mixed * norm).toFloat()
        }
    }

    private fun initializePhases(spread: Float) {
        val phaseAmount = spread.coerceIn(0f, 1f)
        for (voice in 0 until safeVoiceCount) {
            val offset = if (safeVoiceCount == 1) 0.0 else voice.toDouble() / safeVoiceCount.toDouble()
            phases[voice] = offset * phaseAmount
        }
    }

    private fun detuneUnitForVoice(index: Int, curve: Float): Float {
        if (safeVoiceCount == 1) return 0f
        val centered = (index - centerIndex).toFloat()
        val normalized = centered / centerIndex.coerceAtLeast(1).toFloat()
        val magnitude = kotlin.math.abs(normalized).toDouble().pow(curve.toDouble()).toFloat()
        return kotlin.math.sign(normalized) * magnitude
    }

    private fun centsToRatio(cents: Double): Double = 2.0.pow(cents / 1200.0)
}

private fun wrapPhase(phase: Double): Double = phase - floor(phase)

private fun pulseSample(phase: Double, dt: Double, width: Double): Double {
    val wrapped = wrapPhase(phase)
    val pw = width.coerceIn(0.01, 0.99)
    var sample = if (wrapped < pw) 1.0 else -1.0
    sample += polyBlep(wrapped, dt)
    sample -= polyBlep((wrapped + (1.0 - pw)) % 1.0, dt)
    return sample
}

private fun squareSample(phase: Double, dt: Double): Double {
    val wrapped = wrapPhase(phase)
    var sample = if (wrapped < 0.5) 1.0 else -1.0
    sample += polyBlep(wrapped, dt)
    sample -= polyBlep((wrapped + 0.5) % 1.0, dt)
    return sample
}

private fun varSawSample(phase: Double, width: Double): Double {
    val wrapped = wrapPhase(phase)
    val w = width.coerceIn(0.01, 0.99)
    return if (wrapped < w) {
        -1.0 + 2.0 * (wrapped / w)
    } else {
        1.0 - 2.0 * ((wrapped - w) / (1.0 - w))
    }
}

private fun triangleSample(phase: Double): Double {
    val wrapped = wrapPhase(phase)
    return 1.0 - 4.0 * abs(wrapped - 0.5)
}

private fun semitoneRatio(semitones: Double): Double = 2.0.pow(semitones / 12.0)

private fun midiFromHz(hz: Double): Double = 69.0 + 12.0 * log2(hz / 440.0)

private fun mix(a: Double, b: Double, amount: Float): Double {
    val t = amount.coerceIn(0f, 1f).toDouble()
    return a * (1.0 - t) + b * t
}

private class MoogLadderCore {
    private val stage = DoubleArray(4)

    fun process(input: Double, cutoff: Double, resonance: Double, sampleRate: Double): Float {
        val freq = cutoff.coerceIn(20.0, sampleRate * 0.499)
        val res = resonance.coerceIn(0.0, 1.0) * 4.0
        val f = 2.0 * sin(PI * freq / sampleRate)
        val fb = res * (1.0 - 0.15 * f * f)
        val inp = input - fb * stage[3]

        stage[0] += f * (tanh(inp) - tanh(stage[0]))
        stage[1] += f * (tanh(stage[0]) - tanh(stage[1]))
        stage[2] += f * (tanh(stage[1]) - tanh(stage[2]))
        stage[3] += f * (tanh(stage[2]) - tanh(stage[3]))

        return stage[3].toFloat()
    }
}

private class OnePoleHighPassCore {
    private var low = 0.0

    fun process(input: Double, cutoff: Double, sampleRate: Double): Float {
        val freq = cutoff.coerceIn(1.0, sampleRate * 0.499)
        val b1 = exp(-2.0 * PI * freq / sampleRate)
        val a0 = 1.0 - b1
        low = a0 * input + b1 * low
        return (input - low).toFloat()
    }
}

private class HeldNoiseCore {
    private var phase = 0.0
    private var current = 0.0

    fun next(freq: Double, invSr: Double, rng: ThreadLocalRandom, amplitude: Double = 1.0): Double {
        phase += abs(freq) * invSr
        if (phase >= 1.0) {
            phase -= floor(phase)
            current = (rng.nextDouble() * 2.0 - 1.0) * amplitude
        }
        return current
    }
}

private class LFNoise1Core {
    private var phase = 0.0
    private var current = 0.0
    private var target = 0.0

    fun next(freq: Double, invSr: Double, rng: ThreadLocalRandom): Double {
        val rate = abs(freq)
        if (rate <= 1e-6) return current
        phase += rate * invSr
        if (phase >= 1.0) {
            phase -= floor(phase)
            current = target
            target = rng.nextDouble() * 2.0 - 1.0
        }
        return current + (target - current) * phase
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Tidal-inspired Super* synths
// ═══════════════════════════════════════════════════════════════════

/**
 * Continuous port of SuperDirt's `supersquare` voice:
 * two pulse oscillators into a moog-style low-pass with an audio-rate-tracked LFO.
 */
class SuperSquare(
    val freq: Param = Param(110f),
    /** Pulse width for both oscillators. Values near 0 or 1 nearly mute the sound. */
    val voice: Param = Param(0.5f),
    /** Detune of the secondary oscillator in semitones below the main pitch. */
    val semitone: Param = Param(12f),
    /** Filter resonance amount. */
    val resonance: Param = Param(0.2f),
    /** Additional filter sweep range above the tracked base cutoff. */
    val lfo: Param = Param(1f),
    /** Multiplier for the internal LFO rate. */
    val rate: Param = Param(1f),
    /** Scales the tracked cutoff floor. */
    val pitch1: Param = Param(1f)
) : Signal() {
    private var primaryPhase = 0.0
    private var subPhase = 0.0
    private var lfoPhase = 0.0
    private val filter = MoogLadderCore()

    private var freqBuf = FloatArray(0)
    private var voiceBuf = FloatArray(0)
    private var semiBuf = FloatArray(0)
    private var resBuf = FloatArray(0)
    private var lfoBuf = FloatArray(0)
    private var rateBuf = FloatArray(0)
    private var pitchBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (freqBuf.size < numFrames) {
            freqBuf = FloatArray(numFrames)
            voiceBuf = FloatArray(numFrames)
            semiBuf = FloatArray(numFrames)
            resBuf = FloatArray(numFrames)
            lfoBuf = FloatArray(numFrames)
            rateBuf = FloatArray(numFrames)
            pitchBuf = FloatArray(numFrames)
        }

        freq.render(freqBuf, numFrames, ctx)
        voice.render(voiceBuf, numFrames, ctx)
        semitone.render(semiBuf, numFrames, ctx)
        resonance.render(resBuf, numFrames, ctx)
        lfo.render(lfoBuf, numFrames, ctx)
        rate.render(rateBuf, numFrames, ctx)
        pitch1.render(pitchBuf, numFrames, ctx)

        val invSr = ctx.inverseSampleRate.toDouble()
        val sr = ctx.sampleRate.toDouble()
        val maxCutoff = sr * 0.499

        for (i in 0 until numFrames) {
            val baseFreq = abs(freqBuf[i].toDouble()).coerceIn(0.0, maxCutoff)
            val mainDt = baseFreq * invSr
            val subFreq = (baseFreq / semitoneRatio(semiBuf[i].toDouble())).coerceIn(0.0, maxCutoff)
            val subDt = subFreq * invSr
            val width = voiceBuf[i].coerceIn(0.02f, 0.98f).toDouble()

            val sound =
                0.7 * pulseSample(primaryPhase, mainDt, width) +
                0.3 * pulseSample(subPhase, subDt, width)

            val trackedCutoff =
                (baseFreq * 10.0 * pitchBuf[i].coerceAtLeast(0f)).coerceIn(20.0, maxCutoff)
            val sweepTop =
                (trackedCutoff * (1.0 + lfoBuf[i].coerceAtLeast(0f))).coerceIn(trackedCutoff, maxCutoff)
            val lfoValue = 0.5 + 0.5 * sin(2.0 * PI * lfoPhase)
            val cutoff = trackedCutoff + (sweepTop - trackedCutoff) * lfoValue

            val filtered = filter.process(sound, cutoff, resBuf[i].toDouble(), sr)
            output[i] = (tanh(filtered.toDouble()) * 2.0).toFloat()

            primaryPhase = wrapPhase(primaryPhase + mainDt)
            subPhase = wrapPhase(subPhase + subDt)
            lfoPhase = wrapPhase(lfoPhase + (baseFreq / 64.0) * rateBuf[i].toDouble() * invSr)
        }
    }
}

/**
 * Continuous port of SuperDirt's `superpwm` voice.
 * The second filter uses [secondarySweep] instead of a note-duration envelope.
 */
class SuperPWM(
    val freq: Param = Param(110f),
    /** Phase offset between the multiplied pulse pairs, expressed in oscillator cycles. */
    val voice: Param = Param(0.5f),
    /** Detune of the secondary oscillator in semitones below the main pitch. */
    val semitone: Param = Param(12f),
    /** Filter resonance amount for the tracked low-pass stage. */
    val resonance: Param = Param(0.2f),
    /** Additional filter sweep range above the tracked base cutoff. */
    val lfo: Param = Param(1f),
    /** Multiplier for the internal LFO rate. */
    val rate: Param = Param(1f),
    /** Scales the tracked cutoff floor. */
    val pitch1: Param = Param(1f),
    /** Replaces SuperDirt's note-duration sweep on the second filter in this continuous port. */
    val secondarySweep: Param = Param(0.65f)
) : Signal() {
    private var primaryPhase = 0.0
    private var subPhase = 0.0
    private var lfoPhase = 0.0
    private val firstFilter = MoogLadderCore()
    private val secondFilter = MoogLadderCore()

    private var freqBuf = FloatArray(0)
    private var voiceBuf = FloatArray(0)
    private var semiBuf = FloatArray(0)
    private var resBuf = FloatArray(0)
    private var lfoBuf = FloatArray(0)
    private var rateBuf = FloatArray(0)
    private var pitchBuf = FloatArray(0)
    private var sweepBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (freqBuf.size < numFrames) {
            freqBuf = FloatArray(numFrames)
            voiceBuf = FloatArray(numFrames)
            semiBuf = FloatArray(numFrames)
            resBuf = FloatArray(numFrames)
            lfoBuf = FloatArray(numFrames)
            rateBuf = FloatArray(numFrames)
            pitchBuf = FloatArray(numFrames)
            sweepBuf = FloatArray(numFrames)
        }

        freq.render(freqBuf, numFrames, ctx)
        voice.render(voiceBuf, numFrames, ctx)
        semitone.render(semiBuf, numFrames, ctx)
        resonance.render(resBuf, numFrames, ctx)
        lfo.render(lfoBuf, numFrames, ctx)
        rate.render(rateBuf, numFrames, ctx)
        pitch1.render(pitchBuf, numFrames, ctx)
        secondarySweep.render(sweepBuf, numFrames, ctx)

        val invSr = ctx.inverseSampleRate.toDouble()
        val sr = ctx.sampleRate.toDouble()
        val maxCutoff = sr * 0.499

        for (i in 0 until numFrames) {
            val baseFreq = abs(freqBuf[i].toDouble()).coerceIn(0.0, maxCutoff)
            val mainDt = baseFreq * invSr
            val subFreq = (baseFreq / semitoneRatio(semiBuf[i].toDouble())).coerceIn(0.0, maxCutoff)
            val subDt = subFreq * invSr
            val phaseOffset = voiceBuf[i].toDouble()

            val primary =
                pulseSample(primaryPhase, mainDt, 0.5) *
                pulseSample(primaryPhase - phaseOffset, mainDt, 0.5)
            val secondary =
                pulseSample(subPhase, subDt, 0.5) *
                pulseSample(subPhase - (phaseOffset + 0.1), subDt, 0.5)

            val trackedCutoff =
                (baseFreq * 10.0 * pitchBuf[i].coerceAtLeast(0f)).coerceIn(20.0, maxCutoff)
            val sweepTop =
                (trackedCutoff * (1.0 + lfoBuf[i].coerceAtLeast(0f))).coerceIn(trackedCutoff, maxCutoff)
            val lfoValue = 0.5 + 0.5 * sin(2.0 * PI * lfoPhase)
            val firstCutoff = trackedCutoff + (sweepTop - trackedCutoff) * lfoValue

            val shaped = 0.7 * primary + 0.3 * secondary
            val firstPass = firstFilter.process(shaped, firstCutoff, resBuf[i].toDouble(), sr)
            val secondCutoff =
                (sweepTop * 1.1 * sweepBuf[i].coerceIn(0.05f, 1f)).coerceIn(20.0, maxCutoff)
            val secondPass = secondFilter.process(firstPass.toDouble(), secondCutoff, 0.75, sr)

            output[i] = (tanh(secondPass * 5.0)).toFloat()

            primaryPhase = wrapPhase(primaryPhase + mainDt)
            subPhase = wrapPhase(subPhase + subDt)
            lfoPhase = wrapPhase(lfoPhase + (baseFreq / 32.0) * rateBuf[i].toDouble() * invSr)
        }
    }
}

/**
 * Continuous port of SuperDirt's `supercomparator` voice:
 * a comparator bank over a skewed ramp into a resonant low-pass.
 */
class SuperComparator(
    val freq: Param = Param(110f),
    /** Scales the internal comparator frequencies. Higher values sound breathier. */
    val voice: Param = Param(0.5f),
    /** Filter resonance amount. */
    val resonance: Param = Param(0.5f),
    /** Amount of LFO and random movement applied to the filter. */
    val lfo: Param = Param(1f),
    /** Multiplier for the internal LFO rate. */
    val rate: Param = Param(1f),
    /** Scales the tracked cutoff floor. */
    val pitch1: Param = Param(1f),
    /** Fixed width for the raw port's VarSaw source. */
    val shape: Param = Param(0.5f)
) : Signal() {
    private val comparatorFreqs = doubleArrayOf(
        1.000, 2.188, 5.091, 8.529, 8.950, 9.305, 13.746, 14.653,
        19.462, 22.003, 24.888, 25.991, 26.085, 30.509, 33.608, 35.081
    )
    private var sourcePhase = 0.0
    private var lfoPhase = 0.0
    private val comparatorPhases = DoubleArray(comparatorFreqs.size)
    private val filter = MoogLadderCore()
    private val noise = LFNoise1Core()

    private var freqBuf = FloatArray(0)
    private var voiceBuf = FloatArray(0)
    private var resBuf = FloatArray(0)
    private var lfoBuf = FloatArray(0)
    private var rateBuf = FloatArray(0)
    private var pitchBuf = FloatArray(0)
    private var shapeBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (freqBuf.size < numFrames) {
            freqBuf = FloatArray(numFrames)
            voiceBuf = FloatArray(numFrames)
            resBuf = FloatArray(numFrames)
            lfoBuf = FloatArray(numFrames)
            rateBuf = FloatArray(numFrames)
            pitchBuf = FloatArray(numFrames)
            shapeBuf = FloatArray(numFrames)
        }

        freq.render(freqBuf, numFrames, ctx)
        voice.render(voiceBuf, numFrames, ctx)
        resonance.render(resBuf, numFrames, ctx)
        lfo.render(lfoBuf, numFrames, ctx)
        rate.render(rateBuf, numFrames, ctx)
        pitch1.render(pitchBuf, numFrames, ctx)
        shape.render(shapeBuf, numFrames, ctx)

        val invSr = ctx.inverseSampleRate.toDouble()
        val sr = ctx.sampleRate.toDouble()
        val maxCutoff = sr * 0.499
        val rng = ThreadLocalRandom.current()

        for (i in 0 until numFrames) {
            val baseFreq = abs(freqBuf[i].toDouble()).coerceIn(0.0, maxCutoff)
            val baseDt = baseFreq * invSr
            val source = varSawSample(sourcePhase, shapeBuf[i].coerceIn(0.05f, 0.95f).toDouble())
            val exponent = voiceBuf[i].toDouble() / 5.0

            var summed = 0.0
            for (index in comparatorFreqs.indices) {
                val comparatorFreq = comparatorFreqs[index].pow(exponent)
                comparatorPhases[index] = wrapPhase(comparatorPhases[index] + comparatorFreq * invSr)
                val threshold = triangleSample(comparatorPhases[index])
                summed += if (source > threshold) 1.0 else 0.0
            }
            val compared = summed / comparatorFreqs.size.toDouble()

            val lfoAmount = lfoBuf[i].coerceAtLeast(0f).toDouble()
            val movement =
                sin(2.0 * PI * lfoPhase) * lfoAmount * baseFreq / 2.0 +
                noise.next(1.0, invSr, rng) * lfoAmount * baseFreq
            val cutoff =
                (pitchBuf[i].coerceAtLeast(0f) * 4.0 * baseFreq + movement).coerceIn(20.0, maxCutoff)

            val filtered = filter.process(compared, cutoff, resBuf[i].toDouble(), sr)
            output[i] = (tanh(filtered * 4.0) * 4.0).toFloat()

            sourcePhase = wrapPhase(sourcePhase + baseDt)
            lfoPhase = wrapPhase(lfoPhase + (baseFreq / 64.0) * rateBuf[i].toDouble() * invSr)
        }
    }
}

/**
 * Approximation of SuperDirt's AY-based `superchip` using three antialiased square voices.
 */
class SuperChip(
    val freq: Param = Param(110f),
    val pitch2: Param = Param(2f),
    val pitch3: Param = Param(3f),
    /** Bit-pattern selector for which of the three oscillators are active. */
    val voice: Param = Param(0f)
) : Signal() {
    private var phaseA = 0.0
    private var phaseB = 0.0
    private var phaseC = 0.0

    private var freqBuf = FloatArray(0)
    private var pitch2Buf = FloatArray(0)
    private var pitch3Buf = FloatArray(0)
    private var voiceBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (freqBuf.size < numFrames) {
            freqBuf = FloatArray(numFrames)
            pitch2Buf = FloatArray(numFrames)
            pitch3Buf = FloatArray(numFrames)
            voiceBuf = FloatArray(numFrames)
        }

        freq.render(freqBuf, numFrames, ctx)
        pitch2.render(pitch2Buf, numFrames, ctx)
        pitch3.render(pitch3Buf, numFrames, ctx)
        voice.render(voiceBuf, numFrames, ctx)

        val invSr = ctx.inverseSampleRate.toDouble()
        val maxFreq = ctx.nyquist.toDouble()

        for (i in 0 until numFrames) {
            val baseFreq = abs(freqBuf[i].toDouble()).coerceIn(0.0, maxFreq)
            val freqB = (baseFreq * pitch2Buf[i].toDouble().coerceAtLeast(0.0)).coerceIn(0.0, maxFreq)
            val freqC = (baseFreq * pitch3Buf[i].toDouble().coerceAtLeast(0.0)).coerceIn(0.0, maxFreq)

            val dtA = baseFreq * invSr
            val dtB = freqB * invSr
            val dtC = freqC * invSr

            val v = voiceBuf[i]
            val voiceB = (((v * 2f) % 1f) + 1f) % 1f
            val voiceC = (((v * 4f) % 1f) + 1f) % 1f
            val gainA = if (v < 0.5f) 1.0 else 0.0
            val gainB = if (voiceB < 0.5f) 1.0 else 0.0
            val gainC = if (voiceC < 0.5f) 1.0 else 0.0

            val sample =
                squareSample(phaseA, dtA) * gainA +
                squareSample(phaseB, dtB) * gainB +
                squareSample(phaseC, dtC) * gainC

            output[i] = (tanh(sample) * 2.0).toFloat()

            phaseA = wrapPhase(phaseA + dtA)
            phaseB = wrapPhase(phaseB + dtB)
            phaseC = wrapPhase(phaseC + dtC)
        }
    }
}

/**
 * Continuous port of SuperDirt's `supernoise` voice:
 * crossfaded stepped noise and brown/white noise into a resonant filter.
 */
class SuperNoise(
    val freq: Param = Param(110f),
    /** Scales the tracked band-pass center frequency. */
    val pitch1: Param = Param(1f),
    /** Filter resonance amount. */
    val resonance: Param = Param(0f),
    /** 0 = digital stepped noise, 1 = brown/white blend. */
    val voice: Param = Param(0f)
) : Signal() {
    private val digitalNoise = HeldNoiseCore()
    private val ladder = MoogLadderCore()
    private val highPass = OnePoleHighPassCore()
    private val white = WhiteNoise()
    private val brown = BrownNoise()

    private var freqBuf = FloatArray(0)
    private var pitchBuf = FloatArray(0)
    private var resBuf = FloatArray(0)
    private var voiceBuf = FloatArray(0)
    private var whiteBuf = FloatArray(0)
    private var brownBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (freqBuf.size < numFrames) {
            freqBuf = FloatArray(numFrames)
            pitchBuf = FloatArray(numFrames)
            resBuf = FloatArray(numFrames)
            voiceBuf = FloatArray(numFrames)
            whiteBuf = FloatArray(numFrames)
            brownBuf = FloatArray(numFrames)
        }

        freq.render(freqBuf, numFrames, ctx)
        pitch1.render(pitchBuf, numFrames, ctx)
        resonance.render(resBuf, numFrames, ctx)
        voice.render(voiceBuf, numFrames, ctx)
        white.render(whiteBuf, numFrames, ctx)
        brown.render(brownBuf, numFrames, ctx)

        val invSr = ctx.inverseSampleRate.toDouble()
        val sr = ctx.sampleRate.toDouble()
        val maxFreq = ctx.nyquist.toDouble()
        val rng = ThreadLocalRandom.current()

        for (i in 0 until numFrames) {
            val baseFreq = (abs(freqBuf[i].toDouble()) * 8.0).coerceIn(0.0, maxFreq)
            val digital = digitalNoise.next(baseFreq, invSr, rng, amplitude = 0.5)

            val analogBlend = (midiFromHz(baseFreq.coerceAtLeast(1.0)) / 127.0).coerceIn(0.0, 1.0)
            val analog = mix(brownBuf[i].toDouble() * 0.5, whiteBuf[i].toDouble() * 0.5, analogBlend.toFloat())
            val blended = mix(digital, analog, voiceBuf[i])

            val cutoff = (baseFreq * 5.0 * pitchBuf[i].coerceAtLeast(0f)).coerceIn(60.0, maxFreq)
            val filtered = ladder.process(blended, cutoff, resBuf[i].toDouble(), sr)
            val highPassed = highPass.process(filtered.toDouble(), 20.0, sr)
            output[i] = highPassed.coerceIn(-1f, 1f) * 0.3f
        }
    }
}

/**
 * Continuous tuning-fork tone based on the partial set used by SuperDirt's `superfork`.
 * The original per-note exponential decays are expected to be supplied by an external envelope here.
 */
class SuperFork(
    val freq: Param = Param(440f)
) : Signal() {
    private val partialRatios = doubleArrayOf(1.0, 6.1, 16.7, 20.1)
    private val partialLevels = doubleArrayOf(0.3, 1.0, 1.3, 2.0)
    private val phases = DoubleArray(partialRatios.size)
    private var freqBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (freqBuf.size < numFrames) freqBuf = FloatArray(numFrames)
        freq.render(freqBuf, numFrames, ctx)

        val invSr = ctx.inverseSampleRate.toDouble()
        val maxFreq = ctx.nyquist.toDouble()

        for (i in 0 until numFrames) {
            val baseFreq = abs(freqBuf[i].toDouble()).coerceAtLeast(0.0)
            var sample = 0.0

            for (index in partialRatios.indices) {
                val partialFreq = (baseFreq * partialRatios[index]).coerceIn(0.0, maxFreq)
                sample += 0.2 * partialLevels[index] * sin(2.0 * PI * phases[index])
                phases[index] = wrapPhase(phases[index] + partialFreq * invSr)
            }

            output[i] = (sample / partialRatios.size.toDouble()).toFloat()
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  WavetableOsc – arbitrary waveform playback
// ═══════════════════════════════════════════════════════════════════


class WavetableOsc(
    val table: FloatArray,
    val freq: Param = Param(440f)
) : Signal() {
    private var phase: Double = 0.0
    private val size = table.size
    private var freqBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (freqBuf.size < numFrames) freqBuf = FloatArray(numFrames)
        freq.render(freqBuf, numFrames, ctx)
        val invSr = ctx.inverseSampleRate.toDouble()
        for (i in 0 until numFrames) {
            val pos = phase * size
            val idx = pos.toInt()
            val frac = (pos - idx).toFloat()
            // Hermite interpolation (4-point, 3rd-order)
            val s0 = table[(idx - 1 + size) % size]
            val s1 = table[idx % size]
            val s2 = table[(idx + 1) % size]
            val s3 = table[(idx + 2) % size]
            output[i] = hermite(frac, s0, s1, s2, s3)

            phase += freqBuf[i].toDouble() * invSr
            phase -= floor(phase)
        }
    }
}

/** Hermite 4-point interpolation. */
internal fun hermite(frac: Float, s0: Float, s1: Float, s2: Float, s3: Float): Float {
    val c0 = s1
    val c1 = 0.5f * (s2 - s0)
    val c2 = s0 - 2.5f * s1 + 2f * s2 - 0.5f * s3
    val c3 = 0.5f * (s3 - s0) + 1.5f * (s1 - s2)
    return ((c3 * frac + c2) * frac + c1) * frac + c0
}

// ═══════════════════════════════════════════════════════════════════
//  Wavetables – factory for common single-cycle waveforms
// ═══════════════════════════════════════════════════════════════════


object Wavetables {

    fun sine(size: Int = 2048): FloatArray {
        val t = FloatArray(size)
        val step = 2.0 * PI / size
        for (i in 0 until size) t[i] = sin(step * i).toFloat()
        return t
    }

    /** Band-limited sawtooth (first [harmonics] partials via additive). */
    fun saw(size: Int = 2048, harmonics: Int = 64): FloatArray {
        val t = FloatArray(size)
        val step = 2.0 * PI / size
        for (h in 1..harmonics) {
            val amp = 2.0 / (PI * h) * if (h % 2 == 0) 1.0 else -1.0
            for (i in 0 until size) t[i] += (amp * sin(step * i * h)).toFloat()
        }
        return t
    }

    /** Band-limited square (odd harmonics only). */
    fun square(size: Int = 2048, harmonics: Int = 64): FloatArray {
        val t = FloatArray(size)
        val step = 2.0 * PI / size
        for (h in 1..harmonics step 2) {
            val amp = 4.0 / (PI * h)
            for (i in 0 until size) t[i] += (amp * sin(step * i * h)).toFloat()
        }
        return t
    }

    /** Band-limited triangle (odd harmonics, alternating sign, 1/n²). */
    fun triangle(size: Int = 2048, harmonics: Int = 64): FloatArray {
        val t = FloatArray(size)
        val step = 2.0 * PI / size
        var sign = 1.0
        for (h in 1..harmonics step 2) {
            val amp = 8.0 / (PI * PI * h.toDouble() * h.toDouble()) * sign
            for (i in 0 until size) t[i] += (amp * sin(step * i * h)).toFloat()
            sign *= -1.0
        }
        return t
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Noise generators
// ═══════════════════════════════════════════════════════════════════


class WhiteNoise : Signal() {
    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        val rng = ThreadLocalRandom.current()
        for (i in 0 until numFrames) {
            output[i] = (rng.nextFloat() * 2f - 1f)
        }
    }
}


class PinkNoise : Signal() {
    private var b0 = 0.0; private var b1 = 0.0; private var b2 = 0.0
    private var b3 = 0.0; private var b4 = 0.0; private var b5 = 0.0
    private var b6 = 0.0

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        val rng = ThreadLocalRandom.current()
        for (i in 0 until numFrames) {
            val white = rng.nextDouble() * 2.0 - 1.0
            b0 = 0.99886 * b0 + white * 0.0555179
            b1 = 0.99332 * b1 + white * 0.0750759
            b2 = 0.96900 * b2 + white * 0.1538520
            b3 = 0.86650 * b3 + white * 0.3104856
            b4 = 0.55000 * b4 + white * 0.5329522
            b5 = -0.7616 * b5 - white * 0.0168980
            val pink = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362
            b6 = white * 0.115926
            output[i] = (pink * 0.11).toFloat()  // normalize roughly to [-1, 1]
        }
    }
}


class BrownNoise : Signal() {
    private var state = 0.0

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        val rng = ThreadLocalRandom.current()
        for (i in 0 until numFrames) {
            state += (rng.nextDouble() * 2.0 - 1.0) * 0.02
            state *= 0.998  // gentle leak
            state = state.coerceIn(-1.0, 1.0)
            output[i] = state.toFloat()
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Impulse – periodic impulse train
// ═══════════════════════════════════════════════════════════════════


class Impulse(
    val freq: Param = Param(1f)
) : Signal() {
    private var phase: Double = 0.0
    private var freqBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (freqBuf.size < numFrames) freqBuf = FloatArray(numFrames)
        freq.render(freqBuf, numFrames, ctx)
        val invSr = ctx.inverseSampleRate.toDouble()
        for (i in 0 until numFrames) {
            val prevPhase = phase
            phase += abs(freqBuf[i].toDouble()) * invSr
            output[i] = if (floor(phase) > floor(prevPhase)) 1f else 0f
            phase -= floor(phase)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  SampleAndHold
// ═══════════════════════════════════════════════════════════════════


class SampleAndHold(
    val input: Signal,
    val trigger: Signal
) : Signal() {
    private var held = 0f
    private var prevTrig = 0f
    private var inBuf  = FloatArray(0)
    private var trigBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (inBuf.size < numFrames) {
            inBuf = FloatArray(numFrames)
            trigBuf = FloatArray(numFrames)
        }
        input.render(inBuf, numFrames, ctx)
        trigger.render(trigBuf, numFrames, ctx)
        for (i in 0 until numFrames) {
            val t = trigBuf[i]
            if (t > 0f && prevTrig <= 0f) held = inBuf[i]
            prevTrig = t
            output[i] = held
        }
    }
}
