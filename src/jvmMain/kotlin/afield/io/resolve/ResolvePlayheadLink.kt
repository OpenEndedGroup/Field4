package afield.io.resolve

import afield.app.diagnostics.logging.lprintln
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Latest-only playhead following. All Resolve calls run on one daemon, never on the playback thread. */
class ResolvePlayheadLink internal constructor(
    private val read: () -> ResolvePlayhead,
    private val write: (Double) -> ResolvePlayhead,
    intervalSeconds: Double = .25,
    private val retrySeconds: Double = 1.0,
    private val verifySeconds: Double = 1.0,
    private val clock: () -> Long = System::nanoTime,
    automatic: Boolean = true,
) : AutoCloseable {
    /** [timeline] must already be open in Resolve; this link never switches its current timeline. */
    constructor(connection: ResolveConnection, timeline: String? = null, intervalSeconds: Double = .25,
                retrySeconds: Double = 1.0, verifySeconds: Double = 1.0) : this(
        read = { if (timeline == null) connection.playhead() else connection.playhead(timeline) },
        write = { connection.setPlayheadSeconds(it, timeline) }, intervalSeconds = intervalSeconds,
        retrySeconds = retrySeconds, verifySeconds = verifySeconds)

    /** Immutable evidence of the latest operation; target/enabled/busy are refreshed on access. */
    data class Status(val enabled: Boolean = false, val targetSeconds: Double? = null,
                      val observed: ResolvePlayhead? = null, val busy: Boolean = false,
                      val operation: String = "idle", val error: String? = null,
                      val writes: Long = 0, val reads: Long = 0, val settled: Boolean = false)

    private val target = AtomicReference<Double?>()
    private val pendingGet = AtomicReference<CompletableFuture<ResolvePlayhead>?>()
    private val reads = ConcurrentHashMap.newKeySet<CompletableFuture<ResolvePlayhead>>()
    private val worker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "resolve-playhead-link").apply { isDaemon = true }
    }
    @Volatile private var closed = false
    @Volatile private var busy = false
    @Volatile private var epoch = 0L
    @Volatile private var evidence = Status()
    private var lastTarget: Double? = null
    private var verifyAt = 0L
    private var retryAt = 0L
    private var lastLoggedError: String? = null

    /** Enable following. Disabling prevents new writes; an already-running Resolve call may finish. */
    @Volatile var enabled = false
        set(value) = synchronized(this) {
            check(!closed || !value) { "Resolve playhead link is closed" }
            if (field != value) { epoch++; field = value }
        }

    val status: Status get() = evidence.copy(enabled = enabled, targetSeconds = target.get(), busy = busy,
        settled = target.get()?.let { matches(evidence.observed, it) } == true && evidence.error == null)

    private val task = run {
        require(intervalSeconds.isFinite() && intervalSeconds >= .05)
        require(retrySeconds.isFinite() && retrySeconds > 0 && verifySeconds.isFinite() && verifySeconds > 0)
        if (automatic) worker.scheduleWithFixedDelay({ step() }, 0,
            (intervalSeconds * 1e9).toLong(), TimeUnit.NANOSECONDS) else null
    }

    /** Offer source-video seconds (null while unloaded). Replaces the previous target without enqueueing work. */
    fun offer(seconds: Double?) {
        require(seconds == null || seconds.isFinite() && seconds >= 0)
        if (!closed && target.get() != seconds) target.set(seconds)
    }

    /** Disable following and asynchronously read Resolve. Apply the result to Field on its own loop. */
    fun getPlayhead(): CompletableFuture<ResolvePlayhead> = synchronized(this) {
        check(!closed) { "Resolve playhead link is closed" }
        enabled = false
        val result = CompletableFuture<ResolvePlayhead>()
        reads.add(result)
        result.whenComplete { _, _ -> reads.remove(result) }
        pendingGet.getAndSet(result)?.completeExceptionally(CancellationException("Replaced by a newer Get"))
        result
    }

    /** The scheduler is single-threaded; tests step the same implementation with a controlled clock/transport. */
    internal fun step() {
        if (closed) return
        val request = pendingGet.getAndSet(null)
        val version = epoch
        val desired = target.get()
        val now = clock()
        if (request == null && (!enabled || desired == null || now < retryAt ||
                desired == lastTarget && evidence.error == null && now < verifyAt)) return
        busy = true
        var operation = if (request != null) "get" else "set"
        try {
            var p: ResolvePlayhead
            var wrote = false
            var readPosition = false
            if (request != null || desired == lastTarget && evidence.error == null) {
                operation = if (request != null) "get" else "verify"
                p = read()
                readPosition = true
                if (request == null && !matches(p, desired!!) && enabled && epoch == version && !closed) {
                    operation = "set"
                    p = write(desired)
                    wrote = true
                }
            } else {
                if (closed || !enabled || epoch != version) return
                p = write(desired!!)
                wrote = true
            }
            if (!closed && epoch == version) {
                evidence = evidence.copy(observed = p, operation = operation, error = null,
                    writes = evidence.writes + if (wrote) 1 else 0, reads = evidence.reads + if (readPosition) 1 else 0)
                if (lastLoggedError != null) lprintln("info,resolve.playhead", "Recovered: ${p.timeline} ${p.timecode}")
                lastLoggedError = null
                lastTarget = if (request == null && matches(p, desired!!)) desired else null
                verifyAt = clock() + (verifySeconds * 1e9).toLong()
                retryAt = 0L
            }
            request?.complete(p)
        } catch (failure: Exception) {
            if (!closed && epoch == version) {
                val message = failure.message ?: failure.javaClass.simpleName
                evidence = evidence.copy(operation = operation, error = message)
                retryAt = clock() + (retrySeconds * 1e9).toLong()
                if (message != lastLoggedError) lprintln("warning,resolve.playhead",
                    "$operation failed; ${if (request == null) "latest target retained for retry" else "press Get to retry"}: $message")
                lastLoggedError = message
            }
            request?.completeExceptionally(failure)
        } finally { busy = false }
    }

    /** Close without interrupting an in-flight worker exchange or blocking Field's loop. */
    override fun close() {
        synchronized(this) { closed = true; enabled = false; epoch++ }
        target.set(null)
        pendingGet.set(null)
        reads.toList().forEach { it.completeExceptionally(CancellationException("Resolve playhead link closed")) }
        task?.cancel(false)
        worker.shutdown()
    }

    private fun matches(p: ResolvePlayhead?, seconds: Double) = p != null && p.fps.isFinite() && p.fps > 0 &&
        abs(p.seconds - seconds) <= .5 / p.fps + 1e-9
}
