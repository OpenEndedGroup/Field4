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
    fun wrapsReusableShortTimeSpectralRepresentations() {
        val request = spectralEmbeddingAnalysis(source, nMels = 80, nMfcc = 24)
        assertEquals("analysis.embed_spectral", request.operation)
        assertEquals("analysis.librosa", request.backendPreference)
        assertEquals(882, request.parameters.getValue("hop_length").jsonPrimitive.content.toInt())
        assertEquals(80, request.parameters.getValue("n_mels").jsonPrimitive.content.toInt())
        assertEquals(24, request.parameters.getValue("n_mfcc").jsonPrimitive.content.toInt())
    }

    @Test
    fun wrapsBothLearnedSpeechSpacesBehindOneOperation() {
        val emotion = emotion2VecEmbeddingAnalysis(source, mdsLandmarks = 128)
        val wavlm = wavlmBasePlusEmbeddingAnalysis(source, layer = 7, chunkSeconds = 12.0)

        assertEquals("analysis.embed_speech", emotion.operation)
        assertEquals("analysis.emotion2vec", emotion.backendPreference)
        assertEquals("analysis.embed_speech", wavlm.operation)
        assertEquals("analysis.wavlm_base_plus", wavlm.backendPreference)
        assertEquals(7, wavlm.parameters.getValue("wavlm_layer").jsonPrimitive.content.toInt())
        assertEquals(12.0, wavlm.parameters.getValue("chunk_seconds").jsonPrimitive.content.toDouble())
        assertEquals(4, wavlm.parameters.getValue("mds_dimensions").jsonPrimitive.content.toInt())
    }

    @Test
    fun wrapsRevisionPinnedMuscriptorTranscription() {
        val request = musicTranscriptionAnalysis(
            source,
            backend = MuscriptorBackend.MEDIUM,
            instruments = listOf(MuscriptorInstrument.CELLO, MuscriptorInstrument.VIOLIN),
            tempoDetection = MuscriptorTempoDetection.OFF,
            requestedBy = "field:test/muscriptor",
        )

        assertEquals("analysis.transcribe_music", request.operation)
        assertEquals("analysis.muscriptor.medium", request.backendPreference)
        assertEquals(
            listOf("cello", "violin"),
            request.parameters.getValue("instruments").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(false, request.parameters.getValue("detect_tempo").jsonPrimitive.content.toBoolean())
        assertTrue(request.parameters.getValue("prelude_forcing").jsonPrimitive.content.toBoolean())
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
