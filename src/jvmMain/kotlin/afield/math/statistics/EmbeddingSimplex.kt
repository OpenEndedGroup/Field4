@file:afield.runtime.completion.CompletionCategory("Data")

package afield.math.statistics

import afield.ai.inference.onnx.ClipVector
import afield.math.linalg.Vec3
import afield.math.linalg.vec
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A handful of embeddings as a shape you can look at: every pairwise distance, and the points placed
 * in 3D so that those distances are the lengths of the edges between them.
 *
 * **Any four points of a Euclidean space sit exactly in three dimensions**, however many the space
 * has. So four 512-dimensional CLIP vectors make a tetrahedron whose six edges *are* their six
 * distances, not an approximation of them: classical MDS on four points recovers them up to rotation
 * and reflection. Three points make a triangle (exact in a plane), two a segment. Five or more do not
 * fit in general: [EmbeddingSimplex.stress1] says by how much, and the edges drawn are then the best
 * 3D fit rather than the distances in the table.
 *
 * ```kotlin
 * val simplex = embeddingSimplex(
 *     listOf(clipEncodeImage(cat), clipEncodeImage(dog), clipEncodeImage(tiger), clipEncode("a photo of a cat")),
 *     labels = listOf("cat", "dog", "tiger", "text"),
 * )
 * simplex.edges.maxBy { it.distance }      // the two that are furthest apart
 * showSimplex(simplex)                      // draw it (afield.graphics.three)
 * ```
 *
 * #### Which distance
 *
 * With `normalize` on (the default), each vector is scaled to unit length and
 * [EmbeddingSimplex.distances] are **chord** lengths, `|a − b| = √(2 − 2 cos θ)`, which range over
 * `0..2`. Chord length is a true Euclidean distance between the points on the sphere, which is what
 * makes the embedding exact; cosine distance `1 − cos θ` is half the *square* of it and is not a
 * metric, so it is carried along in [EmbeddingSimplex.cosineDistances] for reading but never fed
 * to MDS.
 *
 * #### The 3D coordinates
 *
 * [EmbeddingSimplex.points] come from [smileMdsDistances] with `positiveCorrection = false`:
 * Torgerson's classical MDS, the double-centred `−½ D²` and its leading eigenvectors scaled by `√λ`,
 * three of them once there are four points or more. They are centred on the origin and multiplied by
 * [EmbeddingSimplex.scale] so that the point farthest from the centre lies at
 * [EmbeddingSimplex.radius]; multiply a distance by that scale to get an edge's drawn length. Signs
 * are not canonicalised, so two machines may draw mirror images of one simplex.
 */

/**
 * The distances among a few embeddings, with a 3D placement of them whose edge lengths are those
 * distances. [exact] says whether the drawing is faithful; [showSimplex] draws it.
 */
class EmbeddingSimplex internal constructor(
    /** One per point, in input order. */
    val labels: List<String>,
    /** Chord distances between the embeddings, `n × n`; plain Euclidean ones with `normalize = false`. */
    val distances: Array<DoubleArray>,
    /** `1 − cos θ` between the points, `n × n`. Carried for reading; never fed to MDS. */
    val cosineDistances: Array<DoubleArray>,
    /** The 3D positions, centred on the origin and scaled by [scale]; `points[i]` is input `i`. */
    val points: List<Vec3>,
    /** Display units per unit of distance. */
    val scale: Double,
    /** Where the point farthest from the centre is drawn, in display units. */
    val radius: Double,
    /** Every pair `a < b`, in the order (0,1), (0,2), … (n−2,n−1). */
    val edges: List<SimplexEdge>,
    /**
     * Kruskal's stress-1 of the 3D placement against [distances]. It is at or below the threshold
     * [exact] tests against whenever the fit is exact (four points or fewer), and larger when the
     * points need more than three dimensions.
     */
    val stress1: Double,
) {
    /** How many points. */
    val size: Int get() = labels.size

    /** Whether the drawn edges are the distances, to [EXACT_STRESS]: always for four or fewer points. */
    val exact: Boolean get() = stress1 <= EXACT_STRESS

    /** The edge between [a] and [b], in either order. */
    fun edge(a: Int, b: Int): SimplexEdge = edges.first { (it.a == a && it.b == b) || (it.a == b && it.b == a) }

    /** The edges that touch point [i], closest first. */
    fun edgesFrom(i: Int): List<SimplexEdge> = edges.filter { it.a == i || it.b == i }.sortedBy { it.rankFrom(i) }

    /** A header line, then one line per edge: the two labels, the distance, and the cosine distance. */
    override fun toString(): String = buildString {
        append("EmbeddingSimplex(${labels.size} points, stress1 %.2e)".format(stress1))
        for (e in edges) append("\n  ${labels[e.a]} – ${labels[e.b]}: %.3f (cos %.3f)".format(e.distance, e.cosineDistance))
    }

    companion object {
        /** The [stress1] below which a fit counts as [exact]; float32 embeddings land near 1e-8. */
        const val EXACT_STRESS = 1e-5
    }
}

/**
 * One pair of an [EmbeddingSimplex]: the two points, how far apart they are, and where that distance
 * ranks at each end.
 */
data class SimplexEdge(
    /** Index of one end, the lower of the two. */
    val a: Int,
    /** Index of the other end, always greater than [a]. */
    val b: Int,
    /** Chord (or Euclidean) distance between the two embeddings. */
    val distance: Double,
    /** `1 − cos θ` between them. */
    val cosineDistance: Double,
    /**
     * Where this edge comes among the edges that touch [a], `0` being the closest of them; ties go to
     * the lower point index. With four points the ranks run 0, 1, 2, and [showSimplex] with
     * `flags = true` draws them as green, orange and red diamonds at each end of each edge.
     */
    val rankFromA: Int,
    /** The same for [b]. */
    val rankFromB: Int,
) {
    /** [rankFromA] or [rankFromB], for whichever end [i] is. */
    fun rankFrom(i: Int): Int = when (i) {
        a -> rankFromA
        b -> rankFromB
        else -> throw IllegalArgumentException("point $i is not an end of edge $a–$b")
    }
}

/**
 * Build an [EmbeddingSimplex] from equal-length vectors: any embedding, not only CLIP.
 *
 * @param labels one per vector; defaults to `1`, `2`, ….
 * @param normalize scale every vector to unit length first, so distances are chords (see
 * [EmbeddingSimplex]); turn it off for an embedding whose length means something.
 * @param radius where the point farthest from the centre is drawn.
 */
fun embeddingSimplex(
    vectors: List<DoubleArray>,
    labels: List<String> = vectors.indices.map { "${it + 1}" },
    normalize: Boolean = true,
    radius: Double = 3.0,
): EmbeddingSimplex {
    val n = vectors.size
    require(n >= 1) { "embeddingSimplex: at least one vector is required" }
    require(labels.size == n) { "embeddingSimplex: ${labels.size} labels for $n vectors" }
    require(radius.isFinite() && radius > 0) { "embeddingSimplex: radius must be positive, got $radius" }
    val dims = vectors[0].size
    require(dims > 0) { "embeddingSimplex: vectors must not be empty" }
    vectors.forEachIndexed { i, v ->
        require(v.size == dims) { "embeddingSimplex: vector $i has ${v.size} values; expected $dims" }
        require(v.all { it.isFinite() }) { "embeddingSimplex: vector $i has a value that is not finite" }
    }
    val unit = vectors.mapIndexed { i, v ->
        val norm = sqrt(v.sumOf { it * it })
        if (normalize) {
            require(norm > 0) { "embeddingSimplex: vector $i is zero and has no direction" }
            DoubleArray(dims) { v[it] / norm }
        } else v
    }

    val distances = Array(n) { DoubleArray(n) }
    val cosine = Array(n) { DoubleArray(n) }
    for (a in 0 until n) for (b in 0 until a) {
        var dot = 0.0
        var sq = 0.0
        var na = 0.0
        var nb = 0.0
        for (k in 0 until dims) {
            val x = unit[a][k]
            val y = unit[b][k]
            dot += x * y; na += x * x; nb += y * y
            sq += (x - y) * (x - y)
        }
        val d = sqrt(max(0.0, sq))
        val c = (1.0 - dot / sqrt(na * nb)).coerceIn(0.0, 2.0)
        distances[a][b] = d; distances[b][a] = d
        cosine[a][b] = c; cosine[b][a] = c
    }

    val coordinates = classicalMds3(distances)
    val reach = coordinates.maxOf { sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2]) }
    val scale = if (reach > 0) radius / reach else 1.0
    val points = coordinates.map { vec(it[0] * scale, it[1] * scale, it[2] * scale) }

    // Rank each point's incident edges, closest first, ties to the lower index.
    val rank = Array(n) { IntArray(n) }
    for (i in 0 until n) {
        (0 until n).filter { it != i }
            .sortedWith(compareBy<Int> { distances[i][it] }.thenBy { it })
            .forEachIndexed { r, j -> rank[i][j] = r }
    }
    val edges = ArrayList<SimplexEdge>()
    for (a in 0 until n) for (b in a + 1 until n) {
        edges += SimplexEdge(a, b, distances[a][b], cosine[a][b], rank[a][b], rank[b][a])
    }

    return EmbeddingSimplex(labels.toList(), distances, cosine, points, scale, radius, edges, stress1(distances, coordinates))
}

/** [embeddingSimplex] over `FloatArray`s. */
@JvmName("embeddingSimplexFloat")
fun embeddingSimplex(
    vectors: List<FloatArray>,
    labels: List<String> = vectors.indices.map { "${it + 1}" },
    normalize: Boolean = true,
    radius: Double = 3.0,
): EmbeddingSimplex = embeddingSimplex(vectors.map { v -> DoubleArray(v.size) { v[it].toDouble() } }, labels, normalize, radius)

/** [embeddingSimplex] over CLIP vectors, from images and prompts alike: they share one space. */
@JvmName("embeddingSimplexClip")
fun embeddingSimplex(
    vectors: List<ClipVector>,
    labels: List<String> = vectors.indices.map { "${it + 1}" },
    radius: Double = 3.0,
): EmbeddingSimplex = embeddingSimplex(vectors.map { it.values }, labels, normalize = true, radius = radius)

/**
 * Classical MDS of [distances] into three columns: [smileMdsDistances] wherever it applies (it needs
 * at least one output dimension, fewer than the number of points), the obvious answer for one or two
 * points, and zero columns past what the points can span.
 */
private fun classicalMds3(distances: Array<DoubleArray>): List<DoubleArray> {
    val n = distances.size
    return when (n) {
        1 -> listOf(DoubleArray(3))
        2 -> listOf(doubleArrayOf(-distances[0][1] / 2, 0.0, 0.0), doubleArrayOf(distances[0][1] / 2, 0.0, 0.0))
        else -> {
            val dims = minOf(3, n - 1)
            val fit = smileMdsDistances(distances, dimensions = dims, label = "chord", positiveCorrection = false)
            fit.coordinates.map { row -> DoubleArray(3) { if (it < dims) row[it] else 0.0 } }
        }
    }
}

/** Kruskal's stress-1 of [coordinates] against [distances]: `√(Σ(d − δ)² / Σd²)` over pairs. */
private fun stress1(distances: Array<DoubleArray>, coordinates: List<DoubleArray>): Double {
    var num = 0.0
    var den = 0.0
    for (a in distances.indices) for (b in 0 until a) {
        val p = coordinates[a]
        val q = coordinates[b]
        val fitted = sqrt((0 until 3).sumOf { (p[it] - q[it]) * (p[it] - q[it]) })
        num += (distances[a][b] - fitted) * (distances[a][b] - fitted)
        den += distances[a][b] * distances[a][b]
    }
    return if (den > 0) sqrt(num / den) else 0.0
}
