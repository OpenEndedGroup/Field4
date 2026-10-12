# FLine against Paper.js, Skia and p5: the 2026-10-12 audit

What `afield.graphics.FLine` offers for the operations a 2D path library is judged on, where it
sat before this pass, and what is still missing. New API lives in
`src/jvmMain/kotlin/afield/graphics/FLineMeasure.kt`; its tests in
`src/jvmTest/kotlin/afield/graphics/FLineMeasureTest.kt`; the user-facing sections are
"Measuring along a line", "Trimming and splitting by distance" and "Crossings, bounds and
orientation" in `public/docs/fline_examples.md`; the demo is
`dev_docs/agents/transcripts/fline_measure.yaml` (registered `EXECUTE`).

## Gap table

| Operation (Paper.js / Skia name) | Before | Now |
| --- | --- | --- |
| length (`path.length`, `SkPathMeasure.getLength`) | `cursor().lengthD()`, and `arcLength()` (Double) in `stdlib/typography.kt` | `length()` |
| point + tangent + normal + curvature at length (`getLocationAt`, `getPosTan`) | `cursor().setD(d)` then `position()` / `tangent()` / `normal()`; tangent unnormalised, no curvature | `locationAt(d)`, `locationAtFraction(f)` -> `FLineLocation` |
| nearest point (`getNearestLocation`) | `closestPoint(p)` -> (point, t) | `nearestLocation(p)` adds distance, frame, curvature |
| split / trim by length (`splitAt`, `SkPathMeasure.getSegment`) | `cursor().setD(d).split()`, `splitD(list)`, `byExtracting` | `splitAt(d)`, `trim(from, to)` |
| dashing (`SkDashPathEffect`, `dashArray`) | `byDashing(dash, gap)` (polyline output) | unchanged |
| uniform resample | `sampleByDistance`, `byArcLengthParameterizing`, `FLineSampler.byDistance` | unchanged |
| simplify / fit (`simplify`, `smooth`) | `bySimplifying`, `fitBezierCubic`, `byBSplineSmoothed`, hyperbezier fitting | unchanged |
| reverse / orientation (`reverse`, `isClockwise`) | `byReversing`, `signedArea2D` (chords only) | `isClockwise()`, `area2D()` exact on cubics, per subpath |
| tight bounds (`bounds`, `SkPath::computeTightBounds`) | `fast2DBounds` (control points) | `tightBounds2D()` |
| contains with fill rule (`contains`, `SkPathFillType`) | `contains(p)`: non-zero on chords | `contains(p, FLineFillRule.NonZero / EvenOdd)` following curves |
| intersections with params (`getIntersections`, `getCrossings`) | `findIntersectionsWith`, `findSelfIntersections`: points only | `crossings(other)`, `selfCrossings()` with `t` on both lines |
| booleans (`unite`, `SkPathOps`) | `byUnionWith` etc. through `java.awt.geom.Area` | unchanged (flattens; output is polyline) |

## Defects fixed (each pinned in `FLineMeasureTest`)

- `FLinesAndJavaShapes.intersectTwoLineSegments` bounded the hit on the first segment only: two
  segments whose *lines* crossed reported a point even when the second stopped short.
- `FLinesAndJavaShapes.intersectY` added the offset back to `x` (every cubic hit landed at y≈0) and
  range-checked line segments in `x` instead of `y`.
- `CubicSegment2/3.intersection` stopped subdividing on `max(r.w, r.y)`, a coordinate, not the height.
- `findSelfIntersections` ignored straight segments (a polyline bowtie had no crossing) and both it
  and `findIntersectionsWith` returned clusters of near-duplicate points and ignored `Close`
  segments; `findIntersectionsWith` ignored its `tolerance`. Both now wrap the new crossing code.
- `FLine.pieces()` dropped a leading run that had no `MoveTo`.

## Notes for the next pass

- In box code `listA + listB` of `List<Vec3>` resolves to Field's element-wise vector `plus` and
  throws "List sizes must match"; concatenate with `listOf(a, b).flatten()`.
- `isClockwise()` is on-screen (y down), so a circle built counter-clockwise in y-up maths is
  clockwise; `area2D()` keeps the y-up sign convention of the older `signedArea2D()`.
- Not done: exact (non-flattening) boolean ops, offsetting with exact cubic output, a
  `getOffsetOf`-style inverse (point -> distance) for points not on the line, and arc segments as a
  node type. `selfCrossings` misses a crossing that falls exactly on a node join.
