# Field Document: motion_resistance_replay

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. CLoSD — resistance and release

<!-- field-box id="motion_resistance_replay" index="0" -->

```edn [field-box id="motion_resistance_replay" role="properties"]
{"id" "motion_resistance_replay" "index" 0 "name" "CLoSD — resistance and release" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "CLoSD — resistance and release" "_tombstone" false "frame" #afield/rect [80.0 80.0 600.0 110.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "motion_resistance_replay_code_.kt" "lines" 34 "characters" 1449 "sha256" "410e023810afd3f02d27ab53d8caf6f17e7260543c670f3fd43b3299c0b4b9df"}]}
```

```kotlin [field-property box="motion_resistance_replay" name="code" path="motion_resistance_replay_code_.kt"]
import afield.animation.mocap.*
import kotlin.time.Duration.Companion.seconds

val take = MotionRecording("%docs/assets/motion_recordings/closd-resistance.f4motion".path)
frames {
    var loop = 0
    while (true) {
        MotionSession.replay(take).use { dancer ->
            playMotion(dancer, 9.seconds) { snapshot ->
                val t = dancer.telemetry.tether("tether")
                showMotion(
                    snapshot.frame,
                    dancer.skeleton,
                    follow = true,
                    followHalfLife = .4,
                    followEpoch = "${loop}:${snapshot.frame.episode}",
                    overlays = t?.overlays() ?: emptyList(),
                )
                val action =
                    if (t?.active == true) "TETHERED · ${(t.stretchMetres*100).f1} cm · ${t.forceN.f0} N"
                    else if (snapshot.frame.time >= 4.0) "RELEASED" else "WALKING"
                markdown(
                    "status",
                    "**$action** · ${snapshot.frame.time.f2} s\n\n" +
                        "Recorded CLoSD physics. White: executed · cyan: reference · red: anchor · amber: submitted force (scaled). " +
                        "8000 N/m, 120 N s/m, 2500 N cap. The prompt stays unchanged. Follow camera half-life: 0.4 s.",
                )
                displayOpen("motion")
                displayOpen("status")
            }
        }
        loop++
    }
}
```

