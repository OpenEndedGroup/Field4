package afield

import afield.Generator.Companion.isMain
import afield.logger.lprintln
import kotlinx.coroutines.*
import java.util.concurrent.LinkedTransferQueue
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.*

/**
 * A frame-based animation. Returning a [Frames] from launched box code makes Field
 * run its [body] once per frame on the Runloop (see [Box.launchFrames] and the
 * `Frames` return-value handler in `ReturnValueRegistry.registerFramesReturnValue`).
 *
 * Inside a [body] lambda the receiver is this [Frames], so these members are in scope:
 * - [frameNumber]: current frame count, incremented by each [frame] call.
 * - [end]: requests that the animation stop; it cancels the running job the next time
 *   the loop reaches a frame boundary (or marks it stillborn if not yet launched).
 * - [delay]: suspends for `ms` milliseconds and then advances one frame.
 *
 * Build one with [frames] (manual `frame()` control), [eachFrame]/`animate`
 * (run body every frame while `frame()` is true), or [loop] (run body every pass,
 * auto-advancing a frame if the body didn't).
 */
class Frames {
    var job: Job? = null
    private var stillborn = false

    var atEnd = suspend {}
    internal var onAtEndStart: (Job) -> Unit = {}
    internal var onAtEndFinish: (Job) -> Unit = {}
    internal var shouldCancelAtEnd = { false }
    internal var inEndPhase = false
    var body = suspend {}
    var shouldend = { false }

    var frameNumber = 0

    override fun toString(): String {
        return "(frames)"
    }

    suspend fun wait() {
        while (!coroutineContext[Job]!!.isCancelled) frame()
    }

    suspend fun end() {
        if (job != null) job?.cancel()
        else {
            stillborn = true
        }
    }

    suspend fun delay(ms: Int) {
        kotlinx.coroutines.delay(ms.toLong())
        frame()
    }

    @OptIn(ExperimentalStdlibApi::class)
    suspend fun frame(): Boolean {
        job = coroutineContext[Job]
        var r = true
        if (stillborn && job != null) {
            job?.cancel()
            stillborn = false
            r = false
        }

        val shouldCancel = if (inEndPhase) shouldCancelAtEnd() else shouldend()
        if (shouldCancel && job != null) {
            job?.cancel()
            r = false
        }

        try {
            yield()
            frameNumber++
            return r
        } catch (t: Throwable) {
            if (inEndPhase) {
                throw t
            }
            // Run the end phase outside the cancelled launch job, but preserve the
            // current dispatcher and other contextual data (such as box context).
            withContext(NonCancellable) {
                val endScope = CoroutineScope(coroutineContext.minusKey(Job) + Job())
                var endFailure: Throwable? = null
                val endJob = endScope.launch {
                    inEndPhase = true
                    try {
                        atEnd()
                    } catch (endThrowable: Throwable) {
                        endFailure = endThrowable
                    } finally {
                        inEndPhase = false
                    }
                }
                onAtEndStart(endJob)
                try {
                    endJob.join()
                } finally {
                    onAtEndFinish(endJob)
                }
                endFailure?.let { throw it }
            }
            throw t
        }
    }
}

class EndedException : Throwable()

/**
 * Lowest-level [Frames] builder: the body runs once and is responsible for calling
 * [Frames.frame] itself to advance/suspend frames (e.g. `while (frame()) { ... }`).
 */
fun frames(block: suspend Frames.() -> Unit): Frames {
    val v = Frames()

    v.body = suspend {
        block(v)
    }

    return v
}

fun forever(block: suspend Frames.() -> Boolean): Frames {
    val v = Frames()

    v.body = suspend {
        var f = v.frameNumber
        while (block(v)) {
            if (v.frameNumber == f) {
                v.frame()
            }
            f = v.frameNumber
        }
    }

    return v
}

/**
 * Runs [block] repeatedly. Unlike [eachFrame], it auto-advances one frame after each
 * pass only if the body did not itself advance the frame (via [Frames.frame]/[Frames.delay]).
 */
fun loop(block: suspend Frames.() -> Unit): Frames {
    val v = Frames()

    v.body = suspend {
        var f = v.frameNumber
        while (true) {
            block(v)
            if (v.frameNumber == f) {
                v.frame()
            }
            f = v.frameNumber
        }
    }

    return v
}

fun Frames.end(block: suspend Frames.() -> Unit): Frames {
    val original = atEnd
    atEnd = {
        original()
        this@end.block()
    }
    return this
}


/**
 * Runs [block] once per frame while [Frames.frame] returns true. This is what
 * `animate { }` (stdlib.kt) delegates to.
 */
fun eachFrame(block: suspend Frames.() -> Unit): Frames {
    return frames {
        while (frame()) {
            this.block()
        }
    }
}

/**
 * Run [frames] on Field's Runloop with this box as the active context.
 *
 * The launched coroutine is added to [Box.jobs], so `Box.stop(...)` cancels it.
 * Reusing [strand] advances the box generation for that strand, causing older
 * Frames launched through this helper to stop the next time they reach [Frames.frame].
 */
fun Box.launchFrames(
    frames: Frames,
    strand: String = "frames",
    mode: String = "frames",
    reportRunning: Boolean = true
): Job {
    val box = this
    val launchEpoch = box.captureLaunchEpoch()
    val generation = box.runGeneration.compute(strand) { _, current -> (current ?: -1) + 1 }!!

    val previousShouldEnd = frames.shouldend
    frames.shouldend = {
        previousShouldEnd() || box.runGeneration[strand] != generation
    }

    val previousAtEndStart = frames.onAtEndStart
    val previousAtEndFinish = frames.onAtEndFinish
    val previousShouldCancelAtEnd = frames.shouldCancelAtEnd

    frames.onAtEndStart = onAtEndStart@{ endJob ->
        // The end phase registers against the epoch *now*, not `launchEpoch`: stop bumps the epoch
        // and it is stop's cancellation that creates this job. See Box.registerEndPhaseJob.
        if (!box.registerEndPhaseJob(endJob)) return@onAtEndStart
        previousAtEndStart(endJob)
        val endingGeneration = box.runGeneration[strand] ?: generation
        frames.shouldCancelAtEnd = {
            previousShouldCancelAtEnd() || box.runGeneration[strand] != endingGeneration
        }
        // Only claim the running state if a newer launch has not already taken this strand; see the
        // same guard in registerFramesReturnValue.
        val holder = box.currentRunningStates[strand]
        if (holder == null || holder.watermark <= generation) {
            box.currentRunningStates[strand] = RunningState(generation, "ending")
            if (reportRunning) {
                send(Message("is-running").payload(IsRunning(box.id, true, generation, strand, "ending")))
            }
        }
    }
    frames.onAtEndFinish = { endJob ->
        previousAtEndFinish(endJob)
        frames.shouldCancelAtEnd = previousShouldCancelAtEnd
        box.jobs.remove(endJob)
    }

    val launchJob = inMain {
        Box.withBoxContext(box) {
            if (!box.isLaunchEpochCurrent(launchEpoch)) return@withBoxContext
            box.currentRunningStates[strand] = RunningState(generation, mode)
            if (reportRunning) {
                send(Message("is-running").payload(IsRunning(box.id, true, generation, strand, mode)))
            }

            val job = coroutineContext[Job]
            frames.job = job

            try {
                frames.body()
            } catch (e: CancellationException) {
            } catch (t: Throwable) {
                lprintln("error,execution", t.stackTraceToString())
                box.error(t.message ?: t.javaClass.name)
            } finally {
                if (box.currentRunningStates[strand]?.watermark == generation) {
                    box.currentRunningStates.remove(strand)
                }
                if (reportRunning) {
                    send(Message("is-running").payload(IsRunning(box.id, false, generation, strand, mode)))
                }
            }
        }
    }
    box.registerLaunchJob(launchJob, launchEpoch)
    launchJob.invokeOnCompletion {
        box.jobs.remove(launchJob)
    }
    return launchJob
}

fun Box.launchFrames(
    strand: String = "frames",
    mode: String = "frames",
    reportRunning: Boolean = true,
    block: suspend Frames.() -> Unit
): Job = launchFrames(frames(block), strand = strand, mode = mode, reportRunning = reportRunning)

/**
 * Run two Frame programs in parallel. Each child advances as the parent advances,
 * and cancellation of the parent stops both children.
 *
 * **The children run their own [body], so they need the parent's fences.** A launcher installs
 * [Frames.shouldend], [Frames.onAtEndStart], [Frames.onAtEndFinish] and [Frames.shouldCancelAtEnd]
 * on whatever `Frames` it was handed — here, the combined one. The children are reached only through
 * `body()`, so they used to keep the defaults, and the `onAtEndStart = {}` default is the one that
 * mattered: a child's end job was never registered with the box, so nothing could cancel it. A child
 * `.end { }` that looped on `frame()` ran on the Runloop forever, through any number of stops. This
 * forwards all four, so a child is fenced exactly as the parent is.
 *
 * The forwarding is deliberately *lazy* — each child reads the parent's current hook rather than a
 * copy — because a launcher installs those hooks after this function has returned.
 *
 * It also means `+` **writes to its operands**, so a `Frames` cannot be composed twice: in
 * `val c1 = a + b; val c2 = a + d`, `a`'s fences end up pointing at `c2`. Composing twice was already
 * wrong — both parents would drive the same `a.body()` and share its `frameNumber` — so this narrows
 * nothing, but it is now wrong in a second way.
 *
 * One rough edge is left, and it is a reporting one. Up to three end phases (both children and the
 * parent) now forward to the same `onAtEndStart`/`onAtEndFinish` pair, so the box may report
 * `"ending"` more than once, and the first end phase to *finish* restores the parent's
 * `shouldCancelAtEnd` while the others are still winding down. Neither weakens the stop: every end
 * job is in [Box.jobs], which is the route `stopTrackedJobs` uses.
 */
operator fun Frames.plus(other: Frames): Frames {
    val left = this
    val parent = frames combined@{
        coroutineScope {
            val a = launch(start = CoroutineStart.UNDISPATCHED) { left.body() }
            val b = launch(start = CoroutineStart.UNDISPATCHED) { other.body() }

            try {
                while (a.isActive || b.isActive) {
                    if (!this@combined.frame()) break
                }
            } finally {
                a.cancel()
                b.cancel()
            }
        }
    }

    listOf(left, other).forEach { child ->
        child.shouldend = { parent.shouldend() }
        child.shouldCancelAtEnd = { parent.shouldCancelAtEnd() }
        child.onAtEndStart = { endJob -> parent.onAtEndStart(endJob) }
        child.onAtEndFinish = { endJob -> parent.onAtEndFinish(endJob) }
    }

    return parent
}

@OptIn(InternalCoroutinesApi::class)
class Framer : CoroutineDispatcher() {

    private val queueLock = Any()
    private var nextList = LinkedTransferQueue<Pair<CoroutineContext, Runnable>>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        synchronized(queueLock) {
            nextList.add(context to block)
        }
//        println(" framer will dispatch $context $block $nextList ${nextList.size} ${System.identityHashCode(this)}")
    }


    @OptIn(ExperimentalAtomicApi::class)
    fun frame(): Int {
        val currentList = synchronized(queueLock) {
            val current = nextList
            nextList = LinkedTransferQueue()
            current
        }
        var n = currentList.poll()
        var c = 0
        while (n != null) {
            c++
            try {
                isMain.store(true)
                n.second.run()
            } catch (ex: Throwable) {
                ex.printStackTrace()
            } finally {
                isMain.store(false)
            }
            n = currentList.poll()
        }
//        if (c == 0) print(".") else print(":")
        return c
    }

}

fun (suspend () -> Unit).then(next: suspend () -> Unit): (suspend () -> Unit) {
    val first = this
    return suspend {
        first()
        next()
    }
}


/**
 * Scope available inside `generator { ... }`.
 *
 * - Call `yield(out)` to emit a value to the caller.
 * - `yield(out)` SUSPENDS and RETURNS the next input value that is sent in
 *   via `Generator.next(input)`.
 */
class GeneratorScope<In, Out>(
    private val onYield: (Out, Continuation<In>) -> Unit
) {
    suspend fun yield(value: Out): In =
        suspendCoroutine { cont: Continuation<In> ->
            onYield(value, cont)
        }

    /**
     * Convenience `yield()` without value — yields `true`.
     * Only valid for generators whose output type is `Boolean`;
     * any other output type will fail at runtime with a ClassCastException.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun yield(): In =
        suspendCoroutine { cont: Continuation<In> ->
            onYield(true as Out, cont)
        }
}

/**
 * Python-style generator that supports `yield(out)` / `send(input)`-like behavior.
 *
 * Usage:
 *   val gen = generator<Float, Boolean> { ... }
 *   val firstOut = gen.start()
 *   val nextOut  = gen.next(dt)
 */
class Generator<In, Out> internal constructor(
    private val block: suspend GeneratorScope<In, Out>.() -> Unit
) {
    private val scope = GeneratorScope<In, Out> { value, cont ->
        pendingOutput = value
        waitingContinuation = cont
    }

    private var started = false
    private var completed = false
    private var failure: Throwable? = null

    private var pendingOutput: Out? = null
    private var waitingContinuation: Continuation<In>? = null

    companion object {
        @OptIn(ExperimentalAtomicApi::class)
        val isMain: AtomicBoolean = AtomicBoolean(false)
    }

    /**
     * Starts the coroutine and runs until the first `yield`.
     * Returns the first yielded value, or null if the generator completed
     * without yielding anything.
     */
    fun start(): Out? {
//        check(!started) { "Generator already started" }
        started = true

        block.startCoroutine(scope, object : Continuation<Unit> {
            override val context: CoroutineContext = EmptyCoroutineContext

            override fun resumeWith(result: Result<Unit>) {
                completed = true
                failure = result.exceptionOrNull()
            }
        })

        return pollOutput()
    }

    /**
     * Resume after a `yield`, providing the next input.
     * Returns the next yielded value, or null if the generator has completed.
     */
    fun next(input: In): Out? {
        check(started) { "Call start() before next()" }
        if (completed) return null

        val cont = waitingContinuation
            ?: error("Generator is not currently suspended at yield()")

        waitingContinuation = null
        cont.resume(input)

        return pollOutput()
    }

    val isCompleted: Boolean
        get() = completed && failure == null

    private fun pollOutput(): Out? {
        // Propagate any exception from inside the generator
        failure?.let { throw it }

        if (completed && pendingOutput == null) {
            return null
        }

        val out = pendingOutput
        pendingOutput = null
        return out
    }
}

/**
 * `sequence { ... }`-style helper.
 *
 * Inside the block you can:
 *   val input: In = yield(out)
 */
@JvmName("generator_2")
fun <In, Out> generator2(
    block: suspend GeneratorScope<In, Out>.() -> Unit
): Generator<In, Out> = Generator(block)

/**
 * Convenience overload when you don't need to send inputs.
 * Input type is `Unit`, so `yield(out)` just returns `Unit`.
 */
@JvmName("generator_1")
fun <Out> generator(
    block: suspend GeneratorScope<Unit, Out>.() -> Unit
): Generator<Unit, Out> = Generator(block)

/**
 * Convenience overload when you don't need to send outputs.
 * Input type is `Unit`, so `yield(out)` just returns `Unit`.
 */
fun <In> gather(
    block: suspend GeneratorScope<In, Boolean>.() -> Unit
): Generator<In, Boolean> = Generator(block)

/* -------------------------------------------------------------------------- */
/*                                COMBINATORS                                 */
/* -------------------------------------------------------------------------- */

/**
 * Sequential composition: (this + other)
 *
 * Drives `this` to completion, then `other`, sharing the same input/output
 * types.
 *
 * Both generators are assumed to be "fresh" (not yet started) when composed.
 */
operator fun <In, Out> Generator<In, Out>.plus(
    other: Generator<In, Out>
): Generator<In, Out> = generator2<In, Out> {
    // First, run the left generator
    var out = this@plus.start()
    while (out != null) {
        val input: In = yield(out)
        out = this@plus.next(input)
    }

    // Then, run the right generator
    out = other.start()
    while (out != null) {
        val input: In = yield(out)
        out = other.next(input)
    }
}

/**
 * Parallel / lockstep composition: (this % other)
 *
 * Both generators receive the same input `In` each step.
 * On each step, yields a Pair of their outputs (OutA, OutB).
 *
 * The combined generator stops when EITHER underlying generator completes
 * (zip-style semantics).
 */
operator fun <In, A, B> Generator<In, A>.rem(
    other: Generator<In, B>
): Generator<In, Pair<A, B>> = generator2<In, Pair<A, B>> {
    var outA = this@rem.start()
    var outB = other.start()

    // Zip-style: stop when either side finishes
    while (outA != null && outB != null) {
        val combined = outA to outB
        val input: In = yield(combined)

        outA = this@rem.next(input)
        outB = other.next(input)
    }
}

fun <In, Out, Out2> Generator<In, Out>.map(m: (Out) -> (Out2)) = generator2<In, Out2> {
    var a = this@map.start()
    while (a != null) {
        a = this@map.next(yield(m(a)))
    }
}

infix fun <In, Out, Out2> Generator<In, Out>.pipe(g: Generator<Out, Out2>) = generator2<In, Out2> {
    var a: Out? = this@pipe.start() ?: return@generator2
    val b: Out2 = g.start() ?: return@generator2

    while (a != null) {
        val o = g.next(a) ?: break

        val i = yield(o)
        a = this@pipe.next(i)
    }
}
