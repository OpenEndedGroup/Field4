# Field Document: chain_tracked_body

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. measured

<!-- field-box id="ctb_measured" index="0" -->

```edn [field-box id="ctb_measured" role="properties"]
{"id" "ctb_measured" "index" 0 "name" "measured" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "measured" "_tombstone" false "frame" #afield/rect [20.0 40.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ctb_measured_code_.kt" "lines" 68 "characters" 3618 "sha256" "4888fc0b48b013b16c9bcd50630d4b18e38b33fad6a219ebdcf48bfaea9781e5"}]}
```

```kotlin [field-property box="ctb_measured" name="code" path="ctb_measured_code_.kt"]
import java.io.File
import afield.graphics.scene.body.*
import afield.graphics.vulkan.targets.OpenXRHmdTarget.*
import kotlinx.serialization.json.*

// The recording: per frame, null or mediapipe's 33 landmarks as [x, y, z, visibility].
val rec = Json.parseToJsonElement(File("public/docs/assets/chain_tracked_body/swing_pose.json").readText()).jsonObject
val aspect = rec["width"]!!.jsonPrimitive.float / rec["height"]!!.jsonPrimitive.float
val frames = rec["frames"]!!.jsonArray.map { f ->
    if (f is JsonNull) null else f.jsonArray.map { p -> p.jsonArray.map { it.jsonPrimitive.float } }
}

// Image coordinates to a y-up world measured in frame heights.
fun world(p: List<Float>) = Vector3f((p[0] - .5f) * aspect, 1f - p[1], -p[2] * aspect)
fun mid(pose: List<List<Float>>, a: Int, b: Int) = world(pose[a]).add(world(pose[b])).mul(.5f)

// mediapipe's landmarks into WiVRn's eight body slots.
fun body(pose: List<List<Float>>) = BodyTrackingState().apply {
    isActive = true
    fun put(j: BodyJoint, p: Vector3f) = this[j].apply { position.set(p); positionValid = true }
    put(BodyJoint.HIPS, mid(pose, 23, 24))
    put(BodyJoint.CHEST, mid(pose, 11, 12))
    put(BodyJoint.LEFT_ARM_LOWER, mid(pose, 13, 15))   // forearm: elbow to wrist
    put(BodyJoint.RIGHT_ARM_LOWER, mid(pose, 14, 16))
    put(BodyJoint.LEFT_LOWER_LEG, mid(pose, 25, 27))   // shin: knee to ankle
    put(BodyJoint.RIGHT_LOWER_LEG, mid(pose, 26, 28))
    put(BodyJoint.LEFT_FOOT, world(pose[31]))
    put(BodyJoint.RIGHT_FOOT, world(pose[32]))
}
fun head(pose: List<List<Float>>) = JointPose(position = world(pose[0]), positionValid = true)

val geometry = frames.map { it?.let { p -> BodyGeometry(body(p), head(p)) } }

// The right wrist is the query, as in body_tracking's overlay; the body is limb cylinders and a head sphere.
val limb = .03f; val headR = .07f
val hits = frames.indices.map { i -> frames[i]?.let { geometry[i]!!.closestPoint(world(it[16]), limbRadius = limb, headRadius = headR) } }

val strip = mutableListOf<FLine>()
val step = 30 // one figure every 1.2 s
for (i in frames.indices step step) {
    val g = geometry[i] ?: continue
    val dx = i / step * 0.6f
    fun xy(p: Vector3f) = vec((p.x + dx) * 200.0, -p.y * 200.0)
    for ((a, b) in BodyGeometry.CONNECTIONS) {
        val p = g.joints[a] ?: continue; val q = g.joints[b] ?: continue
        strip += FLine().moveTo(xy(p)).lineTo(xy(q)).apply { thickness = 2.0; color = vec(.55, .85, .6, 1.0) }
    }
    val hit = hits[i]!!
    val wrist = world(frames[i]!![16])
    strip += FLine().moveTo(xy(wrist)).lineTo(xy(hit.point)).apply { thickness = 1.5; color = vec(1.0, .4, .7, 1.0) }
    strip += FLine().circle(xy(wrist).x, xy(wrist).y, 3.0).apply { filled = true; color = vec(1.0, .4, .7, 1.0) }
}
lines(strip, channel = "strip", w = 1100.0, h = 260.0, fitToSize = true)

val curve = FLine().apply { thickness = 1.5; color = vec(1.0, .4, .7, 1.0) }
var down = true
for (i in frames.indices) {
    val h = hits[i]
    if (h == null) { down = true; continue }
    val x = i * 3.0; val y = -h.signedDistance * 400.0
    if (down) curve.moveTo(x, y) else curve.lineTo(x, y)
    down = false
}
lines(curve, channel = "distance", w = 1100.0, h = 110.0, fitToSize = true)
val valid = hits.filterNotNull()
display("summary", "<pre>${frames.size} frames, ${valid.size} with a body\n" +
    "nearest primitive: a limb in ${valid.count { it.segment != null }} frames, the head in ${valid.count { it.head }}\n" +
    "signed distance ${"%.3f".format(valid.minOf { it.signedDistance })} to ${"%.3f".format(valid.maxOf { it.signedDistance })} frame heights</pre>")
```

## 2. heard

<!-- field-box id="ctb_heard" index="1" -->

```edn [field-box id="ctb_heard" role="properties"]
{"id" "ctb_heard" "index" 1 "name" "heard" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "heard" "_tombstone" false "frame" #afield/rect [20.0 500.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ctb_heard_code_.kt" "lines" 46 "characters" 2786 "sha256" "e0f9060daa8dbfc5a09a845dba39fbfe0f982a71faaa6d2d262d93c3010621bd"}]}
```

```kotlin [field-property box="ctb_heard" name="code" path="ctb_heard_code_.kt"]
import java.io.File
import kotlin.math.roundToInt
import afield.graphics.scene.body.*
import afield.graphics.vulkan.targets.OpenXRHmdTarget.*
import kotlinx.serialization.json.*

// The recording: per frame, null or mediapipe's 33 landmarks as [x, y, z, visibility].
val rec = Json.parseToJsonElement(File("public/docs/assets/chain_tracked_body/swing_pose.json").readText()).jsonObject
val aspect = rec["width"]!!.jsonPrimitive.float / rec["height"]!!.jsonPrimitive.float
val frames = rec["frames"]!!.jsonArray.map { f ->
    if (f is JsonNull) null else f.jsonArray.map { p -> p.jsonArray.map { it.jsonPrimitive.float } }
}

// Image coordinates to a y-up world measured in frame heights.
fun world(p: List<Float>) = Vector3f((p[0] - .5f) * aspect, 1f - p[1], -p[2] * aspect)
fun mid(pose: List<List<Float>>, a: Int, b: Int) = world(pose[a]).add(world(pose[b])).mul(.5f)

// mediapipe's landmarks into WiVRn's eight body slots.
fun body(pose: List<List<Float>>) = BodyTrackingState().apply {
    isActive = true
    fun put(j: BodyJoint, p: Vector3f) = this[j].apply { position.set(p); positionValid = true }
    put(BodyJoint.HIPS, mid(pose, 23, 24))
    put(BodyJoint.CHEST, mid(pose, 11, 12))
    put(BodyJoint.LEFT_ARM_LOWER, mid(pose, 13, 15))   // forearm: elbow to wrist
    put(BodyJoint.RIGHT_ARM_LOWER, mid(pose, 14, 16))
    put(BodyJoint.LEFT_LOWER_LEG, mid(pose, 25, 27))   // shin: knee to ankle
    put(BodyJoint.RIGHT_LOWER_LEG, mid(pose, 26, 28))
    put(BodyJoint.LEFT_FOOT, world(pose[31]))
    put(BodyJoint.RIGHT_FOOT, world(pose[32]))
}
fun head(pose: List<List<Float>>) = JointPose(position = world(pose[0]), positionValid = true)

val geometry = frames.map { it?.let { p -> BodyGeometry(body(p), head(p)) } }
val hits = frames.indices.map { i -> frames[i]?.let { geometry[i]!!.closestPoint(world(it[16]), limbRadius = .03f, headRadius = .07f) } }

// One note per five frames (a fifth of a second): nearer the body is higher, a gap is a rest.
val near = hits.filterNotNull().minOf { it.signedDistance }
val far = hits.filterNotNull().maxOf { it.signedDistance }
val notes = (frames.indices step 5).mapNotNull { i ->
    val h = hits[i] ?: return@mapNotNull null
    val t = (h.signedDistance - near) / (far - near)
    TimedNote(start = i.toDouble(), end = i + 5.0, note = (84 - t * 36).roundToInt().toDouble(), gain = if (h.head) 1.0 else .6)
}
val tune = patTimed(notes, beats = frames.size.toDouble())
showPatRollOf("right wrist" to tune, title = "the right wrist, heard", subtitle = "${notes.size} notes, MIDI ${notes.minOf { it.note }.toInt()}..${notes.maxOf { it.note }.toInt()}", cycles = 1, pitched = true)
showPatAudio(tune, cycles = 1, cps = 25.0 / frames.size, voice = patVoice(wave = PatWave.TRIANGLE, decay = .02, sustain = .6), channel = "audio")
```

