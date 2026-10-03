# EnCodec reconstruction microscope

Field keeps codec tokens visible as compositional data. A `codec.reconstruct` operation produces an
immutable model-native input, an NPY token tensor, a model-native reconstruction, a 48 kHz audition
proxy, reconstruction metrics, and full model provenance. Here the input is a four-second,
eight-voice chord rendered by Field's Faust example. Listen to the model input and reconstruction
while comparing their spectra and the four codebook streams between them.

```diffstatic_runner {data-lang=kotlin screenshot="assets/audio_ai_codec/reconstruction_microscope.png" capture="box" open-outputs="true" wait="4" padding="16" timeout="180" w="1120" h="1100" hidden=true embed=true name="elsewhere-codec" alt="Real EnCodec input and reconstruction waveforms, spectra, measured errors and four codebook streams"}
:::static
import afield.stdlib.noir.audioai.*
import afield.stdlib.AudioVisualizer
import afield.stdlib.AudioVisualizerOptions
import afield.four.audio.WavFloatArrayLoader
import java.io.File
import java.nio.file.Path
val json = FieldAudioClient.json
val folder = File("public/docs/assets/audio_ai_codec")
val result = json.decodeFromString<CodecReconstructionResult>(File(folder, "result.json").readText())
val data = CodecVisualization.load(result, File(folder, "source.wav").toPath(), File(folder, "reconstruction.wav").toPath(), File(folder, "tokens.npy").toPath())
display("main", CodecVisualization.renderHtml(data, width = 1040))

```

Listen to the [model input](assets/audio_ai_codec/source.wav) and its [EnCodec reconstruction](assets/audio_ai_codec/reconstruction.wav).

<audio controls preload="metadata" src="assets/audio_ai_codec/source.wav"></audio>
<audio controls preload="metadata" src="assets/audio_ai_codec/reconstruction.wav"></audio>


The players contain the actual 32 kHz model input and decoded result. The error figures are the
worker's measurements, including an SNR of 6.79 dB; the 48 kHz audition proxy is a separate asset.

For a live result, submit `codecReconstruction(region)`, await it with
`client.awaitCodec(accepted)`, then call `client.codecVisualizationHtml(result, cacheDirectory)`.
The comparison uses the 32 kHz mono model input and native reconstruction so the waveforms and
log-frequency STFTs remain exactly aligned; the 48 kHz proxy remains a separately labelled audition
asset. Both waveform players use the general `AudioVisualizer`: click to seek, drag to pan, use the
wheel to zoom, and follow the optional vertical playhead during playback.
