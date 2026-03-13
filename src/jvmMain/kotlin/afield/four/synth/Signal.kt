package afield.four.synth

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
}

// ═══════════════════════════════════════════════════════════════════
//  Signal – the universal audio atom
// ═══════════════════════════════════════════════════════════════════


abstract class Signal {

    // ── per-cycle cache ─────────────────────────────────────────────

    private var _cache: FloatArray = FloatArray(0)
    private var _cacheId: Long = -1L

    /**
     * Evaluate (or replay cache) into [output].
     * Call this from consuming nodes – *never* call [generate] directly.
     */
    fun render(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (ctx.cycleId != _cacheId) {
            if (_cache.size < numFrames) _cache = FloatArray(numFrames)
            generate(_cache, numFrames, ctx)
            _cacheId = ctx.cycleId
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
    operator fun plus(value: Float): Signal   = BinaryOp(this, Const(value), BinaryOp.Kind.ADD)
    operator fun minus(other: Signal): Signal = BinaryOp(this, other, BinaryOp.Kind.SUB)
    operator fun minus(value: Float): Signal  = BinaryOp(this, Const(value), BinaryOp.Kind.SUB)
    operator fun times(other: Signal): Signal = BinaryOp(this, other, BinaryOp.Kind.MUL)
    operator fun times(value: Float): Signal  = Scale(this, value)
    operator fun div(other: Signal): Signal   = BinaryOp(this, other, BinaryOp.Kind.DIV)
    operator fun div(value: Float): Signal    = Scale(this, 1f / value)
    operator fun div(processor: SignalProcessor): Signal = through(processor)
    operator fun unaryMinus(): Signal         = Negate(this)

    /** Pipe this signal through a [SignalProcessor] (filter, effect, …). */
    fun through(processor: SignalProcessor): Signal {
        processor.input = this
        return processor
    }

    infix fun mix(other: Signal): Signal = this + other
}


operator fun Float.plus(sig: Signal): Signal  = sig + this
operator fun Float.times(sig: Signal): Signal = sig * this
operator fun Float.minus(sig: Signal): Signal = Const(this) - sig

// ═══════════════════════════════════════════════════════════════════
//  Param – modulatable parameter
// ═══════════════════════════════════════════════════════════════════


class Param(var base: Float = 0f) : Signal() {

    /**
     * Optional lambda evaluated once per cycle to produce a target value.
     * Receiver is [SynthContext], giving access to [SynthContext.time],
     * [SynthContext.sampleRate], etc.
     */
    var supplier: (SynthContext.() -> Float)? = null

    /** Optional modulation signal added on top of [base] / supplier. */
    var mod: Signal? = null

    // Interpolation state (NaN = uninitialised → snap to first value)
    private var current: Float = Float.NaN

    companion object {
        /**
         * Create a parameter driven by a lambda, with per-buffer
         * linear interpolation.  The lambda receives [SynthContext]
         * as `this`, so you can use [SynthContext.time] etc.
         *
         * ```kotlin
         * val cutoff = Param { 1000f + 500f * sin(time * 3f) }
         * ```
         */
        operator fun invoke(supplier: SynthContext.() -> Float): Param {
            val p = Param()
            p.supplier = supplier
            return p
        }
    }

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
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
                if (base != 0f) Buf.addScalar(output, base, 0, numFrames)
            } else {
                Buf.fill(output, base, 0, numFrames)
            }
        }
    }

    /** Convenience: set modulation and return `this` for chaining. */
    fun modulate(signal: Signal): Param { mod = signal; return this }

    override fun toString(): String =
        "Param(base=$base, supplier=${supplier != null}, mod=${mod != null})"
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


internal class Scale(private val src: Signal, private val scalar: Float) : Signal() {
    private var tmp = FloatArray(0)
    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        src.render(output, numFrames, ctx)
        Buf.scale(output, scalar, 0, numFrames)
    }
}


internal class Negate(private val src: Signal) : Signal() {
    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        src.render(output, numFrames, ctx)
        Buf.scale(output, -1f, 0, numFrames)
    }
}


internal class BinaryOp(
    private val a: Signal,
    private val b: Signal,
    private val kind: Kind
) : Signal() {
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
}


class Mix(vararg sources: Signal) : Signal() {
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
            generateStereo(cacheL, cacheR, numFrames, ctx)
            cacheId = ctx.cycleId
        }
        System.arraycopy(cacheL, 0, outL, 0, numFrames)
        System.arraycopy(cacheR, 0, outR, 0, numFrames)
    }

    protected abstract fun generateStereo(outL: FloatArray, outR: FloatArray, numFrames: Int, ctx: SynthContext)
}

class StereoHold(
    private val source: StereoRenderable
) : StereoNode() {
    override fun generateStereo(outL: FloatArray, outR: FloatArray, numFrames: Int, ctx: SynthContext) {
        source.render(outL, outR, numFrames, ctx)
    }
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
}


class Pan(
    private val source: Signal,
    val pan: Param = Param(0f)
) : StereoNode() {
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
}
