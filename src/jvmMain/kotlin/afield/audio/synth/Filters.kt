@file:afield.runtime.completion.CompletionCategory("Synthesis")
package afield.audio.synth

import kotlin.math.*

/**
 * The filters, and the one saturator that keeps them company. Each is a [SignalProcessor], so
 * `saw / SVF(cutoff = 800.p)` is how one gets used, and every coefficient is recomputed per sample
 * from its [Param]s, which is what lets a cutoff be swept at audio rate.
 *
 * Which one to reach for. [SVF] is the default for anything modulated: it is the state-variable form
 * under a topology-preserving transform, and it stays well behaved while the cutoff is moving.
 * [Biquad] is the Audio EQ Cookbook set, second order in eight responses including the two shelves
 * and a peaking EQ, and is what to use for a fixed curve. [OnePole] is 6 dB per octave and costs
 * almost nothing. [MoogLadder] and [DiodeLadder] are four-pole resonant lowpasses that keep the
 * character of the circuits they are named after.
 *
 * The rest are more particular. [CombFilter] and [AllpassFilter] are the classic reverb building
 * blocks offered raw, in feedforward or feedback form; the reverbs in `Effects.kt` build their own
 * rather than compose these. [FormantFilter] parks three bandpasses on vowel formants and morphs
 * between A, E, I, O and U on a single control. [DCBlocker] is the one-line high-pass that removes
 * the offset rectification and asymmetric shaping leave behind. [Saturator] is a bare `tanh` with a
 * drive in front of it, which is often what a ladder filter's output wants next.
 *
 * At the end of the file are the nonlinear ones. [AdaaShaper] is a waveshaper ([AdaaShape.TANH], hard
 * clip, cubic) with antiderivative anti-aliasing, which keeps a hard-driven curve from folding
 * harmonics back below Nyquist. [ZdfLadder] and [ZdfSvf] are a four-pole ladder and a state-variable
 * filter with the saturation inside a zero-delay-feedback loop solved every sample, so both
 * self-oscillate cleanly at their cutoff. [Smoother] takes the steps out of a control signal.
 */

// ═══════════════════════════════════════════════════════════════════
//  OnePole – simple one-pole filter
// ═══════════════════════════════════════════════════════════════════

/**
 * First-order (6 dB/oct) one-pole filter.
 * [mode]: `LP` for lowpass, `HP` for highpass.
 */
class OnePole(
    val cutoff: Param = Param(1000f),
    val mode: Mode = Mode.LP
) : SignalProcessor() {
    enum class Mode { LP, HP }

    private var y1 = 0f
    private var inBuf = FloatArray(0)
    private var cBuf  = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (inBuf.size < numFrames) { inBuf = FloatArray(numFrames); cBuf = FloatArray(numFrames) }
        input.render(inBuf, numFrames, ctx)
        cutoff.render(cBuf, numFrames, ctx)
        val invSr = ctx.inverseSampleRate.toDouble()
        for (i in 0 until numFrames) {
            val freq = cBuf[i].toDouble().coerceIn(1.0, ctx.nyquist.toDouble())
            val b1 = exp(-2.0 * PI * freq * invSr).toFloat()
            val a0 = 1f - b1
            y1 = a0 * inBuf[i] + b1 * y1
            output[i] = when (mode) {
                Mode.LP -> y1
                Mode.HP -> inBuf[i] - y1
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Biquad – second-order IIR filter
// ═══════════════════════════════════════════════════════════════════


enum class BiquadType {
    LPF, HPF, BPF, NOTCH, APF, PEAKING, LOW_SHELF, HIGH_SHELF
}

/**
 * This signal through a 12 dB/octave [Biquad] lowpass at [cutoffHz]: `bass.lowpass(150f)`.
 *
 * Box code reaches for this rather than `Biquad(BiquadType.LPF, …)` because `afield.math.statistics` has a
 * `BiquadType` of its own, so the bare name is ambiguous in a box. Chain two for 24 dB/octave.
 */
fun Signal.lowpass(cutoffHz: Float, q: Float = 0.707f): Signal = through(Biquad(BiquadType.LPF, Param(cutoffHz), Param(q)))

/** This signal through a 12 dB/octave [Biquad] highpass at [cutoffHz]. See [lowpass]. */
fun Signal.highpass(cutoffHz: Float, q: Float = 0.707f): Signal = through(Biquad(BiquadType.HPF, Param(cutoffHz), Param(q)))

class Biquad(
    val type: BiquadType = BiquadType.LPF,
    val cutoff: Param = Param(1000f),
    val q: Param = Param(0.707f),
    /** Gain in dB for PEAKING / LOW_SHELF / HIGH_SHELF. */
    val gainDb: Param = Param(0f)
) : SignalProcessor() {

    // biquad state (transposed direct-form II)
    private var s1 = 0f; private var s2 = 0f

    private var inBuf  = FloatArray(0)
    private var cBuf   = FloatArray(0)
    private var qBuf   = FloatArray(0)
    private var gBuf   = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (inBuf.size < numFrames) {
            inBuf = FloatArray(numFrames); cBuf = FloatArray(numFrames)
            qBuf = FloatArray(numFrames); gBuf = FloatArray(numFrames)
        }
        input.render(inBuf, numFrames, ctx)
        cutoff.render(cBuf, numFrames, ctx)
        q.render(qBuf, numFrames, ctx)
        gainDb.render(gBuf, numFrames, ctx)
        val sr = ctx.sampleRate.toDouble()

        for (i in 0 until numFrames) {
            val freq = cBuf[i].toDouble().coerceIn(20.0, sr * 0.499)
            val Q = qBuf[i].toDouble().coerceAtLeast(0.1)
            val dBGain = gBuf[i].toDouble()
            cook(freq, Q, dBGain, sr)
            // transposed direct form II
            val x = inBuf[i]
            val y = b0 * x + s1
            s1 = (b1 * x - a1 * y + s2).toFloat()
            s2 = (b2 * x - a2 * y).toFloat()
            output[i] = y.toFloat()
        }
    }

    // coefficient temporaries (recalculated per sample)
    private var b0 = 0.0; private var b1 = 0.0; private var b2 = 0.0
    private var a1 = 0.0; private var a2 = 0.0

    private fun cook(freq: Double, Q: Double, dBGain: Double, sr: Double) {
        val w0 = 2.0 * PI * freq / sr
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / (2.0 * Q)
        val A = 10.0.pow(dBGain / 40.0)

        var a0t: Double
        when (type) {
            BiquadType.LPF -> {
                b0 = (1.0 - cosW0) / 2.0; b1 = 1.0 - cosW0; b2 = b0
                a0t = 1.0 + alpha; a1 = -2.0 * cosW0; a2 = 1.0 - alpha
            }
            BiquadType.HPF -> {
                b0 = (1.0 + cosW0) / 2.0; b1 = -(1.0 + cosW0); b2 = b0
                a0t = 1.0 + alpha; a1 = -2.0 * cosW0; a2 = 1.0 - alpha
            }
            BiquadType.BPF -> {
                b0 = alpha; b1 = 0.0; b2 = -alpha
                a0t = 1.0 + alpha; a1 = -2.0 * cosW0; a2 = 1.0 - alpha
            }
            BiquadType.NOTCH -> {
                b0 = 1.0; b1 = -2.0 * cosW0; b2 = 1.0
                a0t = 1.0 + alpha; a1 = -2.0 * cosW0; a2 = 1.0 - alpha
            }
            BiquadType.APF -> {
                b0 = 1.0 - alpha; b1 = -2.0 * cosW0; b2 = 1.0 + alpha
                a0t = 1.0 + alpha; a1 = -2.0 * cosW0; a2 = 1.0 - alpha
            }
            BiquadType.PEAKING -> {
                b0 = 1.0 + alpha * A; b1 = -2.0 * cosW0; b2 = 1.0 - alpha * A
                a0t = 1.0 + alpha / A; a1 = -2.0 * cosW0; a2 = 1.0 - alpha / A
            }
            BiquadType.LOW_SHELF -> {
                val sq = 2.0 * sqrt(A) * alpha
                b0 = A * ((A + 1.0) - (A - 1.0) * cosW0 + sq)
                b1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * cosW0)
                b2 = A * ((A + 1.0) - (A - 1.0) * cosW0 - sq)
                a0t = (A + 1.0) + (A - 1.0) * cosW0 + sq
                a1 = -2.0 * ((A - 1.0) + (A + 1.0) * cosW0)
                a2 = (A + 1.0) + (A - 1.0) * cosW0 - sq
            }
            BiquadType.HIGH_SHELF -> {
                val sq = 2.0 * sqrt(A) * alpha
                b0 = A * ((A + 1.0) + (A - 1.0) * cosW0 + sq)
                b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cosW0)
                b2 = A * ((A + 1.0) + (A - 1.0) * cosW0 - sq)
                a0t = (A + 1.0) - (A - 1.0) * cosW0 + sq
                a1 = 2.0 * ((A - 1.0) - (A + 1.0) * cosW0)
                a2 = (A + 1.0) - (A - 1.0) * cosW0 - sq
            }
        }
        // normalize
        b0 /= a0t; b1 /= a0t; b2 /= a0t; a1 /= a0t; a2 /= a0t
    }
}

// ═══════════════════════════════════════════════════════════════════
//  SVF – State Variable Filter (TPT / Cytomic)
// ═══════════════════════════════════════════════════════════════════


enum class SVFMode { LP, HP, BP, NOTCH }

class SVF(
    val mode: SVFMode = SVFMode.LP,
    val cutoff: Param = Param(1000f),
    val resonance: Param = Param(0.5f)
) : SignalProcessor() {

    private var ic1eq = 0.0; private var ic2eq = 0.0
    private var inBuf = FloatArray(0); private var cBuf = FloatArray(0); private var rBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (inBuf.size < numFrames) {
            inBuf = FloatArray(numFrames); cBuf = FloatArray(numFrames); rBuf = FloatArray(numFrames)
        }
        input.render(inBuf, numFrames, ctx)
        cutoff.render(cBuf, numFrames, ctx)
        resonance.render(rBuf, numFrames, ctx)
        val sr = ctx.sampleRate.toDouble()

        for (i in 0 until numFrames) {
            val freq = cBuf[i].toDouble().coerceIn(20.0, sr * 0.499)
            val res = rBuf[i].toDouble().coerceIn(0.0, 1.0)
            val g = tan(PI * freq / sr)
            val k = 2.0 - 2.0 * res  // damping: 0 = max resonance, 2 = no resonance

            val a1 = 1.0 / (1.0 + g * (g + k))
            val a2 = g * a1
            val a3 = g * a2

            val v3 = inBuf[i] - ic2eq
            val v1 = a1 * ic1eq + a2 * v3
            val v2 = ic2eq + a2 * ic1eq + a3 * v3
            ic1eq = 2.0 * v1 - ic1eq
            ic2eq = 2.0 * v2 - ic2eq

            output[i] = when (mode) {
                SVFMode.LP    -> v2
                SVFMode.BP    -> v1
                SVFMode.HP    -> inBuf[i] - k * v1 - v2
                SVFMode.NOTCH -> inBuf[i] - k * v1
            }.toFloat()
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  MoogLadder – 24 dB/oct resonant lowpass
// ═══════════════════════════════════════════════════════════════════


class MoogLadder(
    val cutoff: Param = Param(1000f),
    val resonance: Param = Param(0.5f)
) : SignalProcessor() {

    // Four cascaded one-pole stages + their delayed output
    private var stage = DoubleArray(4)
    private var inBuf = FloatArray(0); private var cBuf = FloatArray(0); private var rBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (inBuf.size < numFrames) {
            inBuf = FloatArray(numFrames); cBuf = FloatArray(numFrames); rBuf = FloatArray(numFrames)
        }
        input.render(inBuf, numFrames, ctx)
        cutoff.render(cBuf, numFrames, ctx)
        resonance.render(rBuf, numFrames, ctx)
        val sr = ctx.sampleRate.toDouble()

        for (i in 0 until numFrames) {
            val freq = cBuf[i].toDouble().coerceIn(20.0, sr * 0.499)
            val res = rBuf[i].toDouble().coerceIn(0.0, 1.0) * 4.0  // 0..4 feedback

            // Frequency coefficient (proper bilinear-ish warping)
            val f = 2.0 * sin(PI * freq / sr)

            // Feedback with resonance compensation
            val fb = res * (1.0 - 0.15 * f * f)
            val inp = inBuf[i].toDouble() - fb * stage[3]

            // Four cascaded one-pole sections with tanh nonlinearity
            stage[0] += f * (tanh(inp)       - tanh(stage[0]))
            stage[1] += f * (tanh(stage[0])  - tanh(stage[1]))
            stage[2] += f * (tanh(stage[1])  - tanh(stage[2]))
            stage[3] += f * (tanh(stage[2])  - tanh(stage[3]))

            output[i] = stage[3].toFloat()
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  CombFilter – feedforward & feedback comb filter
// ═══════════════════════════════════════════════════════════════════


class CombFilter(
    /** Delay time in seconds. */
    val delayTime: Param = Param(0.01f),
    /** Feedback/feedforward gain (-1…+1 for stability). */
    val feedback: Param = Param(0.7f),
    val mode: CombMode = CombMode.FEEDBACK,
    maxDelaySec: Float = 1f
) : SignalProcessor() {
    enum class CombMode { FEEDFORWARD, FEEDBACK }

    private var buffer: FloatArray = FloatArray(0)
    private var writePos = 0
    private var bufSize = 0

    private var inBuf = FloatArray(0); private var dBuf = FloatArray(0); private var fBuf = FloatArray(0)

    private fun ensureBuffer(sampleRate: Float, maxDelay: Float) {
        val needed = (sampleRate * maxDelay).toInt() + 4
        if (bufSize < needed) {
            buffer = FloatArray(needed)
            bufSize = needed
            writePos = 0
        }
    }

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        ensureBuffer(ctx.sampleRate, 1f)
        if (inBuf.size < numFrames) {
            inBuf = FloatArray(numFrames); dBuf = FloatArray(numFrames); fBuf = FloatArray(numFrames)
        }
        input.render(inBuf, numFrames, ctx)
        delayTime.render(dBuf, numFrames, ctx)
        feedback.render(fBuf, numFrames, ctx)

        for (i in 0 until numFrames) {
            val delaySamples = (dBuf[i] * ctx.sampleRate).toDouble().coerceIn(1.0, (bufSize - 1).toDouble())
            val readPos = writePos - delaySamples.toInt()
            val idx = if (readPos >= 0) readPos else readPos + bufSize
            val delayed = buffer[idx % bufSize]
            val fb = fBuf[i].coerceIn(-0.999f, 0.999f)
            val out = when (mode) {
                CombMode.FEEDFORWARD -> inBuf[i] + fb * delayed
                CombMode.FEEDBACK   -> inBuf[i] + fb * delayed
            }
            output[i] = out
            buffer[writePos] = when (mode) {
                CombMode.FEEDFORWARD -> inBuf[i]
                CombMode.FEEDBACK    -> out
            }
            writePos = (writePos + 1) % bufSize
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  AllpassFilter – allpass filter (for reverb, phaser, etc.)
// ═══════════════════════════════════════════════════════════════════


class AllpassFilter(
    val delayTime: Param = Param(0.005f),
    val coefficient: Param = Param(0.5f),
    maxDelaySec: Float = 0.5f
) : SignalProcessor() {
    private var buffer = FloatArray(0)
    private var writePos = 0
    private var bufSize = 0
    private var inBuf = FloatArray(0); private var dBuf = FloatArray(0); private var cBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        val needed = (ctx.sampleRate * 0.5f).toInt() + 4
        if (bufSize < needed) { buffer = FloatArray(needed); bufSize = needed; writePos = 0 }
        if (inBuf.size < numFrames) {
            inBuf = FloatArray(numFrames); dBuf = FloatArray(numFrames); cBuf = FloatArray(numFrames)
        }
        input.render(inBuf, numFrames, ctx)
        delayTime.render(dBuf, numFrames, ctx)
        coefficient.render(cBuf, numFrames, ctx)

        for (i in 0 until numFrames) {
            val delaySamples = (dBuf[i] * ctx.sampleRate).toInt().coerceIn(1, bufSize - 1)
            val readIdx = ((writePos - delaySamples + bufSize) % bufSize)
            val delayed = buffer[readIdx]
            val g = cBuf[i]
            val out = -g * inBuf[i] + delayed + g * delayed
            buffer[writePos] = inBuf[i] + g * delayed
            output[i] = out
            writePos = (writePos + 1) % bufSize
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  DCBlocker – removes DC offset
// ═══════════════════════════════════════════════════════════════════


class DCBlocker : SignalProcessor() {
    private var x1 = 0f; private var y1 = 0f
    private var inBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (inBuf.size < numFrames) inBuf = FloatArray(numFrames)
        input.render(inBuf, numFrames, ctx)
        // y[n] = x[n] - x[n-1] + R * y[n-1], R ~ 0.995
        val r = 0.995f
        for (i in 0 until numFrames) {
            val x = inBuf[i]
            y1 = x - x1 + r * y1
            x1 = x
            output[i] = y1
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  FormantFilter – vowel formant synthesis
// ═══════════════════════════════════════════════════════════════════


class FormantFilter(
    /** Vowel morph: 0=A, 1=E, 2=I, 3=O, 4=U (interpolated). */
    val morph: Param = Param(0f)
) : SignalProcessor() {
    // Formant table: [vowel][formant] = (freq, bandwidth, amplitude)
    companion object {
        private val VOWELS = arrayOf(
            // A
            floatArrayOf(800f, 80f, 1f,  1150f, 90f, 0.5f,  2900f, 120f, 0.25f),
            // E
            floatArrayOf(400f, 60f, 1f,  1600f, 80f, 0.5f,  2700f, 120f, 0.25f),
            // I
            floatArrayOf(350f, 50f, 1f,  2000f, 100f, 0.4f,  2800f, 120f, 0.2f),
            // O
            floatArrayOf(450f, 70f, 1f,  800f, 80f, 0.5f,   2830f, 100f, 0.2f),
            // U
            floatArrayOf(325f, 50f, 1f,  700f, 60f, 0.4f,   2530f, 100f, 0.2f)
        )
    }

    // 3 biquad BPF states (one per formant)
    private val s1 = FloatArray(3); private val s2 = FloatArray(3)
    private var inBuf = FloatArray(0); private var mBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (inBuf.size < numFrames) { inBuf = FloatArray(numFrames); mBuf = FloatArray(numFrames) }
        input.render(inBuf, numFrames, ctx)
        morph.render(mBuf, numFrames, ctx)
        val sr = ctx.sampleRate.toDouble()
        Buf.clear(output, 0, numFrames)

        for (i in 0 until numFrames) {
            val m = mBuf[i].toDouble().coerceIn(0.0, 4.0)
            val idx = m.toInt().coerceAtMost(3)
            val frac = (m - idx).toFloat()
            val v0 = VOWELS[idx]; val v1 = VOWELS[idx + if (idx < 4) 1 else 0]
            var sum = 0f
            for (f in 0 until 3) {
                val freq = (v0[f * 3] + (v1[f * 3] - v0[f * 3]) * frac).toDouble().coerceIn(20.0, sr * 0.499)
                val bw   = v0[f * 3 + 1] + (v1[f * 3 + 1] - v0[f * 3 + 1]) * frac
                val amp  = v0[f * 3 + 2] + (v1[f * 3 + 2] - v0[f * 3 + 2]) * frac
                val w0 = 2.0 * PI * freq / sr
                val alpha = sin(w0) * sinh(ln(2.0) / 2.0 * bw / freq * w0 / sin(w0))
                val a0 = 1.0 + alpha
                val b0r = (alpha / a0).toFloat()
                val a1r = (-2.0 * cos(w0) / a0).toFloat()
                val a2r = ((1.0 - alpha) / a0).toFloat()
                // transposed direct form II
                val x = inBuf[i]
                val y = b0r * x + s1[f]
                s1[f] = (-a1r * y + s2[f])
                s2[f] = (-b0r * x - a2r * y)
                sum += y * amp
            }
            output[i] = sum
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  LadderVCF – diode ladder filter (Roland-style)
// ═══════════════════════════════════════════════════════════════════


class DiodeLadder(
    val cutoff: Param = Param(1000f),
    val resonance: Param = Param(0.5f)
) : SignalProcessor() {
    private var s = DoubleArray(4)
    private var inBuf = FloatArray(0); private var cBuf = FloatArray(0); private var rBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (inBuf.size < numFrames) {
            inBuf = FloatArray(numFrames); cBuf = FloatArray(numFrames); rBuf = FloatArray(numFrames)
        }
        input.render(inBuf, numFrames, ctx)
        cutoff.render(cBuf, numFrames, ctx)
        resonance.render(rBuf, numFrames, ctx)
        val sr = ctx.sampleRate.toDouble()

        for (i in 0 until numFrames) {
            val freq = cBuf[i].toDouble().coerceIn(20.0, sr * 0.499)
            val res = rBuf[i].toDouble().coerceIn(0.0, 1.0) * 17.0  // feedback gain

            val g = tan(PI * freq / sr)
            val G = g / (1.0 + g)

            // feedback path
            val sigma = s[0] * G * G * G + s[1] * G * G + s[2] * G + s[3]
            val u = (inBuf[i].toDouble() - res * sigma) / (1.0 + res * G * G * G * G)

            // four cascaded TPT integrators
            var v = u
            for (j in 0 until 4) {
                val vn = (v - s[j]) * G
                val out = vn + s[j]
                s[j] = out + vn
                v = out
            }
            output[i] = v.toFloat()
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Saturator – soft clipping / tanh saturation
// ═══════════════════════════════════════════════════════════════════


class Saturator(
    val drive: Param = Param(1f)
) : SignalProcessor() {
    private var inBuf = FloatArray(0); private var dBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (inBuf.size < numFrames) { inBuf = FloatArray(numFrames); dBuf = FloatArray(numFrames) }
        input.render(inBuf, numFrames, ctx)
        drive.render(dBuf, numFrames, ctx)
        for (i in 0 until numFrames) {
            output[i] = tanh(inBuf[i].toDouble() * dBuf[i].toDouble()).toFloat()
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Topology-preserving filters after evieve (Pete Dowling, GPL-3.0)
// ═══════════════════════════════════════════════════════════════════
//
// The filters below follow the families in evieve, a Max package of GenExpr audio code
// (https://github.com/steadykammer/evieve, © Peter Dowling 2026, GPL v3). They are written from the
// published methods those files cite — Zavalishin's "The Art of VA Filter Design" for the
// trapezoidal one-pole and the instantaneous loop solve, Will Pirkle's Korg35 application note for
// the MS-20 topology — rather than transcribed, and every coefficient is computed per sample from
// its Param, so any of them can be swept by another Signal at audio rate (`Param().also { it.mod = lfo }`).

/** The response [TptOnePole] reads from its one trapezoidal integrator. */
enum class TptOnePoleMode { LP, HP, AP }

/**
 * A first-order filter by trapezoidal integration (zero-delay feedback): the analog `1/(1+s)` mapped
 * by the bilinear transform with its cutoff prewarped, so the −3 dB point is exactly [cutoff] at any
 * sample rate. [OnePole] is the cheaper impulse-invariant version, whose cutoff drifts near Nyquist
 * and which has no allpass; this one also gives `AP`, the first-order phase shifter phasers stack.
 *
 * ```kotlin
 * val sweep = Param(800f).also { it.mod = SineOsc(Param(3f)) * 600f }
 * saw / TptOnePole(sweep, TptOnePoleMode.LP)
 * ```
 */
class TptOnePole(
    val cutoff: Param = Param(1000f),
    val mode: TptOnePoleMode = TptOnePoleMode.LP
) : SignalProcessor() {
    private var s = 0.0
    private var inBuf = FloatArray(0); private var cBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (inBuf.size < numFrames) { inBuf = FloatArray(numFrames); cBuf = FloatArray(numFrames) }
        input.render(inBuf, numFrames, ctx)
        cutoff.render(cBuf, numFrames, ctx)
        val sr = ctx.sampleRate.toDouble()
        for (i in 0 until numFrames) {
            val g = tan(PI * cBuf[i].toDouble().coerceIn(1.0, sr * 0.49) / sr)
            val G = g / (1.0 + g)
            val x = inBuf[i].toDouble()
            val v = (x - s) * G
            val lp = v + s
            s = lp + v
            output[i] = when (mode) {
                TptOnePoleMode.LP -> lp
                TptOnePoleMode.HP -> x - lp
                TptOnePoleMode.AP -> 2.0 * lp - x
            }.toFloat()
        }
    }
}

/**
 * A tilt equaliser (evieve's `evi_filter_tilt`): one trapezoidal one-pole split at [pivot] into its
 * lowpass and highpass halves, the lows scaled by `−tilt` dB and the highs by `+tilt` dB. Positive
 * [tiltDb] brightens, negative darkens, and at 0 the two halves sum back to the input exactly.
 */
class TiltEQ(
    val pivot: Param = Param(800f),
    val tiltDb: Param = Param(0f)
) : SignalProcessor() {
    private var s = 0.0
    private var inBuf = FloatArray(0); private var pBuf = FloatArray(0); private var tBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (inBuf.size < numFrames) { inBuf = FloatArray(numFrames); pBuf = FloatArray(numFrames); tBuf = FloatArray(numFrames) }
        input.render(inBuf, numFrames, ctx)
        pivot.render(pBuf, numFrames, ctx)
        tiltDb.render(tBuf, numFrames, ctx)
        val sr = ctx.sampleRate.toDouble()
        for (i in 0 until numFrames) {
            val g = tan(PI * pBuf[i].toDouble().coerceIn(1.0, sr * 0.49) / sr)
            val G = g / (1.0 + g)
            val x = inBuf[i].toDouble()
            val v = (x - s) * G
            val lp = v + s
            s = lp + v
            val up = 10.0.pow(tBuf[i].toDouble() / 20.0)
            output[i] = (lp / up + (x - lp) * up).toFloat()
        }
    }
}

/** Which output of [Korg35] to listen to. */
enum class Korg35Mode { LP, HP }

/**
 * The Korg MS-20's Sallen-Key filter (the Korg35 chip), as a zero-delay-feedback loop: two
 * trapezoidal one-poles in the forward path and a highpass one-pole feeding the output back, with
 * the instantaneous loop solved exactly each sample and a `tanh` on the solved feedback node, as in
 * evieve's `evi_korgms20`/`evi_korgms10` and Pirkle's application note.
 *
 * At small signal the lowpass is `K/(s² + (2−K)s + 1)` divided back down by `K`, a 12 dB/octave
 * resonant lowpass whose damping `2−K` reaches zero, and self-oscillation, as [resonance] reaches 1.
 * The highpass mirrors it — highpass stages forward, a lowpass in the feedback — giving `s²/(s² + (2−K)s + 1)`. [drive] is the gain into the
 * `tanh`: at 1 it is the circuit's soft limit on the resonant peak; push it for the MS-20's growl.
 */
class Korg35(
    val cutoff: Param = Param(1000f),
    val resonance: Param = Param(0.5f),
    val mode: Korg35Mode = Korg35Mode.LP,
    val drive: Param = Param(1f)
) : SignalProcessor() {
    private var s1 = 0.0; private var s2 = 0.0; private var s3 = 0.0
    private var inBuf = FloatArray(0); private var cBuf = FloatArray(0)
    private var rBuf = FloatArray(0); private var dBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (inBuf.size < numFrames) {
            inBuf = FloatArray(numFrames); cBuf = FloatArray(numFrames)
            rBuf = FloatArray(numFrames); dBuf = FloatArray(numFrames)
        }
        input.render(inBuf, numFrames, ctx)
        cutoff.render(cBuf, numFrames, ctx)
        resonance.render(rBuf, numFrames, ctx)
        drive.render(dBuf, numFrames, ctx)
        val sr = ctx.sampleRate.toDouble()
        for (i in 0 until numFrames) {
            val g = tan(PI * cBuf[i].toDouble().coerceIn(20.0, sr * 0.45) / sr)
            val G = g / (1.0 + g)
            val K = 0.01 + 1.98 * rBuf[i].toDouble().coerceIn(0.0, 1.0)
            val d = dBuf[i].toDouble().coerceAtLeast(1e-3)
            val x = inBuf[i].toDouble()

            // stage 1: lowpass (LP mode) or highpass (HP mode) of the input
            val v1 = (x - s1) * G
            val lp1 = v1 + s1
            s1 = lp1 + v1
            val y1 = if (mode == Korg35Mode.LP) lp1 else x - lp1

            // LP loop: y = K·LP2(y1 + HP3(y)); HP loop: y = K·HP2(y1 + LP3(y)).
            // Each one-pole's lowpass is G·in + c·state (c = 1 − G); solve the loop for y, then saturate u.
            val c = 1.0 - G
            val out: Double
            if (mode == Korg35Mode.LP) {
                val y = K * (G * y1 - G * c * s3 + c * s2) / (1.0 - K * G * c)
                val u = tanh(d * (y1 + c * (y - s3))) / d
                val v2 = (u - s2) * G
                val lp2 = v2 + s2
                s2 = lp2 + v2
                out = K * lp2
                val v3 = (out - s3) * G
                s3 = v3 + s3 + v3
            } else {
                val y = K * c * (y1 + c * s3 - s2) / (1.0 - K * G * c)
                val u = tanh(d * (y1 + G * y + c * s3)) / d
                val v2 = (u - s2) * G
                val lp2 = v2 + s2
                s2 = lp2 + v2
                out = K * (u - lp2)
                val v3 = (out - s3) * G
                s3 = v3 + s3 + v3
            }

            output[i] = (out / K).toFloat()
        }
    }
}

/**
 * A two-pole resonator: a pole pair at [freq] whose radius rings down by 60 dB in [decay] seconds,
 * with the input scaled so the peak gain stays at 1 whatever the decay (evieve's `evi_band_reson`
 * and the formlet are this cell). Strike it with an impulse for a tuned ping, feed it noise for a
 * pitched band, or sweep [freq] at audio rate — the coefficients follow per sample.
 */
class Resonator(
    val freq: Param = Param(440f),
    val decay: Param = Param(0.5f)
) : SignalProcessor() {
    private var y1 = 0.0; private var y2 = 0.0
    private var inBuf = FloatArray(0); private var fBuf = FloatArray(0); private var dBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (inBuf.size < numFrames) { inBuf = FloatArray(numFrames); fBuf = FloatArray(numFrames); dBuf = FloatArray(numFrames) }
        input.render(inBuf, numFrames, ctx)
        freq.render(fBuf, numFrames, ctx)
        decay.render(dBuf, numFrames, ctx)
        val sr = ctx.sampleRate.toDouble()
        for (i in 0 until numFrames) {
            val (r, theta, b0) = resonatorCoefficients(fBuf[i].toDouble(), dBuf[i].toDouble(), sr)
            val y = b0 * inBuf[i] + 2.0 * r * cos(theta) * y1 - r * r * y2
            y2 = y1; y1 = y
            output[i] = y.toFloat()
        }
    }

    companion object {
        /** `(r, θ, b0)` for [Resonator]: pole radius, pole angle, and the gain that holds the peak at 1. */
        fun resonatorCoefficients(freq: Double, decay: Double, sr: Double): Triple<Double, Double, Double> {
            val theta = 2.0 * PI * freq.coerceIn(1.0, sr * 0.49) / sr
            val r = exp(ln(0.001) / (decay.coerceAtLeast(1e-4) * sr))
            // |1 − 2r cosθ e^{−jθ} + r² e^{−2jθ}| at the peak
            val re = 1.0 - 2.0 * r * cos(theta) * cos(theta) + r * r * cos(2.0 * theta)
            val im = 2.0 * r * cos(theta) * sin(theta) - r * r * sin(2.0 * theta)
            return Triple(r, theta, sqrt(re * re + im * im))
        }
    }
}

/**
 * The impulse response of [processor] at [sampleRate]: a unit impulse rendered through it for
 * [frames] samples. Use small [amplitude] to read a nonlinear filter's small-signal response.
 * The processor's state is consumed; pass a fresh one. Its magnitude response is
 * `fft(impulseResponse(f, sampleRate), sampleRate.toDouble()).magnitudeDbOneSided()`, one bin per
 * hertz when [frames] equals [sampleRate].
 */
fun impulseResponse(processor: SignalProcessor, frames: Int = 8192, sampleRate: Int = 48_000, amplitude: Float = 1f): FloatArray {
    var first = true
    processor.input = object : Signal() {
        override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
            Buf.clear(output, 0, numFrames)
            if (first && numFrames > 0) { output[0] = amplitude; first = false }
        }
    }
    val h = renderSignalsOffline(frames.toDouble() / sampleRate, processor, sampleRate = sampleRate)[0]
    if (amplitude != 1f) for (i in h.indices) h[i] /= amplitude
    return h
}

// ═══════════════════════════════════════════════════════════════════
//  Antiderivative anti-aliasing and nonlinear zero-delay-feedback filters (after evieve)
// ═══════════════════════════════════════════════════════════════════
//
// The shapers and filters below follow evieve's saturation and nonlinear-filter families
// (https://github.com/steadykammer/evieve, © Peter Dowling, GPL v3), written in Kotlin from the methods
// those files rest on: antiderivative anti-aliasing after Parker, Zavalishin, Le Bivic and Bilbao
// ("Reducing the aliasing of nonlinear waveshaping using continuous-time convolution", DAFx 2016) and
// Bilbao, Esqueda, Parker and Välimäki ("Antiderivative antialiasing for memoryless nonlinearities",
// IEEE SPL 2017); the nonlinear ladder and SVF from Zavalishin's "The Art of VA Filter Design",
// with the instantaneous loop solved by Newton's method each sample.

/** The curve an [AdaaShaper] applies. Each one carries its antiderivatives, which is what ADAA needs. */
enum class AdaaShape {
    /** `tanh(x)`: the smooth saturator. First order only (its second antiderivative needs the dilogarithm). */
    TANH,
    /** `x` clamped to ±1: the hardest corner, and the one that aliases most when computed naively. */
    HARD_CLIP,
    /** The cubic soft clip `x − x³/3` inside ±1, flat at ±2/3 outside: a corner in the second derivative only. */
    CUBIC;

    /** The curve itself. */
    fun f(x: Double): Double = when (this) {
        TANH -> tanh(x)
        HARD_CLIP -> x.coerceIn(-1.0, 1.0)
        CUBIC -> if (abs(x) <= 1.0) x - x * x * x / 3.0 else sign(x) * 2.0 / 3.0
    }

    /** Its first antiderivative, zero at zero. For `tanh` it is `ln cosh x`, written so large `x` does not overflow. */
    fun f1(x: Double): Double {
        val a = abs(x)
        return when (this) {
            TANH -> a + ln1p(exp(-2.0 * a)) - ln(2.0)
            HARD_CLIP -> if (a <= 1.0) 0.5 * x * x else a - 0.5
            CUBIC -> if (a <= 1.0) 0.5 * x * x - x * x * x * x / 12.0 else 2.0 * a / 3.0 - 0.25
        }
    }

    /** Its second antiderivative, zero at zero; [TANH] has none in closed form and throws. */
    fun f2(x: Double): Double {
        val a = abs(x)
        return when (this) {
            TANH -> throw UnsupportedOperationException("tanh has no elementary second antiderivative")
            HARD_CLIP -> if (a <= 1.0) x * x * x / 6.0 else sign(x) * (0.5 * a * a - 0.5 * a + 1.0 / 6.0)
            CUBIC -> if (a <= 1.0) x * x * x / 6.0 - x.pow(5) / 60.0 else sign(x) * (a * a / 3.0 - 0.25 * a + 1.0 / 15.0)
        }
    }
}

/**
 * A waveshaper with antiderivative anti-aliasing (ADAA): instead of the curve's value at each
 * sample it outputs the curve's average over the straight line joining this sample to the last,
 * `(F₁(x[n]) − F₁(x[n−1])) / (x[n] − x[n−1])`. That average is a continuous-time lowpass applied to
 * the shaped signal before it is sampled, so the harmonics the curve makes above Nyquist come back
 * down much quieter. A 4.1 kHz sine driven by 8 through `tanh` at 48 kHz carries 8 dB less total
 * alias power than [Waveshaper.tanh] gives (−25 dB against −17 dB relative to the fundamental), with
 * no oversampling; through the hard clip, order 1 gains 8 dB and order 2 another 7.
 *
 * [order] 2 averages twice (second antiderivative, three samples), which suppresses aliases further
 * for [AdaaShape.HARD_CLIP] and [AdaaShape.CUBIC]; [AdaaShape.TANH] supports order 1 only. When two
 * successive inputs are closer than the division can be trusted the shaper falls back to the curve at
 * their midpoint, which is the limit of the same expression, so the output stays continuous.
 *
 * The averaging costs a half-sample delay per order and a gentle high-frequency roll-off (about
 * −3 dB at 0.7 × Nyquist for order 1). [drive] scales the input before the curve, as in [Waveshaper],
 * and may be modulated at audio rate.
 *
 * ```kotlin
 * SineOsc(4000.p) / AdaaShaper(AdaaShape.TANH, drive = 8.p)
 * ```
 */
class AdaaShaper(
    val shape: AdaaShape = AdaaShape.TANH,
    val drive: Param = Param(1f),
    val order: Int = 1
) : SignalProcessor() {
    init {
        require(order == 1 || order == 2) { "AdaaShaper order must be 1 or 2, was $order" }
        require(order == 1 || shape != AdaaShape.TANH) { "AdaaShaper: TANH supports order 1 only" }
    }

    private var x1 = 0.0; private var x2 = 0.0
    private var inBuf = FloatArray(0); private var dBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (inBuf.size < numFrames) { inBuf = FloatArray(numFrames); dBuf = FloatArray(numFrames) }
        input.render(inBuf, numFrames, ctx)
        drive.render(dBuf, numFrames, ctx)
        for (i in 0 until numFrames) {
            val x0 = inBuf[i].toDouble() * dBuf[i].toDouble()
            output[i] = (if (order == 1) first(shape, x0, x1) else second(shape, x0, x1, x2)).toFloat()
            x2 = x1; x1 = x0
        }
    }

    companion object {
        /** Below this input difference the first-order quotient is replaced by the midpoint value. */
        const val EPS1: Double = 1e-5
        /** The same threshold for the second-order quotients, larger because they divide twice. */
        const val EPS2: Double = 1e-3

        /** First-order ADAA of [shape] between the previous input [xPrev] and this one [x]. */
        fun first(shape: AdaaShape, x: Double, xPrev: Double): Double {
            val d = x - xPrev
            return if (abs(d) > EPS1) (shape.f1(x) - shape.f1(xPrev)) / d else shape.f(0.5 * (x + xPrev))
        }

        private fun d1(shape: AdaaShape, a: Double, b: Double): Double {
            val d = a - b
            return if (abs(d) > EPS2) (shape.f2(a) - shape.f2(b)) / d else shape.f1(0.5 * (a + b))
        }

        /** Second-order ADAA of [shape] over the three inputs `x0` (now), `x1`, `x2` (two samples ago). */
        fun second(shape: AdaaShape, x0: Double, x1: Double, x2: Double): Double {
            val d = x0 - x2
            if (abs(d) > EPS2) return 2.0 / d * (d1(shape, x0, x1) - d1(shape, x1, x2))
            val xb = 0.5 * (x0 + x2)
            val delta = xb - x1
            if (abs(delta) <= EPS2) return shape.f(0.5 * (xb + x1))
            return 2.0 / delta * (shape.f1(xb) + (shape.f2(x1) - shape.f2(xb)) / delta)
        }
    }
}

/** The response [ZdfLadder] reads from its four stages. */
enum class ZdfLadderMode { LP4, LP2, HP4 }

/**
 * A four-pole transistor ladder in zero-delay-feedback form, with a `tanh` at the input of the loop
 * and the loop solved exactly every sample: the one-saturator ladder of Zavalishin's *Art of VA
 * Filter Design*, chapter 6, as in evieve's nonlinear ladder.
 *
 * Each stage is a trapezoidal one-pole prewarped to [cutoff], so at small signal the lowpass is
 * `L⁴/(1 + kL⁴)` with `L = 1/(1 + s)` and `k = 4·resonance`, exact under the bilinear transform. At
 * `resonance = 1` the loop gain at the cutoff reaches one; above it the filter self-oscillates at
 * [cutoff] with an amplitude the `tanh` sets (about `1/drive`). A noise floor near −120 dB is mixed in
 * so it starts to sing with no input at all. Unlike [MoogLadder], whose explicit update detunes the
 * cutoff as it rises, this one stays where it is told.
 *
 * [drive] is the gain into the `tanh` (the output is divided back down by it), so it changes how
 * soon the filter saturates and not its small-signal level. As in the circuit, the passband drops by
 * `1 + k` as resonance rises. `LP2` taps the second stage; `HP4` mixes the stages into a four-pole
 * highpass with the same resonant denominator.
 *
 * ```kotlin
 * SawOsc(55.p) / ZdfLadder(Param(600f).also { it.mod = SineOsc(0.2.p) * 400f }, resonance = 0.9.p)
 * ```
 */
class ZdfLadder(
    val cutoff: Param = Param(1000f),
    val resonance: Param = Param(0.5f),
    val drive: Param = Param(1f),
    val mode: ZdfLadderMode = ZdfLadderMode.LP4
) : SignalProcessor() {
    private val s = DoubleArray(4)
    private var yPrev = 0.0
    private var noise = 0x2545F491L
    /** Newton iterations taken on the last sample (diagnostic; at most [MAX_ITERATIONS]). */
    var lastIterations: Int = 0; private set
    private var inBuf = FloatArray(0); private var cBuf = FloatArray(0)
    private var rBuf = FloatArray(0); private var dBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (inBuf.size < numFrames) {
            inBuf = FloatArray(numFrames); cBuf = FloatArray(numFrames)
            rBuf = FloatArray(numFrames); dBuf = FloatArray(numFrames)
        }
        input.render(inBuf, numFrames, ctx)
        cutoff.render(cBuf, numFrames, ctx)
        resonance.render(rBuf, numFrames, ctx)
        drive.render(dBuf, numFrames, ctx)
        val sr = ctx.sampleRate.toDouble()
        for (i in 0 until numFrames) {
            val g = tan(PI * cBuf[i].toDouble().coerceIn(10.0, sr * 0.45) / sr)
            val G = g / (1.0 + g); val c = 1.0 - G
            val k = 4.0 * rBuf[i].toDouble().coerceIn(0.0, 1.2)
            val d = dBuf[i].toDouble().coerceAtLeast(1e-3)
            noise = noise * 6364136223846793005L + 1442695040888963407L
            val x = inBuf[i].toDouble() + 1e-6 * ((noise ushr 11).toDouble() / (1L shl 53) - 0.5)

            val G2 = G * G; val G4 = G2 * G2
            val S = G2 * G * c * s[0] + G2 * c * s[1] + G * c * s[2] + c * s[3]
            // Solve y = G⁴·tanh(d(x − k·y))/d + S; the left side minus the right is increasing in y.
            var y = yPrev
            var it = 0
            while (it < MAX_ITERATIONS) {
                val t = tanh(d * (x - k * y))
                val h = y - G4 * t / d - S
                val step = h / (1.0 + G4 * k * (1.0 - t * t))
                y -= step
                it++
                if (abs(step) < 1e-12 * (1.0 + abs(y))) break
            }
            lastIterations = it
            val u = tanh(d * (x - k * y)) / d
            var stageIn = u
            val ys = DoubleArray(4)
            for (j in 0 until 4) {
                val v = (stageIn - s[j]) * G
                val lp = v + s[j]
                s[j] = lp + v
                ys[j] = lp
                stageIn = lp
            }
            yPrev = ys[3]
            output[i] = when (mode) {
                ZdfLadderMode.LP4 -> ys[3]
                ZdfLadderMode.LP2 -> ys[1]
                ZdfLadderMode.HP4 -> u - 4.0 * ys[0] + 6.0 * ys[1] - 4.0 * ys[2] + ys[3]
            }.toFloat()
        }
    }

    companion object {
        /** The cap on Newton steps per sample; the loop is monotone, so two or three is typical. */
        const val MAX_ITERATIONS: Int = 8
    }
}

/**
 * A state-variable filter in zero-delay-feedback form whose damping saturates, solved per sample by
 * Newton's method: the nonlinear SVF of Zavalishin's *Art of VA Filter Design* (chapter 6), as in
 * evieve's nonlinear SVF.
 *
 * The damping path carries `2R·bp + 2(bp − tanh(drive·bp)/drive)` with `R = 1 − resonance`. At small
 * signal the second term vanishes, so the response is exactly [SVF]'s, `1/(s² + 2Rs + 1)` for the
 * lowpass. Past `resonance = 1` the small-signal damping goes negative and the filter self-oscillates
 * at [cutoff]; the saturating term adds damping as the band-pass grows, which holds the oscillation
 * at an amplitude of roughly `1/drive` instead of letting it run away. Below `resonance = 1` it adds
 * damping only to loud signals, which is the soft, rounded resonance of an analog SVF.
 *
 * ```kotlin
 * WhiteNoise() * 0.1f / ZdfSvf(SVFMode.BP, 800.p, resonance = 1.05.p)
 * ```
 */
class ZdfSvf(
    val mode: SVFMode = SVFMode.LP,
    val cutoff: Param = Param(1000f),
    val resonance: Param = Param(0.5f),
    val drive: Param = Param(1f)
) : SignalProcessor() {
    private var s1 = 0.0; private var s2 = 0.0
    private var noise = 0x5DEECE66DL
    private var inBuf = FloatArray(0); private var cBuf = FloatArray(0)
    private var rBuf = FloatArray(0); private var dBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (inBuf.size < numFrames) {
            inBuf = FloatArray(numFrames); cBuf = FloatArray(numFrames)
            rBuf = FloatArray(numFrames); dBuf = FloatArray(numFrames)
        }
        input.render(inBuf, numFrames, ctx)
        cutoff.render(cBuf, numFrames, ctx)
        resonance.render(rBuf, numFrames, ctx)
        drive.render(dBuf, numFrames, ctx)
        val sr = ctx.sampleRate.toDouble()
        for (i in 0 until numFrames) {
            val g = tan(PI * cBuf[i].toDouble().coerceIn(10.0, sr * 0.45) / sr)
            val R = 1.0 - rBuf[i].toDouble().coerceIn(0.0, 1.2)
            val d = dBuf[i].toDouble().coerceAtLeast(1e-3)
            noise = noise * 6364136223846793005L + 1442695040888963407L
            val x = inBuf[i].toDouble() + 1e-6 * ((noise ushr 11).toDouble() / (1L shl 53) - 0.5)

            // bp = g·(x − D(bp) − g·bp − s2) + s1, D(b) = 2R·b + 2(b − tanh(d·b)/d); increasing in bp for R ≥ −0.2.
            val rhs = g * (x - s2) + s1
            var b = rhs / (1.0 + g * g + 2.0 * R * g)
            for (it in 0 until 8) {
                val t = tanh(d * b)
                val h = b * (1.0 + g * g) + g * (2.0 * R * b + 2.0 * (b - t / d)) - rhs
                val step = h / (1.0 + g * g + g * (2.0 * R + 2.0 * t * t))
                b -= step
                if (abs(step) < 1e-12 * (1.0 + abs(b))) break
            }
            val damp = 2.0 * R * b + 2.0 * (b - tanh(d * b) / d)
            val lp = g * b + s2
            val hp = x - damp - lp
            s1 = 2.0 * b - s1
            s2 = 2.0 * lp - s2
            output[i] = when (mode) {
                SVFMode.LP -> lp
                SVFMode.BP -> b
                SVFMode.HP -> hp
                SVFMode.NOTCH -> hp + lp
            }.toFloat()
        }
    }
}

/** How a [Smoother] moves toward a new value. */
enum class SmootherMode {
    /** A one-pole lag: 63 % of the way in `time`, never quite arriving. */
    EXP,
    /** A straight ramp that reaches each new value in exactly `time`, then holds. */
    LINEAR
}

/**
 * Smooths a stepped control signal so a filter or gain fed from it does not click. A slider writing
 * a constant [Param]'s `base` jumps between audio blocks; a sequence of steps jumps on the sample;
 * either, fed to a cutoff, is a zipper. `stepped / Smoother(0.02.p)` takes the corner off every step.
 *
 * The first sample is passed through as the starting value, so a smoother never ramps up from zero
 * when a graph starts. [Param.smoothed] wraps a parameter in one, which is the usual way in:
 * `ZdfLadder(cutoff = cutoffParam.smoothed(0.03f))`.
 *
 * Two neighbours do similar jobs differently. [Slew] limits the *rate*: its times are per unit of
 * change, so a jump of 2000 Hz takes two thousand times as long as a jump of 1, rising and falling
 * can differ, and it starts from zero — right for portamento, wrong for a cutoff slider. `LINEAR`
 * here instead reaches every new value in the same `time`, whatever the size of the step. `EXP` is
 * the same lag as an [OnePole] lowpass, set by a time constant instead of a cutoff and primed with
 * the first sample instead of zero.
 */
class Smoother(
    val time: Param = Param(0.02f),
    val mode: SmootherMode = SmootherMode.EXP
) : SignalProcessor() {
    private var y = Double.NaN
    private var target = 0.0; private var step = 0.0
    private var inBuf = FloatArray(0); private var tBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (inBuf.size < numFrames) { inBuf = FloatArray(numFrames); tBuf = FloatArray(numFrames) }
        input.render(inBuf, numFrames, ctx)
        time.render(tBuf, numFrames, ctx)
        val sr = ctx.sampleRate.toDouble()
        for (i in 0 until numFrames) {
            val x = inBuf[i].toDouble()
            if (y.isNaN()) { y = x; target = x }
            val samples = (tBuf[i].toDouble() * sr).coerceAtLeast(1.0)
            when (mode) {
                SmootherMode.EXP -> y += (x - y) * (1.0 - exp(-1.0 / samples))
                SmootherMode.LINEAR -> {
                    if (x != target) { target = x; step = (target - y) / samples }
                    y = if (abs(target - y) <= abs(step)) target else y + step
                }
            }
            output[i] = y.toFloat()
        }
    }
}

/**
 * This parameter through a [Smoother] of [seconds], as a new [Param] to hand to a filter: set this
 * one's `base` from a slider and the filter sees the value glide rather than jump.
 */
fun Param.smoothed(seconds: Float = 0.02f, mode: SmootherMode = SmootherMode.EXP): Param {
    val source: Signal = this
    return Param(0f).also { it.mod = source / Smoother(Param(seconds), mode) }
}
