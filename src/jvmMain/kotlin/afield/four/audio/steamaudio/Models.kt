package afield.four.audio.steamaudio

import afield.four.audio.steamaudio.raw.IPLAirAbsorptionModel
import afield.four.audio.steamaudio.raw.IPLDirectivity
import afield.four.audio.steamaudio.raw.IPLDistanceAttenuationModel
import afield.four.audio.steamaudio.raw.Phonon
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * How a source's level falls off with distance.
 *
 * Steam Audio's [DEFAULT] model is a physically-motivated inverse-square falloff. The
 * [inverseDistance] variant lets you set the distance below which no further attenuation is
 * applied, which is how you keep a source from becoming painfully loud as the listener walks
 * into it.
 */
public class DistanceAttenuationModel private constructor(
    /** Which model this is. */
    public val type: DistanceAttenuationModelType,
    /** Distance below which attenuation stops increasing, for [inverseDistance]. */
    public val minDistance: Float,
) {

    internal fun allocate(arena: Arena): MemorySegment =
        IPLDistanceAttenuationModel.allocate(arena).also(::writeInto)

    internal fun writeInto(segment: MemorySegment) {
        IPLDistanceAttenuationModel.type(segment, type.value)
        IPLDistanceAttenuationModel.minDistance(segment, minDistance)
        IPLDistanceAttenuationModel.callback(segment, MemorySegment.NULL)
        IPLDistanceAttenuationModel.userData(segment, MemorySegment.NULL)
        // `dirty` tells Steam Audio to re-read a callback-driven curve. With no callback there
        // is nothing to invalidate, so it stays false.
        IPLDistanceAttenuationModel.dirty(segment, 0)
    }

    /**
     * Evaluates this model for a given source and listener position.
     *
     * Returns a linear gain in `0..1`. Feed it to [DirectEffectParams.distanceAttenuation].
     *
     * This allocates a scratch arena, so it is not for the audio thread in a tight loop --
     * either call it on your game-logic thread, or let a [Simulator] compute it.
     */
    public fun calculate(
        context: SteamAudioContext,
        source: Vector3,
        listener: Vector3,
    ): Float {
        val contextHandle = context.liveHandle
        Arena.ofConfined().use { arena ->
            return Phonon.iplDistanceAttenuationCalculate(
                contextHandle,
                source.allocate(arena),
                listener.allocate(arena),
                allocate(arena),
            )
        }
    }

    override fun toString(): String = "DistanceAttenuationModel($type, minDistance=$minDistance)"

    public companion object {
        /** Steam Audio's built-in physics-based falloff. */
        public val DEFAULT: DistanceAttenuationModel =
            DistanceAttenuationModel(DistanceAttenuationModelType.DEFAULT, 1f)

        /**
         * Inverse-distance falloff, clamped so that no extra attenuation is applied closer
         * than [minDistance] metres.
         */
        public fun inverseDistance(minDistance: Float): DistanceAttenuationModel {
            require(minDistance > 0f) { "minDistance must be positive, got $minDistance" }
            return DistanceAttenuationModel(
                DistanceAttenuationModelType.INVERSE_DISTANCE,
                minDistance,
            )
        }
    }
}

/**
 * How much high-frequency energy the air removes over distance.
 *
 * Air absorption is what makes a distant sound dull as well as quiet, and it is a strong
 * distance cue -- more reliable than level alone, since level is confounded by how loud the
 * source is.
 */
public class AirAbsorptionModel private constructor(
    /** Which model this is. */
    public val type: AirAbsorptionModelType,
    /** Per-band exponential coefficients, for [exponential]. */
    private val coefficients: FloatArray,
) {

    internal fun allocate(arena: Arena): MemorySegment =
        IPLAirAbsorptionModel.allocate(arena).also(::writeInto)

    internal fun writeInto(segment: MemorySegment) {
        IPLAirAbsorptionModel.type(segment, type.value)
        MemorySegment.copy(
            coefficients, 0,
            IPLAirAbsorptionModel.coefficients(segment), ValueLayout.JAVA_FLOAT, 0L,
            SteamAudio.NUM_BANDS,
        )
        IPLAirAbsorptionModel.callback(segment, MemorySegment.NULL)
        IPLAirAbsorptionModel.userData(segment, MemorySegment.NULL)
        IPLAirAbsorptionModel.dirty(segment, 0)
    }

    /**
     * Evaluates per-band air absorption gains for a source and listener position.
     *
     * Writes [SteamAudio.NUM_BANDS] linear gains into [destination], which must be that long.
     * Pass the result to [DirectEffectParams.setAirAbsorption].
     */
    public fun calculate(
        context: SteamAudioContext,
        source: Vector3,
        listener: Vector3,
        destination: FloatArray,
    ) {
        require(destination.size == SteamAudio.NUM_BANDS) {
            "destination needs ${SteamAudio.NUM_BANDS} entries, got ${destination.size}"
        }
        val contextHandle = context.liveHandle
        Arena.ofConfined().use { arena ->
            val out = arena.allocate(ValueLayout.JAVA_FLOAT, SteamAudio.NUM_BANDS.toLong())
            Phonon.iplAirAbsorptionCalculate(
                contextHandle,
                source.allocate(arena),
                listener.allocate(arena),
                allocate(arena),
                out,
            )
            MemorySegment.copy(out, ValueLayout.JAVA_FLOAT, 0L, destination, 0, SteamAudio.NUM_BANDS)
        }
    }

    override fun toString(): String = "AirAbsorptionModel($type)"

    public companion object {
        /** Steam Audio's built-in air absorption curve. */
        public val DEFAULT: AirAbsorptionModel =
            AirAbsorptionModel(AirAbsorptionModelType.DEFAULT, FloatArray(SteamAudio.NUM_BANDS))

        /**
         * Exponential absorption with explicit per-band coefficients.
         *
         * Larger coefficients absorb faster. Expect values around `0.0002` for low frequencies
         * rising to `0.007` or so for high, which is roughly what air does; exaggerate them for
         * a mistier, more distance-cued space.
         */
        public fun exponential(coefficients: FloatArray): AirAbsorptionModel {
            require(coefficients.size == SteamAudio.NUM_BANDS) {
                "expected ${SteamAudio.NUM_BANDS} coefficients, got ${coefficients.size}"
            }
            return AirAbsorptionModel(
                AirAbsorptionModelType.EXPONENTIAL,
                coefficients.copyOf(),
            )
        }
    }
}

/**
 * How much a source radiates in a given direction.
 *
 * Modelled as a weighted blend between an omnidirectional pattern and a dipole. [dipoleWeight]
 * of `0` is omnidirectional, `1` is a pure dipole; [dipolePower] sharpens the lobe.
 */
public class Directivity(
    /** Blend between omnidirectional (`0`) and dipole (`1`). */
    public val dipoleWeight: Float = 0f,
    /** How sharply the dipole lobe narrows. `1` is a plain figure-of-eight. */
    public val dipolePower: Float = 1f,
) {
    init {
        require(dipoleWeight in 0f..1f) { "dipoleWeight must be in 0..1, got $dipoleWeight" }
        require(dipolePower > 0f) { "dipolePower must be positive, got $dipolePower" }
    }

    internal fun allocate(arena: Arena): MemorySegment =
        IPLDirectivity.allocate(arena).also(::writeInto)

    internal fun writeInto(segment: MemorySegment) {
        IPLDirectivity.dipoleWeight(segment, dipoleWeight)
        IPLDirectivity.dipolePower(segment, dipolePower)
        IPLDirectivity.callback(segment, MemorySegment.NULL)
        IPLDirectivity.userData(segment, MemorySegment.NULL)
    }

    /**
     * Evaluates the directivity gain for a source with a given orientation and a listener
     * position.
     *
     * Returns a linear gain in `0..1`, for [DirectEffectParams.directivity].
     */
    public fun calculate(
        context: SteamAudioContext,
        source: CoordinateSpace3,
        listener: Vector3,
    ): Float {
        val contextHandle = context.liveHandle
        Arena.ofConfined().use { arena ->
            return Phonon.iplDirectivityCalculate(
                contextHandle,
                source.allocate(arena),
                listener.allocate(arena),
                allocate(arena),
            )
        }
    }

    override fun toString(): String = "Directivity(weight=$dipoleWeight, power=$dipolePower)"

    public companion object {
        /** Radiates equally in all directions. */
        public val OMNIDIRECTIONAL: Directivity = Directivity(dipoleWeight = 0f)

        /** A figure-of-eight pattern, radiating forwards and backwards but not sideways. */
        public val DIPOLE: Directivity = Directivity(dipoleWeight = 1f, dipolePower = 1f)

        /** A forward-biased lobe, roughly like a person speaking. */
        public val CARDIOID_ISH: Directivity = Directivity(dipoleWeight = 0.5f, dipolePower = 2f)
    }
}
