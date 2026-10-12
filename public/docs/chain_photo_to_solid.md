# A photograph, closed and mounted

This page turns one photograph into a printable object. `afield.onnx` runs the Depth Anything V2
Small model, which predicts how far away everything in a still image is. Field's imaging functions
normalise and smooth that prediction, its mesh functions turn it into triangles, and
`afield.manifold` closes and combines the result as a solid. The
[photo_to_solid_chain](agent_transcripts.md#photo_to_solid_chain) starting point has the whole
route as a document you can open.

The photograph is a Vision Pro passthrough frame of a living room, the same one the
[ONNX page](onnx.md) uses. No depth camera is involved. The model's weights download on the first run
and are cached under `~/.afield/onnx/models` after that.

The frame has Field's own interface drawn over the room: a panel, some buttons, a blue triangle and
an orange curve. The model cannot tell those from surfaces, and the large dark rectangle in the
middle of the depth map is where the semi-transparent panel is. The steps below work on any depth
map, but a clean photograph would give a different plaque.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_photo_to_solid/depth.png" capture="box" open-outputs="true" wait-output=true wait-output-kind="all" wait-output-contains="input forced to a multiple of 14" embed=true force=true timeout="300" w="1120" h="380" padding="18" name="chain-photo-depth" alt="Two panels: a photograph of a pale living room with framed prints, a television on a grey wall and a wooden floor, beside its depth map in the inferno colormap, where the television wall is almost black and the near floor along the bottom edge is a bright orange-yellow band" macro-file="../../dev_docs/agents/transcripts/photo_to_solid_chain.yaml" capture-box="photo_depth"}
// Built and launched from dev_docs/agents/transcripts/photo_to_solid_chain.yaml.
```

Read the right-hand panel as a height map and the room is **concave**. The side walls and the near
floor are close to the camera, the far wall is not, so the brightest values ring the edges of the
frame and the darkest sit in the middle. So the solid this page builds is a basin: the room turned
inside out.

## Closing a heightfield into a solid

`Img.toHeightfieldMesh` turns a raster into a surface, but that surface is a sheet with no
underside, and `TriMesh.toSolid` refuses an open sheet. A sheet is fine for drawing; an exact
Boolean, a volume or a printed object needs a closed solid. `TriMesh.repairToSolid` does not give
the shape you want either: it fits a lid across the sheet's rim, which encloses only the sliver
between the lid and the surface.

`Img.toReliefMesh` (and `reliefMesh`, for a plain grid of heights) drops the rim straight down to a
flat base and floors it, the way a lithophane, a relief map or a stamp is made:

```diffstatic {data-lang=kotlin}
// before: two calls that do not compose
val open = depth.toHeightfieldMesh(scale = 0.35)   // χ = 1, one boundary loop
open.toSolid()                                     // refused: not a closed manifold

// after
val plaque = depth.toReliefMesh(scale = 0.35, baseThickness = 0.12)  // χ = 2, no boundary loops
plaque.toSolid()                                                     // legal
```

You can predict the size of the mesh before you build it. With `P = 2·nx + 2·nz` rim vertices, a
relief has `(nx+1)(nz+1) + P + 1` vertices (the grid, the rim at the base, and one centre vertex the
floor fans out from) and `2·nx·nz + 3·P` triangles. Its Euler characteristic is `2` at every
resolution, as a closed surface with no holes should be.

??? aside "The rim winds clockwise in `(x, z)`"

    The rim runs clockwise in `(x, z)`, because `x̂ × ẑ = −ŷ`: a clockwise loop in those two
    coordinates is the one whose right-hand normal points `+y`. That matches the way the grid quads
    wind, so each wall crosses its shared rim edge in the opposite direction, as a consistently
    oriented closed mesh requires. Wound the other way, the mesh still renders, with a negative
    volume.

## What crosses each hop

| hop | the call | what crosses | what stops here |
| --- | --- | --- | --- |
| photograph to depth | [`DepthAnything.infer`](reference/machine-learning.md) | one single-channel map, in the model's own arbitrary units | the photograph's colours, and any absolute scale. The output is *inverse relative* depth and needs a normalisation window |
| depth to a surface | [`Img.toReliefMesh`](reference/graphics.md) | positions, on one grid vertex per sampled pixel | every pixel between samples. `step` and the resample before it are where the resolution decision lives |
| a surface to a solid | [`TriMesh.toSolid`](reference/graphics.md) | positions and indices | normals, uvs, colours. The binding hands Manifold three doubles per vertex and nothing else |
| a solid through a Boolean | [`Solid.plus`](reference/graphics.md) | a closed surface | any correspondence with the triangles that went in. A Boolean retriangulates |
| a solid to triangles | [`Solid.toTriMesh`](reference/graphics.md) | positions, indices, optionally split normals | watertightness, *if* you ask for normals — see below |

## The plaque

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_photo_to_solid/plaque.png" capture="box" open-outputs="true" wait-output=true wait-output-kind="all" wait-output-contains="a plaque has none" wait="3" embed=true force=true timeout="300" w="1120" h="440" padding="18" name="chain-photo-plaque" alt="Three renders of the same pale wireframed relief plaque on a dark background: from above, where the room reads as a shallow basin with a raised near edge and raised side walls; from three-quarters, where the flat base slab is visible under the relief; and edge-on along the rim, where the slab and the vertical skirt joining it to the surface are clearest" macro-file="../../dev_docs/agents/transcripts/docs_capture/photo_plaque.yaml" capture-box="photo_plaque"}
// Built and launched from dev_docs/agents/transcripts/docs_capture/photo_plaque.yaml.
```

The caption reads `watertight=true manifold=true chi=2` and `boundary loops=0`, where the
heightfield it came from has one boundary loop. Field's `signedVolume` and Manifold's own volume
agree on the plaque. If you change `scale` or `baseThickness` and the plaque stops looking right,
compare those two first.

## Mounted in a frame

A picture frame is a slab with a rectangular window, so it is a torus and its genus is `1`. Union it
with the plaque and the plaque's base blocks the window, so the assembly's genus is `0`. The box
mounts the plaque and reports both.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_photo_to_solid/boolean.png" capture="box" open-outputs="true" wait-output=true wait-output-kind="all" wait-output-contains="inclusion-exclusion holds to" embed=true force=true timeout="300" w="1120" h="440" padding="18" name="chain-photo-boolean" alt="Three renders on a dark background: the relief plaque with a notch cut around its rim, a plain rectangular frame ring that is visibly a torus, and the two assembled with the frame lip standing proud around the relief" macro-file="../../dev_docs/agents/transcripts/photo_to_solid_chain.yaml" capture-box="photo_boolean"}
// Built and launched from dev_docs/agents/transcripts/photo_to_solid_chain.yaml.
```

The identity in that readout is inclusion–exclusion, which holds for any two solids at all:

```diffstatic {data-lang=text}
vol(A ∪ B) + vol(A ∩ B) = vol(A) + vol(B)
```

It is a quick check on any Boolean. A union that dropped a component, a difference that kept a
coincident face, or an intersection that misclassified a shell changes one side and not the other.
The companion `vol(A − B) = vol(A) − vol(A ∩ B)` is checked in the same box.

The renders come from `TriMesh.renderPng`, a CPU rasteriser with a depth-tested wireframe. It does
not use AWT, so it is safe to call from a box on macOS. For a lit picture instead of a wireframe,
the same mounted relief and its frame can go through the GPU path tracer:

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_photo_to_solid/mounted_path_trace.png" capture="native-window" native-wait-frames="3" wait-output=true wait-output-kind="all" wait-output-contains="photo_mount_trace study converged" embed=true hidden=true timeout="300" requires="manifold" name="elsewhere-photo_mount_trace" alt="The mounted relief and its frame, lit and rendered with the GPU path tracer." macro-file="../../dev_docs/agents/transcripts/photo_mount_path_trace.yaml" capture-box="photo_mount_trace" capture-profile="linux-nvidia"}
// Built and launched from dev_docs/agents/transcripts/photo_mount_path_trace.yaml.
```

### Asking for normals breaks the round trip

A renderer wants `toTriMesh(normals = true)`, but a mesh made that way cannot go back through
`toSolid`. Manifold refuses it, and `MeshDiagnosis` says why:

```diffstatic {data-lang=text}
this TriMesh is not a valid manifold: NOT_MANIFOLD.
mesh: 18389 vertices, 35578 triangles, chi=7, 11 component(s)
2 blocking defect(s) — Manifold will refuse this mesh.
1. [BLOCKING] UNWELDED_VERTICES x598
     598 of 18389 vertices are duplicates within 1.0E-6; until they are merged every seam reads as a hole
     fix: TriMesh.weld(1.0E-6)
2. [BLOCKING] OPEN_BOUNDARY x1186
     1186 boundary edges in 15 traced loop(s), edge counts [457, 195, 180, 178, 103, 30, 10, 6] …
```

`normals = true` splits a vertex wherever two faces disagree about the surface direction, and being a
manifold is a property of the **indices**: two vertices at one position that share an edge nowhere
read as a line of holes. The [Manifold page](manifold.md) gives the rule: ask for `toTriMesh()`
when you are measuring and `toTriMesh(normals = true)` when you are drawing. Pass the unsplit mesh
along and let each renderer add normals itself, as `renderPng` does.

## Two constructions of the same solid

Marching an image as a field gives the same surface `Img.toHeightfieldMesh` builds directly, to
about one marching cell. One walks a grid in Kotlin; the other is Manifold's own marcher, calling
back into Kotlin for each sample. Either gets a plaque out of a depth map, and `Img.asHeightSdf` is
the entry point to the second. This box builds both and compares them.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_photo_to_solid/levelset.png" capture="box" open-outputs="true" wait-output=true wait-output-kind="display" wait-output-contains="eroded to islands" embed=true force=true timeout="900" w="1100" h="420" padding="18" name="chain-photo-levelset" alt="A four-column table headed regime, components, parallel = false and parallel = true. Each of three rows names a regime and its component count, and the parallel = false column reads 1/8 distinct on every row while the parallel = true column varies" macro-file="../../dev_docs/agents/transcripts/photo_to_solid_chain.yaml" capture-box="photo_levelset"}
// Built and launched from dev_docs/agents/transcripts/photo_to_solid_chain.yaml.
```

### `Solid.levelSet(parallel = true)` is not reproducible

The second half of that box runs the same level set eight times on each row of the table, with
`parallel = false` and with `parallel = true`. Only `floor` changes from row to row: raising it
erodes the plaque so that only the nearer parts of the photograph survive, as islands. Two runs
count as the same answer only if the triangle count, the component count and the volume all agree.

- `parallel = false` gives the same answer on all eight runs, on every row.
- `parallel = true` does not, even on the first row, where the plaque is a single solid block. It
  is worst on the most fragmented row. The exact figures change from one capture to the next.
- `parallel = true` can also agree with itself on the wrong answer: at some values of `floor` it
  finds one component on every run where the sequential path finds two.

So use `parallel = false` for any level set whose numbers you are going to read or count.
`Solid.levelSetOf` already defaults to it.

## Why Manifold reports a negative genus

Genus is computed from the Euler characteristic of the whole surface, `g = (2 − χ)/2`, and χ adds
across disjoint components, so `k` separate pieces report `1 − k`. A negative genus is a
component count in disguise, not an error: composing `k` translated plaques for `k = 1..4` gives
genus `0, −1, −2, −3` while `decompose()` gives `1, 2, 3, 4` parts.

## The limits of this route

The relief is not a portrait. Monocular depth of an interior gives a basin, and a lithophane of a
face would want a subject where the near surface is the subject rather than the walls. Nothing in the
route changes; only the photograph does.

## Where the code is

* `reliefMesh` and `Img.toReliefMesh` are in
  [`mesh_generate.kt`](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/stdlib/mesh_generate.kt).
* The whole route as one program is
  [`PhotoToSolidChain.kt`](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/examples/chain/PhotoToSolidChain.kt).
* The [photo_to_solid_chain](agent_transcripts.md#photo_to_solid_chain) starting point is the same
  route as a document.
