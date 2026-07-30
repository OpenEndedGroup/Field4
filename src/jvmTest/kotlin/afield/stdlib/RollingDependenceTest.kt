package afield.stdlib

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Coverage for [RollingDistanceCorrelation], [RollingXiCorrelation] and
 * [StatsDependence.distanceCorrelation], none of which had any.
 *
 * The load-bearing test is [rolling distance correlation agrees with the batch call], and it is a
 * genuine cross-check rather than an identity: [RollingDistanceCorrelation] only ever adds to and
 * subtracts from maintained row/grand/cross sums (`correlations.kt:689`, `correlations.kt:742`),
 * while [StatsDependence.distanceCorrelation] materialises two full n×n distance matrices and
 * double-centres them (`correlations.kt:334`). Two different algorithms, one answer. That the guard
 * can fire is pinned by [comparing against the wrong window does disagree] — otherwise a tolerance
 * this tight would be evidence of a tautology rather than of correctness.
 *
 * The reference dCor value in [distance correlation matches an independent NumPy implementation]
 * came from a from-scratch NumPy transcription of the Székely–Rizzo double-centred estimator run
 * outside the JVM.
 */
class RollingDependenceTest {

    private fun gaussians(n: Int, seed: Long): DoubleArray {
        val rng = java.util.Random(seed)
        return DoubleArray(n) { rng.nextGaussian() }
    }

    // -------------------------
    // distanceCorrelation known answers
    // -------------------------

    @Test
    fun `distance correlation is exactly one for any straight line and unsigned`() {
        val n = 200
        val x = DoubleArray(n) { it.toDouble() }
        assertEquals(1.0, StatsDependence.distanceCorrelation(x, DoubleArray(n) { 3.0 * it + 1.0 }), 1e-12)
        assertEquals(1.0, StatsDependence.distanceCorrelation(x, DoubleArray(n) { -2.0 * it + 5.0 }), 1e-12)
        assertEquals(1.0, StatsDependence.distanceCorrelation(x, x), 1e-12)
        // ...and affine rescaling of either argument changes nothing
        assertEquals(
            1.0,
            StatsDependence.distanceCorrelation(
                DoubleArray(n) { 2.0 * x[it] + 7.0 },
                DoubleArray(n) { -0.5 * x[it] - 11.0 },
            ),
            1e-12,
        )
    }

    @Test
    fun `distance correlation matches an independent NumPy implementation`() {
        val x = doubleArrayOf(1.0, 2.0, 2.0, 4.0, 5.0, 6.0, 6.0, 6.0, 9.0, 10.0)
        val y = doubleArrayOf(3.0, 1.0, 4.0, 1.0, 5.0, 9.0, 2.0, 6.0, 5.0, 3.0)
        assertEquals(0.4853854614, StatsDependence.distanceCorrelation(x, y), 1e-9)
    }

    @Test
    fun `distance correlation is symmetric and invariant to a joint permutation`() {
        val n = 120
        val x = gaussians(n, 41)
        val y = DoubleArray(n) { x[it] * x[it] + 0.2 * gaussians(n, 42)[it] }
        val direct = StatsDependence.distanceCorrelation(x, y)
        assertEquals(direct, StatsDependence.distanceCorrelation(y, x), 1e-12)

        val perm = (0 until n).shuffled(java.util.Random(1))
        assertEquals(
            direct,
            StatsDependence.distanceCorrelation(DoubleArray(n) { x[perm[it]] }, DoubleArray(n) { y[perm[it]] }),
            1e-12,
        )
    }

    @Test
    fun `distance correlation answers zero on a constant where pearson answers NaN`() {
        val n = 50
        val x = DoubleArray(n) { it.toDouble() }
        val const = DoubleArray(n) { 4.0 }
        assertEquals(0.0, StatsDependence.distanceCorrelation(x, const), 0.0)
        assertEquals(0.0, StatsDependence.distanceCorrelation(const, x), 0.0)
        assertTrue(StatsDependence.pearson(x, const).isNaN())
        assertTrue(StatsDependence.spearman(x, const).isNaN())
        assertTrue(StatsDependence.kendallTauB(x, const).isNaN())
    }

    /**
     * The sharp edge `statistics_examples.md` warns about: `chatterjeeXi` breaks x-ties by original
     * index (`correlations.kt:257`), so with a constant predictor it silently scores how nearly `y`
     * is a function of *arrival order*.
     */
    @Test
    fun `xi with a constant predictor scores arrival order, not dependence`() {
        val n = 100
        val const = DoubleArray(n) { 4.0 }
        val inArrivalOrder = DoubleArray(n) { it.toDouble() }
        assertTrue(
            StatsDependence.chatterjeeXi(const, inArrivalOrder) > 0.9,
            "documented artefact: ${StatsDependence.chatterjeeXi(const, inArrivalOrder)}",
        )
        val shuffled = (0 until n).shuffled(java.util.Random(2)).map { it.toDouble() }.toDoubleArray()
        assertTrue(
            abs(StatsDependence.chatterjeeXi(const, shuffled)) < 0.1,
            "shuffling the pairs restores the honest answer: ${StatsDependence.chatterjeeXi(const, shuffled)}",
        )
        // and the documented "all y identical -> 0.0" branch (correlations.kt:297)
        assertEquals(0.0, StatsDependence.chatterjeeXi(inArrivalOrder, const), 0.0)
    }

    @Test
    fun `xi is asymmetric on a parabola in the documented direction`() {
        val n = 400
        val x = DoubleArray(n) { it / (n - 1.0) * 2.0 - 1.0 }
        val y = DoubleArray(n) { x[it] * x[it] }
        val forward = StatsDependence.xi(x, y)
        val backward = StatsDependence.xi(y, x)
        assertTrue(forward > 0.9, "y is a function of x: $forward")
        assertTrue(backward < 0.4, "x is NOT a function of y — two branches: $backward")
    }

    /**
     * The claim the docs page makes about small-sample bias: dCor under independence is not 0, and
     * the 95th percentile of its null tracks 2.5/sqrt(n) for n >= 50.
     */
    @Test
    fun `dcor under independence is biased upward and shrinks like one over root n`() {
        val results = listOf(50, 100, 200, 400).map { m ->
            val rng = java.util.Random(1234)
            val reps = 120
            val v = DoubleArray(reps) {
                StatsDependence.distanceCorrelation(
                    DoubleArray(m) { rng.nextGaussian() },
                    DoubleArray(m) { rng.nextGaussian() },
                )
            }.sortedArray()
            m to v[(reps * 0.95).toInt()]
        }
        for ((m, p95) in results) {
            assertTrue(p95 > 0.02, "the null is not concentrated at 0 (n=$m gave $p95)")
            val predicted = 2.5 / kotlin.math.sqrt(m.toDouble())
            assertTrue(
                abs(p95 - predicted) < 0.35 * predicted,
                "n=$m: null p95 $p95 should be near 2.5/sqrt(n) = $predicted",
            )
        }
        // strictly shrinking with n
        for (i in 1 until results.size) {
            assertTrue(results[i].second < results[i - 1].second, "the null must shrink with n: $results")
        }
    }

    // -------------------------
    // RollingDistanceCorrelation
    // -------------------------

    @Test
    fun `rolling distance correlation agrees with the batch call`() {
        val w = 60
        val rng = java.util.Random(3)
        val roll = RollingDistanceCorrelation(w)
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        var worst = 0.0
        var checked = 0

        for (i in 0 until 400) {
            val xv = rng.nextGaussian()
            // a regime change at 200, so the coefficient genuinely moves across the run
            val yv = if (i < 200) rng.nextGaussian() else xv * xv + 0.1 * rng.nextGaussian()
            xs += xv
            ys += yv
            val rolling = roll.push(xv, yv)
            if (i == 0) {
                assertNull(rolling, "the first push has nothing to correlate")
                continue
            }
            assertNotNull(rolling)
            val lo = maxOf(0, xs.size - w)
            val batch = StatsDependence.distanceCorrelation(
                xs.subList(lo, xs.size).toDoubleArray(),
                ys.subList(lo, ys.size).toDoubleArray(),
            )
            worst = maxOf(worst, abs(batch - rolling))
            checked++
        }
        assertEquals(399, checked)
        assertTrue(worst < 1e-11, "incremental sums must reproduce the batch answer: worst = $worst")
    }

    @Test
    fun `comparing against the wrong window does disagree`() {
        // Guards the test above from being vacuous: the same assertion against a window shifted by
        // 20 samples, or against the whole series, fails by a wide margin.
        val w = 40
        val rng = java.util.Random(9)
        val roll = RollingDistanceCorrelation(w)
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        var rolling = 0.0
        for (i in 0 until 200) {
            val xv = rng.nextGaussian()
            val yv = if (i < 100) rng.nextGaussian() else xv * xv
            xs += xv
            ys += yv
            rolling = roll.push(xv, yv) ?: 0.0
        }
        fun batch(from: Int, to: Int) = StatsDependence.distanceCorrelation(
            xs.subList(from, to).toDoubleArray(),
            ys.subList(from, to).toDoubleArray(),
        )
        assertEquals(batch(xs.size - w, xs.size), rolling, 1e-12)
        assertTrue(abs(batch(xs.size - w - 20, xs.size - 20) - rolling) > 1e-3, "a shifted window must differ")
        assertTrue(abs(batch(0, xs.size) - rolling) > 0.1, "the whole series must differ")
    }

    /**
     * The KDoc at `correlations.kt:586` warns that the maintained sums accumulate floating-point
     * drift. They do — and this pins how much, because the docs page quotes the bound.
     */
    @Test
    fun `the incremental drift stays below 1e-12 over 200k pushes`() {
        val w = 64
        val rng = java.util.Random(21)
        val roll = RollingDistanceCorrelation(w)
        val xs = ArrayDeque<Double>()
        val ys = ArrayDeque<Double>()
        var worst = 0.0
        for (i in 0 until 200_000) {
            val xv = rng.nextGaussian()
            val yv = 0.7 * xv + 0.7 * rng.nextGaussian()
            xs.addLast(xv)
            ys.addLast(yv)
            if (xs.size > w) {
                xs.removeFirst()
                ys.removeFirst()
            }
            val rolling = roll.push(xv, yv)
            if (i % 20_000 == 0 && i > 0) {
                val batch = StatsDependence.distanceCorrelation(xs.toDoubleArray(), ys.toDoubleArray())
                worst = maxOf(worst, abs(batch - rolling!!))
            }
        }
        assertTrue(worst < 1e-12, "drift should stay far below anything that matters: $worst")
        assertTrue(worst > 0.0, "if this is exactly zero the check is not reaching the maintained sums")
    }

    @Test
    fun `rolling distance correlation is clamped and defined without a warm-up gate`() {
        val roll = RollingDistanceCorrelation(4)
        assertNull(roll.current())
        assertNull(roll.push(1.0, 2.0))
        // From the second sample it returns a real value over however few points it holds.
        val two = roll.push(2.0, 4.0)
        assertNotNull(two)
        assertEquals(1.0, two, 1e-12, "two points always lie on a line")
        repeat(20) { roll.push(it.toDouble(), (it % 3).toDouble()) }
        val v = roll.current()!!
        assertTrue(v in 0.0..1.0, "always clamped into 0..1: $v")
        // a constant window answers 0.0, matching the batch call
        val flat = RollingDistanceCorrelation(4)
        repeat(6) { flat.push(it.toDouble(), 7.0) }
        assertEquals(0.0, flat.current()!!, 0.0)
    }

    @Test
    fun `both rolling classes reject a window below two`() {
        assertFailsWith<IllegalArgumentException> { RollingDistanceCorrelation(1) }
        assertFailsWith<IllegalArgumentException> { RollingXiCorrelation(1) }
    }

    // -------------------------
    // RollingXiCorrelation
    // -------------------------

    @Test
    fun `rolling xi is exactly the batch xi over the window`() {
        val w = 50
        val rng = java.util.Random(13)
        val roll = RollingXiCorrelation(w)
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        var worst = 0.0
        for (i in 0 until 300) {
            val xv = rng.nextGaussian()
            val yv = if (i < 150) rng.nextGaussian() else kotlin.math.sin(2.0 * xv)
            xs += xv
            ys += yv
            val rolling = roll.push(xv, yv) ?: continue
            val lo = maxOf(0, xs.size - w)
            val batch = StatsDependence.chatterjeeXi(
                xs.subList(lo, xs.size).toDoubleArray(),
                ys.subList(lo, ys.size).toDoubleArray(),
            )
            worst = maxOf(worst, abs(batch - rolling))
        }
        // It literally calls chatterjeeXi over a copy of the window (correlations.kt:843), so this
        // is bit-exact rather than merely close, and any deviation means the window is wrong.
        assertEquals(0.0, worst, 0.0)
    }

    @Test
    fun `clear resets rolling xi to a fresh instance`() {
        val roll = RollingXiCorrelation(10)
        repeat(10) { roll.push(it.toDouble(), it * it.toDouble()) }
        assertTrue(roll.current()!! > 0.5)
        roll.clear()
        assertNull(roll.current())
        assertNull(roll.push(1.0, 2.0), "after clear the first push is null again")
    }

    @Test
    fun `a regime change moves both rolling coefficients while pearson barely notices`() {
        // The claim the docs page's rolling figure makes, as arithmetic.
        val w = 80
        val total = 600
        val change = 300
        val rng = java.util.Random(3)
        val dcor = RollingDistanceCorrelation(w)
        val xiRoll = RollingXiCorrelation(w)
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        val d = ArrayList<Double>()
        val xi = ArrayList<Double>()
        val absR = ArrayList<Double>()
        for (i in 0 until total) {
            val xv = rng.nextGaussian()
            val yv = if (i < change) rng.nextGaussian() else xv * xv - 1.0 + 0.15 * rng.nextGaussian()
            xs += xv
            ys += yv
            d += dcor.push(xv, yv) ?: 0.0
            xi += xiRoll.push(xv, yv) ?: 0.0
            val lo = maxOf(0, xs.size - w)
            val bx = xs.subList(lo, xs.size).toDoubleArray()
            absR += if (bx.size >= 2) {
                abs(StatsDependence.pearson(bx, ys.subList(lo, ys.size).toDoubleArray()))
            } else 0.0
        }
        val dBefore = d.subList(w, change).average()
        val dAfter = d.subList(change + w, total).average()
        val xiBefore = xi.subList(w, change).average()
        val xiAfter = xi.subList(change + w, total).average()
        val rBefore = absR.subList(w, change).average()
        val rAfter = absR.subList(change + w, total).average()

        assertTrue(dAfter > 2.0 * dBefore, "dCor should more than double: $dBefore -> $dAfter")
        assertTrue(xiBefore < 0.1 && xiAfter > 0.5, "xi should go from nothing to strong: $xiBefore -> $xiAfter")
        assertTrue(rAfter < 0.35, "rolling |pearson| stays small on a parabola: $rAfter")
        assertTrue(
            (dAfter - dBefore) > 3.0 * (rAfter - rBefore),
            "dCor's response must dwarf pearson's: d ${dAfter - dBefore} vs r ${rAfter - rBefore}",
        )
    }
}
