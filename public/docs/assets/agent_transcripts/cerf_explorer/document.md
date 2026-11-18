# Field Document: cerf_explorer

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 1 - Cerf diagram of a live simulation

<!-- field-box id="cerf_live" index="0" -->

```edn [field-box id="cerf_live" role="properties"]
{"id" "cerf_live" "index" 0 "name" "1 - Cerf diagram of a live simulation" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - Cerf diagram of a live simulation" "_tombstone" false "frame" #afield/rect [60.0 60.0 420.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "cerf_live_code_.kt" "lines" 26 "characters" 1196 "sha256" "7bacd1a71b44ba299d722168bea22c96cd94fe091c600bbc7df5496128aef39a"}]}
```

```kotlin [field-property box="cerf_live" name="code" path="cerf_live_code_.kt"]
import afield.geometry.topology.morsesmale.*
import afield.geometry.topology.tracking.*

/*
Launch this one (Alt-R). A Gray–Scott run in its wandering-worms regime, seeded with three squares.
Each frame advances it 20 steps, adds the frame's critical points to the Cerf diagram, and draws
the diagram so far beside the field, with births (orange) and cancellations (violet) on both.
*/
var state = grayScottSeed(96, 96, patches = 3, patchSize = 10, seed = 7)
val cerf = CerfDiagram()

loop {
    state = state.stepTimes(GRAY_SCOTT_WORMS, 20)
    val v = state.v.gaussianBlur(1.0)
    cerf.add(frameNumber.toDouble(), MorseSmale.compute(v, persistence = 0.05), minPersistence = 0.05)
    val now = frameNumber.toDouble()
    showImgGrid(
        "births ${cerf.events(CerfEventType.BIRTH).size}, cancellations ${cerf.events(CerfEventType.DEATH).size}" to
            Img.filled(320, 220, 1, 0.08f)
                .drawFLines(cerf.toFLines(320.0, 220.0, cursor = now, showSwaps = false), scale = 2.0),
        "frame $frameNumber" to
            v.colormap("magma", 0f, 0.4f).drawFLines(cerf.fieldMarkers(now, 5.0, window = 2.0), scale = 4.5),
        columns = 2,
        cellSide = 440,
    )
}
```

## 2. 2 - Scrubbing a run whose topology churns

<!-- field-box id="cerf_churn" index="1" -->

```edn [field-box id="cerf_churn" role="properties"]
{"id" "cerf_churn" "index" 1 "name" "2 - Scrubbing a run whose topology churns" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - Scrubbing a run whose topology churns" "_tombstone" false "frame" #afield/rect [560.0 60.0 420.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "cerf_churn_code_.kt" "lines" 37 "characters" 1461 "sha256" "16cc9f60109b2f0e94f6f0930d81643d515c6167293814e2667759fa795c9c3b"}]}
```

```kotlin [field-property box="cerf_churn" name="code" path="cerf_churn_code_.kt"]
import afield.geometry.topology.morsesmale.*
import afield.geometry.topology.tracking.*

/*
Run this one (Alt-Enter), then drag the slider. Three seed patches in Gray–Scott's spot regime, where each spot
grows, splits in two and pushes its daughters apart until the spots fill the field. 120 frames, 40 steps apart: 4,800 steps in all.
*/
var state = grayScottSeed(96, 96, patches = 3, patchSize = 10, seed = 7)
val frames = ArrayList<Img>()
val cerf = CerfDiagram()
for (frame in 0 until 120) {
    state = state.stepTimes(GRAY_SCOTT_SPOTS, 40)
    val v = state.v.gaussianBlur(1.5)
    frames.add(v)
    cerf.add(frame.toDouble(), MorseSmale.compute(v, persistence = 0.05), minPersistence = 0.05)
}

val births = cerf.events(CerfEventType.BIRTH).size
val deaths = cerf.events(CerfEventType.DEATH).size
val swaps = cerf.events(CerfEventType.SWAP).size

fun show(t: Double) {
    val diagram =
        Img.filled(480, 320, 1, 0.08f)
            .drawFLines(cerf.toFLines(480.0, 320.0, cursor = t, showSwaps = false, thickness = 1.0), scale = 2.0)
    val view =
        frames[t.toInt()].colormap("magma", 0f, 0.4f).drawFLines(cerf.fieldMarkers(t, 4.0, window = 1.0), scale = 5.0)
    showImgGrid(
        "$births births, $deaths cancellations, $swaps swaps" to diagram,
        "frame ${t.toInt()}" to view,
        columns = 2,
        cellSide = 480,
    )
}

slider("frame", min = 0.0, max = 119.0, step = 1.0, initialValue = 70.0) { show(it) }
show(70.0)
```

