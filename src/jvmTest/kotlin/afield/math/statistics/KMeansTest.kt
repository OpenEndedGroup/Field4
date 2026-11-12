package afield.math.statistics

import afield.math.linalg.Vec2
import afield.math.linalg.Vec3
import afield.math.linalg.vec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * k-means in `clustering.kt`: Lloyd and e-means side by side, and the claims on the e-means page
 * (<https://e-means.github.io>) reproduced on seeded synthetic mixtures. Every measured number is
 * printed so the run log is the record; `dev_docs/KMEANS_EMEANS.md` copies them.
 */
class KMeansTest {

    /** [clusters] isotropic Gaussian blobs in the unit-10 cube, sizes from [weights]. */
    private class Mixture(val points: List<Vec3>, val centres: List<Vec3>)

    private fun mixture(
        n: Int,
        clusters: Int,
        seed: Long,
        grid: Double = 0.0,
        weights: (Int) -> Double = { 1.0 }
    ): Mixture {
        val rng = java.util.Random(seed)
        val centres = List(clusters) { vec(rng.nextDouble() * 10, rng.nextDouble() * 10, rng.nextDouble() * 10) }
        val sigmas = List(clusters) { 0.15 + rng.nextDouble() * 0.3 }
        val w = DoubleArray(clusters) { weights(it) }
        val cumulative = DoubleArray(clusters)
        var total = 0.0
        for (c in 0 until clusters) {
            total += w[c]
            cumulative[c] = total
        }
        val points = List(n) {
            val r = rng.nextDouble() * total
            var c = cumulative.indexOfFirst { it >= r }
            if (c < 0) c = clusters - 1
            val m = centres[c]
            val s = sigmas[c]
            fun q(v: Double) = if (grid > 0.0) kotlin.math.round(v / grid) * grid else v
            vec(q(m.x + rng.nextGaussian() * s), q(m.y + rng.nextGaussian() * s), q(m.z + rng.nextGaussian() * s))
        }
        return Mixture(points, centres)
    }

    /** Planted blobs with no centroid within [radius]: the visible failure of a bad k-means. */
    private fun missed(mix: Mixture, result: KMeansResult<Vec3>, radius: Double = 0.5) =
        mix.centres.count { c ->
            result.centroids.none { q ->
                val dx = (q.x - c.x).toDouble()
                val dy = (q.y - c.y).toDouble()
                val dz = (q.z - c.z).toDouble()
                dx * dx + dy * dy + dz * dz < radius * radius
            }
        }

    private fun dead(result: KMeansResult<*>, below: Int = 10) = result.clusterSizes.count { it < below }

    @Test
    fun lloydAndEmeansRecoverThreeBlobs() {
        val rng = java.util.Random(3)
        val centres = [vec(0.0, 0.0), vec(5.0, 0.0), vec(0.0, 5.0)]
        val points: List<Vec2> = centres.flatMap { c -> List(300) { vec(c.x + rng.nextGaussian() * 0.4, c.y + rng.nextGaussian() * 0.4) } }
        for (algorithm in KMeansAlgorithm.entries) {
            val r = vec2KMeans(points, 3, KMeansOptions(algorithm = algorithm, randomSeed = 5L))
            assertEquals([300, 300, 300], r.clusterSizes.sorted(), "$algorithm sizes")
            assertEquals(r.inertia / points.size, r.mseHistory.last(), 1e-9)
            assertEquals(3, r.asLayer().clusterCount)
        }
    }

    @Test
    fun seededRunsAreReproducible() {
        val values = List(2000) { (it % 7) * 1.0f + (it % 13) * 0.01f }
        val a = floatKMeans(values, 7, KMeansOptions(randomSeed = 9L))
        val b = floatKMeans(values, 7, KMeansOptions(randomSeed = 9L))
        assertContentEquals(a.labels, b.labels)
        assertEquals(a.centroids, b.centroids)
    }

    /**
     * The headline claim: e-means at 3 epochs below Lloyd at 25, on 100k points / 200 clusters.
     * Measured for both seedings, since Lloyd's weakness is mostly its seeds.
     */
    @Test
    fun emeansThreeEpochsBeatsLloydTwentyFive() {
        val mix = mixture(100_000, 200, seed = 42L)
        val points = mix.points
        for (seeding in KMeansSeeding.entries) {
            val t0 = System.nanoTime()
            val lloyd = vec3KMeans(points, 200, KMeansOptions(KMeansAlgorithm.LLOYD, epochs = 25, seeding = seeding, randomSeed = 1L))
            val t1 = System.nanoTime()
            val emeans = vec3KMeans(points, 200, KMeansOptions(KMeansAlgorithm.EMEANS, epochs = 3, seeding = seeding, randomSeed = 1L))
            val t2 = System.nanoTime()
            println(
                "KMEANS-CLAIM seeding=$seeding lloyd25 mse=%.6f epochs=%d converged=%s dead=%d missed=%d (%.2fs) | emeans3 mse=%.6f B=%d alpha0=%.4f revivals=%d dead=%d missed=%d (%.2fs) | ratio=%.4f"
                    .format(lloyd.mse, lloyd.epochs, lloyd.converged, dead(lloyd), missed(mix, lloyd), (t1 - t0) / 1e9,
                        emeans.mse, emeans.batchSize, emeans.alpha0, emeans.revivals, dead(emeans), missed(mix, emeans), (t2 - t1) / 1e9,
                        emeans.mse / lloyd.mse)
            )
            println("KMEANS-CLAIM seeding=$seeding lloyd history=" + lloyd.mseHistory.joinToString { "%.5f".format(it) })
            println("KMEANS-CLAIM seeding=$seeding emeans history=" + emeans.mseHistory.joinToString { "%.5f".format(it) })
            assertTrue(emeans.mse < lloyd.mse, "$seeding: e-means(3) ${emeans.mse} vs Lloyd(25) ${lloyd.mse}")
            // The page's sharper form of the claim: one e-means epoch already within 2% of Lloyd's 25.
            assertTrue(emeans.mseHistory[1] < lloyd.mse * 1.02, "$seeding: e-means after 1 epoch ${emeans.mseHistory[1]}")
        }
    }

    /**
     * Dead clusters. D² seeding loves far strays: on 50 blobs plus 20 strays 40 units out, k-means++
     * spends a seed on most strays, and each becomes a cluster of one that Lloyd never gives back,
     * while two real blobs share a centroid. e-means sees those clusters' EMA mass fall below a tenth
     * of the mean and revives them by splitting a heavy cluster.
     */
    @Test
    fun revivalRescuesDeadClusters() {
        val base = mixture(30_000, 50, seed = 7L)
        val rng = java.util.Random(11)
        val strays = List(20) {
            val a = rng.nextDouble() * 2 * Math.PI
            val b = rng.nextDouble() * Math.PI
            vec(5 + 40 * Math.sin(b) * Math.cos(a), 5 + 40 * Math.sin(b) * Math.sin(a), 5 + 40 * Math.cos(b))
        }
        val mix = Mixture(buildList { addAll(base.points); addAll(strays) }, base.centres)
        val opts = KMeansOptions(KMeansAlgorithm.EMEANS, epochs = 3, seeding = KMeansSeeding.PLUS_PLUS, randomSeed = 2L)
        val off = vec3KMeans(mix.points, 50, opts.copy(reviveThreshold = 0.0))
        val on = vec3KMeans(mix.points, 50, opts)
        val lloyd = vec3KMeans(mix.points, 50, opts.copy(algorithm = KMeansAlgorithm.LLOYD, epochs = 25))
        println(
            "KMEANS-REVIVAL off: mse=%.6f dead=%d missed=%d | on: mse=%.6f dead=%d missed=%d revivals=%d | lloyd25: mse=%.6f dead=%d missed=%d reseeds=%d"
                .format(off.mse, dead(off), missed(mix, off), on.mse, dead(on), missed(mix, on), on.revivals,
                    lloyd.mse, dead(lloyd), missed(mix, lloyd), lloyd.revivals)
        )
        assertTrue(on.revivals > 0, "revival should have fired")
        assertTrue(dead(on) < dead(off), "dead clusters, on: ${dead(on)}, off: ${dead(off)}")
        assertTrue(missed(mix, on) < missed(mix, lloyd), "missed blobs, e-means: ${missed(mix, on)}, Lloyd: ${missed(mix, lloyd)}")
    }

    /** [clusters] blobs in [d] dimensions, centres in the unit-10 hypercube, σ 0.3–0.8. */
    private fun highDimensional(n: Int, clusters: Int, d: Int, seed: Long): Pair<List<DoubleArray>, IntArray> {
        val rng = java.util.Random(seed)
        val centres = List(clusters) { DoubleArray(d) { rng.nextDouble() * 10 } }
        val truth = IntArray(n) { it % clusters }
        val vectors = List(n) { i ->
            val c = centres[truth[i]]
            val s = 0.3 + (truth[i] % 5) * 0.1
            DoubleArray(d) { j -> c[j] + rng.nextGaussian() * s }
        }
        return vectors to truth
    }

    /** Fraction of samples whose cluster's majority planted label is their own. */
    private fun purity(labels: IntArray, truth: IntArray, k: Int): Double {
        val counts = HashMap<Long, Int>()
        for (i in labels.indices) counts.merge(labels[i].toLong() * 1_000_003 + truth[i], 1, Int::plus)
        val best = IntArray(k)
        for ((key, c) in counts) {
            val label = (key / 1_000_003).toInt()
            if (c > best[label]) best[label] = c
        }
        return best.sum().toDouble() / labels.size
    }

    /**
     * `vectorKMeans` reaches past `Vec4`. At d = 32 e-means switches to the "throughput" batch law,
     * whose constant is 8× the "quality" one. This is also where plain k-means++ fails: with 40 blobs
     * the spread inside the blobs already seeded outweighs the blobs still missing, so every one of
     * four seeds leaves a blob unseeded and two blobs share a centroid. Greedy k-means++ (the default)
     * seeds every blob from every seed; `runs` restarts keep the best plain run.
     */
    @Test
    fun vectorKMeansClustersThirtyTwoDimensions() {
        val (vectors, truth) = highDimensional(20_000, 40, 32, seed = 21L)
        val copy = vectors.map { it.copyOf() }
        val quality = vectorKMeans(vectors.map { it.copyOf(16) }, 40, KMeansOptions(epochs = 3, randomSeed = 4L))
        for (algorithm in KMeansAlgorithm.entries) {
            val epochs = if (algorithm == KMeansAlgorithm.LLOYD) 25 else 3
            val greedy = (1L..4L).map { vectorKMeans(vectors, 40, KMeansOptions(algorithm, epochs, randomSeed = it)) }
            val plain = (1L..4L).map {
                vectorKMeans(vectors, 40, KMeansOptions(algorithm, epochs, seeding = KMeansSeeding.PLUS_PLUS, randomSeed = it))
            }
            val restarted = vectorKMeans(vectors, 40, KMeansOptions(algorithm, epochs, seeding = KMeansSeeding.PLUS_PLUS, randomSeed = 1L, runs = 4))
            println(
                "KMEANS-VECTOR d=32 $algorithm greedy mse=[%s] purity=[%s] | plain mse=[%s] purity=[%s] | plain runs=4 mse=%.5f | B=%d (d=16 B=%d)".format(
                    greedy.joinToString { "%.3f".format(it.mse) }, greedy.joinToString { "%.3f".format(purity(it.labels, truth, 40)) },
                    plain.joinToString { "%.3f".format(it.mse) }, plain.joinToString { "%.3f".format(purity(it.labels, truth, 40)) },
                    restarted.mse, greedy[0].batchSize, quality.batchSize
                )
            )
            greedy.forEach { assertTrue(purity(it.labels, truth, 40) > 0.99, "$algorithm greedy purity") }
            assertTrue(plain.any { purity(it.labels, truth, 40) < 0.99 }, "$algorithm: plain k-means++ no longer fails here; the comment is stale")
            // Run 1 of the restarted call draws the same seeds as plain[0], so keeping the best is never worse.
            assertTrue(restarted.inertia <= plain[0].inertia, "$algorithm runs keep the best")
            assertEquals(32, greedy[0].centroids[0].size)
            if (algorithm == KMeansAlgorithm.EMEANS) {
                assertTrue(greedy[0].batchSize > quality.batchSize, "throughput batch ${greedy[0].batchSize} vs quality ${quality.batchSize}")
            }
        }
        vectors.indices.forEach { assertContentEquals(copy[it], vectors[it], "input $it was modified") }
    }

    /**
     * Spherical k-means clusters by direction. Eight directions in 16-D, each sample scaled by a
     * random length from 0.2 to 5: plain k-means spends its clusters on length, spherical recovers
     * the directions, and its centroids are unit vectors.
     */
    @Test
    fun sphericalClustersByDirection() {
        val rng = java.util.Random(31)
        val d = 16
        val axes = List(8) { DoubleArray(d) { rng.nextGaussian() }.also { v -> val l = Math.sqrt(v.sumOf { it * it }); for (j in v.indices) v[j] /= l } }
        val truth = IntArray(8_000) { it % 8 }
        val vectors = List(truth.size) { i ->
            val length = 0.2 + rng.nextDouble() * 4.8
            DoubleArray(d) { j -> length * (axes[truth[i]][j] + rng.nextGaussian() * 0.08) }
        }
        for (algorithm in KMeansAlgorithm.entries) {
            val plain = vectorKMeans(vectors, 8, KMeansOptions(algorithm, randomSeed = 6L))
            val spherical = vectorKMeans(vectors, 8, KMeansOptions(algorithm, randomSeed = 6L, spherical = true))
            val p = purity(plain.labels, truth, 8)
            val q = purity(spherical.labels, truth, 8)
            println("KMEANS-SPHERICAL $algorithm plain purity=%.4f spherical purity=%.4f mse=%.5f".format(p, q, spherical.mse))
            assertTrue(q > 0.99, "$algorithm spherical purity $q")
            assertTrue(q > p, "$algorithm spherical $q vs plain $p")
            spherical.centroids.forEach { c -> assertEquals(1.0, Math.sqrt(c.sumOf { it * it }), 1e-9) }
        }
    }

    @Test
    fun sphericalRejectsZeroVectors() {
        val vectors = listOf(doubleArrayOf(1.0, 0.0), doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 1.0))
        val error = kotlin.runCatching { vectorKMeans(vectors, 2, KMeansOptions(spherical = true)) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException && "row 1" in error.message.orEmpty(), "got $error")
    }

    /** `centroidHistory` lines up with `mseHistory`: seeds first, the final centroids last. */
    @Test
    fun centroidHistoryMatchesMseHistory() {
        val mix = mixture(6_000, 12, seed = 3L)
        val points = mix.points.map { vec(it.x, it.y) }
        for (algorithm in KMeansAlgorithm.entries) {
            val r = vec2KMeans(points, 12, KMeansOptions(algorithm, epochs = 4, randomSeed = 8L, recordCentroids = true))
            assertEquals(r.mseHistory.size, r.centroidHistory.size, "$algorithm history lengths")
            assertEquals(r.epochs + 1, r.centroidHistory.size, "$algorithm entries")
            assertEquals(r.centroids, r.centroidHistory.last(), "$algorithm last entry")
            for (e in r.centroidHistory.indices) {
                val epoch = r.centroidHistory[e]
                val mse = points.sumOf { p -> epoch.minOf { c -> ((p.x - c.x) * (p.x - c.x) + (p.y - c.y) * (p.y - c.y)).toDouble() } } / points.size
                assertEquals(r.mseHistory[e], mse, 1e-4 * mse, "$algorithm epoch $e")
            }
        }
        assertTrue(vec2KMeans(points, 12, KMeansOptions(randomSeed = 8L)).centroidHistory.isEmpty())
    }

    /** A 2-D cloud like the docs page's: [blobs] blobs of width 0.08–0.30 in a 10-square. */
    private fun planeCloud(n: Int, blobs: Int, seed: Long): List<Vec2> {
        val rng = java.util.Random(seed)
        val c = List(blobs) { vec(rng.nextDouble() * 10, rng.nextDouble() * 10) }
        val w = List(blobs) { 0.08 + rng.nextDouble() * 0.22 }
        return List(n) { i ->
            val b = i % blobs
            vec(c[b].x + rng.nextGaussian() * w[b], c[b].y + rng.nextGaussian() * w[b])
        }
    }

    /**
     * The 2-D sweep behind the page's seeding and "is e-means a win in 2-D" paragraphs, cut down to
     * run in seconds: 5 000 and 20 000 points, 10 to 160 blobs, two clouds each. The full sweep
     * (to 80 000 points, three clouds) is in `dev_docs/KMEANS_EMEANS.md`.
     */
    @Test
    fun planeSweepGreedySeedingAndEmeans() {
        val greedyRatios = ArrayList<Double>()
        val emeansRatios = ArrayList<Double>()
        for (n in [5_000, 20_000]) for (k in [10, 40, 160]) for (seed in 1L..2L) {
            val points = planeCloud(n, k, seed * 17)
            val plain = KMeansOptions(seeding = KMeansSeeding.PLUS_PLUS, randomSeed = seed, recordHistory = false)
            val lloyd = vec2KMeans(points, k, plain.copy(algorithm = KMeansAlgorithm.LLOYD, epochs = 25))
            val emeans = vec2KMeans(points, k, plain.copy(epochs = 3))
            val greedy = vec2KMeans(points, k, plain.copy(epochs = 3, seeding = KMeansSeeding.GREEDY_PLUS_PLUS))
            greedyRatios.add(greedy.mse / emeans.mse)
            emeansRatios.add(emeans.mse / lloyd.mse)
            println("KMEANS-PLANE n=$n k=$k seed=$seed lloyd25=%.5f emeans3/lloyd=%.4f greedy/plain=%.4f".format(lloyd.mse, emeans.mse / lloyd.mse, greedy.mse / emeans.mse))
        }
        println("KMEANS-PLANE mean greedy/plain=%.4f mean emeans3/lloyd25=%.4f".format(greedyRatios.average(), emeansRatios.average()))
        assertTrue(greedyRatios.average() < 0.97, "greedy seeding should average at least 3% lower: ${greedyRatios.average()}")
        assertTrue(emeansRatios.average() < 1.02, "e-means(3) should average within 2% of Lloyd(25): ${emeansRatios.average()}")
    }

    /** `centroidPaths`: one point per update, aligned across centroids, seed first, final last. */
    @Test
    fun centroidPathsAreAlignedPerUpdate() {
        val points = planeCloud(4_000, 20, seed = 5L)
        val options = KMeansOptions(epochs = 2, randomSeed = 3L, recordPaths = true, recordCentroids = true)
        val r = vec2KMeans(points, 20, options)
        val steps = 2 * ((points.size + r.batchSize - 1) / r.batchSize)
        // Seeds, one per mini-batch, and the close on the mean.
        r.centroidPaths.forEach { assertEquals(steps + 2, it.size) }
        assertEquals(r.centroidHistory.first(), r.centroidPaths.map { it.first() })
        assertEquals(r.centroids, r.centroidPaths.map { it.last() })
        val open = vec2KMeans(points, 20, options.copy(closeOnMean = false))
        open.centroidPaths.forEach { assertEquals(steps + 1, it.size) }
        assertEquals(open.centroids, open.centroidPaths.map { it.last() })
        val lloyd = vec2KMeans(points, 20, options.copy(algorithm = KMeansAlgorithm.LLOYD, epochs = 5))
        lloyd.centroidPaths.forEach { assertEquals(lloyd.epochs + 1, it.size) }
        assertTrue(vec2KMeans(points, 20, KMeansOptions(randomSeed = 3L)).centroidPaths.isEmpty())
    }

    /** `stepScale` slows the walk: a tenth of the step travels less far in the first epoch. */
    @Test
    fun stepScaleSlowsTheWalk() {
        val points = planeCloud(4_000, 20, seed = 6L)
        val base = KMeansOptions(epochs = 1, randomSeed = 2L, recordPaths = true, closeOnMean = false)
        val full = vec2KMeans(points, 20, base)
        val slow = vec2KMeans(points, 20, base.copy(stepScale = 0.1))
        assertEquals(full.alpha0!! * 0.1, slow.alpha0!!, 1e-12)
        fun travelled(r: KMeansResult<Vec2>) = r.centroidPaths.sumOf { path ->
            (1 until path.size).sumOf { i -> Math.hypot((path[i].x - path[i - 1].x).toDouble(), (path[i].y - path[i - 1].y).toDouble()) }
        }
        fun displacement(r: KMeansResult<Vec2>) = r.centroidPaths.sumOf { path ->
            Math.hypot((path.last().x - path.first().x).toDouble(), (path.last().y - path.first().y).toDouble())
        }
        println("KMEANS-STEPSCALE full travelled=%.3f displaced=%.3f | slow travelled=%.3f displaced=%.3f".format(travelled(full), displacement(full), travelled(slow), displacement(slow)))
        assertTrue(displacement(slow) < displacement(full), "slow ${displacement(slow)} vs full ${displacement(full)}")
        assertTrue(kotlin.runCatching { vec2KMeans(points, 20, base.copy(stepScale = Double.NaN)) }.isFailure)
    }

    /** `vec2KMeansTree` halves the count per level down to one, each level clustering the last's centroids. */
    @Test
    fun kMeansTreeShrinksToOne() {
        val points = planeCloud(1_000, 12, seed = 9L)
        for (shrink in [2.0, 3.0]) {
            val tree = vec2KMeansTree(points, shrink, KMeansOptions(epochs = 3, randomSeed = 1L))
            val sizes = tree.levels.map { it.result.k }
            println("KMEANS-TREE shrink=$shrink sizes=$sizes")
            assertEquals(1, sizes.last())
            var n = points.size
            for (level in tree.levels) {
                assertEquals(n, level.inputs.size)
                assertEquals(maxOf(1, (n / shrink).toInt()), level.result.k)
                level.result.centroidPaths.forEach { assertTrue(it.size >= 2) }
                n = level.result.k
            }
            for (i in 1 until tree.levels.size) assertEquals(tree.levels[i - 1].result.centroids, tree.levels[i].inputs)
            assertTrue(tree.toFLines().size > tree.levels.sumOf { it.result.k } / 2)
        }
        assertEquals(3, vec2KMeansTree(points, maxLevels = 3).depth)
    }
}
