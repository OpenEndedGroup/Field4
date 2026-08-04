package afield.stdlib.noir.audioai

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalysisOperationsTest {
    private val source = AssetRegion("sha256:" + "a".repeat(64), 12.5, 8.0)

    @Test
    fun wrapsMultiScaleBeatRequest() {
        val request = multiScaleBeatAnalysis(
            source,
            scalesSeconds = listOf(1.5, 3.0, 6.0),
            requestedBy = "field:test",
        )
        assertEquals("analysis.beats", request.operation)
        assertEquals("analysis.librosa", request.backendPreference)
        assertEquals(1, request.candidateCount)
        assertEquals(
            listOf(1.5, 3.0, 6.0),
            request.parameters.getValue("scales_seconds").jsonArray.map { it.jsonPrimitive.content.toDouble() },
        )
    }

    @Test
    fun wrapsBothPitchTrackers() {
        val mono = monophonicPitchAnalysis(source, fminHz = 40.0, fmaxHz = 1200.0)
        val poly = polyphonicPitchAnalysis(source, multiplePitchBends = true)
        assertEquals("analysis.pitch_mono", mono.operation)
        assertEquals("analysis.librosa", mono.backendPreference)
        assertEquals("analysis.pitch_poly", poly.operation)
        assertEquals("analysis.basic_pitch", poly.backendPreference)
        assertTrue(poly.parameters.getValue("multiple_pitch_bends").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun rendersRangeAwareAnalysisCanvas() {
        val result = AnalysisResult(
            id = "analysis_test",
            operationId = "op_test",
            requestHash = "sha256:" + "b".repeat(64),
            source = source,
            data = AssetRepresentation(
                id = "rep_test",
                assetId = "sha256:" + "c".repeat(64),
                role = "analysis_pitch_poly",
                mediaType = "application/x-npz",
                binaryUrl = "/v1/representations/rep_test/content",
            ),
            createdAt = "2026-08-03T00:00:00Z",
        )
        val html = analysisVisualizationHtml(result)
        assertTrue("analysis-results/analysis_test/preview" in html)
        assertTrue("wheel to zoom" in html)
        assertTrue("pitch poly" in html)
    }
}
