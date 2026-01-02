package afield.four.buffers

import afield.four.Renderer
import afield.four.device.CommandBuffer
import afield.four.device.Fence
import org.lwjgl.BufferUtils
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VkBufferCopy
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * this is, as far as I can tell, completely decoy — with resizable BAR (or unified memory on Apple Silicon) this staging buffer thing is just excess engineering _and_ writing shaders that do their own pulling is better _and_ device pointers set through push constants is faster and architecturally skips all of the descriptor nonsense
 */
class VertexBuffer(
    val renderer: Renderer,
    val size: Long,
    val usage: Int = VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, val createCPU: Boolean = false
) : HasBuffer, BufferWriteable {

    val cpu = if (createCPU) BufferUtils.createByteBuffer(size.toInt()) else null

    val src = VulkanBuffer(
        renderer,
        size,
        VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
        VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
    )
    val dest = VulkanBuffer(
        renderer,
        size,
        VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT or usage,
        VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
    )

    var watermark = 0
    var transferWatermark = -1
    var neededSize = size

    override fun toString(): String {
        val status = if (watermark != transferWatermark) "DIRTY" else "clean"
        return "VertexBuffer(render, size=$size, usage=$usage $status)"
    }

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
                        transfer(cmd)
                    }
                }
            }
        }
        return this
    }

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

    fun transfer(cmd: CommandBuffer) {

        if (transferWatermark != watermark) {
            if (neededSize > 0)
                MemoryStack.stackPush().use { stack ->
                    val copyRegion = VkBufferCopy.calloc(1, stack).srcOffset(0).dstOffset(0).size(neededSize)
                    VK10.vkCmdCopyBuffer(
                        cmd.vkCommandBuffer, src.buffer, dest.buffer, copyRegion
                    )
                }
            transferWatermark = watermark
        }
    }

    fun transferNow() {
        val cmd = CommandBuffer(renderer.commandPool, true, true)

        cmd.beginRecording()
        transfer(cmd)
        cmd.endRecording()

        // apparently we should be reusing a fence here
        if (fence==null)
            fence = afield.four.device.Fence(renderer.device, true)

        fence!!.reset()
        MemoryStack.stackPush().use { stack ->
            renderer.graphQueue.submit(stack.pointers(cmd.vkCommandBuffer), null, null, null, fence)
        }
        fence!!.fenceWait()
//        fence!!.cleanup()
        cmd.cleanup()
    }

    companion object {
        var fence : Fence? = null

    }

    fun cleanup() {
        renderer.cleanup.add {
            src.cleanup()
            dest.cleanup()
        }
    }

    fun trimTo(neededSize: Long) {
        this.neededSize = neededSize
    }

    // returns the dest buffer (the one for the GPU)
    override fun getBuffer(): Long {
        return dest.buffer
    }

    override fun getGPUAddress(cmd : CommandBuffer): Long {
        throw IllegalStateException("Not supported")
    }

}
