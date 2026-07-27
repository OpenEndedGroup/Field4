package afield.stdlib

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Known-answer coverage for the rank-based members of [StatsDependence] ([correlations.kt]).
 *
 * These exist because `ranksAverageTies` and the private `orderRanks` helper inside `micTicNaive`
 * both used to call `sortedWith` and throw the returned list away, leaving the index array in
 * identity order. The "ranks" were then just positions, so `spearman` answered ≈1.0 for any
 * tie-free input and `micTicNaive` reported a MIC of ≈1.0 for anything at all. Every assertion
 * below that pins a value away from 1.0 would have failed against that.
 */
class StatsDependenceTest {

    private val eps = 1e-12

    // -------------------------
    // ranksAverageTies
    // -------------------------

    @Test
    fun `ranks of an already-sorted array are 1 through n`() {
        val ranks = StatsDependence.ranksAverageTies(doubleArrayOf(10.0, 20.0, 30.0, 40.0))
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0), ranks.toList())
    }

    @Test
    fun `ranks reflect value order, not position`() {
        // the pre-fix implementation returned 1,2,3,4,5 here — the identity permutation
        val ranks = StatsDependence.ranksAverageTies(doubleArrayOf(30.0, 10.0, 40.0, 20.0, 50.0))
        assertEquals(listOf(3.0, 1.0, 4.0, 2.0, 5.0), ranks.toList())
    }

    @Test
    fun `tied values share their average rank`() {
        // sorted: 1(idx1) 1(idx3) 3(idx0) 4(idx2) 5(idx4)
        // the two 1s occupy positions 1 and 2, so both take (1+2)/2 = 1.5
        val ranks = StatsDependence.ranksAverageTies(doubleArrayOf(3.0, 1.0, 4.0, 1.0, 5.0))
        assertEquals(listOf(3.0, 1.5, 4.0, 1.5, 5.0), ranks.toList())
    }

    @Test
    fun `a non-adjacent run of ties is averaged across all its members`() {
        // three 7s at positions 2,3,4 (1-based) -> 3.0 each; hand-computed
        val ranks = StatsDependence.ranksAverageTies(doubleArrayOf(7.0, 9.0, 5.0, 7.0, 7.0))
        assertEquals(listOf(3.0, 5.0, 1.0, 3.0, 3.0), ranks.toList())
    }

    @Test
    fun `all values tied gives every element the same middle rank`() {
        val ranks = StatsDependence.ranksAverageTies(doubleArrayOf(2.0, 2.0, 2.0, 2.0))
        assertEquals(listOf(2.5, 2.5, 2.5, 2.5), ranks.toList())
    }

    @Test
    fun `ranks are a permutation summing to n times n plus one over two`() {
        val values = doubleArrayOf(0.4, -3.0, 11.5, 2.0, 2.0, -7.25, 8.0, 0.0)
        val n = values.size
        val ranks = StatsDependence.ranksAverageTies(values)
        assertEquals(n * (n + 1) / 2.0, ranks.sum(), 1e-9)
    }

    // -------------------------
    // spearman
    // -------------------------

    @Test
    fun `strictly increasing against strictly increasing is plus one`() {
        val x = DoubleArray(12) { it.toDouble() }
        val y = DoubleArray(12) { it * 3.0 + 1.0 }
        assertEquals(1.0, StatsDependence.spearman(x, y), 1e-12)
    }

    @Test
    fun `strictly increasing against strictly decreasing is minus one`() {
        // the headline regression: pre-fix this answered +1.0
        val n = 12
        val x = DoubleArray(n) { it.toDouble() }
        val y = DoubleArray(n) { (n - it).toDouble() }
        assertEquals(-1.0, StatsDependence.spearman(x, y), 1e-12)
    }

    @Test
    fun `a monotone but nonlinear pair is plus one for spearman and less for pearson`() {
        val n = 20
        val x = DoubleArray(n) { it + 1.0 }
        val y = DoubleArray(n) { (it + 1.0) * (it + 1.0) * (it + 1.0) }
        assertEquals(1.0, StatsDependence.spearman(x, y), 1e-12)
        assertTrue(
            StatsDependence.pearson(x, y) < 0.95,
            "pearson should be pulled below 1 by the cubic: ${StatsDependence.pearson(x, y)}"
        )
    }

    @Test
    fun `a shuffled pair lands strictly between minus one and plus one`() {
        // x = 1..5, y = 3,1,4,5,2 -> both already rank vectors; pearson of those is exactly 0.2
        val x = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val y = doubleArrayOf(3.0, 1.0, 4.0, 5.0, 2.0)
        val rho = StatsDependence.spearman(x, y)
        assertEquals(0.2, rho, 1e-12)
        assertTrue(rho > -1.0 && rho < 1.0, "expected strictly inside (-1,1), got $rho")
    }

    @Test
    fun `spearman with ties matches the hand-computed average ranks`() {
        // x ranks 1,2,3,4 ; y ranks 1,2.5,2.5,4 -> pearson of those is sqrt(0.9)
        val x = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        val y = doubleArrayOf(10.0, 20.0, 20.0, 30.0)
        assertEquals(sqrt(0.9), StatsDependence.spearman(x, y), 1e-12)
    }

    @Test
    fun `spearman is near zero on a symmetric parabola`() {
        // non-monotone dependence: the rank correlation should not see it at all
        val n = 401
        val x = DoubleArray(n) { (it - n / 2).toDouble() / (n / 2).toDouble() }
        val y = DoubleArray(n) { x[it] * x[it] }
        val rho = StatsDependence.spearman(x, y)
        assertTrue(abs(rho) < 1e-9, "symmetric parabola should give spearman ≈ 0, got $rho")
    }

    @Test
    fun `spearman agrees with pearson when both inputs are already ranks`() {
        val x = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0)
        val y = doubleArrayOf(2.0, 1.0, 4.0, 3.0, 6.0, 5.0)
        assertEquals(StatsDependence.pearson(x, y), StatsDependence.spearman(x, y), eps)
    }

    @Test
    fun `spearman is symmetric in its arguments`() {
        val x = doubleArrayOf(5.0, 1.0, 4.0, 4.0, 9.0, -2.0, 0.5)
        val y = doubleArrayOf(2.0, 8.0, 8.0, 1.0, 3.0, 7.0, -1.0)
        assertEquals(StatsDependence.spearman(x, y), StatsDependence.spearman(y, x), eps)
    }

    // -------------------------
    // micTicNaive
    // -------------------------

    /** Deterministic LCG so the "unrelated" case is reproducible without a test-order dependency. */
    private fun lcg(n: Int, seed: Long = 987654321L): DoubleArray {
        var s = seed
        return DoubleArray(n) {
            s = (s * 6364136223846793005L + 1442695040888963407L)
            ((s ushr 11).toDouble() / (1L shl 53).toDouble())
        }
    }

    @Test
    fun `mic is high for an exact monotone relationship`() {
        val n = 240
        val x = DoubleArray(n) { it.toDouble() }
        val y = DoubleArray(n) { it * 2.0 }
        assertTrue(
            StatsDependence.micTicNaive(x, y).mic > 0.99,
            "y = 2x should be essentially MIC 1"
        )
    }

    @Test
    fun `mic is far below one for unrelated series`() {
        // pre-fix this returned ~1.0: every grid saw the identity permutation on both axes
        val n = 240
        val x = DoubleArray(n) { it.toDouble() }
        val y = lcg(n)
        val result = StatsDependence.micTicNaive(x, y)
        assertTrue(
            result.mic < 0.5,
            "independent noise should not look like a perfect relationship: mic=${result.mic}"
        )
        assertTrue(result.tic >= 0.0, "tic should be a non-negative sum of clipped scores")
    }

    @Test
    fun `mic recovers a nonlinear relationship that pearson misses`() {
        val n = 240
        val x = DoubleArray(n) { (it - n / 2).toDouble() / (n / 2).toDouble() }
        val y = DoubleArray(n) { x[it] * x[it] }
        val mic = StatsDependence.micTicNaive(x, y).mic
        assertTrue(abs(StatsDependence.pearson(x, y)) < 0.1, "pearson should shrug at the parabola")
        assertTrue(mic > 0.5, "mic should see the parabola: $mic")
    }

    // -------------------------
    // the unaffected members, as cross-checks on the fixed ones
    // -------------------------

    @Test
    fun `kendall and spearman agree in sign on monotone data`() {
        val n = 30
        val x = DoubleArray(n) { it.toDouble() }
        val down = DoubleArray(n) { (n - it).toDouble() }
        assertEquals(-1.0, StatsDependence.kendallTauB(x, down), 1e-12)
        assertEquals(-1.0, StatsDependence.spearman(x, down), 1e-12)
    }

    @Test
    fun `the five batch metrics disagree on a parabola in the documented direction`() {
        // this is the shape public/docs/interpolants_examples.md plots: pearson and spearman shrug,
        // xi and dcor notice. Guards the published capture's story.
        val n = 320
        val x = DoubleArray(n) { it / (n - 1.0) * 2.0 - 1.0 }
        val y = DoubleArray(n) { x[it] * x[it] }

        assertTrue(abs(StatsDependence.pearson(x, y)) < 0.1)
        assertTrue(abs(StatsDependence.spearman(x, y)) < 0.1)
        assertTrue(abs(StatsDependence.kendallTauB(x, y)) < 0.1)
        assertTrue(StatsDependence.xi(x, y) > 0.5)
        assertTrue(StatsDependence.distanceCorrelation(x, y) > 0.4)
    }
}
