# Field Document: audio_ai_analysis

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 1 · analysis guide and jobs

<!-- field-box id="analysis_guide" index="0" -->

```edn [field-box id="analysis_guide" role="properties"]
{"id" "analysis_guide" "index" 0 "name" "1 · analysis guide and jobs" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 · analysis guide and jobs" "_tombstone" false "frame" #afield/rect [40.0 40.0 440.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "analysis_guide_code_.kt" "lines" 12 "characters" 1082 "sha256" "377776ef5289a305a91e5c49cebe86261ee43dbb55c10cb1c26be594db66705c"}]}
```

```kotlin [field-property box="analysis_guide" name="code" path="analysis_guide_code_.kt"]
display(
    "guide",
    """
    <section style="width:1040px;max-width:100%;box-sizing:border-box;background:#151a20;color:#dbe3e9;padding:18px;border:1px solid #303c46;border-radius:8px;font:13px/1.5 system-ui,sans-serif">
      <h2 style="margin:0 0 8px;color:#7fcbd7">Beat and pitch analysis</h2>
      <p><b>librosa</b> supplies the onset envelope, multi-scale tempograms, beat grid and monophonic pYIN track. <b>Basic Pitch</b> supplies polyphonic note events, activations, pitch bends and MIDI. Essentia is not loaded by this worker.</p>
      <p>Run box 2 for real cached beat and pitch previews of Nancy from 18–42 seconds. Run box 3 for the complete live path: filename lookup → source window → three submitted jobs → WebSocket progress → durable results → synchronized analysis board. The source player and every analysis lane share hover, zoom and playback time; click any plot to seek the recording. Re-running unchanged requests is a cache hit.</p>
    </section>
    """
        .trimIndent(),
)
display("all durable jobs", fieldAudioJobMonitor(heightPixels = 430))
```

## 2. 2 · real cached beat and pitch results

<!-- field-box id="analysis_visualizer_gallery" index="1" -->

```edn [field-box id="analysis_visualizer_gallery" role="properties"]
{"id" "analysis_visualizer_gallery" "index" 1 "name" "2 · real cached beat and pitch results" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 · real cached beat and pitch results" "_tombstone" false "frame" #afield/rect [520.0 40.0 440.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "analysis_visualizer_gallery_code_.kt" "lines" 19 "characters" 556 "sha256" "7c33ba1fbee777015e8c388988f2347ac28547ecf8b14023c30bb028d24a81bd"}]}
```

```kotlin [field-property box="analysis_visualizer_gallery" name="code" path="analysis_visualizer_gallery_code_.kt"]
import java.io.File

val folder = File("public/docs/assets/audio_ai_analysis")
val previews =
    ["beats", "pitch_mono", "pitch_poly"].map { label ->
        FieldAudioClient.json.decodeFromString<AnalysisPreview>(
            File(folder, "$label-preview.json").readText(),
        )
    }
markdown("source", "Nancy · 18–42 s · saved librosa and Basic Pitch results")
display(
    "analysis board",
    analysisPreviewDashboardHtml(
        previews,
        width = 1040,
        panelHeight = 250,
        syncGroup = "nancy-cached-analysis",
    ),
)
```

## 3. 3 · submit, monitor, await, visualize Nancy

<!-- field-box id="analyse_nancy" index="2" -->

```edn [field-box id="analyse_nancy" role="properties"]
{"id" "analyse_nancy" "index" 2 "name" "3 · submit, monitor, await, visualize Nancy" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 · submit, monitor, await, visualize Nancy" "_tombstone" false "frame" #afield/rect [40.0 190.0 440.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "analyse_nancy_code_.kt" "lines" 76 "characters" 3190 "sha256" "d752cde1c2e13c2b80ed718cf3a3491d5b2b85f0321bbc74521e0522b9f827d2"}]}
```

```kotlin [field-property box="analyse_nancy" name="code" path="analyse_nancy_code_.kt"]
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

val client = cached { FieldAudioClient() }
val start = 18.0
val duration = 24.0
val timeSync = "nancy-analysis"
display("all jobs", fieldAudioJobMonitor(heightPixels = 330))

runAsync(
    onResult = { results: List<AnalysisResult> ->
        displayClear("all jobs")
        for (label in ["beats", "monophonic pitch", "polyphonic pitch"]) displayClear("$label job")
        displayOpen("source")
        display(
            "analysis",
            analysisDashboardHtml(
                results,
                width = 1040,
                panelHeight = 250,
                syncGroup = timeSync,
                timeOffsetSeconds = start,
            ),
        )
        displayOpen("analysis")
    },
    onError = { failure ->
        markdown("analysis error", "Analysis failed: ${(failure.message ?: failure.toString()).md}")
    },
) {
    val showSource = progress<String> { display("source", it) }
    // HTTP continuations run elsewhere; every analysis still belongs to this box run.
    val run = RunScope.current()
    val lookup = client.assetByFilename("*Nancy*")
    onCancel { lookup.cancel(true) }
    lookup.thenCompose { source ->
        if (future.isDone) throw CancellationException("run ended")

        val region = AssetRegion(source.id, start, duration)
        val canonical =
            source.representations.firstOrNull { it.role == "canonical_project" }
                ?: source.representations.first { it.mediaType in ["audio/wav", "audio/x-wav"] }
        showSource(
            canonical.audioVisualizerHtml(
                label = "Nancy · 18–42 s",
                options = AudioVisualizerOptions(
                    width = 1040,
                    waveformHeight = 110,
                    initialStartSeconds = start,
                    initialDurationSeconds = duration,
                    initialPositionSeconds = start,
                    syncGroup = timeSync,
                ),
            ),
        )

        val requests = [
            "beats" to multiScaleBeatAnalysis(region, requestedBy = "field:analysis-example"),
            "monophonic pitch" to monophonicPitchAnalysis(region, requestedBy = "field:analysis-example"),
            "polyphonic pitch" to polyphonicPitchAnalysis(region, requestedBy = "field:analysis-example"),
        ]
        val analyses = requests.map { (label, request) ->
            RunScope.withCurrent(run) {
                    client.trackAnalysis(
                        request,
                        onAccepted = { display("$label job", fieldAudioJobStatusHtml(it)) },
                        onProgress = { job, event -> display("$label job", fieldAudioJobStatusHtml(job, event)) },
                        onCompleted = { display("$label job", fieldAudioJobStatusHtml(it)) },
                        onError = {}, // The outer operation reports failure once.
                    )
                }
                .also { pending -> onCancel { pending.cancel(true) } }
        }
        CompletableFuture.allOf(*analyses.toTypedArray()).thenApply { analyses.map { it.join() } }
    }
}
```

