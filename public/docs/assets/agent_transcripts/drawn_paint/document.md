# Field Document: drawn_paint

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 6 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_session" "fileManagerAccessHistory"]}
```

## 1. pencil

<!-- field-box id="dp_pencil" index="0" -->

```edn [field-box id="dp_pencil" role="properties"]
{"id" "dp_pencil" "index" 0 "name" "pencil" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "pencil" "_tombstone" false "frame" #afield/rect [1750.0 4200.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dp_pencil_code_.kt" "lines" 15 "characters" 584 "sha256" "3c2429c2fe07d1b0895cd785def3eeabf0072279e22966fc7629b250a1129c5a"}]}
```

```kotlin [field-property box="dp_pencil" name="code" path="dp_pencil_code_.kt"]
import afield.paint.*

run {
if (!PaintCanvas.isAvailable) {
    markdown(PaintLibrary.describe())
    return@run
}
// The strokes from `strokes` in a 6B pencil, on a 20 cm sheet, its pressure following the pen.
val region = Rect(40.0, 40.0, 1040.0, 400.0)
val canvas = PaintCanvas(900, aspect = 3.0, view = Rect(40.0, 40.0, 1040.0, 347.0), sizeMm = 200.0)
val pencil = canvas.pencil("6B")
for (s in notation.strokes.filter { it.bounds.intersects(region) })
    canvas.draw(s.toFLine(), pencil, pressure = s.pressuresAlong(32).ifEmpty { listOf(0.6) })
display("main", canvas.png())
}
```

## 2. strokes

<!-- field-box id="dp_strokes" index="1" -->

### Narrative

From `code`

Three pen strokes, drawn from code into the document's drawing, each with a pen pressure
(0..1) as a function of how far along the stroke the pen is. Running it again replaces them.

```edn [field-box id="dp_strokes" role="properties"]
{"id" "dp_strokes" "index" 1 "name" "strokes" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "strokes" "_tombstone" false "frame" #afield/rect [1750.0 40.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dp_strokes_code_.kt" "lines" 18 "characters" 1367 "sha256" "fa2ce3c8027725a16342680181959fc53bb22e3a1cefd96225e456c9a2086685"}]}
```

```kotlin [field-property box="dp_strokes" name="code" path="dp_strokes_code_.kt"]
// Three pen strokes, drawn from code into the document's drawing, each with a pen pressure
// (0..1) as a function of how far along the stroke the pen is. Running it again replaces them.
notation {
    stroke(FLine().moveTo(80, 300).cubicTo(200, 60, 330, 420, 420, 140), name = "S",
        pressure = { 0.15 + 0.85 * sin(PI * it) })
    stroke(FLine().moveTo(470, 110).cubicTo(560, 300, 700, 330, 820, 180), name = "sweep",
        pressure = { 1.0 - 0.85 * it })
    stroke(FLine().moveTo(880, 330).cubicTo(1060, 300, 1020, 60, 900, 120).cubicTo(820, 160, 880, 260, 960, 250),
        name = "loop", pressure = { 0.15 + 0.85 * it * it })
}
// Read back every stroke in this part of the drawing, with its pressure at 50 evenly spaced points.
val strokes = notation.strokes.filter { it.bounds.intersects(Rect(40.0, 40.0, 1040.0, 400.0)) }
showSignals(*strokes.map { (it.label ?: it.id) to it.pressuresAlong(50) }.toTypedArray(),
    x = (0 until 50).map { it / 49.0 }, title = "pen pressure along each stroke",
    xTitle = "fraction of the way along", yTitle = "pressure", channel = "pressure")
markdown("table", "| stroke | points | lightest | heaviest |\n|---|---|---|---|\n" + strokes.joinToString("\n") {
    "| ${it.label ?: "drawn"} | ${it.points.size} | %.2f | %.2f |".format(it.pressures.minOrNull() ?: 0.0, it.pressures.maxOrNull() ?: 0.0)
}, width = 380)
```

## 3. paint

<!-- field-box id="dp_paint" index="2" -->

```edn [field-box id="dp_paint" role="properties"]
{"id" "dp_paint" "index" 2 "name" "paint" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "paint" "_tombstone" false "frame" #afield/rect [1750.0 900.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dp_paint_code_.kt" "lines" 26 "characters" 1338 "sha256" "3a66cb096b7b98ea49280c148f8394a8fda49e61186c79d4a6be9b7fc0cb9e08"}]}
```

```kotlin [field-property box="dp_paint" name="code" path="dp_paint_code_.kt"]
import afield.paint.*

run {
if (!PaintCanvas.isAvailable) {
    markdown(PaintLibrary.describe())
    return@run
}
// Every stroke in the region `strokes` drew in, painted three times, one row each: the brush
// pressing as the pen did; the pen's pressure squared, so light strokes go lighter; one even pressure.
val region = Rect(40.0, 40.0, 1040.0, 400.0)
val strokes = notation.strokes.filter { it.bounds.intersects(region) }
val colours = listOf(mapOf("ultramarine blue" to 2.0, "bone black" to 0.3), mapOf("vermilion" to 1.0), mapOf("viridian" to 1.0, "chrome yellow" to 0.6))
val canvas = PaintCanvas(900, aspect = 0.9, view = Rect(40.0, 40.0, 1000.0, 1110.0))
val brush = canvas.brush(BrushKind.Round, width = 70.0)
fun lower(s: Mark, dy: Double) = FLine().also { f -> s.points.forEachIndexed { k, p -> if (k == 0) f.moveTo(p.x, p.y + dy) else f.lineTo(p.x, p.y + dy) } }
strokes.forEachIndexed { i, s ->
    val pen = s.pressuresAlong(64)
    brush.load(colours[i % 3], wipe = 1.0)
    canvas.stroke(s.toFLine(), brush, pressure = 1.0..1.0, swell = pen)
    brush.load(colours[i % 3], wipe = 1.0)
    canvas.stroke(lower(s, 360.0), brush, pressure = 1.0..1.0, swell = pen.map { it * it })
    brush.load(colours[i % 3], wipe = 1.0)
    canvas.stroke(lower(s, 720.0), brush, pressure = 0.6..0.6)
}
display("main", canvas.png())
}
```

## 4. drying

<!-- field-box id="dp_drying" index="3" -->

```edn [field-box id="dp_drying" role="properties"]
{"id" "dp_drying" "index" 3 "name" "drying" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "drying" "_tombstone" false "frame" #afield/rect [1750.0 2700.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dp_drying_code_.kt" "lines" 29 "characters" 1305 "sha256" "1830d5f0c3ff5fcc36406f501986a2b26d687ac76e680f8cf96433a8aedd4e0f"}]}
```

```kotlin [field-property box="dp_drying" name="code" path="dp_drying_code_.kt"]
import afield.paint.*

run {
if (!PaintCanvas.isAvailable) {
    markdown(PaintLibrary.describe())
    return@run
}
// One yellow band, crossed in blue after longer and longer waits.
val hours = listOf(0.0, 2.0, 10.0, 33.0, 100.0)
notation {
    stroke(FLine().moveTo(60, 1080).cubicTo(400, 1070, 700, 1090, 1020, 1080), name = "slow band", pressure = { 0.85 })
    hours.indices.forEach { i ->
        val x = 150.0 + i * 190
        stroke(FLine().moveTo(x, 960).lineTo(x + 20, 1200), name = "after $i", pressure = { 0.5 + 0.5 * sin(PI * it) })
    }
}
val canvas = PaintCanvas(900, aspect = 3.2, view = Rect(40.0, 940.0, 1000.0, 312.0))
fun paint(name: String, brush: PaintBrush) = notation.named(name)!!.let {
    canvas.stroke(it.toFLine(), brush, pressure = 1.0..1.0, swell = it.pressuresAlong(32), shake = 0.4)
}
paint("slow band", canvas.brush(BrushKind.HogFlat, width = 80.0).load(mapOf("cadmium yellow" to 1.0)))
var waited = 0.0
hours.forEachIndexed { i, h ->
    canvas.wait((h - waited) * 60); waited = h      // simulated minutes
    paint("after $i", canvas.brush(BrushKind.HogFlat, width = 60.0).load(mapOf("ultramarine blue" to 1.0)))
}
display("main", canvas.png())
markdown("times", "crossed after " + hours.joinToString(", ") { "${it.toInt()} h" } + " (left to right)", width = 500)
}
```

## 5. crossings

<!-- field-box id="dp_crossings" index="4" -->

```edn [field-box id="dp_crossings" role="properties"]
{"id" "dp_crossings" "index" 4 "name" "crossings" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "crossings" "_tombstone" false "frame" #afield/rect [1750.0 2100.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dp_crossings_code_.kt" "lines" 37 "characters" 1849 "sha256" "3480cedb590d8d7ee6921f464ffdb4f4c2b3656c41fa665e1709f5d99fc838ce"}]}
```

```kotlin [field-property box="dp_crossings" name="code" path="dp_crossings_code_.kt"]
import afield.paint.*

run {
if (!PaintCanvas.isAvailable) {
    markdown(PaintLibrary.describe())
    return@run
}
// Four pairs of bands, yellow over red, each crossed by one vertical stroke.
notation {
    for (k in 0 until 4) {
        val x = 60.0 + k * 250
        stroke(FLine().moveTo(x, 560).cubicTo(x + 70, 545, x + 140, 575, x + 210, 560), name = "yellow $k", pressure = { 0.8 })
        stroke(FLine().moveTo(x, 760).cubicTo(x + 70, 745, x + 140, 775, x + 210, 760), name = "red $k", pressure = { 0.8 })
        stroke(FLine().moveTo(x + 95, 480).cubicTo(x + 120, 600, x + 90, 720, x + 115, 860), name = "across $k",
            pressure = { 0.5 + 0.5 * sin(PI * it) })
    }
}
val canvas = PaintCanvas(900, aspect = 2.4, view = Rect(40.0, 460.0, 1000.0, 416.0))
fun paint(name: String, brush: PaintBrush) = notation.named(name)!!.let {
    canvas.stroke(it.toFLine(), brush, pressure = 1.0..1.0, swell = it.pressuresAlong(32), shake = 0.5)
}
val band = canvas.brush(BrushKind.HogFlat, width = 70.0)
for (k in 0 until 4) {
    paint("yellow $k", band.load(mapOf("cadmium yellow" to 1.0), wipe = 1.0))
    paint("red $k", band.load(mapOf("vermilion" to 1.0), wipe = 1.0))
}
// 1: blue straight across the wet bands, so the colours mix where they meet
paint("across 0", canvas.brush(BrushKind.HogFlat, width = 60.0).load(mapOf("ultramarine blue" to 1.0)))
// 3: a clean, dry hog-hair brush dragged through them: no paint of its own, it smears theirs
paint("across 2", canvas.brush(BrushKind.HogFlat, width = 60.0))
// 4: a clean badger blender, which only softens what is there
paint("across 3", canvas.brush(BrushKind.Badger, width = 60.0))
// 2: the same blue, after everything has dried
canvas.dry()
paint("across 1", canvas.brush(BrushKind.HogFlat, width = 60.0).load(mapOf("ultramarine blue" to 1.0)))
display("main", canvas.png())
}
```

## 6. meadow

<!-- field-box id="dp_meadow" index="5" -->

```edn [field-box id="dp_meadow" role="properties"]
{"id" "dp_meadow" "index" 5 "name" "meadow" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "meadow" "_tombstone" false "frame" #afield/rect [1750.0 3200.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dp_meadow_code_.kt" "lines" 75 "characters" 5244 "sha256" "b39e8f87dd6ddb00963d1521ce4df9abfe2622618625985ee6fd8826dc2b0b98"}]}
```

```kotlin [field-property box="dp_meadow" name="code" path="dp_meadow_code_.kt"]
import afield.paint.*
import kotlin.random.Random

run {
if (!PaintCanvas.isAvailable) {
    markdown(PaintLibrary.describe())
    return@run
}
// A meadow at evening, from 560 pen strokes on the drawing. The strokes are drawn by code here
// (a seeded random hand) into the region below; draw your own there instead and paint those.
val region = Rect(40.0, 1460.0, 1600.0, 1000.0)
val X = region.x
val Y = region.y
val rnd = Random(7)
fun r(a: Double, b: Double) = rnd.nextDouble(a, b)
fun sweep(x: Double, y: Double, w: Double, dy: Double = 0.0) =
    FLine().moveTo(X + x, Y + y).cubicTo(X + x + w * 0.3, Y + y - 20, X + x + w * 0.7, Y + y + 20, X + x + w, Y + y + dy)
notation {
    for (i in 0 until 18) stroke(sweep(r(-160.0, 0.0), 20.0 + i * 26 + r(-6.0, 6.0), 1760.0, r(-10.0, 10.0)),   // sky
        name = "sky $i", pressure = { 0.5 + 0.5 * sin(PI * it) })
    for (c in 0 until 5) {                                                                                  // clouds
        val cx = r(150.0, 1350.0); val cy = r(90.0, 300.0)
        for (k in 0 until 7) stroke(sweep(cx + r(-120.0, 80.0), cy + r(-35.0, 25.0), r(80.0, 220.0), 4.0),
            name = "cloud $c.$k", pressure = { 0.2 + 0.8 * sin(PI * it) })
    }
    for (i in 0 until 5) stroke(sweep(-80.0, 500.0 + i * 10, 1760.0, -40.0),                                 // far hills
        name = "hill $i", pressure = { 0.4 + 0.6 * sin(PI * it) })
    for (i in 0 until 22) stroke(sweep(-80.0, 540.0 + i * 22, 1760.0),                                       // the field
        name = "field $i", pressure = { 0.7 + 0.3 * sin(PI * it) })
    for (i in 0 until 420) {                                                                                // grass
        val y = r(560.0, 1000.0); val d = (y - 540) / 460; val x = r(-20.0, 1620.0)
        val h = r(40.0, 120.0) + 300 * d * d; val lean = r(-0.3, 0.5) * h
        stroke(FLine().moveTo(X + x, Y + y).cubicTo(X + x + lean * 0.1, Y + y - h * 0.5, X + x + lean * 0.5, Y + y - h * 0.9, X + x + lean, Y + y - h),
            name = "grass $i", pressure = { 1.0 - 0.95 * it })                                              // flicked up from the root
    }
    for (i in 0 until 60) {                                                                                 // poppies
        val y = r(570.0, 990.0); val x = r(40.0, 1560.0); val s = 4.0 + 22 * (y - 540) / 460
        stroke(FLine().moveTo(X + x - s, Y + y).cubicTo(X + x - s * 0.3, Y + y - s, X + x + s * 0.5, Y + y - s * 0.8, X + x + s, Y + y + s * 0.2),
            name = "poppy $i", pressure = { 0.5 + 0.5 * sin(PI * it) })
    }
}

val canvas = PaintCanvas(1200, aspect = 1.6, ground = PaintGround.OilRedGround, view = region)
val strokes = notation.strokes.filter { it.bounds.intersects(region) }
fun named(p: String) = strokes.filter { it.label?.startsWith(p) == true }
fun depth(s: Mark) = ((s.start!!.y - Y - 540) / 460).coerceIn(0.0, 1.0)   // 0 at the horizon, 1 at the front
fun paint(s: Mark, b: PaintBrush, p: Double = 1.0, shake: Double = 1.0) =
    canvas.stroke(s.toFLine(), b, pressure = p..p, swell = s.pressuresAlong(32), shake = shake)

val sky = canvas.brush(BrushKind.Filbert, width = 60.0)
named("sky").forEachIndexed { i, s ->
    val t = i / 17.0   // cobalt at the top, warming to Naples yellow and a little rose at the horizon
    paint(s, sky.load(mapOf("lead white" to 3.0, "cobalt blue" to 2.0 * (1 - t) * (1 - t), "Naples yellow" to 2.5 * t * t, "rose madder" to 0.4 * t), wipe = 0.5))
}
val cloud = canvas.brush(BrushKind.HogFlat, width = 34.0)
named("cloud").forEach { paint(it, cloud.load(Tubes.mix(mapOf("lead white" to 4.0, "Naples yellow" to 0.4, "rose madder" to 0.1)), hiding = 0.5, wipe = 0.3), shake = 0.8) }
val hill = canvas.brush(BrushKind.HogFlat, width = 26.0)
named("hill").forEachIndexed { i, s -> paint(s, hill.load(mapOf("lead white" to 3.0 - i * 0.4, "cobalt blue" to 1.0, "green earth" to 1.0 + i * 0.4), wipe = 0.6)) }
val field = canvas.brush(BrushKind.HogFlat, width = 50.0)
named("field").forEachIndexed { i, s ->
    val t = i / 21.0
    paint(s, field.load(mapOf("green earth" to 2.0, "yellow ochre" to 2.0 * (1 - t), "raw umber" to 1.5 * t, "lead white" to 1.0 * (1 - t)), wipe = 0.5))
}
canvas.wait(40.0)   // let the field set a little before the grass goes into it
val grass = canvas.brush(BrushKind.Round, width = 10.0)
val greens = listOf(mapOf("viridian" to 1.0, "yellow ochre" to 1.5), mapOf("green earth" to 2.0, "chrome yellow" to 1.0),
    mapOf("viridian" to 1.0, "raw umber" to 1.0), mapOf("yellow ochre" to 2.0, "lead white" to 1.0),
    mapOf("Prussian blue" to 0.2, "chrome yellow" to 2.0), mapOf("Naples yellow" to 2.0, "green earth" to 1.0))
named("grass").sortedBy { it.start!!.y }.forEachIndexed { i, s -> paint(s, grass.load(greens[i % greens.size], wipe = 0.5), 0.4 + 0.6 * depth(s), shake = 0.6) }
val poppy = canvas.brush(BrushKind.Filbert, width = 24.0)
named("poppy").sortedBy { it.start!!.y }.forEach { s -> paint(s, poppy.load(mapOf("vermilion" to 3.0, "cadmium red" to 1.0, "lead white" to 0.2), wipe = 0.9), 0.3 + 0.7 * depth(s)) }
canvas.dry()
canvas.glaze(rgb(0.6, 0.45, 0.2), coats = 0.12)   // a thin warm varnish over the whole thing
display("main", canvas.png())
}
```

