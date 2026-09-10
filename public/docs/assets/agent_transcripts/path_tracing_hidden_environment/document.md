# Field Document: path_tracing_hidden_environment

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. path tracing renderer

<!-- field-box id="path_trace_renderer" index="0" -->

```edn [field-box id="path_trace_renderer" role="properties"]
{"id" "path_trace_renderer" "index" 0 "name" "path tracing renderer" "parents" ["root"] "children" ["path_trace_hidden_environment"] "properties" {"children" #{#afield/box "path_trace_hidden_environment"} "parents" #{#afield/box "root"} "name" "path tracing renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 220.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "path_trace_renderer_code_.kt" "lines" 9 "characters" 208 "sha256" "b6c2bd22807c33e2c2e4d8717a315d29f7748bf5fc921acfae8e2aad2e0a0ec1"}]}
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

## 2. environment lighting without a visible sky

<!-- field-box id="path_trace_hidden_environment" index="1" -->

```edn [field-box id="path_trace_hidden_environment" role="properties"]
{"id" "path_trace_hidden_environment" "index" 1 "name" "environment lighting without a visible sky" "parents" ["root" "path_trace_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "path_trace_renderer"} "name" "environment lighting without a visible sky" "_tombstone" false "frame" #afield/rect [320.0 140.0 250.0 70.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "path_trace_hidden_environment_code_.kt" "lines" 20 "characters" 873 "sha256" "1a865dc90ee9340dfff728bb1ef4b5e4fbd7422902e233fed18e849f3d929f4f"}]}
```

```kotlin [field-property box="path_trace_hidden_environment" name="code" path="path_trace_hidden_environment_code_.kt"]
import afield.four.fabricated.PathTraceDemo
import afield.four.fabricated.PathTracingRenderer
import afield.four.modules.fromContext
import java.io.File
val renderer: Renderer = fromContext()
prop<PathTracingRenderer>(tracer)
tracer = PathTraceDemo.install(renderer,
    File("public/docs/assets/fabricate_demo/venice_sunset_1k.exr").absolutePath)
tracer.samplesPerFrame = 1
tracer.maxBounces = 5
tracer.indirectClamp = 10f // Optional biased suppression of indirect fireflies.
tracer.exposure = 1.5f
println("${tracer.backend}: ${tracer.triangleCount} triangles, ${tracer.width} x ${tracer.height}")
tracer.showEnvironment = false
tracer.blitToScreen()
// This coroutine only announces capture readiness; the named scene owns the tracer.
frames {
    while (tracer.samples < 1024) { frame() }
    println("hidden environment study converged: ${tracer.samples} samples")
}
```

