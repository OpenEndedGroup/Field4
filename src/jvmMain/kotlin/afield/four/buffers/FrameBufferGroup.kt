package afield.four.buffers

import afield.four.Renderer
import afield.four.device.CommandBuffer
import afield.four.device.Fence
import afield.stdlib.invoke
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.VkBufferCopy
import org.lwjgl.vulkan.VkBufferMemoryBarrier2
import org.lwjgl.vulkan.VkDependencyInfo
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer

enum class BufferAccessPattern {
    HOST_VISIBLE,
    STAGED_WRITE_ONCE,
    STAGED_STREAM_WRITE,
    DEVICE_LOCAL_DEBUG_READ,
    HOST_VISIBLE_STREAM_READ,
    DEVICE_LOCAL_ZERO
}

class FrameBufferGroup(
    val renderer: Renderer,
    val n: Int = renderer.numImages,
    val buffers: MutableList<VulkanBuffer> = mutableListOf(),
    val accessPattern: BufferAccessPattern = BufferAccessPattern.HOST_VISIBLE,
    private val stagedUploads: Boolean = accessPattern == BufferAccessPattern.STAGED_WRITE_ONCE ||
            accessPattern == BufferAccessPattern.STAGED_STREAM_WRITE,
    private val readbackStaging: Boolean = accessPattern == BufferAccessPattern.DEVICE_LOCAL_DEBUG_READ
) : HasBuffer, DynamicallyWriteable, BufferWriteable, BufferReadable {

    @Volatile
    var lastWritten = -1
    private val stagingBuffers = mutableListOf<VulkanBuffer>()
    private val readbackBuffers = mutableListOf<VulkanBuffer>()
    private val pendingUploads = mutableSetOf<Int>()
    private val pendingZeros = mutableSetOf<Int>()
    private var transferFence: Fence? = null

    val usesStagedUploads: Boolean
        get() = stagedUploads

    val usesReadbackStaging: Boolean
        get() = readbackStaging

    fun get(write : Boolean = false): VulkanBuffer {
        return getAt(renderer.currentImage, write)
    }

    fun getAt(imageIndex: Int, write: Boolean = false): VulkanBuffer {
        if (write) markAsWritten(imageIndex)
        return buffers[imageIndex % buffers.size]
    }

    private fun stagingAt(imageIndex: Int): VulkanBuffer {
        require(usesStagedUploads) { "FrameBufferGroup is not configured for staged CPU writes" }
        return stagingBuffers[imageIndex % stagingBuffers.size]
    }

    private fun readbackAt(imageIndex: Int): VulkanBuffer {
        require(usesReadbackStaging) { "FrameBufferGroup is not configured for staged CPU reads" }
        return readbackBuffers[imageIndex % readbackBuffers.size]
    }

    fun read(builder: FloatBuffer.() -> Unit) {
        mapAndRead(builder)
    }

    // it's very likely that this should be earlier than the write buffer?
    fun read_ints(builder: IntBuffer.() -> Unit) {
        mapAndRead_ints(builder)
    }

    fun getLastWritten() = if (lastWritten == -1) get() else buffers[lastWritten % buffers.size]

    fun markAsWritten(imageIndex: Int = renderer.currentImage) {
        lastWritten = imageIndex % buffers.size
    }

    fun build(builder: (Int) -> VulkanBuffer) {
        buffers.clear()
        n {
            buffers.add(builder(it))
        }
        if (accessPattern == BufferAccessPattern.DEVICE_LOCAL_ZERO) {
            pendingZeros.clear()
            buffers.indices.forEach { pendingZeros += it }
        }
    }

    fun buildStaging(builder: (Int) -> VulkanBuffer) {
        stagingBuffers.clear()
        n {
            stagingBuffers.add(builder(it))
        }
    }

    fun buildReadback(builder: (Int) -> VulkanBuffer) {
        readbackBuffers.clear()
        n {
            readbackBuffers.add(builder(it))
        }
    }

    override fun getBuffer() = get().buffer

    override fun getGPUAddress(cmd: CommandBuffer): Long {
        clean(false, cmd)
        return getLastWritten().getGPUAddress(cmd)
    }

    override fun write(builder: DynamicBuffer.() -> Unit): DynamicallyWriteable {
        val imageIndex = renderer.currentImage
        writable(imageIndex).write(builder)
        noteWrite(imageIndex)

        // todo: should we take a write barrier to shader here ?
        return this
    }

    override val cursor : Int
        get() = get(write=false).writePosition


    override fun append(builder: DynamicBuffer.() -> Unit): DynamicallyWriteable {
        val imageIndex = renderer.currentImage
        writable(imageIndex).append(builder)
        noteWrite(imageIndex)

        // todo: should we take a write barrier to shader here ?
        return this
    }

    override fun mapAndWrite(builder: FloatBuffer.() -> Unit): FrameBufferGroup {
        val imageIndex = renderer.currentImage
        writable(imageIndex).mapAndWrite(builder)
        noteWrite(imageIndex)
        return this
    }

    override fun mapAndWrite_bytes(builder: ByteBuffer.() -> Unit): FrameBufferGroup {
        val imageIndex = renderer.currentImage
        writable(imageIndex).mapAndWrite_bytes(builder)
        noteWrite(imageIndex)
        return this
    }

    override fun mapAndWrite_ints(builder: IntBuffer.() -> Unit): FrameBufferGroup {
        val imageIndex = renderer.currentImage
        writable(imageIndex).mapAndWrite_ints(builder)
        noteWrite(imageIndex)
        return this
    }

    override fun clean(hostReading: Boolean, cmd: CommandBuffer?): FrameBufferGroup {
        if (hostReading) return this
        if (pendingZeros.isEmpty() && (!usesStagedUploads || pendingUploads.isEmpty())) return this
        if (cmd == null) {
            pendingZeros.toList().forEach { submitZeroNow(it) }
            pendingUploads.toList().forEach { submitUploadNow(it) }
            return this
        }
        pendingZeros.toList().forEach { imageIndex ->
            if (cmd.inRenderPassInstance) {
                submitZeroNow(imageIndex)
            } else {
                recordZero(cmd, imageIndex)
            }
            pendingZeros.remove(imageIndex)
        }
        pendingUploads.toList().forEach { imageIndex ->
            if (cmd.inRenderPassInstance) {
                submitUploadNow(imageIndex)
            } else {
                recordUpload(cmd, imageIndex)
            }
            pendingUploads.remove(imageIndex)
        }
        return this
    }

    override fun mapAndRead(builder: FloatBuffer.() -> Unit): FrameBufferGroup {
        if (usesReadbackStaging) {
            copyToReadbackNow(renderer.currentImage)
            readbackAt(renderer.currentImage).mapAndRead(builder)
        } else {
            get(write = false).mapAndRead(builder)
        }
        return this
    }

    override fun mapAndRead_bytes(builder: ByteBuffer.() -> Unit): FrameBufferGroup {
        if (usesReadbackStaging) {
            copyToReadbackNow(renderer.currentImage)
            readbackAt(renderer.currentImage).mapAndRead_bytes(builder)
        } else {
            get(write = false).mapAndRead_bytes(builder)
        }
        return this
    }

    override fun mapAndRead_ints(builder: IntBuffer.() -> Unit): FrameBufferGroup {
        if (usesReadbackStaging) {
            copyToReadbackNow(renderer.currentImage)
            readbackAt(renderer.currentImage).mapAndRead_ints(builder)
        } else {
            get(write = false).mapAndRead_ints(builder)
        }
        return this
    }

    private fun writable(imageIndex: Int): VulkanBuffer =
        if (usesStagedUploads) stagingAt(imageIndex) else getAt(imageIndex, write = true)

    private fun noteWrite(imageIndex: Int) {
        markAsWritten(imageIndex)
        if (usesStagedUploads) pendingUploads += imageIndex % buffers.size
    }

    private fun recordUpload(cmd: CommandBuffer, imageIndex: Int) {
        val index = imageIndex % buffers.size
        val src = stagingBuffers[index]
        val dst = buffers[index]
        val copySize = minOf(src.requestedSize, dst.requestedSize)
        if (copySize <= 0) return
        MemoryStack.stackPush().use { stack ->
            val copyRegion = VkBufferCopy.calloc(1, stack).srcOffset(0).dstOffset(0).size(copySize)
            vkCmdCopyBuffer(cmd.vkCommandBuffer, src.buffer, dst.buffer, copyRegion)
            recordTransferToShaderBarrier(cmd, dst, copySize)
        }
    }

    private fun recordTransferToShaderBarrier(cmd: CommandBuffer, dst: VulkanBuffer, size: Long) {
        MemoryStack.stackPush().use { stack ->
            val barriers = VkBufferMemoryBarrier2.calloc(1, stack)
            barriers[0]
                .sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER_2)
                .srcStageMask(VK_PIPELINE_STAGE_2_COPY_BIT)
                .srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
                .dstAccessMask(
                    VK_ACCESS_2_SHADER_READ_BIT or VK_ACCESS_2_SHADER_WRITE_BIT or
                            VK_ACCESS_2_UNIFORM_READ_BIT or VK_ACCESS_2_SHADER_STORAGE_READ_BIT or
                            VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT
                )
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .buffer(dst.buffer)
                .offset(0L)
                .size(size)

            val dep = VkDependencyInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO)
                .pBufferMemoryBarriers(barriers)
            vkCmdPipelineBarrier2(cmd.vkCommandBuffer, dep)
        }
    }

    private fun submitUploadNow(imageIndex: Int) {
        transferFence?.let {
            if (!it.isSignaled()) it.fenceWait()
        }
        val cmd = CommandBuffer(renderer.commandPool, true, true)
        cmd.beginRecording()
        recordUpload(cmd, imageIndex)
        cmd.endRecording()
        val fence = transferFence ?: Fence(renderer.device, true).also { transferFence = it }
        fence.reset()
        MemoryStack.stackPush().use { stack ->
            renderer.graphQueue.submit(stack.pointers(cmd.vkCommandBuffer), null, null, null, fence)
        }
        renderer.cleanup.add {
            fence.fenceWait()
            cmd.cleanup()
        }
        pendingUploads.remove(imageIndex % buffers.size)
    }

    private fun recordZero(cmd: CommandBuffer, imageIndex: Int) {
        val dst = buffers[imageIndex % buffers.size]
        if (dst.requestedSize <= 0) return
        vkCmdFillBuffer(cmd.vkCommandBuffer, dst.buffer, 0L, dst.requestedSize, 0)
        recordTransferToShaderBarrier(cmd, dst, dst.requestedSize)
    }

    private fun submitZeroNow(imageIndex: Int) {
        transferFence?.let {
            if (!it.isSignaled()) it.fenceWait()
        }
        val cmd = CommandBuffer(renderer.commandPool, true, true)
        cmd.beginRecording()
        recordZero(cmd, imageIndex)
        cmd.endRecording()
        val fence = transferFence ?: Fence(renderer.device, true).also { transferFence = it }
        fence.reset()
        MemoryStack.stackPush().use { stack ->
            renderer.graphQueue.submit(stack.pointers(cmd.vkCommandBuffer), null, null, null, fence)
        }
        renderer.cleanup.add {
            fence.fenceWait()
            cmd.cleanup()
        }
        pendingZeros.remove(imageIndex % buffers.size)
    }

    private fun copyToReadbackNow(imageIndex: Int) {
        val index = imageIndex % buffers.size
        val src = buffers[index]
        val dst = readbackBuffers[index]
        val copySize = minOf(src.requestedSize, dst.requestedSize)
        if (copySize <= 0) return
        val cmd = CommandBuffer(renderer.commandPool, true, true)
        cmd.beginRecording()
        MemoryStack.stackPush().use { stack ->
            val copyRegion = VkBufferCopy.calloc(1, stack).srcOffset(0).dstOffset(0).size(copySize)
            vkCmdCopyBuffer(cmd.vkCommandBuffer, src.buffer, dst.buffer, copyRegion)
        }
        cmd.endRecording()
        val fence = transferFence ?: Fence(renderer.device, true).also { transferFence = it }
        fence.reset()
        MemoryStack.stackPush().use { stack ->
            renderer.graphQueue.submit(stack.pointers(cmd.vkCommandBuffer), null, null, null, fence)
        }
        fence.fenceWait()
        cmd.cleanup()
    }
}
