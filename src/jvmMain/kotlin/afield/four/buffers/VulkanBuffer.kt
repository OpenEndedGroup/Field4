@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package afield.four.buffers

import afield.four.BufferParameters
import afield.four.Renderer
import afield.four.device.CommandBuffer
import afield.four.modules.fromContext
import afield.four.util.vkCheck
import afield.stdlib.i
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.vma.Vma
import org.lwjgl.util.vma.Vma.vmaDestroyBuffer
import org.lwjgl.util.vma.VmaAllocationCreateInfo
import org.lwjgl.util.vma.VmaAllocationInfo
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
import org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO
import org.lwjgl.vulkan.VK12.vkGetBufferDeviceAddress
import org.lwjgl.vulkan.VkBufferCreateInfo
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo
import org.lwjgl.vulkan.VkBufferMemoryBarrier
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.math.max

/** Small interfaces you already had */
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

interface BufferWriteable {
    fun mapAndWrite(builder: FloatBuffer.() -> Unit): BufferWriteable
    fun mapAndWrite_bytes(builder: ByteBuffer.() -> Unit): BufferWriteable
    fun mapAndWrite_ints(builder: IntBuffer.() -> Unit): BufferWriteable
    fun clean(hostReading: Boolean, cmd: CommandBuffer? = null): BufferWriteable
}

interface DynamicallyWriteable {
    fun write(builder: DynamicBuffer.() -> Unit): DynamicallyWriteable
    fun append(builder: DynamicBuffer.() -> Unit): DynamicallyWriteable
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
    val needsDeviceAddress: Boolean = false
) : HasBuffer, BufferWriteable, BufferReadable, DynamicallyWriteable {

    var gpuAddress: Long = -1

    /** VMA-managed */
    private var allocationSize: Long = 0
    var buffer: Long = 0L
        @JvmName("_getBuffer")
        get() = field

    /** VmaAllocation handle (NOT a VkDeviceMemory) */
    private var allocation: Long = 0L

    /** CPU pointer; stays valid while mapped (we keep it persistently mapped) */
    private var mappedMemory: Long = 0L

    var hint_read: Boolean = false
    var hint_write: Boolean = false

    var watermark: Long = 0
    var readWatermark: Long = -1
    var writeWatermark: Long = -1

    var zeroBeforeComputation = false
    var source: BufferParameters? = null

    init {
        allocate()
    }

    /** Allocate buffer + suballocation via VMA; keep persistently mapped. */
    private fun allocate() {
        mappedMemory = MemoryUtil.NULL
        gpuAddress = 0L

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
                // Hint: small, CPU-updated buffers
                flags(
                    Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT or
                            Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT
                )
                // If you must force HOST_VISIBLE|COHERENT, uncomment:
                // requiredFlags(reqMask)
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
        val oldBuffer = buffer
        val oldAlloc  = allocation
        val oldMapped = mappedMemory

        // Clear live handles
        buffer = 0L
        allocation = 0L
        mappedMemory = 0L

        requestedSize = bytes
        allocate()

        renderer.cleanup.add {
            // No need to unmap explicitly if we created it MAPPED; destroy takes care of it.
            if (oldBuffer != VK_NULL_HANDLE && oldAlloc != 0L) {
                vmaDestroyBuffer(allocatorForRenderer(renderer), oldBuffer, oldAlloc)
            }
        }
    }

    /** Optional helper you already used for dynamic growth. */
    fun dynamicResizer() = { was: FloatBuffer, hint: Int ->
        val nextSize = 2 + max(hint, (was.capacity() * 1.5).i)
        println("Realloc ${was.capacity()}")
        val addrBefore = mappedMemory
        val allocBefore = allocation

        realloc(nextSize * 4L)

        val mapped = map()
        val data = MemoryUtil.memFloatBuffer(mapped, requestedSize.toInt() / 4)
        was.clear()
        data.put(was)
        data
    }

    /** Fill with zeros then barrier to COMPUTE. */
    fun zero(cmd: CommandBuffer) {
        MemoryStack.stackPush().use { stack ->
            vkCmdFillBuffer(cmd.vkCommandBuffer, buffer, 0L, allocationSize, 0)
            val barrier = VkBufferMemoryBarrier.calloc(1, stack).apply {
                it.sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                it.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                it.dstAccessMask(VK_ACCESS_SHADER_READ_BIT or VK_ACCESS_SHADER_WRITE_BIT)
                it.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                it.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                it.buffer(buffer)
                it.offset(0L)
                it.size(allocationSize)
            }
            vkCmdPipelineBarrier(
                cmd.vkCommandBuffer,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0, null, barrier, null
            )
        }
    }

    override fun clean(hostReading: Boolean, cmd: CommandBuffer?): VulkanBuffer {
        if (cmd == null) return this
        // You had an early return true; keeping behavior as-is.
        if (true) return this

        // (If you re-enable this path later, it still works unchanged.)
        return this
    }

    /** Map via VMA; keep persistently mapped (recommended). */
    fun map(): Long {
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
        // Always safe; becomes a no-op on coherent memory
        Vma.vmaFlushAllocation(allocatorForRenderer(renderer), allocation, offset, size)
        // If you truly want to unmap:
        // Vma.vmaUnmapMemory(allocatorForRenderer(renderer), allocation)
        // mappedMemory = MemoryUtil.NULL
    }

    override fun mapAndWrite(builder: FloatBuffer.() -> Unit): VulkanBuffer {
        hint_write = true
        writeWatermark++
        val m = map()
        val data = MemoryUtil.memFloatBuffer(m, requestedSize.toInt() / 4)
        builder.invoke(data)
        unmap(0, requestedSize)
        return this
    }

    // dynamic write helpers
    var writePosition = 0

    override fun write(builder: DynamicBuffer.() -> Unit): VulkanBuffer {
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

    override fun append(builder: DynamicBuffer.() -> Unit): VulkanBuffer {
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

    override fun mapAndWrite_bytes(builder: ByteBuffer.() -> Unit): VulkanBuffer {
        hint_write = true
        writeWatermark++
        val m = map()
        val data = MemoryUtil.memByteBuffer(m, requestedSize.toInt())
        builder.invoke(data)
        unmap(0, requestedSize)
        return this
    }

    override fun mapAndWrite_ints(builder: IntBuffer.() -> Unit): VulkanBuffer {
        hint_write = true
        writeWatermark++
        val m = map()
        val data = MemoryUtil.memIntBuffer(m, requestedSize.toInt() / 4)
        builder.invoke(data)
        unmap(0, requestedSize)
        return this
    }

    override fun mapAndRead(builder: FloatBuffer.() -> Unit): VulkanBuffer {
        hint_read = true
        readWatermark++
        val m = map()
        val data = MemoryUtil.memFloatBuffer(m, requestedSize.toInt() / 4)
        builder.invoke(data)
        // If host reads on non-coherent, you’d call vmaInvalidateAllocation before reading.
        // Not done here since your path doesn’t stage GPU->CPU writes yet.
        return this
    }

    override fun mapAndRead_bytes(builder: ByteBuffer.() -> Unit): VulkanBuffer {
        hint_read = true
        readWatermark++
        val m = map()
        val data = MemoryUtil.memByteBuffer(m, requestedSize.toInt())
        builder.invoke(data)
        return this
    }

    override fun mapAndRead_ints(builder: IntBuffer.() -> Unit): VulkanBuffer {
        hint_read = true
        readWatermark++
        val m = map()
        val data = MemoryUtil.memIntBuffer(m, requestedSize.toInt() / 4)
        builder.invoke(data)
        return this
    }

    /** Defer destruction with your renderer.cleanup, or call directly when safe. */
    fun pushCleanup() {
        val oldBuffer = buffer
        val oldAlloc  = allocation
        val oldMapped = mappedMemory

        buffer = 0L
        allocation = 0L
        mappedMemory = 0L

        renderer.cleanup.add(Cleaner(renderer, oldBuffer, oldAlloc, oldMapped))
    }

    fun cleanup() {
        if (buffer != VK_NULL_HANDLE && allocation != 0L) {
            vmaDestroyBuffer(allocatorForRenderer(renderer), buffer, allocation)
        }
        buffer = VK_NULL_HANDLE
        allocation = 0L
        mappedMemory = 0L
        allocationSize = 0L
        gpuAddress = 0L
    }

    class Cleaner(
        val renderer: Renderer,
        val cbuffer: Long,
        val callocation: Long,
        val cmappedMemory: Long
    ) : Runnable {
        override fun run() {
            // No explicit unmap required for persistently mapped allocations before destroy.
            if (cbuffer != VK_NULL_HANDLE && callocation != 0L) {
                vmaDestroyBuffer(allocatorForRenderer(renderer), cbuffer, callocation)
            }
        }
    }

    override fun toString(): String {
        return "VulkanBuffer(requestedSize=$requestedSize${if (mappedMemory != MemoryUtil.NULL) " MAPPED" else ""})"
    }

    override fun getBuffer(): Long = buffer

    override fun getGPUAddress(cmd: CommandBuffer): Long {
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
