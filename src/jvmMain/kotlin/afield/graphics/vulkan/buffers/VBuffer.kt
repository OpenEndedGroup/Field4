package afield.graphics.vulkan.buffers

import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.device.CommandBuffer
import afield.graphics.vulkan.device.Fence
import afield.app.diagnostics.logging.lprintln
import org.lwjgl.BufferUtils
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.VkBufferCopy
import org.lwjgl.vulkan.VkBufferMemoryBarrier2
import org.lwjgl.vulkan.VkDependencyInfo
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * VertexBuffer is a classic vertex buffer with a host-side staging copy that is transferred to device-local memory before drawing.
 *
 * Most Field geometry instead pulls vertices in the shader through device addresses set in push
 * constants, which needs no staging step; [VertexBuffer] remains for code that binds vertex buffers
 * directly.
 */

/**
 * this is, as far as I can tell, completely decoy — with resizable BAR (or unified memory on Apple Silicon) this staging buffer thing is just excess engineering _and_ writing shaders that do their own pulling is better _and_ device pointers set through push constants is faster and architecturally skips all of the descriptor nonsense
 */
class VertexBuffer(
    /** The renderer whose device both allocations are made on, and whose graphics queue carries the copy. */
    val renderer: Renderer,
    /** Bytes. Both the staging and the device-local allocation are this size, for the life of the buffer; [trimTo] narrows what is copied, not what is allocated. */
    val size: Long,
    /** The `VkBufferUsageFlags` the device-local buffer carries beyond transfer-destination. It also decides which reads the transfer barriers cover: vertex-attribute reads for a vertex buffer, index reads for an index buffer, and no barrier at all for a usage with neither bit. */
    val usage: Int = VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
    /** Whether to allocate the spare host-side [cpu] buffer. Nothing in the transfer path reads it. */
    val createCPU: Boolean = false
) : HasBuffer, BufferWriteable {

    /**
     * A plain host [size]-byte buffer, allocated only when `createCPU` was asked for, and null
     * otherwise. It is not the staging buffer and is not part of the upload: nothing here writes to
     * it or reads from it, and the renderer's buffer inspector only reports whether it exists.
     */
    val cpu = if (createCPU) BufferUtils.createByteBuffer(size.toInt()) else null

    /** The host-visible, host-coherent staging buffer. The `mapAndWrite` family writes here, and a transfer copies it into [dest]. */
    val src = VulkanBuffer(
        renderer,
        size,
        VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
        VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
    )
    /** The device-local buffer the GPU draws from, carrying [usage] on top of transfer-destination. This is the handle [getBuffer] returns. */
    val dest = VulkanBuffer(
        renderer,
        size,
        VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT or usage,
        VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
    )

    /** Counts host writes: every `mapAndWrite` call raises it by one, whether or not the block it ran completed. */
    var watermark = 0

    /**
     * The [watermark] the last transfer copied. While the two differ the device-local buffer is
     * behind the staging one, and [clean] or [transfer] will copy; a transfer sets them equal. It
     * starts at -1 so that a buffer written once before its first transfer is copied.
     */
    var transferWatermark = -1

    /**
     * How many bytes a transfer copies, starting at the whole [size] and narrowed by [trimTo].
     * At zero or less the copy and both barriers are skipped entirely, so a trimmed-to-nothing
     * buffer still reports itself clean after a transfer.
     */
    var neededSize = size

    /** Size, usage and whether the staging copy is still ahead of the device one, as `DIRTY` or `clean`. */
    override fun toString(): String {
        val status = if (watermark != transferWatermark) "DIRTY" else "clean"
        return "VertexBuffer(render, size=$size, usage=$usage $status)"
    }

    /**
     * Brings the device-local buffer up to date with the staging one, if it is not already.
     *
     * With a [cmd] to record into, the copy is recorded there and happens in queue order with the
     * rest of that command buffer. Without one, or when [cmd] is already inside a render pass
     * instance, the copy instead goes out on its own command buffer and is waited on before this
     * returns, because `vkCmdCopyBuffer` may not be recorded inside a render pass.
     *
     * [hostReading] means the caller is about to read on the host, which this buffer has no path
     * for, so nothing is done.
     */
    override fun clean(hostReading: Boolean, cmd: CommandBuffer?): VertexBuffer {
        if (!hostReading) {
            if (transferWatermark != watermark) {
                if (cmd == null)
                    transferNow()
                else {
                    if (cmd.inRenderPassInstance) {
                        // vkCmdCopyBuffer is not allowed inside a render pass instance (including dynamic rendering).
                        // Fall back to an immediate transfer on a separate command buffer to preserve correctness.
                        transferNow()
                    } else {
                        transfer(cmd, immediate = false)
                    }
                }
            }
        }
        return this
    }

    /**
     * Maps the staging buffer and hands [builder] a float view of the whole allocation, then
     * unmaps and marks the device copy out of date. The view holds [size] / 4 floats and starts at
     * position zero on every call, so a partial write leaves the rest of the previous contents in
     * place. The buffer is unmapped and the watermark raised even if the block throws.
     */
    override fun mapAndWrite(builder: FloatBuffer.() -> Unit): VertexBuffer {
        val rawMemory = src.map()
        try {
            builder(MemoryUtil.memFloatBuffer(rawMemory, src.requestedSize.toInt() / 4))
        } finally {
            src.unmap()
            watermark++
        }
        return this
    }

    /** [mapAndWrite] with an integer view of the same memory, [size] / 4 ints wide. Index data goes here. */
    override fun mapAndWrite_ints(builder: IntBuffer.() -> Unit): VertexBuffer {
        val rawMemory = src.map()
        try {
            builder(MemoryUtil.memIntBuffer(rawMemory, src.requestedSize.toInt() / 4))
        } finally {
            src.unmap()
            watermark++
        }
        return this
    }

    /** [mapAndWrite] with a byte view of the same memory, the full [size]. Use it for packed or mixed vertex formats. */
    override fun mapAndWrite_bytes(builder: ByteBuffer.() -> Unit): VertexBuffer {
        val rawMemory = src.map()
        try {
            builder(MemoryUtil.memByteBuffer(rawMemory, src.requestedSize.toInt()))
        } finally {
            src.unmap()
            watermark++
        }
        return this
    }

    private fun recordTransferToInputBarrier(cmd: CommandBuffer) {
        var dstAccessMask = 0L
        if ((usage and VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT) != 0) {
            dstAccessMask = dstAccessMask or VK_ACCESS_2_VERTEX_ATTRIBUTE_READ_BIT
        }
        if ((usage and VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT) != 0) {
            dstAccessMask = dstAccessMask or VK_ACCESS_2_INDEX_READ_BIT
        }
        if (dstAccessMask == 0L || neededSize <= 0) return

        MemoryStack.stackPush().use { stack ->
            val barriers = VkBufferMemoryBarrier2.calloc(1, stack)
            barriers[0]
                .sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER_2)
                .srcStageMask(VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT)
                .srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_2_VERTEX_INPUT_BIT)
                .dstAccessMask(dstAccessMask)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .buffer(dest.buffer)
                .offset(0L)
                .size(neededSize)

            val dep = VkDependencyInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO)
                .pBufferMemoryBarriers(barriers)
            vkCmdPipelineBarrier2(cmd.vkCommandBuffer, dep)
        }

        if (debugTransferInputBarrier) {
            val accessKinds = buildList {
                if ((dstAccessMask and VK_ACCESS_2_VERTEX_ATTRIBUTE_READ_BIT) != 0L) add("vertex")
                if ((dstAccessMask and VK_ACCESS_2_INDEX_READ_BIT) != 0L) add("index")
            }.joinToString("+")
            lprintln(
                "debug,vulkan,sync",
                "transfer->vertex-input barrier recorded buf=0x${dest.buffer.toULong().toString(16)} size=$neededSize access=$accessKinds usage=0x${usage.toUInt().toString(16)} cmd=0x${cmd.vkCommandBuffer.address().toULong().toString(16)}"
            )
        }
    }

    private fun recordInputToTransferBarrier(cmd: CommandBuffer) {
        var srcAccessMask = 0L
        if ((usage and VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT) != 0) {
            srcAccessMask = srcAccessMask or VK_ACCESS_2_VERTEX_ATTRIBUTE_READ_BIT
        }
        if ((usage and VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT) != 0) {
            srcAccessMask = srcAccessMask or VK_ACCESS_2_INDEX_READ_BIT
        }
        if (srcAccessMask == 0L || neededSize <= 0) return

        MemoryStack.stackPush().use { stack ->
            val barriers = VkBufferMemoryBarrier2.calloc(1, stack)
            barriers[0]
                .sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER_2)
                .srcStageMask(VK_PIPELINE_STAGE_2_VERTEX_INPUT_BIT)
                .srcAccessMask(srcAccessMask)
                .dstStageMask(VK_PIPELINE_STAGE_2_COPY_BIT)
                .dstAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .buffer(dest.buffer)
                .offset(0L)
                .size(neededSize)

            val dep = VkDependencyInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO)
                .pBufferMemoryBarriers(barriers)
            vkCmdPipelineBarrier2(cmd.vkCommandBuffer, dep)
        }
    }

    /**
     * Records the staging-to-device copy into [cmd], bracketed by the two barriers that move the
     * buffer between vertex input and transfer, and then counts it as clean. A buffer already at
     * its transfer watermark records nothing.
     *
     * [immediate] is for the renderer's upload accounting only: it decides which of the two
     * per-frame byte totals this copy lands in, and does not change what is recorded.
     */
    fun transfer(cmd: CommandBuffer, immediate: Boolean = false) {

        if (transferWatermark != watermark) {
            if (neededSize > 0) {
                recordInputToTransferBarrier(cmd)
                MemoryStack.stackPush().use { stack ->
                    val copyRegion = VkBufferCopy.calloc(1, stack).srcOffset(0).dstOffset(0).size(neededSize)
                    VK10.vkCmdCopyBuffer(
                        cmd.vkCommandBuffer, src.buffer, dest.buffer, copyRegion
                    )
                }
                recordTransferToInputBarrier(cmd)
                renderer.recordVertexBufferUpload(neededSize, immediate)
            }
            transferWatermark = watermark
        }
    }

    /**
     * Does the transfer on a command buffer of its own and blocks until the GPU has finished it.
     *
     * This is the path [clean] takes when it has nowhere to record. The fence it waits on is a
     * single one shared by every [VertexBuffer], which is reset here before the submit, so two of
     * these running at once on two threads are not safe.
     */
    fun transferNow() {
        val cmd = CommandBuffer(renderer.commandPool, true, true)

        cmd.beginRecording()
        transfer(cmd, immediate = true)
        cmd.endRecording()

        // apparently we should be reusing a fence here
        if (fence==null)
            fence = afield.graphics.vulkan.device.Fence(renderer.device, true)

        fence!!.reset()
        MemoryStack.stackPush().use { stack ->
            renderer.graphQueue.submit(stack.pointers(cmd.vkCommandBuffer), null, null, null, fence)
        }
        fence!!.fenceWait()
        cmd.cleanup()
    }

    companion object {
        var fence : Fence? = null
        @JvmStatic
        var debugTransferInputBarrier: Boolean = parseDebugFlag(
            System.getenv("AFIELD_DEBUG_TRANSFER_INPUT_BARRIER"),
            System.getProperty("afield.debugTransferInputBarrier")
        )

        private fun parseDebugFlag(vararg raw: String?): Boolean {
            return raw.any {
                when (it?.trim()?.lowercase()) {
                    "1", "true", "yes", "on" -> true
                    else -> false
                }
            }
        }
    }

    /**
     * Queues both allocations for release on the renderer's cleanup list rather than freeing them
     * here, so a frame still reading this buffer finishes first. The buffer is unusable from the
     * moment that list runs, not from the moment this is called.
     */
    fun cleanup() {
        renderer.cleanup.add {
            src.cleanup()
            dest.cleanup()
        }
    }

    /**
     * Narrows the copy to the first [neededSize] bytes, for the common case of a buffer allocated
     * for a worst-case vertex count and filled with fewer. It changes neither allocation, and it
     * takes effect on the next transfer rather than marking the buffer dirty.
     */
    fun trimTo(neededSize: Long) {
        this.neededSize = neededSize
    }

    /** The device-local [dest] handle, which is what a draw binds; the staging buffer is never handed out. */
    override fun getBuffer(): Long {
        return dest.buffer
    }

    /**
     * Always throws: neither allocation is created with the device-address usage bit, so this
     * buffer cannot be reached by pointer. Shader-pulled geometry wants a [VulkanBuffer] built
     * with `addressable()` instead.
     */
    override fun getGPUAddress(cmd : CommandBuffer): Long {
        throw IllegalStateException("Not supported")
    }

}
