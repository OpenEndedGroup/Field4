// 60 Gaussian blobs of uneven width, 800 points each: 48 000 points.
val rng = java.util.Random(4)
val centres = List(60) { vec(rng.nextDouble() * 10, rng.nextDouble() * 10) }
val widths = List(60) { 0.08 + rng.nextDouble() * 0.22 }
val points =
    List(48_000) { i ->
        val c = i % 60
        vec(centres[c].x + rng.nextGaussian() * widths[c], centres[c].y + rng.nextGaussian() * widths[c])
    }
val window = Rect(-0.6, -0.6, 11.2, 11.2)
// Same random seeds for both, so the only difference is the algorithm.
val seeds = KMeansOptions(seeding = KMeansSeeding.RANDOM, randomSeed = 3L)
val lloyd = vec2KMeans(points, 60, seeds.copy(algorithm = KMeansAlgorithm.LLOYD, epochs = 25))
val emeans = vec2KMeans(points, 60, seeds.copy(algorithm = KMeansAlgorithm.EMEANS, epochs = 3))

// Draw one point in thirteen (coprime with 60, so every blob shows): the clustering saw all 48 000.
val shown = points.indices.filter { it % 13 == 0 }
fun drawn(r: KMeansResult<Vec2>) =
    ClusterLayer("shown", IntArray(shown.size) { r.labels[shown[it]] })
        .toFLines(
            shown.map { points[it] },
            centers = r.centroids,
            drawSpokes = false,
            pointSize = 0.9,
            centerSize = 1.6,
        )

// MSE per epoch on a log axis, laid out inside the same window as the clouds.
val lo = ln(0.05)
val hi = ln(maxOf(lloyd.mseHistory.max(), emeans.mseHistory.max()))
fun curve(history: DoubleArray, color: Vec4) = polylineFrom(
    history.indices.map { e -> vec(0.4 + e / 25.0 * 9.2, 9.6 - (ln(history[e]) - lo) / (hi - lo) * 9.2) },
    0,
    closed = false,
).also {
    it.color = color
    it.thickness = 2.0
    it.pointed = true
    it.pointSize = 2.4
}
val axes = Rect(0.0, 0.0, 10.0, 10.0).toFLine(color = vec(0.35, 0.44, 0.56, 0.55), thickness = 1.0)

showPlaneGrid(
    "Lloyd, 25 epochs\nMSE %.4f, converged: ${lloyd.converged}".format(lloyd.mse) to drawn(lloyd),
    "e-means, 3 epochs\nMSE %.4f, batch ${emeans.batchSize}, alpha0 %.3f".format(emeans.mse, emeans.alpha0) to
        drawn(emeans),
    "MSE per epoch, log scale\norange Lloyd, blue e-means; e-means 1 epoch %.4f".format(emeans.mseHistory[1]) to
        listOf(
            axes,
            curve(lloyd.mseHistory, vec(1.0, 0.62, 0.30, 0.95)),
            curve(emeans.mseHistory, vec(0.45, 0.80, 1.0, 0.95)),
        ),
    columns = 3,
    cellWidth = 340,
    cellHeight = 340,
    window = window,
)
