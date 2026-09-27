# Field Document: path_tracing_emitters

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. path tracing renderer

<!-- field-box id="pt_renderer" index="0" -->

```edn [field-box id="pt_renderer" role="properties"]
{"id" "pt_renderer" "index" 0 "name" "path tracing renderer" "parents" ["root"] "children" ["pt_emitters"] "properties" {"children" #{#afield/box "pt_emitters"} "parents" #{#afield/box "root"} "name" "path tracing renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 320.0 110.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pt_renderer_code_.kt" "lines" 9 "characters" 212 "sha256" "a27b9916860ed84fb3ab64c59102141d5a6485bb30f0e5e871b509f0049f3bac"}]}
```

```kotlin [field-property box="pt_renderer" name="code" path="pt_renderer_code_.kt"]
import afield.four.util.Options
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field · emitter sampling")
    Renderer(window)
}
start(renderer::run)
```

## 2. emitter sampling, on and off

<!-- field-box id="pt_emitters" index="1" -->

```edn [field-box id="pt_emitters" role="properties"]
{"id" "pt_emitters" "index" 1 "name" "emitter sampling, on and off" "parents" ["root" "pt_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "pt_renderer"} "name" "emitter sampling, on and off" "_tombstone" false "frame" #afield/rect [320.0 180.0 380.0 130.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pt_emitters_code_.kt" "lines" 55 "characters" 2755 "sha256" "78358aa84940ec00c93413f36d88fa00cb745da8072b34a75cf0d84d64d258aa"}]}
```

```kotlin [field-property box="pt_emitters" name="code" path="pt_emitters_code_.kt"]
import afield.four.fabricated.PathTraceEnvironment
import afield.four.fabricated.PathTraceMaterial
import afield.four.fabricated.PathTraceScene
import afield.four.fabricated.PathTracingRenderer
import afield.four.modules.fromContext
import afield.four.util.Camera
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f

val renderer: Renderer = fromContext()
prop<PathTracingRenderer>(tracer)

// Two lamps in the dark and nothing else: the case BSDF sampling alone cannot find.
// The wide one radiates from both faces; the small, bright one is single-sided and
// faces down, so its back is dark to the direct sampler exactly as it is to a hit.
val scene = PathTraceScene().apply {
    val floor = material(PathTraceMaterial.diffuse(0.72f, 0.70f, 0.66f))
    val wall = material(PathTraceMaterial.diffuse(0.58f, 0.22f, 0.18f))
    val ball = material(PathTraceMaterial.diffuse(0.75f, 0.75f, 0.78f))
    val warm = material(PathTraceMaterial.emissive(24f, 21f, 15f))
    val cool = material(PathTraceMaterial.emissive(60f, 74f, 110f).copy(doubleSided = false))
    quad(Vector3f(-5f, 0f, -5f), Vector3f(-5f, 0f, 5f), Vector3f(5f, 0f, 5f), Vector3f(5f, 0f, -5f), floor)
    quad(Vector3f(-5f, 0f, -3.2f), Vector3f(-5f, 5f, -3.2f), Vector3f(5f, 5f, -3.2f), Vector3f(5f, 0f, -3.2f), wall)
    sphere(Vector3f(0.55f, 0.6f, 0.7f), 0.6f, ball, 40, 20)
    quad(Vector3f(-1.3f, 2.6f, -0.9f), Vector3f(-0.5f, 2.6f, -0.9f),
        Vector3f(-0.5f, 2.6f, -0.1f), Vector3f(-1.3f, 2.6f, -0.1f), warm)
    quad(Vector3f(1.0f, 2.2f, 0.2f), Vector3f(1.35f, 2.2f, 0.2f),
        Vector3f(1.35f, 2.2f, 0.55f), Vector3f(1.0f, 2.2f, 0.55f), cool)
}
val camera = Camera(renderer).also {
    it.at.set(0.2f, 1.9f, 6.4f); it.target.set(0f, 0.9f, 0f); it.p.fov = 0.78
}
tracer = PathTracingRenderer(renderer, scene,
    PathTraceEnvironment.constant(0f), 640, 480, camera, name = "emitter-demo")
tracer.maxBounces = 3
tracer.samplesPerFrame = 2
tracer.showEnvironment = false
tracer.background = Vector4f(0f, 0f, 0f, 1f)
tracer.exposure = 1.6f
tracer.install()
tracer.blitToScreen()
println("${tracer.emitterCount} emissive triangles of ${tracer.triangleCount}, " +
    "in a black environment: they are the only light in this scene")

// Flip the one flag every 256 samples. Changing it resets accumulation, so the two
// estimators are always compared at the same budget, over and over. Off is a
// blizzard; on is the same image, clean. Comment out the loop to hold one of them.
frames {
    while (true) {
        while (tracer.samples < 256) frame()
        println("emitterSampling = ${tracer.emitterSampling} at ${tracer.samples} samples")
        tracer.emitterSampling = !tracer.emitterSampling
        while (tracer.samples >= 256) frame()
    }
}
```

