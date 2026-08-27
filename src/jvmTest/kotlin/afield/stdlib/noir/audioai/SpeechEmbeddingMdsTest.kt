package afield.stdlib.noir.audioai

import afield.stdlib.SmileMdsDistance
import kotlinx.serialization.json.JsonObject
import org.msgpack.core.MessagePack
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeechEmbeddingMdsTest {
    @Test
    fun retainsEveryFrameInsideAShortTimeWindow() {
        val frames = decodeSpeechEmbeddingMessagePack(
            fixture(frameCount = 20, dimensions = 6),
            maxFrames = 100,
            startSeconds = 0.04,
            durationSeconds = 0.10,
        )

        assertEquals(listOf(2, 3, 4, 5, 6), frames.sampledFrameIndices.toList())
        assertEquals(listOf(0.04, 0.06, 0.08, 0.10, 0.12), frames.timesSeconds.toList())
        assertEquals(20.0, frames.poolMillis)
        assertEquals(20.0, frames.hopMillis)
    }

    @Test
    fun meanPoolsCenteredWindowsAtAnIndependentHop() {
        val frames = decodeSpeechEmbeddingMessagePack(
            fixture(frameCount = 6, dimensions = 1),
            maxFrames = 100,
            poolMillis = 60.0,
            hopMillis = 40.0,
        )

        assertEquals(6, frames.sourceFrameCount)
        assertEquals(3, frames.pooledFrameCount)
        assertEquals(60.0, frames.poolMillis)
        assertEquals(40.0, frames.hopMillis)
        assertEquals(listOf(0, 2, 4), frames.sampledFrameIndices.toList())
        assertEquals(listOf(0.0, 0.04, 0.08), frames.timesSeconds.toList())
        assertEquals(0.75f, frames.vectors[0][0], 0.0001f)
        assertEquals(-1.0f / 12.0f, frames.vectors[1][0], 0.0001f)
        assertEquals(1.0f / 6.0f, frames.vectors[2][0], 0.0001f)
    }

    @Test
    fun appliesMaxFramesAfterTemporalPooling() {
        val frames = decodeSpeechEmbeddingMessagePack(
            fixture(frameCount = 10, dimensions = 1),
            maxFrames = 2,
            poolMillis = 60.0,
            hopMillis = 40.0,
        )

        assertEquals(5, frames.pooledFrameCount)
        assertEquals(listOf(0, 8), frames.sampledFrameIndices.toList())
        assertEquals(listOf(0.0, 0.16), frames.timesSeconds.toList())
    }

    @Test
    fun decodesOnlyRequestedRowsAndProjectsThemWithSmile() {
        val bytes = fixture(frameCount = 10, dimensions = 6)
        val frames = decodeSpeechEmbeddingMessagePack(bytes, maxFrames = 5)

        assertEquals(10, frames.sourceFrameCount)
        assertEquals(6, frames.sourceDimensions)
        assertEquals(listOf(0, 2, 4, 6, 9), frames.sampledFrameIndices.toList())
        assertEquals(listOf(0.0, 0.04, 0.08, 0.12, 0.18), frames.timesSeconds.toList())
        assertEquals(1.0f, frames.vectors.first()[0])
        assertEquals("analysis.wavlm_base_plus", frames.backend)
        assertEquals(6, frames.wavlmLayer)

        val projection = frames.mds(dimensions = 4, distance = SmileMdsDistance.COSINE)
        assertEquals(5, projection.coordinates.size)
        assertTrue(projection.coordinates.all { it.size == 4 })

        val base = AnalysisPreview(
            operation = "analysis.embed_speech",
            durationSeconds = 0.2,
            series = listOf(
                AnalysisSeries("voice_activity", "voice", timesSeconds = listOf(0.0), values = listOf(1.0)),
                AnalysisSeries("mds_0", "worker MDS", timesSeconds = listOf(0.0), values = listOf(9.0)),
            ),
            analysis = JsonObject(emptyMap()),
        )
        val fieldPreview = base.withSpeechMds(projection)
        assertEquals(4, fieldPreview.series.count { it.name.startsWith("mds_") })
        assertEquals(
            "field",
            fieldPreview.analysis.getValue("projection_owner").toString().trim('"'),
        )
        assertEquals("20.0", fieldPreview.analysis.getValue("projection_pool_millis").toString())
        assertEquals("20.0", fieldPreview.analysis.getValue("projection_hop_millis").toString())
    }

    private fun fixture(frameCount: Int, dimensions: Int): ByteArray {
        val times = ByteBuffer.allocate(frameCount * Double.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frameCount) { times.putDouble(it / 50.0) }
        val vectors = ByteBuffer.allocate(frameCount * dimensions * Short.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        repeat(frameCount) { row ->
            repeat(dimensions) { column ->
                val code = when ((row + column) % 4) {
                    0 -> 0x3c00 // 1
                    1 -> 0x3800 // .5
                    2 -> 0x3400 // .25
                    else -> 0xbc00 // -1
                }
                vectors.putShort(code.toShort())
            }
        }

        return MessagePack.newDefaultBufferPacker().use { packer ->
            packer.packMapHeader(11)
            packer.packString("format").packString("field.audio.speech-embedding.v1")
            packer.packString("backend").packString("analysis.wavlm_base_plus")
            packer.packString("model_id").packString("microsoft/wavlm-base-plus")
            packer.packString("model_revision").packString("test-revision")
            packer.packString("wavlm_layer").packInt(6)
            packer.packString("frame_rate_hz").packDouble(50.0)
            packer.packString("shape").packArrayHeader(2).packInt(frameCount).packInt(dimensions)
            packer.packString("times_dtype").packString("float64-le")
            packer.packString("embedding_dtype").packString("float16-le")
            packer.packString("times_seconds").packBinaryHeader(times.capacity()).writePayload(times.array())
            packer.packString("embedding").packBinaryHeader(vectors.capacity()).writePayload(vectors.array())
            packer.toByteArray()
        }
    }
}
