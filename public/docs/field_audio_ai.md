# Field Audio AI

Point Field at a recording you already have, name four and a half seconds of it, and ask for eight
variations that keep its room and its grain. Or ask which other stretches of the same folder sound
like that one. Generating from a source and navigating a corpus are the two things Field Audio AI
is for.

The models are not in Field. They run in long-lived Python workers behind a model-neutral HTTP and
WebSocket protocol, so swapping a checkpoint does not take the piece with it: Field keeps the source
selections, the operations, the candidate sets, the ratings and annotations, the provenance and the
arrangement.

The companion service is experimental, and pinned under `services/field-audio-ai` as a Git submodule
while its architecture moves. The Field-side API is in `afield.stdlib.noir.audioai` and names
operations such as `generate.style`, `codec.reconstruct`, and `analysis.pitch_poly`, rather than
depending on AudioCraft classes.

Field's public docs consume a committed, model-free snapshot of the service contract. A normal
docs build does not initialize the submodule, install analysis or AudioCraft packages, or download
weights. See the [generated service reference](field_audio_service_reference.md) for the
current pinned HTTP objects, states, and error vocabulary.

## What the service does

- immutable content-addressed source assets and 48 kHz project representations;
- asynchronous, cached and reproducible operations with progress and structured errors;
- source-window MusicGen-Style candidate families and automatic splice previews;
- checksummed Underfit LoRA and Stable Audio 3 source-conditioned candidate families;
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

## Underfit LoRAs and Stable Audio 3

Underfit checkpoints use the same durable candidate path, while Stable Audio 3 runs in its own
Python 3.10 worker. Field hashes the local `.safetensors` file before submission; the worker checks
that digest again beneath `FIELD_AUDIO_UNDERFIT_LORA_ROOT`. The digest participates in the request
hash, so replacing a checkpoint at the same filename cannot return an older cached candidate set.

```diffstatic {data-lang=kotlin}
import afield.stdlib.noir.audioai.*
import java.nio.file.Path

val client = FieldAudioClient()
val source = client.assetByFilename("*Nancy*").get()
val region = AssetRegion(source.id, startSeconds = 12.0, durationSeconds = 20.0)
val loraRoot = Path.of(requireNotNull(System.getenv("FIELD_AUDIO_UNDERFIT_LORA_ROOT")))
val lora = UnderfitLoraCheckpoint.fromFile(
    loraRoot.resolve("orchestra/10000.safetensors"),
    workerRoot = loraRoot,
    strength = 0.7,
    intervalMax = 0.92,
)

client.submit(underfitCandidates(
    source = region,
    prompt = "sparse suspended orchestral tension",
    durationSeconds = 24.0,
    checkpoints = listOf(lora),
    model = UnderfitModel.SMALL_MUSIC,
    controls = UnderfitGenerationControls(
        steps = 8,
        cfg = 1.0,
        initNoiseLevel = 0.65,
    ),
)).thenCompose(client::awaitCandidates)
  .thenAccept { display("underfit", candidateBoardHtml(it)) }
```

`initNoiseLevel` is the audio-to-audio distance control: zero preserves the selected source and one
approaches a fresh text-conditioned sample. A LoRA `intervalMax` below one skips it during the
earliest structure-setting denoising steps. `UnderfitOperation.VARIATIONS` selects the explicit
variation operation; both operations retain the source, model name, sampling controls, LoRA digest,
strength, upstream revisions, and seed in candidate provenance.

## Gestures and how strongly they are enforced

The gesture board accepts timed prompt regions, salience events, and density, register,
brightness and activity curves. Chord regions, pitch salience, pulse, continuation endpoints,
corpus attraction, and EnCodec codebook selections are not part of its vocabulary. The interface
always reveals how a gesture reaches the sound:

- **direct** — consumed as a native model condition;
- **compiled** — translated into chords, drums, pitch salience or several bounded requests;
- **evaluative** — used to analyse, rank and retry candidate families;
- **transformative** — applied afterward through tokens, splicing or decoding.

A density curve is an analysis-and-ranking target rather than a direct MusicGen control. The
service measures every candidate, persists per-lane scores, and the Field browser presents them
alongside listening and human ratings.

Start with the executable [gesture board](audio_ai_gesture_board.md), then use source-window motion,
parameter sweeps and candidate comparison around it. The complete
[beat and pitch analysis workflow](audio_ai_analysis.md) shows source lookup, submission, live
progress, cached result retrieval and synchronized librosa/pYIN/Basic Pitch views.

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

[MuScriptor multi-instrument transcription](audio_ai_muscriptor.md) turns a mixed recording into
instrument-labelled note events, performance and optional quantized MIDI, and a synchronized
instrument-coloured piano roll. The three revision-pinned model sizes remain behind the same
`analysis.transcribe_music` operation and a separate gated-weight worker.

Move from analysis into source-derived navigation with the
[corpus microscope](audio_ai_corpus_microscope.md), then continue with the
[EnCodec reconstruction microscope](audio_ai_codec.md) and the
[recorded-audio visualizers](visualizers.md#recorded-audio-waveform-player-playhead-and-navigation).
