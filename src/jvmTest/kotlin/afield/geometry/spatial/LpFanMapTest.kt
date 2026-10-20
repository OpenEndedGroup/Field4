package afield.geometry.spatial

import afield.geometry.path.FLine
import afield.math.linalg.vec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LpFanMapTest {
    private val pentagon = LpPolygon(
        listOf(vec(-2.0, -1.0), vec(1.5, -1.5), vec(3.0, 1.0), vec(0.5, 2.5), vec(-2.5, 1.5))
    )
    private val weighted = pentagon.fanMap(doubleArrayOf(6.0, 1.0, 1.0, 2.0, 1.0))

    @Test
    fun jacobianMatchesCentralFiniteDifferences() {
        for (map in listOf(pentagon.fanMap(), weighted)) {
            for (theta in listOf(0.1, 1.3, 2.9, 4.4, 5.8)) {
                for (logMu in listOf(ln(50.0), 0.0, ln(0.05), ln(0.002))) {
                    val p = map.at(theta, logMu)
                    val h = 1e-5
                    val tp = map.at(theta + h, logMu, p)
                    val tm = map.at(theta - h, logMu, p)
                    val lp = map.at(theta, logMu + h, p)
                    val lm = map.at(theta, logMu - h, p)
                    val scale = hypot(p.dxdTheta.x.toDouble(), p.dxdTheta.y.toDouble()) + hypot(p.dxdLogMu.x.toDouble(), p.dxdLogMu.y.toDouble())
                    val tol = 1e-4 * scale + 1e-9
                    assertEquals((tp.x - tm.x) / (2 * h), p.dxdTheta.x.toDouble(), tol, "∂x/∂θ at θ=$theta, log μ=$logMu")
                    assertEquals((tp.y - tm.y) / (2 * h), p.dxdTheta.y.toDouble(), tol, "∂y/∂θ at θ=$theta, log μ=$logMu")
                    assertEquals((lp.x - lm.x) / (2 * h), p.dxdLogMu.x.toDouble(), tol, "∂x/∂log μ at θ=$theta, log μ=$logMu")
                    assertEquals((lp.y - lm.y) / (2 * h), p.dxdLogMu.y.toDouble(), tol, "∂y/∂log μ at θ=$theta, log μ=$logMu")
                }
            }
        }
    }

    @Test
    fun pointsAreTheCentralPathSamples() {
        val c = vec(1.0, 0.4)
        val path = pentagon.centralPath(c, samples = 20, muHigh = 10.0, muLow = 1e-3)
        val theta = kotlin.math.atan2(0.4, 1.0)
        for (s in path.samples) {
            val p = pentagon.fanMap().at(theta, ln(s.scale))
            assertTrue(hypot(p.x - s.x, p.y - s.y) < 1e-7, "fan map and centralPath agree at μ=${s.scale}")
        }
    }

    @Test
    fun determinantIsPositiveAndPinchesAtBothEnds() {
        val grid = weighted.grid(thetas = 72, logMus = 40, logMuHigh = ln(1000.0), logMuLow = ln(1e-5))
        assertTrue(grid.minDeterminant > 0.0, "the map never folds: min det ${grid.minDeterminant}")
        fun median(j: Int) = grid.points.map { it[j].jacobianDeterminant }.sorted()[grid.thetas.size / 2]
        val middle = median(20)
        assertTrue(grid.points.maxOf { it.first().jacobianDeterminant } < 1e-3 * middle, "pinches toward the analytic centre")
        // At small μ most angles land at a vertex, where det → 0. The few angles near an edge's
        // normal land along that edge instead and keep det of order 1: they cross it fast.
        assertTrue(median(39) < 1e-6 * middle, "pinches toward the vertices: median ${median(39)} vs $middle")
    }

    @Test
    fun gridStaysInsideAndIsContinuous() {
        val map = pentagon.fanMap()
        val grid = map.grid(thetas = 96, logMus = 48)
        assertTrue(grid.points.all { row -> row.all { pentagon.containsStrictly(it.x, it.y) } }, "every grid point strictly inside")
        // Continuity: quadrupling the angular resolution shrinks the largest gap between θ
        // neighbours about fourfold, and every warm-started point is the cold-started one.
        fun worstGap(g: LpFanGrid) = g.thetas.indices.maxOf { i ->
            g.logMus.indices.maxOf { j ->
                val p = g.points[i][j]
                val a = g.points[(i + 1) % g.thetas.size][j]
                hypot(a.x - p.x, a.y - p.y)
            }
        }
        // Down to μ = 0.05: below that a ring crosses each edge inside an ever narrower window of
        // angles (width about μ), so no fixed angular step resolves it.
        val coarse = map.grid(thetas = 96, logMus = 24, logMuLow = ln(0.05))
        val fine = map.grid(thetas = 4 * 96, logMus = 24, logMuLow = ln(0.05))
        val ratio = worstGap(fine) / worstGap(coarse)
        assertTrue(ratio < 0.4, "gap ratio $ratio at 4× angles")
        val gapsInMu = grid.points.maxOf { row -> row.zipWithNext().maxOf { (a, b) -> hypot(a.x - b.x, a.y - b.y) } }
        assertTrue(gapsInMu < 0.5, "largest step between μ neighbours $gapsInMu")
        for (i in grid.thetas.indices step 7) for (j in grid.logMus.indices step 5) {
            val p = grid.points[i][j]
            val cold = map.at(grid.thetas[i], grid.logMus[j])
            assertTrue(hypot(p.x - cold.x, p.y - cold.y) < 1e-7, "warm and cold agree at ($i, $j)")
        }
        // Warm-started across neighbours, the grid takes fewer steps than cold starts (about 58% of them here).
        val cold = grid.thetas.indices.sumOf { i -> grid.logMus.sumOf { l -> map.at(grid.thetas[i], l).newtonSteps } }
        assertTrue(grid.newtonSteps < 0.65 * cold, "warm grid ${grid.newtonSteps} Newton steps vs cold $cold")
        assertEquals(grid.thetas.size, grid.thetaLines().size)
        assertEquals((grid.logMus.size + 3) / 4, grid.muLines(every = 4).size)
    }

    @Test
    fun warpStaysInsideAndFollowsTheMap() {
        val map = pentagon.fanMap()
        val pattern = FLine().apply {
            moveTo(0.0, ln(50.0))
            for (k in 1..40) lineTo(2 * PI * k / 40, ln(50.0) + (ln(1e-3) - ln(50.0)) * k / 40)
            moveTo(1.0, 0.0)
            cubicTo(2.0, 1.0, 3.0, -1.0, 4.0, -3.0)
        }
        val warped = map.warp(pattern, stepsPerSegment = 8)
        assertEquals(1 + 40 * 8 + 1 + 8, warped.nodes.size)
        assertTrue(warped.nodes.all { pentagon.containsStrictly(it.to.x.toDouble(), it.to.y.toDouble()) }, "every warped point inside")
        val end = map.at(4.0, -3.0)
        val last = warped.nodes.last().to
        assertTrue(hypot(last.x - end.x, last.y - end.y) < 1e-5, "the warp ends where the map sends the end point")
    }
}
