# Field Document: polytope_pick_lineage

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. Point at a face

<!-- field-box id="polytope_pick" index="0" -->

```edn [field-box id="polytope_pick" role="properties"]
{"id" "polytope_pick" "index" 0 "name" "Point at a face" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Point at a face" "_tombstone" false "frame" #afield/rect [80.0 80.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "polytope_pick_code_.kt" "lines" 59 "characters" 2040 "sha256" "4ed38275374e6ebc2150d8edd8aa34b03245fcb20b0b8e3b7a9812b1d1b37ec8"}]}
```

```kotlin [field-property box="polytope_pick" name="code" path="polytope_pick_code_.kt"]
import afield.geometry.mesh.MeshView
import afield.geometry.polytope.ElementId
import afield.geometry.polytope.NormalPolicy
import afield.geometry.polytope.canonicalConway
import afield.geometry.polytope.triangulate
import afield.graphics.interaction.PickParts
import afield.graphics.interaction.pickScene
import afield.graphics.interaction.rayCamera
import afield.ui.visualization.showPointer

val chain = canonicalConway("bD")
fun sceneWith(normals: NormalPolicy) =
    chain.polyhedron.triangulate(normals = normals).let { geometry ->
        pickScene { add("Beveled dodecahedron", geometry.mesh, parts = PickParts.fromTriangulated(geometry)) }
    }
val flat = sceneWith(NormalPolicy.Flat)
val smooth = sceneWith(NormalPolicy.Crease(180.0))
val view = MeshView(width = 530, height = 340)
val camera = view.rayCamera(flat.mergedMesh())

var x = view.width / 2.0
var y = view.height / 2.0
var smoothNormals = false

fun drawPick() {
    val scene = if (smoothNormals) smooth else flat
    val hit = showPointer(scene, camera, vec(x, y), view)
    val face = hit?.externalId?.let(::ElementId)
    val source = face?.let { chain.sourceElements(it).single() }
    val report = if (face == null || source == null) {
        "No face at this pixel."
    } else {
            val seedPart = when (source.rank) {
                0 -> "vertex"
                1 -> "edge"
                else -> "pentagon"
            }
            val sides = chain.polyhedron.topology.faceCycle(face).size
            "$sides-sided face ${face.value} came from a seed $seedPart."
        }
    markdown("lineage", report)
    println(report)
}

controlGroup("Pointer") {
    slider("Horizontal pixel", min = 0.0, max = view.width - 1.0, step = 1.0, initialValue = x) {
        x = it
        drawPick()
    }
    slider("Vertical pixel", min = 0.0, max = view.height - 1.0, step = 1.0, initialValue = y) {
        y = it
        drawPick()
    }
    checkbox("Smooth normals", smoothNormals) {
        smoothNormals = it
        drawPick()
    }
}
drawPick()
```

