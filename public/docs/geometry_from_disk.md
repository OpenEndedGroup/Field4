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

## A GLB is a scene, not a mesh

Which means flattening one into a single `TriMesh` has a domain, and you should know where its edges
are. A glTF file is a node hierarchy: each node carries a **full 4×4 transform**, several nodes can
reference the same mesh, and each mesh carries its own material. Here is what `meshFromAnyFile` does
with all of that, measured on a four-node scene that `AssimpSceneBridgeTest` builds from scratch — one
asymmetric triangle instanced by an identity node, a translated node, a *child* of that translated node,
and a **mirrored** node with `scale (-1, 1, 1)`.

| the scene has | the bridge does |
|---|---|
| several nodes referencing meshes | **merges all of them.** Four instances arrive as 4 triangles in 4 components, not "the first one". |
| a node transform | **applies it.** ToyCar's own three nodes each carry a uniform `1e-4` scale, which is what makes that figure evidence rather than a file that happens not to exercise this: a bridge that dropped transforms would render it 10,000× too big — 730 m across, not 0.073 m. |
| a parent chain | **composes it.** The child of the node translated by `(3, 0, 0)`, itself translated by `(0, 2, 0)`, lands at `(3, 2, 0)`. |
| a **mirror** — negative determinant | applies it, **and reverses that part's winding**, so the surface still faces outward instead of being culled away. |
| non-uniform scale | normals go through the **inverse transpose**, not the matrix, so they stay perpendicular to the surface. |
| per-vertex `COLOR_0` | kept, per instance. An all-white array — what glTF writes when the file has no colours — is dropped. |
| quads or n-gons | already triangles: Assimp's `aiProcess_Triangulate` runs before the bridge sees anything. |
| materials and textures | **dropped.** A `TriMesh` has no concept of a texture. Use the [Fabricate renderer](fabricate_renderer.md) for those. |
| animation | **dropped.** The bind pose is baked; node TRS tracks and skinning are not read. |
| a Y-up / Z-up disagreement | **nothing.** glTF is Y-up by specification and no axis conversion is applied, so a Z-up PLY or OBJ arrives on its side. `rotated(...)` is yours to apply. |

!!! warning "The bug that scene caught, which is still live on the GPU paths"

    `GltfSupport.MeshInstance.bindPose` is a *translation / rotation / scale* triple, and a mirror does
    not fit in one — `Matrix4f.getScale` is `sqrt(...)` per basis column, so it is never negative.
    Measured: Assimp hands the mirrored node over as row 1 `[-1 0 0 -3]` with 3×3 determinant **−1.0**,
    and `bindPose.toMatrix()` returns `[1 0 0 -3]` with determinant **+1.0**. The reflection is gone.

    A bridge reading `bindPose` therefore placed that instance **un-mirrored** — far vertex at `x = -2`
    instead of `x = -4` — with no error, no warning, and a perfectly plausible-looking mesh. That is the
    worst failure mode a loader has. The fix was to record the un-decomposed world matrix
    (`MeshInstance.bindMatrix`) and read *that*, which `assimpToTriMesh` now does; the test fails if it
    stops.

    **The GPU paths have not been fixed, and they fail in two different ways.**
    `FabricateScene.importGltf` and `StandardRendererDocsHarness.gltfWorldBounds` call
    `bindPose.toMatrix()`, so a mirrored node is still *flattened* there — the same defect, one line from
    a fix (`instance.bindMatrix ?: instance.bindPose.toMatrix()`). `PlyModel.applyToMesh`/the glTF
    `applyToMesh` are worse and differently wrong: they set `modelView` and `proj` from the camera and
    apply **no model matrix at all**, so they drop every node transform, not just the mirror — a scene
    whose parts are positioned by their nodes draws them all at the origin. Stated rather than fixed:
    changing a renderer's model matrix needs its committed captures re-verified, which is a separate
    change.

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

## When it does not look right

Six symptoms, in the order they actually happen, each with the one-line diagnosis. `summary()` answers
five of the six, which is the argument for printing it before drawing anything.

| symptom | cause | what to do |
|---|---|---|
| a blank black panel | an empty mesh | A file whose *contents* are not what its extension says parses to `TriMesh.empty` with **no exception at all**. `println(mesh.summary())` — an empty one prints exactly `TriMesh(empty)`. |
| holes, or whole faces missing | inside-out winding, open mesh | Back faces are culled exactly as a GPU culls them, so a wrongly-wound patch is not dark, it is *gone*. `fixWinding()`, then check `topology().isConsistentlyOriented`. |
| looks fine, every measurement negative | inside-out winding, closed solid | The silhouette is unchanged and the rasterizer flips inward normals rather than shading them black, so the picture is no help at all. `signedVolume() < 0` is the only tell; `fixWinding()` is the fix. |
| flat, faceted or oddly uniform shading | no normals, or invented ones | A mesh with *none* is crease-shaded for you; one with wrong ones is not. Recompute with `shadedNormals(35.0)`. To drop them, `withoutAttributes()` — note that `copy(normals = null)` **keeps** them, because `null` there means "unchanged". |
| `isWatertight()` false on a closed solid | it came through STL | Or it was `flatShaded()` before it was written. `weld(1e-5)` recovers the topology, and `meanEdgeLength()` is the scale that tells you 1e-5 is safe rather than a guess. |
| the vertex count is exactly 3× | STL, or `flatShaded()` | Both un-weld every triangle, and the reader on the other side will not weld it back. `weld`, then `shadedNormals` if you want the hard edges again. |

The first row is the one that costs an hour, so it is worth being concrete: a file called `thing.obj`
that is actually a ZIP, a glTF, or an HTML error page parses to zero vertices and zero triangles and
raises nothing. `meshFromFile` is loud about a *missing* file and about an extension it does not read,
and silent about a file whose contents make no sense. `println(mesh.summary())` before `showMesh` costs
nothing and turns that hour into a second.

An inside-out open mesh really is *invisible*, not dark: a flipped 128-triangle plane patch renders
**zero** non-background pixels, with all 128 triangles reported as culled. Both halves of that are
asserted, along with the empty-parse behaviour above.

## From a file on disk to a solid you can cut

The table above is the manual version. `mesh_repair.kt` is the automatic one, and it exists because
the step after "I loaded a mesh" is usually "I want to boolean it", and
[`toSolid()`](manifold.md) refuses anything that is not already closed.

Two calls. `diagnose()` says *why*; `repairToSolid()` fixes what it can and tells you what it could
not.

```kotlin
val mesh = meshFromAnyFile("scan.glb")
println(mesh.diagnose().report())          // why, worst first
val fixed = mesh.repairToSolid()
println(fixed.report())                    // what was done, and what was not
if (fixed.isSolid) display(fixed.mesh.toSolid() - drill)
```

### The vocabulary, and which of it actually matters

Every severity below was established by damaging a known-good mesh in exactly one named way and
asking Manifold whether it accepted the result. The answers are not the ones folklore predicts.

| defect | severity | what it means |
|---|---|---|
| `NON_FINITE_POSITION` | **blocking** | a `NaN` or infinite coordinate |
| `UNWELDED_VERTICES` | **blocking** | coincident vertices with different indices — until they merge, every seam reads as a hole |
| `DUPLICATE_FACE` | **blocking** | two triangles with the same corners; each extra copy makes its three edges non-manifold |
| `NON_MANIFOLD_EDGE` | **blocking** | an edge with three or more faces |
| `OPEN_BOUNDARY` | **blocking** | a hole |
| `UNWALKABLE_BOUNDARY` | **blocking** | boundary edges in no closed ring |
| `DEGENERATE_FACE` | suspicious | zero-area triangles — **Manifold accepts these** |
| `NON_MANIFOLD_VERTEX` | suspicious | a pinch point — **Manifold accepts these** |
| `INVERTED_ORIENTATION` | suspicious | closed but wound inward — **Manifold accepts this** |
| `MULTIPLE_COMPONENTS` | informational | disjoint pieces — **Manifold accepts these and sums the volumes** |
| `UNREFERENCED_VERTEX` | informational | vertices no triangle uses |

The three "Manifold accepts this" rows are the reason there is a *suspicious* severity at all rather
than a pass/fail. The dangerous one is `INVERTED_ORIENTATION`: an inside-out closed mesh is a
perfectly legal manifold, and Manifold hands back a **negative volume**. Nothing throws. The boolean
you do next quietly computes the complement of what you meant. A checker that only answered
"valid / not valid" could not tell you that.

`UNWALKABLE_BOUNDARY` deserves a note, because the first thing it usually means is not the exotic
thing. Tracing a boundary into a ring follows directed half-edges, so **inconsistent winding breaks
the trace** and a perfectly ordinary hole reports as unwalkable. Run `fixWinding()` and diagnose
again before believing the exotic reading (two holes meeting at one vertex).

### What the repairs do, and what they do not fix

`repairToSolid()` runs these in this order, and the order is causal rather than a matter of taste —
each step exists because the next would give a wrong answer without it.

| step | fixes | does **not** fix |
|---|---|---|
| `weld` | unwelded seams, STL-style unshared vertices | anything, until it has run: hole and component counts are meaningless before it |
| `removeDegenerateTriangles` | zero-area faces | — |
| `removeDuplicateFaces` | duplicated faces; a back-to-back pair cancels to nothing | — |
| `removeNonManifoldFins` | dangling flaps (a non-manifold edge **and** a boundary edge on the same triangle) | two closed sheets meeting on an edge — that needs a modelling decision, so it is left alone and reported |
| `fixWinding` | inconsistent orientation | — |
| `fillHoles` | open boundary loops | a non-planar or self-intersecting rim gets a cap it flags as suspect |
| `fixWinding` again | inside-out components | — |

The second `fixWinding` is not redundant. Its outward-facing stage only flips a component that is
*closed*, because "outward" is undefined for an open sheet — so before the caps exist, every holed
component is skipped. Running it again afterwards is what stops a repaired mesh coming back inside
out with a negative volume.

**Hole filling is the part to be suspicious of.** Each ring is reversed (a cap must walk its boundary
edges the opposite way to the surface), given a Newell normal, projected into that plane and
ear-clipped; if ear clipping stalls, a centroid fan closes it anyway. Two failure modes are detected
and reported:

- **a non-planar rim** gets a flat cap over a wavy hole. The mesh is a valid solid; the *volume is
  wrong*. `BoundaryLoopInfo.planarity` measures the deviation against the loop's own diameter before
  the fill happens, so the warning is available in advance.
- **a self-intersecting rim** is what makes ear clipping stall, and the fan that closes it can pass
  through itself.

A ring that repeats a vertex is neither: it is a figure-eight, two holes pinched together, and
capping it as one polygon *creates* a non-manifold edge — the repair would make the mesh worse. Those
are split at the repeats first, so each real hole gets its own cap.

#### And one failure mode that is *not* detectable, which matters more

**No local property of a hole distinguishes "this end should be a flat cap" from "the model continued
here."** Two measured cases settle it, and they point opposite ways:

| | rim | cap | cap share of surface | result |
|---|---|---|---|---|
| uncapped cylinder | flat | clean ear clip | 16.5% | volume error **0.00000** — exactly right |
| torus with its tube severed | flat | clean ear clip | 2.7% | volume error **4.2%**, and **genus 1 becomes 0** |

Planarity, fill method, edge count and cap area are all equal-or-*better* on the wrong one. A cap-area
threshold was implemented, measured against these two, and removed: at any cut-off that flags the
severed torus it also flags the cylinder, whose caps are correct. `capAreaFraction` is still reported
as a fact, but it deliberately does not set `isSuspect`.

So the contract is: **`isSuspect == false` means the cap's geometry is sound, not that the model is
the one you started with.** Only comparison against the shape you meant catches the difference, and at
run time you do not have it.

### How much of this was already there, and how much of it is actually *right*

Honest accounting, because `repaired()` (weld → degenerate → unused → winding) has shipped in
`mesh_topology.kt` for a while and is not a straw man.

The test table is 27 meshes broken in named, deterministic ways, of which **21 are rejected by
Manifold as-is** — the other six are damaged inputs Manifold accepts unchanged, and counting them
would inflate everything below. Each row is a damaged copy of a known-good mesh, so the correct
answer is known, and "repaired" is judged against **the undamaged original** rather than against
`isValidSolid()`:

| of the 21 Manifold rejects | |
|---|---|
| the pre-existing `repaired()` makes valid | **6** — every unwelded-seam and every inconsistent-winding case, all of them faithful |
| `repairToSolid()` makes valid | **19** |
| ...of which **faithful** — volume, area *and* genus match the original | **15** |
| ...of which **valid but the wrong shape** | **4** |
| cannot be fixed, and say so | **2** |

That split is the point. An earlier draft of this page claimed "14 of 16" — measured on a table in
which **every hole was flat**, planarity `0.0000` and volume error `0.00000` on every one. A fan fill
is trivially correct on a planar rim, so that number measured the easy case and quietly used "fixes"
to mean "Manifold accepts it". The four wrong rows are curved and non-planar holes added specifically
to stop that: a hole across a torus's tube (1.5% off), one that severs it (4.2% off, genus 1→0), a
wide hole over a sphere (1.9% off), and a crowned rim (**35%** off).

The new code is responsible for exactly two families: open boundaries, and non-manifold edges from
duplicated faces or dangling fins. Two rows stay broken on purpose and assert that the failure is
*reported*: a `NaN` position (refused outright, because repairing around it would silently delete the
affected triangles and cap the gap), and two closed tetrahedra sharing a face.

### On the one real asset

`ToyCar.glb` — 77,429 vertices, 108,936 triangles, 2,197 components, 42,104 boundary edges in 2,782
traced loops — **repairs to a valid solid you can boolean**. 21,047 vertices welded, 84 holes closed
with 3,668 triangles.

Note which half of the claim that is. Unlike every row of the test table, ToyCar has **no undamaged
original to check against**, so "valid solid" is all anyone can say about it — exactly the weaker
statement the table exists to separate from the stronger one.

And the volume it then reports is `3.16e-5`, which is a number rather than an answer. Nine of those
84 caps are flagged suspect, one of them a 335-edge centroid fan — a lid the size of a body panel.
`result.suspectFills` is the list to look at before trusting any measurement, and on a multi-part
export of this size a repair that flagged *nothing* would mean the flagging was broken, not that the
model was clean.

### Seeing it

`Box.showMeshDiagnosis(mesh)` puts the mesh — painted by defect — beside the ordered list of what is
wrong. `TriMesh.defectFLines()` gives the same information as drawable lines to lay over your own
drawing: red for boundary loops, orange for non-manifold edges, magenta for pinched figure-eight
loops, yellow (opt-in) for winding disagreements. `TriMesh.withDefectColors()` is the per-vertex
colouring the card renders, if you want it in your own picture.

The colouring is asserted on the raster rather than on the call returning: a clean cube paints 0 red
pixels and one with a face removed paints 3,345; a clean icosphere paints 0 orange and one with a
non-manifold fin paints 2,579.

`showMeshDiagnosis` **points the camera at the worst defect** unless you pass a view, and that is not
a nicety. Two captures taken while this was being built showed a flawless-looking model and asserted
nothing about it — once a non-manifold fin on the far side of a sphere, once a hole. A card captioned
"4 faces removed" over a picture of an unblemished sphere argues that nothing is wrong.
`TriMesh.defectFacingView()` is that camera, if you want it for your own render.

### Open this to see it

`public/docs/assets/mesh_repair_demo/` — two boxes: one damages a sphere and shows the diagnosis
card, the other repairs it, prints what was done, and cuts the result. Both run with no files and no
network, and both import `afield.stdlib.diagnose` / `repairToSolid`, so if they compile the Field you
are running has this code.

## What it costs

Reading a mesh off disk is cheap enough to do in a live box on every re-run. Measured on an M-series
laptop, five reads after two warm-up reads:

| | file | triangles | time | rate |
|---|---|---|---|---|
| `ToyCar.glb` via Assimp | 5.4 MB binary | 108,936 | **83 ms** | 1310 k tri/s |
| `big.obj` via `meshFromFile` | 9.4 MB text | 132,488 | **337 ms** | 393 k tri/s |
| `big.stl` via `meshFromFile` | 6.6 MB binary | 132,488 | **185 ms** | 718 k tri/s |
| `big.obj` via `assimpToTriMesh` | the same 9.4 MB | 132,488 | **223 ms** | 595 k tri/s |

Two things fall out of that table. **Binary beats text by about 2×** for the same mesh, which is the
argument for STL over OBJ when the file is an intermediate nobody reads. And **Assimp's OBJ reader is
about 1.5× faster than the one in `mesh_io.kt`** — it is native, and the Kotlin one splits strings.
`meshFromAnyFile` still routes `.obj` to the Kotlin reader, because its corner-triple handling makes a
round trip index-for-index and 100 ms on a 9 MB file is not the bottleneck. If you are reading
hundreds of OBJs in a loop, `assimpToTriMesh` on them directly is the faster call.

Looking at it costs more than reading it, and not where you would guess:

| | on 108,936 triangles |
|---|---|
| `renderPng(560×420, wireframe = false)` | 51 ms |
| `renderPng(560×420, wireframe = true)` | 114 ms |
| `toFigureHtml(…)` — what `showMesh` calls | **419 ms** |

The raster is 51 ms; the depth-tested wireframe doubles it; and the figure costs **four times the
render**, because the caption's `χ`, genus, watertightness and boundary-loop count need a full topology
pass — edge map, vertex stars, boundary walk — over the whole mesh. On a model with 2782 boundary
loops that is the expensive part of `showMesh`, not the picture. If you are re-rendering in a loop and
do not need the numbers, call `renderPng` and `display` the base64 yourself.

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

What keeps the page honest is `src/jvmTest/kotlin/afield/stdlib/MeshFromDiskTest.kt`: sixteen tests,
every number quoted here asserted as an exact value, including the ToyCar counts, the fidelity table
row by row, the `84 + 50n` STL size, the five shading counts, and the fact that `.step` still fails.
`MeshIoTest.kt` covers the OBJ/STL readers themselves — quads, negative indices, the
`solid`-prefixed-binary sniff trap, uv survival.

## Playing with it instead of reading it

The three fences above are also a loadable document. Replay
`dev_docs/agents/transcripts/geometry_from_disk.yaml` into a fresh Field with the agent backdoor
(`field_run_transcript`) — or open it from the [transcript gallery](agent_transcripts.md) — and you get
the same three boxes live. Point the first box's `path` at a mesh file of your own and re-run it;
change the second box's weld tolerance, or the third's corner colours, and watch the numbers and the
picture move together. Boxes two and three write only into a temp directory they delete themselves.
That transcript is classified `EXECUTE` in `DemoTranscriptExecSmokeTest`, so the GLB path on this page
is re-run by CI on every build rather than asserted once and forgotten.

## Reference

| | |
|---|---|
| **read, any format** | `meshFromAnyFile`, `showMesh(path)`, `assimpToTriMesh`, `PlyModel.toTriMesh` — `afield/four/modules/MeshFromDisk.kt` |
| **read, no dependencies** | `meshFromFile`, `triMeshFromObj`, `objToTriMesh`, `triMeshFromStl`, `stlToTriMesh` — `afield/stdlib/mesh_io.kt` |
| **write** | `toObj`, `saveObj`, `toStlAscii`, `toStlBinary`, `saveStl` — same file |
| **look at it** | `showMesh`, `showMeshGrid`, `showMeshTurntable`, `MeshView`, `renderPng`, `renderPixels`, `toFigureHtml`, `summary` — `afield/stdlib/mesh_field.kt` |
| **repair what you read** | `weld`, `fixWinding`, `removeDegenerateTriangles`, `repaired`, `largestComponent`, `shadedNormals`, `normalized` |
| **on the GPU instead** | `plyModelHere`, `plyPointCloudHere`, `gltfModelHere`, `modelWithTextureHere` — `simplePLYSupport.kt`, `simpleGLTFSupport.kt` |
| **let the AI read it** | `GeometryAgentTools.addSearchRoot`, `.register` — `afield/four/modules/GeometryAgentTools.kt` |

## Letting Field's AI look at your geometry

Two lines turn everything on this page into something the AI can use:

```diffstatic {data-lang=kotlin}
GeometryAgentTools.addSearchRoot(File(System.getProperty("user.home"), "scans"))
GeometryAgentTools.register()
```

`ListGeometryFiles` tells the model what you have. `InspectGeometryFile` loads one, returns the same
measurements this page describes, and — because the CPU rasteriser needs no GPU and no window — hands
back a *rendered image*, so a model can look at a scan rather than reason about its bounding box. It
will only read inside roots you have added.

See [Give the AI a tool of your own](aiFeatures.md), which uses this tool set as its worked example
and shows the schema and the picture the model receives.

Related pages: [Meshes and 3D geometry](mesh_examples.md) for everything you can *do* with a mesh
once you have one; [the Fabricate renderer](fabricate_renderer.md) for textured PBR rendering of the
same files; [afield.four graphics](afield_four_graphics.md) for the module/shader layer the GPU
loaders plug into; [FLine examples](fline_examples.md) for `toFLines`, the route from a mesh back into
Field's drawing. Developer notes: `dev_docs/MESH_GEOMETRY.md` and `dev_docs/ply_loader.md`.
