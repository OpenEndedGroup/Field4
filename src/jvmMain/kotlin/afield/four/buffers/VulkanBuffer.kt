package afield.four.buffers

import afield.four.BufferParameters
import afield.four.Renderer
import afield.four.device.CommandBuffer
import afield.four.modules.fromContext
import afield.four.util.memoryTypeFromProperties
import afield.four.util.vkCheck
import afield.stdlib.i
import org.lwjgl.PointerBuffer
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO
import org.lwjgl.vulkan.VK12.*
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.math.max

/**
can we have the minimal path for a "shared buffer" that is inherited and, if possible, automatically relinked when it changes?
what happens if all buffers either have to be findable by name from the current context ('vars' or properties I don't care)
or if not, automatically sizable. We've gone down the wrong path with writing a custom linker -per box in Field4, we should have a single complex linked premade

the trouble with this vision is something where you just want to customize what the shader is going to do
that is, zero'd or notHosted,

before:

```
var module = computeModuleHere(parameters {
ssboResolver = { buffer ->
val b = buffer {
when(name) {
"InputBuffer" -> {
size = numVec4 * 4 * 4L
hosted()
storage()
}
"VertexData" -> {
size = numVec4 * 4 * 4L
storage()
notHosted()
}
"DrawingControl" -> {
zerod()
}
}
}.toBuffer(renderer);
b
}
})
```

ideal:

```

// here buffer is not _making a buffer_ it's just making a BufferParameters. Once it's run (in the _resolver_) there's a buffer attached to the customizer
// and the 'exported()' stuff has been run.
// if the resolver can relink on a reload or even a rerun (if the customizer isn't recreated) then it will
// if the

// buffer customizers and their created buffers need to be bi-connected

// find a buffer in var, find a buffer in prop, find a buffer parameter in var ->
val inputBuffer = buffer {
size = numVec4 * 4 * 4L
hosted()
storage()
exported()
}

val drawingControl = buffer {
zerod()
}

val vertexData = buffer {
size = numVec4 * 4 * 4L
notHosted()
exported(name="foo")
}

val vertexData = buffer {
imported(?)
}

we're going to put this in fourMagic.kt

var module = computeModuleHere { } // throws when it can't find 'inputBuffer' or when it's the wrong size

```

 */

interface HasBuffer {
    fun getBuffer(): Long
    fun getGPUAddress(cmd: CommandBuffer): Long
}

operator fun HasBuffer.plus(offset: Long): HasBuffer {
    val inside = this
    return object : HasBuffer {
        override fun getBuffer(): Long = inside.getBuffer()

        override fun getGPUAddress(cmd: CommandBuffer): Long {
            return inside.getGPUAddress(cmd) + offset
        }
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

//todo: write finishes before compute barrier

interface BufferWriteable {
    fun mapAndWrite(builder: FloatBuffer.() -> Unit): BufferWriteable;
    fun mapAndWrite_bytes(builder: ByteBuffer.() -> Unit): BufferWriteable;
    fun mapAndWrite_ints(builder: IntBuffer.() -> Unit): BufferWriteable;
    fun clean(hostReading: Boolean, cmd: CommandBuffer? = null): BufferWriteable
}

interface DynamicallyWriteable {
    fun write(builder: DynamicBuffer.() -> Unit): DynamicallyWriteable;
    fun append(builder: DynamicBuffer.() -> Unit): DynamicallyWriteable;
}

interface BufferReadable {
    fun mapAndRead(builder: FloatBuffer.() -> Unit): BufferReadable;
    fun mapAndRead_bytes(builder: ByteBuffer.() -> Unit): BufferReadable;
    fun mapAndRead_ints(builder: IntBuffer.() -> Unit): BufferReadable;
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

    private var allocationSize: Long = 0
    var buffer: Long = 0
        @JvmName("_getBuffer") get() = field
    private var memory: Long = 0
    private var pb: PointerBuffer? = null

    private var mappedMemory: Long = 0

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

    private fun allocate() {
        mappedMemory = MemoryUtil.NULL
        gpuAddress = 0
        MemoryStack.stackPush().use { stack ->
            val bufferCreateInfo = VkBufferCreateInfo.calloc(stack).sType(VK10.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE).size(requestedSize).usage(usage)
            val lp = stack.mallocLong(1)
            vkCheck(
                VK10.vkCreateBuffer(renderer.device.vkDevice, bufferCreateInfo, null, lp), "Failed to create buffer"
            )
            buffer = lp[0]

            val memReqs = VkMemoryRequirements.calloc(stack)
            VK10.vkGetBufferMemoryRequirements(renderer.device.vkDevice, buffer, memReqs)

            val memAlloc = VkMemoryAllocateInfo.calloc(stack).sType(VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(memReqs.size()).memoryTypeIndex(
                    memoryTypeFromProperties(
                        renderer.device.physicalDevice, memReqs.memoryTypeBits(), reqMask
                    )
                )

            if (needsDeviceAddress) {
                val extraInfo = VkMemoryAllocateFlagsInfo.calloc(stack)
                extraInfo.sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO)
                extraInfo.flags(VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT)
                memAlloc.pNext(extraInfo.address())
            }

            vkCheck(
                VK10.vkAllocateMemory(renderer.device.vkDevice, memAlloc, null, lp), "Failed to allocate memory"
            )
            allocationSize = memAlloc.allocationSize()
            memory = lp[0]
            pb = MemoryUtil.memAllocPointer(1)
            vkCheck(
                VK10.vkBindBufferMemory(renderer.device.vkDevice, buffer, memory, 0), "Failed to bind buffer memory"
            )
        }
    }

    fun dynamicResizer() = { was: FloatBuffer, hint: Int ->
        var nextSize = (2 + max(hint, (was.capacity() * 1.5).i))

        println("Realloc ${was.capacity()}")

        var mm = mappedMemory
        var mem = memory

        realloc(nextSize * 4L)

        val mapped = map()
        val data = MemoryUtil.memFloatBuffer(mapped, requestedSize.toInt() / 4)
        was.clear()
        data.put(was)

//        if (mm != MemoryUtil.NULL) {
//            VK10.vkUnmapMemory(renderer.device.vkDevice, mem)
//            mm = MemoryUtil.NULL
//        }

        data
    }

    fun realloc(bytes: Long) {
        val cpb = pb
        val cbuffer = buffer
        val cmemory = memory
        var cmappedMemory = mappedMemory
        pb = null
        buffer = 0
        memory = 0
        mappedMemory = 0L

        requestedSize = bytes
        allocate()
        renderer.cleanup.add {
            println(" cleanup is running ${cpb}(${pb}) ${cbuffer}(${buffer}) ${cmemory}(${memory})")
//            if (cmappedMemory != MemoryUtil.NULL) {
//                VK10.vkUnmapMemory(renderer.device.vkDevice, memory)
//            }
            if (cpb != null)
                MemoryUtil.memFree(cpb)
            if (cbuffer != 0L)
                VK10.vkDestroyBuffer(renderer.device.vkDevice, cbuffer, null)

            if (cmemory != 0L)
                VK10.vkFreeMemory(renderer.device.vkDevice, cmemory, null)
        }
    }

    class Cleaner(
        val renderer: Renderer,
        val cpb: PointerBuffer?,
        val cbuffer: Long,
        val cmemory: Long,
        val cmappedMemory: Long
    ) : Runnable {
        override fun run() {
            println(" -- cleaner is running ${cpb} ${cbuffer} ${cmemory}")
//            if (cmappedMemory != MemoryUtil.NULL) {
//                VK10.vkUnmapMemory(renderer.device.vkDevice, cmemory)
//            }
            if (cpb != null)
                MemoryUtil.memFree(cpb)
            if (cbuffer != 0L)
                VK10.vkDestroyBuffer(renderer.device.vkDevice, cbuffer, null)
            if (cmemory != 0L)
                VK10.vkFreeMemory(renderer.device.vkDevice, cmemory, null)
        }

    }

    fun pushCleanup() {
        val cpb = pb
        val cbuffer = buffer
        val cmemory = memory
        var cmappedMemory = mappedMemory

        pb = null
        buffer = 0L
        memory = 0L
        mappedMemory = 0L

        renderer.cleanup.add(Cleaner(renderer, cpb, cbuffer, cmemory, cmappedMemory))
    }

    fun cleanup() {
        MemoryUtil.memFree(pb)
        VK10.vkDestroyBuffer(renderer.device.vkDevice, buffer, null)
        VK10.vkFreeMemory(renderer.device.vkDevice, memory, null)
    }

    fun zero(cmd: CommandBuffer) {
        MemoryStack.stackPush().use { stack ->

            vkCmdFillBuffer(
                cmd.vkCommandBuffer, buffer, 0L,                           // offset
                allocationSize,       // size = 4
                0                             // 32-bit pattern = 0
            )

            // 2) Insert a barrier so the fill completes before we run compute
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
                cmd.vkCommandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,       // srcStageMask
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, // dstStageMask
                0,                                    // dependencyFlags
                null,                                 // memoryBarriers
                barrier,                              // bufferMemoryBarriers
                null                                  // imageMemoryBarriers
            )
        }
    }

    override fun clean(hostReading: Boolean, cmd: CommandBuffer?): VulkanBuffer {
        if (cmd == null) return this

        if (true) return this

        // todo: only if dirty, including direct writes
        MemoryStack.stackPush().use { stack ->

            if (!hostReading && zeroBeforeComputation) {
                vkCmdFillBuffer(
                    cmd.vkCommandBuffer, buffer, 0L,                           // offset
                    allocationSize,       // size = 4
                    0                             // 32-bit pattern = 0
                )

                // 2) Insert a barrier so the fill completes before we run compute
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
                    cmd.vkCommandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,       // srcStageMask
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, // dstStageMask
                    0,                                    // dependencyFlags
                    null,                                 // memoryBarriers
                    barrier,                              // bufferMemoryBarriers
                    null                                  // imageMemoryBarriers
                )
            } else if (hint_write && !hostReading && writeWatermark != watermark) {
                writeWatermark = watermark

                val transferBarrier = VkBufferMemoryBarrier.calloc(1, stack)
                transferBarrier.sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                transferBarrier.srcAccessMask(VK_ACCESS_HOST_WRITE_BIT)
                transferBarrier.dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                transferBarrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                transferBarrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                transferBarrier.buffer(buffer)
                transferBarrier.offset(0)
                transferBarrier.size(requestedSize)

                vkCmdPipelineBarrier(
                    cmd.vkCommandBuffer,
                    VK_PIPELINE_STAGE_HOST_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0,            // no dependency flags
                    null,
                    transferBarrier,   // no global memory barriers
                    null
                )
            }
            if (hint_read && hostReading) {

                // todo: only if ... requested some how?

                val transferBarrier = VkBufferMemoryBarrier.calloc(1, stack)
                transferBarrier.sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                transferBarrier.srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                transferBarrier.dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                transferBarrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                transferBarrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                transferBarrier.buffer(buffer)
                transferBarrier.offset(0)
                transferBarrier.size(requestedSize)

                vkCmdPipelineBarrier(
                    cmd.vkCommandBuffer,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,       // or VK_PIPELINE_STAGE_HOST_BIT for host writes
                    // destination stage: where you want to read it
                    VK_PIPELINE_STAGE_HOST_BIT,
                    0,            // no dependency flags
                    null,
                    transferBarrier,   // no global memory barriers
                    null
                )

            }
        }
        return this
    }

    fun map(): Long {
        if (mappedMemory == MemoryUtil.NULL) {
            vkCheck(
                VK10.vkMapMemory(renderer.device.vkDevice, memory, 0, allocationSize, 0, pb!!), "Failed to map Buffer"
            )
            mappedMemory = pb!![0]
        }
        return mappedMemory
    }


    override fun mapAndWrite(builder: FloatBuffer.() -> Unit): VulkanBuffer {
        hint_write = true
        writeWatermark++
        val m = map()
        try {
            val data = MemoryUtil.memFloatBuffer(m, requestedSize.toInt() / 4)
            builder.invoke(data)
        } finally {
            unmap()
        }
        return this
    }

    // todo: right now, only updated in mapAndWrite_dynamic
    var writePosition = 0

    override fun write(builder: DynamicBuffer.() -> Unit): VulkanBuffer {
        hint_write = true
        writeWatermark++
        val m = map()
        try {
            val data = MemoryUtil.memFloatBuffer(m, requestedSize.toInt() / 4)
            val db = DynamicBuffer(data, dynamicResizer())
            builder.invoke(db)
            writePosition = db.target.position()
        } finally {
            unmap()
        }
        return this
    }

    override fun append(builder: DynamicBuffer.() -> Unit): VulkanBuffer {
        hint_write = true
        writeWatermark++
        val m = map()
        try {
            val data = MemoryUtil.memFloatBuffer(m, requestedSize.toInt() / 4)
            val db = DynamicBuffer(data, dynamicResizer())
            db.target.position(writePosition)
            builder.invoke(db)
            writePosition = db.target.position()
        } finally {
            unmap()
        }
        return this
    }

    override fun mapAndWrite_bytes(builder: ByteBuffer.() -> Unit): VulkanBuffer {
        hint_write = true
        writeWatermark++
        val m = map()
        try {
            val data = MemoryUtil.memByteBuffer(m, requestedSize.toInt())
            builder.invoke(data)
        } finally {
            unmap()
        }
        return this
    }

    override fun mapAndWrite_ints(builder: IntBuffer.() -> Unit): VulkanBuffer {
        hint_write = true
        writeWatermark++
        val m = map()
        try {
            val data = MemoryUtil.memIntBuffer(m, requestedSize.toInt() / 4)
            builder.invoke(data)
        } finally {
            unmap()
        }
        return this
    }

    override fun mapAndRead(builder: FloatBuffer.() -> Unit): VulkanBuffer {
        hint_read = true
        readWatermark++
        val m = map()
        try {
            val data = MemoryUtil.memFloatBuffer(m, requestedSize.toInt() / 4)
            builder.invoke(data)
        } finally {
            unmap()
        }
        return this
    }

    override fun mapAndRead_bytes(builder: ByteBuffer.() -> Unit): VulkanBuffer {
        hint_read = true
        readWatermark++
        val m = map()
        try {
            val data = MemoryUtil.memByteBuffer(m, requestedSize.toInt())
            builder.invoke(data)
        } finally {
            unmap()
        }
        return this
    }

    override fun mapAndRead_ints(builder: IntBuffer.() -> Unit): VulkanBuffer {
        hint_read = true
        readWatermark++
        val m = map()
        try {
            val data = MemoryUtil.memIntBuffer(m, requestedSize.toInt() / 4)
            builder.invoke(data)
        } finally {
            unmap()
        }
        return this
    }

    fun unmap(offset : Long = 0, size : Long = requestedSize) {
        if (mappedMemory != MemoryUtil.NULL) {
            MemoryStack.stackPush().use { stack ->
                val range = VkMappedMemoryRange.calloc(stack)
                range.sType(VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE)
                range.memory(memory)
                range.size(requestedSize)
                range.offset(offset)
                VK10.vkFlushMappedMemoryRanges(renderer.device.vkDevice, range)
                VK10.vkUnmapMemory(renderer.device.vkDevice, memory)
                mappedMemory = MemoryUtil.NULL
            }
        }
    }

    override fun toString(): String {
        return "VulkanBuffer(requestedSize=$requestedSize${if (mappedMemory != MemoryUtil.NULL) "  MAPPED" else ""})"
    }

    override fun getBuffer(): Long {
        return buffer
    }

    override fun getGPUAddress(cmd: CommandBuffer): Long {
        if (gpuAddress > 0) return gpuAddress
        else {
            MemoryStack.stackPush().use { stack ->
                val info = VkBufferDeviceAddressInfo.calloc(stack)
                info.sType(VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO)
                info.buffer(buffer)
                gpuAddress = vkGetBufferDeviceAddress(renderer.device.vkDevice, info)
                return gpuAddress
            }
        }
    }


}
