package afield.four.audio.steamaudio

import afield.four.audio.steamaudio.raw.IPLEnergyFieldSettings
import afield.four.audio.steamaudio.raw.IPLImpulseResponseSettings
import afield.four.audio.steamaudio.raw.IPLReconstructorInputs
import afield.four.audio.steamaudio.raw.IPLReconstructorOutputs
import afield.four.audio.steamaudio.raw.IPLReconstructorSettings
import afield.four.audio.steamaudio.raw.IPLReconstructorSharedInputs
import afield.four.audio.steamaudio.raw.Phonon
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * Acoustic energy arriving at a point, binned by time, direction and frequency band.
 *
 * This is the intermediate representation the reflection simulation actually produces: for each
 * Ambisonic channel and each frequency band, how much energy arrives in each short time bin.
 * It is not audio -- there is no phase -- which is why it is compact enough to bake and
 * interpolate. A [Reconstructor] turns it into an [ImpulseResponse] you can convolve with.
 *
 * Working with energy fields directly is how you inspect or manipulate a simulated space:
 * reading decay curves out of a probe bake, blending two spaces together with [add] and
 * [scale], or building your own reverb analysis.
 *
 * Channel layout is `(order + 1)^2` Ambisonic channels, each holding
 * [SteamAudio.NUM_BANDS] bands of [binCount] time bins.
 */
public class EnergyField private constructor(handle: MemorySegment) : SteamAudioObject(handle) {

    override fun releaseAction(): (MemorySegment) -> Unit = Phonon::iplEnergyFieldRelease

    override val typeName: String get() = "EnergyField"

    /** Number of Ambisonic channels. */
    public val channelCount: Int
        get() = Phonon.iplEnergyFieldGetNumChannels(liveHandle)

    /** Number of time bins per band. */
    public val binCount: Int
        get() = Phonon.iplEnergyFieldGetNumBins(liveHandle)

    /**
     * A view of the whole field: `channelCount * NUM_BANDS * binCount` floats, contiguous.
     *
     * Aliases native memory, so writes go straight through.
     */
    public fun data(): MemorySegment {
        val handle = liveHandle
        val length = channelCount.toLong() * SteamAudio.NUM_BANDS * binCount * Float.SIZE_BYTES
        val pointer = Phonon.iplEnergyFieldGetData(handle)
        return if (pointer.address() == 0L) MemorySegment.NULL else pointer.reinterpret(length)
    }

    /** A view of one Ambisonic channel: `NUM_BANDS * binCount` floats. */
    public fun channel(index: Int): MemorySegment {
        require(index in 0 until channelCount) {
            "channel $index outside 0..<$channelCount"
        }
        val length = SteamAudio.NUM_BANDS.toLong() * binCount * Float.SIZE_BYTES
        val pointer = Phonon.iplEnergyFieldGetChannel(liveHandle, index)
        return if (pointer.address() == 0L) MemorySegment.NULL else pointer.reinterpret(length)
    }

    /** A view of one band within one channel: [binCount] floats. */
    public fun band(channelIndex: Int, bandIndex: Int): MemorySegment {
        require(channelIndex in 0 until channelCount) {
            "channel $channelIndex outside 0..<$channelCount"
        }
        require(bandIndex in 0 until SteamAudio.NUM_BANDS) {
            "band $bandIndex outside 0..<${SteamAudio.NUM_BANDS}"
        }
        val length = binCount.toLong() * Float.SIZE_BYTES
        val pointer = Phonon.iplEnergyFieldGetBand(liveHandle, channelIndex, bandIndex)
        return if (pointer.address() == 0L) MemorySegment.NULL else pointer.reinterpret(length)
    }

    /** Copies one band's time bins into [destination], which must hold [binCount] floats. */
    public fun readBand(channelIndex: Int, bandIndex: Int, destination: FloatArray) {
        require(destination.size >= binCount) {
            "destination needs $binCount floats, got ${destination.size}"
        }
        MemorySegment.copy(
            band(channelIndex, bandIndex), ValueLayout.JAVA_FLOAT, 0L,
            destination, 0, binCount,
        )
    }

    /** Zeroes the field. */
    public fun reset() {
        Phonon.iplEnergyFieldReset(liveHandle)
    }

    /** Copies this field into [destination], which must have the same shape. */
    public fun copyInto(destination: EnergyField) {
        Phonon.iplEnergyFieldCopy(liveHandle, destination.liveHandle)
    }

    /** Exchanges contents with [other]. Cheap -- useful for double-buffering simulation results. */
    public fun swapWith(other: EnergyField) {
        Phonon.iplEnergyFieldSwap(liveHandle, other.liveHandle)
    }

    /** Scales this field by [scalar], writing into [destination]. */
    public fun scaleInto(scalar: Float, destination: EnergyField) {
        Phonon.iplEnergyFieldScale(liveHandle, scalar, destination.liveHandle)
    }

    /** Scales this field by [scalar] and *adds* it into [destination]. */
    public fun scaleAccumulateInto(scalar: Float, destination: EnergyField) {
        Phonon.iplEnergyFieldScaleAccum(liveHandle, scalar, destination.liveHandle)
    }

    /** Increments the reference count and returns an independently closeable wrapper. */
    public fun retain(): EnergyField = EnergyField(Phonon.iplEnergyFieldRetain(liveHandle))

    public companion object {
        /**
         * Creates an energy field.
         *
         * @param duration how much time the field spans, in seconds.
         * @param order Ambisonic order, giving `(order + 1)^2` channels.
         */
        public operator fun invoke(
            context: SteamAudioContext,
            duration: Float,
            order: Int = 1,
        ): EnergyField {
            require(duration > 0f) { "duration must be positive, got $duration" }
            require(order >= 0) { "order cannot be negative, got $order" }
            val contextHandle = context.liveHandle
            Arena.ofConfined().use { arena ->
                val settings = IPLEnergyFieldSettings.allocate(arena)
                IPLEnergyFieldSettings.duration(settings, duration)
                IPLEnergyFieldSettings.order(settings, order)
                return EnergyField(
                    createHandle("iplEnergyFieldCreate") { out ->
                        Phonon.iplEnergyFieldCreate(contextHandle, settings, out)
                    },
                )
            }
        }

        /** Adds [first] and [second] into [destination]. All three must share a shape. */
        public fun add(first: EnergyField, second: EnergyField, destination: EnergyField) {
            Phonon.iplEnergyFieldAdd(
                first.liveHandle,
                second.liveHandle,
                destination.liveHandle,
            )
        }
    }
}

/**
 * A multi-channel impulse response: actual audio, ready to be convolved with a source.
 *
 * Produced from an [EnergyField] by a [Reconstructor]. Where an energy field holds only
 * magnitudes over time, an impulse response has the phase detail needed to convolve, which is
 * why it is much larger and is not what gets baked.
 */
public class ImpulseResponse private constructor(handle: MemorySegment) : SteamAudioObject(handle) {

    override fun releaseAction(): (MemorySegment) -> Unit = Phonon::iplImpulseResponseRelease

    override val typeName: String get() = "ImpulseResponse"

    /** Number of Ambisonic channels. */
    public val channelCount: Int
        get() = Phonon.iplImpulseResponseGetNumChannels(liveHandle)

    /** Number of samples per channel. */
    public val sampleCount: Int
        get() = Phonon.iplImpulseResponseGetNumSamples(liveHandle)

    /** A view of all samples: `channelCount * sampleCount` floats, contiguous. */
    public fun data(): MemorySegment {
        val length = channelCount.toLong() * sampleCount * Float.SIZE_BYTES
        val pointer = Phonon.iplImpulseResponseGetData(liveHandle)
        return if (pointer.address() == 0L) MemorySegment.NULL else pointer.reinterpret(length)
    }

    /** A view of one channel's samples. */
    public fun channel(index: Int): MemorySegment {
        require(index in 0 until channelCount) { "channel $index outside 0..<$channelCount" }
        val length = sampleCount.toLong() * Float.SIZE_BYTES
        val pointer = Phonon.iplImpulseResponseGetChannel(liveHandle, index)
        return if (pointer.address() == 0L) MemorySegment.NULL else pointer.reinterpret(length)
    }

    /** Copies one channel into [destination], which must hold [sampleCount] floats. */
    public fun readChannel(index: Int, destination: FloatArray) {
        require(destination.size >= sampleCount) {
            "destination needs $sampleCount floats, got ${destination.size}"
        }
        MemorySegment.copy(
            channel(index), ValueLayout.JAVA_FLOAT, 0L,
            destination, 0, sampleCount,
        )
    }

    /** Zeroes the impulse response. */
    public fun reset() {
        Phonon.iplImpulseResponseReset(liveHandle)
    }

    /** Copies this impulse response into [destination]. */
    public fun copyInto(destination: ImpulseResponse) {
        Phonon.iplImpulseResponseCopy(liveHandle, destination.liveHandle)
    }

    /** Exchanges contents with [other]. */
    public fun swapWith(other: ImpulseResponse) {
        Phonon.iplImpulseResponseSwap(liveHandle, other.liveHandle)
    }

    /** Scales this impulse response by [scalar], writing into [destination]. */
    public fun scaleInto(scalar: Float, destination: ImpulseResponse) {
        Phonon.iplImpulseResponseScale(liveHandle, scalar, destination.liveHandle)
    }

    /** Scales by [scalar] and adds into [destination]. */
    public fun scaleAccumulateInto(scalar: Float, destination: ImpulseResponse) {
        Phonon.iplImpulseResponseScaleAccum(liveHandle, scalar, destination.liveHandle)
    }

    /** Increments the reference count and returns an independently closeable wrapper. */
    public fun retain(): ImpulseResponse =
        ImpulseResponse(Phonon.iplImpulseResponseRetain(liveHandle))

    public companion object {
        /** Creates an impulse response buffer. */
        public operator fun invoke(
            context: SteamAudioContext,
            duration: Float,
            order: Int = 1,
            samplingRate: Int = 48_000,
        ): ImpulseResponse {
            require(duration > 0f) { "duration must be positive, got $duration" }
            val contextHandle = context.liveHandle
            Arena.ofConfined().use { arena ->
                val settings = IPLImpulseResponseSettings.allocate(arena)
                IPLImpulseResponseSettings.duration(settings, duration)
                IPLImpulseResponseSettings.order(settings, order)
                IPLImpulseResponseSettings.samplingRate(settings, samplingRate)
                return ImpulseResponse(
                    createHandle("iplImpulseResponseCreate") { out ->
                        Phonon.iplImpulseResponseCreate(contextHandle, settings, out)
                    },
                )
            }
        }

        /** Adds [first] and [second] into [destination]. */
        public fun add(
            first: ImpulseResponse,
            second: ImpulseResponse,
            destination: ImpulseResponse,
        ) {
            Phonon.iplImpulseResponseAdd(
                first.liveHandle,
                second.liveHandle,
                destination.liveHandle,
            )
        }
    }
}

/**
 * Turns [EnergyField]s into [ImpulseResponse]s.
 *
 * Reconstruction invents plausible phase for energy that was only stored as magnitude, which is
 * what lets a compact baked field become something convolvable. It is expensive, and batches
 * well -- [reconstruct] takes several fields at once so the work is shared.
 *
 * Definitely not for the audio thread.
 */
public class Reconstructor private constructor(handle: MemorySegment) : SteamAudioObject(handle) {

    override fun releaseAction(): (MemorySegment) -> Unit = Phonon::iplReconstructorRelease

    override val typeName: String get() = "Reconstructor"

    /**
     * Reconstructs impulse responses from energy fields, pairwise.
     *
     * [energyFields] and [impulseResponses] must be the same length; entry `i` of the former is
     * reconstructed into entry `i` of the latter.
     *
     * @param duration how much of each field to reconstruct, in seconds.
     * @param order Ambisonic order to reconstruct at.
     */
    public fun reconstruct(
        energyFields: List<EnergyField>,
        impulseResponses: List<ImpulseResponse>,
        duration: Float,
        order: Int = 1,
    ) {
        require(energyFields.isNotEmpty()) { "nothing to reconstruct" }
        require(energyFields.size == impulseResponses.size) {
            "${energyFields.size} energy fields but ${impulseResponses.size} impulse responses"
        }
        val handle = liveHandle
        Arena.ofConfined().use { arena ->
            val count = energyFields.size

            val inputLayout = IPLReconstructorInputs.layout()
            val inputs = arena.allocate(inputLayout, count.toLong())
            energyFields.forEachIndexed { index, field ->
                IPLReconstructorInputs.energyField(
                    inputs.asSlice(index * inputLayout.byteSize(), inputLayout.byteSize()),
                    field.liveHandle,
                )
            }

            val outputLayout = IPLReconstructorOutputs.layout()
            val outputs = arena.allocate(outputLayout, count.toLong())
            impulseResponses.forEachIndexed { index, response ->
                IPLReconstructorOutputs.impulseResponse(
                    outputs.asSlice(index * outputLayout.byteSize(), outputLayout.byteSize()),
                    response.liveHandle,
                )
            }

            val shared = IPLReconstructorSharedInputs.allocate(arena)
            IPLReconstructorSharedInputs.duration(shared, duration)
            IPLReconstructorSharedInputs.order(shared, order)

            Phonon.iplReconstructorReconstruct(handle, count, inputs, shared, outputs)
        }
    }

    /** Increments the reference count and returns an independently closeable wrapper. */
    public fun retain(): Reconstructor = Reconstructor(Phonon.iplReconstructorRetain(liveHandle))

    public companion object {
        /**
         * Creates a reconstructor.
         *
         * The maxima here bound what [reconstruct] may later ask for.
         */
        public operator fun invoke(
            context: SteamAudioContext,
            maxDuration: Float,
            maxOrder: Int = 1,
            samplingRate: Int = 48_000,
        ): Reconstructor {
            require(maxDuration > 0f) { "maxDuration must be positive, got $maxDuration" }
            val contextHandle = context.liveHandle
            Arena.ofConfined().use { arena ->
                val settings = IPLReconstructorSettings.allocate(arena)
                IPLReconstructorSettings.maxDuration(settings, maxDuration)
                IPLReconstructorSettings.maxOrder(settings, maxOrder)
                IPLReconstructorSettings.samplingRate(settings, samplingRate)
                return Reconstructor(
                    createHandle("iplReconstructorCreate") { out ->
                        Phonon.iplReconstructorCreate(contextHandle, settings, out)
                    },
                )
            }
        }
    }
}
