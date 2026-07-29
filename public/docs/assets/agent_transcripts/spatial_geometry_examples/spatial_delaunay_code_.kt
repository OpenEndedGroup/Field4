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
