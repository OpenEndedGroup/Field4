# S9 weights for `clothoidSplineFrom`

`clothoidSplineFrom(..., weight = w)` in `src/jvmMain/kotlin/afield/geometry/path/FLineGeometry.kt`.
Public page: `public/docs/fline_examples.md`, "Sharper turns at the points".

## Sources

Aurimas Gasiulis, S9 (https://gasiulis.name/new-spline): segments are Cesàro curves (curvature as a
function of arc length) fixed by their two end tangent angles, with two weights; weight 1 is the
Euler spiral; below 1 the turn sharpens near the ends, curvature maxima sit at or near knots, one
inflection at most per segment. The page has no licence and gives neither the curvature formula
nor its weight rule, so nothing was copied: the family below is our own, built from Levien,
*From Spiral to Spline* (2009, the Euler-spiral spline and its global tangent solve) and Moreton,
*Minimum Curvature Variation Curves* (1992, why monotone curvature reads as fair).

## The family we chose

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

## Weight rule: not S9's

S9 computes two weights per segment from its end angles. We expose one user `weight` for the whole
spline. Reason: the rule is unpublished, and a per-segment rule makes the segment family depend on
the unknowns the global Newton is solving, which needs care to stay differentiable. Next step
written on the page: lower the weight only on pieces that would dip toward zero curvature.

## Traps found

- **The first family was wrong.** An odd-symmetric shape `K = a + b·½sign(u)|2u|^(1/w)` (flat
  middle, both ends steep) passed every unit test (G2, ≤1 inflection, circles) but *introduced*
  inflections on convex outlines (egg: min/max curvature 0.006 → −0.16 at w = 0.3): flattening the
  middle forces the low end below zero. The criterion to test a family against is "convex knots
  stay convex", not the per-segment properties.
- Symmetric pieces (equal end curvature) are arcs at every weight; a symmetric rounded rectangle
  shows no difference, so it is useless as a demo.
- Box code: `list + [item]` and `zeros + [a, b]` hit Kotlin's `plus` overload ambiguity with
  collection literals; name a typed `List<FLine>` first.

## Measured (convex test shapes, closed, min/max curvature)

| shape | w=1 | 0.7 | 0.5 | 0.3 |
|---|---|---|---|---|
| egg2 (page) | −0.080 | 0.005 | 0.027 | 0.045 |
| egg3 | −0.136 | −0.026 | 0.003 | 0.016 |
| bean | −0.191 | −0.085 | −0.007 | 0.003 |
| pebble2 | 0.058 | 0.090 | 0.105 | 0.116 |
