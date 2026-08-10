# Geometry from disk

*You have a mesh file. You want to see it in Field, and then compute with it. This page is the whole
route, including the parts that do not work.*

The short answer is one line, and it is the same line for every format:

```diffstatic {data-lang=kotlin}
showMesh("/path/to/thing.glb")
```

That reads the file, assembles it, and pushes a shaded, z-buffered, wireframed render into the box's
output area with the mesh's measurements printed underneath. Keep the mesh instead of the picture and
everything in the [mesh family](mesh_examples.md) applies — weld it, measure its volume, decimate it,
shoot a ray at it, write it back out as something else.

The long answer is worth reading, because Field has **four** separate things that read geometry off
disk, they were built for different reasons, and only some of them can show you the result.

## What reads what

| you have | the call | what you get back | works headless |
|---|---|---|---|
| `.obj` | `meshFromFile(path)` | `TriMesh`, index-for-index with the file | yes |
| `.stl` (ASCII or binary) | `meshFromFile(path)` | `TriMesh`, triangle soup — see [below](#what-each-format-loses) | yes |
| `.ply` (ASCII, binary, either endianness) | `meshFromAnyFile(path)` | `TriMesh` with the file's per-vertex colours | yes |
| `.glb`, `.gltf`, `.fbx`, `.dae`, `.3ds`, … | `meshFromAnyFile(path)` | `TriMesh`, all parts baked through their node transforms | yes |
| any of the above, textured, with PBR materials | `gltfModelHere("…")` / the [Fabricate renderer](fabricate_renderer.md) | GPU buffers, materials, textures, a live scene | no — needs a renderer |
| `.step`, `.iges`, `.brep` | **nothing** | — | — |

`meshFromAnyFile` handles everything `meshFromFile` does and more, so if you are unsure, use it. The
narrower one exists because it is dependency-free — pure Kotlin, no LWJGL, no native library — which
matters if you are reading `afield.stdlib` to learn from it.

## Four loaders, one renderer, and the two edges that were missing

Worth having the map, because "where is the loader" is a question this tree answers four times.

| loader | source | reads | produces |
|---|---|---|---|
| stdlib mesh I/O | `afield/stdlib/mesh_io.kt` | OBJ, STL | `TriMesh` |
| PLY | `afield/four/modules/simplePLYSupport.kt` | PLY, meshes and point clouds | packed 10-float GPU buffer |
| Assimp / glTF | `afield/four/modules/simpleGLTFSupport.kt` | glTF, GLB, FBX, DAE, 3DS, OBJ, STL, PLY, … | packed 12-float GPU buffer + materials |
| Fabricate | `afield/four/fabricated/FabricateGltfBridge.kt` | the Assimp model above | `FabricateScene` for the PBR [`StandardRenderer`](fabricate_renderer.md) |

Against that there is exactly **one** renderer that can put a mesh on screen without a GPU:
`VisionGeometryPreview`, behind `TriMesh.renderPng`, `showMesh` and the inline `toHTML()` card. It
takes a `TriMesh` and nothing else.

So until recently the graph had two missing edges. The PLY and Assimp loaders both produce ordinary
host memory — `memAlloc`, not a Vulkan allocation — and both were one function away from a `TriMesh`,
but that function did not exist. `mesh_io.kt`'s own KDoc said bridging them "is deliberately not
attempted, because those loaders return renderer buffers", and that reason was only half right: the
*buffers* need no renderer, only the *textures* do. `afield/four/modules/MeshFromDisk.kt` is the two
edges — `PlyModel.toTriMesh()`, `assimpToTriMesh(path)`, the `meshFromAnyFile` dispatcher, and the
`showMesh(path)` overload at the top of this page. It lives in `afield.four` rather than
`afield.stdlib` so that `mesh_io.kt` stays dependency-free; `afield.four.modules.*` is in every box's
default imports, so a box calls these with no import either way.

## A real GLB, in a real output area

The pressure test is the Khronos ToyCar sample — the same 5.4 MB GLB the [Fabricate
renderer](fabricate_renderer.md) uses for its PBR work, so the two pages cannot disagree about the
file. It is a genuinely awkward model: three mesh/material buckets, a draped cloth with 60k triangles
of folds, glass, and everything positioned by node transforms rather than baked into the vertices.

```diffstatic_runner {data-lang=kotlin screenshot="assets/geometry_from_disk/toycar.png" wait="14.0" capture="box" open-outputs="true" padding="20"}
:::static
// The same file public/docs/fabricate_renderer.md renders with full PBR — here through the CPU
// z-buffer preview instead, which needs no GPU and works in a headless box.
val glb = java.io.File("public/docs/assets/fabricate_demo/ToyCar.glb")
println("${glb.name}: ${glb.length()} bytes on disk")

val car = meshFromAnyFile(glb.path)
println(car.summary())

// Three mesh/material buckets, assembled by node transform rather than heaped at the origin.
val b = car.bounds()
println("assembled extent %.4f x %.4f x %.4f metres".format(
    b.max.x - b.min.x, b.max.y - b.min.y, b.max.z - b.min.z))
:::fold draw it
showMesh(
    car,
    view = MeshView(width = 560, height = 420, wireframe = false),
    title = "ToyCar.glb — 108,936 triangles through VisionGeometryPreview",
)
:::endfold
```

![The Khronos ToyCar GLB rendered by Field's CPU z-buffer preview: a 1950s saloon model sitting on a heavily draped cloth, correctly shaded with soft folds, above a caption reading 77429 verts, 108936 tris, extent 0.073 x 0.029 x 0.074, chi=1909, open](assets/geometry_from_disk/toycar.png)

The numbers under the picture are the interesting part:

- **77,429 vertices, 108,936 triangles** (326,808 indices), summed over the three buckets: ToyCar
  itself at 66,951 vertices, Fabric at 8,959 and Glass at 1,519.
- **extent 0.073 × 0.029 × 0.074** — the file is in metres, and it is a 7 cm toy. `MeshView`'s
  `distance` is measured in bounding-sphere radii, so the camera frames it correctly without your
  having to know that. `normalized(2.0)` is there if you want a common scale across several models.
- **χ = 1909, open, 2782 boundary loops.** Not a defect: an artist's model is a shell, not a solid,
  and every UV seam and every hard edge is a real cut in the surface. Expect this of anything that
  came out of a modeller, and do not reach for `repaired()` unless you actually need watertightness
  (for a volume, a boolean, or a printer).

That the picture is right is not a matter of opinion — `MeshFromDiskTest` asserts that this file
produces exactly 77,429 vertices and 108,936 triangles, that no `NaN` survives the transform bake,
that the model has real extent on all three axes rather than being heaped at the origin, and that a
render of it paints more than 5% of its pixels.

!!! warning "The one trap in the Assimp path"

    `GltfSupport.loadGltfBasic(path)` with **default arguments cannot run outside a live renderer**.
    `GltfLoadConfig.loadTextures` defaults to `true`, and that path asks the box context for a
    `Renderer` so it can build stub textures — so from a plain function, a test, or a headless script
    it dies with a bare `NullPointerException` that says nothing about textures. Pass
    `GltfLoadConfig(loadTextures = false)` and geometry-only loading works everywhere.
    `assimpToTriMesh` does exactly that for you.

## Writing a file and reading it back

The round trip is worth doing once yourself, because what comes back is not always what went out.

```diffstatic_runner {data-lang=kotlin screenshot="assets/geometry_from_disk/roundtrip.png" wait="9.0" capture="box" open-outputs="true" padding="20"}
:::static
// A coloured sphere, out to both formats and back. Nothing is written into the repository: this is a
// temp directory that the box deletes at the end.
val dir = java.nio.file.Files.createTempDirectory("mesh-round-trip").toFile()
val source = icosphereMesh(3, 1.0).withColor(0.3, 0.7, 1.0)

val objFile = java.io.File(dir, "ball.obj").also { source.saveObj(it) }
val stlFile = java.io.File(dir, "ball.stl").also { source.saveStl(it) }
println("ball.obj  ${objFile.length()} bytes")
println("ball.stl  ${stlFile.length()} bytes   (= 84 + 50 x ${source.triangleCount})")
println()

// meshFromFile picks the reader by extension. meshFromAnyFile would too, and also reads .ply/.glb.
val viaObj = meshFromFile(objFile.path)
val viaStl = meshFromFile(stlFile.path)
val welded = viaStl.weld(1e-5)

// A lambda rather than a local `fun`, because inside a box `println` is rewritten onto the box's own
// receiver and a nested function does not have it.
val row = { label: String, m: TriMesh ->
    "%-16s %5d verts  %5d tris  normals=%-5s colours=%-5s chi=%-5d %s  volume %.6f".format(
        label, m.vertexCount, m.triangleCount, m.hasNormals, m.hasColors,
        m.topology().eulerCharacteristic,
        if (m.isWatertight()) "watertight" else "open      ",
        m.signedVolume(),
    )
}
println(row("source", source))
println(row("via .obj", viaObj))
println(row("via .stl", viaStl))
println(row("via .stl, welded", welded))
:::fold draw the three of them side by side, then clean up
showMeshGrid(
    "source — icosphereMesh(3), coloured" to source,
    "via ball.obj — colours gone" to viaObj,
    "via ball.stl, welded — normals gone" to welded,
    columns = 3,
    cellWidth = 235,
)
dir.deleteRecursively()
:::endfold
```

![Three renders of the same 1280-triangle sphere side by side: the source in pale blue from its own per-vertex colours, the OBJ round trip in the renderer's neutral blue-grey because OBJ dropped the colours, and the welded STL round trip in the same neutral grey, all three captioned as watertight with chi=2 and volume 4.1527](assets/geometry_from_disk/roundtrip.png)

The pictures differ, and each difference is a format telling you what it cannot carry.

## What each format loses

Measured on `icosphereMesh(3, 1.0).withColor(0.3, 0.7, 1.0)` — 642 vertices, 1280 triangles, area
12.506493, volume 4.152741 — and asserted, row by row, by `MeshFromDiskTest`:

| | verts | tris | normals | uvs | colours | χ | closed | area | volume |
|---|---|---|---|---|---|---|---|---|---|
| **source** | 642 | 1280 | yes | — | yes | 2 | watertight | 12.506493 | 4.152741 |
| **via `.obj`** | 642 | 1280 | **yes** | yes¹ | **no** | 2 | watertight | 12.506493 | 4.152741 |
| **via `.stl`** | **3840** | 1280 | **no** | no | no | **1280** | **open** | 12.506493 | 4.152741 |
| **via `.stl`, `weld(1e-5)`** | 642 | 1280 | **no** | no | no | 2 | watertight | 12.506493 | 4.152741 |

¹ uvs survive OBJ when the mesh has them; this sphere does not. A `heightfieldMesh` round-trips its
uvs to within 1e-5, which is also asserted (in `MeshIoTest`).

Reading down the columns:

- **The coordinates always survive.** Area and volume are identical to six decimal places through
  every route. If a mesh's *shape* changed on a round trip, that is a bug, not a format.
- **OBJ keeps structure, loses colour.** It indexes positions, normals and uvs separately, so
  Field's reader emits one vertex per distinct `(position, uv, normal)` corner — which is why a hard-
  edged cube from a modeller comes back with 24 vertices and not 8, faithfully. There is no
  standardised per-vertex colour in OBJ, so `withColor` does not make it into the file.
- **STL keeps nothing but triangles.** It has no vertex sharing at all, so 1280 triangles become
  3840 unshared vertices and every edge reads as a boundary. `weld(1e-5)` gets the topology back
  exactly — 642 vertices, χ = 2, watertight — but **not** the normals, because STL never stored a
  per-vertex normal to recover. Re-shade after welding: `shadedNormals(35.0)` is usually right.
- **A binary STL is exactly `84 + 50n` bytes** — 64,084 here — against 75,625 for the OBJ and
  324,825 for the ASCII STL, five times the binary for the same 1280 triangles.
- **PLY is the one that carries colour.** Which is generally why a file is a PLY: it is what
  photogrammetry and scanners emit. `meshFromAnyFile` routes `.ply` through `PlySupport` rather than
  Assimp for exactly that reason.

## Two ways the numbers will surprise you

**The fact sheet is computed, not remembered.** `summary()`, `toFigureHtml()` and the inline
`toHTML()` card all compute topology on the mesh you hand them and print it as fact. That is the
right design — they never lie about the object in front of them — but it means a format artefact
reads as a property of your model:

```diffstatic {data-lang=kotlin}
icosphereMesh(3).summary()
// 642 verts, 1280 tris, extent 2.0000 …, chi=2, genus 0, watertight, area 12.5065, volume 4.1527, …

stlToTriMesh(icosphereMesh(3).toStlBinary()).summary()
// 3840 verts, 1280 tris, extent 2.0000 …, chi=1280, genus 0, open(1280), area 12.5065, …
```

Note what is *missing* from the second line: there is no `volume`, because `summary()` only prints
one for a watertight mesh — and yet `signedVolume()` on that same soup returns 4.152741, exactly
right. The number was dropped by a rule about topology, not by any loss of geometry. `genus 0` is
correct too, and not about a sphere: 1280 separate triangles are 1280 discs, each of genus 0.

**An unexpected vertex count is usually shading, not the file.** Writing and reading is faithful, so
if a count looks wrong, look upstream at what shaded the mesh:

| | vertices |
|---|---|
| `boxMesh()` | 8 |
| `boxMesh().shadedNormals(35.0)` | 24 |
| `boxMesh().flatShaded()` | 36 |
| `objToTriMesh(boxMesh().toObj())` | 8 |
| `objToTriMesh(boxMesh().shadedNormals(35.0).toObj())` | 24 |

`flatShaded()` un-welds every triangle, so a solid stops being watertight and its Euler
characteristic becomes its triangle count. Save the *unshaded* mesh and let the renderer crease-shade
it, or weld after reading. All five of those counts are asserted.

## PLY, and what a scan carries

```diffstatic_runner {data-lang=kotlin screenshot="assets/geometry_from_disk/ply.png" wait="9.0" capture="box" open-outputs="true" padding="20"}
:::static
// A four-vertex coloured square, written as ASCII PLY and read back. The same code path a 10-million-
// point scan takes; small enough to read here.
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

// The loader is PlySupport — no renderer, no Vulkan, no Box context needed.
val model = PlySupport.loadPly(file.path)
val b = model.buffers
println("vertices ${b.vertexCount}, indices ${b.indexCount}, colours ${b.hasColors}, " +
    "normals in the file ${b.hasNormals}, point cloud ${b.isPointCloud}")
println("packed as ${b.vertexData.capacity() / (b.vertexCount * 4)} floats per vertex: " +
    "[pos.xyz][normal.xyz][color.rgba]")

// ...and the bridge into a TriMesh keeps the colours.
val mesh = model.toTriMesh()
println("as a TriMesh: ${mesh.summary()}")
println("colours survived: ${mesh.hasColors}, vertex 0 is ${mesh.color(0)}")
println("normals dropped: ${!mesh.hasNormals} — the file had none, and the loader's " +
    "invented (0,0,1) would shade the whole thing as if it faced you")
:::fold draw it flat on, then clean up
showMesh(
    mesh,
    view = MeshView(yawDegrees = 0.0, pitchDegrees = 0.0, width = 420, height = 315),
    title = "square.ply — per-vertex colours",
)
dir.deleteRecursively()
:::endfold
```

![A two-triangle square rendered flat on, its four corners coloured red, green, blue and yellow with the colours interpolating smoothly across each triangle and meeting along the shared diagonal, captioned 4 verts, 2 tris, extent 1.000 x 1.000 x 0.000, chi=1, open](assets/geometry_from_disk/ply.png)

Three details in that output are the ones that bite:

- **A PLY with no normals gets `(0, 0, 1)` written for every vertex by the loader.** That is a
  *wrong* normal rather than a missing one, and a wrong normal shades the whole model as though it
  faced the camera. `toTriMesh()` drops them when the file had none, so the render bridges
  crease-shade for you instead. Pass `keepNormals = false` to drop them even when the file has them.
- **A PLY with no `face` element is a point cloud**, and `toTriMesh()` says so rather than handing
  back a mesh with no triangles. Check `buffers.isPointCloud` first; for drawing those, the
  `plyPointCloudHere` macro renders them on the GPU as camera-facing quads.
- **All-white colours are dropped.** Both bridges do this. A glTF with no `COLOR_0`, or a PLY with no
  `red`/`green`/`blue`, gets white written for every vertex by its loader — that carries no
  information and would only override the renderer's own shading with a flat white.

## What genuinely does not exist

The useful half of a page like this. None of the following is hiding somewhere in the tree; if you
need one, you are building it.

**STEP, IGES, BREP — any CAD boundary representation.** There is no CAD kernel on the classpath and no
STEP parser anywhere. This is the big one, and it is genuinely big: STEP is not a mesh format. A
`.step` file is NURBS surfaces trimmed against each other, and reading it means both an
ISO-10303-21 parser *and* a tessellator that can evaluate a trimmed rational surface to a triangle
mesh at a tolerance. Realistically that is an OpenCASCADE binding — a large native dependency with a
build story on three platforms — not a weekend of Kotlin. A far cheaper 80% is to convert outside
Field (FreeCAD and every CAD tool export STL) and read the STL. `meshFromAnyFile` on a `.step` throws
rather than returning an empty mesh, so at least the failure is loud.

**Textures and materials into a `TriMesh`.** A `TriMesh` has positions, normals, uvs and per-vertex
colours, and no concept of a texture; `assimpToTriMesh` drops the material Assimp read. Textured PBR
already exists on the other path — `gltfModelHere`, `FabricateGltfBridge`, the
[`StandardRenderer`](fabricate_renderer.md) — which is where to go for a *rendered* model. Bringing
textures across to the CPU side would mean sampling a `LoadedTexture` per vertex into vertex colours,
which is easy and lossy, or teaching `VisionGeometryPreview` to sample a texture, which is a real
change to the rasterizer.

**Point clouds as first-class geometry.** `PlySupport` reads them and `plyPointCloudHere` draws them
on the GPU, but there is no CPU point-cloud type — no splatting into `VisionGeometryPreview`, no
Poisson or ball-pivot surface reconstruction, no downsampling. `convexHullMesh` over the positions
and `voronoi3d` are what the tree has. (For Gaussian splats specifically, see
`afield/four/fabricated/GaussianSplatRenderer.kt`, which is a different and much more specific
thing.)

**Animation.** Assimp samples node TRS tracks and `GltfSupport.MeshInstance.animationSamples`
carries them, but `assimpToTriMesh` bakes the bind pose and throws the tracks away — a `TriMesh` is
one static surface. Skinned deformation is not read at all: no joints, no weights, no skin matrices.

**A scrub-through-steps mesh viewer.** Nothing lets you step back and forth through the stages of a
mesh pipeline in a UI. `showMeshGrid` (several meshes side by side) and `showMeshTurntable` (one mesh
from four angles) are the static approximations, and they are usually enough. The pieces for the real
thing exist — the [keyframer](keyframer_examples.md) captures live state, the
[history lens](history_lens.md) scrubs a box's past — but nobody has wired a mesh pipeline to either.

**Writing anything but OBJ and STL.** `toObj`, `saveObj`, `toStlAscii`, `toStlBinary` and `saveStl`
are the whole export surface. Writing a PLY would be short and would let you *keep* per-vertex colours
on the way out, which neither current writer can; writing a GLB means the buffer/accessor/JSON
scaffolding, which is a day rather than an hour. Assimp can export, but LWJGL's binding exposes
`aiExportScene`, and building an `AIScene` by hand from a `TriMesh` is more work than emitting the
text.

## Writing back out

```diffstatic {data-lang=kotlin}
// java.io is not in a box's default imports, hence the qualified names.
mesh.saveObj(java.io.File("/tmp/thing.obj"))     // keeps normals and uvs; loses colours
mesh.saveStl(java.io.File("/tmp/thing.stl"))     // binary; positions only; every slicer reads it
java.io.File("/tmp/thing.stl").writeText(mesh.toStlAscii())   // ~5x larger, human-readable
```

Two rules that save an afternoon. **Write the un-shaded mesh** — `flatShaded()` triples the vertex
count and un-welds the surface, and the reader on the other side will not weld it back. And for a
printer, check `isWatertight()` and that `signedVolume()` is **positive** before writing: a
consistently-oriented but inside-out solid looks completely normal in a render (the silhouette is
unchanged and the rasterizer flips inward normals rather than shading them black) and reports a
negative volume. `fixWinding()` is the fix.

## Where the pictures come from

All three figures above are produced by the docs build executing the fence above them in a real box:
the image is that box's own output area. Two caveats, the same ones
[`mesh_examples.md`](mesh_examples.md#where-the-pictures-come-from) carries:

- A fence is re-executed only when the build decides its capture is stale, from a digest over the
  runner machinery plus the **stdlib** source files the fence's code names. `MeshFromDisk.kt` is in
  `afield.four`, not `afield.stdlib`, so editing *it* does not invalidate these captures on its own.
  Force them with `FIELD_DOCS_RUNNER_MODE=always` after changing the bridges.
- The ToyCar figure depends on a committed 5.4 MB binary, `assets/fabricate_demo/ToyCar.glb`. If that
  file is ever replaced, this page's counts and `MeshFromDiskTest` both go wrong at once — which is
  the intended arrangement, since the test says so out loud.

What keeps the page honest is `src/jvmTest/kotlin/afield/stdlib/MeshFromDiskTest.kt`: twelve tests,
every number quoted here asserted as an exact value, including the ToyCar counts, the fidelity table
row by row, the `84 + 50n` STL size, the five shading counts, and the fact that `.step` still fails.
`MeshIoTest.kt` covers the OBJ/STL readers themselves — quads, negative indices, the
`solid`-prefixed-binary sniff trap, uv survival.

## Reference

| | |
|---|---|
| **read, any format** | `meshFromAnyFile`, `showMesh(path)`, `assimpToTriMesh`, `PlyModel.toTriMesh` — `afield/four/modules/MeshFromDisk.kt` |
| **read, no dependencies** | `meshFromFile`, `triMeshFromObj`, `objToTriMesh`, `triMeshFromStl`, `stlToTriMesh` — `afield/stdlib/mesh_io.kt` |
| **write** | `toObj`, `saveObj`, `toStlAscii`, `toStlBinary`, `saveStl` — same file |
| **look at it** | `showMesh`, `showMeshGrid`, `showMeshTurntable`, `MeshView`, `renderPng`, `renderPixels`, `toFigureHtml`, `summary` — `afield/stdlib/mesh_field.kt` |
| **repair what you read** | `weld`, `fixWinding`, `removeDegenerateTriangles`, `repaired`, `largestComponent`, `shadedNormals`, `normalized` |
| **on the GPU instead** | `plyModelHere`, `plyPointCloudHere`, `gltfModelHere`, `modelWithTextureHere` — `simplePLYSupport.kt`, `simpleGLTFSupport.kt` |

Related pages: [Meshes and 3D geometry](mesh_examples.md) for everything you can *do* with a mesh
once you have one; [the Fabricate renderer](fabricate_renderer.md) for textured PBR rendering of the
same files; [afield.four graphics](afield_four_graphics.md) for the module/shader layer the GPU
loaders plug into; [FLine examples](fline_examples.md) for `toFLines`, the route from a mesh back into
Field's drawing. Developer notes: `dev_docs/MESH_GEOMETRY.md` and `dev_docs/ply_loader.md`.
