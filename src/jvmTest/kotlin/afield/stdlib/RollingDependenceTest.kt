package afield.stdlib

import kotlin.math.abs
import kotlin.math.ulp
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
 * subtracts from maintained row/grand/cross sums (`correlations.kt:770`, `correlations.kt:823`),
 * while [StatsDependence.distanceCorrelation] materialises two full n×n distance matrices and
 * double-centres them (`correlations.kt:415`). Two different algorithms, one answer. That the guard
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
     * A predictor that is *constant* carries no information about anything, so xi must read 0 — and
     * the answer must not depend on the order the pairs were handed over in.
     *
     * The oracle is analytic rather than a recorded number. Under Chatterjee's prescription (break
     * x-ties uniformly at random) a single tie group of size n with distinct y gives
     * `E[sum |R_{i+1} - R_i|] = (n^2 - 1)/3` while the denominator is `2 * n(n+1)(n-1)/6`, so
     * `xi = 1 - n(n^2-1)/3 / (n(n^2-1)/3)` is **exactly** zero for every n *in exact arithmetic*.
     *
     * It is not exactly zero in `Double`, and this test sweeps n rather than picking one precisely
     * because picking one hides that. Over n = 2..400, 54 values of n come back non-zero; the
     * largest deviation is 2.220446e-16, which is one ulp of 1.0 — the last subtraction in
     * `1 - numerator/denominator` rounding against itself. n = 100 happens to land on exact 0.0, so
     * an n = 100 test with a 0.0 tolerance passes and says nothing. One ulp is the honest bound.
     *
     * Before `correlations.kt` computed that expectation it broke x-ties by input index, and this
     * case returned 0.970297 — a near-perfect dependence score for a predictor carrying nothing.
     */
    @Test
    fun `xi on a constant predictor is zero to within one ulp, whatever order the pairs arrived in`() {
        val oneUlp = 1.0.ulp // 2.220446049250313e-16
        var worst = 0.0
        var worstN = -1
        for (n in 2..400) {
            val const = DoubleArray(n) { 4.0 }
            val v = StatsDependence.chatterjeeXi(const, DoubleArray(n) { it.toDouble() })
            if (abs(v) > abs(worst)) {
                worst = v
                worstN = n
            }
        }
        assertTrue(
            abs(worst) <= oneUlp,
            "a constant predictor explains nothing: the closed form is exactly 0, so Double must " +
                "land within one ulp of it — worst was $worst at n=$worstN",
        )

        // Order invariance is a stronger claim than the magnitude one, and it *is* exact: the same
        // multiset of pairs must give the bit-identical answer however it is shuffled in.
        val n = 100
        val const = DoubleArray(n) { 4.0 }
        val inArrivalOrder = DoubleArray(n) { it.toDouble() }
        val shuffled = (0 until n).shuffled(java.util.Random(2)).map { it.toDouble() }.toDoubleArray()
        assertEquals(
            StatsDependence.chatterjeeXi(const, inArrivalOrder),
            StatsDependence.chatterjeeXi(const, shuffled),
            0.0,
            "the same 0 regardless of arrival order",
        )
        // the "all y identical -> 0.0" branch is unaffected, and that one really is exactly 0.0:
        // it is an early return, not a computed quotient.
        assertEquals(0.0, StatsDependence.chatterjeeXi(inArrivalOrder, const), 0.0)
    }

    /**
     * The constant predictor was only the clearest symptom. The bug was arrival order leaking through
     * *any* x-tie, so the case that matters is heavy ties: a few distinct x values with many repeats,
     * which is what you get from a quantised sensor, a binned covariate or a categorical predictor.
     *
     * Here x cycles over three values and y is the arrival index, so x predicts nothing about y and
     * xi must read ~0. Index tie-breaking returned **+0.950566** at n=300 and **+0.799280** for the
     * four-value variant at n=100 (both measured against the pre-fix code). The tolerance is loose on
     * purpose: the honest answer is a small negative number near zero, not exactly zero, because y's
     * ranks are not uniformly spread inside each tie group.
     */
    @Test
    fun `xi does not leak arrival order through heavy x-ties`() {
        for (distinct in intArrayOf(2, 3, 4, 5, 10)) {
            val n = 300
            val x = DoubleArray(n) { (it % distinct).toDouble() }
            val y = DoubleArray(n) { it.toDouble() } // pure arrival order: unpredictable from x
            val v = StatsDependence.chatterjeeXi(x, y)
            assertTrue(
                abs(v) < 0.05,
                "x with $distinct distinct values predicts nothing about arrival order, got $v",
            )
        }
    }

    /**
     * Permutation invariance, stated directly: relabelling *which sample arrived first* cannot change
     * a dependence measure. Ten shuffles of a heavily-tied pair set must all give one answer.
     *
     * This is the property the index tie-break violated, and it is checked independently of what the
     * right number is — so it would fail for any order-sensitive tie rule, including a differently
     * seeded random one.
     */
    @Test
    fun `xi is invariant to the order the pairs are presented in`() {
        val n = 200
        val rnd = java.util.Random(17)
        val xs = DoubleArray(n) { (it % 7).toDouble() }              // heavy ties
        val ys = DoubleArray(n) { (it % 13).toDouble() + 0.5 * (it / 13) } // some y-ties too
        val reference = StatsDependence.chatterjeeXi(xs, ys)
        repeat(10) {
            val perm = (0 until n).shuffled(rnd)
            val px = DoubleArray(n) { xs[perm[it]] }
            val py = DoubleArray(n) { ys[perm[it]] }
            assertEquals(
                reference,
                StatsDependence.chatterjeeXi(px, py),
                1e-12,
                "xi changed when only the presentation order changed",
            )
        }
    }

    /**
     * A cross-implementation oracle for the tied-x path, and the check that keeps the fix from being
     * "return something near zero whenever x has ties".
     *
     * The expected values come from a second, independent implementation (a Python transcription written
     * from the paper's definition, not from this code) whose answers were in turn validated against a
     * 30 000-replication Monte Carlo of the *randomised* estimator — the thing the closed form is the
     * expectation of. Agreement was within 1.5 standard errors on every case below, so these numbers are
     * not this function reporting on itself.
     *
     * The two strong cases matter most: heavy ties with a real relationship still read **0.891**, so the
     * fix has not flattened genuine dependence — it has removed 0.990, the inflated reading that the
     * within-group arrival ordering used to add on top of it.
     */
    @Test
    fun `xi on tied x matches an independent implementation and its Monte-Carlo`() {
        val n = 300
        // no information: x cycles, y is the arrival index
        assertEquals(
            -0.006577850865009616,
            StatsDependence.xi(DoubleArray(n) { (it % 3).toDouble() }, DoubleArray(n) { it.toDouble() }),
            1e-12,
        )
        // real dependence through ties: ten blocks of thirty, y rising across and within blocks.
        // Pre-fix this read 0.990033, inflated by the arrival order inside each block.
        assertEquals(
            0.8911099012211247,
            StatsDependence.xi(
                DoubleArray(n) { (it / 30).toDouble() },
                DoubleArray(n) { ((it / 30) * (it / 30)).toDouble() + (it % 30) / 100.0 },
            ),
            1e-12,
        )
        // and the same value for the interleaved presentation of the same structure, which is the
        // permutation invariance again but with a number attached
        assertEquals(
            0.8911099012211247,
            StatsDependence.xi(
                DoubleArray(n) { (it % 10).toDouble() },
                DoubleArray(n) { ((it % 10) * (it % 10)).toDouble() + (it / 10) / 50.0 },
            ),
            1e-12,
        )
        // When y is constant inside every x-tie group the tie-breaking cannot matter, so this case is
        // unchanged by the fix — a V shape read 0.9824016563146998 before and after.
        assertEquals(
            0.9824016563146998,
            StatsDependence.xi(
                DoubleArray(n) { (it / 30).toDouble() },
                DoubleArray(n) { abs(it / 30 - 5).toDouble() },
            ),
            1e-12,
        )
    }

    /**
     * The tie-aware path must not have moved the tie-*free* answers, because the published figures on
     * `statistics_examples.md` and `interpolants_examples.md` are tie-free and their numbers are
     * quoted in prose. With every x distinct each tie group has size one, the expectation is over a
     * single ordering, and the arithmetic reduces term-for-term to the old sum — so this asserts
     * *bit* equality against values recorded from the pre-fix implementation, not a tolerance.
     */
    @Test
    fun `tie-free xi is bit-identical to the pre-fix implementation`() {
        val n = 400
        val x = DoubleArray(n) { it / (n - 1.0) * 2.0 - 1.0 }
        // Recorded by running the pre-fix build, which sorted by x and never consulted the index here.
        assertEquals(0.9850375467576664, StatsDependence.xi(x, DoubleArray(n) { x[it] * x[it] }), 0.0)
        assertEquals(0.9925187032418953, StatsDependence.xi(x, DoubleArray(n) { -x[it] }), 0.0)
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
     * The KDoc at `correlations.kt:667` warns that the maintained sums accumulate floating-point
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
        // It literally calls chatterjeeXi over a copy of the window (correlations.kt:924), so this
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
