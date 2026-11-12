# clothoidSplineFrom: a G2 Euler-spiral spline (S9, weight 1)

Source idea: Aurimas Gasiulis, "S9" (https://gasiulis.name/new-spline, scout card f010d03670c4).
The page gives no formulas and no licence; nothing was copied. Built from the method as described
and from Levien's thesis (2009).

## Method
- Each segment between knots is the planar clothoid fixed by its two end tangent angles: the
  existing private `fitClothoidG1` in `FLineGeometry.kt` (Bertolazzi & Frego) returns κ, κ', L.
- Unknowns: one tangent angle per knot. Residual at an interior knot: (κ_end(prev) − κ_start(next))
  × mean neighbouring length (dimensionless). Open curves add κ'·L² = 0 on the first and last
  segments (they end as circular arcs). Closed curves wrap.
- Damped Newton with a finite-difference Jacobian; perturbing one angle refits only its two
  segments and moves only three residuals, so the Jacobian is tridiagonal (cyclic when closed).
  Solved with pivoted `dgtsv`-style elimination, Sherman–Morrison for the cyclic corners (answer
  checked against the full system; a dense solve rescues closed curves up to 400 knots). Initial
  angles: Catmull-Rom directions, ends reflected about the end chord.
- Converges quadratically: three iterations to |r| < 1e-10 on the docs shapes.
- Failure (fit null, singular Jacobian, residual > 1e-6·n) falls back to `cubicSplineFrom`, per
  stretch between corners (whole curve for a closed curve without corners).

## What is not S9
S9 computes two weights per segment from its end angles; that rule is unpublished and not
implemented. Since 2026-11-12 there is a weight family of our own with one user `weight` for the
whole spline: see "S9 weights (2026-11-12)" below.

## Measuring curvature on float polylines (trap)
Three-point circles through neighbouring samples on a 200-per-segment float polyline give ~5%
curvature noise on a radius-100 circle. Tests and `curvatureComb`/`curvaturePlot` take circles
through samples several steps apart. Continuity at knots is tested by extrapolating each side
linearly to the knot (curvature is linear within a piece), threshold 1% of peak.

Linear curvature per piece means at most one inflection per segment by construction.

## Follow-up pass (2026-11-11): what was done from the first page's list

Done, each with tests in `ClothoidSplineTest` and a section on `fline_examples.md`:

- **Corners** (`corners = [i, ...]`, indices into the caller's list, surviving dropped repeats). Each
  stretch between corners is an open solve with arc ends; two adjacent corners give a straight
  segment; a closed shape with corners is walked from its first corner (so it starts there). Corner
  nodes carry `clothoidCorner = true`, which the comb's scale check skips.
- **Fixed end directions** (`startDirection`, `endDirection`). The end residual becomes
  `θ − θ_fixed` in place of `κ'L² = 0`; the fixed heading is taken within π of the end chord. A
  two-knot open curve with one or two fixed ends is now solved (one Euler spiral) instead of a
  straight line. Closed curves reject them (`require`).
- **Many knots.** The note said dense elimination was "fine to a few dozen knots"; measured, the
  dense solve already did 1000 knots in ~0.17 s, because the cost is the per-piece Fresnel fits, not
  the algebra. The banded solve matters past that: O(n) memory instead of n² doubles (5000 knots =
  200 MB dense) and no n³ term. Measured with the banded solve: 600 closed knots ~0.17–0.2 s, 5000
  ~0.7–2 s (varies with machine load). Warm-starting each piece fit from the previous `A` was tried
  and gave no measurable gain (the quadrature dominates), so it was taken out.
- **Comb after a transform.** The recorded curvature is compared with the turning measured at each
  node (turn ≈ κ·mean neighbouring spacing); a least-squares factor corrects uniform scale (1/s) and
  mirroring (−1); a poor fit (non-uniform scale, an edit) falls back to measuring the shape.
- **2.5D (Marc asked for 3D / 2.5D extensions).** The curve is solved in a plane (default XY,
  `planeNormal`, or the best-fit plane of the knots for `planeNormal = null`, via Jacobi on the
  covariance) and the height along the normal is a cubic spline in plane arc length through the
  knots' heights: natural ends, clamped to a 3D end direction's slope, periodic when closed. The
  plane curve is C2 in its arc length (G2, unit speed) and the height is C2 in the same parameter, so
  the space curve is C2 in that parameter and therefore G2. Points on a helix give the helix (open
  ends are arcs and a natural spline reproduces a linear height). Previously all points took the
  first point's Z; a flat input gives the same curve as before (to the solve's tolerance). Recorded curvature (for the comb)
  is plan curvature and is only written in the XY frame.

Not done, and why:

- **The S9 weights** — no formula or licence to work from (above); still a research item.
- **A Hobby comparison** — a separate spline with its own function and page section; worth doing,
  but it is not a limitation of this one.
- **Draggable knots through `notation`** — box-level wiring rather than spline code, and a truthful
  capture needs strokes in the drawing layer; better done with the drawing-layer work.
- **Robustness on doubling-back paths.** Probed: a zigzag with 10-unit steps and 80-unit teeth, and
  a path going out and back along nearly one line, fail the Newton solve and fall back to cubics; a
  4-point hairpin (100 × 10) and a square solve fine. Corners at the sharp turns are the remedy, and
  the page says so. A continuation start (solve with the knots pulled toward a smooth curve, then
  walk them back) is the next thing to try if this matters.

## True 3D: design notes for the next pass

The 2.5D curve bends in one plane only; its torsion comes from the height spline, not from the
pieces. A 3D member of the family needs a piece fixed by two points and two 3D end tangents. Options:

1. **Linear curvature vector in a parallel-transport frame.** T' = k1(s)·N1 + k2(s)·N2 with k1, k2
   linear in s (four parameters plus length). The G1 Hermite problem (match P1 and T1: five
   conditions) leaves a one-parameter family; pick the member minimising ∫|k'|² (the 3D analogue of
   "linear curvature"). Knot solve: unknowns are the 3D tangents (two angles per knot), residuals the
   curvature vector jump (two components per knot) — a block-tridiagonal system of 2×2 blocks, so
   the banded solver generalises directly.
2. **Planar pieces in rotating planes** (Levien's suggestion for 3D splines): each piece a planar
   clothoid in the plane of its chord and averaged tangents, continuity of the curvature vector
   only approximately. Cheaper, but G2 is not exact.

`eulerSpiralBetween3D` (planar clothoid in the plane of the two directions plus a linear lift) is
the existing two-point stand-in. Start with option 1 and a test that a 3D helix sampled at uneven
knots is reproduced (constant curvature and torsion is inside the family).

## Where to go next (in order)

1. True 3D pieces, option 1 above, with the block-tridiagonal knot solve.
2. A continuation start for doubling-back paths, so fewer stretches fall back to cubics.
3. Faster piece fits: `generalizedFresnel` uses 1024 Simpson panels per call; Gauss–Legendre or
   the asymptotic Fresnel expansions (Bertolazzi & Frego's own) would cut the solve several times.
4. Curvature-matched fixed ends (an extra free piece per fixed end).
5. Hobby's spline as its own function, compared on the page with combs.
6. Draggable knots and corners from the drawing layer (`notation`).
7. A per-segment weight rule (lower the weight only on pieces that would dip toward zero
   curvature), then compare with S9's lettering examples.

## S9 weights (2026-11-12)

`clothoidSplineFrom(..., weight = w)` in `src/jvmMain/kotlin/afield/geometry/path/FLineGeometry.kt`.
Public page: `public/docs/fline_examples.md`, "Sharper turns at the points".

### Sources

Aurimas Gasiulis, S9 (https://gasiulis.name/new-spline): segments are Cesàro curves (curvature as a
function of arc length) fixed by their two end tangent angles, with two weights; weight 1 is the
Euler spiral; below 1 the turn sharpens near the ends, curvature maxima sit at or near knots, one
inflection at most per segment. The page has no licence and gives neither the curvature formula
nor its weight rule, so nothing was copied: the family below is our own, built from Levien,
*From Spiral to Spline* (2009, the Euler-spiral spline and its global tangent solve) and Moreton,
*Minimum Curvature Variation Curves* (1992, why monotone curvature reads as fair).

### The family we chose

Per segment, `t = s/L`, dimensionless curvature `K = κ·L`:

    F(t) = F0 + (F1 − F0)·t,     K(t) = sign(F)·|F|^(1/w)

- `w = 1`: `K` linear in `t`, the Euler spiral. The code keeps the old Bertolazzi–Frego fit and the
  old heading expression on that path, so weight-1 output is unchanged (the existing six
  `clothoid_spline_*` captures re-rendered byte-identical after the change).
- `w < 1`: `K` is a convex/concave power of a linear function, so it lingers near its smaller end
  value and climbs steeply into the larger. Monotone in `t` ⇒ at most one inflection, extremes at
  the knots. `F0 = F1` ⇒ constant curvature ⇒ circles are exact at any weight.
- Turn has a closed form: `∫₀ᵗ K = (|F(t)|^(e+1) − |F0|^(e+1)) / ((e+1)(F1 − F0))`, `e = 1/w`
  (midpoint rule when `F1 ≈ F0`).

Fit (`fitWeightedPiece`): chord frame, unit chord; unknowns are the end values `K0`, `K1` (not
`F0`, `F1`: `dK/dF → 0` at `F = 0` for `e > 1` made the Jacobian singular on near-straight pieces).
Conditions: `∫K = φ1 − φ0` and `∫ sin ψ = 0`; 2-D Newton with backtracking, started from the
Euler fit; `L = chord / ∫ cos ψ` (Simpson, 64 panels). The knot solve (`solveClothoidSpline`) is
reused unchanged except the free-end condition is written as `bendSpread` (`K1 − K0`, which is
`κ'L²` at weight 1).

### Weight rule: not S9's

S9 computes two weights per segment from its end angles. We expose one user `weight` for the whole
spline. Reason: the rule is unpublished, and a per-segment rule makes the segment family depend on
the unknowns the global Newton is solving, which needs care to stay differentiable. Next step
written on the page: lower the weight only on pieces that would dip toward zero curvature.

### Traps found

- **The first family was wrong.** An odd-symmetric shape `K = a + b·½sign(u)|2u|^(1/w)` (flat
  middle, both ends steep) passed every unit test (G2, ≤1 inflection, circles) but *introduced*
  inflections on convex outlines (egg: min/max curvature 0.006 → −0.16 at w = 0.3): flattening the
  middle forces the low end below zero. The criterion to test a family against is "convex knots
  stay convex", not the per-segment properties.
- Symmetric pieces (equal end curvature) are arcs at every weight; a symmetric rounded rectangle
  shows no difference, so it is useless as a demo.
- Box code: `list + [item]` and `zeros + [a, b]` hit Kotlin's `plus` overload ambiguity with
  collection literals; name a typed `List<FLine>` first.

### Measured (convex test shapes, closed, min/max curvature)

| shape | w=1 | 0.7 | 0.5 | 0.3 |
|---|---|---|---|---|
| egg2 (page) | −0.080 | 0.005 | 0.027 | 0.045 |
| egg3 | −0.136 | −0.026 | 0.003 | 0.016 |
| bean | −0.191 | −0.085 | −0.007 | 0.003 |
| pebble2 | 0.058 | 0.090 | 0.105 | 0.116 |
