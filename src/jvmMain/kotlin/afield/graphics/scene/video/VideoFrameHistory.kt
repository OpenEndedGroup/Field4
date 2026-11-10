package afield.graphics.scene.video

import afield.graphics.texture.*
import afield.graphics.texture.video.VideoTexture
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.device.CommandBuffer
import org.lwjgl.vulkan.VK10.*

/** The selected movie image, which can be the live decoder slot or a persistent delayed snapshot. */
internal interface VideoSourceFrame {
    val width: Int
    val height: Int
    val colorSpace: Int
    val colorRange: Int
    val imageViewArray: List<HasImageView>
}

internal data class PublishedVideoFrame(
    val serial: Long,
    val timestamp: Double,
    val sourceFrameIndex: Long,
    override val width: Int,
    override val height: Int,
    override val colorSpace: Int,
    override val colorRange: Int,
    override val imageViewArray: List<HasImageView>,
) : VideoSourceFrame

/** Native P010 GPU ring: one pair of exact plane copies per published frame, shared by every screen. */
internal class VideoFrameHistory(private val renderer: Renderer, capacity: Int) : AutoCloseable {
    private var cursor = VideoHistoryCursor(capacity)
    private data class Storage(val images: List<Image>, val views: List<ImageView>, var frame: PublishedVideoFrame? = null)
    private val slots = MutableList<Storage?>(capacity) { null }
    val capacity get() = cursor.capacity
    val count get() = cursor.count
    val bytes get() = slots.filterNotNull().sumOf { slot -> slot.images.sumOf { it.memorySize } }

    fun clear() { cursor.clear() }

    /** Reset the temporal layout, retaining allocated images for subsequent slider edits. Queue thread only. */
    fun resize(capacity: Int) {
        if (capacity == this.capacity) return
        val next = VideoHistoryCursor(capacity)
        while (slots.size < capacity) slots.add(null)
        cursor = next
    }

    fun capture(command: CommandBuffer, source: VideoTexture, serial: Long): PublishedVideoFrame {
        val slot = cursor.advance()
        val storage = slots[slot] ?: source.imageViewArray.map { plane ->
            val original = plane.imageView.underlyingImage!!
            Image(renderer.device, Image.ImageData().width(original.imageData.width).height(original.imageData.height)
                .format(original.format).usage(VK_IMAGE_USAGE_SAMPLED_BIT or VK_IMAGE_USAGE_TRANSFER_DST_BIT).mipLevels(1))
        }.let { images ->
            Storage(images, images.map { image -> ImageView(renderer.device, image,
                ImageView.ImageViewData().format(image.format).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevels(1)) })
        }.also { slots[slot] = it }
        source.copyUploadedFrame(command, storage.images)
        return PublishedVideoFrame(serial, source.timestamp, source.uploadedFrameIndex, source.width, source.height,
            source.colorSpace, source.colorRange, storage.views).also { storage.frame = it }
    }

    // UI requests can change between source preparation and surface sampling. Clamp against this
    // frame's actual ring, never a concurrently edited requested capacity.
    fun frame(framesBehind: Int): PublishedVideoFrame? =
        cursor.slot(framesBehind.coerceIn(0, capacity - 1))?.let { slots[it]?.frame }

    override fun close() {
        val allocated = slots.filterNotNull()
        renderer.cleanup.add { allocated.forEach { slot -> slot.views.forEach { it.cleanup() }; slot.images.forEach { it.cleanup() } } }
        slots.clear()
        cursor.clear()
    }
}
