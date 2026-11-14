# Field Document: path_tracing_materials

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. path tracing renderer

<!-- field-box id="path_trace_renderer" index="0" -->

```edn [field-box id="path_trace_renderer" role="properties"]
{"id" "path_trace_renderer" "index" 0 "name" "path tracing renderer" "parents" ["root"] "children" ["path_trace_materials"] "properties" {"children" #{#afield/box "path_trace_materials"} "parents" #{#afield/box "root"} "name" "path tracing renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 320.0 110.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "path_trace_renderer_code_.kt" "lines" 10 "characters" 220 "sha256" "d9a523d21baae9bd403e31097aeb6360849ac2629258b04f0145cc3d54c4d2a9"}]}
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

## 2. progressive material study

<!-- field-box id="path_trace_materials" index="1" -->

```edn [field-box id="path_trace_materials" role="properties"]
{"id" "path_trace_materials" "index" 1 "name" "progressive material study" "parents" ["root" "path_trace_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "path_trace_renderer"} "name" "progressive material study" "_tombstone" false "frame" #afield/rect [320.0 180.0 350.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "path_trace_materials_code_.kt" "lines" 43 "characters" 1745 "sha256" "78d0fb60abb05b7bb30faa08244aaa1be4abeff017a8bb67e28fbc6f70616263"}]}
```

```kotlin [field-property box="path_trace_materials" name="code" path="path_trace_materials_code_.kt"]
import afield.graphics.scene.fabricated.*
import afield.graphics.vulkan.util.Camera

prop<PathTracingRenderer>(tracer)
val scene = PathTraceScene()
// Colours are linear RGB; the sphere materials end with roughness.
val floor = scene.material(PathTraceMaterial.diffuse(0.48f, 0.48f, 0.48f))
val red = scene.material(PathTraceMaterial.plastic(0.65f, 0.055f, 0.025f, 0.22f))
val gold = scene.material(PathTraceMaterial.metal(0.95f, 0.7f, 0.3f, 0.12f))
val rough = scene.material(PathTraceMaterial.metal(0.55f, 0.65f, 0.8f, 0.55f))
// A floor and three independently editable spheres.
scene.quad(Vector3f(-15f, 0f, -15f), Vector3f(-15f, 0f, 15f), Vector3f(15f, 0f, 15f), Vector3f(15f, 0f, -15f), floor)
scene.sphere(Vector3f(-1.7f, 0.8f, 0f), 0.8f, red, 48, 24)
scene.sphere(Vector3f(0f, 0.8f, 0f), 0.8f, gold, 48, 24)
scene.sphere(Vector3f(1.7f, 0.8f, 0f), 0.8f, rough, 48, 24)
val camera = Camera(renderer).also {
    it.at.set(3.5f, 2.4f, 7.5f)
    it.target.set(0f, 0.65f, 0f)
    it.p.fov = 0.62
}

val environment = PathTraceEnvironment.fromFile("public/docs/assets/fabricate_demo/venice_sunset_1k.exr")
tracer = PathTracingRenderer(
    renderer,
    scene,
    environment,
    width = 1280,
    height = 720,
    camera = camera,
).install()
tracer.samplesPerFrame = 1
tracer.maxBounces = 5
tracer.indirectClamp = 10f // Optional biased suppression of indirect fireflies.
tracer.exposure = 1.5f
println("${tracer.backend}: ${tracer.triangleCount} triangles, ${tracer.width} x ${tracer.height}")
tracer.blitToScreen()
// This coroutine only announces capture readiness; the named scene owns the tracer.
frames {
    while (tracer.samples < 1024) {
        frame()
    }
    println("materials study converged: ${tracer.samples} samples")
}
```

