package afield.stdlib.linalg.arena

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * Thread-confined, resettable scratch storage for generated linear-algebra values.
 *
 * Handles never expose a [MemorySegment] or native address. This lets growth move
 * the backing allocation while preserving logical offsets. Debug validation is on
 * by default because Field code is unusually good at retaining values in callbacks.
 */
class FrameArena(
    initialCapacityBytes: Int = DEFAULT_CAPACITY_BYTES,
    val debugChecks: Boolean = ARENA_CHECKS_COMPILED,
) : AutoCloseable {
    internal enum class Kind(val componentCount: Int) {
        VEC2(2),
        VEC3(3),
        VEC4(4),
    }

    private var storageOwner: Arena = Arena.ofConfined()
    private var storage: MemorySegment
    // One entry per 16-byte slot: (stamp.toLong() shl 32) or (kind.ordinal + 1). 0 means free.
    // Packing stamp and kind together keeps the per-access validation to a single array load.
    private var slotMetadata: LongArray
    private var cursorBytes = 0
    private var resetFloorStamp = 0
    private var closed = false
    private val ownerThreadId = if (ARENA_CHECKS_COMPILED) Thread.currentThread().threadId() else -1L
    private val ownerThreadName = if (ARENA_CHECKS_COMPILED) Thread.currentThread().name else ""
    private val scope = FrameScope(this)
    private var markStack = LongArray(8)
    private var markDepth = 0

    var highWaterBytes: Int = 0
        private set
    var allocationCount: Long = 0
        private set
    var growthCount: Long = 0
        private set
    var bytesCopiedDuringGrowth: Long = 0
        private set
    var resetCount: Long = 0
        private set

    init {
        require(initialCapacityBytes > 0) { "initial capacity must be positive" }
        val capacity = align16(initialCapacityBytes)
        storage = storageOwner.allocate(capacity.toLong(), ALIGNMENT_BYTES.toLong())
        slotMetadata = if (ARENA_CHECKS_COMPILED && debugChecks) LongArray(capacity / SLOT_BYTES) else LongArray(0)
    }

    // Cached so the hot allocation path does not call MemorySegment.byteSize() on every allocation.
    private var capacityBytesCached: Int = storage.byteSize().toInt()

    val capacityBytes: Int
        get() = capacityBytesCached

    val usedBytes: Int
        get() = cursorBytes

    val isClosed: Boolean
        get() = closed

    val checksEnabled: Boolean
        get() = ARENA_CHECKS_COMPILED && debugChecks

    /**
     * Runs a non-suspending scratch region and invalidates everything allocated
     * in the block when it returns.
     */
    inline fun <R> scoped(block: FrameScope.() -> R): R {
        val mark = mark()
        return try {
            block(scopeForInline())
        } finally {
            restore(mark)
        }
    }

    @PublishedApi
    internal fun scopeForInline(): FrameScope = scope

    fun reset() {
        checkAccess()
        cursorBytes = 0
        markDepth = 0
        resetFloorStamp = if (ARENA_CHECKS_COMPILED && debugChecks) nextStamp() else 0
        resetCount++
    }

    fun mark(): ArenaMark {
        checkAccess()
        val stamp = if (ARENA_CHECKS_COMPILED && debugChecks) nextStamp() else 0
        val token = pack(stamp, cursorBytes)
        if (markDepth == markStack.size) markStack = markStack.copyOf(markStack.size * 2)
        markStack[markDepth++] = token
        return ArenaMark(token)
    }

    fun restore(mark: ArenaMark) {
        checkAccess()
        check(markDepth > 0 && markStack[markDepth - 1] == mark.token) {
            "FrameArena marks must be restored once, in LIFO order"
        }
        val restoredCursor = unpackOffset(mark.token)
        if (ARENA_CHECKS_COMPILED && debugChecks) {
            val firstDeadSlot = restoredCursor / SLOT_BYTES
            val lastUsedSlot = (cursorBytes + SLOT_BYTES - 1) / SLOT_BYTES
            slotMetadata.fill(0L, firstDeadSlot, lastUsedSlot)
        }
        cursorBytes = restoredCursor
        markDepth--
    }

    internal fun allocateVector(kind: Kind): Long {
        checkAccess()
        val offset = align16(cursorBytes)
        val required = offset + SLOT_BYTES
        ensureCapacity(required)
        val stamp = if (ARENA_CHECKS_COMPILED && debugChecks) nextStamp() else 0
        if (ARENA_CHECKS_COMPILED && debugChecks) {
            slotMetadata[offset / SLOT_BYTES] = packSlotMetadata(stamp, kind)
        }
        cursorBytes = required
        highWaterBytes = max(highWaterBytes, cursorBytes)
        allocationCount++
        if (ARENA_CHECKS_COMPILED && debugChecks) {
            for (component in 0 until 4) {
                storage.set(ValueLayout.JAVA_FLOAT, offset.toLong() + component * Float.SIZE_BYTES, Float.NaN)
            }
        }
        return pack(stamp, offset)
    }

    internal fun read(token: Long, component: Int, expectedKind: Kind): Float {
        val offset = validate(token, component, expectedKind)
        return storage.get(ValueLayout.JAVA_FLOAT, offset.toLong() + component * Float.SIZE_BYTES)
    }

    internal fun write(token: Long, component: Int, value: Float, expectedKind: Kind) {
        val offset = validate(token, component, expectedKind)
        storage.set(ValueLayout.JAVA_FLOAT, offset.toLong() + component * Float.SIZE_BYTES, value)
    }

    /**
     * Validates a handle once and returns its byte offset. Multi-component operations resolve each
     * input and the destination a single time, then use [readAt]/[writeAt], instead of re-validating
     * on every component. The component index is a generated constant in that case, so the
     * per-component range check is unnecessary.
     */
    internal fun resolve(token: Long, expectedKind: Kind): Int {
        val offset = unpackOffset(token)
        if (!ARENA_CHECKS_COMPILED || !debugChecks) return offset
        // Thread-confinement and closed-state are enforced by the confined FFM arena on the get/set
        // that follows (WrongThreadException / IllegalStateException); this path only checks the
        // arena-specific logic the segment cannot know: liveness after reset, slot ownership, kind.
        val stamp = unpackStamp(token)
        check(stamp > resetFloorStamp) {
            "${expectedKind.name} handle was used after FrameArena.reset()"
        }
        check(offset >= 0 && offset + SLOT_BYTES <= cursorBytes && offset % SLOT_BYTES == 0) {
            "${expectedKind.name} handle points outside live FrameArena storage: offset=$offset used=$cursorBytes"
        }
        val metadata = slotMetadata[offset / SLOT_BYTES]
        val currentStamp = (metadata ushr 32).toInt()
        check(currentStamp == stamp) {
            "${expectedKind.name} handle is stale or belongs to another FrameArena " +
                "(handleStamp=$stamp, slotStamp=$currentStamp, offset=$offset)"
        }
        val currentKind = (metadata.toInt() and 0xff) - 1
        check(currentKind == expectedKind.ordinal) {
            "Arena handle type mismatch: expected ${expectedKind.name}, slot contains " +
                Kind.entries.getOrNull(currentKind)?.name
        }
        return offset
    }

    internal fun readAt(offset: Int, component: Int): Float =
        storage.get(ValueLayout.JAVA_FLOAT, offset.toLong() + component * Float.SIZE_BYTES)

    internal fun writeAt(offset: Int, component: Int, value: Float) =
        storage.set(ValueLayout.JAVA_FLOAT, offset.toLong() + component * Float.SIZE_BYTES, value)

    private fun validate(token: Long, component: Int, expectedKind: Kind): Int {
        if (ARENA_CHECKS_COMPILED && debugChecks) {
            require(component in 0 until expectedKind.componentCount) {
                "component $component outside ${expectedKind.name}"
            }
        }
        return resolve(token, expectedKind)
    }

    private fun ensureCapacity(requiredBytes: Int) {
        if (requiredBytes <= capacityBytes) return
        val newCapacity = align16(max(requiredBytes, Math.multiplyExact(capacityBytes, 2)))
        val newOwner = Arena.ofConfined()
        val newStorage = newOwner.allocate(newCapacity.toLong(), ALIGNMENT_BYTES.toLong())
        MemorySegment.copy(storage, 0, newStorage, 0, cursorBytes.toLong())
        bytesCopiedDuringGrowth += cursorBytes
        val oldOwner = storageOwner
        storageOwner = newOwner
        storage = newStorage
        capacityBytesCached = newCapacity
        if (ARENA_CHECKS_COMPILED && debugChecks) {
            slotMetadata = slotMetadata.copyOf(newCapacity / SLOT_BYTES)
        }
        oldOwner.close()
        growthCount++
    }

    private fun checkAccess() {
        if (!ARENA_CHECKS_COMPILED || !debugChecks) return
        check(!closed) { "FrameArena is closed" }
        val current = Thread.currentThread()
        check(current.threadId() == ownerThreadId) {
            "FrameArena owned by '$ownerThreadName' was accessed from '${current.name}'"
        }
    }

    override fun close() {
        if (closed) return
        checkAccess()
        closed = true
        storageOwner.close()
    }

    companion object {
        const val DEFAULT_CAPACITY_BYTES: Int = 64 * 1024
        val checksCompiledIn: Boolean
            get() = ARENA_CHECKS_COMPILED
        private const val SLOT_BYTES = 16
        private const val ALIGNMENT_BYTES = 16
        private val globalStamp: AtomicInteger? = if (ARENA_CHECKS_COMPILED) AtomicInteger(0) else null
        private val currentArena = ThreadLocal<FrameArena?>()

        /**
         * Scratch arena owned by the current platform thread. This is intended for
         * stable render, update, and worker threads; prefer an explicit [FrameArena]
         * for short-lived virtual threads so each task does not retain native memory.
         */
        fun current(): FrameArena = currentArena.get()
            ?.takeUnless { it.isClosed }
            ?: FrameArena().also(currentArena::set)

        /** Releases native storage retained by [current] on this thread. */
        fun closeCurrent() {
            currentArena.get()?.close()
            currentArena.remove()
        }

        private fun nextStamp(): Int = checkNotNull(globalStamp).incrementAndGet().also {
            check(it > 0) { "FrameArena allocation stamp space exhausted" }
        }

        private fun pack(stamp: Int, offset: Int): Long =
            (stamp.toLong() shl 32) or (offset.toLong() and 0xffff_ffffL)

        private fun unpackStamp(token: Long): Int = (token ushr 32).toInt()
        private fun unpackOffset(token: Long): Int = token.toInt()
        private fun packSlotMetadata(stamp: Int, kind: Kind): Long =
            (stamp.toLong() shl 32) or (kind.ordinal + 1).toLong()
        private fun align16(value: Int): Int = (value + 15) and -16
    }
}

@JvmInline
value class ArenaMark internal constructor(internal val token: Long)

/**
 * Runs an allocation-free, non-suspending scratch region on this platform thread.
 * Nested calls are supported and restore their arena marks in LIFO order.
 */
inline fun <R> frameScratch(block: FrameScope.() -> R): R = FrameArena.current().scoped(block)
