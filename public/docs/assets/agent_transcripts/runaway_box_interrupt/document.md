# Field Document: runaway_box_interrupt

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. Runaway Loop

<!-- field-box id="agent_runaway_loop" index="0" -->

### Narrative

From `code`

This loop never ends. After about five seconds Field throws out of it
("Box interrupted: ..."); run Interrupt Running Box to stop it sooner.

```edn [field-box id="agent_runaway_loop" role="properties"]
{"id" "agent_runaway_loop" "index" 0 "name" "Runaway Loop" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Runaway Loop" "_tombstone" false "frame" #afield/rect [80.0 80.0 320.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_runaway_loop_code_.kt" "lines" 6 "characters" 183 "sha256" "c6a33396996a8fda0df6392385e167dba85ea0119c3d8196c726505a62925617"}]}
```

```kotlin [field-property box="agent_runaway_loop" name="code" path="agent_runaway_loop_code_.kt"]
// This loop never ends. After about five seconds Field throws out of it
// ("Box interrupted: ..."); run Interrupt Running Box to stop it sooner.
var i = 0L
while (true) {
    i++
}
```

## 2. After The Runaway

<!-- field-box id="agent_after_runaway" index="1" -->

```edn [field-box id="agent_after_runaway" role="properties"]
{"id" "agent_after_runaway" "index" 1 "name" "After The Runaway" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "After The Runaway" "_tombstone" false "frame" #afield/rect [440.0 80.0 320.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_after_runaway_code_.kt" "lines" 1 "characters" 61 "sha256" "8c906ba5086d941f65d918b3acfca2574eba421d9b6b07855e930084fb566cfb"}]}
```

```kotlin [field-property box="agent_after_runaway" name="code" path="agent_after_runaway_code_.kt"]
markdown("main", "Field is running again: 6 * 7 = ${6 * 7}")
```

