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
 * The processor's state is consumed; pass a fresh one.
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

/**
 * The measured magnitude response of [processor], in dB, at each frequency of [freqsHz]: its
 * [impulseResponse] evaluated by a direct DFT at exactly those frequencies. This is the plot that
 * sits beside a filter: `showSignals("Korg35" to frequencyResponseDb(Korg35(1000.p, 0.8.p), freqs))`.
 */
fun frequencyResponseDb(
    processor: SignalProcessor,
    freqsHz: List<Double>,
    sampleRate: Int = 48_000,
    frames: Int = 16_384,
    amplitude: Float = 1f
): List<Float> {
    val h = impulseResponse(processor, frames, sampleRate, amplitude)
    return freqsHz.map { f ->
        val w = 2.0 * PI * f / sampleRate
        var re = 0.0; var im = 0.0
        for (n in h.indices) { re += h[n] * cos(w * n); im -= h[n] * sin(w * n) }
        (20.0 * log10(sqrt(re * re + im * im).coerceAtLeast(1e-12))).toFloat()
    }
}

/** [count] frequencies spaced evenly in octaves from [lowHz] to [highHz], the x axis for [frequencyResponseDb]. */
fun logFrequencies(lowHz: Double = 20.0, highHz: Double = 20_000.0, count: Int = 200): List<Double> =
    List(count) { lowHz * (highHz / lowHz).pow(it / (count - 1.0)) }
