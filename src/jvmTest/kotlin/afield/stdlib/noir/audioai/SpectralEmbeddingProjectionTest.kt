package afield.stdlib.noir.audioai

import kotlinx.serialization.json.JsonObject
import org.msgpack.core.MessagePack
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpectralEmbeddingProjectionTest {
    @Test
    fun decodesPoolsAndRenormalizesEveryMelDistribution() {
        val frames = decodeSpectralEmbeddingMessagePack(
            fixture(frameCount = 8, melDimensions = 4, mfccDimensions = 2),
            maxFrames = 20,
            poolMillis = 120.0,
            hopMillis = 80.0,
        )
        assertEquals(4, frames.pooledFrameCount)
        assertEquals(listOf(0, 2, 4, 6), frames.sampledFrameIndices.toList())
        assertEquals(listOf(0.0, 0.08, 0.16, 0.24), frames.timesSeconds.toList())
        assertEquals(120.0, frames.poolMillis)
        assertEquals(80.0, frames.hopMillis)
        frames.melDistributions.forEach { distribution ->
            assertEquals(1.0, distribution.sum().toDouble(), 0.001)
            assertTrue(distribution.all { it >= 0f })
        }
    }

    @Test
    fun melWassersteinMeasuresHowFarMassMovesAlongTheMelAxis() {
        assertEquals(
            1.0,
            melWassersteinDistance(
                floatArrayOf(1f, 0f, 0f, 0f),
                floatArrayOf(0f, 0f, 0f, 1f),
            ),
            1e-9,
        )
        assertEquals(
            1.0 / 3.0,
            melWassersteinDistance(
                floatArrayOf(1f, 0f, 0f, 0f),
                floatArrayOf(0f, 1f, 0f, 0f),
            ),
            1e-9,
        )
    }

    @Test
    fun projectsMelTransportAndAttachesFourTimedAxes() {
        val frames = decodeSpectralEmbeddingMessagePack(
            fixture(frameCount = 8, melDimensions = 4, mfccDimensions = 2), maxFrames = 8,
        )
        val projection = frames.mds(dimensions = 4)
        assertEquals(SpectralEmbeddingDistance.MEL_WASSERSTEIN, projection.distance)
        assertEquals(8, projection.coordinates.size)
        assertTrue(projection.coordinates.all { it.size == 4 && it.all(Double::isFinite) })

        val preview = AnalysisPreview(
            operation = "analysis.embed_spectral",
            durationSeconds = 0.32,
            series = listOf(
                AnalysisSeries("energy_dbfs", "energy", timesSeconds = listOf(0.0), values = listOf(-30.0)),
            ),
            analysis = JsonObject(emptyMap()),
        ).withSpectralProjection(projection)
        assertEquals(4, preview.series.count { it.name.startsWith("mds_") })
        assertEquals(
            "mel Wasserstein-1",
            preview.analysis.getValue("projection_distance").toString().trim('"'),
        )
    }

    private fun fixture(frameCount: Int, melDimensions: Int, mfccDimensions: Int): ByteArray {
        val times = ByteBuffer.allocate(frameCount * Double.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frameCount) { times.putDouble(it / 25.0) }
        fun halfMatrix(dimensions: Int, value: (Int, Int) -> Int): ByteArray =
            ByteBuffer.allocate(frameCount * dimensions * Short.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN).apply {
                    repeat(frameCount) { row -> repeat(dimensions) { column ->
                        putShort(value(row, column).toShort())
                    } }
                }.array()
        val mel = halfMatrix(melDimensions) { row, column ->
            if (column == row % melDimensions) 0x3c00 else 0x0000
        }
        val logMel = halfMatrix(melDimensions) { row, column ->
            if ((row + column) % 2 == 0) 0x3c00 else 0x3800
        }
        val mfcc = halfMatrix(mfccDimensions) { row, column ->
            if ((row + column) % 3 == 0) 0xbc00 else 0x3800
        }
        val frequencies = ByteBuffer.allocate(melDimensions * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN).apply {
                repeat(melDimensions) { putFloat(100f * (it + 1)) }
            }.array()
        return MessagePack.newDefaultBufferPacker().use { packer ->
            packer.packMapHeader(15)
            packer.packString("format").packString("field.audio.spectral-embedding.v1")
            packer.packString("backend").packString("analysis.librosa")
            packer.packString("library_version").packString("test")
            packer.packString("frame_rate_hz").packDouble(25.0)
            packer.packString("frame_count").packInt(frameCount)
            packer.packString("mel_dimensions").packInt(melDimensions)
            packer.packString("mfcc_dimensions").packInt(mfccDimensions)
            packer.packString("times_dtype").packString("float64-le")
            packer.packString("matrix_dtype").packString("float16-le")
            packer.packString("mel_frequencies_dtype").packString("float32-le")
            packer.packString("times_seconds").packBinaryHeader(times.capacity()).writePayload(times.array())
            packer.packString("mel_distribution").packBinaryHeader(mel.size).writePayload(mel)
            packer.packString("log_mel_db").packBinaryHeader(logMel.size).writePayload(logMel)
            packer.packString("mfcc").packBinaryHeader(mfcc.size).writePayload(mfcc)
            packer.packString("mel_frequencies_hz").packBinaryHeader(frequencies.size).writePayload(frequencies)
            packer.toByteArray()
        }
    }
}
