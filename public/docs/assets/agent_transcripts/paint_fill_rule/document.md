# Field Document: paint_fill_rule

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "watchdog" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. worked under each fill rule

<!-- field-box id="pfr_worked" index="0" -->

```edn [field-box id="pfr_worked" role="properties"]
{"id" "pfr_worked" "index" 0 "name" "worked under each fill rule" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "worked under each fill rule" "_tombstone" false "frame" #afield/rect [40.0 40.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pfr_worked_code_.kt" "lines" 18 "characters" 630 "sha256" "3fde29112394800b5b75d476a82a43ee0c51e42b80d7136e16f31dbde21398fd"}]}
```

```kotlin [field-property box="pfr_worked" name="code" path="pfr_worked_code_.kt"]
import afield.graphics.nile.*

run {
    if (!PaintCanvas.isAvailable) {
        markdown(PaintLibrary.describe())
        return@run
    }

    val canvas = PaintCanvas(900, aspect = 2.0, sizeMm = 300.0)

    fun squares(dx: Double, rule: FillRule) =
        FLine().rect(dx + 60.0, 60.0, 220.0, 220.0).rect(dx + 180.0, 180.0, 220.0, 220.0).also { it.fillRule = rule }

    canvas.work(squares(0.0, FillRule.NON_ZERO), rgb(0.2, 0.4, 0.6), Handling.Body, angle = 0.5, clip = true)
    canvas.work(squares(500.0, FillRule.EVEN_ODD), rgb(0.2, 0.4, 0.6), Handling.Body, angle = 0.5, clip = true)

    display("main", canvas.png())
}
```

## 2. paint mask against Gezira

<!-- field-box id="pfr_against_gezira" index="1" -->

```edn [field-box id="pfr_against_gezira" role="properties"]
{"id" "pfr_against_gezira" "index" 1 "name" "paint mask against Gezira" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "paint mask against Gezira" "_tombstone" false "frame" #afield/rect [40.0 180.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pfr_against_gezira_code_.kt" "lines" 27 "characters" 1067 "sha256" "e13f169a2db2f2e5953c1488bd31d113eb3a76152dd58d6b8a018b829543696d"}]}
```

```kotlin [field-property box="pfr_against_gezira" name="code" path="pfr_against_gezira_code_.kt"]
import afield.graphics.nile.*

run {
    if (!PaintCanvas.isAvailable) {
        markdown(PaintLibrary.describe())
        return@run
    }

    fun squares(k: Double, rule: FillRule) = FLine().rect(150.0 * k, 50.0 * k, 400.0 * k, 350.0 * k).rect(400.0 * k, 150.0 * k, 450.0 * k, 300.0 * k).also {
        it.fillRule = rule
    }

    for (rule in [FillRule.NON_ZERO, FillRule.EVEN_ODD]) {
        val canvas = PaintCanvas(400, aspect = 2.0)
        canvas.glaze(rgb(0.05, 0.1, 0.6), squares(1.0, rule), coats = 4.0, hiding = 1.0)
        val painted = canvas.render(wet = true).map { ((it shr 16) and 0xff) < 128 }
        val reference = squares(0.4, rule).also {
            it.filled = true
            it.stroked = false
        }
        val gezira = GeziraFLine.render([reference], 400, 200).canvas
        val differ = painted.indices.count { painted[it] != (gezira.alpha(it % 400, it / 400) > 0.5f) }
        println(
            "$rule: $differ of ${painted.size} pixels differ from Gezira; overlap painted: ${painted[110 * 400 + 190]}",
        )
    }
}
```

