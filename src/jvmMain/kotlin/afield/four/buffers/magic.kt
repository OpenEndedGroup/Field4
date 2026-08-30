package afield.four.buffers

import afield.four.Renderer
import afield.four.modules.Introspected
import afield.four.modules.fromContext
import afield.four.modules.fromContextWithNameOrNull
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK12.*
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo

fun buffer(
    from: Introspected.UBO? = null,
    name: String = from?.name ?: "unnamed",
    parameters: BufferParameters.() -> Unit
): BufferParameters {
    val b = BufferParameters(from, name)
    b.customize.add(parameters)
    return b
}

/*
given resizable BAR, everything is hosted, coherent, ssbo capable and addressable.
 */
fun defaultBuffer(
    from: Introspected.UBO? = null,
    name: String = from?.name ?: "unnamed",
    parameters: BufferParameters.() -> Unit
): BufferParameters {
    val b = BufferParameters(from, name)
    b.hosted()
    b.storage()
    b.addressable()
    b.customize.add(parameters)
    return b
}


class BufferParameters(
    var source: Introspected.UBO? = null,
    var name: String = source?.name ?: "unnamed"
) {

    var perFrame: Boolean = false

    //std430
    val Int.floats: Long
        get() = this * 4L
    val Int.ints: Long
        get() = this * 4L
    val Int.bytes: Long
        get() = this * 1L
    val Int.vec2s: Long
        get() = this * 8L
    val Int.vec3s: Long
        get() = this * 16L // expanded to vec4
    val Int.vec4s: Long
        get() = this * 16L

    val customize = mutableListOf<BufferParameters.() -> Unit>()

    var inlined = false

    var size: Long = 0
    var usage: Int = 0
    var memory: Int = 0
    var needsDeviceAddress = false
    var accessPattern: BufferAccessPattern = BufferAccessPattern.HOST_VISIBLE
    var stagedUploads = false
    var readbackStaging = false

    var postAdaptation = mutableListOf<(VulkanBuffer) -> Unit>();
    var result: FrameBufferGroup? = null

    fun storage() {
        usage = usage or VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
    }

    fun inlined() {
        this.inlined = true
    }

    fun addressable() {
        storage()
        usage = usage or VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
        needsDeviceAddress = true
    }

    fun uniform() {
        usage = usage or VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT
    }

    fun hosted() {
        accessPattern = BufferAccessPattern.HOST_VISIBLE
        stagedUploads = false
        readbackStaging = false
        memory = memory// or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
        memory = memory or VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
    }

    fun notHosted() {
        memory = memory and VK_MEMORY_PROPERTY_HOST_COHERENT_BIT.inv()
        memory = memory and VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT.inv()
    }

    fun writeOnce() {
        accessPattern = BufferAccessPattern.STAGED_WRITE_ONCE
        stagedUploads = true
        usage = usage or VK_BUFFER_USAGE_TRANSFER_DST_BIT
        memory = (memory and hostMemoryBits.inv()) or VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
        perFrame = false
    }

    fun stagedOnce() = writeOnce()

    fun streamWrites() {
        accessPattern = BufferAccessPattern.STAGED_STREAM_WRITE
        stagedUploads = true
        usage = usage or VK_BUFFER_USAGE_TRANSFER_DST_BIT
        memory = (memory and hostMemoryBits.inv()) or VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
        perFrame = true
    }

    fun stagedStream() = streamWrites()

    fun readOccasionallyForDebugging() {
        if (!stagedUploads) accessPattern = BufferAccessPattern.DEVICE_LOCAL_DEBUG_READ
        readbackStaging = true
        usage = usage or VK_BUFFER_USAGE_TRANSFER_SRC_BIT
        memory = (memory and hostMemoryBits.inv()) or VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
    }

    fun debugReads() = readOccasionallyForDebugging()
    fun occasionalReads() = readOccasionallyForDebugging()

    fun streamReads() {
        accessPattern = BufferAccessPattern.HOST_VISIBLE_STREAM_READ
        stagedUploads = false
        readbackStaging = false
        memory = memory or VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_CACHED_BIT
    }

    fun zeroInitialized() {
        accessPattern = BufferAccessPattern.DEVICE_LOCAL_ZERO
        stagedUploads = false
        // FrameBufferGroup establishes the initial contents with vkCmdFillBuffer.
        usage = usage or VK_BUFFER_USAGE_TRANSFER_DST_BIT
        memory = (memory and hostMemoryBits.inv()) or VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
    }

    fun zeroInit() = zeroInitialized()

    fun indirect() {
        usage = usage or VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT
    }

    fun dest() {
        usage = usage or VK_BUFFER_USAGE_TRANSFER_DST_BIT
    }

    fun source() {
        usage = usage or VK_BUFFER_USAGE_TRANSFER_SRC_BIT
    }


    operator fun invoke(buffer: BufferParameters.() -> Unit): BufferParameters {
        this.buffer()
        return this
    }

    fun mergeFrom(initialDefaults: BufferParameters) {
        this.customize.addAll(0, initialDefaults.customize)
        if (initialDefaults.result != null && this.result == null)
            this.result = initialDefaults.result!!
    }

    fun checkCompatibility(result: BufferParameters?): Boolean {
        if (result == null) return false
        result.customize.forEach { result.it() }

        //TODO — zeroed? binding?
        return result.usage == usage &&
                result.memory == memory &&
                result.size == size &&
                result.inlined == inlined &&
                result.accessPattern == accessPattern &&
                result.stagedUploads == stagedUploads &&
                result.readbackStaging == readbackStaging
    }

    fun store(): BufferParameters {
        val stored = BufferParameters()
        stored.usage = usage
        stored.memory = memory
        stored.size = size
        stored.name = name
        stored.source = source
        stored.needsDeviceAddress = needsDeviceAddress
        stored.accessPattern = accessPattern
        stored.stagedUploads = stagedUploads
        stored.readbackStaging = readbackStaging
        stored.result = result
        //TODO — zeroed? binding?
        return stored
    }

    val buffer: FrameBufferGroup
        get() = toBuffer()

    fun toBuffer(renderer: Renderer = fromContext()): FrameBufferGroup {

        if (inlined && (usage and VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT !=0)) {
            throw RuntimeException("Cannot have a descriptor push buffer that is also gpu addressable")
        }

        if (result != null) {
            if (checkCompatibility(result!!.get().source)) {
                return result!!
            }
        }

        customize.forEach {
            it()
        }

        val buffer = FrameBufferGroup(
            renderer,
            n = if (perFrame) renderer.numImages else 1,
            accessPattern = accessPattern,
            stagedUploads = stagedUploads,
            readbackStaging = readbackStaging
        )
        buffer.build {
            val buffer = VulkanBuffer(renderer, size, usage, memory, needsDeviceAddress = needsDeviceAddress, cpuOnly = inlined)
            postAdaptation.forEach { it(buffer) }
            buffer
        }
        if (buffer.usesStagedUploads) {
            buffer.buildStaging {
                VulkanBuffer(
                    renderer,
                    size,
                    usage or VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
                )
            }
        }
        if (buffer.usesReadbackStaging) {
            buffer.buildReadback {
                VulkanBuffer(
                    renderer,
                    size,
                    VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_CACHED_BIT
                )
            }
        }

        result = buffer
        buffer.buffers.forEach { it.source = this.store() }

        if (usage and VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT != 0) {
            buffer.buffers.forEach { buffer ->
                MemoryStack.stackPush().use { stack ->
                    val info = VkBufferDeviceAddressInfo.calloc(stack)
                    info.sType(VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO)
                    info.buffer(buffer.buffer)
                    buffer.gpuAddress = vkGetBufferDeviceAddress(renderer.device.vkDevice, info)
                }
            }
        }
        return buffer
    }

    companion object {
        private const val hostMemoryBits =
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or
                    VK_MEMORY_PROPERTY_HOST_COHERENT_BIT or
                    VK_MEMORY_PROPERTY_HOST_CACHED_BIT
    }
}

fun linkBuffer(name: String, initialSettings: BufferParameters): Pair<Boolean, BufferParameters> {
    var found = fromContextWithNameOrNull<BufferParameters>(name, null)
    if (found == null) {
        return false to initialSettings
    } else {
        found.mergeFrom(initialSettings)
        return true to found
    }
}
