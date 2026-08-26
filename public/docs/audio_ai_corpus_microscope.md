# Corpus microscope: from recordings to related windows

The corpus microscope makes source recordings navigable before asking a generator to invent
anything. It cuts immutable recordings into overlapping windows at 1.5, 3, 6 and 12 seconds,
measures each window, and retrieves related material with an explicit weighted distance.

The descriptor is intentionally inspectable rather than a single opaque similarity number:

| Group | Measurements |
| --- | --- |
| harmony | mean and variation of twelve chroma classes |
| pitch | dominant-pitch centre, spread and voiced fraction |
| texture | MFCC plus spectral centroid, bandwidth, flatness and rolloff |
| rhythm | onset strength, peak density and local tempo statistics |
| dynamics | RMS centre, spread and range |

Full 71-dimensional vectors stay in content-addressed NPZ assets. SQLite stores their segment
identity and source adjacency. The browser receives only bounded metadata, audio handles and short
splice-preview handles; waveform envelopes continue to load at the resolution of the visible
window.

## Submit and monitor the index

Segmentation and descriptor extraction are ordinary cached `analysis.*` jobs. The following is
the complete Field path for Nancy and Final Out at Candlestick Park—there is no indexing hidden in
the document setup:

```diffstatic {data-lang=kotlin}
import afield.stdlib.noir.audioai.*
import java.util.concurrent.CompletableFuture

val client = FieldAudioClient()
display("all jobs", fieldAudioJobMonitor(heightPixels = 330))

fun tracked(label: String, operation: ModelOperation): CompletableFuture<AnalysisResult> =
    client.submit(operation).thenCompose { accepted ->
        display("$label job", fieldAudioJobStatusHtml(accepted))
        client.watch(accepted.jobId) { event ->
            display("$label job", fieldAudioJobStatusHtml(accepted, event))
        }
        client.awaitAnalysis(accepted)
    }

val lookups = listOf("*Nancy*", "*Candlestick Park*").map { client.assetByFilename(it) }
CompletableFuture.allOf(*lookups.toTypedArray()).thenCompose {
    val assets = lookups.map { it.join() }
    val jobs = assets.map { asset ->
        val whole = AssetRegion(asset.id, 0.0, requireNotNull(asset.durationSeconds))
        tracked("segments", segmentCorpus(whole, requestedBy = "field:corpus/index"))
            .thenCompose {
                tracked("descriptors", embedCorpus(whole, requestedBy = "field:corpus/index"))
            }
    }
    CompletableFuture.allOf(*jobs.toTypedArray()).thenApply { assets.first() }
}
```

`segmentCorpus` records deterministic windows and their previous/next source neighbours.
`embedCorpus` pools the five descriptor groups over those exact windows. Re-running either unchanged
request returns its persisted result immediately.

## Query, await and visualize

Retrieval is another job because the query descriptor is analysis work too. Once it completes,
`corpusPreview` hydrates the compact neighbour records and `corpusMicroscopeHtml` supplies the live
audition surface. This query excludes Nancy itself, so every result comes from Candlestick; set
`includeSameAsset = true` when source-local echoes are useful:

```diffstatic {data-lang=kotlin}
val query = AssetRegion(nancy.id, startSeconds = 18.0, durationSeconds = 6.0)

tracked("Nancy neighbours", findCorpusNeighbours(
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
)).thenCompose { result ->
    client.corpusPreview(result.id)
}.thenAccept { preview ->
    val canonical = nancy.representations.first { it.role == "canonical_project" }
    display("microscope", corpusMicroscopeHtml(preview, canonical))
}
```

Each neighbour card contains its exact source window, grouped distance bars and an automatic
equal-power query-to-neighbour splice. Query and neighbour players map their own segment beginnings
to shared time zero; hover, zoom, clicking and playback cursors therefore remain aligned across
different recordings. Source timestamps and previous/next segment ids remain visible provenance,
not an implied common timeline.

The loadable `audio_ai_corpus_microscope` operation transcript contains the guide, durable monitor,
and complete Nancy/Candlestick indexing and query box. Timeline placement is outside
this first microscope. Continue with the [EnCodec reconstruction microscope](audio_ai_codec.md) for
the token representation that the forthcoming surgery editor will manipulate.
