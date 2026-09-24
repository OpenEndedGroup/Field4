# Geometry from disk

*You have a mesh file. You want to see it in Field, and then compute with it. This page is the whole
route, including the parts that do not work.*

The short answer is one line, and it is the same line for every format:

```diffstatic {data-lang=kotlin}
showMesh("/path/to/thing.glb")
```

That reads the file, assembles it, and pushes a shaded, wireframed, **orbitable** render into the
box's output area, with the mesh's measurements printed underneath. That is the Three.js viewer,
which is where every mesh goes unless you ask otherwise. Keep the mesh instead of the picture and
everything in the [mesh family](mesh_examples.md) applies: weld it, measure its volume, decimate it,
shoot a ray at it, write it back out as something else.

The long answer matters because Field has **four** separate things that read geometry off disk, they
were built for different reasons, and only some of them can show you the result.

## What reads what

<div data-code-lang="kotlin" markdown>

| you have | the call | what you get back | works headless |
| --- | --- | --- | --- |
| `.obj` | `meshFromFile(path)` | `TriMesh`, index-for-index with the file | yes |
| `.stl` (ASCII or binary) | `meshFromFile(path)` | `TriMesh`, triangle soup — see [below](#what-each-format-loses) | yes |
| `.ply` (ASCII, binary, either endianness) | `meshFromAnyFile(path)` | `TriMesh` with the file's per-vertex colours | yes |
| `.glb`, `.gltf`, `.fbx`, `.dae`, `.3ds`, … | `meshFromAnyFile(path)` | `TriMesh`, all parts baked through their node transforms | yes |
| any of the above, textured, with PBR materials | `gltfModelHere("…")` / the [Fabricate renderer](fabricate_renderer.md) | GPU buffers, materials, textures, a live scene | no — needs a renderer |
| `.step`, `.iges`, `.brep` | **nothing** | — | — |

</div>

`meshFromAnyFile` handles everything `meshFromFile` does and more, so if you are unsure, use it. The
narrower one exists because it is dependency-free (pure Kotlin, no LWJGL, no native library), which
matters if you are reading `afield.stdlib` to learn from it.

## Four loaders and two renderers

Here is the map, because "where is the loader" is a question this tree answers four times.

| loader | source | reads | produces |
|---|---|---|---|
| stdlib mesh I/O | `afield/stdlib/mesh_io.kt` | OBJ, STL | `TriMesh` |
| PLY | `afield/four/modules/simplePLYSupport.kt` | PLY, meshes and point clouds | packed 10-float GPU buffer |
| Assimp / glTF | `afield/four/modules/simpleGLTFSupport.kt` | glTF, GLB, FBX, DAE, 3DS, OBJ, STL, PLY, … | packed 12-float GPU buffer + materials |
| Fabricate | `afield/four/fabricated/FabricateGltfBridge.kt` | the Assimp model above | [`FabricateScene`](reference/renderers.md#fabricatescene-kt-fabricatescene) for the PBR [`StandardRenderer`](fabricate_renderer.md) |

Against that there are **two** renderers that will put a mesh on screen. `showMesh` sends it to the
Three.js viewer, where you can orbit it; it is the same viewer `threeLines(...)` draws FLines in, so a
solid and its annotations can share one scene. `VisionGeometryPreview` is the other: a CPU z-buffer behind
`TriMesh.renderPng` and the inline `toHTML()` card, needing no GPU and, being deterministic, what the
pixel-exact tests assert on. Pass `renderer = MeshRenderer.CPU` to `showMesh` for that one. Both take
a `TriMesh` and nothing else.

What joins the two halves of that map is that the PLY and Assimp loaders produce ordinary host
memory (`memAlloc`, not a Vulkan allocation), so the *buffers* need no renderer even though the
*textures* do. `afield/four/modules/MeshFromDisk.kt` is where they cross over:
`PlyModel.toTriMesh()`, `assimpToTriMesh(path)`, the `meshFromAnyFile` dispatcher, and the
`showMesh(path)` overload at the top of this page. It lives in `afield.four` rather than
`afield.stdlib` so that `mesh_io.kt` stays dependency-free; `afield.four.modules.*` is in every box's
default imports, so a box calls these with no import either way.

## A real GLB, in a real output area

The pressure test is the Khronos ToyCar sample: the same 5.4 MB GLB the [Fabricate
renderer](fabricate_renderer.md) uses for its PBR work, so the two pages cannot disagree about the
file. It is a genuinely awkward model: three mesh/material buckets, a draped cloth with 60k triangles
of folds, glass, and everything positioned by node transforms rather than baked into the vertices.

```diffstatic_runner {data-lang=kotlin screenshot="assets/geometry_from_disk/toycar.png" wait="14.0" capture="box" open-outputs="true" padding="20"}
:::static
// The same file public/docs/fabricate_renderer.md renders with full PBR — here through the plain
// Three.js viewer instead, which is `showMesh`'s default and gives you a solid you can orbit.
// `renderer = MeshRenderer.CPU` for the static z-buffer figure, which needs no GPU at all.
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
    title = "ToyCar.glb — 108,936 triangles in the Three.js viewer",
)
:::endfold
```

![The Khronos ToyCar GLB in Field's Three.js viewer: a 1950s saloon model sitting on a heavily draped cloth, correctly shaded with soft folds, above a caption reading 77429 verts, 108936 tris, extent 0.073 x 0.029 x 0.074, chi=1909, open](assets/geometry_from_disk/toycar.png)

The caption under the picture is the mesh's own measurements:

- **77,429 vertices, 108,936 triangles** (326,808 indices), summed over the three buckets: ToyCar
  itself at 66,951 vertices, Fabric at 8,959 and Glass at 1,519.
- **extent 0.073 × 0.029 × 0.074** — the file is in metres, and it is a 7 cm toy. `MeshView`'s
  `distance` is measured in bounding-sphere radii, so the camera frames it correctly without your
  having to know that. `normalized(2.0)` is there if you want a common scale across several models.
- **χ = 1909, open, 2782 boundary loops.** An artist's model is a shell, not a solid, and every UV
  seam and every hard edge is a real cut in the surface. Expect this of anything that came out of a
  modeller, and do not reach for `repaired()` unless you actually need watertightness (for a volume,
  a boolean, or a printer).

!!! warning "The one trap in the Assimp path"

    `GltfSupport.loadGltfBasic(path)` with **default arguments cannot run outside a live renderer**.
    `GltfLoadConfig.loadTextures` defaults to `true`, and that path asks the box context for a
    `Renderer` so it can build stub textures. From a plain function, a test, or a headless script it
    therefore dies with a bare `NullPointerException` that says nothing about textures. Pass
    `GltfLoadConfig(loadTextures = false)` and geometry-only loading works everywhere.
    `assimpToTriMesh` does exactly that for you.

## A GLB is a scene, not a mesh

Which means flattening one into a single `TriMesh` has a domain, and you should know where its edges
are. A glTF file is a node hierarchy: each node carries a **full 4×4 transform**, several nodes can
reference the same mesh, and each mesh carries its own material. Here is what `meshFromAnyFile` does
with all of that, measured on a four-node scene built from scratch for the purpose: one asymmetric
triangle instanced by an identity node, a translated node, a *child* of that translated node, and a
**mirrored** node with `scale (-1, 1, 1)`.

<div class="field-data-table" markdown>

| the scene has | the bridge does |
|---|---|
| several nodes referencing meshes | **merges all of them.** Four instances arrive as 4 triangles in 4 components, not "the first one". |
| a node transform | **applies it.** ToyCar's own three nodes each carry a uniform `1e-4` scale, so a bridge that dropped transforms would render the figure above 10,000× too big: 730 m across, not 0.073 m. |
| a parent chain | **composes it.** The child of the node translated by `(3, 0, 0)`, itself translated by `(0, 2, 0)`, lands at `(3, 2, 0)`. |
| a **mirror** — negative determinant | applies it, **and reverses that part's winding**, so the surface still faces outward instead of being culled away. |
| non-uniform scale | normals go through the **inverse transpose**, not the matrix, so they stay perpendicular to the surface. |
| per-vertex `COLOR_0` | kept, per instance. An all-white array — what glTF writes when the file has no colours — is dropped. |
| quads or n-gons | already triangles: Assimp's `aiProcess_Triangulate` runs before the bridge sees anything. |
| materials and textures | **dropped.** A `TriMesh` has no concept of a texture. Use the [Fabricate renderer](fabricate_renderer.md) for those. |
| animation | **dropped.** The bind pose is baked; node TRS tracks and skinning are not read. |
| a Y-up / Z-up disagreement | **nothing.** glTF is Y-up by specification and no axis conversion is applied, so a Z-up PLY or OBJ arrives on its side. `rotated(...)` is yours to apply. |

</div>

??? aside "Placing instances yourself: `bindMatrix`, not `bindPose`"

    `bindPose` is a translation / rotation / scale triple, and a mirror does not fit in one —
    `Matrix4f.getScale` is a `sqrt` per basis column, so it is never negative. A mirrored node
    arrives with 3×3 determinant −1.0 and `bindPose.toMatrix()` gives it back at +1.0: the
    reflection is gone, the instance is placed un-mirrored, and nothing warns you. `bindMatrix` is
    the un-decomposed world matrix, and it is what `assimpToTriMesh` and the GPU paths read.

    `FabricateTransform` is itself a TRS triple, so `FabricateTransform.set(Matrix4f)` checks its
    own decomposition and keeps the original matrix when recomposing does not reproduce it. While
    that override is in force the TRS fields are a lossy summary — mutating them in place does
    nothing to what is drawn — and `clearMatrix()` goes back to composing them.

    `MeshInstance.applyToMesh` defaults `model` to the instance's own node transform, so a glTF
    scene positioned by its nodes assembles instead of stacking every part at the origin.
    `PlyModel.applyToMesh` defaults to the identity, because PLY has no scene graph to drop.

    The GPU path leaves two things alone, and `MeshInstance.bindMatrix`'s KDoc in
    `simpleGLTFSupport.kt` has the reasoning: winding is not reversed for a negative-determinant
    instance (every Field pipeline sets `VK_CULL_MODE_NONE`), and normals are `mat3(modelView) * n`
    rather than the inverse transpose, which is exact for a rigid, uniformly-scaled or mirrored node
    and skewed for a non-uniformly scaled one.

## Writing a file and reading it back

Do the round trip once yourself: what comes back is not always what went out.

Both examples below write into a temp directory, because a docs page must not leave files in the
repository when it runs. In a document of your own you would not do that: name the file and let
Field place it, with `"ball.obj".file` landing in the document's own `files/` folder next to the
boxes that made it. [Where Field looks for a file](files_and_paths.md) is that resolver in full.

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

![Three renders of the same 1280-triangle sphere side by side: the source in pale blue from its own per-vertex colours, the OBJ round trip in the renderer's neutral blue-grey because OBJ dropped the colours, and the welded STL round trip in the same neutral grey. The panels are headed "source — icosphereMesh(3), coloured", "via ball.obj — colours gone" and "via ball.stl, welded — normals gone"; the counts and volumes are in the printed lines above, not under the panels](assets/geometry_from_disk/roundtrip.png)

The pictures differ, and each difference is a format telling you what it cannot carry.

## What each format loses

Measured on `icosphereMesh(3, 1.0).withColor(0.3, 0.7, 1.0)`, which is 642 vertices, 1280 triangles,
area 12.506493 and volume 4.152741:

| Representation | verts | tris | normals | uvs | colours | χ | closed | area | volume |
|---|---|---|---|---|---|---|---|---|---|
| **source** | 642 | 1280 | yes | — | yes | 2 | watertight | 12.506493 | 4.152741 |
| **via `.obj`** | 642 | 1280 | **yes** | yes¹ | **no** | 2 | watertight | 12.506493 | 4.152741 |
| **via `.stl`** | **3840** | 1280 | **no** | no | no | **1280** | **open** | 12.506493 | 4.152741 |
| **via `.stl`, `weld(1e-5)`** | 642 | 1280 | **no** | no | no | 2 | watertight | 12.506493 | 4.152741 |

¹ uvs survive OBJ when the mesh has them; this sphere does not. A `heightfieldMesh` round-trips its
uvs to within 1e-5.

The coordinates always survive: area and volume are identical to six decimal places through every
route, so a mesh whose *shape* changed on a round trip is a bug and not a format.

OBJ keeps structure and loses colour. It indexes positions, normals and uvs separately, so Field's
reader emits one vertex per distinct `(position, uv, normal)` corner — which is why a hard-edged cube
from a modeller faithfully comes back with 24 vertices and not 8. There is no standardised
per-vertex colour in OBJ, so `withColor` never reaches the file. STL keeps nothing but triangles: no
vertex sharing at all, so 1280 triangles become 3840 unshared vertices and every edge reads as a
boundary. `weld(1e-5)` recovers the topology exactly but not the normals, because STL never stored
one per vertex, so re-shade after welding — `shadedNormals(35.0)` is usually right. PLY is the format
that carries colour, which is generally why a file is a PLY at all: it is what photogrammetry and
scanners emit, and `meshFromAnyFile` routes `.ply` through `PlySupport` rather than Assimp for that
reason.

File size for the same 1280 triangles, with binary STL's exactly `84 + 50n` bytes as the floor:

| Format | bytes |
|---|---|
| binary `.stl` | 64,084 |
| `.obj` | 75,625 |
| ASCII `.stl` | 324,825 |

## Where the counts come from

`summary()`, `toFigureHtml()` and the inline `toHTML()` card compute topology on the mesh you hand
them rather than remembering what it was, so a format artefact arrives looking like a property of
your model:

```diffstatic {data-lang=kotlin}
icosphereMesh(3).summary()
// 642 verts, 1280 tris, extent 2.0000 …, chi=2, genus 0, watertight, area 12.5065, volume 4.1527, …

stlToTriMesh(icosphereMesh(3).toStlBinary()).summary()
// 3840 verts, 1280 tris, extent 2.0000 …, chi=1280, genus 0, open(1280), area 12.5065, …
```

The second line has no `volume` at all, because `summary()` only prints one for a watertight mesh —
yet `signedVolume()` on that same soup returns 4.152741, exactly right. A rule about topology
dropped the number; no geometry was lost. `genus 0` is correct too, and it is not about a sphere:
1280 separate triangles are 1280 discs, each of genus 0.

An unexpected vertex count is usually shading rather than the file. Writing and reading are
faithful, so look upstream at what shaded the mesh:

| Construction | vertices |
|---|---|
| `boxMesh()` | 8 |
| `boxMesh().shadedNormals(35.0)` | 24 |
| `boxMesh().flatShaded()` | 36 |
| `objToTriMesh(boxMesh().toObj())` | 8 |
| `objToTriMesh(boxMesh().shadedNormals(35.0).toObj())` | 24 |

`flatShaded()` un-welds every triangle, so a solid stops being watertight and its Euler
characteristic becomes its triangle count. Save the *unshaded* mesh and let the renderer crease-shade
it, or weld after reading.

## PLY: colours, point clouds and missing normals

A temp directory again, for the same reason as above; `"square.ply".file` is the form to reach for
in a document.

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

## Missing formats and missing features

None of the following is hiding somewhere in the tree; if you need one, you are building it.

**STEP, IGES, BREP — any CAD boundary representation.** There is no CAD kernel on the classpath and no
STEP parser anywhere. This is the big one, and it is genuinely big: STEP is not a mesh format. A
`.step` file is NURBS surfaces trimmed against each other, and reading it means both an
ISO-10303-21 parser *and* a tessellator that can evaluate a trimmed rational surface to a triangle
mesh at a tolerance. Realistically that is an OpenCASCADE binding (a large native dependency with a
build story on three platforms), not a weekend of Kotlin. A far cheaper 80% is to convert outside
Field (FreeCAD and every CAD tool export STL) and read the STL. `meshFromAnyFile` on a `.step` throws
rather than returning an empty mesh, so at least the failure is loud.

**Textures and materials into a `TriMesh`.** A `TriMesh` has positions, normals, uvs and per-vertex
colours, and no concept of a texture; `assimpToTriMesh` drops the material Assimp read. Textured PBR
already exists on the other path (`gltfModelHere`,
[`FabricateGltfBridge`](reference/renderers.md#fabricategltfbridge-kt-fabricategltfbridge), the
[`StandardRenderer`](fabricate_renderer.md)), which is where to go for a *rendered* model — the
[Renderers](reference/renderers.md) reference page has that path's declarations in full. Bringing
textures across to the CPU side would mean sampling a `LoadedTexture` per vertex into vertex colours,
which is easy and lossy, or teaching `VisionGeometryPreview` to sample a texture, which is a real
change to the rasterizer.

**Point clouds as first-class geometry.** `PlySupport` reads them and `plyPointCloudHere` draws them
on the GPU, but there is still no CPU point-cloud *type*: no Poisson or ball-pivot surface
reconstruction, and no downsampling of a plain `PlySupport` cloud. `convexHullMesh` over the
positions and `voronoi3d` are what the tree has.

Gaussian splats are the exception, and a large one. `afield/four/modules/SplatRaster.kt` rasterizes a
splat cloud **on the CPU**, into PNG bytes, with no GPU and no window: it is a line-by-line
transcription of `afield/four/fabricated/GaussianSplatRenderer.kt`'s own Vulkan shaders, consuming
the same 16-float layout it packs, so the same `Config` numbers frame a cloud identically on both
sides. Its KDoc names the three places the two diverge and why (float accumulation instead of eight
bits, no depth test on either, coverage by pixel centre along a quad edge).
`SplatContactSheet` puts a whole 126-frame capture in one image, and `SplatCloud` is an editable
CPU form: crop, filter, decimate, transform, merge, and write a valid `.compressed.ply` back out.
None of that goes through `VisionGeometryPreview`; it is its own rasterizer, because a Gaussian is
not a triangle and the EWA projection has nothing to share with a scanline fill.

**Animation.** Assimp samples node TRS tracks and `GltfSupport.MeshInstance.animationSamples`
carries them, but `assimpToTriMesh` bakes the bind pose and throws the tracks away — a `TriMesh` is
one static surface. Skinned deformation is not read at all: no joints, no weights, no skin matrices.

**A scrub-through-steps mesh viewer.** Nothing lets you step back and forth through the stages of a
mesh pipeline in a UI. `showMeshGrid` (several meshes side by side) and `showMeshTurntable` (one mesh
from four angles) are the static approximations, and they are usually enough. The pieces for the real
thing exist (the [keyframer](keyframer_examples.md) captures live state, the
[history lens](history_lens.md) scrubs a box's past), but nobody has wired a mesh pipeline to either.

**Writing a `TriMesh` as anything but OBJ and STL.** `toObj`, `saveObj`, `toStlAscii`, `toStlBinary`
and `saveStl` are the whole mesh export surface; the `.compressed.ply` writer above is for splats,
not triangles. A PLY writer would be short and would let you *keep* per-vertex colours on the way
out, which neither current writer can; writing a GLB means the buffer/accessor/JSON scaffolding,
which is a day rather than an hour. Assimp can export, but LWJGL's binding exposes `aiExportScene`,
and building an `AIScene` by hand from a `TriMesh` is more work than emitting the text.

## When it does not look right

Six symptoms, in the order they actually happen, each with the one-line diagnosis. `summary()` answers
five of the six, so print it before you draw anything.

<div class="field-data-table" markdown>

| symptom | cause | what to do |
|---|---|---|
| a blank black panel | an empty mesh | A file whose *contents* are not what its extension says parses to `TriMesh.empty` with **no exception at all**. `println(mesh.summary())` — an empty one prints exactly `TriMesh(empty)`. |
| holes, or whole faces missing | inside-out winding, open mesh | Back faces are culled exactly as a GPU culls them, so a wrongly-wound patch is not dark, it is *gone*. `fixWinding()`, then check `topology().isConsistentlyOriented`. |
| looks fine, every measurement negative | inside-out winding, closed solid | The silhouette is unchanged and both renderers flip inward normals rather than shading them black, so the picture is no help at all. `signedVolume() < 0` is the only tell; `fixWinding()` is the fix. |
| flat, faceted or oddly uniform shading | no normals, or invented ones | A mesh with *none* is crease-shaded for you; one with wrong ones is not. Recompute with `shadedNormals(35.0)`. To drop them, `withoutAttributes()` — note that `copy(normals = null)` **keeps** them, because `null` there means "unchanged". |
| `isWatertight()` false on a closed solid | it came through STL | Or it was `flatShaded()` before it was written. `weld(1e-5)` recovers the topology, and `meanEdgeLength()` is the scale that tells you 1e-5 is safe rather than a guess. |
| the vertex count is exactly 3× | STL, or `flatShaded()` | Both un-weld every triangle, and the reader on the other side will not weld it back. `weld`, then `shadedNormals` if you want the hard edges again. |

</div>

The first row is the one that costs an hour: a file called `thing.obj` that
is actually a ZIP, a glTF, or an HTML error page parses to zero vertices and zero triangles and
raises nothing. `meshFromFile` is loud about a *missing* file and about an extension it does not read,
and silent about a file whose contents make no sense. `println(mesh.summary())` before `showMesh` costs
nothing and turns that hour into a second.

An inside-out open mesh really is *invisible*, not dark: a flipped 128-triangle plane patch renders
**zero** non-background pixels, with all 128 triangles reported as culled.

## From a file on disk to a solid you can cut

The table above is the manual version. `mesh_repair.kt` is the automatic one, and it exists because
the step after "I loaded a mesh" is usually "I want to boolean it", and
[`toSolid()`](manifold.md) refuses anything that is not already closed.

Two calls. `diagnose()` says *why*; `repairToSolid()` fixes what it can and tells you what it could
not.

```diffstatic {data-lang=kotlin}
val mesh = meshFromAnyFile("scan.glb")
println(mesh.diagnose().report())          // why, worst first
val fixed = mesh.repairToSolid()
println(fixed.report())                    // what was done, and what was not
if (fixed.isSolid) display(fixed.mesh.toSolid() - drill)
```

### Defect severities: what blocks a solid

Each severity below comes from damaging a known-good mesh in exactly one named way and asking
Manifold whether it accepted the result. The answers are not the ones folklore predicts.

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

The three *suspicious* rows are the reason that severity exists at all rather than a pass/fail. The
dangerous one is `INVERTED_ORIENTATION`: an inside-out closed mesh is a perfectly legal manifold, and
Manifold hands back a **negative volume**. Nothing throws. The boolean you do next computes the
complement of what you meant, with nothing to flag it. A checker that only answered "valid / not
valid" could not tell you that.

`UNWALKABLE_BOUNDARY` deserves a note, because the first thing it usually means is not the exotic
thing. Tracing a boundary into a ring follows directed half-edges, so **inconsistent winding breaks
the trace** and a perfectly ordinary hole reports as unwalkable. Run `fixWinding()` and diagnose
again before believing the exotic reading (two holes meeting at one vertex).

### The repair steps, in order

`repairToSolid()` runs these in this order, and the order is causal: each step exists because the
next would give a wrong answer without it.

| step | fixes | does **not** fix |
|---|---|---|
| `weld` | unwelded seams, STL-style unshared vertices | anything, until it has run: hole and component counts are meaningless before it |
| `removeDegenerateTriangles` | zero-area faces | — |
| `removeDuplicateFaces` | duplicated faces; a back-to-back pair cancels to nothing | — |
| `removeNonManifoldFins` | dangling flaps (a non-manifold edge **and** a boundary edge on the same triangle) | two closed sheets meeting on an edge — that needs a modelling decision, so it is left alone and reported |
| `fixWinding` | inconsistent orientation | — |
| `fillHoles` | open boundary loops | a non-planar or self-intersecting rim gets a cap it flags as suspect |
| `fixWinding` again | inside-out components | — |

The second `fixWinding` does different work from the first. Its outward-facing stage only flips a
component that is *closed*, because "outward" is undefined for an open sheet, so before the caps
exist every holed component is skipped. Running it again afterwards is what stops a repaired mesh
coming back inside out with a negative volume.

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

#### The failure mode no cap check can catch

**No local property of a hole distinguishes "this end should be a flat cap" from "the model continued
here."** Two measured cases settle it, and they point opposite ways:

| Input surface | rim | cap | cap share of surface | result |
|---|---|---|---|---|
| uncapped cylinder | flat | clean ear clip | 16.5% | volume error **0.00000** — exactly right |
| torus with its tube severed | flat | clean ear clip | 2.7% | volume error **4.2%**, and **genus 1 becomes 0** |

Planarity, fill method, edge count and cap area are all equal-or-*better* on the wrong one, which is
what defeats a cap-area threshold: any cut-off that flags the severed torus flags the cylinder too,
whose caps are correct. `capAreaFraction` is reported as a fact and does not set `isSuspect`.

So the contract is: **`isSuspect == false` means the cap's geometry is sound, not that the model is
the one you started with.** Only comparison against the shape you meant catches the difference, and at
run time you do not have it.

### What `repairToSolid` does

Beyond what `repaired()` (weld → degenerate → unused → winding, in `mesh_topology.kt`) already
does, `repairToSolid()` is responsible for exactly two families: **open boundaries**, and
**non-manifold edges** from duplicated faces or dangling fins.

It refuses rather than guesses on two shapes. A `NaN` position is refused outright, because
repairing around it would silently delete the affected triangles and cap the gap; so are two closed
tetrahedra sharing a face. Both report the failure instead of returning something that looks
repaired.

Of a table of 21 broken meshes Manifold rejects, `repairToSolid()` makes 19 valid — and 15 of those
19 match the original's volume, area and genus. The other four are valid solids of the wrong shape,
and they are all curved or non-planar holes, where a fan fill is doing the best it can: the worst,
a crowned rim, is 35% off by volume. That gap between 19 and 15 is the whole reason for the
paragraph above: compare against the shape you meant, not against `isSuspect`.

The 27-mesh table, what each row is broken in, and the per-row residuals are in
`dev_docs/MESH_REPAIR_MEASUREMENTS.md`.

### ToyCar, the one real asset

`ToyCar.glb` (77,429 vertices, 108,936 triangles, 2,197 components, 42,104 boundary edges in 2,782
traced loops) **repairs to a valid solid you can boolean**. 21,047 vertices welded, 84 holes closed
with 3,668 triangles.

That is the weaker of the two claims. Unlike every row of the test table, ToyCar has **no undamaged
original to check against**, so "valid solid" is all anyone can say about it, which is precisely the
distinction the table exists to draw.

And the volume it then reports is `3.16e-5`, which is a number rather than an answer. Nine of those
84 caps are flagged suspect, one of them a 335-edge centroid fan — a lid the size of a body panel.
`result.suspectFills` is the list to look at before trusting any measurement, and on a multi-part
export of this size a repair that flagged *nothing* would mean the flagging was broken, not that the
model was clean.

### Seeing it

`Box.showMeshDiagnosis(mesh)` puts the mesh, painted by defect, beside the ordered list of what is
wrong. `TriMesh.defectFLines()` gives the same information as drawable lines to lay over your own
drawing: red for boundary loops, orange for non-manifold edges, magenta for pinched figure-eight
loops, yellow (opt-in) for winding disagreements. `TriMesh.withDefectColors()` is the per-vertex
colouring the card renders, if you want it in your own picture.

```diffstatic_runner {data-lang=kotlin screenshot="assets/geometry_from_disk/mesh_defects.png" wait="9.0" capture="box" open-outputs="true" padding="20"}
:::static
// A sphere with a patch of faces removed, and one more face left unwelded — the two commonest
// ways a mesh off disk fails. Built here rather than loaded, so the picture needs no file.
val base = icosphereMesh(3, 1.0)
var broken = base.filterTriangles { it >= 4 }

// Give triangle 0 its own three vertices: the crack an STL export leaves at every seam.
val pos = broken.positions.copyOf(broken.positions.size + 9)
val idx = broken.indices.copyOf()
for (k in 0..2) {
    val v = broken.indices[k]
    val d = (broken.vertexCount + k) * 3
    pos[d] = broken.x(v); pos[d + 1] = broken.y(v); pos[d + 2] = broken.z(v)
    idx[k] = broken.vertexCount + k
}
broken = TriMesh(pos, idx)

println(broken.diagnose().report())
:::fold draw it
// No `view` argument, and that is the point: the camera aims itself at the worst defect. The hole
// is on the far side from the default three-quarter view, so without that this figure would be a
// photograph of an apparently perfect sphere captioned "4 faces removed".
showMeshDiagnosis(broken, title = "4 faces removed, plus one unwelded face")
:::endfold
```

![A grey wireframed icosphere with a black triangular hole punched through it, seen from a camera pointed straight at the damage. A red halo stains the ring of vertices around the hole, and a second, smaller red patch sits just below and right of it where one face was left unwelded. Beneath the render a legend reads "red = open boundary, orange = non-manifold edge, yellow = pinch vertex", then "645 verts, 1276 tris, 2 blocking" and three defect rows: BLOCKING UNWELDED_VERTICES x3, BLOCKING OPEN_BOUNDARY x12 with edge counts 6, 3, 3, and INFORMATIONAL MULTIPLE_COMPONENTS x2, each with the call that fixes it](assets/geometry_from_disk/mesh_defects.png)

`showMeshDiagnosis` **points the camera at the worst defect** unless you pass a view, which is the
only reason the hole is in shot above: it sits on the far side of the sphere from the default
three-quarter view. `TriMesh.defectFacingView()` is that camera, if you want it for your own render.

The red halo and the smaller red patch in the picture are the two blocking rows of the list beside
it, which is the case for a card over a `println`. The colouring is checked on the raster rather than
on the call returning: `MeshDefectRenderTest` requires a cube with a face removed to paint red where a
clean one paints essentially none (3,345 pixels against a ceiling of 200), and a non-manifold fin to
do the same in orange (2,579 against a ceiling of 100).

### Open this to see it

`public/docs/assets/mesh_repair_demo/` holds two boxes: one damages a sphere and shows the diagnosis
card, the other repairs it, prints what was done, and cuts the result. Both run with no files and no
network.

## What it costs

Reading a mesh off disk is cheap enough to do in a live box on every re-run. Measured on an M-series
laptop, five reads after two warm-up reads:

| Loader and input | file | triangles | time | rate |
|---|---|---|---|---|
| `ToyCar.glb` via Assimp | 5.4 MB binary | 108,936 | **83 ms** | 1310 k tri/s |
| `big.obj` via `meshFromFile` | 9.4 MB text | 132,488 | **337 ms** | 393 k tri/s |
| `big.stl` via `meshFromFile` | 6.6 MB binary | 132,488 | **185 ms** | 718 k tri/s |
| `big.obj` via `assimpToTriMesh` | the same 9.4 MB | 132,488 | **223 ms** | 595 k tri/s |

Two things fall out of that table. **Binary beats text by about 2×** for the same mesh, so prefer STL
over OBJ when the file is an intermediate nobody reads. And **Assimp's OBJ reader is about 1.5×
faster than the one in `mesh_io.kt`** — it is native, and the Kotlin one splits strings.
`meshFromAnyFile` still routes `.obj` to the Kotlin reader, because its corner-triple handling makes a
round trip index-for-index and 100 ms on a 9 MB file is not the bottleneck. If you are reading
hundreds of OBJs in a loop, `assimpToTriMesh` on them directly is the faster call.

Looking at it costs more than reading it, and not where you would guess:

| Rendering operation | on 108,936 triangles |
|---|---|
| `renderPng(560×420, wireframe = false)` | 51 ms |
| `renderPng(560×420, wireframe = true)` | 114 ms |
| `toFigureHtml(…)` — what `showMesh(renderer = MeshRenderer.CPU)` calls | **419 ms** |

The raster is 51 ms; the depth-tested wireframe doubles it; and the figure costs **four times the
render**, because the caption's `χ`, genus, watertightness and boundary-loop count need a full topology
pass (edge map, vertex stars, boundary walk) over the whole mesh. On a model with 2782 boundary
loops that is the expensive part of the call, not the picture. If you are re-rendering in a loop and
do not need the numbers, call `renderPng` and `display` the base64 yourself.

Those are the **CPU** path's numbers, which is what `showMesh(renderer = MeshRenderer.CPU)` calls.
The default `showMesh` uploads the mesh to the Three.js viewer once and then costs nothing per frame,
so the trade is the other way round: a higher fixed cost to open the viewer, and free re-rendering
afterwards because the re-rendering is the GPU's. The topology pass behind the caption costs the same
either way — it is the same `topology()` call.

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

The figures above are each a real box's own output area, captured by the docs build running the
fence above them. The ToyCar figure reads a committed 5.4 MB binary,
`assets/fabricate_demo/ToyCar.glb`; replace that file and this page's counts describe a different
car. The counts themselves are pinned in
`src/jvmTest/kotlin/afield/stdlib/MeshFromDiskTest.kt`, and `MeshIoTest.kt` covers the reader
behaviour they rest on: quads, negative indices, the `solid`-prefixed-binary sniff trap, uv
survival.

??? aside "Forcing these captures to re-run"
    A fence is re-executed only when the build decides its capture is stale, from a digest over the runner
    machinery plus the **stdlib** source files the fence's code names. `MeshFromDisk.kt` is in
    `afield.four`, not `afield.stdlib`, so editing *it* does not invalidate these captures on its own.
    Force them with `FIELD_DOCS_RUNNER_MODE=always` after changing the bridges.
    [`mesh_examples.md`](mesh_examples.md) carries the same caveat.

## Playing with it instead of reading it

The ToyCar, round-trip and PLY fences are also a loadable document. Replay
`dev_docs/agents/transcripts/geometry_from_disk.yaml` into a fresh Field with the agent backdoor
(`field_run_transcript`), or open it from the [transcript gallery](agent_transcripts.md), and you get
the same three boxes live. Point the first box's `path` at a mesh file of your own and re-run it;
change the second box's weld tolerance, or the third's corner colours, and watch the numbers and the
picture move together. Boxes two and three write only into a temp directory they delete themselves.
The transcript is itself re-executed on every build, so the GLB path is checked as code and not only
as a picture.

## Reference

### Read, any format

Load a mesh through the format-dispatching entry point; convert imported scene or PLY data when you need a `TriMesh`.

<div class="field-reference" data-code-lang="kotlin" markdown>

| Entry point | Behavior |
| --- | --- |
| `meshFromAnyFile(path)` | Loads OBJ, STL, PLY or an Assimp-supported scene into a CPU `TriMesh`. Scene parts are baked through their transforms; unreadable paths throw instead of returning an empty mesh. |
| `fun Box.showMesh(mesh: TriMesh, channel: String = "main", view: MeshView = MeshView(), caption: String? = null, title: String? = null, renderer: MeshRenderer = MeshRenderer.GPU, height: Int = 430)` | Push a rendered view of `mesh` to one of this box's output channels, with its measurements as a caption. [API](reference/graphics.md#mesh-field-kt). |
| `assimpToTriMesh(path, keepColors = true)` | Flattens a supported scene file into a `TriMesh`, applying node transforms. Retain per-vertex colours when present; textured materials require the renderer path. |
| `fun VoronoiLattice.toTriMesh(shrink: Double = 0.92, boundedOnly: Boolean = false): TriMesh` | Every cell of a 3D Voronoi lattice as one [`TriMesh`](reference/graphics.md#mesh-kt-trimesh), one connected component per cell. [API](reference/graphics.md#mesh-field-kt). |

</div>

### Read, no dependencies

Read OBJ or STL through the direct readers when no native scene importer is needed.

<div class="field-reference" data-code-lang="kotlin" markdown>

| Entry point | Behavior |
| --- | --- |
| `fun meshFromFile(path: String): TriMesh` | Load a mesh from a path, choosing the reader by extension (`.obj`, `.stl`). [API](reference/graphics.md#mesh-io-kt). |
| `fun triMeshFromObj(file: File): TriMesh` | Read an OBJ file — see `objToTriMesh` for what survives. [API](reference/graphics.md#mesh-io-kt). |
| `fun objToTriMesh(text: String): TriMesh` | Parse Wavefront OBJ text into a [`TriMesh`](reference/graphics.md#mesh-kt-trimesh). [API](reference/graphics.md#mesh-io-kt). |
| `fun triMeshFromStl(file: File): TriMesh` | Read an STL file, ASCII or binary — see `stlToTriMesh`. [API](reference/graphics.md#mesh-io-kt). |
| `fun stlToTriMesh(bytes: ByteArray): TriMesh` | Parse STL, ASCII or binary — the format is sniffed, not declared. [API](reference/graphics.md#mesh-io-kt). |

</div>

### Write

Write OBJ or STL files for other tools. Check the format comparison above before relying on preserved normals or colours.

<div class="field-reference" data-code-lang="kotlin" markdown>

| Entry point | Behavior |
| --- | --- |
| `fun TriMesh.toObj(name: String = "mesh", decimals: Int = 6): String` | This mesh as Wavefront OBJ text: `v` positions, then `vn` normals and `vt` uvs if present, then `f` faces with 1-based indices. [API](reference/graphics.md#mesh-io-kt). |
| `fun TriMesh.saveObj(file: File, name: String = file.nameWithoutExtension)` | Write this mesh to `file` as OBJ. Creates parent directories. [API](reference/graphics.md#mesh-io-kt). |
| `fun TriMesh.toStlAscii(name: String = "mesh"): String` | This mesh as ASCII STL. One `facet` per triangle with its face normal, no vertex sharing (the format has none). [API](reference/graphics.md#mesh-io-kt). |
| `fun TriMesh.toStlBinary(header: String = "Field afield.stdlib mesh_io.kt"): ByteArray` | This mesh as binary STL: an 80-byte header, the triangle count, then 50 bytes per triangle (little-endian float normal, three float positions, a zero attribute short). [API](reference/graphics.md#mesh-io-kt). |
| `fun TriMesh.saveStl(file: File)` | Write binary STL to `file`. Creates parent directories. [API](reference/graphics.md#mesh-io-kt). |

</div>

### Look at it

Inspect shape and appearance in an output panel. Choose a grid for comparisons and a turntable for views from several angles.

<div class="field-reference" data-code-lang="kotlin" markdown>

| Entry point | Behavior |
| --- | --- |
| `fun Box.showMesh(mesh: TriMesh, channel: String = "main", view: MeshView = MeshView(), caption: String? = null, title: String? = null, renderer: MeshRenderer = MeshRenderer.GPU, height: Int = 430)` | Push a rendered view of `mesh` to one of this box's output channels, with its measurements as a caption. [API](reference/graphics.md#mesh-field-kt). |
| `fun Box.showMeshGrid(vararg panels: Pair<String, TriMesh>, channel: String = "main", columns: Int = 2, cellWidth: Int = 380, view: MeshView = MeshView(), renderer: MeshRenderer = MeshRenderer.GPU)` | Several meshes side by side, each rendered and captioned — the fastest way to see a whole pipeline (generated / subdivided / smoothed / decimated) side by side. [API](reference/graphics.md#mesh-field-kt). |
| `fun Box.showMeshTurntable(mesh: TriMesh, channel: String = "main", views: List<MeshView> = MeshView.turntable(4), cellWidth: Int = 260, title: String? = null, renderer: MeshRenderer = MeshRenderer.GPU)` | A turntable contact sheet of one mesh: the same shape from `views`, laid out in a row. [API](reference/graphics.md#mesh-field-kt). |
| `MeshView` | How to draw a [`TriMesh`](reference/graphics.md#mesh-kt-trimesh) in a preview: where the camera stands and what the frame looks like. [API](reference/graphics.md#mesh-field-kt). |
| `fun TriMesh.summary(): String` | A one-line factual summary of a mesh: counts, extent, topology and volume — the thing to `print` when you want the numbers rather than the picture. [API](reference/graphics.md#mesh-field-kt). |
| `three(subject = mesh)` | Opens an orbitable Three.js view of a mesh. Use a scene block to combine solids and line annotations. |
| `threeGrid(…)` | Displays several subjects as titled viewports. Use it for side-by-side geometry comparisons. |
| `threeTurntable(…)` | Displays a subject from a sequence of viewing directions. Use it to inspect shape beyond one fixed camera. |

</div>

### Look at it without a gpu

Render a still or an interactive figure using the CPU path when a native GPU renderer is unavailable.

<div class="field-reference" data-code-lang="kotlin" markdown>

| Entry point | Behavior |
| --- | --- |
| `fun TriMesh.renderPng(view: MeshView = MeshView(), caption: String? = null, title: String? = null): ByteArray` | This mesh rasterised to PNG bytes: perspective, z-buffer, two-light lambert shading over the per-vertex colours, and a depth-tested wireframe. [API](reference/graphics.md#mesh-field-kt). |
| `fun TriMesh.renderPixels(view: MeshView = MeshView()): VisionGeometryPreview.Raster` | This mesh's raster as ARGB ints plus its size — for compositing several meshes into one figure. [API](reference/graphics.md#mesh-field-kt). |
| `fun TriMesh.toFigureHtml(view: MeshView = MeshView(), caption: String? = null, title: String? = null): String` | A complete `<figure>` — this mesh rendered as an inline PNG plus its measurements as a caption — for `display(…)` in a box output or for the value visualizer. [API](reference/graphics.md#mesh-field-kt). |
| `fun Box.showMesh(mesh: TriMesh, channel: String = "main", view: MeshView = MeshView(), caption: String? = null, title: String? = null, renderer: MeshRenderer = MeshRenderer.GPU, height: Int = 430)` | Push a rendered view of `mesh` to one of this box's output channels, with its measurements as a caption. [API](reference/graphics.md#mesh-field-kt). |

</div>

### Repair what you read

Repair connectivity before solid operations. Normal generation and normalization affect shading or scale, so choose them separately from topology repair.

<div class="field-reference" data-code-lang="kotlin" markdown>

| Entry point | Behavior |
| --- | --- |
| `fun TriMesh.weld(tolerance: Double = 1e-6): TriMesh` | Merge vertices closer together than `tolerance` and renumber the triangles onto the survivors. [API](reference/graphics.md#mesh-topology-kt). |
| `fun TriMesh.fixWinding(): TriMesh` | Make the winding consistent across the whole mesh, then make it point **outward**. [API](reference/graphics.md#mesh-topology-kt). |
| `fun TriMesh.removeDegenerateTriangles(areaEpsilon: Double = 1e-14): TriMesh` | Drop triangles with no area — a repeated index, or three collinear corners — and any vertex that leaves unreferenced. [API](reference/graphics.md#mesh-topology-kt). |
| `fun TriMesh.repaired(tolerance: Double = 1e-6): TriMesh` | Weld, drop degenerate triangles, drop unused vertices and fix the winding — the one call to make before trusting a mesh that came from a file, a scanner, or a per-cell generator. [API](reference/graphics.md#mesh-topology-kt). |
| `fun TriMesh.largestComponent(): TriMesh` | The largest connected component, by triangle count — the usual way to throw away the specks a noisy iso-surface leaves around the shape you wanted. [API](reference/graphics.md#mesh-topology-kt). |
| `fun TriMesh.shadedNormals(creaseAngleDegrees: Double = 35.0): TriMesh` | Crease-aware normals: smooth across edges where the two faces are within `creaseAngleDegrees` of each other, hard where they are not. [API](reference/graphics.md#mesh-process-kt). |
| `fun TriMesh.normalized(size: Number = 1.0): TriMesh` | This mesh recentred on its own bounding box and scaled so its longest axis is `size`. [API](reference/graphics.md#mesh-field-kt). |

</div>

### On the gpu instead

Load an asset directly into a native renderer when drawing is the next step rather than CPU mesh processing.

<div class="field-reference" data-code-lang="kotlin" markdown>

| Entry point | Behavior |
| --- | --- |
| `plyModelHere("model.ply")` | Loads a PLY model into the current renderer’s module context. Choose this path for GPU drawing rather than CPU mesh processing. |
| `plyPointCloudHere("scan.ply")` | Loads PLY data for point-cloud drawing in the current renderer. Use it when samples should remain points instead of forming a triangle surface. |
| `gltfModelHere("model.glb")` | Loads glTF geometry, materials and textures into the renderer. This requires a live renderer and preserves rendering data a flattened CPU mesh does not. |
| `modelWithTextureHere(…)` | Loads geometry with a texture for a renderer module. Use the material-aware scene path when appearance matters alongside vertex positions. |

</div>

### Let the ai read it

Register geometry locations with Field’s AI tools so it can resolve and inspect those assets.

<div class="field-reference" data-code-lang="kotlin" markdown>

| Entry point | Behavior |
| --- | --- |
| `GeometryAgentTools.addSearchRoot(directory)` | Registers a directory to search when an AI geometry tool resolves assets. The argument is a `File` naming the directory. |
| `GeometryAgentTools.register()` | Registers the geometry inspection tools with Field’s AI tool registry. Add asset search roots so those tools can find the intended files. |

</div>


## Letting Field's AI look at your geometry

Two lines turn everything on this page into something the AI can use:

```diffstatic {data-lang=kotlin}
GeometryAgentTools.addSearchRoot(File(System.getProperty("user.home"), "scans"))
GeometryAgentTools.register()
```

`ListGeometryFiles` tells the model what you have. `InspectGeometryFile` loads one and returns the
same measurements this page describes, plus a *rendered image*: the CPU rasteriser behind it needs no
GPU and no window, so a model can look at a scan rather than reason about its bounding box. It will
only read inside roots you have added.

See [Give the AI a tool of your own](aiFeatures.md), which uses this tool set as its worked example
and shows the schema and the picture the model receives.

Related pages: [Meshes and 3D geometry](mesh_examples.md) for everything you can *do* with a mesh
once you have one; [the Fabricate renderer](fabricate_renderer.md) for textured PBR rendering of the
same files; [afield.four graphics](afield_four_graphics.md) for the module/shader layer the GPU
loaders plug into; [FLine examples](fline_examples.md) for `toFLines`, the route from a mesh back into
Field's drawing. Developer notes: `dev_docs/MESH_GEOMETRY.md` and `dev_docs/ply_loader.md`.
