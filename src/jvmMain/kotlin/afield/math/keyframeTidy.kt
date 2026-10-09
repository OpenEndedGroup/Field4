package afield

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

// KeyframeEvaluator.simplify and .smooth, which do what the editor's Simplify and Smooth sliders do
// (planSimplify and smoothRunValues in keyframe_editor.js).

private val ANCHORING = setOf("hold", "custom")

/**
 * The fewest of [keys] (sorted by time) that stay within [tolerance] of [curve]. Keys go back in one
 * at a time, each where the simplified curve is furthest from the original, until it is within
 * [tolerance] everywhere. A key that stays becomes a continuous bezier whose handles follow the
 * original's slope there; locked, hold and custom keys stay exactly as they are, and so does the key
 * a hold or a custom segment arrives at.
 *
 * [kindOf] is "hold", "custom", "linear" or "bezier" for a type; [segment] is the value at a time of
 * the segment between two keys.
 */
internal fun simplifyKeys(
    keys: List<Keyframe>,
    tolerance: Double,
    kindOf: (String) -> String,
    curve: (Double) -> Double,
    segment: (Keyframe, Keyframe, Double) -> Double,
): List<Keyframe> {
    val n = keys.size
    if (n < 3) return keys
    val asIs = BooleanArray(n) { keys[it].locked || kindOf(keys[it].type) in ANCHORING }
    val kept = BooleanArray(n) { asIs[it] || it == 0 || it == n - 1 || kindOf(keys[it - 1].type) in ANCHORING }

    // The original curve, at its keys and eight times along each segment.
    val refT = buildList {
        for (i in 0 until n - 1) {
            val a = keys[i].time
            val b = keys[i + 1].time
            for (s in 0 until 8) add(a + (b - a) * s / 8.0)
        }
        add(keys.last().time)
    }.toDoubleArray()
    val refV = DoubleArray(refT.size) { curve(refT[it]) }

    fun prevKept(i: Int): Int { for (j in i - 1 downTo 0) if (kept[j]) return j; return -1 }
    fun nextKept(i: Int): Int { for (j in i + 1 until n) if (kept[j]) return j; return -1 }

    // The key at i as it would be with its kept neighbours either side.
    fun shape(i: Int): Keyframe {
        val kf = keys[i]
        if (asIs[i]) return kf
        val a = prevKept(i)
        val b = nextKept(i)
        val spans = listOfNotNull(
            if (a >= 0) kf.time - keys[a].time else null,
            if (b >= 0) keys[b].time - kf.time else null,
        ).filter { it > TIDY_EPSILON }
        if (spans.isEmpty()) return kf
        val near = spans.min()
        val w = near * 0.25
        val t0 = if (i > 0) kf.time - w else kf.time
        val t1 = if (i < n - 1) kf.time + w else kf.time
        val m = if (t1 - t0 > TIDY_EPSILON) (curve(t1) - curve(t0)) / (t1 - t0) else 0.0
        val l = near / 3
        return kf.copy(type = "bezierContinuous", handles = Handles(Handle(-l, -m * l), Handle(l, m * l)))
    }

    class Segment(val b: Int, val err: Double, val at: Double, var stuck: Boolean = false)

    fun measure(a: Int): Segment {
        val b = nextKept(a)
        val start = shape(a)
        val end = shape(b)
        if (kindOf(start.type) == "custom") return Segment(b, 0.0, start.time)
        var err = 0.0
        var at = start.time
        for (k in refT.indices) {
            val t = refT[k]
            if (t <= start.time + TIDY_EPSILON || t >= end.time - TIDY_EPSILON) continue
            val d = abs(segment(start, end, t) - refV[k])
            if (d > err) { err = d; at = t }
        }
        return Segment(b, err, at)
    }

    val segments = HashMap<Int, Segment>()
    for (i in 0 until n - 1) if (kept[i]) segments[i] = measure(i)
    while (true) {
        val (a, seg) = segments.entries.filter { !it.value.stuck }.maxByOrNull { it.value.err } ?: break
        if (seg.err <= tolerance) break
        var j = -1
        for (i in a + 1 until seg.b) {
            if (!kept[i] && (j < 0 || abs(keys[i].time - seg.at) < abs(keys[j].time - seg.at))) j = i
        }
        if (j < 0) { seg.stuck = true; continue }
        kept[j] = true
        // j changes the spans, so the handles, of the keys either side: re-measure what touches them.
        listOf(prevKept(a), a, j, seg.b).forEach { if (it >= 0 && it < n - 1) segments[it] = measure(it) }
    }
    return (0 until n).filter { kept[it] }.map { shape(it) }
}

/**
 * The values of [keys] (sorted by time) smoothed by a Gaussian in time of standard deviation
 * [width]. The ends and locked keys keep their values, and the curve is reflected through the ends
 * so that a key near one is not pulled toward the middle.
 */
internal fun smoothKeys(keys: List<Keyframe>, width: Double): List<Keyframe> {
    val n = keys.size
    if (n < 3 || !(width > TIDY_EPSILON)) return keys
    val times = DoubleArray(n) { keys[it].time }
    val values = DoubleArray(n) { keys[it].value }
    // Each key stands for the time around it, so uneven spacing does not tilt the average; an end
    // stands for its reflection's side too.
    val weightOf = DoubleArray(n) {
        val span = when (it) {
            0 -> times[1] - times[0]
            n - 1 -> times[n - 1] - times[n - 2]
            else -> (times[it + 1] - times[it - 1]) / 2
        }
        max(span, TIDY_EPSILON)
    }
    val reach = width * 3
    // The Gaussian, lowered to reach zero at [reach], so a key just inside or outside it on one side
    // and not the other does not tip the average.
    val edge = exp(-4.5)
    val t0 = times[0]
    val v0 = values[0]
    val tn = times[n - 1]
    val vn = values[n - 1]
    return keys.mapIndexed { i, kf ->
        if (i == 0 || i == n - 1 || kf.locked) return@mapIndexed kf
        val ti = times[i]
        var sum = 0.0
        var total = 0.0
        fun add(t: Double, v: Double, w: Double) {
            val d = t - ti
            if (abs(d) > reach) return
            val g = w * (exp(-(d * d) / (2 * width * width)) - edge)
            sum += g * v
            total += g
        }
        for (j in 0 until n) add(times[j], values[j], weightOf[j])
        // The images of the keys reflected through each end.
        for (j in 1 until n) add(2 * t0 - times[j], 2 * v0 - values[j], weightOf[j])
        for (j in 0 until n - 1) add(2 * tn - times[j], 2 * vn - values[j], weightOf[j])
        if (total > 0) kf.copy(value = sum / total) else kf
    }
}

private const val TIDY_EPSILON = 1e-6
