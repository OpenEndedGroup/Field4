package afield.stdlib.noir.audioai

import afield.four.synth.Param
import afield.four.synth.SineOsc
import afield.four.synth.pat
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RenderedAudioAnalysisTest {
    @Test
    fun `Pat and Dough render real analysis-ready WAVs`() {
        val directory = Files.createTempDirectory("field-render-analysis-test")
        val pat = renderPatWav(
            directory.resolve("pat.wav"), pat("60 64 67 71"), cycles = 2, cps = 2.0,
            provenance = mapOf("field_box" to "test-pat"),
        )
        val dough = renderDoughWav(
            directory.resolve("dough.wav"), SineOsc(Param(220f)), seconds = 0.25,
            provenance = mapOf("field_box" to "test-dough"),
        )

        assertEquals("Pat", pat.generator)
        assertEquals(2, pat.channels)
        assertEquals("test-pat", pat.provenance["field_box"])
        assertTrue(Files.size(pat.path) > 44)
        assertEquals("RIFF", Files.readAllBytes(pat.path).copyOfRange(0, 4).toString(Charsets.US_ASCII))

        assertEquals("Dough", dough.generator)
        assertEquals(1, dough.channels)
        assertEquals(48_000, dough.sampleRate)
        assertTrue(Files.size(dough.path) > 44)
    }

    @Test
    fun `standard request fanout can be narrowed`() {
        val region = AssetRegion("sha256:" + "a".repeat(64), 0.0, 4.0)
        val all = standardAudioAnalyses(region, requestedBy = "field:test")
        val rhythmic = standardAudioAnalyses(
            region,
            includeMonophonicPitch = false,
            includePolyphonicPitch = false,
        )

        assertEquals(listOf("analysis.beats", "analysis.pitch_mono", "analysis.pitch_poly"), all.map { it.operation.operation })
        assertEquals(listOf("beats"), rhythmic.map { it.label })
    }

    @Test
    fun `completed render visualization joins source and analysis on one sync group`() {
        val directory = Files.createTempDirectory("field-render-analysis-html")
        val render = renderDoughWav(directory.resolve("tone.wav"), SineOsc(Param(110f)), seconds = 0.1)
        val assetId = "sha256:" + "b".repeat(64)
        val source = AssetRepresentation(
            id = "rep_source", assetId = assetId, role = "original", mediaType = "audio/wav",
            sampleRate = 48_000, channels = 1, frameCount = 4_800, durationSeconds = 0.1,
            binaryUrl = "/v1/representations/rep_source/content",
        )
        val region = AssetRegion(assetId, 0.0, 0.1)
        val result = AnalysisResult(
            id = "analysis_beats", operationId = "op_beats", requestHash = "sha256:" + "c".repeat(64),
            source = region,
            data = AssetRepresentation(
                id = "rep_beats", assetId = "sha256:" + "d".repeat(64), role = "analysis_beats",
                mediaType = "application/x-npz", binaryUrl = "/v1/representations/rep_beats/content",
            ),
            createdAt = "2026-08-13T00:00:00Z",
        )
        val accepted = JobAccepted(
            "job_test", JobState.COMPLETE, "sha256:" + "e".repeat(64), "analysis.librosa", true,
        )
        val bundle = RenderedAudioAnalysis(
            render,
            AudioAsset(assetId, Files.size(render.path), "2026-08-13T00:00:00Z", listOf(source)),
            source,
            region,
            listOf(CompletedAudioAnalysis("beats", accepted, result)),
        )

        val html = bundle.visualizationHtml(syncGroup = "render-sync")
        assertTrue("render-sync" in html)
        assertTrue("analysis-results/analysis_beats/preview" in html)
        assertTrue("rep_source/content" in html)
        assertTrue("Dough" in html)
    }
}
