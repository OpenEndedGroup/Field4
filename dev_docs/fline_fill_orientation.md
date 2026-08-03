# Filled FLines: which way a tessellated triangle faces

*2026-08-02.*

Everything that turns a **filled** `FLine` into triangles funnels into one place:
`FLine.tessellate` (`src/jvmMain/kotlin/afield/graphics/FLine.kt:1014`). It flattens the line into
rings and hands them to `PlanarTessellation`
(`src/jvmMain/kotlin/afield/graphics/PlanarTessellation.kt:163`), which groups them into planes,
works out which rings are holes in which, and hands each group to `FloatEarcut`.

*Updated 2026-08-03: the second half of this document — "The limit that remains" and "A convention
note" — described what the 2026-08-02 branch left undone. Both are now done, and those two sections
say what happened instead. The history above them is unchanged, because it is still what went
wrong.*

`e4b309379` ("feat(polytope): add composable Conway geometry core") changed that function for every
filled FLine in Field, not just polytope faces: it added a **dominant-Newell-axis projection**, so
that a filled FLine planar in *any* 3D orientation can be filled instead of only one lying in XY.
The idea is right. Two details of it were wrong, and a third was already wrong before it.

## The thing that makes projection choices load-bearing

`FloatEarcut` **normalises the winding of every ring it is handed**. `linkedList`
(`FloatEarcut.kt:705`) walks the ring forwards or backwards depending on the sign of its signed
area, so the outer ring always ends up in earcut's canonical orientation and the holes always end up
in the opposite one. That is a good property for a triangulator — it means you can feed it rings in
any order and any winding — but it has a consequence that is easy to miss:

> **The facing of every triangle that comes back is decided by the projected frame, and never by the
> winding the caller authored.**

Which means "which two coordinates do I keep?" is not an implementation detail of the triangulator.
It *is* the normal of the resulting surface.

## What was wrong

Three defects, in the order they were found.

### 1. The y-dominant pair was a mirror

The projection was

```
x dominant -> (y, z)
y dominant -> (x, z)     <-- mirror
z dominant -> (x, y)
```

The orientation-preserving choice is the right-handed cycle: `x -> (y, z)`, `y -> (z, x)`,
`z -> (x, y)`. Keeping `(x, z)` for a y-dominant face is the reflection of `(z, x)`, so every
wall-like face in the XZ plane came back facing `-y` — regardless of which way it was actually
wound. `+y` faces were inside out; `-y` faces were right by accident.

### 2. The negative hemisphere was not handled

A face whose normal points down the *negative* half of the dominant axis projects mirror-reversed
even on the correct pair. So `-x`, `-y` and `-z` facing polygons all came back inverted. This one is
independent of the mirror above: it is what makes an XY polygon rotated 180° about x fail, in a
codebase that has always projected XY polygons onto `(x, y)`.

Fixed by swapping the kept pair when the dominant normal component is negative.

### 3. The hemisphere was one decision for the whole FLine

**Not a regression from `e4b309379`** — this one fails in plain XY too, and always has.

Coplanar is not the same as co-oriented. One FLine can hold subpaths wound against each other: a
glyph pair where one letter came from a mirrored source, two faces of a solid flattened into one
line, anything assembled by `plusAssign`. Those are separate polygons with separate normals. Taking
the hemisphere once, from whichever ring in the whole FLine happened to have the largest normal,
emits the other subpath inside out.

So the split is now: the **axis** is one decision for the whole FLine, because ring nesting has to be
measured in a single frame to mean anything; the **hemisphere** is per filled group, read from that
group's own outer ring.

Bounding boxes, signed areas and point-in-polygon deliberately stay in the *unflipped* frame. A
mirror does not change what is inside what, so the nesting comes out the same either way, and
keeping one frame there keeps the grouping stable however the individual groups end up facing.

## What was *not* wrong

Worth recording, because it is the obvious suspicion and it is false.

The mirror flips the sign of `area2D`, and `area2D` is what the code uses to pick between candidate
parent rings. It does **not** corrupt anything, because `tessellate` only ever consumes the signed
area through `abs()` (`PlanarTessellation.kt:381`), and point-in-polygon containment is mirror
invariant. So
triangle counts, total areas and nesting parity were already invariant under rigid rotation before
this branch, and still are. Measured, not reasoned: `TessellationOrientationOracleTest`'s area,
nesting and disjoint-hole tests were green on the unfixed code in all eight orientations, and only
the two winding tests were red.

The `same2D` -> `same3D` change in the ring sanitiser is likewise **correct, and load-bearing**. A
wall in the XZ plane has only two distinct `(x, y)` pairs along its whole boundary: under the old
predicate the sanitiser collapsed it to two points and the ring was dropped before the projection
ever ran. The projection and the three-dimensional duplicate test are one change, not two — neither
is any use alone. (`TessellationRingRobustnessTest.theThreeDimensionalDuplicateTestIsWhatMakesAnXZWallTessellableAtAll`
measures both halves, including the counterfactual.)

## Blast radius, and why nobody saw it

`FLine.tessellate` has exactly two production call sites:

| | |
|---|---|
| `StandardDrawing.kt:134` | inside `trianglesOut`, which de-indexes into an `afield.four` vertex buffer. **No in-repo callers** — it is stdlib API for user boxes that own a `DynamicallyWriteable`. |
| `ThreeJsViewer.kt:850` | inside `appendFilledMesh`, reached from `Box.threeLines(...)` (`ThreeJsViewer.kt:361`) and served into `threejs_viewer.html`. Also the wire format the visionOS native viewer reads (`FLineWire.swift:69`). |

Not on this path at all: SVG export (`graphics/someSkiko.kt:553` writes `<path fill=…>`), the 2D Skia canvas
(`graphics/someSkiko.kt:368`, a filled Skia `Path`), and typography extrusion (`typography_field.kt:342` calls
`FloatEarcut` directly).

And the reason it shipped: **nothing in Field culls back faces.** Every Vulkan pipeline is
`VK_CULL_MODE_NONE` (`four/modules/Module.kt:803`, `:988`, `:1146`; `four/modules/MeshModule.kt:466`,
`:664`; `four/imgui/ImGuiRenderToTexture.kt:250`). The three.js fill materials are
`MeshStandardMaterial` with `side: THREE.DoubleSide` (`threejs_viewer.html:900`, `:999`), and
although they do derive normals from the winding — `computeVertexNormals()` at `:744`, `:795`,
`:1059`, `:1236` — three.js flips the normal for back-facing fragments on a double-sided material,
so a uniformly mis-wound flat face still shades correctly. The visionOS viewer sets
`faceCulling = .none` explicitly (`FLineMeshBuilder.swift:401`).

So the honest statement of severity: **the defect was real but latent in every first-party viewer.**
What this fix protects is anything that turns culling on, reads a face normal off the mesh, exports
it, or builds a solid from filled FLines — that is, exactly the use the polytope work opened up.

## The pixel proof

`src/jvmTest/kotlin/afield/graphics/TessellationFacingRenderTest.kt` builds a cube out of six filled
FLines, one per face, each with a square hole, each wound outward, and rasterises it with a z-buffer,
diffuse shading and **back-face culling** — an instrument that does care, since Field's own do not.

Before: the `+y` lid is missing and you look straight into the box, at the insides of the far `-x`
and `-z` walls. After: three lit faces, each with its hole, nothing behind them. Both images, and
the commands that regenerate either half, are in
[`agents/artifacts/tessellate_facing/`](agents/artifacts/tessellate_facing/README.md).

## Coverage

Before the 2026-08-02 branch, **no** test exercised `FLine.tessellate` with holes at all, and only
`PolytopeRealizationAndTessellationTest.kt:61` exercised it off the XY plane — by triangle count
alone. That is the gap that let the facing defect land.

The invariants are now pinned by `src/jvmTest/kotlin/afield/graphics/`:

* `TessellationOrientationOracleTest` — one polygon (with holes, nested holes, disjoint holes,
  concave, oppositely wound subpaths) tessellated in XY and in the *same polygon rigidly rotated*
  into XZ, YZ and two oblique planes, in both hemispheres of every principal axis. Same triangle
  count, same area, same nesting parity, every triangle wound with its own polygon's normal.
* `TessellationRingRobustnessTest` — the sanitiser, plus collinear, all-identical, zero-normal,
  self-intersecting, self-touching and project-to-coincident rings; and the multi-plane cases at the
  `FLine` level.
* `PlanarTessellationClusteringTest` — the four clustering decisions, one test each, described below.
* `TessellationFacingRenderTest` — the rendered assertions, both pairs.
* `src/jvmTest/kotlin/afield/stdlib/polytope/EarcutFaceTriangulatorHolesTest` — holes through the
  polytope adapter, in every orientation.

## The limit that used to remain

*Was: "one FLine, one projection axis." Fixed 2026-08-03.*

A single FLine holding filled subpaths in two different planes used to get one axis, chosen by the
strongest ring, and every other subpath projected onto a line and did not fill.
`TessellationRingRobustnessTest.oneFLineSpanningTwoPlanesFillsOnlyTheDominantOne` characterised that
rather than asserting an ideal, "so a future move to per-group projection has something to move". It
is now `oneFLineSpanningTwoPlanesNowFillsBoth`.

What made this more than a change of projection is the sentence the old version of this section ended
on: hole classification has to be scoped to a plane as well as to a projection, because "ring A is a
hole in ring B" is measured on projected coordinates. It only *means* something when A and B are
flattened in the same frame, and it is only *true* when they lie in the same surface. So the rings
are clustered into planes first (`PlanarTessellation.kt:195`), and everything downstream — frame,
nesting, hole parity, hemisphere — is scoped to a cluster.

### The clustering rule

A ring joins a plane when **both** hold:

* **parallel** — the sine of the angle between the ring's unit Newell normal and the plane's is at
  most `Coplanarity.sineOfAngle`, default `1e-3`, about 0.06 degrees. Unsigned, so **antiparallel
  counts as parallel**: a hole is normally wound against the ring it is a hole in, so if this test
  were signed, every hole in Field would stop being a hole at once.
* **in the plane** — *every* point of the ring lies within `Coplanarity.offsetFraction` of the plane,
  measured as a fraction (default `1e-3`) of the diagonal of the bounding box of every ring in the
  input.

The tolerance is therefore **relative, never an absolute epsilon**. That is the point: a glyph
authored in screen pixels and the same glyph in unit-cube coordinates are the same shape, and an
absolute epsilon would say they are not, silently changing its mind somewhere between the two.

Three more decisions, each of which someone will eventually want to know the reason for:

**Ambiguity resolves to the first plane in ring order** (`PlanarTessellation.kt:216`), not the
nearest. Planes are grown greedily and a plane is represented by the ring that *seeded* it, never by
a running average of its members. Nearest-plane needs a global pass over every candidate; "first"
is the only tie-break that stays stable as rings are appended; and seed-not-average is what stops a
chain of pairwise-near-coplanar rings from transitively collapsing into one plane. It does mean the
answer depends on ring order to the extent that earlier rings seed. That is the price of not doing a
global fit, and it is stable and predictable.

**A degenerate ring has no plane.** A ring whose Newell normal is zero — collinear, all-identical, a
bowtie — cannot vote on a plane and is not allowed to seed one. Those rings are placed *after* every
ring that has a normal (`PlanarTessellation.kt:238`), by the distance test alone; one that matches
nothing gets a plane to itself, where it fills nothing. This reproduces the single-axis code's
behaviour exactly: its `projectionAxis` kept its `(x, y)` default when no ring had a normal, and an
all-degenerate FLine still comes out the same way.

**A "hole" ring whose plane disagrees with its outer ring is not a hole.** It lands in a different
cluster, is never classified against that outer ring, and fills as the separate surface it is. This
is the decision worth defending, and the counterfactual makes the case better than the argument
does. Take a 100x100 square in XY and a parallelogram tilted 45 degrees whose shadow on `(x, y)` is a
40x40 square inside it. Under one shared frame the parallelogram was subtracted — and because its
boundary leaves the square's plane, the triangles bridging out to it climbed out too, so a figure
whose flat interpretation has area 8400 came back measuring **12276**. The old shared frame did not
merely misclassify the ring; it emitted a surface lying in no plane at all.
(`TessellationRingRobustnessTest.aRingInADifferentPlaneIsNotAHoleEvenWhenItProjectsInsideOne`.)

The old behaviour stays *measurable* rather than merely described: `Coplanarity.SINGLE_PLANE` puts
every ring in one plane whatever its geometry says, which is exactly what the single-axis code did.
Every "before" claim in this document is asserted against it, including the rendered one.

## The convention note, resolved

*Was: two implementations, two conventions, and hole support impossible in one of them. Unified
2026-08-03.*

`EarcutFaceTriangulator` used to solve the same problem the other way round: it projected on the
**mirror** pair for the y-dominant case and then flipped each returned triangle back against the 3D
face normal. Both landed in the same place and both were correct, and the old version of this section
noted that the after-the-fact flip "is only safe because `EarcutFaceTriangulator` has no hole
support".

That is worth restating more strongly, because it is the reason the two were merged. "No hole
support" was not a missing feature that someone had not got round to. `FloatEarcut` decides which of
the rings it is handed is the *outer* one from the sign of its projected area (`FloatEarcut.kt:705`),
so a mirrored frame inverts the hole-versus-outer decision **before any triangle exists to flip**.
The convention and the absence of holes were the same fact. Fixing the frame is what removes both.

So there is now one implementation — `PlanarTessellation` — and two thin adapters over it:

| | |
|---|---|
| `FLine.tessellate` (`FLine.kt:1014`) | flattens and sanitises, then packs each returned polygon's own vertices |
| `EarcutFaceTriangulator` (`polytope/FieldAdapters.kt:122`) | reads a `Realization3` and maps the returned `(ring, vertex)` references back to realization indices |

`EarcutFaceTriangulator` therefore *gained* hole support, through a new
`HoleAwareFaceTriangulator` (`polytope/FieldAdapters.kt:97`) that adds
`triangulate(outer, holes, realization)` without disturbing the existing `fun interface
FaceTriangulator`. Holes are still not reachable from `PolyhedralObject.triangulate`, because a
`faceCycle` is a single cycle and the topology has no notion of a hole in a face; the capability is
for callers that know their own boundary rings, and `EarcutFaceTriangulatorHolesTest` is what makes
it a capability rather than a claim.

**This is not the "do not unify the independent flatteners" case**, and the resemblance is close
enough to be worth saying out loud. That standing note is about curve *flattening* — `PathFlattener`
and friends, turning a cubic into line segments, where the implementations differ in tolerance model
and in what they are allowed to allocate. Nothing here touches that: `FLine.tessellate` still calls
`uniform_linearize`, `EarcutFaceTriangulator` still reads a realized face cycle, and neither
flattener moved. What was unified is the triangulation of an already-flat ring set, where one of the
two copies could not be fixed in place.

## And the client-side half

*Was: a naive JS fan. Fixed 2026-08-03, in the same pass.*

`FLine.tessellate` has two production call sites, and the second one — `ThreeJsViewer.kt:850`, inside
`appendFilledMesh` — is only half the story for the three.js viewer, because `editable = true`
**re-triangulates client-side**. It has to: the user drags points, and the fill has to follow between
server round-trips.

It did that with a naive fan over each subpath's boundary. No holes, no projection, no concavity. So
the same filled FLine came back with different holes *and* different facing depending on a flag that
is supposed to be about editing — a hole ring drawn as a solid quad on top of its own face, a face in
a non-dominant plane drawn solid where the server dropped it, a concave ring filled outside itself.

`tessellateCoplanarGroups` in `threejs_viewer.html` is now the same rule, transliterated: Newell
normals, greedy clustering on the same two dimensionless tolerances, the right-handed projected pair,
per-group hemisphere, nesting inside a plane, and `THREE.ShapeUtils.triangulateShape` — upstream
earcut, so the winding normalisation the frame compensates for is the same property. One fill mesh per
*polygon* rather than per subpath, so a hole ring contributes to its outer ring's mesh and gets none of
its own.

Being written twice is a real cost. The reason to pay it, rather than shipping the server's
triangulation in the payload, is that a dragged point must not wait for a round-trip to re-fill. The
seam is guarded two ways: `ThreeJsEditableFillParityTest` asserts that both modes report the same
`meta.triangles` for the same line — which is what goes wrong first when one side is changed and the
other is not — and there is a real browser before/after pair in
[`agents/artifacts/tessellate_facing/multi_plane_fill_demo/`](agents/artifacts/tessellate_facing/multi_plane_fill_demo/README.md).

That count found a second thing on the way: `meta.triangles` for editable mode was itself a fan
estimate, `boundaryPoints - 2` per subpath, so the viewer's own HUD said `12 tri` for a line that the
same viewer with `editable = false` called `24`. It runs `FLine.tessellate` now.

Left alone deliberately: the selection and hit-test overlay still fans each subpath
(`buildFaceGeometry`). That is a *picking* proxy rather than a rendering of the fill, and a hole ring
is a legitimate thing to want to click on, so per-subpath is the right shape there. It is imprecise
for a concave ring — a separate question about hit testing, not about fills.

## And a dead loop in `FloatEarcut`

Found by the first two-hole test that had ever been written against it, and unrelated to any
projection.

`findHoleBridge` (`FloatEarcut.kt:586`) implements David Eberly's bridge search: cast a ray left from
the hole's leftmost point, take the intersected edge, and then — this is the second half — walk the
outer ring looking for vertices inside the triangle formed by hole point, intersection and endpoint,
choosing the one at the minimum angle. Upstream that walk is a `do … while (p !== stop)` starting at
`p = m` with `stop = m`. Field's port had it as `while (p !== stop) { … }`, so with `p` and `stop`
both `m` **the body never executed once**. The refinement pass was dead code.

The symptom needed two holes in a diagonal arrangement to show: a 100x100 square with a 20x20 hole
low-left and a 30x30 hole high-right came back as 13 triangles instead of 14, with overlapping
bridges, measuring 9000 instead of 8700. One hole was always fine, which is why nothing noticed —
`FLine.tessellate` had no hole test at all before 2026-08-02, and typography extrusion
(`typography_field.kt:342`) is the only other caller. A letter with two counters (`B`, `%`, `8`) was
in the blast radius.

Fixed at `FloatEarcut.kt:629` by restoring the `do … while`. All 1283 `jvmTest` cases stay green, and
the cube render is byte-identical before and after, so nothing single-holed changed.
