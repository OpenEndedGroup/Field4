# Short-time spectral spaces

*To run the examples on this page, open the [audio_ai_spectral_embeddings](agent_transcripts.md#audio_ai_spectral_embeddings) starting point.*

A resonance sliding upward, a hiss that thickens, a room closing in around a voice: none of that
needs a trained model to notice, only a record of which frequencies are loud from one moment to the
next. `analysis.embed_spectral` keeps that record as mel bands, log-mel bands and MFCCs, and Field
lays the frames out as a map you can play through.

```diffstatic_runner {data-lang=kotlin screenshot="assets/audio_ai_spectral_embeddings/spectral_spaces.png" capture="box" open-outputs="true" wait="4" padding="16" timeout="180" w="1120" h="1100" hidden=true embed=true name="elsewhere-spectral" alt="Three real Field t-SNE projections of librosa mel, log-mel and MFCC frames"}
:::static
import java.io.File

val json = FieldAudioClient.json
val folder = File("public/docs/assets/audio_ai_spectral_embeddings")
val preview = json.decodeFromString<AnalysisPreview>(File(folder, "result-preview.json").readText())
val frames = decodeSpectralEmbeddingMessagePack(
    File(folder, "frames.msgpack").readBytes(),
    maxFrames = 600,
    durationSeconds = 10.0,
    poolMillis = 200.0,
    hopMillis = 40.0,
)
val previews =
    [
            SpectralEmbeddingDistance.MEL_WASSERSTEIN,
            SpectralEmbeddingDistance.LOG_MEL_COSINE,
            SpectralEmbeddingDistance.MFCC_EUCLIDEAN,
        ]
        .mapIndexed { index, distance ->
            preview.withSpectralProjection(
                frames.tsne(
                    dimensions = 4,
                    distance = distance,
                    perplexity = 30.0,
                    maxIterations = 750,
                    seed = 184921L + index,
                ),
            )
        }
display(
    "spectral spaces",
    spectralEmbeddingPreviewDashboardHtml(previews, width = 1040, phaseHeight = 210),
)
```

Three distances give three views of the first ten seconds of `near_drowning.wav`: mel transport,
log-mel cosine and MFCC Euclidean. Each projection is computed by Field from the worker's real
spectral frames.

One cached librosa job holds all three representations. How much audio pools into one point,
which distance compares two points, and whether MDS or t-SNE lays them out are all chosen in
Field afterwards, so changing them does not analyse the sound again.

The retained representations follow librosa's
[mel-spectrogram](https://librosa.org/doc/latest/generated/librosa.feature.melspectrogram.html) and
[MFCC](https://librosa.org/doc/main/api/generated/librosa.feature.mfcc.html) definitions. “Earth mover”
here is one-dimensional [Wasserstein-1 distance](https://docs.scipy.org/doc/scipy/reference/generated/scipy.stats.wasserstein_distance.html)
across the mel bands.

For maps built from trained speech models (WavLM, emotion2vec), see
[speech-reactive spaces](audio_ai_speech_embeddings.md). The spectral maps answer simpler questions:
where the energy is, and how the spectral envelope changes.

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

## Plotting a recording

This box finds the already imported `near_drowning.wav` or imports it, displays the source, submits
and monitors one durable analysis job, downloads the artifact, and compares three t-SNE readings of
the first ten seconds. Every panel shares the source player's cursor in both directions.

```diffstatic {data-lang=kotlin}
:::fold imports
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

:::endfold

val client = FieldAudioClient()
val localPath = Path.of(System.getProperty("user.home"), "Downloads", "near_drowning.wav")
val timeSync = "near-drowning-spectral-spaces"
val poolMillis = 200.0
val hopMillis = 40.0
val perplexity = 30.0
val iterations = 750
val seed = 184921L

display("all jobs", fieldAudioJobMonitor(heightPixels = 340))
runAsync(
    onResult = { previews: List<AnalysisPreview> ->
        display("projection", "complete · one cached job · ${previews.size} Field-owned spaces")
        display(
            "spectral spaces",
            spectralEmbeddingPreviewDashboardHtml(
                previews,
                width = 1100,
                phaseHeight = 270,
                syncGroup = timeSync,
            ),
        )
    },
    onError = { failure ->
        markdown("spectral error", "Analysis failed: ${(failure.message ?: failure.toString()).md}")
    },
) {
    val showSource = progress<String> { display("source", it) }
    val showProjection = progress<String> { display("projection", it) }
    // HTTP continuations run on other threads. New tracked legs still belong to this run.
    val run = RunScope.current()
    :::fold durable job tracking
    val tracked: (ModelOperation) -> CompletableFuture<AnalysisResult> = { request ->
        RunScope.withCurrent(run) {
                client.trackAnalysis(
                    request,
                    onAccepted = { display("spectral job", fieldAudioJobStatusHtml(it)) },
                    onProgress = { job, event -> display("spectral job", fieldAudioJobStatusHtml(job, event)) },
                    onCompleted = { display("spectral job", fieldAudioJobStatusHtml(it)) },
                    onError = {}, // The outer operation reports failure once.
                )
            }
            .also { pending -> onCancel { pending.cancel(true) } }
    }
    :::endfold
    val lookup = client.assetsByFilename("near_drowning.wav")
    onCancel { lookup.cancel(true) }
    lookup
        .thenCompose { matches ->
            if (future.isDone) throw CancellationException("run ended")
            :::fold find or import the recording
            when {
                matches.size == 1 -> CompletableFuture.completedFuture(matches.single())
                matches.isEmpty() && Files.isRegularFile(localPath) ->
                    client.importWav(localPath).also { pending -> onCancel { pending.cancel(true) } }
                matches.isEmpty() ->
                    CompletableFuture.failedFuture(
                        IllegalStateException("near_drowning.wav is neither imported nor present at $localPath"),
                    )
                else ->
                    CompletableFuture.failedFuture(
                        IllegalStateException("near_drowning.wav matched ${matches.size} assets"),
                    )
            }
            :::endfold
        }
        .thenCompose { source ->
            if (future.isDone) throw CancellationException("run ended")
            val audio =
                source.representations.firstOrNull { it.role == "canonical_project" }
                    ?: source.representations.first { it.mediaType in ["audio/wav", "audio/x-wav"] }
            val duration = requireNotNull(audio.durationSeconds) { "source audio has no duration" }
            showSource(
                audio.audioVisualizerHtml(
                    label = "near_drowning.wav · ${duration.f2} s · ${audio.sampleRate} Hz",
                    options = AudioVisualizerOptions(
                        width = 1100,
                        waveformHeight = 150,
                        initialDurationSeconds = minOf(10.0, duration),
                        syncGroup = timeSync,
                    ),
                ),
            )
            tracked(
                spectralEmbeddingAnalysis(
                    AssetRegion(source.id, 0.0, duration),
                    requestedBy = "field:spectral-embeddings/near-drowning",
                ),
            )
        }
        .thenCompose { result ->
            if (future.isDone) throw CancellationException("run ended")
            showProjection(
                "decoding one artifact · ${poolMillis.f0} ms pool · ${hopMillis.f0} ms hop · fitting three t-SNE spaces…",
            )
            val preview = client.analysisPreview(result.id, 1800)
            val frames = client.spectralEmbeddingFrames(
                result,
                maxFrames = 600,
                startSeconds = 0.0,
                durationSeconds = 10.0,
                poolMillis = poolMillis,
                hopMillis = hopMillis,
            )
            onCancel {
                preview.cancel(true)
                frames.cancel(true)
            }
            preview.thenCombineAsync(frames) { summary, samples ->
                [
                        SpectralEmbeddingDistance.MEL_WASSERSTEIN,
                        SpectralEmbeddingDistance.LOG_MEL_COSINE,
                        SpectralEmbeddingDistance.MFCC_EUCLIDEAN,
                    ]
                    .mapIndexed { index, distance ->
                        // The fitter has no interrupt hook; stop suppresses output and skips later fits.
                        if (future.isDone) throw CancellationException("run ended")
                        summary.withSpectralProjection(
                            samples.tsne(
                                dimensions = 4,
                                distance = distance,
                                perplexity = perplexity,
                                maxIterations = iterations,
                                seed = seed + index,
                            ),
                        )
                    }
            }
        }
}
```

The whole-file job for this 273.82-second recording produces 6,846<!--derive: 273.82/0.04--> frames. A ten-second projection
at a 40 ms hop has about 250<!--derive: 10000/40--> points and is comfortable for exact pairwise distances.
Longer fits get expensive quickly; reach for `maxFrames`, a longer hop, or a selected range.
Changing them does not rerun the cached analysis.

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
geometry have no stable meaning. Either algorithm can use any of the distances above, including
mel Wasserstein, which is the integral of the absolute difference between two cumulative mel distributions, on a shared
0–1 mel-bin axis.

See also: [Audio AI analysis](audio_ai_analysis.md) for beats and pitch, and
[Visualizers](visualizers.md#short-time-spectral-phase-portraits) for how the panels share a cursor.
