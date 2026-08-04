package afield.stdlib.noir.audioai

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodecVisualizationTest {
    @Test
    fun readsNumpyInt64TokenTensor() {
        val bytes = npy(longArrayOf(1, 2, 3, 4, 5, 6), intArrayOf(1, 2, 3))
        val tensor = NpyIntTensor.decode(bytes)
        assertContentEquals(intArrayOf(1, 2, 3), tensor.shape)
        assertEquals(2, tensor.codebooks)
        assertEquals(3, tensor.frames)
        assertEquals(5, tensor[1, 1])
    }

    @Test
    fun rendersSharedCodecMicroscope() {
        val sampleRate = 8000
        val source = FloatArray(sampleRate) { i ->
            (0.7 * sin(2 * PI * 330 * i / sampleRate)).toFloat()
        }
        val reconstruction = FloatArray(sampleRate) { i -> source[i] * 0.92f }
        val tokens = NpyIntTensor(intArrayOf(1, 4, 50), LongArray(200) { (it * 37L) % 2048 })
        val html = CodecVisualization.renderHtml(
            CodecVisualizationData(
                source, reconstruction, sampleRate, tokens,
                ReconstructionMetrics(0.01, 18.2, 0.11), 50.0, 2048, "test-codec"
            ),
            width = 320,
        )
        assertTrue("EnCodec reconstruction microscope" in html)
        assertTrue("data:image/png;base64," in html)
        assertTrue("4 codebooks × 50 frames" in html)
    }

    private fun npy(values: LongArray, shape: IntArray): ByteArray {
        val dictionary = "{'descr': '<i8', 'fortran_order': False, 'shape': (${shape.joinToString(", ")},), }"
        val headerLength = ((dictionary.length + 1 + 63) / 64) * 64
        val header = (dictionary + " ".repeat(headerLength - dictionary.length - 1) + "\n")
            .toByteArray(Charsets.US_ASCII)
        return ByteBuffer.allocate(10 + header.size + values.size * 8).order(ByteOrder.LITTLE_ENDIAN)
            .put(byteArrayOf(0x93.toByte(), 'N'.code.toByte(), 'U'.code.toByte(), 'M'.code.toByte(),
                'P'.code.toByte(), 'Y'.code.toByte()))
            .put(1).put(0).putShort(header.size.toShort()).put(header)
            .also { buffer -> values.forEach(buffer::putLong) }
            .array()
    }
}
