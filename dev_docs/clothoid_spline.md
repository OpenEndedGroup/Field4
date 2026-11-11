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
  segments. Dense Gaussian elimination (fine to a few dozen knots). Initial angles: Catmull-Rom
  directions, ends reflected about the end chord.
- Converges quadratically: three iterations to |r| < 1e-10 on the docs shapes.
- Failure (fit null, singular Jacobian, residual > 1e-6·n) falls back to `cubicSplineFrom`.

## What is not S9
S9's per-segment weights (lower weights sharpen the turn near segment ends, computed from the end
angles) are not implemented: the page does not state the curve formula or weight rule, and the
GitHub source has no stated licence. With weights 1, S9 is this spline. Next step if wanted: a
two-weight Cesàro family κ(s) and a weight rule of our own, swapped in for `fitClothoidG1`.

## Measuring curvature on float polylines (trap)
Three-point circles through neighbouring samples on a 200-per-segment float polyline give ~5%
curvature noise on a radius-100 circle. Tests and `curvatureComb`/`curvaturePlot` take circles
through samples several steps apart. Continuity at knots is tested by extrapolating each side
linearly to the knot (curvature is linear within a piece), threshold 1% of peak.

Linear curvature per piece means at most one inflection per segment by construction.
