# Field Document: audio_ai_corpus_microscope

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. 2 · index Nancy and Candlestick, then explore

<!-- field-box id="index_and_explore_corpus" index="0" -->

```edn [field-box id="index_and_explore_corpus" role="properties"]
{"id" "index_and_explore_corpus" "index" 0 "name" "2 · index Nancy and Candlestick, then explore" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 · index Nancy and Candlestick, then explore" "_tombstone" false "frame" #afield/rect [520.0 40.0 440.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "index_and_explore_corpus_code_.kt" "lines" 60 "characters" 2724 "sha256" "bbe11a8844f00b788f338080323456bf4b923c9c84e6a59a8e5d4562ce2d13c7"}]}
```

```kotlin [field-property box="index_and_explore_corpus" name="code" path="index_and_explore_corpus_code_.kt"]
import afield.stdlib.noir.audioai.*
import java.util.concurrent.CompletableFuture

val client = FieldAudioClient()
display("job monitor", fieldAudioJobMonitor(heightPixels = 330))

fun tracked(label: String, operation: ModelOperation): CompletableFuture<AnalysisResult> =
    client.submit(operation).thenCompose { accepted ->
        display("$label job", fieldAudioJobStatusHtml(accepted))
        client.watch(
            accepted.jobId,
            onEvent = { event -> display("$label job", fieldAudioJobStatusHtml(accepted, event)) },
            onError = { error -> display("$label stream", "progress stream: ${error.message}") },
        )
        client.awaitAnalysis(accepted).thenApply { result ->
            client.inspect(accepted.jobId).thenAccept { job ->
                display("$label job", fieldAudioJobStatusHtml(job))
            }
            result
        }
    }

val lookups = listOf("*Nancy*", "*Candlestick Park*").map { client.assetByFilename(it) }
CompletableFuture.allOf(*lookups.toTypedArray()).thenCompose {
    val assets = lookups.map { it.join() }
    val indexes = assets.mapIndexed { index, asset ->
        val duration = requireNotNull(asset.durationSeconds) { "asset has no duration" }
        val whole = AssetRegion(asset.id, 0.0, duration)
        tracked("source ${index + 1} segments", segmentCorpus(
            whole, requestedBy = "field:corpus-microscope/index",
        )).thenCompose {
            tracked("source ${index + 1} descriptors", embedCorpus(
                whole, requestedBy = "field:corpus-microscope/index",
            ))
        }
    }
    CompletableFuture.allOf(*indexes.toTypedArray()).thenApply { assets.first() }
}.thenCompose { nancy ->
    val query = AssetRegion(nancy.id, startSeconds = 18.0, durationSeconds = 6.0)
    tracked("Nancy neighbour query", findCorpusNeighbours(
        query,
        limit = 12,
        includeSameAsset = false,
        weights = CorpusDistanceWeights(
            harmony = 0.8, pitch = 0.55, texture = 1.35, rhythm = 0.7, dynamics = 0.35,
        ),
        requestedBy = "field:corpus-microscope/query",
    )).thenCompose { result ->
        client.corpusPreview(result.id).thenApply { preview -> nancy to preview }
    }
}.thenAccept { (nancy, preview) ->
    val canonical = nancy.representations.first { it.role == "canonical_project" }
    display("corpus microscope", corpusMicroscopeHtml(
        preview, canonical, width = 1100, cardWidth = 510,
    ))
}.exceptionally { error ->
    val message = error.cause?.message ?: error.message ?: error.toString()
    display("corpus error", """<pre style="color:#f1aaaa;background:#28191b;padding:12px;white-space:pre-wrap">$message</pre>""")
    null
}
```

## 2. 1 · corpus microscope guide and jobs

<!-- field-box id="corpus_microscope_guide" index="1" -->

```edn [field-box id="corpus_microscope_guide" role="properties"]
{"id" "corpus_microscope_guide" "index" 1 "name" "1 · corpus microscope guide and jobs" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 · corpus microscope guide and jobs" "_tombstone" false "frame" #afield/rect [40.0 40.0 440.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "corpus_microscope_guide_code_.kt" "lines" 10 "characters" 990 "sha256" "ca52470eed288ee77f94922e86f9863e686bbaa33527c3c87e64f78335977a3e"}]}
```

```kotlin [field-property box="corpus_microscope_guide" name="code" path="corpus_microscope_guide_code_.kt"]
import afield.stdlib.noir.audioai.*

display("guide", """
<section style="width:1040px;max-width:100%;box-sizing:border-box;background:#151a20;color:#dbe3e9;padding:18px;border:1px solid #303c46;border-radius:8px;font:13px/1.5 system-ui,sans-serif">
  <h2 style="margin:0 0 8px;color:#7fcbd7">Corpus microscope</h2>
  <p>Run box 2 for the complete path: resolve Nancy and Candlestick by filename; submit and await multi-scale segmentation and grouped descriptor jobs; query a six-second Nancy window; then audition the nearest windows and automatic source-to-neighbour splices.</p>
  <p>Every descriptor is an immutable NPZ asset. Retrieval weights remain visible and editable: harmony, pitch, texture, rhythm and dynamics. Players from different files share relative time, so hover, zoom, seek and the playback cursor line up without pretending that their file timestamps are the same.</p>
</section>
""".trimIndent())
display("all durable jobs", fieldAudioJobMonitor(heightPixels = 410))
```

