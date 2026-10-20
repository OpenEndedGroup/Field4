# Field Document: motion_ardy_demo_replay

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. ARDY — the interactive skipping take

<!-- field-box id="motion_ardy_demo_replay" index="0" -->

```edn [field-box id="motion_ardy_demo_replay" role="properties"]
{"id" "motion_ardy_demo_replay" "index" 0 "name" "ARDY — the interactive skipping take" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "ARDY — the interactive skipping take" "_tombstone" false "frame" #afield/rect [80.0 80.0 600.0 110.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "motion_ardy_demo_replay_code_.kt" "lines" 27 "characters" 1262 "sha256" "20d1f628bcd9ca9939e7dc00a14eea0dc7125e7ce903b481c1d85aaed9dcd69a"}]}
```

```kotlin [field-property box="motion_ardy_demo_replay" name="code" path="motion_ardy_demo_replay_code_.kt"]
import afield.animation.mocap.*
val clip = MotionRecording("%docs/assets/motion_recordings/ardy-live-skip.f4motion".path).clip()
val started = AnimationClock.nowSeconds()

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

