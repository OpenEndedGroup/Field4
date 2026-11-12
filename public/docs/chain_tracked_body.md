# A Tracked Body, Measured and Heard

In a clip from *Ballet Mécanique*, a woman swings while holding the ropes. We'll track her pose,
measure how close her wrist comes to the rest of her body, draw the result over the film and turn
the distance into sound. Along the way we'll use [body geometry](body_tracking.md), `FLine` and
Field's pattern language together.

The public-domain clip ships with Field. The `Pose` detector from [Computer vision in
Python](python_cv.md) (mediapipe) has already been run over it, saving either 33 landmarks or no
pose for each frame in [swing_pose.json](assets/chain_tracked_body/swing_pose.json). To make the
recording again, run [record_pose.py](https://github.com/OpenEndedGroup/Field4/blob/main/templates/chain_tracked_body/record_pose.py).

## From landmarks to WiVRn's body slots

`BodyGeometry` reads a `BodyTrackingState`, the eight slots WiVRn reports: hips, chest, lower arms,
lower legs and feet. mediapipe reports shoulders, elbows, wrists, hips, knees, ankles and toes, so
the box below fills each slot from the nearest landmarks: the chest is between the shoulders, a
lower arm is halfway along the forearm, a lower leg halfway along the shin. The nose stands in for
the head pose. Image coordinates become a y-up world measured in frame heights, not metres.

We query the right wrist, as in the live tracking overlay, against limb cylinders with a radius of
0.03 frame heights and a head sphere with a radius of 0.07. `videoFrames` decodes the clip with ffmpeg, and
`drawFLines` draws onto every 30th frame: mediapipe's own skeleton in thin white, the eight body
slots and their connections in green, and the right wrist in pink, with a line to the closest point
the geometry found. Below the stills is the signed distance over the whole clip.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_tracked_body/measured.png" scale="1.5" wait="3" capture="box" open-outputs="true" w="1180" h="640" padding="18" timeout="120" name="chain-tracked-body-measured" embed=true alt="Ten stills from the swing film, two rows of five, each labelled with its frame and time: a woman in a patterned dress holding the swing ropes at shoulder height, with a thin white mediapipe skeleton, green body slots and connections, and a pink dot on her right wrist joined to the nearest point of her body; below, a jagged pink curve of the wrist distance across the clip; below that, a summary of frames and distances"}
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

The clip opens with no body in frame, so the curve starts where the detector first finds one. The
film shows her from the waist up; the hips, legs and feet are mediapipe's guesses below the frame.
Those estimates still fill the slots, and `BodyGeometry` has no way to distinguish them.
For 71 of the 308 frames the nearest thing to the wrist is the head sphere: she holds the swing's
ropes, so her hands are up beside her head. The wrist is never inside the body. Its distance stays
at least 0.103 frame heights, partly because a lower-arm slot sits mid-forearm, so the wrist is
always about half a forearm from the end of its own limb.

The jitter from frame to frame comes from the detector. `BodyGeometry` copies each frame as it is:
it neither smooths the positions nor fills gaps, so a frame with no body has no hit.

## The distance as a pattern

`patTimed` turns notes with explicit start and end times into a pattern. Every fifth frame becomes
a note a fifth of a second long: the nearer the wrist is to the body, the higher the note. A note
whose nearest primitive is the head plays louder. A frame without a body is a rest. `cps` is set
so that one cycle lasts as long as the clip.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_tracked_body/heard.png" wait="4" capture="box" open-outputs="true" w="1180" h="420" padding="18" timeout="120" name="chain-tracked-body-heard" embed=true alt="A one-lane piano roll titled 'the right wrist, heard', 61 short blue notes between MIDI 49 and 82 rising and falling with the distance curve, starting after an empty stretch, above a 14-second audio player"}
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
```

Here it is, rendered offline to a `.wav` from the same recording:

```diffstatic_runner {data-lang=kotlin audio="assets/chain_tracked_body/right-wrist.wav" audio-spec="right-wrist" audio-renderer="afield.audio.synth.TrackedBodyDocs" embed=true hidden=true name="chain-tracked-body-audio"}
// TrackedBodyDocs repeats the heard box's steps and renders one cycle at the clip's speed.
```

The rests at the start are the frames with no body. The louder notes mark the frames where her head
is the part nearest to her wrist.

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
