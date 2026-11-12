// 40 blobs of 400 points, and 12 strays on a circle far outside them.
val rng = java.util.Random(8)
val centres = List(40) { vec(rng.nextDouble() * 10, rng.nextDouble() * 10) }
val blobs =
    List(16_000) { i ->
        val c = i % 40
        vec(centres[c].x + rng.nextGaussian() * 0.2, centres[c].y + rng.nextGaussian() * 0.2)
    }
val strays = List(12) { j -> vec(5.0 + 9.0 * cos(j * PI / 6), 5.0 + 9.0 * sin(j * PI / 6)) }
val points = buildList {
    addAll(blobs)
    addAll(strays)
}
val window = Rect(-4.6, -4.6, 19.2, 19.2)

// Plain k-means++ seeds favour far points, so several land on strays. Revival is off by default in 2-D.
val options = KMeansOptions(epochs = 3, seeding = KMeansSeeding.PLUS_PLUS, randomSeed = 5L)
val off = vec2KMeans(points, 40, options.copy(reviveThreshold = 0.0))
val on = vec2KMeans(points, 40, options.copy(reviveThreshold = 0.1))
fun dead(r: KMeansResult<Vec2>) = r.clusterSizes.count { it < 10 }

val shown = points.indices.filter { it % 7 == 0 || it >= blobs.size }
fun drawn(r: KMeansResult<Vec2>) =
    ClusterLayer("shown", IntArray(shown.size) { r.labels[shown[it]] })
        .toFLines(
            shown.map { points[it] },
            centers = r.centroids,
            drawSpokes = false,
            pointSize = 1.0,
            centerSize = 1.6,
        )

showPlaneGrid(
    "e-means, revival off\n${dead(off)} clusters under 10 members, MSE %.3f".format(off.mse) to drawn(off),
    "e-means, revival on\n${dead(on)} under 10, ${on.revivals} revivals, MSE %.3f".format(on.mse) to drawn(on),
    columns = 2,
    cellWidth = 400,
    cellHeight = 400,
    window = window,
)
