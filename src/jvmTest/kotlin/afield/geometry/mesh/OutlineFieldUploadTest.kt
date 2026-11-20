package afield.geometry.mesh

import afield.geometry.path.FLine
import afield.media.image.CellRect
import afield.media.image.bufferCanvas
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A drawn outline written into a buffer the way a GPU simulation receives it: `FLine`s ->
 * [signedDistance2D] -> [afield.media.image.BufferCanvas.sample] over a direct `FloatBuffer` (what
 * `mapAndWrite` hands a box). Each uploaded value is compared with the CPU field at the cell centre
 * and with the closed-form distance.
 */
class OutlineFieldUploadTest {
    private fun square(x: Double, y: Double, s: Double) = FLine().apply {
        moveTo(x, y); lineTo(x + s, y); lineTo(x + s, y + s); lineTo(x, y + s); lineTo(x, y)
    }

    private fun polygonCircle(cx: Double, cy: Double, r: Double, n: Int = 720) = FLine().apply {
        for (k in 0..n) {
            val a = 2 * Math.PI * k / n
            if (k == 0) moveTo(cx + r * Math.cos(a), cy + r * Math.sin(a)) else lineTo(cx + r * Math.cos(a), cy + r * Math.sin(a))
        }
    }

    @Test
    fun `uploaded cells hold the CPU signed distance of the outline`() {
        val w = 64
        val h = 48
        val memory = ByteBuffer.allocateDirect(w * h * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val canvas = bufferCanvas(memory, width = w, channels = 2, height = h)
        canvas.fill(canvas.bounds, 7f)
        val circle = polygonCircle(20.0, 24.0, 12.0)
        val field = listOf(circle, square(40.0, 10.0, 16.0)).signedDistance2D()
        canvas.sample(field, x0 = 0.0, y0 = 0.0, cell = 1.0, channel = 1)

        var worstCpu = 0.0
        var worstTruth = 0.0
        for (y in 0 until h) for (x in 0 until w) {
            val cx = x + 0.5
            val cy = y + 0.5
            val uploaded = memory.get((y * w + x) * 2 + 1).toDouble()
            worstCpu = max(worstCpu, abs(uploaded - field(cx, cy)))
            assertEquals(7f, memory.get((y * w + x) * 2), "channel 0 must be untouched")
            val dCircle = hypot(cx - 20, cy - 24) - 12
            val qx = abs(cx - 48) - 8
            val qy = abs(cy - 18) - 8
            val dSquare = hypot(max(qx, 0.0), max(qy, 0.0)) + minOf(max(qx, qy), 0.0)
            worstTruth = max(worstTruth, abs(uploaded - minOf(dCircle, dSquare)))
        }
        println("outline upload: max |uploaded - cpu| = $worstCpu, max |uploaded - exact| = $worstTruth")
        assertTrue(worstCpu < 1e-5, "float upload differs from the CPU field by $worstCpu")
        // 720-gon chord error (~0.011) plus half the default sample spacing.
        assertTrue(worstTruth < 0.05, "uploaded field differs from the exact distance by $worstTruth")
        assertTrue(memory.get((24 * w + 20) * 2 + 1) < -11f, "circle centre is inside")
        assertTrue(memory.get((2 * w + 2) * 2 + 1) > 0f, "corner is outside")
    }

    @Test
    fun `sample writes only the requested rectangle and an empty drawing is far away`() {
        val a = FloatArray(16 * 16)
        val canvas = bufferCanvas(a, width = 16)
        canvas.sample(listOf<FLine>().signedDistance2D(), 0.0, 0.0, 1.0, r = CellRect(4, 4, 2, 2))
        assertEquals(Float.POSITIVE_INFINITY, a[4 * 16 + 4])
        assertEquals(0f, a[0])
        assertEquals(0f, a[6 * 16 + 6])
    }
}
