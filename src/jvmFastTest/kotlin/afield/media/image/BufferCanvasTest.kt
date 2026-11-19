package afield.media.image

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.test.*

class BufferCanvasTest {

    @Test
    fun editsWriteIntoTheCallersArray() {
        val a = FloatArray(16 * 8)
        val c = bufferCanvas(a, width = 16)
        assertEquals(8, c.height)
        c[3, 2] = 0.7f
        assertEquals(0.7f, a[2 * 16 + 3])
        c.fill(CellRect(0, 0, 2, 2), 1f)
        assertEquals(1f, a[17])
    }

    @Test
    fun strideAndOffsetAddressASubWindow() {
        val a = FloatArray(100) { it.toFloat() }
        val c = bufferCanvas(a, width = 3, offset = 5, rowStride = 10, height = 4)
        assertEquals(5f, c[0, 0])
        assertEquals(27f, c[2, 2])
        c.fill(c.bounds, -1f)
        assertEquals(4f, a[4])
        assertEquals(8f, a[8])
        assertEquals(-1f, a[35 + 2])
    }

    @Test
    fun paintMovesTowardTheValueAndOnlyNearTheBrush() {
        val a = FloatArray(32 * 32)
        val c = bufferCanvas(a, width = 32)
        c.paint(16.0, 16.0, radius = 4.0, value = 1f, hardness = 1.0)
        assertEquals(1f, c[16, 16])
        assertEquals(0f, c[0, 0])
        c.paint(16.0, 16.0, radius = 4.0, value = 0f, opacity = 0.5, hardness = 1.0)
        assertEquals(0.5f, c[16, 16])
    }

    @Test
    fun copyPasteRoundTripsExactValuesIncludingOutOfRange() {
        val a = FloatArray(10 * 10) { (it - 50) * 3.5f }
        val c = bufferCanvas(a, width = 10)
        val clip = c.copy(CellRect(1, 1, 3, 3))
        c.paste(clip, 6, 6)
        for (j in 0 until 3) for (i in 0 until 3) assertEquals(c[1 + i, 1 + j], c[6 + i, 6 + j])
    }

    @Test
    fun blurConservesMassOnAWrappingCanvas() {
        val a = FloatArray(20 * 20)
        val c = bufferCanvas(a, width = 20, wrapX = true, wrapY = true)
        c[0, 0] = 100f
        val before = c.sum()
        c.blur(sigma = 1.5)
        assertTrue(abs(c.sum() - before) < 1e-3, "sum ${c.sum()} vs $before")
        assertTrue(c[19, 19] > 0f, "blur should wrap across the corner")
        assertTrue(c[0, 0] < 100f)
    }

    @Test
    fun motionBlurSmearsAlongTheDirectionOnly() {
        val a = FloatArray(21 * 21)
        val c = bufferCanvas(a, width = 21)
        c[10, 10] = 5f
        c.motionBlur(length = 5, dx = 1.0, dy = 0.0)
        assertEquals(1f, c[8, 10], 1e-6f)
        assertEquals(1f, c[12, 10], 1e-6f)
        assertEquals(0f, c[10, 9])
        assertEquals(0f, c[13, 10])
    }

    @Test
    fun ringBufferMotionBlurWrapsInTime() {
        val ring = FloatArray(64)
        ring[0] = 3f
        val c = bufferCanvas(ring, width = 64, wrapX = true)
        c.motionBlur(length = 3)
        assertEquals(1f, ring[63], 1e-6f)
        assertEquals(1f, ring[1], 1e-6f)
    }

    @Test
    fun smudgeCarriesValueAlongTheDrag() {
        val a = FloatArray(40 * 20)
        val c = bufferCanvas(a, width = 40)
        c.fill(CellRect(2, 6, 8, 8), 1f)
        c.smudge(6.0, 10.0, 30.0, 10.0, radius = 3.0)
        assertTrue(c[22, 10] > 0.1f, "value should have been dragged: ${c[22, 10]}")
        assertEquals(0f, c[38, 10])
    }

    @Test
    fun rawMapMarksInsteadOfClamping() {
        val c = bufferCanvas(floatArrayOf(0f, 0.5f, 1f, -0.2f, 3f, Float.NaN), width = 6)
        val px = c.pixels()
        assertEquals(0xff808080.toInt(), px[1])
        assertEquals(BufferCanvas.Clipped(1, 1, 1), c.lastClipped)
        assertEquals(0xffff00ff.toInt(), px[5])
        c.map = ValueMap.Linear(-1.0, 3.0)
        c.pixels()
        assertEquals(BufferCanvas.Clipped(0, 0, 1), c.lastClipped)
        assertTrue(c.toHTML().contains("linear -1.0…3.0"))
    }

    @Test
    fun byteAndFloatBuffersReadTheirOwnNumbers() {
        val bytes = ByteBuffer.allocateDirect(8)
        val u8 = bufferCanvas(bytes, width = 4, type = BufferElement.U8)
        u8[1, 0] = 300f
        assertEquals(255f, u8[1, 0])
        val f32 = bufferCanvas(ByteBuffer.allocateDirect(64).order(ByteOrder.LITTLE_ENDIAN), width = 4, type = BufferElement.F32, order = ByteOrder.LITTLE_ENDIAN)
        f32.paint(1.5, 1.5, 1.0, 2.5f, hardness = 1.0)
        assertEquals(2.5f, f32[1, 1])
        val fb = FloatBuffer.allocate(4 * 4 * 4)
        val vec4 = bufferCanvas(fb, width = 4, channels = 4)
        vec4.fill(CellRect(0, 0, 1, 1), floatArrayOf(1f, 2f, 3f, 4f))
        assertEquals(3f, fb.get(2))
    }
}
