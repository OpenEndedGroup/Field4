package afield.four.synth

import java.util.concurrent.ThreadLocalRandom
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

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
private fun hermite(frac: Float, s0: Float, s1: Float, s2: Float, s3: Float): Float {
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
