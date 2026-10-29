# Field Document: path_tracing_glass

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. path tracing renderer

<!-- field-box id="path_trace_renderer" index="0" -->

```edn [field-box id="path_trace_renderer" role="properties"]
{"id" "path_trace_renderer" "index" 0 "name" "path tracing renderer" "parents" ["root"] "children" ["path_trace_glass"] "properties" {"children" #{#afield/box "path_trace_glass"} "parents" #{#afield/box "root"} "name" "path tracing renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 320.0 110.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "path_trace_renderer_code_.kt" "lines" 10 "characters" 220 "sha256" "d9a523d21baae9bd403e31097aeb6360849ac2629258b04f0145cc3d54c4d2a9"}]}
```

```kotlin [field-property box="path_trace_renderer" name="code" path="path_trace_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field · Path tracing")
    Renderer(window)
}
start(renderer::run)
```

## 2. glass material study

<!-- field-box id="path_trace_glass" index="1" -->

```edn [field-box id="path_trace_glass" role="properties"]
{"id" "path_trace_glass" "index" 1 "name" "glass material study" "parents" ["root" "path_trace_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "path_trace_renderer"} "name" "glass material study" "_tombstone" false "frame" #afield/rect [320.0 180.0 350.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "path_trace_glass_code_.kt" "lines" 23 "characters" 775 "sha256" "5d766ed17986573226a0406b6120e31ee11b6ed1da9dc5e97d841f543361de1a"}]}
```

```kotlin [field-property box="path_trace_glass" name="code" path="path_trace_glass_code_.kt"]
import afield.graphics.scene.fabricated.PathTraceGlassDemo
import afield.graphics.scene.fabricated.PathTracingRenderer
import java.io.File

prop<PathTracingRenderer>(tracer)
tracer = PathTraceGlassDemo.install(
    renderer,
    width = 1280,
    height = 720,
    environmentPath = File("public/docs/assets/fabricate_demo/venice_sunset_1k.exr").absolutePath,
)
tracer.samplesPerFrame = 1
tracer.maxBounces = 10
tracer.exposure = 1.5f
println("${tracer.backend}: ${tracer.triangleCount} triangles, ${tracer.width} x ${tracer.height}")
tracer.blitToScreen()
// This coroutine only announces capture readiness; the named scene owns the tracer.
frames {
    while (tracer.samples < 2048) {
        frame()
    }
    println("glass study converged: ${tracer.samples} samples")
}
```

