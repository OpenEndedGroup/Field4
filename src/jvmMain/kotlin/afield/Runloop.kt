package afield


import afield.Generator.Companion.isMain
import afield.four.util.MapOfSequences
import afield.logger.lprintln
import kotlinx.coroutines.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.ceil
import kotlin.math.max


//@OptIn(DelicateCoroutinesApi::class, ExperimentalAtomicApi::class)
//fun inMain(block: suspend CoroutineScope.() -> Unit) = GlobalScope.launch(Runloop.runLoop.main, start = if (isMain.load()) CoroutineStart.UNDISPATCHED else CoroutineStart.DEFAULT, block = block)

@OptIn(DelicateCoroutinesApi::class, ExperimentalAtomicApi::class)
fun inMain(block: suspend CoroutineScope.() -> Unit) = GlobalScope.launch(
    Runloop.runLoop.main,
    start = if (isMain.load()) CoroutineStart.DEFAULT else CoroutineStart.DEFAULT,
    block = block
)

class Runloop {

    private constructor() {
        lprintln("info,runloop", "Runloop is being constructed")
        lprintln("info,runloop", "main = ${System.identityHashCode(main)}")
    }

    companion object {
        val runLoop = Runloop()
        val miscPool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() + 2)

        @Volatile
        var mainThread: Thread? = null

        val isMainThread: Boolean
            get() = mainThread == Thread.currentThread()
    }

    var frame = 0L

    val main = Framer()
    val preRun = MapOfSequences<String>()
    val postRun = MapOfSequences<String>()
    var shouldSleep = true

    var limiter = RateLimiter(10.0, 0.2)

    private val timingMonitor = RunloopTimingMonitor()

    fun timingStats(): RunloopTimingStats = timingMonitor.snapshot(frame)

    fun start() {
        if (mainThread == null) {
            mainThread = Thread.currentThread()
        }
        while (true) {
            val frameStartNs = System.nanoTime()
            preRun.update()
            main.frame()
            postRun.update()
            val workNs = System.nanoTime() - frameStartNs
            frame++
            timingMonitor.record(frame, frameStartNs, workNs)

            if (shouldSleep) {
//                lprintln("debug,runloop", "framer has run out of work thread is ${Thread.currentThread().name}")
                Thread.yield()
            }
            shouldSleep = true
        }
    }
}

private class RunloopTimingMonitor(private val sampleCapacity: Int = 8192) {
    private val intervalSamplesNs = LongArray(sampleCapacity)
    private val workSamplesNs = LongArray(sampleCapacity)
    private val totalSamples = AtomicLong(0)

    @Volatile
    private var lastStartNs = 0L

    @Volatile
    private var latestIntervalNs = 0L

    @Volatile
    private var latestWorkNs = 0L

    fun record(frame: Long, startNs: Long, workNs: Long) {
        val previousStartNs = lastStartNs
        lastStartNs = startNs
        latestWorkNs = workNs
        if (previousStartNs == 0L) return

        val intervalNs = (startNs - previousStartNs).coerceAtLeast(0L)
        latestIntervalNs = intervalNs
        val index = (totalSamples.getAndIncrement() % sampleCapacity).toInt()
        intervalSamplesNs[index] = intervalNs
        workSamplesNs[index] = workNs
    }

    fun snapshot(frame: Long): RunloopTimingStats {
        val total = totalSamples.get()
        val count = minOf(total, sampleCapacity.toLong()).toInt()
        if (count == 0) {
            return RunloopTimingStats(
                running = lastStartNs != 0L,
                frame = frame,
                totalSamples = total,
                sampleCapacity = sampleCapacity
            )
        }

        val intervals = LongArray(count)
        val work = LongArray(count)
        val start = (total - count).coerceAtLeast(0L)
        for (offset in 0 until count) {
            val source = ((start + offset) % sampleCapacity).toInt()
            intervals[offset] = intervalSamplesNs[source]
            work[offset] = workSamplesNs[source]
        }

        intervals.sort()
        work.sort()

        fun Long.nsToMs(): Double = this / 1_000_000.0
        fun percentile(sorted: LongArray, percent: Double): Double {
            val index = ceil(percent * sorted.size).toInt().coerceIn(1, sorted.size) - 1
            return sorted[index].nsToMs()
        }
        fun median(sorted: LongArray): Double {
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 0) {
                (sorted[middle - 1].nsToMs() + sorted[middle].nsToMs()) / 2.0
            } else {
                sorted[middle].nsToMs()
            }
        }

        val intervalSumNs = intervals.sum()
        val ticksPerSecond = if (intervalSumNs > 0L) {
            count * 1_000_000_000.0 / intervalSumNs.toDouble()
        } else {
            0.0
        }

        return RunloopTimingStats(
            running = true,
            frame = frame,
            totalSamples = total,
            sampleCount = count,
            sampleCapacity = sampleCapacity,
            ticksPerSecond = ticksPerSecond,
            latestIntervalMs = latestIntervalNs.nsToMs(),
            intervalMedianMs = median(intervals),
            intervalP95Ms = percentile(intervals, 0.95),
            intervalP99Ms = percentile(intervals, 0.99),
            intervalMaxMs = intervals.last().nsToMs(),
            latestWorkMs = latestWorkNs.nsToMs(),
            workMedianMs = median(work),
            workP95Ms = percentile(work, 0.95),
            workP99Ms = percentile(work, 0.99),
            workMaxMs = work.last().nsToMs(),
            intervalsOver1Ms = intervals.count { it > 1_000_000L },
            intervalsOver4Ms = intervals.count { it > 4_000_000L },
            intervalsOver8Ms = intervals.count { it > 8_000_000L },
            intervalsOver16Ms = intervals.count { it > 16_000_000L }
        )
    }
}

class RateLimiter(
    rate: Double, private val maxLatency: Double
) {
    private val intervalMillis = (1000.0 / rate).toLong()
    private var lastExecution = 0L             // timestamp of last execution
    private var pending: (() -> Unit)? = null  // the most recent block waiting
    private var job: Job? = null               // scheduled coroutine
    private val lock = Any()

    fun submit(block: () -> Unit) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            // compute next allowed execution time
            val nextTime = max(lastExecution + intervalMillis, now)
            val latency = nextTime - now

            if (latency > maxLatency * 1000) {
                return
            }

            if (job == null) {
                schedule(block, nextTime)
            } else {
                pending = block
            }
        }
    }

    operator fun invoke(block: () -> Unit) = submit(block)

    @OptIn(DelicateCoroutinesApi::class)
    private fun schedule(block: () -> Unit, executeAt: Long) {
        val delayMs = max(0, executeAt - System.currentTimeMillis())
        job = GlobalScope.launch {
            delay(delayMs)
            var blockToRun: (() -> Unit)? = null
            synchronized(lock) {
                // If there’s a newer pending block, schedule it now.
                pending?.also { next ->
                    pending = null
                    val now = System.currentTimeMillis()
                    val nextExec = max(lastExecution + intervalMillis, now)
                    if (nextExec - now <= maxLatency * 1000) {
                        schedule(next, nextExec)
                    } else {
                        job = null
                    }
                } ?: run {
                    lastExecution = System.currentTimeMillis()
                    job = null
                    blockToRun = block
                }
            }
            blockToRun?.let { ready ->
                ready()
            }
        }
    }
}
