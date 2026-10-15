# FLine exact booleans and offsets (2026-10-13)

Follow-up to `fline_paperjs_parity.md`, which listed "exact (non-flattening) boolean ops" and
"offsetting with exact cubic output" as not done. Code: `src/jvmMain/kotlin/afield/geometry/path/FLineExact.kt`.
Tests (these print the tables below): `src/jvmTest/kotlin/afield/geometry/path/FLineExactBooleanTest.kt` and
`FLineExactOffsetTest.kt`. User docs: "Exact booleans and offsets" in `public/docs/fline_examples.md`.
Demo: `dev_docs/agents/transcripts/fline_exact_booleans.yaml` (registered `EXECUTE`). Before/after
renders through `lines()`: `dev_docs/assets/fline_exact/booleans.png`, `offsets.png`.

## What the measurements changed about the plan

The backlog item said the booleans "go through java.awt.geom / flattening, so results lose their
cubic segments". **That turned out to be wrong for the booleans.** `java.awt.geom.Area` keeps quadratic and
cubic segments (its `Order3` curves), and `javaShapeToFLine` copies `SEG_CUBICTO` through. Measured
on the corpus, Area's answers stay within about 1e-8 of the size of the true outline, which is the
precision of float storage. What Area does cost is segments: it splits every cubic at its y-extrema,
so its answers carry about 40% more segments than needed. Its other limits: it drops z, and a
thickened operand is stroked first, which the exact path hands back to Area.

The offsets did match the backlog description. `byOffsetting` samples 50 points per node, joins
them with straight lines, leaves corners open, and draws one polyline across separate subpaths (the
ring glyph's error of 12 units is its bridge between the two circles).

So the booleans are opt-in (`exact = true` / `booleanExact`) and claim only "same accuracy, fewer
segments, and they say when they fell back". `byOffsettingExact` is a new function beside
`byOffsetting`, not a replacement, because `byOffsetting`'s output (dense, evenly sampled) is
something other code may depend on.

## Boolean engine

1. `exactContours()`: each subpath becomes a closed chain of `XBez` (double precision cubics,
   lines degree-elevated but flagged `straight`). Open subpaths are closed by a straight segment,
   as AWT fills them.
2. All pairs of segments across both operands, self pairs included: crossings by bounding-box
   subdivision to a chord flatness of 2e-6 of the size, refined by 2-D Newton. Before that, each
   curve's end points are projected onto the other (`nearestT`: 32 samples, golden section,
   guarded Newton). That catches T-junctions and tangencies. When two projections bracket a span
   along which the curves coincide (`sameCurve`), the pair is an overlap: only those end hits are
   kept and the subdivision is skipped.
3. Each segment is cut at its hits. Piece ends are snapped to the shared hit point and then to
   tolerance-merged vertices, so pieces meet exactly.
4. Classification: at each piece's midpoint the winding numbers of both operands are evaluated a
   hair (1e-6 of the size) to the left and to the right. The winding uses a horizontal ray, the
   cubic is split into y-monotone pieces, and each piece is bisected. The piece is kept when the op
   (non-zero) gives different answers on the two sides, and it is oriented with the result on its
   left. This one rule handles subtract/xor orientation and coincident edges. Two coincident pieces
   facing the same way are both kept, then de-duplicated. Facing opposite ways (a shared edge in a
   union), both are dropped.
5. Chaining: out-edges are listed by vertex. Where a vertex has more than one (a pinch or a tangent
   touch), the chain takes the sharpest left turn.
6. Re-joining: consecutive pieces cut from the same operand segment, adjacent in parameter and
   running the same way, are merged back into one sub-curve of the original. This is why a cut
   circle has 7 segments, not 8.
7. Check: the signed area of the loops (Gauss-Legendre on the cubics) must match the area of the
   Area answer, flattened to 1e-5 of the size, within 0.2%. On a mismatch, an open chain or a chain
   that does not close, the Area answer is returned with `exact = false` and a `fallbackReason`.

## Offsets

- Per cubic: the end points are moved along `normal2D` (the same side as `byOffsetting`) and the end
  tangents kept. The two handle lengths are solved so the approximant passes through the true offset
  at t = 1/2. That is a 2x2 system in the handle directions. Where it is ill-conditioned or gives a
  negative length, Tiller-Hanson curvature scaling `1 + d*kappa` is used instead. The error is the
  largest | distance(approximant(t), original) - |d| | over 15 samples. Past `tolerance` the
  original is halved and both halves offset, to a depth of 10.
- Straight segments are offset exactly.
- Joins, where the tangent turns: on the outer side ROUND (arc in cubics of up to 90°, k = 4/3
  tan(θ/4)), MITER (falls back to bevel past `miterLimit`) or BEVEL. On the inner side of a closed
  subpath the join runs through the vertex (A → V → B), and the resolve step below removes it. On
  the inner side of an open run, the two adjacent offset pieces are cut where they cross (the last
  and first six pieces are searched).
- Closed subpaths: all offset contours together go through the boolean engine's `resolve` with a
  positive-winding rule (inside when winding × the sign of the original's total area is > 0). This
  removes the concave-corner swallowtails (winding 0 or 2s) and the inverted parts of a collapsing
  shrink (winding -s).
- Open runs: `removeInvertedLoops` cuts out a self-crossing loop when its middle lies nearer the
  original than |d| - tolerance. A genuine loop, one the original line has itself, is kept.

## Measured (FLineExactBooleanTest, FLineExactOffsetTest)

Booleans, "off" = the largest distance from points on the result's own segments to the nearest
input curve, as a fraction of the size. Every corpus case came back exact:

| case | segments Area → exact (Union / Intersect / Subtract / Xor) | off, Area → exact |
| --- | --- | --- |
| circles | 8→7 / 6→5 / 8→7 / 14→12 | 1.4e-8 → 1.4e-8 |
| rounded rects | 14→14 / 6→6 / 10→10 / 20→20 | 1.0e-8 → 1.0e-8 |
| ring and bar | 16→16 / 12→12 / 12→12 / 28→28 | 1.2e-8 → 1.2e-8 |
| blob and circle | 9→7 / 5→5 / 9→7 / 14→12 | 2.5e-8 → 1.9e-8 |
| tangent circles, shared edge, same circle, nested circles | equal | ≤ 1.6e-8 both |

60 seeded random blob pairs × 4 ops: 240/240 exact, 2141 segments against Area's 3020. Worst "off":
Area 7.8e-8, exact 1.8e-8. Worst area error against the polygon reference: 7.8e-8 against 7.5e-8.

Offsets at tolerance 0.01. The error is | distance to the original - |d| |, at points on each result's own
segments, chords included:

| case | d | segments sampled → exact | max error sampled → exact | exact area vs Minkowski |
| --- | --- | --- | --- | --- |
| circle r100 | ±20 | 200 → 4 | 0.016 / 0.011 → 0.0055 | ≤ 7e-5 |
| rounded rect | ±15 | 400 → 8 | 0.006 / 0.002 → 0.0041 | ≤ 2e-5 |
| blob | 10, -10, 30, -25 | 200 → 18, 16, 23, 15 | 0.08, 0.12, 0.18, 3.9 → ≤ 0.0096 | ≤ 2e-5 |
| star (concave corners) | 10, -8, -30 | 501 → 20, 15, 5 | 6.8, 8.0, 30 → ≤ 0.001 | ≤ 4e-7 |
| ring glyph | 8, -8, 25 | 400 → 8 | 12, 12, 24 → ≤ 0.0068 | ≤ 1e-4 |
| open S-curve | ±10 | 150 → 16, 13 | 5.1, 10.0 → 0.0082, 0.0079 | (open) |

The rounded rect's -15 case is the only one where the sampled polyline was closer than the exact
(0.002 against 0.004). Both are within tolerance, and it uses 400 segments to do it.

## Known limits

- A single cubic that loops back across itself (a self-loop inside one segment) is not cut against
  itself. Neither operand-level self-crossings nor the classification need that for ordinary
  shapes, but a figure-eight drawn as one cubic would be filled wrong and then caught by the area
  check, so it falls back.
- Curves that coincide only partly and are not split at the same parameters are handled by the
  end-point projections. A coincidence that starts and ends strictly inside both curves (neither
  curve's end on the other) is not detected and falls back through the area check.
- The open-run inner-corner trim searches six pieces each side. A very short segment between two
  long ones with a large d can leave the trim unfound, and then the corner gets a straight
  connector.
- z is dropped (xy only), as with Area.
- Nothing is default yet. Making `exact = true` the default for the booleans would be safe by the
  numbers above, since the fallback is the old path, but it would change the segment structure of
  existing documents' results. That is Marc's call.
