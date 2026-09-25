package afield.stdlib.noir.audioai

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CorpusOperationsTest {
    private val assetId = "sha256:" + "d".repeat(64)
    private val source = AssetRegion(assetId, 18.0, 6.0)

    @Test
    fun wrapsSegmentEmbeddingAndNeighbourRequests() {
        val whole = AssetRegion(assetId, 0.0, 48.0)
        val segments = segmentCorpus(whole)
        val embedding = embedCorpus(whole, scalesSeconds = listOf(3.0, 6.0), overlap = 0.25)
        val neighbours = findCorpusNeighbours(
            source,
            limit = 8,
            weights = CorpusDistanceWeights(texture = 2.0, harmony = 0.2),
            includeSameAsset = false,
        )
        assertEquals("analysis.segment", segments.operation)
        assertEquals("analysis.embed", embedding.operation)
        assertEquals(listOf(3.0, 6.0), embedding.parameters.getValue("scales_seconds")
            .jsonArray.map { it.jsonPrimitive.content.toDouble() })
        assertEquals("analysis.find_neighbours", neighbours.operation)
        assertEquals(8, neighbours.parameters.getValue("limit").jsonPrimitive.content.toInt())
        assertEquals(
            2.0,
            neighbours.parameters.getValue("weights").jsonObject
                .getValue("texture").jsonPrimitive.content.toDouble(),
        )
        assertEquals(false, neighbours.parameters.getValue("include_same_asset").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun refusesAQueryWhoseDurationDoesNotMatchItsScale() {
        assertFailsWith<IllegalArgumentException> {
            findCorpusNeighbours(source, scaleSeconds = 3.0)
        }
    }

    @Test
    fun microscopeUsesRelativeTimeAndExistingWaveformEndpoints() {
        val queryAudio = wavRepresentation("query", assetId, 60.0)
        val neighbourAsset = "sha256:" + "e".repeat(64)
        val neighbourAudio = wavRepresentation("neighbour", neighbourAsset, 90.0)
        val splice = wavRepresentation("splice", "sha256:" + "f".repeat(64), 4.45)
        val preview = CorpusPreview(
            operation = "analysis.find_neighbours",
            query = source,
            scaleSeconds = 6.0,
            indexedSegmentCount = 42,
            weights = mapOf("texture" to 1.0),
            neighbours = listOf(CorpusNeighbour(
                segment = CorpusSegment(
                    id = "segment_one", assetId = neighbourAsset,
                    startSeconds = 31.0, durationSeconds = 6.0, scaleSeconds = 6.0,
                    previousSegmentId = "segment_zero", nextSegmentId = "segment_two",
                ),
                distance = 0.2,
                groupDistances = mapOf("texture" to 0.1),
                audio = neighbourAudio,
                splicePreview = splice,
            )),
        )
        val html = corpusMicroscopeHtml(preview, queryAudio)
        assertTrue("corpus microscope" in html)
        assertTrue("query → neighbour splice" in html)
        // Relative time reaches the player through the time-axis viewer's JSON config rather
        // than a data-time-offset attribute. The assertion is unchanged in substance: the query
        // at t=18 and the neighbour at t=31 each play against their own zero.
        assertTrue("\"syncOffset\":-18.0" in html)
        assertTrue("\"syncOffset\":-31.0" in html)
        assertTrue("representations/neighbour/waveform" in html)
        assertTrue("42 eligible indexed windows" in html)
    }

    private fun wavRepresentation(id: String, assetId: String, duration: Double) = AssetRepresentation(
        id = id,
        assetId = assetId,
        role = "canonical_project",
        mediaType = "audio/wav",
        sampleRate = 48_000,
        channels = 2,
        durationSeconds = duration,
        binaryUrl = "/v1/representations/$id/content",
    )
}
