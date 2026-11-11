import afield.graphics.scene.body.*
import afield.graphics.vulkan.targets.OpenXRHmdTarget.*
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
