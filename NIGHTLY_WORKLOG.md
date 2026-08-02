# Nightly 2026-08-02 — `FLine.tessellate` dominant-axis projection audit

Branch `nightly/2026-08-02/2-fline-tessellate-projection-audit`, based on `590e1f781`.
Kind B: defect / robustness, high blast radius.
**Status: done. Recommendation: merge.**

## Plan

`e4b309379` ("feat(polytope): add composable Conway geometry core") changed the central filled-FLine
tessellation path in `src/jvmMain/kotlin/afield/graphics/FLine.kt:1001` for *every* filled FLine in
Field, not just polytope faces: `same2D` -> `same3D` in the ring sanitiser, and a
dominant-Newell-axis projection replacing raw (x, y) for bounding boxes, signed area,
point-in-polygon and the Earcut feed.

1. Build an independent **orientation oracle** in `src/jvmTest/kotlin/afield/graphics/`: one polygon,
   tessellated in XY and rigidly rotated into XZ / YZ / two oblique planes, both hemispheres of each
   principal axis. Assert triangle count, area, winding and hole classification are invariant. Run
   it red first.
2. Test the specific suspicion (y-dominant `(x, z)` is a mirror of `(z, x)`), the negative
   hemisphere, and the zero-normal default — one test each, recording refutations as plainly as
   confirmations.
3. Map the blast radius by `file:line`, and check what the *existing* tests covered.
4. Decide with a test, not by reading, whether `same2D` -> `same3D` is right.
5. Fix, red -> green.
6. Prove it with rendered pixels.
7. Widen: oblique planes, concave, nested holes, disjoint subpaths, mixed planes, degenerate rings.

The premise held. No pivot was needed.

## What changed

| File | Why |
|---|---|
| `src/jvmMain/kotlin/afield/graphics/FLine.kt` | The fix (two commits). Right-handed projection cycle; hemisphere swap; hemisphere moved from FLine-wide to per filled group. |
| `src/jvmTest/kotlin/afield/graphics/TessellationOrientationOracleTest.kt` | **New.** The rotation-invariance oracle: 7 tests × 8 orientations. |
| `src/jvmTest/kotlin/afield/graphics/TessellationRingRobustnessTest.kt` | **New.** The sanitiser change plus degenerate, self-intersecting, self-touching and mixed-plane rings. |
| `src/jvmTest/kotlin/afield/graphics/TessellationFacingRenderTest.kt` | **New.** The pixel proof — a culled, lit, z-buffered render, asserted numerically. |
| `dev_docs/fline_fill_orientation.md` | **New.** The write-up. |
| `dev_docs/FLineGeometry.md` | Links to it. |
| `dev_docs/agents/artifacts/tessellate_facing/` | **New.** `before_590e1f781.png`, `after_fixed.png`, `README.md` with the exact regeneration commands. |
| `src/jvmTest/kotlin/afield/graphics/CloseNodeCitationDriftTest.kt`, `dev_docs/fline_close_nodes.md` | Line-number citations recomputed; the added comment moved `FLine.kt` by 96 lines. |

Eleven tracked files, six commits, +1193 / -12, all inside the worktree. No Field JVM was ever
started, so nothing was listening on port 9602 (checked with `lsof` at the end; empty).

*This worklog is listed in `.gitignore:238`, so it was force-added to satisfy the nightly brief's
"commit it". If the ignore was deliberate, `git rm --cached NIGHTLY_WORKLOG.md` drops it without
touching anything else.*

## Confirmed

1. **The y-dominant pair `(x, z)` is a mirror.** The orientation-preserving cycle is `x -> (y, z)`,
   `y -> (z, x)`, `z -> (x, y)`. The decisive measurement is that `rotX(-90)` (XZ plane, normal
   `+y`) failed while `rotX(+90)` (XZ, normal `-y`) *passed* — the exact opposite of the x- and
   z-dominant cases, which is the signature of a mirror rather than of a missing sign.
2. **The negative hemisphere was unhandled** — independently of (1). A polygon rotated 180° about x
   (still in XY, normal `-z`) came back inverted, as did `-x`. Half of all faces, on every axis.
3. **The mechanism**: `FloatEarcut` normalises ring winding itself (`FloatEarcut.kt:705`), so the
   facing of every returned triangle is set by the projected frame and never by the authored
   winding. That is what turns "which two coordinates do I keep" into a decision about surface
   normals.
4. **`same2D` -> `same3D` is right, and load-bearing.** An XZ wall has only two distinct `(x, y)`
   pairs along its whole boundary, so the old predicate collapsed it to two points and the ring was
   dropped before the projection ever ran. Measured, including the counterfactual, in
   `TessellationRingRobustnessTest`.
5. **A third defect, found by widening, that predates `e4b309379`**: the hemisphere was one decision
   for the whole FLine. Two disjoint subpaths wound against each other are two polygons with two
   normals; the smaller one was emitted inside out, in *every* orientation including plain XY.
6. **The coverage gap that let it land**: before tonight no test exercised `FLine.tessellate` with
   holes at all, and only `PolytopeRealizationAndTessellationTest.kt:61` exercised it off the XY
   plane — by triangle count alone.

## Refuted

* **"The sign flip corrupts outer-ring selection and hole classification."** It does not.
  `tessellate` only ever consumes the signed area through `abs()` (`FLine.kt:1185`), and
  point-in-polygon containment is mirror invariant. On the *unfixed* code, the area, nested-parity,
  disjoint-hole and concave tests were already green in all eight orientations; only the two winding
  tests were red. There is also no winding-normalisation step in `tessellate` for the sign to
  corrupt — the brief described one, but the code does not have it. The defect was facing, and only
  facing.
* **"Wall-like faces are *classified* backwards."** No — they are *wound* backwards. Classification
  was never affected.
* **"The zero-normal default (`projectionAxis` starts at 2) may be wrong."** It is reachable — a
  ring collinear along z, for instance — and it is correct: such a ring has no area to fill, and the
  fallback to `(x, y)` produces nothing rather than throwing. Pinned by
  `aDegenerateNormalFallsBackToTheXYProjectionWithoutThrowing`.
* **"This is currently visible on screen."** It is not, and this is the honest headline. **Nothing
  in Field culls back faces**: every Vulkan pipeline is `VK_CULL_MODE_NONE`
  (`four/modules/Module.kt:803`, `:988`, `:1146`; `four/modules/MeshModule.kt:466`, `:664`), the
  three.js fill materials are `DoubleSide` (`threejs_viewer.html:900`, `:999`) and three.js flips the
  normal for back-facing fragments, and the visionOS viewer sets `faceCulling = .none`
  (`FLineMeshBuilder.swift:401`). The defect was real but **latent**. What the fix protects is
  anyone who turns culling on, reads a face normal off the mesh, exports it, or builds a solid out
  of filled FLines — which is exactly the use the polytope work opened up.

## Verification — what was actually observed

* **Red then green.** The oracle was committed red (`d87af3f1a`) with the four failing orientations
  quoted in the commit message, and went green on `7b0740c4d`. The per-group defect was reproduced
  red in all eight orientations before `63c911424` fixed it. Both transitions were observed, not
  inferred.
* **`./gradlew compileKotlinJvm jvmTest`** on the final tree, results directory deleted first:
  **106 classes, 1230 tests, 0 skipped, 0 failures.** Counted out of
  `build/test-results/jvmTest/TEST-*.xml`, not read off a summary line.
* **Rendered output was looked at.** `before_590e1f781.png` and `after_fixed.png` in
  `dev_docs/agents/artifacts/tessellate_facing/` were both opened and inspected. Before: the `+y`
  lid of the cube is gone and you see into the box, at the insides of the far `-x` and `-z` walls —
  three of six faces inverted, which the test reports as `24 triangles are wound against their face:
  [1, 2, 5]` (`-y` was right only because two mirrors cancelled). After: three lit
  faces, `+x` red, `+y` green, `+z` blue, each with its square hole, nothing showing behind them.
  `after_fixed.png` was regenerated after the per-group change and is **byte-identical** (sha1
  `3dde6edd…`), so the committed image matches the committed code.
* Not run: `run_field_tests.py` smokes and the docs build. Neither touches this path — `tessellate`
  has two production call sites and no docs figure goes through either (SVG output uses
  `<path fill=…>`, the 2D canvas uses Skia). Saying so rather than claiming a green I did not see.

## Risks and unknowns

* **One-line risk:** a 2D XY filled FLine authored *clockwise* now yields `-z`-wound triangles where
  it previously always yielded `+z` — correct, and invisible under every current viewer's
  no-cull/double-side settings, but it is a behaviour change for any user code that was relying on
  the old unconditional `+z`.
* The mixed-plane limitation is unchanged and now characterised by a test rather than fixed;
  `oneFLineSpanningTwoPlanesFillsOnlyTheDominantOne` will start failing (with a message saying so) if
  someone implements per-group projection, which is the intent.
* `TessellationFacingRenderTest` writes to a temp directory unless `AFIELD_TESSELLATION_RENDER_OUT`
  is set, so an ordinary run writes nothing into the repository. Its rasteriser is test-local and
  deliberately simple; it proves the triangles, not Field's renderer.
* The `CloseNodeCitationDriftTest` line citations into `FLine.kt` had to be recomputed twice. Any
  further edit near the tessellator will need the same, by design.

## Follow-up found in passing, not acted on

In `editable = true` mode the three.js viewer does **not** use the tessellated `fills` — it
re-triangulates each subpath in JS as a naive fan from `face.boundary`
(`src/commonMain/resources/threejs_viewer.html:1446`, `buildFaceGeometry` at `:1300`), with no hole
support and no projection. So editable and non-editable mode can disagree about both facing and
holes for the same FLine. Different bug, different language, out of scope tonight.

## The one thing to change, and what should happen

Open `dev_docs/agents/artifacts/tessellate_facing/README.md` and follow the "before" recipe: revert
`src/jvmMain/kotlin/afield/graphics/FLine.kt` to `590e1f781`, leaving the tests in place, and run

```
AFIELD_TESSELLATION_RENDER_OUT=dev_docs/agents/artifacts/tessellate_facing \
  ./gradlew jvmTest --tests 'afield.graphics.TessellationFacingRenderTest'
```

`TessellationFacingRenderTest` should fail with exactly these two messages (observed, not predicted):

```
face 2 should be a large lit quad but covers 0px.  coverage by face = [14763, 7241, 0, 0, 18502, 8422]
24 triangles are wound against their face: [1, 2, 5]
```

and the regenerated `cube_faces.png` should show the open-topped box. Restore the file and both
tests should pass and the picture should show the closed cube. That single change is the whole
branch.
