package afield.four.audio.steamaudio

/**
 * Common shape for the plain (non-flag) Steam Audio enumerations.
 *
 * The `value` is the underlying C enumerator, which is what crosses the FFM boundary.
 */
internal interface NativeEnum {
    val value: Int
}

// -------------------------------------------------------------------------------------------------
// Logging and diagnostics
// -------------------------------------------------------------------------------------------------

/** Severity of a message passed to a [LogCallback]. */
public enum class LogLevel(override val value: Int) : NativeEnum {
    INFO(0),
    WARNING(1),
    ERROR(2),
    DEBUG(3),
    ;

    internal companion object {
        private val byValue = entries.associateBy(LogLevel::value)
        fun from(value: Int): LogLevel = byValue.getValue(value)
    }
}

/**
 * SIMD instruction sets Steam Audio may use.
 *
 * Careful: in `phonon.h`, `IPL_SIMDLEVEL_NEON` is defined as an **alias** of
 * `IPL_SIMDLEVEL_SSE2` -- both are `0`. There is therefore no distinct `NEON` entry here;
 * use [Companion.NEON], which resolves to [SSE2], when writing ARM-oriented code.
 */
public enum class SimdLevel(override val value: Int) : NativeEnum {
    SSE2(0),
    SSE4(1),
    AVX(2),
    AVX2(3),
    AVX512(4),
    ;

    public companion object {
        /** ARM NEON. An alias of [SSE2] -- the two share the enumerator value `0` upstream. */
        public val NEON: SimdLevel get() = SSE2

        private val byValue = entries.associateBy(SimdLevel::value)

        internal fun from(value: Int): SimdLevel = byValue.getValue(value)
    }
}

// -------------------------------------------------------------------------------------------------
// Devices
// -------------------------------------------------------------------------------------------------

/** Which class of OpenCL device to use. */
public enum class OpenClDeviceType(override val value: Int) : NativeEnum {
    ANY(0),
    CPU(1),
    GPU(2),
    ;

    internal companion object {
        private val byValue = entries.associateBy(OpenClDeviceType::value)
        fun from(value: Int): OpenClDeviceType = byValue.getValue(value)
    }
}

// -------------------------------------------------------------------------------------------------
// Scene
// -------------------------------------------------------------------------------------------------

/** The ray-tracing backend a [Scene] uses. */
public enum class SceneType(override val value: Int) : NativeEnum {
    /** Steam Audio's built-in ray tracer. */
    DEFAULT(0),

    /** Intel Embree. Requires an [EmbreeDevice]. */
    EMBREE(1),

    /** AMD Radeon Rays. Requires a [RadeonRaysDevice]. */
    RADEON_RAYS(2),

    /** Ray casts are delegated to application-supplied callbacks. See [CustomSceneCallbacks]. */
    CUSTOM(3),
    ;

    internal companion object {
        private val byValue = entries.associateBy(SceneType::value)
        fun from(value: Int): SceneType = byValue.getValue(value)
    }
}

/** How occlusion is sampled for a source. */
public enum class OcclusionType(override val value: Int) : NativeEnum {
    /** A single ray cast from source to listener. */
    RAYCAST(0),

    /** Multiple rays cast over a sphere around the source, giving partial occlusion. */
    VOLUMETRIC(1),
    ;

    internal companion object {
        private val byValue = entries.associateBy(OcclusionType::value)
        fun from(value: Int): OcclusionType = byValue.getValue(value)
    }
}

// -------------------------------------------------------------------------------------------------
// Audio
// -------------------------------------------------------------------------------------------------

/** The speaker configuration of an audio buffer. */
public enum class SpeakerLayoutType(override val value: Int) : NativeEnum {
    MONO(0),
    STEREO(1),
    QUADRAPHONIC(2),
    SURROUND_5_1(3),
    SURROUND_7_1(4),

    /** An application-defined layout; see [SpeakerLayout.custom]. */
    CUSTOM(5),
    ;

    /** The channel count implied by this layout, or `null` for [CUSTOM]. */
    public val channelCount: Int?
        get() = when (this) {
            MONO -> 1
            STEREO -> 2
            QUADRAPHONIC -> 4
            SURROUND_5_1 -> 6
            SURROUND_7_1 -> 8
            CUSTOM -> null
        }

    internal companion object {
        private val byValue = entries.associateBy(SpeakerLayoutType::value)
        fun from(value: Int): SpeakerLayoutType = byValue.getValue(value)
    }
}

/** Ambisonic channel ordering and normalisation convention. */
public enum class AmbisonicsType(override val value: Int) : NativeEnum {
    /** ACN channel ordering, N3D normalisation. */
    N3D(0),

    /** ACN channel ordering, SN3D normalisation. */
    SN3D(1),

    /** FuMa channel ordering and normalisation. */
    FUMA(2),
    ;

    internal companion object {
        private val byValue = entries.associateBy(AmbisonicsType::value)
        fun from(value: Int): AmbisonicsType = byValue.getValue(value)
    }
}

/**
 * Whether an effect still has a reverb tail left to render.
 *
 * This is a normal return value, not an error: an effect reports
 * [TAIL_REMAINING] for as long as it has audio left to flush after its input stops.
 */
public enum class AudioEffectState(override val value: Int) : NativeEnum {
    /** The effect has more tail audio to render; keep calling `getTail`. */
    TAIL_REMAINING(0),

    /** The effect has finished rendering its tail. */
    TAIL_COMPLETE(1),
    ;

    internal companion object {
        private val byValue = entries.associateBy(AudioEffectState::value)
        fun from(value: Int): AudioEffectState = byValue.getValue(value)
    }
}

// -------------------------------------------------------------------------------------------------
// HRTF
// -------------------------------------------------------------------------------------------------

/** Where HRTF data comes from. */
public enum class HrtfType(override val value: Int) : NativeEnum {
    /** Steam Audio's built-in HRTF. */
    DEFAULT(0),

    /** An HRTF loaded from a SOFA file or in-memory SOFA image. */
    SOFA(1),
    ;

    internal companion object {
        private val byValue = entries.associateBy(HrtfType::value)
        fun from(value: Int): HrtfType = byValue.getValue(value)
    }
}

/** Volume normalisation applied to HRTF data at load time. */
public enum class HrtfNormType(override val value: Int) : NativeEnum {
    /** No normalisation. */
    NONE(0),

    /** Normalise so each direction has the same RMS. */
    RMS(1),
    ;

    internal companion object {
        private val byValue = entries.associateBy(HrtfNormType::value)
        fun from(value: Int): HrtfNormType = byValue.getValue(value)
    }
}

/** How HRTF data is interpolated between measured directions. */
public enum class HrtfInterpolation(override val value: Int) : NativeEnum {
    /** Use the nearest measured direction. Cheapest; fine for static or slow-moving sources. */
    NEAREST(0),

    /** Blend the four surrounding measurements. Smoother for fast-moving sources. */
    BILINEAR(1),
    ;

    internal companion object {
        private val byValue = entries.associateBy(HrtfInterpolation::value)
        fun from(value: Int): HrtfInterpolation = byValue.getValue(value)
    }
}

// -------------------------------------------------------------------------------------------------
// Direct path
// -------------------------------------------------------------------------------------------------

/** How transmission through geometry is modelled. */
public enum class TransmissionType(override val value: Int) : NativeEnum {
    /** A single attenuation applied to all frequencies. */
    FREQUENCY_INDEPENDENT(0),

    /** A per-band attenuation. */
    FREQUENCY_DEPENDENT(1),
    ;

    internal companion object {
        private val byValue = entries.associateBy(TransmissionType::value)
        fun from(value: Int): TransmissionType = byValue.getValue(value)
    }
}

/** How distance attenuation is computed. */
public enum class DistanceAttenuationModelType(override val value: Int) : NativeEnum {
    /** Steam Audio's default physics-based falloff. */
    DEFAULT(0),

    /** Inverse-distance falloff clamped at a minimum distance. */
    INVERSE_DISTANCE(1),

    /** An application-supplied callback. */
    CALLBACK(2),
    ;

    internal companion object {
        private val byValue = entries.associateBy(DistanceAttenuationModelType::value)
        fun from(value: Int): DistanceAttenuationModelType = byValue.getValue(value)
    }
}

/** How air absorption is computed. */
public enum class AirAbsorptionModelType(override val value: Int) : NativeEnum {
    /** Steam Audio's default model. */
    DEFAULT(0),

    /** Exponential decay with per-band coefficients. */
    EXPONENTIAL(1),

    /** An application-supplied callback. */
    CALLBACK(2),
    ;

    internal companion object {
        private val byValue = entries.associateBy(AirAbsorptionModelType::value)
        fun from(value: Int): AirAbsorptionModelType = byValue.getValue(value)
    }
}

/** How deviation-based attenuation (for pathing) is computed. */
public enum class DeviationModelType(override val value: Int) : NativeEnum {
    /** Steam Audio's default model. */
    DEFAULT(0),

    /** An application-supplied callback. */
    CALLBACK(1),
    ;

    internal companion object {
        private val byValue = entries.associateBy(DeviationModelType::value)
        fun from(value: Int): DeviationModelType = byValue.getValue(value)
    }
}

// -------------------------------------------------------------------------------------------------
// Reflections
// -------------------------------------------------------------------------------------------------

/** The algorithm a [ReflectionEffect] uses to render reflections. */
public enum class ReflectionEffectType(override val value: Int) : NativeEnum {
    /**
     * Convolution with the full simulated impulse response.
     *
     * The accurate option, and -- measured -- also cheaper than [HYBRID] at every reverb
     * duration from 1 to 10 seconds. See [ReflectionEffect] for the numbers.
     */
    CONVOLUTION(0),

    /**
     * A feedback delay network driven by simulated reverb decay times. By far the cheapest,
     * around five times less per block than [CONVOLUTION].
     *
     * Loses early-reflection detail, and can glitch audibly on a foreground source because the
     * network is re-driven by reverb times that step at the simulation rate. Good for distant
     * and background sources.
     */
    PARAMETRIC(1),

    /**
     * Convolution for early reflections, a feedback delay network for the late tail.
     *
     * Intended to save the cost of convolving a long tail. Measured, it does not: it pays for
     * both stages and comes out more expensive than plain [CONVOLUTION] at every duration
     * tested. See [ReflectionEffect].
     */
    HYBRID(2),

    /** Convolution on the GPU via AMD TrueAudio Next. Requires a [TrueAudioNextDevice]. */
    TRUE_AUDIO_NEXT(3),
    ;

    internal companion object {
        private val byValue = entries.associateBy(ReflectionEffectType::value)
        fun from(value: Int): ReflectionEffectType = byValue.getValue(value)
    }
}

// -------------------------------------------------------------------------------------------------
// Probes and baking
// -------------------------------------------------------------------------------------------------

/** How probe positions are generated within a box. */
public enum class ProbeGenerationType(override val value: Int) : NativeEnum {
    /** A single probe at the centre of the box. */
    CENTROID(0),

    /** A regular grid of probes placed above walkable floor geometry. */
    UNIFORM_FLOOR(1),
    ;

    internal companion object {
        private val byValue = entries.associateBy(ProbeGenerationType::value)
        fun from(value: Int): ProbeGenerationType = byValue.getValue(value)
    }
}

/** What a piece of baked data varies over. */
public enum class BakedDataVariation(override val value: Int) : NativeEnum {
    /** Reverb at the probe position; listener and source are both at the probe. */
    REVERB(0),

    /** A fixed source position, listener varying over probes. */
    STATIC_SOURCE(1),

    /** A fixed listener position, source varying over probes. */
    STATIC_LISTENER(2),

    /** Both source and listener vary over probes. */
    DYNAMIC(3),
    ;

    internal companion object {
        private val byValue = entries.associateBy(BakedDataVariation::value)
        fun from(value: Int): BakedDataVariation = byValue.getValue(value)
    }
}

/** Which simulation stage a piece of baked data belongs to. */
public enum class BakedDataType(override val value: Int) : NativeEnum {
    REFLECTIONS(0),
    PATHING(1),
    ;

    internal companion object {
        private val byValue = entries.associateBy(BakedDataType::value)
        fun from(value: Int): BakedDataType = byValue.getValue(value)
    }
}

// -------------------------------------------------------------------------------------------------
// Flag sets
//
// These are bit-OR'd in C, so they are value classes over an Int rather than enums.
// -------------------------------------------------------------------------------------------------

/** Optional [SteamAudioContext] behaviour. */
@JvmInline
public value class ContextFlags(public val bits: Int) {
    public infix fun or(other: ContextFlags): ContextFlags = ContextFlags(bits or other.bits)

    /** True when every bit set in [other] is also set here. */
    public operator fun contains(other: ContextFlags): Boolean = (bits and other.bits) == other.bits

    override fun toString(): String = "ContextFlags(0x${bits.toString(16)})"

    public companion object {
        public val NONE: ContextFlags = ContextFlags(0)

        /** Enable extra validation of API usage. Costs performance; useful during development. */
        public val VALIDATION: ContextFlags = ContextFlags(1 shl 0)
    }
}

/** Which components a [DirectEffect] should apply. */
@JvmInline
public value class DirectEffectFlags(public val bits: Int) {
    public infix fun or(other: DirectEffectFlags): DirectEffectFlags =
        DirectEffectFlags(bits or other.bits)

    public operator fun contains(other: DirectEffectFlags): Boolean =
        (bits and other.bits) == other.bits

    override fun toString(): String = "DirectEffectFlags(0x${bits.toString(16)})"

    public companion object {
        public val NONE: DirectEffectFlags = DirectEffectFlags(0)
        public val APPLY_DISTANCE_ATTENUATION: DirectEffectFlags = DirectEffectFlags(1 shl 0)
        public val APPLY_AIR_ABSORPTION: DirectEffectFlags = DirectEffectFlags(1 shl 1)
        public val APPLY_DIRECTIVITY: DirectEffectFlags = DirectEffectFlags(1 shl 2)
        public val APPLY_OCCLUSION: DirectEffectFlags = DirectEffectFlags(1 shl 3)
        public val APPLY_TRANSMISSION: DirectEffectFlags = DirectEffectFlags(1 shl 4)

        public val ALL: DirectEffectFlags =
            APPLY_DISTANCE_ATTENUATION or APPLY_AIR_ABSORPTION or APPLY_DIRECTIVITY or
                APPLY_OCCLUSION or APPLY_TRANSMISSION
    }
}

/** Which simulation stages a [Simulator] or [Source] participates in. */
@JvmInline
public value class SimulationFlags(public val bits: Int) {
    public infix fun or(other: SimulationFlags): SimulationFlags =
        SimulationFlags(bits or other.bits)

    public operator fun contains(other: SimulationFlags): Boolean =
        (bits and other.bits) == other.bits

    override fun toString(): String = "SimulationFlags(0x${bits.toString(16)})"

    public companion object {
        public val NONE: SimulationFlags = SimulationFlags(0)

        /** Direct-path simulation: occlusion, transmission, distance attenuation, etc. */
        public val DIRECT: SimulationFlags = SimulationFlags(1 shl 0)

        /** Reflection simulation. */
        public val REFLECTIONS: SimulationFlags = SimulationFlags(1 shl 1)

        /** Pathing simulation. */
        public val PATHING: SimulationFlags = SimulationFlags(1 shl 2)

        public val ALL: SimulationFlags = DIRECT or REFLECTIONS or PATHING
    }
}

/** Which direct-path quantities a simulation should compute. */
@JvmInline
public value class DirectSimulationFlags(public val bits: Int) {
    public infix fun or(other: DirectSimulationFlags): DirectSimulationFlags =
        DirectSimulationFlags(bits or other.bits)

    public operator fun contains(other: DirectSimulationFlags): Boolean =
        (bits and other.bits) == other.bits

    override fun toString(): String = "DirectSimulationFlags(0x${bits.toString(16)})"

    public companion object {
        public val NONE: DirectSimulationFlags = DirectSimulationFlags(0)
        public val DISTANCE_ATTENUATION: DirectSimulationFlags = DirectSimulationFlags(1 shl 0)
        public val AIR_ABSORPTION: DirectSimulationFlags = DirectSimulationFlags(1 shl 1)
        public val DIRECTIVITY: DirectSimulationFlags = DirectSimulationFlags(1 shl 2)
        public val OCCLUSION: DirectSimulationFlags = DirectSimulationFlags(1 shl 3)
        public val TRANSMISSION: DirectSimulationFlags = DirectSimulationFlags(1 shl 4)

        public val ALL: DirectSimulationFlags =
            DISTANCE_ATTENUATION or AIR_ABSORPTION or DIRECTIVITY or OCCLUSION or TRANSMISSION
    }
}

/** Which kinds of reflection data a bake should produce. */
@JvmInline
public value class ReflectionsBakeFlags(public val bits: Int) {
    public infix fun or(other: ReflectionsBakeFlags): ReflectionsBakeFlags =
        ReflectionsBakeFlags(bits or other.bits)

    public operator fun contains(other: ReflectionsBakeFlags): Boolean =
        (bits and other.bits) == other.bits

    override fun toString(): String = "ReflectionsBakeFlags(0x${bits.toString(16)})"

    public companion object {
        public val NONE: ReflectionsBakeFlags = ReflectionsBakeFlags(0)

        /** Bake impulse responses for convolution reflections. */
        public val BAKE_CONVOLUTION: ReflectionsBakeFlags = ReflectionsBakeFlags(1 shl 0)

        /** Bake reverb decay times for parametric reflections. */
        public val BAKE_PARAMETRIC: ReflectionsBakeFlags = ReflectionsBakeFlags(1 shl 1)
    }
}
