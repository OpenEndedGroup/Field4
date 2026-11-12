# Field Document: paint_fold_press

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. Paint — press a plate and peel it off

<!-- field-box id="paint_press" index="0" -->

```edn [field-box id="paint_press" role="properties"]
{"id" "paint_press" "index" 0 "name" "Paint — press a plate and peel it off" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Paint — press a plate and peel it off" "_tombstone" false "frame" #afield/rect [80.0 2400.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "paint_press_code_.kt" "lines" 29 "characters" 1294 "sha256" "0356f0aa8e4f2ace4c2f1ee0ee517daa62b0b012ed5b3d1959b4a66d41f4e406"}]}
```

```kotlin [field-property box="paint_press" name="code" path="paint_press_code_.kt"]
run {
    if (!PaintCanvas.isAvailable) {
        markdown(PaintLibrary.describe())
        return@run
    }

    val canvas = PaintCanvas(900, aspect = 1.4, seed = 8)
    canvas.work(FLine().rect(150, 150, 700, 420), rgb(0.12, 0.3, 0.55), handling = Handling.Broad, stiff = 0.5)
    val brush = canvas.brush(BrushKind.HogFlat, width = 90.0)
    brush.load(rgb(0.95, 0.85, 0.7), amount = 4.0, stiff = 0.5)
    canvas.stroke(FLine().moveTo(200, 360).lineTo(800, 360), brush, pressure = 1.0..1.0)
    display("before", canvas.png(wet = true, light = PaintLight.Raking))

    val wet = canvas.wetMm3
    // a palm-sized plate pressed down left of centre, peeled off toward the right
    val left = canvas.press(330.0, 360.0, radius = 150.0, pressure = 0.9, take = 0.5, peel = 0.8, ridge = 12.0)
    // and a lighter press on the right, peeled upward
    val right = canvas.press(680.0, 360.0, radius = 110.0, pressure = 0.6, dx = 0.0, dy = -1.0, take = 0.4, peel = 0.6)
    display("main", canvas.png(wet = true, light = PaintLight.Raking))
    markdown(
        "plates lifted %.2f and %.2f mm³; wet paint %.2f mm³ before, %.2f after"
            .format(
                left.liftedMm3,
                right.liftedMm3,
                wet,
                canvas.wetMm3,
            ),
    )
}
```

## 2. Paint — fold the canvas and open it

<!-- field-box id="paint_fold" index="1" -->

```edn [field-box id="paint_fold" role="properties"]
{"id" "paint_fold" "index" 1 "name" "Paint — fold the canvas and open it" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Paint — fold the canvas and open it" "_tombstone" false "frame" #afield/rect [80.0 80.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "paint_fold_code_.kt" "lines" 25 "characters" 1146 "sha256" "c7485569f4875513b07d53dde66c4ca739a6304c7c285a12a98dc2b310ecd839"}]}
```

```kotlin [field-property box="paint_fold" name="code" path="paint_fold_code_.kt"]
run {
    if (!PaintCanvas.isAvailable) {
        markdown(PaintLibrary.describe())
        return@run
    }

    val canvas = PaintCanvas(900, aspect = 1.4, seed = 7)
    val brush = canvas.brush(BrushKind.HogFlat, width = 70.0)
    val blobs = listOf(
        rgb(0.75, 0.1, 0.08) to FLine().moveTo(300, 150).cubicTo(380, 200, 440, 260, 500, 290),
        rgb(0.1, 0.2, 0.6) to FLine().moveTo(230, 360).cubicTo(330, 320, 420, 400, 490, 430),
        rgb(0.95, 0.75, 0.15) to FLine().moveTo(340, 560).lineTo(500, 520),
    )
    for ((color, line) in blobs) {
        brush.load(color, amount = 4.0, stiff = 0.5)
        canvas.stroke(line, brush, pressure = 1.0..1.0)
    }
    display("before", canvas.png(wet = true, light = PaintLight.Raking))

    val wet = canvas.wetMm3
    // fold down the middle (the line x = 500, running down), press, open it again
    val fold = canvas.fold(500.0, 0.0, pressure = 0.9, spread = 0.5, peel = 0.8, ridge = 18.0)
    display("main", canvas.png(wet = true, light = PaintLight.Raking))
    markdown("fold moved %.1f mm³; wet paint %.2f mm³ before, %.2f after".format(fold.movedMm3, wet, canvas.wetMm3))
}
```

## 3. Paint — rub a thumb through wet paint

<!-- field-box id="paint_rub" index="2" -->

```edn [field-box id="paint_rub" role="properties"]
{"id" "paint_rub" "index" 2 "name" "Paint — rub a thumb through wet paint" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Paint — rub a thumb through wet paint" "_tombstone" false "frame" #afield/rect [80.0 4700.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "paint_rub_code_.kt" "lines" 30 "characters" 1321 "sha256" "acb2330bfbdaf25c8cc568c64a44dad240dea3e223f651e81da11bac504fe753"}]}
```

```kotlin [field-property box="paint_rub" name="code" path="paint_rub_code_.kt"]
run {
    if (!PaintCanvas.isAvailable) {
        markdown(PaintLibrary.describe())
        return@run
    }

    val canvas = PaintCanvas(900, aspect = 1.4, seed = 9)
    val brush = canvas.brush(BrushKind.HogFlat, width = 60.0)
    for ((i, color) in listOf(rgb(0.8, 0.15, 0.1), rgb(0.95, 0.75, 0.15), rgb(0.1, 0.25, 0.6)).withIndex()) {
        brush.load(color, amount = 3.0, stiff = 0.4)
        canvas.stroke(FLine().moveTo(180 + i * 70, 150).lineTo(180 + i * 70, 560), brush, pressure = 1.0..1.0)
    }
    display("before", canvas.png(wet = true, light = PaintLight.Raking))

    val wet = canvas.wetMm3
    // three rubs across the stripes: light, firm, and a curve
    val light = canvas.rub(FLine().moveTo(120, 220).lineTo(820, 230), radius = 30.0, pressure = 0.4)
    val firm = canvas.rub(FLine().moveTo(120, 360).lineTo(820, 350), radius = 40.0, pressure = 0.9)
    val curve =
        canvas.rub(FLine().moveTo(120, 500).cubicTo(400, 420, 600, 600, 860, 480), radius = 35.0, pressure = 0.7)
    display("main", canvas.png(wet = true, light = PaintLight.Raking))
    markdown(
        "rubs moved %.1f mm³; wet paint %.2f mm³ before, %.2f after"
            .format(
                light.movedMm3 + firm.movedMm3 + curve.movedMm3,
                wet,
                canvas.wetMm3,
            ),
    )
}
```

