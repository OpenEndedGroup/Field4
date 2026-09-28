package afield

import kotlinx.coroutines.*
import java.util.PriorityQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/** Work performed by [SimulationClock.advance]; discarded elapsed time is reported explicitly. */
data class SimulationAdvance(val steps: Int, val resumed: Int, val deferred: Int, val droppedSeconds: Double)

/**
 * A coroutine scope with a manually stepped dispatcher. It creates no thread and registers no new box launcher.
 * Use [withSimulationClock] inside ordinary [frames]; standard `launch` children inherit the box,
 * source and parent job. `delay` and `withTimeout` then use simulation time. [advance] and [close]
 * belong to the creating thread; dispatch and cancellation may enqueue from another thread.
 *
 * A resume budget bounds cooperative work, not an uninterruptible loop inside user code. Close runs
 * cancellation/finally continuations on the owner thread; finally blocks must not suspend for future
 * simulation time. No pending timer survives close. Cancellation requested from another thread (a box
 * stop) can still be reaching the children when the owner arrives in close; their resumes are awaited
 * for up to [closeGraceMillis] of wall time before an unfinished cleanup is reported.
 */
@OptIn(InternalCoroutinesApi::class)
class SimulationClock(
    parentContext: CoroutineContext,
    val stepSeconds: Double = 1.0 / 60.0,
    val maxStepsPerAdvance: Int = 8,
    val maxResumesPerStep: Int = 1024
) : CoroutineScope, AutoCloseable {
    private val owner = Thread.currentThread()
    private val root = Job(parentContext[Job])
    private val dispatcher = object : CoroutineDispatcher(), Delay {
        override fun dispatch(context: CoroutineContext, block: Runnable) = enqueue(context, block)
        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) =
            scheduleDelay(timeMillis, continuation)
        override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext) =
            timer(timeMillis / 1000.0, Task(context, block))
    }
    override val coroutineContext = parentContext + root + dispatcher
    private data class Task(val context: CoroutineContext, val runnable: Runnable)
    private class Wake(val tick: Long, val serial: Long, var task: Task?)
    private val ready = ArrayDeque<Task>()
    private val timers = PriorityQueue(compareBy<Wake> { it.tick }.thenBy { it.serial })
    private var serial = 0L
    private var remainder = 0.0
    private var pumping = false
    private var advancing = false
    private var closing = false
    private var closed = false
    internal var closeGraceMillis = 5_000L
    /** Released by enqueues during close and by root completion, so close can wait for cleanup arriving from another thread. */
    private val cleanupSignal = Semaphore(0)
    var tick: Long = 0L
        private set
    val time: Double get() = tick * stepSeconds
    val pendingResumes: Int get() = synchronized(this) { ready.size }
    val pendingTimers: Int get() = synchronized(this) { timers.size }

    init {
        require(stepSeconds.isFinite() && stepSeconds > 0)
        require(maxStepsPerAdvance > 0 && maxResumesPerStep > 0)
    }

    @Synchronized private fun enqueue(context: CoroutineContext, block: Runnable) {
        check(!closed) { "Simulation clock is closed" }
        ready.addLast(Task(context, block))
        if (closing) cleanupSignal.release()
    }

    @Synchronized private fun timer(seconds: Double, task: Task): DisposableHandle {
        check(!closed && !closing) { "Cannot schedule a delay while the simulation is closing" }
        val ticks = ceil(seconds / stepSeconds - 1e-9).coerceAtLeast(1.0)
            .coerceAtMost((Long.MAX_VALUE - tick - 1).toDouble()).toLong()
        val wake = Wake(tick + ticks, serial++, task)
        timers.add(wake)
        return DisposableHandle { synchronized(this) { wake.task = null; timers.remove(wake) } }
    }

    private fun scheduleDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        val handle = timer(timeMillis / 1000.0, Task(continuation.context, Runnable {
            if (continuation.isActive) continuation.resume(Unit) { _, _, _ -> }
        }))
        continuation.invokeOnCancellation { handle.dispose() }
    }

    /** Suspend for simulation seconds, with resolution [stepSeconds]. Zero still yields one tick. */
    suspend fun sleep(seconds: Double) {
        require(seconds.isFinite() && seconds >= 0)
        suspendCancellableCoroutine<Unit> { continuation ->
            val handle = timer(seconds, Task(continuation.context, Runnable {
                if (continuation.isActive) continuation.resume(Unit) { _, _, _ -> }
            }))
            continuation.invokeOnCancellation { handle.dispose() }
        }
    }

    private fun ownerThread() = check(Thread.currentThread() === owner) { "Step and close the simulation on its owner thread" }

    /** Run ready children without advancing time, for example to establish their initial delays. */
    fun runCurrent(): Int = pump(maxResumesPerStep)

    private fun pump(limit: Int): Int {
        ownerThread()
        check(!closed && !pumping) { "Simulation pump is closed or reentrant" }
        pumping = true
        try {
            synchronized(this) {
                while (timers.peek()?.tick?.let { it <= tick } == true) {
                    val wake = timers.remove()
                    wake.task?.let(ready::addLast)
                    wake.task = null
                }
            }
            var count = 0
            while (count < limit) {
                val task = synchronized(this) { ready.removeFirstOrNull() } ?: break
                CoroutineProfiles.resume(task.context, task.runnable)
                count++
            }
            return count
        } finally { pumping = false }
    }

    /** Advance fixed ticks, discarding excess catch-up time rather than creating an unbounded backlog. */
    fun advance(seconds: Double, onStep: (Double) -> Unit = {}): SimulationAdvance =
        advance(seconds, { _, _ -> }, onStep)

    /**
     * Prepare a shared snapshot before each tick's coroutines, then integrate in [onStep].
     * [beforeStep] receives the zero-based step index and total steps in this advance, allowing
     * observed input motion to be distributed over catch-up steps. It sees the preceding [time];
     * the clock then advances one tick, resumes due children, and calls [onStep].
     */
    fun advance(seconds: Double, beforeStep: (Int, Int) -> Unit, onStep: (Double) -> Unit): SimulationAdvance {
        ownerThread()
        check(!closed && !closing && !pumping && !advancing)
        require(seconds.isFinite() && seconds >= 0)
        advancing = true
        try {
            val accepted = min(seconds, stepSeconds * maxStepsPerAdvance)
            remainder += accepted
            val steps = floor((remainder + stepSeconds * 1e-9) / stepSeconds).toInt().coerceAtMost(maxStepsPerAdvance)
            var resumed = 0
            repeat(steps) { index ->
                beforeStep(index, steps)
                synchronized(this) { tick++ }
                remainder = (remainder - stepSeconds).coerceAtLeast(0.0)
                resumed += runCurrent()
                onStep(stepSeconds)
            }
            return SimulationAdvance(steps, resumed, pendingResumes, seconds - accepted)
        } finally { advancing = false }
    }

    override fun close() {
        ownerThread()
        if (closed) return
        check(!pumping && !advancing) { "Close the clock outside a child continuation or step callback" }
        synchronized(this) { closing = true }
        root.invokeOnCompletion { cleanupSignal.release() }
        root.cancel()
        try {
            // A generous finite drain allows ordinary cancellation/finally cleanup, including children.
            // If root was already cancelled on another thread, that thread may not yet have dispatched
            // the children's cancellation resumes, so an empty queue waits for them rather than failing.
            var budget = maxOf(maxResumesPerStep, 65_536)
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(closeGraceMillis)
            while (true) {
                budget -= pump(budget)
                if (root.isCompleted && pendingResumes == 0) break
                val waitNanos = deadline - System.nanoTime()
                check(budget > 0 && waitNanos > 0) { "Simulation cleanup did not finish; finally blocks must not suspend" }
                if (pendingResumes == 0) cleanupSignal.tryAcquire(waitNanos, TimeUnit.NANOSECONDS)
            }
        } finally {
            synchronized(this) { ready.clear(); timers.clear(); closed = true }
        }
    }
}

/**
 * Scope a simulation to the current box coroutine. Use this inside an ordinary [frames] program;
 * the existing launch/stop/relaunch controls own it. Closing cancels and drains all children.
 */
suspend fun <T> withSimulationClock(
    stepSeconds: Double = 1.0 / 60.0,
    maxStepsPerAdvance: Int = 8,
    maxResumesPerStep: Int = 1024,
    block: suspend SimulationClock.() -> T
): T {
    val clock = SimulationClock(currentCoroutineContext(), stepSeconds, maxStepsPerAdvance, maxResumesPerStep)
    try { return clock.block() } finally { clock.close() }
}
