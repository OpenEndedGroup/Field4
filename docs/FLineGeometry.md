# FLineGeometry - Comprehensive Computational Geometry Utilities

`FLineGeometry` is a comprehensive utility object for performing computational geometry operations on `FLine` objects in Field4. It provides a rich set of 2D and 3D transformations, projections, intersections, decompositions, curvature analyses, and many other geometric algorithms.

## Table of Contents

- [2D Transformations](#2d-transformations)
- [3D Transformations](#3d-transformations)
- [Curve Offsetting](#curve-offsetting)
- [Curve Smoothing](#curve-smoothing)
- [Intersections](#intersections)
- [Curvature Analysis](#curvature-analysis)
- [Decomposition](#decomposition)
- [Tangent and Normal Operations](#tangent-and-normal-operations)
- [Boolean Operations](#boolean-operations)
- [Geometric Queries](#geometric-queries)
- [Path Operations](#path-operations)
- [Advanced Curve Fitting](#advanced-curve-fitting)
- [Pattern Generation](#pattern-generation)

## 2D Transformations

### Rotation

Rotate an FLine around a point in 2D (preserves Z coordinate):

```kotlin
val rotated = FLineGeometry.rotate2D(myLine, angle = PI/4, center = vec(100f, 100f))
```

Parameters:
- `angle`: rotation angle in radians
- `center`: center of rotation (defaults to origin)

### Scaling

Scale uniformly from a center point:

```kotlin
val scaled = FLineGeometry.scale2D(myLine, scale = 2.0f, center = vec(100f, 100f))
```

Scale with different X and Y factors:

```kotlin
val stretched = FLineGeometry.scale2D(myLine, scaleX = 2.0f, scaleY = 0.5f, center = vec(100f, 100f))
```

### Reflection

Reflect across a line defined by two points:

```kotlin
val reflected = FLineGeometry.reflect2D(myLine, 
    linePoint1 = vec(0f, 0f), 
    linePoint2 = vec(100f, 100f))
```

### Shear

Apply shear transformation:

```kotlin
val sheared = FLineGeometry.shear2D(myLine, shearX = 0.5f, shearY = 0.2f)
```

## 3D Transformations

### 3D Rotation

Rotate around an arbitrary 3D axis:

```kotlin
val rotated = FLineGeometry.rotate3D(myLine, 
    angle = PI/4, 
    axis = vec(1f, 1f, 0f),
    center = vec(100f, 100f, 0f))
```

### Perspective Projection

Project onto a plane with perspective:

```kotlin
val projected = FLineGeometry.perspectiveProject(myLine,
    cameraPos = vec(0f, 0f, 100f),
    planePoint = vec(0f, 0f, 0f),
    planeNormal = vec(0f, 0f, 1f))
```

### Orthographic Projection

Project onto a plane orthographically:

```kotlin
val projected = FLineGeometry.orthographicProject(myLine,
    planePoint = vec(0f, 0f, 0f),
    planeNormal = vec(0f, 0f, 1f))
```

## Curve Offsetting

### Basic Offset

Create an offset curve at a specified distance:

```kotlin
// Positive = right/outward, negative = left/inward
val offset = FLineGeometry.offset(myLine, distance = 10f, samples = 50)
```

### Parallel Curves

Create parallel curves on both sides:

```kotlin
val (leftOffset, rightOffset) = FLineGeometry.parallelCurves(myLine, distance = 10f, samples = 50)
```

## Curve Smoothing

### Gaussian Smoothing

Smooth a curve using Gaussian kernel:

```kotlin
val smooth = FLineGeometry.gaussianSmooth(myLine, sigma = 2.0f, samples = 100)
```

Parameters:
- `sigma`: smoothing strength (larger = smoother)
- `samples`: number of samples to use

### Simplification

Simplify using Ramer-Douglas-Peucker algorithm:

```kotlin
val simplified = FLineGeometry.simplify(myLine, epsilon = 5.0f)
```

Parameters:
- `epsilon`: maximum distance threshold for point elimination

## Intersections

### Self-Intersections

Find all self-intersections in a curve:

```kotlin
val intersections: List<Vec3> = FLineGeometry.findSelfIntersections(myLine, tolerance = 1f)
```

### Curve-Curve Intersections

Find intersections between two curves:

```kotlin
val intersections: List<Vec3> = FLineGeometry.findIntersections(line1, line2, tolerance = 1f)
```

## Curvature Analysis

### Curvature Analysis

Calculate curvature at regular intervals:

```kotlin
val analysis: List<Pair<Vec3, Float>> = FLineGeometry.curvatureAnalysis(myLine, samples = 100)
// Each pair contains (position, curvature)
```

### Curvature-Adaptive Sampling

Sample with density proportional to curvature:

```kotlin
val adaptive = FLineGeometry.curvatureAdaptiveSample(myLine, 
    minSamples = 20, 
    maxSamples = 200)
```

### High Curvature Points

Find points of maximum curvature:

```kotlin
val highCurvaturePoints: List<Vec3> = FLineGeometry.findHighCurvaturePoints(myLine, 
    threshold = 0.1f, 
    samples = 100)
```

## Decomposition

### Convex Hull

Compute the 2D convex hull:

```kotlin
val hull = FLineGeometry.convexHull2D(myLine)
```

### Split at Inflections

Split curve at points of inflection (where curvature changes sign):

```kotlin
val pieces: List<FLine> = FLineGeometry.splitAtInflections(myLine)
```

## Tangent and Normal Operations

### Tangent Lines

Generate tangent lines at regular intervals:

```kotlin
val tangents: List<FLine> = FLineGeometry.tangentLines(myLine, 
    samples = 20, 
    length = 10f)
```

### Normal Lines

Generate normal lines at regular intervals:

```kotlin
val normals: List<FLine> = FLineGeometry.normalLines(myLine, 
    samples = 20, 
    length = 10f)
```

### Osculating Circles

Generate osculating circles (circles with same tangent and curvature):

```kotlin
val circles: List<FLine> = FLineGeometry.osculatingCircles(myLine, 
    samples = 20, 
    circleSegments = 32)
```

## Boolean Operations

These operations use Java2D Area for robust boolean operations:

### Union

```kotlin
val combined = FLineGeometry.union(line1, line2)
```

### Difference

```kotlin
val difference = FLineGeometry.difference(line1, line2)  // line1 - line2
```

### Intersection

```kotlin
val intersection = FLineGeometry.intersection(line1, line2)
```

### Exclusive Or

```kotlin
val xor = FLineGeometry.exclusiveOr(line1, line2)
```

## Geometric Queries

### Closest Point

Find the closest point on a curve to a given point:

```kotlin
val (closestPoint, t) = FLineGeometry.closestPoint(myLine, point = vec(50f, 50f, 0f))
```

### Winding Number

Calculate winding number (for point-in-polygon test):

```kotlin
val winding = FLineGeometry.windingNumber(myLine, point = vec(50f, 50f))
// winding > 0 means point is inside (if curve is closed)
```

### Contains

Test if a point is inside a closed curve:

```kotlin
val inside = FLineGeometry.contains(myLine, point = vec(50f, 50f))
```

### Signed Area

Calculate the signed area of a 2D closed curve:

```kotlin
val area = FLineGeometry.signedArea2D(myLine)
// Positive = counter-clockwise, negative = clockwise
```

### Centroid

Calculate the centroid of a 2D closed curve:

```kotlin
val center: Vec2 = FLineGeometry.centroid2D(myLine)
```

## Path Operations

### Reverse

Reverse the direction of a curve:

```kotlin
val reversed = FLineGeometry.reverse(myLine)
```

### Concatenate

Concatenate multiple curves into one:

```kotlin
val combined = FLineGeometry.concatenate(line1, line2, line3)
```

### Blend

Blend between two curves:

```kotlin
val blended = FLineGeometry.blend(line1, line2, t = 0.5f)
// t=0 gives line1, t=1 gives line2, t=0.5 gives halfway blend
```

### Extract Sub-Curve

Extract a sub-curve between two t parameters:

```kotlin
val subCurve = FLineGeometry.extract(myLine, tStart = 1.5f, tEnd = 3.2f)
```

## Advanced Curve Fitting

### Circle Fitting

Fit a circle to points on the curve:

```kotlin
val (center, radius, error) = FLineGeometry.fitCircle(myLine, samples = 20)
```

Returns:
- `center`: center of fitted circle
- `radius`: radius of fitted circle
- `error`: average fitting error

### Subdivision

Subdivide using De Casteljau's algorithm:

```kotlin
val subdivided = FLineGeometry.subdivide(myLine, levels = 2)
```

### Arc-Length Parameterization

Resample with equal spacing along length:

```kotlin
val resampled = FLineGeometry.arcLengthParameterize(myLine, spacing = 5.0f)
```

## Pattern Generation

### Dashing

Create a dashed version of the curve:

```kotlin
val dashes: List<FLine> = FLineGeometry.dash(myLine, 
    dashLength = 10f, 
    gapLength = 5f)
```

### Wave Pattern

Create a wave pattern along the curve:

```kotlin
val wave = FLineGeometry.wave(myLine, 
    amplitude = 5f, 
    frequency = 2f, 
    samples = 100)
```

### Zigzag Pattern

Create a zigzag pattern:

```kotlin
val zigzag = FLineGeometry.zigzag(myLine, 
    amplitude = 5f, 
    segments = 20)
```

## Example Usage

Here's a complete example showing several operations:

```kotlin
// Create a simple square
val square = FLine().apply {
    moveTo(0f, 0f)
    lineTo(100f, 0f)
    lineTo(100f, 100f)
    lineTo(0f, 100f)
    lineTo(0f, 0f)
}

// Transform it
val rotated = FLineGeometry.rotate2D(square, PI.toFloat() / 4, center = vec(50f, 50f))
val scaled = FLineGeometry.scale2D(rotated, 1.5f, center = vec(50f, 50f))

// Create an offset version
val offset = FLineGeometry.offset(scaled, distance = 10f)

// Get curvature information
val curvatures = FLineGeometry.curvatureAnalysis(scaled, samples = 50)

// Find centroid
val center = FLineGeometry.centroid2D(scaled)

// Create tangent lines
val tangents = FLineGeometry.tangentLines(scaled, samples = 10, length = 20f)

// Create a smoothed version
val smoothed = FLineGeometry.gaussianSmooth(scaled, sigma = 2.0f, samples = 100)
```

## Notes

- Most functions preserve the Z coordinate when working in 2D
- Curvature calculations work best on smooth curves with sufficient samples
- Boolean operations require closed curves for meaningful results
- Intersection calculations may be expensive for complex curves
- For real-time applications, consider caching results when possible

## Implementation Details

The utility uses:
- Existing FLine cursor infrastructure for traversal
- CubicSegment3 and CubicSegment2 for cubic Bezier segment operations
- Java2D Area for robust boolean operations
- JOML (Java OpenGL Math Library) for vector math
- Custom implementations for algorithms like convex hull, simplification, etc.
