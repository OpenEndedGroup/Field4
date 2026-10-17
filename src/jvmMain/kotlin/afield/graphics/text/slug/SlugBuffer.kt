package afield.graphics.text.slug

import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.buffers.FrameBufferGroup
import afield.graphics.vulkan.buffers.HasBuffer
import afield.graphics.vulkan.buffers.defaultBuffer
import afield.graphics.vulkan.device.CommandBuffer
import java.nio.ByteOrder

/**
 * An immutable Slug atlas upload behind a stable address supplier.
 *
 * Call [replace] on the render thread after preparing geometry. It uploads a fresh allocation,
 * publishes it, then retires the old allocation through Field's existing deferred GPU cleanup.
 * Camera and fill edits do not call [replace].
 *
 * [close] can come before or after the consuming mesh is removed. A closed buffer hands its
 * consumers the renderer's empty atlas, whose bounds are empty, so the slug shader clips the quad
 * away and draws nothing; the real allocation is retired once pending frames finish.
 */
class SlugBuffer(private val renderer: Renderer, path: SlugPath) : HasBuffer, AutoCloseable {
    @Volatile private var current: FrameBufferGroup = upload(path)
    private val empty: FrameBufferGroup = emptyAtlas(renderer)
    @Volatile var isClosed: Boolean = false
        private set
    var revision: Long = 0
        private set

    private fun upload(path: SlugPath): FrameBufferGroup = upload(renderer, path.byteSize, path.words)

    private companion object {
        /** One empty atlas per renderer, shared by every closed buffer; it lives as long as the renderer. */
        private val emptyAtlases = java.util.WeakHashMap<Renderer, FrameBufferGroup>()

        /** Sixteen zero words: the bounds (words 8–11) are empty, which the vertex shader draws as nothing. */
        fun emptyAtlas(renderer: Renderer): FrameBufferGroup = synchronized(emptyAtlases) {
            emptyAtlases.getOrPut(renderer) { upload(renderer, 16L * 4, IntArray(16)) }
        }

        fun upload(renderer: Renderer, byteSize: Long, words: IntArray): FrameBufferGroup {
            val data = defaultBuffer(name = "slug-atlas") { size = byteSize }.toBuffer(renderer)
            try {
                data.mapAndWrite_bytes { order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(words) }
                return data
            } catch (failure: Throwable) {
                data.buffers.forEach { it.cleanup() }
                throw failure
            }
        }
    }

    /** Publish newly prepared geometry; existing shader bindings keep the same supplier. */
    @Synchronized
    fun replace(path: SlugPath) {
        check(!isClosed) { "SlugBuffer is closed" }
        val next = upload(path)
        val previous = current
        current = next
        revision++
        previous.buffers.forEach { it.cleanup() }
    }

    override fun getBuffer(): Long = current.getBuffer()

    override fun getGPUAddress(cmd: CommandBuffer): Long = current.getGPUAddress(cmd)

    /**
     * Retire the atlas. A mesh still bound to this buffer draws nothing from the next frame on, so
     * it does not matter whether the mesh is removed first. Repeated close calls are harmless.
     */
    @Synchronized
    override fun close() {
        if (isClosed) return
        isClosed = true
        val previous = current
        current = empty
        previous.buffers.forEach { it.cleanup() }
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
