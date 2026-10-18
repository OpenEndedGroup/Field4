# Field Document: polyvector_explorer

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 2 - A photograph's edges, off the main thread

<!-- field-box id="pv_photograph" index="0" -->

```edn [field-box id="pv_photograph" role="properties"]
{"id" "pv_photograph" "index" 0 "name" "2 - A photograph's edges, off the main thread" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - A photograph's edges, off the main thread" "_tombstone" false "frame" #afield/rect [60.0 900.0 420.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pv_photograph_code_.kt" "lines" 18 "characters" 1142 "sha256" "ddeb62d044e04d9900720517e91b55679320ba1e380abe1b88a97eca775f8bf9"}]}
```

```kotlin [field-property box="pv_photograph" name="code" path="pv_photograph_code_.kt"]
import afield.geometry.vectorize.polyvector.*

// A photograph's edges, vectorized off the box's thread: an edge map holds far more ink than a
// drawing, and this takes about a minute.
val print = imgFromFile("public/docs/assets/vision/fline-viewer.jpg", 3).crop(78, 262, 128, 236).scaleBy(2.0)
val edges = print.luma().gaussianBlur(1.2).sobel().magnitude()
val strongest = edges.data.sortedDescending()[edges.data.size / 50]
val evidence = Img(edges.width, edges.height, 1, FloatArray(edges.data.size) { (edges.data[it] / strongest).coerceIn(0f, 1f) })
val drawing = Img(evidence.width, evidence.height, 3, FloatArray(evidence.data.size * 3) { 1f - evidence.data[it / 3] })

markdown("Vectorizing the edges of the leaf print...")
runAsync(onResult = { result: PolyvectorDrawing ->
    val dark = Img(print.width, print.height, 3, FloatArray(print.width * print.height * 3))
    markdown("${result.curves.size} curves, coverage ${"%.2f".format(result.coverage())}")
    showImgGrid("curves" to print.overlay(dark, 0.55).drawFLines(result.toFLines(thickness = 1.5)), columns = 1, cellSide = 360)
}) {
    Polyvector.vectorizeAsync(drawing)
}
```

## 2. 1 - A sketch, re-traced under a slider

<!-- field-box id="pv_sketch" index="1" -->

```edn [field-box id="pv_sketch" role="properties"]
{"id" "pv_sketch" "index" 1 "name" "1 - A sketch, re-traced under a slider" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - A sketch, re-traced under a slider" "_tombstone" false "frame" #afield/rect [60.0 60.0 420.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pv_sketch_code_.kt" "lines" 45 "characters" 2292 "sha256" "976bb707b3ebc113ea2ade2b7ef226b27467f20888d79dcab4f2d78ce3c161ea"}]}
```

```kotlin [field-property box="pv_sketch" name="code" path="pv_sketch_code_.kt"]
import afield.geometry.vectorize.polyvector.*

// A sketch, drawn the way a hand would: each stroke laid down three times with a little wobble, in a
// soft pencil line about three pixels wide.
val random = java.util.Random(3)
val strokes = [
    [Vec2(40f, 200f), Vec2(110f, 60f), Vec2(180f, 200f)],         // an A
    [Vec2(70f, 140f), Vec2(150f, 140f)],                         // its crossbar
    [Vec2(220f, 60f), Vec2(220f, 200f)],                         // a post
    [Vec2(220f, 130f), Vec2(300f, 130f)],                        // meeting a beam: a T
    [Vec2(250f, 60f), Vec2(340f, 200f)],                         // and a stroke crossing it: an X
    (0..30).map { Vec2(40f + 300f * it / 30, (245 + 12 * sin(it / 30.0 * 2 * PI)).toFloat()) },  // a wave
]
val w = 380; val h = 280
val ink = FloatArray(w * h)
for (stroke in strokes) repeat(3) {
    val jitter = { random.nextGaussian().toFloat() * 1.5f }
    val pts = stroke.map { Vec2(it.x + jitter(), it.y + jitter()) }
    for (k in 0 until pts.size - 1) {
        val a = pts[k]; val b = pts[k + 1]
        val n = (a.distance(b) * 2).toInt()
        for (s in 0..n) {
            val t = s.toFloat() / n
            val cx = a.x + (b.x - a.x) * t; val cy = a.y + (b.y - a.y) * t
            for (y in (cy - 4).toInt()..(cy + 4).toInt()) for (x in (cx - 4).toInt()..(cx + 4).toInt()) {
                if (x !in 0 until w || y !in 0 until h) continue
                val d = hypot(x + 0.5 - cx, y + 0.5 - cy) / 1.6
                ink[y * w + x] = maxOf(ink[y * w + x], (0.8 * exp(-d * d)).toFloat())
            }
        }
    }
}
val drawing = Img(w, h, 3, FloatArray(w * h * 3) { 1f - ink[it / 3] })

val field = Polyvector.fitField(Polyvector.inkOf(drawing))

// The field is built once; dragging the slider only re-traces it, so it answers quickly.
fun show(prune: Double) {
    val result = field.trace(TraceParameters(pruneRatio = prune))
    showImgGrid("${result.curves.size} curves, coverage ${"%.2f".format(result.coverage())}" to
        drawing.drawFLines(listOf(result.streamlinesFLine()) + result.toFLines(thickness = 2.0), scale = 2.0),
        channel = "curves", columns = 1, cellSide = 640)
}
slider("prune short branches", min = 0.0, max = 1.0, step = 0.05, initialValue = 0.75) { show(it) }
show(0.75)
```

