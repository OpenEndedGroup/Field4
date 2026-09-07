package afield

import com.sun.management.ThreadMXBean
import kotlinx.coroutines.Job
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Immutable source reference captured at launch, inherited by child coroutines. No hashing when off. */
class ProfileOrigin(val box: Box, val strand: String = "code") : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ProfileOrigin>
    val document: String = box.profilingDocument()
    val source: String = box.properties["code"] as? String ?: ""
}

/**
 * Runloop-only instrumentation. A continuation's CPU/heap counters exclude its suspended lifetime.
 * No timers or allocations on the disabled dispatch path. Export copies happen on request, not per
 * resume. A process permits one capture at a time; document isolation also applies to stop/snapshot.
 */
object CoroutineProfiles {
    @Volatile private var active: CoroutineCapture? = null
    private val captures = LinkedHashMap<String, CoroutineCapture>()
    private val expiry by lazy { java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "field-profile-expiry").apply { isDaemon = true }
    } }
    private val counters: ThreadMXBean? by lazy {
        (ManagementFactory.getThreadMXBean() as? ThreadMXBean)?.apply {
            if (isThreadCpuTimeSupported) isThreadCpuTimeEnabled = true
            if (isThreadAllocatedMemorySupported) isThreadAllocatedMemoryEnabled = true
        }
    }

    fun resume(context: CoroutineContext, task: Runnable) {
        val capture = active
        if (capture == null) { task.run(); return }
        val origin = context[ProfileOrigin]
        if (origin == null || origin.document != capture.document) { task.run(); return }
        val now = System.nanoTime()
        if (now >= capture.deadlineNs) { finish(capture, now); task.run(); return }
        val lane = capture.beginResume(context[Job], origin, now)
        if (lane < 0) { task.run(); return }
        val bean = counters
        val thread = Thread.currentThread().threadId()
        // The wall interval encloses the counter reads too. Otherwise the wider CPU interval can
        // misleadingly exceed elapsed time, especially across many very short resumes.
        val began = System.nanoTime()
        val cpu = if (bean?.isThreadCpuTimeSupported == true) bean.currentThreadCpuTime else -1L
        val allocated = if (bean?.isThreadAllocatedMemorySupported == true) bean.getThreadAllocatedBytes(thread) else -1L
        try { task.run() } finally {
            val endAllocated = if (allocated >= 0) bean!!.getThreadAllocatedBytes(thread) else -1L
            val endCpu = if (cpu >= 0) bean!!.currentThreadCpuTime else -1L
            val ended = System.nanoTime()
            capture.endResume(lane, Runloop.runLoop.frame, began, ended, difference(endCpu, cpu), difference(endAllocated, allocated), thread)
            if (ended >= capture.deadlineNs) finish(capture, ended)
        }
    }

    @Synchronized fun request(document: String, request: CoroutineProfileRequest): CoroutineProfileSnapshot {
        val now = System.nanoTime()
        active?.takeIf { now >= it.deadlineNs }?.let { finish(it, now) }
        when (request.action) {
            "start" -> {
                require(request.seconds.isFinite() && request.seconds in 1.0..30.0) { "Capture length must be 1–30 seconds" }
                check(active == null || active?.document == document) { "Another document is recording; finish its capture first" }
                active?.let { finish(it, now) }
                counters // Initialise/enable counters before the first measured dispatch.
                val stacks = if (request.stacks) JvmProfileCapture() else null
                val c = CoroutineCapture(document, System.nanoTime(), (request.seconds * 1e9).toLong(), stackCapture = stacks)
                captures.remove(document)
                captures[document] = c
                while (captures.size > 4) captures.remove(captures.keys.first())
                active = c
                expiry.schedule({ finish(c, System.nanoTime()) }, (request.seconds*1000).toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            "stop" -> active?.takeIf { it.document == document }?.let { finish(it, now) }
            "snapshot" -> Unit
            else -> throw IllegalArgumentException("Unknown profiling action: ${request.action}")
        }
        val c = captures[document] ?: return CoroutineProfileSnapshot()
        val after = if (request.captureId == c.id) request.after else -1
        return c.snapshot(after, System.nanoTime(), stackAfter = if (request.captureId == c.id) request.stackAfter else -1)
    }

    @Synchronized private fun finish(capture: CoroutineCapture, now: Long) {
        if (active !== capture) return
        capture.stop(now)
        active = null
    }

    private fun difference(after: Long, before: Long) = if (after < 0 || before < 0) -1L else (after-before).coerceAtLeast(0)
}

/** Bounded primitive storage. Jobs/boxes never escape into the latched DTOs. */
internal class CoroutineCapture(
    val document: String,
    val startNs: Long,
    durationNs: Long,
    private val capacity: Int = 65_536,
    private val maximumLanes: Int = 1024,
    private val stackCapture: JvmProfileCapture? = null
) {
    val id = UUID.randomUUID().toString()
    val deadlineNs = startNs + durationNs
    private var stopNs = -1L
    private var sequence = 0L
    private var rejected = 0L
    private var inFlight = 0
    private val jobs = IdentityHashMap<Job, Int>()
    private val origins = IdentityHashMap<ProfileOrigin, Int>()
    private val lanes = ArrayList<CoroutineProfileLane>()
    private val lastEnds = LongArray(maximumLanes)
    private val laneIds = IntArray(capacity)
    private val frames = LongArray(capacity)
    private val starts = LongArray(capacity)
    private val walls = LongArray(capacity)
    private val cpus = LongArray(capacity)
    private val allocations = LongArray(capacity)
    private val threads = LongArray(capacity)

    init { stackCapture?.align(startNs) }

    @Synchronized fun beginResume(job: Job?, origin: ProfileOrigin, now: Long): Int {
        val id = lane(job, origin, now)
        if (id >= 0) inFlight++
        return id
    }

    @Synchronized fun endResume(lane: Int, frame: Long, start: Long, end: Long, cpu: Long, allocation: Long, thread: Long = -1) {
        record(lane, frame, start, end, cpu, allocation, thread)
        inFlight--
        if (stopNs >= 0 && inFlight == 0) {
            stopNs = maxOf(stopNs, end)
            releaseJobs()
        }
    }

    @Synchronized fun lane(job: Job?, origin: ProfileOrigin, now: Long): Int {
        if (stopNs >= 0) return -1
        (if (job == null) origins[origin] else jobs[job])?.let { return it }
        if (lanes.size >= maximumLanes) { rejected++; return -1 }
        val id = lanes.size
        stackCapture?.register(origin)
        val hash = MessageDigest.getInstance("SHA-256").digest(origin.source.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
        lanes += CoroutineProfileLane(id, origin.box.id, origin.box.name, origin.strand, hash, "observed", (now-startNs)/1e6, (now-startNs)/1e6)
        lastEnds[id] = now
        if (job != null) jobs[job] = id else origins[origin] = id
        return id
    }

    @Synchronized fun record(lane: Int, frame: Long, start: Long, end: Long, cpu: Long, allocation: Long, thread: Long = -1) {
        if (stopNs >= 0 && inFlight == 0) return
        val at = (sequence % capacity).toInt()
        laneIds[at] = lane; frames[at] = frame; starts[at] = start
        walls[at] = (end-start).coerceAtLeast(0); cpus[at] = cpu; allocations[at] = allocation
        threads[at] = thread
        lastEnds[lane] = maxOf(lastEnds[lane], end)
        sequence++
    }

    @Synchronized fun stop(now: Long) {
        if (stopNs >= 0) return
        stopNs = now
        stackCapture?.finish(minOf(now, deadlineNs))
        if (inFlight == 0) releaseJobs()
    }

    private fun releaseJobs() {
        updateLaneStates()
        jobs.clear()
        origins.clear() // do not retain live object graphs in a saved capture
    }

    private fun updateLaneStates() {
        for ((job, id) in jobs) lanes[id] = lanes[id].copy(state = when {
            job.isCancelled -> "cancelled"
            job.isCompleted -> "completed"
            else -> "active"
        })
    }

    @Synchronized fun snapshot(after: Long, now: Long, limit: Int = 4096, stackAfter: Int = -1): CoroutineProfileSnapshot {
        val recording = stopNs < 0 || inFlight > 0
        if (recording) updateLaneStates()
        val oldest = (sequence-capacity).coerceAtLeast(0)
        val first = maxOf(oldest, after+1).coerceAtMost(sequence)
        val end = minOf(sequence, first+limit)
        val records = (first until end).map { seq ->
            val i = (seq % capacity).toInt()
            CoroutineProfileSpan(seq, laneIds[i], frames[i], (starts[i]-startNs)/1e6, walls[i]/1e6,
                if (cpus[i] < 0) -1.0 else cpus[i]/1e6, allocations[i], threads[i])
        }
        val snapshot = CoroutineProfileSnapshot(captureId = id, recording = recording,
            elapsedMs = ((if (recording) now else stopNs)-startNs)/1e6,
            durationMs = (deadlineNs-startNs)/1e6, oldestSequence = oldest, nextSequence = sequence,
            droppedSpans = oldest+rejected, lanes = lanes.map { it.copy(lastMs = (lastEnds[it.id]-startNs)/1e6) }, spans = records)
        return stackCapture?.decorate(snapshot, stackAfter) ?: snapshot
    }
}

@RunsAt(installHandlers)
fun installCoroutineProfilingHandlers() {
    handlers["coroutine-profile"] = mutableSetOf({ message, reply ->
        val response = try {
            val request = message.payload?.let { Json.decodeFromJsonElement<CoroutineProfileRequest>(it) } ?: CoroutineProfileRequest()
            CoroutineProfiles.request(thisSessionInfo.get().filename, request)
        } catch (e: Exception) { CoroutineProfileSnapshot(ok = false, error = e.message ?: "Profiling failed") }
        reply(Message(message.replyTo ?: "coroutine-profile-result").payload(response))
        Handled.yes
    })
}
