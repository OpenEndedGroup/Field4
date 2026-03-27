package afield

import kotlinx.coroutines.*
import java.util.concurrent.LinkedTransferQueue
import kotlin.coroutines.*

class Frames {
    var job: Job? = null
    private var stillborn = false

    var atEnd = suspend {}
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

        if (shouldend() && job != null) {
            job?.cancel()
            r = false
        }

        try {
            yield()
            frameNumber++
            return r
        } catch (t: Throwable) {
            // this seems a little wild
            coroutineScope {
                launch(coroutineContext[CoroutineDispatcher]!! + Job()) {
                    atEnd()
                }
            }
            throw t
        }
    }
}

class EndedException : Throwable()

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


fun eachFrame(block: suspend Frames.() -> Unit): Frames {
    return frames {
        while (frame()) {
            this.block()
        }
    }
}

/**
 * Run two Frame programs in parallel. Each child advances as the parent advances,
 * and cancellation of the parent stops both children.
 */
operator fun Frames.plus(other: Frames): Frames = frames combined@{
    coroutineScope {
        val a = launch(start = CoroutineStart.UNDISPATCHED) { this@plus.body() }
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
                n.second.run()
            } catch (ex: Throwable) {
                ex.printStackTrace()
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

    suspend fun yield(): In =
        suspendCoroutine { cont: Continuation<In> ->
            onYield(true as Out, cont) // dies if we aren't Generator<In, Boolean>
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
@JvmName("generator_2") fun <In, Out> generator2(
    block: suspend GeneratorScope<In, Out>.() -> Unit
): Generator<In, Out> = Generator(block)

/**
 * Convenience overload when you don't need to send inputs.
 * Input type is `Unit`, so `yield(out)` just returns `Unit`.
 */
@JvmName("generator_1") fun <Out> generator(
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

infix fun <In, Out, Out2> Generator<In, Out>.pipe(g : Generator<Out, Out2>) = generator2<In, Out2> {
    var a: Out? = this@pipe.start() ?: return@generator2
    val b: Out2 = g.start() ?: return@generator2

    while (a != null) {
        val o = g.next(a) ?: break

        val i = yield(o)
        a = this@pipe.next(i)
    }
}
