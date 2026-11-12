// The centroid-trails cloud, on a slider: drag through the epochs and watch Lloyd crawl while e-means jumps.
val rng = java.util.Random(12)
val centres = List(30) { vec(rng.nextDouble() * 10, rng.nextDouble() * 10) }
val points =
    List(12_000) { i ->
        val c = i % 30
        vec(centres[c].x + rng.nextGaussian() * 0.25, centres[c].y + rng.nextGaussian() * 0.25)
    }
val seeds = KMeansOptions(seeding = KMeansSeeding.RANDOM, randomSeed = 2L, recordCentroids = true)
val lloyd = vec2KMeans(points, 30, seeds.copy(algorithm = KMeansAlgorithm.LLOYD, epochs = 25))
val emeans = vec2KMeans(points, 30, seeds.copy(epochs = 3))
val cloud =
    pointCloudFLine(points.filterIndexed { i, _ -> i % 16 == 0 }, color = vec(0.55, 0.65, 0.78, 0.35), pointSize = 0.8)

// The trails up to epoch e, and the centroids at e; a run that has finished stays where it ended.
fun upTo(r: KMeansResult<Vec2>, e: Int): List<FLine> {
    val last = minOf(e, r.centroidHistory.size - 1)
    val trails =
        (0 until r.k).map { c ->
            polylineFrom(r.centroidHistory.take(last + 1).map { it[c] }).also {
                it.color = vec(1.0, 0.85, 0.45, 0.9)
                it.thickness = 1.2
            }
        }
    return listOf(cloud) +
        trails +
        pointCloudFLine(r.centroidHistory[last], color = vec(1.0, 1.0, 1.0, 0.95), pointSize = 2.2)
}

fun show(e: Int) = showPlaneGrid(
    "Lloyd, epoch ${minOf(e, lloyd.epochs)} of ${lloyd.epochs}\nMSE %.4f"
        .format(lloyd.mseHistory[minOf(e, lloyd.epochs)]) to upTo(lloyd, e),
    "e-means, epoch ${minOf(e, emeans.epochs)} of ${emeans.epochs}\nMSE %.4f"
        .format(emeans.mseHistory[minOf(e, emeans.epochs)]) to upTo(emeans, e),
    columns = 2,
    cellWidth = 420,
    cellHeight = 420,
    window = Rect(-0.8, -0.8, 11.6, 11.6),
)
slider("epoch", min = 0.0, max = 25.0, step = 1.0, initialValue = 1.0) { show(it.toInt()) }
displayOpen("controls")
show(1)
