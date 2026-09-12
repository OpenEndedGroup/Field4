package afield

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
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
 * Box-owned continuation instrumentation, shared by Runloop and SimulationClock. A continuation's CPU/heap counters exclude its suspended lifetime.
 * No timers or allocations on the disabled dispatch path. Export copies happen on request, not per
 * resume. A process permits one capture at a time; document isolation also applies to stop/snapshot.
 */
object CoroutineProfiles {
    @Volatile private var active: CoroutineCapture? = null
    private val captures = LinkedHashMap<String, CoroutineCapture>()
    private val expiry by lazy { java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "field-profile-expiry").apply { isDaemon = true }
    } }
    private val measuring = ThreadLocal<ProfileMeasurement?>()

    internal fun captureFor(origin: ProfileOrigin): CoroutineCapture? =
        active?.takeIf { it.document == origin.document && System.nanoTime() < it.deadlineNs }

    fun resume(context: CoroutineContext, task: Runnable) {
        val capture = active
        val origin = context[ProfileOrigin]
        if (capture == null || origin == null || origin.document != capture.document) { task.run(); return }
        val now = System.nanoTime()
        if (now >= capture.deadlineNs) { finish(capture, now); task.run(); return }
        val lane = capture.beginResume(context[Job], origin, now, context[CoroutineName]?.name.orEmpty())
        if (lane < 0) { task.run(); return }
        val token = beginMeasurement(capture, lane, origin)
        try { task.run() } finally { endMeasurement(token) }
    }

    @PublishedApi internal fun beginScope(requestedOrigin: ProfileOrigin?, name: String): ProfileMeasurement? {
        val capture = active ?: return null
        val origin = requestedOrigin ?: measuring.get()?.origin ?: return null
        if (capture.document != origin.document || System.nanoTime() >= capture.deadlineNs) return null
        require(name.isNotBlank() && name.length <= 120) { "Scope names must have 1–120 characters" }
        val lane = capture.beginScope(origin, name, System.nanoTime())
        return if (lane < 0) null else beginMeasurement(capture, lane, origin)
    }

    private fun beginMeasurement(capture: CoroutineCapture, lane: Int, origin: ProfileOrigin): ProfileMeasurement {
        val parent = measuring.get()?.takeIf { it.capture === capture }
        // Wall encloses the CPU/heap counter reads. Self totals subtract nested measured children.
        val token = ProfileMeasurement(capture, lane, origin, parent, System.nanoTime(), ProfileCounters.cpu(),
            ProfileCounters.allocated(), Thread.currentThread().threadId())
        measuring.set(token)
        return token
    }

    @PublishedApi internal fun endMeasurement(token: ProfileMeasurement?) {
        if (token == null) return
        val allocated = ProfileCounters.delta(ProfileCounters.allocated(), token.allocated)
        val cpu = ProfileCounters.delta(ProfileCounters.cpu(), token.cpu)
        val end = System.nanoTime()
        val wall = end - token.start
        measuring.set(token.parent)
        token.parent?.let { parent ->
            parent.childWall += wall
            parent.childCpu = if (cpu < 0 || parent.childCpu < 0) -1 else parent.childCpu + cpu
            parent.childAllocated = if (allocated < 0 || parent.childAllocated < 0) -1 else parent.childAllocated + allocated
        }
        var depth = 0
        var parent = token.parent
        while (parent != null) { depth++; parent = parent.parent }
        token.capture.endResume(token.lane, Runloop.runLoop.frame, token.start, end, cpu, allocated, token.thread,
            (wall-token.childWall).coerceAtLeast(0), ProfileCounters.delta(cpu, token.childCpu),
            ProfileCounters.delta(allocated, token.childAllocated), depth)
        if (end >= token.capture.deadlineNs) finish(token.capture, end)
    }

    @Synchronized fun request(document: String, request: CoroutineProfileRequest): CoroutineProfileSnapshot {
        val now = System.nanoTime()
        active?.takeIf { now >= it.deadlineNs }?.let { finish(it, now) }
        when (request.action) {
            "start" -> {
                require(request.seconds.isFinite() && request.seconds in 1.0..30.0) { "Capture length must be 1–30 seconds" }
                check(active == null || active?.document == document) { "Another document is recording; finish its capture first" }
                active?.let { finish(it, now) }
                ProfileCounters.bean // Initialise/enable counters before the first measured dispatch.
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
        return c.snapshot(after, System.nanoTime(), stackAfter = if (request.captureId == c.id) request.stackAfter else -1,
            frameAfter = if (request.captureId == c.id) request.frameAfter else -1)
    }

    @Synchronized private fun finish(capture: CoroutineCapture, now: Long) {
        if (active !== capture) return
        capture.stop(now)
        active = null
    }

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
    private val named = IdentityHashMap<ProfileOrigin, MutableMap<Pair<String, String>, Int>>()
    private val frameRecords = ProfileFrames()
    private val gcBefore = ProfileCounters.gc()
    private var gcAfter: Pair<Long, Long>? = null
    private val lastEnds = LongArray(maximumLanes)
    private val laneIds = IntArray(capacity)
    private val frames = LongArray(capacity)
    private val starts = LongArray(capacity)
    private val walls = LongArray(capacity)
    private val cpus = LongArray(capacity)
    private val allocations = LongArray(capacity)
    private val threads = LongArray(capacity)
    private val selfWalls = LongArray(capacity)
    private val selfCpus = LongArray(capacity)
    private val selfAllocations = LongArray(capacity)
    private val depths = IntArray(capacity)

    init { stackCapture?.align(startNs) }

    @Synchronized fun beginResume(job: Job?, origin: ProfileOrigin, now: Long, label: String = ""): Int {
        val id = lane(job, origin, now, label)
        if (id >= 0) inFlight++
        return id
    }

    @Synchronized fun endResume(lane: Int, frame: Long, start: Long, end: Long, cpu: Long, allocation: Long, thread: Long = -1,
        selfWall: Long = end-start, selfCpu: Long = cpu, selfAllocation: Long = allocation, depth: Int = 0) {
        record(lane, frame, start, end, cpu, allocation, thread, selfWall, selfCpu, selfAllocation, depth)
        inFlight--
        if (stopNs >= 0 && inFlight == 0) {
            stopNs = maxOf(stopNs, end)
            releaseJobs()
        }
    }

    @Synchronized fun lane(job: Job?, origin: ProfileOrigin, now: Long, label: String = ""): Int {
        if (stopNs >= 0) return -1
        (if (job == null) origins[origin] else jobs[job])?.let { return it }
        if (lanes.size >= maximumLanes) { rejected++; return -1 }
        val id = lanes.size
        stackCapture?.register(origin)
        val hash = MessageDigest.getInstance("SHA-256").digest(origin.source.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
        lanes += CoroutineProfileLane(id, origin.box.id, origin.box.name, origin.strand, hash, "observed", (now-startNs)/1e6, (now-startNs)/1e6, label = label.take(120))
        lastEnds[id] = now
        if (job != null) jobs[job] = id else origins[origin] = id
        return id
    }

    @Synchronized fun namedLane(origin: ProfileOrigin, name: String, kind: String, now: Long): Int {
        if (stopNs >= 0) return -1
        val names = named.getOrPut(origin) { HashMap() }
        return names.getOrPut(name to kind) {
            if (lanes.size >= maximumLanes) { rejected++; return -1 }
            val id = lanes.size
            stackCapture?.register(origin)
            val hash = MessageDigest.getInstance("SHA-256").digest(origin.source.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
            lanes += CoroutineProfileLane(id, origin.box.id, origin.box.name, origin.strand, hash,
                "observed", (now-startNs)/1e6, (now-startNs)/1e6, name, kind)
            lastEnds[id] = now
            id
        }
    }

    @Synchronized fun beginScope(origin: ProfileOrigin, name: String, now: Long): Int {
        val id = namedLane(origin, name, "scope", now)
        if (id >= 0) inFlight++
        return id
    }

    @Synchronized fun frame(lane: Int, start: Long, end: Long, cpu: Long, allocation: Long, target: Double, guide: Double) {
        if (stopNs >= 0 || lane < 0) return
        frameRecords.add(lane, start, end, cpu, allocation, target, guide)
        lastEnds[lane] = end
    }

    @Synchronized fun record(lane: Int, frame: Long, start: Long, end: Long, cpu: Long, allocation: Long, thread: Long = -1,
        selfWall: Long = end-start, selfCpu: Long = cpu, selfAllocation: Long = allocation, depth: Int = 0) {
        if (stopNs >= 0 && inFlight == 0) return
        val at = (sequence % capacity).toInt()
        laneIds[at] = lane; frames[at] = frame; starts[at] = start
        walls[at] = (end-start).coerceAtLeast(0); cpus[at] = cpu; allocations[at] = allocation
        threads[at] = thread
        selfWalls[at] = selfWall; selfCpus[at] = selfCpu; selfAllocations[at] = selfAllocation; depths[at] = depth
        lastEnds[lane] = maxOf(lastEnds[lane], end)
        sequence++
    }

    @Synchronized fun stop(now: Long) {
        if (stopNs >= 0) return
        stopNs = now
        gcAfter = ProfileCounters.gc()
        stackCapture?.finish(minOf(now, deadlineNs))
        if (inFlight == 0) releaseJobs()
    }

    private fun releaseJobs() {
        updateLaneStates()
        jobs.clear()
        named.clear()
        origins.clear() // do not retain live object graphs in a saved capture
    }

    private fun updateLaneStates() {
        for ((job, id) in jobs) lanes[id] = lanes[id].copy(state = when {
            job.isCancelled -> "cancelled"
            job.isCompleted -> "completed"
            else -> "active"
        })
    }

    @Synchronized fun snapshot(after: Long, now: Long, limit: Int = 4096, stackAfter: Int = -1, frameAfter: Long = -1): CoroutineProfileSnapshot {
        val recording = stopNs < 0 || inFlight > 0
        if (recording) updateLaneStates()
        val oldest = (sequence-capacity).coerceAtLeast(0)
        val first = maxOf(oldest, after+1).coerceAtMost(sequence)
        val end = minOf(sequence, first+limit)
        val records = (first until end).map { seq ->
            val i = (seq % capacity).toInt()
            CoroutineProfileSpan(seq, laneIds[i], frames[i], (starts[i]-startNs)/1e6, walls[i]/1e6,
                if (cpus[i] < 0) -1.0 else cpus[i]/1e6, allocations[i], threads[i], selfWalls[i]/1e6,
                if (selfCpus[i] < 0) -1.0 else selfCpus[i]/1e6, selfAllocations[i], depths[i])
        }
        val gc = gcAfter ?: ProfileCounters.gc()
        val snapshot = CoroutineProfileSnapshot(captureId = id, recording = recording,
            elapsedMs = ((if (recording) now else stopNs)-startNs)/1e6,
            durationMs = (deadlineNs-startNs)/1e6, oldestSequence = oldest, nextSequence = sequence,
            droppedSpans = oldest+rejected, lanes = lanes.map { it.copy(lastMs = (lastEnds[it.id]-startNs)/1e6) }, spans = records,
            frameRecords = frameRecords.snapshot(frameAfter, startNs), nextFrameSequence = frameRecords.sequence,
            oldestFrameSequence = frameRecords.oldest, gcCollections = gc.first-gcBefore.first,
            gcCollectionMs = gc.second-gcBefore.second)
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
