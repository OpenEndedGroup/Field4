package afield.stdlib

import afield.graphics.HungarianAssignment
import java.util.SplittableRandom
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The claims in `bspot.kt`: every result is a bijection whose reported cost is its real cost, merging
 * never makes it worse, SIMD and scalar agree, the worker count changes nothing, and the answer is
 * close to the exact one where the exact one can be computed.
 */
class BspOtTest {

    private fun total(ax: FloatArray, ay: FloatArray, bx: FloatArray, by: FloatArray, p: IntArray): Double {
        var s = 0.0
        for (i in p.indices) {
            val dx = ax[i].toDouble() - bx[p[i]].toDouble()
            val dy = ay[i].toDouble() - by[p[i]].toDouble()
            s += dx * dx + dy * dy
        }
        return s
    }

    private fun checkResult(ax: FloatArray, ay: FloatArray, bx: FloatArray, by: FloatArray, r: BspOt2D.Result) {
        BspOt2D.validatePermutation(r.targetOfSource, ax.size)
        val actual = total(ax, ay, bx, by, r.targetOfSource)
        assertTrue(abs(actual - r.squaredCost) <= 1e-10 * max(1e-300, actual), "reported ${r.squaredCost}, actual $actual")
        for (i in 1 until r.costHistory.size)
            assertTrue(r.costHistory[i] <= r.costHistory[i - 1] * (1.0 + 1e-12), "cost rose at merge $i")
    }

    private fun exhaustive(ax: FloatArray, ay: FloatArray, bx: FloatArray, by: FloatArray): Double {
        val p = IntArray(ax.size) { it }
        var best = Double.POSITIVE_INFINITY
        fun visit(k: Int) {
            if (k == p.size) { best = minOf(best, total(ax, ay, bx, by, p)); return }
            for (j in k until p.size) {
                val t = p[k]; p[k] = p[j]; p[j] = t
                visit(k + 1)
                p[j] = p[k]; p[k] = t
            }
        }
        visit(0)
        return best
    }

    @Test
    fun everyShapeAndSizeGivesABijectionThatSimdAndScalarAgreeOn() {
        val rng = SplittableRandom(123)
        // SIMD-lane boundaries, odd sizes, all-coincident, duplicates, huge offsets, collinear.
        for (splits in BspOt2D.Splits.entries) for (n in listOf(0, 1, 2, 3, 7, 8, 9, 15, 16, 17, 31, 63, 127, 257, 1025)) {
            for (shape in 0..4) {
                val ax = FloatArray(n) {
                    when (shape) {
                        1 -> 0f
                        2 -> (it % 5).toFloat()
                        3 -> 1e20f + rng.nextDouble().toFloat() * 1e17f
                        else -> rng.nextDouble(-1.0, 1.0).toFloat()
                    }
                }
                val ay = FloatArray(n) { if (shape == 1 || shape == 4) 0f else rng.nextDouble().toFloat() }
                val bx = FloatArray(n) { if (shape == 1) 0f else ax[n - it - 1] + if (shape == 3) 1e18f else .1f }
                val by = FloatArray(n) { if (shape == 1 || shape == 4) 0f else rng.nextDouble().toFloat() }
                val opt = BspOt2D.Options(trees = 8, seed = 91, parallelism = 1, splits = splits)
                val saved = ax.copyOf()
                val scalar = BspOt2D.match(ax, ay, bx, by, opt.copy(simd = false))
                val vector = BspOt2D.match(ax, ay, bx, by, opt)
                checkResult(ax, ay, bx, by, scalar); checkResult(ax, ay, bx, by, vector)
                assertContentEquals(scalar.targetOfSource, vector.targetOfSource, "SIMD differs n=$n shape=$shape $splits")
                assertContentEquals(saved, ax, "input mutated")
                assertContentEquals(vector.targetOfSource, BspOt2D.match(ax, ay, bx, by, opt).targetOfSource)
                val warm = BspOt2D.match(ax, ay, bx, by, opt.copy(seed = 92), vector.targetOfSource)
                checkResult(ax, ay, bx, by, warm)
                assertTrue(warm.squaredCost <= vector.squaredCost * (1.0 + 1e-12), "warm start got worse")
                if (shape == 4) {
                    // Collinear on y = 0: sorting both and pairing in order is the exact optimum.
                    val optimal = total(ax.sortedArray(), FloatArray(n), bx.sortedArray(), FloatArray(n), IntArray(n) { it })
                    assertTrue(abs(vector.squaredCost - optimal) <= 1e-10 * max(1.0, optimal), "1D n=$n $splits")
                }
            }
        }
    }

    @Test
    fun neverBeatsTheExhaustiveOptimumAndFindsItForTwoPoints() {
        val rng = SplittableRandom(7)
        repeat(20) {
            val n = 2 + it % 6
            val ax = FloatArray(n) { rng.nextDouble().toFloat() }
            val ay = FloatArray(n) { rng.nextDouble().toFloat() }
            val bx = FloatArray(n) { rng.nextDouble().toFloat() }
            val by = FloatArray(n) { rng.nextDouble().toFloat() }
            val r = BspOt2D.match(ax, ay, bx, by, BspOt2D.Options(trees = 16))
            checkResult(ax, ay, bx, by, r)
            val optimum = exhaustive(ax, ay, bx, by)
            assertTrue(r.squaredCost + 1e-12 >= optimum)
            if (n == 2) assertTrue(abs(r.squaredCost - optimum) < 1e-12)
        }
    }

    private fun exactCost(ax: FloatArray, ay: FloatArray, bx: FloatArray, by: FloatArray): Double {
        val cost = Array(ax.size) { i ->
            DoubleArray(ax.size) { j ->
                val dx = ax[i].toDouble() - bx[j]; val dy = ay[i].toDouble() - by[j]
                dx * dx + dy * dy
            }
        }
        return total(ax, ay, bx, by, HungarianAssignment.solve(cost))
    }

    private fun ratio(ax: FloatArray, ay: FloatArray, bx: FloatArray, by: FloatArray, exact: Double, trees: Int, splits: BspOt2D.Splits): Double {
        val r = BspOt2D.match(ax, ay, bx, by, BspOt2D.Options(trees = trees, splits = splits))
        assertTrue(r.squaredCost >= exact * (1 - 1e-12), "beat the exact optimum")
        return r.squaredCost / exact
    }

    @Test
    fun gaussianSplitsHalveTheExcessCostBetweenDifferentShapes() {
        // A uniform square onto three discs of different sizes, 1000 points, against the exact
        // Hungarian answer. Measured 2026-10-01: Gaussian splits 1.055 at 8 trees and 1.029 at 32,
        // random splits 1.115 and 1.049. Bounds are those with a little room.
        val rng = SplittableRandom(59)
        val n = 1000
        val ax = FloatArray(n) { rng.nextDouble().toFloat() }
        val ay = FloatArray(n) { rng.nextDouble().toFloat() }
        val discs = listOf(Triple(0.33, 0.40, 0.25), Triple(0.74, 0.30, 0.16), Triple(0.64, 0.76, 0.20))
        val area = discs.sumOf { it.third * it.third }
        val bx = FloatArray(n); val by = FloatArray(n)
        for (j in 0 until n) {
            var pick = rng.nextDouble() * area; var k = 0
            while (pick > discs[k].third * discs[k].third) { pick -= discs[k].third * discs[k].third; k++ }
            val r = discs[k].third * sqrt(rng.nextDouble()); val a = 2 * PI * rng.nextDouble()
            bx[j] = (discs[k].first + r * cos(a)).toFloat(); by[j] = (discs[k].second + r * sin(a)).toFloat()
        }
        val exact = exactCost(ax, ay, bx, by)
        val gaussian8 = ratio(ax, ay, bx, by, exact, 8, BspOt2D.Splits.GAUSSIAN)
        val gaussian32 = ratio(ax, ay, bx, by, exact, 32, BspOt2D.Splits.GAUSSIAN)
        val random8 = ratio(ax, ay, bx, by, exact, 8, BspOt2D.Splits.RANDOM)
        println("square onto three discs, cost / Hungarian: GAUSSIAN 8 trees $gaussian8, 32 trees $gaussian32; RANDOM 8 trees $random8")
        assertTrue(gaussian8 < 1.07, "8 trees: $gaussian8")
        assertTrue(gaussian32 < 1.04, "32 trees: $gaussian32")
        assertTrue(gaussian8 - 1 < 0.6 * (random8 - 1), "Gaussian $gaussian8 against random $random8")
    }

    @Test
    fun twoUniformCloudsAreTheHardCase() {
        // Two independent uniform clouds of 400. Both subsets of every node have nearly the same
        // Gaussian, so the transport map is close to the identity and its eigenvectors are noise;
        // Gaussian splits are no better than random ones here. Measured 2026-10-01: Gaussian 1.325
        // at 16 trees and 1.260 at 64, random 1.227 and 1.156, unmatched 123.
        val rng = SplittableRandom(2026)
        val n = 400
        val ax = FloatArray(n) { rng.nextDouble().toFloat() }
        val ay = FloatArray(n) { rng.nextDouble().toFloat() }
        val bx = FloatArray(n) { rng.nextDouble().toFloat() }
        val by = FloatArray(n) { rng.nextDouble().toFloat() }
        val exact = exactCost(ax, ay, bx, by)
        for (splits in BspOt2D.Splits.entries) {
            val sixteen = ratio(ax, ay, bx, by, exact, 16, splits)
            val sixtyFour = ratio(ax, ay, bx, by, exact, 64, splits)
            println("two uniform clouds, cost / Hungarian, $splits: 16 trees $sixteen, 64 trees $sixtyFour")
            assertTrue(sixtyFour < sixteen, "more trees should help")
            assertTrue(sixtyFour < 1.30, "$splits, 64 trees: $sixtyFour")
        }
    }

    @Test
    fun aShuffledCopyMatchesBackAtZeroCost() {
        val rng = SplittableRandom(5)
        val x = FloatArray(257) { rng.nextDouble().toFloat() }
        val y = FloatArray(257) { rng.nextDouble().toFloat() }
        assertEquals(0.0, BspOt2D.match(x, y, x.reversedArray(), y.reversedArray()).squaredCost)
    }

    @Test
    fun finiteFloatExtremesDoNotOverflowAndMergeOptionsAllHold() {
        val e = floatArrayOf(Float.MAX_VALUE, -Float.MAX_VALUE, 0f, Float.MIN_VALUE)
        val z = FloatArray(4)
        checkResult(e, z, z, e, BspOt2D.match(e, z, z, e))
        val rng = SplittableRandom(11)
        val x = FloatArray(257) { rng.nextDouble().toFloat() }
        val y = FloatArray(257) { rng.nextDouble().toFloat() }
        for (cycle in listOf(false, true)) for (passes in listOf(0, 1, 3)) {
            checkResult(x, y, y, x, BspOt2D.match(x, y, y, x, BspOt2D.Options(cycleMerge = cycle, swapPasses = passes)))
        }
    }

    @Test
    fun theWorkerCountChangesNothing() {
        // Includes an incomplete final batch, and more workers than trees.
        val rng = SplittableRandom(59)
        val px = FloatArray(20_003) { rng.nextDouble().toFloat() }
        val py = FloatArray(px.size) { rng.nextDouble().toFloat() }
        for (cycle in listOf(false, true)) {
            val opt = BspOt2D.Options(trees = 7, seed = 456, parallelism = 1, cycleMerge = cycle)
            val serial = BspOt2D.match(px, py, py, px, opt)
            val warmSerial = BspOt2D.match(px, py, py, px, opt.copy(seed = 789), serial.targetOfSource)
            for (workers in listOf(2, 3, 4, 12)) {
                val parallel = BspOt2D.match(px, py, py, px, opt.copy(parallelism = workers))
                checkResult(px, py, py, px, parallel)
                assertContentEquals(serial.targetOfSource, parallel.targetOfSource, "workers=$workers")
                assertContentEquals(serial.costHistory, parallel.costHistory, "workers=$workers")
                val warmParallel = BspOt2D.match(px, py, py, px,
                    opt.copy(seed = 789, parallelism = workers), serial.targetOfSource)
                assertContentEquals(warmSerial.targetOfSource, warmParallel.targetOfSource)
                assertContentEquals(warmSerial.costHistory, warmParallel.costHistory)
            }
        }
    }

    @Test
    fun anInterruptedCallerGetsCancellationAndKeepsItsInterrupt() {
        val x = FloatArray(1000) { it.toFloat() }
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<CancellationException> { BspOt2D.match(x, x, x, x, BspOt2D.Options(parallelism = 2)) }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun badInputIsRejected() {
        val z = FloatArray(4)
        assertFailsWith<IllegalArgumentException> { BspOt2D.Options(trees = 0) }
        assertFailsWith<IllegalArgumentException> { BspOt2D.Options(swapPasses = -1) }
        assertFailsWith<IllegalArgumentException> { BspOt2D.Options(parallelism = 0) }
        assertFailsWith<IllegalArgumentException> { BspOt2D.match(floatArrayOf(Float.NaN), floatArrayOf(0f), floatArrayOf(0f), floatArrayOf(0f)) }
        assertFailsWith<IllegalArgumentException> { BspOt2D.match(floatArrayOf(Float.POSITIVE_INFINITY), floatArrayOf(0f), floatArrayOf(0f), floatArrayOf(0f)) }
        assertFailsWith<IllegalArgumentException> { BspOt2D.match(z, z, z, FloatArray(3)) }
        assertFailsWith<IllegalArgumentException> { BspOt2D.match(z, z, z, z, initial = intArrayOf(0, 0, 2, 3)) }
        assertFailsWith<IllegalArgumentException> { BspOt2D.match(z, z, z, z, initial = intArrayOf(-1, 1, 2, 3)) }
        assertFailsWith<IllegalArgumentException> { BspOt2D.match(z, z, z, z, initial = intArrayOf(4, 1, 2, 3)) }
        assertFailsWith<IllegalArgumentException> { matchPoints(listOf(Vec2(0f, 0f)), emptyList()) }
    }

    @Test
    fun matchPointsReordersTargetsIntoSourceOrder() {
        val from = listOf(Vec2(0f, 0f), Vec2(10f, 0f), Vec2(0f, 10f))
        val to = listOf(Vec2(0.5f, 10f), Vec2(0.5f, 0f), Vec2(10.5f, 0f))
        val r = matchPoints(from, to)
        assertContentEquals(intArrayOf(1, 2, 0), r.targetOfSource)
        assertEquals(listOf(to[1], to[2], to[0]), r.reorder(to))
        assertEquals(0.75, r.squaredCost, 1e-9)
    }

    @Test
    fun resampleByLengthSpacesPointsEvenlyWhateverTheInputSpacing() {
        // An L whose first leg has 2 points and second leg 40: by length the samples split evenly.
        val l = listOf(vec(0, 0), vec(10, 0)) + (1..40).map { vec(10, it * 0.25) }
        val r = l.resampleByLength(21)
        assertEquals(21, r.size)
        assertEquals(0f, r.first().x); assertEquals(10f, r.last().y, 1e-5f)
        val gaps = r.zipWithNext { a, b -> sqrt(((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y)).toDouble()) }
        gaps.forEach { assertEquals(1.0, it, 1e-4) }
        // Closed: a unit square of 4 corners, 8 samples, no repeat of the start.
        val sq = listOf(vec(0, 0), vec(1, 0), vec(1, 1), vec(0, 1)).resampleByLength(8, closed = true)
        assertEquals(8, sq.size)
        assertEquals(vec(0.5, 0.0), sq[1]); assertEquals(vec(0.0, 0.5), sq[7])
        assertEquals(List(3) { vec(2, 2) }, listOf(vec(2, 2), vec(2, 2)).resampleByLength(3))
    }
}
