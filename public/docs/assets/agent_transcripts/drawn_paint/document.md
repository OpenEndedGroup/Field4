# Field Document: drawn_paint

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. pencil

<!-- field-box id="dp_pencil" index="0" -->

```edn [field-box id="dp_pencil" role="properties"]
{"id" "dp_pencil" "index" 0 "name" "pencil" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "pencil" "_tombstone" false "frame" #afield/rect [1150.0 1440.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dp_pencil_code_.kt" "lines" 13 "characters" 485 "sha256" "c0f2e180512c74f4b68b9554658b4e5ff51d7553481123fc297d5bcfd6c473cd"}]}
```

```kotlin [field-property box="dp_pencil" name="code" path="dp_pencil_code_.kt"]
import afield.paint.*

run {
if (!PaintCanvas.isAvailable) {
    markdown(PaintLibrary.describe())
    return@run
}
// The same strokes in a 6B pencil, on a 20 cm sheet, its pressure following the pen.
val canvas = PaintCanvas(900, aspect = 2.5, view = Rect(40.0, 40.0, 1040.0, 416.0), sizeMm = 200.0)
val pencil = canvas.pencil("6B")
for (s in notation.strokes) canvas.draw(s.toFLine(), pencil, pressure = s.pressuresAlong(32).ifEmpty { listOf(0.6) })
display("main", canvas.png())
}
```

## 2. strokes

<!-- field-box id="dp_strokes" index="1" -->

### Narrative

From `code`

Three pen strokes, drawn from code into the document's drawing, each with a pen pressure
(0..1) as a function of how far along the stroke the pen is. Running it again replaces them.
To paint strokes you drew yourself (Alt+D, P, with a tablet pen for pressure), delete this block.

```edn [field-box id="dp_strokes" role="properties"]
{"id" "dp_strokes" "index" 1 "name" "strokes" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "strokes" "_tombstone" false "frame" #afield/rect [1150.0 40.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dp_strokes_code_.kt" "lines" 17 "characters" 1281 "sha256" "b5d876f47cb6c94ad55fa83b6837aed9f8ffa5e4934a36eb7921a85fa4500845"}]}
```

```kotlin [field-property box="dp_strokes" name="code" path="dp_strokes_code_.kt"]
// Three pen strokes, drawn from code into the document's drawing, each with a pen pressure
// (0..1) as a function of how far along the stroke the pen is. Running it again replaces them.
// To paint strokes you drew yourself (Alt+D, P, with a tablet pen for pressure), delete this block.
notation {
    stroke(FLine().moveTo(80, 300).cubicTo(200, 60, 330, 420, 420, 140), name = "S",
        pressure = { 0.15 + 0.85 * sin(PI * it) })
    stroke(FLine().moveTo(470, 110).cubicTo(560, 300, 700, 330, 820, 180), name = "sweep",
        pressure = { 1.0 - 0.85 * it })
    stroke(FLine().moveTo(880, 330).cubicTo(1060, 300, 1020, 60, 900, 120).cubicTo(820, 160, 880, 260, 960, 250),
        name = "loop", pressure = { 0.15 + 0.85 * it * it })
}
// Read them back: the pressure at 50 points evenly spaced along each.
val strokes = notation.strokes.filter { it.pressures.isNotEmpty() }
if (strokes.isNotEmpty()) showSignals(*strokes.map { (it.label ?: it.id) to it.pressuresAlong(50) }.toTypedArray(),
    x = (0 until 50).map { it / 49.0 }, title = "pen pressure along each stroke",
    xTitle = "fraction of the way along", yTitle = "pressure", channel = "pressure")
println(notation.strokes.map { "${it.label ?: it.id}: ${it.points.size} points, ${it.pressures.size} pressures" })
```

## 3. paint

<!-- field-box id="dp_paint" index="2" -->

```edn [field-box id="dp_paint" role="properties"]
{"id" "dp_paint" "index" 2 "name" "paint" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "paint" "_tombstone" false "frame" #afield/rect [1150.0 700.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dp_paint_code_.kt" "lines" 26 "characters" 1206 "sha256" "d079663f548d5938b3e2287ee864ec8fbb97755d0bf3316e1486a443d1c2b6c1"}]}
```

```kotlin [field-property box="dp_paint" name="code" path="dp_paint_code_.kt"]
import afield.paint.*

run {
if (!PaintCanvas.isAvailable) {
    markdown(PaintLibrary.describe())
    return@run
}
// Every stroke in the drawing, painted with a brush that presses as hard as the pen did.
// `view` is the rectangle of drawing coordinates that becomes the canvas.
val colours = listOf(rgb(0.12, 0.2, 0.55), rgb(0.85, 0.3, 0.1), rgb(0.15, 0.5, 0.25))
val canvas = PaintCanvas(900, aspect = 1.6, view = Rect(40.0, 40.0, 1040.0, 650.0))
val brush = canvas.brush(BrushKind.Round, width = 40.0)
notation.strokes.forEachIndexed { i, s ->
    brush.load(colours[i % colours.size], wipe = 1.0)
    val swell = s.pressuresAlong(64)   // empty for a mouse stroke: then the base pressure applies
    canvas.stroke(s.toFLine(), brush, pressure = if (swell.isEmpty()) 0.6..0.6 else 1.0..1.0, swell = swell)
}
// The same strokes 320 units lower, at one even pressure, to compare.
notation.strokes.forEachIndexed { i, s ->
    val lower = FLine()
    s.points.forEachIndexed { k, p -> if (k == 0) lower.moveTo(p.x, p.y + 320) else lower.lineTo(p.x, p.y + 320) }
    brush.load(colours[i % colours.size], wipe = 1.0)
    canvas.stroke(lower, brush, pressure = 0.6..0.6)
}
display("main", canvas.png())
}
```

