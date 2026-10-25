# Field Document: audio_ai_gesture_board

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 2 · generate and rank

<!-- field-box id="generate_and_rank" index="0" -->

```edn [field-box id="generate_and_rank" role="properties"]
{"id" "generate_and_rank" "index" 0 "name" "2 · generate and rank" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 · generate and rank" "_tombstone" false "frame" #afield/rect [480.0 40.0 400.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "generate_and_rank_code_.kt" "lines" 45 "characters" 1827 "sha256" "19763bf17dcaf2fe47ff726f62ccf18e13480b96e6b522f95215574760ef2d51"}]}
```

```kotlin [field-property box="generate_and_rank" name="code" path="generate_and_rank_code_.kt"]
val client = cached { FieldAudioClient() }
val sketch = gestureBoard(18.0, "uneasy nocturnal orchestra, old optical-film texture") {
    prompt(0.0, 5.0, "almost motionless low strings")
    prompt(5.0, 8.0, "woodwind fragments begin to circle")
    prompt(13.0, 5.0, "brass shadow, then an unresolved decay")
    event(5.2, "first disturbance", 0.45)
    event(13.1, "dark accent", 0.92, 1.6)
    curve("breath", GestureCurveKind.ACTIVITY) {
        point(0.0, 0.08)
        point(5.0, 0.16)
        point(10.0, 0.55)
        point(13.1, 0.94)
        point(18.0, 0.12)
    }
    curve("event density", GestureCurveKind.DENSITY, 1.25) {
        point(0.0, 0.05)
        point(7.0, 0.22)
        point(12.5, 0.78)
        point(18.0, 0.08)
    }
}

display("gesture", gestureBoardHtml(sketch))
runAsync(
    onResult = { result: CandidateSet ->
        display("ranked gesture", gestureBoardHtml(sketch, candidates = result))
        display("audition", candidateBoardHtml(result))
    },
    onError = { failure ->
        markdown("generation error", "Generation failed: ${(failure.message ?: failure.toString()).md}")
    },
) {
    val lookup = client.assetByFilename("*Nancy*")
    onCancel { lookup.cancel(false) }
    lookup.thenCompose { source ->
        if (future.isDone) throw java.util.concurrent.CancellationException("run ended")
        val region = AssetRegion(source.id, startSeconds = 18.0, durationSeconds = 4.5)
        val submitted = client.submit(gestureCandidates(region, sketch, seeds = 0L until 8L))
        onCancel { submitted.cancel(false) }
        submitted.thenCompose { accepted ->
            if (future.isDone) throw java.util.concurrent.CancellationException("run ended")
            client.awaitCandidates(accepted).also { polling -> onCancel { polling.cancel(false) } }
        }
    }
}
```

## 2. 1 · draw the gesture

<!-- field-box id="draw_the_gesture" index="1" -->

```edn [field-box id="draw_the_gesture" role="properties"]
{"id" "draw_the_gesture" "index" 1 "name" "1 · draw the gesture" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 · draw the gesture" "_tombstone" false "frame" #afield/rect [40.0 40.0 400.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "draw_the_gesture_code_.kt" "lines" 33 "characters" 1161 "sha256" "02140c7e0c6013d228ceb0b051963879cc00b29ad68248ce9a8a136e845e40a0"}]}
```

```kotlin [field-property box="draw_the_gesture" name="code" path="draw_the_gesture_code_.kt"]
val sketch = gestureBoard(18.0, "uneasy nocturnal orchestra, old optical-film texture") {
    prompt(0.0, 5.0, "almost motionless low strings")
    prompt(5.0, 8.0, "woodwind fragments begin to circle")
    prompt(13.0, 5.0, "brass shadow, then an unresolved decay")
    event(5.2, "first disturbance", strength = 0.45)
    event(13.1, "dark accent", strength = 0.92, weight = 1.6)
    curve("breath", GestureCurveKind.ACTIVITY) {
        point(0.0, 0.08)
        point(5.0, 0.16)
        point(10.0, 0.55)
        point(13.1, 0.94)
        point(18.0, 0.12)
    }
    curve("event density", GestureCurveKind.DENSITY, weight = 1.25) {
        point(0.0, 0.05)
        point(7.0, 0.22)
        point(12.5, 0.78)
        point(18.0, 0.08)
    }
    curve("register drift", GestureCurveKind.REGISTER, weight = 0.7) {
        point(0.0, 0.18)
        point(9.0, 0.48)
        point(14.0, 0.72)
        point(18.0, 0.31)
    }
    curve("surface light", GestureCurveKind.BRIGHTNESS, weight = 0.6) {
        point(0.0, 0.13)
        point(11.0, 0.27)
        point(13.1, 0.68)
        point(18.0, 0.16)
    }
}
display("main", gestureBoardHtml(sketch, width = 1100))
```

