@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package afield.four

import afield.four.buffers.HasBuffer
import afield.four.buffers.VulkanBuffer
import afield.four.device.CommandBuffer
import afield.four.texture.Image
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED
import org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED
import org.lwjgl.vulkan.VK10.VK_WHOLE_SIZE
import org.lwjgl.vulkan.VK13.*

/**
 * Describes how a resource (buffer or image) is being used at a given point in a command buffer.
 * Encapsulates the Vulkan pipeline stage mask and access mask pair, plus an optional image layout
 * for image resources.
 *
 * Use the predefined constants in the companion object for common usage patterns.
 */
data class ResourceUsage(
    val stageMask: Long,
    val accessMask: Long,
    val imageLayout: Int = VK_IMAGE_LAYOUT_UNDEFINED
) {
    companion object {
        // -- Buffer usages --

        /** No prior access recorded; barrier src will be NONE/NONE. */
        val NONE = ResourceUsage(VK_PIPELINE_STAGE_2_NONE, VK_ACCESS_2_NONE)

        /** Buffer filled / copied to via transfer commands (vkCmdFillBuffer, vkCmdCopyBuffer, …). */
        val TRANSFER_WRITE = ResourceUsage(VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT)

        /** Buffer read via transfer commands (vkCmdCopyBuffer src, …). */
        val TRANSFER_READ = ResourceUsage(VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT)

        /** Read in a compute shader (SSBO / uniform / BDA). */
        val COMPUTE_READ = ResourceUsage(VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_READ_BIT)

        /** Written in a compute shader. */
        val COMPUTE_WRITE = ResourceUsage(VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_WRITE_BIT)

        /** Read+written in a compute shader (e.g. atomic ops, read-modify-write). */
        val COMPUTE_READ_WRITE = ResourceUsage(
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
            VK_ACCESS_2_SHADER_READ_BIT or VK_ACCESS_2_SHADER_WRITE_BIT
        )

        /** Read in vertex+fragment shaders. */
        val GRAPHICS_READ = ResourceUsage(VK_PIPELINE_STAGE_2_ALL_GRAPHICS_BIT, VK_ACCESS_2_SHADER_READ_BIT)

        /** Written in vertex+fragment shaders (e.g. SSBO writes in fragment). */
        val GRAPHICS_WRITE = ResourceUsage(VK_PIPELINE_STAGE_2_ALL_GRAPHICS_BIT, VK_ACCESS_2_SHADER_WRITE_BIT)

        /** Read+written in graphics shaders. */
        val GRAPHICS_READ_WRITE = ResourceUsage(
            VK_PIPELINE_STAGE_2_ALL_GRAPHICS_BIT,
            VK_ACCESS_2_SHADER_READ_BIT or VK_ACCESS_2_SHADER_WRITE_BIT
        )

        /** Read as vertex attribute data. */
        val VERTEX_INPUT = ResourceUsage(VK_PIPELINE_STAGE_2_VERTEX_INPUT_BIT, VK_ACCESS_2_VERTEX_ATTRIBUTE_READ_BIT)

        /** Read as index buffer data. */
        val INDEX_INPUT = ResourceUsage(VK_PIPELINE_STAGE_2_VERTEX_INPUT_BIT, VK_ACCESS_2_INDEX_READ_BIT)

        /** Read from the host (CPU map + read after GPU writes). */
        val HOST_READ = ResourceUsage(VK_PIPELINE_STAGE_2_HOST_BIT, VK_ACCESS_2_HOST_READ_BIT)

        /** Written from the host (CPU map + write before GPU reads). */
        val HOST_WRITE = ResourceUsage(VK_PIPELINE_STAGE_2_HOST_BIT, VK_ACCESS_2_HOST_WRITE_BIT)

        /** Read in fragment shader only (more precise than GRAPHICS_READ). */
        val FRAGMENT_READ = ResourceUsage(VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_SHADER_READ_BIT)

        /** Read in vertex shader only (more precise than GRAPHICS_READ). */
        val VERTEX_SHADER_READ = ResourceUsage(VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT, VK_ACCESS_2_SHADER_READ_BIT)

        /** Indirect draw argument read. */
        val INDIRECT_READ = ResourceUsage(VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT, VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT)

        // -- Image-specific usages (include layout) --

        /** Image as a color attachment being rendered to. */
        val COLOR_ATTACHMENT_WRITE = ResourceUsage(
            VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
            VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
            VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
        )

        /** Image as a color attachment being read (e.g. blending). */
        val COLOR_ATTACHMENT_READ_WRITE = ResourceUsage(
            VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
            VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT or VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
            VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
        )

        /** Depth/stencil attachment write. */
        val DEPTH_ATTACHMENT_WRITE = ResourceUsage(
            VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT or VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
            VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
            VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL
        )

        /** Depth/stencil attachment read+write. */
        val DEPTH_ATTACHMENT_READ_WRITE = ResourceUsage(
            VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT or VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
            VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT or VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
            VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL
        )

        /** Image sampled in a shader (read-only). */
        val SHADER_SAMPLED = ResourceUsage(
            VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT or VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
            VK_ACCESS_2_SHADER_READ_BIT,
            VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
        )

        /** Image used as a storage image in compute. */
        val STORAGE_IMAGE_COMPUTE = ResourceUsage(
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
            VK_ACCESS_2_SHADER_READ_BIT or VK_ACCESS_2_SHADER_WRITE_BIT,
            VK10.VK_IMAGE_LAYOUT_GENERAL
        )

        /** Image used as a storage image in graphics shaders. */
        val STORAGE_IMAGE_GRAPHICS = ResourceUsage(
            VK_PIPELINE_STAGE_2_ALL_GRAPHICS_BIT,
            VK_ACCESS_2_SHADER_READ_BIT or VK_ACCESS_2_SHADER_WRITE_BIT,
            VK10.VK_IMAGE_LAYOUT_GENERAL
        )

        /** Transfer destination (image copy/blit target). */
        val IMAGE_TRANSFER_DST = ResourceUsage(
            VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
            VK_ACCESS_2_TRANSFER_WRITE_BIT,
            VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
        )

        /** Transfer source (image copy/blit source). */
        val IMAGE_TRANSFER_SRC = ResourceUsage(
            VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
            VK_ACCESS_2_TRANSFER_READ_BIT,
            VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL
        )

        /** Swapchain image ready for presentation. */
        val PRESENT = ResourceUsage(
            VK_PIPELINE_STAGE_2_NONE,
            VK_ACCESS_2_NONE,
            KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR
        )
    }

    /** True if this usage includes any write access. */
    val isWrite: Boolean
        get() {
            val writeBits = VK_ACCESS_2_SHADER_WRITE_BIT or
                    VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT or
                    VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT or
                    VK_ACCESS_2_TRANSFER_WRITE_BIT or
                    VK_ACCESS_2_HOST_WRITE_BIT or
                    VK_ACCESS_2_MEMORY_WRITE_BIT
            return (accessMask and writeBits) != 0L
        }

    /** True if this usage includes any read access. */
    val isRead: Boolean
        get() {
            val readBits = VK_ACCESS_2_SHADER_READ_BIT or
                    VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT or
                    VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT or
                    VK_ACCESS_2_TRANSFER_READ_BIT or
                    VK_ACCESS_2_HOST_READ_BIT or
                    VK_ACCESS_2_MEMORY_READ_BIT or
                    VK_ACCESS_2_VERTEX_ATTRIBUTE_READ_BIT or
                    VK_ACCESS_2_INDEX_READ_BIT or
                    VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT
            return (accessMask and readBits) != 0L
        }
}

/**
 * Tracks per-resource last-known usage within a command buffer recording session.
 * When a resource transitions to a new usage, automatically determines whether a pipeline barrier
 * is needed and records it.
 *
 * Attach to a [CommandBuffer] and call [transitionBuffer] / [transitionImage] before each use.
 * Call [reset] when the command buffer is reset or re-recorded.
 */
class ResourceTracker {

    // Keyed by VkBuffer handle or VkImage handle
    private val lastBufferAccess = HashMap<Long, ResourceUsage>()
    private val lastImageAccess = HashMap<Long, ResourceUsage>()

    // Pending barriers batched for a single vkCmdPipelineBarrier2 call
    private val pendingBufferBarriers = ArrayList<PendingBufferBarrier>()
    private val pendingImageBarriers = ArrayList<PendingImageBarrier>()

    private data class PendingBufferBarrier(
        val buffer: Long,
        val size: Long,
        val from: ResourceUsage,
        val to: ResourceUsage
    )

    private data class PendingImageBarrier(
        val image: Long,
        val from: ResourceUsage,
        val to: ResourceUsage,
        val aspectMask: Int,
        val baseMipLevel: Int,
        val levelCount: Int,
        val baseArrayLayer: Int,
        val layerCount: Int
    )

    /**
     * Transition a buffer to a new usage. If the previous usage was a write and the new usage
     * reads (or writes) the same resource, a barrier is recorded immediately.
     *
     * @return true if a barrier was inserted
     */
    fun transitionBuffer(
        cmd: CommandBuffer,
        buffer: Long,
        size: Long,
        to: ResourceUsage
    ): Boolean {
        if (buffer == 0L) return false

        val from = lastBufferAccess[buffer]
        lastBufferAccess[buffer] = to

        if (from == null || from == ResourceUsage.NONE) return false
        if (!needsBarrier(from, to)) return false

        recordBufferBarrier(cmd, buffer, size, from, to)
        return true
    }

    /**
     * Transition a [VulkanBuffer] to a new usage, using its handle and size automatically.
     */
    fun transitionBuffer(
        cmd: CommandBuffer,
        buffer: VulkanBuffer,
        to: ResourceUsage
    ): Boolean = transitionBuffer(cmd, buffer.buffer, buffer.requestedSize, to)

    /**
     * Transition an image to a new usage. Handles both memory barriers and layout transitions.
     * The old layout is taken from the tracked state; if no state is tracked, UNDEFINED is assumed.
     *
     * @return true if a barrier was inserted
     */
    fun transitionImage(
        cmd: CommandBuffer,
        image: Long,
        to: ResourceUsage,
        aspectMask: Int = VK10.VK_IMAGE_ASPECT_COLOR_BIT,
        baseMipLevel: Int = 0,
        levelCount: Int = 1,
        baseArrayLayer: Int = 0,
        layerCount: Int = 1
    ): Boolean {
        if (image == 0L) return false

        val from = lastImageAccess[image] ?: ResourceUsage.NONE
        lastImageAccess[image] = to

        // Always transition if layouts differ
        val layoutChanged = from.imageLayout != to.imageLayout && to.imageLayout != VK_IMAGE_LAYOUT_UNDEFINED
        if (!layoutChanged && !needsBarrier(from, to)) return false

        recordImageBarrier(cmd, image, from, to, aspectMask, baseMipLevel, levelCount, baseArrayLayer, layerCount)
        return true
    }

    /**
     * Transition an [Image] to a new usage.
     */
    fun transitionImage(
        cmd: CommandBuffer,
        image: Image,
        to: ResourceUsage,
        aspectMask: Int = VK10.VK_IMAGE_ASPECT_COLOR_BIT,
        baseMipLevel: Int = 0,
        levelCount: Int = image.mipLevels.coerceAtLeast(1),
        baseArrayLayer: Int = 0,
        layerCount: Int = image.imageData.arrayLayers
    ): Boolean {
        val result = transitionImage(cmd, image.vkImage, to, aspectMask, baseMipLevel, levelCount, baseArrayLayer, layerCount)
        if (result) {
            image.setLayoutFor(cmd.vkCommandBuffer.address(), to.imageLayout)
        }
        return result
    }

    /**
     * Record the current usage of a resource without inserting a barrier.
     * Use this to "prime" the tracker with the known initial state at the start of a frame
     * or after an external barrier has already been recorded.
     */
    fun setBufferUsage(buffer: Long, usage: ResourceUsage) {
        if (buffer != 0L) lastBufferAccess[buffer] = usage
    }

    /** Prime the tracker for a [VulkanBuffer]. */
    fun setBufferUsage(buffer: VulkanBuffer, usage: ResourceUsage) =
        setBufferUsage(buffer.buffer, usage)

    /** Set the known state of an image without inserting a barrier. */
    fun setImageUsage(image: Long, usage: ResourceUsage) {
        if (image != 0L) lastImageAccess[image] = usage
    }

    /** Set the known state of an [Image] without inserting a barrier. */
    fun setImageUsage(image: Image, usage: ResourceUsage) =
        setImageUsage(image.vkImage, usage)

    /** Query the last known usage of a buffer, or null if untracked. */
    fun getBufferUsage(buffer: Long): ResourceUsage? = lastBufferAccess[buffer]

    /** Query the last known usage of an image, or null if untracked. */
    fun getImageUsage(image: Long): ResourceUsage? = lastImageAccess[image]

    /** Clear all tracked state. Call when the command buffer is reset or re-recorded. */
    fun reset() {
        lastBufferAccess.clear()
        lastImageAccess.clear()
        pendingBufferBarriers.clear()
        pendingImageBarriers.clear()
    }

    // --- Batched barrier recording ---

    /**
     * Stage a buffer barrier for deferred recording. Call [flushBarriers] to record all
     * pending barriers in a single vkCmdPipelineBarrier2 call. This is more efficient than
     * individual barriers when transitioning many resources at once.
     */
    fun deferBufferTransition(buffer: Long, size: Long, to: ResourceUsage): Boolean {
        if (buffer == 0L) return false

        val from = lastBufferAccess[buffer]
        lastBufferAccess[buffer] = to

        if (from == null || from == ResourceUsage.NONE) return false
        if (!needsBarrier(from, to)) return false

        pendingBufferBarriers.add(PendingBufferBarrier(buffer, size, from, to))
        return true
    }

    /** Stage a buffer barrier for deferred recording. */
    fun deferBufferTransition(buffer: VulkanBuffer, to: ResourceUsage): Boolean =
        deferBufferTransition(buffer.buffer, buffer.requestedSize, to)

    /**
     * Stage an image barrier for deferred recording.
     */
    fun deferImageTransition(
        image: Long,
        to: ResourceUsage,
        aspectMask: Int = VK10.VK_IMAGE_ASPECT_COLOR_BIT,
        baseMipLevel: Int = 0,
        levelCount: Int = 1,
        baseArrayLayer: Int = 0,
        layerCount: Int = 1
    ): Boolean {
        if (image == 0L) return false

        val from = lastImageAccess[image] ?: ResourceUsage.NONE
        lastImageAccess[image] = to

        val layoutChanged = from.imageLayout != to.imageLayout && to.imageLayout != VK_IMAGE_LAYOUT_UNDEFINED
        if (!layoutChanged && !needsBarrier(from, to)) return false

        pendingImageBarriers.add(PendingImageBarrier(image, from, to, aspectMask, baseMipLevel, levelCount, baseArrayLayer, layerCount))
        return true
    }

    /**
     * Record all deferred buffer and image barriers in a single vkCmdPipelineBarrier2 call,
     * then clear the pending lists.
     */
    fun flushBarriers(cmd: CommandBuffer) {
        if (pendingBufferBarriers.isEmpty() && pendingImageBarriers.isEmpty()) return

        MemoryStack.stackPush().use { stack ->
            val dep = VkDependencyInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO)

            if (pendingBufferBarriers.isNotEmpty()) {
                val barriers = VkBufferMemoryBarrier2.calloc(pendingBufferBarriers.size, stack)
                pendingBufferBarriers.forEachIndexed { i, pb ->
                    barriers[i]
                        .sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER_2)
                        .srcStageMask(pb.from.stageMask)
                        .srcAccessMask(pb.from.accessMask)
                        .dstStageMask(pb.to.stageMask)
                        .dstAccessMask(pb.to.accessMask)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .buffer(pb.buffer)
                        .offset(0L)
                        .size(pb.size)
                }
                dep.pBufferMemoryBarriers(barriers)
            }

            if (pendingImageBarriers.isNotEmpty()) {
                val barriers = VkImageMemoryBarrier2.calloc(pendingImageBarriers.size, stack)
                pendingImageBarriers.forEachIndexed { i, pi ->
                    barriers[i]
                        .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2)
                        .srcStageMask(pi.from.stageMask)
                        .srcAccessMask(pi.from.accessMask)
                        .dstStageMask(pi.to.stageMask)
                        .dstAccessMask(pi.to.accessMask)
                        .oldLayout(pi.from.imageLayout)
                        .newLayout(pi.to.imageLayout)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(pi.image)
                        .subresourceRange {
                            it.aspectMask(pi.aspectMask)
                                .baseMipLevel(pi.baseMipLevel)
                                .levelCount(pi.levelCount)
                                .baseArrayLayer(pi.baseArrayLayer)
                                .layerCount(pi.layerCount)
                        }
                }
                dep.pImageMemoryBarriers(barriers)
            }

            vkCmdPipelineBarrier2(cmd.vkCommandBuffer, dep)
        }

        pendingBufferBarriers.clear()
        pendingImageBarriers.clear()
    }

    // --- Internal ---

    companion object {
        /**
         * A barrier is needed when:
         * 1. The previous access wrote AND the next access reads or writes (WAR or WAW hazard)
         * 2. The previous access reads AND the next access writes (RAW hazard)
         *
         * Read-after-read needs no barrier (both are reads, no hazard).
         */
        fun needsBarrier(from: ResourceUsage, to: ResourceUsage): Boolean {
            if (from == ResourceUsage.NONE) return false
            // WAW — write followed by write
            if (from.isWrite && to.isWrite) return true
            // WAR — write followed by read
            if (from.isWrite && to.isRead) return true
            // RAW — read followed by write
            if (from.isRead && to.isWrite) return true
            // RAR — read followed by read: no barrier needed
            return false
        }
    }

    private fun recordBufferBarrier(
        cmd: CommandBuffer,
        buffer: Long,
        size: Long,
        from: ResourceUsage,
        to: ResourceUsage
    ) {
        MemoryStack.stackPush().use { stack ->
            val barrier = VkBufferMemoryBarrier2.calloc(1, stack)
            barrier[0]
                .sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER_2)
                .srcStageMask(from.stageMask)
                .srcAccessMask(from.accessMask)
                .dstStageMask(to.stageMask)
                .dstAccessMask(to.accessMask)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .buffer(buffer)
                .offset(0L)
                .size(size)

            val dep = VkDependencyInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO)
                .pBufferMemoryBarriers(barrier)

            vkCmdPipelineBarrier2(cmd.vkCommandBuffer, dep)
        }
    }

    private fun recordImageBarrier(
        cmd: CommandBuffer,
        image: Long,
        from: ResourceUsage,
        to: ResourceUsage,
        aspectMask: Int,
        baseMipLevel: Int,
        levelCount: Int,
        baseArrayLayer: Int,
        layerCount: Int
    ) {
        MemoryStack.stackPush().use { stack ->
            val barrier = VkImageMemoryBarrier2.calloc(1, stack)
            barrier[0]
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2)
                .srcStageMask(from.stageMask)
                .srcAccessMask(from.accessMask)
                .dstStageMask(to.stageMask)
                .dstAccessMask(to.accessMask)
                .oldLayout(from.imageLayout)
                .newLayout(to.imageLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image)
                .subresourceRange {
                    it.aspectMask(aspectMask)
                        .baseMipLevel(baseMipLevel)
                        .levelCount(levelCount)
                        .baseArrayLayer(baseArrayLayer)
                        .layerCount(layerCount)
                }

            val dep = VkDependencyInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO)
                .pImageMemoryBarriers(barrier)

            vkCmdPipelineBarrier2(cmd.vkCommandBuffer, dep)
        }
    }
}

// =====================================================================================
// Extension functions for ergonomic use
// =====================================================================================

/**
 * Wrap this buffer so that accessing its GPU address via [HasBuffer.getGPUAddress] automatically
 * transitions it to the specified [usage] via the command buffer's [ResourceTracker].
 *
 * Example:
 * ```
 * val particleData: VulkanBuffer = ...
 * // In compute dispatch:
 * val addr = particleData.tracked(ResourceUsage.COMPUTE_READ_WRITE).getGPUAddress(cmd)
 * // In graphics draw:
 * val addr = particleData.tracked(ResourceUsage.GRAPHICS_READ).getGPUAddress(cmd)
 * ```
 */
fun VulkanBuffer.tracked(usage: ResourceUsage): HasBuffer {
    val self = this
    return object : HasBuffer {
        override fun getBuffer(): Long = self.buffer
        override fun getGPUAddress(cmd: CommandBuffer): Long {
            cmd.resourceTracker.transitionBuffer(cmd, self, usage)
            return self.getGPUAddress(cmd)
        }
    }
}

/**
 * Wrap any [HasBuffer] so that accessing its GPU address automatically records a tracked
 * transition to the specified [usage].
 */
fun HasBuffer.tracked(usage: ResourceUsage): HasBuffer {
    val self = this
    return object : HasBuffer {
        override fun getBuffer(): Long = self.getBuffer()
        override fun getGPUAddress(cmd: CommandBuffer): Long {
            cmd.resourceTracker.transitionBuffer(cmd, self.getBuffer(), VK_WHOLE_SIZE, usage)
            return self.getGPUAddress(cmd)
        }
    }
}

/** Shorthand: wrap for compute read access. */
val VulkanBuffer.trackedComputeRead: HasBuffer get() = tracked(ResourceUsage.COMPUTE_READ)

/** Shorthand: wrap for compute write access. */
val VulkanBuffer.trackedComputeWrite: HasBuffer get() = tracked(ResourceUsage.COMPUTE_WRITE)

/** Shorthand: wrap for compute read+write access. */
val VulkanBuffer.trackedComputeReadWrite: HasBuffer get() = tracked(ResourceUsage.COMPUTE_READ_WRITE)

/** Shorthand: wrap for graphics read access. */
val VulkanBuffer.trackedGraphicsRead: HasBuffer get() = tracked(ResourceUsage.GRAPHICS_READ)

/** Shorthand: wrap for graphics write access. */
val VulkanBuffer.trackedGraphicsWrite: HasBuffer get() = tracked(ResourceUsage.GRAPHICS_WRITE)

/** Shorthand: wrap for host read (CPU readback after GPU writes). */
val VulkanBuffer.trackedHostRead: HasBuffer get() = tracked(ResourceUsage.HOST_READ)
