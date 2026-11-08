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
                assertNull(curves.emaLongestSeconds)
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

    /** `fieldaudio_curve.ema` marks its files with kind 1 at byte 40 and the longest time constant at 44. */
    @Test
    fun readsTheEmaKindFromTheReservedHeaderBytes() {
        val path = Files.createTempFile("audio-curve-ema", ".bin")
        try {
            val b = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
            b.put(byteArrayOf(70, 52, 67, 85, 82, 86, 69, 0))
            b.putInt(1).putInt(8000).putInt(128).putInt(32).putInt(3).putInt(2).putLong(0)
            b.putInt(1).putDouble(20.0)
            Files.write(path, b.array())
            AudioCurveFile(path).use { assertEquals(20.0, it.emaLongestSeconds) }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun readsAMapFileAndEachFramesBestMatch() {
        val path = Files.createTempFile("audio-curve-som", ".bin")
        try {
            // Three prototypes plus the present: 4 points of 2 floats per record.
            val b = ByteBuffer.allocate(64 + 16 + 4 * 4 * 2).order(ByteOrder.LITTLE_ENDIAN)
            b.put(byteArrayOf(70, 52, 67, 85, 82, 86, 69, 0))
            b.putInt(1).putInt(8000).putInt(128).putInt(32).putInt(4).putInt(2).putLong(1)
            b.putInt(2).putDouble(0.5).put(ByteArray(12))
            b.putDouble(0.008).putInt(4).putInt(1)
            repeat(8) { b.putFloat(it.toFloat()) }
            Files.write(path, b.array())
            AudioCurveFile(path).use {
                assertEquals(0.5, it.somLearnSeconds)
                assertEquals(null, it.emaLongestSeconds)
                val frame = it.frame(0)
                assertEquals(1, frame.bestMatch)
                assertEquals(4, frame.pointCount)
                assertEquals(3, frame.flines().size, "two chain segments and the tether")
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun readsASheetsGridAndDrawsItsEdges() {
        val path = Files.createTempFile("audio-curve-sheet", ".bin")
        try {
            // A 2 × 2 sheet plus the present: 5 points of 3 floats.
            val b = ByteBuffer.allocate(64 + 16 + 5 * 3 * 4).order(ByteOrder.LITTLE_ENDIAN)
            b.put(byteArrayOf(70, 52, 67, 85, 82, 86, 69, 0))
            b.putInt(1).putInt(8000).putInt(128).putInt(32).putInt(5).putInt(3).putLong(1)
            b.putInt(2).putDouble(0.5).putInt(2).putInt(2).putInt(1)
            b.putDouble(0.008).putInt(5).putInt(3)
            repeat(15) { b.putFloat(it.toFloat()) }
            Files.write(path, b.array())
            AudioCurveFile(path).use {
                assertEquals(listOf(2, 2), it.somGrid)
                val frame = it.frame(0)
                assertEquals(listOf(2, 2), frame.somGrid)
                assertEquals(3, frame.bestMatch)
                assertEquals(5, frame.flines().size, "four sheet edges and the tether")
                assertEquals(7, frame.flines(trail = listOf(0, 1, 3)).size, "and two trail segments")
                assertEquals(5, frame.flines(stretch = true).size)
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
