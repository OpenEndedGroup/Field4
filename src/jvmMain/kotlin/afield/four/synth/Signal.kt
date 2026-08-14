@file:afield.CompletionCategory("Synthesis")
package afield.four.synth

import afield.graphquery.LiveGraphEdge
import afield.graphquery.LiveGraphInspectable
import java.util.IdentityHashMap
import java.util.WeakHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.createCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs

// ═══════════════════════════════════════════════════════════════════
//  SynthContext – runtime context carried through the signal graph
// ═══════════════════════════════════════════════════════════════════


class SynthContext(
    /** Sample rate in Hz (e.g. 48000). */
    val sampleRate: Float,
    /** Maximum buffer size (frames per JACK cycle). */
    val bufferSize: Int
) {
    val inverseSampleRate: Float = 1f / sampleRate
    val nyquist: Float = sampleRate / 2f

    /** Monotonically increasing id; bumped once per process cycle. */
    var cycleId: Long = 0L
        internal set

    /** Elapsed time in seconds since the synth started (beginning of current buffer). */
    var time: Float = 0f
        internal set

    /** Latest Ableton Link timing snapshot captured at the beginning of this audio block. */
    var linkTiming: LinkTimingSnapshot? = null
        internal set

    val linkBeat: Double
        get() = linkTiming?.beat ?: 0.0

    val linkPhase: Double
        get() = linkTiming?.phase ?: 0.0

    val linkTempo: Double
        get() = linkTiming?.tempo ?: 0.0

    val linkPeers: Long
        get() = linkTiming?.peers ?: 0L

    internal fun advanceCycle() {
        cycleId++
        time += bufferSize * inverseSampleRate
    }

    /**
     * Force-render a signal graph [cycles] times on the current thread.
     *
     * This serves two purposes:
     * 1. Pre-allocates every internal buffer (avoids GC on audio thread).
     * 2. Exercises the hot path enough times (~15 000) to trigger
     *    HotSpot C2 JIT compilation, so the optimised native code is
     *    ready before the first real JACK callback.
     *
     * Call from the main thread *before* assigning the signal to a
     * [JackSynth].
     */
    fun warmUp(signal: Signal, cycles: Int = 15_000) {
        val saved = cycleId
        val buf = FloatArray(bufferSize)
        for (i in 0 until cycles) {
            cycleId = Long.MIN_VALUE + i   // unique id each iteration
            signal.render(buf, bufferSize, this)
        }
        cycleId = saved
    }

    // ── scratch-buffer pool ─────────────────────────────────────────

    private val pool = ArrayDeque<FloatArray>(16)

    /** Borrow a buffer (at least [size] floats). */
    fun acquireBuf(size: Int = bufferSize): FloatArray {
        val buf = pool.removeLastOrNull()
        return if (buf != null && buf.size >= size) buf else FloatArray(size)
    }

    /** Return a buffer to the pool. */
    fun releaseBuf(buf: FloatArray) { pool.addLast(buf) }

    override fun toString(): String = synthPrettyObject(
        this,
        "sampleRate" to sampleRate,
        "bufferSize" to bufferSize,
        "cycleId" to cycleId,
        "time" to time,
        "linkTiming" to linkTiming
    )
}

// ═══════════════════════════════════════════════════════════════════
//  Signal – the universal audio atom
// ═══════════════════════════════════════════════════════════════════


abstract class Signal {

    // ── per-cycle cache ─────────────────────────────────────────────

    private var _cache: FloatArray = FloatArray(0)
    private var _cacheId: Long = -1L
    private var _cacheContext: SynthContext? = null
    private var _cacheFrames: Int = 0

    /**
     * Evaluate (or replay cache) into [output].
     * Call this from consuming nodes – *never* call [generate] directly.
     */
    fun render(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (ctx !== _cacheContext || ctx.cycleId != _cacheId || _cacheFrames < numFrames) {
            if (_cache.size < numFrames) _cache = FloatArray(numFrames)
            generate(_cache, numFrames, ctx)
            _cacheContext = ctx
            _cacheId = ctx.cycleId
            _cacheFrames = numFrames
        }
        System.arraycopy(_cache, 0, output, 0, numFrames)
    }

    /**
     * Produce [numFrames] samples into [output].
     * Implementors write directly into [output]`[0 .. numFrames-1]`.
     */
    protected abstract fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext)

    // ── operators ───────────────────────────────────────────────────

    operator fun plus(other: Signal): Signal  = BinaryOp(this, other, BinaryOp.Kind.ADD)
    open operator fun plus(value: Float): Signal   = BinaryOp(this, Const(value), BinaryOp.Kind.ADD)
    open operator fun plus(value: Double): Signal  = this + value.toFloat()
    operator fun minus(other: Signal): Signal = BinaryOp(this, other, BinaryOp.Kind.SUB)
    open operator fun minus(value: Float): Signal  = BinaryOp(this, Const(value), BinaryOp.Kind.SUB)
    open operator fun minus(value: Double): Signal = this - value.toFloat()
    operator fun times(other: Signal): Signal = BinaryOp(this, other, BinaryOp.Kind.MUL)
    open operator fun times(value: Float): Signal  = Scale(this, value)
    open operator fun times(value: Double): Signal = Scale(this, value.toFloat())
    operator fun div(other: Signal): Signal   = BinaryOp(this, other, BinaryOp.Kind.DIV)
    open operator fun div(value: Float): Signal    = Scale(this, 1f / value)
    open operator fun div(value: Double): Signal   = Scale(this, 1f / value.toFloat())
    operator fun div(processor: SignalProcessor): Signal = through(processor)
    operator fun unaryMinus(): Signal         = Negate(this)

    /** Pipe this signal through a [SignalProcessor] (filter, effect, …). */
    fun through(processor: SignalProcessor): Signal {
        processor.input = this
        return processor
    }

    infix fun mix(other: Signal): Signal = this + other

    override fun toString(): String = synthPrettyObject(this)
}

/** Mono signals already memoize per audio block, so this is an intent marker. */
fun Signal.hold(): Signal = this


operator fun Float.plus(sig: Signal): Signal  = sig + this
operator fun Float.times(sig: Signal): Signal = sig * this
operator fun Float.minus(sig: Signal): Signal = Const(this) - sig

operator fun Float.plus(param: Param): Param = param + this
operator fun Double.plus(sig: Signal): Signal  = sig + toFloat()
operator fun Double.times(sig: Signal): Signal = sig * toFloat()
operator fun Double.minus(sig: Signal): Signal = Const(toFloat()) - sig
operator fun Double.plus(param: Param): Param = param + this
operator fun Int.plus(param: Param): Param = param + toFloat()
operator fun Int.times(sig: Signal): Signal    = sig * toFloat()

// ═══════════════════════════════════════════════════════════════════
//  Param – modulatable parameter
// ═══════════════════════════════════════════════════════════════════
open class Param(base: Float = 0f) : Signal() {
    private var rawBase: Float = base
    @Volatile
    private var observedValue: Float? = null

    var base: Float
        get() = observedValue ?: rawBase
        set(value) {
            rawBase = value
            if (!isGraphControlDynamic()) {
                observedValue = null
            }
            GraphBindingRuntime.update(this)
        }

    /**
     * Optional lambda evaluated once per cycle to produce a target value.
     * Receiver is [SynthContext], giving access to [SynthContext.time],
     * [SynthContext.sampleRate], etc.
     */
    var supplier: (SynthContext.() -> Float)? = null
        set(value) {
            field = value
            if (!isGraphControlDynamic()) {
                observedValue = null
            }
            GraphBindingRuntime.update(this)
        }

    /** Optional modulation signal added on top of [base] / supplier. */
    var mod: Signal? = null
        set(value) {
            field = value
            if (!isGraphControlDynamic()) {
                observedValue = null
            }
            GraphBindingRuntime.update(this)
        }

    // Interpolation state (NaN = uninitialised → snap to first value)
    private var current: Float = Float.NaN

    companion object {
        /**
         * Create a parameter from a lambda.
         *
         * If the block simply returns a [Float], it behaves like the original
         * once-per-buffer supplier:
         *
         * ```kotlin
         * val cutoff = Param { 1000f + 500f * kotlin.math.sin(time * 3f) }
         * ```
         *
         * If the block uses [ParamScope.yield], it becomes a coroutine-style
         * generator that resumes once per audio block while keeping local state:
         *
         * ```kotlin
         * val cutoff = Param {
         *     var step = 0
         *     while (true) {
         *         step = (step + 1) % 8
         *         yield(400f + step * 120f + kotlin.math.sin(time) * 40f)
         *     }
         * }
         * ```
         */
        operator fun invoke(block: suspend ParamScope.() -> Any?): Param {
            val p = Param()
            p.supplier = {
                ParamCoroutineStates
                    .stateFor(p, block, this)
                    .next(this)
            }
            return p
        }

        /**
         * Create a parameter that steps through a sequence once per audio block.
         *
         * When the sequence is exhausted, the parameter holds the last value.
         */
        operator fun invoke(values: Sequence<Float>, defaultValue: Float = 0f): Param =
            invoke(values.iterator(), defaultValue)

        /**
         * Iterator form of the sequence-driven parameter constructor.
         *
         * Values are consumed once per audio block and then linearly interpolated
         * across that block, matching the standard [supplier] behaviour.
         */
        operator fun invoke(values: Iterator<Float>, defaultValue: Float = 0f): Param {
            val p = Param(defaultValue)
            var last = defaultValue
            p.supplier = {
                if (values.hasNext()) {
                    last = values.next()
                }
                last
            }
            return p
        }
    }

    open override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        val sup = supplier
        val m = mod

        if (sup != null) {
            // ── Lambda mode: evaluate once, interpolate across buffer ──
            val target = ctx.sup()
            if (current.isNaN()) current = target   // first call: snap

            if (m != null) {
                m.render(output, numFrames, ctx)
                if (current == target) {
                    Buf.addScalar(output, target, 0, numFrames)
                } else {
                    val step = (target - current) / numFrames
                    var v = current
                    for (i in 0 until numFrames) {
                        output[i] += v
                        v += step
                    }
                }
            } else {
                if (current == target) {
                    Buf.fill(output, target, 0, numFrames)
                } else {
                    val step = (target - current) / numFrames
                    var v = current
                    for (i in 0 until numFrames) {
                        output[i] = v
                        v += step
                    }
                }
            }
            current = target
        } else {
            // ── Constant mode (original behaviour) ──────────────────
            if (m != null) {
                m.render(output, numFrames, ctx)
                if (rawBase != 0f) Buf.addScalar(output, rawBase, 0, numFrames)
            } else {
                Buf.fill(output, rawBase, 0, numFrames)
            }
        }
        if (numFrames > 0) {
            observedValue = output[numFrames - 1]
        }
    }

    internal fun isGraphControlDynamic(): Boolean = supplier != null || mod != null

    internal fun tickGraphControl(context: GraphBindingContext) = Unit

    internal fun refreshGraphControl(context: GraphBindingContext) {
        if (!isGraphControlDynamic()) {
            observedValue = null
        }
    }

    internal fun evaluateControl(ctx: SynthContext): Float {
        val base = supplier?.invoke(ctx) ?: rawBase
        val modulation = mod ?: return base
        val buf = ctx.acquireBuf(1)
        try {
            modulation.render(buf, 1, ctx)
            return base + buf[0]
        } finally {
            ctx.releaseBuf(buf)
        }
    }

    /** Convenience: set modulation and return `this` for chaining. */
    fun modulate(signal: Signal): Param { mod = signal; return this }

    override operator fun plus(value: Float): Param = OffsetParam(this, value)

    override operator fun plus(value: Double): Param = this + value.toFloat()

    operator fun plus(value: Int): Param = this + value.toFloat()

    override operator fun minus(value: Float): Param = OffsetParam(this, -value)

    override operator fun minus(value: Double): Param = this - value.toFloat()

    operator fun minus(value: Int): Param = this - value.toFloat()

    protected fun publishObservedValue(value: Float?) {
        observedValue = value
    }

    override fun toString(): String =
        "Param(base=$base, supplier=${supplier != null}, mod=${mod != null})"
}

private class OffsetParam(
    private val source: Param,
    private val offset: Float
) : Param(source.base + offset) {
    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        source.render(output, numFrames, ctx)
        if (offset != 0f) {
            Buf.addScalar(output, offset, 0, numFrames)
        }
        if (numFrames > 0) {
            publishObservedValue(output[numFrames - 1])
        }
    }

    override fun toString(): String = "OffsetParam(source=$source, offset=$offset)"
}

class ParamScope internal constructor() {
    internal var currentContext: SynthContext? = null
    internal var yieldedValue: Float = 0f
    internal var hasYielded: Boolean = false
    internal var nextStep: Continuation<Unit>? = null

    val context: SynthContext
        get() = currentContext ?: error("Param builder is not currently running inside an audio context")

    val time: Float
        get() = context.time

    val sampleRate: Float
        get() = context.sampleRate

    val bufferSize: Int
        get() = context.bufferSize

    val cycleId: Long
        get() = context.cycleId

    val inverseSampleRate: Float
        get() = context.inverseSampleRate

    val nyquist: Float
        get() = context.nyquist

    val linkTiming: LinkTimingSnapshot?
        get() = context.linkTiming

    val linkBeat: Double
        get() = context.linkBeat

    val linkPhase: Double
        get() = context.linkPhase

    val linkTempo: Double
        get() = context.linkTempo

    val linkPeers: Long
        get() = context.linkPeers

    fun acquireBuf(size: Int = bufferSize): FloatArray = context.acquireBuf(size)

    fun releaseBuf(buf: FloatArray) {
        context.releaseBuf(buf)
    }

    suspend fun yield(value: Float) {
        suspendCoroutine<Unit> { continuation ->
            yieldedValue = value
            hasYielded = true
            nextStep = continuation
        }
    }

    suspend fun yieldAll(values: Iterator<Float>) {
        while (values.hasNext()) yield(values.next())
    }

    suspend fun yieldAll(values: Iterable<Float>) = yieldAll(values.iterator())

    suspend fun yieldAll(values: Sequence<Float>) = yieldAll(values.iterator())
}

private class ParamCoroutineDriver(
    private val block: suspend ParamScope.() -> Any?
) {
    private val scope = ParamScope()
    private var nextStep: Continuation<Unit>? = null
    private var completed = false
    private var completionValue: Any? = null
    private var failure: Throwable? = null
    private var hasEverYielded = false

    fun next(ctx: SynthContext): Float? {
        failure?.let { throw it }
        if (nextStep == null) {
            if (hasEverYielded && completed) return null
            createFreshCoroutine()
        }

        scope.currentContext = ctx
        scope.hasYielded = false
        completionValue = null

        val step = nextStep ?: return null
        nextStep = null
        step.resume(Unit)

        failure?.let { throw it }
        if (scope.hasYielded) {
            hasEverYielded = true
            nextStep = scope.nextStep
            scope.nextStep = null
            return scope.yieldedValue
        }

        if (!completed) {
            error("Param builder neither returned a value nor yielded one")
        }

        if (hasEverYielded) return null

        return when (val value = completionValue) {
            is Number -> value.toFloat()
            null -> 0f
            else -> error("Param builder returned ${value::class.simpleName}; expected a Number or yield(...)")
        }
    }

    private fun createFreshCoroutine() {
        completed = false
        completionValue = null
        failure = null
        nextStep = block.createCoroutine(scope, object : Continuation<Any?> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<Any?>) {
                completed = true
                completionValue = result.getOrNull()
                failure = result.exceptionOrNull()
            }
        })
    }
}

private class ParamCoroutineState(
    private val driver: ParamCoroutineDriver
) {
    private var last = 0f

    fun next(ctx: SynthContext): Float = synchronized(this) {
        driver.next(ctx)?.also { last = it } ?: last
    }
}

private object ParamCoroutineStates {
    private val states = WeakHashMap<Any, MutableMap<SynthContext, ParamCoroutineState>>()

    fun stateFor(
        owner: Any,
        block: suspend ParamScope.() -> Any?,
        ctx: SynthContext
    ): ParamCoroutineState = synchronized(this) {
        val byContext = states.getOrPut(owner) { WeakHashMap() }
        byContext.getOrPut(ctx) { ParamCoroutineState(ParamCoroutineDriver(block)) }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  SignalProcessor – base for filters / effects with an input
// ═══════════════════════════════════════════════════════════════════


abstract class SignalProcessor : Signal() {
    /** The signal feeding this processor. */
    var input: Signal = Const(0f)
}

// ═══════════════════════════════════════════════════════════════════
//  Built-in combinators
// ═══════════════════════════════════════════════════════════════════


class Const(val value: Float) : Signal() {
    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        Buf.fill(output, value, 0, numFrames)
    }
}


internal class Scale(private val src: Signal, private val scalar: Float) : Signal(), LiveGraphInspectable {
    private var tmp = FloatArray(0)
    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        src.render(output, numFrames, ctx)
        Buf.scale(output, scalar, 0, numFrames)
    }

    override fun liveGraphChildren(): Iterable<LiveGraphEdge> = listOf(
        LiveGraphEdge("src") { src },
        LiveGraphEdge("scalar") { scalar }
    )
}


internal class Negate(private val src: Signal) : Signal(), LiveGraphInspectable {
    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        src.render(output, numFrames, ctx)
        Buf.scale(output, -1f, 0, numFrames)
    }

    override fun liveGraphChildren(): Iterable<LiveGraphEdge> = listOf(
        LiveGraphEdge("src") { src }
    )
}


internal class BinaryOp(
    private val a: Signal,
    private val b: Signal,
    private val kind: Kind
) : Signal(), LiveGraphInspectable {
    enum class Kind { ADD, SUB, MUL, DIV }

    private var tmp = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (tmp.size < numFrames) tmp = FloatArray(numFrames)
        a.render(output, numFrames, ctx)
        b.render(tmp, numFrames, ctx)
        when (kind) {
            Kind.ADD -> Buf.add(output, tmp, 0, numFrames)
            Kind.SUB -> Buf.sub(output, tmp, 0, numFrames)
            Kind.MUL -> Buf.mul(output, tmp, 0, numFrames)
            Kind.DIV -> Buf.div(output, tmp, 0, numFrames)
        }
    }

    override fun liveGraphChildren(): Iterable<LiveGraphEdge> = listOf(
        LiveGraphEdge("a") { a },
        LiveGraphEdge("b") { b },
        LiveGraphEdge("kind") { kind.name }
    )
}


class Mix(vararg sources: Signal) : Signal(), LiveGraphInspectable {
    private val srcs = sources.toMutableList()
    private var tmp = FloatArray(0)

    fun add(signal: Signal): Mix { srcs += signal; return this }

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (tmp.size < numFrames) tmp = FloatArray(numFrames)
        Buf.clear(output, 0, numFrames)
        for (s in srcs) {
            s.render(tmp, numFrames, ctx)
            Buf.add(output, tmp, 0, numFrames)
        }
    }

    override fun liveGraphChildren(): Iterable<LiveGraphEdge> = listOf(
        LiveGraphEdge("sources") { srcs }
    )
}

interface StereoRenderable {
    fun render(outL: FloatArray, outR: FloatArray, numFrames: Int, ctx: SynthContext)
}

/**
 * Stereo equivalent of [Signal]'s per-cycle memoization.
 *
 * Any stateful stereo generator should extend this rather than implementing
 * [StereoRenderable] directly, otherwise reusing it twice in one audio block
 * can advance its internal state twice.
 */
abstract class StereoNode : StereoRenderable {
    private var cacheL: FloatArray = FloatArray(0)
    private var cacheR: FloatArray = FloatArray(0)
    private var cacheId: Long = -1L

    final override fun render(outL: FloatArray, outR: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (ctx.cycleId != cacheId) {
            if (cacheL.size < numFrames) {
                cacheL = FloatArray(numFrames)
                cacheR = FloatArray(numFrames)
            }
            Buf.clear(cacheL, 0, numFrames)
            Buf.clear(cacheR, 0, numFrames)
            generateStereo(cacheL, cacheR, numFrames, ctx)
            cacheId = ctx.cycleId
        }
        System.arraycopy(cacheL, 0, outL, 0, numFrames)
        System.arraycopy(cacheR, 0, outR, 0, numFrames)
    }

    protected abstract fun generateStereo(outL: FloatArray, outR: FloatArray, numFrames: Int, ctx: SynthContext)

    override fun toString(): String = synthPrettyObject(this)
}

class StereoHold(
    private val source: StereoRenderable
) : StereoNode(), LiveGraphInspectable {
    override fun generateStereo(outL: FloatArray, outR: FloatArray, numFrames: Int, ctx: SynthContext) {
        source.render(outL, outR, numFrames, ctx)
    }

    override fun liveGraphChildren(): Iterable<LiveGraphEdge> = listOf(
        LiveGraphEdge("source") { source }
    )
}

fun StereoRenderable.hold(): StereoRenderable = if (this is StereoNode) this else StereoHold(this)

class StereoSignal(
    val left: Signal,
    val right: Signal
) : StereoNode() {
    override fun generateStereo(outL: FloatArray, outR: FloatArray, numFrames: Int, ctx: SynthContext) {
        left.render(outL, numFrames, ctx)
        right.render(outR, numFrames, ctx)
    }

    override fun toString(): String = synthPrettyObject(
        this,
        "left" to left,
        "right" to right
    )
}


class Pan(
    private val source: Signal,
    val pan: Param = Param(0f)
) : StereoNode(), LiveGraphInspectable {
    private var panBuf = FloatArray(0)
    private var srcBuf = FloatArray(0)

    override fun generateStereo(outL: FloatArray, outR: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (panBuf.size < numFrames) {
            panBuf = FloatArray(numFrames)
            srcBuf = FloatArray(numFrames)
        }
        source.render(srcBuf, numFrames, ctx)
        pan.render(panBuf, numFrames, ctx)
        val piOver4 = (Math.PI / 4.0).toFloat()
        for (i in 0 until numFrames) {
            val p = panBuf[i].coerceIn(-1f, 1f)
            val angle = p * piOver4
            outL[i] = srcBuf[i] * kotlin.math.cos(angle + piOver4)
            outR[i] = srcBuf[i] * kotlin.math.sin(angle + piOver4)
        }
    }

    override fun liveGraphChildren(): Iterable<LiveGraphEdge> = listOf(
        LiveGraphEdge("source") { source },
        LiveGraphEdge("pan") { pan }
    )
}
