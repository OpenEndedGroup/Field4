package afield.stdlib.noir.audioai

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class AnalysisVisualizationTest {
    private fun series(name: String, label: String = name) = AnalysisSeries(
        name = name,
        label = label,
        timesSeconds = listOf(0.0, 0.5, 1.0),
        values = listOf(0.1, 0.8, 0.2),
    )

    @Test
    fun embeddedPreviewUsesTheSameInteractiveRendererWithoutAServiceFetch() {
        val preview = AnalysisPreview(
            operation = "analysis.beats",
            durationSeconds = 1.0,
            series = listOf(
                series("onset_strength", "onset strength"),
                series("tempo_0_bpm", "tempo · 2 s"),
                series("tempo_0_confidence", "confidence · 2 s"),
            ),
            beatTimesSeconds = listOf(0.25, 0.75),
        )

        val html = analysisPreviewVisualizationHtml(
            preview, width = 900, syncGroup = "cue-a", timeOffsetSeconds = 18.0,
        )

        assertContains(html, "analysis-preview-data")
        assertContains(html, "local tempo")
        assertContains(html, "tempo confidence")
        assertContains(html, "field-analysis-window")
        assertContains(html, "wheel to zoom")
        assertContains(html, "data-afield-intrinsic-width=\"900\"")
        assertContains(html, "data-sync=\"cue-a\"")
        assertContains(html, "data-time-offset=\"18.0\"")
        assertContains(html, "field-time-cursor")
        assertContains(html, "field-time-seek")
        assertContains(html, "timeBus.dispatchEvent")
    }

    @Test
    fun dashboardSynchronizesDistinctAnalysisViews() {
        val previews = listOf(
            AnalysisPreview("analysis.pitch_mono", 2.0, listOf(series("frequency_hz"), series("voiced_probability"))),
            AnalysisPreview(
                "analysis.pitch_poly",
                2.0,
                listOf(series("maximum_note_activation"), series("active_pitch_count")),
                notes = listOf(AnalysisNote(0.2, 0.8, 60, 0.9)),
            ),
        )

        val html = analysisPreviewDashboardHtml(previews, width = 960, syncGroup = "explicit-group")

        assertContains(html, "analysis board")
        assertContains(html, "shared playhead/cursor")
        assertContains(html, "pYIN pitch")
        assertContains(html, "Basic Pitch piano roll")
        assertContains(html, "data-sync=\"explicit-group\"")
    }

    @Test
    fun statusCardCarriesProgressAndStructuredFailure() {
        val accepted = JobAccepted("job_1", JobState.QUEUED, "sha256:req", "analysis.librosa")
        val event = ProgressEvent(2, "job_1", JobState.ANALYSING, 0.65, "tracking beats", null, "now")
        assertContains(fieldAudioJobStatusHtml(accepted, event), "65% · tracking beats")

        val failed = JobRecord(
            "job_1", "op_1", "sha256:req", JobState.FAILED, "analysis.librosa", 1.0,
            "then", "now", StructuredError("ANALYSIS_FAILED", "pitch bounds exceed Nyquist"),
        )
        val failureHtml = fieldAudioJobStatusHtml(failed)
        assertContains(failureHtml, "ANALYSIS_FAILED")
        assertContains(failureHtml, "pitch bounds exceed Nyquist")
    }

    @Test
    fun dashboardRejectsAnEmptyResultSet() {
        assertFailsWith<IllegalArgumentException> { analysisPreviewDashboardHtml(emptyList()) }
    }
}
