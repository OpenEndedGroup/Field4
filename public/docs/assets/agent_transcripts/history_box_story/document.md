# Field Document: history_box_story

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. sketch

<!-- field-box id="history_sketch" index="0" -->

### Narrative

From `code`

A ring of points, saved four times.

```edn [field-box id="history_sketch" role="properties"]
{"id" "history_sketch" "index" 0 "name" "sketch" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "sketch" "_tombstone" false "frame" #afield/rect [60.0 60.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "history_sketch_code_.kt" "lines" 4 "characters" 158 "sha256" "fa55f3e3edfd55b1f1faff100289184368e39035583a8a4842696aec569137a4"}]}
```

```kotlin [field-property box="history_sketch" name="code" path="history_sketch_code_.kt"]
// A ring of points, saved four times.
val radius = 80.0
val ring = (0 until 12).map { it * 30.0 }
println("radius " + radius + ", " + ring.size + " points")
```

