package afield.stdlib

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.math.abs
import kotlin.math.sqrt

class SmileMdsTest {
    @Test
    fun projectsVectorsWithoutChangingRowOrderOrShape() {
        val vectors = Array(12) { row ->
            DoubleArray(6) { column ->
                kotlin.math.sin((row + 1.0) * (column + 1.0) * 0.31) +
                    kotlin.math.cos(row * 0.17 + column * 0.43)
            }
        }
        val projection = smileMds(vectors, dimensions = 4)

        assertEquals(12, projection.coordinates.size)
        assertTrue(projection.coordinates.all { it.size == 4 })
        assertTrue(projection.coordinates.all { row -> row.all(Double::isFinite) })
        assertTrue(projection.scores.isNotEmpty())
        assertEquals(SmileMdsDistance.COSINE, projection.distance)

        assertTrue(projection.coordinates.any { row -> row.any { kotlin.math.abs(it) > 1e-6 } })
    }

    @Test
    fun rejectsAZeroVectorForCosineDistance() {
        assertFailsWith<IllegalArgumentException> {
            smileMds(arrayOf(doubleArrayOf(1.0, 0.0), doubleArrayOf(0.0, 0.0)))
        }
    }

    @Test
    fun projectsAnExplicitDistanceMatrix() {
        val points = doubleArrayOf(0.0, 0.2, 0.7, 1.0, 1.8)
        val distances = Array(points.size) { a ->
            DoubleArray(points.size) { b -> kotlin.math.abs(points[a] - points[b]) }
        }
        val projection = smileMdsDistances(
            distances, dimensions = 2, label = "earth mover", positiveCorrection = false,
        )
        assertEquals("earth mover", projection.label)
        assertEquals(points.size, projection.coordinates.size)
        assertTrue(projection.coordinates.all { row -> row.size == 2 && row.all(Double::isFinite) })
    }

    @Test
    fun tsneIsFiniteAndReproducibleForAFixedSeed() {
        val vectors = Array(30) { row ->
            DoubleArray(8) { column ->
                kotlin.math.sin(row * 0.19 + column * 0.37) +
                    kotlin.math.cos((row + 1.0) * (column + 1.0) * 0.07)
            }
        }
        fun fit() = smileTsne(
            vectors,
            dimensions = 4,
            perplexity = 5.0,
            maxIterations = 300,
            seed = 17L,
        )

        val first = fit()
        val second = fit()
        assertEquals(30, first.coordinates.size)
        assertTrue(first.coordinates.all { row -> row.size == 4 && row.all(Double::isFinite) })
        assertTrue(first.cost.isFinite())
        first.coordinates.indices.forEach { row ->
            first.coordinates[row].indices.forEach { column ->
                assertEquals(first.coordinates[row][column], second.coordinates[row][column], 1e-12)
            }
        }
    }

    /**
     * The oracle for the ARPACK-free path: classical MDS on a **Euclidean** distance matrix must
     * reproduce the configuration it was built from, so every pairwise distance has to come back.
     *
     * This is the only check here that does not take Smile's word for anything. `smile.manifold.MDS`
     * reaches for `ARPACK.syev`, whose native library cannot be loaded on macOS/arm64 (see
     * `smile_mds.kt`, `arpackAvailable`), so on the platform this repository is developed on the
     * whole call used to throw `NoClassDefFoundError` for every input. The LAPACK fallback that
     * replaced it is a transcription of Smile's algorithm, and a transcription needs an external
     * measure of correctness rather than a shape assertion. Recovering 300 distances to 1e-9 is that
     * measure: the eigendecomposition, the double-centring, the ordering and the `sqrt(lambda)`
     * scaling all have to be right at once for it to hold.
     */
    @Test
    fun classicalMdsReconstructsAEuclideanConfiguration() {
        val truth = Array(25) { row ->
            doubleArrayOf(
                kotlin.math.sin(row * 0.7),
                kotlin.math.cos(row * 0.31) * 2.0,
                row / 9.0,
            )
        }
        fun distance(a: DoubleArray, b: DoubleArray): Double {
            var squared = 0.0
            for (c in a.indices) {
                val delta = a[c] - b[c]
                squared += delta * delta
            }
            return sqrt(squared)
        }
        val input = Array(truth.size) { a -> DoubleArray(truth.size) { b -> distance(truth[a], truth[b]) } }

        // Three dimensions is exactly the rank of the configuration, and no additive constant: the
        // matrix is already Euclidean, so correcting it would be correcting a correct thing.
        val projection = smileMdsDistances(input, dimensions = 3, positiveCorrection = false)

        var worst = 0.0
        for (a in truth.indices) for (b in 0 until a) {
            worst = kotlin.math.max(
                worst,
                abs(distance(projection.coordinates[a], projection.coordinates[b]) - input[a][b]),
            )
        }
        assertTrue(worst < 1e-9, "worst reconstructed-distance error was $worst")

        // The fourth eigenvalue is the rank test: a rank-3 configuration has nothing in it.
        val four = smileMdsDistances(input, dimensions = 4, positiveCorrection = false)
        assertTrue(
            four.scores[3] < 1e-9 * four.scores[0],
            "a rank-3 configuration should have no fourth component; got ${four.scores.toList()}",
        )
    }

    /**
     * All four entry points advertised `dimensions >= 1` and none of them accepted it.
     *
     * `MDS.Options` and `TSNE.Options` both throw `Invalid dimension of feature space: 1` for
     * `d < 2` (Smile 5.0.1), so a caller who read the `require` message got a different exception
     * with a different message from inside Smile. The floor now says 2 in all four places.
     */
    @Test
    fun oneDimensionalProjectionsAreRefusedByTheWrapperNotBySmile() {
        val vectors = Array(8) { row -> DoubleArray(4) { column -> kotlin.math.sin(row * 0.3 + column) + 2.0 } }
        val distances = Array(8) { a -> DoubleArray(8) { b -> abs(a - b).toDouble() } }

        listOf(
            { smileMds(vectors, dimensions = 1) },
            { smileMdsDistances(distances, dimensions = 1) },
            { smileTsne(vectors, dimensions = 1, perplexity = 3.0) },
            { smileTsneDistances(distances, dimensions = 1, perplexity = 3.0) },
        ).forEachIndexed { index, call ->
            val refusal = assertFailsWith<IllegalArgumentException>("entry point $index accepted dimensions = 1") {
                call()
            }
            assertTrue(
                refusal.message.orEmpty().contains("between 2 and"),
                "entry point $index refused with Smile's message rather than ours: ${refusal.message}",
            )
        }
    }

    /**
     * `proportion` is normalised over the **retained** components only, so it always sums to 1.
     *
     * Smile runs `MathEx.unitize1` over the `d` eigenvalues it kept, not over the whole spectrum
     * (`MDS.java`, `fit`). That makes it useless as an "how much did I keep" measure — two dimensions
     * of a twenty-point matrix and fifteen dimensions of the same matrix both report a proportion
     * summing to 1 — and the wrapper's own KDoc calling it "their explained proportion" invites
     * exactly that misreading. Pinned here because `public/docs/projection_examples.md` publishes it.
     */
    @Test
    fun proportionIsNormalisedOverTheRetainedComponentsOnly() {
        val points = DoubleArray(20) { kotlin.math.sin(it * 0.37) * 4.0 }
        val distances = Array(20) { a -> DoubleArray(20) { b -> abs(points[a] - points[b]) } }
        listOf(2, 5, 15).forEach { dimensions ->
            val projection = smileMdsDistances(distances, dimensions = dimensions)
            assertEquals(dimensions, projection.proportion.size)
            assertEquals(1.0, projection.proportion.sum(), 1e-12, "dimensions = $dimensions")
        }
    }
}
