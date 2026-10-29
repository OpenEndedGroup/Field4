import java.io.File

val json = FieldAudioClient.json
val folder = File("public/docs/assets/audio_ai_speech_embeddings")
val previews = ["emotion2vec", "wavlm_base_plus"].map {
    json.decodeFromString<AnalysisPreview>(File(folder, "$it-preview.json").readText())
}
display("speech spaces", speechEmbeddingPreviewDashboardHtml(previews, width = 1040, phaseHeight = 220))
