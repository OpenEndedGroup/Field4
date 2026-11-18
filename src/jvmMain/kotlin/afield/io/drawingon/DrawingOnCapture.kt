@file:afield.runtime.completion.CompletionCategory("Networking")

package afield.io.drawingon

import afield.geometry.path.FLine
import afield.graphics.render.color
import afield.math.linalg.Vec3
import afield.math.linalg.vec
import kotlinx.serialization.json.*
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.*

/** A measured point on the capture clock, in drawing coordinates. [data] retains every sensor field. */
data class DrawingOnSample(
    val t: Double, val x: Double, val y: Double, val sequence: Long,
    val layer: String?, val stroke: String?, val data: JsonObject
) {
    val id: Int? get() = data["id"]?.jsonPrimitive?.intOrNull
    val force: Double? get() = value("force")
    val maximumForce: Double? get() = value("maximumForce")
    val pressure: Double? get() = maximumForce?.takeIf { it > 0 }?.let { force?.div(it) }
    val altitude: Double? get() = value("altitude")
    val azimuth: Double? get() = value("azimuth")
    val roll: Double? get() = value("roll")
    /** Normalized hover height; absent on contact samples. */
    val z: Double? get() = value("z")
    fun value(name: String): Double? = data.number(name)
    fun position(): Vec3 = vec(x, y, 0)
}

/** Original capture sample attached to a measured FLine node, including timing and sensor values. */
val FLine.Node.drawingOnSample: DrawingOnSample? get() = properties["drawingOn.sample"] as? DrawingOnSample

/** A recorded layer at a scrub time. [visible] includes local visibility and solo overrides. */
data class DrawingOnLayer(val id: String, val name: String, val visible: Boolean, val source: String? = null)

/**
 * One replayed frame. Geometry uses drawing coordinates; [viewport] outlines the visible screen in
 * those coordinates. [drawing] reconstructs human ink, including line/circle tools and recorded undo.
 * Returned Field reference meshes and transient predicted ink are not human ink and are excluded.
 */
data class DrawingOnFrame(
    val time: Double, val drawing: List<FLine>, val layers: List<DrawingOnLayer>,
    val viewport: FLine?, val transform: JsonObject?, val light: Boolean
)

/**
 * A refreshable capture with independent replay controls. Scrubbing never edits the recording or
 * sends commands to the iPad. Times are seconds on its capture clock, not wall-clock timestamps.
 *
 * [at] reconstructs what was known at a time, so a late correction cannot leak into earlier frames.
 * [drawingSince] and [hoverSince] return measured paths in the half-open interval `(since, until]`.
 * They omit predicted samples and never connect separate strokes or hover encounters.
 */
class DrawingOnCapture internal constructor(private val read: ((JsonObject) -> Unit) -> Unit) {
    private val events = mutableListOf<JsonObject>()
    private var sequence = 0L
    private var end = 0.0
    private val visibility = mutableMapOf<String, Boolean>()
    private var soloLayer: String? = null
    private var overrideSolo = false

    init { refresh() }

    /** Latest recorded event time, including speech and pauses. Call [refresh] for newly saved input. */
    val duration: Double get() = end
    val layers: List<DrawingOnLayer> get() {
        val state = replay(duration)
        return state.layers.values.map { it.copy(visible = visible(it.id, state)) }
    }

    /** Append newly archived events. Audio/geometry buffers are streamed past, not retained in RAM. */
    @Synchronized fun refresh(): DrawingOnCapture {
        read { event ->
            val seq = event["seq"]?.jsonPrimitive?.longOrNull ?: return@read
            if (seq > sequence) {
                val t = event.number("t") ?: return@read
                val type = event["type"]?.jsonPrimitive?.contentOrNull ?: return@read
                if (type in retainedTypes) events += event
                sequence = seq
                end = max(end, t)
            }
        }
        return this
    }

    /** Override one layer by its ID; `null` follows its recorded visibility again. */
    fun layerVisible(id: String, visible: Boolean?): DrawingOnCapture = apply {
        if (visible == null) visibility.remove(id) else visibility[id] = visible
    }

    /** Solo a layer locally; `null` turns solo off. [resetLayers] restores recorded solo. */
    fun solo(id: String?): DrawingOnCapture = apply { soloLayer = id; overrideSolo = true }

    /** Clear all local overrides, restoring recorded visibility and solo. */
    fun resetLayers(): DrawingOnCapture = apply { visibility.clear(); soloLayer = null; overrideSolo = false }

    /** Reconstruct visible human ink, shape intent, layers and viewport at [time]. */
    @Synchronized fun at(time: Double): DrawingOnFrame {
        require(time.isFinite() && time >= 0) { "Scrub time must be finite and nonnegative" }
        val state = replay(time)
        val layers = state.layers.values.map { layer -> layer.copy(visible = visible(layer.id, state)) }
        val ink = state.strokes.values.filter { it.visible && !it.cancelled && visible(it.layer, state) }
            .mapNotNull { stroke -> shape(stroke, time)?.also { decorate(it, stroke.id, stroke.layer, state.light) } }
        return DrawingOnFrame(time, ink, layers, state.viewport?.let(::footprint), state.transform, state.light)
    }

    /** Visible human ink at [time], as ordinary editable FLines. */
    fun drawingAt(time: Double): List<FLine> = at(time).drawing

    /**
     * New contact paths, one FLine per stroke. By default includes undone/hidden/cancelled material
     * for analysis; [visibleOnly] restricts to the scene at [until]. Local layer overrides always apply.
     * [map] can turn sensor data into a graph, e.g. `{ vec(it.t, it.force ?: 0.0, 0) }`.
     */
    @Synchronized fun drawingSince(
        since: Double, until: Double = duration, visibleOnly: Boolean = false,
        map: (DrawingOnSample) -> Vec3 = { it.position() }
    ): List<FLine> {
        interval(since, until)
        val state = replay(until)
        return state.strokes.values.filter { allowed(it.layer, state, visibleOnly) && (!visibleOnly || it.visible && !it.cancelled) }
            .mapNotNull { stroke ->
                measured(stroke.samples.values.filter { it.t > since && it.t <= until }, map)?.also {
                    decorate(it, stroke.id, stroke.layer, state.light)
                }
            }
    }

    /**
     * New hover paths, independently of ink. Breaks at contact, navigation, phase boundaries, layer
     * changes and gaps greater than [gap] seconds. Hover has no force; use [DrawingOnSample.z] for height.
     */
    @Synchronized fun hoverSince(
        since: Double, until: Double = duration, gap: Double = 0.25, visibleOnly: Boolean = false,
        map: (DrawingOnSample) -> Vec3 = { it.position() }
    ): List<FLine> {
        interval(since, until)
        require(gap.isFinite() && gap > 0) { "Hover gap must be positive" }
        val state = replay(until)
        val result = mutableListOf<FLine>()
        var pending = mutableListOf<DrawingOnSample>()
        fun flush() {
            measured(pending, map)?.also {
                it.properties["drawingOn.kind"] = "hover"
                it.properties["drawingOn.layer"] = pending.first().layer
                it.color = vec(1, 1, 1, 0.35)
                result += it
            }
            pending = mutableListOf()
        }
        for (item in state.hover) {
            if (item == null) { flush(); continue }
            if (item.t <= since || item.t > until || !allowed(item.layer, state, visibleOnly)) { flush(); continue }
            val previous = pending.lastOrNull()
            if (item.data["phase"]?.jsonPrimitive?.intOrNull == 1 || previous != null &&
                (item.t - previous.t > gap || item.t < previous.t || item.layer != previous.layer)) flush()
            pending += item
        }
        flush()
        return result
    }

    private fun interval(since: Double, until: Double) {
        require(!since.isNaN() && since != Double.POSITIVE_INFINITY && until.isFinite() && until >= 0 && since <= until) {
            "Expected since <= until, with finite nonnegative until"
        }
    }

    private fun allowed(layer: String?, state: Replay, recorded: Boolean): Boolean = when {
        layer != null && visibility[layer] == false -> false
        soloLayer != null && layer != soloLayer -> false
        recorded && layer != null -> visible(layer, state)
        else -> true
    }

    private fun visible(layer: String, state: Replay): Boolean =
        (visibility[layer] ?: state.layers[layer]?.visible ?: true) &&
            ((if (overrideSolo) soloLayer else state.solo) == null || (if (overrideSolo) soloLayer else state.solo) == layer)

    private data class Stroke(val id: String, val layer: String, val tool: String, val transform: JsonObject?,
        val samples: LinkedHashMap<Int, DrawingOnSample> = linkedMapOf(), var visible: Boolean = true, var cancelled: Boolean = false)
    private class Replay {
        val layers = linkedMapOf<String, DrawingOnLayer>()
        val strokes = linkedMapOf<String, Stroke>()
        val hover = mutableListOf<DrawingOnSample?>()
        var active: String? = null
        var solo: String? = null
        var viewport: JsonObject? = null
        var transform: JsonObject? = null
        var light = false
    }

    private fun replay(time: Double): Replay {
        val state = Replay()
        for (event in events) {
            val t = event.number("t") ?: continue
            if (t > time) continue
            val p = event["data"] as? JsonObject ?: continue
            val seq = event["seq"]!!.jsonPrimitive.long
            val type = event["type"]!!.jsonPrimitive.content
            when (type) {
                "layers.state" -> {
                    state.layers.clear()
                    (p["layers"] as? JsonArray)?.forEach { raw ->
                        val layer = raw as? JsonObject ?: return@forEach
                        val id = layer.string("id") ?: return@forEach
                        state.layers[id] = DrawingOnLayer(id, layer.string("name") ?: id,
                            layer["visible"]?.jsonPrimitive?.booleanOrNull ?: true, layer.string("source"))
                    }
                    state.active = p.string("active"); state.solo = p.string("solo")
                    state.hover += null
                }
                "canvas.appearance" -> state.light = p["light"]?.jsonPrimitive?.booleanOrNull ?: false
                "canvas.viewport", "canvas.transform" -> {
                    state.viewport = (p["viewport"] as? JsonObject) ?: p.takeIf { "drawingCorners" in it } ?: state.viewport
                    state.transform = p["transform"] as? JsonObject ?: state.transform
                    state.hover += null
                }
                "stroke.begin" -> {
                    val id = p.string("stroke") ?: continue
                    val layer = p.string("layer") ?: continue
                    state.layers.putIfAbsent(layer, DrawingOnLayer(layer, layer, true))
                    state.strokes[id] = Stroke(id, layer, p.string("tool") ?: "freehand", p["transform"] as? JsonObject ?: state.transform)
                    state.hover += null
                }
                "stroke.samples", "stroke.correct" -> {
                    val stroke = state.strokes[p.string("stroke")] ?: continue
                    (p["samples"] as? JsonArray)?.forEach { raw ->
                        val data = raw as? JsonObject ?: return@forEach
                        val id = data["id"]?.jsonPrimitive?.intOrNull ?: return@forEach
                        val sample = sample(data, data.number("t") ?: t, seq, stroke.layer, stroke.id, stroke.transform) ?: return@forEach
                        if (sample.t <= time && (type != "stroke.correct" || id in stroke.samples)) stroke.samples[id] = sample
                    }
                }
                "stroke.end" -> state.strokes[p.string("stroke")]?.cancelled = p["cancelled"]?.jsonPrimitive?.booleanOrNull ?: false
                "stroke.visibility" -> state.strokes[p.string("stroke")]?.visible = p["visible"]?.jsonPrimitive?.booleanOrNull ?: true
                "pencil.hover" -> {
                    val phase = p["phase"]?.jsonPrimitive?.intOrNull
                    if (phase != null && phase !in 1..2) state.hover += null
                    else state.hover += sample(p, t, seq, state.active, null, state.transform)
                }
            }
        }
        return state
    }

    private fun sample(data: JsonObject, t: Double, seq: Long, layer: String?, stroke: String?, transform: JsonObject?): DrawingOnSample? {
        val rawX = data.number("preciseX") ?: data.number("x") ?: data.number("drawingX") ?: return null
        val rawY = data.number("preciseY") ?: data.number("y") ?: data.number("drawingY") ?: return null
        val a = transform?.number("a") ?: 1.0; val b = transform?.number("b") ?: 0.0
        val c = transform?.number("c") ?: 0.0; val d = transform?.number("d") ?: 1.0
        val tx = transform?.number("tx") ?: 0.0; val ty = transform?.number("ty") ?: 0.0
        val determinant = a*d - b*c
        val x = data.number("drawingX") ?: if (abs(determinant) > 1e-15) (d*(rawX-tx)-c*(rawY-ty))/determinant else rawX
        val y = data.number("drawingY") ?: if (abs(determinant) > 1e-15) (-b*(rawX-tx)+a*(rawY-ty))/determinant else rawY
        return DrawingOnSample(t, x, y, seq, layer, stroke, data)
    }

    private fun shape(stroke: Stroke, time: Double): FLine? {
        val points = stroke.samples.values.filter { it.t <= time }
        if (points.isEmpty()) return null
        val first = points.first(); val last = points.last()
        return when (stroke.tool) {
            "line" -> measured(listOf(first, last)) { it.position() }
            "circle" -> {
                val radius = hypot(last.x-first.x, last.y-first.y)
                FLine().also { line ->
                    for (i in 0..96) {
                        val angle = i * 2.0 * PI / 96
                        val x = first.x + radius*cos(angle); val y = first.y + radius*sin(angle)
                        if (i == 0) line.moveTo(x,y) else line.lineTo(x,y)
                    }
                    line.close()
                }
            }
            else -> measured(points) { it.position() }
        }
    }

    private fun measured(samples: List<DrawingOnSample>, map: (DrawingOnSample) -> Vec3): FLine? {
        if (samples.isEmpty()) return null
        return FLine().also { line -> samples.forEachIndexed { index, sample ->
            val point = map(sample)
            require(point.x.isFinite() && point.y.isFinite() && point.z.isFinite()) { "Sample mapping must be finite" }
            if (index == 0) line.moveTo(point) else line.lineTo(point)
            line.here.properties["drawingOn.sample"] = sample
            line.here.properties["t"] = sample.t
        } }
    }

    private fun decorate(line: FLine, stroke: String, layer: String, light: Boolean) {
        line.properties["drawingOn.stroke"] = stroke; line.properties["drawingOn.layer"] = layer
        line.properties["drawingOn.kind"] = "drawing"
        line.color = if (light) vec(0,0,0,0.24) else vec(1,1,1,0.24)
    }

    private fun footprint(data: JsonObject): FLine? {
        val corners = data["drawingCorners"] as? JsonArray ?: return null
        val points = corners.mapNotNull { raw ->
            val xy = raw as? JsonArray ?: return@mapNotNull null
            val x = xy.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val y = xy.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            if (x.isFinite() && y.isFinite()) vec(x,y,0) else null
        }
        if (points.size != 4) return null
        return FLine().also { line -> points.forEachIndexed { i, p -> if (i == 0) line.moveTo(p) else line.lineTo(p) }; line.close() }
    }

    private companion object {
        val retainedTypes = setOf("layers.state", "canvas.appearance", "canvas.viewport", "canvas.transform", "stroke.begin",
            "stroke.samples", "stroke.correct", "stroke.end", "stroke.visibility", "pencil.hover")
    }
}

/** Read an exported iPad save file locally, without a running server or a target binding. */
fun readDrawingOn(file: File): DrawingOnCapture = DrawingOnCapture(drawingOnFileReader(file))

internal fun drawingOnFileReader(file: File): ((JsonObject) -> Unit) -> Unit {
    var offset = 0L
    return { consume ->
        RandomAccessFile(file, "r").use { input ->
            require(input.length() >= offset) { "Capture was truncated; open a new replay handle" }
            input.seek(offset)
            while (input.filePointer < input.length()) {
                val raw = input.readLine() ?: break
                val end = input.filePointer
                input.seek(end - 1)
                val terminated = input.read() == 10
                input.seek(end)
                if (!terminated) break // A live writer has not committed this final record yet.
                if (raw.isNotBlank()) consume(drawingOnJson.parseToJsonElement(
                    String(raw.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)).jsonObject)
                offset = end
            }
        }
    }
}

/** Read an exported save file by path. */
fun readDrawingOn(path: String): DrawingOnCapture = readDrawingOn(File(path))

private fun JsonObject.number(key: String): Double? = (get(key) as? JsonPrimitive)?.doubleOrNull?.takeIf(Double::isFinite)
private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
