# Field Document: path_tracing_materials

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. path tracing renderer

<!-- field-box id="path_trace_renderer" index="0" -->

```edn [field-box id="path_trace_renderer" role="properties"]
{"id" "path_trace_renderer" "index" 0 "name" "path tracing renderer" "parents" ["root"] "children" ["path_trace_materials"] "properties" {"children" #{#afield/box "path_trace_materials"} "parents" #{#afield/box "root"} "name" "path tracing renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 320.0 110.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "path_trace_renderer_code_.kt" "lines" 9 "characters" 208 "sha256" "b6c2bd22807c33e2c2e4d8717a315d29f7748bf5fc921acfae8e2aad2e0a0ec1"}]}
```

```kotlin [field-property box="path_trace_renderer" name="code" path="path_trace_renderer_code_.kt"]
import afield.four.util.Options
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field · Path tracing")
    Renderer(window)
}
start(renderer::run)
```

## 2. progressive material study

<!-- field-box id="path_trace_materials" index="1" -->

```edn [field-box id="path_trace_materials" role="properties"]
{"id" "path_trace_materials" "index" 1 "name" "progressive material study" "parents" ["root" "path_trace_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "path_trace_renderer"} "name" "progressive material study" "_tombstone" false "frame" #afield/rect [320.0 180.0 350.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "path_trace_materials_code_.kt" "lines" 17 "characters" 795 "sha256" "f65605bc2aae0f65a1a908ec3251132601392512f73335890baeee5a511790a4"}]}
```

```kotlin [field-property box="path_trace_materials" name="code" path="path_trace_materials_code_.kt"]
import afield.four.fabricated.PathTraceDemo
import afield.four.fabricated.PathTracingRenderer
import java.io.File
prop<PathTracingRenderer>(tracer)
tracer = PathTraceDemo.install(renderer, width=1280, height=720,
    environmentPath=File("public/docs/assets/fabricate_demo/venice_sunset_1k.exr").absolutePath)
tracer.samplesPerFrame = 1
tracer.maxBounces = 5
tracer.indirectClamp = 10f // Optional biased suppression of indirect fireflies.
tracer.exposure = 1.5f
println("${tracer.backend}: ${tracer.triangleCount} triangles, ${tracer.width} x ${tracer.height}")
tracer.blitToScreen()
// This coroutine only announces capture readiness; the named scene owns the tracer.
frames {
    while (tracer.samples < 1024) { frame() }
    println("materials study converged: ${tracer.samples} samples")
}
```

