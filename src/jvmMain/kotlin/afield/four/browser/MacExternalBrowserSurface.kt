package afield.four.browser

import afield.four.Renderer
import afield.four.device.CommandBuffer
import afield.four.texture.HasImageView
import afield.four.texture.Image
import afield.four.texture.ImageView
import afield.four.texture.MetalIOSurface
import afield.four.texture.image_layout_transition
import afield.four.util.OS
import afield.four.util.OSType
import afield.logger.lprintln
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_UNORM
import org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT
import org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM
import org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED
import org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT
import org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT
import org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT
import org.lwjgl.vulkan.VK10.vkCmdClearColorImage
import org.lwjgl.vulkan.VK10.vkCmdCopyImage
import org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_READ_BIT
import org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT
import org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT
import org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT
import org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COPY_BIT
import org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT
import org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT
import org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT
import org.lwjgl.vulkan.VkClearColorValue
import org.lwjgl.vulkan.VkImageCopy
import org.lwjgl.vulkan.VkImageSubresourceRange
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * MoltenVK implementation of [ExternalBrowserSurface].
 *
 * Electron's `paint` event owns a finite-lived Chromium texture. A native producer transfers a
 * Mach send right for its process-local IOSurface; IPC supplies the resulting receiver-local port
 * name in [ExternalBrowserFrame]. This class resolves and imports the IOSurface, copies it into one
 * stable device-local image, and acknowledges the frame only after the graphics fence has
 * signalled. Field therefore keeps displaying the last browser paint while Chromium is free to
 * recycle its texture.
 *
 * The surface has a fixed coded size. Resize by closing it, resizing the offscreen BrowserWindow,
 * and constructing a new surface; this keeps VkImage/View replacement out of in-flight descriptor
 * sets.
 */
class MacExternalBrowserSurface(
    private val renderer: Renderer,
    override val spec: ExternalBrowserSurfaceSpec,
    releaseListener: BrowserFrameReleaseListener = BrowserFrameReleaseListener { }
) : ExternalBrowserSurface {
    private data class FrameKey(val streamId: Long, val frameId: Long, val releaseToken: Long)

    private data class ImportedSlot(
        val ioSurfaceId: Long,
        val ioSurfaceRef: Long,
        val image: Image,
        var layout: Int = VK_IMAGE_LAYOUT_UNDEFINED
    ) {
        fun cleanup() {
            image.cleanup()
            MetalIOSurface.release(ioSurfaceRef)
        }
    }

    private val inFlight = ConcurrentHashMap<FrameKey, ExternalBrowserFrame>()
    private val onRelease = BrowserFrameReleaseListener { release ->
        runCatching { releaseListener.released(release) }
            .onFailure { lprintln("warning,graphics", "browser frame release listener failed: ${it.message}") }
    }
    private val mailbox = ExternalBrowserFrameMailbox(spec, onRelease)
    private val importedSlots = mutableMapOf<Int, ImportedSlot>()
    private var importedStreamId: Long? = null
    private val currentFrame = AtomicReference<ExternalBrowserFrame?>(null)

    private val vkFormat = spec.pixelFormat.vkFormat().also {
        require(OS == OSType.MACOS) { "MacExternalBrowserSurface requires macOS/MoltenVK" }
        require(renderer.device.supportsMetalObjects) {
            "MacExternalBrowserSurface requires ${org.lwjgl.vulkan.EXTMetalObjects.VK_EXT_METAL_OBJECTS_EXTENSION_NAME}"
        }
    }
    private val residentImage = Image(
        renderer.device,
        Image.ImageData()
            .width(spec.width)
            .height(spec.height)
            .format(vkFormat)
            .mipLevels(1)
            .usage(VK_IMAGE_USAGE_TRANSFER_SRC_BIT or VK_IMAGE_USAGE_TRANSFER_DST_BIT or VK_IMAGE_USAGE_SAMPLED_BIT)
            .debugName("external-browser-resident ${spec.width}x${spec.height}")
    )
    private val residentView = ImageView(
        renderer.device,
        residentImage,
        ImageView.ImageViewData()
            .format(vkFormat)
            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
            .mipLevels(1)
            .debugName("external-browser-resident-view ${spec.width}x${spec.height}")
    )

    @Volatile
    private var residentLayout = VK_IMAGE_LAYOUT_UNDEFINED
    @Volatile
    private var closed = false

    override val imageView: ImageView
        get() = residentView

    override val imageViewArray: List<HasImageView>
        get() = listOf(this)

    override val presentedFrame: ExternalBrowserFrame?
        get() = currentFrame.get()

    override fun offerFrame(frame: ExternalBrowserFrame): BrowserFrameOfferResult = mailbox.offer(frame)

    override fun prepareSampledImagesFor(command: CommandBuffer) {
        if (closed) return
        check(!command.inRenderPassInstance) {
            "External browser texture copies must be recorded before dynamic rendering begins"
        }

        val frame = mailbox.takeLatest()
        if (frame == null) {
            ensureResidentInitialized(command)
            return
        }

        val imported = runCatching { importSlot(frame) }
            .onFailure {
                lprintln(
                    "warning,graphics",
                    "could not import browser frame ${frame.streamId}:${frame.frameId}: ${it.message ?: it::class.simpleName}"
                )
            }
            .getOrNull()

        if (imported == null) {
            release(frame, BrowserFrameReleaseReason.IMPORT_FAILED)
            ensureResidentInitialized(command)
            return
        }

        recordCopy(command, imported)
        val key = frame.key()
        inFlight[key] = frame
        renderer.afterCurrentGraphicsFrameComplete {
            if (inFlight.remove(key, frame)) {
                currentFrame.set(frame.copy(handle = null))
                release(frame, BrowserFrameReleaseReason.PRESENTED)
            }
        }
    }

    private fun importSlot(frame: ExternalBrowserFrame): ImportedSlot? {
        if (frame.streamId != importedStreamId) {
            retireImportedSlots()
            importedStreamId = frame.streamId
        }
        if (frame.handle == null) return importedSlots[frame.slot]
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
            // lookup retained the IOSurface; the transport right is no longer needed here.
            runCatching { frame.handle.close() }
                .onFailure {
                    lprintln("warning,graphics", "could not release browser transport handle: ${it.message}")
                }
        }
        importedSlots[frame.slot]?.takeIf { it.ioSurfaceId == identity }?.let {
            // A repeated Mach port still transferred a fresh send right and lookup retain. The
            // existing import owns the retained ref it needs; balance this redundant lookup.
            if (ioSurfaceRef != 0L) MetalIOSurface.release(ioSurfaceRef)
            return it
        }
        if (ioSurfaceRef == 0L) return null

        val imported = try {
            val data = Image.ImageData()
                .width(spec.width)
                .height(spec.height)
                .format(vkFormat)
                .mipLevels(1)
                .usage(VK_IMAGE_USAGE_TRANSFER_SRC_BIT or VK_IMAGE_USAGE_SAMPLED_BIT)
                .debugName("external-browser-iosurface slot=${frame.slot} handle=$identity")
            data.importMetalIOSurface = ioSurfaceRef
            ImportedSlot(identity, ioSurfaceRef, Image(renderer.device, data))
        } catch (t: Throwable) {
            MetalIOSurface.release(ioSurfaceRef)
            throw t
        }

        importedSlots.put(frame.slot, imported)?.let { retired ->
            // Replacement can occur while a previous graphics frame still references this import.
            // Field's deferred render cleanup is deliberately several frames behind the GPU.
            renderer.cleanup.add(Runnable { retired.cleanup() })
        }
        return imported
    }

    private fun retireImportedSlots() {
        importedSlots.values.forEach { retired ->
            // An import from the previous producer stream may still occur in an in-flight copy.
            renderer.cleanup.add(Runnable { retired.cleanup() })
        }
        importedSlots.clear()
    }

    private fun recordCopy(command: CommandBuffer, source: ImportedSlot) {
        MemoryStack.stackPush().use { stack ->
            val range = VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1)

            image_layout_transition(
                command.vkCommandBuffer,
                source.image,
                if (source.layout == VK_IMAGE_LAYOUT_UNDEFINED) VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT else VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                VK_PIPELINE_STAGE_2_COPY_BIT,
                0,
                VK_ACCESS_2_TRANSFER_READ_BIT,
                source.layout,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                range
            )
            image_layout_transition(
                command.vkCommandBuffer,
                residentImage,
                if (residentLayout == VK_IMAGE_LAYOUT_UNDEFINED) VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT else sampledStages,
                VK_PIPELINE_STAGE_2_COPY_BIT,
                if (residentLayout == VK_IMAGE_LAYOUT_UNDEFINED) 0 else VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                VK_ACCESS_2_TRANSFER_WRITE_BIT,
                residentLayout,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                range
            )

            val region = VkImageCopy.calloc(1, stack)
            region[0]
                .srcSubresource {
                    it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1)
                }
                .srcOffset { it.set(0, 0, 0) }
                .dstSubresource {
                    it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1)
                }
                .dstOffset { it.set(0, 0, 0) }
                .extent { it.set(spec.width, spec.height, 1) }

            vkCmdCopyImage(
                command.vkCommandBuffer,
                source.image.vkImage,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                residentImage.vkImage,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                region
            )

            image_layout_transition(
                command.vkCommandBuffer,
                source.image,
                VK_PIPELINE_STAGE_2_COPY_BIT,
                VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                VK_ACCESS_2_TRANSFER_READ_BIT,
                0,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_IMAGE_LAYOUT_GENERAL,
                range
            )
            image_layout_transition(
                command.vkCommandBuffer,
                residentImage,
                VK_PIPELINE_STAGE_2_COPY_BIT,
                sampledStages,
                VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                range
            )
        }
        source.layout = VK_IMAGE_LAYOUT_GENERAL
        residentLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
    }

    private fun ensureResidentInitialized(command: CommandBuffer) {
        if (residentLayout != VK_IMAGE_LAYOUT_UNDEFINED) return
        MemoryStack.stackPush().use { stack ->
            val range = VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1)
            image_layout_transition(
                command.vkCommandBuffer,
                residentImage,
                VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_2_COPY_BIT,
                0,
                VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                range
            )
            val black = VkClearColorValue.calloc(stack).float32(0, 0f).float32(1, 0f).float32(2, 0f).float32(3, 1f)
            vkCmdClearColorImage(
                command.vkCommandBuffer,
                residentImage.vkImage,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                black,
                range
            )
            image_layout_transition(
                command.vkCommandBuffer,
                residentImage,
                VK_PIPELINE_STAGE_2_COPY_BIT,
                sampledStages,
                VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                range
            )
        }
        residentLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
    }

    override fun close() {
        if (closed) return
        closed = true
        mailbox.close()
        renderer.device.waitIdle()
        inFlight.entries.toList().forEach { (key, frame) ->
            if (inFlight.remove(key, frame)) release(frame, BrowserFrameReleaseReason.CLOSED)
        }
        residentView.cleanup()
        residentImage.cleanup()
        importedSlots.values.forEach { it.cleanup() }
        importedSlots.clear()
    }

    private fun release(frame: ExternalBrowserFrame, reason: BrowserFrameReleaseReason) {
        onRelease.released(
            BrowserFrameRelease(frame.streamId, frame.frameId, frame.releaseToken, frame.slot, reason)
        )
    }

    private fun ExternalBrowserFrame.key() = FrameKey(streamId, frameId, releaseToken)

    private fun BrowserSurfacePixelFormat.vkFormat(): Int = when (this) {
        BrowserSurfacePixelFormat.BGRA8_UNORM -> VK_FORMAT_B8G8R8A8_UNORM
        BrowserSurfacePixelFormat.RGBA8_UNORM -> VK_FORMAT_R8G8B8A8_UNORM
        BrowserSurfacePixelFormat.RGBA16_FLOAT -> VK_FORMAT_R16G16B16A16_SFLOAT
    }

    private companion object {
        const val sampledStages = VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT or VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT
    }
}
