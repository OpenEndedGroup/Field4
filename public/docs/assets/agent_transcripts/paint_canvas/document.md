# Field Document: paint_canvas

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_session" "fileManagerAccessHistory"]}
```

## 1. Paint — strokes along a flow field

<!-- field-box id="paint_flow_field" index="0" -->

```edn [field-box id="paint_flow_field" role="properties"]
{"id" "paint_flow_field" "index" 0 "name" "Paint — strokes along a flow field" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Paint — strokes along a flow field" "_tombstone" false "frame" #afield/rect [80.0 1000.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "paint_flow_field_code_.kt" "lines" 33 "characters" 1003 "sha256" "9c4fead67187dcb359158b1d77f78ccdcb89ce58adb6e8566c73827517dfb9ef"}]}
```

```kotlin [field-property box="paint_flow_field" name="code" path="paint_flow_field_code_.kt"]
import afield.paint.*
import kotlin.random.Random

run {
if (!PaintCanvas.isAvailable) {
    markdown(PaintLibrary.describe())
    return@run
}

val canvas = PaintCanvas(900, aspect = 1.4, seed = 4)
val brush = canvas.brush(BrushKind.Filbert, width = 16.0)
val rnd = Random(4)

// the direction of the field at (x, y)
fun angle(x: Double, y: Double) = sin(x * 0.006) * 2.0 + cos(y * 0.008 + x * 0.002) * 1.5

repeat(260) {
    var x = rnd.nextDouble(0.0, 1000.0)
    var y = rnd.nextDouble(0.0, canvas.heightUnits)
    val line = FLine().moveTo(x, y)
    repeat(30) {
        val a = angle(x, y)
        x += cos(a) * 6
        y += sin(a) * 6
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
{"id" "paint_pencil_drawing" "index" 1 "name" "Paint — pencil, hatching and chalk" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Paint — pencil, hatching and chalk" "_tombstone" false "frame" #afield/rect [80.0 1900.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "paint_pencil_drawing_code_.kt" "lines" 25 "characters" 915 "sha256" "987e0847612b772f01ca96422ae12bca8b3d633087c715381432249c17bc2dc9"}]}
```

```kotlin [field-property box="paint_pencil_drawing" name="code" path="paint_pencil_drawing_code_.kt"]
import afield.paint.*

run {
if (!PaintCanvas.isAvailable) {
    markdown(PaintLibrary.describe())
    return@run
}

// a 20 cm sheet, so a 0.3 mm pencil point reads at this pixel width
val canvas = PaintCanvas(900, aspect = 1.5, sizeMm = 200.0)

val vase = FLine().moveTo(420, 120).cubicTo(330, 220, 300, 420, 380, 560)
    .lineTo(620, 560).cubicTo(700, 420, 670, 220, 580, 120)
canvas.sketch(vase, canvas.pencil("2B"), pressure = 0.5, passes = 3)

// hatch the shadow side with a softer pencil
val shadow = FLine().moveTo(560, 140).cubicTo(650, 240, 680, 420, 610, 550).lineTo(540, 550)
    .cubicTo(600, 420, 590, 240, 520, 140).lineTo(560, 140)
canvas.hatch(shadow, canvas.pencil("4B"), angle = 1.0, spacing = 7.0, length = 50.0)

// the table edge, ruled in chalk
canvas.draw(FLine().moveTo(80, 560).lineTo(920, 560), canvas.chalk(), pressure = 0.7, ruler = true)

display("main", canvas.png(relief = 0.0))
}
```

## 3. Paint — a canvas, a brush, three strokes

<!-- field-box id="paint_first_strokes" index="2" -->

```edn [field-box id="paint_first_strokes" role="properties"]
{"id" "paint_first_strokes" "index" 2 "name" "Paint — a canvas, a brush, three strokes" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Paint — a canvas, a brush, three strokes" "_tombstone" false "frame" #afield/rect [80.0 80.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "paint_first_strokes_code_.kt" "lines" 24 "characters" 697 "sha256" "6575f71a90a7706054a4702f955af9355205ddacf52d2500c7819cd4a1583f2f"}]}
```

```kotlin [field-property box="paint_first_strokes" name="code" path="paint_first_strokes_code_.kt"]
import afield.paint.*

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

