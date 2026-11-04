# Field Document: contour_trees_explorer

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 1 - A landscape and its contour tree

<!-- field-box id="contour_tree_landscape" index="0" -->

```edn [field-box id="contour_tree_landscape" role="properties"]
{"id" "contour_tree_landscape" "index" 0 "name" "1 - A landscape and its contour tree" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - A landscape and its contour tree" "_tombstone" false "frame" #afield/rect [60.0 60.0 420.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "contour_tree_landscape_code_.kt" "lines" 41 "characters" 1433 "sha256" "7344d2f52616f36885eda189e774eae23d0032d712f00d9a3bf4472a534f0d0d"}]}
```

```kotlin [field-property box="contour_tree_landscape" name="code" path="contour_tree_landscape_code_.kt"]
import afield.geometry.topology.trees.*

// Seven hills and three hollows. Drag the slider to prune branches shorter than its persistence.
val bumps = [
    Triple(40.0, 40.0, 1.0),
    Triple(150.0, 35.0, 0.8),
    Triple(100.0, 80.0, 0.6),
    Triple(30.0, 120.0, 0.7),
    Triple(170.0, 120.0, 0.9),
    Triple(120.0, 130.0, 0.5),
    Triple(70.0, 60.0, 0.45),
    Triple(100.0, 30.0, -0.5),
    Triple(60.0, 100.0, -0.4),
    Triple(150.0, 80.0, -0.45),
]
val field =
    Img.generate(200, 150) { x, y ->
        bumps.sumOf { (cx, cy, a) -> a * exp(-((x - cx).pow(2) + (y - cy).pow(2)) / 300.0) }.toFloat()
    }
val full = ContourTrees.contourTree(field)

fun show(persistence: Double) {
    val tree = full.simplify(persistence)
    val dots = fline {
        tree.nodes.forEach { moveTo(it.x, it.y) }
        pointed = true
        pointSize = 5.0
    }
    val plot =
        Img(200, 150, 3, FloatArray(200 * 150 * 3)).drawFLines(tree.plot(200.0, 150.0, margin = 15.0), scale = 2.0)
    showImgGrid(
        "field: ${tree.nodes.size} nodes" to
            field.normalized().colormap("viridis").drawFLines([dots], scale = 2.0, color = Vec4(1f, 1f, 1f, 1f)),
        "regions: ${tree.arcs.size} arcs" to tree.segmentationImage().scaleBy(2.0),
        "tree" to plot,
        columns = 3,
        cellSide = 300,
    )
}
slider("persistence", min = 0.0, max = 0.5, step = 0.005, initialValue = 0.03) { show(it) }
show(0.03)
```

## 2. 2 - A photograph's contour-tree regions

<!-- field-box id="contour_tree_photo" index="1" -->

```edn [field-box id="contour_tree_photo" role="properties"]
{"id" "contour_tree_photo" "index" 1 "name" "2 - A photograph's contour-tree regions" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - A photograph's contour-tree regions" "_tombstone" false "frame" #afield/rect [560.0 60.0 420.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "contour_tree_photo_code_.kt" "lines" 14 "characters" 650 "sha256" "b05561d670aab0eac3817dc18c2f6ddfb43fde4252041b6f5488a512313787d4"}]}
```

```kotlin [field-property box="contour_tree_photo" name="code" path="contour_tree_photo_code_.kt"]
import afield.geometry.topology.trees.*

// The leaf print's brightness, divided into the regions of its contour tree, lightly and strongly simplified.
val print = imgFromFile("public/docs/assets/vision/fline-viewer.jpg", 3).crop(78, 262, 128, 236)
val tree = ContourTrees.contourTree(print.luma().gaussianBlur(1.5))
val light = tree.simplify(persistence = 0.01)
val strong = tree.simplify(persistence = 0.05)
showImgGrid(
    "photograph" to print.scaleBy(2.0),
    "${light.arcs.size} arcs" to light.segmentationImage().scaleBy(2.0),
    "${strong.arcs.size} arcs" to strong.segmentationImage().scaleBy(2.0),
    columns = 3,
    cellSide = 260,
)
```

