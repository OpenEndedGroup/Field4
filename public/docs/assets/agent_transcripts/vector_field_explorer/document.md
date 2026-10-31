# Field Document: vector_field_explorer

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 1 - Swirls, sources and sinks

<!-- field-box id="vector_field_swirls" index="0" -->

```edn [field-box id="vector_field_swirls" role="properties"]
{"id" "vector_field_swirls" "index" 0 "name" "1 - Swirls, sources and sinks" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - Swirls, sources and sinks" "_tombstone" false "frame" #afield/rect [60.0 60.0 420.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "vector_field_swirls_code_.kt" "lines" 42 "characters" 1457 "sha256" "93b302e82ab82ed33802e915100ca2e7a6f0277de254eaa1f38515b2b6b7f3ef"}]}
```

```kotlin [field-property box="vector_field_swirls" name="code" path="vector_field_swirls_code_.kt"]
import afield.geometry.topology.vectorfield.*

// Each entry is (x, y, outflow, swirl): positive outflow is a source, negative a sink, swirl turns it.
// The slider scales every swirl: watch sources become spirals and saddles move.
val features = [
    [50.0, 45.0, 1.0, 0.6],
    [150.0, 40.0, -1.0, -0.8],
    [100.0, 100.0, 0.2, 1.2],
    [40.0, 115.0, -0.8, 0.0],
    [165.0, 115.0, 0.9, -0.3],
]

fun show(twist: Double) {
    val flow =
        Img.generateRGB(200, 150) { x, y ->
            var u = 0.0
            var v = 0.0
            for ((cx, cy, out, swirl) in features) {
                val dx = x - cx
                val dy = y - cy
                val k = exp(-(dx * dx + dy * dy) / 900.0)
                u += k * (out * dx - twist * swirl * dy)
                v += k * (out * dy + twist * swirl * dx)
            }
            Triple(u.toFloat(), v.toFloat(), 0f)
        }
    val topology = VectorFieldTopology.compute(flow, minStrength = 0.01)
    val black = Img(200, 150, 3, FloatArray(200 * 150 * 3))
    showImgGrid(
        "$topology" to
            black.drawFLines(
                topology.streamlines(spacing = 9.0, length = 14.0) +
                    topology.toFLines() +
                    topology.criticalPointsFLine(8.0),
                scale = 3.0,
            ),
        columns = 1,
        cellSide = 600,
    )
}
slider("swirl", min = 0.0, max = 3.0, step = 0.05, initialValue = 1.0) { show(it) }
show(1.0)
```

## 2. 2 - Ridges and valleys of a photograph

<!-- field-box id="vector_field_leaf" index="1" -->

```edn [field-box id="vector_field_leaf" role="properties"]
{"id" "vector_field_leaf" "index" 1 "name" "2 - Ridges and valleys of a photograph" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - Ridges and valleys of a photograph" "_tombstone" false "frame" #afield/rect [560.0 60.0 420.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "vector_field_leaf_code_.kt" "lines" 19 "characters" 947 "sha256" "122b0999d20ed7e870fb3265477923a7c21a128344bb4fd406e65818ea76c2c1"}]}
```

```kotlin [field-property box="vector_field_leaf" name="code" path="vector_field_leaf_code_.kt"]
import afield.geometry.topology.vectorfield.*

// The gradient of the leaf print's brightness runs uphill: its sinks (blue) are bright places, its
// sources (red) dark ones, and the separatrices from its saddles (white) are ridges and valleys.
val print = imgFromFile("public/docs/assets/vision/fline-viewer.jpg", 3).crop(78, 262, 128, 236)
val dark = Img(print.width, print.height, 3, FloatArray(print.width * print.height * 3))

fun show(blur: Double) {
    val topology = VectorFieldTopology.compute(VectorFieldTopology.gradientOf(print.luma().gaussianBlur(blur)))
    showImgGrid(
        "photograph" to print.scaleBy(2.0),
        "${topology.criticalPoints.size} points" to
            print.overlay(dark, 0.6).drawFLines(topology.toFLines() + topology.criticalPointsFLine(7.0), scale = 2.0),
        columns = 2,
        cellSide = 300,
    )
}
slider("blur", min = 3.0, max = 16.0, step = 0.5, initialValue = 7.0) { show(it) }
show(7.0)
```

