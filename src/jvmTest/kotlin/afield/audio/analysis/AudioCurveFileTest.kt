package afield.audio.analysis

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AudioCurveFileTest {
    @Test
    fun readsRecordsAndFindsTheFrameAtPlaybackTime() {
        val path = Files.createTempFile("audio-curve", ".bin")
        try {
            val b = ByteBuffer.allocate(64 + 2 * 40).order(ByteOrder.LITTLE_ENDIAN)
            b.put(byteArrayOf(70, 52, 67, 85, 82, 86, 69, 0))
            b.putInt(1).putInt(8000).putInt(128).putInt(32).putInt(3).putInt(2).putLong(2)
            b.position(64)
            b.putDouble(.008).putInt(1).putInt(0).putFloat(1f).putFloat(2f)
            b.position(104)
            b.putDouble(.012).putInt(2).putInt(0).putFloat(3f).putFloat(4f).putFloat(5f).putFloat(6f)
            Files.write(path, b.array())
            AudioCurveFile(path).use { curves ->
                assertEquals(2L, curves.frameCount)
                assertNull(curves.frameAt(.007))
                assertContentEquals(floatArrayOf(1f, 2f), curves.frameAt(.008)!!.coordinates)
                assertContentEquals(floatArrayOf(3f, 4f, 5f, 6f), curves.frameAt(.013)!!.coordinates)
                assertEquals(2, curves.frameAt(10.0)!!.pointCount)
                assertFailsWith<IllegalArgumentException> { curves.frame(2) }
            }
            Files.write(path, b.array().copyOf(80))
            assertFailsWith<IllegalArgumentException> { AudioCurveFile(path) }
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
