# Field Document: drawing_on

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. iPad drawing head

<!-- field-box id="drawing-on-head" index="0" -->

```edn [field-box id="drawing-on-head" role="properties"]
{"id" "drawing-on-head" "index" 0 "name" "iPad drawing head" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "iPad drawing head" "_tombstone" false "frame" #afield/rect [520.0 40.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "drawing-on-head_code_.kt" "lines" 38 "characters" 1581 "sha256" "33077654b8d5557ed6347facf8a31c2e25d60e6a1fed640f67f34b2de026c385"}]}
```

```kotlin [field-property box="drawing-on-head" name="code" path="drawing-on-head_code_.kt"]
import afield.io.drawingon.drawingOn
import kotlinx.serialization.json.*

val pad = drawingOn("ipad")
val roomWidth = 400.0
val plan =
    FLine().moveTo(120, 160).lineTo(120 + roomWidth, 160).lineTo(120 + roomWidth, 460).lineTo(120, 460).lineTo(120, 160)
plan.color = vec(1.0, 1.0, 1.0, 0.45)

fun showPlan(extra: List<FLine> = emptyList()) {
    val drawing = listOf(plan) + extra
    pad.publish(drawing, title = "Studio plan")
    lines(drawing, w = 640.0, h = 520.0)
}
showPlan()

val endpoints = mutableMapOf<String, Pair<Double, Double>>()
pad.onEvent { event ->
    if (!event.upload) {
        val stroke = event.data["stroke"]?.jsonPrimitive?.contentOrNull
        if (event.type == "stroke.samples" && stroke != null) {
            val last = event.data["samples"]?.jsonArray?.lastOrNull()?.jsonObject
            val x = last?.get("drawingX")?.jsonPrimitive?.doubleOrNull
            val y = last?.get("drawingY")?.jsonPrimitive?.doubleOrNull
            if (x != null && y != null) endpoints[stroke] = x to y
        }
        if (event.type == "stroke.end" && stroke != null) {
            val point = endpoints.remove(stroke)
            if (point != null && event.data["cancelled"]?.jsonPrimitive?.booleanOrNull != true) {
                val (x, y) = point
                val marker = FLine().moveTo(x - 12, y).lineTo(x + 12, y).moveTo(x, y - 12).lineTo(x, y + 12)
                marker.color = vec(1.0, 1.0, 1.0, 0.8)
                showPlan(listOf(marker))
                println("Pencil endpoint ($x, $y) at ${event.time}s")
            }
        }
    }
}
```

## 2. Drawing On · start here

<!-- field-box id="drawing-on-guide" index="1" -->

```edn [field-box id="drawing-on-guide" role="properties"]
{"id" "drawing-on-guide" "index" 1 "name" "Drawing On · start here" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Drawing On · start here" "_tombstone" false "frame" #afield/rect [40.0 40.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "drawing-on-guide_code_.kt" "lines" 20 "characters" 1013 "sha256" "40c16c65894e0e8f2258c7ef051ae6f1616a88d5d07dc99fc5c54d6e686ceb2b"}]}
```

```kotlin [field-property box="drawing-on-guide" name="code" path="drawing-on-guide_code_.kt"]
markdown(
    """
    # Drawing On
    Run **iPad drawing head** to publish the plan and watch its preview here.

    On a Mac, **./field.sh --setup** offers Drawing On and prepares its offline reader.
    Build the optional app in **native/field-drawingon/DrawingOn.xcodeproj**.
    Connect it to **/drawingon/ws**, target **ipad**, and enable **Stream live**.
    Finish a Pencil stroke: Field sends a crosshair at its last measured point.

    Change **roomWidth** and rerun to change the plan. Re-running replaces this
    box's callback. Give another independent head a different target name.

    For offline work leave streaming off, record drawing and optional voice,
    then **Finish capture**. Upload later from **Saved captures**, or share the
    **.ndjson** save and run **native/field-drawingon/tools/offline-review/review**
    on a Mac for contact sheets, speech clips and a manifest.

    [Setup, coordinates and offline review](https://openendedgroup.com/field4/drawing_on/)""".trimIndent(),
)
```

