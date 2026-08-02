# Filled FLines: which way a tessellated triangle faces

*2026-08-02.*

Everything that turns a **filled** `FLine` into triangles funnels into one place:
`FLine.tessellate` (`src/jvmMain/kotlin/afield/graphics/FLine.kt:1001`). It flattens the line into
rings, works out which rings are holes, and hands the result to `FloatEarcut`.

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
area through `abs()` (`FLine.kt:1185`), and point-in-polygon containment is mirror invariant. So
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

Before this branch, **no** test exercised `FLine.tessellate` with holes at all, and only
`PolytopeRealizationAndTessellationTest.kt:61` exercised it off the XY plane — by triangle count
alone. That is the gap that let this land.

The invariant is now pinned by `src/jvmTest/kotlin/afield/graphics/`:

* `TessellationOrientationOracleTest` — one polygon (with holes, nested holes, disjoint holes,
  concave, oppositely wound subpaths) tessellated in XY and in the *same polygon rigidly rotated*
  into XZ, YZ and two oblique planes, in both hemispheres of every principal axis. Same triangle
  count, same area, same nesting parity, every triangle wound with its own polygon's normal.
* `TessellationRingRobustnessTest` — the sanitiser, plus collinear, all-identical, zero-normal,
  self-intersecting, self-touching and project-to-coincident rings.
* `TessellationFacingRenderTest` — the rendered assertion described above.

## The limit that remains

**One FLine, one projection axis.** A single FLine holding filled subpaths in two *different* planes
gets one axis, chosen by the strongest ring, and the other subpath projects onto a line and does not
fill. `TessellationRingRobustnessTest.oneFLineSpanningTwoPlanesFillsOnlyTheDominantOne`
characterises it rather than asserting an ideal, so a future move to per-group projection has
something to move.

This is not a regression: before `e4b309379` the axis was permanently `(x, y)` and the non-XY
subpath vanished just the same. What changed is only *which* of the two survives. It is also not the
common case — `PolyhedralObject.toFaceFLines` (`polytope/FieldAdapters.kt:23`) deliberately emits
**one FLine per face**, precisely so that faces can be styled and filled independently.

The reason it was not fixed here: hole classification would then have to be scoped to a plane as
well as to a projection, and "which rings are coplanar" is a clustering decision with a tolerance in
it — a different, larger design question than the handedness of a fixed frame.

## A convention note

`EarcutFaceTriangulator` (`polytope/FieldAdapters.kt:96`) solves the same problem the other way
round: it projects on the **mirror** pair for the y-dominant case (`:126`) and then flips each
returned triangle back against the 3D face normal (`:154`). Both land in the same place, and both are
correct, but they are not the same convention. Worth knowing if the two are ever merged: the
after-the-fact flip is only safe because `EarcutFaceTriangulator` has no hole support (`:93`) — a
hole ring's classification depends on the projected frame's handedness, so a hole-carrying version
would have to fix the frame, as `FLine.tessellate` now does.
