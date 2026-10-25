package afield.animation.gesture

import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.document.graph.box
import afield.runtime.execution.plus
import afield.runtime.lifecycle.loop
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem
import afield.ui.notation.with
import afield.ui.visualization.gesture

import afield.animation.keyframes.Keyframe
import afield.animation.keyframes.KeyframeEvaluator
import afield.animation.keyframes.keyframes
import afield.audio.synth.*
import afield.data.table.times
import afield.document.graph.Box
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import afield.runtime.lifecycle.end
import afield.runtime.lifecycle.map
import afield.tools.docs.format
import afield.ui.output.templates.filter
import afield.ui.visualization.GestureHandle

import afield.math.linalg.Vector2f
import kotlin.math.abs
import kotlin.math.max

/**
 * Gesture takes and keyframe lanes, in both directions: `bakeGesture` turns a take recorded on a
 * pad into two editable lanes, and `lanesToTake` renders lanes back into a take a pad accepts.
 *
 * A take recorded by `gesture(...)` is a run of (t, x, y) samples that `GestureHandle.at` joins with
 * straight lines. Baking keeps the fewest of those samples that stay within a tolerance of the
 * take, as `linear` keys in two lanes that share their key times. Because the keys are a subset of
 * the samples and both are joined by straight lines, the largest difference anywhere in time is the
 * largest difference at a sample, so the tolerance holds at every t, not only at the samples.
 */

/** How well a pair of lanes matches a take: errors in pad units, worst axis at each moment. */
data class GestureFit(
    /** Largest difference between take and lanes, over a dense resample of the take's length. */
    val maxError: Double,
    /** Mean of that difference over the same resample. */
    val meanError: Double,
    /** Keys in each lane (the lanes share their key times). */
    val keyCount: Int,
    /** Samples in the take. */
    val sampleCount: Int
) {
    override fun toString() =
        "GestureFit(keys=$keyCount of $sampleCount samples, max=${"%.4f".format(maxError)}, mean=${"%.4f".format(meanError)})"
}

/** The x and y lanes baked from a take. */
data class GestureLanes(val x: List<Keyframe>, val y: List<Keyframe>)

/**
 * The indices of the samples of [take] (flattened (t, x, y) triples) to keep so that neither axis
 * strays more than [tolerance] from the take: Ramer–Douglas–Peucker over both axes at once, measured
 * vertically (at the same time), which is how a lane is evaluated. The first and last samples are
 * always kept, so the lanes span the take's whole loop.
 */
fun reduceTakeIndices(take: DoubleArray, tolerance: Double): List<Int> {
    val n = take.size / 3
    if (n <= 2) return (0 until n).toList()
    val keep = BooleanArray(n)
    keep[0] = true
    keep[n - 1] = true
    val stack = ArrayDeque<Pair<Int, Int>>()
    stack.addLast(0 to n - 1)
    while (stack.isNotEmpty()) {
        val (a, b) = stack.removeLast()
        if (b - a < 2) continue
        val ta = take[a * 3]
        val tb = take[b * 3]
        var worst = -1.0
        var worstAt = -1
        for (i in a + 1 until b) {
            val t = take[i * 3]
            val f = if (tb > ta) (t - ta) / (tb - ta) else 0.0
            val ex = abs(take[a * 3 + 1] + (take[b * 3 + 1] - take[a * 3 + 1]) * f - take[i * 3 + 1])
            val ey = abs(take[a * 3 + 2] + (take[b * 3 + 2] - take[a * 3 + 2]) * f - take[i * 3 + 2])
            val e = max(ex, ey)
            if (e > worst) { worst = e; worstAt = i }
        }
        if (worst > tolerance) {
            keep[worstAt] = true
            stack.addLast(a to worstAt)
            stack.addLast(worstAt to b)
        }
    }
    // Samples at the same time (a take can hold two) would make a vertical jump; keep both, the
    // lane's `linear` evaluation takes the later one, as `at` does.
    return (0 until n).filter { keep[it] }
}

/** Bakes [take] (flattened (t, x, y) triples) into two `linear` lanes; see [reduceTakeIndices]. */
fun takeToLanes(take: DoubleArray, tolerance: Double): GestureLanes {
    val idx = reduceTakeIndices(take, tolerance)
    return GestureLanes(
        idx.map { Keyframe(take[it * 3], take[it * 3 + 1], "linear") },
        idx.map { Keyframe(take[it * 3], take[it * 3 + 2], "linear") }
    )
}

/** Where [take] is at [t], joined by straight lines as `GestureHandle.at` does, without looping. */
internal fun takeAt(take: DoubleArray, t: Double): Pair<Double, Double> {
    val n = take.size / 3
    if (n == 0) return 0.0 to 0.0
    if (t <= take[0]) return take[1] to take[2]
    if (t >= take[(n - 1) * 3]) return take[(n - 1) * 3 + 1] to take[(n - 1) * 3 + 2]
    var lo = 0
    var hi = n - 1
    while (hi - lo > 1) {
        val mid = (lo + hi) / 2
        if (take[mid * 3] <= t) lo = mid else hi = mid
    }
    val t0 = take[lo * 3]
    val t1 = take[hi * 3]
    val f = if (t1 > t0) ((t - t0) / (t1 - t0)).coerceIn(0.0, 1.0) else 0.0
    return (take[lo * 3 + 1] + (take[hi * 3 + 1] - take[lo * 3 + 1]) * f) to
        (take[lo * 3 + 2] + (take[hi * 3 + 2] - take[lo * 3 + 2]) * f)
}

/**
 * Measures [x] and [y] against [take]: at every sample time and at [between] evenly spaced moments
 * between neighbouring samples, the worse axis's difference.
 */
fun measureGestureFit(take: DoubleArray, x: KeyframeEvaluator, y: KeyframeEvaluator, between: Int = 8): GestureFit {
    val n = take.size / 3
    if (n == 0) return GestureFit(0.0, 0.0, x.getKeyframeCount(), 0)
    var worst = 0.0
    var sum = 0.0
    var count = 0
    fun probe(t: Double) {
        val (tx, ty) = takeAt(take, t)
        val e = max(abs(tx - x.evaluateValueAt(t)), abs(ty - y.evaluateValueAt(t)))
        worst = max(worst, e)
        sum += e
        count++
    }
    for (i in 0 until n - 1) {
        val t0 = take[i * 3]
        val t1 = take[(i + 1) * 3]
        for (k in 0 until between) probe(t0 + (t1 - t0) * k / between)
    }
    probe(take[(n - 1) * 3])
    return GestureFit(worst, sum / count, x.getKeyframeCount(), n)
}

/**
 * Renders two lanes into a take that `gesture(initialTake = ...)` or [GestureHandle.setTake] accepts:
 * [samplesPerSecond] evenly spaced samples from the first key to the last of either lane, plus every
 * key time, so corners stay sharp and bezier segments come out as smooth runs of short lines. Time
 * starts at the earlier lane's first key; a take always starts at 0, so it is shifted there.
 */
fun lanesToTake(x: KeyframeEvaluator, y: KeyframeEvaluator, samplesPerSecond: Double = 60.0): List<Pair<Double, Vector2f>> {
    val times = (x.getKeyframeTimes() + y.getKeyframeTimes()).sorted()
    if (times.isEmpty()) return emptyList()
    val start = times.first()
    val end = times.last()
    val steps = ((end - start) * samplesPerSecond).toInt().coerceIn(1, 4000)
    val all = (times + (0..steps).map { start + (end - start) * it / steps })
        .sorted()
        .fold(mutableListOf<Double>()) { acc, t -> if (acc.isEmpty() || t - acc.last() > 1e-9) acc.add(t); acc }
        .take(4096)
    return all.map { t ->
        (t - start) to Vector2f(x.evaluateValueAt(t).toFloat(), y.evaluateValueAt(t).toFloat())
    }
}

/**
 * Bakes the take recorded on [pad] into two editable lanes, `x` and `y` by default, shown in this
 * box's keyframe editors and saved with it. The lanes share a time axis and their key times, so a
 * Cmd-drag across a moment selects it in both. [tolerance] is in pad units: no point of the lanes is
 * further than that from the take, on either axis. Returns the two lanes; their keys replace whatever
 * the lanes held. With no take recorded, the lanes are left alone.
 *
 * ```kotlin
 * val (x, y) = bakeGesture(pad, tolerance = 0.02)
 * ```
 */
fun Box.bakeGesture(
    pad: GestureHandle,
    tolerance: Double = 0.02,
    xName: String = "x",
    yName: String = "y",
    channel: String? = null
): Pair<KeyframeEvaluator, KeyframeEvaluator> {
    val x = keyframes(channel, xName, defaultTimes = null)
    val y = keyframes(channel, yName, defaultTimes = null)
    if (pad.hasTake) {
        val lanes = pad.toKeyframes(tolerance)
        x.setKeys(lanes.x)
        y.setKeys(lanes.y)
    }
    return x to y
}
