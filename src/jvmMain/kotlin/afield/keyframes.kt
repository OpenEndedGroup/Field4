package afield

import afield.server.Inspector
import io.ktor.websocket.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * KeyframeEvaluator - Evaluates animation curves defined by keyframes
 *
 * Supports multiple interpolation types:
 * - linear: Linear interpolation between keyframes
 * - hold: Holds the value until the next keyframe
 * - bezierFree: Free-form bezier curve with independent handles
 * - bezierFlat: Bezier curve with flat (horizontal) handles
 * - bezierContinuous: Bezier curve with continuous handles
 */
class KeyframeEvaluator(jsonString: String) {

    lateinit var session: WebSocketSession

    private var keyframes: List<Keyframe>
    private var cachedSamples: List<Sample>? = null

    init {
        val json = Json { ignoreUnknownKeys = true }
        keyframes = json.decodeFromString<List<Keyframe>>(jsonString).sortedBy { it.time }
    }

    fun set(jsonString: String) {
        val json = Json { ignoreUnknownKeys = true }
        keyframes = json.decodeFromString<List<Keyframe>>(jsonString).sortedBy { it.time }
        cachedSamples = null
    }

    fun setTime(t: Double) {
        inMain {
            session.send("""{"type":"playhead", "time":${t}}""")
        }
    }

    fun send() {
        inMain {
            session.send("""{"type":"keyframes", "keyframes":${Json.encodeToString(keyframes)}}""")
        }
    }

    /**
     * Evaluates the animation curve value at a given time
     * @param time The time at which to evaluate the curve
     * @return The interpolated value at the given time
     */
    fun evaluateValueAt(time: Double): Double {
        if (cachedSamples == null) {
            cachedSamples = sampleCurve(stepsPerSegment = 96)
        }

        val samples = cachedSamples ?: return 0.0

        if (samples.isEmpty()) return 0.0
        if (time <= samples.first().time) return samples.first().value

        val last = samples.last()
        if (time >= last.time) return last.value

        // Binary search for the sample
        var low = 0
        var high = samples.size - 1

        while (low <= high) {
            val mid = (low + high) / 2
            val sample = samples[mid]

            if (abs(sample.time - time) < EPSILON) {
                return sample.value
            }

            if (sample.time < time) {
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        // Interpolate between samples
        val left = max(0, high)
        val right = min(samples.size - 1, low)
        val segment = samples[right].time - samples[left].time

        if (segment < EPSILON) return samples[left].value

        val t = (time - samples[left].time) / segment
        return samples[left].value + (samples[right].value - samples[left].value) * t
    }

    /**
     * Returns the time range over which the keyframes are defined
     * @return Pair of (minTime, maxTime)
     */
    fun getTimeRange(): Pair<Double, Double> {
        if (keyframes.isEmpty()) {
            return Pair(0.0, 0.0)
        }
        return Pair(keyframes.first().time, keyframes.last().time)
    }

    /**
     * Samples the entire curve into discrete points
     */
    private fun sampleCurve(stepsPerSegment: Int = 48): List<Sample> {
        if (keyframes.isEmpty()) return emptyList()

        val samples = mutableListOf(Sample(keyframes[0].time, keyframes[0].value))

        for (i in 1 until keyframes.size) {
            val prev = keyframes[i - 1]
            val next = keyframes[i]

            when (prev.type) {
                "hold" -> {
                    val span = max(next.time - prev.time, EPSILON)
                    for (step in 1..stepsPerSegment) {
                        val t = step.toDouble() / stepsPerSegment
                        val time = prev.time + span * t
                        val value = if (step == stepsPerSegment) next.value else prev.value
                        samples.add(Sample(time, value))
                    }
                }

                "linear" -> {
                    val span = max(next.time - prev.time, EPSILON)
                    for (step in 1..stepsPerSegment) {
                        val t = step.toDouble() / stepsPerSegment
                        val time = prev.time + span * t
                        val value = prev.value + (next.value - prev.value) * t
                        samples.add(Sample(time, value))
                    }
                }

                else -> { // bezier types
                    val span = max(next.time - prev.time, EPSILON)
                    val controls = buildRationalControls(prev, next, span)

                    for (step in 1..stepsPerSegment) {
                        val u = step.toDouble() / stepsPerSegment
                        val point = rationalBezierPoint(controls, u)

                        // Skip if time goes backwards
                        if (point.x <= samples.last().time) {
                            continue
                        }

                        samples.add(Sample(point.x, point.y))
                    }
                }
            }
        }

        return samples
    }

    private fun buildRationalControls(prev: Keyframe, next: Keyframe, span: Double): BezierControls {
        val delta = next.value - prev.value

        val p0 = ControlPoint(prev.time, prev.value, 1.0)
        val p3 = ControlPoint(next.time, next.value, 1.0)

        var p1 = resolveControlPoint(prev, next, "out", span, delta)
        var p2 = resolveControlPoint(next, prev, "in", span, delta)

        // Ensure p1.x <= p2.x
        if (p1.x > p2.x - EPSILON) {
            val mid = (p1.x + p2.x) / 2
            p1 = p1.copy(x = clamp(mid - EPSILON, prev.time + EPSILON, next.time - EPSILON))
            p2 = p2.copy(x = clamp(mid + EPSILON, prev.time + EPSILON, next.time - EPSILON))
        }

        return BezierControls(p0, p1, p2, p3)
    }

    private fun resolveControlPoint(
        node: Keyframe,
        other: Keyframe,
        key: String,
        span: Double,
        delta: Double
    ): ControlPoint {
        val handle = if (key == "out") node.handles?.out else node.handles?.`in`

        val fallbackRatio = 0.3
        val defaultTimeOffset = span * if (key == "out") fallbackRatio else -fallbackRatio
        val defaultValueOffset = delta * if (key == "out") fallbackRatio else -fallbackRatio

        val timeOffset = handle?.time ?: defaultTimeOffset
        val valueOffset = handle?.value ?: defaultValueOffset

        val minTime = min(node.time, other.time) + EPSILON
        val maxTime = max(node.time, other.time) - EPSILON

        val x = clamp(node.time + timeOffset, minTime, maxTime)
        val y = node.value + valueOffset
        val w = controlWeightFromHandle(handle, span)

        return ControlPoint(x, y, w)
    }

    private fun controlWeightFromHandle(handle: Handle?, span: Double): Double {
        if (handle == null) return 1.0

        val length = hypot(handle.time, handle.value)
        val baseline = max(span, EPSILON)

        return clamp(1.0 + length / baseline, 0.2, 6.0)
    }

    private fun rationalBezierPoint(controls: BezierControls, t: Double): Point {
        val mt = 1 - t

        val b0 = mt * mt * mt
        val b1 = 3 * mt * mt * t
        val b2 = 3 * mt * t * t
        val b3 = t * t * t

        val weightSum = b0 * controls.p0.w + b1 * controls.p1.w +
                b2 * controls.p2.w + b3 * controls.p3.w

        val x = (b0 * controls.p0.w * controls.p0.x +
                b1 * controls.p1.w * controls.p1.x +
                b2 * controls.p2.w * controls.p2.x +
                b3 * controls.p3.w * controls.p3.x) / weightSum

        val y = (b0 * controls.p0.w * controls.p0.y +
                b1 * controls.p1.w * controls.p1.y +
                b2 * controls.p2.w * controls.p2.y +
                b3 * controls.p3.w * controls.p3.y) / weightSum

        return Point(x, y)
    }

    private fun clamp(value: Double, min: Double, max: Double): Double {
        return when {
            value < min -> min
            value > max -> max
            else -> value
        }
    }

    companion object {
        private const val EPSILON = 1e-6
    }
}

// Data classes for deserialization and internal use

@Serializable
data class Keyframe(
    val time: Double,
    val value: Double,
    val type: String,
    val handles: Handles? = null
)

@Serializable
data class Handles(
    val `in`: Handle,
    val out: Handle
)

@Serializable
data class Handle(
    val time: Double = 0.0,
    val value: Double = 0.0
)

// Internal data classes

private data class Sample(
    val time: Double,
    val value: Double
)

private data class ControlPoint(
    val x: Double,
    val y: Double,
    val w: Double
)

private data class BezierControls(
    val p0: ControlPoint,
    val p1: ControlPoint,
    val p2: ControlPoint,
    val p3: ControlPoint
)

private data class Point(
    val x: Double,
    val y: Double
)

val knownKeyframeTargets = mutableMapOf<String, KeyframeEvaluator>()

@RunsAt(installHandlers)
fun installKeyframeHandler() {
    rawHandlers.getOrPut("__keyframes__") { mutableSetOf() }.add { text ->
        val s = thisSession.get()
        try {
            val t = JSONObject(text)
            if (t.has("debug_uid") && t.has("type") && t.getString("type") == "hello") {
                knownKeyframeTargets[t.getString("debug_uid")]?.session = s
                knownKeyframeTargets[t.getString("debug_uid")]?.send()
            }
            if (t.has("debug_uid") && t.has("type") && t.getString("type") == "keyframes") {
                knownKeyframeTargets[t.getString("debug_uid")]?.session = s
                knownKeyframeTargets[t.getString("debug_uid")]?.set(t.getJSONArray("keyframes").toString())
            }
            Handled.yes
        } catch (e: Exception) {
            Handled.no
        }

    }
}


fun Box.keyframes(channel: String = "debug", name: String, a: Any?): KeyframeEvaluator {
    val aa = a ?: "null"
    val ln = Inspector.linkFor(aa, "/keyframe")
    val uid = this.id + "_" + name
    display(
        channel, """
<iframe src='${ln}&uid=${uid}' style='border:none;padding:0;margin:0'>
"""
    )
    val eval = knownKeyframeTargets.computeIfAbsent(uid) { KeyframeEvaluator("[]") }
    return eval
}

