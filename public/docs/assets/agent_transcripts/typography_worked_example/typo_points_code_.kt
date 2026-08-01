// The last bridge is the plainest: even-by-arc-length samples along the outlines, which is
// what every point-consuming family in the stdlib eats. Once a word is points, the
// clusterers, the triangulators and the alife agents all work on letters.
val layout = layoutText("field", typeface("SansSerif", 200.0))
val coarse = layout.samplePoints(spacing = 18.0)
val fine = layout.samplePoints(spacing = 4.5)
println("spacing 18 -> ${coarse.size} points, spacing 4.5 -> ${fine.size} points " +
    "(${"%.2f".format(fine.size.toDouble() / coarse.size)}x for 4x finer)")

// Delaunay over the outline samples: the triangulation of a word.
val graph = vec2DelaunayGraph(coarse)
println("Delaunay over ${coarse.size} outline samples: ${graph.edges.size} edges")

// And k-medoids, which finds the strokes: six clusters over the same points, each medoid an
// actual sample rather than an average.
val km = vec2KMedoids(coarse, k = 6, options = KMedoidsOptions(randomSeed = 11L))
val layer = ClusterLayer("outline strokes", km.labels)
println("k-medoids k=6: sizes ${km.labels.toList().groupingBy { it }.eachCount().values.sorted()}, " +
    "distortion ${"%.1f".format(km.distortion)}, converged ${km.converged}")

// Every panel shares one window, so the four views of the same word line up.
val window = layout.bounds
showPlaneGrid(
    "outline samples (${coarse.size} at spacing 18)" to listOf(layout.toPointFLine(spacing = 18.0, pointSize = 1.1)),
    "Delaunay over those samples" to
        listOf(graph.toFLine(coarse, thickness = 0.8), layout.toPointFLine(spacing = 18.0, pointSize = 0.9)),
    "k-medoids, k = 6" to layer.toFLines(coarse, centers = km.medoids, drawSpokes = false),
    "the outlines the samples came from" to layout.toFLines(thickness = 1.2),
    columns = 2, cellWidth = 400, cellHeight = 240, window = window
)
