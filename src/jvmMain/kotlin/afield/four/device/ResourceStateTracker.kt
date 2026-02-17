package afield.four.device

import afield.four.texture.Image
import afield.four.buffers.VulkanBuffer
import org.lwjgl.vulkan.VK10.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralized state tracking for Vulkan images and buffers.
 * 
 * Tracks current layouts, access masks, and pipeline stages for resources to:
 * - Prevent validation errors from incorrect layout assumptions
 * - Provide correct oldLayout values for image barriers
 * - Debug layout transition issues
 * - Ensure resources are in expected states before use
 * 
 * Thread-safe for multi-threaded command buffer recording.
 */
class ResourceStateTracker(val debug: Boolean = false) {
    
    // Image state by VkImage handle
    private val imageStates = ConcurrentHashMap<Long, ImageState>()
    
    // Buffer state by VkBuffer handle
    private val bufferStates = ConcurrentHashMap<Long, BufferState>()
    
    /**
     * Current state of a Vulkan image resource.
     */
    data class ImageState(
        var layout: Int = VK_IMAGE_LAYOUT_UNDEFINED,
        var accessMask: Long = 0L,
        var stageMask: Long = VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT,
        // For debugging: track recent transitions
        val history: MutableList<TransitionRecord> = mutableListOf()
    ) {
        data class TransitionRecord(
            val oldLayout: Int,
            val newLayout: Int,
            val timestamp: Long = System.nanoTime()
        )
        
        fun recordTransition(oldLayout: Int, newLayout: Int) {
            history.add(TransitionRecord(oldLayout, newLayout))
            // Keep only last 10 transitions
            if (history.size > 10) {
                history.removeAt(0)
            }
        }
    }
    
    /**
     * Current state of a Vulkan buffer resource.
     */
    data class BufferState(
        var accessMask: Long = 0L,
        var stageMask: Long = VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT
    )
    
    // === IMAGE STATE MANAGEMENT ===
    
    /**
     * Check if an image is currently tracked.
     */
    fun isImageTracked(imageHandle: Long): Boolean {
        return imageStates.containsKey(imageHandle)
    }
    
    /**
     * Check if an Image object is currently tracked.
     */
    fun isImageTracked(image: Image): Boolean = isImageTracked(image.vkImage)
    
    /**
     * Get the current layout of an image. Returns UNDEFINED if not tracked.
     */
    fun getImageLayout(imageHandle: Long): Int {
        return imageStates[imageHandle]?.layout ?: VK_IMAGE_LAYOUT_UNDEFINED
    }
    
    /**
     * Get the current layout of an Image object.
     */
    fun getImageLayout(image: Image): Int = getImageLayout(image.vkImage)
    
    /**
     * Get the current access mask for an image.
     */
    fun getImageAccess(imageHandle: Long): Long {
        return imageStates[imageHandle]?.accessMask ?: 0L
    }
    
    /**
     * Get the current pipeline stage mask for an image.
     */
    fun getImageStage(imageHandle: Long): Long {
        return imageStates[imageHandle]?.stageMask ?: VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT
    }
    
    /**
     * Update image state after a layout transition.
     */
    fun updateImageState(
        imageHandle: Long,
        newLayout: Int,
        dstAccessMask: Long,
        dstStageMask: Long
    ) {
        val state = imageStates.getOrPut(imageHandle) { ImageState() }
        val oldLayout = state.layout
        
        state.recordTransition(oldLayout, newLayout)
        state.layout = newLayout
        state.accessMask = dstAccessMask
        state.stageMask = dstStageMask
        
        if (debug) {
            println("ResourceStateTracker: Image 0x${imageHandle.toString(16)} " +
                    "layout ${layoutName(oldLayout)} -> ${layoutName(newLayout)}")
        }
    }
    
    /**
     * Update image state for an Image object.
     */
    fun updateImageState(
        image: Image,
        newLayout: Int,
        dstAccessMask: Long,
        dstStageMask: Long
    ) = updateImageState(image.vkImage, newLayout, dstAccessMask, dstStageMask)
    
    /**
     * Initialize image state (typically called right after creation).
     */
    fun initImageState(
        imageHandle: Long,
        initialLayout: Int = VK_IMAGE_LAYOUT_UNDEFINED
    ) {
        imageStates[imageHandle] = ImageState(layout = initialLayout)
        if (debug) {
            println("ResourceStateTracker: Image 0x${imageHandle.toString(16)} " +
                    "initialized to ${layoutName(initialLayout)}")
        }
    }
    
    /**
     * Initialize image state for an Image object.
     */
    fun initImageState(image: Image, initialLayout: Int = VK_IMAGE_LAYOUT_UNDEFINED) {
        initImageState(image.vkImage, initialLayout)
    }
    
    /**
     * Verify an image is in the expected layout. Throws if mismatch.
     */
    fun verifyImageLayout(imageHandle: Long, expectedLayout: Int, context: String = "") {
        val actual = getImageLayout(imageHandle)
        if (actual != expectedLayout) {
            val msg = buildString {
                append("Image layout mismatch")
                if (context.isNotEmpty()) append(" in $context")
                append(": expected ${layoutName(expectedLayout)}, ")
                append("but found ${layoutName(actual)}")
                
                imageStates[imageHandle]?.let { state ->
                    if (state.history.isNotEmpty()) {
                        append("\nRecent transitions:")
                        state.history.takeLast(5).forEach { t ->
                            append("\n  ${layoutName(t.oldLayout)} -> ${layoutName(t.newLayout)}")
                        }
                    }
                }
            }
            throw IllegalStateException(msg)
        }
    }
    
    /**
     * Unregister an image (e.g., when destroyed).
     */
    fun unregisterImage(imageHandle: Long) {
        imageStates.remove(imageHandle)
        if (debug) {
            println("ResourceStateTracker: Image 0x${imageHandle.toString(16)} unregistered")
        }
    }
    
    /**
     * Unregister an Image object.
     */
    fun unregisterImage(image: Image) = unregisterImage(image.vkImage)
    
    // === BUFFER STATE MANAGEMENT ===
    
    /**
     * Get current buffer access mask.
     */
    fun getBufferAccess(bufferHandle: Long): Long {
        return bufferStates[bufferHandle]?.accessMask ?: 0L
    }
    
    /**
     * Get current buffer pipeline stage mask.
     */
    fun getBufferStage(bufferHandle: Long): Long {
        return bufferStates[bufferHandle]?.stageMask ?: VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT
    }
    
    /**
     * Update buffer state after a barrier.
     */
    fun updateBufferState(
        bufferHandle: Long,
        dstAccessMask: Long,
        dstStageMask: Long
    ) {
        val state = bufferStates.getOrPut(bufferHandle) { BufferState() }
        state.accessMask = dstAccessMask
        state.stageMask = dstStageMask
        
        if (debug) {
            println("ResourceStateTracker: Buffer 0x${bufferHandle.toString(16)} state updated")
        }
    }
    
    /**
     * Update buffer state for a VulkanBuffer object.
     */
    fun updateBufferState(
        buffer: VulkanBuffer,
        dstAccessMask: Long,
        dstStageMask: Long
    ) = updateBufferState(buffer.buffer, dstAccessMask, dstStageMask)
    
    /**
     * Initialize buffer state.
     */
    fun initBufferState(bufferHandle: Long) {
        bufferStates[bufferHandle] = BufferState()
        if (debug) {
            println("ResourceStateTracker: Buffer 0x${bufferHandle.toString(16)} initialized")
        }
    }
    
    /**
     * Initialize buffer state for a VulkanBuffer object.
     */
    fun initBufferState(buffer: VulkanBuffer) = initBufferState(buffer.buffer)
    
    /**
     * Unregister a buffer.
     */
    fun unregisterBuffer(bufferHandle: Long) {
        bufferStates.remove(bufferHandle)
        if (debug) {
            println("ResourceStateTracker: Buffer 0x${bufferHandle.toString(16)} unregistered")
        }
    }
    
    /**
     * Unregister a VulkanBuffer object.
     */
    fun unregisterBuffer(buffer: VulkanBuffer) = unregisterBuffer(buffer.buffer)
    
    // === UTILITY METHODS ===
    
    /**
     * Clear all tracked state (e.g., on device reset).
     */
    fun clearAll() {
        imageStates.clear()
        bufferStates.clear()
        if (debug) {
            println("ResourceStateTracker: All state cleared")
        }
    }
    
    /**
     * Get diagnostic information about tracked resources.
     * 
     * Returns a multi-line string with:
     * - Number of tracked images and buffers
     * - List of image states (up to 20) with their handles and current layouts
     * 
     * Format:
     * ```
     * ResourceStateTracker Diagnostics:
     *   Images tracked: N
     *   Buffers tracked: M
     *   Image states:
     *     0xHANDLE: LAYOUT_NAME
     *     ...
     * ```
     */
    fun getDiagnostics(): String = buildString {
        appendLine("ResourceStateTracker Diagnostics:")
        appendLine("  Images tracked: ${imageStates.size}")
        appendLine("  Buffers tracked: ${bufferStates.size}")
        if (imageStates.isNotEmpty()) {
            appendLine("  Image states:")
            imageStates.entries.take(20).forEach { (handle, state) ->
                appendLine("    0x${handle.toString(16)}: ${layoutName(state.layout)}")
            }
        }
    }
    
    companion object {
        // VK_IMAGE_LAYOUT constants not exposed in older LWJGL versions
        private const val VK_IMAGE_LAYOUT_PRESENT_SRC_KHR = 1000001002
        private const val VK_IMAGE_LAYOUT_DEPTH_READ_ONLY_STENCIL_ATTACHMENT_OPTIMAL = 1000117000
        private const val VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_STENCIL_READ_ONLY_OPTIMAL = 1000117001
        private const val VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL = 1000241000
        private const val VK_IMAGE_LAYOUT_DEPTH_READ_ONLY_OPTIMAL = 1000241001
        private const val VK_IMAGE_LAYOUT_STENCIL_ATTACHMENT_OPTIMAL = 1000241002
        private const val VK_IMAGE_LAYOUT_STENCIL_READ_ONLY_OPTIMAL = 1000241003
        
        /**
         * Human-readable name for a layout constant.
         */
        fun layoutName(layout: Int): String = when (layout) {
            VK_IMAGE_LAYOUT_UNDEFINED -> "UNDEFINED"
            VK_IMAGE_LAYOUT_GENERAL -> "GENERAL"
            VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL -> "COLOR_ATTACHMENT_OPTIMAL"
            VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL -> "DEPTH_STENCIL_ATTACHMENT_OPTIMAL"
            VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL -> "DEPTH_STENCIL_READ_ONLY_OPTIMAL"
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> "SHADER_READ_ONLY_OPTIMAL"
            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL -> "TRANSFER_SRC_OPTIMAL"
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> "TRANSFER_DST_OPTIMAL"
            VK_IMAGE_LAYOUT_PREINITIALIZED -> "PREINITIALIZED"
            VK_IMAGE_LAYOUT_PRESENT_SRC_KHR -> "PRESENT_SRC_KHR"
            VK_IMAGE_LAYOUT_DEPTH_READ_ONLY_STENCIL_ATTACHMENT_OPTIMAL -> "DEPTH_READ_ONLY_STENCIL_ATTACHMENT_OPTIMAL"
            VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_STENCIL_READ_ONLY_OPTIMAL -> "DEPTH_ATTACHMENT_STENCIL_READ_ONLY_OPTIMAL"
            VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL -> "DEPTH_ATTACHMENT_OPTIMAL"
            VK_IMAGE_LAYOUT_DEPTH_READ_ONLY_OPTIMAL -> "DEPTH_READ_ONLY_OPTIMAL"
            VK_IMAGE_LAYOUT_STENCIL_ATTACHMENT_OPTIMAL -> "STENCIL_ATTACHMENT_OPTIMAL"
            VK_IMAGE_LAYOUT_STENCIL_READ_ONLY_OPTIMAL -> "STENCIL_READ_ONLY_OPTIMAL"
            else -> "UNKNOWN(0x${layout.toString(16)})"
        }
    }
}
