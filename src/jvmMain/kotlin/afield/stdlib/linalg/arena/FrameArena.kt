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
    val debugChecks: Boolean = true,
) : AutoCloseable {
    internal enum class Kind(val componentCount: Int) {
        VEC2(2),
        VEC3(3),
        VEC4(4),
    }

    private var storageOwner: Arena = Arena.ofConfined()
    private var storage: MemorySegment
    private var allocationStamps: IntArray
    private var allocationKinds: ByteArray
    private var cursorBytes = 0
    private var resetFloorStamp = 0
    private var closed = false
    private val ownerThreadId = Thread.currentThread().threadId()
    private val ownerThreadName = Thread.currentThread().name
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
        allocationStamps = IntArray(capacity / SLOT_BYTES)
        allocationKinds = ByteArray(capacity / SLOT_BYTES)
    }

    val capacityBytes: Int
        get() = storage.byteSize().toInt()

    val usedBytes: Int
        get() = cursorBytes

    val isClosed: Boolean
        get() = closed

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
        resetFloorStamp = nextStamp()
        resetCount++
    }

    fun mark(): ArenaMark {
        checkAccess()
        val stamp = nextStamp()
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
        if (debugChecks) {
            val firstDeadSlot = restoredCursor / SLOT_BYTES
            val lastUsedSlot = (cursorBytes + SLOT_BYTES - 1) / SLOT_BYTES
            allocationStamps.fill(0, firstDeadSlot, lastUsedSlot)
            allocationKinds.fill(0, firstDeadSlot, lastUsedSlot)
        }
        cursorBytes = restoredCursor
        markDepth--
    }

    internal fun allocateVector(kind: Kind): Long {
        checkAccess()
        val offset = align16(cursorBytes)
        val required = offset + SLOT_BYTES
        ensureCapacity(required)
        val stamp = nextStamp()
        allocationStamps[offset / SLOT_BYTES] = stamp
        allocationKinds[offset / SLOT_BYTES] = (kind.ordinal + 1).toByte()
        cursorBytes = required
        highWaterBytes = max(highWaterBytes, cursorBytes)
        allocationCount++
        if (debugChecks) {
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

    private fun validate(token: Long, component: Int, expectedKind: Kind): Int {
        checkAccess()
        require(component in 0 until expectedKind.componentCount) {
            "component $component outside ${expectedKind.name}"
        }
        val offset = unpackOffset(token)
        if (!debugChecks) return offset
        val stamp = unpackStamp(token)
        check(stamp > resetFloorStamp) {
            "${expectedKind.name} handle was used after FrameArena.reset()"
        }
        check(offset >= 0 && offset + SLOT_BYTES <= cursorBytes && offset % SLOT_BYTES == 0) {
            "${expectedKind.name} handle points outside live FrameArena storage: offset=$offset used=$cursorBytes"
        }
        val currentStamp = allocationStamps[offset / SLOT_BYTES]
        check(currentStamp == stamp) {
            "${expectedKind.name} handle is stale or belongs to another FrameArena " +
                "(handleStamp=$stamp, slotStamp=$currentStamp, offset=$offset)"
        }
        val currentKind = allocationKinds[offset / SLOT_BYTES].toInt() - 1
        check(currentKind == expectedKind.ordinal) {
            "Arena handle type mismatch: expected ${expectedKind.name}, slot contains " +
                Kind.entries.getOrNull(currentKind)?.name
        }
        return offset
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
        allocationStamps = allocationStamps.copyOf(newCapacity / SLOT_BYTES)
        allocationKinds = allocationKinds.copyOf(newCapacity / SLOT_BYTES)
        oldOwner.close()
        growthCount++
    }

    private fun checkAccess() {
        check(!closed) { "FrameArena is closed" }
        if (debugChecks) {
            val current = Thread.currentThread()
            check(current.threadId() == ownerThreadId) {
                "FrameArena owned by '$ownerThreadName' was accessed from '${current.name}'"
            }
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
        private const val SLOT_BYTES = 16
        private const val ALIGNMENT_BYTES = 16
        private val globalStamp = AtomicInteger(0)

        private fun nextStamp(): Int = globalStamp.incrementAndGet().also {
            check(it > 0) { "FrameArena allocation stamp space exhausted" }
        }

        private fun pack(stamp: Int, offset: Int): Long =
            (stamp.toLong() shl 32) or (offset.toLong() and 0xffff_ffffL)

        private fun unpackStamp(token: Long): Int = (token ushr 32).toInt()
        private fun unpackOffset(token: Long): Int = token.toInt()
        private fun align16(value: Int): Int = (value + 15) and -16
    }
}

@JvmInline
value class ArenaMark internal constructor(internal val token: Long)
