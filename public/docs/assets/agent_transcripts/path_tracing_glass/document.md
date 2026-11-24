# Field Document: path_tracing_glass

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
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
{"id" "path_trace_glass" "index" 1 "name" "glass material study" "parents" ["root" "path_trace_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "path_trace_renderer"} "name" "glass material study" "_tombstone" false "frame" #afield/rect [320.0 180.0 350.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "path_trace_glass_code_.kt" "lines" 71 "characters" 2396 "sha256" "457963fcae05c751d990318dab00a6964eecacc1f384b27540655ea4c741caf5"}]}
```

```kotlin [field-property box="path_trace_glass" name="code" path="path_trace_glass_code_.kt"]
import afield.graphics.scene.fabricated.*
import afield.graphics.vulkan.util.Camera

prop<PathTracingRenderer>(tracer)
val scene = PathTraceScene()
val dark = scene.material(PathTraceMaterial.diffuse(0.055f, 0.055f, 0.055f))
val light = scene.material(PathTraceMaterial.diffuse(0.55f, 0.55f, 0.55f))
for (x in -10 until 10) for (z in -6 until 8) {
    val a = x * 0.55f
    val b = z * 0.55f
    val m = if ((x + z) and 1 == 0) light else dark
    scene.quad(
        Vector3f(a, 0f, b),
        Vector3f(a, 0f, b + 0.55f),
        Vector3f(a + 0.55f, 0f, b + 0.55f),
        Vector3f(a + 0.55f, 0f, b),
        m,
    )
}
// Narrow stripes reveal refraction and frosting against a common background.
for (i in 0 until 18) {
    val y = i * 0.18f
    scene.quad(
        Vector3f(-6f, y, -2.5f),
        Vector3f(6f, y, -2.5f),
        Vector3f(6f, y + 0.18f, -2.5f),
        Vector3f(-6f, y + 0.18f, -2.5f),
        if (i and 1 == 0) light else dark,
    )
}
val clear = scene.material(PathTraceMaterial.glass())
val green = scene.material(PathTraceMaterial.glass(attenuationColor = Vector3f(0.45f, 0.9f, 0.65f)))
val frosted = scene.material(PathTraceMaterial.glass(roughness = 0.32f))
val sheet = scene.material(PathTraceMaterial.thinGlass(tint = Vector3f(0.65f, 0.85f, 1f)))
scene.sphere(Vector3f(-2.7f, 0.8f, 0f), 0.8f, clear, 48, 24)
scene.sphere(Vector3f(-0.9f, 0.8f, 0f), 0.8f, green, 48, 24)
scene.sphere(Vector3f(0.9f, 0.8f, 0f), 0.8f, frosted, 48, 24)
scene.quad(
    Vector3f(2f, 0.05f, 0.1f),
    Vector3f(3.4f, 0.05f, 0.1f),
    Vector3f(3.4f, 1.7f, 0.1f),
    Vector3f(2f, 1.7f, 0.1f),
    sheet,
)
val camera = Camera(renderer).also {
    it.at.set(0f, 2.7f, 9.5f)
    it.target.set(0f, 0.8f, 0f)
    it.p.fov = 0.52
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

