package afield.four.buffers

import afield.four.Renderer
import afield.four.device.CommandBuffer
import afield.four.modules.fromContext
import org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
import org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
import org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

class ParallelGeometryPool(
    val renderer: Renderer = fromContext(),
    val targetWindowIndex: Int = 0,
    val idleRetainFrames: Long = 120,
    private val initialSizeBytes: Long = 4L * 1024L,
    private val usageFlags: Int = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT or VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
    private val memoryFlags: Int = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
) {

    data class WriterPublication(
        val vertexCount: Int? = null,
        val indexCount: Int? = null,
        val dispatchX: Int? = null,
        val dispatchY: Int? = null,
        val dispatchZ: Int? = null
    )

    class ActiveWriter internal constructor(
        val writerId: Long,
        val publication: WriterPublication,
        internal val channels: Map<String, FrameBufferGroup>
    )

    data class SealedSnapshot(
        val token: Renderer.GeometryFrameToken,
        val writers: List<ActiveWriter>
    )

    private data class WriterSlot(
        val writerId: Long,
        val lock: Any = Any(),
        val channels: MutableMap<String, FrameBufferGroup> = mutableMapOf(),
        var publication: WriterPublication = WriterPublication(),
        var lastTouchedFrameId: Long = Long.MIN_VALUE,
        var publishedFrameId: Long = Long.MIN_VALUE
    )

    private data class DispatchContext(
        val pool: ParallelGeometryPool,
        val token: Renderer.GeometryFrameToken,
        val writer: ActiveWriter
    )

    private val currentToken = AtomicReference<Renderer.GeometryFrameToken?>(null)
    private val slots = ConcurrentHashMap<Long, WriterSlot>()
    private val dispatchContext = ThreadLocal<DispatchContext?>()

    @Volatile
    private var sealedSnapshot: SealedSnapshot? = null

    init {
        renderer.registerParallelGeometryPool(this)
    }

    fun writer(writerId: Long = Thread.currentThread().threadId()): ParallelGeometryWriter {
        return ParallelGeometryWriter(this, writerId)
    }

    fun ref(channel: String): ParallelGeometryRef {
        return ParallelGeometryRef(this, channel)
    }

    internal fun beginFrame(token: Renderer.GeometryFrameToken) {
        if (token.windowIndex != targetWindowIndex) return
        currentToken.set(token)
        sealedSnapshot = null
    }

    internal fun sealFrame(token: Renderer.GeometryFrameToken) {
        if (token.windowIndex != targetWindowIndex) return
        if (currentToken.get() != token) return

        val active = mutableListOf<ActiveWriter>()
        val staleWriterIds = mutableListOf<Long>()
        val staleBefore = token.frameId - idleRetainFrames

        slots.forEach { (writerId, slot) ->
            var includeActive = false
            var activePublication = WriterPublication()
            lateinit var channelSnapshot: Map<String, FrameBufferGroup>
            var stale = false
            synchronized(slot.lock) {
                if (slot.publishedFrameId == token.frameId) {
                    includeActive = true
                    activePublication = slot.publication
                    channelSnapshot = HashMap(slot.channels)
                }
                stale = slot.lastTouchedFrameId < staleBefore
            }
            if (includeActive) {
                active += ActiveWriter(writerId, activePublication, channelSnapshot)
            } else if (stale) {
                staleWriterIds += writerId
            }
        }

        active.sortBy { it.writerId }
        sealedSnapshot = SealedSnapshot(token, active)
        currentToken.compareAndSet(token, null)

        if (staleWriterIds.isNotEmpty()) {
            staleWriterIds.forEach { writerId ->
                slots.remove(writerId)?.let { cleanupSlot(it) }
            }
        }
    }

    internal fun snapshotForFrame(frameId: Long, imageIndex: Int): SealedSnapshot? {
        val s = sealedSnapshot ?: return null
        if (s.token.frameId != frameId) return null
        if (s.token.imageIndex != imageIndex) return null
        return s
    }

    internal fun <T> withDispatchWriter(
        token: Renderer.GeometryFrameToken,
        writer: ActiveWriter,
        block: () -> T
    ): T {
        dispatchContext.set(DispatchContext(this, token, writer))
        try {
            return block()
        } finally {
            dispatchContext.remove()
        }
    }

    internal fun resolveRefBuffer(channel: String): VulkanBuffer {
        val ctx = dispatchContext.get()
            ?: throw IllegalStateException("ParallelGeometryRef '$channel' used outside fan-out dispatch context")
        if (ctx.pool !== this) {
            throw IllegalStateException("ParallelGeometryRef '$channel' resolved from the wrong pool")
        }
        val frameGroup = ctx.writer.channels[channel]
            ?: throw IllegalArgumentException("Writer ${ctx.writer.writerId} has no published channel '$channel'")
        return frameGroup.getAt(ctx.token.imageIndex, write = false)
    }

    internal fun write(
        writerId: Long,
        channel: String,
        append: Boolean,
        block: DynamicBuffer.() -> Unit
    ) {
        val token = requireActiveToken()
        val slot = slots.computeIfAbsent(writerId) { WriterSlot(writerId) }
        synchronized(slot.lock) {
            val target = slot.channels.getOrPut(channel) { createChannelBuffer() }
            val buffer = target.getAt(token.imageIndex, write = true)
            if (append) {
                buffer.append(block)
            } else {
                buffer.write(block)
            }
            slot.lastTouchedFrameId = token.frameId
        }
    }

    internal fun publish(
        writerId: Long,
        vertexCount: Int? = null,
        indexCount: Int? = null,
        dispatchX: Int? = null,
        dispatchY: Int? = null,
        dispatchZ: Int? = null
    ) {
        val token = requireActiveToken()
        val slot = slots.computeIfAbsent(writerId) { WriterSlot(writerId) }
        synchronized(slot.lock) {
            slot.publication = WriterPublication(vertexCount, indexCount, dispatchX, dispatchY, dispatchZ)
            slot.publishedFrameId = token.frameId
            slot.lastTouchedFrameId = token.frameId
        }
    }

    fun cleanup() {
        renderer.unregisterParallelGeometryPool(this)
        currentToken.set(null)
        sealedSnapshot = null

        val oldSlots = slots.values.toList()
        slots.clear()
        oldSlots.forEach { cleanupSlot(it) }
    }

    private fun requireActiveToken(): Renderer.GeometryFrameToken {
        return currentToken.get()
            ?: throw IllegalStateException(
                "No active geometry phase for this pool. Write/publish inside Renderer.geometryProducers or between Renderer.beginGeometryWrites()/endGeometryWrites()."
            )
    }

    private fun createChannelBuffer(): FrameBufferGroup {
        val fg = FrameBufferGroup(renderer, n = renderer.numImages)
        fg.build {
            VulkanBuffer(
                renderer = renderer,
                requestedSize = initialSizeBytes,
                usage = usageFlags,
                reqMask = memoryFlags,
                needsDeviceAddress = true
            )
        }
        return fg
    }

    private fun cleanupSlot(slot: WriterSlot) {
        val cleanupTasks = mutableListOf<Runnable>()
        synchronized(slot.lock) {
            slot.channels.values.forEach { group ->
                group.buffers.forEach { buffer ->
                    cleanupTasks += Runnable { buffer.cleanup() }
                }
            }
            slot.channels.clear()
        }
        if (cleanupTasks.isNotEmpty()) {
            renderer.cleanup.addAll(cleanupTasks)
        }
    }
}

class ParallelGeometryWriter internal constructor(
    private val pool: ParallelGeometryPool,
    val writerId: Long
) {
    fun write(channel: String, block: DynamicBuffer.() -> Unit) {
        pool.write(writerId, channel, append = false, block)
    }

    fun append(channel: String, block: DynamicBuffer.() -> Unit) {
        pool.write(writerId, channel, append = true, block)
    }

    fun publish(
        vertexCount: Int? = null,
        indexCount: Int? = null,
        dispatchX: Int? = null,
        dispatchY: Int? = null,
        dispatchZ: Int? = null
    ) {
        pool.publish(writerId, vertexCount, indexCount, dispatchX, dispatchY, dispatchZ)
    }
}

class ParallelGeometryRef internal constructor(
    val pool: ParallelGeometryPool,
    val channel: String
) : HasBuffer {
    override fun getBuffer(): Long {
        return pool.resolveRefBuffer(channel).getBuffer()
    }

    override fun getGPUAddress(cmd: CommandBuffer): Long {
        return pool.resolveRefBuffer(channel).getGPUAddress(cmd)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParallelGeometryRef) return false
        return pool === other.pool && channel == other.channel
    }

    override fun hashCode(): Int {
        return 31 * System.identityHashCode(pool) + channel.hashCode()
    }
}
