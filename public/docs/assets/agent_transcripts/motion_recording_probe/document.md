# Field Document: motion_recording_probe

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. Motion — coordinate probe

<!-- field-box id="motion_recording_probe" index="0" -->

```edn [field-box id="motion_recording_probe" role="properties"]
{"id" "motion_recording_probe" "index" 0 "name" "Motion — coordinate probe" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Motion — coordinate probe" "_tombstone" false "frame" #afield/rect [80.0 80.0 600.0 110.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "motion_recording_probe_code_.kt" "lines" 49 "characters" 2033 "sha256" "c89b685563724a344b9bea7744e3a4b7d6a8c2b5a8a9e86c64932aa25dc0ea6f"}]}
```

```kotlin [field-property box="motion_recording_probe" name="code" path="motion_recording_probe_code_.kt"]
import afield.animation.mocap.MotionExamples
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
        "Time ${time.f2} s · episode ${snapshot.frame.episode} · " +
        if (snapshot.held) "holding before reset" else "recorded pose")
    displayOpen("motion")
    displayOpen("status")
    delay(16) // Yield without flooding the UI transport.

}
```

