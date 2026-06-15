# FLineGeometry - Comprehensive Computational Geometry Utilities

FLineGeometry provides comprehensive extension functions for FLine objects for computational geometry operations in Field4. It provides a rich set of 2D and 3D transformations, projections, intersections, decompositions, curvature analyses, and many other geometric algorithms.

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
- [Single-Contour Interpolation](#single-contour-interpolation)
- [Advanced Curve Fitting](#advanced-curve-fitting)
- [Pattern Generation](#pattern-generation)
- [Arcs and Higher-Order Curves](#arcs-and-higher-order-curves)
- [Noise Utilities](#noise-utilities)
- [Curve Combination](#curve-combination)
- [Parameterization System](#parameterization-system)

## 2D Transformations

### Rotation

Rotate an FLine around a point in 2D (preserves Z coordinate):

```kotlin
val rotated = myLine.byRotating2D( angle = PI/4, center = vec(100f, 100f))
```

Parameters:
- `angle`: rotation angle in radians
- `center`: center of rotation (defaults to origin)

### Scaling

Scale uniformly from a center point:

```kotlin
val scaled = myLine.byScaling2D( scale = 2.0f, center = vec(100f, 100f))
```

Scale with different X and Y factors:

```kotlin
val stretched = myLine.byScaling2D( scaleX = 2.0f, scaleY = 0.5f, center = vec(100f, 100f))
```

### Reflection

Reflect across a line defined by two points:

```kotlin
val reflected = myLine.byReflecting2D( 
    linePoint1 = vec(0f, 0f), 
    linePoint2 = vec(100f, 100f))
```

### Shear

Apply shear transformation:

```kotlin
val sheared = myLine.byShearing2D( shearX = 0.5f, shearY = 0.2f)
```

## 3D Transformations

### 3D Rotation

Rotate around an arbitrary 3D axis:

```kotlin
val rotated = myLine.byRotating3D( 
    angle = PI/4, 
    axis = vec(1f, 1f, 0f),
    center = vec(100f, 100f, 0f))
```

### Perspective Projection

Project onto a plane with perspective:

```kotlin
val projected = myLine.byPerspectiveProjecting(
    cameraPos = vec(0f, 0f, 100f),
    planePoint = vec(0f, 0f, 0f),
    planeNormal = vec(0f, 0f, 1f))
```

### Orthographic Projection

Project onto a plane orthographically:

```kotlin
val projected = myLine.byOrthographicProjecting(
    planePoint = vec(0f, 0f, 0f),
    planeNormal = vec(0f, 0f, 1f))
```

## Curve Offsetting

### Basic Offset

Create an offset curve at a specified distance:

```kotlin
// Positive = right/outward, negative = left/inward
val offset = myLine.byOffsetting( distance = 10f, samples = 50)
```

### Parallel Curves

Create parallel curves on both sides:

```kotlin
val (leftOffset, rightOffset) = myLine.byCreatingParallelCurves( distance = 10f, samples = 50)
```

## Curve Smoothing

### Gaussian Smoothing

Smooth a curve using Gaussian kernel:

```kotlin
val smooth = myLine.byGaussianSmoothing( sigma = 2.0f, samples = 100)
```

Parameters:
- `sigma`: smoothing strength (larger = smoother)
- `samples`: number of samples to use

### Simplification

Simplify using Ramer-Douglas-Peucker algorithm:

```kotlin
val simplified = myLine.bySimplifying( epsilon = 5.0f)
```

Parameters:
- `epsilon`: maximum distance threshold for point elimination

## Intersections

### Self-Intersections

Find all self-intersections in a curve:

```kotlin
val intersections: List<Vec3> = myLine.findSelfIntersections( tolerance = 1f)
```

### Curve-Curve Intersections

Find intersections between two curves:

```kotlin
val intersections: List<Vec3> = line1.findIntersectionsWith(line2, tolerance = 1f)
```

## Curvature Analysis

### Curvature Analysis

Calculate curvature at regular intervals:

```kotlin
val analysis: List<Pair<Vec3, Float>> = myLine.curvatureAnalysis( samples = 100)
// Each pair contains (position, curvature)
```

### Curvature-Adaptive Sampling

Sample with density proportional to curvature:

```kotlin
val adaptive = myLine.byCurvatureAdaptiveSampling( 
    minSamples = 20, 
    maxSamples = 200)
```

### High Curvature Points

Find points of maximum curvature:

```kotlin
val highCurvaturePoints: List<Vec3> = myLine.findHighCurvaturePoints( 
    threshold = 0.1f, 
    samples = 100)
```

## Decomposition

### Convex Hull

Compute the 2D convex hull:

```kotlin
val hull = myLine.byConvexHull2D()
```

### Split at Inflections

Split curve at points of inflection (where curvature changes sign):

```kotlin
val pieces: List<FLine> = myLine.bySplittingAtInflections()
```

## Tangent and Normal Operations

### Tangent Lines

Generate tangent lines at regular intervals:

```kotlin
val tangents: List<FLine> = myLine.byCreatingTangentLines( 
    samples = 20, 
    length = 10f)
```

### Normal Lines

Generate normal lines at regular intervals:

```kotlin
val normals: List<FLine> = myLine.byCreatingNormalLines( 
    samples = 20, 
    length = 10f)
```

### Osculating Circles

Generate osculating circles (circles with same tangent and curvature):

```kotlin
val circles: List<FLine> = myLine.byCreatingOsculatingCircles( 
    samples = 20, 
    circleSegments = 32)
```

## Boolean Operations

These operations use Java2D Area for robust boolean operations:

### Union

```kotlin
val combined = line1.byUnionWith(line2)
```

### Difference

```kotlin
val difference = line1.byDifferenceWith(line2)  // line1 - line2
```

### Intersection

```kotlin
val intersection = line1.byIntersectionWith(line2)
```

### Exclusive Or

```kotlin
val xor = line1.byExclusiveOrWith(line2)
```

## Geometric Queries

### Closest Point

Find the closest point on a curve to a given point:

```kotlin
val (closestPoint, t) = myLine.closestPoint( point = vec(50f, 50f, 0f))
```

### Winding Number

Calculate winding number (for point-in-polygon test):

```kotlin
val winding = myLine.windingNumber( point = vec(50f, 50f))
// winding > 0 means point is inside (if curve is closed)
```

### Contains

Test if a point is inside a closed curve:

```kotlin
val inside = myLine.contains( point = vec(50f, 50f))
```

### Signed Area

Calculate the signed area of a 2D closed curve:

```kotlin
val area = myLine.signedArea2D()
// Positive = counter-clockwise, negative = clockwise
```

### Centroid

Calculate the centroid of a 2D closed curve:

```kotlin
val center: Vec2 = myLine.centroid2D()
```

## Path Operations

### Reverse

Reverse the direction of a curve:

```kotlin
val reversed = myLine.byReversing()
```

### Concatenate

Concatenate multiple curves into one:

```kotlin
val combined = concatenate(line1, line2, line3)
```

### Blend

Blend between two curves:

```kotlin
val blended = line1.byBlendingWith(line2, t = 0.5f)
// t=0 gives line1, t=1 gives line2, t=0.5 gives halfway blend
```

### Extract Sub-Curve

Extract a sub-curve between two t parameters:

```kotlin
val subCurve = myLine.byExtracting( tStart = 1.5f, tEnd = 3.2f)
```

## Single-Contour Interpolation

For repeated morphing between two single-contour `FLine`s, use `FLineInterpolator` from
`src/jvmMain/kotlin/afield/graphics/FLineInterpolation.kt`.

```kotlin
val interpolator = lineA.interpolatorTo(
    lineB,
    FLineInterpolationConfig(
        alignment = FLineInterpolationAlignment(
            rotationMode = FLineInterpolationRotationMode.FIT_LINE_2D
        ),
        matching = FLineInterpolationMatching(
            denseSampleCount = 256,
            allowClosedOrderRotation = true,
            allowReverseOrder = true
        ),
        motion = FLineInterpolationMotion(
            spatialSpinAngle = PI.toFloat(),
            spatialSpinMode = FLineInterpolationSpinMode.MIDPOINT_RETURN,
            orderSpin = 0.5f
        )
    )
)

val halfway = interpolator.lineAt(0.5f)
```

Key ideas:
- Setup is intentionally heavier than evaluation. It densifies each line, aligns them for matching, chooses reversal and seam shift with a Hungarian assignment, then caches a common basis for fast repeated evaluation.
- Node-to-segment projection is part of setup, so if one line has a node near the middle of a segment on the other line the target contour is subdivided there before the final basis is built.
- `spatialSpinMode = FLineInterpolationSpinMode.MIDPOINT_RETURN` gives a flourish in the middle of the morph while still hitting both keyframes exactly. The same idea is available for `orderSpin` via `orderSpinMode`.
- `orderSpin` rotates the point order through the morph and is most useful for closed contours; `spatialSpinAngle` adds ordinary geometric rotation on top of the blended result.
- This primitive currently expects one contour only: zero or one `MoveTo`, with multi-contour composition left for a later layer.

For one-off evaluation there is also:

```kotlin
val once = lineA.byInterpolatingTo(lineB, t = 0.35f)
```

## Advanced Curve Fitting

### Circle Fitting

Fit a circle to points on the curve:

```kotlin
val (center, radius, error) = myLine.fitCircle( samples = 20)
```

Returns:
- `center`: center of fitted circle
- `radius`: radius of fitted circle
- `error`: average fitting error

### Line Fitting in 2D

Fit a line to points using least squares in 2D:

```kotlin
val (startPoint, endPoint, error) = myLine.fitLine2D( samples = 20)
```

Returns:
- `startPoint`: start point of the best-fit line
- `endPoint`: end point of the best-fit line
- `error`: RMS error (perpendicular distance to line)

Uses principal component analysis (PCA) to find the best-fit line through sampled points.

### Line Fitting in 3D

Fit a line to points in 3D space:

```kotlin
val (pointOnLine, direction, error) = myLine.fitLine3D( samples = 20)
```

Returns:
- `pointOnLine`: a point on the line (centroid)
- `direction`: normalized direction vector of the line
- `error`: RMS error (perpendicular distance to line)

Uses power iteration to find the principal eigenvector of the covariance matrix.

### Polynomial Fitting

Fit a polynomial of arbitrary degree to the curve:

```kotlin
val (coefficients, error) = myLine.fitPolynomial( 
    degree = 2,  // 1=linear, 2=quadratic, 3=cubic, etc.
    samples = 50
)
```

Returns:
- `coefficients`: array [a0, a1, ..., an] where y = a0 + a1*x + a2*x^2 + ... + an*x^n
- `error`: RMS fitting error

Example with quadratic (parabola):
```kotlin
val (coeffs, error) = myLine.fitPolynomial(degree = 2, samples = 30)
// coeffs[0] = constant term, coeffs[1] = linear term, coeffs[2] = quadratic term

// Create smooth curve from fitted polynomial
val fitted = FLine()
val xMin = points.minOf { it.x }
val xMax = points.maxOf { it.x }
fitted.moveTo(xMin, coeffs[0] + coeffs[1] * xMin + coeffs[2] * xMin * xMin)
for (i in 1..100) {
    val x = xMin + (xMax - xMin) * i / 100f
    val y = coeffs[0] + coeffs[1] * x + coeffs[2] * x * x
    fitted.lineTo(x, y)
}
```

### Ellipse Fitting

Fit an ellipse to points using moment-based approximation:

```kotlin
val ellipseFit = myLine.fitEllipse( samples = 30)
```

Returns an `EllipseFit` data class with:
- `center`: center point of the ellipse
- `semiMajorAxis`: length of semi-major axis
- `semiMinorAxis`: length of semi-minor axis  
- `rotationAngle`: rotation angle in radians
- `error`: average fitting error

Example:
```kotlin
val fit = myLine.fitEllipse(samples = 40)
println("Ellipse center: ${fit.center}")
println("Semi-major axis: ${fit.semiMajorAxis}")
println("Semi-minor axis: ${fit.semiMinorAxis}")
println("Rotation: ${fit.rotationAngle} radians")
println("Fitting error: ${fit.error}")
```

### Cubic Bezier Fitting

Fit a cubic Bezier curve to sampled points:

```kotlin
val (controlPoints, error) = myLine.fitBezierCubic( samples = 20)
```

Returns:
- `controlPoints`: array of 4 Vec3 points [P0, P1, P2, P3] where P0 and P3 are endpoints
- `error`: RMS fitting error

The endpoints (P0, P3) are fixed to the first and last sampled points, and the two control points (P1, P2) are optimized using least squares.

Example:
```kotlin
val (bezier, error) = myLine.fitBezierCubic(samples = 15)
val fitted = FLine()
fitted.moveTo(bezier[0])
fitted.cubicTo(bezier[1], bezier[2], bezier[3])
```

### Piecewise Linear Fitting

Fit multiple line segments adaptively based on curvature:

```kotlin
val segments = myLine.fitPiecewiseLinear(
    maxSegments = 10,
    curvatureThreshold = 0.05f,
    samples = 100
)
```

Returns a list of FLine objects, each representing a line segment. High curvature areas automatically get more segments.

Parameters:
- `maxSegments`: maximum number of segments to create
- `curvatureThreshold`: minimum curvature to force a segment break
- `samples`: number of points to sample for analysis

### Points to FLine Conversion

Create an FLine from a list of points with automatic curve fitting:

```kotlin
val points = listOf(
    vec(0f, 0f, 0f),
    vec(20f, 30f, 0f),
    vec(40f, 35f, 0f),
    vec(60f, 25f, 0f)
)

// Various fitting strategies:
val linear = pointsToFLine(points, "linear")       // Simple linear interpolation
val quad = pointsToFLine(points, "quadratic")      // Quadratic polynomial fit
val cubic = pointsToFLine(points, "cubic")         // Cubic polynomial fit
val bezier = pointsToFLine(points, "bezier")       // Cubic Bezier segments
val adaptive = pointsToFLine(points, "adaptive")   // Adaptive (default, recommended)
```

The `"adaptive"` strategy automatically chooses between Bezier curves and straight lines based on point spacing and local curvature, producing the most natural-looking results.

Also works with 2D points:
```kotlin
val points2D = listOf(vec(0f, 0f), vec(10f, 5f), vec(20f, 8f))
val line = pointsToFLine(points2D, "adaptive")  // z coordinate set to 0
```

### Subdivision

Subdivide using De Casteljau's algorithm:

```kotlin
val subdivided = myLine.bySubdividing( levels = 2)
```

### Arc-Length Parameterization

Resample with equal spacing along length:

```kotlin
val resampled = myLine.byArcLengthParameterizing( spacing = 5.0f)
```

## Pattern Generation

### Dashing

Create a dashed version of the curve:

```kotlin
val dashes: List<FLine> = myLine.byDashing( 
    dashLength = 10f, 
    gapLength = 5f)
```

### Wave Pattern

Create a wave pattern along the curve:

```kotlin
val wave = myLine.byWaving( 
    amplitude = 5f, 
    frequency = 2f, 
    samples = 100)
```

### Zigzag Pattern

Create a zigzag pattern:

```kotlin
val zigzag = myLine.byZigzagging( 
    amplitude = 5f, 
    segments = 20)
```

## Arcs and Higher-Order Curves

### Circular Arc in 3D

Create a circular arc in any 3D plane:

```kotlin
val line = FLine()
line.byAppendingArc3D(
    line,
    center = vec(0f, 0f, 0f),
    radius = 50f,
    startAngle = 0f,
    endAngle = PI.toFloat(),
    normal = vec(0f, 0f, 1f),  // XY plane
    segments = 32
)
```

### Arc Through Three Points

Create an arc that passes through three 3D points:

```kotlin
val arc = arcThrough3Points(
    p1 = vec(0f, 0f, 0f),
    p2 = vec(50f, 50f, 0f),
    p3 = vec(100f, 0f, 0f),
    segments = 32
)
```

### Quadratic Bezier Curve

Append a quadratic Bezier curve (converted to cubic internally):

```kotlin
val line = FLine()
line.moveTo(0f, 0f)
line.byAppendingQuadratic(
    line,
    controlPoint = vec(50f, 100f, 0f),
    endPoint = vec(100f, 0f, 0f)
)
```

### Arc by Tangent

Create an arc constrained by tangent directions:

```kotlin
val line = FLine()
line.moveTo(0f, 0f)
line.byAppendingArcByTangent(
    line,
    endPoint = vec(100f, 0f, 0f),
    startTangent = vec(1f, 1f, 0f),
    endTangent = vec(1f, -1f, 0f),
    segments = 32
)
```

## Noise Utilities

### Gaussian Noise

Apply standard Gaussian (random) noise:

```kotlin
val noisy = byGaussianNoising(
    myLine,
    amplitude = 5f,
    seed = 12345L,
    samples = 100
)
```

### Oriented Gaussian Noise

Apply direction-sensitive noise primarily along curve normals:

```kotlin
val noisy = byOrientedGaussianNoising(
    myLine,
    normalAmplitude = 10f,    // Noise perpendicular to curve
    tangentAmplitude = 2f,    // Noise along curve direction
    seed = 12345L,
    samples = 100
)
```

### Perlin Noise

Apply smooth, coherent Perlin noise:

```kotlin
val noisy = byPerlinNoising(
    myLine,
    amplitude = 10f,
    frequency = 1f,           // Higher = more variation
    octaves = 4,              // More = more detail
    seed = 12345L,
    samples = 100
)
```

## Curve Combination

### Addition

Add two curves point-wise:

```kotlin
val sum = curve1.byAddingWith(curve2, samples = 100)
```

### Subtraction

Subtract one curve from another:

```kotlin
val difference = curve1.bySubtractingWith(curve2, samples = 100)
```

### Multiplication

Multiply curve coordinates component-wise:

```kotlin
val product = curve1.byMultiplyingWith(curve2, samples = 100)
```

### Projection

Project one curve onto another (find closest points):

```kotlin
val projected = curve1.byProjectingOnto(curve2, samples = 100)
```

### Geometric Inversion

Invert a curve through a circle (conformal mapping):

```kotlin
val inverted = byInverting(
    myLine,
    center = vec(50f, 50f),
    radius = 100f,
    samples = 100
)
```

## Parameterization System

The parameterization system allows any numeric parameter to vary along a curve according to different rules.

### Parameter Types

#### Constant Parameter

```kotlin
val constant = ConstantParameter(value = 10f)
```

#### Linear Parameter

```kotlin
val linear = LinearParameter(start = 5f, end = 20f)
```

#### Curve-Driven Parameter

Use another curve's Y coordinate to drive a parameter:

```kotlin
val controlCurve = fline {
    moveTo(0f, 5f)
    lineTo(50f, 20f)
    lineTo(100f, 5f)
}
val curveParam = CurveParameter(
    curve = controlCurve,
    scale = 1f,
    offset = 0f
)
```

#### Distance-Based Parameter

Vary parameter based on distance from a point:

```kotlin
val distParam = DistanceParameter(
    point = vec(50f, 50f, 0f),
    minValue = 5f,
    maxValue = 20f,
    maxDistance = 100f
)
```

#### Curvature-Based Parameter

Vary parameter based on local curvature:

```kotlin
val curvParam = CurvatureParameter(
    line = myLine,
    minValue = 2f,
    maxValue = 10f,
    maxCurvature = 0.5f
)
```

#### Sine Wave Parameter

```kotlin
val sineParam = SineParameter(
    amplitude = 5f,
    frequency = 2f,
    phase = 0f,
    offset = 10f
)
```

#### Lambda Parameter

Create custom parameter function:

```kotlin
val customParam = parameterFromLambda { t, pos ->
    // Custom logic based on t (0 to 1) and position
    10f + 5f * sin(t * PI.toFloat()) * (1f + pos.x / 100f)
}
```

### Applying Parameterized Operations

#### Parameterized Offset

Offset distance varies along the curve:

```kotlin
val offset = byOffsettingParameterized(
    myLine,
    distanceFunc = SineParameter(
        amplitude = 10f,
        frequency = 2f
    ),
    samples = 100
)
```

#### Parameterized Noise

Noise amplitude varies along the curve:

```kotlin
val noisy = byNoisingParameterized(
    myLine,
    amplitudeFunc = LinearParameter(
        start = 1f,
        end = 10f
    ),
    seed = 12345L,
    samples = 100
)
```

#### Parameterized Scaling

Scale factor varies along the curve:

```kotlin
val scaled = byScalingParameterized(
    myLine,
    scaleFunc = CurvatureParameter(
        line = myLine,
        minValue = 0.5f,
        maxValue = 2f
    ),
    center = vec(50f, 50f),
    samples = 100
)
```

### Combined Example

Combine multiple parameterized operations:

```kotlin
// Create a wavy line
val base = fline {
    moveTo(0f, 50f)
    for (i in 1..100) {
        lineTo(i.toFloat(), 50f + sin(i / 10f) * 20f)
    }
}

// Apply offset that increases with curvature
val offset = byOffsettingParameterized(
    base,
    CurvatureParameter(
        line = base,
        minValue = 2f,
        maxValue = 15f
    )
)

// Add noise that decreases along the curve
val final = byNoisingParameterized(
    offset,
    LinearParameter(start = 10f, end = 0f)
)
```

## Example Usage

Here's a complete example showing several operations:

```kotlin
// Create a simple square
val square = fline {
    moveTo(0f, 0f)
    lineTo(100f, 0f)
    lineTo(100f, 100f)
    lineTo(0f, 100f)
    lineTo(0f, 0f)
}

// Transform it
val rotated = square.byRotating2D( PI.toFloat() / 4, center = vec(50f, 50f))
val scaled = rotated.byScaling2D( 1.5f, center = vec(50f, 50f))

// Create an offset version
val offset = scaled.byOffsetting( distance = 10f)

// Get curvature information
val curvatures = scaled.curvatureAnalysis( samples = 50)

// Find centroid
val center = scaled.centroid2D()

// Create tangent lines
val tangents = scaled.byCreatingTangentLines( samples = 10, length = 20f)

// Create a smoothed version
val smoothed = scaled.byGaussianSmoothing( sigma = 2.0f, samples = 100)
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
