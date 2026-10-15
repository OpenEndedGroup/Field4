# FLine against Paper.js, Skia and p5: the 2026-10-12 audit

What `afield.geometry.path.FLine` offers for the operations a 2D path library is judged on, where it
sat before this pass, and what is still missing. New API lives in
`src/jvmMain/kotlin/afield/geometry/path/FLineMeasure.kt`; its tests in
`src/jvmTest/kotlin/afield/geometry/path/FLineMeasureTest.kt`; the user-facing sections are
"Measuring along a line", "Trimming and splitting by distance" and "Crossings, bounds and
orientation" in `public/docs/fline_examples.md`; the demo is
`dev_docs/agents/transcripts/fline_measure.yaml` (registered `EXECUTE`).

## One measurer

There is one curve measurer, `FLine.cursor()` (`FLinesAndJavaShapes.Cursor`), and one segment
evaluator under it, `FLineSeg` in `FLineMeasure.kt` (exact cubic first and second derivatives,
`LineTo` / `Close` degree-elevated, degenerate ends evaluated just inside). The cursor's
`position()`, `tangent()`, `normal()`, `binormal()`, `curvature()`, `normal2D()`,
`signedCurvature2D()`, `radiusOfCurvature()` and `location()` all read that evaluator; the older
`evaluateDCubicFrame` route inside the cursor is gone. The FLine-level `length`, `locationAt`,
`locationAtFraction`, `nearestLocation`, `trim`, `splitAt` are one-line front doors onto a cursor
for one-shot questions — Paper.js's shape — and the cursor is for walking. Nearest point has one
implementation (`Cursor.setNearest`, which `cursor(closestTo)` and `nearestLocation` use).

`FLineLocation` frame choices:
- `tangent` is unit length; at a degenerate cubic end it is the limiting direction.
- `normal` / `binormal` are the 3D Frenet frame and are `null` on straight runs (no direction is
  better than an arbitrary one in 3D); `curvature` is unsigned and 0 there.
- `normal2D = (tangent.y, -tangent.x)` normalised (left of travel on Field's y-down canvas — the
  convention the offsetting / waving / noising helpers already used through `normal2()`), defined on
  straight runs; `signedCurvature2D` is positive bending towards it.
- At a node the frame is the outgoing segment's; at the end of the line the incoming one's; inside
  a `MoveTo` jump the cursor snaps to the nearer drawn segment.

Naming: everything that only exists in the xy plane carries `2D` — `normal2D`, `signedCurvature2D`,
`tightBounds2D`, `area2D`, `isClockwise2D`, `windingNumber2D`, `contains2D`, `crossings2D`,
`selfCrossings2D`, plus the older `centroid2D`, `fast2DBounds`. `inflectionPointsXY` already names
its plane and was left.

Removed outright (no deprecation; every caller in `src/`, docs fences, transcripts and their
generated assets migrated): `FLine.closestPoint`, `FLinesAndJavaShapes.closestT`,
`findSelfIntersections`, `findIntersectionsWith`, `contains(Vec2)`, `windingNumber`,
`signedArea2D` (now the private `chordArea2D` that `centroid2D`'s chord moments pair with),
`Cursor.tangentForward/Backward`, `accelerationForward/Backward`, `acceleration`,
`binormalForward/Backward`, `normalForward/Backward`, `normal2`, `currentSegment`. Still present
and arguably duplicate: `CubicSegment3.closestToPoint` (a per-segment helper, not a line measurer).

## Cursor defects found by characterization (pinned in `CursorCharacterizationTest`)

- `tangent()` was `B'(u)`, not a unit vector (magnitude ~150 on a 100-unit arc); `normal2()` the same,
  so `byOffsetting(distance)`, `byWaving` and `byZigzagging` offset by `distance * |B'|`.
- `normal()` was `t × (t × a)`, which points *away* from the centre, so `radiusOfCurvature()` (and
  `byCreatingOsculatingCircles`) put the centre on the outside; it also only worked on cubics and
  `requireNotNull`-threw where `normal()` was null.
- In a `MoveTo` gap `tangent()` was null although `position()` snapped to the next segment.
- At a degenerate cubic end (control point on the end point) `tangent()` was the zero vector.
- `split()` exactly on a node appended a zero-length segment to the left piece.
- `split()` inside a closed subpath copied its `Close` into the right piece, which then closed back
  to the cut point instead of the subpath's start.
- `splitD` built its follow-on cursors at tolerance 0.1 regardless of the cursor's own.
- `byCreatingOsculatingCircles` printed every point to stdout.

## Gap table

| Operation (Paper.js / Skia name) | Before | Now |
| --- | --- | --- |
| length (`path.length`, `SkPathMeasure.getLength`) | `cursor().lengthD()`, and `arcLength()` (Double) in `stdlib/typography.kt` | `length()` |
| point + tangent + normal + curvature at length (`getLocationAt`, `getPosTan`) | `cursor().setD(d)` then `position()` / `tangent()` / `normal()`; tangent unnormalised, no curvature | `locationAt(d)`, `locationAtFraction(f)` -> `FLineLocation` |
| nearest point (`getNearestLocation`) | `closestPoint(p)` -> (point, t) | `nearestLocation(p)` / `cursor(p)`; `closestPoint` removed |
| split / trim by length (`splitAt`, `SkPathMeasure.getSegment`) | `cursor().setD(d).split()`, `splitD(list)`, `byExtracting` | `splitAt(d)`, `trim(from, to)` |
| dashing (`SkDashPathEffect`, `dashArray`) | `byDashing(dash, gap)` (polyline output) | unchanged |
| uniform resample | `sampleByDistance`, `byArcLengthParameterizing`, `FLineSampler.byDistance` | unchanged |
| simplify / fit (`simplify`, `smooth`) | `bySimplifying`, `fitBezierCubic`, `byBSplineSmoothed`, hyperbezier fitting | unchanged |
| reverse / orientation (`reverse`, `isClockwise`) | `byReversing`, `signedArea2D` (chords only) | `isClockwise2D()`, `area2D()` exact on cubics, per subpath |
| tight bounds (`bounds`, `SkPath::computeTightBounds`) | `fast2DBounds` (control points) | `tightBounds2D()` |
| contains with fill rule (`contains`, `SkPathFillType`) | `contains(p)`: non-zero on chords | `contains2D(p, rule = NonZero)`, `windingNumber2D(p)`, following curves |
| intersections with params (`getIntersections`, `getCrossings`) | `findIntersectionsWith`, `findSelfIntersections`: points only | `crossings2D(other)`, `selfCrossings2D()` with `t` on both lines (the point-only wrappers are removed) |
| booleans (`unite`, `SkPathOps`) | `byUnionWith` etc. through `java.awt.geom.Area` | Area keeps cubics (it does not flatten, measured 2026-10-13) but splits at y-extrema; `booleanExact` / `exact = true` keep original curve pieces, ~30% fewer segments (`fline_exact_booleans.md`) |
| offset (`PathKit` stroke, Paper `offset`) | `byOffsetting`: 50 samples per node, polyline, no joins | `byOffsettingExact`: cubic, within tolerance, round/miter/bevel joins, resolved outlines |

## Defects fixed (each pinned in `FLineMeasureTest`)

- `FLinesAndJavaShapes.intersectTwoLineSegments` bounded the hit on the first segment only: two
  segments whose *lines* crossed reported a point even when the second stopped short.
- `FLinesAndJavaShapes.intersectY` added the offset back to `x` (every cubic hit landed at y≈0) and
  range-checked line segments in `x` instead of `y`.
- `CubicSegment2/3.intersection` stopped subdividing on `max(r.w, r.y)`, a coordinate, not the height.
- `findSelfIntersections` ignored straight segments (a polyline bowtie had no crossing) and both it
  and `findIntersectionsWith` returned clusters of near-duplicate points and ignored `Close`
  segments; `findIntersectionsWith` ignored its `tolerance`. Both have since been removed in favour of `selfCrossings2D` / `crossings2D`.
- `FLine.pieces()` dropped a leading run that had no `MoveTo`.

## Notes for the next pass

- In box code `listA + listB` of `List<Vec3>` resolves to Field's element-wise vector `plus` and
  throws "List sizes must match"; concatenate with `listOf(a, b).flatten()`.
- `isClockwise2D()` is on-screen (y down), so a circle built counter-clockwise in y-up maths is
  clockwise; `area2D()` keeps the y-up sign convention.
- Exact booleans and cubic offsets: done 2026-10-13, see `fline_exact_booleans.md`.
- Not done: a
  `getOffsetOf`-style inverse (point -> distance) for points not on the line, and arc segments as a
  node type. `selfCrossings2D` misses a crossing that falls exactly on a node join.
