package afield.geometry.curves.principal

import afield.geometry.network.NativeGeometry
import afield.geometry.path.FLine
import java.util.Random
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PrincipalCurvesTest {
    private fun available(): Boolean {
        if (!PrincipalCurvesLibrary.isAvailable) println("SKIPPED principal curves tests: ${PrincipalCurvesLibrary.describe()}")
        return PrincipalCurvesLibrary.isAvailable
    }

    private val r = Random(7)
    private fun g(s: Double) = r.nextGaussian() * s

    private fun cloud(n: Int, f: (Int) -> Pair<Double, Double>) = DoubleArray(2 * n).also { a ->
        for (i in 0 until n) { val (x, y) = f(i); a[2 * i] = x; a[2 * i + 1] = y }
    }

    private fun yShape() = cloud(6000) { i ->
        val t = r.nextDouble() * 60
        val ang = when (i % 3) { 0 -> -PI / 2; 1 -> PI / 2 - 0.6; else -> PI / 2 + 0.6 }
        t * cos(ang) + g(1.0) to t * sin(ang) + g(1.0)
    }

    @Test
    fun capabilityIsReported() {
        assertTrue("principalCurves" in NativeGeometry.names)
        assertEquals(PrincipalCurvesLibrary.isAvailable, NativeGeometry.available("principalCurves"))
    }

    @Test
    fun treeDecomposesIntoThreeCurvesAtOneJunction() {
        if (!available()) return
        PrincipalCurves.fit(yShape(), nodes = 22, allowBranching = true).use { f ->
            val net = f.network
            assertEquals(3, net.curves.size, "curves of a Y")
            val junction = net.nodes.ids.withIndex().single { net.nodes.channels.getValue("degree")[it.index] == 3.0 }.value
            assertEquals(3, net.curvesAt(junction).size)
            assertEquals(1, net.components().size)
            assertEquals(22, f.nodeCount)
        }
    }

    @Test
    fun drawingHelpersProduceLines() {
        if (!available()) return
        PrincipalCurves.fit(yShape(), nodes = 22, allowBranching = true).use { f ->
            assertTrue(f.widthFLines().isNotEmpty(), "width bands")
            assertTrue(f.pointsFLine(pointSize = 1.5).nodes.isNotEmpty(), "input points")
        }
    }

    @Test
    fun curvesOnlyNeverBranch() {
        if (!available()) return
        PrincipalCurves.fit(yShape(), nodes = 22).use { f ->
            assertTrue(f.network.nodes.channels.getValue("degree").data.all { it <= 2.0 })
        }
    }

    @Test
    fun parametersReachTheSolver() {
        if (!available()) return
        // A line with a band of outliers above it: only trimming keeps the curve on the line, so a
        // trimming radius that arrives as garbage would show here.
        val pts = cloud(3600) { i -> if (i < 3000) r.nextDouble() * 100 to g(0.5) else r.nextDouble() * 100 to 40 + r.nextDouble() * 20 }
        fun drift(p: ElasticParameters) = PrincipalCurves.create(pts, parameters = p).use { f ->
            f.initGraph(doubleArrayOf(0.0, 0.0, 50.0, 0.0, 100.0, 0.0), intArrayOf(0, 1, 1, 2))
            f.grow(12)
            f.nodePositions.filterIndexed { i, _ -> i % 2 == 1 }.maxOf { abs(it) } to f.energy().trimmedFraction
        }
        val (loose, noTrim) = drift(ElasticParameters())
        val (tight, trimmed) = drift(ElasticParameters(trimmingRadius = 8.0))
        assertTrue(tight < 2.0 && loose > 2 * tight, "drift untrimmed $loose, trimmed $tight")
        assertEquals(0.0, noTrim)
        assertTrue(abs(trimmed - 600.0 / 3600) < 0.03, "trimmed fraction $trimmed")
        // lambda: a stiffer graph is shorter
        val ring = cloud(3000) { val a = r.nextDouble() * 2 * PI; 50 * cos(a) + g(1.0) to 50 * sin(a) + g(1.0) }
        fun length(lambda: Double) = PrincipalCurves.create(ring, parameters = ElasticParameters(lambda = lambda)).use { f ->
            f.initComponents(1, 12); f.fit(); f.network.curves.sumOf { it.length }
        }
        assertTrue(length(1.0) < length(0.001), "lambda should shorten the graph")
    }

    @Test
    fun loopIsOneClosedCurve() {
        if (!available()) return
        val ring = cloud(3000) { val a = r.nextDouble() * 2 * PI; 50 * cos(a) + g(1.0) to 50 * sin(a) + g(1.0) }
        val circle = FLine().also { f -> for (i in 0..5) { val a = i * PI / 3; if (i == 0) f.moveTo(30 * cos(a), 30 * sin(a)) else f.lineTo(30 * cos(a), 30 * sin(a)) }; f.close() }
        PrincipalCurves.create(ring).use { f ->
            f.initFromFLines(listOf(circle), nodesPerCurve = 6)
            f.grow(30, Growth.REFINE)
            val net = f.network
            assertEquals(1, net.curves.size)
            assertTrue(net.curves[0].closed)
            val radii = (0 until net.curves[0].vertexCount).map { net.curves[0].vertex(it).let { v -> hypot(v.x.toDouble(), v.y.toDouble()) } }
            assertTrue(radii.all { it in 46.0..52.0 }, "radii $radii")
        }
    }

    @Test
    fun drawnStrokesKeepACrossingAsACrossing() {
        if (!available()) return
        val pts = cloud(6000) { i -> val t = r.nextDouble() * 100 - 50; if (i % 2 == 0) t + g(0.5) to t + g(0.5) else t + g(0.5) to -t + g(0.5) }
        val strokes = listOf(FLine().moveTo(-45, -45).lineTo(45, 45), FLine().moveTo(-45, 45).lineTo(45, -45))
        PrincipalCurves.fit(pts, nodes = 24, from = strokes).use { f ->
            val net = f.network
            assertEquals(2, net.components().size)
            assertTrue(net.nodes.channels.getValue("degree").data.all { it <= 2.0 })
        }
    }

    @Test
    fun widthMatchesAUniformBand() {
        if (!available()) return
        val band = cloud(6000) { r.nextDouble() * 200 to (r.nextDouble() * 12 - 6) }
        PrincipalCurves.create(band).use { f ->
            f.initComponents(1, 12); f.fit()
            val s = f.nodeStatistics()
            val w = s.width[6]
            assertTrue(abs(w * w - 12.0) / 12 < 0.15, "variance ${w * w}, expected 12")
            assertTrue(s.standardError[6] < w / 10, "standard error ${s.standardError[6]} against width $w")
            assertEquals(f.pointCount, f.assignments().node.size)
        }
    }

    @Test
    fun movedNodeStaysPinned() {
        if (!available()) return
        val pts = cloud(2000) { r.nextDouble() * 100 to g(1.0) }
        PrincipalCurves.fit(pts, nodes = 10).use { f ->
            f.moveNode(0, 10.0, 30.0)
            f.fit()
            assertEquals(10.0, f.nodePositions[0]); assertEquals(30.0, f.nodePositions[1])
            val k = f.nodeCount
            assertEquals(k, f.splitEdge(0, 0.5))
            assertEquals(k + 1, f.nodeCount)
        }
    }

    @Test
    fun threeDimensionalTubeHasAnisotropicCovariance() {
        if (!available()) return
        val pts = DoubleArray(8000 * 3).also { a -> for (i in 0 until 8000) { a[3 * i] = r.nextDouble() * 200; a[3 * i + 1] = g(3.0); a[3 * i + 2] = g(1.0) } }
        PrincipalCurves.create(pts, dimension = 3).use { f ->
            f.initComponents(1, 10); f.fit()
            val c = f.network.curves.single().samples.getValue("covariance")
            val mid = c.count / 2
            val a = c[mid, 0]; val b = c[mid, 1]; val d = c[mid, 2]
            val tr = a + d; val det = a * d - b * b
            val l1 = tr / 2 + kotlin.math.sqrt(tr * tr / 4 - det)
            val l2 = tr / 2 - kotlin.math.sqrt(tr * tr / 4 - det)
            assertTrue(abs(l1 - 9) < 1.2 && abs(l2 - 1) < 0.25, "eigenvalues $l1, $l2")
        }
    }

    @Test
    fun refineFollowsMovingPoints() {
        if (!available()) return
        fun line(y: Double) = cloud(2000) { r.nextDouble() * 100 to y + g(0.5) }
        PrincipalCurves.fit(line(0.0), nodes = 12).use { f ->
            val k = f.nodeCount
            repeat(20) { step -> f.setPoints(line(step + 1.0)); f.refine(5) }
            assertEquals(k, f.nodeCount, "refining keeps the graph")
            val meanY = f.nodePositions.filterIndexed { i, _ -> i % 2 == 1 }.average()
            assertTrue(abs(meanY - 20.0) < 1.0, "followed to y = $meanY, expected 20")
        }
    }

    @Test
    fun errorsAndLifecycle() {
        if (!available()) return
        assertFailsWith<IllegalArgumentException> { PrincipalCurves.create(doubleArrayOf(0.0, Double.NaN, 1.0, 1.0)) }
        val f = PrincipalCurves.create(cloud(100) { it.toDouble() to 0.0 })
        assertTrue(assertFailsWith<IllegalArgumentException> { f.fit() }.message!!.contains("initialise"))
        f.close(); f.close()
        assertFailsWith<IllegalStateException> { f.fit() }
        repeat(200) { PrincipalCurves.create(cloud(50) { i -> i.toDouble() to (i % 3).toDouble() }).let { if (it.hashCode() % 2 == 0) it.close() } }
        System.gc()
    }

    @Test
    fun fitAsyncCompletes() {
        if (!available()) return
        PrincipalCurves.fitAsync(yShape(), nodes = 12).get(60, TimeUnit.SECONDS).use { assertEquals(12, it.nodeCount) }
    }
}
