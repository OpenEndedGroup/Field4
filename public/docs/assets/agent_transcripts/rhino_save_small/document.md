# Field Document: rhino_save_small

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "watchdog" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. saved meshes vs evaluated

<!-- field-box id="rhino_save_small_draw" index="0" -->

```edn [field-box id="rhino_save_small_draw" role="properties"]
{"id" "rhino_save_small_draw" "index" 0 "name" "saved meshes vs evaluated" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "saved meshes vs evaluated" "_tombstone" false "frame" #afield/rect [40.0 40.0 420.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rhino_save_small_draw_code_.kt" "lines" 16 "characters" 754 "sha256" "61b3de6d3808297a5f2b0f1b078d4d19372623cecfbb219a5c8bd96d884919f3"}]}
```

```kotlin [field-property box="rhino_save_small_draw" name="code" path="rhino_save_small_draw_code_.kt"]
import afield.geometry.rhino.*

// A "Save Small" .3dm: NURBS Breps and SubDs with no render meshes, drawn as Rhino's saved
// meshes alone would give them, then tessellated from their exact surfaces
if (!Rhino3dm.isAvailable) {
    println(Rhino3dm.describe())
} else {
    val dir = java.nio.file.Files.createTempDirectory("rhino-nurbs").toFile()
    val file = Rhino3dm.writeNurbsSample(java.io.File(dir, "save_small.3dm"))
    val saved = Rhino3dm.load(file, meshing = RhinoBrepMeshing.SAVED)
    val evaluated = Rhino3dm.load(file)
    println(evaluated)
    three(channel = "saved", name = "saved", height = 360) { rhino(saved) }
    three(channel = "evaluated", name = "evaluated", height = 360) { rhino(evaluated) }
    dir.deleteRecursively()
}
```

## 2. area against the exact shapes

<!-- field-box id="rhino_save_small_measure" index="1" -->

```edn [field-box id="rhino_save_small_measure" role="properties"]
{"id" "rhino_save_small_measure" "index" 1 "name" "area against the exact shapes" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "area against the exact shapes" "_tombstone" false "frame" #afield/rect [40.0 200.0 420.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rhino_save_small_measure_code_.kt" "lines" 28 "characters" 1148 "sha256" "794063d793cd69195edc5d445510a69644e499d69fddeefca2e15628639bba75"}]}
```

```kotlin [field-property box="rhino_save_small_measure" name="code" path="rhino_save_small_measure_code_.kt"]
import afield.geometry.rhino.*

// How close the tessellations are to the shapes the file describes: area against the analytic
// value, at the default tolerance and at a ten times finer one
if (!Rhino3dm.isAvailable) {
    println(Rhino3dm.describe())
} else {
    val dir = java.nio.file.Files.createTempDirectory("rhino-nurbs").toFile()
    val file = Rhino3dm.writeNurbsSample(java.io.File(dir, "save_small.3dm"))
    val exact = mapOf(
        "sphere" to 4 * PI * 100,
        "capped cylinder" to 2 * PI * 5 * 20 + 2 * PI * 25,
        "plate with hole" to 400 - PI * 25,
        "creased SubD" to 600.0,
    )
    for (tolerance in [null, 0.003]) {
        val model = Rhino3dm.load(file, tolerance = tolerance)
        println("tolerance ${tolerance ?: "default"}")
        for ((name, area) in exact) {
            val mesh = model.meshes.single { it.name == name }.mesh
            val error = (mesh.surfaceArea() - area) / area * 100
            println(
                "  ${name.padEnd(16)} ${mesh.triangleCount.toString().padStart(6)} triangles, area off by ${error.f3}%",
            )
        }
    }
    dir.deleteRecursively()
}
```

