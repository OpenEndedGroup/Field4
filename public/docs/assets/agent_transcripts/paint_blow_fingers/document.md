# Field Document: paint_blow_fingers

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. Paint — blow a straw across wet paint

<!-- field-box id="paint_blow" index="0" -->

```edn [field-box id="paint_blow" role="properties"]
{"id" "paint_blow" "index" 0 "name" "Paint — blow a straw across wet paint" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Paint — blow a straw across wet paint" "_tombstone" false "frame" #afield/rect [80.0 80.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "paint_blow_code_.kt" "lines" 22 "characters" 1013 "sha256" "b0bd5de7a0230109302a467fc9fcf74f2161bd92e5709f1a69bfc06fca6f03e7"}]}
```

```kotlin [field-property box="paint_blow" name="code" path="paint_blow_code_.kt"]
run {
    if (!PaintCanvas.isAvailable) {
        markdown(PaintLibrary.describe())
        return@run
    }

    val canvas = PaintCanvas(900, aspect = 1.4, seed = 11)
    val brush = canvas.brush(BrushKind.HogFlat, width = 120.0)
    brush.load(rgb(0.1, 0.18, 0.55), amount = 5.0, stiff = 0.3)
    for (y in listOf(220, 330, 440, 550)) canvas.stroke(FLine().moveTo(120, y).lineTo(880, y), brush, pressure = 1.0..1.0)
    display("before", canvas.png(wet = true, light = PaintLight.Raking))

    val wet = canvas.wetMm3
    // a straw, blown along a curve through the film
    val path = FLine().moveTo(150, 480).cubicTo(350, 250, 600, 600, 850, 300)
    val blown = canvas.blow(path, radius = 14.0, pressure = 0.9, tendril = 9.0)
    display("main", canvas.png(wet = true, light = PaintLight.Raking))
    markdown(
        "blow moved %.1f mm³ at %d places, branching %.2f; wet paint %.2f mm³ before, %.2f after"
            .format(blown.movedMm3, blown.stations, blown.branching, wet, canvas.wetMm3),
    )
}
```

## 2. Paint — one puff, three viscosity contrasts

<!-- field-box id="paint_blow_contrast" index="1" -->

```edn [field-box id="paint_blow_contrast" role="properties"]
{"id" "paint_blow_contrast" "index" 1 "name" "Paint — one puff, three viscosity contrasts" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Paint — one puff, three viscosity contrasts" "_tombstone" false "frame" #afield/rect [80.0 2400.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "paint_blow_contrast_code_.kt" "lines" 18 "characters" 859 "sha256" "1b0e07310b4ecfc6664c2c4cc712db8b09e6b0925b63e94927172d6cc114d96d"}]}
```

```kotlin [field-property box="paint_blow_contrast" name="code" path="paint_blow_contrast_code_.kt"]
run {
    if (!PaintCanvas.isAvailable) {
        markdown(PaintLibrary.describe())
        return@run
    }

    val canvas = PaintCanvas(900, aspect = 2.4, seed = 12)
    val brush = canvas.brush(BrushKind.HogFlat, width = 130.0)
    brush.load(rgb(0.1, 0.18, 0.55), amount = 5.0, stiff = 0.3)
    for (y in listOf(140, 260)) canvas.stroke(FLine().moveTo(60, y).lineTo(940, y), brush, pressure = 1.0..1.0)
    val report = StringBuilder()
    for ((x, contrast) in listOf(170.0 to 1.5, 500.0 to 8.0, 830.0 to 200.0)) {
        val b = canvas.blow(FLine().moveTo(x, 200).lineTo(x + 2, 200), radius = 16.0, pressure = 1.0, contrast = contrast, tendril = 8.0)
        report.append("contrast %.1f: branching %.2f  \n".format(contrast, b.branching))
    }
    display("main", canvas.png(wet = true, light = PaintLight.Raking))
    markdown(report.toString())
}
```

