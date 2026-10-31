# Field Document: motion_tether_score

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. CLoSD — a movement score

<!-- field-box id="motion_tether_score" index="0" -->

```edn [field-box id="motion_tether_score" role="properties"]
{"id" "motion_tether_score" "index" 0 "name" "CLoSD — a movement score" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "CLoSD — a movement score" "_tombstone" false "frame" #afield/rect [80.0 80.0 600.0 110.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "motion_tether_score_code_.kt" "lines" 36 "characters" 1209 "sha256" "3b1d83f004a954b86b1f427922943c049a3597250f5b8a14b07bf01c18f9ff91"}]}
```

```kotlin [field-property box="motion_tether_score" name="code" path="motion_tether_score_code_.kt"]
import afield.animation.mocap.*
import kotlin.time.Duration.Companion.seconds

frames {
    CLoSD.openAsync().awaitMotion().use { dancer ->
        dancer.prompt("A person walks forward.")
        fun draw(snapshot: MotionSnapshot) {
            showMotion(
                snapshot.frame,
                dancer.skeleton,
                follow = true,
                overlays = dancer.telemetry.tethers().flatMap { it.overlays() },
            )
            val foot = dancer.telemetry.tether("left-foot")
            markdown(
                "status",
                when {
                    foot?.active == true -> "**TETHERED** · ${"%.0f".format(foot.forceN)} N"
                    foot != null -> "**RELEASED**"
                    else -> "**WALKING**"
                },
            )
            displayOpen("motion")
            displayOpen("status")
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

