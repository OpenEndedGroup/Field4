# Field Document: motion_recorded_results

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. Recorded motion — ARDY and CLoSD

<!-- field-box id="motion_recorded_results" index="0" -->

```edn [field-box id="motion_recorded_results" role="properties"]
{"id" "motion_recorded_results" "index" 0 "name" "Recorded motion — ARDY and CLoSD" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Recorded motion — ARDY and CLoSD" "_tombstone" false "frame" #afield/rect [80.0 80.0 600.0 110.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "motion_recorded_results_code_.kt" "lines" 71 "characters" 4063 "sha256" "1b89847eb85ce7f6f0855a0e99e9267770811a83220ae3c304e2d21aa1904a92"}]}
```

```kotlin [field-property box="motion_recorded_results" name="code" path="motion_recorded_results_code_.kt"]
import afield.animation.mocap.*

// Change to "live", "closd" or "route" to compare the other recordings.
val experiment = "ardy"
val sources = when (experiment) {
    "live" -> listOf("Skip" to "ardy-live-skip", "Run" to "ardy-live-run", "Recoil" to "ardy-live-recoil")
    "closd" -> listOf("Baseline" to "closd-baseline-seed0", "Weak tether" to "closd-weak-seed0", "Strong tether" to "closd-capped-seed0")
    "route" -> listOf("4 steps" to "ardy-path", "10 steps" to "ardy-path-10steps")
    else -> listOf("Run" to "ardy-run-seed0", "Jump" to "ardy-jump-seed0", "Somersault" to "ardy-somersault-seed0")
}
val takes = sources.map { (_, file) -> MotionRecording(("%docs/assets/motion_recordings/$file.f4motion").path) }
val clips = takes.map { it.clip() }
val logs = takes.map { it.events().toList() }
val duration = clips.minOf { it.endTime - it.startTime }
val started = AnimationClock.nowSeconds()

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
    markdown("status", "**${sources.joinToString(" · ") { it.first }}** · ${time.f2} s\n\n" +
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

