# Field Document: stipple_sheet

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. stipple sheet

<!-- field-box id="stipple_sheet" index="0" -->

```edn [field-box id="stipple_sheet" role="properties"]
{"id" "stipple_sheet" "index" 0 "name" "stipple sheet" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "stipple sheet" "_tombstone" false "frame" #afield/rect [80.0 80.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "stipple_sheet_code_.kt" "lines" 54 "characters" 2108 "sha256" "6297aea1174cfe1a8eeaf228864c981b5ec44bcd9b51260243a55870e922ce4d"}]}
```

```kotlin [field-property box="stipple_sheet" name="code" path="stipple_sheet_code_.kt"]
import afield.stdlib.loadStippleTransport

// Run this in a Field box. The small portable model needs no Python at runtime.
val field = loadStippleTransport("public/docs/assets/stipple_transport/two-images.json")
val canonical = field.sourceParticles(400, seed = 44)
val lattice = (0..6).flatMap { row ->
    val v = -1.0 + row / 3.0
    listOf(
        (0..24).map { vec(-1.0 + it / 12.0, v, 0.0) },
        (0..24).map { vec(v, -1.0 + it / 12.0, 0.0) },
    )
}
var transportTime = 1.0
var movieTime = 0.5
var integrationSteps = 24

fun drawSheet() {
    // Always deform canonical coordinates, so particle i retains its identity.
    val particles = field.transport(canonical, transportTime, movieTime, integrationSteps)
    val dots = FLine()
    particles.forEach { dots.moveTo(it * 180.0) }
    dots.pointed = true
    dots.pointSize = 2.5
    dots.color = vec(0.45, 0.85, 0.8, 1.0)
    val warped = lattice.map { row ->
        val f = FLine()
        field.transport(row, transportTime, movieTime, integrationSteps).forEachIndexed { i, p ->
            if (i == 0) f.moveTo(p * 180.0) else f.lineTo(p * 180.0)
        }
        f.color = vec(0.55, 0.65, 0.8, 0.65)
        f.thickness = 0.65
        f
    }
    lines(warped + dots, w = 800.0, h = 540.0, fitToSize = true)
    markdown("times", "transport time **$transportTime** · movie time **$movieTime** · $integrationSteps integration steps")
}

controlGroup("transport plane") {
    // Experimental ranges remain accessible; extrapolation is not trained.
    slider("transport time", min = -0.25, max = 1.4, step = 0.025, initialValue = transportTime) {
        transportTime = it
        drawSheet()
    }
    slider("movie time", min = -0.25, max = 1.25, step = 0.025, initialValue = movieTime) {
        movieTime = it
        drawSheet()
    }
    slider("integration steps", min = 1.0, max = 64.0, step = 1.0, initialValue = integrationSteps.toDouble()) {
        integrationSteps = it.toInt()
        drawSheet()
    }
}
drawSheet()
println("stipple sheet ready: ${field.parameterCount} parameters; independent transport/movie controls")
```

