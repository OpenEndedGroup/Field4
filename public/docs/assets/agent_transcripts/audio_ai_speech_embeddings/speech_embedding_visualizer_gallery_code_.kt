import afield.stdlib.noir.audioai.*
import afield.stdlib.AudioVisualizer
import afield.stdlib.AudioVisualizerOptions
import afield.four.audio.WavFloatArrayLoader
import java.io.File
import java.nio.file.Path
val json = FieldAudioClient.json
val folder = File("public/docs/assets/audio_ai_speech_embeddings")
val previews = listOf("emotion2vec", "wavlm_base_plus").map {
    json.decodeFromString<AnalysisPreview>(File(folder, "$it-preview.json").readText())
}
display("speech spaces", speechEmbeddingPreviewDashboardHtml(previews, width = 1040, phaseHeight = 220))
