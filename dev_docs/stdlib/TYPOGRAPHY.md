# Typography as geometry — implementation notes

Public-facing worked example: [`public/docs/typography_examples.md`](../../public/docs/typography_examples.md).
This file is the *why*, for whoever changes the code next.

| file | what is in it |
|---|---|
| `src/jvmMain/kotlin/afield/stdlib/typography.kt` | the values: `Typeface`, `Glyph`, `GlyphContour`, `TextLayout`, `PlacedGlyph`; the `PathIterator` walk; layout; `onCurve`; word wrap |
| `src/jvmMain/kotlin/afield/stdlib/typography_field.kt` | the bridges: → `FLine`, → `TriMesh`, → `ScalarField3D`, → `Img`, → points; the two figure helpers |
| `src/jvmMain/kotlin/afield/examples/stdlib/TypographyGallery.kt` | `FIELD_TEST stdlib.typography.gallery`, twelve PNG panels, four of them control-armed pairs |
| `src/jvmTest/kotlin/afield/stdlib/TypographyTest.kt` | 38 assertions, cross-representation or control-armed |
| `dev_docs/agents/transcripts/typography_worked_example.yaml` | eight-box loadable demo, in `DemoTranscriptExecSmokeTest.EXECUTE` |
| `public/docs/assets/typography_gallery/*.png` | committed captures, regenerated from the gallery's `args[0]` |

## The three decisions

### 1. Text space is y-down

Glyph and layout coordinates are x right, **y down**, origin on the first baseline, units = the
typeface's point size. That is AWT's own glyph frame *and* the frame Field's 2D outputs already use
(`Rect`'s y grows downwards, `rpc.kt:1063`), so a layout handed straight to `Box.lines` or
`showText` reads the right way up with no transform anywhere.

The cost is paid once, at the 3D boundary: `toMesh(yUp = true)` mirrors y, which reverses winding, so
it composes `transformed { … }` with `flipped()`. Nothing else in the family flips anything.

### 2. Winding is decided, enforced and asserted

> Signed area `½Σ(xᵢyᵢ₊₁ − xᵢ₊₁yᵢ)`: **outer rings positive, holes negative.** Nesting depth (how
> many other rings of the same glyph contain this one) is authoritative; a ring whose sign disagrees
> is reversed.

`isoContourFLines` in `contours.kt` is the counter-example — its winding is arbitrary and every
consumer downstream copes. Here the convention is load-bearing rather than tidy: **Skia and Java2D
both fill non-zero**, so the counter of an `o` is a hole exactly because its ring cancels its bowl.
`toContours(honourHoles = false)` exists to demonstrate the alternative, and nothing in Field should
call it outside the gallery and the test.

Two independent computations are compared to keep it honest: depth from point-in-polygon containment,
sign from the shoelace formula. `TypographyTest.holesAreNegativeAndOutersArePositive` checks all of
them across three faces and 66 characters.

### 3. The handles are the API

`PlacedGlyph` carries mutable `offset` / `rotation` / `scale` / `pivot`, and `pivot` defaults to the
glyph's *visual-bounds centre*, so `rotation = 0.3` spins a letter about itself. `TextLayout` is
therefore immutable in structure and mutable in handles — computed once, animated many times — and
`onCurve` is nothing but a routine that sets them from an `FLine`'s arc-length cursor. `copyLayout()`
is what lets `onCurve` return a copy.

## Why the `PathIterator` walk is hand-rolled

`FLinesAndJavaShapes.javaShapeToFLine(Shape, AffineTransform?)`
(`src/jvmMain/kotlin/afield/graphics/FLinesAndJavaShapes.kt:2188`) already converts a Java2D shape to
an `FLine`, and it is not enough: it flattens the **whole** shape into one `FLine` with no per-ring
handle, so there is nowhere to compute nesting depth and no way to reverse a single contour without
touching the others. Winding control is the entire point, so `ringsOf` parses the iterator into
per-ring segment lists first. From there a ring can be flattened, measured, reversed and re-emitted
independently, which is what guarantees `Glyph.contours[i]` and the `i`-th subpath of `Glyph.outline`
describe the same curve in the same direction (`TypographyTest.outlineSubpathsMatchTheClassifiedContours`).

Two details from that function are matched deliberately: quadratics are promoted to cubics (exactly —
`FLine` has no quadratic node), and `SEG_CLOSE` becomes an explicit closing `lineTo` rather than a
`Close` node, so there is one representation and no special case for `FLine.tessellate`'s ring
sanitiser to trip over.

## The mesh bridge, and why it is not `extrudeMesh`

`extrudeMesh` (`mesh_generate.kt:619`) and `triangulatePolygon` (`:547`) both state that holes are not
supported. A letter is a ring system, so `toMesh` does its own thing:

- caps through `FloatEarcut.earcut(data, holeIndices, 2)` (`afield/graphics/FloatEarcut.kt:17`), the
  same triangulator `FLine.tessellate` uses;
- walls built from the **identical flattened vertices the caps indexed**, which is the whole trick:
  cap and wall share vertices, every edge gets exactly two faces, and the result is watertight;
- orientation *derived*, not repaired. Every cap triangle is measured and emitted with the winding
  that faces its own cap; every wall quad uses `(eᵥ, −eₓ)`, which is the outward side of any ring
  under this convention (an outer ring runs CCW so its interior is on the left; a hole runs CW so the
  *material* is on the left too — one formula, both cases). Nothing calls `fixWinding()`, which is
  what makes `signedVolume() > 0` a real check rather than a restatement.

**`toMesh` attaches no normals, on purpose**, exactly as `extrudeMesh` leaves them off. `flatShaded()`
un-welds every triangle into its own three vertices, so a mesh that has been through it is no longer
topologically anything — `isWatertight()` and `genus()` would be answering about the render copy.
Measure first, shade at the render boundary. This was a real bug: the gallery's first run failed with
"extruded 'o' is not watertight" because `toMesh` ended in `flatShaded()`.

## The SDF bridge

`distanceField` is a brute-force grid (nearest point over every segment of every ring, with a
bounding-box reject per ring) sampled bilinearly, because the honest per-query answer costs a few
thousand segment tests and `marchingCubes` at resolution 96 asks a million times. Outside the grid the
nearest edge value plus the distance to the box keeps the field monotone, so a marcher finds no
spurious surface out there.

`toSdf` delivers **normalised, y-up, origin-centred** coordinates — ascent becomes 1.0 — because that
is the scale `boundsAround()` and the `sdf*` primitives already speak. The z extrusion uses the exact
prism formula `min(max(d, dz), 0) + |(max(d,0), max(dz,0))|` rather than `max(d, dz)`, which matters
as soon as you `.rounded()` or smooth-union it. And the returned lambda only ever *reads* its
argument, per `mesh_sdf.kt:61-66` — the marcher reuses one scratch `Vec3`.

Sign convention is `mesh_sdf.kt`'s (negative inside), which is the opposite of
`GlyphContour.signedArea`'s. Not an inconsistency: an area's sign says which way a ring runs, a
distance's sign says which side of the ink you are on.

## The raster bridge

`rasterizeText` is an analytic-in-x, supersampled-in-y scanline fill with both fill rules
(`TextFillRule`). Deliberately not `Graphics2D.drawString`, for two reasons and the second is the
important one:

1. `imaging_field.kt:417-425` forbids in-process AWT text rendering (AppKit versus GLFW's main run
   loop) — which is why `montage` captions with a 5×7 bitmap table.
2. A PNG from AWT's rasteriser would be evidence that *AWT* can draw text. Filling the contours this
   family extracted makes a legible PNG evidence about the extraction.

Both rules are exposed because they disagree on broken input and agree on correct input, which is a
cross-check you can run in a test (`fillRulesAgreeOnCorrectlyWoundRings`, worst per-pixel difference
`0.0`) and see in a figure.

## The `-XstartOnFirstThread` trap

**Read this before changing `Typefaces.warm`.** Measured 2026-07-30: in a JVM started with
`-XstartOnFirstThread` that is not pumping its run loop, the first `Font.createGlyphVector(...)`
*never returns*. The sampled stack:

```
sun.font.CFontManager.loadFonts <- sun.font.SunFontManager.findFont2D <- java.awt.Font.getFont2D
  <- sun.font.StandardGlyphVector.<init> <- java.awt.Font.createGlyphVector
```

`CFontManager.loadFonts` dispatches to the process's **first** thread and waits. macOS GLFW requires
`-XstartOnFirstThread`, so `requiredAgentJvmArgs` passes it to every jvm-main child
(`src/jvmMain/kotlin/afield/agent_backdoor.kt:830`) — and a child that opens no window never services
that dispatch.

> A JVM that wants glyph outlines and passes `-XstartOnFirstThread` must also pass
> `-Djava.awt.headless=true`, **set before any AWT class initialises**. Headless AWT does not route
> the font manager through the main thread at all.

Where that leaves each caller:

| context | state |
|---|---|
| jvm-main galleries | fine — every one of them sets the property in the first line of `main` |
| `DemoTranscriptExecSmokeTest` | fine — now sets it too, first line, with the reasoning inline. Verified not to disturb any of the 30 pre-existing demos |
| `jvmTest` | fine — Gradle's test JVM has no `-XstartOnFirstThread` |
| an interactive Field window | **expected** fine (`glfwPollEvents` pumps the run loop) but **not covered by a test** |
| any other headless JVM with the GLFW flag | needs the property; `warm()` throws in 4s with the flag in the message |

Two designs were tried and removed, so nobody re-tries them:

- **`System.setProperty("java.awt.headless", "true")` from inside `warm()`** — far too late. By the
  time a box runs, something (Skiko, `BufferedImage`, `ImageIO`) has latched AWT already, and the
  property is only read once.
- **Falling back to running the probe inline on the caller's thread** — the caller is not the first
  thread either, so it hangs just the same. Worse than throwing.

`warm()` therefore throws rather than blocks. `imaging_field.kt`'s rule is that a stdlib call every
box might make must not be able to take the process down; a hang can, a diagnosable exception with a
sampled stack (`Typefaces.lastWarmStall`) cannot.

`Typefaces.available()` is split out from `warm()` for a related reason: `GraphicsEnvironment` is a
second and heavier hazard, and only family *enumeration* needs it. Loading a face by name, laying out
and extracting outlines all avoid it, so a live box never pays for it unless it asks for the list — or
passes `strict = true` to `named()`, which is the same question in disguise.

## Verification

| what | how | result |
|---|---|---|
| module compile | `./gradlew compileKotlinJvm compileTestKotlinJvm` | clean, no new warnings |
| structure | `./gradlew jvmTest --tests afield.stdlib.TypographyTest` | 38 / 38 |
| pixels | `python3 tools/run_field_tests.py --id stdlib.typography.gallery --port 952x` | 12 / 12 panels, all inspected by eye |
| the demo, through the real engine | `python3 tools/run_field_tests.py --id examples.demo-transcript.exec --port 952x` | `PASS typography_worked_example (applied=16, output lines=32)`; 31 demos green |
| the loadable document | `python3 tools/run_field_tests.py --id examples.agent-transcripts.materialize --port 952x` | green after regenerating `public/docs/assets/agent_transcripts/typography_worked_example/` |

The gallery asserts before every write — ink fraction in range, the control arm actually differing,
genus and watertightness, the resampler scaling with spacing — so a panel that produced a plausible
blank fails the test instead of shipping.

## Open ends

- **The RectSL letter font** (backlog, 2026-07-20: "a rect *letter*-font so axis TITLES / series
  NAMES become possible — numerals-only is the one real limit") is still open. This family is the
  supply side of it: `GlyphContour` polygons at a small size are exactly what a rect font needs. The
  remaining work is on the RectSL side.
- **Font features** beyond kerning — OpenType `liga`, `smcp`, variable-font axes — are whatever
  `Font.deriveFont(attributes)` gives you and nothing more. `Typeface.awtFont` is public so you can
  reach them; there is no first-class API.
- **Bidi and complex scripts.** `layoutGlyphVector` is called with `LAYOUT_LEFT_TO_RIGHT`. Arabic and
  Devanagari will produce glyphs but not correct text.
- **`onCurve` on a closed path** does not wrap: a glyph past the end is clamped (or extrapolated along
  the end tangent with `clampToPath = false`). Sizing the circle to the layout is the idiom, and it is
  what the docs page does.

## Two known defects, unfixed (added at closeout by the nightly orchestrator)

Both were found after the branch's own verification passed, so treat this section as the pre-merge
worklist rather than as history.

### 1. ~~`onCurve` is not continuous under a morphing curve~~ — FIXED (2026-07-30 closeout)

**Fixed** by a minimum-subdivision floor in `PathFlattener.emitCubicFrame`
(`src/jvmMain/kotlin/afield/graphics/PathFlattener.kt`, `minSubDiv = 3`, clamped against `maxSubDiv`
so the floor can never reach the bail-out that drops a segment). **`stdlib.typography.gallery` is now
14/14**, and `motion_morph` reports `median step 2.21, worst 6.06 (2.74x median)` against its 3x
threshold — down from 9.7x.

The floor depth was chosen by measurement rather than by the prototype's estimate: `minSubDiv` of
3 / 4 / 5 gives **2.74x / 2.73x / 2.73x**, i.e. the metric has plateaued by 3, so deeper floors cost
frames and buy nothing. Note this does *not* match the 1.6x the judge's Python port predicted — the
port and the running system disagree, and the running system is the one that counts. The residual
2.73x is not subdivision-limited and is not believed to be a defect: the panel deforms the curve
sinusoidally (arc length sweeps 940..1021), so a glyph pinned at a fixed arc-length offset genuinely
moves fastest mid-sweep. What the floor removed was the discontinuity; what remains is the motion.

Collateral checked: `jvmTest` **696 tests / 60 suites / 0 failures / 0 errors / 0 skipped**, and
`examples.demo-transcript.exec` PASS (all 31 demos). That suite failed on its *first* run here with
`ConcurrentModificationException at Box.resyncAll:1241` on `alife_worked_example` and passed on the
second — the known race, **not** this change, and the exact frame branch 2's `PropertyBag` fix
targets. It is a fourth independent sighting, and an argument for merging branch 2 first.

The original diagnosis follows, for the record.

`stdlib.typography.gallery` is **RED** on the `motion_morph` panel:

    IllegalStateException: a glyph jumped 9.7x the median step — the motion is not continuous
    at TypographyGallery.panelMotionMorph(TypographyGallery.kt:533)

13 of 14 panels pass, and the sibling `motion_march` panel proves the *fixed*-curve case is exact
(dL=7.331, mean step 7.331, worst per-step error 0.1%, 0 stalls, 0 jumps over 1246 steps). So
arc-length placement is right; rebuilding the table under a moving curve is not. The failing check
is correct and was deliberately kept — a kinetic-typography family whose text jumps when the path
animates should not ship claiming otherwise.

**DIAGNOSED (nightly judge, 2026-07-30) — and the defect is NOT in this branch.** The earlier
guess in commit `80b13eb8` ("knots at fixed parameter spacing") was wrong in mechanism. The real
cause is in `src/jvmMain/kotlin/afield/graphics/PathFlattener.kt:150-174`, which predates this
branch and is on `main`.

`emitCubicFrame` subdivides **adaptively by flatness**, recursing while
`flatnessFor(a, c1, c2, b) > tol`; `flatnessFor` (`:177-182`) is the control points' distance from
the chord. The `motion_morph` panel sweeps `lift = -300 * sin(phase)`, so at frames 0 and 45 the
lift is exactly zero, all four control points are **collinear**, flatness is exactly `0.0`, the
recursion stops immediately — and the entire cubic becomes **one linear segment**. `Cursor.setD`
(`FLinesAndJavaShapes.kt:729`) then degenerates to plain **parameter** interpolation. Since this
cubic is not uniformly parameterised (controls at x=260/640, not 313/627), the glyph teleports:

| frame | lift | flattener leaves | x at half arc-length |
| --- | --- | --- | --- |
| 89 | 20.93 | 82 | 469.97 |
| 0 | 0.00 | **1** | **455.00** |
| 45 | 0.00 | **1** | **455.00** |

A 14.98-unit jump against a median step of 0.297 — **50x median** for a mid-string glyph. The panel
reports 9.7x only because it averages over 32 glyphs and both axes, and the error vanishes near the
path ends.

**Consequences beyond typography:** the same flattener backs `Cursor.d`/`setD`,
`FLine.sampleByDistance` and `byDashing`, so **every arc-length spacing operation in Field**
mis-spaces any near-straight cubic. This is the highest-value item to come out of the night, and it
is not typography-specific.

**Tightening `tol` cannot fix it** — flatness is `0.0`, which is `<=` any tolerance. The fix is a
**minimum-subdivision floor** in `emitCubicFrame`; the judge measured `minSubDiv = 3` taking the
ratio from 50.4x to **1.6x** (the panel's threshold is 3x), costing 8 leaves for a straight cubic.
Roughly three lines. Expect `stdlib.typography.gallery` to go 14/14, and expect `byDashing` /
`sampleByDistance` goldens to shift slightly — toward correct.

### 2. ~~`flatShaded()` before a topology-printing sink publishes false captions~~ — FIXED (2026-07-30 closeout)

`Box.showMesh`, `Box.showMeshGrid` and `TriMesh.toHTML()` each compute `topology()` on the mesh they
are handed and print it as fact in the figcaption (`mesh_field.kt:383-393`, `:466-481`, `:582-592`).
`flatShaded()` un-welds every triangle (`V = E = 3F`, `mesh_process.kt:89-108`), so a flat-shaded copy
of a closed mesh captions as `χ=F · open (F boundary loop(s))`. And the call is **unnecessary**: every
render bridge already shades an unshaded mesh via `toCapturedMesh` (`mesh_field.kt:246`), which
`renderPng` (`:369`) and `renderPixels` (`:372`) both go through.

**All of it is now fixed.** The causal KDoc (`typography_field.kt` `toMesh`, `mesh_generate.kt:617`),
the docs-page prose that repeated the wrong rule, and every call site below. Sites 1-5 were edited by
hand; 6-9 are materializer output and were regenerated with
`run-jvm-main afield.examples.AgentTranscriptMaterializer --arg <repo>/public/docs/assets/agent_transcripts`.
There are now **zero** non-comment `flatShaded()` occurrences anywhere in the worked example, including
inside `metadata.edn`, so the loadable document is fixed and not just the page.

Verified after the change: `examples.agent-transcripts.materialize` **PASS** (the drift guard, which is
what caught the staleness in the first place) and `examples.demo-transcript.exec` **PASS** across all 31
demos — the edited box bodies still compile and execute.

The original site list, for the record:

| # | site |
| --- | --- |
| 1 | `public/docs/typography_examples.md:178` — `showMesh(o.flatShaded())`; line 177 prints the *truth* two lines above |
| 2 | `public/docs/typography_examples.md:205-208` — four `showMeshGrid` panels over marched SDF meshes, which are welded and closed by construction (`mesh_sdf.kt:44`) |
| 3 | `dev_docs/agents/transcripts/typography_worked_example.yaml:305-306` — worst case in the repo: the panel *title* carries the true genus and the figcaption directly beneath it carries the false topology |
| 4 | `dev_docs/agents/transcripts/typography_worked_example.yaml:311` |
| 5 | `dev_docs/agents/transcripts/typography_worked_example.yaml:342-345` |
| 6 | `public/docs/assets/agent_transcripts/typography_worked_example/typo_mesh_code_.kt:35,36,41` (published mirror of 3/4) |
| 7 | `public/docs/assets/agent_transcripts/typography_worked_example/typo_sdf_code_.kt:22-25` (published mirror of 5) |
| 8 | `public/docs/assets/agent_transcripts/typography_worked_example/document.md:333,334,339` and `:169-172` |
| 9 | `public/docs/assets/agent_transcripts/typography_worked_example/metadata.edn` — same code as an inline blob, re-executed on replay |

Mirrors 6-9 are materializer output, so fix 1-5 and regenerate
(`--id examples.agent-transcripts.materialize`) rather than editing them by hand.

**Scope, stated precisely:** none of the *committed* PNGs under
`public/docs/assets/typography_gallery/` carry these captions — they come from
`TypographyGallery.renderPng`, which computes no topology. The false captions are generated at
runtime, so this is latent-on-replay, not baked into any shipped image. `grep` for
`boundary loop(s)` in the docs therefore finds nothing.

**Not a defect, for contrast:** `TypographyGallery.kt:371-373` does this correctly — it asserts
`isWatertight()`/`genus()` on the *welded* mesh at `:359-362` and sends the flat-shaded copies only
to `renderPng`, which prints no topology. `MeshGallery.kt:681-697` is also safe (hand-written `facts`
string, not the topology-computing helper). And every existing *assertion* on a flat-shaded mesh in
the tree asserts its falseness deliberately, as a weld/repair regression test — `MeshCoreTest.kt:295-302`,
`:306-310`, `:391-395`, `MeshProcessTest.kt:48-58`, `MeshIoTest.kt:134-138`, `:148-150` — so no
existing assertion is vacuous.
