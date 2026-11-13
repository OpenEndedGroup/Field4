/**
 * A film's timeline: keys at film time, read from a JSON file, that `film-page --timeline` plays frame by frame.
 *
 * The file says what the film shows at every moment (the camera of each 3D view, which parts are hidden, a
 * declared motion's value, a box's control or a drag on its 2D drawing, the captions) and where each box output
 * sits on the frame (the stage, [FilmStage]). Everything is a function of the frame's time: [FilmTimeline.frame]
 * gives what a frame asks the page for, and [FilmTimeline.serverActions] what the server does between two
 * frames (a drag's press, moves and release). Nothing here reads a clock, so a frame can be computed again,
 * which is how a shoot resumes from frame k.
 *
 * JSON, not a script: the backdoor (Python), the server (Kotlin) and the page (JavaScript) all read it, it can be
 * checked before a shoot starts, diffed between takes, and evaluated at any frame without running anything.
 * `public/docs/filming_a_timeline.md` documents the format.
 */

package afield.tools.docs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A timeline file that does not say what it means: [message] names the place, `tracks[2].keys[1].t` say. */
class FilmTimelineException(message: String) : IllegalArgumentException(message)

/** How a key is arrived at from the one before it. */
enum class FilmEase(val wire: String) {
    LINEAR("linear"), IN("in"), OUT("out"), IN_OUT("inOut"), STEP("step");

    fun at(u: Double): Double {
        val x = u.coerceIn(0.0, 1.0)
        return when (this) {
            LINEAR -> x
            IN -> x * x
            OUT -> 1 - (1 - x) * (1 - x)
            // The smoothstep the viewer's own timeline eases with.
            IN_OUT -> x * x * (3 - 2 * x)
            STEP -> if (x >= 1.0) 1.0 else 0.0
        }
    }

    companion object {
        fun of(text: String?, where: String): FilmEase = when (text) {
            null -> LINEAR
            else -> entries.firstOrNull { it.wire == text }
                ?: throw FilmTimelineException("$where: ease is one of ${entries.joinToString { it.wire }}, not \"$text\"")
        }
    }
}

/** A camera pose: where the eye is, what it looks at, which way is up, and (optionally) the vertical field of view. */
data class FilmPose(val eye: List<Double>, val target: List<Double>, val up: List<Double>? = null, val fov: Double? = null) {
    fun json(): JsonObject = buildJsonObject {
        put("eye", eye.json()); put("target", target.json())
        up?.let { put("up", it.json()) }
        fov?.let { put("fov", it) }
    }

    companion object {
        fun of(json: JsonElement?): FilmPose? {
            val o = json as? JsonObject ?: return null
            val eye = o.vec("eye") ?: return null
            val target = o.vec("target") ?: return null
            return FilmPose(eye, target, o.vec("up"), (o["fov"] as? JsonPrimitive)?.doubleOrNull)
        }
    }
}

/** Where a region sits on the frame, in CSS px of the film page, and how its output fills it. */
data class FilmRegion(
    val id: String,
    val box: String,
    val channel: String?,
    val rect: List<Double>,
    val fit: String?,
    val json: JsonObject,
)

/** The stage: the film page's regions, each bound to one box output, and the caption's place and look. */
data class FilmStage(val regions: List<FilmRegion>, val json: JsonObject)

/** One thing the server does to a box between two frames: a press, move or release of a drag on its drawing. */
data class FilmServerAction(val box: String, val channel: String, val phase: String, val x: Double, val y: Double)

private sealed interface CameraKey {
    val t: Double
    val ease: FilmEase
    val arc: List<Double>?

    data class Pose(override val t: Double, override val ease: FilmEase, override val arc: List<Double>?, val pose: FilmPose) : CameraKey

    /** Turns about [target] and [up] at [degreesPerSecond] from [t]; a missing eye or target continues the camera. */
    data class Orbit(
        override val t: Double, override val ease: FilmEase, override val arc: List<Double>?,
        val eye: List<Double>?, val target: List<Double>?, val up: List<Double>?, val degreesPerSecond: Double,
    ) : CameraKey
}

private data class PartsKey(val t: Double, val hidden: Boolean, val ids: List<String>, val layers: List<List<String>>)

private sealed interface MotionKey {
    val t: Double

    data class Value(override val t: Double, val value: Double, val ease: FilmEase) : MotionKey

    /** The view's own timeline played from [t]: [shape] over [period] seconds, as the viewer's Play does. */
    data class Play(override val t: Double, val period: Double, val shape: String) : MotionKey
    data class Rest(override val t: Double) : MotionKey
}

private data class ValueKey(val t: Double, val value: List<Double>?, val ease: FilmEase, val release: Boolean)

/** Wait until [view]'s metadata at [metadata] is [scale] times the track's value (its [of] coordinate), to [tol]. */
private data class Until(val view: String, val metadata: String, val of: String, val tol: Double, val scale: Double = 1.0)

/**
 * One press, the moves while it is held, and the release: a run of `at` keys ended by a `release` key (let go at
 * [release], so still pressed just before it) or by the end of the track (let go just after its last key).
 */
private class DragGesture(val keys: List<ValueKey>, val release: Double, private val explicit: Boolean) {
    val start: Double get() = keys.first().t
    val first: List<Double> get() = keys.first().value!!
    val last: List<Double> get() = keys.last().value!!
    fun pressed(t: Double) = t >= start && (if (explicit) t < release else t <= release)
    fun point(t: Double): List<Double> {
        val k = keys.indexOfLast { it.t <= t }.coerceAtLeast(0)
        return interpolated(keys, k, t)
    }
}

private class DragTrack(val box: String, val channel: String, keys: List<ValueKey>, val until: Until?) {
    val gestures: List<DragGesture> = buildList {
        var run = mutableListOf<ValueKey>()
        for (key in keys) {
            if (key.release) { add(DragGesture(run, key.t, explicit = true)); run = mutableListOf() }
            else run += key
        }
        if (run.isNotEmpty()) add(DragGesture(run, run.last().t, explicit = false))
    }
}

private data class ControlTrack(val box: String, val label: String, val keys: List<ValueKey>, val until: Until?)

private data class CaptionKey(val t: Double, val text: String, val fade: Double)

/** A parsed timeline; [frame] and [serverActions] evaluate it. */
class FilmTimeline private constructor(
    val duration: Double?,
    val fps: Int?,
    val width: Int?,
    val height: Int?,
    val pixelRatio: Double?,
    val stage: FilmStage?,
    private val cameras: Map<String, List<CameraKey>>,
    private val parts: Map<String, List<PartsKey>>,
    private val motions: Map<String, List<MotionKey>>,
    private val tracksOn: Map<String, List<Pair<Double, Boolean>>>,
    private val drags: List<DragTrack>,
    private val controls: List<ControlTrack>,
    private val captions: List<CaptionKey>,
) {
    /** The views (region ids, or `box:channel`) whose camera, parts or motion the timeline sets. */
    val views: Set<String> get() = cameras.keys + parts.keys + motions.keys + tracksOn.keys

    /** Every box the timeline names, as written, so a caller can check or resolve them before a shoot. */
    val boxes: Set<String>
        get() = buildSet {
            stage?.regions?.forEach { add(it.box) }
            drags.forEach { add(it.box) }
            controls.forEach { add(it.box) }
        }

    /**
     * What frame time [t] asks the page for, as the JSON the page's `FieldFilm.frame` takes. [initialPoses] is each
     * view's camera as the stage found it, for an orbit that names no eye; [previousT] (the frame before, or
     * null for the first) decides which values changed, so the frame waits for the views to show them.
     */
    fun frame(i: Int, t: Double, previousT: Double?, initialPoses: Map<String, FilmPose> = emptyMap()): JsonObject = buildJsonObject {
        put("i", i); put("t", t)
        put("views", buildJsonObject {
            for (view in views) {
                put(view, buildJsonObject {
                    cameraAt(view, t, initialPoses[view])?.let { put("camera", it.json()) }
                    val ops = parts[view].orEmpty().filter { it.t <= t }
                    if (ops.isNotEmpty()) put("parts", buildJsonArray {
                        for (op in ops) add(buildJsonObject {
                            put("hidden", op.hidden)
                            if (op.ids.isNotEmpty()) put("ids", op.ids.jsonStrings())
                            if (op.layers.isNotEmpty()) put("layers", JsonArray(op.layers.map { it.jsonStrings() }))
                        })
                    })
                    motionAt(view, t)?.let { put("motion", it) }
                    tracksOn[view]?.lastOrNull { it.first <= t }?.let { put("tracks", it.second) }
                })
            }
        })
        val waits = buildJsonArray {
            for (d in drags) for (g in d.gestures) {
                if (d.until == null || !g.pressed(t)) continue
                val point = g.point(t)
                if (previousT == null || !g.pressed(previousT) || g.point(previousT) != point) add(d.until.wait(point))
            }
            for (c in controls) {
                val now = valueAt(c.keys, t) ?: continue
                // The slider snaps the value to its step, as a hand's drag would; the page waits for the snapped one.
                if (c.until != null && now != previousT?.let { valueAt(c.keys, it) }) add(JsonObject(c.until.wait(now) +
                    ("control" to buildJsonObject { put("box", c.box); put("label", c.label) })))
            }
        }
        if (waits.isNotEmpty()) put("waits", waits)
        val values = buildJsonArray {
            for (c in controls) valueAt(c.keys, t)?.let { v ->
                add(buildJsonObject { put("box", c.box); put("label", c.label); put("value", v.first()) })
            }
        }
        if (values.isNotEmpty()) put("controls", values)
        captionAt(t)?.let { put("caption", it) }
    }

    /**
     * What the server does to boxes between frame time [previousT] (null: before the film) and [t]: each drag's
     * press, its move when the point changed, and its release, in the order a hand would make them.
     */
    fun serverActions(previousT: Double?, t: Double): List<FilmServerAction> = buildList {
        for (d in drags) for (g in d.gestures) {
            fun act(phase: String, p: List<Double>) = add(FilmServerAction(d.box, d.channel, phase, p[0], p[1]))
            val wasDown = previousT != null && g.pressed(previousT)
            val isDown = g.pressed(t)
            val skipped = !wasDown && !isDown && (previousT == null || previousT < g.start) && t >= g.release
            when {
                !wasDown && isDown -> { act("start", g.first); g.point(t).let { if (it != g.first) act("move", it) } }
                wasDown && isDown -> g.point(t).let { if (it != g.point(previousT!!)) act("move", it) }
                wasDown -> { if (g.point(previousT!!) != g.last) act("move", g.last); act("end", g.last) }
                // A whole gesture between two frames: made all the same, so the box sees every press.
                skipped -> { act("start", g.first); if (g.last != g.first) act("move", g.last); act("end", g.last) }
            }
        }
    }

    private fun Until.wait(value: List<Double>): JsonObject = buildJsonObject {
        put("view", view); put("metadata", metadata); put("tol", tol)
        when (of) {
            "x" -> put("value", scale * value[0])
            "y" -> put("value", scale * value.getOrElse(1) { value[0] })
            else -> put("value", if (value.size == 1) JsonPrimitive(scale * value[0]) else value.map { scale * it }.json())
        }
        if (scale != 1.0) put("scale", scale)
    }

    private fun cameraAt(view: String, t: Double, initial: FilmPose?): FilmPose? = cameras[view]?.let { cameraAt(it, t, initial) }

    /** The camera [keys] give at [t]: the key before [t], eased into the key after it. */
    private fun cameraAt(keys: List<CameraKey>, t: Double, initial: FilmPose?): FilmPose? {
        val next = keys.indexOfFirst { it.t > t }
        val k = if (next == -1) keys.size - 1 else next - 1
        if (k < 0) return null
        val here = poseOf(keys, k, t, initial) ?: return null
        if (next == -1) return here
        val to = keys[next]
        if (to.ease == FilmEase.STEP) return here
        val there = poseOf(keys, next, t, initial) ?: return here
        val u = to.ease.at((t - keys[k].t) / (to.t - keys[k].t))
        val mixed = mix(here, there, u)
        val arc = to.arc ?: return mixed
        val lift = sin(PI * u)
        return mixed.copy(eye = mixed.eye.mapIndexed { j, e -> e + arc[j] * lift })
    }

    /**
     * Key [k]'s own pose at [t]: a pose is still; an orbit turns from its start, which is its own eye and target or,
     * without them, where the keys before it leave the camera at the orbit's start (the view's own camera for the first).
     */
    private fun poseOf(keys: List<CameraKey>, k: Int, t: Double, initial: FilmPose?): FilmPose? = when (val key = keys[k]) {
        is CameraKey.Pose -> key.pose
        is CameraKey.Orbit -> {
            val from = if (key.eye != null && key.target != null) null
                else if (k == 0) initial else cameraAt(keys.subList(0, k), key.t, initial)
            val eye = key.eye ?: from?.eye
            val target = key.target ?: from?.target
            if (eye == null || target == null) null
            else {
                val up = key.up ?: from?.up ?: listOf(0.0, 1.0, 0.0)
                // The viewer's own film turn (turnCamera): about the up axis, by minus the angle.
                val angle = -key.degreesPerSecond * PI / 180.0 * max(0.0, t - key.t)
                FilmPose(rotate(eye, target, up, angle), target, up, from?.fov)
            }
        }
    }

    private fun motionAt(view: String, t: Double): JsonObject? {
        val keys = motions[view] ?: return null
        val next = keys.indexOfFirst { it.t > t }
        val k = if (next == -1) keys.size - 1 else next - 1
        if (k < 0) return null
        fun own(key: MotionKey): Double? = when (key) {
            is MotionKey.Value -> key.value
            is MotionKey.Play -> timelineAt(key.shape, (t - key.t) / key.period)
            is MotionKey.Rest -> null
        }
        val here = own(keys[k])
        val to = keys.getOrNull(next)
        val value = if (here != null && to is MotionKey.Value && to.ease != FilmEase.STEP)
            here + (to.value - here) * to.ease.at((t - keys[k].t) / (to.t - keys[k].t)) else here
        return buildJsonObject { if (value == null) put("rest", true) else put("value", value) }
    }

    private fun valueAt(keys: List<ValueKey>, t: Double): List<Double>? {
        val k = keys.indexOfLast { it.t <= t }
        if (k < 0) return null
        return interpolated(keys, k, t)
    }

    private fun captionAt(t: Double): JsonObject? {
        val k = captions.indexOfLast { it.t <= t }
        if (k < 0) return null
        val c = captions[k]
        var opacity = 1.0
        if (c.fade > 0) {
            opacity = min(opacity, (t - c.t) / c.fade)
            captions.getOrNull(k + 1)?.let { n -> opacity = min(opacity, (n.t - t) / c.fade) }
        }
        return buildJsonObject { put("text", c.text); put("opacity", opacity.coerceIn(0.0, 1.0)) }
    }

    companion object {
        const val FORMAT = "field-film-timeline/1"

        fun parse(text: String): FilmTimeline {
            val root = try { Json.parseToJsonElement(text) } catch (e: Exception) {
                throw FilmTimelineException("the timeline is not JSON: ${e.message?.lineSequence()?.firstOrNull()}")
            }
            return of(root as? JsonObject ?: throw FilmTimelineException("the timeline is a JSON object"))
        }

        fun of(root: JsonObject): FilmTimeline {
            val format = root.string("format", "timeline")
            if (format != null && format != FORMAT) throw FilmTimelineException("timeline: format is \"$FORMAT\", not \"$format\"")
            known(root, "timeline", "format", "duration", "fps", "size", "pixelRatio", "stage", "tracks", "//")
            val duration = root.number("duration", "timeline")?.also { if (it <= 0 || it > 3600) throw FilmTimelineException("timeline: duration is more than 0 and at most 3600 seconds") }
            val fps = (root["fps"] as? JsonPrimitive)?.let { it.intOrNull ?: throw FilmTimelineException("timeline: fps is a whole number") }
            val size = root.vec("size", "timeline")?.also { if (it.size != 2) throw FilmTimelineException("timeline: size is [width, height]") }
            val stage = (root["stage"])?.let { stage(it) }
            val regionIds = stage?.regions?.map { it.id }?.toSet().orEmpty()
            val cameras = linkedMapOf<String, List<CameraKey>>()
            val parts = linkedMapOf<String, List<PartsKey>>()
            val motions = linkedMapOf<String, List<MotionKey>>()
            val tracks = linkedMapOf<String, List<Pair<Double, Boolean>>>()
            val drags = mutableListOf<DragTrack>()
            val controls = mutableListOf<ControlTrack>()
            var captions = emptyList<CaptionKey>()
            val list = root["tracks"] as? JsonArray ?: if (root["tracks"] == null) JsonArray(emptyList()) else throw FilmTimelineException("timeline: tracks is a list")
            for ((n, element) in list.withIndex()) {
                val where = "tracks[$n]"
                val track = element as? JsonObject ?: throw FilmTimelineException("$where is an object")
                val kinds = listOf("camera", "parts", "motion", "drag", "control", "captions").filter { it in track }
                if (kinds.size != 1) throw FilmTimelineException("$where names one of camera, parts, motion, drag, control or captions${if (kinds.isEmpty()) "" else ", not ${kinds.joinToString(" and ")}"}")
                val kind = kinds.single()
                if (kind == "captions") {
                    known(track, where, "captions", "//")
                    captions = (track["captions"] as? JsonArray ?: throw FilmTimelineException("$where: captions is a list of keys"))
                        .mapIndexed { j, e -> caption(e, "$where.captions[$j]") }.also { sorted(it.map(CaptionKey::t), "$where.captions") }
                    continue
                }
                known(track, where, kind, "keys", "until", "//")
                val keys = track["keys"] as? JsonArray ?: throw FilmTimelineException("$where: keys is a list")
                if (keys.isEmpty()) throw FilmTimelineException("$where has no keys")
                val objects = keys.mapIndexed { j, e -> e as? JsonObject ?: throw FilmTimelineException("$where.keys[$j] is an object") }
                val times = objects.mapIndexed { j, o -> o.number("t", "$where.keys[$j]") ?: throw FilmTimelineException("$where.keys[$j] has no t") }
                sorted(times, "$where.keys")
                when (kind) {
                    "camera", "parts", "motion" -> {
                        val view = view(track[kind], "$where.$kind", regionIds)
                        if (track["until"] != null) throw FilmTimelineException("$where: until belongs to a drag or control track")
                        when (kind) {
                            "camera" -> cameras[view] = (cameras[view].orEmpty() + objects.mapIndexed { j, o -> camera(o, times[j], "$where.keys[$j]") }).sortedBy { it.t }
                            "parts" -> parts[view] = (parts[view].orEmpty() + objects.mapIndexed { j, o -> partsKey(o, times[j], "$where.keys[$j]") }).sortedBy { it.t }
                            else -> objects.forEachIndexed { j, o ->
                                val w = "$where.keys[$j]"
                                known(o, w, "t", "value", "ease", "play", "rest", "tracks", "//")
                                (o["tracks"] as? JsonPrimitive)?.let { p ->
                                    tracks[view] = tracks[view].orEmpty() + (times[j] to (p.booleanOrNull ?: throw FilmTimelineException("$w: tracks is true or false")))
                                }
                                motionKey(o, times[j], w)?.let { motions[view] = motions[view].orEmpty() + it }
                            }
                        }
                    }
                    "drag" -> {
                        val target = track["drag"] as? JsonObject ?: throw FilmTimelineException("$where: drag is {\"box\": …, \"channel\": …}")
                        known(target, "$where.drag", "box", "channel")
                        val box = target.string("box", "$where.drag") ?: throw FilmTimelineException("$where.drag names a box")
                        val channel = target.string("channel", "$where.drag") ?: "lines"
                        val ks = objects.mapIndexed { j, o -> valueKey(o, times[j], "$where.keys[$j]", point = true) }
                        ks.forEachIndexed { j, k -> if (k.release && (j == 0 || ks[j - 1].release)) throw FilmTimelineException("$where.keys[$j]: a release follows an at key") }
                        drags += DragTrack(box, channel, ks, until(track["until"], "$where.until", regionIds))
                    }
                    "control" -> {
                        val target = track["control"] as? JsonObject ?: throw FilmTimelineException("$where: control is {\"box\": …, \"label\": …}")
                        known(target, "$where.control", "box", "label")
                        val box = target.string("box", "$where.control") ?: throw FilmTimelineException("$where.control names a box")
                        val label = target.string("label", "$where.control") ?: throw FilmTimelineException("$where.control names the control's label")
                        val ks = objects.mapIndexed { j, o -> valueKey(o, times[j], "$where.keys[$j]", point = false) }
                        if (ks.any { it.release }) throw FilmTimelineException("$where: a control has no release")
                        controls += ControlTrack(box, label, ks, until(track["until"], "$where.until", regionIds))
                    }
                }
            }
            for ((view, keys) in motions) motions[view] = keys.sortedBy { it.t }
            return FilmTimeline(duration, fps, size?.get(0)?.toInt(), size?.get(1)?.toInt(), root.number("pixelRatio", "timeline"),
                stage, cameras, parts, motions, tracks, drags, controls, captions)
        }

        private fun stage(element: JsonElement): FilmStage {
            val o = element as? JsonObject ?: throw FilmTimelineException("stage is an object")
            known(o, "stage", "background", "regions", "caption", "//")
            val regions = (o["regions"] as? JsonArray ?: throw FilmTimelineException("stage: regions is a list")).mapIndexed { j, e ->
                val w = "stage.regions[$j]"
                val r = e as? JsonObject ?: throw FilmTimelineException("$w is an object")
                known(r, w, "id", "box", "channel", "rect", "fit", "scale", "align", "background", "//")
                val id = r.string("id", w) ?: throw FilmTimelineException("$w has no id")
                if (id.contains(':')) throw FilmTimelineException("$w: a region id has no ':'")
                val box = r.string("box", w) ?: throw FilmTimelineException("$w names no box")
                val rect = r.vec("rect", w)?.takeIf { it.size == 4 && it[2] > 0 && it[3] > 0 }
                    ?: throw FilmTimelineException("$w: rect is [left, top, width, height] in CSS px, width and height more than 0")
                val fit = r.string("fit", w)?.also { if (it !in setOf("size", "contain", "width")) throw FilmTimelineException("$w: fit is size, contain or width, not \"$it\"") }
                r.number("scale", w)?.let { if (it <= 0) throw FilmTimelineException("$w: scale is more than 0") }
                r.string("align", w)?.let { if (it !in setOf("start", "center")) throw FilmTimelineException("$w: align is start or center") }
                FilmRegion(id, box, r.string("channel", w), rect, fit, r)
            }
            regions.groupBy { it.id }.filter { it.value.size > 1 }.keys.firstOrNull()?.let { throw FilmTimelineException("stage: two regions are called \"$it\"") }
            (o["caption"] as? JsonObject)?.let { c ->
                known(c, "stage.caption", "rect", "style", "//")
                c.vec("rect", "stage.caption")?.let { if (it.size != 4) throw FilmTimelineException("stage.caption: rect is [left, top, width, height]") }
                    ?: throw FilmTimelineException("stage.caption has no rect")
                if (c["style"] != null && c["style"] !is JsonObject) throw FilmTimelineException("stage.caption: style is an object of CSS properties")
            }
            return FilmStage(regions, o)
        }

        private fun view(element: JsonElement?, where: String, regions: Set<String>): String = when (element) {
            is JsonPrimitive -> element.content.also {
                if (element.isString && it !in regions) throw FilmTimelineException("$where: no stage region is called \"$it\"${if (regions.isEmpty()) "" else "; the regions are ${regions.joinToString()}"}")
            }
            is JsonObject -> {
                known(element, where, "box", "channel")
                val box = element.string("box", where) ?: throw FilmTimelineException("$where names a region, or a box and channel")
                "$box:${element.string("channel", where) ?: ""}"
            }
            else -> throw FilmTimelineException("$where names a region, or a box and channel")
        }

        private fun camera(o: JsonObject, t: Double, where: String): CameraKey {
            known(o, where, "t", "pose", "orbit", "ease", "arc", "//")
            val ease = FilmEase.of(o.string("ease", where), where)
            val arc = o.vec("arc", where)?.also { if (it.size != 3) throw FilmTimelineException("$where: arc is [x, y, z]") }
            val pose = o["pose"]
            val orbit = o["orbit"]
            if ((pose == null) == (orbit == null)) throw FilmTimelineException("$where has a pose or an orbit")
            if (pose != null) {
                val p = pose as? JsonObject ?: throw FilmTimelineException("$where: pose is {\"eye\", \"target\"}")
                known(p, "$where.pose", "eye", "target", "up", "fov")
                for (name in listOf("eye", "target", "up")) p.vec(name, "$where.pose")?.let { if (it.size != 3) throw FilmTimelineException("$where.pose: $name is [x, y, z]") }
                return CameraKey.Pose(t, ease, arc, FilmPose.of(p) ?: throw FilmTimelineException("$where.pose has an eye and a target"))
            }
            val b = orbit as? JsonObject ?: throw FilmTimelineException("$where: orbit is {\"degreesPerSecond\": …}")
            known(b, "$where.orbit", "eye", "target", "up", "degreesPerSecond")
            for (name in listOf("eye", "target", "up")) b.vec(name, "$where.orbit")?.let { if (it.size != 3) throw FilmTimelineException("$where.orbit: $name is [x, y, z]") }
            return CameraKey.Orbit(t, ease, arc, b.vec("eye", "$where.orbit"), b.vec("target", "$where.orbit"), b.vec("up", "$where.orbit"),
                b.number("degreesPerSecond", "$where.orbit") ?: throw FilmTimelineException("$where.orbit has degreesPerSecond"))
        }

        private fun partsKey(o: JsonObject, t: Double, where: String): PartsKey {
            known(o, where, "t", "hide", "show", "//")
            val hide = o["hide"]
            val show = o["show"]
            if ((hide == null) == (show == null)) throw FilmTimelineException("$where hides or shows parts")
            val which = hide ?: show!!
            val (ids, layers) = when (which) {
                is JsonArray -> which.strings("$where.${if (hide != null) "hide" else "show"}") to emptyList()
                is JsonObject -> {
                    val w = "$where.${if (hide != null) "hide" else "show"}"
                    known(which, w, "ids", "layer", "layers")
                    val layer = (which["layer"] as? JsonArray)?.let { listOf(it.strings("$w.layer")) }.orEmpty()
                    val layers = (which["layers"] as? JsonArray)?.mapIndexed { j, e -> (e as? JsonArray ?: throw FilmTimelineException("$w.layers[$j] is a layer path")).strings("$w.layers[$j]") }.orEmpty()
                    ((which["ids"] as? JsonArray)?.strings("$w.ids").orEmpty()) to (layer + layers)
                }
                else -> throw FilmTimelineException("$where: a list of part ids, or {\"ids\", \"layer\"}")
            }
            if (ids.isEmpty() && layers.isEmpty()) throw FilmTimelineException("$where names no parts")
            return PartsKey(t, hide != null, ids, layers)
        }

        private fun motionKey(o: JsonObject, t: Double, where: String): MotionKey? {
            val forms = listOf("value", "play", "rest").filter { it in o }
            if (forms.size > 1) throw FilmTimelineException("$where sets one of value, play or rest")
            if (forms.isEmpty()) { if ("tracks" in o) return null; throw FilmTimelineException("$where sets value, play, rest or tracks") }
            return when (forms.single()) {
                "value" -> MotionKey.Value(t, o.number("value", where)!!.also { if (it !in 0.0..1.0) throw FilmTimelineException("$where: a motion value is 0 to 1") },
                    FilmEase.of(o.string("ease", where), where))
                "rest" -> MotionKey.Rest(t)
                else -> {
                    val p = o["play"] as? JsonObject ?: throw FilmTimelineException("$where: play is {\"period\": seconds, \"shape\": …}")
                    known(p, "$where.play", "period", "shape")
                    val period = p.number("period", "$where.play")?.takeIf { it > 0 } ?: throw FilmTimelineException("$where.play: period is seconds, more than 0")
                    val shape = (p.string("shape", "$where.play") ?: "swing").lowercase()
                    if (shape !in setOf("swing", "loop", "once")) throw FilmTimelineException("$where.play: shape is swing, loop or once")
                    MotionKey.Play(t, period, shape)
                }
            }
        }

        private fun valueKey(o: JsonObject, t: Double, where: String, point: Boolean): ValueKey {
            known(o, where, "t", if (point) "at" else "value", "ease", "release", "//")
            val release = (o["release"] as? JsonPrimitive)?.booleanOrNull == true
            val name = if (point) "at" else "value"
            val value = if (point) o.vec("at", where)?.also { if (it.size != 2) throw FilmTimelineException("$where: at is [x, y] in the drawing's own coordinates") }
                else o.number("value", where)?.let { listOf(it) }
            if (release && value != null) throw FilmTimelineException("$where: a release key has no $name")
            if (!release && value == null) throw FilmTimelineException("$where has no $name")
            return ValueKey(t, value, FilmEase.of(o.string("ease", where), where), release)
        }

        private fun until(element: JsonElement?, where: String, regions: Set<String>): Until? {
            val o = element as? JsonObject ?: return if (element == null) null else throw FilmTimelineException("$where is an object")
            known(o, where, "view", "metadata", "of", "tol", "scale")
            val of = o.string("of", where) ?: "value"
            if (of !in setOf("x", "y", "value")) throw FilmTimelineException("$where: of is x, y or value")
            return Until(view(o["view"], "$where.view", regions), o.string("metadata", where) ?: throw FilmTimelineException("$where names the view's metadata to wait for"),
                of, o.number("tol", where) ?: 1e-6, o.number("scale", where) ?: 1.0)
        }

        private fun caption(e: JsonElement, where: String): CaptionKey {
            val o = e as? JsonObject ?: throw FilmTimelineException("$where is an object")
            known(o, where, "t", "text", "fade", "//")
            return CaptionKey(o.number("t", where) ?: throw FilmTimelineException("$where has no t"), o.string("text", where) ?: "",
                o.number("fade", where) ?: 0.0)
        }

        private fun sorted(times: List<Double>, where: String) {
            for (j in 1 until times.size) if (times[j] < times[j - 1]) throw FilmTimelineException("$where: keys go in time order (${times[j - 1]} then ${times[j]})")
        }

        private fun known(o: JsonObject, where: String, vararg names: String) {
            val unknown = o.keys - names.toSet()
            if (unknown.isNotEmpty()) throw FilmTimelineException("$where: unknown ${if (unknown.size == 1) "field" else "fields"} ${unknown.joinToString { "\"$it\"" }}; known: ${names.filter { it != "//" }.joinToString()}")
        }

        private fun JsonObject.number(name: String, where: String): Double? {
            val p = this[name] ?: return null
            if (p is JsonNull) return null
            return (p as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
                ?: throw FilmTimelineException("$where: $name is a number")
        }

        private fun JsonObject.string(name: String, where: String): String? {
            val p = this[name] ?: return null
            return (p as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw FilmTimelineException("$where: $name is a string")
        }

        private fun JsonObject.vec(name: String, where: String): List<Double>? {
            val a = this[name] ?: return null
            return (a as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.doubleOrNull ?: throw FilmTimelineException("$where: $name is a list of numbers") }
                ?: throw FilmTimelineException("$where: $name is a list of numbers")
        }

        private fun JsonArray.strings(where: String): List<String> =
            map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw FilmTimelineException("$where is a list of strings") }
    }
}

/** Key [k]'s value at [t], eased towards the next key (keys hold values: no releases). */
private fun interpolated(keys: List<ValueKey>, k: Int, t: Double): List<Double> {
    val here = keys[k].value!!
    val to = keys.getOrNull(k + 1) ?: return here
    if (to.ease == FilmEase.STEP || to.t <= keys[k].t) return if (t >= to.t) to.value!! else here
    val u = to.ease.at((t - keys[k].t) / (to.t - keys[k].t))
    return here.mapIndexed { j, a -> a + (to.value!![j] - a) * u }
}

/** The viewer's timeline shapes (`timelineAt` in threejs_viewer.html): a motion's value at phase [u]. */
internal fun timelineAt(shape: String, u: Double): Double {
    fun ease(x: Double) = x * x * (3 - 2 * x)
    if (shape == "loop") return ((u % 1.0) + 1.0) % 1.0
    if (shape == "once") return u.coerceIn(0.0, 1.0)
    val p = ((u % 1.0) + 1.0) % 1.0
    return when {
        p < 0.4 -> ease(p / 0.4)
        p < 0.5 -> 1.0
        p < 0.9 -> 1 - ease((p - 0.5) / 0.4)
        else -> 0.0
    }
}

private fun JsonObject.vec(name: String): List<Double>? = (this[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }

private fun List<Double>.json(): JsonArray = JsonArray(map { JsonPrimitive(it) })
private fun List<String>.jsonStrings(): JsonArray = JsonArray(map { JsonPrimitive(it) })

private fun mix(a: FilmPose, b: FilmPose, u: Double): FilmPose {
    fun lerp(x: List<Double>, y: List<Double>) = x.mapIndexed { j, v -> v + (y[j] - v) * u }
    val up = when {
        a.up != null && b.up != null -> normalize(lerp(a.up, b.up))
        else -> a.up ?: b.up
    }
    val fov = if (a.fov != null && b.fov != null) a.fov + (b.fov - a.fov) * u else b.fov ?: a.fov
    return FilmPose(lerp(a.eye, b.eye), lerp(a.target, b.target), up, fov)
}

private fun normalize(v: List<Double>): List<Double> {
    val n = sqrt(v.sumOf { it * it })
    return if (n < 1e-12) v else v.map { it / n }
}

/** [eye] turned about the line through [target] along [axis] by [angle] radians (Rodrigues). */
private fun rotate(eye: List<Double>, target: List<Double>, axis: List<Double>, angle: Double): List<Double> {
    val k = normalize(axis)
    val v = eye.mapIndexed { j, e -> e - target[j] }
    val c = cos(angle)
    val s = sin(angle)
    val dot = k[0] * v[0] + k[1] * v[1] + k[2] * v[2]
    val cross = listOf(k[1] * v[2] - k[2] * v[1], k[2] * v[0] - k[0] * v[2], k[0] * v[1] - k[1] * v[0])
    return List(3) { j -> target[j] + v[j] * c + cross[j] * s + k[j] * dot * (1 - c) }
}

/** True when two poses are the same to [tol] of the eye's distance from the target. */
internal fun FilmPose.near(other: FilmPose, tol: Double = 1e-6): Boolean {
    val scale = max(1.0, sqrt(eye.indices.sumOf { (eye[it] - target[it]).let { d -> d * d } }))
    fun close(a: List<Double>, b: List<Double>) = a.indices.all { abs(a[it] - b[it]) <= tol * scale }
    return close(eye, other.eye) && close(target, other.target)
}
