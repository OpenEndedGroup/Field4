package afield.four.audio.steamaudio

import afield.four.audio.steamaudio.raw.IPLDirectEffectParams
import afield.four.audio.steamaudio.raw.IPLDirectEffectSettings
import afield.four.audio.steamaudio.raw.Phonon
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * Per-block parameters for a [DirectEffect], held directly in native memory.
 *
 * Mutable view over an `IPLDirectEffectParams` struct: allocate one per source from a
 * long-lived [Arena] and update it in place each block, so the audio thread allocates nothing.
 *
 * Only the quantities named in [flags] are applied, so a parameter you do not set is simply
 * ignored rather than silently attenuating the source.
 *
 * When driving this from a [Simulator], prefer copying the simulator's own output block
 * wholesale via [copyFrom] rather than transcribing fields one at a time.
 */
public class DirectEffectParams private constructor(
    internal val segment: MemorySegment,
) {

    /** Allocates a parameter block from [arena]. */
    public constructor(
        arena: Arena,
        flags: DirectEffectFlags = DirectEffectFlags.NONE,
    ) : this(IPLDirectEffectParams.allocate(arena)) {
        this.flags = flags
        this.transmissionType = TransmissionType.FREQUENCY_INDEPENDENT
        this.distanceAttenuation = 1f
        this.directivity = 1f
        this.occlusion = 1f
        for (band in 0 until SteamAudio.NUM_BANDS) {
            setAirAbsorption(band, 1f)
            setTransmission(band, 1f)
        }
    }

    /** Which of the quantities below are actually applied. */
    public var flags: DirectEffectFlags
        get() = DirectEffectFlags(IPLDirectEffectParams.flags(segment))
        set(value) = IPLDirectEffectParams.flags(segment, value.bits)

    /** Whether transmission is applied per-band or as a single broadband gain. */
    public var transmissionType: TransmissionType
        get() = TransmissionType.from(IPLDirectEffectParams.transmissionType(segment))
        set(value) = IPLDirectEffectParams.transmissionType(segment, value.value)

    /**
     * Linear gain from distance falloff, where `1` is no attenuation.
     *
     * Applied only when [DirectEffectFlags.APPLY_DISTANCE_ATTENUATION] is set. Compute it with
     * [DistanceAttenuationModel.calculate], or let a [Simulator] fill it in.
     */
    public var distanceAttenuation: Float
        get() = IPLDirectEffectParams.distanceAttenuation(segment)
        set(value) = IPLDirectEffectParams.distanceAttenuation(segment, value)

    /**
     * Linear gain from the source's directivity pattern, where `1` is omnidirectional.
     *
     * Applied only when [DirectEffectFlags.APPLY_DIRECTIVITY] is set.
     */
    public var directivity: Float
        get() = IPLDirectEffectParams.directivity(segment)
        set(value) = IPLDirectEffectParams.directivity(segment, value)

    /**
     * Fraction of the direct path that reaches the listener: `1` is clear, `0` fully blocked.
     *
     * Applied only when [DirectEffectFlags.APPLY_OCCLUSION] is set.
     */
    public var occlusion: Float
        get() = IPLDirectEffectParams.occlusion(segment)
        set(value) = IPLDirectEffectParams.occlusion(segment, value)

    /**
     * Per-band gain from air absorption, where `1` is no absorption.
     *
     * Applied only when [DirectEffectFlags.APPLY_AIR_ABSORPTION] is set.
     * [band] indexes `0 until` [SteamAudio.NUM_BANDS], low frequency first.
     */
    public fun setAirAbsorption(band: Int, gain: Float) {
        checkBand(band)
        IPLDirectEffectParams.airAbsorption(segment).setAtIndex(
            ValueLayout.JAVA_FLOAT,
            band.toLong(),
            gain,
        )
    }

    /** Reads back a per-band air absorption gain. */
    public fun airAbsorption(band: Int): Float {
        checkBand(band)
        return IPLDirectEffectParams.airAbsorption(segment)
            .getAtIndex(ValueLayout.JAVA_FLOAT, band.toLong())
    }

    /** Copies all [SteamAudio.NUM_BANDS] air absorption gains from [gains]. */
    public fun setAirAbsorption(gains: FloatArray) {
        require(gains.size == SteamAudio.NUM_BANDS) {
            "expected ${SteamAudio.NUM_BANDS} bands, got ${gains.size}"
        }
        MemorySegment.copy(
            gains, 0,
            IPLDirectEffectParams.airAbsorption(segment), ValueLayout.JAVA_FLOAT, 0L,
            gains.size,
        )
    }

    /**
     * Per-band gain for sound transmitted through geometry.
     *
     * Applied only when [DirectEffectFlags.APPLY_TRANSMISSION] is set.
     */
    public fun setTransmission(band: Int, gain: Float) {
        checkBand(band)
        IPLDirectEffectParams.transmission(segment).setAtIndex(
            ValueLayout.JAVA_FLOAT,
            band.toLong(),
            gain,
        )
    }

    /** Reads back a per-band transmission gain. */
    public fun transmission(band: Int): Float {
        checkBand(band)
        return IPLDirectEffectParams.transmission(segment)
            .getAtIndex(ValueLayout.JAVA_FLOAT, band.toLong())
    }

    /** Copies all [SteamAudio.NUM_BANDS] transmission gains from [gains]. */
    public fun setTransmission(gains: FloatArray) {
        require(gains.size == SteamAudio.NUM_BANDS) {
            "expected ${SteamAudio.NUM_BANDS} bands, got ${gains.size}"
        }
        MemorySegment.copy(
            gains, 0,
            IPLDirectEffectParams.transmission(segment), ValueLayout.JAVA_FLOAT, 0L,
            gains.size,
        )
    }

    /**
     * Copies every field from another parameter block, in one native memory copy.
     *
     * This is the cheap path for taking a [Simulator]'s direct-path output and handing it
     * straight to a [DirectEffect].
     */
    public fun copyFrom(other: DirectEffectParams) {
        segment.copyFrom(other.segment)
    }

    private fun checkBand(band: Int) {
        if (band !in 0 until SteamAudio.NUM_BANDS) {
            throw IndexOutOfBoundsException("band $band outside 0..<${SteamAudio.NUM_BANDS}")
        }
    }
}

/**
 * Applies the direct-path acoustics of a source: distance falloff, air absorption, directivity,
 * occlusion and transmission.
 *
 * This is the non-spatial half of a source's signal chain -- it changes *how much* and *what
 * spectrum* arrives, while a [BinauralEffect] or [PanningEffect] decides *where from*. The
 * usual order is direct first, then spatialisation:
 *
 * ```
 * direct.apply(directParams, dry, shaped)      // mono -> mono
 * binaural.apply(binauralParams, shaped, out)  // mono -> stereo
 * ```
 *
 * Input and output must both have the channel count the effect was created with.
 */
public class DirectEffect private constructor(
    handle: MemorySegment,
    /** The channel count this effect was created for. */
    public val channelCount: Int,
) : AudioEffect(handle) {

    override fun releaseAction(): (MemorySegment) -> Unit = Phonon::iplDirectEffectRelease

    override val typeName: String get() = "DirectEffect"

    /** Renders one block. Input and output must both have [channelCount] channels. */
    public fun apply(
        params: DirectEffectParams,
        input: AudioBuffer,
        output: AudioBuffer,
    ): AudioEffectState {
        require(input.channelCount == channelCount && output.channelCount == channelCount) {
            "this direct effect works on $channelCount channels, got " +
                "${input.channelCount} in and ${output.channelCount} out"
        }
        require(input.sampleCount == output.sampleCount) {
            "input and output must be the same length: ${input.sampleCount} vs ${output.sampleCount}"
        }
        return AudioEffectState.from(
            Phonon.iplDirectEffectApply(liveHandle, params.segment, input.struct, output.struct),
        )
    }

    override fun reset() {
        Phonon.iplDirectEffectReset(liveHandle)
    }

    override val tailSize: Int
        get() = Phonon.iplDirectEffectGetTailSize(liveHandle)

    override fun getTail(output: AudioBuffer): AudioEffectState {
        require(output.channelCount == channelCount) {
            "this direct effect works on $channelCount channels, got ${output.channelCount}"
        }
        return AudioEffectState.from(Phonon.iplDirectEffectGetTail(liveHandle, output.struct))
    }

    /** Increments the reference count and returns an independently closeable wrapper. */
    public fun retain(): DirectEffect =
        DirectEffect(Phonon.iplDirectEffectRetain(liveHandle), channelCount)

    public companion object {
        /**
         * Creates a direct effect.
         *
         * @param channelCount how many channels the effect processes. Mono is the usual choice,
         *   since spatialisation happens downstream.
         */
        public operator fun invoke(
            context: SteamAudioContext,
            audioSettings: AudioSettings,
            channelCount: Int = 1,
        ): DirectEffect {
            require(channelCount > 0) { "channelCount must be positive, got $channelCount" }
            val contextHandle = context.liveHandle
            Arena.ofConfined().use { arena ->
                val audio = audioSettings.allocate(arena)
                val settings = IPLDirectEffectSettings.allocate(arena)
                IPLDirectEffectSettings.numChannels(settings, channelCount)
                return DirectEffect(
                    createHandle("iplDirectEffectCreate") { out ->
                        Phonon.iplDirectEffectCreate(contextHandle, audio, settings, out)
                    },
                    channelCount,
                )
            }
        }
    }
}
