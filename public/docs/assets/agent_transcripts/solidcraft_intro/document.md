# Field Document: solidcraft_intro

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. SolidCraft — a parameter changes the part

<!-- field-box id="solidcraft_compare" index="0" -->

```edn [field-box id="solidcraft_compare" role="properties"]
{"id" "solidcraft_compare" "index" 0 "name" "SolidCraft — a parameter changes the part" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "SolidCraft — a parameter changes the part" "_tombstone" false "frame" #afield/rect [80.0 80.0 500.0 110.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "solidcraft_compare_code_.kt" "lines" 43 "characters" 1847 "sha256" "48b4f0503a0b510bb47cc5752eb6a187555bc262056d60bfe7b1ee119cafc1b0"}]}
```

```kotlin [field-property box="solidcraft_compare" name="code" path="solidcraft_compare_code_.kt"]
import afield.geometry.solid.solidcraft.SolidCraftDesign
import afield.geometry.solid.solidcraft.SolidCraftLibrary

run {
    if (!SolidCraftLibrary.isAvailable) {
        display(
            "main",
            "<div style='padding:24px;background:#101318;color:#e6ecf4;font-family:Iosevka,monospace'>" +
                "SolidCraft is optional. Run <code>./gradlew buildSolidCraftNative</code>, restart Field, then run this box.</div>",
        )
        println(SolidCraftLibrary.describe())
        return@run
    }

    // Change this value and run again. The sketch is 40 × 30 mm; the first extrusion is
    // width / 2 deep, while the circular cut remains exactly 20 mm deep.
    val width = 50
    val tolerance = 0.1

    SolidCraftDesign.openBundledExample().use { part ->
        val original = part.summary().getJSONArray("bodies").getJSONObject(0)
        val before = part.mesh(tolerance = tolerance).toTriMesh().weld()
        part.setParameter("width", "$width mm")
        val changed = part.summary().getJSONArray("bodies").getJSONObject(0)
        val after = part.mesh(tolerance = tolerance).toTriMesh().weld()

        println(
            "B-rep volume: ${original.getDouble("volume").f1} → " + "${changed.getDouble("volume").f1} mm³",
        )
        println("STEP ${part.exportStep().size} bytes; STL ${part.exportStl().size} bytes")
        println("The hole is blind at width > 40 mm: its cut depth stays 20 mm.")

        showMeshGrid(
            "40 mm: through cut" to before,
            "$width mm: blind cut" to after,
            columns = 2,
            cellWidth = 380,
            // Look from the cut's side of the XY plane so the blind hole stays visible.
            view = MeshView(yawDegrees = 165.0, pitchDegrees = 14.0, wireframe = false),
            renderer = MeshRenderer.CPU,
        )
    }
}
```

