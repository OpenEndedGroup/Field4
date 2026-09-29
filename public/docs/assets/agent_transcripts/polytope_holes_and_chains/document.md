# Field Document: polytope_holes_and_chains

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. 3 — every composite chains now

<!-- field-box id="polytope_chained_vocabulary" index="0" -->

```edn [field-box id="polytope_chained_vocabulary" role="properties"]
{"id" "polytope_chained_vocabulary" "index" 0 "name" "3 — every composite chains now" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 — every composite chains now" "_tombstone" false "frame" #afield/rect [80.0 360.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "polytope_chained_vocabulary_code_.kt" "lines" 46 "characters" 2323 "sha256" "11a5849687776fb5e0d747feec41cc5a8f71d1b0bce869c5f95c607ad06e47b1"}]}
```

```kotlin [field-property box="polytope_chained_vocabulary" name="code" path="polytope_chained_vocabulary_code_.kt"]
import afield.stdlib.polytope.*
import afield.stdlib.MeshView

// Until 2026-08-05 none of these compiled: the eight composites were declared on
// PolyhedralObject only, so cube().ambo() had no .bevel(), and needle, zip and ortho had no
// named function at all. Every operator is now on both receivers, so a chain reads the same
// wherever a step sits.
val chains: List<Pair<String, PolytopeTransformResult>> = listOf(
    "cube().ambo().join()" to cube().ambo().join(),
    "cube().ambo().needle()" to cube().ambo().needle(),
    "cube().ambo().zip()" to cube().ambo().zip(),
    "cube().ambo().ortho()" to cube().ambo().ortho(),
    "cube().ambo().expand()" to cube().ambo().expand(),
    "cube().ambo().snub()" to cube().ambo().snub(),
    "cube().ambo().meta()" to cube().ambo().meta(),
    "cube().ambo().bevel()" to cube().ambo().bevel(),
)

val view = MeshView(wireframe = false, distance = 2.4)
val cells = chains.map { (source, result) ->
    val topology = result.polyhedron.topology
    ThreeMeshPanel(
        label = source,
        mesh = result.polyhedron.canonicalize().polyhedron.toTriMesh(),
        facts = "asked ${result.notationHistory.joinToString("") { it.symbol.toString() }}" +
            " · ran ${result.history.joinToString("") { it.symbol.toString() }}" +
            " · ${topology.elements(0).size}v ${topology.elements(1).size}e ${topology.elements(2).size}f" +
            " · χ=${topology.eulerCharacteristic()}",
    )
}
threeGrid(cells, channel = "chains", name = "chains", columns = 4, height = 440, cellWidth = 260, view = view)

// The composite is recorded as what you asked for; the history is what actually ran. And a
// longer chain is just more of the same.
val long = cube().ambo().bevel().kis().dual()
println(
    """
    asked for: ${long.notationHistory.joinToString(" ") { it.displayName }}
    actually ran: ${long.history.joinToString(" ") { it.displayName }}
    result: ${long.polyhedron.topology.elements(0).size}v ${long.polyhedron.topology.elements(2).size}f,
        chi = ${long.polyhedron.topology.eulerCharacteristic()}
    a composite still refuses a single realization policy:
        ${runCatching { cube().ambo().transform(ConwayOperator.BEVEL, DualCentroidRealization) }.exceptionOrNull()?.message}
    """.trimIndent(),
)
viz("chain", long.summary())
```

## 2. 2 — the same plate, with the ring and without it

<!-- field-box id="polytope_plate_with_and_without" index="1" -->

```edn [field-box id="polytope_plate_with_and_without" role="properties"]
{"id" "polytope_plate_with_and_without" "index" 1 "name" "2 — the same plate, with the ring and without it" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 — the same plate, with the ring and without it" "_tombstone" false "frame" #afield/rect [80.0 220.0 430.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "polytope_plate_with_and_without_code_.kt" "lines" 66 "characters" 3388 "sha256" "36593b17d9300d57ed48d3ab64ef1c1981e001342d4784b6f9e85b0515c596c6"}]}
```

```kotlin [field-property box="polytope_plate_with_and_without" name="code" path="polytope_plate_with_and_without_code_.kt"]
import afield.stdlib.polytope.*
import afield.stdlib.MeshView
import afield.stdlib.Vec3

// ***** CHANGE THIS LINE ***** the hole's half-width; 0 or more, less than 1.
val hole = 0.45f

// A single square face, built by hand, optionally with one inner ring. This is the whole of
// the hole API from a caller's side: record the ring's edges as incidences of the same face,
// then declare the ring with addFaceHole. Winding is a convention — outer counter-clockwise
// about the normal, inner clockwise — and nothing downstream leans on it, because the
// tessellator decides which ring is the outer one geometrically.
fun plate(withRing: Boolean): PolyhedralObject {
    val outer = listOf(Vec3(-1f, -1f, 0f), Vec3(1f, -1f, 0f), Vec3(1f, 1f, 0f), Vec3(-1f, 1f, 0f))
    val inner = listOf(
        Vec3(-hole, -hole, 0f), Vec3(-hole, hole, 0f), Vec3(hole, hole, 0f), Vec3(hole, -hole, 0f),
    )
    // buildList, not `outer + inner`: a box has afield.graphics in scope, where
    // `List<Vec3> + List<Vec3>` is element-wise vector addition and returns four points,
    // not eight.
    val points = buildList { addAll(outer); if (withRing) addAll(inner) }
    val rings = if (withRing) listOf(listOf(0, 1, 2, 3), listOf(4, 5, 6, 7)) else listOf(listOf(0, 1, 2, 3))

    val builder = PolytopeTopologyBuilder(rank = 3)
    val vertices = points.map { builder.addElement(0) }
    val face = builder.addElement(2)
    for (ring in rings) for (i in ring.indices) {
        val edge = builder.addElement(1)
        builder.addIncidence(vertices[ring[i]], edge)
        builder.addIncidence(vertices[ring[(i + 1) % ring.size]], edge)
        builder.addIncidence(edge, face)
    }
    builder.setFaceCycle(face, rings.first().map { vertices[it] })
    rings.drop(1).forEach { ring -> builder.addFaceHole(face, ring.map { vertices[it] }) }
    builder.addIncidence(face, builder.addElement(3))
    return PolyhedralObject(builder.buildValidated(), Realization3(points))
}

// cullBackFaces, deliberately: a rim triangle wound the wrong way would be *missing* here
// exactly as it would be missing on a GPU, so this picture is a winding test as well as a
// hole test. The wireframe is on so the eight rim triangles can be counted.
val view = MeshView(
    yawDegrees = 0.0, pitchDegrees = 0.0, distance = 2.2,
    wireframe = true, cullBackFaces = true,
)
val cells = listOf(true, false).map { withRing ->
    val object3 = plate(withRing)
    val mesh = object3.toTriMesh()
    val face = object3.topology.elements(2).single()
    ThreeMeshPanel(
        label = if (withRing) "addFaceHole(face, ring)" else "the same plate, no ring",
        mesh = mesh,
        facts = "${object3.topology.elements(0).size}v ${object3.topology.elements(1).size}e" +
            " · holes ${object3.topology.faceHoles(face.id).size}" +
            " · ${mesh.triangleCount} triangles" +
            " · area ${"%.3f".format(4.0 - if (withRing) 4.0 * hole * hole else 0.0)}",
    )
}
threeGrid(cells, channel = "plates", name = "plates", columns = 2, height = 260, cellWidth = 300, view = view)
println(
    """
    left: one face, two boundary rings, ${plate(true).toTriMesh().triangleCount} triangles
    right: the identical outline with the ring dropped, ${plate(false).toTriMesh().triangleCount} triangles
    the filled area differs by exactly the ring: ${4.0 * hole * hole}
    """.trimIndent(),
)
```

## 3. 1 — a face with a hole, end to end

<!-- field-box id="polytope_face_with_a_hole" index="2" -->

```edn [field-box id="polytope_face_with_a_hole" role="properties"]
{"id" "polytope_face_with_a_hole" "index" 2 "name" "1 — a face with a hole, end to end" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 — a face with a hole, end to end" "_tombstone" false "frame" #afield/rect [80.0 80.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "polytope_face_with_a_hole_code_.kt" "lines" 72 "characters" 3170 "sha256" "321e87ddece3cc28cb27deb19fa7c09c2d6a2fdedaee46687de8a42ec0739fc9"}]}
```

```kotlin [field-property box="polytope_face_with_a_hole" name="code" path="polytope_face_with_a_hole_code_.kt"]
import afield.stdlib.polytope.*

// ***** CHANGE THIS LINE ***** the window's half-width, anywhere inside (0, 1).
val solid = cubeWithWindow(side = 0.45f)

val topology = solid.topology
val windowed = topology.elements(2).single { topology.faceHasHoles(it.id) }
val geometry = solid.triangulate()
val rimTriangles = (0 until geometry.triangleCount).count { geometry.sourceFace(it) == windowed.id }

// Everything a holed face refuses, and the message it refuses with. These are values, not
// accidents: a Conway operator, a dualization and an OFF export are all defined for closed
// manifolds, and a face with a hole is a surface with boundary.
val refusals = listOf<Pair<String, () -> Any>>(
    "topology.dual()" to { topology.dual() },
    "solid.bevel()" to { solid.bevel() },
    "solid.toOff()" to { solid.toOff() },
    "triangulate(FanFaceTriangulator)" to { solid.triangulate(FanFaceTriangulator) },
).map { (name, call) ->
    name to (runCatching { call() }.exceptionOrNull()?.message ?: "DID NOT THROW")
}

println(
    """
    face ${windowed.id.value} boundary: outer ${topology.faceCycle(windowed.id).size} vertices,
        holes ${topology.faceHoles(windowed.id).map { it.size }}, rings ${topology.faceRings(windowed.id).size}
    chi counting elements = ${topology.eulerCharacteristic()}, counting cells = ${topology.cellularEulerCharacteristic()}
        (a disk with one hole is 1 - 1, so a sphere with a window is 1)
    closed manifold = ${topology.validate(checkManifold = true).isValid}, and the only complaints are
        ${topology.validate(checkManifold = true).issues.size} one-sided window edges
    triangles cut from the windowed face = $rimTriangles (a solid square would be 2)
    """.trimIndent(),
)
println("\nrefused, by name:")
refusals.forEach { (call, message) -> println("  $call -> $message") }
println("\norbit the view below; the window is a window, and the far wall is behind it")

// One FLine per face. A face's holes are further closed contours on that same line — one
// line is still one face, and a hole is not a face — which is what the viewer's own
// outer-ring-plus-holes grouping then subtracts.
val faceLines = solid.toFaceFLines { face, line ->
    val holes = topology.faceHoles(face.id)
    line.filled = true
    line.stroked = true
    line.thickness = if (holes.isEmpty()) 1.1 else 2.0
    line.color = if (holes.isEmpty()) vec(0.26, 0.42, 0.58, 0.70) else vec(1.00, 0.62, 0.24, 0.88)
    line.hoverColor = vec(0.96, 0.98, 1.00, 0.98)
    line.onClick = { hit ->
        markdown(
            "face detail",
            """
            **face ${face.id.value}**

            - outer ring: `${topology.faceCycle(face.id).size}` vertices
            - holes: `${holes.map { it.size }}`
            - triangles: `${(0 until geometry.triangleCount).count { geometry.sourceFace(it) == face.id }}`
            - clicked at: `${hit.position}`
            """.trimIndent(),
        )
    }
}

threeLines(
    faceLines,
    channel = "polytope-holes",
    name = "cube-with-window",
    height = 520,
    autoFrame = true,
    editable = false,
)
displayOpen("polytope-holes")
viz("topology", topology.summary())
```

