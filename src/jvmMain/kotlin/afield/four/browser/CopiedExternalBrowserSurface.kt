package afield.four.browser

import afield.four.Renderer
import afield.four.device.CommandBuffer
import afield.four.texture.HasImageView
import afield.four.texture.Image
import afield.four.texture.ImageView
import afield.four.texture.image_layout_transition
import afield.logger.lprintln
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.VkClearColorValue
import org.lwjgl.vulkan.VkImageCopy
import org.lwjgl.vulkan.VkImageSubresourceRange
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * The platform-independent half of all native browser receivers. A backend only imports a frame's
 * native image; this class owns conflation, the stable resident texture, copies, fence-delayed
 * release acknowledgements, and close ordering.
 */
sealed class CopiedExternalBrowserSurface(
    protected val renderer: Renderer,
    final override val spec: ExternalBrowserSurfaceSpec,
    releaseListener: BrowserFrameReleaseListener
) : ExternalBrowserSurface {
    protected class ImportedImage(
        val identity: Any,
        val image: Long,
        private val cleanupAction: () -> Unit,
        var layout: Int = VK_IMAGE_LAYOUT_UNDEFINED,
        val externalQueueFamily: Int = VK_QUEUE_FAMILY_IGNORED
    ) {
        fun cleanup() = cleanupAction()
    }

    private data class FrameKey(val streamId: Long, val frameId: Long, val releaseToken: Long)

    private val inFlight = ConcurrentHashMap<FrameKey, ExternalBrowserFrame>()
    protected val onRelease = BrowserFrameReleaseListener { release ->
        runCatching { releaseListener.released(release) }
            .onFailure { lprintln("warning,graphics", "browser frame release listener failed: ${it.message}") }
    }
    private val mailbox = ExternalBrowserFrameMailbox(spec, onRelease)
    private val currentFrame = AtomicReference<ExternalBrowserFrame?>(null)

    // External APIs describe the allocation's channel storage separately from its color space.
    // Import the raw bytes, then copy them into the view format Field should sample.
    protected val importVkFormat = spec.pixelFormat.storageVkFormat()
    private val residentVkFormat = spec.pixelFormat.sampledVkFormat()
    private val residentImage = Image(
        renderer.device,
        Image.ImageData()
            .width(spec.width)
            .height(spec.height)
            .format(residentVkFormat)
            .mipLevels(1)
            .usage(VK_IMAGE_USAGE_TRANSFER_SRC_BIT or VK_IMAGE_USAGE_TRANSFER_DST_BIT or VK_IMAGE_USAGE_SAMPLED_BIT)
            .debugName("external-browser-resident ${spec.width}x${spec.height}")
    )
    private val residentView = ImageView(
        renderer.device,
        residentImage,
        ImageView.ImageViewData()
            .format(residentVkFormat)
            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
            .mipLevels(1)
            .debugName("external-browser-resident-view ${spec.width}x${spec.height}")
    )

    @Volatile
    private var residentLayout = VK_IMAGE_LAYOUT_UNDEFINED
    @Volatile
    private var closed = false

    final override val imageView: ImageView
        get() = residentView

    final override val imageViewArray: List<HasImageView>
        get() = listOf(this)

    final override val presentedFrame: ExternalBrowserFrame?
        get() = currentFrame.get()

    final override fun offerFrame(frame: ExternalBrowserFrame): BrowserFrameOfferResult = mailbox.offer(frame)

    final override fun prepareSampledImagesFor(command: CommandBuffer) {
        if (closed) return
        check(!command.inRenderPassInstance) {
            "External browser texture copies must be recorded before dynamic rendering begins"
        }

        val frame = mailbox.takeLatest()
        if (frame == null) {
            ensureResidentInitialized(command)
            return
        }

        val imported = runCatching { importFrame(frame) }
            .onFailure {
                lprintln(
                    "warning,graphics",
                    "could not import browser frame ${frame.streamId}:${frame.frameId}: ${it.message ?: it::class.simpleName}"
                )
            }
            .getOrNull()

        if (imported == null) {
            // A backend may reject the handle type before its normal import/consume path.
            runCatching { frame.handle?.close() }
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

    /** Return the imported image for [frame], using a slot cache when its handle is omitted. */
    protected abstract fun importFrame(frame: ExternalBrowserFrame): ImportedImage?

    /** Destroy currently cached imports. The device is idle when this is called. */
    protected abstract fun cleanupImports()

    protected fun retire(imported: ImportedImage) {
        // Replacement can occur while a previous graphics frame still references this image.
        renderer.cleanup.add(Runnable { imported.cleanup() })
    }

    private fun recordCopy(command: CommandBuffer, source: ImportedImage) {
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
                if (source.externalQueueFamily != VK_QUEUE_FAMILY_IGNORED) VK_PIPELINE_STAGE_2_NONE
                else if (source.layout == VK_IMAGE_LAYOUT_UNDEFINED) VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT
                else VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                VK_PIPELINE_STAGE_2_COPY_BIT,
                0,
                VK_ACCESS_2_TRANSFER_READ_BIT,
                source.layout,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                range,
                source.externalQueueFamily,
                if (source.externalQueueFamily == VK_QUEUE_FAMILY_IGNORED) VK_QUEUE_FAMILY_IGNORED
                else renderer.graphQueue.queueFamilyIndex
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
                source.image,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                residentImage.vkImage,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                region
            )

            image_layout_transition(
                command.vkCommandBuffer,
                source.image,
                VK_PIPELINE_STAGE_2_COPY_BIT,
                if (source.externalQueueFamily == VK_QUEUE_FAMILY_IGNORED) VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT
                else VK_PIPELINE_STAGE_2_NONE,
                VK_ACCESS_2_TRANSFER_READ_BIT,
                0,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_IMAGE_LAYOUT_GENERAL,
                range,
                if (source.externalQueueFamily == VK_QUEUE_FAMILY_IGNORED) VK_QUEUE_FAMILY_IGNORED
                else renderer.graphQueue.queueFamilyIndex,
                source.externalQueueFamily
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
                VK_PIPELINE_STAGE_2_CLEAR_BIT,
                0,
                VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                range
            )
            val black = VkClearColorValue.calloc(stack)
                .float32(0, 0f).float32(1, 0f).float32(2, 0f).float32(3, 1f)
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
                VK_PIPELINE_STAGE_2_CLEAR_BIT,
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

    final override fun close() {
        if (closed) return
        closed = true
        mailbox.close()
        renderer.device.waitIdle()
        inFlight.entries.toList().forEach { (key, frame) ->
            if (inFlight.remove(key, frame)) release(frame, BrowserFrameReleaseReason.CLOSED)
        }
        residentView.cleanup()
        residentImage.cleanup()
        cleanupImports()
    }

    private fun release(frame: ExternalBrowserFrame, reason: BrowserFrameReleaseReason) {
        onRelease.released(
            BrowserFrameRelease(frame.streamId, frame.frameId, frame.releaseToken, frame.slot, reason)
        )
    }

    private fun ExternalBrowserFrame.key() = FrameKey(streamId, frameId, releaseToken)

    private fun BrowserSurfacePixelFormat.sampledVkFormat(): Int = when (this) {
        BrowserSurfacePixelFormat.BGRA8_SRGB -> VK_FORMAT_B8G8R8A8_SRGB
        BrowserSurfacePixelFormat.RGBA8_SRGB -> VK_FORMAT_R8G8B8A8_SRGB
        BrowserSurfacePixelFormat.BGRA8_UNORM -> VK_FORMAT_B8G8R8A8_UNORM
        BrowserSurfacePixelFormat.RGBA8_UNORM -> VK_FORMAT_R8G8B8A8_UNORM
        BrowserSurfacePixelFormat.RGBA16_FLOAT -> VK_FORMAT_R16G16B16A16_SFLOAT
    }

    private fun BrowserSurfacePixelFormat.storageVkFormat(): Int = when (this) {
        BrowserSurfacePixelFormat.BGRA8_SRGB,
        BrowserSurfacePixelFormat.BGRA8_UNORM -> VK_FORMAT_B8G8R8A8_UNORM
        BrowserSurfacePixelFormat.RGBA8_SRGB,
        BrowserSurfacePixelFormat.RGBA8_UNORM -> VK_FORMAT_R8G8B8A8_UNORM
        BrowserSurfacePixelFormat.RGBA16_FLOAT -> VK_FORMAT_R16G16B16A16_SFLOAT
    }

    private companion object {
        const val sampledStages = VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT or VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT
    }
}
