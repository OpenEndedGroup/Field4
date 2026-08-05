# Field Audio AI

Field Audio AI is an experimental companion system for source-evocative music generation and
corpus exploration. Field remains the compositional environment: it owns source selections,
operations, candidate sets, ratings, annotations, provenance, and arrangement. Long-lived Python
workers perform analysis and inference behind a model-neutral HTTP and WebSocket protocol.

The companion service is pinned under `services/field-audio-ai` as a Git submodule while its
architecture is still moving quickly. The Field-side API is in `afield.stdlib.noir.audioai` and deliberately names
operations such as `generate.style`, `codec.reconstruct`, and `analysis.pitch_poly`, rather than
depending on AudioCraft classes.

Field's public docs consume a committed, model-free snapshot of the service contract. A normal
docs build does not initialize the submodule, install analysis or AudioCraft packages, or download
weights. See the [generated service reference](field_audio_service_reference.md) for the
current pinned HTTP objects, states, and error vocabulary.

## What works now

- immutable content-addressed source assets and 48 kHz project representations;
- asynchronous, cached and reproducible operations with progress and structured errors;
- source-window MusicGen-Style candidate families and automatic splice previews;
- EnCodec token capture, reconstruction, metrics, waveform and spectrogram comparison;
- multi-scale beat and tempo trajectories;
- monophonic pYIN and polyphonic Basic Pitch analysis;
- zoomable waveform, pitch, beat and token views, plus the embedded job monitor.

```diffstatic {data-lang=kotlin}
import afield.stdlib.noir.audioai.*

val client = FieldAudioClient()
val source = client.assetByFilename("*Nancy*").get()
val region = AssetRegion(source.id, startSeconds = 18.0, durationSeconds = 4.5)

val request = styleCandidates(
    source = region,
    prompt = "sparse suspended orchestral tension",
    durationSeconds = 12.0,
    seeds = 0L until 8L,
    controls = MusicGenStyleControls(
        temperature = 1.1,
        topK = 180,
        cfg = 3.2,
        styleQuantization = 2,
    ),
)

client.submit(request)
    .thenCompose(client::awaitCandidates)
    .thenAccept { display("candidates", candidateBoardHtml(it)) }
```

The source window, text, seed family, sampling temperature, top-k/top-p filtering, guidance balance,
and style bottleneck are direct MusicGen-Style controls. They are global controls: this checkpoint
does not accept an arbitrary event timeline or density curve.

## Gestures and how strongly they are enforced

Field's intended gesture vocabulary includes timed prompts and events, chord regions, pitch
salience, pulse, density, register and activity curves, continuation endpoints, corpus attraction,
and EnCodec codebook selections. The interface should always reveal how a gesture reaches the
sound:

- **direct** — consumed as a native model condition;
- **compiled** — translated into chords, drums, pitch salience or several bounded requests;
- **evaluative** — used to analyse, rank and retry candidate families;
- **transformative** — applied afterward through tokens, splicing or decoding.

This distinction matters. A density curve can already be useful as an analysis-and-ranking target,
but it should not be labelled as direct control. A future JASCO worker can make timed chords, drum
gestures and melodic salience model-native. Until then, source-window motion, parameter sweeps,
candidate comparison, beat/pitch analysis and token surgery form the honest playable instrument.

Continue with the [EnCodec reconstruction microscope](audio_ai_codec.md) and the
[recorded-audio visualizers](visualizers.md#recorded-audio-waveform-player-playhead-and-navigation).
