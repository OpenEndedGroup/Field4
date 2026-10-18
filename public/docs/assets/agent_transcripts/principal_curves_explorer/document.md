# Field Document: principal_curves_explorer

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 1 - Three strands, under a bending slider

<!-- field-box id="pc_strands" index="0" -->

```edn [field-box id="pc_strands" role="properties"]
{"id" "pc_strands" "index" 0 "name" "1 - Three strands, under a bending slider" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - Three strands, under a bending slider" "_tombstone" false "frame" #afield/rect [60.0 60.0 420.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pc_strands_code_.kt" "lines" 26 "characters" 1098 "sha256" "ba5357a4e723ab452a4b55e27fdeb059e843798acbc4338c160681957fee5129"}]}
```

```kotlin [field-property box="pc_strands" name="code" path="pc_strands_code_.kt"]
import afield.geometry.curves.principal.*

// Three noisy strands of different thickness, 1000 points each.
val random = java.util.Random(2)
val points = DoubleArray(3000 * 2)
for (i in 0 until 3000) {
    val t = random.nextDouble()
    val (x, y, spread) = when (i % 3) {
        0 -> Triple(30 + 360 * t, 50 + 25 * sin(7 * t), 2.0)
        1 -> Triple(40 + 300 * t, 130 + 40 * t * t, 7.0)
        else -> Triple(330 + 50 * cos(3 * t), 200 + 50 * sin(3 * t), 3.5)
    }
    points[2 * i] = x + spread * random.nextGaussian()
    points[2 * i + 1] = y + spread * random.nextGaussian()
}
val canvas = Img(420, 270, 3, FloatArray(420 * 270 * 3) { 0.06f })

fun show(mu: Double) {
    val forest = PrincipalCurves.fit(points, curves = 3, nodes = 45, parameters = ElasticParameters(mu = mu))
    showImgGrid("mu = $mu" to canvas.drawFLines([forest.pointsFLine(2.0)] + forest.widthFLines() + forest.toFLines(), scale = 2.0),
        channel = "fit", columns = 1, cellSide = 520)
    forest.close()
}

slider("bending (mu)", min = 0.0, max = 2.0, step = 0.05, initialValue = 0.1) { show(it) }
show(0.1)
```

## 2. 2 - A leaf as a tree

<!-- field-box id="pc_leaf" index="1" -->

```edn [field-box id="pc_leaf" role="properties"]
{"id" "pc_leaf" "index" 1 "name" "2 - A leaf as a tree" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - A leaf as a tree" "_tombstone" false "frame" #afield/rect [560.0 60.0 420.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pc_leaf_code_.kt" "lines" 14 "characters" 935 "sha256" "0955f660274a9d753ab3c694f8c3423d20934c0db9e579cbd99a479101c81387"}]}
```

```kotlin [field-property box="pc_leaf" name="code" path="pc_leaf_code_.kt"]
import afield.geometry.curves.principal.*

// Every pixel of the leaf print greener than it is red or blue, weighted by how much.
val print = imgFromFile("public/docs/assets/vision/fline-viewer.jpg", 3).crop(78, 262, 128, 236).scaleBy(2.0)
val points = ArrayList<Double>()
val weights = ArrayList<Double>()
for (y in 0 until print.height) for (x in 0 until print.width) {
    val green = print.at(x, y, 1) - maxOf(print.at(x, y, 0), print.at(x, y, 2))
    if (green > 0.04) { points.add(x.toDouble()); points.add(y.toDouble()); weights.add(green.toDouble()) }
}
val forest = PrincipalCurves.fit(points.toDoubleArray(), weights = weights.toDoubleArray(), nodes = 40, allowBranching = true)
val dark = Img(print.width, print.height, 3, FloatArray(print.width * print.height * 3))
showImgGrid("as a tree" to print.overlay(dark, 0.6).drawFLines(forest.widthFLines(1.0) + forest.toFLines(thickness = 2.5)),
    columns = 1, cellSide = 320)
```

## 3. 3 - Following points that move

<!-- field-box id="pc_live" index="2" -->

```edn [field-box id="pc_live" role="properties"]
{"id" "pc_live" "index" 2 "name" "3 - Following points that move" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - Following points that move" "_tombstone" false "frame" #afield/rect [60.0 260.0 420.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pc_live_code_.kt" "lines" 24 "characters" 1055 "sha256" "d0b25b20b44ebd51ccb40c5e344d07529dfe93b432b317c8d499c4473cdbcaf5"}]}
```

```kotlin [field-property box="pc_live" name="code" path="pc_live_code_.kt"]
import afield.geometry.curves.principal.*

// Launch this one (Alt-R). 2000 points scatter around a wave that travels and bends; one
// forest follows them, refined five iterations a frame. The graph is never regrown, so its
// nodes keep their identities from frame to frame.
val random = java.util.Random(3)
val offsets = DoubleArray(2000) { random.nextGaussian() * 4 }
val along = DoubleArray(2000) { random.nextDouble() }
fun points(time: Double) = DoubleArray(4000).also { p ->
    for (i in 0 until 2000) {
        val t = along[i]
        p[2 * i] = 20 + 360 * t
        p[2 * i + 1] = 110 + 50 * sin(5 * t + time) * cos(time * 0.3) + offsets[i] * (1 + t)
    }
}
val forest = PrincipalCurves.fit(points(0.0), nodes = 24)
val canvas = Img(400, 220, 3, FloatArray(400 * 220 * 3) { 0.06f })

loop {
    forest.setPoints(points(frameNumber / 30.0))
    forest.refine(5)
    showImgGrid("frame $frameNumber" to canvas.drawFLines([forest.pointsFLine(1.5)] + forest.widthFLines() + forest.toFLines(), scale = 2.0),
        columns = 1, cellSide = 600)
}
```

