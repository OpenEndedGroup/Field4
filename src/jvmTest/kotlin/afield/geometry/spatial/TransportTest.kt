package afield.geometry.spatial

import afield.math.linalg.Vec2
import java.util.SplittableRandom
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The claims in `transport.kt`, each against an answer computed another way: the Hungarian assignment
 * against brute force over every permutation, Sinkhorn's marginals against its weights, its cost
 * against the exact assignment as epsilon shrinks, the divergence's zero, and the multiscale solve
 * against the direct one.
 */
class TransportTest {

    private fun cloud(n: Int, seed: Long, dx: Float = 0f): List<Vec2> {
        val r = SplittableRandom(seed)
        return List(n) { Vec2(r.nextDouble().toFloat() + dx, r.nextDouble().toFloat()) }
    }

    private fun permutations(n: Int): Sequence<IntArray> = sequence {
        val p = IntArray(n) { it }
        suspend fun SequenceScope<IntArray>.go(k: Int) {
            if (k == n) { yield(p.copyOf()); return }
            for (i in k until n) {
                p[k] = p[i].also { p[i] = p[k] }
                go(k + 1)
                p[k] = p[i].also { p[i] = p[k] }
            }
        }
        go(0)
    }

    private fun total(cost: Array<DoubleArray>, p: IntArray) = p.indices.sumOf { cost[it][p[it]] }

    @Test
    fun assignmentIsTheBestPermutation() {
        val r = SplittableRandom(3)
        repeat(20) {
            val n = 1 + r.nextInt(7)
            val cost = Array(n) { DoubleArray(n) { r.nextDouble() * 10 } }
            val best = permutations(n).minOf { total(cost, it) }
            val found = assignment(cost)
            assertContentEquals((0 until n).toList(), found.sorted(), "a bijection")
            assertEquals(best, total(cost, found), 1e-9)
        }
    }

    @Test
    fun pointAssignmentMatchesTheMatrixForm() {
        val a = cloud(40, 1)
        val b = cloud(40, 2)
        assertContentEquals(assignment(squaredDistanceCost(a, b)), assignment(a, b))
    }

    @Test
    fun sinkhornMeetsItsMarginals() {
        val a = cloud(30, 4)
        val b = cloud(45, 5, dx = 0.5f)
        val wa = DoubleArray(a.size) { 1.0 + it % 3 }
        val plan = sinkhorn(a, b, epsilon = 0.01, sourceWeights = wa)
        assertTrue(plan.converged, "$plan")
        val total = wa.sum()
        for (i in a.indices) assertEquals(wa[i] / total, plan.plan[i].sum(), 1e-8)
        for (j in b.indices) assertEquals(1.0 / b.size, plan.plan.sumOf { it[j] }, 1e-8)
        assertEquals(1.0, plan.plan.sumOf { it.sum() }, 1e-8)
    }

    @Test
    fun sinkhornApproachesTheExactAssignmentAsEpsilonShrinks() {
        val a = cloud(24, 6)
        val b = cloud(24, 7, dx = 0.3f)
        val cost = squaredDistanceCost(a, b)
        val exact = total(cost, assignment(cost)) / a.size
        val costs = listOf(0.1, 0.01, 0.001).map { sinkhorn(cost, it, maxIterations = 200_000).cost }
        // entropic cost is never below the exact optimum, and falls toward it
        costs.forEach { assertTrue(it >= exact - 1e-9, "entropic cost $it below exact $exact") }
        assertTrue(costs[0] > costs[1] && costs[1] > costs[2], "$costs should fall with epsilon")
        assertEquals(exact, costs[2], exact * 0.02)
        // and at small epsilon the strongest target of each source is its assigned one
        assertContentEquals(assignment(cost), sinkhorn(cost, 0.0005, maxIterations = 400_000).strongest())
    }

    @Test
    fun divergenceIsZeroForTheSameSetAndPositiveOtherwise() {
        val a = cloud(20, 8)
        assertEquals(0.0, sinkhornDivergence(a, a, epsilon = 0.01), 1e-8)
        val shifted = a.map { Vec2(it.x + 0.2f, it.y) }
        val d = sinkhornDivergence(a, shifted, epsilon = 0.01)
        // a rigid shift by t has squared-distance transport cost t²; the divergence is close to it
        assertEquals(0.04, d, 0.01)
    }

    @Test
    fun barycentricMapOfATranslationIsTheTranslation() {
        val a = cloud(25, 9)
        val b = a.map { Vec2(it.x + 1f, it.y - 0.5f) }
        val plan = sinkhorn(a, b, epsilon = 1e-4, maxIterations = 200_000)
        plan.barycentricMap(b).forEachIndexed { i, p ->
            assertEquals(a[i].x + 1f, p.x, 0.02f)
            assertEquals(a[i].y - 0.5f, p.y, 0.02f)
        }
    }

    @Test
    fun multiscaleReachesTheSamePlanAsDirect() {
        val a = cloud(300, 10)
        val b = cloud(260, 11, dx = 0.4f)
        val pa = Array(a.size) { doubleArrayOf(a[it].x.toDouble(), a[it].y.toDouble()) }
        val pb = Array(b.size) { doubleArrayOf(b[it].x.toDouble(), b[it].y.toDouble()) }
        val direct = sinkhorn(squaredDistanceCost(pa, pb), 0.005, tolerance = 1e-10, maxIterations = 100_000)
        val multi = multiscaleSinkhorn(pa, pb, 0.005, minSize = 32, tolerance = 1e-10, maxIterationsPerLevel = 100_000)
        assertTrue(multi.converged, "$multi")
        assertEquals(direct.cost, multi.cost, 1e-6)
        var worst = 0.0
        for (i in pa.indices) for (j in pb.indices) worst = maxOf(worst, abs(direct.plan[i][j] - multi.plan[i][j]))
        assertTrue(worst < 1e-6, "largest plan difference $worst")
    }
}
