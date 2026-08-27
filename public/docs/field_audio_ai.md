# Field Audio AI

Field Audio AI is an experimental companion system for source-evocative music generation and
corpus exploration. Field remains the compositional environment: it owns source selections,
operations, candidate sets, ratings, annotations, provenance, and arrangement. Long-lived Python
workers perform analysis and inference behind a model-neutral HTTP and WebSocket protocol.

The companion service is pinned under `services/field-audio-ai` as a Git submodule while its
architecture is still moving quickly. The Field-side API is in `afield.stdlib.noir.audioai` and names
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
- typed temporal gesture boards with prompt compilation and waveform-based candidate scoring;
- EnCodec token capture, reconstruction, metrics, waveform and spectrogram comparison;
- multi-scale beat and tempo trajectories;
- monophonic pYIN and polyphonic Basic Pitch analysis;
- revision-pinned emotion2vec and WavLM Base+ speech trajectories with voice activity and live Field projections;
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

Field's gesture board now accepts timed prompt regions, salience events, and density, register,
brightness and activity curves. Chord regions, pitch salience, pulse, continuation endpoints,
corpus attraction, and EnCodec codebook selections remain later vocabulary. The interface always
reveals how a gesture reaches the sound:

- **direct** — consumed as a native model condition;
- **compiled** — translated into chords, drums, pitch salience or several bounded requests;
- **evaluative** — used to analyse, rank and retry candidate families;
- **transformative** — applied afterward through tokens, splicing or decoding.

This distinction matters. Today a density curve is an analysis-and-ranking target, not a direct
MusicGen control. The service measures every candidate, persists per-lane scores, and the Field
browser presents them alongside listening and human ratings. A future JASCO worker can make timed
chords, drum gestures and melodic salience model-native without changing the board's data model.

Start with the executable [gesture board](audio_ai_gesture_board.md), then use source-window motion,
parameter sweeps and candidate comparison around it. The complete
[beat and pitch analysis workflow](audio_ai_analysis.md) shows source lookup, submission, live
progress, cached result retrieval and synchronized librosa/pYIN/Basic Pitch views without hiding
the job boundary.

For speech and field recordings, [speech-reactive learned spaces](audio_ai_speech_embeddings.md)
adds emotion2vec and WavLM Base+ behind the same durable operation boundary. Its dual phase
portraits let a recording drive t-SNE axes 0×1 and 2×3 while the ordinary waveform player remains
the transport and shared time authority.
The preferred path transfers those vectors as binary MessagePack, temporally pools them at a
user-selected context and hop, and computes the display in Field through Smile, so projection
choices stay live-code parameters rather than neural jobs.

For a fast, inspectable complement, [short-time spectral spaces](audio_ai_spectral_embeddings.md)
retain normalized mel mass, absolute log-mel, MFCC, and scalar spectral trajectories in one cached
librosa job. Field can then compare exact mel-Wasserstein, log-mel, and cepstral distances with MDS
or t-SNE. These views use the same synchronized playhead contract as speech spaces, but require no
model checkpoint or GPU.

Move from analysis into source-derived navigation with the
[corpus microscope](audio_ai_corpus_microscope.md), then continue with the
[EnCodec reconstruction microscope](audio_ai_codec.md) and the
[recorded-audio visualizers](visualizers.md#recorded-audio-waveform-player-playhead-and-navigation).
