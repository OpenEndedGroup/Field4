@file:afield.CompletionCategory("Data")

package afield.stdlib

import smile.linalg.UPLO
import smile.manifold.MDS
import smile.manifold.TSNE
import smile.math.MathEx
import smile.tensor.DenseMatrix
import smile.tensor.ScalarType
import kotlin.math.abs
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
 * [coordinates] remains in the same row order as the input vectors. [scores] are the retained
 * eigenvalues, largest first.
 *
 * [proportion] is those same eigenvalues divided by *their own* L1 norm, which is what Smile's
 * `MathEx.unitize1` does to them, so it **always sums to 1** and says nothing about how much of the
 * structure survived the projection: two dimensions of a twenty-point matrix and fifteen dimensions
 * of the same matrix both report 1.0. Read it as "how the kept components divide up between
 * themselves". For "how much did I lose", compare the projected distances against the input ones —
 * `public/docs/projection_examples.md` does that with a stress figure.
 *
 * The complete pairwise distance matrix is intentionally not retained.
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

/** Classical MDS over a caller-supplied symmetric dissimilarity matrix. */
data class SmileMdsProximityProjection(
    val coordinates: Array<DoubleArray>,
    val scores: DoubleArray,
    val proportion: DoubleArray,
    val label: String,
    val positiveCorrection: Boolean,
)

/** Exact t-SNE over a caller-supplied symmetric dissimilarity matrix. */
data class SmileTsneProximityProjection(
    val coordinates: Array<DoubleArray>,
    val cost: Double,
    val label: String,
    val perplexity: Double,
    val seed: Long,
)

/**
 * Project arbitrary equal-length vectors with Smile's classical MDS implementation.
 *
 * This function constructs the non-squared, symmetric dissimilarity matrix Smile expects. Its
 * memory cost is quadratic in the number of vectors — 191 MiB of `double` at 5000 vectors, 3 GiB at
 * 20 000 — so sample a long trajectory before calling it.
 *
 * [positiveCorrection] adds a constant to every off-diagonal dissimilarity to make the learning
 * matrix positive semi-definite, which is what a non-Euclidean input (cosine distance, an edit
 * distance, a graph distance) needs before it has more than a handful of usable dimensions. It buys
 * *dimensions*, not accuracy: measured on a 20-word Levenshtein matrix, the correction takes the
 * largest requestable `dimensions` from 9 to 18 while leaving the two-dimensional fit fractionally
 * worse. Worked through on `public/docs/projection_examples.md`.
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
    require(dimensions in 2 until vectors.size) {
        "smileMds: dimensions must be between 2 and vectors.size - 1"
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

    val model = fitClassicalMds(proximity, dimensions, positiveCorrection)
    return SmileMdsProjection(
        coordinates = model.coordinates,
        scores = model.scores,
        proportion = model.proportion,
        distance = distance,
        positiveCorrection = positiveCorrection,
    )
}

private val smileTsneLock = Any()

/** Project an explicit distance matrix; useful for transport, edit, and graph distances. */
fun smileMdsDistances(
    distances: Array<DoubleArray>,
    dimensions: Int = 2,
    label: String = "precomputed",
    positiveCorrection: Boolean = true,
): SmileMdsProximityProjection {
    validateDistanceMatrix(distances, dimensions, "smileMdsDistances")
    val model = fitClassicalMds(distances, dimensions, positiveCorrection)
    return SmileMdsProximityProjection(
        model.coordinates, model.scores, model.proportion, label, positiveCorrection,
    )
}

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
    require(dimensions in 2 until vectors.size) {
        "smileTsne: dimensions must be between 2 and vectors.size - 1"
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

/** Run fixed-seed t-SNE from an explicit distance matrix. */
fun smileTsneDistances(
    distances: Array<DoubleArray>,
    dimensions: Int = 2,
    label: String = "precomputed",
    perplexity: Double = 30.0,
    learningRate: Double = 200.0,
    earlyExaggeration: Double = 12.0,
    maxIterations: Int = 750,
    seed: Long = 184_921L,
): SmileTsneProximityProjection {
    validateDistanceMatrix(distances, dimensions, "smileTsneDistances", minimumRows = 4)
    require(perplexity.isFinite() && perplexity > 0.0 && perplexity < distances.size) {
        "smileTsneDistances: perplexity must be positive and smaller than distances.size"
    }
    require(learningRate.isFinite() && learningRate > 0.0) {
        "smileTsneDistances: learningRate must be finite and positive"
    }
    require(earlyExaggeration.isFinite() && earlyExaggeration >= 1.0) {
        "smileTsneDistances: earlyExaggeration must be finite and at least 1"
    }
    require(maxIterations > 250) { "smileTsneDistances: maxIterations must be greater than 250" }
    val options = TSNE.Options(
        dimensions, perplexity, learningRate, earlyExaggeration, maxIterations,
    )
    val model = synchronized(smileTsneLock) {
        MathEx.setSeed(seed)
        TSNE.fit(distances, options)
    }
    return SmileTsneProximityProjection(
        model.coordinates(), model.cost(), label, perplexity, seed,
    )
}

private fun validateDistanceMatrix(
    distances: Array<DoubleArray>,
    dimensions: Int,
    caller: String,
    minimumRows: Int = 2,
) {
    require(distances.size >= minimumRows) { "$caller: at least $minimumRows rows are required" }
    require(dimensions in 2 until distances.size) {
        "$caller: dimensions must be between 2 and distances.size - 1"
    }
    distances.forEachIndexed { row, values ->
        require(values.size == distances.size) {
            "$caller: row $row has ${values.size} values; expected ${distances.size}"
        }
        values.forEachIndexed { column, value ->
            require(value.isFinite() && value >= 0.0) {
                "$caller: distance [$row,$column] must be finite and non-negative"
            }
            require(kotlin.math.abs(value - distances[column][row]) <= 1e-9) {
                "$caller: distance matrix is not symmetric at [$row,$column]"
            }
        }
        require(kotlin.math.abs(values[row]) <= 1e-12) {
            "$caller: diagonal distance [$row,$row] must be zero"
        }
    }
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

/** One classical-MDS fit: the same three arrays `smile.manifold.MDS` returns, in the same order. */
private class ClassicalMdsFit(
    val coordinates: Array<DoubleArray>,
    val scores: DoubleArray,
    val proportion: DoubleArray,
)

/**
 * Whether Smile's ARPACK binding can be initialised in this process.
 *
 * `smile.manifold.MDS.fit` takes its top-*d* eigenpairs from `smile.tensor.ARPACK.syev`, whose
 * `<clinit>` `dlopen`s `libarpack` through the FFM `SymbolLookup`. On macOS/arm64 that fails, and
 * the failure has nothing to do with Field: bytedeco's `arpack-ng:3.9.1-1.5.12:macosx-arm64`
 * `libarpack.2.dylib` wants `@rpath/libgfortran.5.dylib`, `@rpath/libquadmath.0.dylib` and — one
 * level down, through libgfortran — `@rpath/libgcc_s.1.1.dylib`, and **no bytedeco artifact in this
 * build ships any of the three on that platform**. `build.gradle.kts` says so at the `arpackVersion`
 * declaration. The result was that `smileMds` and `smileMdsDistances` threw
 * `NoClassDefFoundError: Could not initialize class smile.linalg.arpack.arpack_h` for *every* input
 * on Apple Silicon — the platform this repository is developed on — while `smileTsne` and
 * `smileTsneDistances`, which never touch ARPACK, worked normally.
 *
 * So the probe: force the class initialiser once and remember the answer, because a failed
 * `MDS.fit` would otherwise repeat the whole double-centring — and, with `positiveCorrection`, a
 * `2n x 2n` non-symmetric eigendecomposition — before hitting the same wall.
 *
 * A rename of Smile's internal binding class would land here as `ClassNotFoundException` and report
 * "unavailable", which fails in the safe direction: [lapackClassicalMds] is the slower path, not the
 * wrong one.
 */
private val arpackAvailable: Boolean by lazy {
    try {
        Class.forName("smile.linalg.arpack.arpack_h", true, MDS::class.java.classLoader)
        true
    } catch (unavailable: LinkageError) {
        false
    } catch (missing: ClassNotFoundException) {
        false
    }
}

/**
 * Classical MDS, from Smile where Smile can run and from LAPACK where it cannot.
 *
 * Prefers `smile.manifold.MDS.fit` so that a platform with a working ARPACK keeps ARPACK's
 * behaviour bit for bit, including its `O(n^2 d)` cost. The `catch` is a second line of defence
 * behind [arpackAvailable] and only fires if that probe was wrong.
 */
private fun fitClassicalMds(
    proximity: Array<DoubleArray>,
    dimensions: Int,
    positiveCorrection: Boolean,
): ClassicalMdsFit {
    if (arpackAvailable) {
        try {
            val model = MDS.fit(proximity, MDS.Options(dimensions, positiveCorrection))
            return ClassicalMdsFit(model.coordinates(), model.scores(), model.proportion())
        } catch (unavailable: LinkageError) {
            // fall through
        }
    }
    return lapackClassicalMds(proximity, dimensions, positiveCorrection)
}

/**
 * `smile.manifold.MDS.fit` with the one ARPACK call replaced by LAPACK's `dsyevd`.
 *
 * Deliberately a transcription rather than an improvement. Every step is Smile's, in Smile's order,
 * including the two details a fresh implementation would get differently:
 *
 * - the `positiveCorrection` branch rebuilds the learning matrix from `-0.5 (d + c)^2` and does
 *   **not** re-centre it, which is not what a textbook Cailliez correction does;
 * - `proportion` is the *retained* eigenvalues divided by their own L1 norm
 *   (`MathEx.unitize1`), so it sums to 1 whatever fraction of the structure was kept.
 *
 * Matching Smile matters more than either, because the point of this path is that a projection does
 * not change when the machine does.
 *
 * The cost difference is real and worth knowing: `dsyevd` computes **all** *n* eigenpairs of a dense
 * `n x n`, `O(n^3)`, where ARPACK computes only the *d* largest. Smile's own iterative path is the
 * better one to be on when it is available.
 */
private fun lapackClassicalMds(
    proximity: Array<DoubleArray>,
    dimensions: Int,
    positiveCorrection: Boolean,
): ClassicalMdsFit {
    val n = proximity.size
    var learning = doubleCentredGram(proximity)
    if (positiveCorrection) {
        val constant = additiveConstant(proximity, learning)
        learning = Array(n) { a ->
            DoubleArray(n) { b ->
                if (a == b) 0.0 else {
                    val shifted = proximity[a][b] + constant
                    -0.5 * shifted * shifted
                }
            }
        }
    }

    val matrix = DenseMatrix.zeros(ScalarType.Float64, n, n)
    for (a in 0 until n) for (b in 0 until n) matrix.set(a, b, learning[a][b])
    val symmetric = matrix.withUplo(UPLO.LOWER)
    val eigen = symmetric.eigen(false, true)
    val values = DoubleArray(n) { eigen.wr().get(it) }
    // Largest *algebraic*, which is what ARPACK.SymmOption.LA means. Not `EVD.sort`, which orders by
    // |lambda|^2 and would put a large negative eigenvalue — the signature of a non-Euclidean input —
    // at the front.
    val retained = values.indices.sortedByDescending { values[it] }.take(dimensions)
    val vectors = eigen.Vr()

    // The one deliberate divergence from Smile, which tests `eigen.wr().get(j) < 0` exactly. A
    // *rank-deficient* input — asking for as many dimensions as the configuration has structure —
    // puts a retained eigenvalue on the floating-point floor, and which side of zero it lands on is
    // noise: the 5x5 cosine matrix in `SpeechEmbeddingMdsTest` at `dimensions = 4` comes out of
    // `dsyevd` at -2.0e-16. An exact test turns that into "some of the first 4 eigenvalues are < 0",
    // which is a true statement about a double and a false one about the data. So the floor is
    // relative to the leading eigenvalue, and a component below it contributes a zero column — the
    // right answer for a direction that carries nothing. A materially negative eigenvalue, which is
    // what an uncorrected non-Euclidean matrix actually produces, still refuses.
    val floor = -1e-9 * max(1.0, abs(values[retained.first()]))
    val coordinates = Array(n) { DoubleArray(dimensions) }
    retained.forEachIndexed { column, index ->
        require(values[index] >= floor) {
            "smileMds: some of the first $dimensions eigenvalues are negative " +
                "(smallest retained ${values[index]}, against a leading ${values[retained.first()]}); " +
                "pass positiveCorrection = true, or reduce dimensions"
        }
        val scale = sqrt(max(0.0, values[index]))
        for (row in 0 until n) coordinates[row][column] = vectors.get(row, index) * scale
    }

    val scores = DoubleArray(dimensions) { values[retained[it]] }
    val proportion = scores.copyOf()
    val norm = proportion.sumOf { abs(it) }
    if (norm > 0.0) for (i in proportion.indices) proportion[i] /= norm
    return ClassicalMdsFit(coordinates, scores, proportion)
}

/** Smile's `MDS.getGram`: `-0.5 d^2`, double-centred. */
private fun doubleCentredGram(proximity: Array<DoubleArray>): Array<DoubleArray> {
    val n = proximity.size
    val squared = Array(n) { a ->
        DoubleArray(n) { b -> if (a == b) 0.0 else -0.5 * proximity[a][b] * proximity[a][b] }
    }
    val rowMeans = DoubleArray(n) { squared[it].average() }
    val grandMean = rowMeans.average()
    return Array(n) { a ->
        DoubleArray(n) { b -> squared[a][b] - rowMeans[a] - rowMeans[b] + grandMean }
    }
}

/**
 * Smile's additive constant: the largest real eigenvalue of a `2n x 2n` non-symmetric matrix built
 * from the learning matrix and the raw proximities.
 *
 * This one goes through LAPACK's `dgeev` in Smile itself — `DenseMatrix.eigen` only reaches ARPACK
 * for the symmetric case — so it is reused rather than transcribed.
 */
private fun additiveConstant(
    proximity: Array<DoubleArray>,
    learning: Array<DoubleArray>,
): Double {
    val n = proximity.size
    val z = DenseMatrix.zeros(ScalarType.Float64, 2 * n, 2 * n)
    for (a in 0 until n) for (b in 0 until n) z.set(a, n + b, 2.0 * learning[a][b])
    for (a in 0 until n) z.set(n + a, a, -1.0)
    val rowMeans = DoubleArray(n) { proximity[it].average() }
    val grandMean = rowMeans.average()
    for (a in 0 until n) for (b in 0 until n) {
        z.set(n + a, n + b, 2.0 * (proximity[a][b] - rowMeans[a] - rowMeans[b] + grandMean))
    }
    return z.eigen(false, false).wr().max()
}
