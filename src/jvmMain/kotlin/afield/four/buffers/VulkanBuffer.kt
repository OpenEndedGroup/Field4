@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package afield.four.buffers

import afield.four.Renderer
import afield.four.device.CommandBuffer
import afield.four.modules.computeMemoryBarrier
import afield.four.modules.graphicsMemoryBarrier
import afield.four.modules.fromContext
import afield.four.util.AutoDisposer
import afield.four.util.vkCheck
import afield.stdlib.i
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.vma.Vma
import org.lwjgl.util.vma.Vma.vmaDestroyBuffer
import org.lwjgl.util.vma.VmaAllocationCreateInfo
import org.lwjgl.util.vma.VmaAllocationInfo
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK12.*
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.VkBufferCreateInfo
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo
import org.lwjgl.vulkan.VkBufferMemoryBarrier2
import org.lwjgl.vulkan.VkDependencyInfo
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.math.max

interface HasBuffer {
    fun getBuffer(): Long
    fun getGPUAddress(cmd: CommandBuffer): Long
}

operator fun HasBuffer.plus(offset: Long): HasBuffer {
    val inside = this
    return object : HasBuffer {
        override fun getBuffer(): Long = inside.getBuffer()
        override fun getGPUAddress(cmd: CommandBuffer): Long = inside.getGPUAddress(cmd) + offset
    }
}

val VulkanBuffer.zeroBefore: HasBuffer
    get() {
        val inside = this
        return object : HasBuffer {
            override fun getBuffer(): Long = inside.getBuffer()
            override fun getGPUAddress(cmd: CommandBuffer): Long {
                inside.zero(cmd)
                return inside.getGPUAddress(cmd)
            }
        }
    }

val HasBuffer.barrier: HasBuffer
    get() {
        val inside = this
        return object : HasBuffer {
            override fun getBuffer(): Long = inside.getBuffer()
            override fun getGPUAddress(cmd: CommandBuffer): Long {
                computeMemoryBarrier(cmd, inside.getBuffer())
                return inside.getGPUAddress(cmd)
            }
        }
    }

val HasBuffer.graphicsBarrier: HasBuffer
    get() {
        val inside = this
        return object : HasBuffer {
            override fun getBuffer(): Long = inside.getBuffer()
            override fun getGPUAddress(cmd: CommandBuffer): Long {
                if (!cmd.inRenderPassInstance) {
                    graphicsMemoryBarrier(cmd, inside.getBuffer())
                }
                return inside.getGPUAddress(cmd)
            }
        }
    }

interface BufferWriteable {
    fun mapAndWrite(builder: FloatBuffer.() -> Unit): BufferWriteable
    fun mapAndWrite_bytes(builder: ByteBuffer.() -> Unit): BufferWriteable
    fun mapAndWrite_ints(builder: IntBuffer.() -> Unit): BufferWriteable
    fun clean(hostReading: Boolean, cmd: CommandBuffer? = null): BufferWriteable
}

interface DynamicallyWriteable {
    fun write(builder: DynamicBuffer.() -> Unit): DynamicallyWriteable
    fun append(builder: DynamicBuffer.() -> Unit): DynamicallyWriteable
    val cursor : Int
}

interface BufferReadable {
    fun mapAndRead(builder: FloatBuffer.() -> Unit): BufferReadable
    fun mapAndRead_bytes(builder: ByteBuffer.() -> Unit): BufferReadable
    fun mapAndRead_ints(builder: IntBuffer.() -> Unit): BufferReadable
    fun clean(hostReading: Boolean, cmd: CommandBuffer?): BufferReadable
}

class VulkanBuffer(
    val renderer: Renderer = fromContext(),
    var requestedSize: Long,
    val usage: Int,
    val reqMask: Int,
    val needsDeviceAddress: Boolean = false,
    val cpuOnly: Boolean = false,
) : HasBuffer, BufferWriteable, BufferReadable, DynamicallyWriteable {

    var gpuAddress: Long = -1
    private val writeLock = Any()

    /** VMA-managed */
    private var allocationSize: Long = 0
    var buffer: Long = 0L
        @JvmName("_getBuffer")
        get() = field

    /** VmaAllocation handle (NOT a VkDeviceMemory) */
    private var allocation: Long = 0L

    /** CPU pointer; stays valid while mapped (we keep it persistently mapped) */
    private var mappedMemory: Long = 0L
    private var hostBuffer: ByteBuffer? = null

    var hint_read: Boolean = false
    var hint_write: Boolean = false

    var watermark: Long = 0
    var readWatermark: Long = -1
    var writeWatermark: Long = -1

    var zeroBeforeComputation = false
    var source: BufferParameters? = null

    private val autoDisposeToken = AutoDisposer.register(this, "VulkanBuffer(size=$requestedSize)") { /* set after allocate */ }

    init {
        allocate()
        autoDisposeToken.updateCleanup(snapshotCleanup())
    }

    /** Allocate buffer + suballocation via VMA; keep persistently mapped. */
    private fun allocate() {
        mappedMemory = MemoryUtil.NULL
        gpuAddress = 0L
        hostBuffer = null

        if (cpuOnly) {
            val size = requestedSize.coerceAtLeast(1L).toInt()
            hostBuffer = MemoryUtil.memAlloc(size)
            allocationSize = size.toLong()
            mappedMemory = MemoryUtil.memAddress(hostBuffer!!)
            buffer = 0L
            allocation = 0L
            return
        }

        MemoryStack.stackPush().use { stack ->
            val usageBits = if (needsDeviceAddress) usage or VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT else usage
            val size = requestedSize.coerceAtLeast(1L)

            val bufCI = VkBufferCreateInfo.calloc(stack).apply {
                sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                size(size)
                usage(usageBits)
                sharingMode(VK_SHARING_MODE_EXCLUSIVE)
            }

            val allocCI = VmaAllocationCreateInfo.calloc(stack).apply {
                usage(Vma.VMA_MEMORY_USAGE_AUTO)
                val hostAccessFlag =
                    if (reqMask and VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT != 0 &&
                        usageBits and VK_BUFFER_USAGE_TRANSFER_DST_BIT != 0 &&
                        usageBits and VK_BUFFER_USAGE_TRANSFER_SRC_BIT == 0
                    ) {
                        Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT
                    } else {
                        Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT
                    }
                flags(
                    hostAccessFlag or Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT
                )
                if (reqMask != 0) {
                    requiredFlags(reqMask and VK_MEMORY_PROPERTY_HOST_CACHED_BIT.inv())
                    if (reqMask and VK_MEMORY_PROPERTY_HOST_CACHED_BIT != 0) {
                        preferredFlags(VK_MEMORY_PROPERTY_HOST_CACHED_BIT)
                    }
                }
            }

            val pBuffer = stack.mallocLong(1)
            val pAlloc  = stack.mallocPointer(1)
            val allocator = allocatorForRenderer(renderer)

            val err = Vma.vmaCreateBuffer(allocator, bufCI, allocCI, pBuffer, pAlloc, null)
            require(err == VK_SUCCESS) { "vmaCreateBuffer failed: $err" }

            buffer     = pBuffer[0]
            allocation = pAlloc[0]

            // Query size + mapped pointer
            val info = VmaAllocationInfo.calloc(stack)
            Vma.vmaGetAllocationInfo(allocator, allocation, info)
            allocationSize = info.size()
            mappedMemory   = info.pMappedData()

            // Optional: device address
            if (needsDeviceAddress) {
                val addrInfo = VkBufferDeviceAddressInfo.calloc(stack).apply {
                    sType(VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO)
                    buffer(buffer)
                }
                gpuAddress = vkGetBufferDeviceAddress(renderer.device.vkDevice, addrInfo)
                require(gpuAddress != 0L) { "vkGetBufferDeviceAddress returned 0; check features/flags/usage." }
            }
        }
    }

    /** Grow-resize with deferred destruction of the old buffer/allocation. */
    fun realloc(bytes: Long) {
        synchronized(writeLock) {
            val oldBuffer = buffer
            val oldAlloc = allocation
            val oldHost = hostBuffer

            // Clear live handles
            buffer = 0L
            allocation = 0L
            mappedMemory = 0L
            hostBuffer = null

            requestedSize = bytes
            allocate()
            autoDisposeToken.updateCleanup(snapshotCleanup())

            renderer.cleanup.add {
                if (oldHost != null) MemoryUtil.memFree(oldHost)
                if (oldBuffer != VK_NULL_HANDLE && oldAlloc != 0L) {
                    vmaDestroyBuffer(allocatorForRenderer(renderer), oldBuffer, oldAlloc)
                }
            }
        }
    }

    /** Optional helper you already used for dynamic growth. */
    fun dynamicResizer() = { was: FloatBuffer, hint: Int ->
        val nextSize = 2 + max(hint, (was.capacity() * 1.5).i)
        println("Realloc ${was.capacity()}")
        val allocBefore = allocation

        realloc(nextSize * 4L)

        val mapped = map()
        val data = MemoryUtil.memFloatBuffer(mapped, requestedSize.toInt() / 4)
        was.clear()
        data.put(was)
        data
    }

    /**
     * Fill with zeros then make the writes visible to subsequent **compute shader** reads/writes
     * using **synchronization2**. If you need this to precede graphics-stage use instead, change
     * dstStageMask to the appropriate stage(s).
     */
    fun zero(cmd: CommandBuffer) {
        if (cpuOnly) {
            hostBuffer?.let { MemoryUtil.memSet(it, 0) }
            return
        }
        MemoryStack.stackPush().use { stack ->
            vkCmdFillBuffer(cmd.vkCommandBuffer, buffer, 0L, allocationSize, 0)

            val bufBarrier = VkBufferMemoryBarrier2.calloc(1, stack)
            bufBarrier[0]
                .sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER_2)
                .srcStageMask(VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT)
                .srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                .dstAccessMask(VK_ACCESS_2_SHADER_READ_BIT or VK_ACCESS_2_SHADER_WRITE_BIT)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .buffer(buffer)
                .offset(0L)
                .size(allocationSize)

            val dep = VkDependencyInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO)
                .pBufferMemoryBarriers(bufBarrier)

            vkCmdPipelineBarrier2(cmd.vkCommandBuffer, dep)
        }
    }

    override fun clean(hostReading: Boolean, cmd: CommandBuffer?): VulkanBuffer {
        if (cmd == null) return this
        if (true) return this
        return this
    }

    /** Map via VMA (or host-only); keep persistently mapped (recommended). */
    fun map(): Long {
        if (cpuOnly) return mappedMemory
        if (mappedMemory == MemoryUtil.NULL) {
            MemoryStack.stackPush().use { stack ->
                val pp = stack.mallocPointer(1)
                val err = Vma.vmaMapMemory(allocatorForRenderer(renderer), allocation, pp)
                vkCheck(err, "vmaMapMemory failed")
                mappedMemory = pp[0]
            }
        }
        return mappedMemory
    }

    /** Flush writes for non-coherent memory. We keep it mapped. */
    fun unmap(offset: Long = 0, size: Long = requestedSize) {
        if (!cpuOnly) {
            Vma.vmaFlushAllocation(allocatorForRenderer(renderer), allocation, offset, size)
        }
    }

    private fun invalidate(offset: Long = 0, size: Long = requestedSize) {
        if (!cpuOnly) {
            Vma.vmaInvalidateAllocation(allocatorForRenderer(renderer), allocation, offset, size)
        }
    }

    override fun mapAndWrite(builder: FloatBuffer.() -> Unit): VulkanBuffer {
        synchronized(writeLock) {
            hint_write = true
            writeWatermark++
            val m = map()
            val data = MemoryUtil.memFloatBuffer(m, requestedSize.toInt() / 4)
            builder.invoke(data)
            unmap(0, requestedSize)
            return this
        }
    }

    // dynamic write helpers
    var writePosition = 0

    override val cursor: Int get() = writePosition

    override fun write(builder: DynamicBuffer.() -> Unit): VulkanBuffer {
        synchronized(writeLock) {
            hint_write = true
            writeWatermark++
            val m = map()
            val data = MemoryUtil.memFloatBuffer(m, requestedSize.toInt() / 4)
            val db = DynamicBuffer(data, dynamicResizer())
            builder.invoke(db)
            writePosition = db.target.position()
            unmap(0, (writePosition * 4L).coerceAtMost(requestedSize))
            return this
        }
    }

    override fun append(builder: DynamicBuffer.() -> Unit): VulkanBuffer {
        synchronized(writeLock) {
            hint_write = true
            writeWatermark++
            val m = map()
            val data = MemoryUtil.memFloatBuffer(m, requestedSize.toInt() / 4)
            val db = DynamicBuffer(data, dynamicResizer())
            db.target.position(writePosition)
            builder.invoke(db)
            writePosition = db.target.position()
            unmap(0, (writePosition * 4L).coerceAtMost(requestedSize))
            return this
        }
    }

    override fun mapAndWrite_bytes(builder: ByteBuffer.() -> Unit): VulkanBuffer {
        synchronized(writeLock) {
            hint_write = true
            writeWatermark++
            val m = map()
            val data = MemoryUtil.memByteBuffer(m, requestedSize.toInt())
            builder.invoke(data)
            unmap(0, requestedSize)
            return this
        }
    }

    override fun mapAndWrite_ints(builder: IntBuffer.() -> Unit): VulkanBuffer {
        synchronized(writeLock) {
            hint_write = true
            writeWatermark++
            val m = map()
            val data = MemoryUtil.memIntBuffer(m, requestedSize.toInt() / 4)
            builder.invoke(data)
            unmap(0, requestedSize)
            return this
        }
    }

    override fun mapAndRead(builder: FloatBuffer.() -> Unit): VulkanBuffer {
        hint_read = true
        readWatermark++
        invalidate(0, requestedSize)
        val m = map()
        val data = MemoryUtil.memFloatBuffer(m, requestedSize.toInt() / 4)
        builder.invoke(data)
        return this
    }

    override fun mapAndRead_bytes(builder: ByteBuffer.() -> Unit): VulkanBuffer {
        return mapAndReadBytes(requestedSize, builder)
    }

    fun mapAndReadBytes(size: Long, builder: ByteBuffer.() -> Unit): VulkanBuffer {
        hint_read = true
        readWatermark++
        val readSize = size.coerceIn(0L, requestedSize)
        invalidate(0, readSize)
        val m = map()
        val data = MemoryUtil.memByteBuffer(m, readSize.toInt())
        builder.invoke(data)
        return this
    }

    override fun mapAndRead_ints(builder: IntBuffer.() -> Unit): VulkanBuffer {
        hint_read = true
        readWatermark++
        invalidate(0, requestedSize)
        val m = map()
        val data = MemoryUtil.memIntBuffer(m, requestedSize.toInt() / 4)
        builder.invoke(data)
        return this
    }

    /** Defer destruction with your renderer.cleanup, or call directly when safe. */
    fun pushCleanup() {
        val oldBuffer = buffer
        val oldAlloc  = allocation
        val oldHost   = hostBuffer

        buffer = 0L
        allocation = 0L
        mappedMemory = 0L
        hostBuffer = null

        renderer.cleanup.add(Cleaner(renderer, oldBuffer, oldAlloc, oldHost))
    }

    fun cleanup() {
        autoDisposeToken.disable()
        if (hostBuffer != null) MemoryUtil.memFree(hostBuffer)
        if (buffer != VK_NULL_HANDLE && allocation != 0L) {
            vmaDestroyBuffer(allocatorForRenderer(renderer), buffer, allocation)
        }
        buffer = VK_NULL_HANDLE
        allocation = 0L
        mappedMemory = 0L
        allocationSize = 0L
        gpuAddress = 0L
        hostBuffer = null
    }

    class Cleaner(
        val renderer: Renderer,
        val cbuffer: Long,
        val callocation: Long,
        val chostBuffer: ByteBuffer?
    ) : Runnable {
        override fun run() {
            if (chostBuffer != null) MemoryUtil.memFree(chostBuffer)
            if (cbuffer != VK_NULL_HANDLE && callocation != 0L) {
                vmaDestroyBuffer(allocatorForRenderer(renderer), cbuffer, callocation)
            }
        }
    }

    private fun snapshotCleanup(): () -> Unit {
        val renderer = renderer
        val buf = buffer
        val alloc = allocation
        val host = hostBuffer
        return {
            if (host != null) MemoryUtil.memFree(host)
            if (buf != VK_NULL_HANDLE && alloc != 0L) {
                vmaDestroyBuffer(allocatorForRenderer(renderer), buf, alloc)
            }
        }
    }

    override fun toString(): String {
        return "VulkanBuffer(requestedSize=$requestedSize${if (mappedMemory != MemoryUtil.NULL) " MAPPED" else ""})"
    }

    override fun getBuffer(): Long = buffer

    override fun getGPUAddress(cmd: CommandBuffer): Long {
        if (cpuOnly) return 0L
        if (gpuAddress > 0) return gpuAddress
        MemoryStack.stackPush().use { stack ->
            val info = VkBufferDeviceAddressInfo.calloc(stack).apply {
                sType(VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO)
                buffer(buffer)
            }
            gpuAddress = vkGetBufferDeviceAddress(renderer.device.vkDevice, info)
            return gpuAddress
        }
    }
}
