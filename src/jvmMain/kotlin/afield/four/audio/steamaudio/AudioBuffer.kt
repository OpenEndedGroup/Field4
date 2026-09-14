package afield.four.audio.steamaudio

import afield.four.audio.steamaudio.raw.IPLAudioBuffer
import afield.four.audio.steamaudio.raw.Phonon
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.FloatBuffer

/** Sampling rate and block size, shared by every effect created against them. */
public data class AudioSettings(
    /** Samples per second, for example 48000. */
    public val samplingRate: Int = 48_000,
    /**
     * Samples per channel in each processing block.
     *
     * Every [AudioBuffer] handed to an effect created with these settings must have exactly
     * this many samples.
     */
    public val frameSize: Int = 1024,
) {
    init {
        require(samplingRate > 0) { "samplingRate must be positive, got $samplingRate" }
        require(frameSize > 0) { "frameSize must be positive, got $frameSize" }
    }

    /** Duration of one block, in seconds. */
    public val frameDuration: Double get() = frameSize.toDouble() / samplingRate

    internal fun allocate(allocator: Arena): MemorySegment =
        afield.four.audio.steamaudio.raw.IPLAudioSettings.allocate(allocator).also {
            afield.four.audio.steamaudio.raw.IPLAudioSettings.samplingRate(it, samplingRate)
            afield.four.audio.steamaudio.raw.IPLAudioSettings.frameSize(it, frameSize)
        }
}

/**
 * A block of deinterleaved 32-bit float audio in native memory.
 *
 * Steam Audio's `IPLAudioBuffer` is `{ numChannels, numSamples, float** data }` -- an array of
 * per-channel pointers, with sample storage owned by the caller. This class allocates the
 * samples as **one contiguous block** and points the channel array into it, which keeps
 * channels cache-adjacent and makes whole-buffer operations a single [MemorySegment] copy.
 *
 * ### Allocation and the audio thread
 *
 * Allocate every buffer you need up front, outside the audio callback, from a long-lived
 * [Arena]; then reuse them each block. Nothing in this class allocates on the JVM heap once
 * constructed -- [clear], [copyFrom], [readChannel], [writeChannel], [interleaveTo] and
 * [deinterleaveFrom] are all allocation-free -- so a steady-state render loop produces no
 * garbage.
 *
 * ```
 * val arena = Arena.ofShared()                       // lives as long as the audio graph
 * val mono   = AudioBuffer(arena, channelCount = 1, sampleCount = settings.frameSize)
 * val stereo = AudioBuffer(arena, channelCount = 2, sampleCount = settings.frameSize)
 * // per block, on the audio thread:
 * mono.deinterleaveFrom(context, inputSamples)
 * binaural.apply(params, mono, stereo)
 * stereo.interleaveTo(context, outputSamples)
 * ```
 *
 * Buffers are not thread-safe; a buffer belongs to whichever thread renders with it.
 */
public class AudioBuffer private constructor(
    /** The `IPLAudioBuffer` struct handed to native calls. */
    internal val struct: MemorySegment,
    /** Contiguous sample storage: [channelCount] * [sampleCount] floats. */
    internal val samples: MemorySegment,
    /** Array of [channelCount] pointers into [samples]. */
    private val channelPointers: MemorySegment,
    /** Number of channels. */
    public val channelCount: Int,
    /** Number of samples per channel. */
    public val sampleCount: Int,
) {

    /**
     * Allocates a buffer from [arena].
     *
     * The buffer stays valid for as long as [arena] does; there is nothing to close here.
     */
    public constructor(arena: Arena, channelCount: Int, sampleCount: Int) : this(
        struct = IPLAudioBuffer.allocate(arena),
        samples = arena.allocate(ValueLayout.JAVA_FLOAT, (channelCount.toLong() * sampleCount)),
        channelPointers = arena.allocate(ValueLayout.ADDRESS, channelCount.toLong()),
        channelCount = channelCount,
        sampleCount = sampleCount,
    ) {
        require(channelCount > 0) { "channelCount must be positive, got $channelCount" }
        require(sampleCount > 0) { "sampleCount must be positive, got $sampleCount" }

        for (channel in 0 until channelCount) {
            channelPointers.setAtIndex(
                ValueLayout.ADDRESS,
                channel.toLong(),
                samples.asSlice(channel.toLong() * sampleCount * Float.SIZE_BYTES, bytesPerChannel),
            )
        }

        IPLAudioBuffer.numChannels(struct, channelCount)
        IPLAudioBuffer.numSamples(struct, sampleCount)
        IPLAudioBuffer.data(struct, channelPointers)
    }

    /** Total number of samples across all channels. */
    public val totalSamples: Int get() = channelCount * sampleCount

    private val bytesPerChannel: Long get() = sampleCount.toLong() * Float.SIZE_BYTES

    /**
     * A view of one channel's samples.
     *
     * The returned segment aliases this buffer's storage -- writing through it writes the
     * buffer. It is a view, not a copy, and costs nothing to take.
     */
    public fun channel(index: Int): MemorySegment {
        checkChannel(index)
        return samples.asSlice(index.toLong() * bytesPerChannel, bytesPerChannel)
    }

    /** Sets every sample in every channel to zero. */
    public fun clear() {
        samples.fill(0)
    }

    /** Sets every sample in one channel to zero. */
    public fun clearChannel(index: Int) {
        checkChannel(index)
        channel(index).fill(0)
    }

    /**
     * Copies all samples from [source], which must have the same shape.
     *
     * A single native memory copy, since both buffers store channels contiguously.
     */
    public fun copyFrom(source: AudioBuffer) {
        require(source.channelCount == channelCount && source.sampleCount == sampleCount) {
            "shape mismatch: copying ${source.channelCount}x${source.sampleCount} " +
                "into ${channelCount}x$sampleCount"
        }
        samples.copyFrom(source.samples)
    }

    /** Reads one channel into [destination], starting at [destinationOffset]. */
    public fun readChannel(index: Int, destination: FloatArray, destinationOffset: Int = 0) {
        checkChannel(index)
        require(destinationOffset >= 0 && destinationOffset + sampleCount <= destination.size) {
            "destination too small: need $sampleCount floats at offset $destinationOffset, " +
                "array is ${destination.size}"
        }
        MemorySegment.copy(
            samples, ValueLayout.JAVA_FLOAT, index.toLong() * bytesPerChannel,
            destination, destinationOffset, sampleCount,
        )
    }

    /** Writes [source] into one channel, starting from [sourceOffset]. */
    public fun writeChannel(index: Int, source: FloatArray, sourceOffset: Int = 0) {
        checkChannel(index)
        require(sourceOffset >= 0 && sourceOffset + sampleCount <= source.size) {
            "source too small: need $sampleCount floats at offset $sourceOffset, " +
                "array is ${source.size}"
        }
        MemorySegment.copy(
            source, sourceOffset,
            samples, ValueLayout.JAVA_FLOAT, index.toLong() * bytesPerChannel,
            sampleCount,
        )
    }

    /** Reads a single sample. Bounds-checked; prefer [channel] or [readChannel] in bulk. */
    public operator fun get(channel: Int, sample: Int): Float {
        checkChannel(channel)
        checkSample(sample)
        return samples.getAtIndex(ValueLayout.JAVA_FLOAT, channel.toLong() * sampleCount + sample)
    }

    /** Writes a single sample. Bounds-checked; prefer [channel] or [writeChannel] in bulk. */
    public operator fun set(channel: Int, sample: Int, value: Float) {
        checkChannel(channel)
        checkSample(sample)
        samples.setAtIndex(ValueLayout.JAVA_FLOAT, channel.toLong() * sampleCount + sample, value)
    }

    // ---------------------------------------------------------------------------------------------
    // Interleaving. These delegate to Steam Audio's own SIMD implementations.
    // ---------------------------------------------------------------------------------------------

    /**
     * Writes this buffer's samples to [destination] in interleaved order.
     *
     * [destination] must hold at least [totalSamples] floats.
     */
    public fun interleaveTo(context: SteamAudioContext, destination: MemorySegment) {
        requireCapacity(destination, "destination")
        Phonon.iplAudioBufferInterleave(context.liveHandle, struct, destination)
    }

    /**
     * Fills this buffer from interleaved samples in [source].
     *
     * [source] must hold at least [totalSamples] floats.
     */
    public fun deinterleaveFrom(context: SteamAudioContext, source: MemorySegment) {
        requireCapacity(source, "source")
        Phonon.iplAudioBufferDeinterleave(context.liveHandle, source, struct)
    }

    /**
     * Interleaves into a scratch [MemorySegment] and copies out to [destination].
     *
     * Convenience for code that holds interleaved audio on the JVM heap. This needs a
     * native staging buffer, so on the audio thread prefer the [MemorySegment] overload with
     * a pre-allocated staging segment, or [readChannel] per channel.
     */
    public fun interleaveTo(context: SteamAudioContext, destination: FloatArray) {
        require(destination.size >= totalSamples) {
            "destination too small: need $totalSamples floats, array is ${destination.size}"
        }
        Arena.ofConfined().use { scratch ->
            val staging = scratch.allocate(ValueLayout.JAVA_FLOAT, totalSamples.toLong())
            Phonon.iplAudioBufferInterleave(context.liveHandle, struct, staging)
            MemorySegment.copy(staging, ValueLayout.JAVA_FLOAT, 0L, destination, 0, totalSamples)
        }
    }

    /** Fills this buffer from interleaved samples held on the JVM heap. See [interleaveTo]. */
    public fun deinterleaveFrom(context: SteamAudioContext, source: FloatArray) {
        require(source.size >= totalSamples) {
            "source too small: need $totalSamples floats, array is ${source.size}"
        }
        Arena.ofConfined().use { scratch ->
            val staging = scratch.allocate(ValueLayout.JAVA_FLOAT, totalSamples.toLong())
            MemorySegment.copy(source, 0, staging, ValueLayout.JAVA_FLOAT, 0L, totalSamples)
            Phonon.iplAudioBufferDeinterleave(context.liveHandle, staging, struct)
        }
    }

    /**
     * Interleaves directly into a direct [FloatBuffer].
     *
     * Allocation-free, so this is the right bridge for a `javax.sound` or JNI output path that
     * already hands you direct buffers.
     */
    public fun interleaveTo(context: SteamAudioContext, destination: FloatBuffer) {
        require(destination.isDirect) { "FloatBuffer must be direct" }
        require(destination.remaining() >= totalSamples) {
            "destination too small: need $totalSamples floats, ${destination.remaining()} remaining"
        }
        interleaveTo(context, MemorySegment.ofBuffer(destination))
    }

    /** Fills this buffer from a direct [FloatBuffer] of interleaved samples. */
    public fun deinterleaveFrom(context: SteamAudioContext, source: FloatBuffer) {
        require(source.isDirect) { "FloatBuffer must be direct" }
        require(source.remaining() >= totalSamples) {
            "source too small: need $totalSamples floats, ${source.remaining()} remaining"
        }
        deinterleaveFrom(context, MemorySegment.ofBuffer(source))
    }

    // ---------------------------------------------------------------------------------------------
    // Channel-count and format conversion
    // ---------------------------------------------------------------------------------------------

    /**
     * Adds this buffer's samples into [mix], which must have the same shape.
     *
     * This is how several sources are summed into one output bus.
     */
    public fun mixInto(context: SteamAudioContext, mix: AudioBuffer) {
        require(mix.channelCount == channelCount && mix.sampleCount == sampleCount) {
            "shape mismatch: mixing ${channelCount}x$sampleCount " +
                "into ${mix.channelCount}x${mix.sampleCount}"
        }
        Phonon.iplAudioBufferMix(context.liveHandle, struct, mix.struct)
    }

    /**
     * Downmixes this buffer to mono in [destination].
     *
     * [destination] must have exactly one channel and the same sample count.
     */
    public fun downmixTo(context: SteamAudioContext, destination: AudioBuffer) {
        require(destination.channelCount == 1) {
            "downmix destination must be mono, got ${destination.channelCount} channels"
        }
        require(destination.sampleCount == sampleCount) {
            "sample count mismatch: $sampleCount vs ${destination.sampleCount}"
        }
        Phonon.iplAudioBufferDownmix(context.liveHandle, struct, destination.struct)
    }

    /**
     * Converts between Ambisonic channel-ordering and normalisation conventions.
     *
     * Both buffers must have the same number of channels and samples.
     */
    public fun convertAmbisonicsTo(
        context: SteamAudioContext,
        destination: AudioBuffer,
        from: AmbisonicsType,
        to: AmbisonicsType,
    ) {
        require(destination.channelCount == channelCount && destination.sampleCount == sampleCount) {
            "shape mismatch: ${channelCount}x$sampleCount vs " +
                "${destination.channelCount}x${destination.sampleCount}"
        }
        Phonon.iplAudioBufferConvertAmbisonics(
            context.liveHandle,
            from.value,
            to.value,
            struct,
            destination.struct,
        )
    }

    /**
     * A read-only view of this buffer reinterpreted as having fewer channels.
     *
     * Effects sometimes need to be handed a buffer with a specific channel count -- an
     * Ambisonics effect running at reduced order, say -- without reallocating. The returned
     * buffer aliases this one's storage for its first [channels] channels.
     */
    public fun withChannelCount(arena: Arena, channels: Int): AudioBuffer {
        require(channels in 1..channelCount) {
            "cannot view $channelCount channels as $channels"
        }
        val view = IPLAudioBuffer.allocate(arena)
        val pointers = arena.allocate(ValueLayout.ADDRESS, channels.toLong())
        for (channel in 0 until channels) {
            pointers.setAtIndex(
                ValueLayout.ADDRESS,
                channel.toLong(),
                samples.asSlice(channel.toLong() * bytesPerChannel, bytesPerChannel),
            )
        }
        IPLAudioBuffer.numChannels(view, channels)
        IPLAudioBuffer.numSamples(view, sampleCount)
        IPLAudioBuffer.data(view, pointers)
        return AudioBuffer(view, samples, pointers, channels, sampleCount)
    }

    /** Largest absolute sample value, useful for metering and for tail-decay checks. */
    public fun peak(): Float {
        var peak = 0f
        for (index in 0 until totalSamples) {
            val magnitude = kotlin.math.abs(samples.getAtIndex(ValueLayout.JAVA_FLOAT, index.toLong()))
            if (magnitude > peak) peak = magnitude
        }
        return peak
    }

    /** True when every sample is exactly zero. */
    public fun isSilent(): Boolean {
        for (index in 0 until totalSamples) {
            if (samples.getAtIndex(ValueLayout.JAVA_FLOAT, index.toLong()) != 0f) return false
        }
        return true
    }

    private fun checkChannel(index: Int) {
        if (index !in 0 until channelCount) {
            throw IndexOutOfBoundsException("channel $index outside 0..<$channelCount")
        }
    }

    private fun checkSample(index: Int) {
        if (index !in 0 until sampleCount) {
            throw IndexOutOfBoundsException("sample $index outside 0..<$sampleCount")
        }
    }

    private fun requireCapacity(segment: MemorySegment, what: String) {
        val needed = totalSamples.toLong() * Float.SIZE_BYTES
        require(segment.byteSize() >= needed) {
            "$what too small: need $needed bytes for ${channelCount}x$sampleCount, " +
                "segment is ${segment.byteSize()}"
        }
    }

    override fun toString(): String = "AudioBuffer(${channelCount}ch x ${sampleCount}smp)"
}

/**
 * How audio is laid out across speakers.
 *
 * Steam Audio's `IPLSpeakerLayout` either names a standard layout or supplies explicit speaker
 * directions. Custom layouts keep a pointer to their direction array, so the array must outlive
 * every effect created with the layout -- hence the [Arena] parameter on [allocate].
 */
public class SpeakerLayout private constructor(
    /** Which standard layout this is, or [SpeakerLayoutType.CUSTOM]. */
    public val type: SpeakerLayoutType,
    /** Explicit speaker directions for a custom layout, otherwise empty. */
    public val speakers: List<Vector3>,
) {

    /** The number of channels this layout implies. */
    public val channelCount: Int
        get() = type.channelCount ?: speakers.size

    internal fun allocate(arena: Arena): MemorySegment {
        val segment = afield.four.audio.steamaudio.raw.IPLSpeakerLayout.allocate(arena)
        afield.four.audio.steamaudio.raw.IPLSpeakerLayout.type(segment, type.value)
        afield.four.audio.steamaudio.raw.IPLSpeakerLayout.numSpeakers(segment, channelCount)
        if (type == SpeakerLayoutType.CUSTOM) {
            val directions = arena.allocate(
                afield.four.audio.steamaudio.raw.IPLVector3.layout(),
                speakers.size.toLong(),
            )
            speakers.forEachIndexed { index, speaker ->
                speaker.writeTo(
                    directions.asSlice(
                        index.toLong() * afield.four.audio.steamaudio.raw.IPLVector3.layout().byteSize(),
                        afield.four.audio.steamaudio.raw.IPLVector3.layout().byteSize(),
                    ),
                )
            }
            afield.four.audio.steamaudio.raw.IPLSpeakerLayout.speakers(segment, directions)
        } else {
            afield.four.audio.steamaudio.raw.IPLSpeakerLayout.speakers(segment, MemorySegment.NULL)
        }
        return segment
    }

    override fun toString(): String =
        if (type == SpeakerLayoutType.CUSTOM) "SpeakerLayout(custom, ${speakers.size} speakers)"
        else "SpeakerLayout($type)"

    public companion object {
        public val MONO: SpeakerLayout = SpeakerLayout(SpeakerLayoutType.MONO, emptyList())
        public val STEREO: SpeakerLayout = SpeakerLayout(SpeakerLayoutType.STEREO, emptyList())
        public val QUADRAPHONIC: SpeakerLayout = SpeakerLayout(SpeakerLayoutType.QUADRAPHONIC, emptyList())
        public val SURROUND_5_1: SpeakerLayout = SpeakerLayout(SpeakerLayoutType.SURROUND_5_1, emptyList())
        public val SURROUND_7_1: SpeakerLayout = SpeakerLayout(SpeakerLayoutType.SURROUND_7_1, emptyList())

        /** A layout with explicit speaker directions, which should be unit vectors. */
        public fun custom(speakers: List<Vector3>): SpeakerLayout {
            require(speakers.isNotEmpty()) { "a custom speaker layout needs at least one speaker" }
            return SpeakerLayout(SpeakerLayoutType.CUSTOM, speakers.toList())
        }

        /** The standard layout for a given channel count, if there is one. */
        public fun forChannelCount(channels: Int): SpeakerLayout? = when (channels) {
            1 -> MONO
            2 -> STEREO
            4 -> QUADRAPHONIC
            6 -> SURROUND_5_1
            8 -> SURROUND_7_1
            else -> null
        }
    }
}
