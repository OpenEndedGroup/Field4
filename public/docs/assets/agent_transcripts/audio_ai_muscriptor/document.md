# Field Document: audio_ai_muscriptor

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 1 · MuScriptor guide and jobs

<!-- field-box id="muscriptor_guide" index="0" -->

```edn [field-box id="muscriptor_guide" role="properties"]
{"id" "muscriptor_guide" "index" 0 "name" "1 · MuScriptor guide and jobs" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 · MuScriptor guide and jobs" "_tombstone" false "frame" #afield/rect [40.0 40.0 340.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "muscriptor_guide_code_.kt" "lines" 13 "characters" 1282 "sha256" "138cd4ab8bc14f57b3cd5ec5fa4c974b785d7323f44580ad0db0d47fc8f3ae22"}]}
```

```kotlin [field-property box="muscriptor_guide" name="code" path="muscriptor_guide_code_.kt"]
display(
    "guide",
    """
    <section style="width:1040px;max-width:100%;box-sizing:border-box;background:#151a20;color:#dbe3e9;padding:18px;border:1px solid #303c46;border-radius:8px;font:13px/1.5 system-ui,sans-serif">
      <h2 style="margin:0 0 8px;color:#7fcbd7">MuScriptor · recording to an instrument-labelled score</h2>
      <p><code>analysis.transcribe_music</code> is one model-neutral operation with three revision-pinned worker capabilities: small, medium, and large. It retains onset, offset, MIDI pitch, instrument group, activity curves, MessagePack events, performance-timed MIDI, and optional beat-quantized MIDI.</p>
      <p>Run box 2 from the committed real result without a server to explore the synchronized piano roll. Replace box 3's <code>sourcePattern</code> with a recording you have the rights to transcribe, then run it for asset lookup → submit → progress → durable result → source/score audition → MIDI download. Unchanged operations are cache hits.</p>
      <p style="color:#e5bd77">The code is MIT, but the gated model weights are CC BY-NC 4.0. Accept the selected model's Hugging Face terms before starting the worker.</p>
    </section>
    """
        .trimIndent(),
)
display("all durable jobs", fieldAudioJobMonitor(heightPixels = 430))
```

## 2. 2 · synchronized instrument piano roll

<!-- field-box id="muscriptor_visualizer_gallery" index="1" -->

```edn [field-box id="muscriptor_visualizer_gallery" role="properties"]
{"id" "muscriptor_visualizer_gallery" "index" 1 "name" "2 · synchronized instrument piano roll" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 · synchronized instrument piano roll" "_tombstone" false "frame" #afield/rect [420.0 40.0 340.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "muscriptor_visualizer_gallery_code_.kt" "lines" 19 "characters" 798 "sha256" "32cdced0e82a572b4cf4ced2909f5f2271e835ba51985c626db29fa957610ffe"}]}
```

```kotlin [field-property box="muscriptor_visualizer_gallery" name="code" path="muscriptor_visualizer_gallery_code_.kt"]
import afield.audio.engine.WavFloatArrayLoader
import java.io.File

val json = FieldAudioClient.json
val folder = File("public/docs/assets/audio_ai_muscriptor")
val preview = json.decodeFromString<AnalysisPreview>(File(folder, "preview.json").readText())
val wav = WavFloatArrayLoader.loadWavToFloatArray(File(folder, "source.wav").absolutePath)
val sync = "docs-real-muscriptor"
val source = AudioVisualizer.renderSamplesHtml(
    wav.samples,
    wav.sampleRate,
    options = AudioVisualizerOptions(width = 1040, waveformHeight = 130, syncGroup = sync),
    wavBytes = File(folder, "source.wav").readBytes(),
    label = "Sonic Pi recording · 8.192 seconds",
)
display(
    "transcription",
    source + analysisPreviewVisualizationHtml(preview, width = 1040, height = 400, syncGroup = sync),
)
```

## 3. 3 · submit, monitor, audition, download MIDI

<!-- field-box id="transcribe_field_render" index="2" -->

```edn [field-box id="transcribe_field_render" role="properties"]
{"id" "transcribe_field_render" "index" 2 "name" "3 · submit, monitor, audition, download MIDI" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 · submit, monitor, audition, download MIDI" "_tombstone" false "frame" #afield/rect [40.0 140.0 340.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "transcribe_field_render_code_.kt" "lines" 71 "characters" 3524 "sha256" "e8c83fc08d7aea221f504b575bd79e860e0b44e56bb39a29c1366090f3b26963"}]}
```

```kotlin [field-property box="transcribe_field_render" name="code" path="transcribe_field_render_code_.kt"]
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await

val client = cached { FieldAudioClient() }
val sourcePattern = "*sonic-pi-study*" // replace with an asset you may transcribe
// Filename matches can refer to different recordings; retain the resolved content ID.
val sync = "muscriptor-live"
val midiDirectory = Path.of(System.getProperty("java.io.tmpdir"), "field-audio-renders")
display("job monitor", fieldAudioJobMonitor(heightPixels = 330))

go {
    try {
        val source = client.assetByFilename(sourcePattern).await()
        display("source identity", source.id)
        val audio =
            source.representations.firstOrNull { it.role == "canonical_project" }
                ?: source.representations.first { it.mediaType in setOf("audio/wav", "audio/x-wav") }
        val duration = requireNotNull(audio.durationSeconds) { "source audio has no duration" }
        val region = AssetRegion(source.id, startSeconds = 0.0, durationSeconds = duration)
        display(
            "source",
            audio.audioVisualizerHtml(
                label = "$sourcePattern · ${duration.f2} seconds",
                options = AudioVisualizerOptions(
                    width = 1100,
                    waveformHeight = 145,
                    initialDurationSeconds = minOf(20.0, duration),
                    syncGroup = sync,
                ),
            ),
        )
        val result =
            client
                .trackAnalysis(
                    musicTranscriptionAnalysis(
                        region,
                        backend = MuscriptorBackend.SMALL,
                        tempoDetection = MuscriptorTempoDetection.BEST_EFFORT,
                        requestedBy = "field:audio-ai-muscriptor/live",
                    ),
                    onAccepted = { display("transcription job", fieldAudioJobStatusHtml(it)) },
                    onProgress = { job, event -> display("transcription job", fieldAudioJobStatusHtml(job, event)) },
                    onCompleted = { display("transcription job", fieldAudioJobStatusHtml(it)) },
                    onError = {}, // Report failure once through the surrounding coroutine.
                )
                .await()
        display("score", analysisVisualizationHtml(result, width = 1100, height = 420, syncGroup = sync))
        val transcription = client.musicTranscription(result).await()
        val byInstrument = transcription.notes.groupingBy { it.instrument }.eachCount()
        display("typed notes", "${transcription.notes.size} notes · $byInstrument")
        display(
            "interpretation",
            "Instrument labels are predictions. Compare pitches and onsets with the source; a completed job is not an accuracy guarantee.",
        )
        val downloads = mutableListOf<Path>()
        downloads.add(
            client
                .download(result.musicTranscriptionMidi(), midiDirectory.resolve("muscriptor-performance.mid"))
                .await(),
        )
        result.musicTranscriptionQuantizedMidiOrNull()?.let { midi ->
            downloads.add(client.download(midi, midiDirectory.resolve("muscriptor-quantized.mid")).await())
        }
        display("MIDI", downloads.joinToString("<br>") { "saved $it" })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        markdown("MuScriptor error", "Operation failed: ${(failure.message ?: failure.toString()).md}")
    }
}
```

