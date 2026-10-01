package afield.graphics.slug

import afield.four.Renderer
import afield.four.buffers.FrameBufferGroup
import afield.four.buffers.HasBuffer
import afield.four.buffers.defaultBuffer
import afield.four.device.CommandBuffer
import java.nio.ByteOrder

/**
 * An immutable Slug atlas upload behind a stable address supplier.
 *
 * Call [replace] on the render thread after preparing geometry. It uploads a fresh allocation,
 * publishes it, then retires the old allocation through Field's existing deferred GPU cleanup.
 * Camera and fill edits do not call [replace]. Detach the consuming mesh before [close].
 */
class SlugBuffer(private val renderer: Renderer, path: SlugPath) : HasBuffer, AutoCloseable {
    @Volatile private var current: FrameBufferGroup = upload(path)
    @Volatile var isClosed: Boolean = false
        private set
    var revision: Long = 0
        private set

    private fun upload(path: SlugPath): FrameBufferGroup {
        val data = defaultBuffer(name = "slug-atlas") { size = path.byteSize }.toBuffer(renderer)
        try {
            val words = path.words
            data.mapAndWrite_bytes { order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(words) }
            return data
        } catch (failure: Throwable) {
            data.buffers.forEach { it.cleanup() }
            throw failure
        }
    }

    /** Publish newly prepared geometry; existing shader bindings keep the same supplier. */
    fun replace(path: SlugPath) {
        check(!isClosed) { "SlugBuffer is closed" }
        val next = upload(path)
        val previous = current
        current = next
        revision++
        previous.buffers.forEach { it.cleanup() }
    }

    override fun getBuffer(): Long {
        check(!isClosed) { "SlugBuffer is closed" }
        return current.getBuffer()
    }

    override fun getGPUAddress(cmd: CommandBuffer): Long {
        check(!isClosed) { "SlugBuffer is closed" }
        return current.getGPUAddress(cmd)
    }

    /** Release after removing the consuming mesh. Repeated close calls are harmless. */
    override fun close() {
        if (isClosed) return
        isClosed = true
        current.buffers.forEach { it.cleanup() }
    }
}

/** Shader sources shared by the documented box and shader/GPU regression tests. */
object SlugShaders {
    val vertex: String get() = source("vertex")
    val fragment: String get() = source("fragment")
    private fun source(stage: String): String = requireNotNull(
        SlugShaders::class.java.getResource("/afield/slug/$stage.glsl")
    ).readText()
}
