package afield.four.synth

import kotlin.math.*

// ═══════════════════════════════════════════════════════════════════
//  Delay – fractional delay line with interpolation
// ═══════════════════════════════════════════════════════════════════

class Delay(
    /** Delay time in seconds. */
    val delayTime: Param = Param(0.3f),
    /** Feedback amount (-1…+1). */
    val feedback: Param = Param(0.5f),
    /** Dry/wet mix (0 = all dry, 1 = all wet). */
    val mix: Param = Param(0.5f),
    val maxDelaySec: Float = 5f
) : SignalProcessor() {
    private var buffer = FloatArray(0)
    private var bufSize = 0
    private var writePos = 0
    private var inBuf = FloatArray(0)
    private var dBuf = FloatArray(0); private var fBuf = FloatArray(0); private var mBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        val needed = (ctx.sampleRate * maxDelaySec).toInt() + 4
        if (bufSize < needed) { buffer = FloatArray(needed); bufSize = needed; writePos = 0 }
        if (inBuf.size < numFrames) {
            inBuf = FloatArray(numFrames); dBuf = FloatArray(numFrames)
            fBuf = FloatArray(numFrames); mBuf = FloatArray(numFrames)
        }
        input.render(inBuf, numFrames, ctx)
        delayTime.render(dBuf, numFrames, ctx)
        feedback.render(fBuf, numFrames, ctx)
        mix.render(mBuf, numFrames, ctx)

        for (i in 0 until numFrames) {
            val delaySamps = (dBuf[i] * ctx.sampleRate).toDouble().coerceIn(0.0, (bufSize - 2).toDouble())
            val readPos = writePos.toDouble() - delaySamps
            val delayed = readCubic(readPos)
            val fb = fBuf[i].coerceIn(-0.999f, 0.999f)
            val wet = inBuf[i] + delayed * fb
            buffer[writePos] = wet
            writePos = (writePos + 1) % bufSize
            val m = mBuf[i].coerceIn(0f, 1f)
            output[i] = inBuf[i] * (1f - m) + delayed * m
        }
    }

    private fun readCubic(pos: Double): Float {
        val idx = pos.toInt()
        val frac = (pos - idx).toFloat()
        fun s(offset: Int): Float {
            val p = ((idx + offset) % bufSize + bufSize) % bufSize
            return buffer[p]
        }
        val s0 = s(-1); val s1 = s(0); val s2 = s(1); val s3 = s(2)
        val c0 = s1
        val c1 = 0.5f * (s2 - s0)
        val c2 = s0 - 2.5f * s1 + 2f * s2 - 0.5f * s3
        val c3 = 0.5f * (s3 - s0) + 1.5f * (s1 - s2)
        return ((c3 * frac + c2) * frac + c1) * frac + c0
    }
}

// ═══════════════════════════════════════════════════════════════════
//  FreeVerb – Schroeder reverb (Freeverb algorithm)
// ═══════════════════════════════════════════════════════════════════


class FreeVerb(
    val roomSize: Param = Param(0.8f),
    val damping: Param = Param(0.5f),
    val mix: Param = Param(0.3f)
) : SignalProcessor() {

    // Comb filter delay lengths (in samples at 44100 Hz, scaled at init)
    companion object {
        private val COMB_LENGTHS = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
        private val AP_LENGTHS = intArrayOf(556, 441, 341, 225)
        private const val SCALE_DAMPING = 0.4f
        private const val SCALE_ROOM = 0.28f
        private const val OFFSET_ROOM = 0.7f
    }

    private inner class CombState(length: Int) {
        val buffer = FloatArray(length)
        var pos = 0
        var filterStore = 0f
    }

    private inner class APState(length: Int) {
        val buffer = FloatArray(length)
        var pos = 0
    }

    private var combs: Array<CombState>? = null
    private var allpasses: Array<APState>? = null
    private var initialized = false

    private var inBuf = FloatArray(0)
    private var rBuf = FloatArray(0); private var dmpBuf = FloatArray(0); private var mBuf = FloatArray(0)

    private fun init(sampleRate: Float) {
        val scale = sampleRate / 44100f
        combs = Array(COMB_LENGTHS.size) { CombState((COMB_LENGTHS[it] * scale).toInt().coerceAtLeast(1)) }
        allpasses = Array(AP_LENGTHS.size) { APState((AP_LENGTHS[it] * scale).toInt().coerceAtLeast(1)) }
        initialized = true
    }

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (!initialized) init(ctx.sampleRate)
        if (inBuf.size < numFrames) {
            inBuf = FloatArray(numFrames); rBuf = FloatArray(numFrames)
            dmpBuf = FloatArray(numFrames); mBuf = FloatArray(numFrames)
        }
        input.render(inBuf, numFrames, ctx)
        roomSize.render(rBuf, numFrames, ctx)
        damping.render(dmpBuf, numFrames, ctx)
        mix.render(mBuf, numFrames, ctx)

        val cms = combs!!; val aps = allpasses!!

        for (i in 0 until numFrames) {
            val room = rBuf[i].coerceIn(0f, 1f) * SCALE_ROOM + OFFSET_ROOM
            val damp = dmpBuf[i].coerceIn(0f, 1f) * SCALE_DAMPING
            val damp2 = 1f - damp
            val inp = inBuf[i] * 0.015f  // input scaling

            // Parallel comb filters
            var wet = 0f
            for (c in cms) {
                val delayed = c.buffer[c.pos]
                c.filterStore = delayed * damp2 + c.filterStore * damp
                c.buffer[c.pos] = inp + c.filterStore * room
                c.pos = (c.pos + 1) % c.buffer.size
                wet += delayed
            }

            // Series allpass filters
            for (a in aps) {
                val delayed = a.buffer[a.pos]
                val out = -wet + delayed
                a.buffer[a.pos] = wet + delayed * 0.5f
                a.pos = (a.pos + 1) % a.buffer.size
                wet = out
            }

            val m = mBuf[i].coerceIn(0f, 1f)
            output[i] = inBuf[i] * (1f - m) + wet * m
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Chorus – LFO-modulated delay
// ═══════════════════════════════════════════════════════════════════


class Chorus(
    /** LFO rate in Hz. */
    val rate: Param = Param(1.5f),
    /** Modulation depth in seconds. */
    val depth: Param = Param(0.003f),
    /** Dry/wet mix (0…1). */
    val mix: Param = Param(0.5f),
    /** Number of chorus voices. */
    val voices: Int = 3
) : SignalProcessor() {
    private val maxDelay = 0.05f  // 50ms max
    private var buffer = FloatArray(0)
    private var bufSize = 0
    private var writePos = 0
    private val phases = DoubleArray(voices) { it.toDouble() / voices }

    private var inBuf = FloatArray(0)
    private var rateBuf = FloatArray(0); private var depthBuf = FloatArray(0); private var mixBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        val needed = (ctx.sampleRate * maxDelay).toInt() + 4
        if (bufSize < needed) { buffer = FloatArray(needed); bufSize = needed; writePos = 0 }
        if (inBuf.size < numFrames) {
            inBuf = FloatArray(numFrames); rateBuf = FloatArray(numFrames)
            depthBuf = FloatArray(numFrames); mixBuf = FloatArray(numFrames)
        }
        input.render(inBuf, numFrames, ctx)
        rate.render(rateBuf, numFrames, ctx)
        depth.render(depthBuf, numFrames, ctx)
        mix.render(mixBuf, numFrames, ctx)

        val invSr = ctx.inverseSampleRate.toDouble()

        for (i in 0 until numFrames) {
            buffer[writePos] = inBuf[i]
            var wet = 0f
            val freq = rateBuf[i].toDouble()
            val dp = depthBuf[i].toDouble().coerceIn(0.0, maxDelay.toDouble())

            for (v in 0 until voices) {
                val delay = dp * (1.0 + sin(2.0 * PI * phases[v])) * 0.5 * ctx.sampleRate
                val readPos = writePos.toDouble() - delay.coerceAtLeast(1.0)
                val idx = ((readPos.toInt() % bufSize) + bufSize) % bufSize
                wet += buffer[idx]
                phases[v] += freq * invSr
                if (phases[v] >= 1.0) phases[v] -= 1.0
            }
            wet /= voices

            val m = mixBuf[i].coerceIn(0f, 1f)
            output[i] = inBuf[i] * (1f - m) + wet * m
            writePos = (writePos + 1) % bufSize
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Waveshaper – arbitrary waveshaping distortion
// ═══════════════════════════════════════════════════════════════════


class Waveshaper(
    /** Transfer function: input sample → output sample. */
    val transferFn: (Float) -> Float,
    /** Pre-gain (drive). */
    val drive: Param = Param(1f)
) : SignalProcessor() {
    private var inBuf = FloatArray(0); private var dBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (inBuf.size < numFrames) { inBuf = FloatArray(numFrames); dBuf = FloatArray(numFrames) }
        input.render(inBuf, numFrames, ctx)
        drive.render(dBuf, numFrames, ctx)
        for (i in 0 until numFrames) {
            output[i] = transferFn(inBuf[i] * dBuf[i])
        }
    }

    companion object {
        /** Tanh soft clipping. */
        fun tanh(drive: Param = Param(2f)): Waveshaper =
            Waveshaper({ tanh(it.toDouble()).toFloat() }, drive)

        /** Hard clipping. */
        fun hardClip(drive: Param = Param(2f), threshold: Float = 1f): Waveshaper =
            Waveshaper({ it.coerceIn(-threshold, threshold) }, drive)

        /** Sine-fold waveshaping (for rich harmonics). */
        fun sineFold(drive: Param = Param(1f)): Waveshaper =
            Waveshaper({ sin(it.toDouble() * PI * 0.5).toFloat() }, drive)

        /** Chebyshev polynomial waveshaper (order N). */
        fun chebyshev(order: Int, drive: Param = Param(1f)): Waveshaper =
            Waveshaper({ chebyshevT(order, it.toDouble()).toFloat() }, drive)

        private fun chebyshevT(n: Int, x: Double): Double = when {
            n == 0 -> 1.0
            n == 1 -> x
            else -> 2.0 * x * chebyshevT(n - 1, x) - chebyshevT(n - 2, x)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Compressor – dynamic range compressor
// ═══════════════════════════════════════════════════════════════════


class Compressor(
    /** Threshold in dB. */
    val threshold: Param = Param(-12f),
    /** Compression ratio (e.g. 4 = 4:1). */
    val ratio: Param = Param(4f),
    /** Attack time in seconds. */
    val attack: Param = Param(0.005f),
    /** Release time in seconds. */
    val release: Param = Param(0.05f),
    /** Makeup gain in dB. */
    val makeupGain: Param = Param(0f),
    /** Knee width in dB (0 = hard knee). */
    val kneeWidth: Float = 6f
) : SignalProcessor() {
    private var envDb = -96f
    internal val vizOutputDbTrace = ScalarTrace(capacity = 4096, stride = 8)
    internal val vizReductionDbTrace = ScalarTrace(capacity = 4096, stride = 8)
    @Volatile internal var vizCurrentGainReductionDb: Float = 0f
    @Volatile internal var vizCurrentThresholdDb: Float = threshold.base
    @Volatile internal var vizCurrentRatio: Float = ratio.base
    private var inBuf = FloatArray(0)
    private var thBuf = FloatArray(0); private var raBuf = FloatArray(0)
    private var aBuf = FloatArray(0); private var rBuf = FloatArray(0); private var mBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (inBuf.size < numFrames) {
            inBuf = FloatArray(numFrames); thBuf = FloatArray(numFrames)
            raBuf = FloatArray(numFrames); aBuf = FloatArray(numFrames)
            rBuf = FloatArray(numFrames); mBuf = FloatArray(numFrames)
        }
        input.render(inBuf, numFrames, ctx)
        threshold.render(thBuf, numFrames, ctx)
        ratio.render(raBuf, numFrames, ctx)
        attack.render(aBuf, numFrames, ctx)
        release.render(rBuf, numFrames, ctx)
        makeupGain.render(mBuf, numFrames, ctx)
        val invSr = ctx.inverseSampleRate.toDouble()

        for (i in 0 until numFrames) {
            val x = inBuf[i]
            val xDb = if (abs(x) > 1e-6f) (20.0 * log10(abs(x).toDouble())).toFloat() else -96f
            val th = thBuf[i]; val r = raBuf[i].coerceAtLeast(1f)
            val at = aBuf[i].coerceAtLeast(0.0001f).toDouble()
            val rt = rBuf[i].coerceAtLeast(0.0001f).toDouble()

            // Gain computer with soft knee
            val halfKnee = kneeWidth / 2f
            val gainReduction = when {
                xDb < th - halfKnee -> 0f  // below knee
                xDb > th + halfKnee -> (th + (xDb - th) / r) - xDb  // above knee
                else -> {  // within knee
                    val x2 = xDb - th + halfKnee
                    (x2 * x2 / (4f * kneeWidth) * (1f / r - 1f))
                }
            }

            // Smooth envelope
            val targetDb = xDb + gainReduction
            val coeff = if (targetDb < envDb)
                exp(-invSr / at).toFloat() else exp(-invSr / rt).toFloat()
            envDb = coeff * envDb + (1f - coeff) * targetDb

            val gainDb = envDb - xDb + mBuf[i]
            output[i] = x * 10f.pow(gainDb / 20f)
            val reductionDb = (envDb - xDb).coerceAtMost(0f)
            vizCurrentGainReductionDb = reductionDb
            vizCurrentThresholdDb = th
            vizCurrentRatio = r
            vizReductionDbTrace.record(reductionDb)
            vizOutputDbTrace.record(envDb + mBuf[i])
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Limiter – brick-wall peak limiter
// ═══════════════════════════════════════════════════════════════════


class Limiter(
    /** Ceiling in dB (e.g. -0.3). */
    val ceiling: Param = Param(-0.3f),
    /** Release time in seconds. */
    val release: Param = Param(0.05f)
) : SignalProcessor() {
    private var gain = 1f
    internal val vizOutputDbTrace = ScalarTrace(capacity = 4096, stride = 8)
    internal val vizReductionDbTrace = ScalarTrace(capacity = 4096, stride = 8)
    @Volatile internal var vizCurrentGainReductionDb: Float = 0f
    @Volatile internal var vizCurrentCeilingDb: Float = ceiling.base
    private var inBuf = FloatArray(0); private var cBuf = FloatArray(0); private var rBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (inBuf.size < numFrames) {
            inBuf = FloatArray(numFrames); cBuf = FloatArray(numFrames); rBuf = FloatArray(numFrames)
        }
        input.render(inBuf, numFrames, ctx)
        ceiling.render(cBuf, numFrames, ctx)
        release.render(rBuf, numFrames, ctx)
        val invSr = ctx.inverseSampleRate.toDouble()

        for (i in 0 until numFrames) {
            val ceil = 10f.pow(cBuf[i] / 20f)
            val x = abs(inBuf[i])
            val targetGain = if (x > ceil) ceil / x else 1f

            val rt = rBuf[i].coerceAtLeast(0.0001f).toDouble()
            val coeff = if (targetGain < gain) 0f else exp(-invSr / rt).toFloat()
            gain = coeff * gain + (1f - coeff) * targetGain

            output[i] = inBuf[i] * gain
            val outputDb = if (kotlin.math.abs(output[i]) > 1e-6f) (20.0 * log10(kotlin.math.abs(output[i]).toDouble())).toFloat() else -96f
            val reductionDb = (20.0 * log10(gain.coerceAtLeast(1e-6f).toDouble())).toFloat().coerceAtMost(0f)
            vizCurrentGainReductionDb = reductionDb
            vizCurrentCeilingDb = cBuf[i]
            vizReductionDbTrace.record(reductionDb)
            vizOutputDbTrace.record(outputDb)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  BitCrusher – sample rate & bit depth reduction
// ═══════════════════════════════════════════════════════════════════


class BitCrusher(
    /** Effective bit depth (1…32). */
    val bits: Param = Param(8f),
    /** Downsample factor (1 = none, 4 = quarter rate). */
    val downsample: Param = Param(1f)
) : SignalProcessor() {
    private var held = 0f
    private var counter = 0f
    private var inBuf = FloatArray(0); private var bBuf = FloatArray(0); private var dBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (inBuf.size < numFrames) {
            inBuf = FloatArray(numFrames); bBuf = FloatArray(numFrames); dBuf = FloatArray(numFrames)
        }
        input.render(inBuf, numFrames, ctx)
        bits.render(bBuf, numFrames, ctx)
        downsample.render(dBuf, numFrames, ctx)

        for (i in 0 until numFrames) {
            counter += 1f
            val ds = dBuf[i].coerceAtLeast(1f)
            if (counter >= ds) {
                counter -= ds
                val b = bBuf[i].coerceIn(1f, 32f)
                val levels = 2f.pow(b)
                held = kotlin.math.round(inBuf[i] * levels) / levels
            }
            output[i] = held
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Flanger
// ═══════════════════════════════════════════════════════════════════


class Flanger(
    /** LFO rate in Hz. */
    val rate: Param = Param(0.3f),
    /** Max delay time in seconds. */
    val depth: Param = Param(0.003f),
    /** Feedback (-1…+1). */
    val feedback: Param = Param(0.7f),
    val mix: Param = Param(0.5f)
) : SignalProcessor() {
    private val maxDelay = 0.02f
    private var buffer = FloatArray(0)
    private var bufSize = 0
    private var writePos = 0
    private var lfoPhase = 0.0

    private var inBuf = FloatArray(0)
    private var rateBuf = FloatArray(0); private var depthBuf = FloatArray(0)
    private var fbBuf = FloatArray(0); private var mixBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        val needed = (ctx.sampleRate * maxDelay).toInt() + 4
        if (bufSize < needed) { buffer = FloatArray(needed); bufSize = needed; writePos = 0 }
        if (inBuf.size < numFrames) {
            inBuf = FloatArray(numFrames); rateBuf = FloatArray(numFrames)
            depthBuf = FloatArray(numFrames); fbBuf = FloatArray(numFrames)
            mixBuf = FloatArray(numFrames)
        }
        input.render(inBuf, numFrames, ctx)
        rate.render(rateBuf, numFrames, ctx)
        depth.render(depthBuf, numFrames, ctx)
        feedback.render(fbBuf, numFrames, ctx)
        mix.render(mixBuf, numFrames, ctx)
        val invSr = ctx.inverseSampleRate.toDouble()

        for (i in 0 until numFrames) {
            val dp = depthBuf[i].toDouble().coerceIn(0.0, maxDelay.toDouble())
            val delay = dp * (0.5 + 0.5 * sin(2.0 * PI * lfoPhase)) * ctx.sampleRate
            val readIdx = ((writePos - delay.toInt() + bufSize) % bufSize)
            val delayed = buffer[readIdx.toInt() % bufSize]
            val fb = fbBuf[i].coerceIn(-0.99f, 0.99f)
            buffer[writePos] = inBuf[i] + delayed * fb
            writePos = (writePos + 1) % bufSize
            lfoPhase += rateBuf[i].toDouble() * invSr
            if (lfoPhase >= 1.0) lfoPhase -= 1.0
            val m = mixBuf[i].coerceIn(0f, 1f)
            output[i] = inBuf[i] * (1f - m) + delayed * m
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  RingModulator
// ═══════════════════════════════════════════════════════════════════


class RingModulator(
    val carrierFreq: Param = Param(300f)
) : SignalProcessor() {
    private var phase = 0.0
    private var inBuf = FloatArray(0); private var fBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (inBuf.size < numFrames) { inBuf = FloatArray(numFrames); fBuf = FloatArray(numFrames) }
        input.render(inBuf, numFrames, ctx)
        carrierFreq.render(fBuf, numFrames, ctx)
        val invSr = ctx.inverseSampleRate.toDouble()
        for (i in 0 until numFrames) {
            output[i] = inBuf[i] * sin(2.0 * PI * phase).toFloat()
            phase += fBuf[i].toDouble() * invSr
            phase -= floor(phase)
        }
    }
}
