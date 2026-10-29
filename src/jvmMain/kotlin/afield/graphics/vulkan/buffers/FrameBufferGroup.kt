package afield.graphics.vulkan.buffers

import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.device.CommandBuffer
import afield.graphics.vulkan.device.Fence
import afield.data.collections.invoke
import afield.math.linalg.invoke
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.VkBufferCopy
import org.lwjgl.vulkan.VkBufferMemoryBarrier2
import org.lwjgl.vulkan.VkDependencyInfo
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * FrameBufferGroup holds one Vulkan buffer per swapchain image, so data written for the current frame never overwrites a buffer an in-flight frame is still reading.
 *
 * The [BufferAccessPattern] chosen at construction decides where the buffers live: host-visible
 * memory written directly, device-local memory fed through staging buffers (once or every frame), or
 * device-local memory with a host-visible copy for reading results back, host-cached memory read
 * every frame, or device-local memory zero-filled when built. Writes go to the current
 * image's buffer and are recorded with [FrameBufferGroup.markAsWritten];
 * [FrameBufferGroup.getLastWritten] returns the most recently written one.
 */

/**
 * Where a [FrameBufferGroup]'s buffers live and how data moves between host and device.
 */
enum class BufferAccessPattern {
    HOST_VISIBLE,
    STAGED_WRITE_ONCE,
    STAGED_STREAM_WRITE,
    DEVICE_LOCAL_DEBUG_READ,
    HOST_VISIBLE_STREAM_READ,
    DEVICE_LOCAL_ZERO
}

class FrameBufferGroup(
    /** The renderer the buffers belong to. It supplies the image currently being drawn, the image count, and the cleanup list the buffers are released on. */
    val renderer: Renderer,
    /** How many buffers [build] makes. It defaults to one per swapchain image; pass 1 for data that does not change between frames. */
    val n: Int = renderer.numImages,
    /** The buffers themselves, in image order. It is empty until [build] runs, and every lookup here takes its index modulo this list's size. */
    val buffers: MutableList<VulkanBuffer> = mutableListOf(),
    /** Where the buffers live and how data moves to and from them. It also sets the two staging flags below unless they are given explicitly. */
    val accessPattern: BufferAccessPattern = BufferAccessPattern.HOST_VISIBLE,
    private val stagedUploads: Boolean = accessPattern == BufferAccessPattern.STAGED_WRITE_ONCE ||
            accessPattern == BufferAccessPattern.STAGED_STREAM_WRITE,
    private val readbackStaging: Boolean = accessPattern == BufferAccessPattern.DEVICE_LOCAL_DEBUG_READ
) : HasBuffer, DynamicallyWriteable, BufferWriteable, BufferReadable {

    /**
     * The index into [buffers] of the one written most recently, or -1 before anything has been
     * written. [getLastWritten] falls back to the current image's buffer while it is -1.
     */
    @Volatile
    var lastWritten = -1
    private val stagingBuffers = mutableListOf<VulkanBuffer>()
    private val readbackBuffers = mutableListOf<VulkanBuffer>()
    private val pendingLock = Any()
    private val pendingUploads = mutableSetOf<Int>()
    private val pendingZeros = mutableSetOf<Int>()
    private var transferFence: Fence? = null

    /** Whether host writes land in a separate staging buffer and reach the device through a copy. [buildStaging] must then have been called too. */
    val usesStagedUploads: Boolean
        get() = stagedUploads

    /** Whether reads come back through a host-cached copy rather than straight out of the buffer. [buildReadback] must then have been called too. */
    val usesReadbackStaging: Boolean
        get() = readbackStaging

    /** The buffer for the image the renderer is drawing now. Passing `write = true` also records it as the most recently written. */
    fun get(write : Boolean = false): VulkanBuffer {
        return getAt(renderer.currentImage, write)
    }

    /**
     * The buffer for a given swapchain image. The index is taken modulo the number of buffers, so
     * a group built with one buffer answers with it for every image.
     */
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

    /** [mapAndRead] under a shorter name, for a float view of the results. */
    fun read(builder: FloatBuffer.() -> Unit) {
        mapAndRead(builder)
    }

    // it's very likely that this should be earlier than the write buffer?
    /** [mapAndRead_ints] under a shorter name, for an integer view of the results. */
    fun read_ints(builder: IntBuffer.() -> Unit) {
        mapAndRead_ints(builder)
    }

    /**
     * The buffer written most recently, which for a group written one frame and read the next is
     * the one holding data. Before anything has been written it is the current image's buffer.
     */
    fun getLastWritten() = if (lastWritten == -1) get() else buffers[lastWritten % buffers.size]

    /**
     * Records an image index as the one most recently written, without writing anything. The
     * write paths call it themselves; call it by hand after filling a buffer through some route
     * this class does not know about.
     */
    fun markAsWritten(imageIndex: Int = renderer.currentImage) {
        lastWritten = imageIndex % buffers.size
    }

    /**
     * Fills the group with [n] buffers, calling [builder] once per index. Any buffers already
     * there are dropped from the list — without being released, so build once rather than to
     * resize. A `DEVICE_LOCAL_ZERO` group also queues every new buffer for a zero fill, which the
     * next [clean] records.
     */
    fun build(builder: (Int) -> VulkanBuffer) {
        buffers.clear()
        n {
            buffers.add(builder(it))
        }
        if (accessPattern == BufferAccessPattern.DEVICE_LOCAL_ZERO) {
            synchronized(pendingLock) {
                pendingZeros.clear()
                buffers.indices.forEach { pendingZeros += it }
            }
        }
    }

    /**
     * Builds the matching host-visible staging buffers, [n] of them. A group with
     * [usesStagedUploads] needs these as well as the ones from [build]; nothing allocates them
     * for you, and the DSL in `magic.kt` is what normally does both.
     */
    fun buildStaging(builder: (Int) -> VulkanBuffer) {
        stagingBuffers.clear()
        n {
            stagingBuffers.add(builder(it))
        }
    }

    /**
     * Builds the matching host-cached readback buffers, [n] of them, which a group with
     * [usesReadbackStaging] copies into before a host read.
     */
    fun buildReadback(builder: (Int) -> VulkanBuffer) {
        readbackBuffers.clear()
        n {
            readbackBuffers.add(builder(it))
        }
    }

    /** The current image's `VkBuffer` handle, as it stands. No pending zero or upload is flushed first. */
    override fun getBuffer() = get().buffer

    /**
     * The device address of the buffer written most recently, after any pending zero fill or
     * staged upload has been recorded into [cmd]. This is the path a push constant takes, which is
     * why binding a buffer is also what flushes it.
     */
    override fun getGPUAddress(cmd: CommandBuffer): Long {
        clean(false, cmd)
        return getLastWritten().getGPUAddress(cmd)
    }

    /**
     * Writes the current image's buffer from the beginning, through a [DynamicBuffer] that pads as
     * std430 does. On a staged group the block fills the staging buffer and the copy is queued for
     * the next [clean] instead of happening here.
     */
    override fun write(builder: DynamicBuffer.() -> Unit): DynamicallyWriteable {
        val imageIndex = renderer.currentImage
        writable(imageIndex).write(builder)
        noteWrite(imageIndex)

        // todo: should we take a write barrier to shader here ?
        return this
    }

    /**
     * How far the current image's buffer has been written, in floats.
     *
     * It reads the device-side buffer, which on a staged group is not the one [write] and [append]
     * fill, so a staged group reports zero however much has been written to its staging buffer.
     */
    override val cursor : Int
        get() = get(write=false).writePosition


    /**
     * Continues writing the current image's buffer from where the last [write] or [append] to it
     * stopped. The per-image cursor is the buffer's own, so appending across frames appends to
     * whichever buffer this frame's image owns, not to a single running one.
     */
    override fun append(builder: DynamicBuffer.() -> Unit): DynamicallyWriteable {
        val imageIndex = renderer.currentImage
        writable(imageIndex).append(builder)
        noteWrite(imageIndex)

        // todo: should we take a write barrier to shader here ?
        return this
    }

    /**
     * Maps the current image's writable buffer and hands [builder] a float view of the whole
     * allocation, no padding applied. On a staged group the upload is queued for the next [clean].
     */
    override fun mapAndWrite(builder: FloatBuffer.() -> Unit): FrameBufferGroup {
        val imageIndex = renderer.currentImage
        writable(imageIndex).mapAndWrite(builder)
        noteWrite(imageIndex)
        return this
    }

    /** [mapAndWrite] with a byte view of the same memory, for packed or mixed layouts. */
    override fun mapAndWrite_bytes(builder: ByteBuffer.() -> Unit): FrameBufferGroup {
        val imageIndex = renderer.currentImage
        writable(imageIndex).mapAndWrite_bytes(builder)
        noteWrite(imageIndex)
        return this
    }

    /** [mapAndWrite] with an integer view of the same memory, for indices, counters and flags. */
    override fun mapAndWrite_ints(builder: IntBuffer.() -> Unit): FrameBufferGroup {
        val imageIndex = renderer.currentImage
        writable(imageIndex).mapAndWrite_ints(builder)
        noteWrite(imageIndex)
        return this
    }

    /**
     * Carries out the zero fills and staged uploads that writes and [build] left pending.
     *
     * Each pending image index is claimed under a lock and removed before anything is recorded,
     * so the graphics and compute recorders that run in parallel cannot both issue the same copy.
     * With a [cmd] outside a render pass the work is recorded there; with no [cmd], or inside a
     * render pass instance, it goes out on its own command buffer instead. [hostReading] returns
     * at once, leaving everything pending: the readback path in `mapAndRead` does its own copy.
     */
    override fun clean(hostReading: Boolean, cmd: CommandBuffer?): FrameBufferGroup {
        if (hostReading) return this
        // Compute and graphics command buffers are recorded in parallel. Exactly one recorder must
        // claim each staged copy; iterating and removing from the shared sets raced, duplicated copies,
        // and occasionally ended the renderer with ConcurrentModificationException.
        val (zeros, uploads) = synchronized(pendingLock) {
            if (pendingZeros.isEmpty() && (!usesStagedUploads || pendingUploads.isEmpty())) return this
            val claimedZeros = pendingZeros.toList()
            val claimedUploads = if (usesStagedUploads) pendingUploads.toList() else emptyList()
            pendingZeros.clear()
            pendingUploads.clear()
            claimedZeros to claimedUploads
        }
        if (cmd == null) {
            zeros.forEach { submitZeroNow(it) }
            uploads.forEach { submitUploadNow(it) }
            return this
        }
        zeros.forEach { imageIndex ->
            if (cmd.inRenderPassInstance) {
                submitZeroNow(imageIndex)
            } else {
                recordZero(cmd, imageIndex)
            }
        }
        uploads.forEach { imageIndex ->
            if (cmd.inRenderPassInstance) {
                submitUploadNow(imageIndex)
            } else {
                recordUpload(cmd, imageIndex)
            }
        }
        return this
    }

    /**
     * Reads the current image's buffer on the host as floats.
     *
     * On a readback group this first copies the device buffer into its host-cached twin on a
     * command buffer of its own and waits for the GPU to finish, so the call blocks and the data
     * is whatever the device held at that moment. Otherwise the buffer is mapped directly and
     * nothing is synchronised for you.
     */
    override fun mapAndRead(builder: FloatBuffer.() -> Unit): FrameBufferGroup {
        if (usesReadbackStaging) {
            copyToReadbackNow(renderer.currentImage)
            readbackAt(renderer.currentImage).mapAndRead(builder)
        } else {
            get(write = false).mapAndRead(builder)
        }
        return this
    }

    /** [mapAndRead] with a byte view of the same memory. */
    override fun mapAndRead_bytes(builder: ByteBuffer.() -> Unit): FrameBufferGroup {
        if (usesReadbackStaging) {
            copyToReadbackNow(renderer.currentImage)
            readbackAt(renderer.currentImage).mapAndRead_bytes(builder)
        } else {
            get(write = false).mapAndRead_bytes(builder)
        }
        return this
    }

    /** [mapAndRead] with an integer view of the same memory, which is how a compute counter comes back. */
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
        if (usesStagedUploads) synchronized(pendingLock) {
            pendingUploads += imageIndex % buffers.size
        }
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
                    VK_ACCESS_2_TRANSFER_WRITE_BIT or
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
