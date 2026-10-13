@file:afield.runtime.completion.CompletionCategory("Graphics")
package afield.io.syphon

import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.Target
import afield.graphics.vulkan.device.CommandBuffer
import afield.graphics.vulkan.targets.RenderToTexture
import afield.graphics.texture.IOSurfaceHostBuffer
import afield.graphics.texture.MetalIOSurface
import afield.graphics.texture.StreamingTexture
import org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SRGB
import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer
import afield.app.diagnostics.logging.lprintln
import org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_SRGB
import org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_UNORM
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Publishes every frame a [RenderToTexture] draws to a [SyphonServer] without the CPU touching the
 * pixels: the frame is copied GPU-side into an IOSurface (on the same command buffer that drew it),
 * and once that frame's GPU work has completed Syphon blits the IOSurface into its own shared one.
 *
 * Made by [SyphonServer.followOnGpu]; a [Target] in the renderer's list, placed after the source so
 * it runs after the source has drawn. The IOSurface reaches Vulkan as host memory
 * (`VK_EXT_external_memory_host`, see [IOSurfaceHostBuffer]), which both KosmicKrisp and MoltenVK
 * offer on Apple silicon. Two surfaces alternate; if Syphon is still copying the one due next, the
 * frame is skipped rather than overwritten under it.
 */
class SyphonGpuPublisher internal constructor(
    val server: SyphonServer,
    val source: RenderToTexture,
    private val renderer: Renderer,
) : Target, AutoCloseable {
    private class Slot(val ref: Long, val host: IOSurfaceHostBuffer) {
        val busy = AtomicBoolean(false)
    }

    private val width = source.parameters.width
    private val height = source.parameters.height
    private val slots: List<Slot>
    @Volatile private var closed = false
    private var next = 0

    /** Frames handed to Syphon, and frames skipped because the next surface was still in use. */
    @Volatile
    var published: Long = 0
        private set

    @Volatile
    var skipped: Long = 0
        private set

    init {
        require(source.parameters.format == VK_FORMAT_B8G8R8A8_SRGB || source.parameters.format == VK_FORMAT_B8G8R8A8_UNORM) {
            "followOnGpu needs a B8G8R8A8 RenderToTexture (the default); this one is format ${source.parameters.format}. Use follow() instead."
        }
        require(source.parameters.colorTransferSrc) { "followOnGpu needs colorTransferSrc = true (the default)" }
        check(renderer.device.supportsExternalMemoryHost) {
            "this Vulkan driver has no VK_EXT_external_memory_host, so an IOSurface cannot be imported; use follow() instead"
        }
        slots = List(2) {
            val ref = SyphonNative.ioSurfaceCreate(width, height)
            try {
                Slot(ref, IOSurfaceHostBuffer(renderer.device, ref, readOnly = false))
            } catch (t: Throwable) {
                MetalIOSurface.release(ref)
                throw t
            }
        }
    }

    override fun render(command: CommandBuffer, index: Int) {
        if (closed) return
        val slot = slots[next]
        if (!slot.busy.compareAndSet(false, true)) {
            skipped++
            return
        }
        next = (next + 1) % slots.size
        source.recordColorTransferSource(command) { image -> slot.host.copyFromImage(command.vkCommandBuffer, image, 4) }
        renderer.afterCurrentGraphicsFrameComplete {
            try {
                if (!closed) {
                    server.publishIOSurface(slot.ref)
                    published++
                }
            } catch (t: Throwable) {
                lprintln("error,syphon", "Syphon publish failed: ${t.message}")
            } finally {
                slot.busy.set(false)
            }
        }
    }

    /**
     * Stop publishing. The surfaces are freed once the GPU and Syphon are done with them, which is
     * at most a frame or two later.
     */
    override fun close() {
        if (closed) return
        closed = true
        renderer.renderList.others.entries.removeIf { it.value === this }
        Thread({
            val deadline = System.nanoTime() + 2_000_000_000L
            while (slots.any { it.busy.get() } && System.nanoTime() < deadline) Thread.sleep(5)
            // A frame recorded just before close may not have completed; wait a little longer.
            Thread.sleep(100)
            slots.forEach { runCatching { it.host.close() }; MetalIOSurface.release(it.ref) }
        }, "syphon-gpu-publisher-release").apply { isDaemon = true }.start()
    }

    override fun toString(): String = "SyphonGpuPublisher(${server.name}, ${width}x$height, $published published, $skipped skipped)"
}
