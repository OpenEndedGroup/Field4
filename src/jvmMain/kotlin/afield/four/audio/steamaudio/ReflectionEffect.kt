package afield.four.audio.steamaudio

import afield.four.audio.steamaudio.raw.IPLReflectionEffectParams
import afield.four.audio.steamaudio.raw.IPLReflectionEffectSettings
import afield.four.audio.steamaudio.raw.Phonon
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * Per-block parameters for a [ReflectionEffect].
 *
 * A [Simulator] fills most of this in for you -- see [SimulationOutputs.reflections] -- but
 * three fields are the **application's** responsibility and are not written by the simulator:
 * [channelCount], [irSize] and [type]. Those describe the shape of the effect you created, and
 * if they disagree with it the effect will misread the impulse response. [applyShapeOf] sets
 * them from an effect, which is the safe way to do it.
 *
 * ```
 * val params = ReflectionEffectParams(arena)
 * // once:
 * params.applyShapeOf(reflectionEffect)
 * // per simulation update:
 * source.getOutputs(SimulationFlags.REFLECTIONS, outputs)
 * params.copySimulatedFrom(outputs)   // preserves the three shape fields
 * ```
 */
public class ReflectionEffectParams private constructor(
    internal val segment: MemorySegment,
) {

    /** Allocates a parameter block from [arena]. */
    public constructor(arena: Arena) : this(IPLReflectionEffectParams.allocate(arena)) {
        type = ReflectionEffectType.CONVOLUTION
        IPLReflectionEffectParams.ir(segment, MemorySegment.NULL)
        IPLReflectionEffectParams.tanDevice(segment, MemorySegment.NULL)
        IPLReflectionEffectParams.tanSlot(segment, 0)
        delay = 0
        for (band in 0 until SteamAudio.NUM_BANDS) {
            setReverbTime(band, 1f)
            setEqualiser(band, 1f)
        }
    }

    /** Which reflection algorithm these parameters describe. Must match the effect. */
    public var type: ReflectionEffectType
        get() = ReflectionEffectType.from(IPLReflectionEffectParams.type(segment))
        set(value) = IPLReflectionEffectParams.type(segment, value.value)

    /** Number of channels in the impulse response. Must match the effect. */
    public var channelCount: Int
        get() = IPLReflectionEffectParams.numChannels(segment)
        set(value) = IPLReflectionEffectParams.numChannels(segment, value)

    /** Impulse response length in samples. Must match the effect. */
    public var irSize: Int
        get() = IPLReflectionEffectParams.irSize(segment)
        set(value) = IPLReflectionEffectParams.irSize(segment, value)

    /** Samples of delay before the reflections begin. */
    public var delay: Int
        get() = IPLReflectionEffectParams.delay(segment)
        set(value) = IPLReflectionEffectParams.delay(segment, value)

    /**
     * RT60 reverb decay time per band, in seconds -- how long it takes the reverb to fall by
     * 60 dB.
     *
     * Used by [ReflectionEffectType.PARAMETRIC] and the late half of
     * [ReflectionEffectType.HYBRID]. This is the number that makes a space feel like a cave:
     * a few seconds at low frequencies, shortening at high frequencies as air and surfaces
     * absorb.
     */
    public fun setReverbTime(band: Int, seconds: Float) {
        checkBand(band)
        IPLReflectionEffectParams.reverbTimes(segment)
            .setAtIndex(ValueLayout.JAVA_FLOAT, band.toLong(), seconds)
    }

    /** Reads back a per-band RT60. */
    public fun reverbTime(band: Int): Float {
        checkBand(band)
        return IPLReflectionEffectParams.reverbTimes(segment)
            .getAtIndex(ValueLayout.JAVA_FLOAT, band.toLong())
    }

    /** Per-band gain applied to the reflected signal. */
    public fun setEqualiser(band: Int, gain: Float) {
        checkBand(band)
        IPLReflectionEffectParams.eq(segment)
            .setAtIndex(ValueLayout.JAVA_FLOAT, band.toLong(), gain)
    }

    /** Reads back a per-band equaliser gain. */
    public fun equaliser(band: Int): Float {
        checkBand(band)
        return IPLReflectionEffectParams.eq(segment)
            .getAtIndex(ValueLayout.JAVA_FLOAT, band.toLong())
    }

    /** The TrueAudio Next device and slot, for [ReflectionEffectType.TRUE_AUDIO_NEXT]. */
    public fun setTrueAudioNext(device: TrueAudioNextDevice, slot: Int) {
        IPLReflectionEffectParams.tanDevice(segment, device.liveHandle)
        IPLReflectionEffectParams.tanSlot(segment, slot)
    }

    /**
     * Copies [effect]'s shape into the three fields the simulator does not fill in.
     *
     * Call this once after allocating, and again if you recreate the effect.
     */
    public fun applyShapeOf(effect: ReflectionEffect) {
        type = effect.type
        channelCount = effect.channelCount
        irSize = effect.irSize
    }

    /**
     * Takes the simulated reflection parameters from [outputs], preserving the three shape
     * fields that describe the effect rather than the simulation.
     *
     * This is the correct way to move a simulator's result into an effect: a blind struct copy
     * would overwrite [channelCount], [irSize] and [type] with whatever the simulator happened
     * to leave there.
     */
    public fun copySimulatedFrom(outputs: SimulationOutputs) {
        val savedType = type
        val savedChannels = channelCount
        val savedIrSize = irSize
        segment.copyFrom(outputs.reflectionsSegment)
        type = savedType
        channelCount = savedChannels
        irSize = savedIrSize
    }

    private fun checkBand(band: Int) {
        if (band !in 0 until SteamAudio.NUM_BANDS) {
            throw IndexOutOfBoundsException("band $band outside 0..<${SteamAudio.NUM_BANDS}")
        }
    }
}

/**
 * Renders the reflected sound of a source: the energy that reaches the listener after bouncing
 * off geometry.
 *
 * This is what makes a space audible as a space. In a cave, most of what you hear from a
 * distant source is reflections, not the direct path.
 *
 * ### Input and output
 *
 * Input is the source's **mono** dry signal. Output is **Ambisonic**, with
 * [channelCount] channels -- the reflected field arrives from all around, so it cannot be
 * represented as a single direction. Decode it to stereo with an
 * [AmbisonicsDecodeEffect] in binaural mode.
 *
 * ### Choosing a type
 *
 * - [ReflectionEffectType.CONVOLUTION] convolves the full simulated impulse response.
 *   **The default, and measurement says it should be.** See below.
 * - [ReflectionEffectType.PARAMETRIC] drives a feedback delay network from the simulated RT60s.
 *   Around five times cheaper (67 us against 281 us median per block, measured), and the right
 *   choice for distant or background sources. It loses the early reflection detail -- 389
 *   discrete early peaks against 515 -- and because the network is driven by reverb times that
 *   jump at the simulation rate rather than cross-fading, it can audibly glitch on a
 *   foreground source.
 * - [ReflectionEffectType.HYBRID] convolves the early reflections and goes parametric for the
 *   late tail. The premise is that this saves the cost of convolving a long tail. **Measured,
 *   it does not**: it is more expensive than plain convolution at every duration tested,
 *   because it pays for the convolution *and* the feedback network.
 *
 *   | duration | CONVOLUTION p95 | HYBRID p95 |
 *   |---|---|---|
 *   | 1 s | 221 us | 284 us |
 *   | 3 s | 555 us | 622 us |
 *   | 6 s | 1066 us | 1138 us |
 *   | 10 s | 1764 us | 1823 us |
 *
 *   One source, order 1, 512-sample blocks at 48 kHz on a 10-core machine. There is no
 *   crossover in that range, and the early-reflection structure is indistinguishable from
 *   convolution's. Prefer [ReflectionEffectType.CONVOLUTION] unless you measure otherwise on
 *   your own content.
 * - [ReflectionEffectType.TRUE_AUDIO_NEXT] is convolution on AMD GPU hardware.
 *
 * ### Sharing work across sources with a mixer
 *
 * With many sources, one reflection effect per source means one convolution per source. A
 * [ReflectionMixer] lets each effect accumulate into a shared Ambisonic bus, so the expensive
 * decode happens once rather than per source. Pass the same mixer to every [apply], then call
 * [ReflectionMixer.apply] once per block to read the mix out.
 */
public class ReflectionEffect private constructor(
    handle: MemorySegment,
    /** The algorithm this effect was created with. */
    public val type: ReflectionEffectType,
    /** Number of Ambisonic output channels. */
    public val channelCount: Int,
    /** Impulse response length in samples. */
    public val irSize: Int,
) : AudioEffect(handle) {

    override fun releaseAction(): (MemorySegment) -> Unit = Phonon::iplReflectionEffectRelease

    override val typeName: String get() = "ReflectionEffect"

    /**
     * Renders one block of reflections.
     *
     * @param input the source's mono dry signal.
     * @param output an Ambisonic buffer with [channelCount] channels. Ignored when [mixer] is
     *   given -- the effect accumulates into the mixer instead.
     * @param mixer when non-null, accumulate into this shared bus rather than writing
     *   [output]. Read the result with [ReflectionMixer.apply].
     */
    public fun apply(
        params: ReflectionEffectParams,
        input: AudioBuffer,
        output: AudioBuffer,
        mixer: ReflectionMixer? = null,
    ): AudioEffectState {
        require(input.channelCount == 1) {
            "a reflection effect takes mono input, got ${input.channelCount} channels"
        }
        if (mixer == null) {
            require(output.channelCount == channelCount) {
                "output needs $channelCount Ambisonic channels, got ${output.channelCount}"
            }
        }
        return AudioEffectState.from(
            Phonon.iplReflectionEffectApply(
                liveHandle,
                params.segment,
                input.struct,
                output.struct,
                mixer.handleOrNull(),
            ),
        )
    }

    override fun reset() {
        Phonon.iplReflectionEffectReset(liveHandle)
    }

    /**
     * Samples of tail remaining.
     *
     * In a long-decay space this is large -- potentially several seconds' worth -- which is
     * exactly why tails must be drained rather than cut. See [AudioEffect].
     */
    override val tailSize: Int
        get() = Phonon.iplReflectionEffectGetTailSize(liveHandle)

    override fun getTail(output: AudioBuffer): AudioEffectState = getTail(output, null)

    /** Renders one block of tail, optionally accumulating into [mixer]. */
    public fun getTail(output: AudioBuffer, mixer: ReflectionMixer? = null): AudioEffectState {
        if (mixer == null) {
            require(output.channelCount == channelCount) {
                "output needs $channelCount Ambisonic channels, got ${output.channelCount}"
            }
        }
        return AudioEffectState.from(
            Phonon.iplReflectionEffectGetTail(liveHandle, output.struct, mixer.handleOrNull()),
        )
    }

    /** Increments the reference count and returns an independently closeable wrapper. */
    public fun retain(): ReflectionEffect =
        ReflectionEffect(Phonon.iplReflectionEffectRetain(liveHandle), type, channelCount, irSize)

    public companion object {
        /**
         * Creates a reflection effect.
         *
         * @param type the reflection algorithm. See the class documentation for how to choose.
         * @param duration the impulse response length in seconds. This must be at least the
         *   `maxDuration` of the [Simulator] feeding it. Longer costs more, linearly, for
         *   convolution.
         * @param ambisonicOrder Ambisonic order of the reflected field. Order 1 (4 channels)
         *   is enough for a diffuse tail; order 2 gives a sharper spatial impression of early
         *   reflections. Must match the simulator's `maxOrder`.
         */
        public operator fun invoke(
            context: SteamAudioContext,
            audioSettings: AudioSettings,
            type: ReflectionEffectType = ReflectionEffectType.CONVOLUTION,
            duration: Float = 2.0f,
            ambisonicOrder: Int = 1,
        ): ReflectionEffect {
            require(duration > 0f) { "duration must be positive, got $duration" }
            require(ambisonicOrder >= 0) { "ambisonicOrder cannot be negative" }

            val irSize = irSizeFor(audioSettings, duration)
            val channelCount = SteamAudio.ambisonicChannelCount(ambisonicOrder)
            val contextHandle = context.liveHandle

            Arena.ofConfined().use { arena ->
                val audio = audioSettings.allocate(arena)
                val settings = IPLReflectionEffectSettings.allocate(arena)
                IPLReflectionEffectSettings.type(settings, type.value)
                IPLReflectionEffectSettings.irSize(settings, irSize)
                IPLReflectionEffectSettings.numChannels(settings, channelCount)
                return ReflectionEffect(
                    createHandle("iplReflectionEffectCreate") { out ->
                        Phonon.iplReflectionEffectCreate(contextHandle, audio, settings, out)
                    },
                    type,
                    channelCount,
                    irSize,
                )
            }
        }

        /** Impulse response length in samples for a given duration, as Steam Audio expects it. */
        public fun irSizeFor(audioSettings: AudioSettings, durationSeconds: Float): Int =
            (durationSeconds * audioSettings.samplingRate).toInt().coerceAtLeast(1)
    }
}

/**
 * A shared Ambisonic bus that several [ReflectionEffect]s accumulate into.
 *
 * With `n` sources, mixing in the Ambisonic domain and decoding once turns `n` decodes into
 * one. For convolution reflections this is the difference between a handful of sources and a
 * useful number of them.
 *
 * ```
 * for (source in sources) {
 *     source.reflection.apply(source.reflectionParams, source.dry, unused, mixer)
 * }
 * mixer.apply(mixerParams, ambisonicBus)          // read the accumulated field out
 * ambisonicDecode.apply(decodeParams, ambisonicBus, stereo)
 * ```
 *
 * A mixer must be created with the same settings as the effects feeding it.
 */
public class ReflectionMixer private constructor(
    handle: MemorySegment,
    /** Number of Ambisonic channels on this bus. */
    public val channelCount: Int,
) : SteamAudioObject(handle) {

    override fun releaseAction(): (MemorySegment) -> Unit = Phonon::iplReflectionMixerRelease

    override val typeName: String get() = "ReflectionMixer"

    /**
     * Reads the accumulated mix into [output] and clears the bus for the next block.
     *
     * Call once per block, after every contributing effect has been applied.
     */
    public fun apply(params: ReflectionEffectParams, output: AudioBuffer): AudioEffectState {
        require(output.channelCount == channelCount) {
            "output needs $channelCount Ambisonic channels, got ${output.channelCount}"
        }
        return AudioEffectState.from(
            Phonon.iplReflectionMixerApply(liveHandle, params.segment, output.struct),
        )
    }

    /** Clears the bus. */
    public fun reset() {
        Phonon.iplReflectionMixerReset(liveHandle)
    }

    /** Increments the reference count and returns an independently closeable wrapper. */
    public fun retain(): ReflectionMixer =
        ReflectionMixer(Phonon.iplReflectionMixerRetain(liveHandle), channelCount)

    public companion object {
        /**
         * Creates a mixer.
         *
         * The arguments must match the [ReflectionEffect]s that will feed it.
         */
        public operator fun invoke(
            context: SteamAudioContext,
            audioSettings: AudioSettings,
            type: ReflectionEffectType = ReflectionEffectType.CONVOLUTION,
            duration: Float = 2.0f,
            ambisonicOrder: Int = 1,
        ): ReflectionMixer {
            val channelCount = SteamAudio.ambisonicChannelCount(ambisonicOrder)
            val contextHandle = context.liveHandle
            Arena.ofConfined().use { arena ->
                val audio = audioSettings.allocate(arena)
                val settings = IPLReflectionEffectSettings.allocate(arena)
                IPLReflectionEffectSettings.type(settings, type.value)
                IPLReflectionEffectSettings.irSize(
                    settings,
                    ReflectionEffect.irSizeFor(audioSettings, duration),
                )
                IPLReflectionEffectSettings.numChannels(settings, channelCount)
                return ReflectionMixer(
                    createHandle("iplReflectionMixerCreate") { out ->
                        Phonon.iplReflectionMixerCreate(contextHandle, audio, settings, out)
                    },
                    channelCount,
                )
            }
        }
    }
}
