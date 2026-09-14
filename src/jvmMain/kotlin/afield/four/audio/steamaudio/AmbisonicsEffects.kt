package afield.four.audio.steamaudio

import afield.four.audio.steamaudio.raw.IPLAmbisonicsBinauralEffectParams
import afield.four.audio.steamaudio.raw.IPLAmbisonicsBinauralEffectSettings
import afield.four.audio.steamaudio.raw.IPLAmbisonicsDecodeEffectParams
import afield.four.audio.steamaudio.raw.IPLAmbisonicsDecodeEffectSettings
import afield.four.audio.steamaudio.raw.IPLAmbisonicsEncodeEffectParams
import afield.four.audio.steamaudio.raw.IPLAmbisonicsEncodeEffectSettings
import afield.four.audio.steamaudio.raw.IPLAmbisonicsRotationEffectParams
import afield.four.audio.steamaudio.raw.IPLAmbisonicsRotationEffectSettings
import afield.four.audio.steamaudio.raw.IPLVector3
import afield.four.audio.steamaudio.raw.Phonon
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

/** Per-block parameters for an [AmbisonicsEncodeEffect]. A mutable native view. */
public class AmbisonicsEncodeEffectParams private constructor(
    internal val segment: MemorySegment,
) {
    /** Allocates a parameter block from [arena]. */
    public constructor(arena: Arena, order: Int) :
        this(IPLAmbisonicsEncodeEffectParams.allocate(arena)) {
        this.order = order
        setDirection(0f, 0f, -1f)
    }

    /** Direction the source should be encoded at. Allocation-free. */
    public fun setDirection(x: Float, y: Float, z: Float) {
        val direction = IPLAmbisonicsEncodeEffectParams.direction(segment)
        IPLVector3.x(direction, x)
        IPLVector3.y(direction, y)
        IPLVector3.z(direction, z)
    }

    /** Direction the source is encoded at. */
    public var direction: Vector3
        get() = Vector3.readFrom(IPLAmbisonicsEncodeEffectParams.direction(segment))
        set(value) = setDirection(value.x, value.y, value.z)

    /** Ambisonic order to encode at. May be lower than the effect's maximum. */
    public var order: Int
        get() = IPLAmbisonicsEncodeEffectParams.order(segment)
        set(value) = IPLAmbisonicsEncodeEffectParams.order(segment, value)
}

/**
 * Encodes a mono source into an Ambisonic field arriving from a given direction.
 *
 * Useful for building a scene-wide Ambisonic bus by hand: encode each source, sum the buses,
 * then decode once. Steam Audio's [ReflectionEffect] already outputs Ambisonics, so this is
 * mainly for direct-path sources you want to fold into the same bus.
 *
 * Input mono, output `(order + 1)^2` channels.
 */
public class AmbisonicsEncodeEffect private constructor(
    handle: MemorySegment,
    /** Highest Ambisonic order this effect can encode. */
    public val maxOrder: Int,
) : AudioEffect(handle) {

    override fun releaseAction(): (MemorySegment) -> Unit = Phonon::iplAmbisonicsEncodeEffectRelease

    override val typeName: String get() = "AmbisonicsEncodeEffect"

    /** Number of output channels at [maxOrder]. */
    public val channelCount: Int get() = SteamAudio.ambisonicChannelCount(maxOrder)

    /** Renders one block. Input mono, output Ambisonic. */
    public fun apply(
        params: AmbisonicsEncodeEffectParams,
        input: AudioBuffer,
        output: AudioBuffer,
    ): AudioEffectState {
        require(input.channelCount == 1) {
            "Ambisonics encoding takes mono input, got ${input.channelCount} channels"
        }
        return AudioEffectState.from(
            Phonon.iplAmbisonicsEncodeEffectApply(
                liveHandle,
                params.segment,
                input.struct,
                output.struct,
            ),
        )
    }

    override fun reset() {
        Phonon.iplAmbisonicsEncodeEffectReset(liveHandle)
    }

    override val tailSize: Int
        get() = Phonon.iplAmbisonicsEncodeEffectGetTailSize(liveHandle)

    override fun getTail(output: AudioBuffer): AudioEffectState = AudioEffectState.from(
        Phonon.iplAmbisonicsEncodeEffectGetTail(liveHandle, output.struct),
    )

    /** Increments the reference count and returns an independently closeable wrapper. */
    public fun retain(): AmbisonicsEncodeEffect =
        AmbisonicsEncodeEffect(Phonon.iplAmbisonicsEncodeEffectRetain(liveHandle), maxOrder)

    public companion object {
        /** Creates an encoder supporting up to [maxOrder]. */
        public operator fun invoke(
            context: SteamAudioContext,
            audioSettings: AudioSettings,
            maxOrder: Int = 1,
        ): AmbisonicsEncodeEffect {
            val contextHandle = context.liveHandle
            Arena.ofConfined().use { arena ->
                val audio = audioSettings.allocate(arena)
                val settings = IPLAmbisonicsEncodeEffectSettings.allocate(arena)
                IPLAmbisonicsEncodeEffectSettings.maxOrder(settings, maxOrder)
                return AmbisonicsEncodeEffect(
                    createHandle("iplAmbisonicsEncodeEffectCreate") { out ->
                        Phonon.iplAmbisonicsEncodeEffectCreate(contextHandle, audio, settings, out)
                    },
                    maxOrder,
                )
            }
        }
    }
}

/** Per-block parameters for an [AmbisonicsDecodeEffect]. A mutable native view. */
public class AmbisonicsDecodeEffectParams private constructor(
    internal val segment: MemorySegment,
) {
    /**
     * Allocates a parameter block from [arena].
     *
     * @param binaural render to headphones via HRTF rather than to a speaker layout. This is
     *   what turns an Ambisonic reflection bus into something you can listen to.
     * @param hrtf required when [binaural] is set.
     */
    public constructor(
        arena: Arena,
        order: Int,
        binaural: Boolean = true,
        hrtf: Hrtf? = null,
    ) : this(IPLAmbisonicsDecodeEffectParams.allocate(arena)) {
        require(!binaural || hrtf != null) { "binaural decoding needs an HRTF" }
        this.order = order
        this.binaural = binaural
        this.orientation = CoordinateSpace3.IDENTITY
        if (hrtf != null) this.hrtf = hrtf
    }

    /** Ambisonic order to decode. May be lower than the effect's maximum. */
    public var order: Int
        get() = IPLAmbisonicsDecodeEffectParams.order(segment)
        set(value) = IPLAmbisonicsDecodeEffectParams.order(segment, value)

    /** Whether to decode binaurally (HRTF) rather than to the speaker layout. */
    public var binaural: Boolean
        get() = IPLAmbisonicsDecodeEffectParams.binaural(segment) != 0
        set(value) = IPLAmbisonicsDecodeEffectParams.binaural(segment, if (value) 1 else 0)

    /**
     * The listener's orientation, used to rotate the Ambisonic field into head-relative space.
     *
     * This is what makes head turns work: the reflected field is simulated in world space, and
     * rotating it here is far cheaper than re-simulating.
     */
    public var orientation: CoordinateSpace3
        get() = CoordinateSpace3.readFrom(IPLAmbisonicsDecodeEffectParams.orientation(segment))
        set(value) = value.writeTo(IPLAmbisonicsDecodeEffectParams.orientation(segment))

    /** The HRTF used for binaural decoding. Held as a raw pointer, so it must stay alive. */
    public var hrtf: Hrtf? = null
        set(value) {
            IPLAmbisonicsDecodeEffectParams.hrtf(segment, value.handleOrNull())
            field = value
        }
}

/**
 * Decodes an Ambisonic field to speakers or, in binaural mode, to headphones.
 *
 * This is the last stage of the reflections chain. A [ReflectionEffect] produces Ambisonics --
 * the reflected energy arrives from everywhere at once, so it has no single direction -- and
 * this turns that field into a stereo signal that carries the spatial impression of the space.
 *
 * ```
 * reflection.apply(reflectionParams, dry, ambisonic)
 * decodeParams.orientation = listenerFrame       // head tracking, cheaply
 * decode.apply(decodeParams, ambisonic, stereo)
 * ```
 *
 * Input must have `(maxOrder + 1)^2` channels. Output is stereo in binaural mode, or the
 * speaker layout's channel count otherwise.
 */
public class AmbisonicsDecodeEffect private constructor(
    handle: MemorySegment,
    /** Highest Ambisonic order this effect can decode. */
    public val maxOrder: Int,
    /** The speaker layout used when not decoding binaurally. */
    public val speakerLayout: SpeakerLayout,
) : AudioEffect(handle) {

    override fun releaseAction(): (MemorySegment) -> Unit = Phonon::iplAmbisonicsDecodeEffectRelease

    override val typeName: String get() = "AmbisonicsDecodeEffect"

    /** Number of Ambisonic input channels expected. */
    public val inputChannelCount: Int get() = SteamAudio.ambisonicChannelCount(maxOrder)

    /** Renders one block. */
    public fun apply(
        params: AmbisonicsDecodeEffectParams,
        input: AudioBuffer,
        output: AudioBuffer,
    ): AudioEffectState {
        require(input.channelCount == inputChannelCount) {
            "input needs $inputChannelCount Ambisonic channels, got ${input.channelCount}"
        }
        if (params.binaural) {
            require(output.channelCount == 2) {
                "binaural decoding produces stereo, got ${output.channelCount} channels"
            }
        }
        return AudioEffectState.from(
            Phonon.iplAmbisonicsDecodeEffectApply(
                liveHandle,
                params.segment,
                input.struct,
                output.struct,
            ),
        )
    }

    override fun reset() {
        Phonon.iplAmbisonicsDecodeEffectReset(liveHandle)
    }

    override val tailSize: Int
        get() = Phonon.iplAmbisonicsDecodeEffectGetTailSize(liveHandle)

    override fun getTail(output: AudioBuffer): AudioEffectState = AudioEffectState.from(
        Phonon.iplAmbisonicsDecodeEffectGetTail(liveHandle, output.struct),
    )

    /** Increments the reference count and returns an independently closeable wrapper. */
    public fun retain(): AmbisonicsDecodeEffect = AmbisonicsDecodeEffect(
        Phonon.iplAmbisonicsDecodeEffectRetain(liveHandle),
        maxOrder,
        speakerLayout,
    )

    public companion object {
        /**
         * Creates a decoder.
         *
         * @param hrtf needed for binaural decoding. Not retained, so keep it alive.
         * @param speakerLayout used when decoding to speakers rather than headphones.
         */
        public operator fun invoke(
            context: SteamAudioContext,
            audioSettings: AudioSettings,
            hrtf: Hrtf,
            maxOrder: Int = 1,
            speakerLayout: SpeakerLayout = SpeakerLayout.STEREO,
        ): AmbisonicsDecodeEffect {
            val contextHandle = context.liveHandle
            Arena.ofConfined().use { arena ->
                val audio = audioSettings.allocate(arena)
                val settings = IPLAmbisonicsDecodeEffectSettings.allocate(arena)
                IPLAmbisonicsDecodeEffectSettings.maxOrder(settings, maxOrder)
                IPLAmbisonicsDecodeEffectSettings.hrtf(settings, hrtf.liveHandle)
                IPLAmbisonicsDecodeEffectSettings.speakerLayout(
                    settings,
                    speakerLayout.allocate(arena),
                )
                return AmbisonicsDecodeEffect(
                    createHandle("iplAmbisonicsDecodeEffectCreate") { out ->
                        Phonon.iplAmbisonicsDecodeEffectCreate(contextHandle, audio, settings, out)
                    },
                    maxOrder,
                    speakerLayout,
                )
            }
        }
    }
}

/** Per-block parameters for an [AmbisonicsBinauralEffect]. */
public class AmbisonicsBinauralEffectParams private constructor(
    internal val segment: MemorySegment,
) {
    /** Allocates a parameter block from [arena]. */
    public constructor(arena: Arena, order: Int, hrtf: Hrtf) :
        this(IPLAmbisonicsBinauralEffectParams.allocate(arena)) {
        this.order = order
        this.hrtf = hrtf
    }

    /** Ambisonic order to render. */
    public var order: Int
        get() = IPLAmbisonicsBinauralEffectParams.order(segment)
        set(value) = IPLAmbisonicsBinauralEffectParams.order(segment, value)

    /** The HRTF to render with. Held as a raw pointer. */
    public var hrtf: Hrtf? = null
        set(value) {
            requireNotNull(value) { "an AmbisonicsBinauralEffect needs an HRTF" }
            IPLAmbisonicsBinauralEffectParams.hrtf(segment, value.liveHandle)
            field = value
        }
}

/**
 * Renders an Ambisonic field directly to headphones via HRTF.
 *
 * Similar in effect to an [AmbisonicsDecodeEffect] in binaural mode, but without the speaker
 * layout machinery or the field rotation. Use the decode effect when you need head tracking;
 * use this when you just want a fixed Ambisonic bus rendered binaurally.
 */
public class AmbisonicsBinauralEffect private constructor(
    handle: MemorySegment,
    /** Highest Ambisonic order this effect can render. */
    public val maxOrder: Int,
) : AudioEffect(handle) {

    override fun releaseAction(): (MemorySegment) -> Unit =
        Phonon::iplAmbisonicsBinauralEffectRelease

    override val typeName: String get() = "AmbisonicsBinauralEffect"

    /** Number of Ambisonic input channels expected. */
    public val inputChannelCount: Int get() = SteamAudio.ambisonicChannelCount(maxOrder)

    /** Renders one block. Input Ambisonic, output stereo. */
    public fun apply(
        params: AmbisonicsBinauralEffectParams,
        input: AudioBuffer,
        output: AudioBuffer,
    ): AudioEffectState {
        require(output.channelCount == 2) {
            "binaural rendering produces stereo, got ${output.channelCount} channels"
        }
        return AudioEffectState.from(
            Phonon.iplAmbisonicsBinauralEffectApply(
                liveHandle,
                params.segment,
                input.struct,
                output.struct,
            ),
        )
    }

    override fun reset() {
        Phonon.iplAmbisonicsBinauralEffectReset(liveHandle)
    }

    override val tailSize: Int
        get() = Phonon.iplAmbisonicsBinauralEffectGetTailSize(liveHandle)

    override fun getTail(output: AudioBuffer): AudioEffectState = AudioEffectState.from(
        Phonon.iplAmbisonicsBinauralEffectGetTail(liveHandle, output.struct),
    )

    /** Increments the reference count and returns an independently closeable wrapper. */
    public fun retain(): AmbisonicsBinauralEffect =
        AmbisonicsBinauralEffect(Phonon.iplAmbisonicsBinauralEffectRetain(liveHandle), maxOrder)

    public companion object {
        /** Creates the effect. */
        public operator fun invoke(
            context: SteamAudioContext,
            audioSettings: AudioSettings,
            hrtf: Hrtf,
            maxOrder: Int = 1,
        ): AmbisonicsBinauralEffect {
            val contextHandle = context.liveHandle
            Arena.ofConfined().use { arena ->
                val audio = audioSettings.allocate(arena)
                val settings = IPLAmbisonicsBinauralEffectSettings.allocate(arena)
                IPLAmbisonicsBinauralEffectSettings.maxOrder(settings, maxOrder)
                IPLAmbisonicsBinauralEffectSettings.hrtf(settings, hrtf.liveHandle)
                return AmbisonicsBinauralEffect(
                    createHandle("iplAmbisonicsBinauralEffectCreate") { out ->
                        Phonon.iplAmbisonicsBinauralEffectCreate(contextHandle, audio, settings, out)
                    },
                    maxOrder,
                )
            }
        }
    }
}

/** Per-block parameters for an [AmbisonicsRotationEffect]. */
public class AmbisonicsRotationEffectParams private constructor(
    internal val segment: MemorySegment,
) {
    /** Allocates a parameter block from [arena]. */
    public constructor(arena: Arena, order: Int) :
        this(IPLAmbisonicsRotationEffectParams.allocate(arena)) {
        this.order = order
        this.orientation = CoordinateSpace3.IDENTITY
    }

    /** Ambisonic order to rotate. */
    public var order: Int
        get() = IPLAmbisonicsRotationEffectParams.order(segment)
        set(value) = IPLAmbisonicsRotationEffectParams.order(segment, value)

    /** The frame to rotate the field into -- typically the listener's head orientation. */
    public var orientation: CoordinateSpace3
        get() = CoordinateSpace3.readFrom(IPLAmbisonicsRotationEffectParams.orientation(segment))
        set(value) = value.writeTo(IPLAmbisonicsRotationEffectParams.orientation(segment))
}

/**
 * Rotates an Ambisonic field, so a world-space field can be made head-relative.
 *
 * Rotating Ambisonics is cheap and exact, which is why simulating reflections in world space
 * and rotating for head movement is far better than re-simulating when the listener turns.
 */
public class AmbisonicsRotationEffect private constructor(
    handle: MemorySegment,
    /** Highest Ambisonic order this effect can rotate. */
    public val maxOrder: Int,
) : AudioEffect(handle) {

    override fun releaseAction(): (MemorySegment) -> Unit =
        Phonon::iplAmbisonicsRotationEffectRelease

    override val typeName: String get() = "AmbisonicsRotationEffect"

    /** Number of Ambisonic channels this effect works on. */
    public val channelCount: Int get() = SteamAudio.ambisonicChannelCount(maxOrder)

    /** Renders one block. Input and output both Ambisonic with the same channel count. */
    public fun apply(
        params: AmbisonicsRotationEffectParams,
        input: AudioBuffer,
        output: AudioBuffer,
    ): AudioEffectState = AudioEffectState.from(
        Phonon.iplAmbisonicsRotationEffectApply(
            liveHandle,
            params.segment,
            input.struct,
            output.struct,
        ),
    )

    override fun reset() {
        Phonon.iplAmbisonicsRotationEffectReset(liveHandle)
    }

    override val tailSize: Int
        get() = Phonon.iplAmbisonicsRotationEffectGetTailSize(liveHandle)

    override fun getTail(output: AudioBuffer): AudioEffectState = AudioEffectState.from(
        Phonon.iplAmbisonicsRotationEffectGetTail(liveHandle, output.struct),
    )

    /** Increments the reference count and returns an independently closeable wrapper. */
    public fun retain(): AmbisonicsRotationEffect =
        AmbisonicsRotationEffect(Phonon.iplAmbisonicsRotationEffectRetain(liveHandle), maxOrder)

    public companion object {
        /** Creates the effect. */
        public operator fun invoke(
            context: SteamAudioContext,
            audioSettings: AudioSettings,
            maxOrder: Int = 1,
        ): AmbisonicsRotationEffect {
            val contextHandle = context.liveHandle
            Arena.ofConfined().use { arena ->
                val audio = audioSettings.allocate(arena)
                val settings = IPLAmbisonicsRotationEffectSettings.allocate(arena)
                IPLAmbisonicsRotationEffectSettings.maxOrder(settings, maxOrder)
                return AmbisonicsRotationEffect(
                    createHandle("iplAmbisonicsRotationEffectCreate") { out ->
                        Phonon.iplAmbisonicsRotationEffectCreate(contextHandle, audio, settings, out)
                    },
                    maxOrder,
                )
            }
        }
    }
}
