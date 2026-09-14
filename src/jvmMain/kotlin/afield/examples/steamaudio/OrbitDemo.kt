package afield.examples.steamaudio

import afield.four.audio.steamaudio.AirAbsorptionModel
import afield.four.audio.steamaudio.AudioBuffer
import afield.four.audio.steamaudio.AudioSettings
import afield.four.audio.steamaudio.BinauralEffect
import afield.four.audio.steamaudio.BinauralEffectParams
import afield.four.audio.steamaudio.DirectEffect
import afield.four.audio.steamaudio.DirectEffectFlags
import afield.four.audio.steamaudio.DirectEffectParams
import afield.four.audio.steamaudio.DistanceAttenuationModel
import afield.four.audio.steamaudio.Hrtf
import afield.four.audio.steamaudio.HrtfInterpolation
import afield.four.audio.steamaudio.SteamAudio
import afield.four.audio.steamaudio.SteamAudioContext
import afield.four.audio.steamaudio.Vector3
import java.lang.foreign.Arena
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Renders a buzzing source flying around the listener's head, as a stereo WAV file.
 *
 * Listen on **headphones** -- HRTF rendering assumes each ear hears only its own channel, and
 * over speakers the effect collapses.
 *
 * The render has three movements, so you can hear each cue in isolation:
 *
 *  1. **Horizontal orbit** -- two laps of the head at ear height. Front, right, behind, left.
 *     This is interaural time and level difference, the strongest localisation cue.
 *  2. **Vertical orbit** -- one lap through the median plane: in front, up over the top,
 *     behind, underneath. There is no time or level difference to work with here, so this is
 *     pure spectral shaping from the outer ear. It is a subtler effect, and it is the one that
 *     varies most between listeners.
 *  3. **Fly-past** -- a close approach from far left to far right, with distance attenuation
 *     and air absorption doing the work of proximity.
 */
private const val SAMPLING_RATE = 48_000

/** Small blocks keep the source direction updating smoothly as it moves. */
private const val FRAME_SIZE = 256

private const val HORIZONTAL_SECONDS = 8.0
private const val VERTICAL_SECONDS = 8.0
private const val FLYPAST_SECONDS = 6.0

/** Radius of the orbits, in metres. */
private const val ORBIT_RADIUS = 1.4f

fun main() {
    val settings = AudioSettings(samplingRate = SAMPLING_RATE, frameSize = FRAME_SIZE)
    val output = Path.of("build/demo/orbit.wav")
    Files.createDirectories(output.parent)

    println("Steam Audio ${SteamAudio.VERSION} - binaural orbit demo")
    println("  sampling rate ${settings.samplingRate} Hz, block ${settings.frameSize} samples")

    SteamAudioContext(
        logCallback = { level, message -> println("  [steamaudio/$level] ${message.trim()}") },
    ).use { context ->
        println("  loaded native library from ${afield.four.audio.steamaudio.SteamAudioNatives.loadedFrom}")

        // Arena for everything that lives for the whole render. Allocating here, once, is what
        // keeps the render loop below free of allocation.
        Arena.ofShared().use { arena ->
            Hrtf.default(context, settings, volume = 1.0f).use { hrtf ->
                DirectEffect(context, settings, channelCount = 1).use { direct ->
                    BinauralEffect(context, settings, hrtf).use { binaural ->

                        val dry = AudioBuffer(arena, channelCount = 1, sampleCount = FRAME_SIZE)
                        val shaped = AudioBuffer(arena, channelCount = 1, sampleCount = FRAME_SIZE)
                        val stereo = AudioBuffer(arena, channelCount = 2, sampleCount = FRAME_SIZE)

                        val directParams = DirectEffectParams(
                            arena,
                            flags = DirectEffectFlags.APPLY_DISTANCE_ATTENUATION or
                                DirectEffectFlags.APPLY_AIR_ABSORPTION,
                        )
                        val binauralParams = BinauralEffectParams(
                            arena,
                            hrtf = hrtf,
                            interpolation = HrtfInterpolation.BILINEAR,
                        )

                        val distanceModel = DistanceAttenuationModel.inverseDistance(minDistance = 0.4f)
                        val airModel = AirAbsorptionModel.DEFAULT
                        val airGains = FloatArray(SteamAudio.NUM_BANDS)

                        val renderer = Renderer(
                            context = context,
                            settings = settings,
                            direct = direct,
                            binaural = binaural,
                            dry = dry,
                            shaped = shaped,
                            stereo = stereo,
                            directParams = directParams,
                            binauralParams = binauralParams,
                            distanceModel = distanceModel,
                            airModel = airModel,
                            airGains = airGains,
                        )

                        println("  movement 1/3: horizontal orbit, two laps")
                        renderer.render(HORIZONTAL_SECONDS) { t ->
                            val angle = 2.0 * 2.0 * PI * (t / HORIZONTAL_SECONDS)
                            Vector3(
                                x = (ORBIT_RADIUS * sin(angle)).toFloat(),
                                y = 0f,
                                z = (-ORBIT_RADIUS * cos(angle)).toFloat(),
                            )
                        }

                        println("  movement 2/3: vertical orbit through the median plane")
                        renderer.render(VERTICAL_SECONDS) { t ->
                            val angle = 2.0 * PI * (t / VERTICAL_SECONDS)
                            Vector3(
                                x = 0f,
                                y = (ORBIT_RADIUS * sin(angle)).toFloat(),
                                z = (-ORBIT_RADIUS * cos(angle)).toFloat(),
                            )
                        }

                        println("  movement 3/3: close fly-past, left to right")
                        renderer.render(FLYPAST_SECONDS) { t ->
                            val progress = t / FLYPAST_SECONDS
                            Vector3(
                                x = (-9.0 + 18.0 * progress).toFloat(),
                                y = 0.1f,
                                // Curve in towards the head at the midpoint, so the fly-past
                                // passes close rather than at constant distance.
                                z = (-0.5 - 2.5 * kotlin.math.abs(0.5 - progress) * 2.0).toFloat(),
                            )
                        }

                        // The source has stopped; flush the HRTF filter tail so the render does
                        // not end on a hard cut.
                        println("  flushing tail (${binaural.tailSize} samples)")
                        binaural.drainTail(stereo) { block ->
                            renderer.appendInterleaved(block)
                        }

                        val samples = renderer.finish()
                        WavWriter.write(output, samples, channelCount = 2, samplingRate = SAMPLING_RATE)

                        val seconds = samples.size / 2.0 / SAMPLING_RATE
                        println()
                        println("  wrote $output")
                        println(
                            "  %.1f s stereo, %d blocks, peak %.3f"
                                .format(seconds, renderer.blockCount, renderer.peak),
                        )
                        println("  listen on headphones")
                    }
                }
            }
        }
    }
}

/**
 * Drives the per-block render loop.
 *
 * Everything this touches is pre-allocated; the loop body itself does no JVM allocation beyond
 * the [Vector3] the position function returns and the scratch arena inside
 * [SteamAudioContext.calculateRelativeDirection]. That is fine for an offline render, and the
 * comments mark where a real-time path would differ.
 */
private class Renderer(
    private val context: SteamAudioContext,
    private val settings: AudioSettings,
    private val direct: DirectEffect,
    private val binaural: BinauralEffect,
    private val dry: AudioBuffer,
    private val shaped: AudioBuffer,
    private val stereo: AudioBuffer,
    private val directParams: DirectEffectParams,
    private val binauralParams: BinauralEffectParams,
    private val distanceModel: DistanceAttenuationModel,
    private val airModel: AirAbsorptionModel,
    private val airGains: FloatArray,
) {
    private val collected = ArrayList<FloatArray>()
    private val interleaveScratch = FloatArray(stereo.totalSamples)
    private val signal = BuzzGenerator(settings.samplingRate)

    /** Listener sits at the origin, facing -z with +y up -- Steam Audio's convention. */
    private val listenerPosition = Vector3.ZERO

    var blockCount: Int = 0
        private set

    var peak: Float = 0f
        private set

    /** Renders [seconds] of audio, calling [positionAt] once per block for the source position. */
    inline fun render(seconds: Double, positionAt: (Double) -> Vector3) {
        val blocks = (seconds * settings.samplingRate / settings.frameSize).toInt()
        for (block in 0 until blocks) {
            val time = block.toDouble() * settings.frameSize / settings.samplingRate
            renderBlock(positionAt(time))
        }
    }

    fun renderBlock(sourcePosition: Vector3) {
        signal.fill(dry)

        // --- Direct path: how much, and with what spectrum, arrives from that distance. ---
        directParams.distanceAttenuation =
            distanceModel.calculate(context, sourcePosition, listenerPosition)
        airModel.calculate(context, sourcePosition, listenerPosition, airGains)
        directParams.setAirAbsorption(airGains)
        direct.apply(directParams, dry, shaped)

        // --- Spatialisation: which direction it arrives from, in the listener's frame. ---
        val direction = context.calculateRelativeDirection(
            sourcePosition = sourcePosition,
            listenerPosition = listenerPosition,
        )
        binauralParams.setDirection(direction.x, direction.y, direction.z)
        binaural.apply(binauralParams, shaped, stereo)

        appendInterleaved(stereo)
    }

    fun appendInterleaved(block: AudioBuffer) {
        block.interleaveTo(context, interleaveScratch)
        collected.add(interleaveScratch.copyOf())
        for (sample in interleaveScratch) {
            val magnitude = kotlin.math.abs(sample)
            if (magnitude > peak) peak = magnitude
        }
        blockCount++
    }

    fun finish(): FloatArray {
        val total = collected.sumOf { it.size }
        val out = FloatArray(total)
        var offset = 0
        for (chunk in collected) {
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        return out
    }
}

/**
 * A buzzing source with bright transients -- deliberately broadband, because that is what makes
 * a position audible.
 *
 * Measuring the HRTF directly (see [HrtfProbe]) shows why: a broadband impulse gets about
 * **18 dB** of interaural level difference at full lateral, but a 180 Hz tone with a few
 * harmonics gets only about **2 dB**. Low frequencies diffract around the head, so they carry
 * almost no level cue and the ear has to localise them from timing alone. Energy above roughly
 * 1.5 kHz is what makes the head-shadow effect strong, and sharp transients give the ear an
 * unambiguous arrival time.
 *
 * So the signal here is three things at once:
 *
 *  - a harmonic buzz extending well into the kilohertz range, for ILD;
 *  - vibrato, so the timbre stays alive rather than being adapted out;
 *  - a bright tick three times a second, for unambiguous ITD.
 */
private class BuzzGenerator(private val samplingRate: Int) {
    private var phase = 0.0
    private var vibratoPhase = 0.0
    private var sampleClock = 0L

    private val baseFrequency = 220.0

    /** Harmonics out to ~6 kHz with a gentle rolloff, so there is real high-frequency energy. */
    private val harmonicCount = 28
    private val harmonicGains = FloatArray(harmonicCount) { index ->
        // 1/n rolloff, tilted up slightly so the top octaves stay present.
        (1.0 / (index + 1).toDouble().pow(0.85)).toFloat()
    }
    private val harmonicNormaliser = harmonicGains.sum()

    /** A short bright tick, retriggered a few times a second. */
    private val tickPeriodSamples = samplingRate / 3
    private val tickLengthSamples = (samplingRate * 0.012).toInt()
    private var noiseState = 0x9E3779B9u

    fun fill(buffer: AudioBuffer) {
        val channel = buffer.channel(0)
        val step = 1.0 / samplingRate

        for (sample in 0 until buffer.sampleCount) {
            val vibrato = 1.0 + 0.04 * sin(vibratoPhase)
            val frequency = baseFrequency * vibrato

            // Harmonic buzz, band-limited so nothing aliases past Nyquist.
            var value = 0.0
            for (index in 0 until harmonicCount) {
                val harmonic = index + 1
                if (frequency * harmonic >= samplingRate / 2.0) break
                value += harmonicGains[index] * sin(phase * harmonic)
            }
            value = value / harmonicNormaliser * 0.55

            // Bright transient: a short burst of noise under an exponential decay.
            val intoTick = (sampleClock % tickPeriodSamples).toInt()
            if (intoTick < tickLengthSamples) {
                val decay = kotlin.math.exp(-6.0 * intoTick / tickLengthSamples)
                value += nextNoise() * 0.5 * decay
            }

            channel.setAtIndex(
                java.lang.foreign.ValueLayout.JAVA_FLOAT,
                sample.toLong(),
                value.toFloat().coerceIn(-1f, 1f),
            )

            phase += 2.0 * PI * frequency * step
            if (phase > 2.0 * PI) phase -= 2.0 * PI
            vibratoPhase += 2.0 * PI * 5.5 * step
            if (vibratoPhase > 2.0 * PI) vibratoPhase -= 2.0 * PI
            sampleClock++
        }
    }

    /** xorshift, so the tick is reproducible run to run. */
    private fun nextNoise(): Double {
        var x = noiseState
        x = x xor (x shl 13)
        x = x xor (x shr 17)
        x = x xor (x shl 5)
        noiseState = x
        return (x.toDouble() / UInt.MAX_VALUE.toDouble()) * 2.0 - 1.0
    }
}
