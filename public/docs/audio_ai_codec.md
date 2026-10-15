# EnCodec round trip

*To run the examples on this page, open the [audio_ai_codec](agent_transcripts.md#audio_ai_codec) starting point.*

EnCodec compresses audio into a few streams of tokens and decodes them back. Running a
`codec.reconstruct` operation on a sound shows you both ends and the tokens in between: the input
as the model hears it, the token streams (one per codebook), the reconstruction, and how far it
differs from the input. Here the input is a four-second, eight-voice chord rendered by Field's
Faust example.

```diffstatic_runner {data-lang=kotlin screenshot="assets/audio_ai_codec/reconstruction_microscope.png" capture="box" open-outputs="true" wait="4" padding="16" timeout="180" w="1120" h="1100" hidden=true embed=true name="elsewhere-codec" alt="Real EnCodec input and reconstruction waveforms, spectra, measured errors and four codebook streams"}
:::static
import afield.audio.engine.WavFloatArrayLoader
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


Both players hold the 32 kHz mono audio the model actually reads and writes, so the waveforms
and spectra line up sample for sample. The operation also returns a 48 kHz copy for listening.
The waveform players are the ordinary [audio visualizer](visualizers.md#recorded-audio-waveform-player-playhead-and-navigation):
click to seek, drag to pan, and use the wheel to zoom.

To analyse your own sound, submit `codecReconstruction(region)`, wait for it with
`client.awaitCodec(accepted)`, then display `client.codecVisualizationHtml(result, cacheDirectory)`.

## See also

- [Field Audio AI](field_audio_ai.md)
- [Faust](faust.md)
