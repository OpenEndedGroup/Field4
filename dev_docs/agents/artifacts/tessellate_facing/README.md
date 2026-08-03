# `FLine.tessellate` facing and planes — before and after

Two before/after pairs, all four images from
`src/jvmTest/kotlin/afield/graphics/TessellationFacingRenderTest.kt`, which builds its scenes out of
filled FLines and rasterises them through a perspective camera with **back-face culling** and flat
diffuse shading. Culling is the whole instrument: it is the thing that notices which way a triangle
faces, and it is the thing Field's own viewers switch off (`four/modules/MeshModule.kt:466` sets
`VK_CULL_MODE_NONE`; `threejs_viewer.html:900` uses `THREE.DoubleSide`), which is why both of these
defects shipped without anyone seeing them.

## Pair one — facing (2026-08-02)

A cube built as **six filled FLines**, one per face, each with a square hole, each wound
counter-clockwise seen from *outside* the cube.

| | |
|---|---|
| `before_590e1f781.png` | The `+y` face (green, the lid) is **gone**. You are looking straight into the box, at the *insides* of the far `-x` and `-z` walls, which are drawn because their winding is inverted too. |
| `after_fixed.png` | Three lit faces — `+x` red, `+y` green, `+z` blue — each with its hole, and nothing behind them. |

Three of the six faces were emitted back-to-front:

* `+y`, because the y-dominant projection kept `(x, z)`, which is the *mirror* of the right-handed
  cycle `y -> (z, x)`;
* `-x` and `-z`, because the negative hemisphere of the dominant axis was not handled — a face whose
  normal points down the axis projects mirror-reversed even on the correct pair.

`-y` came out right in the before image only by the accident of two mirrors cancelling.

## Pair two — planes (2026-08-03)

The same three camera-facing faces, with the same holes, in **one** FLine: six subpaths, three outer
rings in three mutually perpendicular planes.

| | |
|---|---|
| `multi_plane_fline_single_plane.png` | One face. The three outer rings are congruent unit squares, the first of them wins the FLine's single projection axis, and the other two project onto a line and vanish. |
| `multi_plane_fline.png` | All three, each with its hole. Per-plane grouping gives each coplanar group of subpaths its own projected frame, and scopes hole classification to that group. |

`multi_plane_fline.png` is **byte-identical to `after_fixed.png`**, and the test asserts it: with
three of the cube's faces culled anyway, one FLine spanning three planes has to rasterise to exactly
the same frame as six independent single-plane ones. That equivalence is the claim, stated in pixels
rather than in coverage thresholds.

## Regenerating

All four images come from that one test class, which also asserts the same facts numerically, so it
fails in CI without anyone looking at a picture.

```
AFIELD_TESSELLATION_RENDER_OUT=dev_docs/agents/artifacts/tessellate_facing \
  ./gradlew jvmTest --tests 'afield.graphics.TessellationFacingRenderTest'
mv dev_docs/agents/artifacts/tessellate_facing/cube_faces.png \
   dev_docs/agents/artifacts/tessellate_facing/after_fixed.png
```

That writes `cube_faces.png`, `multi_plane_fline.png` and `multi_plane_fline_single_plane.png`. Only
the first needs renaming; the other two land on their committed names.

The second pair's "before" needs **no reverted file** — `Coplanarity.SINGLE_PLANE` reproduces the
one-axis-per-FLine behaviour exactly, every ring in one plane whatever its geometry says, so both
halves come out of one run and the before cannot rot away from the code.

The first pair's before still needs the old recipe, because the frame it shows is not reachable from
any current parameter:

```
git checkout 590e1f781 -- src/jvmMain/kotlin/afield/graphics/FLine.kt
AFIELD_TESSELLATION_RENDER_OUT=dev_docs/agents/artifacts/tessellate_facing \
  ./gradlew jvmTest --tests 'afield.graphics.TessellationFacingRenderTest'   # fails, as it should
mv dev_docs/agents/artifacts/tessellate_facing/cube_faces.png \
   dev_docs/agents/artifacts/tessellate_facing/before_590e1f781.png
git checkout HEAD -- src/jvmMain/kotlin/afield/graphics/FLine.kt
```

Since 2026-08-03 that checkout no longer compiles against the current test file — `tessellate` has
gained a `coplanarity` parameter and the multi-plane tests use it — so the honest way to get that
image is the committed PNG, or to revert the test file to `590e1f781` as well.

Without the environment variable every test renders to a temp directory and asserts there, so an
ordinary test run never writes into the repository.
