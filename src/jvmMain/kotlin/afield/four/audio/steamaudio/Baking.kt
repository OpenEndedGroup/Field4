package afield.four.audio.steamaudio

import afield.four.audio.steamaudio.raw.IPLPathBakeParams
import afield.four.audio.steamaudio.raw.IPLReflectionsBakeParams
import afield.four.audio.steamaudio.raw.Phonon
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

/**
 * Precomputes the probe-to-probe visibility graph that [PathEffect] searches at runtime.
 *
 * ### Why pathing needs this
 *
 * Finding a route for sound around a corner means answering "which points in this space can see
 * which other points". Doing that from scratch every frame would be far more expensive than the
 * reflection simulation. So Steam Audio precomputes it: for every pair of probes within range,
 * it casts rays and records whether they are mutually visible and how far apart they are. At
 * runtime, pathing is then a graph search over that baked data, which is cheap.
 *
 * The consequence is the important part: **the graph is baked against the geometry as it stood
 * at bake time.** Pathing will happily route sound through a doorway that has since been
 * shut by a moving wall, and will not find the route through one that has since opened. If a
 * moving segment changes connectivity in a way that matters, the options are to re-bake that
 * area (not a per-frame operation), to bake with the door open and lean on occlusion and
 * transmission for the closed case, or to keep connectivity-changing geometry out of the
 * pathing graph and let reflections carry it.
 *
 * ### Baking is a tool-time operation
 *
 * [bake] blocks, takes seconds to minutes depending on probe count, and spreads over
 * [PathBakeSettings.numThreads] threads. Run it when building an area -- offline, or at level
 * load -- then [ProbeBatch.save] the result and load it thereafter. Do not bake during play.
 *
 * ```
 * // Tool time:
 * val probes = ProbeArray(context)
 * probes.generate(scene, ProbeGenerationParams(spacing = 3f, transform = areaTransform))
 * val batch = ProbeBatch(context)
 * batch.addProbeArray(probes)
 * batch.commit()
 * PathBaker.bake(context, PathBakeSettings(scene, batch)) { progress -> report(progress) }
 * SerializedObject(context).use { blob ->
 *     batch.save(blob)
 *     Files.write(path, blob.toByteArray())
 * }
 *
 * // Run time:
 * val batch = ProbeBatch.load(context, SerializedObject(context, Files.readAllBytes(path)))
 * simulator.addProbeBatch(batch)
 * simulator.commit()
 * inputs.setPathingProbes(batch)
 * ```
 */
public data class PathBakeSettings(
    /** The scene to bake against. Must be committed. */
    public val scene: Scene,
    /** The probe batch to record visibility into. Must be committed. */
    public val probeBatch: ProbeBatch,
    /** Which slot in the batch the data is stored under. */
    public val identifier: BakedDataIdentifier = BakedDataIdentifier.PATHING,
    /**
     * Rays cast per probe pair to decide mutual visibility.
     *
     * More samples make the visibility test less sensitive to a ray slipping past a wall edge.
     */
    public val numSamples: Int = 1,
    /**
     * Radius around each probe that counts as visible, in metres.
     *
     * Treating a probe as a small sphere rather than a point stops a probe tucked against a
     * wall from being judged invisible to everything.
     */
    public val radius: Float = 1.0f,
    /**
     * Fraction of sample rays that must get through for two probes to count as visible.
     *
     * Raising it demands a clearer line of sight, which makes paths hug the actual openings.
     */
    public val threshold: Float = 0.1f,
    /** Maximum distance over which mutual visibility is tested, in metres. */
    public val visRange: Float = 50.0f,
    /**
     * Maximum path length recorded, in metres.
     *
     * Paths longer than this are not stored, which bounds both bake time and data size. It
     * should comfortably exceed the longest route a listener could plausibly hear around.
     */
    public val pathRange: Float = 100.0f,
    /** Worker threads to spread the bake across. */
    public val numThreads: Int = (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1),
) {
    init {
        require(numSamples > 0) { "numSamples must be positive, got $numSamples" }
        require(radius > 0f) { "radius must be positive, got $radius" }
        require(threshold in 0f..1f) { "threshold must be in 0..1, got $threshold" }
        require(visRange > 0f) { "visRange must be positive, got $visRange" }
        require(pathRange > 0f) { "pathRange must be positive, got $pathRange" }
        require(numThreads > 0) { "numThreads must be positive, got $numThreads" }
    }

    internal fun allocate(arena: Arena): MemorySegment {
        val segment = IPLPathBakeParams.allocate(arena)
        IPLPathBakeParams.scene(segment, scene.liveHandle)
        IPLPathBakeParams.probeBatch(segment, probeBatch.liveHandle)
        identifier.writeTo(IPLPathBakeParams.identifier(segment))
        IPLPathBakeParams.numSamples(segment, numSamples)
        IPLPathBakeParams.radius(segment, radius)
        IPLPathBakeParams.threshold(segment, threshold)
        IPLPathBakeParams.visRange(segment, visRange)
        IPLPathBakeParams.pathRange(segment, pathRange)
        IPLPathBakeParams.numThreads(segment, numThreads)
        return segment
    }
}

/** Bakes the pathing visibility graph. See [PathBakeSettings]. */
public object PathBaker {

    /**
     * Bakes pathing data into [settings]'s probe batch. **Blocks** until done or cancelled.
     *
     * @param progress called from the baking threads with a fraction in `0..1`. Must be
     *   thread-safe; exceptions it throws are swallowed at the FFM boundary.
     */
    public fun bake(
        context: SteamAudioContext,
        settings: PathBakeSettings,
        progress: ProgressCallback? = null,
    ) {
        val contextHandle = context.liveHandle
        Arena.ofConfined().use { arena ->
            val stub = progress?.let { ProgressCallbacks.stub(it, arena) } ?: MemorySegment.NULL
            Phonon.iplPathBakerBake(
                contextHandle,
                settings.allocate(arena),
                stub,
                MemorySegment.NULL,
            )
        }
    }

    /**
     * Asks an in-progress bake to stop.
     *
     * Call from another thread: [bake] is blocking, so the thread running it cannot cancel
     * itself. The blocked [bake] returns once the workers notice.
     */
    public fun cancel(context: SteamAudioContext) {
        Phonon.iplPathBakerCancelBake(context.liveHandle)
    }
}

/**
 * Precomputes reflection data -- impulse responses, or reverb decay times -- at each probe.
 *
 * Baked reflections trade accuracy for cost: instead of simulating live, the runtime
 * interpolates between what was recorded at nearby probes. That is the right trade for a
 * **static** space, and the wrong one for geometry that moves, since the bake bakes in the
 * positions the walls had.
 *
 * For a space whose walls move, simulate reflections live with [Simulator.runReflections]
 * instead. Baking remains useful for a static outer shell, or for areas the player cannot
 * rearrange.
 */
public data class ReflectionsBakeSettings(
    /** The scene to bake against. Must be committed. */
    public val scene: Scene,
    /** The probe batch to record into. Must be committed. */
    public val probeBatch: ProbeBatch,
    /** Which slot in the batch the data is stored under. */
    public val identifier: BakedDataIdentifier = BakedDataIdentifier.REVERB,
    /** Ray tracer backend; must match [scene]'s type. */
    public val sceneType: SceneType = SceneType.DEFAULT,
    /** Whether to bake impulse responses, decay times, or both. */
    public val bakeFlags: ReflectionsBakeFlags = ReflectionsBakeFlags.BAKE_PARAMETRIC,
    /** Rays cast per probe. */
    public val numRays: Int = 8192,
    /** Diffuse samples taken per bounce. */
    public val numDiffuseSamples: Int = 1024,
    /** Bounces per ray. */
    public val numBounces: Int = 32,
    /** Length of impulse response to simulate, in seconds. */
    public val simulatedDuration: Float = 2.0f,
    /**
     * Length of impulse response to store, in seconds.
     *
     * Can be shorter than [simulatedDuration]: simulating a long tail and storing a short one
     * gives an accurate early response without the storage cost of the whole decay.
     */
    public val savedDuration: Float = 2.0f,
    /** Ambisonic order to bake at. */
    public val order: Int = 1,
    /** Worker threads. */
    public val numThreads: Int = (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1),
    /** Rays per batch, for the Radeon Rays backend. */
    public val rayBatchSize: Int = 1,
    /** Minimum distance used when accumulating irradiance, bounding grazing-ray energy. */
    public val irradianceMinDistance: Float = 1.0f,
    /** Probes baked per batch, trading memory against scheduling overhead. */
    public val bakeBatchSize: Int = 1,
    /** OpenCL device, for GPU-backed baking. */
    public val openClDevice: OpenClDevice? = null,
    /** Radeon Rays device, for GPU-backed baking. */
    public val radeonRaysDevice: RadeonRaysDevice? = null,
) {
    init {
        require(numRays > 0) { "numRays must be positive, got $numRays" }
        require(numBounces > 0) { "numBounces must be positive, got $numBounces" }
        require(simulatedDuration > 0f) { "simulatedDuration must be positive" }
        require(savedDuration > 0f) { "savedDuration must be positive" }
        require(savedDuration <= simulatedDuration) {
            "savedDuration ($savedDuration) cannot exceed simulatedDuration ($simulatedDuration)"
        }
        require(order >= 0) { "order cannot be negative, got $order" }
        require(numThreads > 0) { "numThreads must be positive, got $numThreads" }
    }

    internal fun allocate(arena: Arena): MemorySegment {
        val segment = IPLReflectionsBakeParams.allocate(arena)
        IPLReflectionsBakeParams.scene(segment, scene.liveHandle)
        IPLReflectionsBakeParams.probeBatch(segment, probeBatch.liveHandle)
        IPLReflectionsBakeParams.sceneType(segment, sceneType.value)
        identifier.writeTo(IPLReflectionsBakeParams.identifier(segment))
        IPLReflectionsBakeParams.bakeFlags(segment, bakeFlags.bits)
        IPLReflectionsBakeParams.numRays(segment, numRays)
        IPLReflectionsBakeParams.numDiffuseSamples(segment, numDiffuseSamples)
        IPLReflectionsBakeParams.numBounces(segment, numBounces)
        IPLReflectionsBakeParams.simulatedDuration(segment, simulatedDuration)
        IPLReflectionsBakeParams.savedDuration(segment, savedDuration)
        IPLReflectionsBakeParams.order(segment, order)
        IPLReflectionsBakeParams.numThreads(segment, numThreads)
        IPLReflectionsBakeParams.rayBatchSize(segment, rayBatchSize)
        IPLReflectionsBakeParams.irradianceMinDistance(segment, irradianceMinDistance)
        IPLReflectionsBakeParams.bakeBatchSize(segment, bakeBatchSize)
        IPLReflectionsBakeParams.openCLDevice(segment, openClDevice.handleOrNull())
        IPLReflectionsBakeParams.radeonRaysDevice(segment, radeonRaysDevice.handleOrNull())
        return segment
    }
}

/** Bakes reflection data. See [ReflectionsBakeSettings]. */
public object ReflectionsBaker {

    /**
     * Bakes reflection data into [settings]'s probe batch. **Blocks** until done or cancelled.
     *
     * Expensive -- this is a tool-time operation, not a runtime one.
     */
    public fun bake(
        context: SteamAudioContext,
        settings: ReflectionsBakeSettings,
        progress: ProgressCallback? = null,
    ) {
        val contextHandle = context.liveHandle
        Arena.ofConfined().use { arena ->
            val stub = progress?.let { ProgressCallbacks.stub(it, arena) } ?: MemorySegment.NULL
            Phonon.iplReflectionsBakerBake(
                contextHandle,
                settings.allocate(arena),
                stub,
                MemorySegment.NULL,
            )
        }
    }

    /** Asks an in-progress bake to stop. Call from another thread; see [PathBaker.cancel]. */
    public fun cancel(context: SteamAudioContext) {
        Phonon.iplReflectionsBakerCancelBake(context.liveHandle)
    }
}
