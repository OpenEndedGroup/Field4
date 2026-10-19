# Field Document: mapper_explorer

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 1 - A ring, a tail and a blob

<!-- field-box id="mapper_ring" index="0" -->

```edn [field-box id="mapper_ring" role="properties"]
{"id" "mapper_ring" "index" 0 "name" "1 - A ring, a tail and a blob" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - A ring, a tail and a blob" "_tombstone" false "frame" #afield/rect [60.0 60.0 420.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "mapper_ring_code_.kt" "lines" 31 "characters" 1599 "sha256" "fe54a5b9b8998662b9fdeaf81b791c8eb8ceef8f3fce580a035b58cc2da4f0ac"}]}
```

```kotlin [field-property box="mapper_ring" name="code" path="mapper_ring_code_.kt"]
import afield.geometry.topology.mapper.*

// A ring with a tail, and a separate blob, 1500 points in all.
val random = java.util.Random(4)
val points = DoubleArray(1500 * 2)
for (i in 0 until 1500) {
    val (x, y) = when {
        i < 800 -> { val t = random.nextDouble() * 2 * PI; 80 + 45 * cos(t) to 85 + 45 * sin(t) }
        i < 1150 -> { val s = random.nextDouble(); 112 + 70 * s to 117 + 40 * s }
        else -> 160 + 12 * random.nextGaussian() to 35 + 12 * random.nextGaussian()
    }
    points[2 * i] = x + 2.5 * random.nextGaussian()
    points[2 * i + 1] = y + 2.5 * random.nextGaussian()
}
val lenses = mapOf(
    "x" to Mapper.coordinate(points, axis = 0),
    "eccentricity" to Mapper.eccentricity(points),
    "density" to Mapper.density(points, k = 10),
)
val black = Img(200, 170, 3, FloatArray(200 * 170 * 3))
var lens = "eccentricity"; var intervals = 12; var overlap = 0.35

fun show() {
    val graph = Mapper.compute(points, lens = lenses.getValue(lens), intervals = intervals, overlap = overlap)
    showImgGrid("$lens: ${graph.components().size} pieces, ${graph.loops} loop(s)" to
        black.drawFLines([graph.pointsFLine(pointSize = 1.5)] + graph.toFLines(), scale = 3.0), columns = 1, cellSide = 600)
}
slider("intervals", min = 2.0, max = 30.0, step = 1.0, initialValue = 12.0) { intervals = it.toInt(); show() }
slider("overlap", min = 0.05, max = 0.8, step = 0.05, initialValue = 0.35) { overlap = it; show() }
slider("lens: x, eccentricity, density", min = 0.0, max = 2.0, step = 1.0, initialValue = 1.0) { lens = lenses.keys.toList()[it.toInt()]; show() }
show()
```

## 2. 2 - Loops in letters

<!-- field-box id="mapper_letters" index="1" -->

```edn [field-box id="mapper_letters" role="properties"]
{"id" "mapper_letters" "index" 1 "name" "2 - Loops in letters" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - Loops in letters" "_tombstone" false "frame" #afield/rect [560.0 60.0 420.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "mapper_letters_code_.kt" "lines" 25 "characters" 1318 "sha256" "09a9c150f07aaf3e39d2431ac2c476675b4e1c7b13f43bed83233281d485c5c9"}]}
```

```kotlin [field-property box="mapper_letters" name="code" path="mapper_letters_code_.kt"]
import afield.geometry.topology.mapper.*

// Points scattered along three letters, sliced horizontally by the lens. A and O have a loop; H has none.
val strokes = mapOf(
    "A" to [[50.0, 10.0, 15.0, 130.0], [50.0, 10.0, 85.0, 130.0], [30.0, 80.0, 70.0, 80.0]],
    "H" to [[20.0, 10.0, 20.0, 130.0], [80.0, 10.0, 80.0, 130.0], [20.0, 70.0, 80.0, 70.0]],
)
val random = java.util.Random(2)
fun letter(name: String): DoubleArray {
    val out = DoubleArray(900 * 2)
    for (i in 0 until 900) {
        val s = random.nextDouble()
        val (x, y) = if (name == "O") { val t = s * 2 * PI; 50 + 38 * cos(t) to 70 + 58 * sin(t) }
            else strokes.getValue(name)[i % 3].let { (x0, y0, x1, y1) -> x0 + s * (x1 - x0) to y0 + s * (y1 - y0) }
        out[2 * i] = x + 2.0 * random.nextGaussian(); out[2 * i + 1] = y + 2.0 * random.nextGaussian()
    }
    return out
}

val black = Img(100, 140, 3, FloatArray(100 * 140 * 3))
showImgGrid(*["A", "H", "O"].map { name ->
    val points = letter(name)
    val graph = Mapper.compute(points, lens = Mapper.coordinate(points, axis = 1), intervals = 10, overlap = 0.35)
    "$name: ${graph.loops} loop(s)" to black.drawFLines([graph.pointsFLine(pointSize = 1.5)] + graph.toFLines(minSize = 3.0, maxSize = 9.0), scale = 2.0)
}.toTypedArray(), columns = 3, cellSide = 220)
```

