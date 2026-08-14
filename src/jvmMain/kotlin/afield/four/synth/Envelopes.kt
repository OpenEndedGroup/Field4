@file:afield.CompletionCategory("Synthesis")
package afield.four.synth

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

// ═══════════════════════════════════════════════════════════════════
//  Gate – simple on/off trigger signal
// ═══════════════════════════════════════════════════════════════════

/**
 * A gate signal: outputs 1.0 while open, 0.0 while closed.
 * Thread-safe – call [open] / [close] from any thread.
 */
class Gate : Signal() {
    @Volatile var isOpen: Boolean = false

    fun open()  { isOpen = true  }
    fun close() { isOpen = false }
    fun trigger() { isOpen = true }

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        Buf.fill(output, if (isOpen) 1f else 0f, 0, numFrames)
    }
}

// ═══════════════════════════════════════════════════════════════════
//  ADSR – attack / decay / sustain / release envelope
// ═══════════════════════════════════════════════════════════════════

/**
 * Classic ADSR envelope generator.  Driven by a [gate] signal:
 * - Gate > 0 → begin attack → decay → sustain (hold)
 * - Gate ≤ 0 → begin release from current level
 *
 * All times are in **seconds**.  Curvature parameters let you shape
 * each segment: 1.0 = linear, <1 = exponential (fast start),
 * >1 = logarithmic (slow start).
 *
 * ```kotlin
 * val env = ADSR(gate = myGate, attack = 0.01f, decay = 0.1f,
 *                sustain = 0.7f, release = 0.3f)
 * val output = oscillator * env
 * ```
 */
class ADSR(
    val gate: Signal = Gate(),
    val attack:  Param = Param(0.01f),
    val decay:   Param = Param(0.1f),
    val sustain: Param = Param(0.7f),
    val release: Param = Param(0.5f),
    /** Shape curve: 1 = linear, 0.5 = fast-exponential, 2 = slow-log. */
    val curve: Float = 1f
) : Signal() {

    private enum class Stage { IDLE, ATTACK, DECAY, SUSTAIN, RELEASE }

    private var stage = Stage.IDLE
    private var level = 0f
    private var stageTime = 0f       // seconds elapsed in current stage
    private var releaseStart = 0f    // level when release began
    private var prevGate = 0f
    internal val vizLevelTrace = ScalarTrace(capacity = 4096, stride = 8)
    internal val vizGateTrace = ScalarTrace(capacity = 4096, stride = 8)
    @Volatile internal var vizStageLabel: String = Stage.IDLE.name
    @Volatile internal var vizCurrentLevel: Float = 0f

    private var gateBuf = FloatArray(0)
    private var aBuf = FloatArray(0)
    private var dBuf = FloatArray(0)
    private var sBuf = FloatArray(0)
    private var rBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (gateBuf.size < numFrames) {
            gateBuf = FloatArray(numFrames)
            aBuf = FloatArray(numFrames); dBuf = FloatArray(numFrames)
            sBuf = FloatArray(numFrames); rBuf = FloatArray(numFrames)
        }
        gate.render(gateBuf, numFrames, ctx)
        attack.render(aBuf, numFrames, ctx)
        decay.render(dBuf, numFrames, ctx)
        sustain.render(sBuf, numFrames, ctx)
        release.render(rBuf, numFrames, ctx)

        val dt = ctx.inverseSampleRate
        for (i in 0 until numFrames) {
            val g = gateBuf[i]
            val a = aBuf[i].coerceAtLeast(0.001f)
            val d = dBuf[i].coerceAtLeast(0.001f)
            val s = sBuf[i].coerceIn(0f, 1f)
            val r = rBuf[i].coerceAtLeast(0.001f)

            // Gate edge detection
            if (g > 0f && prevGate <= 0f) {
                stage = Stage.ATTACK; stageTime = 0f
            } else if (g <= 0f && prevGate > 0f) {
                stage = Stage.RELEASE; stageTime = 0f; releaseStart = level
            }
            prevGate = g

            when (stage) {
                Stage.IDLE -> level = 0f
                Stage.ATTACK -> {
                    level = shapedRamp(stageTime / a, curve)
                    if (stageTime >= a) { stage = Stage.DECAY; stageTime = 0f }
                }
                Stage.DECAY -> {
                    level = 1f - (1f - s) * shapedRamp(stageTime / d, curve)
                    if (stageTime >= d) { stage = Stage.SUSTAIN; stageTime = 0f }
                }
                Stage.SUSTAIN -> level = s
                Stage.RELEASE -> {
                    level = releaseStart * (1f - shapedRamp(stageTime / r, curve))
                    if (stageTime >= r) { stage = Stage.IDLE; stageTime = 0f; level = 0f }
                }
            }
            output[i] = level
            vizCurrentLevel = level
            vizStageLabel = stage.name
            vizLevelTrace.record(level)
            vizGateTrace.record(if (g > 0f) 1f else 0f)
            stageTime += dt
        }
    }

    /** Retrigger: reset to attack stage (callable from audio thread). */
    fun retrigger() { stage = Stage.ATTACK; stageTime = 0f }
}

/** Attempt shaped ramp: t^curve, clamped to [0,1]. */
private fun shapedRamp(t: Float, curve: Float): Float {
    val tc = t.coerceIn(0f, 1f)
    return if (curve == 1f) tc else tc.toDouble().pow(curve.toDouble()).toFloat()
}

// ═══════════════════════════════════════════════════════════════════
//  AR – attack / release one-shot envelope
// ═══════════════════════════════════════════════════════════════════

/**
 * Simplified two-stage envelope.  Triggers on rising edge of [gate].
 * Runs attack→release once, then stays at zero until re-triggered.
 */
class AR(
    val gate: Signal = Gate(),
    val attack:  Param = Param(0.01f),
    val release: Param = Param(0.3f),
    val curve: Float = 1f
) : Signal() {
    private enum class Stage { IDLE, ATTACK, RELEASE }

    private var stage = Stage.IDLE
    private var level = 0f
    private var stageTime = 0f
    private var prevGate = 0f
    internal val vizLevelTrace = ScalarTrace(capacity = 4096, stride = 8)
    internal val vizGateTrace = ScalarTrace(capacity = 4096, stride = 8)
    @Volatile internal var vizStageLabel: String = Stage.IDLE.name
    @Volatile internal var vizCurrentLevel: Float = 0f
    private var gateBuf = FloatArray(0)
    private var aBuf = FloatArray(0)
    private var rBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (gateBuf.size < numFrames) {
            gateBuf = FloatArray(numFrames)
            aBuf = FloatArray(numFrames); rBuf = FloatArray(numFrames)
        }
        gate.render(gateBuf, numFrames, ctx)
        attack.render(aBuf, numFrames, ctx)
        release.render(rBuf, numFrames, ctx)
        val dt = ctx.inverseSampleRate
        for (i in 0 until numFrames) {
            val g = gateBuf[i]
            if (g > 0f && prevGate <= 0f) { stage = Stage.ATTACK; stageTime = 0f }
            prevGate = g

            val a = aBuf[i].coerceAtLeast(0.001f)
            val r = rBuf[i].coerceAtLeast(0.001f)
            when (stage) {
                Stage.IDLE -> level = 0f
                Stage.ATTACK -> {
                    level = shapedRamp(stageTime / a, curve)
                    if (stageTime >= a) { stage = Stage.RELEASE; stageTime = 0f }
                }
                Stage.RELEASE -> {
                    level = 1f - shapedRamp(stageTime / r, curve)
                    if (stageTime >= r) { stage = Stage.IDLE; stageTime = 0f; level = 0f }
                }
            }
            output[i] = level
            vizCurrentLevel = level
            vizStageLabel = stage.name
            vizLevelTrace.record(level)
            vizGateTrace.record(if (g > 0f) 1f else 0f)
            stageTime += dt
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Line – linear ramp from A to B over duration
// ═══════════════════════════════════════════════════════════════════

/**
 * Generates a linear ramp from [start] to [end] over [duration] seconds,
 * then holds at [end].  Call [trigger] to restart.
 */
class Line(
    val start: Float = 0f,
    val end: Float = 1f,
    val duration: Float = 1f
) : Signal() {
    private var time = 0f
    @Volatile var running = true

    fun trigger() { time = 0f; running = true }

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        val dt = ctx.inverseSampleRate
        for (i in 0 until numFrames) {
            val t = (time / duration).coerceIn(0f, 1f)
            output[i] = start + (end - start) * t
            if (running) time += dt
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  BreakpointEnvelope – multi-segment envelope
// ═══════════════════════════════════════════════════════════════════

/**
 * Multi-segment envelope defined by breakpoints.
 *
 * ```kotlin
 * val env = BreakpointEnvelope(
 *     Breakpoint(0f, 0f),        // start at 0
 *     Breakpoint(0.01f, 1f),     // attack to 1 in 10ms
 *     Breakpoint(0.1f, 0.6f),    // decay to 0.6 over 90ms
 *     Breakpoint(1.0f, 0.6f),    // sustain at 0.6 for 900ms
 *     Breakpoint(1.5f, 0f)       // release to 0 over 500ms
 * )
 * ```
 */
data class Breakpoint(val time: Float, val value: Float, val curve: Float = 1f)

class BreakpointEnvelope(
    vararg points: Breakpoint
) : Signal() {
    private val bp = points.sortedBy { it.time }.toTypedArray()
    private var time = 0f
    @Volatile var running = true
    internal val vizLevelTrace = ScalarTrace(capacity = 4096, stride = 8)
    @Volatile internal var vizCurrentTimeSec: Float = 0f
    @Volatile internal var vizRunning: Boolean = true

    fun trigger() { time = 0f; running = true }

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        val dt = ctx.inverseSampleRate
        for (i in 0 until numFrames) {
            output[i] = evaluate(time)
            vizCurrentTimeSec = time
            vizRunning = running
            vizLevelTrace.record(output[i])
            if (running && time < (bp.lastOrNull()?.time ?: 0f)) time += dt
        }
    }

    private fun evaluate(t: Float): Float {
        if (bp.isEmpty()) return 0f
        if (t <= bp.first().time) return bp.first().value
        if (t >= bp.last().time) return bp.last().value
        // find segment
        for (j in 0 until bp.size - 1) {
            if (t >= bp[j].time && t < bp[j + 1].time) {
                val seg = bp[j + 1].time - bp[j].time
                val frac = if (seg > 0f) (t - bp[j].time) / seg else 1f
                val shaped = shapedRamp(frac, bp[j + 1].curve)
                return bp[j].value + (bp[j + 1].value - bp[j].value) * shaped
            }
        }
        return bp.last().value
    }
}

// ═══════════════════════════════════════════════════════════════════
//  EnvelopeFollower – amplitude follower
// ═══════════════════════════════════════════════════════════════════

/**
 * Tracks the amplitude envelope of an input signal.
 * Uses separate attack and release smoothing coefficients.
 *
 * ```kotlin
 * val follower = EnvelopeFollower(input = micSignal,
 *     attackTime = Param(0.005f), releaseTime = Param(0.05f))
 * ```
 */
class EnvelopeFollower(
    val input: Signal,
    val attackTime:  Param = Param(0.005f),
    val releaseTime: Param = Param(0.05f),
    val mode: Mode = Mode.PEAK
) : Signal() {
    enum class Mode { PEAK, RMS }

    var env = 0f
    internal val vizEnvelopeTrace = ScalarTrace(capacity = 4096, stride = 8)
    @Volatile internal var vizCurrentLevel: Float = 0f
    @Volatile internal var vizModeLabel: String = mode.name
    private var inBuf = FloatArray(0)
    private var aBuf  = FloatArray(0)
    private var rBuf  = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (inBuf.size < numFrames) {
            inBuf = FloatArray(numFrames)
            aBuf  = FloatArray(numFrames)
            rBuf  = FloatArray(numFrames)
        }
        input.render(inBuf, numFrames, ctx)
        attackTime.render(aBuf, numFrames, ctx)
        releaseTime.render(rBuf, numFrames, ctx)
        val invSr = ctx.inverseSampleRate.toDouble()

        for (i in 0 until numFrames) {
            val sample = when (mode) {
                Mode.PEAK -> abs(inBuf[i])
                Mode.RMS  -> inBuf[i] * inBuf[i]
            }
            val at = aBuf[i].coerceAtLeast(0.0001f).toDouble()
            val rt = rBuf[i].coerceAtLeast(0.0001f).toDouble()
            val attackCoeff  = exp(-invSr / at).toFloat()
            val releaseCoeff = exp(-invSr / rt).toFloat()

            val coeff = if (sample > env) attackCoeff else releaseCoeff
            env = coeff * env + (1f - coeff) * sample
            output[i] = if (mode == Mode.RMS) sqrt(env) else env
            vizCurrentLevel = output[i]
            vizModeLabel = mode.name
            vizEnvelopeTrace.record(output[i])
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Slew – slew rate limiter (portamento / glide)
// ═══════════════════════════════════════════════════════════════════

/**
 * Limits how fast a signal can change.
 * [riseTime] and [fallTime] are in seconds (time to traverse full range).
 */
class Slew(
    val input: Signal,
    val riseTime: Param = Param(0.01f),
    val fallTime: Param = Param(0.01f)
) : Signal() {
    private var current = 0f
    private var inBuf   = FloatArray(0)
    private var riseBuf = FloatArray(0)
    private var fallBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (inBuf.size < numFrames) {
            inBuf = FloatArray(numFrames); riseBuf = FloatArray(numFrames); fallBuf = FloatArray(numFrames)
        }
        input.render(inBuf, numFrames, ctx)
        riseTime.render(riseBuf, numFrames, ctx)
        fallTime.render(fallBuf, numFrames, ctx)
        val invSr = ctx.inverseSampleRate
        for (i in 0 until numFrames) {
            val target = inBuf[i]
            val rise = riseBuf[i].coerceAtLeast(0.0001f)
            val fall = fallBuf[i].coerceAtLeast(0.0001f)
            val maxUp = invSr / rise
            val maxDown = invSr / fall
            val diff = target - current
            current += diff.coerceIn(-maxDown, maxUp)
            output[i] = current
        }
    }
}
