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

    // Volatile, like the handle fields below: the render workers read these while recording, on
    // other threads than the one that reallocates. See [realloc].
    @Volatile
    var gpuAddress: Long = -1
    private val writeLock = Any()

    /** VMA-managed */
    @Volatile
    private var allocationSize: Long = 0
    var allocationMemoryType: Int = -1
        private set
    var allocationMemoryFlags: Int = 0
        private set
    @Volatile
    var buffer: Long = 0L
        @JvmName("_getBuffer")
        get() = field

    /** VmaAllocation handle (NOT a VkDeviceMemory) */
    @Volatile
    private var allocation: Long = 0L

    /** CPU pointer; stays valid while mapped (we keep it persistently mapped) */
    @Volatile
    private var mappedMemory: Long = 0L
    private var hostBuffer: ByteBuffer? = null

    /**
     * The handle and range a descriptor write needs, published as one object so a render worker
     * never pairs one allocation's handle with another's size. Replaced (never mutated) by
     * [allocate], [realloc], [cleanup] and [pushCleanup].
     */
    class DescriptorBinding internal constructor(val buffer: Long, val range: Long)

    @Volatile
    private var binding = DescriptorBinding(VK_NULL_HANDLE, VK_WHOLE_SIZE)

    var hint_read: Boolean = false
    var hint_write: Boolean = false

    var watermark: Long = 0
    var readWatermark: Long = -1
    var writeWatermark: Long = -1

    var zeroBeforeComputation = false
    var source: BufferParameters? = null

    private val autoDisposeToken = AutoDisposer.register(this, "VulkanBuffer(size=$requestedSize)", owner = renderer.device) { /* set after allocate */ }

    init {
        allocate(requestedSize)
        autoDisposeToken.updateCleanup(snapshotCleanup())
    }

    /**
     * Allocate a buffer + suballocation of [requested] bytes via VMA (kept persistently mapped), then
     * publish it.
     *
     * Everything is built into locals first and the fields are only written at the end, handle
     * last and [binding] after it. A render worker recording a frame on another thread therefore
     * reads either the previous allocation (still alive: [realloc] retires it through the
     * renderer's frame-retirement queue) or this one, and never `VK_NULL_HANDLE`. Clearing the
     * handle before allocating, as this used to, let `Module.Uniform.pushDescriptorSet` push a
     * null buffer mid-[realloc], which MoltenVK dereferences (`MVKBuffer::getMTLBuffer`, address
     * 0x68). See `dev_docs/renderer_teardown_order.md`, "Live-frame crash".
     */
    private fun allocate(requested: Long) {
        if (cpuOnly) {
            val size = requested.coerceAtLeast(1L).toInt()
            val host = MemoryUtil.memAlloc(size)
            allocationSize = size.toLong()
            allocationMemoryType = -1
            allocationMemoryFlags = 0
            hostBuffer = host
            mappedMemory = MemoryUtil.memAddress(host)
            gpuAddress = 0L
            allocation = 0L
            buffer = 0L
            requestedSize = requested
            binding = DescriptorBinding(VK_NULL_HANDLE, rangeFor(requested))
            return
        }

        MemoryStack.stackPush().use { stack ->
            val usageBits = if (needsDeviceAddress) usage or VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT else usage
            val size = requested.coerceAtLeast(1L)

            val bufCI = VkBufferCreateInfo.calloc(stack).apply {
                sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                size(size)
                usage(usageBits)
                sharingMode(VK_SHARING_MODE_EXCLUSIVE)
            }

            val allocCI = VmaAllocationCreateInfo.calloc(stack).apply {
                usage(Vma.VMA_MEMORY_USAGE_AUTO)
                // Choose RANDOM host access for anything the CPU will *read* back, so VMA
                // lands the allocation in HOST_CACHED memory. The two read signals are:
                //   - an explicit HOST_CACHED request (readback buffers, e.g. streamReads()), and
                //   - a transfer-destination-only staging buffer (uploaded, then read).
                // Everything else is a sequential CPU->GPU write, where write-combined memory is
                // ideal. Getting this wrong is expensive: SEQUENTIAL_WRITE steers VMA toward
                // uncached/write-combined memory, and CPU reads from that are pathologically slow
                // on discrete GPUs (no caching or prefetch) — the classic slow-readback trap.
                val wantsHostReads =
                    reqMask and VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT != 0 && (
                        reqMask and VK_MEMORY_PROPERTY_HOST_CACHED_BIT != 0 ||
                            (usageBits and VK_BUFFER_USAGE_TRANSFER_DST_BIT != 0 &&
                                usageBits and VK_BUFFER_USAGE_TRANSFER_SRC_BIT == 0)
                    )
                val hostAccessFlag =
                    if (wantsHostReads) {
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

            val newBuffer = pBuffer[0]
            val newAllocation = pAlloc[0]

            // Query size + mapped pointer
            val info = VmaAllocationInfo.calloc(stack)
            Vma.vmaGetAllocationInfo(allocator, newAllocation, info)
            val newMemoryType = info.memoryType()
            val newMemoryFlags = if (newMemoryType >= 0) {
                renderer.physicalDevice.vkMemoryProperties.memoryTypes()[newMemoryType].propertyFlags()
            } else {
                0
            }

            // Optional: device address. Eager, so a lazy getGPUAddress() can never cache the
            // previous allocation's address after a realloc.
            var newAddress = 0L
            if (needsDeviceAddress) {
                val addrInfo = VkBufferDeviceAddressInfo.calloc(stack).apply {
                    sType(VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO)
                    buffer(newBuffer)
                }
                newAddress = vkGetBufferDeviceAddress(renderer.device.vkDevice, addrInfo)
                require(newAddress != 0L) { "vkGetBufferDeviceAddress returned 0; check features/flags/usage." }
            }

            // Publish: everything else first, the handle and the descriptor binding last.
            allocation = newAllocation
            allocationSize = info.size()
            allocationMemoryType = newMemoryType
            allocationMemoryFlags = newMemoryFlags
            hostBuffer = null
            mappedMemory = info.pMappedData()
            gpuAddress = newAddress
            buffer = newBuffer
            requestedSize = requested
            binding = DescriptorBinding(newBuffer, rangeFor(requested))
        }
    }

    private fun rangeFor(requested: Long): Long = if (requested > 0L) requested else VK_WHOLE_SIZE

    /**
     * Grow-resize with deferred destruction of the old buffer/allocation.
     *
     * Safe against a frame being recorded on another thread: the old allocation stays valid until
     * the renderer's retirement queue releases it, `8 × numImages` submitted frames later, and the
     * handle a recorder reads is never zero in between (see [allocate]).
     */
    fun realloc(bytes: Long) {
        synchronized(writeLock) {
            // After teardown began the allocator is about to go, and a retirement added now could
            // miss the final drain.
            requireLiveRenderer()
            check(!released) { "$this was released with cleanup(); it cannot be reallocated" }
            val oldBuffer = buffer
            val oldAlloc = allocation
            val oldHost = hostBuffer

            allocate(bytes)
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
        if (hostReading) {
            // mapAndRead* already does this inline before reading, but clean() must keep
            // this promise when called on its own, ahead of some other read of the mapped memory.
            invalidate()
        }
        // hostReading == false: no-op. Unlike Structure/VertexBuffer/FrameBufferGroup, this class
        // has no deferred-write tracking -- mapAndWrite/write/append already flush eagerly on every
        // call, so there is nothing pending here to commit.
        return this
    }

    /**
     * After [Renderer.cleanup], AutoDisposer has destroyed this buffer's allocation (or freed its
     * host copy) through a snapshot that cannot clear `mappedMemory`, so that pointer dangles and
     * the allocator behind flush/invalidate is gone. Writing through it was a SIGSEGV, not an
     * error; this makes it an exception that names the cause. See
     * `dev_docs/renderer_teardown_order.md`.
     */
    private fun requireLiveRenderer() {
        check(!renderer.isClosed) {
            "$this used after its Renderer was disposed; stop the box writing it before the renderer " +
                "(or its window, or its document) goes away"
        }
        check(!destroyed) {
            "$this used after cleanup() destroyed it; drop every use of a buffer before releasing it"
        }
    }

    /**
     * The handle and range to write into a descriptor for this buffer, read once, as a pair.
     *
     * Refuses, with an [IllegalStateException] that names the cause, rather than hand the driver a
     * handle it will fault on: after [Renderer.cleanup] has begun, and once the retirement queue
     * has destroyed a buffer that [cleanup] released while a module still binds it (between the
     * two the handle is still valid, and binding it is allowed). MoltenVK does not
     * validate a pushed buffer handle; it dereferences it while recording, so either case used to
     * be a SIGSEGV in `MVKBuffer::getMTLBuffer` on a render worker. A CPU-only buffer has no
     * VkBuffer and is refused too.
     */
    fun descriptorBinding(): DescriptorBinding {
        requireLiveRenderer()
        val bound = binding
        check(bound.buffer != VK_NULL_HANDLE) {
            if (cpuOnly) "$this is CPU-only and has no VkBuffer to bind to a descriptor"
            else "$this was released (cleanup() or pushCleanup()) while a module still binds it; " +
                "drop the binding before releasing it"
        }
        return bound
    }

    /** Map via VMA (or host-only); keep persistently mapped (recommended). */
    fun map(): Long {
        requireLiveRenderer()
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
        requireLiveRenderer()
        if (!cpuOnly) {
            Vma.vmaFlushAllocation(allocatorForRenderer(renderer), allocation, offset, size)
        }
    }

    private fun invalidate(offset: Long = 0, size: Long = requestedSize) {
        requireLiveRenderer()
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

    /**
     * Same as [cleanup], which now always retires through the renderer's queue while the renderer
     * is alive. Kept for the callers that used it to ask for exactly that.
     */
    fun pushCleanup() = cleanup()

    /**
     * Release this buffer.
     *
     * While its renderer is alive the VkBuffer and its memory are destroyed through the renderer's
     * retirement queue (`Renderer.cleanup`, released `8 × numImages` submitted frames later), so a
     * frame that recorded or is executing the buffer, or a render worker that read it a moment
     * ago, still finds it valid. Until then it behaves as before; afterwards [map], [unmap],
     * [descriptorBinding] and [realloc] refuse by name. After the renderer has closed (device
     * idle) it is destroyed at once.
     *
     * Destroying immediately, as this used to, was reproducible in a live Field: swapping a
     * module's per-frame uniform buffers for new ones and calling `cleanup()` on the old from a
     * thread crashed the JVM in `MVKBuffer::getMTLBuffer` (a destroyed handle pushed by a render
     * worker); doing it from a box's own `loop {}` logged `VUID-vkDestroyBuffer-buffer-00922`
     * (destroyed while a submitted frame used it) 40 times in 15 s. See
     * `dev_docs/renderer_teardown_order.md`, "Immediate cleanup mid-frame".
     */
    fun cleanup() {
        if (!autoDisposeToken.disable()) return
        released = true
        if (renderer.isClosed) destroyNow()
        else renderer.cleanup.add(Runnable { destroyNow() })
    }

    /** Set by [cleanup]: the handles are on their way to the retirement queue. */
    @Volatile
    private var released = false

    /** True once [cleanup] has released this buffer (its handles may not be destroyed yet). */
    val isReleased: Boolean get() = released

    /** Set once the handles are actually destroyed; everything that would touch them refuses. */
    @Volatile
    private var destroyed = false

    private fun destroyNow() {
        destroyed = true
        binding = DescriptorBinding(VK_NULL_HANDLE, binding.range)
        val host = hostBuffer
        val buf = buffer
        val alloc = allocation
        buffer = VK_NULL_HANDLE
        allocation = 0L
        mappedMemory = 0L
        allocationSize = 0L
        allocationMemoryType = -1
        allocationMemoryFlags = 0
        gpuAddress = 0L
        hostBuffer = null
        if (host != null) MemoryUtil.memFree(host)
        if (buf != VK_NULL_HANDLE && alloc != 0L) {
            vmaDestroyBuffer(allocatorForRenderer(renderer), buf, alloc)
        }
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
        return "VulkanBuffer(requestedSize=$requestedSize, memory=${memoryFlagsForString(allocationMemoryFlags)}${if (mappedMemory != MemoryUtil.NULL) " MAPPED" else ""})"
    }

    val descriptorRange: Long
        get() = if (requestedSize > 0L) requestedSize else VK_WHOLE_SIZE

    private fun memoryFlagsForString(flags: Int): String {
        if (cpuOnly) return "CPU_ONLY"
        if (flags == 0) return "auto"
        val names = mutableListOf<String>()
        if (flags and VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT != 0) names += "DEVICE_LOCAL"
        if (flags and VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT != 0) names += "HOST_VISIBLE"
        if (flags and VK_MEMORY_PROPERTY_HOST_COHERENT_BIT != 0) names += "HOST_COHERENT"
        if (flags and VK_MEMORY_PROPERTY_HOST_CACHED_BIT != 0) names += "HOST_CACHED"
        if (flags and VK_MEMORY_PROPERTY_LAZILY_ALLOCATED_BIT != 0) names += "LAZY"
        if (flags and VK_MEMORY_PROPERTY_PROTECTED_BIT != 0) names += "PROTECTED"
        return names.joinToString("|").ifEmpty { "0x${flags.toString(16)}" }
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
