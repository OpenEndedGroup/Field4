# Corpus microscope: from recordings to related windows

*To run the examples on this page, open the [audio_ai_corpus_microscope](agent_transcripts.md#audio_ai_corpus_microscope) starting point.*

The corpus microscope finds the stretches of a set of recordings that sound most like a passage you
pick. It first cuts every recording into overlapping windows of 1.5, 3, 6 and 12 seconds and
measures each one; the audio files themselves are left unchanged. Distance is made of five
measurements (harmony, pitch, texture, rhythm and dynamics), and you choose how much each one
counts. The examples here query six seconds of Nancy against Final Out at Candlestick Park.

```diffstatic_runner {data-lang=kotlin screenshot="assets/audio_ai_corpus_microscope/neighbours.png" capture="box" open-outputs="true" wait="4" padding="16" timeout="180" w="1120" h="1100" hidden=true embed=true name="elsewhere-corpus" alt="Real Nancy query and two Candlestick neighbour windows with waveforms, distance bars and splice players" capture-profile="linux-nvidia"}
:::static
import java.io.File

val json = FieldAudioClient.json
val folder = File("public/docs/assets/audio_ai_corpus_microscope")
val preview = json.decodeFromString<CorpusPreview>(File(folder, "corpus.json").readText())
val query = json.decodeFromString<AssetRepresentation>(File(folder, "query-audio.json").readText())
display(
    "microscope",
    corpusMicroscopeHtml(preview.copy(neighbours = preview.neighbours.take(2)), query, width = 1040, cardWidth = 495) +
        "<style>.fcm-query > .field-audio-visualizer{width:100%!important}</style>",
)
```

The first two neighbours of the six-second Nancy query, with their source timestamps and
separate distances for harmony, pitch, texture, rhythm and dynamics.

## What each window is measured by

| Group | Measurements |
| --- | --- |
| harmony | mean and variation of twelve chroma classes |
| pitch | dominant-pitch centre, spread and voiced fraction |
| texture | MFCC plus spectral centroid, bandwidth, flatness and rolloff |
| rhythm | onset strength, peak density and local tempo statistics |
| dynamics | RMS centre, spread and range |

Each group is pooled over the window, and the five together are the vector a query is matched
against.

## Submit and monitor the index

Segmentation and descriptor extraction are ordinary cached `analysis.*` jobs. This box indexes Nancy and Final Out at Candlestick Park. Keep the index, query and placement
fragments below in the same box. Run the index first and wait for **Index ready** before running the
query. The box properties publish only successful results for the later fragments:

```diffstatic {data-lang=kotlin}
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

val client = cached { FieldAudioClient() }
prop<AudioAsset>(nancy)
prop<CorpusPreview>(preview)
prop<AssetRepresentation>(canonical)
display("all jobs", fieldAudioJobMonitor(heightPixels = 330))

fun tracked(run: RunScope?, label: String, request: ModelOperation): CompletableFuture<AnalysisResult> = RunScope.withCurrent(run) {
    client.trackAnalysis(
        request,
        onAccepted = { display("$label job", fieldAudioJobStatusHtml(it)) },
        onProgress = { job, event -> display("$label job", fieldAudioJobStatusHtml(job, event)) },
        onCompleted = { display("$label job", fieldAudioJobStatusHtml(it)) },
        onError = {}, // The enclosing operation reports failure once.
    )
}

runAsync<AudioAsset>(
    onResult = { source ->
        nancy = source
        markdown("index", "Index ready. Run the query fragment below.")
    },
    onError = { failure -> markdown("index error", "Index failed: ${(failure.message ?: failure.toString()).md}") },
) {
    // Each HTTP continuation must re-enter the run that owns its tracked job.
    val run = RunScope.current()
    val lookups = ["*Nancy*", "*Candlestick Park*"].map { client.assetByFilename(it) }
    onCancel { lookups.forEach { it.cancel(true) } }
    CompletableFuture.allOf(*lookups.toTypedArray()).thenCompose {
        if (future.isDone) throw CancellationException("run ended")
        val assets = lookups.map { it.join() }
        val jobs = assets.mapIndexed { index, asset ->
            val whole = AssetRegion(asset.id, 0.0, requireNotNull(asset.durationSeconds))
            tracked(run, "source ${index + 1} segments", segmentCorpus(whole, requestedBy = "field:corpus/index"))
                .also { pending -> onCancel { pending.cancel(true) } }
                .thenCompose {
                    if (future.isDone) throw CancellationException("run ended")
                    tracked(
                            run,
                            "source ${index + 1} descriptors",
                            embedCorpus(whole, requestedBy = "field:corpus/index"),
                        )
                        .also { pending -> onCancel { pending.cancel(true) } }
                }
        }
        CompletableFuture.allOf(*jobs.toTypedArray()).thenApply { assets.first() }
    }
}
```

`segmentCorpus` records deterministic windows and their previous/next source neighbours.
`embedCorpus` pools the five descriptor groups over those exact windows. Re-running either unchanged
request returns its saved result immediately.

## Query, await and visualize

A query is a job too, since the query passage has to be measured. Once it completes,
`corpusPreview` loads the neighbours and `corpusMicroscopeHtml` shows them with players you can
listen to. This query leaves out Nancy itself, so every result comes from Candlestick; set
`includeSameAsset = true` to search the query's own recording as well:

```diffstatic {data-lang=kotlin}
val query = AssetRegion(nancy.id, startSeconds = 18.0, durationSeconds = 6.0)

runAsync<CorpusPreview>(
    onResult = { result ->
        preview = result
        canonical =
            nancy.representations.firstOrNull { it.role == "canonical_project" }
                ?: nancy.representations.first { it.mediaType in ["audio/wav", "audio/x-wav"] }
        display("microscope", corpusMicroscopeHtml(preview, canonical))
    },
    onError = { failure -> markdown("query error", "Query failed: ${(failure.message ?: failure.toString()).md}") },
) {
    val run = RunScope.current()
    val neighbours = tracked(
        run,
        "Nancy neighbours",
        findCorpusNeighbours(
            query,
            limit = 12,
            includeSameAsset = false,
            weights = CorpusDistanceWeights(
                harmony = 0.8,
                pitch = 0.55,
                texture = 1.35,
                rhythm = 0.7,
                dynamics = 0.35,
            ),
            requestedBy = "field:corpus/query",
        ),
    )
    onCancel { neighbours.cancel(true) }
    neighbours.thenCompose { result ->
        if (future.isDone) throw CancellationException("run ended")
        client.corpusPreview(result.id).also { pending -> onCancel { pending.cancel(true) } }
    }
}
```

Each neighbour card shows where the window comes from, a bar for each of the five distances, and a
player that crossfades from the query into the neighbour. The query and neighbour players both start
at zero, so hovering, zooming, clicking and playback stay aligned between the two recordings. Each
card also lists the window's timestamps in its source and the ids of the windows before and after
it.

## Place a neighbour as a box

Show the microscope with `showCorpusMicroscope` instead of `display`, and every neighbour card gets a
**place** button. Run this fragment after the query has produced its microscope. Pressing it makes a new box beside this one, named after the recording and the
window, whose code plays that window. It is ordinary box code: edit the times to move or stretch the
window, then run it.

```diffstatic {data-lang=kotlin}
showCorpusMicroscope(preview, canonical)
```

`placeCorpusNeighbour(neighbour)` does the same from code. This box places the first neighbour and
shows the code the new box was given:

```diffstatic_runner {data-lang=kotlin screenshot="assets/audio_ai_corpus_microscope/placed.png" capture="box" open-outputs="true" wait="2" padding="16" timeout="120" w="900" h="260" embed=true name="corpus-placed" alt="The code of a box placed from the corpus microscope: a comment naming Final Out at Candlestick Park from 21.00 to 27.00 seconds, a corpusClip call for that window, and audio(clip.sampler())"}
:::static
import java.io.File

val folder = File("public/docs/assets/audio_ai_corpus_microscope")
val preview = FieldAudioClient.json.decodeFromString<CorpusPreview>(File(folder, "corpus.json").readText())
:::fold show the placed box's name and code
val placed = placeCorpusNeighbour(preview.neighbours.first())
markdown("placed", "**${placed.name}**\n\n```kotlin\n${placed.properties["code"]}```")
```

`corpusClip` fetches the recording from the Field audio service once, keeps it under
`~/.afield/cache/field-audio`, and cuts out the window; `clip.sampler()` plays it from the start.

See also: the [EnCodec round trip](audio_ai_codec.md) for the token representation,
and [Short-time spectral spaces](audio_ai_spectral_embeddings.md).
