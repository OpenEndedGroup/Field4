# Field Document: two_box_context

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. Agent Context Parent

<!-- field-box id="agent_context_parent" index="0" -->

```edn [field-box id="agent_context_parent" role="properties"]
{"id" "agent_context_parent" "index" 0 "name" "Agent Context Parent" "parents" ["root"] "children" ["agent_context_child"] "properties" {"children" #{#afield/box "agent_context_child"} "parents" #{#afield/box "root"} "name" "Agent Context Parent" "_tombstone" false "frame" #afield/rect [80.0 260.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_context_parent_code_.kt" "lines" 2 "characters" 77 "sha256" "2d1a160d878af1ed1ab9ed327ddff66411730e4b58988e33eb8374a770b0fcfb"}]}
```

```kotlin [field-property box="agent_context_parent" name="code" path="agent_context_parent_code_.kt"]
prop<String>(greeting) = "value from parent"
println("parent context ready")
```

## 2. Agent Context Child

<!-- field-box id="agent_context_child" index="1" -->

```edn [field-box id="agent_context_child" role="properties"]
{"id" "agent_context_child" "index" 1 "name" "Agent Context Child" "parents" ["root" "agent_context_parent"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "agent_context_parent"} "name" "Agent Context Child" "_tombstone" false "frame" #afield/rect [460.0 260.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_context_child_code_.kt" "lines" 1 "characters" 38 "sha256" "40e3dbabb7048e5bf514501a775f3e0da0df833c4c82efb7c60ef7a89b740627"}]}
```

```kotlin [field-property box="agent_context_child" name="code" path="agent_context_child_code_.kt"]
println("child inherited: $greeting")
```

