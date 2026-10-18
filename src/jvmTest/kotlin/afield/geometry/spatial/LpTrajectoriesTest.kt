package afield.geometry.spatial

import afield.geometry.path.FLine
import afield.math.linalg.vec
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LpTrajectoriesTest {
    private val pentagon = LpPolygon(
        listOf(vec(-2.0, -1.0), vec(1.5, -1.5), vec(3.0, 1.0), vec(0.5, 2.5), vec(-2.5, 1.5))
    )
    private val c = vec(1.0, 0.4)
    private val optimum = pentagon.optimalVertex(c)
    private val optimumValue = (optimum.x * 1.0 + optimum.y * 0.4) / hypot(1.0, 0.4)

    private fun distanceToOptimum(s: LpSample) = hypot(s.x - optimum.x, s.y - optimum.y)

    @Test
    fun centralPathIsFeasibleAndConvergesToTheOptimalVertex() {
        val path = pentagon.centralPath(c)
        assertEquals(160, path.samples.size)
        assertTrue(path.samples.all { pentagon.containsStrictly(it.x, it.y) }, "every central-path sample is strictly inside")
        assertTrue(optimumValue - path.last.objective in 0.0..1e-3, "objective gap ${optimumValue - path.last.objective}")
        assertTrue(distanceToOptimum(path.last) < 1e-3)
        // The objective rises monotonically as mu falls.
        assertTrue(path.samples.zipWithNext().all { (a, b) -> b.objective >= a.objective - 1e-9 })
        assertTrue(path.samples.first().scale > path.last.scale)
    }

    @Test
    fun weightsBendThePathAndEqualRepeatingAConstraint() {
        val plain = pentagon.centralPath(c)
        val weights = doubleArrayOf(1.0, 4.0, 1.0, 1.0, 1.0)
        val weighted = pentagon.centralPath(c, weights)
        assertTrue(weighted.samples.all { pentagon.containsStrictly(it.x, it.y) })
        val maxGap = plain.samples.indices.maxOf { hypot(plain.samples[it].x - weighted.samples[it].x, plain.samples[it].y - weighted.samples[it].y) }
        assertTrue(maxGap > 0.1, "weights should move the path visibly, moved $maxGap")
        assertTrue(distanceToOptimum(weighted.last) < 1e-3, "both still end at the optimum")
        // The heavier edge pushes the path's start (the weighted analytic centre) away from itself.
        val s0 = weighted.samples.first()
        val p0 = plain.samples.first()
        assertTrue(pentagon.slack(1, s0.x, s0.y) > pentagon.slack(1, p0.x, p0.y))
    }

    @Test
    fun fanHasOnePathPerAngleEndingAtEachDirectionsVertex() {
        val fan = pentagon.centralFan(angles = 12, samples = 30)
        assertEquals(12, fan.size)
        assertTrue(fan.all { it.samples.size == 30 })
        val starts = fan.map { it.samples.first() }
        assertTrue(starts.all { hypot(it.x - starts[0].x, it.y - starts[0].y) < 1e-2 }, "all paths leave the analytic centre")
        val ends = fan.mapIndexed { i, t ->
            val th = 2.0 * Math.PI * i / 12
            val vtx = pentagon.optimalVertex(vec(kotlin.math.cos(th), kotlin.math.sin(th)))
            hypot(t.last.x - vtx.x, t.last.y - vtx.y)
        }
        assertTrue(ends.all { it < 1e-2 }, "each fan path ends at its vertex: $ends")
        assertEquals(12, fan.toFLines().size)
    }

    @Test
    fun primalDualStaysFeasibleAndConvergesInFewSteps() {
        val pd = pentagon.primalDual(c)
        assertTrue(pd.samples.size in 3..40, "took ${pd.samples.size} iterates")
        assertTrue(pd.samples.all { pentagon.containsStrictly(it.x, it.y) })
        assertTrue(distanceToOptimum(pd.last) < 1e-5, "ended ${pd.last}")
        assertTrue(pd.last.scale < 1e-8)
    }

    @Test
    fun pdhgKeepsEveryStateAndSpiralsIn() {
        val trace = pentagon.pdhg(c, iterations = 4000)
        assertEquals(4001, trace.trajectory.samples.size)
        assertEquals(trace.trajectory.samples.size, trace.duals.size)
        assertTrue(trace.duals.all { d -> d.size == 5 && d.all { it >= 0.0 } })
        assertTrue(trace.trajectory.samples.any { it.violation > 1e-3 }, "PDHG visits infeasible points")
        assertTrue(distanceToOptimum(trace.trajectory.last) < 1e-3, "ended ${trace.trajectory.last}")
    }

    @Test
    fun ellipsoidShapesAreValidAndShrinkByTheTwoDimensionalFactor() {
        val trace = pentagon.ellipsoid(c, iterations = 120)
        assertEquals(trace.trajectory.samples.size, trace.ellipses.size)
        assertTrue(trace.ellipses.all { it.isPositiveDefinite })
        trace.ellipses.zipWithNext().forEach { (a, b) ->
            assertEquals(16.0 / 27.0, b.determinant / a.determinant, 1e-6)
        }
        assertTrue(trace.trajectory.samples.any { it.tag == "feasibility-cut" })
        assertTrue(trace.trajectory.samples.any { it.tag == "objective-cut" })
        val best = trace.trajectory.samples.filter { it.violation == 0.0 }.maxOf { it.objective }
        assertTrue(optimumValue - best < 1e-3, "best feasible objective gap ${optimumValue - best}")
        // Every ellipse keeps the optimum: the method never cuts the answer away.
        trace.ellipses.forEach { e ->
            val dx = optimum.x - e.cx
            val dy = optimum.y - e.cy
            val q = (e.pyy * dx * dx - 2 * e.pxy * dx * dy + e.pxx * dy * dy) / e.determinant
            assertTrue(q <= 1.0 + 1e-6, "optimum left the ellipse ($q)")
        }
    }

    @Test
    fun simplexWalksTheBoundaryToTheOptimum() {
        val walk = pentagon.simplexWalk(c, startVertex = 0)
        assertTrue(walk.samples.all { s -> pentagon.vertices.any { abs(it.x - s.x) < 1e-6 && abs(it.y - s.y) < 1e-6 } })
        assertTrue(walk.samples.zipWithNext().all { (a, b) -> b.objective > a.objective })
        assertEquals(optimumValue, walk.last.objective, 1e-6)
    }

    @Test
    fun cuttingPlanesShrinkTheRegionTowardTheOptimum() {
        for (kind in LpCenter.entries) {
            val trace = pentagon.cuttingPlanes(c, iterations = 60, center = kind)
            assertEquals(trace.trajectory.samples.size, trace.regions.size)
            assertTrue(trace.trajectory.samples.zipWithNext().all { (a, b) -> b.scale < a.scale }, "$kind area shrinks")
            assertTrue(trace.trajectory.samples.all { it.violation < 1e-9 }, "$kind centres are feasible")
            assertTrue(distanceToOptimum(trace.trajectory.last) < 1e-2, "$kind ended ${trace.trajectory.last}")
        }
    }

    @Test
    fun polygonInputIsNormalisedOrRejectedClearly() {
        val clockwise = LpPolygon(pentagon.vertices.reversed() + pentagon.vertices.last())
        assertEquals(5, clockwise.edgeCount)
        assertTrue(clockwise.containsStrictly(0.0, 0.0))
        val fromLine = LpPolygon.of(pentagon.toFLine())
        assertEquals(5, fromLine.edgeCount)

        assertTrue(assertFailsWith<IllegalArgumentException> { LpPolygon(listOf(vec(0, 0), vec(1, 0))) }.message!!.contains("3 distinct"))
        assertTrue(assertFailsWith<IllegalArgumentException> { LpPolygon(listOf(vec(0, 0), vec(1, 1), vec(2, 2))) }.message!!.contains("no area"))
        assertTrue(assertFailsWith<IllegalArgumentException> {
            LpPolygon(listOf(vec(0, 0), vec(4, 0), vec(4, 4), vec(2, 1), vec(0, 4)))
        }.message!!.contains("reflex"))
        assertTrue(assertFailsWith<IllegalArgumentException> {
            LpPolygon(listOf(vec(0, 0), vec(2, 0), vec(4, 0), vec(4, 4), vec(0, 4)))
        }.message!!.contains("collinear"))
        assertFailsWith<IllegalArgumentException> { pentagon.centralPath(vec(0, 0)) }
        assertFailsWith<IllegalArgumentException> { pentagon.centralPath(c, doubleArrayOf(1.0, 1.0)) }
        assertFailsWith<IllegalArgumentException> { pentagon.centralPath(c, doubleArrayOf(1.0, -1.0, 1.0, 1.0, 1.0)) }
        assertTrue(LpPolygon.regular(7, 2.0).edgeCount == 7)
        assertTrue(pentagon.ellipsoid(c, 3).ellipses[0].toFLine() is FLine)
    }
}
