package afield.four.texture

import org.lwjgl.stb.STBImage.*
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.*
import afield.four.Renderer
import afield.four.buffers.VulkanBuffer

import afield.four.texture.Image.ImageData
import afield.four.texture.ImageView.ImageViewData
import afield.four.device.CommandBuffer
import afield.four.device.Device
import afield.four.modules.fromContext
import org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SRGB
import java.nio.ByteBuffer

class LoadedTexture(fileName: String, val renderer: Renderer = fromContext(), imageFormat: Int = VK_FORMAT_R8G8B8A8_SRGB) {
    val fileName: String
    var height = 0
    lateinit var image: Image
    lateinit var imageView: ImageView
    var mipLevels = 0
    var width = 0
    var recordedTransition: Boolean
    var stgBuffer: VulkanBuffer? = null

    init {
        recordedTransition = false
        this.fileName = fileName
        var buf: ByteBuffer? = null
        try {
            MemoryStack.stackPush().use { stack ->
                val w = stack.mallocInt(1)
                val h = stack.mallocInt(1)
                val channels = stack.mallocInt(1)

                buf = stbi_load(fileName, w, h, channels, 4)
                if (buf == null) {
                    throw RuntimeException("Image file [" + fileName + "] not loaded: " + stbi_failure_reason())
                }

                width = w.get()
                height = h.get()
                mipLevels = 1

                createStgBuffer(renderer.device, buf!!)
                val imageData: ImageData = ImageData().width(width).height(height)
                    .usage(VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT or VK10.VK_IMAGE_USAGE_SAMPLED_BIT).format(imageFormat)
                    .mipLevels(mipLevels)

                image = Image(renderer.device, imageData)
                val imageViewData: ImageViewData =
                    ImageViewData().format(image.format).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevels(mipLevels)
                imageView = ImageView(renderer.device, image.vkImage, imageViewData)
            }
        } finally {
            buf?.let {
                stbi_image_free(it)
            }
        }
    }

    fun cleanup() {
        cleanupStgBuffer()
        imageView.cleanup()
        image.cleanup()
    }

    fun cleanupStgBuffer() {
        if (stgBuffer != null) {
            stgBuffer!!.cleanup()
            stgBuffer = null
        }
    }

    private fun createStgBuffer(device: Device, data: ByteBuffer) {
        val size = data.remaining()
        stgBuffer = VulkanBuffer(
            renderer,
            size.toLong(),
            VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
            VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
        )
        val mappedMemory = stgBuffer!!.map()
        val buffer = MemoryUtil.memByteBuffer(mappedMemory, stgBuffer!!.requestedSize.toInt())
        buffer.put(data)
        data.flip()
        stgBuffer!!.unmap()
    }


    private fun recordCopyBuffer(stack: MemoryStack, cmd: CommandBuffer, bufferData: VulkanBuffer) {
        val region = VkBufferImageCopy.calloc(1, stack).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0)
            .imageSubresource { it: VkImageSubresourceLayers ->
                it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1)
            }.imageOffset { it: VkOffset3D -> it.x(0).y(0).z(0) }.imageExtent { it: VkExtent3D ->
                it.width(width).height(height).depth(1)
            }

        VK10.vkCmdCopyBufferToImage(
            cmd.vkCommandBuffer, bufferData.buffer, image.vkImage, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region
        )
    }

    private fun recordImageTransition(stack: MemoryStack, cmd: CommandBuffer, oldLayout: Int, newLayout: Int) {
        val barrier = VkImageMemoryBarrier.calloc(1, stack).sType(VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
            .oldLayout(oldLayout).newLayout(newLayout).srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
            .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).image(image.vkImage)
            .subresourceRange { it: VkImageSubresourceRange ->
                it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(mipLevels).baseArrayLayer(0)
                    .layerCount(1)
            }

        val srcStage: Int
        val srcAccessMask: Int
        val dstAccessMask: Int
        val dstStage: Int

        if (oldLayout == VK10.VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
            srcStage = VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT
            srcAccessMask = 0
            dstStage = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT
            dstAccessMask = VK10.VK_ACCESS_TRANSFER_WRITE_BIT
        } else if (oldLayout == VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL && newLayout == VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
            srcStage = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT
            srcAccessMask = VK10.VK_ACCESS_TRANSFER_WRITE_BIT
            dstStage = VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
            dstAccessMask = VK10.VK_ACCESS_SHADER_READ_BIT
        } else {
            throw RuntimeException("Unsupported layout transition")
        }

        barrier.srcAccessMask(srcAccessMask)
        barrier.dstAccessMask(dstAccessMask)

        VK10.vkCmdPipelineBarrier(cmd.vkCommandBuffer, srcStage, dstStage, 0, null, null, barrier)
    }

    fun recordTextureTransition(cmd: CommandBuffer) {
        if (stgBuffer != null && !recordedTransition) {
            recordedTransition = true
            MemoryStack.stackPush().use { stack ->
                recordImageTransition(
                    stack, cmd, VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
                )
                recordCopyBuffer(stack, cmd, stgBuffer!!)
                recordImageTransition(
                    stack, cmd, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                )
            }
        } else {
        }
    }

    fun transferNow() {
        if (stgBuffer != null && !recordedTransition) {
            val cmd = CommandBuffer(renderer.commandPool, true, true)

            cmd.beginRecording()
            recordTextureTransition(cmd)
            cmd.endRecording()

            val fence = afield.four.device.Fence(renderer.device, true)
            fence.reset()
            MemoryStack.stackPush().use { stack ->
                renderer.graphQueue.submit(stack.pointers(cmd.vkCommandBuffer), null, null, null, fence)
            }
            fence.fenceWait()
            fence.cleanup()
            cmd.cleanup()
        }
    }
}

class StorageImage(val renderer: Renderer, val width: Int, val height: Int, val imageFormat: Int) {
    lateinit var image: Image
    lateinit var imageView: ImageView

    var recordedTransition = false
    init {
        var buf: ByteBuffer? = null
        try {
            MemoryStack.stackPush().use { stack ->

                val imageData: ImageData =
                    ImageData().width(width).height(height).usage(VK10.VK_IMAGE_USAGE_STORAGE_BIT).format(imageFormat)

                image = Image(renderer.device, imageData)

                val imageViewData: ImageViewData = ImageViewData().format(image.format).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                imageView = ImageView(renderer.device, image.vkImage, imageViewData)
            }
        } finally {
            if (buf != null) {
                stbi_image_free(buf)
            }
        }
    }

    private fun recordImageTransition(stack: MemoryStack, cmd: CommandBuffer, oldLayout: Int, newLayout: Int) {
        val barrier = VkImageMemoryBarrier.calloc(1, stack).sType(VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
            .oldLayout(oldLayout).newLayout(newLayout).srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
            .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).image(image.vkImage)
            .subresourceRange { it: VkImageSubresourceRange ->
                it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0)
                    .layerCount(1)
            }

        val srcStage: Int
        val srcAccessMask: Int
        val dstAccessMask: Int
        val dstStage: Int

        if (oldLayout == VK10.VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
            srcStage = VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT
            srcAccessMask = 0
            dstStage = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT
            dstAccessMask = VK10.VK_ACCESS_TRANSFER_WRITE_BIT
        } else if (oldLayout == VK10.VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK10.VK_IMAGE_LAYOUT_GENERAL) {
            srcStage = VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT
            srcAccessMask = 0
            dstStage = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT
            dstAccessMask = VK10.VK_ACCESS_TRANSFER_WRITE_BIT
        } else if (oldLayout == VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL && newLayout == VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
            srcStage = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT
            srcAccessMask = VK10.VK_ACCESS_TRANSFER_WRITE_BIT
            dstStage = VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
            dstAccessMask = VK10.VK_ACCESS_SHADER_READ_BIT
        } else {
            throw RuntimeException("Unsupported layout transition")
        }

        barrier.srcAccessMask(srcAccessMask)
        barrier.dstAccessMask(dstAccessMask)

        VK10.vkCmdPipelineBarrier(cmd.vkCommandBuffer, srcStage, dstStage, 0, null, null, barrier)
    }

    fun recordTextureTransition(cmd: CommandBuffer) {
        if (!recordedTransition) {
            recordedTransition = true
            MemoryStack.stackPush().use { stack ->
                recordImageTransition(
                    stack, cmd, VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_GENERAL
                )
            }
        }
    }

    fun transitionNow() {
        val cmd = CommandBuffer(renderer.commandPool, true, true)

        cmd.beginRecording()
        recordTextureTransition(cmd)
        cmd.endRecording()

        val fence = afield.four.device.Fence(renderer.device, true)
        fence.reset()
        MemoryStack.stackPush().use { stack ->
            renderer.graphQueue.submit(stack.pointers(cmd.vkCommandBuffer), null, null, null, fence)
        }
        fence.fenceWait()
        fence.cleanup()
        cmd.cleanup()
    }


    fun cleanup() {
        imageView.cleanup()
        image.cleanup()
    }

}
