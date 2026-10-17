import afield.ui.visualization.AudioVisualizer
import afield.ui.visualization.AudioVisualizerOptions
import afield.audio.engine.WavFloatArrayLoader
import java.io.File
import java.nio.file.Path
val json = FieldAudioClient.json
val folder = File("public/docs/assets/audio_ai_codec")
val result = json.decodeFromString<CodecReconstructionResult>(File(folder, "result.json").readText())
val data = CodecVisualization.load(result, File(folder, "source.wav").toPath(), File(folder, "reconstruction.wav").toPath(), File(folder, "tokens.npy").toPath())
display("main", CodecVisualization.renderHtml(data, width = 1040))
