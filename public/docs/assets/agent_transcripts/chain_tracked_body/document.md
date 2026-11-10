# Field Document: chain_tracked_body

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. measured

<!-- field-box id="ctb_measured" index="0" -->

```edn [field-box id="ctb_measured" role="properties"]
{"id" "ctb_measured" "index" 0 "name" "measured" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "measured" "_tombstone" false "frame" #afield/rect [20.0 40.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ctb_measured_code_.kt" "lines" 123 "characters" 4812 "sha256" "411e8588711111327e259d02e7c491bdda1e58307a91454de0dddba315aab49f"}]}
```

```kotlin [field-property box="ctb_measured" name="code" path="ctb_measured_code_.kt"]
import afield.graphics.scene.body.*
import afield.graphics.vulkan.targets.OpenXRHmdTarget.*
import afield.media.image.*
import afield.media.video.*
import java.io.File
import kotlinx.serialization.json.*

// The recording: per frame, null or mediapipe's 33 landmarks as [x, y, z, visibility].
val rec = Json.parseToJsonElement(File("public/docs/assets/chain_tracked_body/swing_pose.json").readText()).jsonObject
val aspect = rec["width"]!!.jsonPrimitive.float / rec["height"]!!.jsonPrimitive.float
val frames =
    rec["frames"]!!.jsonArray.map { f ->
        if (f is JsonNull) null else f.jsonArray.map { p -> p.jsonArray.map { it.jsonPrimitive.float } }
    }

// Image coordinates to a y-up world measured in frame heights.
fun world(p: List<Float>) = Vector3f((p[0] - .5f) * aspect, 1f - p[1], -p[2] * aspect)
fun mid(pose: List<List<Float>>, a: Int, b: Int) = world(pose[a]).add(world(pose[b])).mul(.5f)

// mediapipe's landmarks into WiVRn's eight body slots.
fun body(pose: List<List<Float>>) = BodyTrackingState().apply {
    isActive = true
    fun put(j: BodyJoint, p: Vector3f) = this[j].apply {
        position.set(p)
        positionValid = true
    }
    put(BodyJoint.HIPS, mid(pose, 23, 24))
    put(BodyJoint.CHEST, mid(pose, 11, 12))
    put(BodyJoint.LEFT_ARM_LOWER, mid(pose, 13, 15)) // forearm: elbow to wrist
    put(BodyJoint.RIGHT_ARM_LOWER, mid(pose, 14, 16))
    put(BodyJoint.LEFT_LOWER_LEG, mid(pose, 25, 27)) // shin: knee to ankle
    put(BodyJoint.RIGHT_LOWER_LEG, mid(pose, 26, 28))
    put(BodyJoint.LEFT_FOOT, world(pose[31]))
    put(BodyJoint.RIGHT_FOOT, world(pose[32]))
}
fun head(pose: List<List<Float>>) = JointPose(position = world(pose[0]), positionValid = true)

val geometry = frames.map { it?.let { p -> BodyGeometry(body(p), head(p)) } }

// The right wrist is the query, as in body_tracking's overlay; the body is limb cylinders and a head sphere.
val limb = .03f
val headR = .07f
val hits =
    frames.indices.map { i ->
        frames[i]?.let { geometry[i]!!.closestPoint(world(it[16]), limbRadius = limb, headRadius = headR) }
    }

// The film itself, decoded by ffmpeg: every frame, in the order the detector saw them.
val clip = "python/fieldcv/samples/ballet_mecanique_swing.mp4"
val step = 30 // one still every 1.2 s
val shown = frames.indices.filter { it % step == 0 && frames[it] != null }
val stills = videoFrames(clip, width = 320).withIndex().filter { it.index in shown }.map { it.value }.toList()

// Back from the world to the still's pixels; depth is dropped.
fun px(p: Vector3f, img: Img) = vec((p.x / aspect + .5) * img.width, (1 - p.y) * img.height.toDouble())
fun seg(a: Vec2, b: Vec2, c: Vec4, t: Double) = FLine().moveTo(a).lineTo(b).apply {
    thickness = t
    color = c
}
fun dot(a: Vec2, r: Double, c: Vec4) = FLine().circle(a.x, a.y, r).apply {
    filled = true
    color = c
}
val white = vec(1.0, 1.0, 1.0, .45)
val green = vec(.45, 1.0, .55, 1.0)
val pink = vec(1.0, .35, .7, 1.0)
// mediapipe's own skeleton, thin: arms, shoulders, hips, legs.
val bones = [
    11 to 13,
    13 to 15,
    12 to 14,
    14 to 16,
    11 to 12,
    23 to 24,
    11 to 23,
    12 to 24,
    23 to 25,
    25 to 27,
    27 to 31,
    24 to 26,
    26 to 28,
    28 to 32,
]

val panels =
    shown.zip(stills).map { (i, img) ->
        val pose = frames[i]!!
        val g = geometry[i]!!
        val over = mutableListOf<FLine>()
        for ((a, b) in bones) over += seg(px(world(pose[a]), img), px(world(pose[b]), img), white, 1.5)
        for ((a, b) in BodyGeometry.CONNECTIONS) over += seg(px(g.joints[a]!!, img), px(g.joints[b]!!, img), green, 3.0)
        for (p in g.joints.values) over += dot(px(p, img), 3.0, green)
        val wrist = px(world(pose[16]), img)
        over += seg(wrist, px(hits[i]!!.point, img), pink, 2.5)
        over += dot(wrist, 4.5, pink)
        "frame $i, ${(i / 25.0).f1} s" to img.drawFLines(over, scale = 2.0)
    }
showImgGrid(*panels.toTypedArray(), channel = "stills", columns = 5, cellSide = 300)

val curve = FLine().apply {
    thickness = 1.5
    color = vec(1.0, .4, .7, 1.0)
}
var down = true
for (i in frames.indices) {
    val h = hits[i]
    if (h == null) {
        down = true
        continue
    }
    val x = i * 3.0
    val y = -h.signedDistance * 400.0
    if (down) curve.moveTo(x, y) else curve.lineTo(x, y)
    down = false
}
lines(curve, channel = "distance", w = 1100.0, h = 110.0, fitToSize = true)
val valid = hits.filterNotNull()
display(
    "summary",
    "<pre>${frames.size} frames, ${valid.size} with a body\n" +
        "nearest primitive: a limb in ${valid.count { it.segment != null }} frames, the head in ${valid.count { it.head }}\n" +
        "signed distance ${valid.minOf { it.signedDistance }.f3} to ${valid.maxOf { it.signedDistance }.f3} frame heights</pre>",
)
```

## 2. heard

<!-- field-box id="ctb_heard" index="1" -->

```edn [field-box id="ctb_heard" role="properties"]
{"id" "ctb_heard" "index" 1 "name" "heard" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "heard" "_tombstone" false "frame" #afield/rect [20.0 500.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ctb_heard_code_.kt" "lines" 71 "characters" 2954 "sha256" "e93c3349a252127e6829118e17b6dfccd5b4e30531e74edcf4b9df8c93b60fb4"}]}
```

```kotlin [field-property box="ctb_heard" name="code" path="ctb_heard_code_.kt"]
import afield.graphics.scene.body.*
import afield.graphics.vulkan.targets.OpenXRHmdTarget.*
import java.io.File
import kotlin.math.roundToInt
import kotlinx.serialization.json.*

// The recording: per frame, null or mediapipe's 33 landmarks as [x, y, z, visibility].
val rec = Json.parseToJsonElement(File("public/docs/assets/chain_tracked_body/swing_pose.json").readText()).jsonObject
val aspect = rec["width"]!!.jsonPrimitive.float / rec["height"]!!.jsonPrimitive.float
val frames =
    rec["frames"]!!.jsonArray.map { f ->
        if (f is JsonNull) null else f.jsonArray.map { p -> p.jsonArray.map { it.jsonPrimitive.float } }
    }

// Image coordinates to a y-up world measured in frame heights.
fun world(p: List<Float>) = Vector3f((p[0] - .5f) * aspect, 1f - p[1], -p[2] * aspect)
fun mid(pose: List<List<Float>>, a: Int, b: Int) = world(pose[a]).add(world(pose[b])).mul(.5f)

// mediapipe's landmarks into WiVRn's eight body slots.
fun body(pose: List<List<Float>>) = BodyTrackingState().apply {
    isActive = true
    fun put(j: BodyJoint, p: Vector3f) = this[j].apply {
        position.set(p)
        positionValid = true
    }
    put(BodyJoint.HIPS, mid(pose, 23, 24))
    put(BodyJoint.CHEST, mid(pose, 11, 12))
    put(BodyJoint.LEFT_ARM_LOWER, mid(pose, 13, 15)) // forearm: elbow to wrist
    put(BodyJoint.RIGHT_ARM_LOWER, mid(pose, 14, 16))
    put(BodyJoint.LEFT_LOWER_LEG, mid(pose, 25, 27)) // shin: knee to ankle
    put(BodyJoint.RIGHT_LOWER_LEG, mid(pose, 26, 28))
    put(BodyJoint.LEFT_FOOT, world(pose[31]))
    put(BodyJoint.RIGHT_FOOT, world(pose[32]))
}
fun head(pose: List<List<Float>>) = JointPose(position = world(pose[0]), positionValid = true)

val geometry = frames.map { it?.let { p -> BodyGeometry(body(p), head(p)) } }
val hits =
    frames.indices.map { i ->
        frames[i]?.let { geometry[i]!!.closestPoint(world(it[16]), limbRadius = .03f, headRadius = .07f) }
    }

// One note per five frames (a fifth of a second): nearer the body is higher, a gap is a rest.
val near = hits.filterNotNull().minOf { it.signedDistance }
val far = hits.filterNotNull().maxOf { it.signedDistance }
val notes =
    (frames.indices step 5).mapNotNull { i ->
        val h = hits[i] ?: return@mapNotNull null
        val t = (h.signedDistance - near) / (far - near)
        TimedNote(
            start = i.toDouble(),
            end = i + 5.0,
            note = (84 - t * 36).roundToInt().toDouble(),
            gain = if (h.head) 1.0 else .6,
        )
    }
val tune = patTimed(notes, beats = frames.size.toDouble())
showPatRollOf(
    "right wrist" to tune,
    title = "the right wrist, heard",
    subtitle = "${notes.size} notes, MIDI ${notes.minOf { it.note }.toInt()}..${notes.maxOf { it.note }.toInt()}",
    cycles = 1,
    pitched = true,
)
showPatAudio(
    tune,
    cycles = 1,
    cps = 25.0 / frames.size,
    voice = patVoice(wave = PatWave.TRIANGLE, decay = .02, sustain = .6),
    channel = "audio",
)
```

