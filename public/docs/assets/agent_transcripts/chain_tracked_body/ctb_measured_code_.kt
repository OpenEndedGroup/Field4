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
