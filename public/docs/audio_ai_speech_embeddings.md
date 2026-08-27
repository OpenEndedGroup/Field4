# Speech-reactive learned spaces

Field Audio exposes two frame-level speech models behind one operation,
`analysis.embed_speech`. This is the lane for a field recording where voice activity, vocal
timbre, prosody, and phonetic motion matter more than beat or harmony.

| Backend | Bias | Useful first reading |
| --- | --- | --- |
| `analysis.emotion2vec` | affect, vocal texture, prosody, speaker state | where the manner and energy of speech change |
| `analysis.wavlm_base_plus` | general self-supervised speech structure | voice/timbre/phonetic motion; layer 6 is the default compromise |

Neither backend is a transcript or a sentence-meaning model. WavLM's middle and later layers can
organize phonetic structure, but spoken meaning needs a separate ASR or language embedding lane.
The two models here answer a more immediate reactive question: *where is the sound moving in a
learned speech space right now?*

## What one job records

The worker converts the selected source range to 16 kHz mono without replacing the original,
processes long recordings in overlapping contextual chunks, and stores:

- the full 50 Hz × 768 frame embedding as float16;
- raw and smoothed 20 ms WebRTC voice activity;
- frame energy in dBFS and embedding novelty;
- four worker-side classical-MDS coordinates over cosine distance for immediate previews;
- a MessagePack artifact containing the full float16 frame vectors and float64 frame times for Field;
- the pinned model revision, WavLM layer, chunking, resampling, source format, and worker revision.

The preferred live-coding path downloads the binary artifact, selects a time range before expanding
the tensor into JVM objects, optionally mean-pools a wider temporal context, constructs distances,
and runs fixed-seed Smile t-SNE in Field. `poolMillis` controls how much evidence contributes to each
point; `hopMillis` independently controls point spacing. Both round to the native 20 ms grid, and
omitting them preserves the original 50 Hz vectors. Pooling happens before `maxFrames` sampling, so
changing the range, pool, hop, dimensions, distance, perplexity, or seed never reruns either neural
model. Exact MDS remains available through `speechEmbeddingSmilePreview`; Smile's
[t-SNE](https://haifengl.github.io/manifold.html#t-sne) is the better first view for short-time local
neighbourhoods. The worker projection remains an immediate cached preview and a useful provenance
reference.

MDS is a navigational view, not the model's latent space. Its axes are specific to one operation;
do not compare coordinate 0 numerically between emotion2vec and WavLM, or between two independently
fitted recordings. Use the stored 768-dimensional vectors for retrieval or cross-recording
distance. The worker fixes eigenvector signs so an identical cached request has an identical view,
but rotations of a newly fitted corpus still have no semantic name.

## Start the optional worker

From the Field Audio AI checkout (or Field4's `services/field-audio-ai` submodule):

```diffstatic {data-lang=bash}
./scripts/run-speech-worker.sh
./scripts/run-api.sh
```

The speech worker listens on `127.0.0.1:8768`; the API remains on `127.0.0.1:8765`. Checkpoints
load lazily. Ordinary Field tests and documentation builds do not create this environment or
download either model. The model repositories and their terms remain external to Field4's
AGPL-3.0-or-later source license.

## Complete Field path for `near_drowning.wav`

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
Change any of those inputs—or set `forceRegenerate = true`—to make a new operation.
The settings above produce 250 overlapping points in the first ten seconds: each point averages 500
ms of evidence, while a new point arrives every 40 ms (25 Hz), just faster than a 24 fps display. This
smooths frame-level jitter without turning the path into sparse half-second steps. Set both parameters
to `null` for the original 50 Hz vectors. Changing Field-side range, pooling, sampling, or distance
only recomputes the projection locally. Exact classical MDS is quadratic in memory and cubic in
compute, so do not set `maxFrames` to the recording's full 13,690 raw frames; use temporal pooling and
a practical hop, a short exact window, or the worker's all-frame landmark projection instead.
For this 13,690-frame recording, a 40 ms hop yields 6,845 pooled points; set
`maxFrames = 7_000` to retain that complete 25 Hz path. It is still a substantial t-SNE fit, but its
pairwise matrix has roughly one quarter as many entries as the 13,690-point raw-frame version.
As with every t-SNE plot, local proximity is the evidence: global distances, rotations, and the
individual axis values are not semantically stable.

The loadable `audio_ai_speech_embeddings` operation transcript contains this live box, a durable
job monitor, and a model-free synchronized renderer calibration. Continue with the
[general audio visualizers](visualizers.md#speech-embedding-phase-portraits) or
[beat and pitch analysis](audio_ai_analysis.md).
