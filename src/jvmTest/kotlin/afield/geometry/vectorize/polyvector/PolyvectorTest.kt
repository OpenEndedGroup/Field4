package afield.geometry.vectorize.polyvector

import afield.geometry.network.NativeGeometry
import afield.media.image.Img
import afield.media.image.imgFromFile
import java.io.File
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PolyvectorTest {
    private fun available(): Boolean {
        if (!PolyvectorLibrary.isAvailable) println("SKIPPED polyvector tests: ${PolyvectorLibrary.describe()}")
        return PolyvectorLibrary.isAvailable
    }

    /** Soft pencil-like strokes (Gaussian across), the profile the method is tuned for. */
    private fun strokes(w: Int, h: Int, vararg s: DoubleArray): FloatArray = FloatArray(w * h) { i ->
        val x = i % w + 0.5; val y = i / w + 0.5
        s.maxOf { (x0, y0, x1, y1, width) ->
            val dx = x1 - x0; val dy = y1 - y0
            val t = (((x - x0) * dx + (y - y0) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
            val d = hypot(x - x0 - t * dx, y - y0 - t * dy) / (width / 2)
            exp(-d * d)
        }.toFloat()
    }

    private operator fun DoubleArray.component5() = this[4]

    private fun total(d: PolyvectorDrawing) = d.network().curves.sumOf { it.length }

    @Test
    fun capabilityIsReported() {
        assertTrue("polyvector" in NativeGeometry.names)
        assertEquals(PolyvectorLibrary.isAvailable, NativeGeometry.available("polyvector"))
        if (!PolyvectorLibrary.isAvailable) assertTrue(PolyvectorLibrary.describe().contains("brew install"))
    }

    @Test
    fun fieldHoldsTwoUnitDirectionsOnTheInkOnly() {
        if (!available()) return
        Polyvector.fitField(strokes(140, 140, doubleArrayOf(20.0, 20.0, 120.0, 120.0, 3.0), doubleArrayOf(20.0, 120.0, 120.0, 20.0, 3.0)), 140, 140).use { f ->
            var inked = 0
            for (i in 0 until 140 * 140) {
                val a = hypot(f.directions[4 * i].toDouble(), f.directions[4 * i + 1].toDouble())
                val b = hypot(f.directions[4 * i + 2].toDouble(), f.directions[4 * i + 3].toDouble())
                if (f.mask[i].toInt() == 0) { assertEquals(0.0, a); assertEquals(0.0, b) }
                else { inked++; assertTrue(abs1(a) && abs1(b), "pixel $i: |d0| = $a, |d1| = $b") }
            }
            assertTrue(inked > 400)
            assertEquals(1, f.componentCount)
        }
    }

    private fun abs1(v: Double) = kotlin.math.abs(v - 1) < 1e-3 || v == 0.0

    @Test
    fun crossingAndTee() {
        if (!available()) return
        val x = Polyvector.fitField(strokes(140, 140, doubleArrayOf(20.0, 20.0, 120.0, 120.0, 3.0), doubleArrayOf(20.0, 120.0, 120.0, 20.0, 3.0)), 140, 140).trace()
        assertTrue(total(x) in 250.0..320.0, "X: ${total(x)}")
        assertTrue(x.coverage() > 0.9, "X coverage ${x.coverage()}")
        val t = Polyvector.fitField(strokes(140, 120, doubleArrayOf(20.0, 30.0, 120.0, 30.0, 3.0), doubleArrayOf(70.0, 30.0, 70.0, 100.0, 3.0)), 140, 120).trace()
        assertTrue(total(t) in 150.0..210.0, "T: ${total(t)}")
    }

    @Test
    fun retracingReusesTheField() {
        if (!available()) return
        Polyvector.fitField(strokes(140, 120, doubleArrayOf(20.0, 30.0, 120.0, 30.0, 3.0), doubleArrayOf(70.0, 30.0, 70.0, 100.0, 3.0)), 140, 120).use { f ->
            val a = f.trace()
            val b = f.trace(TraceParameters(smooth = false))
            val c = f.trace()
            assertEquals(a.curves.map { it.toList() }, c.curves.map { it.toList() }, "same parameters, same curves")
            assertTrue(b.streamlines.size == a.streamlines.size)
        }
    }

    @Test
    fun aRealDrawing() {
        if (!available()) return
        val sample = File("build/field-polyvector/upstream").walkTopDown().firstOrNull { it.name == "penguin.png" }
        if (sample == null) { println("SKIPPED real drawing: build/field-polyvector/upstream has no samples"); return }
        val d = Polyvector.vectorize(imgFromFile(sample.path, 3))
        val net = d.network()
        assertTrue(d.curves.size in 35..48, "penguin: ${d.curves.size} curves (reference: 41)")
        assertTrue(d.coverage() > 0.9, "penguin coverage ${d.coverage()} at stroke width ${d.meanStrokeWidth}")
        assertTrue(net.curves.all { it.attributes.getValue("meanEvidence") > 0.3 }, "every curve lies on ink")
        assertEquals(0, d.droppedPoints)
        assertTrue(d.toFLines().all { it.properties["geometryNetwork"] === it.properties["geometryNetwork"] })
    }

    @Test
    fun networkJoinsOnlyCoincidentEnds() {
        if (!available()) return
        val d = Polyvector.fitField(strokes(160, 100, doubleArrayOf(15.0, 20.0, 60.0, 80.0, 5.0), doubleArrayOf(100.0, 20.0, 145.0, 80.0, 5.0)), 160, 100).trace()
        val net = d.network()
        assertEquals(2, d.field.componentCount)
        assertTrue(net.components().size >= 2, "two separate strokes stay separate")
    }

    @Test
    fun errorsAndLifecycle() {
        if (!available()) return
        assertTrue(assertFailsWith<IllegalArgumentException> { Polyvector.fitField(FloatArray(64 * 64), 64, 64) }.message!!.contains("no ink"))
        assertFailsWith<IllegalArgumentException> { Polyvector.fitField(FloatArray(10), 64, 64) }
        assertFailsWith<IllegalArgumentException> { Polyvector.fitField(FloatArray(64 * 64) { 1f }, 64, 64, FrameFieldParameters(inkThreshold = 2.0)) }
        val f = Polyvector.fitField(strokes(80, 60, doubleArrayOf(10.0, 30.0, 70.0, 30.0, 5.0)), 80, 60)
        f.close(); f.close()
        assertFailsWith<IllegalStateException> { f.trace() }
    }

    @Test
    fun imgEvidenceAndInk() {
        if (!available()) return
        val ink = strokes(100, 60, doubleArrayOf(10.0, 30.0, 90.0, 30.0, 5.0))
        val drawing = Img(100, 60, 3, FloatArray(100 * 60 * 3) { 1f - ink[it / 3] })
        val evidence = Polyvector.inkOf(drawing)
        assertTrue(kotlin.math.abs(evidence.data[30 * 100 + 50] - ink[30 * 100 + 50]) < 1e-3)
        assertTrue(Polyvector.vectorize(drawing).coverage() > 0.8)
    }
}
