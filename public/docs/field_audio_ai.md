# Field Audio AI

Point Field at a recording you already have, choose four and a half seconds of it, and ask for
eight variations that keep its room and its grain. Or ask which other stretches in a folder sound
like the passage you picked. Field Audio AI lets you generate from recordings and explore a
collection by how it sounds.

The models run in long-lived Python workers behind an HTTP and WebSocket protocol. Swap a
checkpoint and Field keeps the rest of your work together: source selections, operations,
candidate sets, ratings, annotations, provenance and arrangement.

The companion service is experimental, and pinned under `services/field-audio-ai` as a Git submodule
while its architecture moves. In a box its API needs no import; it names
operations such as `generate.style`, `codec.reconstruct`, and `analysis.pitch_poly`.

Running any of it needs that submodule initialized, the analysis and AudioCraft packages
installed, and weights on disk. Short of that, what you can still read is a committed,
model-free snapshot of the service contract: the
[generated service reference](field_audio_service_reference.md) lists the current pinned HTTP
objects, states, and error vocabulary, and needs none of the above.

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

## Variations of a source

This box takes four and a half seconds of a recording, asks MusicGen-Style for eight
twelve-second variations, one per seed, and shows the source window while it waits and a candidate board when they arrive:

```diffstatic_runner {data-lang=kotlin screenshot="assets/field_audio_ai/style_candidates.png" capture="box" capture-profile="linux-nvidia" open-outputs="true" wait="4" wait-output="true" wait-output-channel="candidates" timeout="900" w="1120" h="40" padding="16" embed=true name="style-candidates" alt="A candidate board of eight MusicGen-Style variations of Nancy from 18 to 22.5 seconds, one row per seed with a waveform and player"}
val client = cached { FieldAudioClient() }
runAsync(
    onResult = { result: CandidateSet ->
        display("candidates", candidateBoardHtml(result))
    },
    onError = { failure ->
        markdown("generation error", "Generation failed: ${(failure.message ?: failure.toString()).md}")
    },
) {
    val showSource = progress<String> { display("source", it) }
    val lookup = client.assetByFilename("*Nancy*")
    onCancel { lookup.cancel(false) }
    lookup.thenCompose { source ->
        if (future.isDone) throw java.util.concurrent.CancellationException("run ended")
        val region = AssetRegion(source.id, startSeconds = 18.0, durationSeconds = 4.5)
        val canonical = source.representations.first { it.role == "canonical_project" }
        showSource(
            canonical.audioVisualizerHtml(
                label = "Nancy · 18–22.5 s",
                options = AudioVisualizerOptions(
                    width = 1100,
                    waveformHeight = 110,
                    initialStartSeconds = 18.0,
                    initialDurationSeconds = 4.5,
                    initialPositionSeconds = 18.0,
                ),
            ),
        )

        val request = styleCandidates(
            source = region,
            prompt = "sparse suspended orchestral tension",
            durationSeconds = 12.0,
            seeds = 0L until 8L,
            backend = "audiocraft.musicgen_style",
            controls = MusicGenStyleControls(
                temperature = 1.1,
                topK = 180,
                cfg = 3.2,
                styleQuantization = 2,
            ),
        )

        val submitted = client.submit(request)
        onCancel { submitted.cancel(false) }
        submitted.thenCompose { accepted ->
            if (future.isDone) throw java.util.concurrent.CancellationException("run ended")
            client.awaitCandidates(accepted).also { polling -> onCancel { polling.cancel(false) } }
        }
    }
}
```

Stop or rerun cancels this box’s lookup and local polling, and suppresses obsolete output.
The durable service job and cached shared client remain available.

The source window, text, seed family, sampling temperature, top-k/top-p filtering, guidance balance,
and style bottleneck are direct MusicGen-Style controls. They are global controls: this checkpoint
does not accept an arbitrary event timeline or density curve.

## Underfit LoRAs and Stable Audio 3

Underfit checkpoints use the same durable candidate path, while Stable Audio 3 runs in its own
Python 3.10 worker. Field hashes the local `.safetensors` file before submission; the worker checks
that digest again beneath `FIELD_AUDIO_UNDERFIT_LORA_ROOT`. The digest participates in the request
hash, so replacing a checkpoint at the same filename cannot return an older cached candidate set.

This box asks for thirty seconds through one LoRA checkpoint at strength 0.7, starting from a
twelve-second window of the source:

```diffstatic {data-lang=kotlin}
import java.nio.file.Path

val client = cached { FieldAudioClient() }
val loraRoot = Path.of(requireNotNull(System.getenv("FIELD_AUDIO_UNDERFIT_LORA_ROOT")))
val lora = UnderfitLoraCheckpoint.fromFile(
    loraRoot.resolve("orchestra/10000.safetensors"),
    workerRoot = loraRoot,
    strength = 0.7,
    intervalMax = 0.92,
)

runAsync(
    onResult = { result: CandidateSet ->
        display("underfit", candidateBoardHtml(result))
    },
    onError = { failure ->
        markdown("generation error", "Generation failed: ${(failure.message ?: failure.toString()).md}")
    },
) {
    val lookup = client.assetByFilename("*Nancy*")
    onCancel { lookup.cancel(false) }
    lookup.thenCompose { source ->
        if (future.isDone) throw java.util.concurrent.CancellationException("run ended")
        val region = AssetRegion(source.id, startSeconds = 12.0, durationSeconds = 12.0)
        val submitted = client.submit(
            underfitCandidates(
                source = region,
                prompt = "sparse suspended orchestral tension",
                durationSeconds = 30.0,
                checkpoints = [lora],
                model = UnderfitModel.SMALL_MUSIC,
                controls = UnderfitGenerationControls(
                    steps = 8,
                    cfg = 1.5,
                    initNoiseLevel = 0.65,
                ),
            ),
        )
        onCancel { submitted.cancel(false) }
        submitted.thenCompose { accepted ->
            if (future.isDone) throw java.util.concurrent.CancellationException("run ended")
            client.awaitCandidates(accepted).also { polling -> onCancel { polling.cancel(false) } }
        }
    }
}
```

Hear the source, matched no-LoRA baseline and actual LoRA take in the
[Underfit Mancini experiment](audio_ai_underfit_mancini.md#listen-to-the-experiment).

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

![A gesture board: an eighteen-second timeline whose prompt is assembled from three timed regions, a row of chips marking each term DIRECT, COMPILED or EVALUATIVE, and four curves (breath, event density, register drift and surface light) with two salience events drawn as dashed orange verticals](assets/audio_ai_gesture_board/gesture_board.png)

Each chip under the prompt names how its term reaches the sound, and each curve carries its score weight. The board is captured from the box on the [gesture board](audio_ai_gesture_board.md) page.

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
| [EnCodec round trip](audio_ai_codec.md) | work on the codec tokens themselves |
| [Recorded-audio visualizers](visualizers.md#recorded-audio-waveform-player-playhead-and-navigation) | put any of it on a shared playhead |

The analysis pages share one contract. The job that measures a
recording is durable and content-addressed; everything downstream of it — the time range, the
pooling, the distance, the projection — is an ordinary Field-side parameter that recomputes
locally. Changing how you look at a recording does not re-run the analysis.
