package afield

import afield.stdlib.Vec2
import afield.stdlib.Functions.Fn
import io.ktor.websocket.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.json.JSONArray
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

    /** True when the iframe WebSocket is connected and still open. */
    val hasLiveSession: Boolean
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        get() = ::session.isInitialized && !session.outgoing.isClosedForSend

    /** The box ID + name uid this evaluator is associated with */
    var associatedBoxId: String? = null
    var associatedName: String? = null

    private var keyframes: List<Keyframe>
    private var cachedSamples: List<Sample>? = null

    fun isEmpty(): Boolean = keyframes.isEmpty()

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

    // ── Read-only accessors ──────────────────────────────────────────

    /** Returns a defensive copy of the current keyframe list (sorted by time). */
    fun getKeyframes(): List<Keyframe> = keyframes.toList()

    /** Number of keyframes currently defined. */
    fun getKeyframeCount(): Int = keyframes.size

    /** Returns the keyframe at the given index (0-based, sorted by time). */
    fun getKeyframeAt(index: Int): Keyframe = keyframes[index]

    /** Returns times of all keyframes (sorted ascending). */
    fun getKeyframeTimes(): List<Double> = keyframes.map { it.time }

    /** Returns values of all keyframes (in time order). */
    fun getKeyframeValues(): List<Double> = keyframes.map { it.value }

    /** Returns the interpolation types of all keyframes (in time order). */
    fun getKeyframeTypes(): List<String> = keyframes.map { it.type }

    /** Returns keyframes as a list of Vec2(time, value). */
    fun getKeyframePoints(): List<Vec2> = keyframes.map { Vec2(it.time.toFloat(), it.value.toFloat()) }

    // ── Change callback ──────────────────────────────────────────────

    /**
     * Callback invoked whenever the keyframe data changes (e.g. user edits in the UI).
     * Receives the updated list of [Keyframe]s.
     * If it returns an [Fn], that function is displayed as an overlay on the editor.
     * Return `null` to leave the overlay unchanged, or clear it explicitly with [clearOverlay].
     */
    var onChange: ((List<Keyframe>) -> Fn?)? = null

    // ── Derivative ───────────────────────────────────────────────────

    /**
     * Evaluates the numerical derivative (rate of change) of the curve at the given time.
     *
     * Uses a central-difference scheme at the given [time], with an adaptive step size
     * based on the local sample spacing. At the boundaries of the keyframe range the
     * derivative is one-sided (forward or backward difference).
     *
     * @param time the time at which to compute the derivative
     * @param h    half-width of the finite-difference stencil (default: auto from sample density)
     * @return     approximate dValue/dTime at [time]
     */
    fun evaluateDerivativeAt(time: Double, h: Double = 0.0): Double {
        if (keyframes.isEmpty()) return 0.0

        // Auto step: use ~1/4 of the smallest segment gap or a fixed small value
        val step = if (h > 0.0) h else {
            if (keyframes.size < 2) 1e-4
            else {
                var minGap = Double.MAX_VALUE
                for (i in 1 until keyframes.size) {
                    val gap = keyframes[i].time - keyframes[i - 1].time
                    if (gap > EPSILON && gap < minGap) minGap = gap
                }
                (minGap * 0.25).coerceIn(1e-6, 0.01)
            }
        }

        val (tMin, tMax) = getTimeRange()

        // Central difference, falling back to one-sided at boundaries
        return when {
            time - step < tMin -> {
                // Forward difference
                val f0 = evaluateValueAt(time)
                val f1 = evaluateValueAt(time + step)
                (f1 - f0) / step
            }
            time + step > tMax -> {
                // Backward difference
                val f0 = evaluateValueAt(time - step)
                val f1 = evaluateValueAt(time)
                (f1 - f0) / step
            }
            else -> {
                // Central difference (2nd-order accurate)
                val fMinus = evaluateValueAt(time - step)
                val fPlus = evaluateValueAt(time + step)
                (fPlus - fMinus) / (2.0 * step)
            }
        }
    }

    // ── Overlay graph ────────────────────────────────────────────────

    /**
     * Send an overlay curve to the keyframe editor UI. The curve is drawn on top of
     * the main keyframe graph as a non-interactive visual reference.
     *
     * @param samples list of (time, value) points defining the overlay curve
     * @param color   CSS color string for the overlay stroke (default: cyan)
     * @param strokeWidth SVG stroke width (default: 1.5)
     * @param dashArray optional SVG dash pattern string (e.g. "4 2"), null for solid
     * @param id      unique id for this overlay; allows updating/replacing specific overlays
     */
    fun setOverlay(
        samples: List<Vec2>,
        color: String = "#ff9800",
        strokeWidth: Double = 1.5,
        dashArray: String? = "6 3",
        id: String = "default"
    ) {
        val pointsJson = samples.joinToString(",", "[", "]") { v ->
            """{"time":${v.x},"value":${v.y}}"""
        }
        val dashPart = if (dashArray != null) ""","dashArray":"$dashArray"""" else ""
        val json = """{"type":"overlay","id":"$id","samples":$pointsJson,"color":"$color","strokeWidth":$strokeWidth$dashPart}"""
        inMain {
            session.send(json)
        }
    }

    /**
     * Draw an overlay curve from a [Fn], sampled over the keyframe time range.
     *
     * @param fn        the function to sample
     * @param sampleCount number of sample points (default: 200)
     * @param color     CSS color string for the overlay stroke (default: cyan)
     * @param strokeWidth SVG stroke width (default: 1.5)
     * @param dashArray optional SVG dash pattern string (e.g. "4 2"), null for solid
     * @param id        unique id for this overlay; allows updating/replacing specific overlays
     */
    fun setOverlay(
        fn: Fn,
        sampleCount: Int = 200,
        color: String = "#ff9800",
        strokeWidth: Double = 1.5,
        dashArray: String? = "6 3",
        id: String = "default"
    ) {
        val (tMin, tMax) = getTimeRange()
        val step = if (sampleCount <= 1) 0.0 else (tMax - tMin) / (sampleCount - 1).toDouble()
        val pts = List(sampleCount) { i ->
            val t = tMin + step * i
            Vec2(t.toFloat(), fn(t).toFloat())
        }
        setOverlay(pts, color, strokeWidth, dashArray, id)
    }

    /**
     * Remove a specific overlay curve from the editor.
     * @param id the overlay id to remove (default: "default")
     */
    fun clearOverlay(id: String = "default") {
        inMain {
            session.send("""{"type":"clearOverlay","id":"$id"}""")
        }
    }

    /**
     * Remove all overlay curves from the editor.
     */
    fun clearAllOverlays() {
        inMain {
            session.send("""{"type":"clearAllOverlays"}""")
        }
    }

    // ── Sampling ─────────────────────────────────────────────────────

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
    val handles: Handles? = null,
    val locked: Boolean = false
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

/**
 * Load persisted keyframe data for a given box and keyframer name.
 * Returns the JSON string of keyframes, or null if none persisted.
 */
fun loadKeyframeData(box: Box, name: String): String? {
    val raw = box.getPropertyRaw("keyframeData") as? String ?: return null
    return try {
        val allData = org.json.JSONObject(raw)
        if (allData.has(name)) allData.getJSONArray(name).toString() else null
    } catch (e: Exception) {
        null
    }
}

/**
 * Save keyframe data for a given box and keyframer name.
 * Merges with any existing keyframeData on the box.
 */
fun saveKeyframeData(box: Box, name: String, keyframesJson: String) {
    val existing = box.getPropertyRaw("keyframeData") as? String
    val allData = try {
        if (existing != null) org.json.JSONObject(existing) else org.json.JSONObject()
    } catch (e: Exception) {
        org.json.JSONObject()
    }
    allData.put(name, org.json.JSONArray(keyframesJson))
    box.setPropertyRaw("keyframeData", allData.toString())
}

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
                val debugUid = t.getString("debug_uid")
                knownKeyframeTargets[debugUid]?.session = s
                val keyframesJsonStr = t.getJSONArray("keyframes").toString()
                val evaluator = knownKeyframeTargets[debugUid]
                evaluator?.set(keyframesJsonStr)

                // Fire the change callback; if it returns an Fn, show it as an overlay
                if (evaluator != null) {
                    val overlayFn = evaluator.onChange?.invoke(evaluator.getKeyframes())
                    if (overlayFn != null) {
                        evaluator.setOverlay(overlayFn, id = "_onChange")
                    }
                }

                // Persist to box property
                if (evaluator != null && evaluator.associatedBoxId != null && evaluator.associatedName != null) {
                    val boxId = evaluator.associatedBoxId!!
                    val kfName = evaluator.associatedName!!
                    try {
                        val si = thisSessionInfo.get()
                        val box = si?.boxes?.get(boxId)
                        if (box != null) {
                            saveKeyframeData(box, kfName, keyframesJsonStr)
                        }
                    } catch (e: Exception) {
                        // silently ignore persistence errors
                    }
                }
            }
            Handled.yes
        } catch (e: Exception) {
            Handled.no
        }

    }
}


/**
 * Shared implementation for [Box.keyframes] overloads.
 *
 * @param defaultKeyframesJson  a JSON array string (e.g. `[{"time":0,"value":0,"type":"linear"}]`)
 *                              used to seed the editor when no persisted data exists. Pass `"[]"` for no defaults.
 */
private fun Box.keyframesImpl(channel: String, name: String, defaultKeyframesJson: String): KeyframeEvaluator {
    val uid = this.id + "_" + name
    // Use a channel that includes the name so multiple keyframes on the same
    // channel don't overwrite each other's iframes.
    val displayChannel = if (channel == name) channel else "$channel/$name"

    // Only send the display/iframe if the evaluator isn't already live —
    // re-sending it would replace the renderer causing flicker and zoom reset.
    val existing = knownKeyframeTargets[uid]
    val alreadyLive = existing != null && existing.hasLiveSession

    if (!alreadyLive) {
        display(
            displayChannel, """
<iframe src='/keyframe?uid=${uid}' style='border:none;padding:0;margin:0;width:100%;height:100%'>
"""
        )
    }

    // Load persisted keyframe data if available
    val persistedData = loadKeyframeData(this, name)

    val initialJson = persistedData ?: defaultKeyframesJson

    val eval = knownKeyframeTargets.computeIfAbsent(uid) {
        KeyframeEvaluator(initialJson)
    }
    // If evaluator already existed but had no data, and we have persisted/default data, load it
    if (eval.isEmpty() && initialJson != "[]") {
        eval.set(initialJson)
    }
    // Track association for persistence
    eval.associatedBoxId = this.id
    eval.associatedName = name
    return eval
}

/**
 * Creates (or reconnects to) a keyframe curve editor displayed in the given output [channel].
 *
 * @param channel  the output-area channel in which to embed the editor iframe (default `"debug"`)
 * @param name     a unique name for this set of keyframes within the box (used as persistence key)
 * @param defaultTimes  optional list of time positions (e.g. `listOf(0, 10, 30)`) used to seed
 *                      the editor with initial keyframes when no persisted data exists for [name].
 *                      Each time produces a keyframe at value `0.0` with type `"linear"`.
 *                      Once the user edits the curve the persisted data takes precedence.
 * @return a [KeyframeEvaluator] that can be queried with [KeyframeEvaluator.evaluateValueAt]
 */
fun Box.keyframes(channel: String = "debug", name: String, defaultTimes: List<Number>? = null): KeyframeEvaluator {
    val json = if (!defaultTimes.isNullOrEmpty()) {
        defaultTimes.joinToString(",", "[", "]") { t ->
            """{ "time": ${t.toDouble()}, "value": 0.0, "type": "linear" }"""
        }
    } else "[]"
    return keyframesImpl(channel, name, json)
}

/**
 * Creates (or reconnects to) a keyframe curve editor, with default keyframes specified as
 * [Vec2] pairs of `(time, value)`.
 *
 * Example:
 * ```kotlin
 * val n = keyframes("out", "n", listOf(Vec2(0.0, 0.0), Vec2(5.0, 1.0), Vec2(10.0, 0.0)))
 * ```
 *
 * @param channel  the output-area channel (default `"debug"`)
 * @param name     unique persistence key for this keyframe set
 * @param defaults list of [Vec2] where `x` = time, `y` = value; used only when no persisted data exists
 * @return a [KeyframeEvaluator] that can be queried with [KeyframeEvaluator.evaluateValueAt]
 */
@JvmName("keyframesVec2")
fun Box.keyframes(channel: String = "debug", name: String, defaults: List<Vec2>): KeyframeEvaluator {
    val json = if (defaults.isNotEmpty()) {
        defaults.joinToString(",", "[", "]") { v ->
            """{ "time": ${v.x}, "value": ${v.y}, "type": "linear" }"""
        }
    } else "[]"
    return keyframesImpl(channel, name, json)
}
