package afield.four.synth

import kotlin.math.abs
import kotlin.math.floor

/*
 ═══════════════════════════════════════════════════════════════════
  DoughDsp – native Kotlin ports of low-level DSP from dough
 ═══════════════════════════════════════════════════════════════════

 Ported from dough.c (https://codeberg.org/uzu/dough), which is
 licensed AGPL-3.0. These are line-by-line ports, so this file is a
 derivative work and carries dough's AGPL-3.0 terms.

 Within dough.c, the Dattorro reverb ([PlateReverb] here) is itself
 noted as adapted from el-visio's MIT-licensed implementation of the
 algorithm in Jon Dattorro, "Effect Design Part 1: Reverberator and
 Other Filters" (1997):
   https://ccrma.stanford.edu/~dattorro/EffectDesignPart1.pdf

 This file is deliberately *complementary* to the WASM binding in
 Dough.kt — that path runs the real dough engine and is unaffected by
 anything here. What follows is only the subset of dough's primitives
 that afield.four.synth did not already cover:

   [DoughMath]          fast float approximations (log2/exp2/pow/sin)
   [PlateReverb]        Dattorro plate reverb, mono in → stereo out
   [Phaser]             LFO-swept cascaded notches (no prior equivalent)
   [ChamberlinLowpass]  the "superdough" 2-pole lowpass + its res curve
   [CurvedADSR]         time-based ADSR with click-free retrigger
   [SoftDistort]        dough's expm1 k-curve soft clipper
   [ZawOsc], [PulzeOsc] naive (deliberately aliasing) saw and pulse

 Skipped as already covered by this package: polyBLEP saw/pulse/square/
 tri (Oscillators.kt, in double precision), white/pink/brown noise
 (same coefficients), the Audio-EQ-Cookbook biquad (Filters.kt),
 delay (Effects.kt), flanger/chorus (Effects.kt), bit crush + sample-rate
 reduction (Effects.kt BitCrusher), and lag (Envelopes.kt Slew). The
 voice allocator, event/mini-notation parser, orbits and scheduler are
 out of scope — use the WASM engine for those.

 The oscillators here live alongside the rest of the port rather than in
 Oscillators.kt so that the licence attribution above covers one file.
 */

// ═══════════════════════════════════════════════════════════════════
//  DoughMath – fast float approximations
// ═══════════════════════════════════════════════════════════════════

/**
 * dough's hand-rolled float math. Cheaper and less accurate than
 * [kotlin.math]; the inaccuracy is audible and is part of the character
 * of [ChamberlinLowpass] and [Phaser], which recompute coefficients with
 * these on every sample.
 *
 * Accuracy, as measured by `DoughDspSmokeTest`:
 * - [log2] 8e-5 absolute over 0.01…20 kHz
 * - [exp2] 4e-5 relative over -30…30
 * - [sinFast] 1e-6 absolute
 * - [parSin] **5.6e-2** absolute — it is a parabola, not a series, and that
 *   peak error is inherent to the fit. It is accurate enough for an LFO but
 *   coarse for filter coefficients: it is why [Phaser]'s notches bottom out
 *   around -18 dB instead of nulling. dough accepts that trade, and so does
 *   this port; reach for [sinFast] or [kotlin.math.sin] if you need a
 *   deeper null.
 */
object DoughMath {

    const val PI_F: Float = Math.PI.toFloat()

    private const val EXP_SHIFT = 23
    private const val MANTISSA_MASK = (1 shl EXP_SHIFT) - 1

    /**
     * Approximate base-2 logarithm via the exponent bits.
     *
     * Only valid for `x > 0` — the caller is responsible for that
     * (as it is in dough, where only [pow] calls this).
     */
    fun log2(x: Float): Float {
        val bits = x.toRawBits()
        // mantissa renormalised into [0.5, 1) — "bends" the linear significand
        val mantissa = Float.fromBits((bits and MANTISSA_MASK) or ((127 - 1) shl EXP_SHIFT))
        val y = bits.toFloat() * (1f / (1 shl EXP_SHIFT))
        return y - 124.22544637f - 1.498030302f * mantissa - 1.72587999f / (0.3520887068f + mantissa)
    }

    /**
     * Approximate `2^x`: exact power-of-two step from the exponent bits,
     * times a 4th-order polynomial over the fractional part.
     *
     * dough leaves the out-of-range exponent shift undefined; here it
     * saturates instead, so a runaway parameter can never inject NaN
     * into an audio buffer.
     */
    fun exp2(x: Float): Float {
        if (x < -126f) return 0f
        if (x > 127f) return Float.MAX_VALUE
        val xf = floor(x)
        val ystep = Float.fromBits((127 + xf.toInt()) shl EXP_SHIFT)
        val x1 = x - xf
        val xt = x1 - 0.5f
        val yTaylor = 1.4142135623730951f +
            xt * (0.9802581434685472f +
                xt * (0.3397315841830749f +
                    xt * (0.07849466324122069f +
                        xt * 0.013602088628663625f)))
        val m0 = 0.9999443187818418f
        val m1 = 1.0000312541780023f
        return ystep * yTaylor * (m0 + (m1 - m0) * x1)
    }

    /** Approximate `x^y`. Returns NaN for `x < 0`, matching dough. */
    fun pow(x: Float, y: Float): Float {
        if (x < 0f) return Float.NaN
        if (x == 0f) return 0f
        if (y == 0f) return if (x >= 0f) 1f else -1f
        return exp2(y * log2(x))
    }

    /** Approximate `e^x - 1`. */
    fun expm1(x: Float): Float = exp2(x * 1.4426950408889634f) - 1f

    /** Approximate `0.5^x`. */
    fun powHalf(x: Float): Float = exp2(-x)

    /** Approximate `10^x`. */
    fun pow10(x: Float): Float = exp2(x * 3.321928094887362f)

    /** Wraps [x] into `[-PI, PI]`. */
    fun modPi(x: Float): Float {
        var v = x + PI_F
        v *= 0.5f / PI_F
        v -= floor(v)
        v *= 2f * PI_F
        return v - PI_F
    }

    /** "Sine" made from a parabola — cheap, ~1e-3 absolute error. */
    fun parSin(x: Float): Float {
        val v = modPi(x)
        return 0.40528473456935094f * v * (PI_F - abs(v))
    }

    /** [parSin] shifted by a quarter turn. */
    fun parCos(x: Float): Float = parSin(x + 0.5f * PI_F)

    /** Sine via a Padé approximant — more accurate and costlier than [parSin]. */
    fun sinFast(x: Float): Float {
        val t = (4f * abs(x * (0.5f / PI_F) - floor(x * (0.5f / PI_F) + 0.75f) + 0.25f) - 1f) * (PI_F / 2f)
        val tt = t * t
        val num = t * (1f + tt * (-(2363f / 18183f) + tt * (12671f / 4363920f)))
        val denom = 1f + tt * ((445f / 12122f) + tt * ((601f / 872784f) + tt * (121f / 16662240f)))
        return num / denom
    }

    /** Flush values below [limit] to zero — keeps denormals out of feedback paths. */
    fun ftz(x: Float, limit: Float = 1e-15f): Float = if (x < limit && x > -limit) 0f else x

    /**
     * Interpolate `y0 → y1` over `x` in `[0, 1]` with a curve [exponent]:
     * `0` = linear, `> 0` = ease-in, `< 0` = ease-out.
     */
    fun lerpCurve(x: Float, y0: Float, y1: Float, exponent: Float): Float {
        if (x <= 0f) return y0
        if (x >= 1f) return y1
        val curved = when {
            exponent == 0f -> x
            exponent > 0f -> pow(x, exponent)
            else -> 1f - pow(1f - x, -exponent)
        }
        return y0 + (y1 - y0) * curved
    }
}

// ═══════════════════════════════════════════════════════════════════
//  PlateReverb – Dattorro plate reverb (mono in → stereo out)
// ═══════════════════════════════════════════════════════════════════

/**
 * Jon Dattorro's plate reverb: a pre-delay and input-diffusion chain
 * feeding a figure-of-eight tank of two cross-coupled halves, each with
 * its own all-pass diffusers and damping lowpass. The stereo image comes
 * from summing seven taps per side at points scattered through the tank,
 * so it is decorrelated rather than merely panned — this is what makes it
 * sound different from [FreeVerb] and [SpringReverb].
 *
 * Mono in, stereo out, so it is a [StereoNode] rather than a
 * [SignalProcessor] — chain the mono part first, then wrap:
 *
 * ```kotlin
 * val wet = PlateReverb(saw(110) / lpf(1200), decay = 0.85.p, mix = 0.4.p)
 * audio { wet }
 * ```
 *
 * Everything except [mix] is read once per block (dough drives these from
 * control-rate event parameters); [mix] is read per sample.
 *
 * The tap lengths are dough's, tuned for 44.1 kHz. By default they are
 * rescaled to the running sample rate so the time constants hold; pass
 * `scaleToSampleRate = false` for behaviour identical to dough.
 */
class PlateReverb(
    private val source: Signal,
    /** Pre-delay, 0…1 of ~109 ms. */
    val preDelay: Param = Param(0.1f),
    /** Input lowpass coefficient, 0…1 (higher = brighter). */
    val preFilter: Param = Param(0.85f),
    /** Gain of the first two input all-pass diffusers. */
    val inputDiffusion1: Param = Param(0.75f),
    /** Gain of the second two input all-pass diffusers. */
    val inputDiffusion2: Param = Param(0.625f),
    /** Tank all-pass diffusion. */
    val decayDiffusion: Param = Param(0.70f),
    /** Tank feedback, clamped to 0.99. */
    val decay: Param = Param(0.75f),
    /** Tank lowpass coefficient, 0…1 (higher = brighter tail). */
    val damping: Param = Param(0.95f),
    /** Dry/wet mix (0 = dry, 1 = wet). */
    val mix: Param = Param(0.3f),
    val scaleToSampleRate: Boolean = true
) : StereoNode(), LiveGraphInspectable {

    companion object {
        private const val TAP_MAIN = 0
        private const val TAP_OUT1 = 1
        private const val TAP_OUT2 = 2
        private const val TAP_OUT3 = 3
        private const val NUM_TAPS = 4

        /** Pre-delay line length in samples at the 44.1 kHz tuning rate. */
        private const val MAX_PREDELAY = 4800
        private const val TUNING_SAMPLE_RATE = 44100f
    }

    /**
     * Power-of-two delay line addressed by a free-running counter.
     *
     * Each tap stores `readOffset = size - delay`, so a read is
     * `buffer[(t + readOffset) and mask]`. [t] wraps at 65536 and every
     * size divides that, so the wrap is invisible to the masking.
     */
    private class VerbLine(delay: Int) {
        val buffer: FloatArray
        val mask: Int
        val readOffset = IntArray(NUM_TAPS)

        init {
            // smallest power of two strictly greater than delay
            var numBits = 0
            var x = delay
            while (x != 0) { numBits++; x = x shr 1 }
            val size = 1 shl numBits
            buffer = FloatArray(size)
            mask = size - 1
            setDelay(TAP_MAIN, delay)
        }

        fun setDelay(tap: Int, delay: Int) { readOffset[tap] = mask + 1 - delay }

        fun write(t: Int, value: Float) { buffer[t and mask] = value }

        fun read(tap: Int, t: Int): Float = buffer[(t + readOffset[tap]) and mask]

        fun process(t: Int, value: Float): Float {
            buffer[t and mask] = value
            return buffer[(t + readOffset[TAP_MAIN]) and mask]
        }

        fun allPass(t: Int, gain: Float, input: Float): Float {
            val delayed = read(TAP_MAIN, t)
            val v = input - delayed * gain
            write(t, v)
            return delayed + v * gain
        }
    }

    private lateinit var preDelayLine: VerbLine
    private lateinit var inDiffusion: Array<VerbLine>
    private lateinit var decayDiffusion1: Array<VerbLine>
    private lateinit var preDampingDelay: Array<VerbLine>
    private lateinit var decayDiffusion2: Array<VerbLine>
    private lateinit var postDampingDelay: Array<VerbLine>

    private var preFilterState = 0f
    private val dampingState = FloatArray(2)
    private var t = 0
    private var maxPreDelaySamples = MAX_PREDELAY
    private var initialized = false

    private var srcBuf = FloatArray(0)
    private var mixBuf = FloatArray(0)
    private var pdBuf = FloatArray(0); private var pfBuf = FloatArray(0)
    private var id1Buf = FloatArray(0); private var id2Buf = FloatArray(0)
    private var ddBuf = FloatArray(0); private var decBuf = FloatArray(0)
    private var dmpBuf = FloatArray(0)

    private fun init(sampleRate: Float) {
        val scale = if (scaleToSampleRate) sampleRate / TUNING_SAMPLE_RATE else 1f
        fun s(n: Int): Int = (n * scale).toInt().coerceAtLeast(1)

        maxPreDelaySamples = s(MAX_PREDELAY)
        preDelayLine = VerbLine(maxPreDelaySamples)

        inDiffusion = arrayOf(VerbLine(s(142)), VerbLine(s(107)), VerbLine(s(379)), VerbLine(s(277)))
        decayDiffusion1 = arrayOf(VerbLine(s(672)), VerbLine(s(908)))

        preDampingDelay = arrayOf(VerbLine(s(4453)), VerbLine(s(4217)))
        preDampingDelay[0].setDelay(TAP_OUT1, s(353))
        preDampingDelay[0].setDelay(TAP_OUT2, s(3627))
        preDampingDelay[0].setDelay(TAP_OUT3, s(1990))
        preDampingDelay[1].setDelay(TAP_OUT1, s(266))
        preDampingDelay[1].setDelay(TAP_OUT2, s(2974))
        preDampingDelay[1].setDelay(TAP_OUT3, s(2111))

        decayDiffusion2 = arrayOf(VerbLine(s(1800)), VerbLine(s(2656)))
        decayDiffusion2[0].setDelay(TAP_OUT1, s(187))
        decayDiffusion2[0].setDelay(TAP_OUT2, s(1228))
        decayDiffusion2[1].setDelay(TAP_OUT1, s(335))
        decayDiffusion2[1].setDelay(TAP_OUT2, s(1913))

        postDampingDelay = arrayOf(VerbLine(s(3720)), VerbLine(s(3163)))
        postDampingDelay[0].setDelay(TAP_OUT1, s(1066))
        postDampingDelay[0].setDelay(TAP_OUT2, s(2673))
        postDampingDelay[1].setDelay(TAP_OUT1, s(121))
        postDampingDelay[1].setDelay(TAP_OUT2, s(1996))

        initialized = true
    }

    // resolved parameters, refreshed once per block
    private var preFilterAmount = 0.85f
    private var inputDiffusion1Amount = 0.75f
    private var inputDiffusion2Amount = 0.625f
    private var decayDiffusion1Amount = 0.70f
    private var decayDiffusion2Amount = 0.5f
    private var decayAmount = 0.75f
    private var dampingAmount = 0.95f

    override fun generateStereo(outL: FloatArray, outR: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (!initialized) init(ctx.sampleRate)
        if (srcBuf.size < numFrames) {
            srcBuf = FloatArray(numFrames); mixBuf = FloatArray(numFrames)
            pdBuf = FloatArray(numFrames); pfBuf = FloatArray(numFrames)
            id1Buf = FloatArray(numFrames); id2Buf = FloatArray(numFrames)
            ddBuf = FloatArray(numFrames); decBuf = FloatArray(numFrames)
            dmpBuf = FloatArray(numFrames)
        }
        source.render(srcBuf, numFrames, ctx)
        mix.render(mixBuf, numFrames, ctx)
        preDelay.render(pdBuf, numFrames, ctx)
        preFilter.render(pfBuf, numFrames, ctx)
        inputDiffusion1.render(id1Buf, numFrames, ctx)
        inputDiffusion2.render(id2Buf, numFrames, ctx)
        decayDiffusion.render(ddBuf, numFrames, ctx)
        decay.render(decBuf, numFrames, ctx)
        damping.render(dmpBuf, numFrames, ctx)

        // control-rate parameters: sampled once per block
        preDelayLine.setDelay(
            TAP_MAIN,
            (pdBuf[0].coerceIn(0f, 1f) * maxPreDelaySamples).toInt().coerceIn(1, maxPreDelaySamples)
        )
        preFilterAmount = pfBuf[0].coerceIn(0f, 1f)
        inputDiffusion1Amount = id1Buf[0].coerceIn(0f, 0.99f)
        inputDiffusion2Amount = id2Buf[0].coerceIn(0f, 0.99f)
        decayDiffusion1Amount = ddBuf[0].coerceIn(0f, 0.99f)
        decayAmount = decBuf[0].coerceIn(0f, 0.99f)
        decayDiffusion2Amount = (decayAmount + 0.15f).coerceIn(0.25f, 0.50f)
        dampingAmount = dmpBuf[0].coerceIn(0f, 1f)

        for (i in 0 until numFrames) {
            process(srcBuf[i])
            val m = mixBuf[i].coerceIn(0f, 1f)
            val dry = srcBuf[i] * (1f - m)
            outL[i] = dry + left() * m
            outR[i] = dry + right() * m
        }
    }

    private fun process(input: Float) {
        // Slowly modulate the tank all-pass delays to break up ringing.
        // t wraps at 65536, so this walks down then back up.
        if ((t and 0x07ff) == 0) {
            if (t < (1 shl 15)) {
                decayDiffusion1[0].readOffset[TAP_MAIN]--
                decayDiffusion1[1].readOffset[TAP_MAIN]--
            } else {
                decayDiffusion1[0].readOffset[TAP_MAIN]++
                decayDiffusion1[1].readOffset[TAP_MAIN]++
            }
        }

        var x = preDelayLine.process(t, input)

        preFilterState += (x - preFilterState) * preFilterAmount
        x = preFilterState

        x = inDiffusion[0].allPass(t, inputDiffusion1Amount, x)
        x = inDiffusion[1].allPass(t, inputDiffusion1Amount, x)
        x = inDiffusion[2].allPass(t, inputDiffusion2Amount, x)
        x = inDiffusion[3].allPass(t, inputDiffusion2Amount, x)

        for (i in 0 until 2) {
            // cross-feed from the opposite half of the tank
            var x1 = x + postDampingDelay[1 - i].read(TAP_MAIN, t) * decayAmount
            x1 = decayDiffusion1[i].allPass(t, -decayDiffusion1Amount, x1)
            x1 = preDampingDelay[i].process(t, x1)
            dampingState[i] += (x1 - dampingState[i]) * dampingAmount
            x1 = dampingState[i] * decayAmount
            x1 = decayDiffusion2[i].allPass(t, decayDiffusion2Amount, x1)
            // ftz is ours, not dough's: the tank runs forever, so without it a
            // decaying tail leaves denormals circulating in the feedback path.
            postDampingDelay[i].write(t, DoughMath.ftz(x1))
        }

        t = (t + 1) and 0xFFFF
    }

    private fun left(): Float =
        preDampingDelay[1].read(TAP_OUT1, t) +
            preDampingDelay[1].read(TAP_OUT2, t) -
            decayDiffusion2[1].read(TAP_OUT2, t) +
            postDampingDelay[1].read(TAP_OUT2, t) -
            preDampingDelay[0].read(TAP_OUT3, t) -
            decayDiffusion2[0].read(TAP_OUT1, t) +
            postDampingDelay[0].read(TAP_OUT1, t)

    private fun right(): Float =
        preDampingDelay[0].read(TAP_OUT1, t) +
            preDampingDelay[0].read(TAP_OUT2, t) -
            decayDiffusion2[0].read(TAP_OUT2, t) +
            postDampingDelay[0].read(TAP_OUT2, t) -
            preDampingDelay[1].read(TAP_OUT3, t) -
            decayDiffusion2[1].read(TAP_OUT1, t) +
            postDampingDelay[1].read(TAP_OUT1, t)

    override fun liveGraphChildren(): Iterable<LiveGraphEdge> = listOf(
        LiveGraphEdge("source") { source },
        LiveGraphEdge("decay") { decay },
        LiveGraphEdge("damping") { damping },
        LiveGraphEdge("mix") { mix }
    )
}

// ═══════════════════════════════════════════════════════════════════
//  Phaser – LFO-swept cascaded notches
// ═══════════════════════════════════════════════════════════════════

/**
 * Two cascaded notch filters swept by a sine LFO, the second offset
 * 282 Hz above the first. Detuning is exponential (in cents), so the
 * sweep sounds even across the range, and [depth] widens the notches by
 * lowering their Q rather than by moving them further.
 *
 * ```kotlin
 * val swirl = supersaw / phaser(rate = 0.4, depth = 0.7, center = 900)
 * ```
 *
 * Coefficients are recomputed every sample with [DoughMath.parSin] /
 * [DoughMath.parCos], as in dough.
 */
class Phaser(
    /** LFO rate in Hz. */
    val rate: Param = Param(0.5f),
    /** 0…1; widens the notches by dropping Q from 2.0 to 0.1. */
    val depth: Param = Param(0.5f),
    /** Centre frequency of the first notch, in Hz. */
    val center: Param = Param(1000f),
    /** LFO sweep range in cents. */
    val sweep: Param = Param(600f),
    /** Dry/wet mix (0 = dry, 1 = wet). */
    val mix: Param = Param(1f)
) : SignalProcessor() {

    /** Direct-form-I notch, recooked per sample. */
    private class NotchStage {
        private var x1 = 0f; private var x2 = 0f
        private var y1 = 0f; private var y2 = 0f

        fun process(input: Float, freq: Float, q: Float, sampleRate: Float): Float {
            val omega = 2f * DoughMath.PI_F * freq / sampleRate
            val sinOmega = DoughMath.parSin(omega)
            val cosOmega = DoughMath.parCos(omega)
            val alpha = sinOmega / (2f * q)

            val a0 = 1f + alpha
            val b0 = 1f / a0
            val b1 = -2f * cosOmega / a0
            val b2 = b0
            val a1 = -2f * cosOmega / a0
            val a2 = (1f - alpha) / a0

            val output = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = input
            y2 = y1; y1 = DoughMath.ftz(output)
            return output
        }
    }

    private val stage1 = NotchStage()
    private val stage2 = NotchStage()
    private var lfoPhase = 0.0

    private var inBuf = FloatArray(0)
    private var rateBuf = FloatArray(0); private var depthBuf = FloatArray(0)
    private var centerBuf = FloatArray(0); private var sweepBuf = FloatArray(0)
    private var mixBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (inBuf.size < numFrames) {
            inBuf = FloatArray(numFrames); rateBuf = FloatArray(numFrames)
            depthBuf = FloatArray(numFrames); centerBuf = FloatArray(numFrames)
            sweepBuf = FloatArray(numFrames); mixBuf = FloatArray(numFrames)
        }
        input.render(inBuf, numFrames, ctx)
        rate.render(rateBuf, numFrames, ctx)
        depth.render(depthBuf, numFrames, ctx)
        center.render(centerBuf, numFrames, ctx)
        sweep.render(sweepBuf, numFrames, ctx)
        mix.render(mixBuf, numFrames, ctx)

        val sr = ctx.sampleRate
        val maxFreq = sr * 0.45f
        val invSr = ctx.inverseSampleRate.toDouble()

        for (i in 0 until numFrames) {
            val lfo = DoughMath.sinFast((lfoPhase * 2.0 * Math.PI).toFloat())
            lfoPhase += rateBuf[i].toDouble() * invSr
            lfoPhase -= floor(lfoPhase)

            val q = 2f - (depthBuf[i] * 2f).coerceIn(0f, 1.9f)
            val detune = DoughMath.exp2(lfo * sweepBuf[i] / 1200f)
            val c = centerBuf[i]
            val freq1 = (c * detune).coerceIn(20f, maxFreq)
            val freq2 = ((c + 282f) * detune).coerceIn(20f, maxFreq)

            var wet = stage1.process(inBuf[i], freq1, q, sr)
            wet = stage2.process(wet, freq2, q, sr)

            val m = mixBuf[i].coerceIn(0f, 1f)
            output[i] = inBuf[i] * (1f - m) + wet * m
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  ChamberlinLowpass – the "superdough" 2-pole lowpass
// ═══════════════════════════════════════════════════════════════════

/**
 * Two-pole Chamberlin-style state-variable lowpass, in the exact form
 * strudel/superdough uses. Distinct from [SVF] (trapezoidal, zero-delay
 * feedback) in both topology and, more audibly, in the resonance curve:
 * damping is `0.5^((resonance + 0.125) / 0.125)`, so the useful range is
 * squeezed into the top of the [resonance] knob and it snarls rather
 * than whistles as it approaches self-oscillation.
 *
 * Lowpass only — dough reaches for a biquad ([Biquad]) for highpass and
 * bandpass, and the highpass output is not available from this topology.
 *
 * The `c` coefficient is clamped to 1.14, which is what keeps the filter
 * stable at high cutoffs; consequently the cutoff flattens out somewhat
 * above roughly a fifth of the sample rate.
 */
class ChamberlinLowpass(
    /** Cutoff in Hz. */
    val cutoff: Param = Param(1000f),
    /** 0…1; higher is more resonant. */
    val resonance: Param = Param(0f)
) : SignalProcessor() {

    private var s0 = 0f
    private var s1 = 0f

    private var inBuf = FloatArray(0)
    private var cBuf = FloatArray(0)
    private var rBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (inBuf.size < numFrames) {
            inBuf = FloatArray(numFrames); cBuf = FloatArray(numFrames); rBuf = FloatArray(numFrames)
        }
        input.render(inBuf, numFrames, ctx)
        cutoff.render(cBuf, numFrames, ctx)
        resonance.render(rBuf, numFrames, ctx)

        val invSr = ctx.inverseSampleRate

        for (i in 0 until numFrames) {
            val c = (2f * DoughMath.parSin(cBuf[i] * invSr * DoughMath.PI_F)).coerceIn(0f, 1.14f)
            val r = DoughMath.powHalf((rBuf[i] + 0.125f) / 0.125f)
            val mrc = 1f - r * c

            var v0 = s0
            var v1 = s1
            v0 = mrc * v0 - c * v1 + c * inBuf[i]
            v1 = mrc * v1 + c * v0

            s0 = DoughMath.ftz(v0)
            s1 = DoughMath.ftz(v1)
            output[i] = v1
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  CurvedADSR – time-based ADSR with click-free retrigger
// ═══════════════════════════════════════════════════════════════════

/**
 * ADSR with separately shaped attack and decay curves.
 *
 * Two behaviours distinguish it from [ADSR]:
 * - **Release from anywhere.** Dropping the gate during the decay
 *   segment releases from the level reached at that instant, rather than
 *   waiting for decay to finish.
 * - **Click-free retrigger.** Raising the gate during release restarts
 *   the attack *from the current level* instead of from zero, so rapid
 *   retriggering does not produce a discontinuity.
 *
 * [attackCurve] and [decayCurve] are exponents: attack eases in as
 * `t^attackCurve`, decay and release ease out as `1 - (1-t)^decayCurve`.
 * dough's defaults are 2.0 for both.
 */
class CurvedADSR(
    val gate: Signal = Gate(),
    val attack: Param = Param(0.01f),
    val decay: Param = Param(0.1f),
    val sustain: Param = Param(0.7f),
    val release: Param = Param(0.3f),
    /** Attack shaping exponent; > 1 eases in. */
    val attackCurve: Float = 2f,
    /** Decay/release shaping exponent; > 1 eases out. */
    val decayCurve: Float = 2f
) : Signal() {

    private enum class Stage { OFF, ATTACK, DECAY, SUSTAIN, RELEASE }

    private var stage = Stage.OFF
    private var startTime = 0f
    private var startVal = 0f
    private var elapsed = 0f

    private var gateBuf = FloatArray(0)
    private var aBuf = FloatArray(0); private var dBuf = FloatArray(0)
    private var sBuf = FloatArray(0); private var rBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (gateBuf.size < numFrames) {
            gateBuf = FloatArray(numFrames)
            aBuf = FloatArray(numFrames); dBuf = FloatArray(numFrames)
            sBuf = FloatArray(numFrames); rBuf = FloatArray(numFrames)
        }
        gate.render(gateBuf, numFrames, ctx)
        attack.render(aBuf, numFrames, ctx)
        decay.render(dBuf, numFrames, ctx)
        sustain.render(sBuf, numFrames, ctx)
        release.render(rBuf, numFrames, ctx)

        val dt = ctx.inverseSampleRate

        for (i in 0 until numFrames) {
            // guarded against zero so time/segment can never be NaN
            val a = aBuf[i].coerceAtLeast(1e-6f)
            val d = dBuf[i].coerceAtLeast(1e-6f)
            val s = sBuf[i].coerceIn(0f, 1f)
            val r = rBuf[i].coerceAtLeast(1e-6f)
            output[i] = step(gateBuf[i], a, d, s, r)
            elapsed += dt
        }
    }

    private fun step(gate: Float, attack: Float, decay: Float, sustain: Float, release: Float): Float {
        when (stage) {
            Stage.OFF -> {
                if (gate > 0f) {
                    stage = Stage.ATTACK
                    startTime = elapsed
                    startVal = 0f
                }
                return 0f
            }

            Stage.ATTACK -> {
                val time = elapsed - startTime
                if (time > attack) {
                    stage = Stage.DECAY
                    startTime = elapsed
                    return 1f
                }
                return DoughMath.lerpCurve(time / attack, startVal, 1f, attackCurve)
            }

            Stage.DECAY -> {
                val time = elapsed - startTime
                val current = DoughMath.lerpCurve(time / decay, 1f, sustain, -decayCurve)
                if (gate <= 0f) {
                    stage = Stage.RELEASE
                    startTime = elapsed
                    startVal = current
                    return current
                }
                if (time > decay) {
                    stage = Stage.SUSTAIN
                    startTime = elapsed
                    return sustain
                }
                return current
            }

            Stage.SUSTAIN -> {
                if (gate <= 0f) {
                    stage = Stage.RELEASE
                    startTime = elapsed
                    startVal = sustain
                }
                return sustain
            }

            Stage.RELEASE -> {
                val time = elapsed - startTime
                if (time > release) {
                    stage = Stage.OFF
                    return 0f
                }
                val current = DoughMath.lerpCurve(time / release, startVal, 0f, -decayCurve)
                if (gate > 0f) {
                    stage = Stage.ATTACK
                    startTime = elapsed
                    startVal = current
                }
                return current
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  SoftDistort – expm1 k-curve soft clipper
// ═══════════════════════════════════════════════════════════════════

/**
 * The soft clipper from superdough: `y = (1+k)x / (1 + k|x|)` with
 * `k = e^amount - 1`, so [amount] maps to drive exponentially and the
 * output stays bounded by `(1+k)/k` however hard it is pushed.
 *
 * At `amount = 0` it is a straight wire. Unlike [Waveshaper.tanh] the
 * knee stays soft at high drive, which is why it stacks well under
 * [PlateReverb].
 */
class SoftDistort(
    /** Drive; 0 = clean. */
    val amount: Param = Param(1f),
    /** Output gain applied after shaping. */
    val postGain: Param = Param(1f)
) : SignalProcessor() {

    private var inBuf = FloatArray(0)
    private var aBuf = FloatArray(0)
    private var gBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (inBuf.size < numFrames) {
            inBuf = FloatArray(numFrames); aBuf = FloatArray(numFrames); gBuf = FloatArray(numFrames)
        }
        input.render(inBuf, numFrames, ctx)
        amount.render(aBuf, numFrames, ctx)
        postGain.render(gBuf, numFrames, ctx)

        for (i in 0 until numFrames) {
            val x = inBuf[i]
            val k = DoughMath.expm1(aBuf[i].coerceAtLeast(0f))
            output[i] = (((1f + k) * x) / (1f + k * abs(x))) * gBuf[i]
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  ZawOsc / PulzeOsc – naive, deliberately aliasing oscillators
// ═══════════════════════════════════════════════════════════════════

/**
 * Naive sawtooth — the raw phase ramp, with no band-limiting.
 *
 * dough calls this `zaw` to distinguish it from its band-limited `saw`.
 * The aliasing is the point: at high frequencies the foldover adds a
 * gritty inharmonic shimmer that [SawOsc] (PolyBLEP) deliberately
 * removes. Use [SawOsc] for anything that needs to stay clean.
 */
class ZawOsc(
    val freq: Param = Param(440f)
) : Signal() {
    private var phase: Double = 0.0
    private var freqBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (freqBuf.size < numFrames) freqBuf = FloatArray(numFrames)
        freq.render(freqBuf, numFrames, ctx)
        val invSr = ctx.inverseSampleRate.toDouble()
        for (i in 0 until numFrames) {
            output[i] = (phase * 2.0 - 1.0).toFloat()
            phase += freqBuf[i].toDouble() * invSr
            phase -= floor(phase)
        }
    }
}

/**
 * Naive variable-width pulse — a hard comparator on the phase ramp, with
 * no band-limiting. dough calls this `pulze`; see [ZawOsc] on why the
 * aliasing is wanted, and [PulseOsc] for the clean version.
 */
class PulzeOsc(
    val freq: Param = Param(440f),
    /** Duty cycle 0…1 (0.5 = square). */
    val duty: Param = Param(0.5f)
) : Signal() {
    private var phase: Double = 0.0
    private var freqBuf = FloatArray(0)
    private var dutyBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (freqBuf.size < numFrames) {
            freqBuf = FloatArray(numFrames); dutyBuf = FloatArray(numFrames)
        }
        freq.render(freqBuf, numFrames, ctx)
        duty.render(dutyBuf, numFrames, ctx)
        val invSr = ctx.inverseSampleRate.toDouble()
        for (i in 0 until numFrames) {
            output[i] = if (phase < dutyBuf[i]) 1f else -1f
            phase += freqBuf[i].toDouble() * invSr
            phase -= floor(phase)
        }
    }
}
