# Field Document: rhino_round_trip

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. write, read back, measure

<!-- field-box id="rhino_round_trip" index="0" -->

```edn [field-box id="rhino_round_trip" role="properties"]
{"id" "rhino_round_trip" "index" 0 "name" "write, read back, measure" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "write, read back, measure" "_tombstone" false "frame" #afield/rect [40.0 40.0 420.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rhino_round_trip_code_.kt" "lines" 129 "characters" 5830 "sha256" "7073ea93a8bdb1a86b6d2aedea12e3a3b85e99c9f4a44eae93b4e22cb89d57c3"}]}
```

```kotlin [field-property box="rhino_round_trip" name="code" path="rhino_round_trip_code_.kt"]
import afield.geometry.mesh.TriMesh
import afield.geometry.rhino.*
import kotlin.math.*

// Write one of each kind of object to a .3dm, read it back, and measure what survived against
// the exact shapes; without the openNURBS wrapper, say how to build it
if (!Rhino3dm.isAvailable) {
    println(Rhino3dm.describe())
} else {
    fun square(x: Double, y: Double, size: Double) =
        FLine()
            .moveTo(x, y, 0.0)
            .lineTo(x + size, y, 0.0)
            .lineTo(x + size, y + size, 0.0)
            .lineTo(x, y + size, 0.0)
            .close()

    // Four corners, linear red, grey and white.
    val quad = TriMesh(
        floatArrayOf(60f, 0f, 0f, 64f, 0f, 0f, 64f, 3f, 0f, 60f, 3f, 0f),
        intArrayOf(0, 1, 2, 0, 2, 3),
        colors = floatArrayOf(1f, 0f, 0f, 0.2f, 0.1f, 0.05f, 0.5f, 0.5f, 0.5f, 1f, 1f, 1f),
    )

    val dir = java.nio.file.Files.createTempDirectory("rhino-roundtrip").toFile()
    val file = Rhino3dm.save(java.io.File(dir, "roundtrip.3dm"), units = "millimeters", version = 7) {
        layer("Solids", vec(0.27, 0.51, 0.78, 1.0))
        layer("Solids::Trimmed", vec(0.78, 0.47, 0.16, 1.0))
        layer("SubD", vec(0.35, 0.67, 0.35, 1.0))
        layer("Flat", vec(0.6, 0.6, 0.6, 1.0))
        brep(RhinoBreps.sphere(vec(0.0, 0.0, 0.0), 10.0), layer = "Solids", name = "sphere")
        brep(RhinoBreps.cylinder(vec(30.0, 0.0, -10.0), 5.0, 20.0), layer = "Solids", name = "cylinder")
        brep(RhinoBreps.box(vec(-30.0, -5.0, -5.0), vec(-20.0, 5.0, 5.0)), layer = "Solids", name = "box")
        brep(
            RhinoBreps.plate(vec(0.0, -30.0, 0.0), 20.0, [Triple(0.0, -30.0, 5.0)]),
            layer = "Solids::Trimmed",
            name = "plate with hole",
        )
        val (points, faces, creases) = RhinoBreps.subdBox(vec(25.0, -35.0, -5.0), vec(35.0, -25.0, 5.0), 2, false)
        subd(points, faces, creases, layer = "SubD", name = "subd box")
        mesh(quad, layer = "Flat", name = "coloured quad")
        curve(square(-35.0, -40.0, 10.0), layer = "Flat", name = "outline")
        val withHole = square(-35.0, 20.0, 10.0)
        withHole.nodes.addAll(square(-32.0, 23.0, 4.0).nodes)
        hatch(withHole, layer = "Flat", name = "hatch")
        block("fin") {
            mesh(
                TriMesh(floatArrayOf(0f, 0f, 0f, 4f, 0f, 0f, 0f, 0f, 6f), intArrayOf(0, 1, 2)),
                layer = "Flat",
                name = "fin skin",
            )
        }
        instance("fin", vec(50.0, 20.0, 0.0), layer = "Flat", name = "fin A")
        instance("fin", vec(56.0, 20.0, 0.0), layer = "Flat", name = "fin B")
    }
    val model = Rhino3dm.load(file, meshing = RhinoBrepMeshing.EVALUATE)

    fun area(m: TriMesh) =
        (0 until m.triangleCount).sumOf { t ->
            val (a, b, c) =
                (0..2).map { k ->
                    m.indices[3 * t + k].let {
                        vec(m.positions[3 * it], m.positions[3 * it + 1], m.positions[3 * it + 2])
                    }
                }
            Vec3(b).sub(a).cross(Vec3(c).sub(a)).length() / 2.0
        }
    fun volume(m: TriMesh) =
        (0 until m.triangleCount).sumOf { t ->
            val (a, b, c) =
                (0..2).map { k ->
                    m.indices[3 * t + k].let {
                        vec(m.positions[3 * it], m.positions[3 * it + 1], m.positions[3 * it + 2])
                    }
                }
            Vec3(a).dot(Vec3(b).cross(c)) / 6.0
        }
    fun openEdges(m: TriMesh): Int {
        val id = HashMap<Triple<Float, Float, Float>, Int>()
        val ids = IntArray(m.vertexCount) {
            id.getOrPut(Triple(m.positions[3 * it], m.positions[3 * it + 1], m.positions[3 * it + 2])) { id.size }
        }
        val count = HashMap<Pair<Int, Int>, Int>()
        for (t in 0 until m.triangleCount) for (e in 0..2) {
            val a = ids[m.indices[3 * t + e]]
            val b = ids[m.indices[3 * t + (e + 1) % 3]]
            count.merge(minOf(a, b) to maxOf(a, b), 1, Int::plus)
        }
        return count.values.count { it == 1 }
    }
    val exact = mapOf(
        "sphere" to (400 * PI to 4000 * PI / 3),
        "cylinder" to (250 * PI to 500 * PI),
        "box" to (600.0 to 1000.0),
        "plate with hole" to (400 - 25 * PI to null),
    )
    fun f(x: Double) = x.f1
    val rows =
        model.objects.joinToString("\n") { o ->
            when (o) {
                is RhinoMesh -> {
                    val m = o.mesh
                    val (a, v) = exact[o.name] ?: (null to null)
                    val vol = if (openEdges(m) == 0) f(volume(m)) + (v?.let { " of " + f(it) } ?: "") else "open"
                    "| ${o.name} | ${o.layer?.path} | ${m.triangleCount} | ${f(area(m))}${a?.let { " of " + f(it) } ?: ""} | $vol | ${openEdges(m)} |"
                }
                is RhinoCurve ->
                    "| ${o.name} | ${o.layer?.path} | ${if (o.isHatch) "hatch" else "curve"}, ${o.line.nodes.size} nodes | | | |"
                else -> "| ${o.name} | ${o.layer?.path} | ${o.type} | | | |"
            }
        }
    val back = model.meshes.single { it.name == "coloured quad" }.mesh.colors
    val sent = quad.colors!!
    markdown(
        "readout",
        """
    | object read back | layer | triangles | area mm² (exact) | volume mm³ (exact) | open edges |
    |---|---|---|---|---|---|
    $rows

    ${model.objects.size} objects on ${model.layers.size} layers from 10 written; the two block placements
    came back as their member, `block` = ${model.meshes.filter { it.block != null }.map { it.block }.toSet()}.
    Vertex colours: largest error ${back?.let { (sent.indices.maxOf { abs(sent[it] - back[it]) }).f4 } ?: "none came back"}.
    """,
        width = 820,
    )
    three(height = 380) { rhino(model) }
    dir.deleteRecursively()
}
```

