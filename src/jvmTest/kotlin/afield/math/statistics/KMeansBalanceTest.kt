package afield.math.statistics

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cluster balance in `clustering.kt`: the [KMeansResult.imbalance] readout, how the e-means revival
 * threshold moves it in many dimensions, and the exact partial-distance pruning in the assignment
 * step. Prompted by Leonardo Kuffo's "SuperEMeans: Making e-means 3x faster"
 * (<https://www.lkuffo.com/superemeans/>). Every measured number is printed with a `KMEANS-BALANCE`
 * prefix so the run log is the record; `dev_docs/KMEANS_EMEANS.md` copies them.
 */
class KMeansBalanceTest {

    /**
     * The two measurements take about twelve minutes together, so they run only when
     * `AFIELD_KMEANS_BENCH` is set; the correctness tests always run.
     */
    private val bench = System.getenv("AFIELD_KMEANS_BENCH") != null

    /**
     * Embedding-like vectors: [blobs] latent centres with Zipf-like weights, each sample a centre
     * plus isotropic noise as large as the spread of the centres, so the clusters overlap and `k`
     * larger than [blobs] has to cut blobs up, which is where balance is decided.
     */
    private fun embeddings(n: Int, d: Int, blobs: Int, seed: Long, noise: Double = 1.0): List<DoubleArray> {
        val rng = java.util.Random(seed)
        val centres = List(blobs) { DoubleArray(d) { rng.nextGaussian() } }
        val weights = DoubleArray(blobs) { 1.0 / (1.0 + it) }
        val total = weights.sum()
        return List(n) {
            var r = rng.nextDouble() * total
            var b = 0
            while (b < blobs - 1 && r > weights[b]) {
                r -= weights[b]
                b++
            }
            val c = centres[b]
            DoubleArray(d) { j -> c[j] + rng.nextGaussian() * noise }
        }
    }

    @Test
    fun imbalanceIsOneWhenBalancedAndGrowsWithSkew() {
        val rows = List(8) { i -> doubleArrayOf(i * 10.0) }
        val balanced = vectorKMeans(rows, 4, KMeansOptions(KMeansAlgorithm.LLOYD),
            seeds = [doubleArrayOf(5.0), doubleArrayOf(25.0), doubleArrayOf(45.0), doubleArrayOf(65.0)])
        assertContentEquals(intArrayOf(2, 2, 2, 2), balanced.clusterSizes)
        assertEquals(1.0, balanced.imbalance, 1e-12)
        assertEquals(2, balanced.largestCluster)
        assertEquals(2.0, balanced.meanClusterSize, 1e-12)
        // Sizes 5,1,1,1 of 8: 4 * (25 + 3) / 64 = 1.75.
        val lopsided = [0.0, 0.0, 0.0, 0.0, 0.0, 100.0, 200.0, 300.0].map { doubleArrayOf(it) }
        val skewed = vectorKMeans(lopsided, 4, KMeansOptions(KMeansAlgorithm.LLOYD),
            seeds = [doubleArrayOf(0.0), doubleArrayOf(100.0), doubleArrayOf(200.0), doubleArrayOf(300.0)])
        assertContentEquals(intArrayOf(5, 1, 1, 1), skewed.clusterSizes)
        assertEquals(1.75, skewed.imbalance, 1e-12)
        assertEquals(5, skewed.largestCluster)
    }

    /** Pruning must change nothing: same labels, centroids and inertia, for both algorithms. */
    @Test
    fun partialDistancePruningGivesIdenticalResults() {
        val vectors = embeddings(4_000, 96, 20, seed = 2L)
        for (algorithm in KMeansAlgorithm.entries) {
            val options = KMeansOptions(algorithm, epochs = 4, randomSeed = 7L)
            val pruned = vectorKMeans(vectors, 30, options)
            val full = try {
                kMeansPartialDistancePruning = false
                vectorKMeans(vectors, 30, options)
            } finally {
                kMeansPartialDistancePruning = true
            }
            assertContentEquals(full.labels, pruned.labels, "$algorithm labels")
            assertEquals(full.inertia, pruned.inertia, "$algorithm inertia")
            assertContentEquals(full.mseHistory, pruned.mseHistory, "$algorithm history")
            for (c in 0 until 30) assertContentEquals(full.centroids[c], pruned.centroids[c], "$algorithm centroid $c")
        }
    }

    /**
     * The post's claim, on our side: at the default threshold 0.1 e-means ends less balanced than
     * Lloyd in many dimensions, and 0.3 narrows the gap. Measured over three seeds; asserted only on
     * the mean, so a single seed going the other way does not fail it.
     */
    @Test
    fun revivalThresholdAndBalanceInManyDimensions() {
        if (!bench) return
        val k = 200
        val thresholds = [0.0, 0.1, 0.3, 0.5]
        val imb = HashMap<String, MutableList<Double>>()
        val mse = HashMap<String, MutableList<Double>>()
        for (d in [2, 16, 128]) for (seed in 1L..3L) {
            val vectors = embeddings(40_000, d, 40, seed = 100L + seed)
            fun record(name: String, r: KMeansResult<DoubleArray>, seconds: Double) {
                imb.getOrPut("d=$d $name") { mutableListOf() }.add(r.imbalance)
                mse.getOrPut("d=$d $name") { mutableListOf() }.add(r.mse)
                println(
                    "KMEANS-BALANCE d=$d seed=$seed $name mse=%.5f imbalance=%.4f largest=%d mean=%.0f under10=%d revivals=%d (%.2fs)"
                        .format(r.mse, r.imbalance, r.largestCluster, r.meanClusterSize, r.clusterSizes.count { it < 10 }, r.revivals, seconds)
                )
            }
            var t = System.nanoTime()
            val lloyd = vectorKMeans(vectors, k, KMeansOptions(KMeansAlgorithm.LLOYD, epochs = 10, randomSeed = seed, recordHistory = false))
            record("lloyd10", lloyd, (System.nanoTime() - t) / 1e9)
            for (tau in thresholds) {
                t = System.nanoTime()
                val r = vectorKMeans(vectors, k, KMeansOptions(epochs = 5, randomSeed = seed, reviveThreshold = tau, recordHistory = false))
                record("emeans5 tau=$tau", r, (System.nanoTime() - t) / 1e9)
            }
        }
        for (key in imb.keys.sorted()) {
            println("KMEANS-BALANCE mean %s imbalance=%.4f mse=%.5f".format(key, imb.getValue(key).average(), mse.getValue(key).average()))
        }
        val i01 = imb.getValue("d=128 emeans5 tau=0.1").average()
        val i03 = imb.getValue("d=128 emeans5 tau=0.3").average()
        assertTrue(i03 <= i01 * 1.02, "d=128: tau 0.3 imbalance $i03 vs tau 0.1 $i01")
    }

    /**
     * Runtime of the exact pruning at 1024 dimensions. MSE is equal by construction (the results
     * are identical, checked here too), so the comparison is time alone. Best of three, after a
     * warm-up run.
     */
    @Test
    fun partialDistancePruningAt1024Dimensions() {
        if (!bench) return
        val vectors = embeddings(20_000, 1024, 50, seed = 9L, noise = 0.5)
        for (algorithm in KMeansAlgorithm.entries) {
            val options = KMeansOptions(algorithm, epochs = if (algorithm == KMeansAlgorithm.LLOYD) 10 else 5, randomSeed = 3L, recordHistory = false)
            fun timed(prune: Boolean): Pair<Double, KMeansResult<DoubleArray>> {
                kMeansPartialDistancePruning = prune
                try {
                    var best = Double.POSITIVE_INFINITY
                    var result: KMeansResult<DoubleArray>? = null
                    repeat(3) {
                        val t = System.nanoTime()
                        result = vectorKMeans(vectors, 100, options)
                        best = minOf(best, (System.nanoTime() - t) / 1e9)
                    }
                    return best to result!!
                } finally {
                    kMeansPartialDistancePruning = true
                }
            }
            timed(true)
            val (full, a) = timed(false)
            val (pruned, b) = timed(true)
            assertContentEquals(a.labels, b.labels)
            println(
                "KMEANS-BALANCE prune d=1024 n=20000 k=100 $algorithm epochs=${a.epochs} mse=%.5f full=%.2fs pruned=%.2fs speedup=%.2fx"
                    .format(b.mse, full, pruned, full / pruned)
            )
        }
    }
}
