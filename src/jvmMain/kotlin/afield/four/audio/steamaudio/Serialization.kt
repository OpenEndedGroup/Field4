package afield.four.audio.steamaudio

import afield.four.audio.steamaudio.raw.IPLSerializedObjectSettings
import afield.four.audio.steamaudio.raw.Phonon
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * A byte buffer Steam Audio can serialise objects into and out of.
 *
 * Scenes, static meshes and probe batches can all be written to one of these and read back,
 * which is how you avoid rebuilding geometry or re-running a bake at startup.
 *
 * Two directions of use:
 *
 * ```
 * // Writing: create empty, save into it, read the bytes out.
 * SerializedObject(context).use { blob ->
 *     scene.save(blob)
 *     Files.write(path, blob.toByteArray())
 * }
 *
 * // Reading: create from bytes, load out of it.
 * SerializedObject(context, Files.readAllBytes(path)).use { blob ->
 *     Scene.load(context, blob).use { scene -> ... }
 * }
 * ```
 */
public class SerializedObject private constructor(handle: MemorySegment) : SteamAudioObject(handle) {

    override fun releaseAction(): (MemorySegment) -> Unit = Phonon::iplSerializedObjectRelease

    override val typeName: String get() = "SerializedObject"

    /** Number of bytes currently held. */
    public val size: Long
        get() = Phonon.iplSerializedObjectGetSize(liveHandle)

    /**
     * A view of the bytes held, valid until this object is closed or written to again.
     *
     * Aliases native memory rather than copying; use [toByteArray] if you need an independent
     * copy on the JVM heap.
     */
    public fun data(): MemorySegment {
        val handle = liveHandle
        val length = Phonon.iplSerializedObjectGetSize(handle)
        val pointer = Phonon.iplSerializedObjectGetData(handle)
        if (pointer.address() == 0L || length == 0L) return MemorySegment.NULL
        return pointer.reinterpret(length)
    }

    /** Copies the bytes held into a new array. */
    public fun toByteArray(): ByteArray {
        val segment = data()
        if (segment.address() == 0L) return ByteArray(0)
        val out = ByteArray(segment.byteSize().toInt())
        MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, 0L, out, 0, out.size)
        return out
    }

    /** Increments the reference count and returns an independently closeable wrapper. */
    public fun retain(): SerializedObject =
        SerializedObject(Phonon.iplSerializedObjectRetain(liveHandle))

    public companion object {
        /** Creates an empty serialised object, ready to be written into. */
        public operator fun invoke(context: SteamAudioContext): SerializedObject {
            val contextHandle = context.liveHandle
            Arena.ofConfined().use { arena ->
                val settings = IPLSerializedObjectSettings.allocate(arena)
                IPLSerializedObjectSettings.data(settings, MemorySegment.NULL)
                IPLSerializedObjectSettings.size(settings, 0L)
                return SerializedObject(
                    createHandle("iplSerializedObjectCreate") { out ->
                        Phonon.iplSerializedObjectCreate(contextHandle, settings, out)
                    },
                )
            }
        }

        /**
         * Creates a serialised object holding a copy of [bytes], ready to be loaded from.
         */
        public operator fun invoke(context: SteamAudioContext, bytes: ByteArray): SerializedObject {
            require(bytes.isNotEmpty()) { "cannot deserialise from an empty byte array" }
            val contextHandle = context.liveHandle
            // Steam Audio copies the buffer during create, so a confined arena is enough.
            Arena.ofConfined().use { arena ->
                val payload = arena.allocate(ValueLayout.JAVA_BYTE, bytes.size.toLong())
                MemorySegment.copy(bytes, 0, payload, ValueLayout.JAVA_BYTE, 0L, bytes.size)
                val settings = IPLSerializedObjectSettings.allocate(arena)
                IPLSerializedObjectSettings.data(settings, payload)
                IPLSerializedObjectSettings.size(settings, bytes.size.toLong())
                return SerializedObject(
                    createHandle("iplSerializedObjectCreate") { out ->
                        Phonon.iplSerializedObjectCreate(contextHandle, settings, out)
                    },
                )
            }
        }
    }
}
