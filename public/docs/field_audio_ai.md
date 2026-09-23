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

Running any of it needs that submodule initialized, the analysis and AudioCraft packages
installed, and weights on disk. A docs build does none of that, so these pages are written
against a committed, model-free snapshot of the service contract instead. That snapshot
is the [generated service reference](field_audio_service_reference.md): the current pinned HTTP
objects, states, and error vocabulary, readable without any of the above.

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
val region = AssetRegion(source.id, startSeconds = 12.0, durationSeconds = 12.0)
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
    durationSeconds = 30.0,
    checkpoints = listOf(lora),
    model = UnderfitModel.SMALL_MUSIC,
    controls = UnderfitGenerationControls(
        steps = 8,
        cfg = 1.5,
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

## The rest of Field Audio AI

Start with the [gesture board](audio_ai_gesture_board.md): it is the shortest runnable path from a
drawing to eight candidates.

| Page | Start here when you want to |
| --- | --- |
| [Gesture board](audio_ai_gesture_board.md) | draw a shape in time and generate toward it |
| [Beat and pitch analysis](audio_ai_analysis.md) | find the beats, the tempo, or what note is sounding |
| [Speech-reactive learned spaces](audio_ai_speech_embeddings.md) | move a recording of speech through emotion2vec or WavLM |
| [Short-time spectral spaces](audio_ai_spectral_embeddings.md) | do the same with no model and no GPU |
| [MuScriptor transcription](audio_ai_muscriptor.md) | turn a mixed recording into labelled notes and MIDI |
| [Corpus microscope](audio_ai_corpus_microscope.md) | find the other stretches that sound like this one |
| [EnCodec microscope](audio_ai_codec.md) | work on the codec tokens themselves |
| [Recorded-audio visualizers](visualizers.md#recorded-audio-waveform-player-playhead-and-navigation) | put any of it on a shared playhead |

The analysis pages share one contract. The job that measures a
recording is durable and content-addressed; everything downstream of it — the time range, the
pooling, the distance, the projection — is an ordinary Field-side parameter that recomputes
locally. Changing how you look at a recording does not re-run the analysis.
