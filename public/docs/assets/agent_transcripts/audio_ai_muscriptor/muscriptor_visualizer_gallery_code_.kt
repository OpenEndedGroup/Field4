import afield.stdlib.AudioVisualizer
import afield.stdlib.AudioVisualizerOptions
import afield.four.audio.WavFloatArrayLoader
import java.io.File
import java.nio.file.Path
val json = FieldAudioClient.json
val folder = File("public/docs/assets/audio_ai_muscriptor")
val preview = json.decodeFromString<AnalysisPreview>(File(folder, "preview.json").readText())
val wav = WavFloatArrayLoader.loadWavToFloatArray(File(folder, "source.wav").absolutePath)
val sync = "docs-real-muscriptor"
val source = AudioVisualizer.renderSamplesHtml(wav.samples, wav.sampleRate, options = AudioVisualizerOptions(width = 1040, waveformHeight = 130, syncGroup = sync), wavBytes = File(folder, "source.wav").readBytes(), label = "Sonic Pi recording · 8.192 seconds")
display("transcription", source + analysisPreviewVisualizationHtml(preview, width = 1040, height = 400, syncGroup = sync))
