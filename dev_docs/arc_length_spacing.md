# Arc-length spacing: the defect, the fix, and the numbers

*2026-07-31.*

Everything in Field that promises "a distance along this curve" — `Cursor.d` / `Cursor.setD`,
`FLine.midPoint`, `fractionAlong`, `FLine.sampleByNumber`, `FLine.sampleByDistance`,
`FLine.byDashing`, `FLine.byArcLengthParameterizing`, `FLineSamplers.byDistance` — funnels into
one place: `FLinesAndJavaShapes.Cursor`, which builds a `PathFlattener` and asks it to convert a
distance into a `node.t`. Until this change that conversion silently degenerated to plain
*parameter* interpolation on a whole class of perfectly ordinary curves.

## The defect

`PathFlattener.emitCubicFrame` bisects each cubic while the error exceeds `tol` and, when it stops,
emits one `Mapping` via `emitLinearFrame`. A `Mapping` records `dotStart`, `dotEnd` and
`cumulativeDistanceAtEnd`, and `lengthToDot` interpolates **linearly in `t`** inside one of them. So
a leaf is only an acceptable approximation when the cubic is *both* geometrically straight *and*
evenly parameterised.

The error metric was `flatnessFor`: the distance of the two control points from the chord. That sees
the first condition and is completely blind to the second.

```kotlin
// control points exactly ON the chord, bunched at the far end
FLine().moveTo(0f, 0f).cubicTo(Vec3(450f, 0f, 0f), Vec3(480f, 0f, 0f), Vec3(500f, 0f, 0f))
```

`flatnessFor` on that is exactly `0.0`. No tolerance can help — `0.0 <= tol` for every `tol >= 0` —
so the recursion terminated at **one leaf** and the arc-length table for a 500-unit curve became a
single linear ramp. `B(0.5)` on that cubic is at x = 411.25, not 250, so "the point half way along"
came out 161 units — a third of the whole curve — from where it belonged.

**How common is that input? Less common than this page originally claimed, and the correction is
worth reading.** The first draft of this section asserted that "a straight stem stored as a cubic is
what font outlines and imported SVG are full of". That was reasoning, not measurement, and when it
was measured it turned out to be false for the case it named: `GlyphArcLengthTest` pulls twelve real
outlines out of the JVM's logical `SANS_SERIF` via `Font.createGlyphVector(...).outline` and finds
**zero flatness-blind cubics in any of them** — the font stores straight stems as `LineTo`, and its
curved cubics keep their control points well off the chord, so the old criterion already subdivided
them correctly. Arc-length spacing on real glyphs measures **1.001× before this fix and 1.000× after**.

So the honest statement of the blast radius is narrower: the defective shape is a cubic whose control
points sit on (or very near) its chord, it is trivially constructible by hand, `ArcLengthTestCurves`'
hand-built `glyphish` curve exercises it (1.107× → 1.000×), and it is exactly what a bulk
`cubicTo`-emitting generator produces — but the claim that real font outlines are full of it did not
survive contact with a font. Imported SVG remains **unmeasured**; do not repeat the original claim
about it either without checking.

## The fix

Two changes in `src/jvmMain/kotlin/afield/graphics/PathFlattener.kt`:

1. **`subdivisionErrorFor` = `max(flatnessFor, parameterSkewFor)`.** The new term is
   `|B(0.5) - (a + b) / 2|`: the gap between the curve's mid-*parameter* point and the chord's
   mid-*point*. It is zero exactly when `t` is locally tracking distance.

   Neither term subsumes the other, which is why it is a `max` and not a replacement:

   | curve | flatness | parameter skew |
   |---|---|---|
   | symmetric S-curve | 200 | **0** |
   | collinear, bunched | **0** | 161 |

   A symmetric S-curve has `B(0.5)` sitting precisely on the chord's midpoint. Replacing the metric
   would have stopped that curve at one leaf — a far worse bug than the one being fixed.

2. **`minSubDiv = 2`.** Adaptive subdivision is blind to symmetry. The curve
   `(0,0)-(240,0)-(260,0)-(500,0)` is collinear *and* symmetric about its chord midpoint, so *both*
   terms are exactly `0.0` while `t` still runs 16% fast through the middle. Two forced levels break
   that symmetry, because the halves of such a curve are not themselves symmetric.

## The numbers

From `src/jvmTest/kotlin/afield/graphics/ArcLengthSpacingTest.kt`, which scores the real Kotlin path
against `ArcLengthOracle` — an independent high-resolution numerical arc-length table that shares no
code with `PathFlattener` (it walks the node list itself, in double precision, 8192 samples per
segment). 64 equally spaced `setD` requests per curve, `tol = 0.01`:

| curve | leaves before → after | step max/median | worst positional error |
|---|---|---|---|
| collinearCubic | 1 → 184 | 3.346 → **1.001** | 165.13 → **0.010** of 500 |
| nearCollinearCubic | 1 → 184 | 3.346 → **1.001** | 165.13 → **0.010** of 500 |
| symmetricCollinear | 1 → 110 | 1.493 → **1.001** | 21.16 → **0.010** of 500 |
| uniformStraightCubic | 1 → 9 | 1.000 → 1.000 | 0.000 → 0.000 |
| collinearBackAndForth | 19 → 396 | 2.014 → **1.001** | 77.54 → **0.007** of 841 |
| sCurve | 208 → 208 | 1.001 → 1.001 | 0.008 → 0.008 |
| sharplyCurved | 256 → 256 | 1.000 → 1.000 | 0.003 → 0.003 |
| glyphish | 416 → 467 | 1.107 → **1.000** | 15.36 → **0.007** of 1960 |

`sCurve` and `sharplyCurved` are the control group: identical leaf counts, identical numbers to the
last printed digit. Nothing that was right got touched.

Per-consumer, worst positional error against the oracle:

| curve | `midPoint` | `fractionAlong` | `sampleByNumber` | `byArcLengthParameterizing` |
|---|---|---|---|---|
| collinearCubic | 161.250 → 0.003 | 161.250 → 0.004 | 165.037 → 0.009 | 3.295 → 1.001 |
| symmetricCollinear | 0.000 → 0.000 | 20.625 → 0.009 | 21.149 → 0.009 | 1.515 → 1.001 |
| collinearBackAndForth | 0.000 → 0.000 | 69.573 → 0.006 | 77.577 → 0.008 | 1.819 → 1.000 |
| glyphish | 0.002 → 0.002 | 13.869 → 0.004 | 15.380 → 0.005 | 1.083 → 1.002 |

`midPoint` reads 0.000 at base for several curves purely by luck: the halfway point is the one place
a symmetric curve's parameterisation error cancels. That is why the audit probes five fractions too.

**Total length was never wrong** — worst relative error against the oracle is 1.2e-5 before and
after. This was purely a mis-*distribution* defect, which is a useful discriminator: any candidate
fix that changed the reported length would have been suspect.

## Cost

`PathFlattener` is **not** on the GPU render path. It is constructed in exactly one place in all of
main source (`FLinesAndJavaShapes.kt:810#return PathFlattener(this.on, tol)`, inside `Cursor.pathFlattener`), and
`FLinesAndJavaShapes.positions` — the only other consumer of its `mappings` — has no callers in
`src/`. So this is the cost of geometry operations, not of frames.

**Field has a second, independent flattener, and it is the one that draws.** `StandardDrawing.linesOut`
(`graphics/StandardDrawing.kt:206#f.uniform_linearize(samplesPerCubic`) calls `FLine.uniform_linearize` (`FLine.kt:857#Unused parameters:`), which emits a fixed
`samplesPerCubic` (default 100) samples per `CubicTo` at **uniform parameter**; fills reach the same
function through `FLine.tessellate`, and `ThreeJsViewer.kt` is a third caller. Nothing on that path
constructs a `PathFlattener` or a `Cursor`.

That second flattener does **not** share this defect, and the reason is structural rather than lucky:
it never reports a distance, so it cannot report a wrong one. Its samples are unevenly spaced *along*
a badly parameterised cubic, but every sample lies exactly on the curve, so the drawn shape is right.
`RenderPathFlatteningTest` pins this — and measures the corollary that matters if anyone is ever
tempted to unify the two: at equal sample budget, spacing by arc length is **two orders of magnitude
worse for shape fidelity** than the uniform-parameter spacing the renderer already uses. Unifying
them would be a regression, not a cleanup.

The worst case is a polyline stored as straight cubics, where `minSubDiv` is pure overhead: 1000
straight cubics go from 1000 to 4000 leaves and from 277.7us to 341.6us to build (1.23x — the leaf
count quadruples but per-leaf work is cheap next to the de Casteljau splits). Anything actually
curved already produced hundreds of leaves per cubic, so the floor never binds there. No
`maxSubDiv reached` warnings on any curve in the family.

`subdivisionCostStaysBounded` in the test file keeps a hard ceiling on the polyline leaf count so a
future change cannot quietly make this quadratic.

## Two neighbours fixed at the same time

**`byDashing` cut corners.** Each dash was a single straight `moveTo`/`lineTo` between its
endpoints, which is only right on a straight stretch. The flattener fix gave it the right endpoints
but the chord between them still short-cut the curve — glyphish dash lengths were 47.8% wrong before
*and* after the flattener fix. `byDashing` now subdivides along the curve, at most `resolution`
(new, defaulted third parameter, 2 units) per straight piece. Worst dash-length error:

| curve | base | after flattener | after byDashing |
|---|---|---|---|
| collinearCubic | 159.6% | 0.0% | 0.0% |
| collinearBackAndForth | 90.8% | 86.7% | 2.2% |
| glyphish | 47.8% | 47.8% | 1.8% |

**A `Cursor` exploded on a `Close` node.** `FLine.Close` is `Node(0)` and stores no points, but
`Node.to` is `storage[0]`, so reading `.to` on a Close throws `IndexOutOfBoundsException`. `Cursor`
did exactly that in sixteen places. Two ways to hit it: park the cursor at the end of a closed line
(`clamp(index + 1)` collapses onto `index`, `segmentIsClose()` is then true for the Close itself), or
step past an interior Close in a multi-subpath line. Every arc-length operation was therefore
unusable on any line that actually carries a `Close` node. Fixed with one `endOf(i)` accessor that
resolves a Close to the start of the subpath it closes, which is genuinely where that instruction
leaves the pen. Covered by `CursorClosedLineTest`.

**But not "every glyph outline", which is what this page said first.** Glyph outlines arrive through
`FLinesAndJavaShapes.javaShapeToFLine`, whose `SEG_CLOSE` branch emits an explicit `lineTo` back to
the last `moveTo` (`FLinesAndJavaShapes.kt:2205#ty == PathIterator.SEG_CLOSE`) and never calls `close()` — so an AWT glyph
outline contains **no `Close` node at all** and never had one to trip over. The lines that did are the
ones Field code closes itself with `.close()`. The crash was real and the fix is real; the population
it was hurting was smaller and differently shaped than the first draft of this page asserted.

~~One caveat on the fix's completeness, so nobody reads this as closed: the `nodes[i - 1].to` pattern
that caused this appears in roughly a dozen further sites outside `Cursor` — `byReversing`,
`findSelfIntersections`, `bySubdividing` (which additionally drops `Close` nodes silently), `tcbTo`,
and the `intersect` family — and an **interior** `Close` still throws there. No test covers that case
yet.~~

**Closed out 2026-08-01** — see [`fline_close_nodes.md`](fline_close_nodes.md), which carries the
whole `Close` story now, interior and trailing, plus the blast radius. Two corrections to the
paragraph above, both from actually running it rather than reading the source: the count was not
"roughly a dozen" but **80 operation/curve pairs across 18 operations**, and it reached further than
this list — `windingNumber` and therefore `FLine.contains`, `bySimplifying`, `byConvexHull2D`,
`byFiltering` and `fast2DBounds` were all in it. The `intersect` family turned out to be carrying a
*different* bug as well, a `nodes.get(-1)` off-by-one with nothing to do with `Close`.

## Seeing it

`dev_docs/agents/transcripts/arclength_spacing_demo.yaml` replays into a fresh document and computes
both spacings live, side by side — two pure-compute boxes, no Window and no Renderer:

```
python3 tools/field_backdoor.py --port <free port> --with-playwright \
  --command "transcript write arclength_spacing_demo --file dev_docs/agents/transcripts/arclength_spacing_demo.yaml" \
  --command "transcript run arclength_spacing_demo"
```

(one invocation: the scratch document's lifetime is per-invocation, so a `write` in one call and a
`run` in the next land in different scratch sessions).

The `arclength_ticks` box is the picture worth having: 24 equal steps of `t` on the collinear cubic
pile up into a solid block at the far end (max/median gap 2.821) while 24 equal steps of arc length
next to it are perfectly even (1.000).

`public/docs/assets/dashing.png` is the one committed asset that pinned this behaviour. Regenerated;
the dashes now visibly bend with the curve where they used to be straight chords.

## Files

| file | what |
|---|---|
| `src/jvmMain/kotlin/afield/graphics/PathFlattener.kt` | `subdivisionErrorFor`, `parameterSkewFor`, `minSubDiv` |
| `src/jvmMain/kotlin/afield/graphics/FLinesAndJavaShapes.kt` | `endOf` / `subpathStartAt`; a leftover debug `println` removed from `evaluateDCubicFrame`; `normal2` KDoc now says it is not normalized |
| `src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt` | `byDashing` follows the curve; `resolution` parameter |
| `src/jvmTest/kotlin/afield/graphics/ArcLengthSpacingTest.kt` | `ArcLengthOracle`, the curve family, five scoring tests |
| `src/jvmTest/kotlin/afield/graphics/CursorClosedLineTest.kt` | the `Close`-node regressions |
| `dev_docs/agents/transcripts/arclength_spacing_demo.yaml` | the replayable before/after |
