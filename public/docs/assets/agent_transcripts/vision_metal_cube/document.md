# Field Document: vision_metal_cube

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. Vision Metal Cube

<!-- field-box id="agent_vision_metal_cube" index="0" -->

```edn [field-box id="agent_vision_metal_cube" role="properties"]
{"id" "agent_vision_metal_cube" "index" 0 "name" "Vision Metal Cube" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Vision Metal Cube" "_tombstone" false "frame" #afield/rect [80.0 80.0 200.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_vision_metal_cube_code_.kt" "lines" 17 "characters" 690 "sha256" "80c65800683407ad4ed63ff42b1adab64967d67b2118c820ec2429643e4e1dbf"}]}
```

```kotlin [field-property box="agent_vision_metal_cube" name="code" path="agent_vision_metal_cube_code_.kt"]
import afield.graphics.VisionMetalGeometryOrigin
import afield.graphics.visionMetal
import afield.graphics.visionMetalExampleScript
import afield.graphics.visionMetalExampleShader

// `geometry` is explicit: there is no fallback to the head's cube, so a program that
// neither ships vertices nor builds them in Kotlin/JS would draw nothing. This one asks
// for the built-in cube by name.
visionMetal(
    shader = visionMetalExampleShader,
    script = visionMetalExampleScript,
    uniforms = listOf("phase", "amount", "tint"),
    geometry = VisionMetalGeometryOrigin.BUILTIN,
).onStatus {
    println("vision-metal ${it.phase}: ${it.message}")
}
println("vision-metal cube published")
```

