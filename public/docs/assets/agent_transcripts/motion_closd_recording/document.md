# Field Document: motion_closd_recording

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. CLoSD — a recorded foot tether

<!-- field-box id="motion_closd_recording" index="0" -->

```edn [field-box id="motion_closd_recording" role="properties"]
{"id" "motion_closd_recording" "index" 0 "name" "CLoSD — a recorded foot tether" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "CLoSD — a recorded foot tether" "_tombstone" false "frame" #afield/rect [80.0 80.0 600.0 110.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "motion_closd_recording_code_.kt" "lines" 43 "characters" 2239 "sha256" "e2b9b00ef1b53c694e955a7139808108e468299dc0c3fe5621387caac7b584e8"}]}
```

```kotlin [field-property box="motion_closd_recording" name="code" path="motion_closd_recording_code_.kt"]
import afield.animation.mocap.MotionRecording
import afield.animation.mocap.showMotion
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

val recording = MotionRecording("%docs/assets/motion_recordings/closd-foot-tether.f4motion".path)
val clip = recording.clip()
val events = recording.events().toList()
val play = AtomicBoolean(false)
val follow = AtomicBoolean(true)
val followHalfLife = AtomicReference(.4)
val scrub = AtomicReference(3.4)
val showReference = AtomicBoolean(true)
controlGroup {
    checkbox("Follow body", initialValue=true) { follow.set(it) }
    slider("Follow half-life seconds", .05, 2.0, initialValue=.4) { followHalfLife.set(it) }
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
    showMotion(snapshot.frame, clip.skeleton, reference=showReference.get(), overlays=overlays, follow=follow.get(), followHalfLife=followHalfLife.get())
    markdown("status", "**CLoSD · recorded GPU motion**\n\n" +
        "White: executed body. Cyan: planner reference positions. Red: physical foot tether, attached at 2 s and released at 4 s. " +
        "Time ${"%.2f".format(seconds)} s · episode ${snapshot.frame.episode}. Orbit the view with the mouse.")
    displayOpen("motion")
    displayOpen("status")
    delay(16) // Yield without flooding the UI transport.

}
```

