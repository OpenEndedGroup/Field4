@file:afield.CompletionCategory("Synthesis")
package afield.four.synth

import java.util.concurrent.ThreadLocalRandom
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Playback of recorded audio, from reading a buffer straight through to scattering it into grains.
 * [AudioSample] is the buffer, [Sampler] plays one back, and the three grain clouds take one apart.
 *
 * Every read goes through a four-point Hermite fit that wraps at both ends of the buffer rather than
 * clamping, so a position past the last sample continues from the first. [Sampler] starts on the
 * rising edge of its `gate` and either runs to the end or loops between `loopStart` and `loopEnd`;
 * it scales its read rate by the ratio of the sample's own rate to the engine's, so a recording made
 * at 48 kHz plays at pitch on a 44.1 kHz output.
 *
 * The three clouds spawn grains the same way and differ in where the grains read from. [GrainCloud]
 * reads a fixed [AudioSample] and sums to mono. [StereoGrainCloud] reads the same kind of sample but
 * gives each grain a pan position, a left/right read-head separation and independent per-channel
 * scatter, so it is a [StereoNode] rather than a [Signal]. [LiveGrainCloud] has no sample at all and
 * grains a ring buffer that its own input is writing.
 *
 * Spawning is by accumulated density rather than by a clock: `density` is grains per second,
 * `densityJitter` varies the gap between onsets, and each cloud holds `maxGrains` voices, 128 by
 * default. A spawn that finds every voice busy is dropped rather than stealing one, so pushing
 * `density` past what `maxGrains` can carry thins the texture instead of cutting grains short.
 * `spray`, `pitchJitterCents`, `grainSizeJitter` and `gainJitter` each widen one property per grain
 * at spawn time, [GrainDirection] decides whether a grain reads forwards, backwards or picks one per
 * grain, and [GrainEnvelope] is the window over a grain's life, eight shapes, with `randomEnvelope`
 * drawing a fresh one each time.
 *
 * [LiveGrainCloud] also has to stay out of the recorder's way. A forward-reading grain is placed at
 * least its own travel distance back from the write head, as far as the ring allows, so it does not
 * overtake the recorder while it sounds. Its `record` control is a slewed gain on the write rather
 * than a switch, and at zero the write head stops advancing, so the grains go on working over
 * whatever the ring was holding.
 */

// ═══════════════════════════════════════════════════════════════════
//  AudioSample – in-memory audio data
// ═══════════════════════════════════════════════════════════════════

/**
 * A block of audio in memory: a `FloatArray` of samples, and the rate they were captured at.
 *
 * Reads go through `readInterp` rather than by index, so a sampler or a grain can sit at a
 * fractional position and move through the buffer at any rate. `generate` builds one from a function
 * of time in seconds, which is how the cookbook in `synth.kt` raises a test tone without a file.
 */
class AudioSample(
    val data: FloatArray,
    /** Original sample rate of the data. */
    val sampleRate: Float = 44100f
) {
    val lengthSamples: Int get() = data.size
    val lengthSeconds: Float get() = data.size / sampleRate

    /** Read with Hermite interpolation at fractional position. */
    fun readInterp(position: Double): Float {
        return readHermiteWrapped(data, position)
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

private fun wrapIndex(index: Int, size: Int): Int =
    ((index % size) + size) % size

private fun readHermiteWrapped(data: FloatArray, position: Double): Float {
    val n = data.size
    if (n == 0) return 0f
    val idx = floor(position).toInt()
    val frac = (position - idx).toFloat()
    val s0 = data[wrapIndex(idx - 1, n)]
    val s1 = data[wrapIndex(idx, n)]
    val s2 = data[wrapIndex(idx + 1, n)]
    val s3 = data[wrapIndex(idx + 2, n)]
    return hermite4(frac, s0, s1, s2, s3)
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

enum class GrainEnvelope {
    HANN,
    SINE,
    TRIANGLE,
    TUKEY,
    GAUSSIAN,
    PERCUSSIVE,
    REVERSE_PERCUSSIVE,
    BLACKMAN
}

enum class GrainDirection {
    FORWARD,
    REVERSE,
    BIDIRECTIONAL
}

private fun grainEnvelopeValue(envelope: GrainEnvelope, phase: Double): Float {
    val p = phase.coerceIn(0.0, 1.0)
    val v = when (envelope) {
        GrainEnvelope.HANN ->
            0.5 - 0.5 * cos(2.0 * PI * p)
        GrainEnvelope.SINE ->
            sin(PI * p)
        GrainEnvelope.TRIANGLE ->
            1.0 - abs(2.0 * p - 1.0)
        GrainEnvelope.TUKEY -> {
            val edge = 0.22
            when {
                p < edge -> 0.5 * (1.0 - cos(PI * p / edge))
                p > 1.0 - edge -> 0.5 * (1.0 - cos(PI * (1.0 - p) / edge))
                else -> 1.0
            }
        }
        GrainEnvelope.GAUSSIAN -> {
            val sigma = 0.18
            val floor = exp(-0.5 * (0.5 / sigma).pow(2.0))
            val raw = exp(-0.5 * ((p - 0.5) / sigma).pow(2.0))
            ((raw - floor) / (1.0 - floor)).coerceIn(0.0, 1.0)
        }
        GrainEnvelope.PERCUSSIVE -> {
            val attack = 0.08
            val tail = if (p < attack) p / attack else exp(-(p - attack) * 5.5)
            tail * releaseTaper(p)
        }
        GrainEnvelope.REVERSE_PERCUSSIVE -> {
            val rp = 1.0 - p
            val attack = 0.08
            val tail = if (rp < attack) rp / attack else exp(-(rp - attack) * 5.5)
            tail * releaseTaper(rp)
        }
        GrainEnvelope.BLACKMAN ->
            0.42 - 0.5 * cos(2.0 * PI * p) + 0.08 * cos(4.0 * PI * p)
    }
    return v.toFloat().coerceIn(0f, 1f)
}

private fun releaseTaper(phase: Double): Double =
    when {
        phase < 0.9 -> 1.0
        phase >= 1.0 -> 0.0
        else -> (1.0 - phase) / 0.1
    }

private fun randomBipolar(rng: ThreadLocalRandom): Double =
    rng.nextDouble() * 2.0 - 1.0

private fun chooseRandomEnvelope(rng: ThreadLocalRandom): GrainEnvelope =
    GrainEnvelope.entries[rng.nextInt(GrainEnvelope.entries.size)]

private fun nextSpawnThreshold(densityJitter: Double, rng: ThreadLocalRandom): Double {
    val jitter = densityJitter.coerceAtLeast(0.0)
    return (1.0 + randomBipolar(rng) * jitter).coerceIn(0.05, 4.0)
}

private fun jitteredDurationSamples(
    grainSizeSeconds: Float,
    grainSizeJitterSeconds: Float,
    sampleRate: Float,
    rng: ThreadLocalRandom
): Int {
    val jitter = grainSizeJitterSeconds.toDouble().coerceAtLeast(0.0)
    val seconds = (grainSizeSeconds.toDouble() + randomBipolar(rng) * jitter)
        .coerceAtLeast(4.0 / sampleRate)
    return (seconds * sampleRate).toInt().coerceAtLeast(4)
}

private fun directedRate(baseRate: Double, direction: GrainDirection, rng: ThreadLocalRandom): Double {
    val magnitude = abs(baseRate)
    return when (direction) {
        GrainDirection.FORWARD -> baseRate
        GrainDirection.REVERSE -> -magnitude
        GrainDirection.BIDIRECTIONAL -> magnitude * if (rng.nextBoolean()) 1.0 else -1.0
    }
}

private fun jitteredGain(gain: Float, gainJitter: Float, rng: ThreadLocalRandom): Float {
    val factor = 1.0 + randomBipolar(rng) * gainJitter.toDouble().coerceAtLeast(0.0)
    return (gain.toDouble() * factor).coerceAtLeast(0.0).toFloat()
}


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
    /** Per-grain random pitch range in cents (± this amount). */
    val pitchJitterCents: Param = Param(0f),
    /** Random position scatter in seconds. */
    val spray: Param = Param(0f),
    /** Envelope shape used by each grain. */
    val envelope: GrainEnvelope = GrainEnvelope.HANN,
    /** If true, choose a random [GrainEnvelope] for each spawned grain. */
    val randomEnvelope: Boolean = false,
    /** Maximum simultaneous grains. */
    val maxGrains: Int = 128,
    /** Per-grain random grain-duration range in seconds (± this amount). */
    val grainSizeJitter: Param = Param(0f),
    /** Per-grain random inter-onset variation. 0 = steady, 1 = up to ±100%. */
    val densityJitter: Param = Param(0f),
    /** Base gain applied when a grain is spawned. */
    val gain: Param = Param(1f),
    /** Per-grain random gain variation as a fraction of [gain]. */
    val gainJitter: Param = Param(0f),
    /** Direction mode used when each grain is spawned. */
    val direction: GrainDirection = GrainDirection.FORWARD
) : Signal() {

    private class Grain {
        var active = false
        var sourcePos = 0.0    // current read position in source (samples)
        var rate = 1.0         // playback rate
        var age = 0            // samples elapsed
        var duration = 0       // grain duration in samples
        var envPhase = 0.0     // envelope phase (0…1)
        var gain = 1f
        var envelope = GrainEnvelope.HANN
    }

    private val grains = Array(maxGrains) { Grain() }
    private var spawnAccum = 0.0  // accumulator for grain spawning
    private var spawnThreshold = 1.0

    private var posBuf   = FloatArray(0)
    private var sizeBuf  = FloatArray(0)
    private var densBuf  = FloatArray(0)
    private var pitchBuf = FloatArray(0)
    private var pitchJitterBuf = FloatArray(0)
    private var sprayBuf = FloatArray(0)
    private var sizeJitterBuf = FloatArray(0)
    private var densityJitterBuf = FloatArray(0)
    private var gainBuf = FloatArray(0)
    private var gainJitterBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (posBuf.size < numFrames) {
            posBuf = FloatArray(numFrames); sizeBuf = FloatArray(numFrames)
            densBuf = FloatArray(numFrames); pitchBuf = FloatArray(numFrames)
            pitchJitterBuf = FloatArray(numFrames); sprayBuf = FloatArray(numFrames)
            sizeJitterBuf = FloatArray(numFrames); densityJitterBuf = FloatArray(numFrames)
            gainBuf = FloatArray(numFrames); gainJitterBuf = FloatArray(numFrames)
        }
        position.render(posBuf, numFrames, ctx)
        grainSize.render(sizeBuf, numFrames, ctx)
        density.render(densBuf, numFrames, ctx)
        pitch.render(pitchBuf, numFrames, ctx)
        pitchJitterCents.render(pitchJitterBuf, numFrames, ctx)
        spray.render(sprayBuf, numFrames, ctx)
        grainSizeJitter.render(sizeJitterBuf, numFrames, ctx)
        densityJitter.render(densityJitterBuf, numFrames, ctx)
        gain.render(gainBuf, numFrames, ctx)
        gainJitter.render(gainJitterBuf, numFrames, ctx)
        Buf.clear(output, 0, numFrames)

        val rng = ThreadLocalRandom.current()
        val srcLen = source.lengthSamples
        val srcRate = source.sampleRate
        val invSr = ctx.inverseSampleRate.toDouble()

        for (i in 0 until numFrames) {
            val dens = densBuf[i].toDouble().coerceAtLeast(0.0)
            if (dens > 0.0) spawnAccum += dens * invSr

            // Spawn new grains
            while (spawnAccum >= spawnThreshold) {
                spawnAccum -= spawnThreshold
                spawnThreshold = nextSpawnThreshold(densityJitterBuf[i].toDouble(), rng)
                val grain = grains.firstOrNull { !it.active } ?: continue

                val posNorm = posBuf[i].toDouble().coerceIn(0.0, 1.0)
                val sprayAmt = sprayBuf[i].toDouble() * srcRate
                val scatter = if (sprayAmt > 0) (rng.nextDouble() * 2.0 - 1.0) * sprayAmt else 0.0

                grain.active = true
                grain.sourcePos = posNorm * srcLen + scatter
                val jitterCents = pitchJitterBuf[i].toDouble().coerceAtLeast(0.0)
                val pitchRatio = if (jitterCents > 0.0) {
                    2.0.pow(randomBipolar(rng) * jitterCents / 1200.0)
                } else {
                    1.0
                }
                grain.rate = directedRate(pitchBuf[i].toDouble() * pitchRatio, direction, rng)
                grain.duration = jitteredDurationSamples(sizeBuf[i], sizeJitterBuf[i], ctx.sampleRate, rng)
                grain.age = 0
                grain.envPhase = 0.0
                grain.gain = jitteredGain(gainBuf[i], gainJitterBuf[i], rng)
                grain.envelope = if (randomEnvelope) chooseRandomEnvelope(rng) else envelope
            }

            // Process all active grains
            var sum = 0f
            for (grain in grains) {
                if (!grain.active) continue
                val env = grainEnvelopeValue(grain.envelope, grain.envPhase)
                val sample = source.readInterp(grain.sourcePos)
                sum += sample * env * grain.gain

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
        "pitchJitterCents" to pitchJitterCents,
        "spray" to spray,
        "envelope" to envelope,
        "randomEnvelope" to randomEnvelope,
        "maxGrains" to maxGrains,
        "grainSizeJitter" to grainSizeJitter,
        "densityJitter" to densityJitter,
        "gain" to gain,
        "gainJitter" to gainJitter,
        "direction" to direction
    )
}


class StereoGrainCloud(
    val source: AudioSample,
    val position: Param = Param(0.5f),
    val grainSize: Param = Param(0.05f),
    val density: Param = Param(20f),
    val pitch: Param = Param(1f),
    val pitchJitterCents: Param = Param(0f),
    val spray: Param = Param(0f),
    /** Stereo spread: 0 = mono center, 1 = full stereo. */
    val spread: Param = Param(0.5f),
    /** Center pan for new grains (-1 = left, 0 = center, 1 = right). */
    val pan: Param = Param(0f),
    /** Per-grain left/right read-head separation in seconds. */
    val stereoWidth: Param = Param(0f),
    /** Per-grain random variation added to [stereoWidth], in seconds. */
    val stereoWidthRandom: Param = Param(0f),
    /** Independent per-channel source-position scatter in seconds. */
    val stereoScatter: Param = Param(0f),
    val envelope: GrainEnvelope = GrainEnvelope.HANN,
    val randomEnvelope: Boolean = false,
    val maxGrains: Int = 128,
    val grainSizeJitter: Param = Param(0f),
    val densityJitter: Param = Param(0f),
    val gain: Param = Param(1f),
    val gainJitter: Param = Param(0f),
    val direction: GrainDirection = GrainDirection.FORWARD
) : StereoNode() {
    private class Grain {
        var active = false
        var sourcePos = 0.0
        var rate = 1.0
        var age = 0
        var duration = 0
        var envPhase = 0.0
        var pan = 0f  // -1=left, 0=center, 1=right
        var widthSamples = 0.0
        var scatterL = 0.0
        var scatterR = 0.0
        var gain = 1f
        var envelope = GrainEnvelope.HANN
    }

    private val grains = Array(maxGrains) { Grain() }
    private var spawnAccum = 0.0
    private var spawnThreshold = 1.0

    private var posBuf = FloatArray(0); private var sizeBuf = FloatArray(0)
    private var densBuf = FloatArray(0); private var pitchBuf = FloatArray(0)
    private var pitchJitterBuf = FloatArray(0); private var sprayBuf = FloatArray(0)
    private var spreadBuf = FloatArray(0); private var panBuf = FloatArray(0)
    private var widthBuf = FloatArray(0); private var widthRandomBuf = FloatArray(0)
    private var stereoScatterBuf = FloatArray(0)
    private var sizeJitterBuf = FloatArray(0); private var densityJitterBuf = FloatArray(0)
    private var gainBuf = FloatArray(0); private var gainJitterBuf = FloatArray(0)

    override fun generateStereo(outL: FloatArray, outR: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (posBuf.size < numFrames) {
            posBuf = FloatArray(numFrames); sizeBuf = FloatArray(numFrames)
            densBuf = FloatArray(numFrames); pitchBuf = FloatArray(numFrames)
            pitchJitterBuf = FloatArray(numFrames); sprayBuf = FloatArray(numFrames)
            spreadBuf = FloatArray(numFrames); panBuf = FloatArray(numFrames)
            widthBuf = FloatArray(numFrames); widthRandomBuf = FloatArray(numFrames)
            stereoScatterBuf = FloatArray(numFrames)
            sizeJitterBuf = FloatArray(numFrames); densityJitterBuf = FloatArray(numFrames)
            gainBuf = FloatArray(numFrames); gainJitterBuf = FloatArray(numFrames)
        }
        position.render(posBuf, numFrames, ctx)
        grainSize.render(sizeBuf, numFrames, ctx)
        density.render(densBuf, numFrames, ctx)
        pitch.render(pitchBuf, numFrames, ctx)
        pitchJitterCents.render(pitchJitterBuf, numFrames, ctx)
        spray.render(sprayBuf, numFrames, ctx)
        spread.render(spreadBuf, numFrames, ctx)
        pan.render(panBuf, numFrames, ctx)
        stereoWidth.render(widthBuf, numFrames, ctx)
        stereoWidthRandom.render(widthRandomBuf, numFrames, ctx)
        stereoScatter.render(stereoScatterBuf, numFrames, ctx)
        grainSizeJitter.render(sizeJitterBuf, numFrames, ctx)
        densityJitter.render(densityJitterBuf, numFrames, ctx)
        gain.render(gainBuf, numFrames, ctx)
        gainJitter.render(gainJitterBuf, numFrames, ctx)
        Buf.clear(outL, 0, numFrames)
        Buf.clear(outR, 0, numFrames)

        val rng = ThreadLocalRandom.current()
        val srcLen = source.lengthSamples
        val srcRate = source.sampleRate
        val invSr = ctx.inverseSampleRate.toDouble()

        for (i in 0 until numFrames) {
            val dens = densBuf[i].toDouble().coerceAtLeast(0.0)
            if (dens > 0.0) spawnAccum += dens * invSr

            while (spawnAccum >= spawnThreshold) {
                spawnAccum -= spawnThreshold
                spawnThreshold = nextSpawnThreshold(densityJitterBuf[i].toDouble(), rng)
                val grain = grains.firstOrNull { !it.active } ?: continue
                val posNorm = posBuf[i].toDouble().coerceIn(0.0, 1.0)
                val sprayAmt = sprayBuf[i].toDouble() * srcRate
                val scatter = if (sprayAmt > 0) randomBipolar(rng) * sprayAmt else 0.0
                val sp = spreadBuf[i].coerceIn(0f, 1f)
                val widthBase = widthBuf[i].toDouble()
                val widthRnd = widthRandomBuf[i].toDouble().coerceAtLeast(0.0)
                val widthSeconds = widthBase + randomBipolar(rng) * widthRnd
                val channelScatter = stereoScatterBuf[i].toDouble().coerceAtLeast(0.0) * srcRate

                grain.active = true
                grain.sourcePos = posNorm * srcLen + scatter
                val jitterCents = pitchJitterBuf[i].toDouble().coerceAtLeast(0.0)
                val pitchRatio = if (jitterCents > 0.0) {
                    2.0.pow(randomBipolar(rng) * jitterCents / 1200.0)
                } else {
                    1.0
                }
                grain.rate = directedRate(pitchBuf[i].toDouble() * pitchRatio, direction, rng)
                grain.duration = jitteredDurationSamples(sizeBuf[i], sizeJitterBuf[i], ctx.sampleRate, rng)
                grain.age = 0
                grain.envPhase = 0.0
                grain.pan = (panBuf[i] + randomBipolar(rng).toFloat() * sp).coerceIn(-1f, 1f)
                grain.widthSamples = widthSeconds * srcRate * if (rng.nextBoolean()) 1.0 else -1.0
                grain.scatterL = if (channelScatter > 0) randomBipolar(rng) * channelScatter else 0.0
                grain.scatterR = if (channelScatter > 0) randomBipolar(rng) * channelScatter else 0.0
                grain.gain = jitteredGain(gainBuf[i], gainJitterBuf[i], rng)
                grain.envelope = if (randomEnvelope) chooseRandomEnvelope(rng) else envelope
            }

            for (grain in grains) {
                if (!grain.active) continue
                val env = grainEnvelopeValue(grain.envelope, grain.envPhase)
                val width = grain.widthSamples * 0.5
                val envGain = env * grain.gain
                val sampleL = source.readInterp(grain.sourcePos - width + grain.scatterL) * envGain
                val sampleR = source.readInterp(grain.sourcePos + width + grain.scatterR) * envGain
                val pan01 = 0.5f + grain.pan * 0.5f
                val gainL = 1f - pan01
                val gainR = pan01
                outL[i] += sampleL * gainL
                outR[i] += sampleR * gainR

                grain.sourcePos += grain.rate
                grain.age++
                grain.envPhase = grain.age.toDouble() / grain.duration.toDouble()
                if (grain.age >= grain.duration) grain.active = false
            }
        }
    }

    override fun toString(): String = synthPrettyObject(
        this,
        "source" to source,
        "position" to position,
        "grainSize" to grainSize,
        "density" to density,
        "pitch" to pitch,
        "pitchJitterCents" to pitchJitterCents,
        "spray" to spray,
        "spread" to spread,
        "pan" to pan,
        "stereoWidth" to stereoWidth,
        "stereoWidthRandom" to stereoWidthRandom,
        "stereoScatter" to stereoScatter,
        "envelope" to envelope,
        "randomEnvelope" to randomEnvelope,
        "maxGrains" to maxGrains,
        "grainSizeJitter" to grainSizeJitter,
        "densityJitter" to densityJitter,
        "gain" to gain,
        "gainJitter" to gainJitter,
        "direction" to direction
    )
}


class LiveGrainCloud(
    val input: Signal,
    /** Normalized delay through the live buffer: 0 = recent, 1 = oldest. */
    val position: Param = Param(0.5f),
    val grainSize: Param = Param(0.05f),
    val density: Param = Param(20f),
    val pitch: Param = Param(1f),
    val pitchJitterCents: Param = Param(0f),
    /** Random source-position scatter in seconds. */
    val spray: Param = Param(0f),
    /** Recording gate/gain for writing into the circular buffer. */
    val record: Param = Param(1f),
    val envelope: GrainEnvelope = GrainEnvelope.HANN,
    val randomEnvelope: Boolean = false,
    val maxGrains: Int = 128,
    val bufferSeconds: Float = 5f,
    val grainSizeJitter: Param = Param(0f),
    val densityJitter: Param = Param(0f),
    val gain: Param = Param(1f),
    val gainJitter: Param = Param(0f),
    val direction: GrainDirection = GrainDirection.FORWARD
) : Signal() {
    private class Grain {
        var active = false
        var sourcePos = 0.0
        var rate = 1.0
        var age = 0
        var duration = 0
        var envPhase = 0.0
        var gain = 1f
        var envelope = GrainEnvelope.HANN
    }

    private val grains = Array(maxGrains) { Grain() }
    private var ring = FloatArray(0)
    private var writePos = 0
    private var recordLevel = 0.0
    private var spawnAccum = 0.0
    private var spawnThreshold = 1.0

    private var inputBuf = FloatArray(0)
    private var posBuf = FloatArray(0)
    private var sizeBuf = FloatArray(0)
    private var densBuf = FloatArray(0)
    private var pitchBuf = FloatArray(0)
    private var pitchJitterBuf = FloatArray(0)
    private var sprayBuf = FloatArray(0)
    private var recordBuf = FloatArray(0)
    private var sizeJitterBuf = FloatArray(0)
    private var densityJitterBuf = FloatArray(0)
    private var gainBuf = FloatArray(0)
    private var gainJitterBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        ensureRing(ctx)
        ensureBuffers(numFrames)

        input.render(inputBuf, numFrames, ctx)
        position.render(posBuf, numFrames, ctx)
        grainSize.render(sizeBuf, numFrames, ctx)
        density.render(densBuf, numFrames, ctx)
        pitch.render(pitchBuf, numFrames, ctx)
        pitchJitterCents.render(pitchJitterBuf, numFrames, ctx)
        spray.render(sprayBuf, numFrames, ctx)
        record.render(recordBuf, numFrames, ctx)
        grainSizeJitter.render(sizeJitterBuf, numFrames, ctx)
        densityJitter.render(densityJitterBuf, numFrames, ctx)
        gain.render(gainBuf, numFrames, ctx)
        gainJitter.render(gainJitterBuf, numFrames, ctx)
        Buf.clear(output, 0, numFrames)

        val rng = ThreadLocalRandom.current()
        val invSr = ctx.inverseSampleRate.toDouble()
        val recordSlew = (1.0 / (ctx.sampleRate * 0.01)).coerceIn(0.0001, 1.0)

        for (i in 0 until numFrames) {
            writeInputSample(inputBuf[i], recordBuf[i], recordSlew)

            val dens = densBuf[i].toDouble().coerceAtLeast(0.0)
            if (dens > 0.0) spawnAccum += dens * invSr

            while (spawnAccum >= spawnThreshold) {
                spawnAccum -= spawnThreshold
                spawnThreshold = nextSpawnThreshold(densityJitterBuf[i].toDouble(), rng)
                spawnLiveGrain(i, ctx, rng)
            }

            var sum = 0f
            for (grain in grains) {
                if (!grain.active) continue
                val env = grainEnvelopeValue(grain.envelope, grain.envPhase)
                val sample = readHermiteWrapped(ring, grain.sourcePos)
                sum += sample * env * grain.gain

                grain.sourcePos += grain.rate
                grain.age++
                grain.envPhase = grain.age.toDouble() / grain.duration.toDouble()
                if (grain.age >= grain.duration) grain.active = false
            }
            output[i] = sum
        }
    }

    private fun ensureRing(ctx: SynthContext) {
        val needed = (bufferSeconds.coerceAtLeast(0.05f) * ctx.sampleRate).toInt()
            .coerceAtLeast(ctx.bufferSize + 8)
        if (ring.size == needed) return
        ring = FloatArray(needed)
        writePos = 0
        recordLevel = 0.0
        spawnAccum = 0.0
        spawnThreshold = 1.0
        grains.forEach { it.active = false }
    }

    private fun ensureBuffers(numFrames: Int) {
        if (inputBuf.size >= numFrames) return
        inputBuf = FloatArray(numFrames)
        posBuf = FloatArray(numFrames)
        sizeBuf = FloatArray(numFrames)
        densBuf = FloatArray(numFrames)
        pitchBuf = FloatArray(numFrames)
        pitchJitterBuf = FloatArray(numFrames)
        sprayBuf = FloatArray(numFrames)
        recordBuf = FloatArray(numFrames)
        sizeJitterBuf = FloatArray(numFrames)
        densityJitterBuf = FloatArray(numFrames)
        gainBuf = FloatArray(numFrames)
        gainJitterBuf = FloatArray(numFrames)
    }

    private fun writeInputSample(sample: Float, recordTarget: Float, slew: Double) {
        val target = recordTarget.toDouble().coerceIn(0.0, 1.0)
        recordLevel += (target - recordLevel) * slew
        if (recordLevel <= 0.00001 && target <= 0.00001) return

        ring[writePos] = sample * recordLevel.toFloat()
        writePos = (writePos + 1) % ring.size
    }

    private fun spawnLiveGrain(i: Int, ctx: SynthContext, rng: ThreadLocalRandom) {
        val grain = grains.firstOrNull { !it.active } ?: return
        val duration = jitteredDurationSamples(sizeBuf[i], sizeJitterBuf[i], ctx.sampleRate, rng)
        val jitterCents = pitchJitterBuf[i].toDouble().coerceAtLeast(0.0)
        val pitchRatio = if (jitterCents > 0.0) {
            2.0.pow(randomBipolar(rng) * jitterCents / 1200.0)
        } else {
            1.0
        }
        val rate = directedRate(pitchBuf[i].toDouble() * pitchRatio, direction, rng)
        val maxDelay = (ring.size - 4).toDouble().coerceAtLeast(4.0)
        val posNorm = posBuf[i].toDouble().coerceIn(0.0, 1.0)
        val spraySamples = sprayBuf[i].toDouble().coerceAtLeast(0.0) * ctx.sampleRate
        val scatter = if (spraySamples > 0) randomBipolar(rng) * spraySamples else 0.0
        val travelSafety = if (rate > 0.0) abs(rate) * duration + 4.0 else 4.0
        val minDelay = travelSafety.coerceAtMost(maxDelay)
        val delaySamples = (posNorm * maxDelay + scatter).coerceIn(minDelay, maxDelay)

        grain.active = true
        grain.sourcePos = writePos.toDouble() - delaySamples
        grain.rate = rate
        grain.duration = duration
        grain.age = 0
        grain.envPhase = 0.0
        grain.gain = jitteredGain(gainBuf[i], gainJitterBuf[i], rng)
        grain.envelope = if (randomEnvelope) chooseRandomEnvelope(rng) else envelope
    }

    override fun toString(): String = synthPrettyObject(
        this,
        "input" to input,
        "position" to position,
        "grainSize" to grainSize,
        "density" to density,
        "pitch" to pitch,
        "pitchJitterCents" to pitchJitterCents,
        "spray" to spray,
        "record" to record,
        "envelope" to envelope,
        "randomEnvelope" to randomEnvelope,
        "maxGrains" to maxGrains,
        "bufferSeconds" to bufferSeconds,
        "grainSizeJitter" to grainSizeJitter,
        "densityJitter" to densityJitter,
        "gain" to gain,
        "gainJitter" to gainJitter,
        "direction" to direction
    )
}
