# Field Document: geometry_from_disk

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. Out to a file and back — what each format keeps

<!-- field-box id="disk_roundtrip" index="0" -->

### Narrative

From `code`

What comes back from a file is not what went out, and this is the round trip that shows it.
Nothing here writes into the repository: a temp directory, deleted at the end.

```edn [field-box id="disk_roundtrip" role="properties"]
{"id" "disk_roundtrip" "index" 0 "name" "Out to a file and back — what each format keeps" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Out to a file and back — what each format keeps" "_tombstone" false "frame" #afield/rect [80.0 210.0 400.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "disk_roundtrip_code_.kt" "lines" 52 "characters" 2571 "sha256" "b7df8f365974edea44df9db40d0329c04f92fd6b931ec6e18c942f29fdc34681"}]}
```

```kotlin [field-property box="disk_roundtrip" name="code" path="disk_roundtrip_code_.kt"]
// What comes back from a file is not what went out, and this is the round trip that shows it.
// Nothing here writes into the repository: a temp directory, deleted at the end.
val dir = java.nio.file.Files.createTempDirectory("mesh-round-trip").toFile()
val source = icosphereMesh(3, 1.0).withColor(0.3, 0.7, 1.0)

val objFile = java.io.File(dir, "ball.obj").also { source.saveObj(it) }
val stlFile = java.io.File(dir, "ball.stl").also { source.saveStl(it) }
println("ball.obj  ${objFile.length()} bytes")
println("ball.stl  ${stlFile.length()} bytes   (binary STL is exactly 84 + 50 x ${source.triangleCount})")
println("ASCII STL would be ${source.toStlAscii().length} bytes — about five times binary")
println()

val viaObj = meshFromFile(objFile.path)
val viaStl = meshFromFile(stlFile.path)

// Change this and watch the last row move: too small and the soup stays soup, too large and
// distinct features merge. mesh.meanEdgeLength() is the scale to judge it against.
val weldTolerance = 1e-5
val welded = viaStl.weld(weldTolerance)

// A lambda rather than a local `fun`: inside a box `println` is rewritten onto the box's own
// receiver, and a nested function does not have it.
val row = { label: String, m: TriMesh ->
    "%-18s %5d verts %5d tris  normals=%-5s colours=%-5s chi=%-5d %-11s volume %.6f".format(
        label, m.vertexCount, m.triangleCount, m.hasNormals, m.hasColors,
        m.topology().eulerCharacteristic,
        if (m.isWatertight()) "watertight" else "open",
        m.signedVolume())
}
println(row("source", source))
println(row("via ball.obj", viaObj))
println(row("via ball.stl", viaStl))
println(row("via ball.stl, welded", welded))
println()
println("mean edge length ${"%.5f".format(source.meanEdgeLength())} — the scale that makes")
println("$weldTolerance a safe weld tolerance rather than a guess")
println()
println("OBJ indexes positions, normals and uvs separately, so it keeps the structure and")
println("the normals — and drops the colours, because OBJ has no standard for them.")
println("STL has no vertex sharing at all: 3 x ${source.triangleCount} unshared vertices,")
println("every edge a boundary, and no per-vertex normal to recover when you weld it back.")
println("The volume is identical down every route. If the SHAPE ever changes on a round")
println("trip, that is a bug rather than a format.")

showMeshGrid(
    "source — coloured" to source,
    "via ball.obj — colours gone" to viaObj,
    "via ball.stl, welded — normals gone" to welded,
    columns = 3,
    cellWidth = 235,
)
dir.deleteRecursively()
```

## 2. A real GLB, straight into an output area

<!-- field-box id="disk_glb" index="1" -->

### Narrative

From `code`

Field has four things that read geometry off disk — stdlib's OBJ/STL reader, PlySupport,
the Assimp-backed glTF loader, and the Fabricate PBR path — and one renderer that can
rasterise a mesh with no GPU. meshFromAnyFile is the join: it dispatches by extension and
always hands back a TriMesh, which is the one type that renderer takes.

Change this to any mesh file you have. .glb .gltf .fbx .dae .3ds all go through Assimp,
.ply through PlySupport, .obj and .stl through the dependency-free stdlib reader.

```edn [field-box id="disk_glb" role="properties"]
{"id" "disk_glb" "index" 1 "name" "A real GLB, straight into an output area" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "A real GLB, straight into an output area" "_tombstone" false "frame" #afield/rect [80.0 80.0 400.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "disk_glb_code_.kt" "lines" 43 "characters" 2258 "sha256" "426292e0b62668095cead586c1812f4b33af7d9921900e865736404b76f339fc"}]}
```

```kotlin [field-property box="disk_glb" name="code" path="disk_glb_code_.kt"]
// Field has four things that read geometry off disk — stdlib's OBJ/STL reader, PlySupport,
// the Assimp-backed glTF loader, and the Fabricate PBR path — and one renderer that can
// rasterise a mesh with no GPU. meshFromAnyFile is the join: it dispatches by extension and
// always hands back a TriMesh, which is the one type that renderer takes.
//
// Change this to any mesh file you have. .glb .gltf .fbx .dae .3ds all go through Assimp,
// .ply through PlySupport, .obj and .stl through the dependency-free stdlib reader.
val path = "public/docs/assets/fabricate_demo/ToyCar.glb"

val file = java.io.File(path)
if (!file.isFile) {
    println("no such file: ${file.absolutePath}")
    println("point `path` at a mesh file of your own — this default is relative to the")
    println("repository root, so it resolves when Field is started from there.")
} else {
    println("${file.name}: ${file.length()} bytes on disk")

    val mesh = meshFromAnyFile(path)
    println(mesh.summary())
    println()

    // Read those numbers rather than trusting the picture:
    //  - 108,936 triangles over three mesh/material buckets (ToyCar, Fabric, Glass), each
    //    baked through its node's world transform, so the model arrives assembled.
    //  - extent 0.073 x 0.029 x 0.074 — the file is in metres and it is a 7 cm toy.
    //    MeshView.distance is in bounding-sphere radii, so the camera frames it anyway.
    //  - chi = 1909, open, 2782 boundary loops. NOT a defect: an artist's model is a shell,
    //    and every UV seam and hard edge is a real cut in the surface. Only reach for
    //    repaired() if you actually need watertightness (a volume, a boolean, a printer).
    val b = mesh.bounds()
    println("extent %.4f x %.4f x %.4f metres".format(
        b.max.x - b.min.x, b.max.y - b.min.y, b.max.z - b.min.z))
    println("watertight ${mesh.isWatertight()} — expect false, and see the comment above")
    println()
    println("try: wireframe = true below, and watch 108,936 triangles become a grey mat —")
    println("     which is why the crease set, not the wireframe, is the readable drawing")

    showMesh(
        mesh,
        view = MeshView(width = 560, height = 420, wireframe = false),
        title = file.name,
    )
}
```

## 3. PLY — the format that carries colour

<!-- field-box id="disk_ply" index="2" -->

### Narrative

From `code`

PLY is what photogrammetry and scanners emit, and the reason is per-vertex colour, which
neither OBJ nor STL can carry. Four corners, two triangles, small enough to read.
Change the six colour triples and re-run.

```edn [field-box id="disk_ply" role="properties"]
{"id" "disk_ply" "index" 2 "name" "PLY — the format that carries colour" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "PLY — the format that carries colour" "_tombstone" false "frame" #afield/rect [80.0 340.0 400.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "disk_ply_code_.kt" "lines" 55 "characters" 2128 "sha256" "a53b187104751e75069e495f2755f5e314e0493e153ffd963a0be5f6d8fb9fc5"}]}
```

```kotlin [field-property box="disk_ply" name="code" path="disk_ply_code_.kt"]
// PLY is what photogrammetry and scanners emit, and the reason is per-vertex colour, which
// neither OBJ nor STL can carry. Four corners, two triangles, small enough to read.
// Change the six colour triples and re-run.
val dir = java.nio.file.Files.createTempDirectory("ply-demo").toFile()
val file = java.io.File(dir, "square.ply")
file.writeText(
    """
    ply
    format ascii 1.0
    element vertex 4
    property float x
    property float y
    property float z
    property uchar red
    property uchar green
    property uchar blue
    element face 2
    property list uchar int vertex_indices
    end_header
    0 0 0 255 40 40
    1 0 0 40 255 40
    1 1 0 60 60 255
    0 1 0 255 235 40
    3 0 1 2
    3 0 2 3
    """.trimIndent() + "\n"
)

// PlySupport.loadPly is parsing plus memAlloc: no renderer, no Vulkan, no Box context. Only
// applyToMesh (the GPU path) needs any of that.
val model = PlySupport.loadPly(file.path)
val b = model.buffers
println("vertices ${b.vertexCount}, indices ${b.indexCount}, colours ${b.hasColors}, " +
    "normals in the file ${b.hasNormals}, point cloud ${b.isPointCloud}")
println("packed as ${b.vertexData.capacity() / (b.vertexCount * 4)} floats per vertex: " +
    "[pos.xyz][normal.xyz][color.rgba] — ordinary host memory, which is why the bridge below")
println("is an unpack rather than a second parser")
println()

val mesh = model.toTriMesh()
println("as a TriMesh: ${mesh.summary()}")
println("colours survived: ${mesh.hasColors}; vertex 0 is ${mesh.color(0)}")
println("normals dropped: ${!mesh.hasNormals}")
println()
println("that last line is a decision, not an accident: loadPly writes (0, 0, 1) for every")
println("vertex of a file with no nx/ny/nz, which is a WRONG normal rather than a missing")
println("one — it would shade the whole model as though it faced the camera. toTriMesh drops")
println("them when the file had none, and the render bridges crease-shade instead.")

showMesh(
    mesh,
    view = MeshView(yawDegrees = 0.0, pitchDegrees = 0.0, width = 420, height = 315),
    title = "square.ply — per-vertex colours",
)
dir.deleteRecursively()
```

