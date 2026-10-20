@file:afield.runtime.completion.CompletionCategory("Animation")

package afield.animation.mocap

import afield.runtime.lifecycle.ReleasePolicy
import afield.runtime.lifecycle.RunScope
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

data class MotionCommand(val revision: Long, val operation: MotionOperation, val requestedTime: Double, val payload: JSONObject)
/** Null time/index means accepted for scheduling; a later update carries the actual applied event. */
data class MotionApplied(val appliedTime: Double?, val frameIndex: Long?, val details: JSONObject = JSONObject())
data class MotionUpdate(val frames: List<MotionFrame>, val events: List<JSONObject> = emptyList())

/**
 * Blocking backend boundary, invoked only by MotionSession's owned daemon thread.
 * Implementations must return bounded batches, honor explicit session times, and close promptly.
 * An installed Python package alone must never advertise a working capability.
 */
interface MotionBackend : AutoCloseable {
    val skeleton: MotionSkeleton
    val capabilities: MotionCapabilities
    fun command(command: MotionCommand): MotionApplied
    fun advance(seconds: Double): MotionUpdate
}

/**
 * One small optional motion facade. No inference or simulator work runs on Field's frame thread.
 * Commands are bounded; path/target replacements coalesce only before the next ordered event.
 * Subscriptions run on the worker thread and must be short; rendering reads [buffer] snapshots.
 * Construction in a box registers with its existing RunScope (stop/re-run/document close).
 * Live ARDY/CLoSD drivers require separately validated model environments; [replay] needs neither.
 */
class MotionSession(private val backend: MotionBackend, val queueCapacity: Int = 32,
    val buffer: MotionBuffer = MotionBuffer()) : AutoCloseable {
    val skeleton get() = backend.skeleton
    val capabilities get() = backend.capabilities
    @Volatile var latest: MotionFrame? = null; private set
    @Volatile var failure: Throwable? = null; private set
    @Volatile var closed = false; private set
    private var revision = 0L
    private data class Pending(val operation: MotionOperation, val command: MotionCommand?, val seconds: Double?,
        val future: CompletableFuture<Any?>)
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = Object()
    private val queue = ArrayDeque<Pending>()
    private val subscribers = CopyOnWriteArrayList<(MotionFrame) -> Unit>()
    private var recorder: MotionRecorder? = null
    private val edits = LinkedHashMap<Long, Long>()
    private val delaySamples = ArrayDeque<Double>()
    private val backendClosed = AtomicBoolean()
    private var active: CompletableFuture<Any?>? = null
    private val worker: Thread
    init {
        require(queueCapacity in 1..1024)
        worker = Thread({ work() }, "field-motion-${capabilities.backend}").apply { isDaemon = true; start() }
        RunScope.current()?.own(this, ReleasePolicy.RUNNING)
    }
    fun subscribe(listener: (MotionFrame) -> Unit): AutoCloseable = synchronized(lock) {
        check(!closed)
        subscribers.add(listener)
        AutoCloseable { subscribers.remove(listener) }.also { RunScope.current()?.own(it, ReleasePolicy.RUNNING) }
    }
    fun record(directory: Path, configuration: JSONObject): MotionRecorder = synchronized(lock) {
        check(!closed && recorder == null) { "Session closed or already recording" }
        MotionRecorder(directory, skeleton, configuration).also { recorder = it }
    }
    fun setPrompt(text: String, effectiveTime: Double) = submit(MotionOperation.PROMPT, effectiveTime, JSONObject().put("text", text))
    fun setRootPlan(points: List<TimedRootPoint>, effectiveTime: Double): CompletableFuture<MotionApplied?> {
        require(points.size <= 10000)
        points.zipWithNext().forEach { (a, b) -> require(b.time > a.time) }
        return submit(MotionOperation.ROOT_PLAN, effectiveTime, JSONObject().put("points", JSONArray(points.map { it.json() })))
    }
    fun setJointTargets(targets: List<JointTarget>, effectiveTime: Double): CompletableFuture<MotionApplied?> {
        capabilities.require(MotionOperation.JOINT_TARGETS)
        require(targets.size <= 10000)
        targets.forEach { skeleton.joint(it.joint) }
        return submit(MotionOperation.JOINT_TARGETS, effectiveTime, JSONObject().put("targets", JSONArray(targets.map { it.json() })))
    }
    fun attach(attachment: MotionAttachment, effectiveTime: Double): CompletableFuture<MotionApplied?> {
        capabilities.require(MotionOperation.ATTACH)
        skeleton.body(attachment.body)
        return submit(MotionOperation.ATTACH, effectiveTime, attachment.json())
    }
    fun updateAttachment(attachment: MotionAttachment, effectiveTime: Double): CompletableFuture<MotionApplied?> {
        capabilities.require(MotionOperation.UPDATE_ATTACHMENT)
        skeleton.body(attachment.body)
        return submit(MotionOperation.UPDATE_ATTACHMENT, effectiveTime, attachment.json())
    }
    fun detach(id: String, effectiveTime: Double) = submit(MotionOperation.DETACH, effectiveTime, JSONObject().put("id", id))
    /** Point is body-local metres; impulse is a world vector in N s. */
    fun applyImpulse(body: String, localPoint: List<Double>, impulse: List<Double>, effectiveTime: Double): CompletableFuture<MotionApplied?> {
        capabilities.require(MotionOperation.IMPULSE)
        skeleton.body(body)
        require(localPoint.size == 3 && impulse.size == 3 && (localPoint + impulse).all(Double::isFinite))
        return submit(MotionOperation.IMPULSE, effectiveTime, JSONObject().put("body", body).put("localPoint", localPoint).put("impulse", impulse))
    }
    fun reset(effectiveTime: Double) = submit(MotionOperation.RESET, effectiveTime, JSONObject())
    fun advance(seconds: Double): CompletableFuture<MotionUpdate> {
        capabilities.require(MotionOperation.ADVANCE)
        require(seconds.isFinite() && seconds > 0 && seconds <= 10)
        val future = CompletableFuture<Any?>()
        synchronized(lock) { enqueue(Pending(MotionOperation.ADVANCE, null, seconds, future)) }
        return future.thenApply { it as MotionUpdate }
    }
    /** Call when a renderer actually displays a revision; timings do not treat receipt as visibility. */
    fun markVisible(frame: MotionFrame): Double? = synchronized(lock) {
        val submitted = edits[frame.revision] ?: return null
        val ms = (System.nanoTime() - submitted) / 1e6
        edits.keys.filter { it <= frame.revision }.forEach(edits::remove)
        delaySamples.addLast(ms)
        if (delaySamples.size > 1024) delaySamples.removeFirst()
        recorder?.event(JSONObject().put("type", "visible").put("revision", frame.revision).put("frameIndex", frame.index).put("delayMs", ms))
        ms
    }
    fun visibleDelayPercentile(fraction: Double): Double? = synchronized(lock) {
        require(fraction in 0.0..1.0)
        val sorted = delaySamples.sorted()
        if (sorted.isEmpty()) null else sorted[(fraction * sorted.lastIndex).toInt()]
    }
    fun queued(): Int = synchronized(lock) { queue.size }
    private fun submit(operation: MotionOperation, time: Double, payload: JSONObject): CompletableFuture<MotionApplied?> {
        capabilities.require(operation)
        require(time.isFinite() && time >= 0)
        synchronized(lock) {
            check(!closed)
            val future = CompletableFuture<Any?>()
            val command = MotionCommand(++revision, operation, time, JSONObject(payload.toString()))
            val coalescible = setOf(MotionOperation.ROOT_PLAN, MotionOperation.JOINT_TARGETS)
            if (operation in coalescible) {
                val iterator = queue.descendingIterator()
                while (iterator.hasNext()) {
                    val old = iterator.next()
                    if (old.operation !in coalescible) break
                    if (old.operation == operation) {
                        iterator.remove(); old.future.complete(null)
                        recorder?.event(commandEvent(old.command!!, "superseded"))
                        edits.remove(old.command!!.revision)
                    }
                }
            }
            enqueue(Pending(operation, command, null, future))
            edits[command.revision] = System.nanoTime()
            // Bounded diagnostics even if a backend never makes a command visible.
            if (edits.size > 1024) edits.remove(edits.keys.first())
            recorder?.event(commandEvent(command, "requested"))
            return future.thenApply { it as MotionApplied? }
        }
    }
    private fun enqueue(pending: Pending) {
        check(!closed)
        check(queue.size < queueCapacity) { "Motion queue full ($queueCapacity); wait for advance before submitting more work" }
        queue.addLast(pending); lock.notifyAll()
    }
    private fun commandEvent(command: MotionCommand, type: String) = JSONObject().put("type", type)
        .put("operation", command.operation.name).put("revision", command.revision).put("requestedTime", command.requestedTime)
        .put("payload", command.payload)
    private fun work() {
        try {
            while (true) {
                val pending = synchronized(lock) {
                    while (queue.isEmpty() && !closed) lock.wait()
                    if (closed) null else queue.removeFirst().also { active = it.future }
                } ?: break
                try {
                    val c = pending.command
                    if (c != null) {
                        val applied = backend.command(c)
                        if (applied.appliedTime != null) {
                            require(applied.appliedTime.isFinite() && applied.appliedTime >= c.requestedTime && applied.frameIndex != null && applied.frameIndex >= 0)
                        } else require(applied.frameIndex == null)
                        synchronized(lock) {
                            recorder?.event(commandEvent(c, if (applied.appliedTime == null) "scheduled" else "applied")
                                .put("appliedTime", applied.appliedTime ?: JSONObject.NULL)
                                .put("frameIndex", applied.frameIndex ?: JSONObject.NULL).put("details", applied.details))
                        }
                        pending.future.complete(applied)
                    } else {
                        val update = backend.advance(pending.seconds!!)
                        require(update.frames.size <= 10000) { "Backend returned an unbounded batch" }
                        synchronized(lock) { update.events.forEach { recorder?.event(it) } }
                        for (frame in update.frames) {
                            check(frame.executed.size == skeleton.size)
                            synchronized(lock) {
                                if (closed) break
                                recorder?.append(frame); buffer.add(frame); latest = frame
                            }
                            subscribers.forEach { listener -> runCatching { listener(frame) }.onFailure { failure = it } }
                        }
                        pending.future.complete(update)
                    }
                } catch (e: Throwable) {
                    pending.future.completeExceptionally(e)
                    throw e
                } finally {
                    synchronized(lock) { active = null }
                }
            }
        } catch (e: InterruptedException) {
            if (!closed) failure = e
        } catch (e: Throwable) {
            failure = e
        } finally {
            synchronized(lock) {
                closed = true
                queue.forEach { it.future.completeExceptionally(failure ?: IllegalStateException("Motion session closed")) }
                queue.clear(); subscribers.clear(); edits.clear()
                failure?.let { error -> runCatching { recorder?.event(JSONObject().put("type", "workerFailure").put("message", error.toString())) } }
                runCatching { recorder?.close() }.onFailure { if (failure == null) failure = it }
            }
            closeBackend()
        }
    }
    /** Nonblocking on Field's runloop; the backend must make its close unblock pending I/O. */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true; subscribers.clear(); lock.notifyAll()
            active?.completeExceptionally(IllegalStateException("Motion session closed"))
            queue.forEach { it.future.completeExceptionally(IllegalStateException("Motion session closed")) }
            queue.clear()
        }
        worker.interrupt()
        Thread({ closeBackend() }, "field-motion-close").apply { isDaemon = true; start() }
    }
    private fun closeBackend() {
        if (backendClosed.compareAndSet(false, true)) runCatching { backend.close() }.onFailure { if (failure == null) failure = it }
    }
    companion object {
        fun replay(clip: MotionClip) = MotionSession(ReplayBackend(clip))
    }
}

private class ReplayBackend(private val clip: MotionClip) : MotionBackend {
    override val skeleton = clip.skeleton
    override val capabilities = MotionCapabilities("recording.replay", setOf(MotionOperation.ADVANCE),
        JSONObject().put("resimulation", false))
    private var next = 0
    private var time = clip.startTime
    override fun command(command: MotionCommand): MotionApplied = error("Recording playback does not change intentions or re-simulate")
    override fun advance(seconds: Double): MotionUpdate {
        time += seconds
        val frames = ArrayList<MotionFrame>()
        while (next < clip.frames.size && clip.frames[next].time <= time) frames.add(clip.frames[next++])
        return MotionUpdate(frames)
    }
    override fun close() = Unit
}
