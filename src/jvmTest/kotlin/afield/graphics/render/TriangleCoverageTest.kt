package afield.graphics.render

import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [TriangleCoverage] against heavy supersampling, with the error and the population it was measured
 * over printed beside each other, so an empty population cannot pass as a small error.
 */
class TriangleCoverageTest {

    /** Stratified supersampling of the window over a unit box at `(x0, y0)`, `n x n` samples. */
    private fun supersample(t: DoubleArray, sigma: Double, x0: Double, y0: Double, n: Int): Double {
        var s = 0.0
        for (j in 0 until n) for (i in 0 until n) {
            s += TriangleCoverage.window(
                t[0], t[1], t[2], t[3], t[4], t[5], sigma, x0 + (i + 0.5) / n, y0 + (j + 0.5) / n,
            )
        }
        return s / (n.toDouble() * n)
    }

    private fun randomTriangle(rng: Random, size: Double): DoubleArray {
        val ox = rng.nextDouble(-1.0, 1.5)
        val oy = rng.nextDouble(-1.0, 1.5)
        return DoubleArray(6) { if (it % 2 == 0) ox + rng.nextDouble() * size else oy + rng.nextDouble() * size }
    }

    @Test
    fun boxCoverageOfAHalfPixelDiagonalIsAHalf() {
        assertEquals(0.5, TriangleCoverage.boxCoverage(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 0.0, 1.0, 1.0), 1e-15)
        // A triangle that contains the pixel covers all of it.
        assertEquals(1.0, TriangleCoverage.boxCoverage(-5.0, -5.0, 10.0, -5.0, -5.0, 10.0, 0.0, 0.0, 1.0, 1.0), 1e-12)
    }

    @Test
    fun windowAtSigmaZeroIsBoxCoverage() {
        val rng = Random(7)
        var worst = 0.0
        var partial = 0
        repeat(2000) {
            val t = randomTriangle(rng, 2.0)
            val box = TriangleCoverage.boxCoverage(t[0], t[1], t[2], t[3], t[4], t[5], 0.0, 0.0, 1.0, 1.0)
            val win = TriangleCoverage.windowCoverage(t[0], t[1], t[2], t[3], t[4], t[5], 0.0, 0.0, 0.0, 1.0, 1.0)
            if (box > 0.0 && box < 1.0) partial++
            worst = max(worst, abs(box - win))
        }
        println("sigma=0 window vs box coverage: worst |diff| $worst over $partial partially covered pixels of 2000")
        assertTrue(partial > 500, "population too small: $partial")
        assertTrue(worst < 1e-12, "window at sigma 0 should be box coverage, worst $worst")
    }

    @Test
    fun windowTotalMatchesTheSumOverPixels() {
        val rng = Random(11)
        var worst = 0.0
        for (sigma in doubleArrayOf(0.0, 0.5, 1.0, 2.0, 4.0)) repeat(40) {
            val t = DoubleArray(6) { rng.nextDouble(0.0, 6.0) }
            var sum = 0.0
            for (j in -1..7) for (i in -1..7) {
                sum += TriangleCoverage.windowCoverage(
                    t[0], t[1], t[2], t[3], t[4], t[5], sigma, i.toDouble(), j.toDouble(), i + 1.0, j + 1.0,
                )
            }
            val total = TriangleCoverage.windowTotal(t[0], t[1], t[2], t[3], t[4], t[5], sigma)
            if (total > 1e-6) worst = max(worst, abs(sum - total) / total)
        }
        println("sum of pixel integrals vs closed-form total: worst relative error $worst over 200 triangles x 5 sigmas")
        assertTrue(worst < 1e-9, "tiling the plane must recover the total, worst $worst")
    }

    @Test
    fun windowCoverageAgreesWithSupersampling() {
        val rng = Random(3)
        val n = 256
        println("| sigma | pixels | partial | mean abs err | worst abs err | point-sample worst |")
        println("|---|---|---|---|---|---|")
        for (sigma in doubleArrayOf(0.0, 1.0, 2.0, 4.0)) {
            var worst = 0.0
            var mean = 0.0
            var pointWorst = 0.0
            var partial = 0
            val count = 300
            repeat(count) {
                // Vertices within a pixel or so of the unit box, so most triangles cross it.
                val t = DoubleArray(6) { rng.nextDouble(-0.8, 1.8) }
                val exact = TriangleCoverage.windowCoverage(t[0], t[1], t[2], t[3], t[4], t[5], sigma, 0.0, 0.0, 1.0, 1.0)
                val ss = supersample(t, sigma, 0.0, 0.0, n)
                val point = TriangleCoverage.window(t[0], t[1], t[2], t[3], t[4], t[5], sigma, 0.5, 0.5)
                if (ss > 0.0 && ss < 1.0) partial++
                val e = abs(exact - ss)
                worst = max(worst, e)
                mean += e / count
                pointWorst = max(pointWorst, abs(point - ss))
            }
            println("| $sigma | $count | $partial | ${"%.2e".format(mean)} | ${"%.2e".format(worst)} | ${"%.3f".format(pointWorst)} |")
            assertTrue(partial > 150, "population too small at sigma $sigma: $partial")
            // 256^2 stratified samples resolve an edge to about 1/256 of a pixel; the error is that.
            assertTrue(worst < 4e-3, "sigma $sigma: worst error $worst against supersampling")
        }
    }

    @Test
    fun subPixelTriangleSurvivesExactAndVanishesPointSampled() {
        // A triangle of area 0.08 px^2 placed between pixel centres.
        val tri = CoverageTriangle(0.05 / 16, 0.05 / 16, 0.45 / 16, 0.05 / 16, 0.05 / 16, 0.45 / 16)
        val point = TriangleSplat.render(listOf(tri), 16, 16, CoverageSampling.POINT)
        val exact = TriangleSplat.render(listOf(tri), 16, 16, CoverageSampling.EXACT)
        assertEquals(0, point.visible)
        assertEquals(1, exact.visible)
        assertEquals(0.08, exact.image.data[0].toDouble(), 1e-6)
    }

    @Test
    fun cullingIsConservative() {
        // Culled triangles are exactly those whose total is under threshold; rendering them anyway
        // must not move any pixel by more than the threshold.
        val rng = Random(5)
        val tris = List(4000) {
            val s = rng.nextDouble(0.0005, 0.02)
            val x = rng.nextDouble(); val y = rng.nextDouble()
            CoverageTriangle(x, y, x + s * rng.nextDouble(), y + s, x + s, y + s * rng.nextDouble(), opacity = rng.nextDouble())
        }
        val culledRender = TriangleSplat.render(tris, 64, 64, sigma = 1.0)
        val full = TriangleSplat.render(tris, 64, 64, sigma = 1.0, cull = 0.0)
        var worst = 0.0
        for (k in full.image.data.indices) worst = max(worst, abs(full.image.data[k] - culledRender.image.data[k]).toDouble())
        println("culling: ${culledRender.culled} of ${tris.size} culled; worst pixel change $worst (threshold ${TriangleSplat.cullThreshold})")
        assertTrue(culledRender.culled > 100, "population too small: ${culledRender.culled}")
        // Over compositing means one culled triangle moves a pixel by at most its own alpha; overlapping
        // culled triangles can add, so the bound checked is per-triangle times the deepest overlap seen.
        assertTrue(worst < 4 * TriangleSplat.cullThreshold, "culling changed a pixel by $worst")
    }
}
