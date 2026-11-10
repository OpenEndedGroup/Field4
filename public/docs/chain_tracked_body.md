# A Tracked Body, Measured and Heard

Take a recording of a moving body, ask [Body and hand tracking](body_tracking.md)'s geometry where
one hand is relative to the rest of the body, draw the answer with `FLine`, and play it as a
pattern. Four parts of Field that know nothing about each other are joined by a few lines of box
code each.

The body comes from a film rather than a headset. [Computer vision in Python](python_cv.md)'s
`Pose` detector (mediapipe) ran over the public-domain *Ballet Mécanique* swing clip that ships in
`python/fieldcv/samples/`, and `templates/chain_tracked_body/record_pose.py` saved what it found
as `public/docs/assets/chain_tracked_body/swing_pose.json`: for each of the clip's frames, either
nothing or 33 landmarks. Re-running that script reproduces the file.

## From landmarks to WiVRn's body slots

`BodyGeometry` reads a `BodyTrackingState`, the eight slots WiVRn reports: hips, chest, lower arms,
lower legs and feet. mediapipe reports shoulders, elbows, wrists, hips, knees, ankles and toes, so
the box below fills each slot from the nearest landmarks: the chest is between the shoulders, a
lower arm is halfway along the forearm, a lower leg halfway along the shin. The nose stands in for
the head pose. Image coordinates become a y-up world measured in frame heights, not metres.

The query is the right wrist, as in the live page's overlay, measured against limb cylinders 0.03
frame heights thick and a head sphere of 0.07. Every 30th frame is drawn as a figure, left to right,
with a pink line from the wrist to the closest point the geometry found. Below it is the signed
distance over the whole clip.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_tracked_body/measured.png" wait="3" capture="box" open-outputs="true" w="1180" h="640" padding="18" timeout="120" name="chain-tracked-body-measured" embed=true alt="Eleven green stick figures in a row, a woman on a swing every 1.2 seconds, with a pink dot at each right wrist joined to the nearest point of her body; below, a jagged pink curve of the wrist's distance to the body across the clip; below that, a summary of frames and distances"}
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

The clip opens with no body in frame, so the curve starts where the detector first finds one.
For 71 of the 308 frames the nearest thing to the wrist is the head sphere: she holds the swing's
ropes, so her hands are up beside her head. The wrist is never inside the body. Its distance stays
at least 0.103 frame heights, partly because a lower-arm slot sits mid-forearm, so the wrist is
always about half a forearm from the end of its own limb.

The jitter from frame to frame comes from the detector. `BodyGeometry` copies each frame as it is
and does not smooth or fill gaps, so a frame with no body has no hit.

## The distance as a pattern

`patTimed` turns notes with explicit start and end times into a pattern. Every fifth frame becomes
a note a fifth of a second long: the nearer the wrist is to the body, the higher the note. A note
whose nearest primitive is the head plays louder. A frame without a body is a rest. `cps` is set
so that one cycle lasts as long as the clip.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_tracked_body/heard.png" wait="4" capture="box" open-outputs="true" w="1180" h="420" padding="18" timeout="120" name="chain-tracked-body-heard" embed=true alt="A one-lane piano roll titled 'the right wrist, heard', 61 short blue notes between MIDI 49 and 82 rising and falling with the distance curve, starting after an empty stretch, above a 14-second audio player"}
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

## What is not here yet

There is no recorder for a live WiVRn session, so this page cannot replay a headset. The mapping
from mediapipe's landmarks to the eight slots above is box code and is not part of `fieldcv` or
`BodyGeometry`. mediapipe's `z` is an estimate of depth from a single camera, so distances include its
error as well as the drawing's.

## Where each piece is documented

* [Body and hand tracking](body_tracking.md) for `BodyGeometry`, `closestPoint` and `BodyHit`.
* [Computer vision in Python](python_cv.md) for `fieldcv` and its `Pose` detector.
* [FLine examples](fline_examples.md) for drawing lines into a box's output.
* [A Drawn Score, Heard](chain_drawn_score.md) is the nearest chain: `patTimed`, `showPatRollOf`
  and `patVoice` are used the same way there.
