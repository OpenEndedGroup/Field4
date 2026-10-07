import afield.stdlib.loris.LorisClient
import afield.stdlib.loris.LorisNative
import afield.stdlib.loris.LorisOptions
import afield.stdlib.loris.LorisViewOptions
import afield.stdlib.loris.visualizationHtml
import afield.stdlib.AudioVisualizer
import afield.stdlib.AudioVisualizerOptions
import afield.four.audio.WavFloatArrayLoader
import afield.stdlib.path

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
