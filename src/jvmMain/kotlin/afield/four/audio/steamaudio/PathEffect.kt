package afield.four.audio.steamaudio

import afield.four.audio.steamaudio.raw.IPLPathEffectParams
import afield.four.audio.steamaudio.raw.IPLPathEffectSettings
import afield.four.audio.steamaudio.raw.Phonon
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * Per-block parameters for a [PathEffect].
 *
 * A [Simulator] fills in the two fields that describe the *paths it found* --
 * [eqCoefficient]s and the spherical-harmonic coefficients -- while the application owns
 * [order], [binaural], [hrtf], [listener] and [normalizeEq], which describe how you want those
 * paths rendered. [copySimulatedFrom] moves the former across while preserving the latter.
 *
 * ### The shCoeffs pointer
 *
 * The simulated directional distribution arrives as a raw `IPLfloat32*` pointing into memory
 * the **simulator owns**, not a copy. Two consequences:
 *
 *  - The coefficients are only valid until the next [Simulator.runPathing] overwrites them. In
 *    the usual arrangement -- simulation on its own thread, audio reading per block -- that is
 *    a benign race for a slowly-changing quantity, and it is what Steam Audio's own
 *    integrations do. If you need a hard guarantee, double-buffer by snapshotting with
 *    [copyShCoefficientsFrom].
 *  - They are in **world space** and must be rotated into the listener's frame. When
 *    [PathEffect.spatializes] is true the effect does that for you from [listener]; when it is
 *    false you must rotate the Ambisonic output yourself with an [AmbisonicsRotationEffect].
 */
public class PathEffectParams private constructor(
    internal val segment: MemorySegment,
    /** Optional caller-owned storage used by [copyShCoefficientsFrom]. */
    private val shSnapshot: MemorySegment,
) {

    /**
     * Allocates a parameter block from [arena].
     *
     * @param order Ambisonic order to render at. May be below the effect's maximum, which
     *   reduces cost.
     * @param snapshotShCoefficients reserve room for [order]'s worth of coefficients so
     *   [copyShCoefficientsFrom] can take a private copy of the simulator's output. Only needed
     *   if you want to decouple the audio thread from the simulation thread entirely.
     */
    public constructor(
        arena: Arena,
        order: Int = 1,
        snapshotShCoefficients: Boolean = false,
    ) : this(
        segment = IPLPathEffectParams.allocate(arena),
        shSnapshot = if (snapshotShCoefficients) {
            arena.allocate(
                ValueLayout.JAVA_FLOAT,
                SteamAudio.ambisonicChannelCount(order).toLong(),
            )
        } else {
            MemorySegment.NULL
        },
    ) {
        this.order = order
        this.binaural = true
        this.normalizeEq = true
        this.listener = CoordinateSpace3.IDENTITY
        IPLPathEffectParams.shCoeffs(segment, MemorySegment.NULL)
        IPLPathEffectParams.hrtf(segment, MemorySegment.NULL)
        for (band in 0 until SteamAudio.NUM_BANDS) setEqCoefficient(band, 1f)
    }

    /**
     * Per-band EQ modelling the frequency-dependent loss of sound bending around an obstacle.
     *
     * Low frequencies diffract around a corner far better than high ones, so a path that goes
     * round a wall arrives dull. This is the coefficient set that produces that, and it is what
     * makes pathing sound like sound going *around* something rather than *through* it.
     */
    public fun setEqCoefficient(band: Int, gain: Float) {
        checkBand(band)
        IPLPathEffectParams.eqCoeffs(segment)
            .setAtIndex(ValueLayout.JAVA_FLOAT, band.toLong(), gain)
    }

    /** Reads back a per-band EQ coefficient. */
    public fun eqCoefficient(band: Int): Float {
        checkBand(band)
        return IPLPathEffectParams.eqCoeffs(segment)
            .getAtIndex(ValueLayout.JAVA_FLOAT, band.toLong())
    }

    /** Ambisonic order to render at. */
    public var order: Int
        get() = IPLPathEffectParams.order(segment)
        set(value) = IPLPathEffectParams.order(segment, value)

    /** Render binaurally via HRTF. Only used when the effect was created with spatialisation. */
    public var binaural: Boolean
        get() = IPLPathEffectParams.binaural(segment) != 0
        set(value) = IPLPathEffectParams.binaural(segment, if (value) 1 else 0)

    /**
     * Divide the EQ coefficients by their largest value before use.
     *
     * Worth leaving on. A physics-based deviation model can attenuate hard enough that a
     * pathed source becomes inaudible; normalising preserves the *relative* spectral tilt --
     * which is the audible cue for going around a corner -- without the absolute loss.
     */
    public var normalizeEq: Boolean
        get() = IPLPathEffectParams.normalizeEQ(segment) != 0
        set(value) = IPLPathEffectParams.normalizeEQ(segment, if (value) 1 else 0)

    /** The listener's frame, used to rotate the world-space field when spatialising. */
    public var listener: CoordinateSpace3
        get() = CoordinateSpace3.readFrom(IPLPathEffectParams.listener(segment))
        set(value) = value.writeTo(IPLPathEffectParams.listener(segment))

    /** The HRTF used for binaural rendering. Held as a raw pointer, so it must stay alive. */
    public var hrtf: Hrtf? = null
        set(value) {
            IPLPathEffectParams.hrtf(segment, value.handleOrNull())
            field = value
        }

    /**
     * Takes the simulated path parameters from [outputs], preserving the fields the application
     * owns rather than the simulator.
     *
     * A blind struct copy would overwrite [order], [binaural], [hrtf], [listener] and
     * [normalizeEq] with whatever the simulator left there.
     */
    public fun copySimulatedFrom(outputs: SimulationOutputs) {
        val savedOrder = order
        val savedBinaural = binaural
        val savedNormalize = normalizeEq
        val savedHrtf = hrtf
        val savedListener = listener

        segment.copyFrom(outputs.pathingSegment)

        order = savedOrder
        binaural = savedBinaural
        normalizeEq = savedNormalize
        savedHrtf?.let { hrtf = it }
        listener = savedListener
    }

    /**
     * Copies the simulator's spherical-harmonic coefficients into this object's own storage and
     * points the struct at the copy, so the audio thread no longer reads simulator memory.
     *
     * Requires `snapshotShCoefficients = true` at construction. Call it after
     * [copySimulatedFrom].
     */
    public fun copyShCoefficientsFrom(outputs: SimulationOutputs) {
        check(shSnapshot.address() != 0L) {
            "this PathEffectParams was created without snapshotShCoefficients = true"
        }
        val source = IPLPathEffectParams.shCoeffs(outputs.pathingSegment)
        if (source.address() == 0L) {
            IPLPathEffectParams.shCoeffs(segment, MemorySegment.NULL)
            return
        }
        val count = SteamAudio.ambisonicChannelCount(order).toLong()
        val available = shSnapshot.byteSize() / Float.SIZE_BYTES
        val copies = minOf(count, available)
        MemorySegment.copy(
            source.reinterpret(copies * Float.SIZE_BYTES), 0L,
            shSnapshot, 0L,
            copies * Float.SIZE_BYTES,
        )
        IPLPathEffectParams.shCoeffs(segment, shSnapshot)
    }

    /**
     * The simulated spherical-harmonic coefficients, read back for inspection.
     *
     * Mostly useful for debugging: a source with no valid path has all-zero coefficients, which
     * is the quickest way to tell "pathing found nothing" from "pathing found a quiet path".
     */
    public fun shCoefficient(index: Int): Float {
        val pointer = IPLPathEffectParams.shCoeffs(segment)
        if (pointer.address() == 0L) return 0f
        return pointer.reinterpret((index + 1).toLong() * Float.SIZE_BYTES)
            .getAtIndex(ValueLayout.JAVA_FLOAT, index.toLong())
    }

    /** True when every coefficient is zero, meaning no path was found. */
    public fun hasNoPath(): Boolean {
        val pointer = IPLPathEffectParams.shCoeffs(segment)
        if (pointer.address() == 0L) return true
        val count = SteamAudio.ambisonicChannelCount(order)
        val view = pointer.reinterpret(count.toLong() * Float.SIZE_BYTES)
        for (index in 0 until count) {
            if (view.getAtIndex(ValueLayout.JAVA_FLOAT, index.toLong()) != 0f) return false
        }
        return true
    }

    private fun checkBand(band: Int) {
        if (band !in 0 until SteamAudio.NUM_BANDS) {
            throw IndexOutOfBoundsException("band $band outside 0..<${SteamAudio.NUM_BANDS}")
        }
    }
}

/**
 * Renders sound that reaches the listener by travelling *around* geometry rather than through
 * it.
 *
 * This is the effect that makes a corner behave like a corner. Occlusion alone says "the direct
 * line is blocked, so attenuate"; pathing says "the direct line is blocked, but there is a way
 * round through that doorway, so the sound arrives from the doorway, dulled by having bent to
 * get there". In an enclosed 2D world of corridors and rooms that difference is most of the
 * spatial information the player has.
 *
 * ### It needs probes
 *
 * Unlike every other effect here, pathing is not purely geometric at runtime. It searches a
 * precomputed **visibility graph** between [ProbeBatch] probes, so a path can only be found
 * where probes exist and where [PathBaker] has recorded which probes can see one another.
 * Without baked pathing data in a probe batch attached to the simulator, [Simulator.runPathing]
 * finds nothing and this effect renders silence.
 *
 * Because the graph is baked, pathing assumes the geometry it was baked against. Moving walls
 * do not re-route paths -- see the note on [PathBaker].
 *
 * ### Input and output
 *
 * Input is the source's **mono** dry signal.
 *
 * Output depends on how the effect was created. With [spatializes] true the effect renders
 * directly to a spatialised buffer -- stereo, for a binaural or stereo speaker layout -- which
 * upstream notes is significantly faster than decoding separately. With [spatializes] false it
 * renders raw Ambisonics with `(maxOrder + 1)^2` channels, which you then rotate and decode
 * yourself; useful when several Ambisonic sources are being mixed before spatialisation.
 *
 * [expectedOutputChannels] reports which of the two applies.
 */
public class PathEffect private constructor(
    handle: MemorySegment,
    /** Highest Ambisonic order this effect can render. */
    public val maxOrder: Int,
    /** Whether this effect spatialises internally rather than emitting raw Ambisonics. */
    public val spatializes: Boolean,
    /** The speaker layout used when spatialising. */
    public val speakerLayout: SpeakerLayout,
    /**
     * Holds a custom speaker layout's direction array, which Steam Audio keeps a raw pointer
     * to. Null for standard layouts, which store no pointer.
     */
    private val layoutArena: Arena?,
) : AudioEffect(handle) {

    override fun releaseAction(): (MemorySegment) -> Unit = Phonon::iplPathEffectRelease

    override val typeName: String get() = "PathEffect"

    override fun onClosed() {
        layoutArena?.close()
    }

    /** Number of Ambisonic channels at [maxOrder]. */
    public val ambisonicChannelCount: Int get() = SteamAudio.ambisonicChannelCount(maxOrder)

    /**
     * The number of output channels [apply] expects.
     *
     * The speaker layout's channel count when [spatializes] is true, otherwise the Ambisonic
     * channel count.
     */
    public val expectedOutputChannels: Int
        get() = if (spatializes) speakerLayout.channelCount else ambisonicChannelCount

    /**
     * Renders one block.
     *
     * @param input the source's mono dry signal.
     * @param output a buffer with [expectedOutputChannels] channels. Cannot alias [input] --
     *   this effect does not work in place.
     */
    public fun apply(
        params: PathEffectParams,
        input: AudioBuffer,
        output: AudioBuffer,
    ): AudioEffectState {
        require(input.channelCount == 1) {
            "a path effect takes mono input, got ${input.channelCount} channels"
        }
        require(output.channelCount >= expectedOutputChannels) {
            "output needs at least $expectedOutputChannels channels " +
                "(spatialize=$spatializes, maxOrder=$maxOrder), got ${output.channelCount}"
        }
        require(input.sampleCount == output.sampleCount) {
            "input and output must be the same length: ${input.sampleCount} vs ${output.sampleCount}"
        }
        return AudioEffectState.from(
            Phonon.iplPathEffectApply(liveHandle, params.segment, input.struct, output.struct),
        )
    }

    override fun reset() {
        Phonon.iplPathEffectReset(liveHandle)
    }

    override val tailSize: Int
        get() = Phonon.iplPathEffectGetTailSize(liveHandle)

    override fun getTail(output: AudioBuffer): AudioEffectState {
        require(output.channelCount >= expectedOutputChannels) {
            "output needs at least $expectedOutputChannels channels, got ${output.channelCount}"
        }
        return AudioEffectState.from(Phonon.iplPathEffectGetTail(liveHandle, output.struct))
    }

    /** Increments the reference count and returns an independently closeable wrapper. */
    public fun retain(): PathEffect = PathEffect(
        Phonon.iplPathEffectRetain(liveHandle),
        maxOrder,
        spatializes,
        speakerLayout,
        // The retained wrapper shares the original's layout storage rather than owning a copy.
        layoutArena = null,
    )

    public companion object {
        /**
         * Creates a path effect that spatialises internally, rendering straight to
         * [speakerLayout].
         *
         * This is the cheaper arrangement and the right default when the pathed signal goes
         * directly to the output bus.
         *
         * @param hrtf used when [PathEffectParams.binaural] is set. Not retained, so it must
         *   outlive the effect.
         */
        public operator fun invoke(
            context: SteamAudioContext,
            audioSettings: AudioSettings,
            hrtf: Hrtf,
            maxOrder: Int = 1,
            speakerLayout: SpeakerLayout = SpeakerLayout.STEREO,
        ): PathEffect = create(context, audioSettings, hrtf, maxOrder, true, speakerLayout)

        /**
         * Creates a path effect that emits raw Ambisonics, leaving rotation and decoding to
         * you.
         *
         * Use this when several Ambisonic buses are summed before a single decode -- the same
         * saving a [ReflectionMixer] gives for reflections. Remember that the coefficients are
         * world-space: rotate with an [AmbisonicsRotationEffect] before decoding, or the field
         * will not follow the listener's head.
         */
        public fun unspatialized(
            context: SteamAudioContext,
            audioSettings: AudioSettings,
            maxOrder: Int = 1,
        ): PathEffect = create(context, audioSettings, null, maxOrder, false, SpeakerLayout.STEREO)

        private fun create(
            context: SteamAudioContext,
            audioSettings: AudioSettings,
            hrtf: Hrtf?,
            maxOrder: Int,
            spatialize: Boolean,
            speakerLayout: SpeakerLayout,
        ): PathEffect {
            require(maxOrder >= 0) { "maxOrder cannot be negative, got $maxOrder" }
            val contextHandle = context.liveHandle
            // A custom layout's direction array is referenced by pointer for the effect's
            // lifetime, so it cannot live in the scratch arena.
            var layoutArena: Arena? = null
            try {
                if (speakerLayout.needsPersistentStorage) layoutArena = Arena.ofShared()
                val handle = Arena.ofConfined().use { scratch ->
                    val audio = audioSettings.allocate(scratch)
                    val settings = IPLPathEffectSettings.allocate(scratch)
                    IPLPathEffectSettings.maxOrder(settings, maxOrder)
                    IPLPathEffectSettings.spatialize(settings, if (spatialize) 1 else 0)
                    IPLPathEffectSettings.hrtf(settings, hrtf.handleOrNull())
                    speakerLayout.writeInto(
                        IPLPathEffectSettings.speakerLayout(settings),
                        layoutArena ?: scratch,
                    )
                    createHandle("iplPathEffectCreate") { out ->
                        Phonon.iplPathEffectCreate(contextHandle, audio, settings, out)
                    }
                }
                return PathEffect(handle, maxOrder, spatialize, speakerLayout, layoutArena)
            } catch (failure: Throwable) {
                layoutArena?.close()
                throw failure
            }
        }
    }
}
