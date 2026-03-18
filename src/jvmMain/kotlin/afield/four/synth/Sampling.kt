package afield.four.synth

import java.util.concurrent.ThreadLocalRandom
import kotlin.math.PI
import kotlin.math.cos

// ═══════════════════════════════════════════════════════════════════
//  AudioSample – in-memory audio data
// ═══════════════════════════════════════════════════════════════════


class AudioSample(
    val data: FloatArray,
    /** Original sample rate of the data. */
    val sampleRate: Float = 44100f
) {
    val lengthSamples: Int get() = data.size
    val lengthSeconds: Float get() = data.size / sampleRate

    /** Read with Hermite interpolation at fractional position. */
    fun readInterp(position: Double): Float {
        val n = data.size
        if (n == 0) return 0f
        val idx = position.toInt()
        val frac = (position - idx).toFloat()
        val s0 = data[((idx - 1) % n + n) % n]
        val s1 = data[(idx % n + n) % n]
        val s2 = data[((idx + 1) % n + n) % n]
        val s3 = data[((idx + 2) % n + n) % n]
        return hermite4(frac, s0, s1, s2, s3)
    }

    companion object {
        /** Generate a sample from a function (e.g. for testing). */
        fun generate(lengthSec: Float, sampleRate: Float = 44100f, fn: (Float) -> Float): AudioSample {
            val n = (lengthSec * sampleRate).toInt()
            val data = FloatArray(n) { fn(it / sampleRate) }
            return AudioSample(data, sampleRate)
        }
    }

    override fun toString(): String = synthPrettyObject(
        this,
        "lengthSamples" to lengthSamples,
        "lengthSeconds" to lengthSeconds,
        "sampleRate" to sampleRate
    )
}

private fun hermite4(frac: Float, s0: Float, s1: Float, s2: Float, s3: Float): Float {
    val c0 = s1
    val c1 = 0.5f * (s2 - s0)
    val c2 = s0 - 2.5f * s1 + 2f * s2 - 0.5f * s3
    val c3 = 0.5f * (s3 - s0) + 1.5f * (s1 - s2)
    return ((c3 * frac + c2) * frac + c1) * frac + c0
}

// ═══════════════════════════════════════════════════════════════════
//  Sampler – sample playback with pitch control
// ═══════════════════════════════════════════════════════════════════


class Sampler(
    val sample: AudioSample,
    /** Playback rate (1 = original speed). */
    val rate: Param = Param(1f),
    /** Gate signal: rising edge triggers playback. */
    val gate: Signal? = null,
    val loop: Boolean = false,
    /** Loop start in samples (0 = beginning). */
    val loopStart: Int = 0,
    /** Loop end in samples (-1 = end of sample). */
    val loopEnd: Int = -1
) : Signal() {
    private var position: Double = 0.0
    private var playing = false
    private var prevGate = 0f
    private var rateBuf = FloatArray(0)
    private var gateBuf = FloatArray(0)

    /** Trigger playback from the start. */
    fun trigger() { position = 0.0; playing = true }

    /** Stop playback. */
    fun stop() { playing = false }

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (rateBuf.size < numFrames) {
            rateBuf = FloatArray(numFrames)
            gateBuf = FloatArray(numFrames)
        }
        rate.render(rateBuf, numFrames, ctx)
        gate?.render(gateBuf, numFrames, ctx) ?: Buf.clear(gateBuf, 0, numFrames)

        val rateScale = sample.sampleRate / ctx.sampleRate
        val end = if (loopEnd < 0) sample.lengthSamples else loopEnd.coerceAtMost(sample.lengthSamples)

        for (i in 0 until numFrames) {
            // Gate trigger detection
            val g = gateBuf[i]
            if (g > 0f && prevGate <= 0f) trigger()
            prevGate = g

            if (!playing) { output[i] = 0f; continue }

            output[i] = sample.readInterp(position)
            position += rateBuf[i].toDouble() * rateScale

            if (loop) {
                val loopLen = end - loopStart
                if (loopLen > 0) {
                    while (position >= end) position -= loopLen
                    while (position < loopStart) position += loopLen
                }
            } else if (position >= end || position < 0) {
                playing = false
                output[i] = 0f
            }
        }
    }

    override fun toString(): String = synthPrettyObject(
        this,
        "sample" to sample,
        "rate" to rate,
        "gate" to gate,
        "loop" to loop,
        "loopStart" to loopStart,
        "loopEnd" to loopEnd
    )
}

// ═══════════════════════════════════════════════════════════════════
//  GrainCloud – granular synthesis
// ═══════════════════════════════════════════════════════════════════


class GrainCloud(
    val source: AudioSample,
    /** Normalized position in source (0…1). */
    val position: Param = Param(0.5f),
    /** Grain duration in seconds. */
    val grainSize: Param = Param(0.05f),
    /** Grains spawned per second. */
    val density: Param = Param(20f),
    /** Playback rate per grain. */
    val pitch: Param = Param(1f),
    /** Random position scatter in seconds. */
    val spray: Param = Param(0f),
    /** Maximum simultaneous grains. */
    val maxGrains: Int = 128
) : Signal() {

    private class Grain {
        var active = false
        var sourcePos = 0.0    // current read position in source (samples)
        var rate = 1.0         // playback rate
        var age = 0            // samples elapsed
        var duration = 0       // grain duration in samples
        var envPhase = 0.0     // envelope phase (0…1)
    }

    private val grains = Array(maxGrains) { Grain() }
    private var spawnAccum = 0.0  // accumulator for grain spawning

    private var posBuf   = FloatArray(0)
    private var sizeBuf  = FloatArray(0)
    private var densBuf  = FloatArray(0)
    private var pitchBuf = FloatArray(0)
    private var sprayBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (posBuf.size < numFrames) {
            posBuf = FloatArray(numFrames); sizeBuf = FloatArray(numFrames)
            densBuf = FloatArray(numFrames); pitchBuf = FloatArray(numFrames)
            sprayBuf = FloatArray(numFrames)
        }
        position.render(posBuf, numFrames, ctx)
        grainSize.render(sizeBuf, numFrames, ctx)
        density.render(densBuf, numFrames, ctx)
        pitch.render(pitchBuf, numFrames, ctx)
        spray.render(sprayBuf, numFrames, ctx)
        Buf.clear(output, 0, numFrames)

        val rng = ThreadLocalRandom.current()
        val srcLen = source.lengthSamples
        val srcRate = source.sampleRate
        val invSr = ctx.inverseSampleRate.toDouble()

        for (i in 0 until numFrames) {
            val dens = densBuf[i].toDouble().coerceAtLeast(0.1)
            spawnAccum += dens * invSr

            // Spawn new grains
            while (spawnAccum >= 1.0) {
                spawnAccum -= 1.0
                val grain = grains.firstOrNull { !it.active } ?: continue

                val posNorm = posBuf[i].toDouble().coerceIn(0.0, 1.0)
                val sprayAmt = sprayBuf[i].toDouble() * srcRate
                val scatter = if (sprayAmt > 0) (rng.nextDouble() * 2.0 - 1.0) * sprayAmt else 0.0

                grain.active = true
                grain.sourcePos = posNorm * srcLen + scatter
                grain.rate = pitchBuf[i].toDouble()
                grain.duration = (sizeBuf[i] * ctx.sampleRate).toInt().coerceAtLeast(4)
                grain.age = 0
                grain.envPhase = 0.0
            }

            // Process all active grains
            var sum = 0f
            for (grain in grains) {
                if (!grain.active) continue
                // Hann window envelope
                val env = 0.5 - 0.5 * cos(2.0 * PI * grain.envPhase)
                val sample = source.readInterp(grain.sourcePos)
                sum += (sample * env).toFloat()

                grain.sourcePos += grain.rate
                grain.age++
                grain.envPhase = grain.age.toDouble() / grain.duration.toDouble()

                if (grain.age >= grain.duration) grain.active = false
            }
            output[i] = sum
        }
    }

    override fun toString(): String = synthPrettyObject(
        this,
        "source" to source,
        "position" to position,
        "grainSize" to grainSize,
        "density" to density,
        "pitch" to pitch,
        "spray" to spray,
        "maxGrains" to maxGrains
    )
}


class StereoGrainCloud(
    val source: AudioSample,
    val position: Param = Param(0.5f),
    val grainSize: Param = Param(0.05f),
    val density: Param = Param(20f),
    val pitch: Param = Param(1f),
    val spray: Param = Param(0f),
    /** Stereo spread: 0 = mono center, 1 = full stereo. */
    val spread: Param = Param(0.5f),
    val maxGrains: Int = 128
) : StereoNode() {
    private class Grain {
        var active = false
        var sourcePos = 0.0
        var rate = 1.0
        var age = 0
        var duration = 0
        var envPhase = 0.0
        var pan = 0.5f  // 0=left, 1=right
    }

    private val grains = Array(maxGrains) { Grain() }
    private var spawnAccum = 0.0

    private var posBuf = FloatArray(0); private var sizeBuf = FloatArray(0)
    private var densBuf = FloatArray(0); private var pitchBuf = FloatArray(0)
    private var sprayBuf = FloatArray(0); private var spreadBuf = FloatArray(0)

    override fun generateStereo(outL: FloatArray, outR: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (posBuf.size < numFrames) {
            posBuf = FloatArray(numFrames); sizeBuf = FloatArray(numFrames)
            densBuf = FloatArray(numFrames); pitchBuf = FloatArray(numFrames)
            sprayBuf = FloatArray(numFrames); spreadBuf = FloatArray(numFrames)
        }
        position.render(posBuf, numFrames, ctx)
        grainSize.render(sizeBuf, numFrames, ctx)
        density.render(densBuf, numFrames, ctx)
        pitch.render(pitchBuf, numFrames, ctx)
        spray.render(sprayBuf, numFrames, ctx)
        spread.render(spreadBuf, numFrames, ctx)
        Buf.clear(outL, 0, numFrames)
        Buf.clear(outR, 0, numFrames)

        val rng = ThreadLocalRandom.current()
        val srcLen = source.lengthSamples
        val srcRate = source.sampleRate
        val invSr = ctx.inverseSampleRate.toDouble()

        for (i in 0 until numFrames) {
            val dens = densBuf[i].toDouble().coerceAtLeast(0.1)
            spawnAccum += dens * invSr

            while (spawnAccum >= 1.0) {
                spawnAccum -= 1.0
                val grain = grains.firstOrNull { !it.active } ?: continue
                val posNorm = posBuf[i].toDouble().coerceIn(0.0, 1.0)
                val sprayAmt = sprayBuf[i].toDouble() * srcRate
                val scatter = if (sprayAmt > 0) (rng.nextDouble() * 2.0 - 1.0) * sprayAmt else 0.0
                val sp = spreadBuf[i].coerceIn(0f, 1f)

                grain.active = true
                grain.sourcePos = posNorm * srcLen + scatter
                grain.rate = pitchBuf[i].toDouble()
                grain.duration = (sizeBuf[i] * ctx.sampleRate).toInt().coerceAtLeast(4)
                grain.age = 0
                grain.envPhase = 0.0
                grain.pan = 0.5f + (rng.nextFloat() - 0.5f) * sp
            }

            for (grain in grains) {
                if (!grain.active) continue
                val env = (0.5 - 0.5 * cos(2.0 * PI * grain.envPhase)).toFloat()
                val sample = source.readInterp(grain.sourcePos) * env
                outL[i] += sample * (1f - grain.pan)
                outR[i] += sample * grain.pan

                grain.sourcePos += grain.rate
                grain.age++
                grain.envPhase = grain.age.toDouble() / grain.duration.toDouble()
                if (grain.age >= grain.duration) grain.active = false
            }
        }
    }
}
