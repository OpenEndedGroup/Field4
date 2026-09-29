package afield.four.targets

import afield.four.device.vkLayout
import afield.four.Renderer
import afield.four.Target
import afield.four.buffers.VulkanBuffer
import afield.four.device.CommandBuffer
import afield.four.device.CommandPool
import afield.four.device.Fence
import afield.four.device.Queue
import afield.four.texture.Image
import afield.four.texture.Image.ImageData
import afield.four.texture.ImageView
import afield.four.texture.image_layout_transition
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.VkBufferImageCopy
import org.lwjgl.vulkan.VkImageCopy
import org.lwjgl.vulkan.VkImageBlit
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Rolling capture that copies a horizontal strip from a source image into an internal snapshot image
 * each frame. Once a full frame has been copied, you can copy the snapshot to a CPU-visible buffer
 * (e.g., on a low-priority transfer queue) without stalling rendering.
 *
 * Usage:
 *  1) Construct with the source image handle/format/extent.
 *  2) Call [recordStrip] at the end of each frame on the graphics command buffer.
 *  3) When [fullFrameReady] becomes true, schedule [copySnapshotToStaging] on a transfer/graphics
 *     command buffer, then read [stagingBuffer] on the CPU. After copying, [fullFrameReady] is reset.
 */
class RollingCapture(
    private val renderer: Renderer,
    private val source: Image,
    private val rowsPerFrame: Int = 16,
    private val sourceLayout: Int = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
    private val downsample: Int = 1 // >1 will downscale source by this factor on ingest
) {
    private val targetWidth = source.imageData.width / downsample
    private val targetHeight = source.imageData.height / downsample

    private val snapshotImageData = ImageData()
        .width(targetWidth)
        .height(targetHeight)
        .format(source.imageData.format)
        .mipLevels(1)
        .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT or VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
    private val snapshotImage = Image(renderer.device, snapshotImageData)
    val snapshotView = ImageView(
        renderer.device,
        snapshotImage,
        ImageView.ImageViewData()
            .format(source.imageData.format)
            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
            .mipLevels(1)
    )

    val stagingBuffer = VulkanBuffer(
        renderer = renderer,
        requestedSize = targetWidth.toLong() * targetHeight.toLong() * source.imageData.bytesPerPixel,
        usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT,
        reqMask = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
    )

    private var snapshotLayout: Int = VK_IMAGE_LAYOUT_UNDEFINED
    private var nextRow: Int = 0
    var fullFrameReady: Boolean = false
        private set

    // Dedicated transfer queue/pool/buffer for async staging copies (low-priority queue if available)
    private val transferQueue: Queue
    private val transferPool: CommandPool
    private val transferCmd: CommandBuffer
    @Volatile private var readerRunning = false
    private var readerThread: Thread? = null
    private var onFrame: ((ByteBuffer) -> Unit)? = null

    init {
        val transferFamily = pickTransferFamily(renderer.device)
        transferQueue = Queue(renderer.device, transferFamily, 0)
        transferPool = CommandPool(renderer.device, transferQueue.queueFamilyIndex)
        transferCmd = CommandBuffer(transferPool, primary = true, oneTimeSubmit = true)
    }

    /**
     * Record a strip copy from the source image into the snapshot image.
     * Call this at the end of your frame on the graphics command buffer.
     */
    fun recordStrip(command: CommandBuffer) {
        if (fullFrameReady) return

        val rows = rowsPerFrame.coerceAtMost(targetHeight - nextRow)
        MemoryStack.stackPush().use { stack ->
            val range = org.lwjgl.vulkan.VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1)

            // Src: previous -> TRANSFER_SRC
            image_layout_transition(
                command.vkCommandBuffer,
                source,
                VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT, // conservative; about to copy
                VK_ACCESS_2_SHADER_READ_BIT,
                0,
                sourceLayout,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                range
            )

            // Dst: previous -> TRANSFER_DST
            image_layout_transition(
                command.vkCommandBuffer,
                snapshotImage,
                VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT,
                0,
                VK_ACCESS_2_TRANSFER_WRITE_BIT,
                snapshotLayout,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                range
            )

            if (downsample <= 1) {
                val copy = VkImageCopy.calloc(1, stack)
                copy[0]
                    .srcSubresource { it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1) }
                    .dstSubresource { it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1) }
                    .srcOffset { it.x(0).y(nextRow).z(0) }
                    .dstOffset { it.x(0).y(nextRow).z(0) }
                    .extent { it.width(targetWidth).height(rows).depth(1) }

                vkCmdCopyImage(
                    command.vkCommandBuffer,
                    source.vkImage, vkLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL),
                    snapshotImage.vkImage, vkLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL),
                    copy
                )
            } else {
                val srcY0 = nextRow * downsample
                val srcY1 = (nextRow + rows) * downsample
                val blit = VkImageBlit.calloc(1, stack)
                blit[0]
                    .srcSubresource { it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1) }
                    .dstSubresource { it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1) }
                    .srcOffsets(0).set(0, srcY0, 0)
                blit[0].srcOffsets(1).set(targetWidth * downsample, srcY1, 1)
                blit[0].dstOffsets(0).set(0, nextRow, 0)
                blit[0].dstOffsets(1).set(targetWidth, nextRow + rows, 1)

                vkCmdBlitImage(
                    command.vkCommandBuffer,
                    source.vkImage, vkLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL),
                    snapshotImage.vkImage, vkLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL),
                    blit,
                    VK_FILTER_LINEAR
                )
            }

            snapshotLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL

            // Restore src to its expected layout
            image_layout_transition(
                command.vkCommandBuffer,
                source,
                VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                0,
                VK_ACCESS_2_SHADER_READ_BIT,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                sourceLayout,
                range
            )
        }

        nextRow += rows
        if (nextRow >= targetHeight) {
            fullFrameReady = true
            nextRow = 0
        }
    }

    /**
     * Copy the accumulated snapshot image to the staging buffer.
     * Call on a transfer/graphics command buffer after checking [fullFrameReady].
     */
    fun copySnapshotToStaging(command: CommandBuffer) {
        if (!fullFrameReady) return

        MemoryStack.stackPush().use { stack ->
            val range = org.lwjgl.vulkan.VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1)

            image_layout_transition(
                command.vkCommandBuffer,
                snapshotImage,
                VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT,
                0,
                VK_ACCESS_2_TRANSFER_READ_BIT,
                snapshotLayout,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                range
            )

            val copy = VkBufferImageCopy.calloc(1, stack)
            copy[0]
                .bufferOffset(0L)
                .bufferRowLength(0)
                .bufferImageHeight(0)
                .imageSubresource { it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1) }
                .imageOffset { it.x(0).y(0).z(0) }
                .imageExtent { it.width(targetWidth).height(targetHeight).depth(1) }

            vkCmdCopyImageToBuffer(
                command.vkCommandBuffer,
                snapshotImage.vkImage,
                vkLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL),
                stagingBuffer.buffer,
                copy
            )

            // Keep snapshot ready for next rolling copy
            snapshotLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
            snapshotImage.setLayoutFor(command.vkCommandBuffer.address(), snapshotLayout)
        }

        fullFrameReady = false
    }

    /**
     * Convenience: records and submits the staging copy on the internal transfer queue.
     * Returns a fence you can wait on from a CPU thread.
     */
    fun copySnapshotToStagingAsync(): Fence {
        if (!fullFrameReady) return Fence(renderer.device, true)

        transferCmd.reset()
        transferCmd.beginRecording()
        copySnapshotToStaging(transferCmd)
        transferCmd.endRecording()

        val fence = Fence(renderer.device, signaled = false)
        MemoryStack.stackPush().use { stack ->
            val pCmd = stack.pointers(transferCmd.vkCommandBuffer.address())
            transferQueue.submit(pCmd, null, null, null, fence)
        }
        return fence
    }

    /**
    * Start a background thread that waits for full captures, kicks the async staging copy,
    * waits the fence, and hands the CPU buffer to [onFrame].
    */
    fun startBackgroundRead(onFrame: (ByteBuffer) -> Unit, sleepMs: Long = 1L) {
        if (readerRunning) return
        readerRunning = true
        this.onFrame = onFrame
        readerThread = thread(name = "RollingCaptureReader", isDaemon = true) {
            while (readerRunning) {
                if (fullFrameReady) {
                    val fence = copySnapshotToStagingAsync()
                    fence.fenceWait()
                    stagingBuffer.mapAndRead_bytes { onFrame(this) }
                    fence.cleanup()
                } else {
                    try { Thread.sleep(sleepMs) } catch (_: InterruptedException) { break }
                }
            }
        }
    }

    fun stopBackgroundRead() {
        readerRunning = false
        readerThread?.interrupt()
        readerThread = null
        onFrame = null
    }

    fun cleanup() {
        snapshotView.cleanup()
        snapshotImage.cleanup()
        stagingBuffer.cleanup()
        transferCmd.cleanup()
        transferPool.cleanup()
        stopBackgroundRead()
    }

    fun install(r : Renderer, onFrame: (ByteBuffer) -> Unit, name : String = "__rolling_capture__") {
        r.renderList.others[name] =  Target { command, index ->
            recordStrip(command)
            copySnapshotToStaging(command)
        }
        startBackgroundRead(onFrame)
    }
}

// Pick a transfer-capable family if available; fall back to graphics family 0.
private fun pickTransferFamily(device: afield.four.device.Device): Int {
    val props = device.physicalDevice.vkQueueFamilyProps
    var transferFamily = -1
    for (i in 0 until props.capacity()) {
        val flags = props[i].queueFlags()
        val transferOnly = (flags and VK_QUEUE_TRANSFER_BIT) != 0 &&
                (flags and VK_QUEUE_GRAPHICS_BIT) == 0 &&
                (flags and VK_QUEUE_COMPUTE_BIT) == 0
        if (transferOnly) {
            transferFamily = i
            break
        }
    }
    if (transferFamily < 0) {
        // fallback: first transfer-capable family (even if also graphics/compute)
        for (i in 0 until props.capacity()) {
            if ((props[i].queueFlags() and VK_QUEUE_TRANSFER_BIT) != 0) {
                transferFamily = i
                break
            }
        }
    }
    if (transferFamily < 0) transferFamily = 0
    return transferFamily
}
