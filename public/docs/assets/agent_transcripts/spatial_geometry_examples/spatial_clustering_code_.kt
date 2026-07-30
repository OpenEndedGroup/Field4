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
