# Field Document: slinky_escalator

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "watchdog" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. slinky on an up escalator

<!-- field-box id="slinky_walk" index="0" -->

```edn [field-box id="slinky_walk" role="properties"]
{"id" "slinky_walk" "index" 0 "name" "slinky on an up escalator" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "slinky on an up escalator" "_tombstone" false "frame" #afield/rect [80.0 80.0 360.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "slinky_walk_code_.kt" "lines" 53 "characters" 1964 "sha256" "3c1f7bcc7f6c56fd7716aa95026d8330c53527c7d19a46be2ce32c4621d9457f"}]}
```

```kotlin [field-property box="slinky_walk" name="code" path="slinky_walk_code_.kt"]
import afield.simulation.physics.*

// The three things worth turning: the escalator's speed, as seconds per step; the step height;
// and the spring constant between neighbouring coils. Lengths are in coil diameters.
var stepPeriod = 0.575f
var rise = 1.5f
var stiffness = 16000f

fun start() = SlinkyOnStairs(stepPeriod = stepPeriod, rise = rise, stiffness = stiffness)
var scene = start()

controlGroup("escalator") {
    slider("seconds per step", min = 0.2, max = 1.5, step = 0.005, initialValue = 0.575) {
        stepPeriod = it.toFloat()
        scene.stepPeriod = stepPeriod
    }
    slider("step rise", min = 0.8, max = 2.5, step = 0.05, initialValue = 1.5) {
        rise = it.toFloat()
        scene.stairs.rise = rise
    }
    slider("spring constant", min = 4000.0, max = 30000.0, step = 500.0, initialValue = 16000.0) {
        stiffness = it.toFloat()
        scene.slinky.stiffness = stiffness
    }
    button("start again") { down -> if (down) scene = start() }
}

// The loop can come round far more often than 60 times a second; the physics keeps to the clock.
val clock = System.nanoTime()
var simulated = 0.0
loop {
    val now = (System.nanoTime() - clock) / 1e9
    var catchUp = 0
    while (simulated < now && catchUp++ < 4) {
        scene.step()
        simulated += 1.0 / 60.0
    }
    simulated = maxOf(simulated, now - 0.1)

    // Follow the slinky, so a walk that keeps pace stays put and one that loses it drifts off.
    val c = scene.slinky.centroid()
    val behind = scene.stairsTravelled - scene.stepsDescended
    showPlane(
        scene.lines(),
        window = Rect(c.x - 5.0, c.y - 4.0, 10.0, 7.0),
        width = 720,
        height = 504,
        title = "slinky on an up escalator · ${scene.stepPeriod.f3} s per step",
        caption =
            "t = ${scene.time.f1} s · walked ${scene.stepsDescended.f1} steps · " +
                "escalator ${scene.stairsTravelled.f1} · behind by ${behind.f1}",
    )
}
```

