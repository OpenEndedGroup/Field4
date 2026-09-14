package afield.four.audio.steamaudio

import afield.four.audio.steamaudio.raw.IPLBakedDataIdentifier
import afield.four.audio.steamaudio.raw.IPLProbeGenerationParams
import afield.four.audio.steamaudio.raw.Phonon
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * Names a particular piece of baked data within a [ProbeBatch].
 *
 * A batch can hold several independent bakes -- reverb, one static source, pathing -- and this
 * is the key that distinguishes them.
 */
public data class BakedDataIdentifier(
    /** Which simulation stage the data is for. */
    public val type: BakedDataType,
    /** What the data varies over. */
    public val variation: BakedDataVariation,
    /**
     * The region the endpoint influences.
     *
     * For [BakedDataVariation.STATIC_SOURCE] this is the source position and its radius of
     * influence; for [BakedDataVariation.STATIC_LISTENER], the listener's. Ignored for
     * [BakedDataVariation.REVERB] and [BakedDataVariation.DYNAMIC].
     */
    public val endpointInfluence: Sphere = Sphere(Vector3.ZERO, 0f),
) {
    internal fun writeTo(segment: MemorySegment) {
        IPLBakedDataIdentifier.type(segment, type.value)
        IPLBakedDataIdentifier.variation(segment, variation.value)
        endpointInfluence.writeTo(IPLBakedDataIdentifier.endpointInfluence(segment))
    }

    internal fun allocate(arena: Arena): MemorySegment =
        IPLBakedDataIdentifier.allocate(arena).also(::writeTo)

    public companion object {
        /** The identifier for baked reverb at each probe. */
        public val REVERB: BakedDataIdentifier = BakedDataIdentifier(
            type = BakedDataType.REFLECTIONS,
            variation = BakedDataVariation.REVERB,
        )

        /** The identifier for baked pathing data. */
        public val PATHING: BakedDataIdentifier = BakedDataIdentifier(
            type = BakedDataType.PATHING,
            variation = BakedDataVariation.DYNAMIC,
        )
    }
}

/** How probes should be distributed within a region. */
public data class ProbeGenerationParams(
    /** Placement strategy. */
    public val type: ProbeGenerationType = ProbeGenerationType.UNIFORM_FLOOR,
    /** Horizontal spacing between probes, in metres. For [ProbeGenerationType.UNIFORM_FLOOR]. */
    public val spacing: Float = 2.0f,
    /** Height above the floor at which to place probes, in metres. */
    public val height: Float = 1.5f,
    /**
     * Transform mapping the unit box `[-0.5, 0.5]^3` onto the region to fill with probes.
     *
     * So a room 20 x 4 x 30 metres centred at the origin is a scale of those dimensions.
     */
    public val transform: Matrix4x4 = Matrix4x4.IDENTITY,
) {
    internal fun allocate(arena: Arena): MemorySegment {
        val segment = IPLProbeGenerationParams.allocate(arena)
        IPLProbeGenerationParams.type(segment, type.value)
        IPLProbeGenerationParams.spacing(segment, spacing)
        IPLProbeGenerationParams.height(segment, height)
        transform.writeTo(IPLProbeGenerationParams.transform(segment))
        return segment
    }
}

/**
 * A freshly generated set of probe positions, before they are committed to a [ProbeBatch].
 *
 * Probes are the sample points at which reflections and pathing can be baked. They are only
 * useful for **static** geometry: baked data assumes the surfaces have not moved since the
 * bake. In a space whose planes are moving every frame, simulate live instead -- see
 * [Simulator.runReflections].
 */
public class ProbeArray private constructor(handle: MemorySegment) : SteamAudioObject(handle) {

    override fun releaseAction(): (MemorySegment) -> Unit = Phonon::iplProbeArrayRelease

    override val typeName: String get() = "ProbeArray"

    /** Number of probes generated. */
    public val size: Int
        get() = Phonon.iplProbeArrayGetNumProbes(liveHandle)

    /** The probe at [index], as a sphere of influence. */
    public operator fun get(index: Int): Sphere {
        require(index in 0 until size) { "probe index $index outside 0..<$size" }
        val handle = liveHandle
        Arena.ofConfined().use { arena ->
            return Sphere.readFrom(Phonon.iplProbeArrayGetProbe(arena, handle, index))
        }
    }

    /**
     * Generates probes covering a region of [scene], replacing anything already generated.
     *
     * For [ProbeGenerationType.UNIFORM_FLOOR] this ray-casts downwards to find walkable
     * surfaces, so the scene must be committed first.
     */
    public fun generate(scene: Scene, params: ProbeGenerationParams) {
        val handle = liveHandle
        Arena.ofConfined().use { arena ->
            Phonon.iplProbeArrayGenerateProbes(handle, scene.liveHandle, params.allocate(arena))
        }
    }

    /** All probes, as spheres. */
    public fun toList(): List<Sphere> = (0 until size).map { get(it) }

    /** Increments the reference count and returns an independently closeable wrapper. */
    public fun retain(): ProbeArray = ProbeArray(Phonon.iplProbeArrayRetain(liveHandle))

    public companion object {
        /** Creates an empty probe array. */
        public operator fun invoke(context: SteamAudioContext): ProbeArray {
            val contextHandle = context.liveHandle
            return ProbeArray(
                createHandle("iplProbeArrayCreate") { out ->
                    Phonon.iplProbeArrayCreate(contextHandle, out)
                },
            )
        }
    }
}

/**
 * A committed collection of probes, together with whatever has been baked at them.
 *
 * Batches are what the simulator consumes ([Simulator.addProbeBatch]) and what a bake writes
 * into ([ReflectionsBaker], [PathBaker]). They serialise, so a bake can be done offline and
 * loaded at runtime.
 */
public class ProbeBatch private constructor(handle: MemorySegment) : SteamAudioObject(handle) {

    override fun releaseAction(): (MemorySegment) -> Unit = Phonon::iplProbeBatchRelease

    override val typeName: String get() = "ProbeBatch"

    /** Number of probes in this batch. */
    public val size: Int
        get() = Phonon.iplProbeBatchGetNumProbes(liveHandle)

    /** Adds a single probe. Takes effect on the next [commit]. */
    public fun addProbe(probe: Sphere) {
        val handle = liveHandle
        Arena.ofConfined().use { arena ->
            Phonon.iplProbeBatchAddProbe(handle, probe.allocate(arena))
        }
    }

    /** Adds every probe from [probeArray]. Takes effect on the next [commit]. */
    public fun addProbeArray(probeArray: ProbeArray) {
        Phonon.iplProbeBatchAddProbeArray(liveHandle, probeArray.liveHandle)
    }

    /** Removes the probe at [index]. Takes effect on the next [commit]. */
    public fun removeProbe(index: Int) {
        require(index in 0 until size) { "probe index $index outside 0..<$size" }
        Phonon.iplProbeBatchRemoveProbe(liveHandle, index)
    }

    /** Applies pending probe additions and removals. */
    public fun commit() {
        Phonon.iplProbeBatchCommit(liveHandle)
    }

    /** Discards the baked data identified by [identifier]. */
    public fun removeData(identifier: BakedDataIdentifier) {
        val handle = liveHandle
        Arena.ofConfined().use { arena ->
            Phonon.iplProbeBatchRemoveData(handle, identifier.allocate(arena))
        }
    }

    /** Size in bytes of the baked data identified by [identifier]. */
    public fun dataSize(identifier: BakedDataIdentifier): Long {
        val handle = liveHandle
        Arena.ofConfined().use { arena ->
            return Phonon.iplProbeBatchGetDataSize(handle, identifier.allocate(arena))
        }
    }

    /** Reads the baked energy field at [probeIndex] into [energyField]. */
    public fun getEnergyField(
        identifier: BakedDataIdentifier,
        probeIndex: Int,
        energyField: EnergyField,
    ) {
        val handle = liveHandle
        Arena.ofConfined().use { arena ->
            Phonon.iplProbeBatchGetEnergyField(
                handle,
                identifier.allocate(arena),
                probeIndex,
                energyField.liveHandle,
            )
        }
    }

    /**
     * Reads the baked per-band RT60 reverb times at [probeIndex] into [reverbTimes].
     *
     * [reverbTimes] must have [SteamAudio.NUM_BANDS] entries.
     */
    public fun getReverb(
        identifier: BakedDataIdentifier,
        probeIndex: Int,
        reverbTimes: FloatArray,
    ) {
        require(reverbTimes.size == SteamAudio.NUM_BANDS) {
            "reverbTimes needs ${SteamAudio.NUM_BANDS} entries, got ${reverbTimes.size}"
        }
        val handle = liveHandle
        Arena.ofConfined().use { arena ->
            val out = arena.allocate(ValueLayout.JAVA_FLOAT, SteamAudio.NUM_BANDS.toLong())
            Phonon.iplProbeBatchGetReverb(handle, identifier.allocate(arena), probeIndex, out)
            MemorySegment.copy(out, ValueLayout.JAVA_FLOAT, 0L, reverbTimes, 0, SteamAudio.NUM_BANDS)
        }
    }

    /** Serialises this batch, including baked data, into [into]. */
    public fun save(into: SerializedObject) {
        Phonon.iplProbeBatchSave(liveHandle, into.liveHandle)
    }

    /** Increments the reference count and returns an independently closeable wrapper. */
    public fun retain(): ProbeBatch = ProbeBatch(Phonon.iplProbeBatchRetain(liveHandle))

    public companion object {
        /** Creates an empty probe batch. */
        public operator fun invoke(context: SteamAudioContext): ProbeBatch {
            val contextHandle = context.liveHandle
            return ProbeBatch(
                createHandle("iplProbeBatchCreate") { out ->
                    Phonon.iplProbeBatchCreate(contextHandle, out)
                },
            )
        }

        /** Loads a batch previously written by [save]. */
        public fun load(context: SteamAudioContext, from: SerializedObject): ProbeBatch {
            val contextHandle = context.liveHandle
            return ProbeBatch(
                createHandle("iplProbeBatchLoad") { out ->
                    Phonon.iplProbeBatchLoad(contextHandle, from.liveHandle, out)
                },
            )
        }
    }
}
