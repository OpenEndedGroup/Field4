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
    fun chebyshevCentreIsTheCentreOfTheLargestInscribedDisc() {
        // A 4 × 2 rectangle: the largest disc has radius 1 and its centre anywhere on the midline;
        // with a triangle the centre is the incentre, unique.
        val rect = LpPolygon(listOf(vec(0.0, 0.0), vec(4.0, 0.0), vec(4.0, 2.0), vec(0.0, 2.0)))
        val r = chebyshevCenter(rect.normalX, rect.normalY, rect.bound)
        assertEquals(1.0, r[2], 1e-9)
        assertEquals(1.0, r[1], 1e-9)
        // The 3-4-5 right triangle's inradius is (3 + 4 − 5) / 2 = 1, its incentre (1, 1).
        val tri = LpPolygon(listOf(vec(0.0, 0.0), vec(4.0, 0.0), vec(0.0, 3.0)))
        val t = chebyshevCenter(tri.normalX, tri.normalY, tri.bound)
        assertEquals(1.0, t[0], 1e-9)
        assertEquals(1.0, t[1], 1e-9)
        assertEquals(1.0, t[2], 1e-9)
        // Every first cutting-plane centre for CHEBYSHEV is the pentagon's own Chebyshev centre.
        val first = pentagon.cuttingPlanes(c, iterations = 1, center = LpCenter.CHEBYSHEV).trajectory.samples.first()
        val p = chebyshevCenter(pentagon.normalX, pentagon.normalY, pentagon.bound)
        assertEquals(p[0], first.x, 1e-12)
        assertEquals(p[1], first.y, 1e-12)
        assertTrue((0 until pentagon.edgeCount).all { pentagon.slack(it, p[0], p[1]) >= p[2] - 1e-9 })
    }

    @Test
    fun volumetricCentreIsStationaryAndDiffersFromTheAnalyticCentre() {
        // On a square both centres are the middle, by symmetry.
        val square = LpPolygon.regular(4, 1.0, cx = 0.5, cy = -0.25, rotation = 0.3)
        val v = volumetricCenter(square.normalX, square.normalY, square.bound, 0.4, -0.2)
        assertEquals(0.5, v[0], 1e-7)
        assertEquals(-0.25, v[1], 1e-7)
        // A triangle with its bottom edge repeated ten times: repeats are ten constraints at the
        // analytic centre but share one leverage budget at the volumetric centre, so the two differ,
        // and the volumetric centre is where the volumetric gradient vanishes.
        val tri = LpPolygon(listOf(vec(-1.0, 0.0), vec(1.0, 0.0), vec(0.0, 2.0)))
        val nx = tri.normalX + DoubleArray(9) { tri.normalX[0] }
        val ny = tri.normalY + DoubleArray(9) { tri.normalY[0] }
        val b = tri.bound + DoubleArray(9) { tri.bound[0] }
        val volumetric = volumetricCenter(nx, ny, b, 0.0, 0.5)
        fun vol(x: Double, y: Double): Double {
            var hxx = 0.0
            var hxy = 0.0
            var hyy = 0.0
            for (i in nx.indices) {
                val s = b[i] - nx[i] * x - ny[i] * y
                hxx += nx[i] * nx[i] / (s * s)
                hxy += nx[i] * ny[i] / (s * s)
                hyy += ny[i] * ny[i] / (s * s)
            }
            return 0.5 * kotlin.math.ln(hxx * hyy - hxy * hxy)
        }
        val h = 1e-5
        val gy = (vol(volumetric[0], volumetric[1] + h) - vol(volumetric[0], volumetric[1] - h)) / (2 * h)
        val gx = (vol(volumetric[0] + h, volumetric[1]) - vol(volumetric[0] - h, volumetric[1])) / (2 * h)
        assertTrue(hypot(gx, gy) < 1e-4, "volumetric gradient ($gx, $gy)")
        assertEquals(0.0, volumetric[0], 1e-7)
        // The analytic centre of the same constraints, on the symmetry axis x = 0 by grid search:
        // the ten repeats push it well up, away from the bottom edge; the volumetric centre less so.
        var analytic = 0.0
        var bestLog = Double.NEGATIVE_INFINITY
        for (step in 1 until 4000) {
            val y = step * 0.0005
            val f = nx.indices.sumOf { kotlin.math.ln(b[it] - ny[it] * y) }
            if (f > bestLog) {
                bestLog = f
                analytic = y
            }
        }
        assertTrue(analytic - volumetric[1] > 0.05, "volumetric y ${volumetric[1]} vs analytic y $analytic")
    }

    @Test
    fun warmStartedCentralPathMatchesTheColdOneInFewerNewtonSteps() {
        val continuation = LpContinuation()
        var warmSteps = 0
        var coldSteps = 0
        for (frame in 0..40) {
            val theta = 0.02 * frame
            val direction = vec(kotlin.math.cos(theta), kotlin.math.sin(theta))
            val moved = LpPolygon(pentagon.vertices.map { vec(it.x * (1.0 + 0.002 * frame), it.y) })
            val warm = continuation.centralPath(moved, direction, samples = 90)
            val cold = moved.centralPath(direction, samples = 90)
            val gap = warm.samples.indices.maxOf { hypot(warm.samples[it].x - cold.samples[it].x, warm.samples[it].y - cold.samples[it].y) }
            assertTrue(gap < 1e-6, "frame $frame: warm and cold paths differ by $gap")
            if (frame > 0) {
                warmSteps += warm.solverSteps
                coldSteps += cold.solverSteps
            }
        }
        assertTrue(warmSteps < 0.85 * coldSteps, "warm $warmSteps Newton steps vs cold $coldSteps")
        continuation.reset()
        assertEquals(null, continuation.last)
        // A previous path with a different sample count is ignored, not misused.
        val other = pentagon.centralPath(c, samples = 40, warm = pentagon.centralPath(c, samples = 90))
        assertEquals(40, other.samples.size)
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
