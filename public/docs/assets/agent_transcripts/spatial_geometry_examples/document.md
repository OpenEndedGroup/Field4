# Field Document: spatial_geometry_examples

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 7 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. The two decisions inside marching squares

<!-- field-box id="spatial_marching" index="0" -->

### Narrative

From `code`

Marching squares has exactly two decisions in it, and both are places where a plausible
implementation is quietly wrong. Each input below has an answer you already know.

```edn [field-box id="spatial_marching" role="properties"]
{"id" "spatial_marching" "index" 0 "name" "The two decisions inside marching squares" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "The two decisions inside marching squares" "_tombstone" false "frame" #afield/rect [1020.0 500.0 390.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "spatial_marching_code_.kt" "lines" 72 "characters" 4223 "sha256" "4189ee20babea63ba4fc66f1911ecf62887f188885dfa2671bb861352964daae"}]}
```

```kotlin [field-property box="spatial_marching" name="code" path="spatial_marching_code_.kt"]
// Marching squares has exactly two decisions in it, and both are places where a plausible
// implementation is quietly wrong. Each input below has an answer you already know.
val plane: ScalarField2D = { x, _ -> x }
val circle: ScalarField2D = { x, y -> kotlin.math.hypot(x, y) - 1.0 }
val saddle: ScalarField2D = { x, y -> x * y }

// 1. A sample EXACTLY at iso. With a symmetric strict test such a sample is neither inside
// nor outside and the crossing on every edge out of that corner is suppressed. f(x,y) = x
// at iso 0 on an even grid used to return an empty list; the unit circle over [-2,2] at
// nx = 16 came back severed into four arcs at the points where it passes through a corner.
// The convention now is the one marchingCubes uses: inside iff strictly BELOW iso.
val planeContours = generateIsoContours(plane, -1.0, 1.0, -1.0, 1.0, 4, 4, 0.0)
val aligned = generateIsoContours(circle, -2.0, 2.0, -2.0, 2.0, 16, 16, 0.0)
println("f(x,y) = x at iso 0, nx = 4 -> ${planeContours.size} contour of ${planeContours.sumOf { it.size }} points")
println("unit circle at nx = 16      -> ${aligned.size} loop of ${aligned[0].size} points")

fun perimeter(loop: List<Vec2>): Double {
    var total = 0.0
    for (i in loop.indices) {
        val a = loop[i]; val b = loop[(i + 1) % loop.size]
        total += kotlin.math.hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
    }
    return total
}
println("   perimeter %.4f against 2pi = %.4f".format(perimeter(aligned[0]), 2 * PI))

// 2. The saddle cell. Four corners alternating inside/outside give four crossings and two
// ways to pair them, and the two choices give different topology — so an arbitrary choice
// lets neighbouring cells disagree and the contour tears. The asymptotic decider settles it
// from the cell's own four corner values, so two cells sharing an edge always agree.
//
// f = x*y is the case where it has nothing to go on: the field is ITSELF bilinear, so the
// cell's interpolant reproduces it and the saddle value is exactly iso. Both pairings are
// then equally correct. Left to floating point that tie flips with resolution, so it is
// broken deterministically towards the pre-decider pairing. Raise nx and watch the branch
// lengths: they are not equal, and with this sampling window they are not meant to be.
val branches = generateIsoContours(saddle, -1.05, 1.03, -1.02, 1.07, 8, 8, 0.0)
println()
println("f = x*y at iso 0 -> ${branches.size} branches of ${branches.map { it.size }.joinToString(" and ")} points")
println("   (a genuine tie: the saddle sits exactly on iso, so either pairing is correct)")

fun gridLines(x0: Double, x1: Double, y0: Double, y1: Double, nx: Int, ny: Int) = fline {
    for (i in 0..nx) { val x = x0 + (x1 - x0) * i / nx; moveTo(x, y0); lineTo(x, y1) }
    for (j in 0..ny) { val y = y0 + (y1 - y0) * j / ny; moveTo(x0, y); lineTo(x1, y) }
    color = vec(0.34, 0.42, 0.54, 0.55)
    thickness = 0.8
}

fun dotted(
    contours: List<List<Vec2>>, x0: Double, x1: Double, y0: Double, y1: Double,
    colors: List<Vec4>, eps: Double,
) = contours.mapIndexed { i, c ->
    polylineFrom(c, 0, closed = isoContourClosed(c, x0, x1, y0, y1, eps)).also {
        it.color = colors[i % colors.size]; it.thickness = 2.2
        it.pointed = true; it.pointSize = 1.1
    }
}

val warm = listOf(vec(1.0, 0.78, 0.36, 0.95), vec(0.62, 0.92, 1.0, 0.95),
                  vec(0.98, 0.55, 0.62, 0.95), vec(0.6, 1.0, 0.72, 0.95))

showPlaneGrid(
    "f(x,y) = x at iso 0, grid aligned on x = 0\n${planeContours.size} contour of ${planeContours.sumOf { it.size }} points (was 0)" to
        (listOf(gridLines(-1.0, 1.0, -1.0, 1.0, 4, 4)) + dotted(planeContours, -1.0, 1.0, -1.0, 1.0, warm, 0.4)),
    "unit circle at nx = 16, aligned\n1 loop of ${aligned[0].size} points (was 4 arcs)" to
        (listOf(gridLines(-2.0, 2.0, -2.0, 2.0, 16, 16)) + dotted(aligned, -2.0, 2.0, -2.0, 2.0, warm, 0.2)),
    "f = x*y at iso 0 — the saddle cell\n${branches.size} branches of ${branches.map { it.size }.joinToString(" and ")} points" to
        (listOf(gridLines(-1.05, 1.03, -1.02, 1.07, 8, 8)) + dotted(branches, -1.05, 1.03, -1.02, 1.07, warm, 0.2)),
    columns = 2,
    cellWidth = 430,
    cellHeight = 280,
)
```

## 2. A field, and the seam through it

<!-- field-box id="spatial_contours" index="1" -->

### Narrative

From `code`

A Voronoi diagram IS the set of ties in argmin |x - p_i|, so contouring the gap between
the nearest and the second nearest site recovers it from a completely different
algorithm. Except at exactly zero — see below.

```edn [field-box id="spatial_contours" role="properties"]
{"id" "spatial_contours" "index" 1 "name" "A field, and the seam through it" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "A field, and the seam through it" "_tombstone" false "frame" #afield/rect [1020.0 80.0 330.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "spatial_contours_code_.kt" "lines" 69 "characters" 3402 "sha256" "fafaf4bb504a70ca1d92aea5cb56c7f7e851b1eb7ac8e5dcd18ecb2b0e55dd0d"}]}
```

```kotlin [field-property box="spatial_contours" name="code" path="spatial_contours_code_.kt"]
// A Voronoi diagram IS the set of ties in argmin |x - p_i|, so contouring the gap between
// the nearest and the second nearest site recovers it from a completely different
// algorithm. Except at exactly zero — see below.
val sites = listOf(
    vec(-1.6, -0.9), vec(1.4, -1.2), vec(2.0, 1.1), vec(-0.2, 1.7), vec(-2.1, 0.9), vec(0.4, -0.1),
)
val gap: ScalarField2D = { x, y ->
    val d = sites.map { s -> kotlin.math.hypot(x - s.x, y - s.y) }.sorted()
    d[1] - d[0]
}

// This field is NON-NEGATIVE: it touches zero on the seams and is positive everywhere
// else. Marching squares finds SIGN CHANGES, so iso 0.0 finds nothing at all, and that is
// the right answer rather than a bug. Every min-of-distances or absolute-value field
// behaves this way.
val atZero = generateIsoContours(gap, -3.4, 3.4, -2.9, 2.9, 120, 120, 0.0)
var lo = Double.MAX_VALUE
var hi = -Double.MAX_VALUE
for (i in 0..120) for (j in 0..120) {
    val v = gap(-3.4 + i * 6.8 / 120.0, -2.9 + j * 5.8 / 120.0)
    if (v < lo) lo = v
    if (v > hi) hi = v
}
println("field range %.3e .. %.4f  (never negative)".format(lo, hi))
println("iso 0.0 exactly -> ${atZero.size} contours")

val levels = listOf(0.05, 0.35, 0.8, 1.4)
val byLevel = levels.map { iso -> iso to generateIsoContours(gap, -3.4, 3.4, -2.9, 2.9, 120, 120, iso) }
byLevel.forEach { (iso, cs) ->
    println("iso %.2f -> %2d contours, %4d points".format(iso, cs.size, cs.sumOf { it.size }))
}

// A completely different use of the same machinery: pointEdgeScalarField2D turns an FLine
// into a signed field whose zero set is a tube around it, so contouring at 0.0 outlines a
// stroke and a negative iso fattens it.
val stroke = fline {
    moveTo(-2.4, 1.2); cubicTo(-1.0, -2.2, 1.2, 2.4, 2.6, -1.0)
}
val outline = generateSmoothIsoContours(stroke, radius = 0.55, spatialStep = 0.05)
val fat = generateSmoothIsoContours(stroke, radius = 0.55, spatialStep = 0.05, iso = -0.28)
println()
println("tube outlines: ${outline.size} at radius 0.55, ${fat.size} at 0.83")

fun contourLines(contours: List<List<Vec2>>, color: Vec4, thickness: Double) =
    contours.filter { it.size >= 2 }.map { c ->
        polylineFrom(c, 0, closed = isoContourClosed(c, -3.4, 3.4, -2.9, 2.9, boundaryEpsilon = 0.05)).also {
            it.color = color; it.thickness = thickness
        }
    }

val siteDots = pointCloudFLine(sites, color = vec(1.0, 1.0, 1.0, 0.9), pointSize = 1.4)
val ramp = okhslSet(levels.size, saturation = 0.82, lightness = 0.66, startHue = 0.55f)
val stacked = byLevel.flatMapIndexed { i, (_, contours) ->
    contourLines(contours, ramp[i], 1.5 - i * 0.15)
} + siteDots

showPlaneGrid(
    "iso 0.05 — the cells, minus a hairline\n${byLevel[0].second.size} contours; at iso 0.0 exactly: ${atZero.size}" to
        (contourLines(byLevel[0].second, vec(0.62, 0.9, 1.0, 0.85), 1.6) + siteDots),
    "iso 0.05 / 0.35 / 0.8 / 1.4 together\ncells shrinking inwards with rounded corners" to stacked,
    "generateSmoothIsoContours on an FLine\ntube radius 0.55 and 0.83" to listOf(
        stroke.also { it.color = vec(1.0, 1.0, 1.0, 0.30); it.thickness = 1.0 },
        *fat.map { it.also { f -> f.color = vec(0.98, 0.55, 0.42, 0.55); f.thickness = 1.3 } }.toTypedArray(),
        *outline.map { it.also { f -> f.color = vec(1.0, 0.82, 0.42, 0.95); f.thickness = 1.6 } }.toTypedArray(),
    ),
    columns = 2,
    cellWidth = 430,
    cellHeight = 280,
)
```

## 3. Sweep the weight, and watch the bisector bow

<!-- field-box id="spatial_apollonius_sweep" index="2" -->

### Narrative

From `code`

The animated twin of "When the bisector is a circle". Three stills at three weights argue
that a straight bisector becomes a circle; sweeping the weight SHOWS it. Launch this box
(it ends in `animate`, so it runs until you stop it) and watch three things:
- only the edges that TOUCH site 1 bow. A bisector between two equally weighted sites
stays exactly straight, which is why both counts are in the caption.
- the counts JUMP rather than drift: the total piece count falls when a light neighbour
is swallowed, because an enclosed cell needs one full circle where it needed several
arcs and a line. `enclosed` goes 0 -> 2 between weight 2.02 and 2.45.
- at exactly 1.0 the whole diagram snaps to straight lines. The piece type is decided
by an EXACT equality of weights, so it really is discontinuous.

```edn [field-box id="spatial_apollonius_sweep" role="properties"]
{"id" "spatial_apollonius_sweep" "index" 2 "name" "Sweep the weight, and watch the bisector bow" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Sweep the weight, and watch the bisector bow" "_tombstone" false "frame" #afield/rect [1020.0 500.0 400.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "spatial_apollonius_sweep_code_.kt" "lines" 50 "characters" 2735 "sha256" "a69c7237114ac3c1eb5e5d639bf871d165837b949b9599a28410cf7b9446fda5"}]}
```

```kotlin [field-property box="spatial_apollonius_sweep" name="code" path="spatial_apollonius_sweep_code_.kt"]
// The animated twin of "When the bisector is a circle". Three stills at three weights argue
// that a straight bisector becomes a circle; sweeping the weight SHOWS it. Launch this box
// (it ends in `animate`, so it runs until you stop it) and watch three things:
//   - only the edges that TOUCH site 1 bow. A bisector between two equally weighted sites
//     stays exactly straight, which is why both counts are in the caption.
//   - the counts JUMP rather than drift: the total piece count falls when a light neighbour
//     is swallowed, because an enclosed cell needs one full circle where it needed several
//     arcs and a line. `enclosed` goes 0 -> 2 between weight 2.02 and 2.45.
//   - at exactly 1.0 the whole diagram snaps to straight lines. The piece type is decided
//     by an EXACT equality of weights, so it really is discontinuous.
val positions = listOf(
    vec(0.0, 0.0), vec(2.2, 0.3), vec(0.9, 2.0), vec(-1.4, 1.1), vec(1.1, -1.6),
)
val clip = CircularDirichlet.Aabb(vec(-3.4, -3.4), vec(4.6, 4.6))
// A FIXED window. Left to fit its own bounds each frame the picture rescales as the arcs
// swell, and the sweep appears to stand still.
val window = Rect(-3.4, -3.4, 8.0, 8.0)
val palette = okhslSet(5, saturation = 0.8, lightness = 0.66)
val cloud = pointCloudFLine(positions, color = vec(1.0, 1.0, 1.0, 0.95), pointSize = 1.6)
val heavy = 1                 // the site whose weight is swept; the other four stay at 1.0
val sweepSeconds = 8.0
val started = AnimationClock.nowSeconds()

// The line that turns elapsed time into the swept parameter — the one you would change. A
// triangle ramp rather than a saw, so it runs out to 2.7 and back to 1.0 with no jump.
fun weightAt(elapsed: Double): Double {
    val phase = (elapsed / sweepSeconds) % 1.0
    return 1.0 + 1.7 * (1.0 - abs(1.0 - 2.0 * phase))
}

animate {
    val w = weightAt(AnimationClock.nowSeconds() - started)
    val diagram = CircularDirichlet.build(
        positions.mapIndexed { i, p -> vec(p.x, p.y, if (i == heavy) w else 1.0) }
    )
    val arcs = diagram.cells.sumOf { c -> c.pieces.count { it is CircularDirichlet.ArcPiece } }
    val straight = diagram.cells.sumOf { c -> c.pieces.count { it is CircularDirichlet.LinePiece } }
    val enclosed = diagram.cells.count { c ->
        c.pieces.size == 1 &&
            (c.pieces[0] as? CircularDirichlet.ArcPiece)?.let { it.sweepAngle > 6.283 } == true
    }
    showPlane(
        diagram.toFLines(clip = clip, maxChordError = 0.005f, palette = palette, thickness = 1.6) + cloud,
        title = "site 1 weight %.2f".format(w),
        caption = "$arcs arcs, $straight straight, enclosed $enclosed",
        width = 560,
        height = 560,
        window = window,
    )
}
```

## 4. Delaunay, and the Voronoi dual

<!-- field-box id="spatial_delaunay" index="3" -->

### Narrative

From `code`

Voronoi and Delaunay are the same object seen twice: the diagram cuts the plane into one
cell per site, the triangulation joins two sites exactly when their cells share an edge.
vec2VoronoiDualGraph is literally one line in the source that returns vec2DelaunayGraph.

```edn [field-box id="spatial_delaunay" role="properties"]
{"id" "spatial_delaunay" "index" 3 "name" "Delaunay, and the Voronoi dual" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Delaunay, and the Voronoi dual" "_tombstone" false "frame" #afield/rect [80.0 80.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "spatial_delaunay_code_.kt" "lines" 71 "characters" 3511 "sha256" "35b9a2469b47748c497e261c52b74b2d49612fcc707ce54cee44ee35d645e0a7"}]}
```

```kotlin [field-property box="spatial_delaunay" name="code" path="spatial_delaunay_code_.kt"]
// Voronoi and Delaunay are the same object seen twice: the diagram cuts the plane into one
// cell per site, the triangulation joins two sites exactly when their cells share an edge.
// vec2VoronoiDualGraph is literally one line in the source that returns vec2DelaunayGraph.
val rng = java.util.Random(17)
val sites = (0 until 54).map { i ->
    vec((i % 9) * 44.0 + rng.nextDouble() * 22.0, (i / 9) * 44.0 + rng.nextDouble() * 22.0)
}

val delaunay = vec2DelaunayGraph(sites)
val triangles = vec2DelaunayTriangles(sites)
val voronoi = vec2VoronoiDiagram(sites)
val hull = vec2ConvexHull(sites)

// Euler's formula pins both counts exactly. A planar triangulation of n points with h of
// them on the convex hull has 2n-2-h triangles and 3n-3-h edges, or it is not a
// triangulation. So this is a complete structural check, not a plausibility check.
val n = sites.size
val h = hull.hullIndices.size
println("sites $n, hull $h")
println("triangles ${triangles.size}  expected 2n-2-h = ${2 * n - 2 - h}")
println("edges     ${delaunay.edges.size}  expected 3n-3-h = ${3 * n - 3 - h}")
println("dual == delaunay: ${vec2VoronoiDualGraph(sites).edges.toSet() == delaunay.edges.toSet()}")

// The cells of the h hull sites are UNBOUNDED, and JTS hands them back clipped to a
// generous envelope rather than to anything you asked for. Print how far it reaches: fit a
// figure to that and the diagram collapses into a speck, which is why showPlaneGrid takes
// an explicit window and clips whatever falls outside it.
val allV = voronoi.cells.flatMap { it.vertices }
println()
println("site  x range %.1f .. %.1f".format(sites.minOf { it.x }, sites.maxOf { it.x }))
println("cell  x range %.1f .. %.1f   <- JTS's clip envelope".format(
    allV.minOf { it.x }, allV.maxOf { it.x }))

val window = Rect(
    sites.minOf { it.x.toDouble() } - 18.0, sites.minOf { it.y.toDouble() } - 18.0,
    sites.maxOf { it.x.toDouble() } - sites.minOf { it.x.toDouble() } + 36.0,
    sites.maxOf { it.y.toDouble() } - sites.minOf { it.y.toDouble() } + 36.0,
)
val bounded = voronoi.cells.count { c ->
    c.vertices.size >= 3 && c.vertices.all {
        it.x >= window.x && it.x <= window.x + window.w &&
            it.y >= window.y && it.y <= window.y + window.h
    }
}
println("cells wholly inside the window: $bounded of ${voronoi.cells.size}")

val cloud = pointCloudFLine(sites, color = vec(1.0, 1.0, 1.0, 0.9), pointSize = 1.1)
val frame = window.toFLine(color = vec(0.35, 0.44, 0.56, 0.55), thickness = 1.0)
val hullLine = polylineFrom(hull.hullPoints, 0, closed = true).also {
    it.color = vec(1.0, 0.78, 0.36, 0.85)
    it.thickness = 2.0
}

showPlaneGrid(
    "vec2DelaunayTriangles — the faces\n${triangles.size} triangles = 2n-2-h" to
        listOf(frame, delaunayTrianglesToFLine(sites, triangles, color = vec(0.55, 0.70, 0.88, 0.6)), cloud),
    "vec2VoronoiDiagram — the dual\n$bounded of ${voronoi.cells.size} cells wholly inside" to
        listOf(frame, *voronoi.toFLines(palette = okhslSet(n, 0.7, 0.62, 0.9f), thickness = 1.2).toTypedArray(), cloud),
    "both, plus vec2ConvexHull\n${delaunay.edges.size} edges = 3n-3-h, $h sites on the hull" to
        listOf(
            frame,
            delaunay.toFLine(sites, color = vec(0.45, 0.58, 0.75, 0.6), thickness = 1.0),
            *voronoi.toFLines(color = vec(0.62, 0.92, 1.0, 0.62), thickness = 1.1).toTypedArray(),
            hullLine,
            cloud,
        ),
    columns = 2,
    cellWidth = 430,
    cellHeight = 280,
    window = window,
)
```

## 5. Sweep the iso level through the saddle

<!-- field-box id="spatial_iso_sweep" index="4" -->

### Narrative

From `code`

The animated twin of "The two decisions inside marching squares". That box argues about a
single ambiguous cell; this one shows why the cell matters. Two Gaussian bumps, and the
iso level swept from 0.18 to 0.78 and back through the saddle between the peaks. Below
the saddle there is ONE contour around both peaks, above it there are TWO, one per peak,
and the changeover is a frame where the marcher's four corner values decide the topology.
Contours are coloured by index, so the merge reads as a colour vanishing.

```edn [field-box id="spatial_iso_sweep" role="properties"]
{"id" "spatial_iso_sweep" "index" 4 "name" "Sweep the iso level through the saddle" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Sweep the iso level through the saddle" "_tombstone" false "frame" #afield/rect [1020.0 1240.0 370.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "spatial_iso_sweep_code_.kt" "lines" 50 "characters" 2386 "sha256" "00886c5826371860d8d5df7155f862e0c52575f0255a40e9a37d35d9f012d0ff"}]}
```

```kotlin [field-property box="spatial_iso_sweep" name="code" path="spatial_iso_sweep_code_.kt"]
// The animated twin of "The two decisions inside marching squares". That box argues about a
// single ambiguous cell; this one shows why the cell matters. Two Gaussian bumps, and the
// iso level swept from 0.18 to 0.78 and back through the saddle between the peaks. Below
// the saddle there is ONE contour around both peaks, above it there are TWO, one per peak,
// and the changeover is a frame where the marcher's four corner values decide the topology.
// Contours are coloured by index, so the merge reads as a colour vanishing.
val field: ScalarField2D = { x, y ->
    exp(-((x - 1.4) * (x - 1.4) + y * y) / 1.2) + exp(-((x + 1.4) * (x + 1.4) + y * y) / 1.2)
}
val x0 = -3.2; val x1 = 3.2; val y0 = -1.9; val y1 = 1.9
val window = Rect(-3.0, -1.7, 6.0, 3.4)       // fixed, or the loops never appear to shrink
val peakDots = pointCloudFLine(
    listOf(vec(1.4, 0.0), vec(-1.4, 0.0)), color = vec(1.0, 1.0, 1.0, 0.85), pointSize = 1.4,
)
val saddleDot = pointCloudFLine(
    listOf(vec(0.0, 0.0)), color = vec(1.0, 0.72, 0.32, 0.98), pointSize = 1.9,
)
val byIndex = listOf(vec(0.42, 0.80, 1.0, 0.95), vec(1.0, 0.45, 0.62, 0.95))
val sweepSeconds = 8.0

// The saddle level is the field's own value at the col.
val saddle = field(0.0, 0.0)
println("saddle level %.3f — below it one contour, above it two".format(saddle))
val started = AnimationClock.nowSeconds()

// The line that turns elapsed time into the swept parameter, straddling the saddle.
fun isoAt(elapsed: Double): Double {
    val phase = (elapsed / sweepSeconds) % 1.0
    return 0.18 + 0.60 * (1.0 - abs(1.0 - 2.0 * phase))
}

animate {
    val iso = isoAt(AnimationClock.nowSeconds() - started)
    val contours = generateIsoContours(field, x0, x1, y0, y1, 100, 60, iso).filter { it.size >= 2 }
    val lines = contours.mapIndexed { i, c ->
        polylineFrom(c, 0, closed = isoContourClosed(c, x0, x1, y0, y1, boundaryEpsilon = 0.05)).also {
            it.color = byIndex[i % byIndex.size]
            it.thickness = 2.0
        }
    }
    showPlane(
        lines + peakDots + saddleDot,
        title = "iso %.3f — %d contour(s)".format(iso, contours.size),
        caption = if (iso > saddle) "above the saddle %.3f — one loop per peak".format(saddle)
        else "below the saddle %.3f — one loop around both".format(saddle),
        width = 560,
        height = 320,
        window = window,
    )
}
```

## 6. When the bisector is a circle

<!-- field-box id="spatial_apollonius" index="5" -->

### Narrative

From `code`

CircularDirichlet minimises |x - p|^2 / w^2 — the multiplicatively weighted, or
Apollonius, diagram. Setting two sites' scores equal and clearing denominators gives the
equation of a CIRCLE, which degenerates to a line only when the weights match. So a cell
here is not a polygon: its boundary is made of ArcPieces and LinePieces.

```edn [field-box id="spatial_apollonius" role="properties"]
{"id" "spatial_apollonius" "index" 5 "name" "When the bisector is a circle" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "When the bisector is a circle" "_tombstone" false "frame" #afield/rect [80.0 500.0 310.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "spatial_apollonius_code_.kt" "lines" 62 "characters" 3208 "sha256" "60779cf12cd818a062300d7422fda4cc824ffb03ad0d60bfa9c5eb2f1e1cb484"}]}
```

```kotlin [field-property box="spatial_apollonius" name="code" path="spatial_apollonius_code_.kt"]
// CircularDirichlet minimises |x - p|^2 / w^2 — the multiplicatively weighted, or
// Apollonius, diagram. Setting two sites' scores equal and clearing denominators gives the
// equation of a CIRCLE, which degenerates to a line only when the weights match. So a cell
// here is not a polygon: its boundary is made of ArcPieces and LinePieces.
val positions = listOf(
    vec(0.0, 0.0), vec(2.2, 0.3), vec(0.9, 2.0), vec(-1.4, 1.1), vec(1.1, -1.6),
)
val clip = CircularDirichlet.Aabb(vec(-3.4, -3.4), vec(4.6, 4.6))

fun diagramOf(weights: List<Double>) =
    CircularDirichlet.build(positions.mapIndexed { i, p -> vec(p.x, p.y, weights[i]) })

val equal = diagramOf(List(5) { 1.0 })
val mixed = diagramOf(listOf(1.0, 1.35, 0.8, 1.1, 1.6))
val swallowed = diagramOf(listOf(1.0, 2.6, 1.0, 1.0, 1.0))

fun arcs(d: CircularDirichlet.Diagram) =
    d.cells.sumOf { c -> c.pieces.count { it is CircularDirichlet.ArcPiece } }
fun straights(d: CircularDirichlet.Diagram) =
    d.cells.sumOf { c -> c.pieces.count { it is CircularDirichlet.LinePiece } }
fun fullCircles(d: CircularDirichlet.Diagram) = d.cells.sumOf { c ->
    c.pieces.count { it is CircularDirichlet.ArcPiece && it.sweepAngle > 6.283 }
}
fun enclosed(d: CircularDirichlet.Diagram) = d.cells.count { c ->
    c.pieces.size == 1 &&
        (c.pieces[0] as? CircularDirichlet.ArcPiece)?.let { it.sweepAngle > 6.283 } == true
}

// All-or-nothing, because the piece type is decided by an exact equality of weights.
println("equal weights : ${straights(equal)} straight, ${arcs(equal)} arcs")
println("mixed weights : ${straights(mixed)} straight, ${arcs(mixed)} arcs")
println("one at 2.6    : ${fullCircles(swallowed)} full-circle pieces, ${enclosed(swallowed)} cells wholly enclosed")

// Every boundary sample is a score tie between its two sites, and no third site beats it.
fun score(s: Vec3, p: Vec2) =
    ((p.x - s.x) * (p.x - s.x) + (p.y - s.y) * (p.y - s.y)).toDouble() / (s.z * s.z).toDouble()
val sites = positions.mapIndexed { i, p -> vec(p.x, p.y, listOf(1.0, 1.35, 0.8, 1.1, 1.6)[i]) }
var worstTie = 0.0
mixed.cells.forEach { cell ->
    cell.pieces.forEach { piece ->
        val p = piece.samplePoint()
        val own = score(sites[cell.siteIndex], p)
        val other = score(sites[piece.neighborSite], p)
        worstTie = maxOf(worstTie, kotlin.math.abs(own - other) / maxOf(1.0, own))
    }
}
println()
println("worst relative score gap across every boundary sample: %.3e".format(worstTie))

val palette = okhslSet(5, saturation = 0.8, lightness = 0.66)
val cloud = pointCloudFLine(positions, color = vec(1.0, 1.0, 1.0, 0.95), pointSize = 1.6)
fun panel(d: CircularDirichlet.Diagram) =
    d.toFLines(clip = clip, maxChordError = 0.01f, palette = palette, thickness = 1.6) + cloud

showPlaneGrid(
    "equal weights — a plain Voronoi diagram\n${straights(equal)} straight bisectors, ${arcs(equal)} arcs" to panel(equal),
    "weights 1.0 / 1.35 / 0.8 / 1.1 / 1.6\n${arcs(mixed)} arcs, ${straights(mixed)} straight" to panel(mixed),
    "one site at weight 2.6\n${fullCircles(swallowed)} complete circles, ${enclosed(swallowed)} cells enclosed" to panel(swallowed),
    columns = 2,
    cellWidth = 430,
    cellHeight = 280,
)
```

## 7. Partitioning the cloud instead of the plane

<!-- field-box id="spatial_clustering" index="6" -->

### Narrative

From `code`

There is no k-means in Field, on purpose. Every algorithm here returns either a hierarchy
or a centre that is meaningful for the data it summarises: a medoid is one of your own
samples, a median is coordinate-wise robust. A k-means centroid is neither.

Well-separated blobs make all four look identical, so this cloud has exactly ONE thing
wrong with it: three tight blobs of 28, and a single 85th point nine units out.

```edn [field-box id="spatial_clustering" role="properties"]
{"id" "spatial_clustering" "index" 6 "name" "Partitioning the cloud instead of the plane" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Partitioning the cloud instead of the plane" "_tombstone" false "frame" #afield/rect [550.0 920.0 400.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "spatial_clustering_code_.kt" "lines" 75 "characters" 4295 "sha256" "aade0bcdfb1cc16d5bd4a9ccb149079c7b780311bf78be3590ebc8d0cb25a0e7"}]}
```

```kotlin [field-property box="spatial_clustering" name="code" path="spatial_clustering_code_.kt"]
// There is no k-means in Field, on purpose. Every algorithm here returns either a hierarchy
// or a centre that is meaningful for the data it summarises: a medoid is one of your own
// samples, a median is coordinate-wise robust. A k-means centroid is neither.
//
// Well-separated blobs make all four look identical, so this cloud has exactly ONE thing
// wrong with it: three tight blobs of 28, and a single 85th point nine units out.
val rng = java.util.Random(7)
val blobCentres = listOf(vec(0.0, 0.0), vec(2.4, 0.2), vec(1.2, 2.2))
val blobs = blobCentres.flatMap { c ->
    List(28) { vec(c.x + rng.nextGaussian() * 0.30, c.y + rng.nextGaussian() * 0.30) }
}
val stray = vec(9.0, 8.0)
// NOT `blobs + stray`: FLineGeometry's `List<Vec2> + Vec2` TRANSLATES every point, and it
// wins over kotlin.collections.plus on specificity. `+ listOf(stray)` zips instead. Append.
val points = buildList { addAll(blobs); add(stray) }
val window = Rect(-1.4, -1.4, 11.2, 10.2)     // shared, so the four panels are comparable

val tree = vec2Agglomerative(points)          // Ward: minimises within-cluster SUM OF SQUARES
val cut3 = tree.partition(3)
val cut6 = tree.partition(6)
val medoids = vec2KMedoids(points, 3, KMedoidsOptions(randomSeed = 11L))
val medians = vec2KMedians(points, 3, KMediansOptions(randomSeed = 11L))

fun sizes(layer: ClusterLayer) =
    (0 until layer.clusterCount).map { layer.indices(it).size }.joinToString("/")

// One ruler for all of them: the objective k-medians optimises, applied to ANY partition.
fun l1Of(labels: IntArray, k: Int) = (0 until k).sumOf { c ->
    val mem = points.indices.filter { labels[it] == c }
    val mx = mem.map { points[it].x }.sorted()[mem.size / 2]
    val my = mem.map { points[it].y }.sorted()[mem.size / 2]
    mem.sumOf { (abs(points[it].x - mx) + abs(points[it].y - my)).toDouble() }
}

println("planted 28/28/28 plus one stray at (9, 8)")
println("agglomerative k=3  ${sizes(cut3)}   L1 of that cut %.3f".format(l1Of(cut3.labels, 3)))
println("agglomerative k=6  ${sizes(cut6)}")
println("kmedoids      k=3  ${medoids.clusterSizes.joinToString("/")}  distortion %.3f  %d rounds  converged=%s"
    .format(medoids.distortion, medoids.iterations, medoids.converged))
println("kmedians      k=3  ${medians.clusterSizes.joinToString("/")}  L1 objective %.3f  converged=%s"
    .format(medians.objective, medians.converged))

// A finer cut of the merge tree refines a coarser one — which is the property that makes a
// hierarchy worth having: you can change your mind about k afterwards, for free.
val nested = (0 until cut6.clusterCount).all { fine ->
    cut6.indices(fine).map { cut3.labels[it] }.toSet().size == 1
}
// And a medoid is an actual input sample. Check it rather than trust it.
val areSamples = medoids.medoids.all { m -> points.any { it.x == m.x && it.y == m.y } }
println()
println("k=6 nested inside k=3: $nested")
println("medoids are input samples: $areSamples")
println("medians beat medoids on the L1 objective: ${medians.objective <= medoids.distortion}")
// The discriminator. Ward spends a whole cluster on the one stray and pays by merging two
// real blobs, because 28/29 * 9.72^2 = 91.2 exceeds 14 * 2.408^2 = 81.2. k-medoids and
// k-medians minimise sums of DISTANCES, where that point costs 9.72 and not 94.5.
println("Ward's cut costs %.2fx k-medians' L1 objective (1.00x on well-separated blobs)"
    .format(l1Of(cut3.labels, 3) / medians.objective))

showPlaneGrid(
    "vec2Agglomerative().partition(3)\nsizes ${sizes(cut3)} — one stray, one whole cluster" to
        cut3.toFLines(points, drawSpokes = false, pointSize = 1.6),
    "…the SAME tree, cut at 6\nsizes ${sizes(cut6)} — nested: $nested. the blobs are back" to
        cut6.toFLines(points, drawSpokes = false, pointSize = 1.6),
    "vec2KMedoids(k = 3)\ndistortion %.3f — samples: %s"
        .format(medoids.distortion, areSamples) to
        medoids.asLayer().toFLines(points, centers = medoids.medoids, pointSize = 1.6),
    "vec2KMedians(k = 3)\nL1 %.3f — Ward's cut costs %.2fx"
        .format(medians.objective, l1Of(cut3.labels, 3) / medians.objective) to
        medians.asLayer().toFLines(points, centers = medians.centers, pointSize = 1.6),
    columns = 2,
    cellWidth = 430,
    cellHeight = 352,
    window = window,
)
```

