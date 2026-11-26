# Field Document: slinky_escalator

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "watchdog" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. a slinky walking down stairs

<!-- field-box id="slinky_walk" index="0" -->

```edn [field-box id="slinky_walk" role="properties"]
{"id" "slinky_walk" "index" 0 "name" "a slinky walking down stairs" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "a slinky walking down stairs" "_tombstone" false "frame" #afield/rect [80.0 80.0 360.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "slinky_walk_code_.kt" "lines" 59 "characters" 2134 "sha256" "ea37d8b9a37e87122c1eb6e9edda8d3d1df819b92afd5340d3612741dfa7071b"}]}
```

```kotlin [field-property box="slinky_walk" name="code" path="slinky_walk_code_.kt"]
import afield.simulation.physics.*

// Lengths are in coil diameters. The slow-motion factor changes only how fast the picture runs.
var run = 1.5f
var rise = 1.1f
var stiffness = 32000f
var stepPeriod = 0f
var slowMotion = 5

fun start() = SlinkyOnStairs(run = run, rise = rise, stiffness = stiffness, stepPeriod = stepPeriod)
var scene = start()

controlGroup("slinky") {
    slider("tread depth", min = 1.2, max = 2.6, step = 0.05, initialValue = 1.5) {
        run = it.toFloat()
        scene = start()
    }
    slider("step rise", min = 0.6, max = 3.0, step = 0.05, initialValue = 1.1) {
        rise = it.toFloat()
        scene = start()
    }
    slider("spring constant", min = 8000.0, max = 48000.0, step = 1000.0, initialValue = 32000.0) {
        stiffness = it.toFloat()
        scene = start()
    }
    // The escalator: 0 is still stairs; about 0.09 matches the default walk.
    slider("seconds per step", min = 0.0, max = 0.3, step = 0.005, initialValue = 0.0) {
        stepPeriod = it.toFloat()
        scene = start()
    }
    slider("slow motion", min = 1.0, max = 10.0, step = 1.0, initialValue = 5.0) { slowMotion = it.toInt() }
    button("start again") { down -> if (down) scene = start() }
}

// One frame of the picture every sixtieth of a second, each a slowMotion-th of a sixtieth of the walk.
val clock = AnimationClock.nowSeconds()
var shown = 0.0
loop {
    val now = AnimationClock.nowSeconds() - clock
    var catchUp = 0
    while (shown < now && catchUp++ < 4) {
        scene.step(1f / 60f / slowMotion)
        shown += 1.0 / 60.0
    }
    shown = maxOf(shown, now - 0.1)

    val c = scene.slinky.centroid()
    val carried = scene.stairsTravelled - scene.stepsDescended
    showPlane(
        scene.lines(),
        window = Rect(c.x - 5.0, c.y - 3.5, 10.0, 7.0),
        width = 720,
        height = 504,
        title = "a slinky walking down stairs · 1/$slowMotion speed",
        caption =
            "t = ${scene.time.f2} s · ${scene.flips} flips · ${scene.stepsDescended.f1} steps down" +
                (if (scene.stepPeriod > 0f) " · carried up ${carried.f1}" else ""),
    )
}
```

