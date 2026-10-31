package afield.graphics.vulkan.buffers

import afield.graphics.vulkan.Renderer
import afield.graphics.shader.Introspected
import afield.document.context.fromContext
import afield.document.context.fromContextWithNameOrNull
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK12.*
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo

/**
 * The buffer DSL: [buffer] and [defaultBuffer] build a [BufferParameters] that says how a Vulkan buffer is sized, placed in memory and used.
 *
 * [defaultBuffer] starts from host-visible, storage and device-addressable memory, which suits
 * hardware with resizable BAR or unified memory; [buffer] starts from nothing. Size helpers such as
 * `10.floats` or `4.vec4s` count std430 bytes (a vec3 takes 16). [linkBuffer] looks for an existing
 * [BufferParameters] of the same name in the box context and merges new settings into it, so boxes
 * can share one buffer.
 */

/**
 * Builds a [BufferParameters] named [name], optionally sized from shader reflection [from], and
 * applies [parameters] to it.
 */
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
/**
 * [buffer], but starting from host-visible, storage and device-addressable before [parameters]
 * runs. That is the combination to reach for on hardware with resizable BAR or unified memory,
 * where the host can write straight into memory the GPU reads by pointer.
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
    /**
     * The reflected shader block this buffer is for, when there is one. It supplies the default
     * [name] and is recorded on every buffer allocated from these parameters, which is what later
     * bindings compare themselves against; sizing from it is the caller's own `size = source.size`.
     */
    var source: Introspected.UBO? = null,
    /** What the buffer is called: the name `linkBuffer` matches in the box context so two boxes can reach the same allocation, and the name the incompatibility message quotes. */
    var name: String = source?.name ?: "unnamed"
) {

    /** Whether to allocate one buffer per swapchain image rather than a single shared one, so the CPU can write next frame's contents while the GPU still reads this frame's. [streamWrites] sets it; [writeOnce] clears it. */
    var perFrame: Boolean = false

    /** `n.floats` — the std430 size in bytes of `n` floats. */
    val Int.floats: Long
        get() = this * 4L

    /** `n.ints` — the std430 size in bytes of `n` 32-bit integers. */
    val Int.ints: Long
        get() = this * 4L

    /** `n.bytes` — `n`, for a size already counted in bytes. */
    val Int.bytes: Long
        get() = this * 1L

    /** `n.vec2s` — the std430 size in bytes of `n` `vec2`s, 8 apiece. */
    val Int.vec2s: Long
        get() = this * 8L

    /** `n.vec3s` — 16 bytes apiece, not 12: std430 pads a `vec3` out to a `vec4`, and a host-side writer that packs them tightly will be read back misaligned. */
    val Int.vec3s: Long
        get() = this * 16L // expanded to vec4

    /** `n.vec4s` — the std430 size in bytes of `n` `vec4`s, 16 apiece. */
    val Int.vec4s: Long
        get() = this * 16L

    /** The blocks that configure these parameters, run in order at allocation rather than when they are added, so a size written in terms of a count the box has not computed yet still works. [mergeFrom] puts a donor's blocks in front of these. */
    val customize = mutableListOf<BufferParameters.() -> Unit>()

    /** Whether the contents live in plain host memory, pushed inline where they are used, instead of in a `VkBuffer` the GPU reads for itself. Such a buffer has nothing to bind to a descriptor and no device address, so asking for [addressable] as well throws. */
    var inlined = false

    /** Size of the buffer in bytes; the `n.floats`-style helpers above count std430 bytes for a given element count. */
    var size: Long = 0

    /** The raw `VkBufferUsageFlags`. Set it through [storage], [uniform], [indirect], [addressable] and the transfer pair `dest()` and `source()`, each of which adds its bit rather than replacing the field. */
    var usage: Int = 0

    /** The raw `VkMemoryPropertyFlags` the allocation is placed by. [hosted], [writeOnce], [streamWrites], [streamReads] and [zeroInitialized] each set a coherent combination of this and [accessPattern]. */
    var memory: Int = 0

    /** Whether the buffer's GPU address is fetched after allocation, so a shader can reach it by pointer instead of through a descriptor. [addressable] sets it. */
    var needsDeviceAddress = false

    /** How the buffer is written and read over its life. It is compared exactly when an existing allocation is offered for this binding, so a buffer tuned for readback will not stand in for a plain [hosted] one even though both are host visible. */
    var accessPattern: BufferAccessPattern = BufferAccessPattern.HOST_VISIBLE

    /** Whether writes go through a host-visible staging buffer and a transfer, rather than into device memory the host can see directly. Allocates a second buffer of the same [size]. */
    var stagedUploads = false

    /** Whether a host-cached buffer is allocated to read results back into, which is what [readOccasionallyForDebugging] is asking for. */
    var readbackStaging = false

    /** Blocks run on each [VulkanBuffer] as it is created, while the group is still being built, so [perFrame] runs them once per swapchain image. Nothing in the tree registers one yet. */
    var postAdaptation = mutableListOf<(VulkanBuffer) -> Unit>();

    /** The allocation these parameters produced, held so that asking for the buffer twice does not allocate twice. Set it yourself to bind an allocation you already have, and [toBuffer] will refuse it rather than silently allocate a fresh one if its parameters do not match. */
    var result: FrameBufferGroup? = null

    /**
     * Set by a module that built this request, found no one else supplying it, and so allocated it
     * itself: the buffer is the module's to carry across a full reload, zero, or retire. A buffer found
     * by name in the context, or handed back by a custom resolver, is not. See `ReloadCarryOver`.
     */
    internal var allocatedByModule = false

    /** Adds the storage-buffer usage bit, so a shader can bind it as an SSBO. */
    fun storage() {
        usage = usage or VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
    }

    /** Marks the contents as pushed inline where they are used rather than living in a `VkBuffer`. It cannot be combined with [addressable], which [toBuffer] refuses. */
    fun inlined() {
        this.inlined = true
    }

    /**
     * Adds the device-address usage bit, and the storage bit with it, and asks for the address to
     * be fetched once the buffer is allocated. This is what lets a shader reach the buffer through
     * a `buffer_reference` pointer instead of a descriptor; it cannot be combined with `inlined()`.
     */
    fun addressable() {
        storage()
        usage = usage or VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
        needsDeviceAddress = true
    }

    /** Adds the uniform-buffer usage bit, for a small block bound as a UBO. */
    fun uniform() {
        usage = usage or VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT
    }

    /**
     * Puts the buffer in memory the host can write directly, with no staging copy in either
     * direction. It adds the host-visible memory bit without clearing any other, so a buffer moved
     * here from a device-local pattern keeps that bit as well.
     */
    fun hosted() {
        accessPattern = BufferAccessPattern.HOST_VISIBLE
        stagedUploads = false
        readbackStaging = false
        memory = memory// or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
        memory = memory or VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
    }

    /**
     * Clears the host-visible and host-coherent memory bits. It touches neither the access pattern
     * nor the staging flags, so it narrows where the allocation may be placed rather than switching
     * the buffer to a staged scheme; `writeOnce` and the rest are what do that.
     */
    fun notHosted() {
        memory = memory and VK_MEMORY_PROPERTY_HOST_COHERENT_BIT.inv()
        memory = memory and VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT.inv()
    }

    /**
     * Device-local memory, filled once from a host staging buffer. It adds the transfer-destination
     * usage bit, clears the host memory bits, and asks for a single buffer rather than one per
     * swapchain image, since the contents do not change between frames.
     */
    fun writeOnce() {
        accessPattern = BufferAccessPattern.STAGED_WRITE_ONCE
        stagedUploads = true
        usage = usage or VK_BUFFER_USAGE_TRANSFER_DST_BIT
        memory = (memory and hostMemoryBits.inv()) or VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
        perFrame = false
    }

    /** [writeOnce] under its other name. */
    fun stagedOnce() = writeOnce()

    /**
     * Device-local memory, refilled from a host staging buffer every frame. Same as [writeOnce]
     * except that it asks for one buffer per swapchain image, so the host can write next frame's
     * contents while the GPU still reads this frame's.
     */
    fun streamWrites() {
        accessPattern = BufferAccessPattern.STAGED_STREAM_WRITE
        stagedUploads = true
        usage = usage or VK_BUFFER_USAGE_TRANSFER_DST_BIT
        memory = (memory and hostMemoryBits.inv()) or VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
        perFrame = true
    }

    /** [streamWrites] under its other name. */
    fun stagedStream() = streamWrites()

    /**
     * Device-local memory with a host-cached copy to read results back into. It adds the
     * transfer-source usage bit and clears the host memory bits; a buffer that is already written
     * through staging keeps that access pattern and gains only the readback.
     *
     * The read itself copies and waits for the GPU, so it is for inspecting a result now and then,
     * not for a per-frame path.
     */
    fun readOccasionallyForDebugging() {
        if (!stagedUploads) accessPattern = BufferAccessPattern.DEVICE_LOCAL_DEBUG_READ
        readbackStaging = true
        usage = usage or VK_BUFFER_USAGE_TRANSFER_SRC_BIT
        memory = (memory and hostMemoryBits.inv()) or VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
    }

    /** [readOccasionallyForDebugging] under a shorter name. */
    fun debugReads() = readOccasionallyForDebugging()
    /** [readOccasionallyForDebugging] under a shorter name. */
    fun occasionalReads() = readOccasionallyForDebugging()

    /**
     * Host-visible, host-cached memory read every frame, with no staging in either direction. The
     * cached bit is what makes repeated host reads bearable; without it a host read of write-combined
     * memory is very slow.
     */
    fun streamReads() {
        accessPattern = BufferAccessPattern.HOST_VISIBLE_STREAM_READ
        stagedUploads = false
        readbackStaging = false
        memory = memory or VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_CACHED_BIT
    }

    /**
     * Device-local memory whose contents start at zero. It adds the transfer-destination usage bit,
     * because the zeroing is a fill recorded by the buffer group the first time the buffer is
     * cleaned rather than anything the host writes.
     */
    fun zeroInitialized() {
        accessPattern = BufferAccessPattern.DEVICE_LOCAL_ZERO
        stagedUploads = false
        // FrameBufferGroup establishes the initial contents with vkCmdFillBuffer.
        usage = usage or VK_BUFFER_USAGE_TRANSFER_DST_BIT
        memory = (memory and hostMemoryBits.inv()) or VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
    }

    /** [zeroInitialized] under a shorter name. */
    fun zeroInit() = zeroInitialized()

    /** Adds the indirect-buffer usage bit, so a draw or dispatch can read its own counts out of this buffer. */
    fun indirect() {
        usage = usage or VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT
    }

    /** Adds the transfer-destination usage bit, for a buffer something else copies into. `source()` is the other half. */
    fun dest() {
        usage = usage or VK_BUFFER_USAGE_TRANSFER_DST_BIT
    }

    /** Adds the transfer-source usage bit, for a buffer something else copies out of. [dest] is the other half. */
    fun source() {
        usage = usage or VK_BUFFER_USAGE_TRANSFER_SRC_BIT
    }


    /**
     * Applies another block of settings and hands the same parameters back, so a prepared
     * [BufferParameters] can be adjusted where it is used: `params { size = 1024.floats }`.
     *
     * The block runs now, unlike one given to [buffer] or [defaultBuffer], which is held in
     * [customize] and run at allocation. A size written here cannot depend on a count that has not
     * been computed yet.
     */
    operator fun invoke(buffer: BufferParameters.() -> Unit): BufferParameters {
        this.buffer()
        return this
    }

    /**
     * Takes another request's settings as defaults under these. Its [customize] blocks go in front
     * of this one's, so they run first and anything set here wins, and its allocation is adopted if
     * this request has none of its own. This is how [linkBuffer] lets two boxes share one buffer.
     */
    fun mergeFrom(initialDefaults: BufferParameters) {
        this.customize.addAll(0, initialDefaults.customize)
        if (initialDefaults.result != null && this.result == null)
            this.result = initialDefaults.result!!
    }

    /**
     * A field-by-field account of why [checkCompatibility] said no, for the exception that
     * reports it.
     *
     * Every line names the field, what this binding wants, and what the supplied buffer has,
     * because the useful half of this failure is *which* field differs — `accessPattern` means
     * someone reached for `streamReads()`, `usage` means a missing `storage()` or `dest()`, and
     * `size` usually means a count that moved.
     */
    fun incompatibilityReport(other: BufferParameters?): String {
        if (other == null) return "  the supplied buffer has no recorded parameters at all"
        val lines = mutableListOf<String>()
        fun compare(field: String, wanted: Any?, has: Any?) {
            if (wanted != has) lines += "  $field: this binding wants $wanted, the buffer has $has"
        }
        compare("usage", "0x${usage.toString(16)}", "0x${other.usage.toString(16)}")
        compare("memory", "0x${memory.toString(16)}", "0x${other.memory.toString(16)}")
        compare("size", size, other.size)
        compare("inlined", inlined, other.inlined)
        compare("accessPattern", accessPattern, other.accessPattern)
        compare("stagedUploads", stagedUploads, other.stagedUploads)
        compare("readbackStaging", readbackStaging, other.readbackStaging)
        return if (lines.isEmpty()) {
            "  no field differs, which means compatibility was refused for a reason this report " +
                "does not cover -- check checkCompatibility()"
        } else {
            lines.joinToString("\n")
        }
    }

    /**
     * Whether an existing allocation's recorded parameters will serve for this request.
     *
     * The candidate's own deferred blocks are run first, so a size it computes at allocation is
     * resolved before the comparison. Then usage, memory, size, inlined, access pattern and the two
     * staging flags must match exactly. Name, source block and the device-address flag are not
     * compared, and [incompatibilityReport] is what says which of the rest differed.
     */
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

    /**
     * A snapshot of the settled fields, recorded on every buffer this request allocates so that a
     * later binding has something to compare itself against. It carries the flags, size, name and
     * source block, and not the deferred [customize] blocks, [perFrame] or [postAdaptation].
     */
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

    /** [toBuffer] with the renderer taken from the box context. Asking twice returns the same allocation. */
    val buffer: FrameBufferGroup
        get() = toBuffer()

    /**
     * Allocates the buffers this request describes, or returns the ones it already made.
     *
     * The deferred [customize] blocks run first, so sizes settle here rather than where they were
     * written. What comes out is a [FrameBufferGroup] of one buffer, or one per swapchain image
     * when [perFrame] is set, with staging or readback buffers alongside if the access pattern asks
     * for them, each buffer carrying a [store] of these parameters and its device address if one
     * was wanted.
     *
     * Two requests are refused rather than worked around: an inlined buffer that also wants a
     * device address, and a [result] handed in whose recorded parameters do not match these, which
     * reports the differing fields instead of allocating a second buffer and leaving the caller's
     * own unwritten and unread.
     */
    fun toBuffer(renderer: Renderer = fromContext()): FrameBufferGroup {

        if (inlined && (usage and VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT !=0)) {
            throw RuntimeException("Cannot have a descriptor push buffer that is also gpu addressable")
        }

        if (result != null) {
            val existing = result!!.get().source
            if (checkCompatibility(existing)) {
                return result!!
            }
            // A caller who set `result` handed us a *specific* allocation to bind. If we cannot
            // bind it, the only honest thing is to say so.
            //
            // This used to fall through and allocate a fresh buffer instead. Everything then
            // worked perfectly on memory the caller had never heard of: their buffer was never
            // written, never read, and never mentioned. It took an afternoon to find once,
            // because "my buffer is unchanged" is indistinguishable from "the dispatch never
            // ran", and there is no third state to notice.
            throw IllegalStateException(
                "buffer '$name' was supplied as an existing allocation, but its parameters do not " +
                    "match the ones requested here, so it cannot be bound:\n" +
                    incompatibilityReport(existing) +
                    "\nEither build the buffer with the parameters the consumer expects, or let the " +
                    "consumer allocate its own by not setting `result`. Note that `streamReads()` " +
                    "and `writeOnce()` and friends all change `accessPattern`, which is compared " +
                    "exactly — a buffer tuned for host readback is not interchangeable with a plain " +
                    "`hosted()` one even though both are host visible."
            )
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

/**
 * Looks for a [BufferParameters] called [name] in the box context, so boxes beneath a common
 * parent can write to and read from one buffer.
 *
 * When one is found, [initialSettings] is merged into it as defaults and the pair is `true` with
 * that shared request; when none is, the pair is `false` with [initialSettings] unchanged. The
 * boolean is how the caller tells whether it is the one that has to publish the request for
 * others to find.
 */
fun linkBuffer(name: String, initialSettings: BufferParameters): Pair<Boolean, BufferParameters> {
    var found = fromContextWithNameOrNull<BufferParameters>(name, null)
    if (found == null) {
        return false to initialSettings
    } else {
        found.mergeFrom(initialSettings)
        return true to found
    }
}
