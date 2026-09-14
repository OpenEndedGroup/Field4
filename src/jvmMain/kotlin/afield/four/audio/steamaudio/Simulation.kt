package afield.four.audio.steamaudio

import afield.four.audio.steamaudio.raw.IPLCoordinateSpace3
import afield.four.audio.steamaudio.raw.IPLSimulationInputs
import afield.four.audio.steamaudio.raw.IPLSimulationOutputs
import afield.four.audio.steamaudio.raw.IPLSimulationSettings
import afield.four.audio.steamaudio.raw.IPLSimulationSharedInputs
import afield.four.audio.steamaudio.raw.IPLSourceSettings
import afield.four.audio.steamaudio.raw.IPLVector3
import afield.four.audio.steamaudio.raw.Phonon
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * How the simulator should be configured. Fixed for the simulator's lifetime.
 *
 * The numbers here are the main quality/cost dial for a reverberant space. Notes on the ones
 * that matter most:
 *
 * - [maxDuration] is how long an impulse response the simulator will produce. For a cave, this
 *   is the reverb length you are asking for -- 2 to 4 seconds is a big space. Cost scales with
 *   it, and a [ReflectionEffect] fed by this simulator must be created with at least this
 *   duration.
 * - [maxNumRays] and [numBounces] (the latter set per-frame via [SimulationSharedInputs])
 *   control how thoroughly the space is explored. Sparse rays in a large dark space means the
 *   simulation misses surfaces and the reverb flickers as the listener moves. This is the
 *   setting to raise first if the space sounds unstable.
 * - [maxOrder] is the Ambisonic order of the reflected field. Order 1 is adequate for a
 *   diffuse tail; order 2 sharpens early reflections at 9 channels instead of 4.
 * - [numThreads] should leave a core free for the audio thread.
 */
public data class SimulationSettings(
    /** Which simulation stages this simulator supports. */
    public val flags: SimulationFlags = SimulationFlags.DIRECT or SimulationFlags.REFLECTIONS,
    /** Ray tracer backend. Must match the [Scene] type. */
    public val sceneType: SceneType = SceneType.DEFAULT,
    /** Reflection algorithm. Must match the [ReflectionEffect] type. */
    public val reflectionType: ReflectionEffectType = ReflectionEffectType.HYBRID,
    /** Maximum occlusion samples for volumetric occlusion. */
    public val maxNumOcclusionSamples: Int = 16,
    /** Maximum rays cast per reflection simulation. */
    public val maxNumRays: Int = 4096,
    /** Diffuse samples taken per ray bounce. */
    public val numDiffuseSamples: Int = 1024,
    /** Longest impulse response the simulator will produce, in seconds. */
    public val maxDuration: Float = 2.0f,
    /** Ambisonic order of the reflected field. */
    public val maxOrder: Int = 1,
    /** How many sources may be simulated at once. */
    public val maxNumSources: Int = 8,
    /** Worker threads for reflection simulation. */
    public val numThreads: Int = (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1),
    /** Rays per batch, for the Radeon Rays backend. */
    public val rayBatchSize: Int = 1,
    /** Visibility samples per probe pair, for pathing. */
    public val numVisSamples: Int = 4,
    /** Sampling rate, matching your [AudioSettings]. */
    public val samplingRate: Int = 48_000,
    /** Block size, matching your [AudioSettings]. */
    public val frameSize: Int = 1024,
) {
    init {
        require(maxDuration > 0f) { "maxDuration must be positive, got $maxDuration" }
        require(maxOrder >= 0) { "maxOrder cannot be negative, got $maxOrder" }
        require(maxNumSources > 0) { "maxNumSources must be positive, got $maxNumSources" }
        require(numThreads > 0) { "numThreads must be positive, got $numThreads" }
        require(maxNumRays > 0) { "maxNumRays must be positive, got $maxNumRays" }
    }

    /** Number of Ambisonic channels the reflected field will have. */
    public val reflectionChannelCount: Int get() = SteamAudio.ambisonicChannelCount(maxOrder)

    internal fun allocate(
        arena: Arena,
        openClDevice: OpenClDevice?,
        radeonRaysDevice: RadeonRaysDevice?,
        trueAudioNextDevice: TrueAudioNextDevice?,
    ): MemorySegment {
        val segment = IPLSimulationSettings.allocate(arena)
        IPLSimulationSettings.flags(segment, flags.bits)
        IPLSimulationSettings.sceneType(segment, sceneType.value)
        IPLSimulationSettings.reflectionType(segment, reflectionType.value)
        IPLSimulationSettings.maxNumOcclusionSamples(segment, maxNumOcclusionSamples)
        IPLSimulationSettings.maxNumRays(segment, maxNumRays)
        IPLSimulationSettings.numDiffuseSamples(segment, numDiffuseSamples)
        IPLSimulationSettings.maxDuration(segment, maxDuration)
        IPLSimulationSettings.maxOrder(segment, maxOrder)
        IPLSimulationSettings.maxNumSources(segment, maxNumSources)
        IPLSimulationSettings.numThreads(segment, numThreads)
        IPLSimulationSettings.rayBatchSize(segment, rayBatchSize)
        IPLSimulationSettings.numVisSamples(segment, numVisSamples)
        IPLSimulationSettings.samplingRate(segment, samplingRate)
        IPLSimulationSettings.frameSize(segment, frameSize)
        IPLSimulationSettings.openCLDevice(segment, openClDevice.handleOrNull())
        IPLSimulationSettings.radeonRaysDevice(segment, radeonRaysDevice.handleOrNull())
        IPLSimulationSettings.tanDevice(segment, trueAudioNextDevice.handleOrNull())
        return segment
    }
}

/**
 * Inputs shared by every source in a simulation: where the listener is, and how hard to work.
 *
 * A mutable native view, so updating it per frame allocates nothing.
 */
public class SimulationSharedInputs private constructor(
    internal val segment: MemorySegment,
    /** Arena used for the pathing-visualisation upcall stub, when one is installed. */
    private val stubArena: Arena,
) {

    /** Allocates a shared-inputs block from [arena]. */
    public constructor(arena: Arena) : this(IPLSimulationSharedInputs.allocate(arena), arena) {
        listener = CoordinateSpace3.IDENTITY
        numRays = 4096
        numBounces = 16
        duration = 2.0f
        order = 1
        irradianceMinDistance = 1.0f
        IPLSimulationSharedInputs.pathingVisCallback(segment, MemorySegment.NULL)
        IPLSimulationSharedInputs.pathingUserData(segment, MemorySegment.NULL)
    }

    /** The listener's position and orientation in world space. */
    public var listener: CoordinateSpace3
        get() = CoordinateSpace3.readFrom(IPLSimulationSharedInputs.listener(segment))
        set(value) = value.writeTo(IPLSimulationSharedInputs.listener(segment))

    /**
     * Sets just the listener's position, leaving the orientation alone.
     *
     * Avoids constructing a [CoordinateSpace3] when only the position has moved.
     */
    public fun setListenerPosition(x: Float, y: Float, z: Float) {
        val origin = IPLCoordinateSpace3.origin(IPLSimulationSharedInputs.listener(segment))
        IPLVector3.x(origin, x)
        IPLVector3.y(origin, y)
        IPLVector3.z(origin, z)
    }

    /**
     * Rays cast per reflection simulation. Must not exceed the simulator's `maxNumRays`.
     *
     * In a large, mostly empty space, too few rays means surfaces get missed and the reverb
     * wobbles frame to frame. Raise this before anything else if the space sounds unstable.
     */
    public var numRays: Int
        get() = IPLSimulationSharedInputs.numRays(segment)
        set(value) = IPLSimulationSharedInputs.numRays(segment, value)

    /**
     * How many times each ray may bounce.
     *
     * A reverberant cave needs a lot of bounces -- the late tail *is* the high-order
     * reflections. Values in the tens are normal for a live space; a handful will sound dead.
     */
    public var numBounces: Int
        get() = IPLSimulationSharedInputs.numBounces(segment)
        set(value) = IPLSimulationSharedInputs.numBounces(segment, value)

    /** Impulse response duration to simulate, in seconds. Capped by the simulator's maxDuration. */
    public var duration: Float
        get() = IPLSimulationSharedInputs.duration(segment)
        set(value) = IPLSimulationSharedInputs.duration(segment, value)

    /** Ambisonic order to simulate. Capped by the simulator's maxOrder. */
    public var order: Int
        get() = IPLSimulationSharedInputs.order(segment)
        set(value) = IPLSimulationSharedInputs.order(segment, value)

    /**
     * Minimum distance used when accumulating irradiance, which stops a ray that grazes a
     * surface from contributing an unbounded amount of energy.
     */
    public var irradianceMinDistance: Float
        get() = IPLSimulationSharedInputs.irradianceMinDistance(segment)
        set(value) = IPLSimulationSharedInputs.irradianceMinDistance(segment, value)

    /**
     * Installs a callback that receives every probe-to-probe segment the pathing search
     * examines, or clears it when [callback] is null.
     *
     * The stub lives in the arena this object was constructed with, so that arena must outlive
     * any simulation run using these inputs. Intended for debugging -- see
     * [PathingVisualizationCallback] for why it is worth wiring up in a 2D game, and for the
     * constraints on what the callback may do.
     */
    public fun setPathingVisualizationCallback(callback: PathingVisualizationCallback?) {
        if (callback == null) {
            IPLSimulationSharedInputs.pathingVisCallback(segment, MemorySegment.NULL)
        } else {
            IPLSimulationSharedInputs.pathingVisCallback(
                segment,
                PathingVisualizationCallbacks.stub(callback, stubArena),
            )
        }
        IPLSimulationSharedInputs.pathingUserData(segment, MemorySegment.NULL)
    }
}

/** Per-source simulation inputs. A mutable native view. */
public class SimulationInputs private constructor(
    internal val segment: MemorySegment,
) {

    /** Allocates a per-source inputs block from [arena]. */
    public constructor(
        arena: Arena,
        flags: SimulationFlags = SimulationFlags.DIRECT or SimulationFlags.REFLECTIONS,
        directFlags: DirectSimulationFlags = DirectSimulationFlags.DISTANCE_ATTENUATION or
            DirectSimulationFlags.AIR_ABSORPTION,
    ) : this(IPLSimulationInputs.allocate(arena)) {
        this.flags = flags
        this.directFlags = directFlags
        source = CoordinateSpace3.IDENTITY
        occlusionType = OcclusionType.RAYCAST
        occlusionRadius = 1.0f
        numOcclusionSamples = 16
        numTransmissionRays = 1
        hybridReverbTransitionTime = 0.4f
        hybridReverbOverlapPercent = 0.25f
        baked = false
        enableValidation = false
        findAlternatePaths = false
        visRadius = 1.0f
        visThreshold = 0.1f
        visRange = 50.0f
        pathingOrder = 1
        for (band in 0 until SteamAudio.NUM_BANDS) setReverbScale(band, 1f)

        // Models default to Steam Audio's own; callers can override.
        DistanceAttenuationModel.DEFAULT.writeInto(IPLSimulationInputs.distanceAttenuationModel(segment))
        AirAbsorptionModel.DEFAULT.writeInto(IPLSimulationInputs.airAbsorptionModel(segment))
        Directivity.OMNIDIRECTIONAL.writeInto(IPLSimulationInputs.directivity(segment))
        IPLSimulationInputs.pathingProbes(segment, MemorySegment.NULL)
        IPLSimulationInputs.deviationModel(segment, MemorySegment.NULL)
    }

    /** Which simulation stages to run for this source. */
    public var flags: SimulationFlags
        get() = SimulationFlags(IPLSimulationInputs.flags(segment))
        set(value) = IPLSimulationInputs.flags(segment, value.bits)

    /** Which direct-path quantities to compute. */
    public var directFlags: DirectSimulationFlags
        get() = DirectSimulationFlags(IPLSimulationInputs.directFlags(segment))
        set(value) = IPLSimulationInputs.directFlags(segment, value.bits)

    /** The source's position and orientation in world space. */
    public var source: CoordinateSpace3
        get() = CoordinateSpace3.readFrom(IPLSimulationInputs.source(segment))
        set(value) = value.writeTo(IPLSimulationInputs.source(segment))

    /** Sets just the source position, leaving orientation alone and allocating nothing. */
    public fun setSourcePosition(x: Float, y: Float, z: Float) {
        val origin = IPLCoordinateSpace3.origin(IPLSimulationInputs.source(segment))
        IPLVector3.x(origin, x)
        IPLVector3.y(origin, y)
        IPLVector3.z(origin, z)
    }

    /** How occlusion is sampled. */
    public var occlusionType: OcclusionType
        get() = OcclusionType.from(IPLSimulationInputs.occlusionType(segment))
        set(value) = IPLSimulationInputs.occlusionType(segment, value.value)

    /** Radius of the source sphere, for [OcclusionType.VOLUMETRIC]. */
    public var occlusionRadius: Float
        get() = IPLSimulationInputs.occlusionRadius(segment)
        set(value) = IPLSimulationInputs.occlusionRadius(segment, value)

    /** Rays used for volumetric occlusion. */
    public var numOcclusionSamples: Int
        get() = IPLSimulationInputs.numOcclusionSamples(segment)
        set(value) = IPLSimulationInputs.numOcclusionSamples(segment, value)

    /** Rays used to estimate transmission through geometry. */
    public var numTransmissionRays: Int
        get() = IPLSimulationInputs.numTransmissionRays(segment)
        set(value) = IPLSimulationInputs.numTransmissionRays(segment, value)

    /** Per-band scaling applied to the simulated reverb time. */
    public fun setReverbScale(band: Int, scale: Float) {
        if (band !in 0 until SteamAudio.NUM_BANDS) {
            throw IndexOutOfBoundsException("band $band outside 0..<${SteamAudio.NUM_BANDS}")
        }
        IPLSimulationInputs.reverbScale(segment)
            .setAtIndex(ValueLayout.JAVA_FLOAT, band.toLong(), scale)
    }

    /**
     * Where the hybrid reflection type switches from convolution to parametric, in seconds.
     *
     * Earlier means cheaper and less accurate. The early reflections carry the spatial
     * impression of the room, so this wants to be long enough to include them -- a few hundred
     * milliseconds in a large space.
     */
    public var hybridReverbTransitionTime: Float
        get() = IPLSimulationInputs.hybridReverbTransitionTime(segment)
        set(value) = IPLSimulationInputs.hybridReverbTransitionTime(segment, value)

    /** How much the convolution and parametric halves overlap, as a fraction. */
    public var hybridReverbOverlapPercent: Float
        get() = IPLSimulationInputs.hybridReverbOverlapPercent(segment)
        set(value) = IPLSimulationInputs.hybridReverbOverlapPercent(segment, value)

    /** Whether to use baked data rather than simulating live. */
    public var baked: Boolean
        get() = IPLSimulationInputs.baked(segment) != 0
        set(value) = IPLSimulationInputs.baked(segment, if (value) 1 else 0)

    /** Extra validation of pathing results. Costs performance. */
    public var enableValidation: Boolean
        get() = IPLSimulationInputs.enableValidation(segment) != 0
        set(value) = IPLSimulationInputs.enableValidation(segment, if (value) 1 else 0)

    /** Whether pathing should look for alternative routes when the direct path is blocked. */
    public var findAlternatePaths: Boolean
        get() = IPLSimulationInputs.findAlternatePaths(segment) != 0
        set(value) = IPLSimulationInputs.findAlternatePaths(segment, if (value) 1 else 0)

    /** Probe visibility test radius, for pathing. */
    public var visRadius: Float
        get() = IPLSimulationInputs.visRadius(segment)
        set(value) = IPLSimulationInputs.visRadius(segment, value)

    /** Fraction of visibility rays that must pass for two probes to count as mutually visible. */
    public var visThreshold: Float
        get() = IPLSimulationInputs.visThreshold(segment)
        set(value) = IPLSimulationInputs.visThreshold(segment, value)

    /** Maximum distance over which probe visibility is tested. */
    public var visRange: Float
        get() = IPLSimulationInputs.visRange(segment)
        set(value) = IPLSimulationInputs.visRange(segment, value)

    /** Ambisonic order used for pathing. */
    public var pathingOrder: Int
        get() = IPLSimulationInputs.pathingOrder(segment)
        set(value) = IPLSimulationInputs.pathingOrder(segment, value)

    /** The distance attenuation model to use for this source. */
    public fun setDistanceAttenuationModel(model: DistanceAttenuationModel) {
        model.writeInto(IPLSimulationInputs.distanceAttenuationModel(segment))
    }

    /** The air absorption model to use for this source. */
    public fun setAirAbsorptionModel(model: AirAbsorptionModel) {
        model.writeInto(IPLSimulationInputs.airAbsorptionModel(segment))
    }

    /** The source's directivity pattern. */
    public fun setDirectivity(directivity: Directivity) {
        directivity.writeInto(IPLSimulationInputs.directivity(segment))
    }

    /** The probe batch to use for pathing, or `null` for none. */
    public fun setPathingProbes(probeBatch: ProbeBatch?) {
        IPLSimulationInputs.pathingProbes(segment, probeBatch.handleOrNull())
    }

    /** The baked data to look up when [baked] is set. */
    public fun setBakedDataIdentifier(identifier: BakedDataIdentifier) {
        identifier.writeTo(IPLSimulationInputs.bakedDataIdentifier(segment))
    }
}

/**
 * Simulation results for one source.
 *
 * Read with [Source.getOutputs]; the fields are then handed to the matching effects. The direct
 * and reflection blocks are laid out exactly as `IPLDirectEffectParams` and
 * `IPLReflectionEffectParams`, so moving them across is a struct copy rather than a
 * field-by-field transcription -- see [DirectEffectParams.copyFrom] and
 * [ReflectionEffectParams.copySimulatedFrom].
 */
public class SimulationOutputs private constructor(
    internal val segment: MemorySegment,
) {

    /** Allocates an outputs block from [arena]. */
    public constructor(arena: Arena) : this(IPLSimulationOutputs.allocate(arena))

    /** The direct-path result, shaped as `IPLDirectEffectParams`. */
    internal val directSegment: MemorySegment get() = IPLSimulationOutputs.direct(segment)

    /** The reflections result, shaped as `IPLReflectionEffectParams`. */
    internal val reflectionsSegment: MemorySegment get() = IPLSimulationOutputs.reflections(segment)

    /** The pathing result, shaped as `IPLPathEffectParams`. */
    internal val pathingSegment: MemorySegment get() = IPLSimulationOutputs.pathing(segment)

    /** Copies the simulated direct-path parameters into [params]. */
    public fun copyDirectInto(params: DirectEffectParams) {
        params.segment.copyFrom(directSegment)
    }

    /** Simulated distance attenuation, as a linear gain. */
    public val distanceAttenuation: Float
        get() = afield.four.audio.steamaudio.raw.IPLDirectEffectParams.distanceAttenuation(directSegment)

    /** Simulated occlusion: `1` clear, `0` fully blocked. */
    public val occlusion: Float
        get() = afield.four.audio.steamaudio.raw.IPLDirectEffectParams.occlusion(directSegment)

    /** Simulated per-band air absorption gain. */
    public fun airAbsorption(band: Int): Float =
        afield.four.audio.steamaudio.raw.IPLDirectEffectParams.airAbsorption(directSegment)
            .getAtIndex(ValueLayout.JAVA_FLOAT, band.toLong())

    /** Simulated per-band RT60 reverb decay time, in seconds. */
    public fun reverbTime(band: Int): Float =
        afield.four.audio.steamaudio.raw.IPLReflectionEffectParams.reverbTimes(reflectionsSegment)
            .getAtIndex(ValueLayout.JAVA_FLOAT, band.toLong())
}

/**
 * Runs acoustic simulation: works out what happens to sound travelling from each source to the
 * listener through the [Scene].
 *
 * ### Threads -- read this before wiring it up
 *
 * The simulator splits cleanly into two halves, and mixing them up is the single easiest way
 * to ruin the audio:
 *
 * | | Where it runs | Cost |
 * |---|---|---|
 * | [runDirect] | game thread or simulation thread | cheap; a few ray casts |
 * | [runReflections] | **its own thread, never the audio thread** | very expensive; milliseconds to tens of milliseconds |
 * | [runPathing] | **its own thread** | expensive |
 * | [Source.getOutputs] | anywhere | cheap; reads the last result |
 * | [commit] / [Scene.commit] | simulation thread, not concurrently with a run | expensive |
 *
 * [runReflections] casts thousands of rays across several worker threads and blocks until
 * done. Calling it from an audio callback will underrun. The intended arrangement is a
 * dedicated simulation thread running at some low rate -- 10 to 30 Hz is plenty, since reverb
 * changes slowly -- with the audio thread reading whatever the last completed result was:
 *
 * ```
 * // simulation thread, ~15 Hz:
 * simulator.setSharedInputs(flags, sharedInputs)
 * for (s in sources) s.setInputs(flags, s.inputs)
 * simulator.runReflections()            // blocks here
 *
 * // audio thread, every block:
 * source.getOutputs(SimulationFlags.REFLECTIONS, outputs)   // cheap, non-blocking
 * reflectionParams.copySimulatedFrom(outputs)
 * reflectionEffect.apply(reflectionParams, dry, ambisonic)
 * ```
 *
 * ### Commit
 *
 * [commit] applies pending source additions and removals. Like [Scene.commit], it must not
 * overlap a run against the same simulator.
 */
public class Simulator private constructor(
    handle: MemorySegment,
    /** The settings this simulator was created with. */
    public val settings: SimulationSettings,
) : SteamAudioObject(handle) {

    override fun releaseAction(): (MemorySegment) -> Unit = Phonon::iplSimulatorRelease

    override val typeName: String get() = "Simulator"

    /**
     * Sets the scene to simulate against. Takes effect on the next [commit].
     *
     * The scene is not retained, so keep it alive for as long as the simulator uses it.
     */
    public fun setScene(scene: Scene) {
        Phonon.iplSimulatorSetScene(liveHandle, scene.liveHandle)
    }

    /** Adds a probe batch, for baked reflections or pathing. Takes effect on the next [commit]. */
    public fun addProbeBatch(probeBatch: ProbeBatch) {
        Phonon.iplSimulatorAddProbeBatch(liveHandle, probeBatch.liveHandle)
    }

    /** Removes a probe batch. Takes effect on the next [commit]. */
    public fun removeProbeBatch(probeBatch: ProbeBatch) {
        Phonon.iplSimulatorRemoveProbeBatch(liveHandle, probeBatch.liveHandle)
    }

    /** Publishes listener position and ray budget for the next run. */
    public fun setSharedInputs(flags: SimulationFlags, inputs: SimulationSharedInputs) {
        Phonon.iplSimulatorSetSharedInputs(liveHandle, flags.bits, inputs.segment)
    }

    /**
     * Applies pending scene, source and probe-batch changes.
     *
     * Must not run concurrently with [runReflections] or [runPathing] on the same simulator.
     */
    public fun commit() {
        Phonon.iplSimulatorCommit(liveHandle)
    }

    /**
     * Runs direct-path simulation: occlusion, transmission, distance attenuation, directivity.
     *
     * Cheap enough to run every frame on the game thread.
     */
    public fun runDirect() {
        Phonon.iplSimulatorRunDirect(liveHandle)
    }

    /**
     * Runs reflection simulation. **Blocking and expensive -- never call this from the audio
     * thread.**
     *
     * See the threading table on this class.
     */
    public fun runReflections() {
        Phonon.iplSimulatorRunReflections(liveHandle)
    }

    /** Runs pathing simulation. Expensive; same threading rules as [runReflections]. */
    public fun runPathing() {
        Phonon.iplSimulatorRunPathing(liveHandle)
    }

    /** Increments the reference count and returns an independently closeable wrapper. */
    public fun retain(): Simulator = Simulator(Phonon.iplSimulatorRetain(liveHandle), settings)

    public companion object {
        /** Creates a simulator. */
        public operator fun invoke(
            context: SteamAudioContext,
            settings: SimulationSettings,
            openClDevice: OpenClDevice? = null,
            radeonRaysDevice: RadeonRaysDevice? = null,
            trueAudioNextDevice: TrueAudioNextDevice? = null,
        ): Simulator {
            val contextHandle = context.liveHandle
            Arena.ofConfined().use { arena ->
                val native = settings.allocate(
                    arena,
                    openClDevice,
                    radeonRaysDevice,
                    trueAudioNextDevice,
                )
                return Simulator(
                    createHandle("iplSimulatorCreate") { out ->
                        Phonon.iplSimulatorCreate(contextHandle, native, out)
                    },
                    settings,
                )
            }
        }
    }
}

/**
 * One sound source within a [Simulator].
 *
 * A source is a simulation participant, not an audio stream: it carries position, directivity
 * and occlusion settings in, and simulation results out. The audio itself flows through the
 * effects you drive from those results.
 */
public class Source private constructor(handle: MemorySegment) : SteamAudioObject(handle) {

    override fun releaseAction(): (MemorySegment) -> Unit = Phonon::iplSourceRelease

    override val typeName: String get() = "Source"

    /** Adds this source to [simulator]. Takes effect on the next [Simulator.commit]. */
    public fun addTo(simulator: Simulator) {
        Phonon.iplSourceAdd(liveHandle, simulator.liveHandle)
    }

    /** Removes this source from [simulator]. Takes effect on the next [Simulator.commit]. */
    public fun removeFrom(simulator: Simulator) {
        Phonon.iplSourceRemove(liveHandle, simulator.liveHandle)
    }

    /** Publishes this source's inputs for the next simulation run. */
    public fun setInputs(flags: SimulationFlags, inputs: SimulationInputs) {
        Phonon.iplSourceSetInputs(liveHandle, flags.bits, inputs.segment)
    }

    /**
     * Reads the most recent simulation results into [outputs].
     *
     * Cheap and non-blocking -- it copies whatever the last completed run produced -- so this
     * is safe to call from the audio thread every block.
     */
    public fun getOutputs(flags: SimulationFlags, outputs: SimulationOutputs) {
        Phonon.iplSourceGetOutputs(liveHandle, flags.bits, outputs.segment)
    }

    /** Increments the reference count and returns an independently closeable wrapper. */
    public fun retain(): Source = Source(Phonon.iplSourceRetain(liveHandle))

    public companion object {
        /**
         * Creates a source in [simulator].
         *
         * @param flags which simulation stages this source takes part in. Must be a subset of
         *   the simulator's own flags.
         */
        public operator fun invoke(
            simulator: Simulator,
            flags: SimulationFlags = SimulationFlags.DIRECT or SimulationFlags.REFLECTIONS,
        ): Source {
            val simulatorHandle = simulator.liveHandle
            Arena.ofConfined().use { arena ->
                val settings = IPLSourceSettings.allocate(arena)
                IPLSourceSettings.flags(settings, flags.bits)
                return Source(
                    createHandle("iplSourceCreate") { out ->
                        Phonon.iplSourceCreate(simulatorHandle, settings, out)
                    },
                )
            }
        }
    }
}
