import afield.ui.visualization.AudioVisualizer
import afield.ui.visualization.AudioVisualizerOptions
import afield.audio.engine.WavFloatArrayLoader
import afield.document.workspace.path

val path = "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path
val sync = "loris-source-study"
val client = LorisClient()
display("worker", LorisNative.describe())
client.analyzeWavAsync(path,
    options = LorisOptions(resolutionHz = 35.0, windowWidthHz = 80.0,
        frequencyDriftHz = 50.0, amplitudeFloorDb = -55.0),
    onProgress = { display("status", it) },
).thenAccept { analyzed ->
    val wav = WavFloatArrayLoader.loadWavToFloatArray(path.toString())
    val source = AudioVisualizer.renderSamplesHtml(wav.samples, wav.sampleRate,
        AudioVisualizerOptions(width = 1000, waveformHeight = 95, syncGroup = sync), label = "Source")
    display("source and reconstruction", source + analyzed.visualizationHtml(
        LorisViewOptions(height = 400, syncGroup = sync)))
    display("provenance", analyzed.provenance)
}.exceptionally { error ->
    display("error", error.cause?.message ?: error.message ?: error.toString())
    null
}
