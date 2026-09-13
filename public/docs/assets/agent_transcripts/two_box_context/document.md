# Field Document: two_box_context

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. Agent Context Parent

<!-- field-box id="agent_context_parent" index="0" -->

```edn [field-box id="agent_context_parent" role="properties"]
{"id" "agent_context_parent" "index" 0 "name" "Agent Context Parent" "parents" ["root"] "children" ["agent_context_child"] "properties" {"children" #{#afield/box "agent_context_child"} "parents" #{#afield/box "root"} "name" "Agent Context Parent" "_tombstone" false "frame" #afield/rect [80.0 260.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_context_parent_code_.kt" "lines" 2 "characters" 99 "sha256" "482c86fc9bc22ae2008bf502ffa7f8a90bf20b93c85592446fe0ae67224d250d"}]}
```

```kotlin [field-property box="agent_context_parent" name="code" path="agent_context_parent_code_.kt"]
thisBox.setPropertyRaw("agent_context_value", "value from parent")
println("parent context ready")
```

## 2. Agent Context Child

<!-- field-box id="agent_context_child" index="1" -->

```edn [field-box id="agent_context_child" role="properties"]
{"id" "agent_context_child" "index" 1 "name" "Agent Context Child" "parents" ["root" "agent_context_parent"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "agent_context_parent"} "name" "Agent Context Child" "_tombstone" false "frame" #afield/rect [460.0 260.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_context_child_code_.kt" "lines" 2 "characters" 112 "sha256" "41c54395379746bd77fd771d6e273e5e65a4e0b794df771153a51ddb0c9df822"}]}
```

```kotlin [field-property box="agent_context_child" name="code" path="agent_context_child_code_.kt"]
val inherited = fromContextWithNameOrNull<String>("agent_context_value")
println("child inherited: $inherited")
```

