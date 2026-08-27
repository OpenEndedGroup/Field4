@file:afield.CompletionCategory("Data")

package afield.stdlib

import smile.manifold.MDS
import smile.manifold.TSNE
import smile.math.MathEx
import kotlin.math.max
import kotlin.math.sqrt

/** Distance functions supplied by Field's general-purpose [smileMds] wrapper. */
enum class SmileMdsDistance {
    /** Angular similarity: useful when vector direction matters and magnitude should not. */
    COSINE,

    /** Ordinary straight-line distance in the input space. */
    EUCLIDEAN,
}

/**
 * A classical multidimensional-scaling result produced in Field by Smile.
 *
 * [coordinates] remains in the same row order as the input vectors. [scores] are Smile's retained
 * eigenvalues and [proportion] reports their explained proportion. The complete pairwise distance
 * matrix is intentionally not retained.
 */
data class SmileMdsProjection(
    val coordinates: Array<DoubleArray>,
    val scores: DoubleArray,
    val proportion: DoubleArray,
    val distance: SmileMdsDistance,
    val positiveCorrection: Boolean,
)

/** A reproducible t-SNE result produced by Smile from Field-owned vectors. */
data class SmileTsneProjection(
    val coordinates: Array<DoubleArray>,
    val cost: Double,
    val distance: SmileMdsDistance,
    val perplexity: Double,
    val seed: Long,
)

/**
 * Project arbitrary equal-length vectors with Smile's classical MDS implementation.
 *
 * This function constructs the non-squared, symmetric dissimilarity matrix Smile expects. Its
 * memory cost is quadratic in the number of vectors, so sample a long trajectory before calling
 * it. [positiveCorrection] asks Smile to correct a non-Euclidean dissimilarity matrix; this is a
 * useful default for cosine distance.
 */
fun smileMds(
    vectors: Array<DoubleArray>,
    dimensions: Int = 2,
    distance: SmileMdsDistance = SmileMdsDistance.COSINE,
    positiveCorrection: Boolean = true,
): SmileMdsProjection {
    require(vectors.size >= 2) { "smileMds: at least two vectors are required" }
    val inputDimensions = vectors.first().size
    require(inputDimensions > 0) { "smileMds: vectors must not be empty" }
    require(dimensions in 1 until vectors.size) {
        "smileMds: dimensions must be between 1 and vectors.size - 1"
    }
    vectors.forEachIndexed { row, vector ->
        require(vector.size == inputDimensions) {
            "smileMds: vector $row has ${vector.size} values; expected $inputDimensions"
        }
        vector.forEachIndexed { column, value ->
            require(value.isFinite()) {
                "smileMds: vector $row value $column is not finite"
            }
        }
    }

    val proximity = Array(vectors.size) { DoubleArray(vectors.size) }
    val norms = if (distance == SmileMdsDistance.COSINE) {
        DoubleArray(vectors.size) { row ->
            sqrt(vectors[row].sumOf { value -> value * value })
        }.also { values ->
            values.forEachIndexed { row, norm ->
                require(norm > 0.0) { "smileMds: cosine distance is undefined for zero vector $row" }
            }
        }
    } else null

    for (a in vectors.indices) {
        for (b in 0 until a) {
            val dissimilarity = when (distance) {
                SmileMdsDistance.COSINE -> {
                    var dot = 0.0
                    for (column in 0 until inputDimensions) {
                        dot += vectors[a][column] * vectors[b][column]
                    }
                    (1.0 - dot / (norms!![a] * norms[b])).coerceIn(0.0, 2.0)
                }
                SmileMdsDistance.EUCLIDEAN -> {
                    var squared = 0.0
                    for (column in 0 until inputDimensions) {
                        val delta = vectors[a][column] - vectors[b][column]
                        squared += delta * delta
                    }
                    sqrt(max(0.0, squared))
                }
            }
            proximity[a][b] = dissimilarity
            proximity[b][a] = dissimilarity
        }
    }

    val model = MDS.fit(proximity, MDS.Options(dimensions, positiveCorrection))
    return SmileMdsProjection(
        coordinates = model.coordinates(),
        scores = model.scores(),
        proportion = model.proportion(),
        distance = distance,
        positiveCorrection = positiveCorrection,
    )
}

private val smileTsneLock = Any()

/**
 * Project equal-length vectors with Smile's exact t-SNE implementation.
 *
 * For [SmileMdsDistance.COSINE], Field supplies a cosine-distance matrix; Euclidean mode lets Smile
 * construct ordinary distances from the vectors. Smile's initializer uses its process-wide random
 * source, so fitting is serialized while [seed] is installed to keep live-code reruns reproducible.
 * t-SNE preserves local neighbourhoods: its axes and long-range distances have no stable meaning.
 */
fun smileTsne(
    vectors: Array<DoubleArray>,
    dimensions: Int = 2,
    distance: SmileMdsDistance = SmileMdsDistance.COSINE,
    perplexity: Double = 30.0,
    learningRate: Double = 200.0,
    earlyExaggeration: Double = 12.0,
    maxIterations: Int = 750,
    seed: Long = 184_921L,
): SmileTsneProjection {
    require(vectors.size >= 4) { "smileTsne: at least four vectors are required" }
    val inputDimensions = vectors.first().size
    require(inputDimensions > 0) { "smileTsne: vectors must not be empty" }
    require(dimensions in 1 until vectors.size) {
        "smileTsne: dimensions must be between 1 and vectors.size - 1"
    }
    require(perplexity.isFinite() && perplexity > 0.0 && perplexity < vectors.size) {
        "smileTsne: perplexity must be positive and smaller than vectors.size"
    }
    require(learningRate.isFinite() && learningRate > 0.0) {
        "smileTsne: learningRate must be finite and positive"
    }
    require(earlyExaggeration.isFinite() && earlyExaggeration >= 1.0) {
        "smileTsne: earlyExaggeration must be finite and at least 1"
    }
    require(maxIterations > 250) { "smileTsne: maxIterations must be greater than 250" }
    vectors.forEachIndexed { row, vector ->
        require(vector.size == inputDimensions) {
            "smileTsne: vector $row has ${vector.size} values; expected $inputDimensions"
        }
        vector.forEachIndexed { column, value ->
            require(value.isFinite()) { "smileTsne: vector $row value $column is not finite" }
        }
    }

    val input = when (distance) {
        SmileMdsDistance.EUCLIDEAN -> vectors
        SmileMdsDistance.COSINE -> cosineDistanceMatrix(vectors, "smileTsne")
    }
    val options = TSNE.Options(
        dimensions,
        perplexity,
        learningRate,
        earlyExaggeration,
        maxIterations,
    )
    val model = synchronized(smileTsneLock) {
        MathEx.setSeed(seed)
        TSNE.fit(input, options)
    }
    return SmileTsneProjection(
        coordinates = model.coordinates(),
        cost = model.cost(),
        distance = distance,
        perplexity = perplexity,
        seed = seed,
    )
}

private fun cosineDistanceMatrix(vectors: Array<DoubleArray>, caller: String): Array<DoubleArray> {
    val norms = DoubleArray(vectors.size) { row ->
        sqrt(vectors[row].sumOf { value -> value * value })
    }.also { values ->
        values.forEachIndexed { row, norm ->
            require(norm > 0.0) { "$caller: cosine distance is undefined for zero vector $row" }
        }
    }
    return Array(vectors.size) { a ->
        DoubleArray(vectors.size) { b ->
            if (a == b) 0.0 else {
                var dot = 0.0
                for (column in vectors[a].indices) dot += vectors[a][column] * vectors[b][column]
                (1.0 - dot / (norms[a] * norms[b])).coerceIn(0.0, 2.0)
            }
        }
    }
}
