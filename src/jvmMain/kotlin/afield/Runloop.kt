package afield


import afield.Generator.Companion.isMain
import afield.four.util.MapOfSequences
import afield.logger.lprintln
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
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

/**
 * Time source for frame-based programs that also need a duration.
 *
 * Ordinarily this is monotonic wall time. A frame-synchronous video capture installs
 * a token that advances by exactly `1 / fps` only after a Field frame has been painted
 * and copied into the movie. Code using [nowSeconds] therefore becomes frame-indexed
 * during offline capture without changing its live, interactive pacing.
 */
object AnimationClock {
    class FrameToken internal constructor(internal val state: FrameState)

    internal class FrameState(
        val startSeconds: Double,
        val fps: Int,
        val frame: AtomicLong = AtomicLong(0)
    )

    private val fixed = AtomicReference<FrameState?>(null)

    fun nowSeconds(): Double {
        val state = fixed.get()
        return if (state == null) {
            System.nanoTime() / 1_000_000_000.0
        } else {
            state.startSeconds + state.frame.get().toDouble() / state.fps.toDouble()
        }
    }

    fun isFrameIndexed(): Boolean = fixed.get() != null

    internal fun beginFrameIndexed(fps: Int): FrameToken {
        val state = FrameState(
            startSeconds = 0.0,
            fps = fps.coerceAtLeast(1)
        )
        check(fixed.compareAndSet(null, state)) { "A frame-synchronous capture is already active" }
        return FrameToken(state)
    }

    internal fun advance(token: FrameToken) {
        check(fixed.get() === token.state) { "Frame clock token is no longer active" }
        token.state.frame.incrementAndGet()
    }

    internal fun endFrameIndexed(token: FrameToken) {
        fixed.compareAndSet(token.state, null)
    }
}

class Runloop {

    private constructor() {
        lprintln("info,runloop", "Runloop is being constructed")
        lprintln("info,runloop", "main = ${System.identityHashCode(main)}")
    }

    companion object {
        val runLoop = Runloop()

        private val miscPoolThreads = AtomicLong(0)

        /**
         * Shared pool for fire-and-forget background work (history screenshots, workspace saves,
         * AI box naming, render-target file writes).
         *
         * **Daemon-threaded, with a draining shutdown hook.** With the default thread factory these
         * workers were non-daemon, so a dozen idle `pool-1-thread-N` threads sat parked in
         * `LinkedBlockingQueue.take` forever and the JVM could never terminate on its own. That is
         * what made `examples.agent-transcripts.materialize` exit 3 under
         * `AFIELD_JVM_MAIN_STRICT_EXIT` — a pool that has finished all its work still pinned the
         * process. (It presents as `ForkJoinPool.managedBlock` frames, which is merely how AQS parks
         * and not evidence of a ForkJoinPool.)
         *
         * Daemon threads alone would have quietly given up something real, though: today an
         * in-flight save keeps the JVM alive by accident, so it gets to finish. The shutdown hook
         * restores that on purpose and extends it — hooks also run on `exitProcess`, which is how
         * this process actually ends, and which previously waited for nothing at all.
         */
        val miscPool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() + 2) { runnable ->
            Thread(runnable, "afield-misc-${miscPoolThreads.incrementAndGet()}").apply { isDaemon = true }
        }.also { pool ->
            // Throws if the JVM is already shutting down when the pool is first touched; that is
            // exactly the case where there is nothing left to drain, so ignoring it is correct.
            runCatching {
                Runtime.getRuntime().addShutdownHook(
                    Thread({
                        pool.shutdown()
                        runCatching { pool.awaitTermination(5, TimeUnit.SECONDS) }
                    }, "afield-misc-drain")
                )
            }
        }

        private val scheduling = RunloopSchedulingConfig.fromEnvironment()
        private val schedulingState = RunloopSchedulingState(scheduling)

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

    fun schedulingStatus(): RunloopSchedulingStatus = schedulingState.status()

    fun start() {
        if (mainThread == null) {
            mainThread = Thread.currentThread()
        }
        schedulingState.install(Thread.currentThread())
        lprintln("info,startup", "entering run loop")
        while (true) {
            val frameStartNs = System.nanoTime()
            preRun.update()
            main.frame()
            postRun.update()
            val workNs = System.nanoTime() - frameStartNs
            frame++
            // First completed frame = Field is up. Announce ready and collapse the startup row.
            if (frame == 1L) afield.logger.PinnedConsole.finishStartup()
            timingMonitor.record(frame, frameStartNs, workNs)

            if (shouldSleep) {
//                lprintln("debug,runloop", "framer has run out of work thread is ${Thread.currentThread().name}")
                schedulingState.idle()
            }
            shouldSleep = true
        }
    }
}

private enum class RunloopIdleStrategy {
    YIELD,
    SPIN,
    PARK,
    SPIN_THEN_PARK
}

private data class RunloopSchedulingConfig(
    val idleStrategy: RunloopIdleStrategy = RunloopIdleStrategy.SPIN_THEN_PARK,
    val spinIterations: Int = 64,
    val parkNanos: Long = 50_000L,
    val affinityCore: Int? = null,
    val affinityEnabled: Boolean = affinityCore != null
) {
    companion object {
        fun fromEnvironment(): RunloopSchedulingConfig {
            fun envName(name: String): String = "AFIELD_RUNLOOP_" + name
                .replace(Regex("([a-z])([A-Z])"), "\$1_\$2")
                .uppercase()
                .replace('.', '_')
            fun setting(name: String): String? {
                return System.getProperty("afield.runloop.$name")
                    ?: System.getenv(envName(name))
            }

            val idle = when (setting("idle")?.trim()?.lowercase()) {
                null, "", "spin-then-park", "spin_then_park" -> RunloopIdleStrategy.SPIN_THEN_PARK
                "yield" -> RunloopIdleStrategy.YIELD
                "spin" -> RunloopIdleStrategy.SPIN
                "park" -> RunloopIdleStrategy.PARK
                else -> RunloopIdleStrategy.SPIN_THEN_PARK
            }
            val spinIterations = setting("spinIterations")?.trim()?.toIntOrNull()?.coerceIn(0, 1_000_000) ?: 64
            val parkNanos = setting("parkNanos")?.trim()?.toLongOrNull()?.coerceIn(0L, 10_000_000L) ?: 50_000L
            val affinityText = setting("affinityCore")?.trim()
                ?: System.getenv("AFIELD_RUNLOOP_AFFINITY")?.trim()
                ?: System.getProperty("afield.runloop.affinity")?.trim()
            val affinityCore = affinityText
                ?.takeUnless { it.isBlank() || it.equals("off", ignoreCase = true) || it.equals("false", ignoreCase = true) }
                ?.toIntOrNull()
                ?.takeIf { it >= 0 }
            return RunloopSchedulingConfig(
                idleStrategy = idle,
                spinIterations = spinIterations,
                parkNanos = parkNanos,
                affinityCore = affinityCore,
                affinityEnabled = affinityCore != null
            )
        }
    }
}


private class RunloopSchedulingState(private val config: RunloopSchedulingConfig) {
    @Volatile private var installed = false
    @Volatile private var applied = false
    @Volatile private var linuxThreadId = -1L
    @Volatile private var message = if (config.affinityEnabled) "pending" else "off"

    fun install(thread: Thread) {
        if (installed) return
        installed = true
        thread.name = "field-runloop-main"
        if (!config.affinityEnabled) {
            message = "affinity disabled"
            lprintln("info,runloop", "Runloop scheduling: ${status().idleStrategy}, affinity disabled")
            return
        }
        val os = System.getProperty("os.name", "").lowercase()
        if (!os.contains("linux")) {
            message = "affinity requested, but this is not Linux"
            return
        }
        val core = config.affinityCore ?: run {
            message = "affinity disabled"
            return
        }
        val tid = currentLinuxThreadId(thread.name)
        linuxThreadId = tid
        if (tid <= 0) {
            message = "could not locate Linux thread id for ${thread.name}"
            return
        }
        val taskset = findTaskset()
        if (taskset == null) {
            message = "taskset not found; cannot apply experimental Linux affinity"
            return
        }
        runCatching {
            val process = ProcessBuilder(taskset, "-pc", core.toString(), tid.toString())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val finished = process.waitFor(2, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                message = "taskset timed out"
            } else if (process.exitValue() == 0) {
                applied = true
                message = output.ifBlank { "pinned Linux tid $tid to core $core" }
            } else {
                message = output.ifBlank { "taskset failed with exit ${process.exitValue()}" }
            }
        }.onFailure {
            message = it.message ?: it.javaClass.name
        }
        lprintln("info,runloop", "Runloop scheduling: ${status().idleStrategy}, affinity=$message")
    }

    fun idle() {
        when (config.idleStrategy) {
            RunloopIdleStrategy.YIELD -> Thread.yield()
            RunloopIdleStrategy.SPIN -> spin(config.spinIterations)
            RunloopIdleStrategy.PARK -> park(config.parkNanos)
            RunloopIdleStrategy.SPIN_THEN_PARK -> {
                spin(config.spinIterations)
                park(config.parkNanos)
            }
        }
    }

    fun status(): RunloopSchedulingStatus = RunloopSchedulingStatus(
        idleStrategy = config.idleStrategy.name.lowercase().replace('_', '-'),
        spinIterations = config.spinIterations,
        parkNanos = config.parkNanos,
        affinityRequested = config.affinityEnabled,
        affinityCore = config.affinityCore ?: -1,
        affinityApplied = applied,
        affinityThreadId = linuxThreadId,
        affinityMessage = message
    )

    private fun spin(iterations: Int) {
        repeat(iterations) { Thread.onSpinWait() }
    }

    private fun park(nanos: Long) {
        if (nanos > 0L) LockSupport.parkNanos(nanos)
    }

    private fun currentLinuxThreadId(threadName: String): Long {
        val tasks = File("/proc/self/task").listFiles() ?: return -1L
        val matches = tasks.mapNotNull { task ->
            val tid = task.name.toLongOrNull() ?: return@mapNotNull null
            val comm = runCatching { task.resolve("comm").readText().trim() }.getOrNull()
            if (comm == threadName.take(15)) tid else null
        }
        return when {
            matches.size == 1 -> matches.single()
            matches.isNotEmpty() -> matches.minOrNull() ?: -1L
            else -> -1L
        }
    }

    private fun findTaskset(): String? {
        val candidates = listOf("/usr/bin/taskset", "/bin/taskset", "taskset")
        return candidates.firstOrNull { candidate ->
            candidate.contains('/') && File(candidate).canExecute() || !candidate.contains('/')
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
    private var hasExecuted = false
    private var pending: (() -> Unit)? = null  // the most recent block waiting
    private var job: Job? = null               // scheduled coroutine
    private val lock = Any()

    fun submit(block: () -> Unit) {
        if (AnimationClock.isFrameIndexed()) {
            // Offline capture must not race a zero-delay coroutine against the screenshot at this
            // runloop boundary. Rate-limit against virtual movie time and publish synchronously;
            // intermediate submissions are still dropped exactly as a rate limiter promises.
            val now = (AnimationClock.nowSeconds() * 1000.0).toLong()
            var ready: (() -> Unit)? = null
            synchronized(lock) {
                if (!hasExecuted || now < lastExecution || now >= lastExecution + intervalMillis) {
                    lastExecution = now
                    hasExecuted = true
                    pending = null
                    ready = block
                } else {
                    pending = block
                }
            }
            ready?.invoke()
            return
        }
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
                    hasExecuted = true
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
