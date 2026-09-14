package afield.four.audio.steamaudio

import afield.four.audio.steamaudio.raw.IPLBinauralEffectParams
import afield.four.audio.steamaudio.raw.IPLBinauralEffectSettings
import afield.four.audio.steamaudio.raw.IPLVector3
import afield.four.audio.steamaudio.raw.Phonon
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * Per-block parameters for a [BinauralEffect], held directly in native memory.
 *
 * This is a mutable view over an `IPLBinauralEffectParams` struct rather than a data class, so
 * that updating it each block costs nothing on the JVM heap. Allocate one per source from a
 * long-lived [Arena] and mutate it in place:
 *
 * ```
 * val params = BinauralEffectParams(arena, hrtf)
 * // per block, on the audio thread:
 * params.setDirection(x, y, z)
 * binaural.apply(params, input, output)
 * ```
 *
 * The struct keeps a raw pointer to its [hrtf], so that HRTF must stay alive and unclosed for
 * as long as this object is in use.
 */
public class BinauralEffectParams private constructor(
    internal val segment: MemorySegment,
    private val peakDelaySlot: MemorySegment,
) {

    /**
     * Allocates a parameter block from [arena].
     *
     * @param hrtf the HRTF to render with. Required -- Steam Audio reads it on every `apply`.
     * @param trackPeakDelays when true, reserves room for the per-ear peak delays that
     *   Steam Audio can report back, readable afterwards via [leftPeakDelay] and
     *   [rightPeakDelay]. Leave this off unless you need them; it costs the native side a
     *   little extra work.
     */
    public constructor(
        arena: Arena,
        hrtf: Hrtf,
        interpolation: HrtfInterpolation = HrtfInterpolation.BILINEAR,
        spatialBlend: Float = 1.0f,
        trackPeakDelays: Boolean = false,
    ) : this(
        segment = IPLBinauralEffectParams.allocate(arena),
        peakDelaySlot = if (trackPeakDelays) {
            arena.allocate(ValueLayout.JAVA_FLOAT, 2L)
        } else {
            MemorySegment.NULL
        },
    ) {
        this.hrtf = hrtf
        this.interpolation = interpolation
        this.spatialBlend = spatialBlend
        setDirection(0f, 0f, -1f)
        IPLBinauralEffectParams.peakDelays(segment, peakDelaySlot)
    }

    /**
     * Direction from the listener to the source, in the **listener's local frame**: +x right,
     * +y up, -z ahead.
     *
     * Need not be normalised; Steam Audio only uses the direction. Use
     * [SteamAudioContext.calculateRelativeDirection] to convert a world-space source and
     * listener into this frame.
     *
     * This overload avoids constructing a [Vector3], so it is the one to call per block.
     */
    public fun setDirection(x: Float, y: Float, z: Float) {
        val direction = IPLBinauralEffectParams.direction(segment)
        IPLVector3.x(direction, x)
        IPLVector3.y(direction, y)
        IPLVector3.z(direction, z)
    }

    /** The source direction, in the listener's local frame. Allocates on read; see [setDirection]. */
    public var direction: Vector3
        get() = Vector3.readFrom(IPLBinauralEffectParams.direction(segment))
        set(value) = setDirection(value.x, value.y, value.z)

    /** How HRTF data is interpolated between measured directions. */
    public var interpolation: HrtfInterpolation
        get() = HrtfInterpolation.from(IPLBinauralEffectParams.interpolation(segment))
        set(value) = IPLBinauralEffectParams.interpolation(segment, value.value)

    /**
     * Blend between unspatialised (`0`) and fully spatialised (`1`) output.
     *
     * Values below 1 pull the source towards the centre of the head, which is useful for
     * sources that should feel close and diffuse rather than precisely placed.
     */
    public var spatialBlend: Float
        get() = IPLBinauralEffectParams.spatialBlend(segment)
        set(value) {
            require(value in 0f..1f) { "spatialBlend must be in 0..1, got $value" }
            IPLBinauralEffectParams.spatialBlend(segment, value)
        }

    /**
     * The HRTF used to render.
     *
     * Stored as a raw pointer, so the [Hrtf] must outlive this parameter block. Reading this
     * property returns the wrapper last assigned, not a value read back from native memory.
     */
    public var hrtf: Hrtf? = null
        set(value) {
            requireNotNull(value) { "a BinauralEffect needs an HRTF" }
            IPLBinauralEffectParams.hrtf(segment, value.liveHandle)
            field = value
        }

    /** Peak delay for the left ear in samples, or `null` unless `trackPeakDelays` was set. */
    public val leftPeakDelay: Float?
        get() = if (peakDelaySlot.address() == 0L) null else peakDelaySlot.getAtIndex(ValueLayout.JAVA_FLOAT, 0L)

    /** Peak delay for the right ear in samples, or `null` unless `trackPeakDelays` was set. */
    public val rightPeakDelay: Float?
        get() = if (peakDelaySlot.address() == 0L) null else peakDelaySlot.getAtIndex(ValueLayout.JAVA_FLOAT, 1L)
}

/**
 * Renders a mono source to stereo using a head-related transfer function, so it appears to come
 * from a specific direction relative to the listener.
 *
 * This is the core of spatial audio: given a mono signal and a direction, it produces the
 * interaural time and level differences, plus the spectral shaping from the head and outer ear,
 * that the ear uses to localise a sound.
 *
 * Input must be **mono**; output must be **stereo**.
 *
 * ```
 * val effect = BinauralEffect(context, settings, hrtf)
 * val params = BinauralEffectParams(arena, hrtf)
 * params.setDirection(dx, dy, dz)
 * effect.apply(params, mono, stereo)
 * ```
 *
 * A binaural effect is stateful -- it holds the filter history that makes movement sound smooth
 * -- so use one effect per source and keep it for that source's lifetime. Sharing one effect
 * between sources, or recycling it without [reset], causes audible clicks.
 */
public class BinauralEffect private constructor(handle: MemorySegment) : AudioEffect(handle) {

    override fun releaseAction(): (MemorySegment) -> Unit = Phonon::iplBinauralEffectRelease

    override val typeName: String get() = "BinauralEffect"

    /**
     * Renders one block.
     *
     * @param input a mono buffer of the block size this effect was created with.
     * @param output a stereo buffer of the same length.
     * @return whether the effect still holds a tail. For a binaural effect the tail is short
     *   (the length of the HRTF impulse response), but it is not zero.
     */
    public fun apply(
        params: BinauralEffectParams,
        input: AudioBuffer,
        output: AudioBuffer,
    ): AudioEffectState {
        require(input.channelCount == 1) {
            "a binaural effect takes mono input, got ${input.channelCount} channels"
        }
        require(output.channelCount == 2) {
            "a binaural effect produces stereo output, got ${output.channelCount} channels"
        }
        require(input.sampleCount == output.sampleCount) {
            "input and output must be the same length: " +
                "${input.sampleCount} vs ${output.sampleCount}"
        }
        return AudioEffectState.from(
            Phonon.iplBinauralEffectApply(liveHandle, params.segment, input.struct, output.struct),
        )
    }

    override fun reset() {
        Phonon.iplBinauralEffectReset(liveHandle)
    }

    override val tailSize: Int
        get() = Phonon.iplBinauralEffectGetTailSize(liveHandle)

    override fun getTail(output: AudioBuffer): AudioEffectState {
        require(output.channelCount == 2) {
            "a binaural effect produces stereo output, got ${output.channelCount} channels"
        }
        return AudioEffectState.from(Phonon.iplBinauralEffectGetTail(liveHandle, output.struct))
    }

    /** Increments the reference count and returns an independently closeable wrapper. */
    public fun retain(): BinauralEffect = BinauralEffect(Phonon.iplBinauralEffectRetain(liveHandle))

    public companion object {
        /**
         * Creates a binaural effect.
         *
         * @param hrtf the HRTF this effect renders with. It is not retained, so it must outlive
         *   the effect.
         */
        public operator fun invoke(
            context: SteamAudioContext,
            audioSettings: AudioSettings,
            hrtf: Hrtf,
        ): BinauralEffect {
            val contextHandle = context.liveHandle
            Arena.ofConfined().use { arena ->
                val audio = audioSettings.allocate(arena)
                val settings = IPLBinauralEffectSettings.allocate(arena)
                IPLBinauralEffectSettings.hrtf(settings, hrtf.liveHandle)
                return BinauralEffect(
                    createHandle("iplBinauralEffectCreate") { out ->
                        Phonon.iplBinauralEffectCreate(contextHandle, audio, settings, out)
                    },
                )
            }
        }
    }
}
