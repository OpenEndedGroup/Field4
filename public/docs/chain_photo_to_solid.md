# A photograph, closed and mounted

*The loadable version of this page is [photo_to_solid_chain](agent_transcripts.md), and CI runs it as
the `FIELD_TEST` `chain.photo-to-solid`.*

Four parts of Field, one photograph. `afield.onnx` runs Depth Anything V2 Small over ONNX Runtime and
predicts how far away everything in a still image is. `afield.stdlib`'s imaging family normalises and
smooths that prediction. Its mesh family turns a raster into triangles. `afield.manifold` does exact
Booleans on closed solids over Manifold's C ABI. None of the four knows the other three exist, and
this page carries one image through all of them until a printable object comes out the far end.

The photograph is `assets/vision/fline-viewer.jpg` — a Vision Pro passthrough frame of a living room
that already ships in the repository, and the same one the [ONNX page](onnx.md) uses. No depth camera
is involved anywhere, and no network: the 99MB of weights are cached under `~/.afield/onnx/models`
after the first run.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_photo_to_solid/depth.png" capture="box" open-outputs="true" wait-output=true wait-output-kind="all" wait-output-contains="input forced to a multiple of 14" embed=true force=true timeout="300" w="1120" h="380" padding="18" name="chain-photo-depth" alt="Two panels: a photograph of a pale living room with framed prints, a television on a grey wall and a wooden floor, beside its depth map in the inferno colormap, where the television wall is almost black and the near floor along the bottom edge is a bright orange-yellow band" macro-file="../../dev_docs/agents/transcripts/photo_to_solid_chain.yaml" capture-box="photo_depth"}
// Built and launched from dev_docs/agents/transcripts/photo_to_solid_chain.yaml.
```

Read the right-hand panel as a height map and the room is **concave**. The side walls and the near
floor are close to the camera, the far wall is not, so the brightest values ring the edges of the
frame and the darkest sit in the middle. That fact decides the shape of everything below: the solid
this page builds is a basin, and it is the room turned inside out.

## The hop that did not exist

`Img.toHeightfieldMesh` has always turned a raster into a surface, and `TriMesh.toSolid` has always
handed a mesh to Manifold. They do not compose, and the reason is in `heightfieldMesh`'s own KDoc:

> A disc, not a solid (`χ = 1`, one boundary loop).

A landscape has no underside, which is the right answer for drawing and the wrong one for anything
that needs a volume — an exact Boolean, a mass, a printed object. `toSolid` refuses an open sheet.
The obvious repair is not the shape you want either: `TriMesh.repairToSolid` closes the disc by
capping its *own* rim, which fits a lid across the boundary polygon and encloses only the sliver
between that lid and the surface.

So this page adds `reliefMesh` and `Img.toReliefMesh` to `mesh_generate.kt`. They drop the rim
vertically onto a plane and floor it, which is what a lithophane, a relief map or a stamp actually is:

```diffstatic {data-lang=kotlin}
// before: two calls that do not compose
val open = depth.toHeightfieldMesh(scale = 0.35)   // χ = 1, one boundary loop
open.toSolid()                                     // refused: not a closed manifold

// after
val plaque = depth.toReliefMesh(scale = 0.35, baseThickness = 0.12)  // χ = 2, no boundary loops
plaque.toSolid()                                                     // legal
```

The counts are a closed form rather than an emergent property, and that is what makes them testable.
With `P = 2·nx + 2·nz` rim vertices, a relief has `(nx+1)(nz+1) + P + 1` vertices — the grid, the rim
at the base plane, and one centre vertex the floor fans from — and `2·nx·nz + 3·P` triangles. The
Euler characteristic falls out and is `2` at every resolution, because the `−P/2` that the fan and the
walls contribute cancels the `+nx+nz` that the grid does. `MeshCoreTest` checks all of that at three
aspect ratios, so a formula that only works on a square grid fails there.

!!! note "Which way the rim is walked, and why it is not a coin flip"

    The rim runs **clockwise in `(x, z)`**, because `x̂ × ẑ = −ŷ`: a clockwise loop in those two
    coordinates is the one whose right-hand normal points `+y`. That is the direction the grid quads
    already wind, and matching it is what lets each wall traverse its shared rim edge in the opposite
    direction — which is the whole definition of a consistently oriented closed mesh. Get it backwards
    and the mesh still renders, with a negative volume.

## What crosses each hop

| hop | the call | what crosses | what stops here |
| --- | --- | --- | --- |
| photograph to depth | [`DepthAnything.infer`](reference/machine-learning.md) | one single-channel map, in the model's own arbitrary units | the photograph's colours, and any absolute scale. The output is *inverse relative* depth and needs a normalisation window |
| depth to a surface | [`Img.toReliefMesh`](reference/graphics.md) | positions, on one grid vertex per sampled pixel | every pixel between samples. `step` and the resample before it are where the resolution decision lives |
| a surface to a solid | [`TriMesh.toSolid`](reference/graphics.md) | positions and indices | normals, uvs, colours. The binding hands Manifold three doubles per vertex and nothing else |
| a solid through a Boolean | [`Solid.plus`](reference/graphics.md) | a closed surface | any correspondence with the triangles that went in. A Boolean retriangulates |
| a solid to triangles | [`Solid.toTriMesh`](reference/graphics.md) | positions, indices, optionally split normals | watertightness, *if* you ask for normals — see below |

## The plaque

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_photo_to_solid/plaque.png" capture="box" open-outputs="true" wait-output=true wait-output-kind="all" wait-output-contains="a plaque has none" embed=true force=true timeout="300" w="1120" h="440" padding="18" name="chain-photo-plaque" alt="Three renders of the same pale wireframed relief plaque on a dark background: from above, where the room reads as a shallow basin with a raised near edge and raised side walls; from three-quarters, where the flat base slab is visible under the relief; and edge-on along the rim, where the slab and the vertical skirt joining it to the surface are clearest" macro-file="../../dev_docs/agents/transcripts/photo_to_solid_chain.yaml" capture-box="photo_plaque"}
// Built and launched from dev_docs/agents/transcripts/photo_to_solid_chain.yaml.
```

The caption on that box is the whole assertion: `watertight=true manifold=true chi=2`, and
`boundary loops=0` where the heightfield it came from has one. The volume it prints and the volume
Manifold computes for the same object agree to the last digit, which is worth more than either number
alone — Field's `signedVolume` is a divergence-theorem sum over the triangles in Kotlin, and
Manifold's is C++ over its own halfedge structure, and the two share no code.

## Mounted in a frame

A picture frame is a slab with a rectangular window, so it is a torus and its genus is `1`. Union it
with the plaque and the plaque's base blocks the window, so the assembly's genus is `0`. That pair is
a statement about holes that no volume can make, and it is checked rather than admired.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_photo_to_solid/boolean.png" capture="box" open-outputs="true" wait-output=true wait-output-kind="all" wait-output-contains="inclusion-exclusion holds to" embed=true force=true timeout="300" w="1120" h="440" padding="18" name="chain-photo-boolean" alt="Three renders on a dark background: the relief plaque with a notch cut around its rim, a plain rectangular frame ring that is visibly a torus, and the two assembled with the frame lip standing proud around the relief" macro-file="../../dev_docs/agents/transcripts/photo_to_solid_chain.yaml" capture-box="photo_boolean"}
// Built and launched from dev_docs/agents/transcripts/photo_to_solid_chain.yaml.
```

The identity in that readout is inclusion–exclusion, which holds for any two solids at all:

```
vol(A ∪ B) + vol(A ∩ B) = vol(A) + vol(B)
```

It is the strongest cheap oracle available on a Boolean, and it is strictly better than "the union got
bigger". A union that silently dropped a component, a difference that kept a coincident face, an
intersection that mis-classified a shell — all three change one side and not the other. The companion
`vol(A − B) = vol(A) − vol(A ∩ B)` is checked in the same place.

The renders are `TriMesh.renderPng`, which is a CPU rasteriser with its own z-buffer and a
depth-tested wireframe, and is AWT-free (the encoder is `afield.util.PngEncoder`). That last detail is
why it is safe to call from inside a live box on macOS, where touching AWT in a process that already
owns its main thread for GLFW takes the whole thing down.

### The trap this route walked into

The first version of this chain asked Manifold for `toTriMesh(normals = true)`, because that is what a
renderer wants, and then handed the result back to `toSolid`. Manifold refused it, and
`MeshDiagnosis` said exactly why:

```
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
read as a seam of holes. The rule is already written down on the [Manifold page](manifold.md) — *ask
for `toTriMesh()` when you are measuring and `toTriMesh(normals = true)` when you are drawing* — and
what is new here is only the confirmation that a real four-subsystem route walks into it on its first
attempt, with the number of split vertices measured on this object rather than assumed. The fix is to
ship the unsplit mesh and let each render bridge add normals itself, which `renderPng` already does.

## Two constructions of the same solid, and a recorded claim re-measured

`Img.asHeightSdf`'s KDoc claims that marching an image as a field gives *"the same surface
`Img.toHeightfieldMesh` builds directly, which is a useful cross-check"*. This page takes that
seriously and turns it into one: the grid triangulation and a marched level set of the same depth
field are compared **against each other**, not against a constant. They share no code — one walks a
`(nx+1)(nz+1)` lattice in Kotlin, the other is Manifold's own marcher calling back into a Kotlin
lambda over FFM — and they agree to about one marching cell.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_photo_to_solid/levelset.png" capture="box" open-outputs="true" wait-output=true wait-output-kind="display" wait-output-contains="eroded to islands" embed=true force=true timeout="900" w="1100" h="420" padding="18" name="chain-photo-levelset" alt="A four-column table headed regime, components, parallel = false and parallel = true. Each of three rows names a regime and its component count, and the parallel = false column reads 1/8 distinct on every row while the parallel = true column varies" macro-file="../../dev_docs/agents/transcripts/photo_to_solid_chain.yaml" capture-box="photo_levelset"}
// Built and launched from dev_docs/agents/transcripts/photo_to_solid_chain.yaml.
```

The second half of that box is a recorded claim put back on the bench. `Solid.levelSet`'s KDoc says
flatly that `parallel = true` is not reproducible — it was first seen on a field that marched to 46,
47 or 48 components on different runs, against a sequential path that was identical every time.

That is **confirmed**, and the table above is the measurement. Population: depth maps of one
photograph, five regimes across two smoothing levels, eight runs each way — 80 marches, plus a
twelve-point component scan to locate the regimes. Within the table, `floor` is the only thing that
varies: the field, the bounds and the edge length are identical, and raising `floor` erodes the plaque
so that only the nearer parts of the photograph survive, as islands. Two answers count as the same
only if the triangle count, the component count *and* the volume to twelve places all agree, so this
counting cannot report a reproducibility that is not there.

- `parallel = false` returned **one distinct answer in eight, in every one of the five regimes** —
  volume equal to twelve decimal places, triangle and component counts equal as integers.
- `parallel = true` did not, and it got worse as the field fragmented: **4/8, 3/8 and 8/8** distinct
  across the three regimes in the figure. Note the first row. That regime is a single solid block,
  the easy case, and the parallel path still gave four different answers in eight runs. On a more
  heavily blurred version of the same photograph its single-block regime did come back 1/8, which is
  the useful negative result: *"parallel is fine on simple shapes"* is not a rule you can lean on,
  because which field is simple enough is not something you can see in advance.
- Worse than the KDoc says, in one respect, and this one is not in the table. At an intermediate
  floor the parallel path returned **one component on all eight runs** where the sequential path
  returned **two**. It does not only wobble around the right answer — it can agree with itself on a
  *wrong* component count. A census taken off a parallel level set can therefore be confidently wrong
  rather than visibly noisy, which is the failure mode that survives review.

The practical reading: a level set whose numbers a person is going to read wants `parallel = false`.
`Solid.levelSetOf` already defaults that way, for an unrelated reason — a `ScalarField3D` takes a
mutable `Vec3` — and this is a second reason to keep it.

## The census that equals something independently known

Manifold's genus can be negative, which reads as an error and is not one. It is computed from the
Euler characteristic of the whole surface, `g = (2 − χ)/2`, and χ adds across disjoint components, so
`k` separate pieces report `1 − k`. The chain re-measures that on plaques rather than on the cubes the
claim was first made about: composing `k` translated copies for `k = 1..4` gives genus `0, −1, −2, −3`
and `decompose()` gives `1, 2, 3, 4` parts. The two sequences are the same statement twice, and they
agree.

## What this page does not do

The render hop here is the CPU rasteriser, not the GPU path tracer. `PathTraceScene.importTriMesh`
takes exactly the meshes `mount()` produces and would trace them, but it needs a live Vulkan
`Renderer` and therefore a native window, so every figure above is CPU work and reproducible in a
headless build. The three chains that do end in a path trace —
[polytope](chain_polytope_render.md), [particles](chain_physics_render.md) and
[3D life](chain_life3d_render.md) — show that hop with native-window captures.

The relief is also not a portrait. Monocular depth of an interior gives a basin, and a lithophane of a
face would want a subject where the near surface is the subject rather than the walls. Nothing in the
route changes; only the photograph does.

## Where the code is

| what | where |
| --- | --- |
| the new hop | `reliefMesh`, `Img.toReliefMesh` in `src/jvmMain/kotlin/afield/stdlib/mesh_generate.kt` |
| its tests | `reliefMeshClosesAHeightfieldWithExactCounts`, `aFlatReliefIsExactlyItsCuboid`, `imgToReliefAgreesWithImgToHeightfieldOnTheSharedGrid` in `MeshCoreTest` |
| the route | `afield.examples.chain.PhotoToSolidChain`, run by CI as `chain.photo-to-solid` |
| the loadable version | `dev_docs/agents/transcripts/photo_to_solid_chain.yaml` |
