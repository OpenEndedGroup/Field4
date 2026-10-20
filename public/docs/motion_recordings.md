# Motion recordings

Scrub recorded movement, draw its bones with FLine, and inspect interventions without loading a
neural network. Motion uses metres and seconds, with Field's Y-up scene coordinates. Joint names
come from each recording's skeleton; physical bodies have a separate explicit mapping.

Open [recorded CLoSD foot tether/release](agent_transcripts.md#motion_closd_recording) for a real six-second GPU simulation. It needs no installed model to replay. White bones show the executed body; cyan shows the planner reference; red marks the physical point tether. The foot remains free to rotate. A capped spring does not guarantee an exact pin.

Once the optional environment is installed, open [live physical interference](agent_transcripts.md#motion_closd_live). Change intention, attach a named body, move its anchor, tune stiffness/damping/cap, release, apply a torso impulse, or reset. Stop the box to release its worker and simulator. Run Field with `FIELD_MOTION_HOME` pointing to the separate development directory. The scene records each run to a fresh `motion-recordings` directory in the document workspace.

Try three ARDY starting points after installing its optional environment: [timed paths](agent_transcripts.md#motion_ardy_path), [changing intentions](agent_transcripts.md#motion_ardy_intentions), and [kinematic foot restriction](agent_transcripts.md#motion_ardy_foot). Routes use Field's existing FLine arc-length cursor. Geometry, timing envelope and heading are independent controls. ARDY restrictions are model conditioning; they do not apply physical forces.

The recorded CLoSD scene below runs locally without either network:

```diffstatic_runner {data-lang=kotlin screenshot="assets/motion_recordings/closd-replay.png" capture="box" execution="launch" open-outputs="true" wait-output="true" wait-output-kind="display" wait="2" timeout="180" w="700" h="100" embed=true name="motion-closd-replay" alt="A real CLoSD GPU recording in Field's 3D viewer: executed white skeleton, cyan reference and a red foot tether, with time and reference controls."}
:::fold imports
import afield.animation.mocap.MotionRecording
import afield.animation.mocap.showMotion
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
:::endfold

val recording = MotionRecording("%docs/assets/motion_recordings/closd-foot-tether.f4motion".path)
val clip = recording.clip()
val events = recording.events().toList()
val play = AtomicBoolean(false)
val scrub = AtomicReference(3.4)
val showReference = AtomicBoolean(true)
controlGroup {
    slider("Recorded seconds", clip.startTime, clip.endTime, initialValue=3.4) { scrub.set(it) }
    checkbox("Play", initialValue=false) { play.set(it) }
    checkbox("Reference pose", initialValue=true) { showReference.set(it) }
}
val started = AnimationClock.nowSeconds()

loop {
    val seconds = if (play.get()) clip.startTime + (AnimationClock.nowSeconds() - started) % (clip.endTime - clip.startTime) else scrub.get()
    val snapshot = clip.sample(seconds)
    val tether = events.lastOrNull { it.optString("type") == "tetherPhysics" && it.getDouble("time") <= seconds }
    val overlays = if (tether != null && seconds < 4.0) listOf(fline {
        val a = tether.getJSONArray("anchor")
        val p = tether.getJSONArray("point")
        moveTo(a.getDouble(0), a.getDouble(1), a.getDouble(2))
        lineTo(p.getDouble(0), p.getDouble(1), p.getDouble(2))
        color = vec(1.0, 0.3, 0.4, 1.0)
        thickness = 3.0
    }) else emptyList()
    showMotion(snapshot.frame, clip.skeleton, reference=showReference.get(), overlays=overlays)
    markdown("status", "**CLoSD · recorded GPU motion**\n\n" +
        "White: executed body. Cyan: planner reference positions. Red: physical foot tether, attached at 2 s and released at 4 s. " +
        "Time ${"%.2f".format(seconds)} s · episode ${snapshot.frame.episode}. Orbit the view with the mouse.")
    displayOpen("motion")
    displayOpen("status")
    delay(16) // Yield without flooding the UI transport.

}

```

Open the [motion recording probe](agent_transcripts.md#motion_recording_probe) to try the controls.
It contains a one-metre, two-joint coordinate probe with a reset at two seconds. It is a diagnostic
recording, not ARDY output or a CLoSD simulation. Scrub across the reset: the previous pose holds
until the new episode begins.

The following box plays that probe and exposes its recorded session time:

```diffstatic_runner {data-lang=kotlin screenshot="assets/motion_recordings/probe.png" capture="box" execution="launch" open-outputs="true" wait-output="true" wait-output-kind="display" wait="1" timeout="180" w="700" h="80" embed=true name="motion-recording-probe" alt="A two-joint coordinate probe drawn in Field, with controls for session time and playback and a label distinguishing it from neural motion."}
:::fold imports
import afield.animation.mocap.MotionExamples
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
:::endfold

// A known coordinate probe: this clip contains no learned or simulated motion.
// Replace it with MotionRecording(Path.of("your.f4motion")).clip() to inspect a recording.
val clip = MotionExamples.contractClip()
val play = AtomicBoolean(false)
val scrub = AtomicReference(1.35)
controlGroup {
    slider("Session time (seconds)", 0.0, 4.0, initialValue=1.35) { scrub.set(it) }
    checkbox("Play recording", initialValue=false) { play.set(it) }
}
val started = AnimationClock.nowSeconds()

loop {
    val time = if (play.get()) (AnimationClock.nowSeconds() - started) % 4.0 else scrub.get()
    val snapshot = clip.sample(time)
    val pose = snapshot.frame.executed
    val drawing = fline {
        clip.skeleton.parents.forEachIndexed { i, parent ->
            if (parent >= 0) {
                val a = pose.position(parent)
                val b = pose.position(i)
                moveTo(300.0 + 180 * a.x, 250.0 - 180 * a.y)
                lineTo(300.0 + 180 * b.x, 250.0 - 180 * b.y)
            }
        }
        color = if (snapshot.frame.episode == 0L) vec(0.25, 0.7, 1.0, 1.0) else vec(1.0, 0.5, 0.2, 1.0)
        thickness = 5.0
    }
    val points = fline {
        repeat(clip.skeleton.size) { i ->
            val p = pose.position(i)
            circle(300.0 + 180 * p.x, 250.0 - 180 * p.y, 7.0)
        }
        color = vec(0.85, 0.9, 1.0, 1.0)
        filled = true
    }
    val border = fline { rect(0.0, 0.0, 600.0, 300.0); color = vec(0.2, 0.25, 0.3, 1.0) }
    lines(border, drawing, points, w=600.0, h=300.0, channel="motion")
    markdown("status", "**Coordinate probe** · no network inference\n\n" +
        "Time ${"%.2f".format(time)} s · episode ${snapshot.frame.episode} · " +
        if (snapshot.held) "holding before reset" else "recorded pose")
    displayOpen("motion")
    displayOpen("status")
    delay(16)
}
```

To use your own recording, replace `MotionExamples.contractClip()` with
`MotionRecording(Path.of("performance.f4motion")).clip()` and import those two classes.
`clip()` explicitly loads all poses; `MotionRecording.frames()` streams a long recording.
`clip.sample(seconds)` returns the pose, whether it is holding, and its lag. It uses shortest-arc
quaternion interpolation and never interpolates across an episode boundary. Playback beyond the
last frame holds the last pose.

For 3D drawing, call `snapshot.frame.executed.bones(clip.skeleton)`. The returned FLine uses your
existing renderer and camera. An optional `intended` pose is kept separate from the executed body,
so a physical controller's reference can be drawn alongside the actual result.

## ARDY and CLoSD

ARDY generates kinematic movement from text and constraints. CLoSD couples a diffusion planner to
a simulated body. They need separate Python environments; neither is required for Field startup
or motion replay. The CLoSD process adapter has run on CachyOS/RTX 4090 with executed and reference streaming, physical tethers and one-step impulses. ARDY upstream generation, browser playback and the Kotlin live process adapter have also run on the same GPU. Both backends remain experimental.

On Linux/NVIDIA, `./field.sh --setup` lists the experimental ARDY and CLoSD extras. Follow the
[optional motion setup guide](https://github.com/OpenEndedGroup/Field4/blob/main/optional/motion/README.md)
for pinned source revisions and prerequisite checks. ARDY needs
[Meta-Llama-3-8B-Instruct access](https://huggingface.co/meta-llama/Meta-Llama-3-8B-Instruct);
CLoSD needs the separate [Isaac Gym Preview SDK](https://developer.nvidia.com/isaac-gym).
Selecting an extra does not accept licenses, download model weights or certify inference.

The shared `MotionSession` facade keeps backend work off the rendering thread. It has typed
prompt, root-plan and joint-target operations, and distinct physical attachment/impulse operations.
Unsupported operations report the backend's capabilities. Replay supports controlled `advance`. CLoSD supports timed prompts, physical attachments, impulses, reset and controlled stepping; it rejects kinematic targets and root paths. Live snapshots and
command queues are bounded, and rendering holds an available pose when new data is late.

Recordings preserve the skeleton, configuration, command timing, poses, contacts with provenance,
diagnostics and episode changes in a
[portable JSON/binary format](https://github.com/OpenEndedGroup/Field4/blob/main/optional/motion/protocol.md).
A recorded pose replays without asking a GPU to repeat a random seed. Re-simulation is separate.
The [verification report](https://github.com/OpenEndedGroup/Field4/blob/main/optional/motion/VERIFICATION.md)
reports real simulator measurements alongside the remaining network and artistic experiments. Three matched seeds show variation; a breakable tether does not break if its threshold is never reached. Halving the physics timestep changes the stronger-tether result substantially, so numerical convergence and arbitrary recovery are unproven.

## Recorded results

The [65-clip ARDY vocabulary archive](assets/motion_recordings/ardy-vocabulary.zip) contains five seeds for each of 13 prompts, plus numerical summaries and an unfilled artistic score sheet. Prompt acceptance is not a performance score. Six-second walking displacement ranges from about 5 cm to 2.38 m; running travels about 4.96–6.84 m. Ground-roll samples include joint heights as low as 12 cm below the nominal floor. Some four-step locomotion sequences become nearly static in their later body poses while the root keeps moving; the unmodified upstream generator reproduces this degradation.

<video controls preload="metadata" style="max-width:100%;height:auto" src="assets/motion_recordings/ardy-vocabulary-review.mp4"></video>

The review above shows seed-0 running, jumping in place and somersault conditioning. It is an offline rendering of actual recorded poses with a separate root-following camera per panel, not a Field UI capture. [Rendering provenance](assets/motion_recordings/ardy-vocabulary-review.provenance.json) identifies every source clip.

A timed ARDY route produced about 3.7 m of travel over six seconds. Repeating left-foot position targets while keeping that route gives a mean raw anchor residual of 0.189 m and maximum 0.484 m; adding orientation gives 0.200 m mean and 0.647 m maximum. No postprocessing or joint overwrite was used. The pinned representation requires root-2D conditioning alongside global joint positions: this experiment is a foot restriction **with an explicit root plan**. [Route recording](assets/motion_recordings/ardy-path.f4motion/manifest.json) · [restriction recording](assets/motion_recordings/ardy-foot.f4motion/manifest.json).

CLoSD baseline, weak and stronger capped tether trials show constraint-dependent locomotion. A breakable tether broke in two of three matched seeds; one never reached its threshold. Stronger stiffness changes the result more clearly, but halving the physics timestep changes that trajectory substantially. These trials establish physical intervention and stock executed-prefix feedback, not arbitrary escape, numerical convergence or trained recovery.

<video controls preload="metadata" style="max-width:100%;height:auto" src="assets/motion_recordings/closd-comparison.mp4"></video>

This is also an offline pose review, showing baseline/weak/capped seed-0 trials with root-following cameras. [Rendering provenance](assets/motion_recordings/closd-comparison.provenance.json) and the [verification report](https://github.com/OpenEndedGroup/Field4/blob/main/optional/motion/VERIFICATION.md) distinguish measured results from outstanding experiments.

See also [frames and the animation loop](animation_time_model.md),
[FLine examples](fline_examples.md), and [skinned characters](chain_skinned_volume.md).
