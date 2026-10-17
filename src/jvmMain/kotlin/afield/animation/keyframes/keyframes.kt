package afield.animation.keyframes

import afield.animation.interpolation.interpolant
import afield.app.diagnostics.named
import afield.app.server.documents
import afield.app.server.thisSessionInfo
import afield.data.table.div
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.runtime.execution.plus
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem
import afield.runtime.lifecycle.then
import afield.ui.notation.string
import afield.ui.notation.with
import afield.ui.output.slider
import afield.ui.visualization.menu

import afield.animation.interpolation.KeyInterpolant
import afield.animation.interpolation.KeySegment
import afield.animation.interpolation.interpolantNamed
import afield.animation.interpolation.interpolantsFor
import afield.animation.interpolation.libraryInterpolants
import afield.app.protocol.Handled
import afield.app.protocol.computeIfAbsent
import afield.app.protocol.handle
import afield.app.protocol.put
import afield.app.protocol.rawHandlers
import afield.app.protocol.send
import afield.app.server.thisSession
import afield.audio.synth.*
import afield.data.table.get
import afield.document.graph.Box
import afield.document.graph.box
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import afield.runtime.lifecycle.RunsAt
import afield.runtime.lifecycle.inMain
import afield.runtime.lifecycle.installHandlers
import afield.runtime.lifecycle.map
import afield.ui.notation.custom
import afield.ui.notation.jsonString
import afield.ui.output.displayOpen
import afield.ui.output.templates.filter
import afield.ui.visualization.h

import afield.app.diagnostics.logging.lprintln
import afield.math.linalg.Vec2
import afield.math.functions.Functions.Fn
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

    // The box itself, so an edit arriving on the editor's websocket — where no session is current —
    // can still be written back to it.
    @Volatile internal var associatedBox: Box? = null

    private var keyframes: List<Keyframe>

    fun isEmpty(): Boolean = keyframes.isEmpty()

    init {
        val json = Json { ignoreUnknownKeys = true }
        keyframes = json.decodeFromString<List<Keyframe>>(jsonString).sortedBy { it.time }
    }

    fun set(jsonString: String) {
        val json = Json { ignoreUnknownKeys = true }
        keyframes = json.decodeFromString<List<Keyframe>>(jsonString).sortedBy { it.time }
    }

    // The playhead and overlays a box last asked for. The editor may not have connected yet, and it
    // reconnects whenever its output panel is redrawn, so these are replayed on every hello rather
    // than sent once and lost.
    @Volatile private var playhead: Double? = null
    private val overlays = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun sendIfLive(message: String) {
        if (!hasLiveSession) return
        inMain {
            session.send(message)
        }
    }

    /** Moves the editor's playhead to [t]: an orange line, and a dot where it crosses the curve. */
    fun setTime(t: Double) {
        if (playhead == t) return
        playhead = t
        sendIfLive("""{"type":"playhead", "time":${t}}""")
    }

    /** Sends the current keys to the editor, with this box's custom key types and their segments. */
    fun send() {
        sendIfLive("""{"type":"keyframes", "keyframes":${Json.encodeToString(keyframes)}, "types":${customTypesJson()}, "segments":${customSegmentsJson()}}""")
    }

    /**
     * The shapes of the custom segments for [keysJson], keys being dragged that are not this
     * evaluator's yet: computed on a throwaway copy, so nothing is saved and nothing else changes.
     */
    internal fun sendPreviewSegments(keysJson: String) {
        val preview = KeyframeEvaluator(keysJson).also { it.associatedBox = associatedBox }
        sendIfLive("""{"type":"segments", "segments":${preview.customSegmentsJson()}}""")
    }

    /** Sends only the shapes of the custom segments, after an edit in the editor changed the keys. */
    internal fun sendCustomSegments() {
        if (keyframes.zipWithNext().none { (a, _) -> segmentKind(a.type) == "custom" }) return
        sendIfLive("""{"type":"segments", "segments":${customSegmentsJson()}}""")
    }

    // Every type beyond the built-in five — the library's, then this box's — with where it sits in
    // the menu and a small drawing of it (0 to 1 across, 0 to 1 up), between made-up neighbours.
    private fun customTypesJson(): String {
        val types = libraryInterpolants.values + interpolantsFor(associatedBox).values
        return types.joinToString(",", "[", "]") { it ->
            val icon = (0..24).joinToString(",", "[", "]") { i ->
                val u = i / 24.0
                val v = it.evaluate(KeySegment(u, u, 0.0, 1.0, 0.0, 1.0, ICON_BEFORE, ICON_AFTER))
                "[${fmt(u)},${fmt(v)}]"
            }
            val e = it.entry
            val meta = if (e == null) """"label":${Json.encodeToString(it.name)},"group":"This box""""
            else """"label":${Json.encodeToString(e.label)},"group":${Json.encodeToString(e.group)}""" +
                (e.family?.let { f -> ""","family":${Json.encodeToString(f)},"variant":${Json.encodeToString(e.variant)}""" } ?: "")
            """{"name":${Json.encodeToString(it.name)},$meta,"icon":$icon}"""
        }
    }

    // The shape of every custom segment, sampled, so the editor (which cannot run Kotlin) can draw it.
    private fun customSegmentsJson(): String = keyframes.zipWithNext()
        .filter { (a, _) -> segmentKind(a.type) == "custom" }
        .joinToString(",", "[", "]") { (a, b) ->
            val pts = (0..64).joinToString(",", "[", "]") { i ->
                val time = a.time + (b.time - a.time) * i / 64.0
                "[${fmt(time)},${fmt(customValue(a, b, time))}]"
            }
            """{"t0":${fmt(a.time)},"v0":${fmt(a.value)},"t1":${fmt(b.time)},"v1":${fmt(b.value)},"type":${Json.encodeToString(a.type)},"samples":$pts}"""
        }

    private fun fmt(d: Double): String = if (d.isFinite()) d.toString() else "0.0"

    /** Everything the editor should show on (re)connecting: keys, overlays, playhead. */
    internal fun sendAll() {
        send()
        overlays.values.forEach(::sendIfLive)
        playhead?.let { sendIfLive("""{"type":"playhead", "time":${it}}""") }
    }

    /**
     * Evaluates the animation curve value at a given time
     * @param time The time at which to evaluate the curve
     * @return The interpolated value at the given time
     */
    fun evaluateValueAt(time: Double): Double {
        val keys = keyframes
        if (keys.isEmpty()) return 0.0
        if (time <= keys.first().time) return keys.first().value
        if (time >= keys.last().time) return keys.last().value
        val (prev, next) = segmentAt(keys, time)
        return segmentValue(prev, next, time)
    }

    // The value at [time] of the segment from [prev] to [next].
    private fun segmentValue(prev: Keyframe, next: Keyframe, time: Double): Double = when (segmentKind(prev.type)) {
        "hold" -> prev.value
        "linear" -> {
            val span = next.time - prev.time
            if (span < EPSILON) next.value else prev.value + (next.value - prev.value) * (time - prev.time) / span
        }
        "custom" -> customValue(prev, next, time)
        else -> {
            val controls = buildRationalControls(prev, next, max(next.time - prev.time, EPSILON))
            rationalBezierPoint(controls, solveBezierParameter(controls, time)).y
        }
    }

    // The two keys either side of [time], which lies strictly inside the keys' range.
    private fun segmentAt(keys: List<Keyframe>, time: Double): Pair<Keyframe, Keyframe> {
        var lo = 0
        var hi = keys.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (keys[mid].time <= time) lo = mid else hi = mid
        }
        return keys[lo] to keys[hi]
    }

    // The curve parameter at which a bezier segment reaches [time]. Its control points' times are
    // kept in order, so time rises monotonically along the segment and bisection converges.
    private fun solveBezierParameter(controls: BezierControls, time: Double): Double {
        var lo = 0.0
        var hi = 1.0
        repeat(52) {
            val mid = (lo + hi) / 2
            if (rationalBezierPoint(controls, mid).x < time) lo = mid else hi = mid
        }
        return (lo + hi) / 2
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
     * If it returns an [Fn], that function replaces the editor's overlay (the one [setOverlay] draws
     * with its default id).
     * Return `null` to leave the overlay unchanged, or clear it explicitly with [clearOverlay].
     */
    var onChange: ((List<Keyframe>) -> Fn?)? = null

    // ── Derivative ───────────────────────────────────────────────────

    /**
     * The rate of change of the curve at [time], in value per unit of time: exact, from the shape of
     * the segment [time] falls in. Zero outside the keys and along a hold. At a key it is the slope
     * of the segment leaving it (arriving, at the last key).
     *
     * @param h    a half-width for a central difference instead; `0.0` (the default) is exact
     */
    fun evaluateDerivativeAt(time: Double, h: Double = 0.0): Double {
        val keys = keyframes
        if (keys.size < 2) return 0.0
        if (h > 0.0) return (evaluateValueAt(time + h) - evaluateValueAt(time - h)) / (2.0 * h)
        if (time < keys.first().time || time > keys.last().time) return 0.0
        val t = time.coerceAtMost(keys.last().time - EPSILON)
        val (prev, next) = segmentAt(keys, t)
        return when (segmentKind(prev.type)) {
            "hold" -> 0.0
            "linear" -> {
                val span = next.time - prev.time
                if (span < EPSILON) 0.0 else (next.value - prev.value) / span
            }
            // A Kotlin lambda has no derivative to read off, so: a central difference inside the segment.
            "custom" -> {
                val hh = max((next.time - prev.time) * 1e-4, 1e-9)
                val a = (t - hh).coerceAtLeast(prev.time)
                val b = (t + hh).coerceAtMost(next.time)
                if (b - a < 1e-12) 0.0 else (customValue(prev, next, b) - customValue(prev, next, a)) / (b - a)
            }
            else -> {
                val controls = buildRationalControls(prev, next, max(next.time - prev.time, EPSILON))
                val (dx, dy) = rationalBezierTangent(controls, solveBezierParameter(controls, t.coerceAtLeast(prev.time)))
                if (abs(dx) < 1e-12) 0.0 else dy / dx
            }
        }
    }

    // ── Editing from code ────────────────────────────────────────────

    /**
     * Replaces the keys with [keys], as an edit would: the editor shows them and they are saved with
     * the box. A bezier key without handles gets the ones the editor derives from its neighbours.
     * [onChange] is not called; it is for edits made in the editor.
     */
    fun setKeys(keys: List<Keyframe>) {
        keyframes = keys.sortedBy { it.time }
        persist()
        send()
    }

    /** Adds a key at [time] with [value] and [type]; see [setKeys]. Returns the new key. */
    fun add(time: Double, value: Double, type: String = "linear"): Keyframe =
        Keyframe(time, value, type).also { setKeys(keyframes + it) }

    /** Removes [key] (compared by value); see [setKeys]. */
    fun remove(key: Keyframe) = setKeys(keyframes - key)

    /** Removes the key at [index], in time order; see [setKeys]. */
    fun removeAt(index: Int) = setKeys(keyframes.filterIndexed { i, _ -> i != index })

    /** Removes every key; see [setKeys]. */
    fun clear() = setKeys(emptyList())

    /**
     * Edits the keys as a list, then saves and shows the result; see [setKeys].
     *
     * ```kotlin
     * curve.edit {
     *     replaceAll { it.copy(value = it.value * 0.5) }   // halve every value
     *     add(Keyframe(6.0, 0.0, "bezierFlat"))
     * }
     * ```
     */
    fun edit(block: MutableList<Keyframe>.() -> Unit) = setKeys(keyframes.toMutableList().apply(block))

    /**
     * Removes every key it can while the curve stays within [tolerance] of where it is now, in the
     * curve's own units, so a dense take becomes a few smooth keys. Keys that stay become
     * `bezierContinuous`, with handles that follow the curve's slope; locked, hold and custom keys
     * stay as they are. The editor's Simplify slider does the same. See [setKeys].
     *
     * ```kotlin
     * curve.simplify(0.01)   // within 0.01 of the take, everywhere
     * ```
     */
    fun simplify(tolerance: Double) = setKeys(
        simplifyKeys(keyframes, tolerance, ::segmentKind, ::evaluateValueAt, ::segmentValue)
    )

    /** [simplify] with the times in [pinned] kept as they are; see [simplifyTogether]. */
    private fun simplifyKeeping(tolerance: Double, pinned: Set<Double>): List<Keyframe> {
        val marked = keyframes.map { if (it.time in pinned && !it.locked) it.copy(locked = true) else it }
        val wasLocked = keyframes.map { it.locked }.zip(keyframes.map { it.time }).filter { it.first }.map { it.second }.toSet()
        return simplifyKeys(marked, tolerance, ::segmentKind, ::evaluateValueAt, ::segmentValue)
            .map { if (it.locked && it.time !in wasLocked) it.copy(locked = false) else it }
    }

    /**
     * Smooths the keys' values with a Gaussian [width] wide in time (its standard deviation), so the
     * jitter in a recorded take goes and its shape stays. The first and last keys and locked keys
     * keep their values, and no key moves in time. The editor's Smooth slider does the same. See
     * [setKeys].
     */
    fun smooth(width: Double) = setKeys(smoothKeys(keyframes, width))

    private fun persist() {
        val box = associatedBox ?: return
        val name = associatedName ?: return
        saveKeyframeData(box, name, Json.encodeToString(keyframes))
    }

    // ── Overlay graph ────────────────────────────────────────────────

    /**
     * Send an overlay curve to the keyframe editor UI. The curve is drawn on top of
     * the main keyframe graph as a non-interactive visual reference.
     *
     * @param samples list of (time, value) points defining the overlay curve
     * @param color   CSS color string for the overlay stroke (default: orange, `#ff9800`)
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
        overlays[id] = json
        sendIfLive(json)
    }

    /**
     * Draw an overlay curve from a [Fn], sampled over the keyframe time range.
     *
     * @param fn        the function to sample
     * @param sampleCount number of sample points (default: 200)
     * @param color     CSS color string for the overlay stroke (default: orange, `#ff9800`)
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
        overlays.remove(id)
        sendIfLive("""{"type":"clearOverlay","id":"$id"}""")
    }

    /**
     * Remove all overlay curves from the editor.
     */
    fun clearAllOverlays() {
        overlays.clear()
        sendIfLive("""{"type":"clearAllOverlays"}""")
    }

    // ── Sampling ─────────────────────────────────────────────────────

    // How a segment leaving a key of [type] is shaped. The editor draws a type it does not know as
    // linear, so evaluation does too, rather than guessing bezier.
    private fun segmentKind(type: String): String = when (type) {
        "hold" -> "hold"
        "bezierFree", "bezierFlat", "bezierContinuous" -> "bezier"
        "linear" -> "linear"
        else -> if (custom(type) != null) "custom" else "linear"
    }

    // A key type defined with interpolant(...) in this editor's box.
    private fun custom(type: String): KeyInterpolant? = interpolantNamed(associatedBox, type)

    private fun customValue(prev: Keyframe, next: Keyframe, time: Double): Double {
        val span = max(next.time - prev.time, EPSILON)
        val u = ((time - prev.time) / span).coerceIn(0.0, 1.0)
        val i = keyframes.indexOf(prev)
        val before = if (i > 0) keyframes[i - 1] else null
        val after = if (i >= 0) keyframes.getOrNull(i + 2) else null
        return custom(prev.type)!!.evaluate(KeySegment(time, u, prev.time, next.time, prev.value, next.value, before, after))
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

    // d(x, y)/du of the rational segment at [u], by the quotient rule on N(u) / D(u).
    private fun rationalBezierTangent(c: BezierControls, u: Double): Point {
        val mt = 1 - u
        val b = doubleArrayOf(mt * mt * mt, 3 * mt * mt * u, 3 * mt * u * u, u * u * u)
        val db = doubleArrayOf(-3 * mt * mt, 3 * mt * mt - 6 * mt * u, 6 * mt * u - 3 * u * u, 3 * u * u)
        val p = arrayOf(c.p0, c.p1, c.p2, c.p3)
        var d = 0.0; var dd = 0.0; var nx = 0.0; var dnx = 0.0; var ny = 0.0; var dny = 0.0
        for (i in 0..3) {
            d += b[i] * p[i].w; dd += db[i] * p[i].w
            nx += b[i] * p[i].w * p[i].x; dnx += db[i] * p[i].w * p[i].x
            ny += b[i] * p[i].w * p[i].y; dny += db[i] * p[i].w * p[i].y
        }
        return Point((dnx * d - nx * dd) / (d * d), (dny * d - ny * dd) / (d * d))
    }

    private fun clamp(value: Double, min: Double, max: Double): Double {
        return when {
            value < min -> min
            value > max -> max
            else -> value
        }
    }

    companion object {
        /**
         * [simplify] for curves whose keys share their times, such as the lanes a gesture take bakes
         * into: every lane ends up with the same key times, each within [tolerance] of where it was.
         * A time stays if any lane needs it.
         *
         * ```kotlin
         * KeyframeEvaluator.simplifyTogether(listOf(xCurve, yCurve), 0.01)
         * ```
         */
        fun simplifyTogether(curves: List<KeyframeEvaluator>, tolerance: Double) {
            var pinned = emptySet<Double>()
            var results: List<List<Keyframe>>
            while (true) {
                results = curves.map { it.simplifyKeeping(tolerance, pinned) }
                val union = results.flatMap { lane -> lane.map { it.time } }.toSet()
                if (union == pinned || results.all { lane -> lane.map { it.time }.toSet() == union }) break
                pinned = union
            }
            curves.zip(results).forEach { (curve, keys) -> curve.setKeys(keys) }
        }

        private const val EPSILON = 1e-6

        // The neighbours a menu icon is drawn between, so the splines have something to look at.
        // Chosen to pull against the segment, so tension, bias and monotonicity look different.
        private val ICON_BEFORE = Keyframe(-1.0, 0.9, "linear")
        private val ICON_AFTER = Keyframe(2.0, 0.1, "linear")
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
                knownKeyframeTargets[t.getString("debug_uid")]?.sendAll()
            }
            if (t.has("debug_uid") && t.has("type") && t.getString("type") == "preview") {
                val evaluator = knownKeyframeTargets[t.getString("debug_uid")]
                if (evaluator != null) {
                    evaluator.session = s
                    evaluator.sendPreviewSegments(t.getJSONArray("keyframes").toString())
                }
            }
            if (t.has("debug_uid") && t.has("type") && t.getString("type") == "keyframes") {
                val debugUid = t.getString("debug_uid")
                knownKeyframeTargets[debugUid]?.session = s
                val keyframesJsonStr = t.getJSONArray("keyframes").toString()
                val evaluator = knownKeyframeTargets[debugUid]
                evaluator?.set(keyframesJsonStr)
                evaluator?.sendCustomSegments()

                // Fire the change callback; if it returns an Fn, show it as an overlay
                if (evaluator != null) {
                    val overlayFn = evaluator.onChange?.invoke(evaluator.getKeyframes())
                    if (overlayFn != null) {
                        // The same overlay setOverlay(fn) draws, so a box can draw it at start and
                        // let onChange keep it up to date: `k.setOverlay(f(keys)); k.onChange = f`.
                        evaluator.setOverlay(overlayFn)
                    }
                }

                // Persist to the box. Not through thisSessionInfo: it is not set on this socket's
                // thread, which is how every edit used to be dropped without a word.
                val box = evaluator?.associatedBox
                val kfName = evaluator?.associatedName
                if (box != null && kfName != null) {
                    try {
                        saveKeyframeData(box, kfName, keyframesJsonStr)
                    } catch (e: Exception) {
                        lprintln("warning,keyframes", "could not save keyframes '$kfName' on ${box.id}: ${e.message}")
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
private fun Box.keyframesImpl(channel: String?, name: String, defaultKeyframesJson: String): KeyframeEvaluator {
    // Per document as well as per box: box ids repeat across documents (a starting point opened twice,
    // a copied document), and two documents must not share one editor.
    val document = if (isInsideInitialized()) inside.filename else ""
    val uid = this.id + "_" + name + if (document.isEmpty()) "" else "_" + Integer.toHexString(document.hashCode())
    // Use a channel that includes the name so multiple keyframes on the same
    // channel don't overwrite each other's iframes.
    // The lanes that share a time axis: this box's editors, or one channel group of them.
    val group = this.id + "/" + (channel ?: "") + if (document.isEmpty()) "" else "@" + Integer.toHexString(document.hashCode())
    // With no channel the panel is named after the curve, which is what a reader wants to see.
    val displayChannel = if (channel == null || channel == name) name else "$channel/$name"

    // Only send the display/iframe if the evaluator isn't already live —
    // re-sending it would replace the renderer causing flicker and zoom reset.
    val existing = knownKeyframeTargets[uid]
    val alreadyLive = existing != null && existing.hasLiveSession

    if (!alreadyLive) {
        display(
            displayChannel, """
<iframe src='/keyframe?uid=${uid}&group=${java.net.URLEncoder.encode(group, "UTF-8")}' data-afield-intrinsic-width='560' data-afield-intrinsic-height='232' style='border:none;padding:0;margin:0;width:100%;min-width:560px;height:232px !important;display:block'>
"""
        )
        // The editor is a fixed 200 px tall, hence `height … !important`: field.css stretches panel
        // iframes to 100% !important, and a panel whose content reaches its bottom never shrinks.
        // An editor exists to be drawn in, so show it. Only when it is first shown: an editor the
        // person collapsed stays collapsed across reruns, because a live one is not re-sent.
        if (existing == null) displayOpen(displayChannel)
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
    eval.associatedBox = this
    // Each run says afresh what happens on an edit: a callback a previous run set, and this run
    // no longer does, must not keep firing.
    eval.onChange = null
    return eval
}

/**
 * Creates (or reconnects to) a keyframe curve editor displayed in the given output [channel].
 *
 * @param channel  the output-area channel in which to embed the editor; by default the panel is named
 *                 [name]. Give one to group editors: `keyframes("motion", "x")` shows in `motion/x`
 * @param name     a unique name for this set of keyframes within the box (used as persistence key)
 * @param defaultTimes  optional list of time positions (e.g. `listOf(0, 10, 30)`) used to seed
 *                      the editor with initial keyframes when no persisted data exists for [name].
 *                      Each time produces a keyframe at value `0.0` with type `"linear"`.
 *                      Once the user edits the curve the persisted data takes precedence.
 * @return a [KeyframeEvaluator] that can be queried with [KeyframeEvaluator.evaluateValueAt]
 */
fun Box.keyframes(channel: String? = null, name: String, defaultTimes: List<Number>? = null): KeyframeEvaluator {
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
 * @param channel  the output-area channel; by default the panel is named [name]
 * @param name     unique persistence key for this keyframe set
 * @param defaults list of [Vec2] where `x` = time, `y` = value; used only when no persisted data exists
 * @return a [KeyframeEvaluator] that can be queried with [KeyframeEvaluator.evaluateValueAt]
 */
@JvmName("keyframesVec2")
fun Box.keyframes(channel: String? = null, name: String, defaults: List<Vec2>): KeyframeEvaluator {
    val json = if (defaults.isNotEmpty()) {
        defaults.joinToString(",", "[", "]") { v ->
            """{ "time": ${v.x}, "value": ${v.y}, "type": "linear" }"""
        }
    } else "[]"
    return keyframesImpl(channel, name, json)
}

/**
 * Creates (or reconnects to) a keyframe curve editor, seeded with [keys] — keys with their types,
 * so a curve can start with a held step or an eased segment rather than straight lines.
 *
 * ```kotlin
 * val gain = keyframes(name = "gain", keys = listOf(
 *     Keyframe(0.0, 0.0, "bezierFlat"),
 *     Keyframe(1.0, 1.0, "hold"),
 *     Keyframe(2.0, 0.3, "linear"),
 *     Keyframe(3.0, 0.8, "linear"),
 * ))
 * ```
 *
 * A key's type shapes the segment that leaves it: `"linear"`, `"hold"`, `"bezierFree"`,
 * `"bezierFlat"` or `"bezierContinuous"`. Bezier keys with no handles start with handles the
 * editor derives from their neighbours.
 *
 * @param channel  the output-area channel; by default the panel is named [name]
 * @param name     unique persistence key for this keyframe set
 * @param keys     used only when no persisted data exists for [name]
 */
@JvmName("keyframesKeys")
fun Box.keyframes(channel: String? = null, name: String, keys: List<Keyframe>): KeyframeEvaluator =
    keyframesImpl(channel, name, if (keys.isEmpty()) "[]" else Json.encodeToString(keys))
