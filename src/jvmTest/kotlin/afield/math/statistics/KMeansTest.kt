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
}
