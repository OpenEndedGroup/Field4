# Speech-reactive learned spaces

A field recording of someone talking gives a beat tracker almost nothing to hold onto. What moves
in it is voice activity, vocal timbre, prosody and phonetic motion, and that is what
`analysis.embed_speech` measures. Two speech models sit behind that one operation, and which of
them you ask changes what the motion is about.

```diffstatic_runner {data-lang=kotlin screenshot="assets/audio_ai_speech_embeddings/learned_spaces.png" capture="box" open-outputs="true" wait="4" padding="16" timeout="180" w="1120" h="1100" hidden=true embed=true name="elsewhere-speech" alt="Real emotion2vec and WavLM learned phase portraits with voice activity, energy and novelty"}
:::static
import afield.stdlib.noir.audioai.*
import afield.stdlib.AudioVisualizer
import afield.stdlib.AudioVisualizerOptions
import afield.four.audio.WavFloatArrayLoader
import java.io.File
import java.nio.file.Path
val json = FieldAudioClient.json
val folder = File("public/docs/assets/audio_ai_speech_embeddings")
val previews = listOf("emotion2vec", "wavlm_base_plus").map {
    json.decodeFromString<AnalysisPreview>(File(folder, "$it-preview.json").readText())
}
display("speech spaces", speechEmbeddingPreviewDashboardHtml(previews, width = 1040, phaseHeight = 220))

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

This box first reuses a previously imported content-addressed asset. If none exists, it imports
`~/Downloads/near_drowning.wav`. Then it displays the source immediately, submits both jobs,
streams progress into replaceable cards, awaits the durable results, and gives all four phase
portraits the source player's time bus.

```diffstatic {data-lang=kotlin}
import afield.Box
import afield.stdlib.AudioVisualizerOptions
import afield.stdlib.SmileMdsDistance
import afield.stdlib.noir.audioai.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

val client = FieldAudioClient()
val local = Path.of(System.getProperty("user.home"), "Downloads", "near_drowning.wav")
val sync = "near-drowning-speech"
val projectionStart = 0.0
val projectionDuration = 10.0
val projectionPoolMillis = 500.0
val projectionHopMillis = 40.0 // 25 Hz: just above a 24 fps display
display("all jobs", fieldAudioJobMonitor(heightPixels = 340))

fun Box.tracked(label: String, request: ModelOperation): CompletableFuture<AnalysisResult> =
    client.submit(request).thenCompose { accepted ->
        display("$label job", fieldAudioJobStatusHtml(accepted))
        client.watch(accepted.jobId) { event ->
            display("$label job", fieldAudioJobStatusHtml(accepted, event))
        }
        client.awaitAnalysis(accepted)
    }

client.assetsByFilename("*near_drowning*").thenCompose { matches ->
    when {
        matches.size == 1 -> CompletableFuture.completedFuture(matches.single())
        matches.isEmpty() && Files.isRegularFile(local) -> client.importWav(local)
        else -> CompletableFuture.failedFuture(IllegalStateException("cannot resolve $local"))
    }
}.thenCompose { source ->
    val audio = source.representations.firstOrNull { it.role == "canonical_project" }
        ?: source.representations.first { it.mediaType in setOf("audio/wav", "audio/x-wav") }
    val duration = requireNotNull(audio.durationSeconds)
    val region = AssetRegion(source.id, 0.0, duration)
    display("source", audio.audioVisualizerHtml(
        options = AudioVisualizerOptions(
            initialDurationSeconds = minOf(projectionDuration, duration), syncGroup = sync,
        ),
    ))

    val jobs = listOf(
        tracked("emotion2vec", emotion2VecEmbeddingAnalysis(region)),
        tracked("WavLM Base+", wavlmBasePlusEmbeddingAnalysis(region, layer = 6)),
    )
    CompletableFuture.allOf(*jobs.toTypedArray()).thenApply { jobs.map { it.join() } }
}.thenCompose { results ->
    val previews = results.map { result ->
        client.speechEmbeddingSmileTsnePreview(
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
    }
    CompletableFuture.allOf(*previews.toTypedArray()).thenApply { previews.map { it.join() } }
}.thenAccept { previews ->
    display("speech spaces", speechEmbeddingPreviewDashboardHtml(
        previews, phaseHeight = 270, syncGroup = sync,
    ))
}
```

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
