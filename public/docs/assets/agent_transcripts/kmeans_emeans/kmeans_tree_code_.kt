// 1 000 points scattered over a square, clustered into n/2, then those centroids into n/4, ... down to one.
val rng = java.util.Random(40)
val points = List(1_000) { vec(rng.nextDouble() * 10, rng.nextDouble() * 10) }
val options = KMeansOptions(epochs = 4, randomSeed = 7L, stepScale = 0.3, closeOnMean = false)
val halves = vec2KMeansTree(points, shrink = 2.0, options = options)
val thirds = vec2KMeansTree(points, shrink = 3.0, options = options)

showPlaneGrid(
    "shrink = 2\n${halves.levels.joinToString(" → ") { "${it.result.k}" }}" to halves.toFLines(leafSize = 0.8),
    "shrink = 3\n${thirds.levels.joinToString(" → ") { "${it.result.k}" }}" to thirds.toFLines(leafSize = 0.8),
    columns = 2,
    cellWidth = 420,
    cellHeight = 420,
    window = Rect(-0.4, -0.4, 10.8, 10.8),
)
