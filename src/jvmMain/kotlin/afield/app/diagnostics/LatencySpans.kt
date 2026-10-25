package afield.app.diagnostics

import java.lang.management.ManagementFactory
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.Serializable

/**
 * Wall-clock spans for the moments a user waits on: building and bootstrapping a box's Kotlin
 * engine, compiling and evaluating a snippet, a whole `execute-all`/`launch-all` up to its verdict,
 * compiling a shader, creating a pipeline.
 *
 * Always on and cheap (one `nanoTime` pair and a ring-buffer write per span; the sites it times
 * cost milliseconds). `GET /api/perf/spans?since=<seq>` returns everything after a sequence
 * number, which is how `tools/feel_bench.py` attributes a step's client-side wall time to server
 * phases. Span start times are epoch microseconds, so they line up with the timestamps a client on
 * the same machine records.
 */
object LatencySpans {

    @Serializable
    data class Span(
        val seq: Long,
        val name: String,
        val startEpochMicros: Long,
        val durationMicros: Long,
        val box: String? = null,
        val detail: String? = null,
        val thread: String,
    )

    @Serializable
    data class Snapshot(
        val spans: List<Span>,
        val nextSeq: Long,
        val dropped: Long,
        val jvmStartEpochMillis: Long,
        val readyMillis: Long?,
        /** This clock's now, so a client can measure its offset from it rather than assume none. */
        val nowEpochMicros: Long,
    )

    private const val CAPACITY = 8192
    private val ring = arrayOfNulls<Span>(CAPACITY)
    private val seq = AtomicLong(0)
    // The wall clock read between two nanoTime reads, keeping the tightest of a few tries: read on
    // two separate lines during startup, a pause between them shifted every span by tens of ms.
    private val anchorNanos: Long
    private val anchorEpochMicros: Long

    init {
        var bestGap = Long.MAX_VALUE
        var nanos = 0L
        var epoch = 0L
        repeat(16) {
            val before = System.nanoTime()
            val wall = Instant.now()
            val after = System.nanoTime()
            if (after - before < bestGap) {
                bestGap = after - before
                nanos = before + (after - before) / 2
                epoch = wall.epochSecond * 1_000_000 + wall.nano / 1000
            }
        }
        anchorNanos = nanos
        anchorEpochMicros = epoch
    }

    /** JVM start to [afield.app.diagnostics.logging.PinnedConsole.finishStartup], once it has run. */
    @Volatile
    var readyMillis: Long? = null

    fun epochMicros(nanos: Long): Long = anchorEpochMicros + (nanos - anchorNanos) / 1000

    fun record(name: String, startNanos: Long, endNanos: Long = System.nanoTime(), box: String? = null, detail: String? = null) {
        val s = seq.incrementAndGet()
        val span = Span(
            seq = s,
            name = name,
            startEpochMicros = epochMicros(startNanos),
            durationMicros = (endNanos - startNanos) / 1000,
            box = box,
            detail = detail,
            thread = Thread.currentThread().name,
        )
        synchronized(ring) { ring[(s % CAPACITY).toInt()] = span }
    }

    inline fun <T> time(name: String, box: String? = null, detail: String? = null, block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            record(name, start, box = box, detail = detail)
        }
    }

    fun since(after: Long): Snapshot {
        val last = seq.get()
        val first = maxOf(after + 1, last - CAPACITY + 1, 1)
        val spans = synchronized(ring) {
            (first..last).mapNotNull { s -> ring[(s % CAPACITY).toInt()]?.takeIf { it.seq == s } }
        }
        return Snapshot(
            spans = spans,
            nextSeq = last,
            dropped = maxOf(0, first - (after + 1)),
            jvmStartEpochMillis = ManagementFactory.getRuntimeMXBean().startTime,
            readyMillis = readyMillis,
            nowEpochMicros = epochMicros(System.nanoTime()),
        )
    }
}
