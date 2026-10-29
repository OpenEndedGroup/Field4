# Generative Mocap — ARDY and CLoSD

Generate human movement from text, direct it along a Field curve, or apply forces to a simulated
body. **ARDY** generates kinematic motion. **CLoSD** runs a motion planner and a physical humanoid.
They are separate optional backends. Recordings play in Field without installing either one.

Start with [resistance and release](agent_transcripts.md#motion_resistance_replay): open the
starting point and run the box. White bones are the executed body; cyan bones are its reference.
Red marks a foot tether; amber shows the submitted force, scaled for drawing. This recording
attaches the foot at two seconds and releases it at four, while the walking prompt stays unchanged.

<video controls preload="metadata" style="max-width:100%;height:auto" src="assets/motion_recordings/resistance-follow.mp4"></video>

## Open a recording

[Recorded CLoSD](agent_transcripts.md#motion_closd_recording) has a time slider, playback,
reference-pose and camera-follow controls. Drag in the 3D view to orbit; scroll to zoom.
To load your own recording:

```diffstatic {data-lang=kotlin}
import afield.animation.mocap.*

val clip = MotionRecording("performance.f4motion".path).clip()
val started = AnimationClock.nowSeconds()
loop {
    val duration = clip.endTime - clip.startTime
    val time = clip.startTime + (AnimationClock.nowSeconds() - started) % duration
    val snapshot = clip.sample(time)
    showMotion(snapshot.frame, clip.skeleton)
    displayOpen("motion")
    delay(16)
}
```

Recordings use metres, seconds and Field's Y-up coordinates. `clip()` loads all poses;
`MotionRecording.frames()` streams a long recording. Sampling interpolates rotations along the
shortest arc, holds the last pose at the end, and never blends across resets. Joint names come
from the recording's skeleton. `snapshot.frame.executed.bones(clip.skeleton)` returns an ordinary
FLine for use with your own renderer and camera.

## Run a live body

Install the backend you want using the [optional setup guide](https://github.com/OpenEndedGroup/Field4/blob/main/optional/motion/README.md).
On Linux/NVIDIA, `./field.sh --setup` lists ARDY and CLoSD under experimental extras. ARDY requires
[Meta-Llama-3-8B-Instruct access](https://huggingface.co/meta-llama/Meta-Llama-3-8B-Instruct);
CLoSD requires [Isaac Gym Preview](https://developer.nvidia.com/isaac-gym-preview-4).
Set `FIELD_MOTION_HOME` to your backend installation directory before starting Field.

Open [live CLoSD](agent_transcripts.md#motion_closd_live), enable **Advance**, then try
**Drag**, **Resist** and **Release**. Resist uses a stronger tether: 8000 N/m stiffness,
120 N·s/m damping and a 2500 N force cap. Adjust the values while it is attached. The force and
stretch readout shows what reaches the simulator. A capped spring can stretch; it is not a hard pin.

Change the prompt, move the anchor, make a tether break above a threshold, or apply a torso impulse.
A fall may trigger the stock controller's reset, which releases its attachments. The status
reports releases, breaks and resets separately. Stopping the box closes the worker. Each live
scene saves a new recording under `motion-recordings` in the document workspace.

## Write a movement score

The [movement score starting point](agent_transcripts.md#motion_tether_score) runs this sequence:
walk for two seconds, tether the left ankle for two, then release it and continue for three.
The code needs the optional CLoSD environment. The MP4 replays an actual recorded run of this
score in Field; you can watch or load its recording without installing the simulator.

```diffstatic {data-lang=kotlin}
:::fold imports
import afield.animation.mocap.*
import kotlin.time.Duration.Companion.seconds
:::endfold

frames {
    CLoSD.openAsync().awaitMotion().use { dancer ->
        dancer.prompt("A person walks forward.")
        fun draw(snapshot: MotionSnapshot) {
            showMotion(snapshot.frame, dancer.skeleton, follow=true,
                overlays=dancer.telemetry.tethers().flatMap { it.overlays() })
            val foot = dancer.telemetry.tether("left-foot")
            markdown("status", when {
                foot?.active == true -> "**TETHERED** · ${"%.0f".format(foot.forceN)} N"
                foot != null -> "**RELEASED**"
                else -> "**WALKING**"
            })
            displayOpen("motion"); displayOpen("status")
        }
        playMotion(dancer, 2.seconds, ::draw)
        val foot = dancer.body("L_Ankle").tether("left-foot") {
            stiffness = 8000.0
            damping = 120.0
            maxForce = 2500.0
        }
        playMotion(dancer, 2.seconds, ::draw)
        foot.release()
        playMotion(dancer, 3.seconds, ::draw)
    }
}
```

```diffstatic_runner {data-lang=kotlin capture="box" execution="launch" video="assets/motion_recordings/movement-score.mp4" duration="7" fps="15" open-outputs="true" wait-output="true" wait-output-kind="display" wait="0.2" timeout="240" w="700" h="80" embed=true hidden=true name="motion-score-replay-movie" alt="Field replays the actual Kotlin movement score: walking, a stronger ankle tether, then release. The complete body, reference, anchor and force are visible."}
:::fold imports and recorded events
import afield.animation.mocap.*

val take = MotionRecording("%docs/assets/motion_recordings/closd-movement-score.f4motion".path)
val clip = take.clip()
val events = take.events().toList()
val attach = events.first { it.optString("type")=="applied" && it.optString("operation")=="ATTACH" }
val release = events.first { it.optString("type")=="applied" && it.optString("operation")=="DETACH" }.getDouble("appliedTime")
val parameters = attach.getJSONObject("payload")
val physics = events.filter { it.optString("type")=="tetherPhysics" }
val started = AnimationClock.nowSeconds()
:::endfold

loop {
    val elapsed = AnimationClock.nowSeconds() - started
    val duration = clip.endTime - clip.startTime
    val time = clip.startTime + elapsed % duration
    val frame = clip.sample(time).frame
    val active = time >= attach.getDouble("appliedTime") && time < release
    val event = physics.lastOrNull { it.getDouble("time") <= time }
    fun values(key: String) = event!!.getJSONArray(key).let { a -> (0..2).map { a.getDouble(it) } }
    val tether = if(active && event != null) TetherObservation(
        parameters.getString("id"), parameters.getString("body"), true, "attached", event.getDouble("time"),
        forceN=event.getDouble("forceN"), rawForceN=event.getDouble("rawForceN"),
        stretchMetres=event.getDouble("errorM"), maxForce=parameters.getDouble("maximumForce"),
        point=values("point"), anchor=values("anchor"), force=values("force")) else null
    val body = frame.executed.bones(clip.skeleton).apply {
        color=vec(.94,.96,1.0,1.0); thickness=3.0; pointed=true; pointSize=.8
    }
    val reference = frame.intended?.bones(clip.skeleton)?.apply { color=vec(.15,.7,.85,.65); thickness=1.5 }
    three(channel="motion", height=480, width=700) {
        lines(body); reference?.let { lines(it) }; lines(tether?.overlays() ?: emptyList())
        camera(yaw=35.0, pitch=12.0, padding=1.5)
        followCamera(vec(frame.executed.root[0],frame.executed.root[1],frame.executed.root[2]),
            halfLifeSeconds=.4, epoch="${(elapsed/duration).toInt()}:${frame.episode}")
        title("CLoSD · movement score · ${"%.2f".format(time)} s")
        helpers(true)
    }
    val phase = when {
        active -> "TETHERED · ${"%.0f".format(tether?.forceN ?: 0.0)} N"
        time >= release -> "RELEASED"
        else -> "WALKING"
    }
    markdown("status", "**$phase**\n\nRecorded execution of the Kotlin score: walk 2 s, tether 2 s, release and continue 3 s. " +
        "White: executed · cyan: reference · red: anchor · amber: submitted force (scaled). No reset in this take.")
    displayOpen("motion"); displayOpen("status")
    delay(16)
}
```

`awaitMotion()` suspends while the worker opens. `playMotion` advances the backend and draws
interpolated snapshots without waiting for inference on the render thread. `body("L_Ankle")`
uses the simulator's explicit body mapping and captures the current attachment point as its
anchor. Keep the handle to change or release the same connection:

```diffstatic {data-lang=kotlin}
foot.tune {
    stiffness = 8000.0
    damping = 120.0
    maxForce = 2500.0
    breakAbove(1800.0, forAtLeast = 100.milliseconds)
}
foot.moveTo(vec(0.0, 0.08, 0.5), velocity = vec(0.1, 0.0, 0.0))
dancer.body("Torso").impulse(vec(10.0, 0.0, 0.0))
foot.release()
```

Import `kotlin.time.Duration.Companion.milliseconds` for that break duration. Positions and
velocities use Field's vectors; stiffness, damping and forces use SI units. The futures acknowledge
scheduling. `foot.observation` reports the actual force, stretch, saturation and attachment state.
Measured force and mechanical power are not a measure of the performer's effort.

## Follow the moving body

Enable **Follow body** in either live scene. **Follow half-life seconds** controls how quickly the
camera catches up: at 0.4 s, half the remaining translation is covered every 0.4 s. Following is
horizontal, so jumping does not bob the camera. You can still orbit, pan and zoom. Disable Follow
to leave the camera in the world.

This replay starts with a fixed camera, then enables Follow at two seconds. Watch the ground grid
move past the body:

```diffstatic_runner {data-lang=kotlin capture="box" execution="launch" video="assets/motion_recordings/camera-follow.mp4" duration="9" fps="15" open-outputs="true" wait-output="true" wait-output-kind="display" wait="0.2" timeout="240" w="700" h="80" embed=true name="motion-follow-movie" alt="Recorded human motion rendered by the Kotlin code shown here, inside Field."}
:::fold imports and recording
import afield.animation.mocap.*
val clip = MotionRecording("%docs/assets/motion_recordings/closd-resistance.f4motion".path).clip()
val started = AnimationClock.nowSeconds()
:::endfold

loop {
    val elapsed = AnimationClock.nowSeconds() - started
    val duration = clip.endTime - clip.startTime
    val time = elapsed % duration
    val snapshot = clip.sample(clip.startTime + time)
    // First two seconds: fixed camera. Then follow; the grid stays in world coordinates.
    val following = time >= 2.0
    showMotion(snapshot.frame, clip.skeleton, reference=false,
        follow=following, followHalfLife=.4, followEpoch="${(elapsed/duration).toInt()}:${snapshot.frame.episode}")
    markdown("status", if(following) "**Following the body** · 0.4 s half-life · orbit and zoom remain yours"
        else "**Fixed camera** · Follow engages at two seconds")
    displayOpen("motion"); displayOpen("status")
    delay(16)
}
```

For a live session, the call is simply:

```diffstatic {data-lang=kotlin}
showMotion(snapshot.frame, dancer.skeleton, follow=true, followHalfLife=.4)
```

Use `followVertical=true` to follow all axes. On an ordinary Three view, call
`followCamera(rootPosition, halfLifeSeconds=.4, epoch=episode.toString())` inside `three { ... }`.
A changed episode starts a fresh camera position. Include a loop counter in `followEpoch` when
repeating a recording, as the replay above does.

## Give ARDY an intention

Open [live ARDY](agent_transcripts.md#motion_ardy_intentions), enable **Advance**, and try
**Skip**, **Run**, **Sneak** or **Recoil**. Use the intention field for your own prompt, then
**Apply intention**. **Reset episode** starts a fresh motion history. Prompt embeddings are cached;
new text needs the optional encoder service described in the setup guide.

This skipping take comes from the interactive Field session, using four denoising steps. It is
six uninterrupted seconds, not a new batch generation or a reconstruction. Open
[the recorded ARDY demo](agent_transcripts.md#motion_ardy_demo_replay) without installing the model:

```diffstatic_runner {data-lang=kotlin capture="box" execution="launch" video="assets/motion_recordings/ardy-live-skipping.mp4" duration="6" fps="15" open-outputs="true" wait-output="true" wait-output-kind="display" wait="0.2" timeout="240" w="700" h="80" embed=true name="motion-ardy-live-demo" alt="ARDY skipping, replayed in Field from an uninterrupted excerpt of the interactive session, with moving limbs and a following camera."}
:::fold imports and recording
import afield.animation.mocap.*
val clip = MotionRecording("%docs/assets/motion_recordings/ardy-live-skip.f4motion".path).clip()
val started = AnimationClock.nowSeconds()
:::endfold

loop {
    val elapsed = AnimationClock.nowSeconds() - started
    val duration = clip.endTime - clip.startTime
    val snapshot = clip.sample(clip.startTime + elapsed % duration)
    val pose = snapshot.frame.executed
    val bones = pose.bones(clip.skeleton).apply {
        color=vec(.94,.96,1.0,1.0); thickness=3.0
        pointed=true; pointSize=.8
    }
    three(channel="motion", height=480, width=700) {
        lines(bones)
        camera(yaw=35.0, pitch=12.0, padding=2.6) // Room for the whole skipping gesture.
        followCamera(vec(pose.root[0], pose.root[1], pose.root[2]), halfLifeSeconds=.4,
            epoch="${(elapsed/duration).toInt()}:${snapshot.frame.episode}")
        title("ARDY · skipping · ${"%.2f".format(snapshot.frame.time)} s")
        helpers(true)
    }
    markdown("status", "**ARDY · skipping**\n\n" +
        "Six uninterrupted seconds from the interactive session. Prompt: A person skips forward. " +
        "Four denoising steps; original poses and session timestamps retained.")
    displayOpen("motion"); displayOpen("status")
    delay(16)
}
```

The prompt and motion history matter. This session produces lively skipping; the walking-route
trials below slide. Use the live controls to explore a phrase and its transitions, then save the
result you want.

## Give ARDY a route

Open [timed paths](agent_transcripts.md#motion_ardy_path),
[changing intentions](agent_transcripts.md#motion_ardy_intentions), or
[foot restriction](agent_transcripts.md#motion_ardy_foot) after installing ARDY.
The path scene samples an FLine by arc length. Edit its geometry, duration or heading independently.

```diffstatic {data-lang=kotlin}
:::fold imports
import afield.animation.mocap.*
import kotlin.time.Duration.Companion.seconds
:::endfold

val path = fline {
    moveTo(0.0, 0.0, 0.0)
    cubicTo(0.0, 0.0, 2.0, 2.0, 0.0, 2.0, 2.0, 0.0, 4.0)
    color=vec(.9,.7,.2,1.0)
}
frames {
    ARDY.openAsync().awaitMotion().use { dancer ->
        dancer.prompt("A person walks forward.")
        dancer.route(path, 8.seconds)
        playMotion(dancer, 8.seconds) { snapshot ->
            showMotion(snapshot.frame, dancer.skeleton, overlays=[path], follow=true)
            displayOpen("motion")
        }
    }
}
```

Pass `distanceFraction={ t -> t*t*(3-2*t) }` for an eased speed envelope, or `headings=false` to
leave heading unconditioned. After advancing to a pose, `dancer.joint("LeftFoot").hold(2.seconds)`
repeats position targets there; `orientation=true` adds orientation. `release()` clears the joint
targets. ARDY's pinned representation also needs a root route covering that interval.
These targets condition a generator; they do not apply a physical force or guarantee a pinned foot.

The first batch route recording slides a nearly static body along the path. A repeat with ten denoising
steps has more articulation but still slides. Compare both below before treating route adherence
as useful walking.

## Recorded results

These are complete recorded outputs, including failures. The renderer is ordinary Kotlin in a
Field box: load each clip, sample its pose and draw its bones. Open
[the recording comparison](agent_transcripts.md#motion_recorded_results) to edit it. Set
`experiment` to `"ardy"`, `"live"`, `"closd"` or `"route"` to choose the comparison. Each lane removes its
own horizontal root travel so that joint movement remains easy to compare. No joint pose is corrected.

ARDY batch trials, seed 0: **run · jump in place · somersault**. The later near-static poses are
part of those takes, not a paused player. These are separate from the interactive skipping excerpt above. The [full vocabulary archive](assets/motion_recordings/ardy-vocabulary.zip)
contains five seeds for each of 13 prompts.

```diffstatic_runner {data-lang=kotlin capture="box" execution="launch" video="assets/motion_recordings/ardy-field-review.mp4" duration="6" fps="15" open-outputs="true" wait-output="true" wait-output-kind="display" wait="0.2" timeout="240" w="700" h="80" embed=true name="motion-results-ardy" alt="Recorded human motion rendered by the Kotlin code shown here, inside Field."}
:::fold imports and recordings
import afield.animation.mocap.*

// Change to "live", "closd" or "route" to compare the other recordings.
val experiment = "ardy"
val sources = when (experiment) {
    "live" -> ["Skip" to "ardy-live-skip", "Run" to "ardy-live-run", "Recoil" to "ardy-live-recoil"]
    "closd" -> ["Baseline" to "closd-baseline-seed0", "Weak tether" to "closd-weak-seed0", "Strong tether" to "closd-capped-seed0"]
    "route" -> ["4 steps" to "ardy-path", "10 steps" to "ardy-path-10steps"]
    else -> ["Run" to "ardy-run-seed0", "Jump" to "ardy-jump-seed0", "Somersault" to "ardy-somersault-seed0"]
}
val takes = sources.map { (_, file) -> MotionRecording(("%docs/assets/motion_recordings/$file.f4motion").path) }
val clips = takes.map { it.clip() }
val logs = takes.map { it.events().toList() }
val duration = clips.minOf { it.endTime - it.startTime }
val started = AnimationClock.nowSeconds()
:::endfold

loop {
    val time = (AnimationClock.nowSeconds() - started) % duration
    val bodies = clips.flatMapIndexed { i, clip ->
        val frame = clip.sample(clip.startTime + time).frame
        val root = frame.executed.root
        // Translate each whole recording into a comparison lane. Never change joint poses.
        val offset = vec((i - (clips.size-1)/2.0)*2.8 - root[0], 0.0, -root[2])
        val actual = (frame.executed.bones(clip.skeleton) + offset).apply {
            color = vec(.94, .96, 1.0, 1.0); thickness = 3.0
        }
        val reference = frame.intended?.bones(clip.skeleton)?.let { (it + offset).apply {
            color = vec(.15, .7, .85, .6); thickness = 1.5
        } }
        val tether = logs[i].lastOrNull { it.optString("type")=="tetherPhysics" && it.getDouble("time")<=frame.time }
        val spring = fline {
            if (tether != null && frame.time in 2.0..4.0) {
                val a=tether.getJSONArray("anchor"); val p=tether.getJSONArray("point")
                moveTo(a.getDouble(0)+offset.x, a.getDouble(1), a.getDouble(2)+offset.z)
                lineTo(p.getDouble(0)+offset.x, p.getDouble(1), p.getDouble(2)+offset.z)
                val x=a.getDouble(0)+offset.x; val y=a.getDouble(1); val z=a.getDouble(2)+offset.z
                moveTo(x-.06,y,z); lineTo(x+.06,y,z)
                moveTo(x,y-.06,z); lineTo(x,y+.06,z)
                moveTo(x,y,z-.06); lineTo(x,y,z+.06)
            }
            color=vec(1.0,.3,.4,1.0); thickness=3.0
        }
        val force = fline {
            if (tether != null && frame.time in 2.0..4.0) {
                val p=tether.getJSONArray("point"); val f=tether.getJSONArray("force")
                val scale=minOf(.00015, .6/tether.getDouble("forceN").coerceAtLeast(1e-9))
                moveTo(p.getDouble(0)+offset.x, p.getDouble(1), p.getDouble(2)+offset.z)
                lineTo(p.getDouble(0)+offset.x+f.getDouble(0)*scale,
                    p.getDouble(1)+f.getDouble(1)*scale, p.getDouble(2)+offset.z+f.getDouble(2)*scale)
            }
            color=vec(1.0,.75,.2,1.0); thickness=4.0
        }
        listOfNotNull(actual, reference, spring, force)
    }
    three(channel="motion", height=460, width=900) {
        lines(bodies)
        camera(yaw=10.0, pitch=10.0, distance=2.8)
        title(sources.joinToString("                 ") { it.first })
        helpers(true)
    }
    markdown("status", "**${sources.joinToString(" · ") { it.first }}** · ${"%.2f".format(time)} s\n\n" +
        "Actual recorded poses; horizontal root travel removed separately in each lane. White: executed. Cyan: reference. " +
        when(experiment) {
            "closd" -> "Strong: 8000 N/m, 120 N s/m, 2500 N cap. Attach at 2 s; release at 4 s. Red: anchor; amber: force (scaled)."
            "route" -> "Both route trials slide; 10 steps is a comparison, not a fixed gait."
            "live" -> "Uninterrupted excerpts from the interactive session; original poses retained."
            else -> "The complete six-second batch takes include unsuccessful and nearly static output."
        })
    displayOpen("motion"); displayOpen("status")
    delay(16)
}
```

CLoSD, seed 0: **baseline · weak tether · strong tether (capped)**. The strong trial uses
8000 N/m stiffness, 120 N·s/m damping and a 2500 N cap. White is the executed body; cyan is the
reference. Red is the logged tether point and anchor between two and four seconds.
Run the same renderer with `experiment="closd"`:

```diffstatic_runner {data-lang=kotlin capture="box" execution="launch" video="assets/motion_recordings/closd-field-review.mp4" duration="6" fps="15" open-outputs="true" wait-output="true" wait-output-kind="display" wait="0.2" timeout="240" w="700" h="80" embed=true hidden=true name="motion-results-closd" alt="Recorded human motion rendered by the Kotlin code shown here, inside Field."}
:::fold imports and recordings
import afield.animation.mocap.*

// Change to "live", "closd" or "route" to compare the other recordings.
val experiment = "closd"
val sources = when (experiment) {
    "live" -> ["Skip" to "ardy-live-skip", "Run" to "ardy-live-run", "Recoil" to "ardy-live-recoil"]
    "closd" -> ["Baseline" to "closd-baseline-seed0", "Weak tether" to "closd-weak-seed0", "Strong tether" to "closd-capped-seed0"]
    "route" -> ["4 steps" to "ardy-path", "10 steps" to "ardy-path-10steps"]
    else -> ["Run" to "ardy-run-seed0", "Jump" to "ardy-jump-seed0", "Somersault" to "ardy-somersault-seed0"]
}
val takes = sources.map { (_, file) -> MotionRecording(("%docs/assets/motion_recordings/$file.f4motion").path) }
val clips = takes.map { it.clip() }
val logs = takes.map { it.events().toList() }
val duration = clips.minOf { it.endTime - it.startTime }
val started = AnimationClock.nowSeconds()
:::endfold

loop {
    val time = (AnimationClock.nowSeconds() - started) % duration
    val bodies = clips.flatMapIndexed { i, clip ->
        val frame = clip.sample(clip.startTime + time).frame
        val root = frame.executed.root
        // Translate each whole recording into a comparison lane. Never change joint poses.
        val offset = vec((i - (clips.size-1)/2.0)*2.8 - root[0], 0.0, -root[2])
        val actual = (frame.executed.bones(clip.skeleton) + offset).apply {
            color = vec(.94, .96, 1.0, 1.0); thickness = 3.0
        }
        val reference = frame.intended?.bones(clip.skeleton)?.let { (it + offset).apply {
            color = vec(.15, .7, .85, .6); thickness = 1.5
        } }
        val tether = logs[i].lastOrNull { it.optString("type")=="tetherPhysics" && it.getDouble("time")<=frame.time }
        val spring = fline {
            if (tether != null && frame.time in 2.0..4.0) {
                val a=tether.getJSONArray("anchor"); val p=tether.getJSONArray("point")
                moveTo(a.getDouble(0)+offset.x, a.getDouble(1), a.getDouble(2)+offset.z)
                lineTo(p.getDouble(0)+offset.x, p.getDouble(1), p.getDouble(2)+offset.z)
                val x=a.getDouble(0)+offset.x; val y=a.getDouble(1); val z=a.getDouble(2)+offset.z
                moveTo(x-.06,y,z); lineTo(x+.06,y,z)
                moveTo(x,y-.06,z); lineTo(x,y+.06,z)
                moveTo(x,y,z-.06); lineTo(x,y,z+.06)
            }
            color=vec(1.0,.3,.4,1.0); thickness=3.0
        }
        val force = fline {
            if (tether != null && frame.time in 2.0..4.0) {
                val p=tether.getJSONArray("point"); val f=tether.getJSONArray("force")
                val scale=minOf(.00015, .6/tether.getDouble("forceN").coerceAtLeast(1e-9))
                moveTo(p.getDouble(0)+offset.x, p.getDouble(1), p.getDouble(2)+offset.z)
                lineTo(p.getDouble(0)+offset.x+f.getDouble(0)*scale,
                    p.getDouble(1)+f.getDouble(1)*scale, p.getDouble(2)+offset.z+f.getDouble(2)*scale)
            }
            color=vec(1.0,.75,.2,1.0); thickness=4.0
        }
        listOfNotNull(actual, reference, spring, force)
    }
    three(channel="motion", height=460, width=900) {
        lines(bodies)
        camera(yaw=10.0, pitch=10.0, distance=2.8)
        title(sources.joinToString("                 ") { it.first })
        helpers(true)
    }
    markdown("status", "**${sources.joinToString(" · ") { it.first }}** · ${"%.2f".format(time)} s\n\n" +
        "Actual recorded poses; horizontal root travel removed separately in each lane. White: executed. Cyan: reference. " +
        when(experiment) {
            "closd" -> "Strong: 8000 N/m, 120 N s/m, 2500 N cap. Attach at 2 s; release at 4 s. Red: anchor; amber: force (scaled)."
            "route" -> "Both route trials slide; 10 steps is a comparison, not a fixed gait."
            "live" -> "Uninterrupted excerpts from the interactive session; original poses retained."
            else -> "The complete six-second batch takes include unsuccessful and nearly static output."
        })
    displayOpen("motion"); displayOpen("status")
    delay(16)
}
```

ARDY route, seed 0: **four · ten denoising steps**. Both are raw six-second trials on the same timed
straight path. More steps improve articulation in this repeat; neither is a convincing walking demo.
Run the renderer with `experiment="route"`:

```diffstatic_runner {data-lang=kotlin capture="box" execution="launch" video="assets/motion_recordings/ardy-route-comparison.mp4" duration="6" fps="15" open-outputs="true" wait-output="true" wait-output-kind="display" wait="0.2" timeout="240" w="700" h="80" embed=true hidden=true name="motion-results-route" alt="Recorded human motion rendered by the Kotlin code shown here, inside Field."}
:::fold imports and recordings
import afield.animation.mocap.*

// Change to "live", "closd" or "route" to compare the other recordings.
val experiment = "route"
val sources = when (experiment) {
    "live" -> ["Skip" to "ardy-live-skip", "Run" to "ardy-live-run", "Recoil" to "ardy-live-recoil"]
    "closd" -> ["Baseline" to "closd-baseline-seed0", "Weak tether" to "closd-weak-seed0", "Strong tether" to "closd-capped-seed0"]
    "route" -> ["4 steps" to "ardy-path", "10 steps" to "ardy-path-10steps"]
    else -> ["Run" to "ardy-run-seed0", "Jump" to "ardy-jump-seed0", "Somersault" to "ardy-somersault-seed0"]
}
val takes = sources.map { (_, file) -> MotionRecording(("%docs/assets/motion_recordings/$file.f4motion").path) }
val clips = takes.map { it.clip() }
val logs = takes.map { it.events().toList() }
val duration = clips.minOf { it.endTime - it.startTime }
val started = AnimationClock.nowSeconds()
:::endfold

loop {
    val time = (AnimationClock.nowSeconds() - started) % duration
    val bodies = clips.flatMapIndexed { i, clip ->
        val frame = clip.sample(clip.startTime + time).frame
        val root = frame.executed.root
        // Translate each whole recording into a comparison lane. Never change joint poses.
        val offset = vec((i - (clips.size-1)/2.0)*2.8 - root[0], 0.0, -root[2])
        val actual = (frame.executed.bones(clip.skeleton) + offset).apply {
            color = vec(.94, .96, 1.0, 1.0); thickness = 3.0
        }
        val reference = frame.intended?.bones(clip.skeleton)?.let { (it + offset).apply {
            color = vec(.15, .7, .85, .6); thickness = 1.5
        } }
        val tether = logs[i].lastOrNull { it.optString("type")=="tetherPhysics" && it.getDouble("time")<=frame.time }
        val spring = fline {
            if (tether != null && frame.time in 2.0..4.0) {
                val a=tether.getJSONArray("anchor"); val p=tether.getJSONArray("point")
                moveTo(a.getDouble(0)+offset.x, a.getDouble(1), a.getDouble(2)+offset.z)
                lineTo(p.getDouble(0)+offset.x, p.getDouble(1), p.getDouble(2)+offset.z)
                val x=a.getDouble(0)+offset.x; val y=a.getDouble(1); val z=a.getDouble(2)+offset.z
                moveTo(x-.06,y,z); lineTo(x+.06,y,z)
                moveTo(x,y-.06,z); lineTo(x,y+.06,z)
                moveTo(x,y,z-.06); lineTo(x,y,z+.06)
            }
            color=vec(1.0,.3,.4,1.0); thickness=3.0
        }
        val force = fline {
            if (tether != null && frame.time in 2.0..4.0) {
                val p=tether.getJSONArray("point"); val f=tether.getJSONArray("force")
                val scale=minOf(.00015, .6/tether.getDouble("forceN").coerceAtLeast(1e-9))
                moveTo(p.getDouble(0)+offset.x, p.getDouble(1), p.getDouble(2)+offset.z)
                lineTo(p.getDouble(0)+offset.x+f.getDouble(0)*scale,
                    p.getDouble(1)+f.getDouble(1)*scale, p.getDouble(2)+offset.z+f.getDouble(2)*scale)
            }
            color=vec(1.0,.75,.2,1.0); thickness=4.0
        }
        listOfNotNull(actual, reference, spring, force)
    }
    three(channel="motion", height=460, width=900) {
        lines(bodies)
        camera(yaw=10.0, pitch=10.0, distance=2.8)
        title(sources.joinToString("                 ") { it.first })
        helpers(true)
    }
    markdown("status", "**${sources.joinToString(" · ") { it.first }}** · ${"%.2f".format(time)} s\n\n" +
        "Actual recorded poses; horizontal root travel removed separately in each lane. White: executed. Cyan: reference. " +
        when(experiment) {
            "closd" -> "Strong: 8000 N/m, 120 N s/m, 2500 N cap. Attach at 2 s; release at 4 s. Red: anchor; amber: force (scaled)."
            "route" -> "Both route trials slide; 10 steps is a comparison, not a fixed gait."
            "live" -> "Uninterrupted excerpts from the interactive session; original poses retained."
            else -> "The complete six-second batch takes include unsuccessful and nearly static output."
        })
    displayOpen("motion"); displayOpen("status")
    delay(16)
}
```

[Movie provenance](assets/motion_recordings/field-movies.provenance.json) lists the source recordings.
For measurements, environment pins, reproducible trial commands and remaining experiments, see the
[verification report](https://github.com/OpenEndedGroup/Field4/blob/main/optional/motion/VERIFICATION.md).
Both backends have run on CachyOS with an RTX 4090; they remain experimental. A simulated fall and
reset are not evidence of recovery. Recorded poses replay directly; re-simulating the same seed can
diverge. The [recording format](https://github.com/OpenEndedGroup/Field4/blob/main/optional/motion/protocol.md)
keeps configuration, skeletons, commands, poses and reset events together.

See also [frames and animation](animation_time_model.md), [FLine](fline_examples.md), and
[skinned characters](chain_skinned_volume.md).
