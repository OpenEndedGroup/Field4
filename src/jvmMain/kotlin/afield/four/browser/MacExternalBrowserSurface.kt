package afield.four.browser

import afield.four.Renderer
import afield.four.device.CommandBuffer
import afield.four.texture.IOSurfaceHostBuffer
import afield.four.texture.Image
import afield.four.texture.MetalIOSurface
import afield.four.texture.image_layout_transition
import afield.four.util.OS
import afield.four.util.OSType
import afield.logger.lprintln
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.VkImageSubresourceRange

/**
 * macOS implementation of [ExternalBrowserSurface]. A native producer transfers a Mach send right
 * for Electron's process-local IOSurface; this receiver resolves and imports it, while
 * [CopiedExternalBrowserSurface] copies into a stable image and acknowledges only after the fence.
 *
 * Two ways in, by driver. MoltenVK backs a `VkImage` with the IOSurface itself
 * (`VK_EXT_metal_objects`), and the base class copies straight out of it. KosmicKrisp cannot back an
 * image with an IOSurface at all (see [IOSurfaceHostBuffer]), so there the surface's pages become a
 * buffer and [recordPreCopy] copies them into a per-slot staging image first — one more GPU copy,
 * no CPU copy, and every other part of the frame lifecycle unchanged.
 */
class MacExternalBrowserSurface(
    renderer: Renderer,
    spec: ExternalBrowserSurfaceSpec,
    releaseListener: BrowserFrameReleaseListener = BrowserFrameReleaseListener { }
) : CopiedExternalBrowserSurface(renderer, spec, releaseListener) {
    private val importedSlots = mutableMapOf<Int, ImportedImage>()
    private var importedStreamId: Long? = null

    /** KosmicKrisp's route: no image-level IOSurface import, but host-pointer buffers. */
    private val viaHostBuffer = !renderer.device.supportsMetalObjects && renderer.device.supportsExternalMemoryHost

    /** One slot's surface as a buffer, and the device-local image the resident copy reads from. */
    private inner class HostSlot(val ioSurfaceRef: Long, slot: Int, identity: Any) {
        val host = IOSurfaceHostBuffer(renderer.device, ioSurfaceRef, readOnly = true)
        val image = try {
            Image(
                renderer.device,
                Image.ImageData()
                    .width(spec.width)
                    .height(spec.height)
                    .format(importVkFormat)
                    .mipLevels(1)
                    .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT or VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                    .debugName("external-browser-iosurface-staging slot=$slot handle=$identity")
            )
        } catch (t: Throwable) {
            host.close()
            throw t
        }

        fun cleanup() {
            host.close()
            image.cleanup()
            MetalIOSurface.release(ioSurfaceRef)
        }
    }

    private val hostSlots = mutableMapOf<Int, HostSlot>()

    /** Set by [importFrame] and consumed by the very next [recordPreCopy]; prepare is not reentrant. */
    private var pendingUpload: HostSlot? = null

    private val bytesPerPixel = when (spec.pixelFormat) {
        BrowserSurfacePixelFormat.RGBA16_FLOAT -> 8
        else -> 4
    }

    init {
        require(OS == OSType.MACOS) { "MacExternalBrowserSurface requires macOS" }
        if (!viaHostBuffer) renderer.device.requireMetalObjects("MacExternalBrowserSurface")
    }

    override fun importFrame(frame: ExternalBrowserFrame): ImportedImage? {
        if (frame.streamId != importedStreamId) {
            importedSlots.values.forEach(::retire)
            importedSlots.clear()
            hostSlots.clear() // their cleanup is inside the retired ImportedImages
            importedStreamId = frame.streamId
        }
        if (frame.handle == null) return importedSlots[frame.slot]?.also { markUpload(frame.slot) }

        val (identity, ioSurfaceRef) = try {
            when (val handle = frame.handle) {
                is ExternalBrowserImageHandle.MacIOSurfaceMachPort -> {
                    val ref = MetalIOSurface.lookupFromMachPort(handle.value)
                    MetalIOSurface.ioSurfaceId(ref) to ref
                }

                is ExternalBrowserImageHandle.MacIOSurfaceId ->
                    handle.value to MetalIOSurface.lookup(handle.value)

                else -> return null
            }
        } finally {
            // Lookup retained the IOSurface; the transport right is no longer needed here.
            runCatching { frame.handle.close() }
                .onFailure {
                    lprintln("warning,graphics", "could not release browser transport handle: ${it.message}")
                }
        }

        importedSlots[frame.slot]?.takeIf { it.identity == identity }?.let {
            // A repeated Mach port still transferred a fresh send right and lookup retain.
            if (ioSurfaceRef != 0L) MetalIOSurface.release(ioSurfaceRef)
            markUpload(frame.slot)
            return it
        }
        if (ioSurfaceRef == 0L) return null

        val imported = if (viaHostBuffer) {
            val slot = try {
                HostSlot(ioSurfaceRef, frame.slot, identity)
            } catch (t: Throwable) {
                MetalIOSurface.release(ioSurfaceRef)
                throw t
            }
            hostSlots[frame.slot] = slot
            ImportedImage(identity, slot.image.vkImage, { slot.cleanup() })
        } else {
            val image = try {
                Image(
                    renderer.device,
                    Image.ImageData()
                        .width(spec.width)
                        .height(spec.height)
                        .format(importVkFormat)
                        .mipLevels(1)
                        .usage(VK_IMAGE_USAGE_TRANSFER_SRC_BIT or VK_IMAGE_USAGE_SAMPLED_BIT)
                        .debugName("external-browser-iosurface slot=${frame.slot} handle=$identity")
                        .also { it.importMetalIOSurface = ioSurfaceRef }
                )
            } catch (t: Throwable) {
                MetalIOSurface.release(ioSurfaceRef)
                throw t
            }
            ImportedImage(identity, image.vkImage, {
                image.cleanup()
                MetalIOSurface.release(ioSurfaceRef)
            })
        }
        importedSlots.put(frame.slot, imported)?.let(::retire)
        markUpload(frame.slot)
        return imported
    }

    // Electron redraws into the same IOSurface, so a host-buffer slot re-copies on every frame,
    // cached or not; an imported image needs nothing, its memory is the surface.
    private fun markUpload(slot: Int) {
        if (viaHostBuffer) pendingUpload = hostSlots[slot]
    }

    override fun recordPreCopy(command: CommandBuffer, source: ImportedImage) {
        val slot = pendingUpload ?: return
        pendingUpload = null
        MemoryStack.stackPush().use { stack ->
            val range = VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1)
            // Entirely overwritten below, so discarding the previous contents is correct.
            image_layout_transition(
                command.vkCommandBuffer, slot.image.vkImage,
                VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_2_COPY_BIT,
                0, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, range
            )
            slot.host.copyToImage(command.vkCommandBuffer, slot.image.vkImage, bytesPerPixel)
        }
        // recordCopy transitions from here to TRANSFER_SRC_OPTIMAL for the copy into the resident
        // texture, so tell it where the image actually is.
        source.layout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
    }

    override fun cleanupImports() {
        pendingUpload = null
        importedSlots.values.forEach { it.cleanup() }
        importedSlots.clear()
        hostSlots.clear()
    }
}
