# A mesh can be picked, and measured against an FLine

*2026-08-16. Written to `dev_docs/` rather than to `public/docs/` because another branch owns the docs
pages tonight; the prose below is ready to be moved into `public/docs/fline_examples.md` next to the
three-inspector section, and the demo it describes is already a loadable artifact.*

## The gap

`533918561` moved meshes onto the three.js viewer. `ThreeScene.kt`'s KDoc states the achievement as
*"The viewer gained no knowledge of either source type — it draws drawables."*

That was true of **drawing** and false of **interacting**:

* `renderPayloadGeometry` built `interactiveObjects` only out of `payload.interactive`;
* `ThreeSceneBuilder.payload` fills `interactive` only from the FLine half of a scene;
* so a ray went straight through every mesh, and every picking primitive in the viewer —
  `parseFLineTopology`, `verticesForLine`, `flinePickClassify`, `interactiveEntry`'s eight fields — was
  FLine-shaped by construction.

The headline example in `Three.kt`'s own KDoc — `mesh(car); lines(measurements)`, sold as *"annotations
in the same space as the solid"* — therefore produced a scene in which **you could measure the
annotations and could not measure the solid.**

`ThreeDrawable` had already declared `callbackId`, `hoverColor`, `label` and `topology`, and nothing
read any of them. The IR was written for this; the wiring was never done.

## What it takes to close it

### 1. Classify a surface hit — `// [viewer-region surface-hit-picking]`

Three.js hands back a `faceIndex` and a world `point`, so the hard half of `flinePickClassify` — hunting
the topology for the nearest element — is simply absent. What is left is deciding what the person meant:
a corner, an edge, or the face.

Two decisions carry the design.

**The tolerance is a real distance, not a barycentric one.** Barycentric coordinates are scale-free,
which is tempting, and wrong: 0.05 of the way across a triangle 0.01 units wide is 0.0005 units, which
nobody can aim a mouse at, while 0.05 across a 500-unit triangle is a 25-unit dead zone. So the
classification is by true perpendicular distance in the triangle's plane, and the snap radius is a
fraction (0.14) of *the hit triangle's own longest edge* — scale-free in the way that matters (the same
mesh at any size classifies the same click identically) while staying a real distance within any one
triangle.

The consequence is stated rather than hidden: **a sliver has no clickable interior.** Every point of a
triangle 10 long and 0.5 tall is within its own 1.4 snap radius of an edge or a corner. That is the
honest answer; a sliver has no interior anybody could point at either.

**Vertex is tested before edge, and that ordering is the whole correctness argument.** A point near a
corner is nearer still to the two edges that meet there — always, by the triangle inequality. An
implementation that asks about edges first can never report a vertex, on any mesh, ever.

### 2. Register a surface drawable as an interactive object

`addPayloadDrawables` now builds an entry for a `pickable` surface, and `renderPayloadGeometry` appends
those entries to `interactiveObjects` **after** the FLine ones — alongside, not instead of.

The entry is built by `surfaceEntry`, which lives next to `interactiveEntry` in the `interactive-entry`
region and *delegates* to it. That is deliberate: on 2026-08-11 there were three copies of the entry
literal and two of them were wrong, and adding a third *source* is exactly the moment that would have
happened again.

### 3. The bridge to the inspector is three lines

```js
function surfacePickElementPoints(tri, record) {
    if (record.kind === 'vertex') return [tri[record.corner]];
    if (record.kind === 'edge')   return [tri[record.edgeIndex], tri[(record.edgeIndex + 1) % 3]];
    return [tri[0], tri[1], tri[2]];
}
```

`measureElement` has always taken exactly `point` / `segment` / `polygon`; an FLine hit has always
resolved to one of those; a mesh hit now does too. Nothing in `measureClosestApproach` learns that
meshes exist. **Measuring from a solid to an annotation is the existing code meeting an old shape from
a new source** — the two paths meet at an existing seam rather than at a new one.

### 4. Kotlin

`ThreeSurfaceHit` is the sibling of `FLineIntersection`: every field that means the same thing is named
the same thing (`position`, `onElement`, `label`, `kind`), and only the identifying triple differs,
because an FLine is addressed by `(subPathIndex, nodeIndex, elementIndex)` and a mesh by
`faceIndex` + `vertexIndex`. It adds barycentric `(u, v, w)` — the only honest way to interpolate
anything the mesh carries per vertex at the clicked point — and `frontFacing`, which has no FLine
counterpart because an FLine fill is a sheet and has no inside.

```kotlin
three(height = 560) {
    mesh(slab, pickable = true, onClick = { hit: ThreeSurfaceHit -> println(hit) })
    lines(conduit)
    inspect(units = "mm")
}
```

`inspect(…)` is new too. The inspector arrived as `Box.threeInspect(lines)`, which takes a
`Collection<FLine>` and builds the payload itself — so the one scene shape that has both a solid and its
annotations, which is the entire reason `Box.three` exists, was the one shape that could not carry the
toolbar.

## Three things that had to change around the edges

**The hover pass.** `updateInteractiveHover` raycasts every interactive object on every frame. That was
affordable while the interactive set was FLine strokes; a pickable 100k-triangle mesh is a brute-force
triangle sweep sixty times a second for a scene nobody is touching. It is now memoised on pointer +
camera + a revision counter, skipped while the camera is being dragged (orbiting is not hovering), and
only entries that can *show* something — a hover colour or a callback — join it at all. A `pickable`
mesh with neither is picked on click, which is one raycast.

**The pick threshold.** `raycaster.params.Line.threshold` was the constant `0.06` world units. Fine for a
figure a couple of units across; at model scale it is sixty microns, and an FLine annotation in a
millimetre figure could not be clicked *at all*. It went unnoticed because until now the only clickable
things in those figures were fills, which are meshes and are picked exactly. It is now
`max(0.06, diagonal × 0.005)` — `max`, so every figure with a diagonal under 12 units keeps precisely
the threshold it has today and this cannot re-tune anything that already works.

**The section.** `inspectorClippableMaterials` and `inspectorDrawCut` walked the FLine slots and the
interactive objects and nothing else, so a section cut the annotations and left the solid standing whole
in front of them. Drawables (and their wireframe overlays, which are a separate material on a child
object) are in both loops now, and the explode moves a drawable by its object transform the way it moves
an interactive object.

## What was measured

* `ThreeSurfaceHitDifferentialTest` lifts the region onto GraalJS with every expectation derived by
  hand. The corpus deliberately contains the inputs that could make it fail: a point nearer two edges
  than the corner it must snap to (with the geometric fact asserted, so the trap cannot stop being one),
  a sliver, a back face, three shapes of degenerate triangle, a 1 µm triangle, and a non-indexed
  geometry. Its last cross-region test measures a mesh triangle against an FLine segment: **2.0 exactly**,
  against **3.3826** had the polygon degraded to the raw click point.
* The 1 µm triangle found a real bug on the way in: the degeneracy epsilon was absolute where the
  quantity it guards (an area squared) scales as the fourth power of the model.
* **Driven to pixels.** A real Field on port 9491, the demo document loaded from its own `metadata.edn`,
  and a browser sweep of 853 canvas points dispatching real `click` events at
  `renderer.domElement`. Distances measured from one anchor triangle: `{0: 768, 60: 2, 150: 25, 154: 1,
  174: 1, 666: 54, 668: 1}`. The 25 readings of **150 mm** are the conduit, and 150 is the figure's own
  clearance (`180 − 30`). With the ruler off, the same two clicks print, through the websocket and the
  Kotlin callback:

  ```
  slab hit: VERTEX face 1 vertex 18 bary 0.037 0.015 0.948 on (-600.0, 400.0, 30.0)
  slab hit: EDGE   face 0 edge 2   bary 0.395 0.030 0.575 on (115.08658, 76.72438, 30.0)
  conduit hit: EDGE node 2 u=0.966 at (300.0, 222.99457, 180.0)
  ```

  `(-600, 400, 30)` is exactly a corner of the slab, and `(300, …, 180)` is exactly on the conduit's
  vertical leg — both self-checking.
* The section, driven through its own slider, reports `cut 1720 mm @ 144 mm`. Both follow from the
  geometry: the plane sits at `−600 + 0.62 × 1200 = 144`, and the cut is the slab's `800 × 60`
  cross-section, perimeter `2 × (800 + 60) = 1720`. That number is now an assertion, not only an
  observation — see `a section through a solid reports the cross-section it actually cut`.

Captures: `dev_docs/agents/artifacts/mesh_inspector_parity/`.

## What that headline could not have caught

*Written after the fact, against my own number.* The `150 mm × 25` reading above is true and it was
oversold. Tracing what it actually depends on:

* `surfacePickElementPoints` returns `tri[corner]`, or `tri[e]` and `tri[e+1]`, or all three corners. It
  **never reads `u`, `v` or `w`.** A wrong barycentric weight cannot change a measured distance, on any
  figure, ever. The 25 readings were not testing the classifier's arithmetic.
* On that slab the conduit's leg projects into the hit triangle *and* crosses the diagonal edge that was
  picked, so `edge` and `triangle` both answer 150. The reading was insensitive to the
  vertex/edge/triangle decision too.

What it *did* exercise, and what nothing else does: the raycast reaching a mesh at all, the
`faceIndex` → triangle readout, and the hand-off into `measureElement`. Real, and less than it looked.

Four tests now cover what it could not, each with the input that makes it able to fail named:

| test | the failing input | correct | broken |
| --- | --- | --- | --- |
| the kind of element picked changes the measured distance | an annotation at `(5, 3, 4)`, off to one side of a right triangle, so vertex/edge/triangle give **three different** answers | 7.0711 / 5 / 4 | 4 / 4 / 4 |
| counterfactual — a classifier that never snaps | the same three clicks, with both snap branches disabled | — | collapses to 4 |
| a wrong barycentric weight gives a wrong point on the element | a click whose weights are **pairwise distinct** (0.2179, 0.5512, 0.2309); the *centroid* is the control, where a swap is invisible | 25 | 31.531 |
| a weight swap leaves every measured dimension untouched | the boundary itself, pinned so it cannot move silently | — | — |

And the status of every number in this page, plainly:

| number | asserted? |
| --- | --- |
| **150 mm** | yes — `the demo's 150 mm is reproducible from the geometry, by edge and by triangle alike`, exact to 1e-9, by both element kinds |
| **1720 mm @ 144 mm** | yes — `a section through a solid reports the cross-section it actually cut` |
| **2.0 vs 3.3826** | yes — the cross-region test |
| **7.287 mm** pick radius | yes — `ThreePickThresholdTest` |
| **853 clicks**, the `{0: 768, …}` histogram | **no — an observation.** It is a description of one browser run, not a property. Nothing floors it and nothing re-runs it |

## Open it

`public/docs/assets/three_mesh_pick_demo/metadata.edn` is a loadable Field document with one Kotlin box:
a concrete slab, a conduit run 150 mm in front of it, one authored dimension, and both objects pickable.
Open it in a fresh Field, run the box, press the ruler, and click the slab and then the conduit.

## What is not covered

* The three.js plumbing around the region — `intersectObjects`, `faceIndex`, the material's `side` and
  therefore which faces can be hit at all — is not liftable and is not under differential test. It is
  covered by the driven capture above and by nothing else.
* `explode` moving a drawable is implemented and reachable (`mesh(…, group = n)`), but nothing in-tree
  sets a drawable's group yet, so it has payload-level coverage only and has not been driven visually.
* The demo has no docs page and therefore no `ThreeInspectorDemoDriftTest`-style fence guard, because
  `public/docs/*.md` belongs to another branch tonight. Adding the fence and the drift test is the
  natural follow-up.
