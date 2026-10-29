# Speech-reactive learned spaces

Listen to a recording of someone talking and you can hear changes in timbre, prosody and the
sounds of the words. `analysis.embed_speech` lets you explore those changes as a path through a
learned space. Two models are available, each attentive to different aspects of speech.

*To run the examples on this page, open the [audio_ai_speech_embeddings](agent_transcripts.md#audio_ai_speech_embeddings) starting point.*

```diffstatic_runner {data-lang=kotlin screenshot="assets/audio_ai_speech_embeddings/learned_spaces.png" capture="box" open-outputs="true" wait="4" padding="16" timeout="180" w="1120" h="1100" hidden=true embed=true name="elsewhere-speech" alt="Real emotion2vec and WavLM learned phase portraits with voice activity, energy and novelty"}
:::static
import java.io.File

val json = FieldAudioClient.json
val folder = File("public/docs/assets/audio_ai_speech_embeddings")
val previews = ["emotion2vec", "wavlm_base_plus"].map {
    json.decodeFromString<AnalysisPreview>(File(folder, "$it-preview.json").readText())
}
display(
    "speech spaces",
    speechEmbeddingPreviewDashboardHtml(previews, width = 1040, phaseHeight = 220),
)
```

These are the two workers' landmark MDS views of `near_drowning.wav`. The workflow below fits
fresh t-SNE views of a selected ten-second interval from the same cached learned features.

| Backend | Bias | Useful first reading |
| --- | --- | --- |
| `analysis.emotion2vec` | affect, vocal texture, prosody, speaker state | where the manner and energy of speech change |
| `analysis.wavlm_base_plus` | general self-supervised speech structure | voice/timbre/phonetic motion; layer 6 is the default compromise |

Neither backend is a transcript or a sentence-meaning model. WavLM's middle and later layers can
organize phonetic structure, but spoken meaning needs a separate ASR or language embedding lane.
Both report a position in a learned speech space every 20 ms; neither reports a word.

## Start the optional worker

From the Field Audio AI checkout (or Field4's `services/field-audio-ai` submodule):

```diffstatic {data-lang=bash}
./scripts/run-speech-worker.sh
./scripts/run-api.sh
```

The speech worker listens on `127.0.0.1:8768`; the API stays on `127.0.0.1:8765`. Each model is
downloaded and loaded the first time a job asks for it. The models have their own licence terms,
separate from Field's.

## Plotting a recording

This box uses an imported recording if one is available, or imports
`~/Downloads/near_drowning.wav`. It displays the source, starts both analyses and shows their
progress. When they finish, four views share a timeline with the audio player.

```diffstatic {data-lang=kotlin}
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await

val client = cached { FieldAudioClient() }
val local = Path.of(System.getProperty("user.home"), "Downloads", "near_drowning.wav")
val sync = "near-drowning-speech"
val projectionStart = 0.0
val projectionDuration = 10.0
val projectionPoolMillis: Double? = 500.0
val projectionHopMillis: Double? = 40.0 // 25 Hz: just above a 24 fps display
display("all jobs", fieldAudioJobMonitor(heightPixels = 340))

go {
    try {
        val matches = client.assetsByFilename("*near_drowning*").await()
        val source = when {
            matches.size == 1 -> matches.single()
            matches.isEmpty() && Files.isRegularFile(local) -> client.importWav(local).await()
            matches.isEmpty() ->
                throw IllegalStateException("near_drowning.wav is neither imported nor present at $local")
            else -> throw IllegalStateException("near_drowning filename matched ${matches.size} assets")
        }
        val audio =
            source.representations.firstOrNull { it.role == "canonical_project" }
                ?: source.representations.first { it.mediaType in setOf("audio/wav", "audio/x-wav") }
        val duration = requireNotNull(audio.durationSeconds)
        val region = AssetRegion(source.id, 0.0, duration)
        display(
            "source",
            audio.audioVisualizerHtml(
                options = AudioVisualizerOptions(
                    initialDurationSeconds = minOf(projectionDuration, duration),
                    syncGroup = sync,
                ),
            ),
        )
        val requests = [
            "emotion2vec" to emotion2VecEmbeddingAnalysis(region),
            "WavLM Base+" to wavlmBasePlusEmbeddingAnalysis(region, layer = 6),
        ]
        val results = coroutineScope {
            requests
                .map { (label, request) ->
                    async {
                        client
                            .trackAnalysis(
                                request,
                                onAccepted = { display("$label job", fieldAudioJobStatusHtml(it)) },
                                onProgress = { job, event ->
                                    display("$label job", fieldAudioJobStatusHtml(job, event))
                                },
                                onCompleted = { display("$label job", fieldAudioJobStatusHtml(it)) },
                                onError = {}, // The surrounding coroutine reports the combined failure.
                            )
                            .await()
                    }
                }
                .awaitAll()
        }
        val projectionDescription =
            "start ${projectionStart}s · duration ${projectionDuration}s · pool ${projectionPoolMillis?.let { "${it} ms" } ?: "raw"} · hop ${projectionHopMillis?.let { "${it} ms" } ?: "native"}"
        display("projection", "Fitting Field Smile t-SNE · $projectionDescription…")
        val previews = coroutineScope {
            results
                .map { result ->
                    async {
                        client
                            .speechEmbeddingSmileTsnePreview(
                                result,
                                maxFrames = 600,
                                startSeconds = projectionStart,
                                durationSeconds = projectionDuration,
                                poolMillis = projectionPoolMillis,
                                hopMillis = projectionHopMillis,
                                dimensions = 4,
                                distance = SmileMdsDistance.COSINE,
                                perplexity = 30.0,
                                seed = 184921L,
                                previewPoints = 1800,
                            )
                            .await()
                    }
                }
                .awaitAll()
        }
        display(
            "speech spaces",
            speechEmbeddingPreviewDashboardHtml(
                previews,
                phaseHeight = 270,
                syncGroup = sync,
            ),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        markdown("speech analysis error", "Speech analysis failed: ${(failure.message ?: failure.toString()).md}")
    }
}
```

Stop or rerun cancels both local subscriptions and pending projection futures through the
box-owned coroutine. Status captions are built from the current range, pool and hop controls.

Re-running the unchanged box returns the cached neural results: source identity, exact range, model
revision, layer, chunking, MDS parameters, and preprocessing are part of the operation hash.
Change any of those inputs, or set `forceRegenerate = true`, to make a new operation. Changing a
Field-side range, pooling, sampling or distance recomputes the projection locally and leaves the
cached analysis where it is.

The settings above give 250 points across the first ten seconds: each averages 500 ms of evidence,
and a new one arrives every 40 ms, just faster than a 24 fps display. That smooths frame-level
jitter without turning the path into sparse half-second steps. Set both parameters to `null` for the
original 50 Hz vectors. Raising `maxFrames` toward the recording's full raw frame count is the one
expensive move, because an exact fit grows with the square of the point count: pool to a practical
hop, take a short exact window, or use the worker's all-frame landmark projection instead. As with
every t-SNE plot, local proximity is the evidence, and global distances, rotations and the
individual axis values are not.

## What one job records

The worker converts the selected source range to 16 kHz mono without replacing the original,
processes long recordings in overlapping contextual chunks, and stores:

- the full 50 Hz × 768 frame embedding as float16;
- raw and smoothed 20 ms WebRTC voice activity;
- frame energy in dBFS and embedding novelty;
- four worker-side classical-MDS coordinates over cosine distance for immediate previews;
- a MessagePack artifact containing the full float16 frame vectors and float64 frame times for Field;
- the pinned model revision, WavLM layer, chunking, resampling, source format, and worker revision.

`poolMillis` controls how much evidence contributes to each point; `hopMillis` independently
controls point spacing. Both round to the native 20 ms grid, and omitting them preserves the
original 50 Hz vectors. Pooling happens before `maxFrames` sampling, so changing the range, pool,
hop, dimensions, distance, perplexity, or seed never reruns either neural model. Exact MDS remains
available through `speechEmbeddingSmilePreview`; Smile's
[t-SNE](https://haifengl.github.io/manifold.html#t-sne) is the better first view for short-time local
neighbourhoods, and the worker's own projection stays as an immediate cached preview and a
provenance reference.

MDS is a navigational view, not the model's latent space. Its axes are specific to one operation;
do not compare coordinate 0 numerically between emotion2vec and WavLM, or between two independently
fitted recordings. Use the stored 768-dimensional vectors for retrieval or cross-recording
distance. The worker fixes eigenvector signs so an identical cached request has an identical view,
but rotations of a newly fitted corpus still have no semantic name.

See also the [general audio visualizers](visualizers.md#speech-embedding-phase-portraits) and
[beat and pitch analysis](audio_ai_analysis.md).
