# Short-time spectral spaces

A resonance sliding upward, a hiss that thickens, a room closing in around a voice: none of that
needs a trained model to notice, only a record of which frequencies are loud from one moment to the
next. `analysis.embed_spectral` keeps that record in several interchangeable forms rather than
settling on one.

A single cached librosa job holds all of them, which puts the interesting decisions after the
analysis instead of inside it: how much audio pools into one point, which distance compares two
points, whether MDS or t-SNE lays them out. Each of those is a re-fit in Field, not a second pass
over the sound.

The retained representations follow librosa's
[mel-spectrogram](https://librosa.org/doc/latest/generated/librosa.feature.melspectrogram.html) and
[MFCC](https://librosa.org/doc/main/api/generated/librosa.feature.mfcc.html) definitions. “Earth mover”
here is one-dimensional [Wasserstein-1 distance](https://docs.scipy.org/doc/scipy/reference/generated/scipy.stats.wasserstein_distance.html)
on the shared ordered mel support; Field evaluates its cumulative-distribution form directly rather
than routing every frame pair through Python.

This complements the learned [speech-reactive spaces](audio_ai_speech_embeddings.md). WavLM and
emotion2vec organize sound using learned speech evidence. The spectral operation asks simpler,
more inspectable questions about where acoustic energy is and how the spectral envelope changes.

| Field distance | Representation | What proximity tends to mean |
| --- | --- | --- |
| `MEL_WASSERSTEIN` | per-frame unit-mass mel spectrum | little spectral mass must move far along the perceptual frequency axis |
| `LOG_MEL_COSINE` | 64 absolute log-mel bands | similar spectral-envelope direction, relatively insensitive to overall scale |
| `LOG_MEL_EUCLIDEAN` | 64 absolute log-mel bands | similar absolute band-energy pattern, including level |
| `MFCC_COSINE` | 20 cepstral coefficients | similar compact envelope/timbre direction |
| `MFCC_EUCLIDEAN` | 20 cepstral coefficients | similar compact envelope/timbre and coefficient magnitude |

Which row to reach for depends on what is moving. Mel Wasserstein is especially useful when a
formant, resonant background sound, or spectral emphasis slides in frequency: it charges in
proportion to how far mass moves. MFCCs suppress fine spectral detail and make broad envelope
changes easier to see. Log-mel retains the most directly inspectable band structure.

## Complete Field workflow

This box finds the already imported `near_drowning.wav` or imports it, displays the source, submits
and monitors one durable analysis job, downloads the artifact, and compares three t-SNE readings of
the first ten seconds. Every panel shares the source player's cursor in both directions.

```diffstatic {data-lang=kotlin}
import afield.Box
import afield.stdlib.AudioVisualizerOptions
import afield.stdlib.noir.audioai.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

val client = FieldAudioClient()
val local = Path.of(System.getProperty("user.home"), "Downloads", "near_drowning.wav")
val sync = "near-drowning-spectral"
display("all jobs", fieldAudioJobMonitor(heightPixels = 340))

fun Box.trackedSpectral(request: ModelOperation): CompletableFuture<AnalysisResult> =
    client.submit(request).thenCompose { accepted ->
        display("spectral job", fieldAudioJobStatusHtml(accepted))
        client.watch(accepted.jobId) { event ->
            display("spectral job", fieldAudioJobStatusHtml(accepted, event))
        }
        client.awaitAnalysis(accepted)
    }

client.assetsByFilename("near_drowning.wav").thenCompose { matches ->
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
            width = 1100, waveformHeight = 150,
            initialDurationSeconds = minOf(10.0, duration), syncGroup = sync,
        ),
    ))
    trackedSpectral(spectralEmbeddingAnalysis(
        region, requestedBy = "field:spectral-embeddings/near-drowning",
    ))
}.thenCompose { result ->
    display("projection", "decoding once, then fitting three Field Smile t-SNE views…")
    client.analysisPreview(result.id, 1800).thenCombineAsync(
        client.spectralEmbeddingFrames(
            result, maxFrames = 600, startSeconds = 0.0, durationSeconds = 10.0,
            poolMillis = 200.0, hopMillis = 40.0,
        )
    ) { preview, frames ->
        listOf(
            SpectralEmbeddingDistance.MEL_WASSERSTEIN,
            SpectralEmbeddingDistance.LOG_MEL_COSINE,
            SpectralEmbeddingDistance.MFCC_EUCLIDEAN,
        ).mapIndexed { index, distance ->
            preview.withSpectralProjection(frames.tsne(
                dimensions = 4, distance = distance, perplexity = 30.0,
                maxIterations = 750, seed = 184921L + index,
            ))
        }
    }
}.thenAccept { previews ->
    display("projection", "complete · one cached job · three Field-owned distance spaces")
    display("spectral spaces", spectralEmbeddingPreviewDashboardHtml(
        previews, width = 1100, phaseHeight = 270, syncGroup = sync,
    ))
}.exceptionally { error ->
    display("spectral error", error.cause?.message ?: error.message ?: error.toString())
    null
}
```

The whole-file job for this 273.82-second recording produces 6,846<!--derive: 273.82/0.04--> frames. A ten-second projection
at a 40 ms hop has about 250<!--derive: 10000/40--> points and is comfortable for exact pairwise distances.
Longer fits get expensive quickly; reach for `maxFrames`, a longer hop, or a selected range.
Changing those Field-side choices does not invalidate or rerun the cached analysis. The cost at
each setting is in `dev_docs/AUDIO_EMBEDDING_PROJECTION_MEASUREMENTS.md`.

## What the durable job stores

The worker mixes the requested range to mono, resamples a private analysis copy to 22.05 kHz, and
uses a 2,048-sample FFT with a default 882-sample hop. The hop is exactly 40 ms, or 25 frames per
second. It stores:

- 64 Slaney-normalized mel power bands, normalized to unit mass per frame;
- the same 64 bands in absolute log-power dB with an 80 dB range;
- 20 MFCCs computed from that log-mel representation;
- energy in dBFS, spectral centroid, flatness, 85% rolloff, and mel-distribution flux;
- float64 frame times and float16 matrices in a versioned MessagePack artifact;
- source format, channel mix, resampling, librosa version, FFT, hop, frequency limits, and every
  other resolved parameter.

The source asset remains unchanged. The NPZ is the full numerical analysis; the service endpoint
downsamples only the five scalar curves for browser-safe previews. Field downloads the dense
MessagePack artifact when it needs to build a space.

## Temporal pooling and projection

`poolMillis` controls how much centered acoustic evidence contributes to one point. `hopMillis`
controls how often a new point is emitted. They are independent: a 200 ms pool with a 40 ms hop
gives overlapping local context at 25 updates per second. Pooling happens before `maxFrames`
sampling. Mel distributions are averaged and renormalized; log-mel and MFCC vectors use ordinary
means.

MDS and t-SNE are views, not new audio features. MDS tries to preserve global pairwise distances;
t-SNE is usually more revealing for local state changes but its axes, rotation, and long-range
geometry have no stable meaning. Field's Smile bridge accepts explicit symmetric distance matrices,
which lets mel Wasserstein drive either algorithm exactly. The normalized mel transport distance is
the integral of the absolute difference between two cumulative mel distributions, on a shared
0–1 mel-bin axis.

Load the `audio_ai_spectral_embeddings` operation transcript for the executable version, a durable
job monitor, and a model-free visualizer calibration. See [Audio AI analysis](audio_ai_analysis.md)
for beats and pitch, and [Visualizers](visualizers.md#short-time-spectral-phase-portraits) for the
shared cursor contract.
