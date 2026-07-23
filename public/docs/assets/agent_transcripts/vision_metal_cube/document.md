# Field Document: vision_metal_cube

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. Vision Metal Cube

<!-- field-box id="agent_vision_metal_cube" index="0" -->

```edn [field-box id="agent_vision_metal_cube" role="properties"]
{"id" "agent_vision_metal_cube" "index" 0 "name" "Vision Metal Cube" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Vision Metal Cube" "_tombstone" false "frame" #afield/rect [80.0 80.0 280.0 130.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_vision_metal_cube_code_.kt" "lines" 12 "characters" 381 "sha256" "14bd67f95e51c286c30bfdede2b56a4d9d07880d415f3384f131e540c2f74ad2"}]}
```

```kotlin [field-property box="agent_vision_metal_cube" name="code" path="agent_vision_metal_cube_code_.kt"]
import afield.graphics.visionMetal
import afield.graphics.visionMetalExampleScript
import afield.graphics.visionMetalExampleShader

visionMetal(
    shader = visionMetalExampleShader,
    script = visionMetalExampleScript,
    uniforms = listOf("phase", "amount", "tint")
).onStatus {
    println("vision-metal ${it.phase}: ${it.message}")
}
println("vision-metal cube published")
```

