@file:afield.CompletionCategory("Math")

package afield.stdlib

import afield.four.buffers.HasUniform
import afield.four.buffers.ProgressingUniform
import afield.stdlib.linalg.Vector2f
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * [smoothTo] wraps a target-producing lambda so that reading it eases toward the target instead
 * of jumping to it.
 *
 * The returned `() -> T` is privately also a `HasUniform`/`ProgressingUniform`, so it can sit
 * where Field expects a uniform and be re-evaluated per frame. It implements
 * [NeedsStartingValue], and whoever binds it is responsible for calling `startAt` with the
 * current value — until that happens the interpolant simply reports the target and marks itself
 * complete.
 *
 * Interpolation is linear and only covers `Float`, `Double`, `Int`, `Long` and
 * `Vector2f`/`Vector3f`/`Vector4f`; any other type completes immediately at the target. Progress
 * is either time-based (`time` seconds), a fixed number of evaluations (`steps`, default 100), or
 * frame-based by supplying `clock = frameClock(fps) { frameNumber }`. Time-based interpolation uses
 * `System.nanoTime` by default; an injected clock makes offline and fixed-rate rendering independent
 * of scheduler pauses. The interpolation is one-shot: once it reaches the target it stays there.
 *
 * [inertialTo] is the other half of the library, for the case [smoothTo] handles badly: a target that
 * keeps moving. It takes the same `time`/`steps`/`clock` arguments, but eases on a smoothstep whose
 * timing it re-solves whenever the target changes, so a re-aim carries the velocity it already had
 * instead of restarting from a standstill.
 */

/**
 * Implemented by a value that can only interpolate once something tells it where it is starting from.
 *
 * [smoothTo]'s interpolant is the one implementation: whatever binds it — a uniform slot, a
 * structure-bound property — is expected to call [startAt] with the current value before the first
 * read. Until that happens the interpolant has nothing to ease *from* and simply reports the target.
 */
interface NeedsStartingValue<T> {
    /**
     * Sets the value to ease away from, and restarts the interpolation.
     *
     * Accepts either the value itself or a `() -> value` lambda, which is re-read on each
     * evaluation — pass the lambda when the origin is itself still moving. Calling this again mid-way
     * re-starts the ease from the new origin rather than continuing.
     */
    fun startAt(a: Any?)
}

private class SmoothInterpolant<T>(
    private val durationSeconds: Double?,
    private val steps: Int?,
    private val clock: (() -> Double)?,
    private val target: () -> T
) : NeedsStartingValue<T>, HasUniform<T>, ProgressingUniform {

    private var start: Any? = null
    private var startedAt: Double? = null
    private var stepIndex = 0
    private var completed = false

    override val isComplete: Boolean
        get() = completed

    override fun startAt(a: Any?) {
        start = a
        startedAt = null
        stepIndex = 0
        completed = false
    }

    override fun evaluateUniform(): T {
        if (completed) return target()

        val resolvedStart = resolveStart()
        val targetValue = target()

        if (resolvedStart == null) {
            completed = true
            return targetValue
        }

        val alpha = progress()
        val value = interpolate(resolvedStart, targetValue, alpha)

        if (alpha >= 1.0) completed = true
        return value
    }

    @Suppress("UNCHECKED_CAST")
    private fun interpolate(startValue: Any?, endValue: T, alpha: Double): T {
        if (alpha <= 0.0) return (startValue ?: endValue) as T
        if (alpha >= 1.0) return endValue

        return when (endValue) {
            is Float -> lerpNumber(startValue, endValue.toDouble(), alpha).toFloat() as T
            is Double -> lerpNumber(startValue, endValue, alpha) as T
            is Int -> lerpNumber(startValue, endValue.toDouble(), alpha).toInt() as T
            is Long -> lerpNumber(startValue, endValue.toDouble(), alpha).toLong() as T
            is Vector2f -> interpolateVector(startValue, endValue, alpha, 2) as T
            is Vector3f -> interpolateVector(startValue, endValue, alpha, 3) as T
            is Vector4f -> interpolateVector(startValue, endValue, alpha, 4) as T
            else -> {
                completed = true
                endValue
            }
        }
    }

    private fun lerpNumber(startValue: Any?, end: Double, alpha: Double): Double {
        val s = (startValue as? Number)?.toDouble()
        if (s == null) {
            completed = true
            return end
        }
        return s + (end - s) * alpha
    }

    private fun interpolateVector(startValue: Any?, end: Any, alpha: Double, dim: Int): Any {
        val startComponents = toComponents(startValue, dim) ?: run {
            completed = true
            return end
        }
        val endComponents = toComponents(end, dim) ?: run {
            completed = true
            return end
        }

        val result = FloatArray(dim) { idx ->
            startComponents[idx] + (endComponents[idx] - startComponents[idx]) * alpha.toFloat()
        }

        return when (dim) {
            2 -> Vector2f(result[0], result[1])
            3 -> Vector3f(result[0], result[1], result[2])
            else -> Vector4f(result[0], result[1], result[2], result[3])
        }
    }

    private fun toComponents(value: Any?, dim: Int): FloatArray? {
        val base = when (value) {
            is Vector2f -> floatArrayOf(value.x, value.y)
            is Vector3f -> floatArrayOf(value.x, value.y, value.z)
            is Vector4f -> floatArrayOf(value.x, value.y, value.z, value.w)
            is Number -> floatArrayOf(value.toFloat())
            null -> return null
            else -> return null
        }
        if (base.size == dim) return base
        val out = FloatArray(dim)
        for (i in 0 until dim) {
            out[i] = if (i < base.size) base[i] else 0f
        }
        return out
    }

    private fun resolveStart(): Any? {
        val s = start
        return when (s) {
            is Function0<*> -> s()
            else -> s
        }
    }

    private fun progress(): Double {
        durationSeconds?.let { duration ->
            if (duration <= 0.0) return 1.0
            val nowSeconds = clock?.invoke() ?: System.nanoTime() / 1_000_000_000.0
            if (startedAt == null) startedAt = nowSeconds
            val elapsed = nowSeconds - (startedAt ?: nowSeconds)
            return (elapsed / duration).coerceIn(0.0, 1.0)
        }

        val totalSteps = steps?.takeIf { it > 0 } ?: 1
        val fraction = (stepIndex.toDouble() / totalSteps.toDouble()).coerceIn(0.0, 1.0)
        if (stepIndex < totalSteps) stepIndex++
        return fraction
    }
}

/**
 * Converts an authoritative rendered-frame counter into the seconds clock accepted by [smoothTo].
 *
 * The returned clock advances by exactly `1 / fps` when [frameNumber] advances by one. Repeated
 * reads in the same frame return the same time, which is the important difference from `steps`:
 * uniforms may be evaluated more than once while producing a single rendered frame.
 *
 * For a deterministic 24 fps render:
 *
 * ```kotlin
 * val eased = smoothTo<Float>(
 *     time = 1.0,
 *     steps = null,
 *     clock = frameClock(24.0) { renderer.frameCounter },
 * ) { 1.0f }
 * ```
 */
fun frameClock(fps: Double, frameNumber: () -> Number): () -> Double {
    require(fps.isFinite() && fps > 0.0) { "fps must be finite and positive" }
    return { frameNumber().toDouble() / fps }
}

/**
 * Wraps a target-producing lambda so that reading it *eases toward* the target instead of jumping
 * there — the one-word way to take the snap out of a value assignment.
 *
 * `m.color = smoothTo { rgb(1, 0, 0) }` eases to red across the next hundred evaluations instead of
 * arriving on the next frame. Counted exactly: the first evaluation reports the starting value unmoved
 * (`0/100`) and the hundred-and-first is the first to report the target, so `steps` is the number of
 * *increments*, not of readings. Give [time] in **seconds** for a timed ease, or leave it null and
 * let [steps] count evaluations. Timed easing uses `System.nanoTime` by default. Supply [clock] to
 * use a deterministic timeline instead; `frameClock(24.0) { renderer.frameCounter }` advances by
 * rendered frames and is suitable for a constant-24-fps film render. [time] wins if both it and
 * [steps] are given.
 *
 * One-shot and linear: it arrives and stays, with no easing curve and no overshoot, and re-aims only
 * if whoever bound it calls [NeedsStartingValue.startAt] again. Interpolation covers `Float`,
 * `Double`, `Int`, `Long` and `Vector2f`/`Vector3f`/`Vector4f`; any other type is delivered
 * immediately at the target rather than animated.
 */
fun <T> smoothTo(
    time: Double? = null,
    steps: Int? = 100,
    clock: (() -> Double)? = null,
    a: () -> T
): () -> T {
    return SmoothInterpolant(time, steps, clock, a)
}

/**
 * The arithmetic behind [inertialTo], for driving an inertial blend by hand — in a game loop, or on
 * something the interpolant itself cannot carry, like a quaternion.
 *
 * The whole idea is that a smoothstep can be *entered part-way*. [solve] picks the time and scale
 * that put the curve at a given distance from its destination *and* at a given velocity, so a blend
 * can be re-aimed mid-flight with no discontinuity in either. Driving it is three lines — solve on a
 * re-aim, advance time, read the curve:
 *
 * ```kotlin
 * if (goal != aimedAt) {
 *     val solved = InertialEasing.solve(goal - value, InertialEasing.smoothstepDt(et, es))
 *     et = solved.t; es = solved.s
 *     offset = value - InertialEasing.smoothstep(et, es)
 *     aimedAt = goal
 * }
 * et += dt / blendTime
 * value = offset + InertialEasing.smoothstep(et, es)
 * ```
 *
 * After Daniel Holden, ["Inertial Easing"](https://theorangeduck.com/page/inertial-easing).
 */
object InertialEasing {

    /**
     * A time and a scale for [smoothstep], as returned by [solve].
     *
     * [t] is where on the curve to start — it can be negative — and [s] is what to scale the curve
     * by. The pair is arranged so that `s - smoothstep(t, s)` is the distance still to travel and
     * `smoothstepDt(t, s)` is the velocity there.
     */
    data class Solved(val t: Double, val s: Double)

    /**
     * The scaled smoothstep `s * (3t² - 2t³)`, held at `s` once `t` passes 1.
     *
     * Not the GLSL `smoothstep(edge0, edge1, x)`: this is the curve itself, indexed by its own time.
     * `t` below zero is deliberately not clamped, because the cubic keeps rising there and that is
     * where [solve]'s overshooting branch lives.
     */
    fun smoothstep(t: Double, s: Double): Double = s * if (t > 1.0) 1.0 else 3 * t * t - 2 * t * t * t

    /** The derivative of [smoothstep] with respect to `t` — the velocity the curve is travelling at. */
    fun smoothstepDt(t: Double, s: Double): Double = s * if (t > 1.0) 0.0 else 6 * t - 6 * t * t

    /**
     * Finds the [smoothstep] that is [x] away from its destination and moving at velocity [v].
     *
     * [x] is signed distance *to* the target (`target - current`) and [v] is the current velocity in
     * the same units, measured per unit of blend time rather than per second: if a blend takes half a
     * second and the value is moving at 3 units/second, [v] is 1.5. Advancing `t` from the returned
     * value up to 1 then closes the gap, arriving at rest, and everything past 1 sits on the target.
     *
     * There are two roots. The usual one decelerates into the target inside a single blend time. The
     * other is negative, starting the curve before its own zero crossing, which shoots past the
     * target and comes back, taking around 1.5 blend times. That branch is taken only when the
     * negative root falls between `-0.5` and `-(0.5 + overshoot)`, which is when the incoming speed
     * is far larger than the distance left and stopping cleanly would mean an implausible jolt. The
     * default `0.05` limits overshoot to those cases; pass `0.0` to never overshoot.
     *
     * [eps] is both the speed below which the value counts as at rest — answered with `t = 0, s = x`,
     * a plain ease from a standstill — and the floor under the discriminant.
     */
    fun solve(x: Double, v: Double, overshoot: Double = 0.05, eps: Double = 1e-8): Solved {
        if (abs(v) < eps) return Solved(0.0, x)

        // The curve is odd about the origin, so only the positive-distance case needs solving.
        if (x < 0.0) {
            val mirrored = solve(-x, -v, overshoot, eps)
            return Solved(mirrored.t, -mirrored.s)
        }

        val discriminant = sqrt(max((6 * x - v) * (6 * x - v) + 8 * v * v, eps))
        val t0 = (v - 6 * x + discriminant) / (4 * v)
        val t1 = (v - 6 * x - discriminant) / (4 * v)

        val t = if (t1 < -0.5 && t1 > -(0.5 + overshoot)) t1 else t0

        val velocityAtT = smoothstepDt(t, 1.0)
        return Solved(t, if (abs(velocityAtT) < eps) 0.0 else v / velocityAtT)
    }
}

private class InertialInterpolant<T>(
    private val durationSeconds: Double?,
    private val steps: Int?,
    private val clock: (() -> Double)?,
    private val overshoot: Double,
    private val target: () -> T
) : NeedsStartingValue<T>, HasUniform<T>, ProgressingUniform {

    private var start: Any? = null
    private var started = false
    private var passthrough = false
    private var lastClock: Double? = null

    // One smoothstep per scalar component: where we are on it, how it is scaled, and the constant
    // it is added to. `current` is the value they produce, kept in doubles so that an Int or a
    // half-precision-bound Float does not quantise the motion it is easing along.
    private var easedTime = DoubleArray(0)
    private var scale = DoubleArray(0)
    private var offset = DoubleArray(0)
    private var current = DoubleArray(0)
    private var aimedAt: DoubleArray? = null

    /**
     * Always false. An interpolant that re-aims has to be evaluated to notice that its target has
     * moved, and a uniform that reports completion stops being written — so this one never claims to
     * be finished, even while sitting still on its target.
     */
    override val isComplete: Boolean
        get() = false

    override fun startAt(a: Any?) {
        start = a
        started = false
        aimedAt = null
        lastClock = null
    }

    override fun evaluateUniform(): T {
        val targetValue = target()
        if (passthrough) return targetValue

        val dimension = componentCount(targetValue)
        val aim = if (dimension == null) null else doubleComponents(targetValue, dimension)
        if (aim == null) {
            passthrough = true
            return targetValue
        }

        if (!started) restAt(startingComponents(aim))
        else if (current.size != aim.size) restAt(aim.copyOf())

        if (aimedAt?.contentEquals(aim) != true) reaim(aim)

        val step = advance()
        for (i in current.indices) {
            easedTime[i] += step
            current[i] = offset[i] + InertialEasing.smoothstep(easedTime[i], scale[i])
        }

        return compose(targetValue, current)
    }

    /**
     * The value to start at rest on. Unlike [SmoothInterpolant] the origin lambda is read once and
     * not re-read, since from then on the interpolant's own state is what it is easing from. With no
     * usable origin it starts on the target itself, which is what makes `inertialTo` usable on its
     * own rather than only when something calls [startAt].
     */
    private fun startingComponents(aim: DoubleArray): DoubleArray {
        val resolved = start.let { if (it is Function0<*>) it() else it }
        return doubleComponents(resolved, aim.size) ?: aim.copyOf()
    }

    private fun restAt(at: DoubleArray) {
        easedTime = DoubleArray(at.size)
        scale = DoubleArray(at.size)
        offset = at.copyOf()
        current = at.copyOf()
        aimedAt = null
        started = true
    }

    /** Re-solves each component's curve for the new target, keeping position and velocity. */
    private fun reaim(aim: DoubleArray) {
        for (i in current.indices) {
            val solved = InertialEasing.solve(
                aim[i] - current[i],
                InertialEasing.smoothstepDt(easedTime[i], scale[i]),
                overshoot
            )
            easedTime[i] = solved.t
            scale[i] = solved.s
            offset[i] = current[i] - InertialEasing.smoothstep(solved.t, solved.s)
        }
        aimedAt = aim.copyOf()
    }

    /** How far to advance eased time, in blend times, since the last evaluation. */
    private fun advance(): Double {
        durationSeconds?.let { duration ->
            val nowSeconds = clock?.invoke() ?: System.nanoTime() / 1_000_000_000.0
            val previousSeconds = lastClock ?: nowSeconds
            lastClock = nowSeconds
            return ((nowSeconds - previousSeconds) / max(duration, 1e-4)).coerceAtLeast(0.0)
        }

        val totalSteps = steps?.takeIf { it > 0 } ?: 1
        return 1.0 / totalSteps
    }

    @Suppress("UNCHECKED_CAST")
    private fun compose(shapedLike: T, c: DoubleArray): T = when (shapedLike) {
        is Float -> c[0].toFloat() as T
        is Double -> c[0] as T
        is Int -> c[0].toInt() as T
        is Long -> c[0].toLong() as T
        is Vector2f -> Vector2f(c[0].toFloat(), c[1].toFloat()) as T
        is Vector3f -> Vector3f(c[0].toFloat(), c[1].toFloat(), c[2].toFloat()) as T
        else -> Vector4f(c[0].toFloat(), c[1].toFloat(), c[2].toFloat(), c[3].toFloat()) as T
    }
}

private fun componentCount(value: Any?): Int? = when (value) {
    is Float, is Double, is Int, is Long -> 1
    is Vector2f -> 2
    is Vector3f -> 3
    is Vector4f -> 4
    else -> null
}

private fun doubleComponents(value: Any?, dimension: Int): DoubleArray? {
    val base = when (value) {
        is Vector2f -> doubleArrayOf(value.x.toDouble(), value.y.toDouble())
        is Vector3f -> doubleArrayOf(value.x.toDouble(), value.y.toDouble(), value.z.toDouble())
        is Vector4f -> doubleArrayOf(value.x.toDouble(), value.y.toDouble(), value.z.toDouble(), value.w.toDouble())
        is Number -> doubleArrayOf(value.toDouble())
        else -> return null
    }
    if (base.size == dimension) return base
    return DoubleArray(dimension) { if (it < base.size) base[it] else 0.0 }
}

/**
 * Wraps a target-producing lambda so that reading it eases toward the target *with inertia* — the
 * version of [smoothTo] for a target that keeps moving.
 *
 * `m.position = inertialTo(time = 0.3) { mouse.position }` follows the mouse. Every time the target
 * changes, the blend is re-solved from where the value actually is and how fast it is actually
 * travelling, so the value never restarts from a standstill and never jumps: no stutter while the
 * target is being dragged, and a reversal swings round instead of stopping dead. A blend that is not
 * interrupted decelerates onto its target in one blend time, and one that is travelling far too fast
 * to stop will shoot past and come back instead (see [InertialEasing.solve]'s `overshoot`).
 *
 * [time] is the blend time in **seconds**; leave it null and [steps] counts evaluations instead, a
 * blend time of `steps` readings. [time] wins if both are given. Timed blending reads
 * `System.nanoTime` unless [clock] supplies a timeline — `frameClock(24.0) { renderer.frameCounter }`
 * for a deterministic render. Only the *deltas* between clock readings matter here, so a paused clock
 * pauses the motion rather than skipping it.
 *
 * Unlike [smoothTo] this is not one-shot and reports itself as never complete, so it keeps costing an
 * evaluation and a uniform write for as long as it is bound. Reach for [smoothTo] for a value that
 * animates once and settles. Interpolation covers `Float`, `Double`, `Int`, `Long` and
 * `Vector2f`/`Vector3f`/`Vector4f`, easing each component of a vector on its own curve; any other
 * type is delivered immediately at the target rather than animated. If nothing calls
 * [NeedsStartingValue.startAt] it starts at rest on whatever the target first reads as, which is
 * what makes it usable directly and not only as a bound uniform.
 *
 * After Daniel Holden, ["Inertial Easing"](https://theorangeduck.com/page/inertial-easing).
 */
fun <T> inertialTo(
    time: Double? = null,
    steps: Int? = 100,
    clock: (() -> Double)? = null,
    overshoot: Double = 0.05,
    a: () -> T
): () -> T {
    return InertialInterpolant(time, steps, clock, overshoot, a)
}

// usage
// val q = smoothTo { vec(1,2,3,4) }
// but really more like:
// m.foo = smoothTo { vec(3,2,1,3 }
// where m is defined lie var Mesh.foo by BoundFromStructure<Vec4>
// and BoundFromStructure is aware of sourceTo and can call startAt
// with the current value
