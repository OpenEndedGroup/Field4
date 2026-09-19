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

/**
 * Classical multidimensional scaling and t-SNE, over equal-length vectors or over a distance matrix
 * you computed yourself, wrapping Smile.
 *
 * Four entry points in two pairs. [smileMds] and [smileTsne] take an array of equal-length vectors
 * and build the dissimilarities for you, cosine or Euclidean by [SmileMdsDistance].
 * [smileMdsDistances] and [smileTsneDistances] take the symmetric matrix instead, which is the route
 * for an edit distance, a transport distance or a hop count, and carry a `label` you supply through
 * to the result. Each returns its own type, and in all four the *i*th row of `coordinates` is the
 * *i*th thing you passed in. `public/docs/projection_examples.md` works both routes end to end.
 *
 * Which of the two methods to reach for is the familiar split, and that page measures it on one
 * dataset: classical MDS reproduces the global distances and scrambles local neighbourhoods, t-SNE
 * the reverse.
 *
 * #### What all four do before Smile sees anything
 *
 * Values have to be finite; a supplied matrix has to be square, non-negative, symmetric to 1e-9 and
 * zero on the diagonal; `dimensions` has to be at least 2 and below the number of rows. The floor
 * of 2 is Smile's own, and the wrapper refuses first so the message names the argument you passed
 * rather than arriving from inside `MDS.Options`.
 *
 * Memory is quadratic in the number of points before any fitting starts, because the dissimilarity
 * matrix is dense: 191 MiB of `double` at 5000 points, 3 GiB at 20 000. Sample a long trajectory
 * rather than projecting all of it.
 *
 * #### The classical path has two eigensolvers
 *
 * `smile.manifold.MDS` takes its top-*d* eigenpairs from ARPACK, whose native library cannot be
 * loaded on macOS/arm64 in this build. So [smileMds] and [smileMdsDistances] probe that binding once
 * and, where it is missing, run a transcription of Smile's own algorithm over LAPACK's `dsyevd`:
 * the same steps in the same order, at `O(n^3)` for all *n* eigenpairs against ARPACK's `O(n^2 d)`
 * for the *d* largest. Signs are not canonicalised on either path, so a projection can come back
 * reflected from a machine that takes the other one. Neither t-SNE entry point touches ARPACK.
 *
 * #### What Smile does with three of the t-SNE settings
 *
 * Read off Smile 5.0.1, at the defaults these wrappers pass it.
 *
 * `maxIterations` is a ceiling rather than a count: past iteration 250 Smile stops as soon as the
 * best cost it has seen is more than 50 iterations old, or the gradient falls under 1e-7. That 250
 * is also why the wrappers require a `maxIterations` above it, since Smile's momentum switch is
 * fixed there and `TSNE.Options` refuses a `maxIter` that does not exceed it.
 *
 * Leave `earlyExaggeration` at 12.0. Smile symmetrises the affinity matrix with a hard-coded factor
 * of 12 and then divides by *this* value when the early phase ends, so any other setting leaves the
 * affinities scaled by `12 / earlyExaggeration` for the rest of the fit instead of changing only the
 * early phase.
 *
 * `seed` is installed in `smile.math.MathEx`'s process-wide random source, which both t-SNE entry
 * points do inside a lock. Two fits of the same input at one seed are therefore bit-identical, which
 * is what makes a live-coded box redraw the same picture, and concurrent fits queue rather than
 * overlap.
 */

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
    /**
     * One row per input vector, in the order they were passed, `dimensions` columns wide. The
     * configuration is centred on the origin and its axes are eigenvectors of the double-centred
     * matrix, so read distances off it rather than coordinates: the axes have no names and their
     * signs are not canonicalised.
     */
    val coordinates: Array<DoubleArray>,
    /**
     * The retained eigenvalues, largest first, one per column of [coordinates]. A value at or near
     * zero says that column carries nothing, which is how to find the rank of a configuration: ask
     * for one more dimension than you think it has and look at the last score.
     */
    val scores: DoubleArray,
    /**
     * [scores] normalised over themselves, so this sums to 1 whatever fraction of the structure was
     * kept. It says how the retained components divide up between each other and nothing about how
     * much was thrown away.
     */
    val proportion: DoubleArray,
    /** Which dissimilarity [smileMds] built the projection from, echoed back so a plot can be labelled from the result alone. */
    val distance: SmileMdsDistance,
    /**
     * Whether an additive constant was applied to the off-diagonal dissimilarities before fitting.
     * Echoed back, because it changes which matrix was decomposed: two projections are only
     * comparable when they agree on it.
     */
    val positiveCorrection: Boolean,
)

/** A reproducible t-SNE result produced by Smile from Field-owned vectors. */
data class SmileTsneProjection(
    /**
     * One row per input vector, in the order they were passed, `dimensions` columns wide, and
     * zero-mean by column because Smile subtracts the column means as its last step. What survives
     * here is which points are near which; the axes and the distance between two separated groups do
     * not, and `public/docs/projection_examples.md` measures the gap between the true ratio of two
     * cluster separations and the one a t-SNE plot of them shows.
     */
    val coordinates: Array<DoubleArray>,
    /**
     * Smile's Kullback-Leibler divergence between the input affinities and the projected ones, in
     * bits, as last measured rather than as minimised: the fit evaluates it every tenth iteration and
     * returns the final evaluation, not the best one it saw. It compares iterations within one fit
     * and does not rank two fits against each other.
     *
     * Two seeds can reach costs that agree to several decimals and pictures that share nothing, which
     * `public/docs/projection_examples.md` measures both halves of.
     */
    val cost: Double,
    /**
     * Which dissimilarity the vectors were reduced to before fitting. `EUCLIDEAN` hands Smile the
     * vectors and lets it compute distances itself; `COSINE` hands it a cosine-distance matrix Field
     * built first.
     */
    val distance: SmileMdsDistance,
    /**
     * The perplexity this fit ran at. A t-SNE plot cannot be read without it, since it is the dial
     * between keeping local neighbourhoods and keeping the distance between clusters, and it is
     * carried here so a caption can be written from the result.
     */
    val perplexity: Double,
    /**
     * The seed installed in Smile's process-wide random source for this fit. Two fits of the same
     * input at the same seed are bit-identical; two at different seeds are two equally good answers
     * that look nothing alike, so the seed belongs in the caption of a figure anyone else will read.
     */
    val seed: Long,
)

/** Classical MDS over a caller-supplied symmetric dissimilarity matrix. */
data class SmileMdsProximityProjection(
    /**
     * One row per row of the distance matrix, in its order, `dimensions` columns wide. These are
     * invented coordinates whose pairwise distances approximate the ones you supplied: for a matrix
     * that really is Euclidean and enough dimensions to hold it, they reproduce them exactly, and
     * `SmileMdsTest` pins that to 1e-9 over a rank-3 configuration.
     */
    val coordinates: Array<DoubleArray>,
    /**
     * The retained eigenvalues, largest first, one per column of [coordinates]. A value at or near
     * zero says that column carries nothing, which is how to find the rank of whatever the matrix
     * describes.
     */
    val scores: DoubleArray,
    /**
     * [scores] normalised over themselves, so this sums to 1 whatever fraction of the structure was
     * kept. For how much a two-dimensional picture actually lost, compare its distances against the
     * matrix you supplied; `public/docs/projection_examples.md` does that with a stress figure.
     */
    val proportion: DoubleArray,
    /**
     * Whatever you passed to [smileMdsDistances], carried through untouched. Nothing in the fit reads
     * it: it is here so a box holding several projections can tell which matrix each came from.
     */
    val label: String,
    /**
     * Whether an additive constant was applied to the off-diagonal dissimilarities before fitting.
     * A distance that is a metric is not thereby Euclidean, and an edit distance or a hop count
     * usually needs this on before classical MDS has more than a couple of usable dimensions.
     */
    val positiveCorrection: Boolean,
)

/** Exact t-SNE over a caller-supplied symmetric dissimilarity matrix. */
data class SmileTsneProximityProjection(
    /**
     * One row per row of the distance matrix, in its order, `dimensions` columns wide, and zero-mean
     * by column because Smile subtracts the column means as its last step. Near stays near; the axes
     * and the distance between two separated groups carry nothing.
     */
    val coordinates: Array<DoubleArray>,
    /**
     * Smile's Kullback-Leibler divergence between the supplied affinities and the projected ones, in
     * bits, as last measured rather than as minimised: the fit evaluates it every tenth iteration and
     * returns the final evaluation, not the best one it saw. Useful within a fit, not between two.
     */
    val cost: Double,
    /**
     * Whatever you passed to [smileTsneDistances], carried through untouched and read by nothing in
     * the fit.
     */
    val label: String,
    /**
     * The perplexity this fit ran at, which is the dial between keeping local neighbourhoods and
     * keeping the distance between clusters. A t-SNE plot cannot be read without it.
     */
    val perplexity: Double,
    /**
     * The seed installed in Smile's process-wide random source for this fit. Two fits of the same
     * matrix at the same seed are bit-identical; two at different seeds are two equally good answers
     * that look nothing alike.
     */
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
 * Matching Smile matters more than either, so that swapping eigensolvers moves as little as it can.
 * It does NOT make a projection machine-independent: signs are not canonicalised (see below).
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
