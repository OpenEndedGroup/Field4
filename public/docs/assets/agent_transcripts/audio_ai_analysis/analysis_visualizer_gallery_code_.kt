import java.io.File

val folder = File("public/docs/assets/audio_ai_analysis")
val previews =
    ["beats", "pitch_mono", "pitch_poly"].map { label ->
        FieldAudioClient.json.decodeFromString<AnalysisPreview>(
            File(folder, "$label-preview.json").readText(),
        )
    }
markdown("source", "Nancy · 18–42 s · saved librosa and Basic Pitch results")
display(
    "analysis board",
    analysisPreviewDashboardHtml(
        previews,
        width = 1040,
        panelHeight = 250,
        syncGroup = "nancy-cached-analysis",
    ),
)
