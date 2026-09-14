package afield.four.audio.steamaudio

import afield.four.audio.steamaudio.raw.IPLHRTFSettings
import afield.four.audio.steamaudio.raw.Phonon
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.file.Files
import java.nio.file.Path

/**
 * A head-related transfer function: the filter pair that makes a mono source appear to come
 * from a particular direction.
 *
 * An HRTF is expensive to build and is shared by every binaural effect that uses it, so create
 * one per context (or one per listener, if you support multiple head models) and keep it alive
 * for the duration of the session.
 *
 * HRTFs are reference-counted like every other Steam Audio object. A [BinauralEffect] created
 * against an HRTF does **not** retain it, so the HRTF must outlive every effect and every
 * `params` block that names it.
 */
public class Hrtf private constructor(handle: MemorySegment) : SteamAudioObject(handle) {

    override fun releaseAction(): (MemorySegment) -> Unit = Phonon::iplHRTFRelease

    override val typeName: String get() = "Hrtf"

    /** Increments the reference count and returns an independently closeable wrapper. */
    public fun retain(): Hrtf = Hrtf(Phonon.iplHRTFRetain(liveHandle))

    public companion object {

        /**
         * Steam Audio's built-in HRTF.
         *
         * @param volume linear gain correction applied to the HRTF data. Binaural rendering is
         *   generally quieter than unprocessed audio; raise this if the spatialised signal sits
         *   too low against non-spatialised material.
         */
        public fun default(
            context: SteamAudioContext,
            audioSettings: AudioSettings,
            volume: Float = 1.0f,
        ): Hrtf = create(context, audioSettings) { arena, settings ->
            IPLHRTFSettings.type(settings, HrtfType.DEFAULT.value)
            IPLHRTFSettings.volume(settings, volume)
            IPLHRTFSettings.normType(settings, HrtfNormType.NONE.value)
            IPLHRTFSettings.sofaFileName(settings, MemorySegment.NULL)
            IPLHRTFSettings.sofaData(settings, MemorySegment.NULL)
            IPLHRTFSettings.sofaDataSize(settings, 0)
            arena.hashCode() // no-op, keeps the lambda shape uniform
        }

        /**
         * An HRTF loaded from a SOFA file on disk.
         *
         * @param normType whether to normalise each direction to equal RMS. [HrtfNormType.RMS]
         *   evens out level differences between directions in measured datasets, which is
         *   usually what you want for a dataset you did not measure yourself.
         */
        public fun fromSofaFile(
            context: SteamAudioContext,
            audioSettings: AudioSettings,
            path: Path,
            volume: Float = 1.0f,
            normType: HrtfNormType = HrtfNormType.RMS,
        ): Hrtf {
            require(Files.isRegularFile(path)) { "no SOFA file at $path" }
            return create(context, audioSettings) { arena, settings ->
                IPLHRTFSettings.type(settings, HrtfType.SOFA.value)
                IPLHRTFSettings.volume(settings, volume)
                IPLHRTFSettings.normType(settings, normType.value)
                IPLHRTFSettings.sofaFileName(settings, arena.allocateFrom(path.toAbsolutePath().toString()))
                IPLHRTFSettings.sofaData(settings, MemorySegment.NULL)
                IPLHRTFSettings.sofaDataSize(settings, 0)
            }
        }

        /**
         * An HRTF loaded from an in-memory SOFA image.
         *
         * Useful when the dataset ships as a classpath resource rather than a file on disk.
         */
        public fun fromSofaBytes(
            context: SteamAudioContext,
            audioSettings: AudioSettings,
            sofaData: ByteArray,
            volume: Float = 1.0f,
            normType: HrtfNormType = HrtfNormType.RMS,
        ): Hrtf {
            require(sofaData.isNotEmpty()) { "SOFA data is empty" }
            return create(context, audioSettings) { arena, settings ->
                val bytes = arena.allocate(ValueLayout.JAVA_BYTE, sofaData.size.toLong())
                MemorySegment.copy(sofaData, 0, bytes, ValueLayout.JAVA_BYTE, 0L, sofaData.size)
                IPLHRTFSettings.type(settings, HrtfType.SOFA.value)
                IPLHRTFSettings.volume(settings, volume)
                IPLHRTFSettings.normType(settings, normType.value)
                IPLHRTFSettings.sofaFileName(settings, MemorySegment.NULL)
                IPLHRTFSettings.sofaData(settings, bytes)
                IPLHRTFSettings.sofaDataSize(settings, sofaData.size)
            }
        }

        private inline fun create(
            context: SteamAudioContext,
            audioSettings: AudioSettings,
            configure: (Arena, MemorySegment) -> Unit,
        ): Hrtf {
            val contextHandle = context.liveHandle
            Arena.ofConfined().use { arena ->
                val audio = audioSettings.allocate(arena)
                val settings = IPLHRTFSettings.allocate(arena)
                configure(arena, settings)
                return Hrtf(
                    createHandle("iplHRTFCreate") { out ->
                        Phonon.iplHRTFCreate(contextHandle, audio, settings, out)
                    },
                )
            }
        }
    }
}
