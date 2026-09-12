package afield

import com.sun.management.ThreadMXBean
import kotlinx.coroutines.currentCoroutineContext
import java.lang.management.ManagementFactory
import kotlin.coroutines.CoroutineContext

/** Shared counter capability checks for coroutine resumes, named scopes and frame boundaries. */
internal object ProfileCounters {
    val bean: ThreadMXBean? by lazy {
        (ManagementFactory.getThreadMXBean() as? ThreadMXBean)?.apply {
            if (isThreadCpuTimeSupported) isThreadCpuTimeEnabled = true
            if (isThreadAllocatedMemorySupported) isThreadAllocatedMemoryEnabled = true
        }
    }
    fun cpu(): Long = bean?.takeIf { it.isThreadCpuTimeSupported }?.currentThreadCpuTime ?: -1L
    fun allocated(): Long = bean?.takeIf { it.isThreadAllocatedMemorySupported }
        ?.getThreadAllocatedBytes(Thread.currentThread().threadId()) ?: -1L
    fun delta(end: Long, start: Long): Long = if (end < 0 || start < 0) -1L else (end - start).coerceAtLeast(0)
    fun gc(): Pair<Long, Long> = ManagementFactory.getGarbageCollectorMXBeans().let { beans ->
        beans.sumOf { it.collectionCount.coerceAtLeast(0) } to beans.sumOf { it.collectionTime.coerceAtLeast(0) }
    }
}

/** Internal inclusive measurement; children are subtracted only from its self counters. */
@PublishedApi
internal class ProfileMeasurement(
    val capture: CoroutineCapture, val lane: Int, val origin: ProfileOrigin, val parent: ProfileMeasurement?,
    val start: Long, val cpu: Long, val allocated: Long, val thread: Long
) {
    var childWall = 0L
    var childCpu = 0L
    var childAllocated = 0L
}

/**
 * Explicit frame boundaries for the existing Profiling panel. This object does not start a capture.
 * Press Record in the panel, then call [beginFrame] once per application frame, on a stable thread.
 * Intervals include time between boxes and waits; CPU/heap counters cover that thread only.
 * Use [measure] for named inclusive stages. No counter reads or sample allocation while recording is off.
 */
class FrameProfile(
    private val context: CoroutineContext,
    val name: String = "Frame",
    val targetFps: Double = 60.0,
    val allocationKiB: Double = 512.0
) : AutoCloseable {
    @PublishedApi internal val origin = context[ProfileOrigin]
    private var previous: CoroutineCapture? = null
    private var start = 0L
    private var cpu = -1L
    private var allocated = -1L
    private var thread = -1L
    private var lane = -1
    init {
        require(name.isNotBlank() && name.length <= 120)
        require(targetFps.isFinite() && targetFps > 0)
        require(allocationKiB.isFinite() && allocationKiB >= 0)
    }

    /** The first boundary in each capture establishes a baseline and is not a completed frame. */
    fun beginFrame() {
        val c = origin?.let { CoroutineProfiles.captureFor(it) }
        if (c == null) { previous = null; return }
        val currentThread = Thread.currentThread().threadId()
        val now = System.nanoTime()
        val currentCpu = ProfileCounters.cpu()
        val currentAllocation = ProfileCounters.allocated()
        if (previous === c) {
            check(thread == currentThread) { "FrameProfile moved threads; create one per frame thread" }
            c.frame(lane, start, now, ProfileCounters.delta(currentCpu, cpu),
                ProfileCounters.delta(currentAllocation, allocated), 1000.0 / targetFps, allocationKiB * 1024)
        } else {
            lane = c.namedLane(origin, name, "frame", now)
        }
        previous = c; thread = currentThread; start = now; cpu = currentCpu; allocated = currentAllocation
    }

    /** A synchronous stage; do not suspend inside it. Nested scopes retain inclusive and self totals. */
    inline fun <T> measure(name: String, block: () -> T): T {
        val token = CoroutineProfiles.beginScope(origin, name)
        try { return block() } finally { CoroutineProfiles.endMeasurement(token) }
    }

    /** Discard an incomplete last interval. A stop halfway through a frame is not a full-frame sample. */
    override fun close() { previous = null }
}

/** Create frame instrumentation inheriting the source and document of this ordinary box coroutine. */
suspend fun frameProfile(name: String = "Frame", targetFps: Double = 60.0, allocationKiB: Double = 512.0): FrameProfile =
    FrameProfile(currentCoroutineContext(), name, targetFps, allocationKiB)

/** A synchronous named stage inside any profiled box or simulation coroutine. */
inline fun <T> profileScope(name: String, block: () -> T): T {
    val token = CoroutineProfiles.beginScope(null, name)
    try { return block() } finally { CoroutineProfiles.endMeasurement(token) }
}

/** Bounded frame records, independent of the continuation ring and paged over the same capture RPC. */
internal class ProfileFrames(private val capacity: Int = 8192) {
    private val lanes = IntArray(capacity)
    private val starts = LongArray(capacity)
    private val walls = LongArray(capacity)
    private val cpus = LongArray(capacity)
    private val allocations = LongArray(capacity)
    private val targets = DoubleArray(capacity)
    private val guides = DoubleArray(capacity)
    var sequence = 0L
        private set
    val oldest: Long get() = (sequence - capacity).coerceAtLeast(0)
    fun add(lane: Int, start: Long, end: Long, cpu: Long, allocation: Long, target: Double, guide: Double) {
        val i = (sequence++ % capacity).toInt()
        lanes[i] = lane; starts[i] = start; walls[i] = (end - start).coerceAtLeast(0)
        cpus[i] = cpu; allocations[i] = allocation; targets[i] = target; guides[i] = guide
    }
    fun snapshot(after: Long, startNs: Long): List<ProfileFrame> {
        val first = maxOf(oldest, after + 1).coerceAtMost(sequence)
        return (first until minOf(sequence, first + 1024)).map { s ->
            val i = (s % capacity).toInt()
            ProfileFrame(s, lanes[i], (starts[i]-startNs)/1e6, walls[i]/1e6,
                if (cpus[i] < 0) -1.0 else cpus[i]/1e6, allocations[i], targets[i], guides[i])
        }
    }
}
