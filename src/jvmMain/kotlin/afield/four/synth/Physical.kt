@file:afield.CompletionCategory("Synthesis")
package afield.four.synth

import kotlin.math.floor

enum class DelayInterpolation {
    LINEAR,
    CUBIC
}

internal class FractionalDelayLine(
    maxDelaySamples: Int,
    private val interpolation: DelayInterpolation = DelayInterpolation.CUBIC
) {
    private val buffer = FloatArray(maxDelaySamples.coerceAtLeast(4) + 4)
    private val size = buffer.size
    private var writePos = 0

    fun clear() {
        buffer.fill(0f)
        writePos = 0
    }

    fun write(sample: Float) {
        buffer[writePos] = sample
        writePos++
        if (writePos >= size) writePos = 0
    }

    fun read(delaySamples: Double): Float {
        val clamped = delaySamples.coerceIn(0.0, (size - 3).toDouble())
        val pos = writePos.toDouble() - clamped
        return when (interpolation) {
            DelayInterpolation.LINEAR -> readLinear(pos)
            DelayInterpolation.CUBIC -> readCubic(pos)
        }
    }

    private fun readLinear(pos: Double): Float {
        val base = floor(pos).toInt()
        val frac = (pos - base).toFloat()
        val s0 = sampleAt(base)
        val s1 = sampleAt(base + 1)
        return s0 + (s1 - s0) * frac
    }

    private fun readCubic(pos: Double): Float {
        val base = floor(pos).toInt()
        val frac = (pos - base).toFloat()
        val s0 = sampleAt(base - 1)
        val s1 = sampleAt(base)
        val s2 = sampleAt(base + 1)
        val s3 = sampleAt(base + 2)
        return hermite(frac, s0, s1, s2, s3)
    }

    private fun sampleAt(index: Int): Float {
        var wrapped = index % size
        if (wrapped < 0) wrapped += size
        return buffer[wrapped]
    }
}

class FractionalDelay(
    val delayTime: Param = Param(0.2f),
    val feedback: Param = Param(0.35f),
    val mix: Param = Param(0.5f),
    val maxDelaySec: Float = 5f,
    val interpolation: DelayInterpolation = DelayInterpolation.CUBIC
) : SignalProcessor() {
    private var line: FractionalDelayLine? = null
    private var lineCapacity = 0

    private var inBuf = FloatArray(0)
    private var delayBuf = FloatArray(0)
    private var feedbackBuf = FloatArray(0)
    private var mixBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        ensureLine(ctx.sampleRate)
        if (inBuf.size < numFrames) {
            inBuf = FloatArray(numFrames)
            delayBuf = FloatArray(numFrames)
            feedbackBuf = FloatArray(numFrames)
            mixBuf = FloatArray(numFrames)
        }

        input.render(inBuf, numFrames, ctx)
        delayTime.render(delayBuf, numFrames, ctx)
        feedback.render(feedbackBuf, numFrames, ctx)
        mix.render(mixBuf, numFrames, ctx)

        val delayLine = line!!
        val maxDelaySamples = lineCapacity - 3.0

        for (i in 0 until numFrames) {
            val delaySamples = (delayBuf[i].coerceAtLeast(0f) * ctx.sampleRate).toDouble().coerceIn(0.0, maxDelaySamples)
            val delayed = delayLine.read(delaySamples)
            val fb = feedbackBuf[i].coerceIn(-0.9999f, 0.9999f)
            val wet = delayed
            delayLine.write(inBuf[i] + wet * fb)

            val m = mixBuf[i].coerceIn(0f, 1f)
            output[i] = inBuf[i] * (1f - m) + wet * m
        }
    }

    private fun ensureLine(sampleRate: Float) {
        val required = (sampleRate * maxDelaySec).toInt().coerceAtLeast(8)
        if (required != lineCapacity || line == null) {
            lineCapacity = required
            line = FractionalDelayLine(required, interpolation)
        }
    }
}

/**
 * Karplus-Strong resonator driven by its input signal.
 *
 * Feed it a short excitation burst for plucks or hammer hits:
 *
 * `audio { (white * AR(gate = gate.also { it.open() }, release = 0.01.p)) / KarplusStrong(freq = 110.p) }`
 */
class KarplusStrong(
    val freq: Param = Param(110f),
    val decay: Param = Param(0.995f),
    val brightness: Param = Param(0.5f),
    val excitationMix: Param = Param(1f),
    val pickPosition: Param = Param(0.15f),
    val trigger: Signal = Const(0f),
    val clearOnTrigger: Boolean = true,
    val maxDelaySec: Float = 2f,
    val interpolation: DelayInterpolation = DelayInterpolation.CUBIC
) : SignalProcessor() {
    private var stringLine: FractionalDelayLine? = null
    private var excitationLine: FractionalDelayLine? = null
    private var lineCapacity = 0

    private var prevTrigger = 0f
    private var previousLoopSample = 0f

    private var inBuf = FloatArray(0)
    private var freqBuf = FloatArray(0)
    private var decayBuf = FloatArray(0)
    private var brightnessBuf = FloatArray(0)
    private var excitationMixBuf = FloatArray(0)
    private var pickPositionBuf = FloatArray(0)
    private var triggerBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        ensureLines(ctx.sampleRate)
        if (inBuf.size < numFrames) {
            inBuf = FloatArray(numFrames)
            freqBuf = FloatArray(numFrames)
            decayBuf = FloatArray(numFrames)
            brightnessBuf = FloatArray(numFrames)
            excitationMixBuf = FloatArray(numFrames)
            pickPositionBuf = FloatArray(numFrames)
            triggerBuf = FloatArray(numFrames)
        }

        input.render(inBuf, numFrames, ctx)
        freq.render(freqBuf, numFrames, ctx)
        decay.render(decayBuf, numFrames, ctx)
        brightness.render(brightnessBuf, numFrames, ctx)
        excitationMix.render(excitationMixBuf, numFrames, ctx)
        pickPosition.render(pickPositionBuf, numFrames, ctx)
        trigger.render(triggerBuf, numFrames, ctx)

        val stringDelay = stringLine!!
        val excitationDelay = excitationLine!!
        val maxDelaySamples = lineCapacity - 3.0

        for (i in 0 until numFrames) {
            val trig = triggerBuf[i]
            if (clearOnTrigger && trig > 0f && prevTrigger <= 0f) {
                stringDelay.clear()
                excitationDelay.clear()
                previousLoopSample = 0f
            }
            prevTrigger = trig

            val hz = freqBuf[i].coerceIn(8f, ctx.sampleRate * 0.45f)
            val periodSamples = ctx.sampleRate / hz

            val bright = brightnessBuf[i].coerceIn(0f, 1f)
            val filterDelay = 1.0 - bright.toDouble()
            val tunedDelay = (periodSamples - filterDelay.toFloat()).toDouble().coerceIn(1.0, maxDelaySamples)

            val delayed = stringDelay.read(tunedDelay)
            val filtered = delayed * bright + previousLoopSample * (1f - bright)
            previousLoopSample = delayed

            val pick = pickPositionBuf[i].coerceIn(0f, 1f)
            val pickDelaySamples = (periodSamples * pick).toDouble().coerceIn(0.0, maxDelaySamples)
            val excitation = inBuf[i]
            val combedExcitation = excitation - excitationDelay.read(pickDelaySamples)
            excitationDelay.write(excitation)

            val loop = filtered * decayBuf[i].coerceIn(-0.9999f, 0.9999f)
            val next = combedExcitation * excitationMixBuf[i] + loop
            stringDelay.write(next)

            output[i] = delayed
        }
    }

    private fun ensureLines(sampleRate: Float) {
        val required = (sampleRate * maxDelaySec).toInt().coerceAtLeast(16)
        if (required != lineCapacity || stringLine == null || excitationLine == null) {
            lineCapacity = required
            stringLine = FractionalDelayLine(required, interpolation)
            excitationLine = FractionalDelayLine(required, interpolation)
            previousLoopSample = 0f
            prevTrigger = 0f
        }
    }
}
