// There is no k-means in Field, on purpose. Every algorithm here returns either a hierarchy
// or a centre that is meaningful for the data it summarises: a medoid is one of your own
// samples, a median is coordinate-wise robust. A k-means centroid is neither.
val rng = java.util.Random(3)
val centres = listOf(vec(0.0, 0.0), vec(3.0, 0.2), vec(1.4, 2.6))
val points = centres.flatMap { c ->
    List(30) { vec(c.x + rng.nextGaussian() * 0.35, c.y + rng.nextGaussian() * 0.35) }
}

val tree = vec2Agglomerative(points)          // Ward linkage by default
val cut3 = tree.partition(3)
val cut6 = tree.partition(6)
val medoids = vec2KMedoids(points, 3, KMedoidsOptions(randomSeed = 11L))
val medians = vec2KMedians(points, 3, KMediansOptions(randomSeed = 11L))

fun sizes(layer: ClusterLayer) =
    (0 until layer.clusterCount).map { layer.indices(it).size }.joinToString("/")

println("planted 30/30/30")
println("agglomerative k=3  ${sizes(cut3)}")
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

showPlaneGrid(
    "vec2Agglomerative().partition(3)\nsizes ${sizes(cut3)} — the planted clusters, whole" to
        cut3.toFLines(points, drawSpokes = false, pointSize = 1.6),
    "…the same tree cut at 6\nsizes ${sizes(cut6)}, nested in the k=3 cut: $nested" to
        cut6.toFLines(points, drawSpokes = false, pointSize = 1.6),
    "vec2KMedoids(k = 3)\ndistortion %.3f, %d rounds, medoids are samples: %s"
        .format(medoids.distortion, medoids.iterations, areSamples) to
        medoids.asLayer().toFLines(points, centers = medoids.medoids, pointSize = 1.6),
    "vec2KMedians(k = 3)\nL1 objective %.3f, converged: %s".format(medians.objective, medians.converged) to
        medians.asLayer().toFLines(points, centers = medians.centers, pointSize = 1.6),
    columns = 2,
    cellWidth = 430,
    cellHeight = 280,
)
