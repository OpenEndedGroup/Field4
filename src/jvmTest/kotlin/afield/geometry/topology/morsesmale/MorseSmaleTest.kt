package afield.geometry.topology.morsesmale

import afield.geometry.network.NativeGeometry
import afield.media.image.Img
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MorseSmaleTest {
    private fun available(): Boolean {
        if (!MorseSmaleLibrary.isAvailable) println("SKIPPED Morse–Smale tests: ${MorseSmaleLibrary.describe()}")
        return MorseSmaleLibrary.isAvailable
    }

    private fun field(w: Int, h: Int, f: (Double, Double) -> Double) =
        FloatArray(w * h) { i -> f(2.0 * (i % w) / (w - 1) - 1, 2.0 * (i / w) / (h - 1) - 1).toFloat() }

    private fun noise(w: Int, h: Int, seed: Long): FloatArray {
        val r = java.util.Random(seed)
        return FloatArray(w * h) { r.nextFloat() }
    }

    @Test
    fun capabilityIsReportedThroughTheRegistry() {
        assertTrue("morseSmale" in NativeGeometry.names)
        assertEquals(MorseSmaleLibrary.isAvailable, NativeGeometry.available("morseSmale"))
        assertTrue(NativeGeometry.describe("morseSmale").startsWith("morseSmale: "))
        if (!MorseSmaleLibrary.isAvailable) assertTrue(MorseSmaleLibrary.describe().contains("buildMorseSmaleNative"))
    }

    @Test
    fun gaussianHillHasOneMaximum() {
        if (!available()) return
        val r = MorseSmale.compute(field(65, 65) { x, y -> exp(-4 * (x * x + y * y)) }, 65, 65)
        assertEquals(1, r.count(CriticalType.MAXIMUM))
        val peak = r.criticalPoints.single { it.type == CriticalType.MAXIMUM }
        assertTrue(kotlin.math.abs(peak.x - 32) < 1 && kotlin.math.abs(peak.y - 32) < 1, "peak at ${peak.x}, ${peak.y}")
    }

    @Test
    fun eggCrateSaddleConnectsToBothHillsAndBothPits() {
        if (!available()) return
        val r = MorseSmale.compute(field(81, 81) { x, y -> sin(PI * x) * sin(PI * y) + 1e-3 * x }, 81, 81)
        val centre = r.criticalPoints.single {
            it.type == CriticalType.SADDLE && kotlin.math.abs(it.x - 40) <= 1 && kotlin.math.abs(it.y - 40) <= 1
        }
        val arcs = r.network.curvesAt(centre.id)
        assertEquals(4, arcs.size)
        val ends = arcs.map { r.complex.critical(it.endNode!!)!!.type }.groupingBy { it }.eachCount()
        assertEquals(mapOf(CriticalType.MINIMUM to 2, CriticalType.MAXIMUM to 2), ends)
    }

    @Test
    fun networkTopologyIsConsistent() {
        if (!available()) return
        MorseSmale.build(noise(48, 40, 3), 48, 40).use { c ->
            for (t in listOf(0.0, 0.05, 0.2, Double.POSITIVE_INFINITY)) {
                val r = c.simplify(t)
                val m = r.count(CriticalType.MINIMUM); val s = r.count(CriticalType.SADDLE); val x = r.count(CriticalType.MAXIMUM)
                assertEquals(1, m - s + x, "Euler characteristic at $t")
                r.network.curves.forEach { curve ->
                    val saddle = c.critical(curve.startNode!!)!!
                    assertEquals(CriticalType.SADDLE, saddle.type)
                    val end = curve.endNode?.let { c.critical(it)!! }
                    if (curve.attributes["kind"] == 0.0) assertEquals(CriticalType.MINIMUM, end!!.type)
                    else if (end != null) assertEquals(CriticalType.MAXIMUM, end.type)
                    // the curve starts on its saddle and ends on its extremum
                    assertTrue(curve.vertex(0).distance(afield.math.linalg.Vec3(saddle.x.toFloat(), saddle.y.toFloat(), 0f)) < 1e-4)
                    if (end != null) assertTrue(curve.vertex(curve.vertexCount - 1).distance(afield.math.linalg.Vec3(end.x.toFloat(), end.y.toFloat(), 0f)) < 1e-4)
                }
                // every basin label is a surviving minimum
                val minima = r.criticalPoints.filter { it.type == CriticalType.MINIMUM }.map { it.id }.toSet()
                assertTrue(r.basins.all { it in minima }, "basin labels at $t")
            }
        }
    }

    @Test
    fun simplificationIsMonotoneAndMatchesTheHierarchy() {
        if (!available()) return
        MorseSmale.build(noise(64, 64, 11), 64, 64).use { c ->
            var previous = Int.MAX_VALUE
            c.survivalCurve().forEach { (t, expected) ->
                val n = c.simplify(t + 1e-12).criticalPoints.size
                assertEquals(expected, n, "survivors just above $t")
                assertTrue(n <= previous)
                previous = n
            }
            // cancelledAt says when each point goes
            val r = c.simplify(0.1)
            assertTrue(r.criticalPoints.all { it.cancelledAt >= 0.1 })
            assertTrue(c.criticalPoints.filter { it.cancelledAt < 0.1 }.none { p -> r.criticalPoints.any { it.id == p.id } })
        }
    }

    @Test
    fun persistencePairsCoverEveryCriticalPointOnce() {
        if (!available()) return
        MorseSmale.build(noise(40, 30, 5), 40, 30).use { c ->
            val cells = c.persistencePairs.flatMap { listOfNotNull(it.birth, it.death) }
            assertEquals(cells.size, cells.toSet().size)
            assertEquals(c.criticalPoints.map { it.id }.toSet(), cells.toSet())
            assertEquals(1, c.persistencePairs.count { it.death == null })
        }
    }

    @Test
    fun identitiesAreStableAcrossThresholds() {
        if (!available()) return
        MorseSmale.build(field(64, 64) { x, y -> sin(3 * x) * sin(2 * y) + 0.02 * sin(37 * x * y) }, 64, 64).use { c ->
            val fine = c.simplify(0.0)
            val coarse = c.simplify(0.05)
            val fineById = fine.network.curves.associateBy { it.id }
            coarse.network.curves.filter { it.id in fineById }.forEach { curve ->
                val same = fineById.getValue(curve.id)
                if (same.endNode == curve.endNode) assertTrue(same.coordinates.contentEquals(curve.coordinates))
            }
        }
    }

    @Test
    fun imgChannelIsReadInPlaceWithStrides() {
        if (!available()) return
        val one = field(32, 24) { x, y -> kotlin.math.cos(3 * x) * kotlin.math.cos(2 * y) }
        val rgb = FloatArray(32 * 24 * 3) { if (it % 3 == 1) one[it / 3] else 7f }
        val a = MorseSmale.compute(one, 32, 24)
        val b = MorseSmale.compute(Img(32, 24, 3, rgb), channel = 1)
        assertEquals(a.criticalPoints.map { it.id }, b.criticalPoints.map { it.id })
    }

    @Test
    fun fLinesKeepProvenance() {
        if (!available()) return
        val r = MorseSmale.compute(noise(32, 32, 9), 32, 32, persistence = 0.2)
        val lines = r.toFLines(smooth = true)
        assertEquals(r.network.curves.size, lines.size)
        lines.forEach { assertTrue(it.properties["geometryNetwork"] === r.network) }
        assertEquals(r.network.curves.count { it.attributes["kind"] == 0.0 }, r.toFLines(traceAscending = false).size)
        assertEquals(r.criticalPoints.size, r.criticalPointsFLine().nodes.size)
    }

    @Test
    fun errorsAreReportedNotSwallowed() {
        if (!available()) return
        val bad = FloatArray(9).also { it[4] = Float.NaN }
        assertTrue(assertFailsWith<IllegalArgumentException> { MorseSmale.build(bad, 3, 3) }.message!!.contains("NaN"))
        assertFailsWith<IllegalArgumentException> { MorseSmale.build(FloatArray(4), 1, 4) }
        assertFailsWith<IllegalArgumentException> { MorseSmale.build(FloatArray(10), 4, 4) }
    }

    @Test
    fun lifecycleCloseIsIdempotentAndUseAfterCloseFails() {
        if (!available()) return
        val c = MorseSmale.build(noise(16, 16, 1), 16, 16)
        c.simplify(0.1)
        c.close()
        c.close()
        assertTrue(c.isClosed)
        assertFailsWith<IllegalStateException> { c.simplify(0.1) }
        // many build/close cycles, and some left to the Cleaner
        repeat(500) { i -> MorseSmale.build(noise(24, 24, i.toLong()), 24, 24).let { if (i % 2 == 0) it.close() } }
        System.gc()
    }

    @Test
    fun computeAsyncCompletesOffThread() {
        if (!available()) return
        val caller = Thread.currentThread()
        val f = java.util.concurrent.CompletableFuture.supplyAsync { Thread.currentThread() }
        f.get(5, TimeUnit.SECONDS)
        val img = Img(48, 48, 1, noise(48, 48, 2))
        val r = MorseSmale.computeAsync(img, 0.1).get(30, TimeUnit.SECONDS)
        assertNotNull(r)
        assertTrue(Thread.currentThread() === caller)
    }
}
