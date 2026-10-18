# Field Document: paint_canvas

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. Paint — strokes along a flow field

<!-- field-box id="paint_flow_field" index="0" -->

```edn [field-box id="paint_flow_field" role="properties"]
{"id" "paint_flow_field" "index" 0 "name" "Paint — strokes along a flow field" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Paint — strokes along a flow field" "_tombstone" false "frame" #afield/rect [80.0 1000.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "paint_flow_field_code_.kt" "lines" 41 "characters" 1427 "sha256" "715ed16506635d2189d9961718a1ea5ef5a37efbb38265bf4de66e6b25f5ec5d"}]}
```

```kotlin [field-property box="paint_flow_field" name="code" path="paint_flow_field_code_.kt"]
import kotlin.random.Random

run {
    if (!PaintCanvas.isAvailable) {
        markdown(PaintLibrary.describe())
        return@run
    }

    // Change these, then rerun: the fixed seed makes comparisons repeatable.
    val strokeCount = 260
    val stepsPerStroke = 30
    val stepLength = 6.0
    val horizontalFrequency = 0.006
    val verticalFrequency = 0.008
    val diagonalFrequency = 0.002

    val canvas = PaintCanvas(900, aspect = 1.4, seed = 4)
    val brush = canvas.brush(BrushKind.Filbert, width = 16.0)
    val rnd = Random(4)

    // the direction of the field at (x, y)
    fun angle(x: Double, y: Double) =
        sin(x * horizontalFrequency) * 2.0 + cos(y * verticalFrequency + x * diagonalFrequency) * 1.5

    repeat(strokeCount) {
        var x = rnd.nextDouble(0.0, 1000.0)
        var y = rnd.nextDouble(0.0, canvas.heightUnits)
        val line = FLine().moveTo(x, y)
        repeat(stepsPerStroke) {
            val a = angle(x, y)
            x += cos(a) * stepLength
            y += sin(a) * stepLength
            line.lineTo(x, y)
        }
        val t = y / canvas.heightUnits
        // wipe = 0.4 keeps some of the last colour in the brush, so neighbouring strokes blend
        brush.load(rgb(0.9 - 0.6 * t, 0.4 + 0.3 * sin(x * 0.01), 0.2 + 0.6 * t), stiff = 0.9, wipe = 0.4)
        canvas.stroke(line, brush, pressure = 0.5..0.9)
    }
    display("main", canvas.png(relief = 0.8))
}
```

## 2. Paint — pencil, hatching and chalk

<!-- field-box id="paint_pencil_drawing" index="1" -->

```edn [field-box id="paint_pencil_drawing" role="properties"]
{"id" "paint_pencil_drawing" "index" 1 "name" "Paint — pencil, hatching and chalk" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Paint — pencil, hatching and chalk" "_tombstone" false "frame" #afield/rect [80.0 1900.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "paint_pencil_drawing_code_.kt" "lines" 32 "characters" 1071 "sha256" "ef6f9147c20f264348d7d62427e9745f019481bb2cac3886783cc01133f826f9"}]}
```

```kotlin [field-property box="paint_pencil_drawing" name="code" path="paint_pencil_drawing_code_.kt"]
run {
    if (!PaintCanvas.isAvailable) {
        markdown(PaintLibrary.describe())
        return@run
    }

    // a 20 cm sheet, so a 0.3 mm pencil point reads at this pixel width
    val canvas = PaintCanvas(900, aspect = 1.5, sizeMm = 200.0)

    val vase =
        FLine()
            .moveTo(420, 120)
            .cubicTo(330, 220, 300, 420, 380, 560)
            .lineTo(620, 560)
            .cubicTo(700, 420, 670, 220, 580, 120)
    canvas.sketch(vase, canvas.pencil("2B"), pressure = 0.5, passes = 3)

    // hatch the shadow side with a softer pencil
    val shadow =
        FLine()
            .moveTo(560, 140)
            .cubicTo(650, 240, 680, 420, 610, 550)
            .lineTo(540, 550)
            .cubicTo(600, 420, 590, 240, 520, 140)
            .lineTo(560, 140)
    canvas.hatch(shadow, canvas.pencil("4B"), angle = 1.0, spacing = 7.0, length = 50.0)

    // the table edge, ruled in chalk
    canvas.draw(FLine().moveTo(80, 560).lineTo(920, 560), canvas.chalk(), pressure = 0.7, ruler = true)

    display("main", canvas.png(relief = 0.0))
}
```

## 3. Paint — a canvas, a brush, three strokes

<!-- field-box id="paint_first_strokes" index="2" -->

```edn [field-box id="paint_first_strokes" role="properties"]
{"id" "paint_first_strokes" "index" 2 "name" "Paint — a canvas, a brush, three strokes" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Paint — a canvas, a brush, three strokes" "_tombstone" false "frame" #afield/rect [80.0 80.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "paint_first_strokes_code_.kt" "lines" 22 "characters" 734 "sha256" "d1dfe006567557060748647a08af3b69f51699d3d894d18b9f7323d5f508ffdf"}]}
```

```kotlin [field-property box="paint_first_strokes" name="code" path="paint_first_strokes_code_.kt"]
run {
    if (!PaintCanvas.isAvailable) {
        markdown(PaintLibrary.describe())
        return@run
    }

    val canvas = PaintCanvas(900, aspect = 1.6)
    val brush = canvas.brush(BrushKind.Filbert, width = 45.0)

    brush.load(rgb(0.15, 0.3, 0.65))
    canvas.stroke(FLine().moveTo(80, 140).cubicTo(330, 40, 630, 240, 920, 120), brush)

    brush.load(rgb(0.95, 0.7, 0.15))
    canvas.stroke(FLine().moveTo(80, 330).cubicTo(330, 230, 630, 430, 920, 310), brush)

    // no reload: three more passes, and the brush runs out along the way
    val zigzag = FLine().moveTo(80, 470)
    for (i in 1..3) zigzag.lineTo(if (i % 2 == 1) 920 else 80, 470 + i * 40)
    canvas.stroke(zigzag, brush)

    display("main", canvas.png())
}
```

