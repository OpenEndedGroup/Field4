# Field Document: gesture_paint_replay

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. what the pad drops

<!-- field-box id="gpr_dimensions" index="0" -->

```edn [field-box id="gpr_dimensions" role="properties"]
{"id" "gpr_dimensions" "index" 0 "name" "what the pad drops" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "what the pad drops" "_tombstone" false "frame" #afield/rect [1160.0 30.0 260.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "gpr_dimensions_code_.kt" "lines" 36 "characters" 1712 "sha256" "cf8e38bff107b87241ddcd01b90e88a88bf13749fb79105f59cc4d764bc0c917"}]}
```

```kotlin [field-property box="gpr_dimensions" name="code" path="gpr_dimensions_code_.kt"]
import afield.graphics.paint.*

run {
if (!PaintCanvas.isAvailable) {
    markdown(PaintLibrary.describe())
    return@run
}
// One take, as a pen records it: an S across the pad, drawn slowly through its first 40%
// (1.1 s) and flicked through the rest (0.25 s), pressing hard at the start and lifting
// as it goes, the pen laid further over toward the end.
val take = (0 until 160).map { i ->
    val u = i / 159.0
    val s = if (u < 0.75) 0.4 * u / 0.75 else 0.4 + 0.6 * (u - 0.75) / 0.25   // how far along
    PenSample(if (u < 0.75) 1.1 * u / 0.75 else 1.1 + 0.25 * (u - 0.75) / 0.25, -0.8 + 1.6 * s, 0.4 * sin(2 * PI * s),
        pressure = 1.0 - 0.95 * s, tiltX = 10.0 + 50.0 * s, tiltY = 0.0)
}
// The same path at an even pace: what is left if the recording kept no time.
val even = run {
    val arc = take.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }.runningFold(0.0) { acc, d -> acc + d }
    take.mapIndexed { i, s -> s.copy(t = take.last().t * arc[i] / arc.last()) }
}
val ways = listOf(
    "position and time, as a mouse records it" to take.map { PenSample(it.t, it.x, it.y) },
    "with the pen's pressure" to take.map { it.copy(tiltX = 0.0) },
    "with pressure and tilt" to take,
    "everything but the time" to even,
)
val canvases = ways.map { (_, samples) ->
    PaintCanvas(300, aspect = 1.0, groundColor = rgb(0.97, 0.96, 0.92), sizeMm = 120.0, seed = 5).also { c ->
        c.stroke(c.gesture(samples), c.dynamicBrush(BrushKind.Filbert, 50.0, hairs = 32).load(rgb(0.15, 0.2, 0.35), stiff = 0.5), seed = 5)
    }
}
display("main", pngRow(canvases))
canvases.forEach { it.close() }
markdown("ways", ways.mapIndexed { i, (name, _) -> "${i + 1}. $name" }.joinToString("\n"))
}
```

## 2. pad to paint

<!-- field-box id="gpr_pad" index="1" -->

```edn [field-box id="gpr_pad" role="properties"]
{"id" "gpr_pad" "index" 1 "name" "pad to paint" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "pad to paint" "_tombstone" false "frame" #afield/rect [40.0 30.0 260.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "gpr_pad_code_.kt" "lines" 48 "characters" 2162 "sha256" "deb1eaaf260f9da927ffddba42c11e4b70aae617038d57f065c78ecc80eefe73"}]}
```

```kotlin [field-property box="gpr_pad" name="code" path="gpr_pad_code_.kt"]
import afield.graphics.paint.*

var p = Vector2f(0f, 0f)
lateinit var pad: GestureHandle

// A wave a pen might draw across the pad in 1.6 s: pressing hardest through the middle,
// leaning right and toward you, with a little tremor.
val rnd = java.util.Random(11)
val wave = (0 until 190).map { i ->
    val u = i / 189.0
    PenSample(1.6 * u, -0.85 + 1.7 * u + 0.006 * (rnd.nextDouble() - 0.5), 0.35 * sin(2 * PI * u) + 0.006 * (rnd.nextDouble() - 0.5),
        pressure = 0.05 + 0.95 * sin(PI * u), tiltX = 35.0, tiltY = 20.0)
}

val x = keyframes("lanes", "x")
val y = keyframes("lanes", "y")
val pressure = keyframes("lanes", "pressure")

fun bake() {
    val (bx, by) = bakeGesture(pad, tolerance = 0.01, channel = "lanes")
    pressure.setKeys(pad.penLane("pressure", tolerance = 0.03))
    println("baked ${pad.samples.size} samples: ${pad.fit(bx, by)}, pressure ${pressure.getKeyframeCount()} keys")
}

fun show(paintIt: (PaintCanvas) -> TimedGesture) {
    if (!PaintCanvas.isAvailable) { markdown(PaintLibrary.describe()); return }
    PaintCanvas(600, aspect = 1.0, groundColor = rgb(0.97, 0.96, 0.92), sizeMm = 200.0, seed = 4).use { c ->
        val g = paintIt(c)
        c.stroke(g, c.dynamicBrush(BrushKind.Round, 40.0, hairs = 32).load(rgb(0.25, 0.15, 0.1), stiff = 0.5), seed = 4)
        println("painted %.2f s, pressure %.2f to %.2f".format(g.duration, g.samples.minOf { it.pressure }, g.samples.maxOf { it.pressure }))
        display("main", c.png(relief = 0.6))
    }
}
fun paintLanes() = show { c -> c.gesture(x, y, pad.xRange, pad.yRange, pressureLane = pressure) }
fun paintTake() = show { c -> c.gesture(pad) }

controlGroup("gesture") {
    pad = gesture("pen", pen = true) { p = it }
    button("bake take") { if (it) { bake(); paintLanes() } }
    button("paint lanes") { if (it) paintLanes() }
    button("paint take") { if (it) paintTake() }
    button("replay lanes") { if (it) { pad.setTake(x, y); println("the pad plays the lanes: ${pad.samples.size} samples") } }
}
displayOpen("gesture")

if (!pad.hasTake) pad.setTake(wave)
if (x.isEmpty() || y.isEmpty()) bake() else println("lanes kept: ${pad.fit(x, y)}")
paintLanes()
```

