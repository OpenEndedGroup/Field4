package afield.graphics.vulkan.buffers

import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.device.CommandBuffer
import afield.document.context.fromContext
import org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
import org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
import org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * ParallelGeometryPool lets several writers produce geometry for the same frame, each into its own per-swapchain-image buffers, and dispatches a module once per writer that published.
 *
 * See [ParallelGeometryPool] for the frame-phase rules, writer publication and how `buffer_reference`
 * push constants switch a module into fan-out mode.
 */

/**
 * Per-writer, per-swapchain-image geometry buffers that fan one module invocation out into one
 * invocation per writer published for the current renderer frame.
 *
 * A pool participates only while its [Renderer] has an open geometry phase. The normal phase is
 * opened by [Renderer.render], after image acquisition and before command recording, and runs the
 * callbacks in [Renderer.geometryProducers]. Runloop code that must launch producers earlier can
 * instead bracket writes with [Renderer.beginGeometryWrites] and [Renderer.endGeometryWrites].
 * Writes outside either window fail.
 *
 * Obtain a stable writer with [writer], write or append one or more named channels, then call
 * [ParallelGeometryWriter.publish] every frame in which that writer should be visible. Publication
 * is strict per-frame liveness; data retained in a channel is not dispatched in a later frame
 * unless the writer publishes again. The sealed writer list is ordered by ascending writer id.
 * Writer slots that have not been touched beyond [idleRetainFrames] are removed and their Vulkan
 * buffers are queued on [Renderer.cleanup]. Call [cleanup] to unregister the pool and release all
 * remaining slots.
 *
 * Bind [ref] values to `buffer_reference` push constants on `Mesh`, `ComputeMesh`, or `MeshMesh`.
 * At command recording, any such reference switches the module to fan-out mode: it dispatches once
 * for each published writer and resolves every reference from that writer's channels. All parallel
 * references in one push-constant structure must belong to this same pool. A missing channel fails
 * that module invocation. Optional counts supplied to `publish` override the module's normal draw
 * or dispatch dimensions for that writer; omitted counts retain the module defaults.
 *
 * [targetWindowIndex] selects the swapchain-image set owned by the channel buffers. External
 * geometry windows currently support only window zero, as enforced by
 * [Renderer.beginGeometryWrites].
 */
class ParallelGeometryPool(
    /** The renderer whose geometry phase the pool follows. Constructing the pool registers it there, and [cleanup] is what takes it off again. */
    val renderer: Renderer = fromContext(),
    /** Which window's swapchain images the channel buffers are sized for. Geometry tokens for any other window are ignored, so a pool only ever sees its own window's frames. */
    val targetWindowIndex: Int = 0,
    /** How many frames a writer slot may go untouched before it is dropped at the next seal and its buffers queued on the renderer's cleanup list. A writer that is still publishing is never dropped; one that falls silent costs this many frames of buffers before it is reclaimed. */
    val idleRetainFrames: Long = 120,
    private val initialSizeBytes: Long = 4L * 1024L,
    private val usageFlags: Int = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT or VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
    private val memoryFlags: Int = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
) {

    /**
     * What one writer asked its fan-out invocation to draw or dispatch this frame. A null count is
     * not zero: it leaves the module's own draw or dispatch dimension in place for that writer.
     */
    data class WriterPublication(
        /** Vertices to draw for this writer, or null to keep the module's own count. */
        val vertexCount: Int? = null,
        /** Indices to draw for this writer, or null to keep the module's own count. */
        val indexCount: Int? = null,
        /** Compute workgroups in x for this writer, or null to keep the module's own. */
        val dispatchX: Int? = null,
        /** Compute workgroups in y for this writer, or null to keep the module's own. */
        val dispatchY: Int? = null,
        /** Compute workgroups in z for this writer, or null to keep the module's own. */
        val dispatchZ: Int? = null
    )

    /**
     * One writer as the frame sealed it: its id, what it published, and the channel buffers it had
     * at that moment. The channel map is copied at seal, so a later write cannot change what this
     * frame's recording sees.
     */
    class ActiveWriter internal constructor(
        /** The id of the writer this invocation is for. */
        val writerId: Long,
        /** The counts this writer published for the frame. */
        val publication: WriterPublication,
        internal val channels: Map<String, FrameBufferGroup>
    )

    /**
     * The whole sealed writer list for one frame, in ascending writer-id order, together with the
     * frame and swapchain image it was sealed against. Recording for a different frame or image
     * finds no snapshot rather than a stale one.
     */
    data class SealedSnapshot(
        /** The frame and image this list was sealed for. */
        val token: Renderer.GeometryFrameToken,
        /** The writers that published this frame, by ascending id. */
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

    /**
     * A handle for writing and publishing under one writer id, by default the calling thread's.
     *
     * The handle is free: it holds no buffers and claims no slot, so making one outside a geometry
     * phase is fine and two handles with the same id are the same writer. The slot and its channel
     * buffers appear on the first write or publish.
     */
    fun writer(writerId: Long = Thread.currentThread().threadId()): ParallelGeometryWriter {
        return ParallelGeometryWriter(this, writerId)
    }

    /**
     * A reference to [channel] that resolves per writer at command-recording time, for binding to
     * a `buffer_reference` push constant. Two references to the same channel of the same pool
     * compare equal, so one can be rebuilt rather than held.
     */
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

    /**
     * Takes the pool off the renderer, drops every writer slot, and queues all of their channel
     * buffers on the renderer's cleanup list. The pool takes no further part in any frame; a write
     * after this one fails for want of an active phase rather than silently going nowhere.
     */
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

/** A stable writer-id view into one [ParallelGeometryPool]. */
class ParallelGeometryWriter internal constructor(
    private val pool: ParallelGeometryPool,
    /** The id this handle writes under. It identifies the pool slot, and it orders this writer among the frame's fan-out invocations. */
    val writerId: Long
) {
    /** Replace this frame image's [channel] contents. */
    fun write(channel: String, block: DynamicBuffer.() -> Unit) {
        pool.write(writerId, channel, append = false, block)
    }

    /** Append to this frame image's existing [channel] contents. */
    fun append(channel: String, block: DynamicBuffer.() -> Unit) {
        pool.write(writerId, channel, append = true, block)
    }

    /**
     * Make this writer live for the current frame, optionally overriding the module's normal draw
     * or dispatch dimensions. Publishing without writing is allowed and reuses retained channels.
     */
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

/**
 * Late-bound reference to one channel of the writer currently being recorded by fan-out dispatch.
 * Resolving it outside that dispatch, or for a writer that lacks [channel], is an error.
 */
class ParallelGeometryRef internal constructor(
    /** The pool this reference resolves against. Every parallel reference in one push-constant block must name this same pool. */
    val pool: ParallelGeometryPool,
    /** The channel name looked up in the writer being recorded. A writer that never wrote this channel fails its own invocation. */
    val channel: String
) : HasBuffer {
    /** The current writer's buffer for [channel], for this frame's swapchain image. */
    override fun getBuffer(): Long {
        return pool.resolveRefBuffer(channel).getBuffer()
    }

    /** The device address of the current writer's [channel] buffer, which is what the push constant carries. */
    override fun getGPUAddress(cmd: CommandBuffer): Long {
        return pool.resolveRefBuffer(channel).getGPUAddress(cmd)
    }

    /** Same pool object and same channel name. The pool is compared by identity, the channel by value. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParallelGeometryRef) return false
        return pool === other.pool && channel == other.channel
    }

    /** Built from the pool's identity hash and the channel name, to match [equals]. */
    override fun hashCode(): Int {
        return 31 * System.identityHashCode(pool) + channel.hashCode()
    }
}
