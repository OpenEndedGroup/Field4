# Field Document: vision_geometry_desk_preview

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. Vision geometry — desk preview

<!-- field-box id="agent_vision_geometry_desk_preview" index="0" -->

```edn [field-box id="agent_vision_geometry_desk_preview" role="properties"]
{"id" "agent_vision_geometry_desk_preview" "index" 0 "name" "Vision geometry — desk preview" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Vision geometry — desk preview" "_tombstone" false "frame" #afield/rect [60.0 60.0 460.0 300.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_vision_geometry_desk_preview_code_.kt" "lines" 70 "characters" 2985 "sha256" "385d2650eface64ff78bb3bb02fa038dbffd84f7590599bf58b0cecb3cc590fb"}]}
```

```kotlin [field-property box="agent_vision_geometry_desk_preview" name="code" path="agent_vision_geometry_desk_preview_code_.kt"]
import afield.graphics.VisionGeometryPreview
import afield.graphics.previewVisionGeometry

// Exactly what you would paste into a `// {{ visionmetal }}` box — a lantern built from
// the shape helpers and a hand-written ring of quads on top.
val program = """
    // {{ visionmetal uniforms=spin }}
    import kotlin.math.cos
    import kotlin.math.sin

    onStart {
        geometry {
            color(0.95, 0.6, 0.2)
            sphere(0.11, segments = 28, rings = 18, cy = 0.06, cz = -1.2)

            color(0.35, 0.7, 1.0)
            tube(
                (0..40).map { i ->
                    val t = i / 40.0
                    val a = t * 6.2831853
                    p(0.17 * sin(a), -0.1, -1.2 + 0.17 * cos(a))
                },
                radius = 0.012,
                sides = 8,
            )

            // The raw level: eight uprights, each a quad stitched from four indices that
            // vertex() handed back, wound counter-clockwise seen from outside.
            //
            // Each upright is a single flat quad, and a flat quad has one side — so in the
            // preview (as in the headset) you see the four facing you and not the four
            // facing away. Give each one a back face, or build it as a thin cube, if you
            // want it visible from everywhere.
            color(0.75, 0.75, 0.8)
            for (i in 0 until 8) {
                val a = i * 6.2831853 / 8.0
                val nx = sin(a)
                val nz = cos(a)
                val tx = cos(a) * 0.012
                val tz = -sin(a) * 0.012
                normal(nx, 0.0, nz)
                val b0 = vertex(0.17 * nx - tx, -0.1, -1.2 + 0.17 * nz - tz)
                val b1 = vertex(0.17 * nx + tx, -0.1, -1.2 + 0.17 * nz + tz)
                val b2 = vertex(0.17 * nx + tx, 0.06, -1.2 + 0.17 * nz + tz)
                val b3 = vertex(0.17 * nx - tx, 0.06, -1.2 + 0.17 * nz - tz)
                quad(b0, b1, b2, b3)
            }
        }
        onFrame { frame -> uniform("spin", frame.time * 0.5) }
    }
""".trimIndent()

// This really compiles the Kotlin above to JavaScript and really runs it; the mesh drawn
// below is the packed array Metal would have received. It takes a second or two.
val capture = try {
    previewVisionGeometry(source = program)
} catch (missing: VisionGeometryPreview.NodeUnavailable) {
    // The preview runs the box's real compiled program, so there is nothing honest to draw
    // without node. Say so rather than inventing a picture.
    println("geometry preview needs node on PATH: " + missing.message)
    null
}

capture?.mesh()?.let { mesh ->
    println("built " + mesh.vertexCount + " vertices, " + mesh.triangleCount + " triangles")
    val b = mesh.bounds()
    println("extent " + "%.3f".format(b[3] - b[0]) + " x " + "%.3f".format(b[4] - b[1]) +
        " x " + "%.3f".format(b[5] - b[2]) + " m")
    println("winding agreement " + "%.0f".format(mesh.windingAgreement() * 100) + "%")
}
```

## 2. … and the same mesh wound backwards

<!-- field-box id="agent_vision_geometry_desk_preview_inverted" index="1" -->

```edn [field-box id="agent_vision_geometry_desk_preview_inverted" role="properties"]
{"id" "agent_vision_geometry_desk_preview_inverted" "index" 1 "name" "… and the same mesh wound backwards" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "… and the same mesh wound backwards" "_tombstone" false "frame" #afield/rect [60.0 400.0 460.0 260.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_vision_geometry_desk_preview_inverted_code_.kt" "lines" 44 "characters" 1688 "sha256" "fe209273b373c2c0b20007d5e5bf40eb8121d0b9098a9d235e391b167c3fd975"}]}
```

```kotlin [field-property box="agent_vision_geometry_desk_preview_inverted" name="code" path="agent_vision_geometry_desk_preview_inverted_code_.kt"]
import afield.graphics.VisionGeometryPreview
import afield.graphics.previewVisionGeometry

// A ring of quads whose corners are given in the other order. Every normal still points
// outward; only the winding changed. The head culls back faces, so on the device this is a
// ring you can only see from the inside — and from any normal viewing position, nothing.
val inverted = """
    // {{ visionmetal }}
    import kotlin.math.cos
    import kotlin.math.sin

    onStart {
        geometry {
            color(0.9, 0.35, 0.35)
            for (i in 0 until 24) {
                val a = i * 6.2831853 / 24.0
                val nx = sin(a)
                val nz = cos(a)
                val tx = cos(a) * 0.024
                val tz = -sin(a) * 0.024
                normal(nx, 0.0, nz)
                val b0 = vertex(0.2 * nx - tx, -0.08, -1.2 + 0.2 * nz - tz)
                val b1 = vertex(0.2 * nx + tx, -0.08, -1.2 + 0.2 * nz + tz)
                val b2 = vertex(0.2 * nx + tx, 0.08, -1.2 + 0.2 * nz + tz)
                val b3 = vertex(0.2 * nx - tx, 0.08, -1.2 + 0.2 * nz - tz)
                quad(b3, b2, b1, b0)   // <- the same four corners, reversed
            }
        }
    }
""".trimIndent()

val capture = try {
    previewVisionGeometry(source = inverted)
} catch (missing: VisionGeometryPreview.NodeUnavailable) {
    println("geometry preview needs node on PATH: " + missing.message)
    null
}

capture?.mesh()?.let { mesh ->
    val agreement = mesh.windingAgreement()
    println("winding agreement " + "%.0f".format(agreement * 100) + "% — " +
        (if (agreement < 0.5) "wound inside-out; the head would cull this away"
         else "consistent"))
}
```

