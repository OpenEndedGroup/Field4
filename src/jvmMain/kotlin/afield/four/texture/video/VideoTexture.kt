package afield.four.texture.video

import afield.four.Renderer
import afield.four.buffers.VulkanBuffer
import afield.four.device.CommandBuffer
import afield.four.modules.fromContext
import afield.four.texture.Image
import afield.four.texture.ImageView
import afield.four.texture.HasImageViewArray
import afield.four.texture.HasImageView
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * VideoTexture manages video playback with P010 format support.
 * Maintains dual texture arrays for luma and chroma planes in semi-planar format.
 * Supports load, play, scrub, and unload operations while reusing Vulkan resources when dimensions match.
 */
class VideoTexture(
    private val renderer: Renderer = fromContext()
) : HasImageViewArray {
    
    // Current video state
    private var decoder: VideoDecoder? = null
    private var currentVideoPath: String? = null
    private var isPlaying = false
    private var currentTimestamp = 0.0
    private var lastUpdateTime = System.nanoTime()
    
    // Video dimensions
    private var videoWidth = 0
    private var videoHeight = 0
    
    // Dual texture arrays for luma and chroma
    private var lumaTextures = arrayOfNulls<VideoPlaneTexture>(2)
    private var chromaTextures = arrayOfNulls<VideoPlaneTexture>(2)
    
    // Current slot index (0 or 1) - indicates most recent frame
    private val currentSlot = AtomicInteger(0)
    
    // Format constants for P010
    private val lumaFormat = VK10.VK_FORMAT_R16_UNORM  // 16-bit luma
    private val chromaFormat = VK10.VK_FORMAT_R16G16_UNORM  // 16-bit interleaved UV
    
    /**
     * Load a video file. If dimensions match the previously loaded video, reuses Vulkan resources.
     */
    fun load(videoPath: String): Boolean {
        // If loading the same video, just seek to start
        if (videoPath == currentVideoPath && decoder != null) {
            seek(0.0)
            return true
        }
        
        // Create new decoder
        val newDecoder = VideoDecoder(videoPath)
        if (!newDecoder.open()) {
            println("Failed to open video: $videoPath")
            return false
        }
        
        val newWidth = newDecoder.width
        val newHeight = newDecoder.height
        
        // Check if we can reuse existing textures (same dimensions)
        val canReuseTextures = (newWidth == videoWidth && newHeight == videoHeight && 
                                lumaTextures[0] != null && chromaTextures[0] != null)
        
        // Close old decoder if exists
        decoder?.close()
        decoder = newDecoder
        currentVideoPath = videoPath
        
        if (canReuseTextures) {
            println("Reusing existing textures for video: $videoPath (${newWidth}x${newHeight})")
        } else {
            println("Creating new textures for video: $videoPath (${newWidth}x${newHeight})")
            
            // Clean up old textures if they exist
            cleanupTextures()
            
            // Update dimensions
            videoWidth = newWidth
            videoHeight = newHeight
            
            // Create new texture pairs
            for (i in 0..1) {
                lumaTextures[i] = VideoPlaneTexture(
                    renderer = renderer,
                    width = videoWidth,
                    height = videoHeight,
                    format = lumaFormat,
                    name = "video_luma_$i"
                )
                
                chromaTextures[i] = VideoPlaneTexture(
                    renderer = renderer,
                    width = videoWidth / 2,  // Chroma is subsampled 2x
                    height = videoHeight / 2,
                    format = chromaFormat,
                    name = "video_chroma_$i"
                )
            }
        }
        
        currentTimestamp = 0.0
        currentSlot.set(0)
        
        return true
    }
    
    /**
     * Start or resume playback.
     */
    fun play() {
        if (decoder != null) {
            isPlaying = true
            lastUpdateTime = System.nanoTime()
        }
    }
    
    /**
     * Pause playback.
     */
    fun pause() {
        isPlaying = false
    }
    
    /**
     * Seek to a specific timestamp in seconds.
     */
    fun seek(timestampSeconds: Double): Boolean {
        decoder?.let { dec ->
            if (dec.seek(timestampSeconds)) {
                currentTimestamp = timestampSeconds
                lastUpdateTime = System.nanoTime()
                return true
            }
        }
        return false
    }
    
    /**
     * Update the video texture. Call this every frame to decode and upload new frames when playing.
     * Returns true if a new frame was uploaded.
     */
    fun update(commandBuffer: CommandBuffer): Boolean {
        if (!isPlaying || decoder == null) {
            return false
        }
        
        // Calculate elapsed time
        val currentTime = System.nanoTime()
        val deltaTime = (currentTime - lastUpdateTime) / 1_000_000_000.0
        lastUpdateTime = currentTime
        
        // Update timestamp
        currentTimestamp += deltaTime
        
        // Check if we're past the video duration
        decoder?.let { dec ->
            if (currentTimestamp >= dec.duration) {
                // Loop back to start
                currentTimestamp = 0.0
                dec.seek(0.0)
            }
        }
        
        // Decode next frame
        val frame = decoder?.decodeNextFrame() ?: return false
        
        // Switch to next slot
        val nextSlot = (currentSlot.get() + 1) % 2
        
        // Upload frame data to textures
        val lumaTexture = lumaTextures[nextSlot] ?: return false
        val chromaTexture = chromaTextures[nextSlot] ?: return false
        
        lumaTexture.upload(commandBuffer, frame.lumaData)
        chromaTexture.upload(commandBuffer, frame.chromaData)
        
        // Update current slot
        currentSlot.set(nextSlot)
        
        return true
    }
    
    /**
     * Get the current luma texture.
     */
    fun getLumaTexture(): HasImageView? {
        return lumaTextures[currentSlot.get()]
    }
    
    /**
     * Get the current chroma texture.
     */
    fun getChromaTexture(): HasImageView? {
        return chromaTextures[currentSlot.get()]
    }
    
    /**
     * Get both current textures as an array (luma at index 0, chroma at index 1).
     */
    override val imageViewArray: List<HasImageView>
        get() {
            val slot = currentSlot.get()
            return listOfNotNull(lumaTextures[slot], chromaTextures[slot])
        }
    
    /**
     * Get video properties.
     */
    val width: Int get() = videoWidth
    val height: Int get() = videoHeight
    val duration: Double get() = decoder?.duration ?: 0.0
    val fps: Double get() = decoder?.fps ?: 0.0
    val timestamp: Double get() = currentTimestamp
    val playing: Boolean get() = isPlaying
    
    /**
     * Unload the current video and free resources.
     */
    fun unload() {
        pause()
        decoder?.close()
        decoder = null
        currentVideoPath = null
        currentTimestamp = 0.0
        // Note: We keep the Vulkan textures allocated for potential reuse
    }
    
    /**
     * Clean up all resources including Vulkan textures.
     */
    fun cleanup() {
        unload()
        cleanupTextures()
    }
    
    private fun cleanupTextures() {
        for (i in 0..1) {
            lumaTextures[i]?.cleanup()
            lumaTextures[i] = null
            chromaTextures[i]?.cleanup()
            chromaTextures[i] = null
        }
        videoWidth = 0
        videoHeight = 0
    }
}

/**
 * Internal class representing a single video plane texture (luma or chroma).
 */
private class VideoPlaneTexture(
    private val renderer: Renderer,
    val width: Int,
    val height: Int,
    val format: Int,
    val name: String
) : HasImageView {
    
    private val image: Image
    override val imageView: ImageView
    private val stagingBuffer: VulkanBuffer
    private var currentLayout = VK10.VK_IMAGE_LAYOUT_UNDEFINED
    
    init {
        // Calculate bytes per pixel based on format
        val bytesPerPixel = when (format) {
            VK10.VK_FORMAT_R16_UNORM -> 2  // 16-bit single channel
            VK10.VK_FORMAT_R16G16_UNORM -> 4  // 16-bit dual channel
            else -> 4
        }
        
        val imageSize = width * height * bytesPerPixel
        
        // Create staging buffer for uploads
        stagingBuffer = VulkanBuffer(
            renderer,
            imageSize.toLong(),
            VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
            VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
        )
        
        // Create image
        val imageData = Image.ImageData()
            .width(width)
            .height(height)
            .format(format)
            .usage(VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT or VK10.VK_IMAGE_USAGE_SAMPLED_BIT)
            .mipLevels(1)
        
        image = Image(renderer.device, imageData)
        
        // Create image view
        val imageViewData = ImageView.ImageViewData()
            .format(format)
            .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
            .mipLevels(1)
        
        imageView = ImageView(renderer.device, image, imageViewData)
    }
    
    /**
     * Upload data to this texture plane.
     */
    fun upload(cmd: CommandBuffer, data: ByteBuffer) {
        // Create a duplicate to avoid modifying the caller's buffer position
        val dataCopy = data.duplicate()
        
        // Copy data to staging buffer
        val mappedMemory = stagingBuffer.map()
        val buffer = MemoryUtil.memByteBuffer(mappedMemory, dataCopy.remaining())
        buffer.put(dataCopy)
        stagingBuffer.unmap()
        
        MemoryStack.stackPush().use { stack ->
            // Transition to transfer destination
            recordImageTransition(stack, cmd, currentLayout, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
            
            // Copy buffer to image
            recordCopyBuffer(stack, cmd)
            
            // Transition to shader read
            recordImageTransition(
                stack,
                cmd,
                VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
            )
        }
        
        currentLayout = VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
    }
    
    private fun recordCopyBuffer(stack: MemoryStack, cmd: CommandBuffer) {
        val region = VkBufferImageCopy.calloc(1, stack)
            .bufferOffset(0)
            .bufferRowLength(0)
            .bufferImageHeight(0)
            .imageSubresource { it: VkImageSubresourceLayers ->
                it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0)
                    .baseArrayLayer(0)
                    .layerCount(1)
            }
            .imageOffset { it: VkOffset3D -> it.x(0).y(0).z(0) }
            .imageExtent { it: VkExtent3D ->
                it.width(width).height(height).depth(1)
            }
        
        VK10.vkCmdCopyBufferToImage(
            cmd.vkCommandBuffer,
            stagingBuffer.buffer,
            image.vkImage,
            VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            region
        )
    }
    
    private fun recordImageTransition(
        stack: MemoryStack,
        cmd: CommandBuffer,
        oldLayout: Int,
        newLayout: Int
    ) {
        val barrier = VkImageMemoryBarrier.calloc(1, stack)
            .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
            .oldLayout(oldLayout)
            .newLayout(newLayout)
            .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
            .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
            .image(image.vkImage)
            .subresourceRange { it: VkImageSubresourceRange ->
                it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1)
            }
        
        val srcStage: Int
        val srcAccessMask: Int
        val dstAccessMask: Int
        val dstStage: Int
        
        when {
            oldLayout == VK10.VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> {
                srcStage = VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT
                srcAccessMask = 0
                dstStage = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT
                dstAccessMask = VK10.VK_ACCESS_TRANSFER_WRITE_BIT
            }
            oldLayout == VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL && newLayout == VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> {
                srcStage = VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
                srcAccessMask = VK10.VK_ACCESS_SHADER_READ_BIT
                dstStage = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT
                dstAccessMask = VK10.VK_ACCESS_TRANSFER_WRITE_BIT
            }
            oldLayout == VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL && newLayout == VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> {
                srcStage = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT
                srcAccessMask = VK10.VK_ACCESS_TRANSFER_WRITE_BIT
                dstStage = VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
                dstAccessMask = VK10.VK_ACCESS_SHADER_READ_BIT
            }
            else -> throw RuntimeException("Unsupported layout transition: $oldLayout -> $newLayout")
        }
        
        barrier.srcAccessMask(srcAccessMask)
        barrier.dstAccessMask(dstAccessMask)
        
        VK10.vkCmdPipelineBarrier(cmd.vkCommandBuffer, srcStage, dstStage, 0, null, null, barrier)
    }
    
    fun cleanup() {
        imageView.cleanup()
        image.cleanup()
        stagingBuffer.cleanup()
    }
}
