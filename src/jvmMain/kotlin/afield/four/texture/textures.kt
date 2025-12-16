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
import org.lwjgl.vulkan.VK10.VK_FORMAT_R8_UNORM
import java.nio.ByteBuffer
import afield.four.Target

open class LoadedTexture protected constructor(
    private val sourceName: String,
    val renderer: Renderer = fromContext(),
    imageFormat: Int = VK_FORMAT_R8G8B8A8_SRGB,
    decoded: DecodedImage
) : HasImageView {
    protected data class DecodedImage(val data: ByteBuffer, val width: Int, val height: Int)

    val fileName: String
    var height = 0
    lateinit var image: Image
    override lateinit var imageView: ImageView
    var mipLevels = 0
    var width = 0
    var recordedTransition: Boolean
    var stgBuffer: VulkanBuffer? = null

    init {
        recordedTransition = false
        this.fileName = sourceName
        this.width = decoded.width
        this.height = decoded.height
        this.mipLevels = 1

        createStgBuffer(renderer.device, decoded.data)
        val imageData: ImageData = ImageData().width(width).height(height)
            .usage(VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT or VK10.VK_IMAGE_USAGE_SAMPLED_BIT).format(imageFormat)
            .mipLevels(mipLevels)

        image = Image(renderer.device, imageData)
        val imageViewData: ImageViewData =
            ImageViewData().format(image.format).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevels(mipLevels)
        imageView = ImageView(renderer.device, image, imageViewData)
    }

    companion object {
        private fun decodeFile(path: String): DecodedImage {
            MemoryStack.stackPush().use { stack ->
                val w = stack.mallocInt(1)
                val h = stack.mallocInt(1)
                val channels = stack.mallocInt(1)
                val buf = stbi_load(path, w, h, channels, 4)
                    ?: throw RuntimeException("Image file [$path] not loaded: ${stbi_failure_reason()}")
                return DecodedImage(buf, w.get(), h.get())
            }
        }

        private fun decodeMemory(data: ByteBuffer): DecodedImage {
            MemoryStack.stackPush().use { stack ->
                val w = stack.mallocInt(1)
                val h = stack.mallocInt(1)
                val channels = stack.mallocInt(1)
                val buf = stbi_load_from_memory(data, w, h, channels, 4)
                    ?: throw RuntimeException("Image buffer not loaded: ${stbi_failure_reason()}")
                return DecodedImage(buf, w.get(), h.get())
            }
        }

        fun fromFile(
            fileName: String,
            renderer: Renderer = fromContext(),
            imageFormat: Int = VK_FORMAT_R8G8B8A8_SRGB
        ): LoadedTexture {
            val decoded = decodeFile(fileName)
            try {
                return LoadedTexture(fileName, renderer, imageFormat, decoded)
            } finally {
                stbi_image_free(decoded.data)
            }
        }

        fun fromEncodedMemory(
            name: String,
            encoded: ByteBuffer,
            renderer: Renderer = fromContext(),
            imageFormat: Int = VK_FORMAT_R8G8B8A8_SRGB
        ): LoadedTexture {
            val decoded = decodeMemory(encoded)
            try {
                return LoadedTexture(name, renderer, imageFormat, decoded)
            } finally {
                stbi_image_free(decoded.data)
            }
        }

        operator fun invoke(
            fileName: String,
            renderer: Renderer = fromContext(),
            imageFormat: Int = VK_FORMAT_R8G8B8A8_SRGB
        ): LoadedTexture = fromFile(fileName, renderer, imageFormat)

        private val stubCache = java.util.WeakHashMap<Renderer, LoadedTexture>()
        fun stubTexture(
            renderer: Renderer = fromContext(),
            rgba: FloatArray = floatArrayOf(1f, 0.5f, 0.3f, 1f),
            imageFormat: Int = VK_FORMAT_R8G8B8A8_SRGB
        ): LoadedTexture {
            stubCache[renderer]?.let { return it }
            val data = MemoryUtil.memAlloc(4) // just leak it
            data.put((rgba[0].coerceIn(0f,1f)*255f).toInt().toByte())
            data.put((rgba[1].coerceIn(0f,1f)*255f).toInt().toByte())
            data.put((rgba[2].coerceIn(0f,1f)*255f).toInt().toByte())
            data.put((rgba[3].coerceIn(0f,1f)*255f).toInt().toByte())
            data.flip()
            val decoded = DecodedImage(data, 1, 1)
            val tex = LoadedTexture("stub_1x1", renderer, imageFormat, decoded)
            stubCache[renderer] = tex
            return tex
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

/**
 * Extremely simple streaming texture: call [recordUpload] every frame.
 *
 * Assumptions:
 * - [source] contains at least `width*height*bytesPerPixel` bytes starting at its current position.
 * - Tearing is acceptable (CPU may write while GPU reads).
 */
class StreamingTexture(
    val source: ByteBuffer,
    width: Int,
    height: Int,
    renderer: Renderer = fromContext(),
    imageFormat: Int = VK_FORMAT_R8_UNORM,
    name: String = "stream_${width}x${height}"
) : LoadedTexture(
    name,
    renderer,
    imageFormat,
    DecodedImage(initialSlice(source, width, height, imageFormat), width, height)
), Target {
    private val bytesPerPixel = ImageData().format(imageFormat).bytesPerPixel
    private val expectedBytes: Int = width * height * bytesPerPixel

    // best-effort tracking for repeated uploads
    private var currentLayout: Int = VK10.VK_IMAGE_LAYOUT_UNDEFINED

    init {
        require(width > 0 && height > 0) { "StreamingTexture requires positive dimensions" }
        require(source.remaining() >= expectedBytes) {
            "StreamingTexture source buffer too small: need $expectedBytes bytes, have ${source.remaining()}"
        }
    }

    override fun render(command: CommandBuffer, index: Int) {
        recordUpload(command, source)
    }

    fun recordUpload(cmd: CommandBuffer, src: ByteBuffer = source) {
        writeToStaging(src)

        MemoryStack.stackPush().use { stack ->
            recordImageTransition(stack, cmd, currentLayout, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
            recordCopyBuffer(stack, cmd, stgBuffer!!)
            recordImageTransition(
                stack,
                cmd,
                VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
            )
        }

        currentLayout = VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
    }

    /** Convenience for when you don't already have a frame command buffer. */
    fun uploadNow(src: ByteBuffer = source) {
        val cmd = CommandBuffer(renderer.commandPool, true, true)

        cmd.beginRecording()
        recordUpload(cmd, src)
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

    private fun writeToStaging(src: ByteBuffer) {
        require(stgBuffer != null) { "StreamingTexture missing staging buffer" }
        require(src.remaining() >= expectedBytes) {
            "StreamingTexture update buffer too small: need $expectedBytes bytes, have ${src.remaining()}"
        }

        val srcDup = src.duplicate()
        val start = srcDup.position()
        srcDup.limit(start + expectedBytes)

        val mapped = stgBuffer!!.map()
        val dst = MemoryUtil.memByteBuffer(mapped, expectedBytes)
        dst.position(0)
        dst.put(srcDup)
        stgBuffer!!.unmap(0, expectedBytes.toLong())
    }

    private fun recordCopyBuffer(stack: MemoryStack, cmd: CommandBuffer, bufferData: VulkanBuffer) {
        val region = VkBufferImageCopy.calloc(1, stack).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0)
            .imageSubresource { it: VkImageSubresourceLayers ->
                it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1)
            }.imageOffset { it: VkOffset3D -> it.x(0).y(0).z(0) }.imageExtent { it: VkExtent3D ->
                it.width(this.width).height(this.height).depth(1)
            }

        VK10.vkCmdCopyBufferToImage(
            cmd.vkCommandBuffer,
            bufferData.buffer,
            image.vkImage,
            VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            region
        )
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
        } else if (oldLayout == VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL && newLayout == VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
            srcStage = VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
            srcAccessMask = VK10.VK_ACCESS_SHADER_READ_BIT
            dstStage = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT
            dstAccessMask = VK10.VK_ACCESS_TRANSFER_WRITE_BIT
        } else if (oldLayout == VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL && newLayout == VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
            srcStage = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT
            srcAccessMask = VK10.VK_ACCESS_TRANSFER_WRITE_BIT
            dstStage = VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
            dstAccessMask = VK10.VK_ACCESS_SHADER_READ_BIT
        } else {
            throw RuntimeException("Unsupported layout transition: $oldLayout -> $newLayout")
        }

        barrier.srcAccessMask(srcAccessMask)
        barrier.dstAccessMask(dstAccessMask)

        VK10.vkCmdPipelineBarrier(cmd.vkCommandBuffer, srcStage, dstStage, 0, null, null, barrier)
    }

    companion object {
        private fun initialSlice(src: ByteBuffer, width: Int, height: Int, format: Int): ByteBuffer {
            val expected = width * height * ImageData().format(format).bytesPerPixel
            require(src.remaining() >= expected) {
                "StreamingTexture source buffer too small: need $expected bytes, have ${src.remaining()}"
            }
            val dup = src.duplicate()
            val start = dup.position()
            dup.limit(start + expected)
            return dup
        }
    }
}

class StorageImage(val renderer: Renderer, val width: Int, val height: Int, val imageFormat: Int) : HasImageView {
    lateinit var image: Image
    lateinit override var imageView: ImageView

    var recordedTransition = false
    init {
        var buf: ByteBuffer? = null
        try {
            MemoryStack.stackPush().use { stack ->

                val imageData: ImageData =
                    ImageData().width(width).height(height).usage(VK10.VK_IMAGE_USAGE_STORAGE_BIT).format(imageFormat)

                image = Image(renderer.device, imageData)

                val imageViewData: ImageViewData = ImageViewData().format(image.format).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                imageView = ImageView(renderer.device, image, imageViewData)
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
