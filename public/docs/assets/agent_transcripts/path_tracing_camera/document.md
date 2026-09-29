# Field Document: path_tracing_camera

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. path tracing renderer

<!-- field-box id="path_trace_renderer" index="0" -->

```edn [field-box id="path_trace_renderer" role="properties"]
{"id" "path_trace_renderer" "index" 0 "name" "path tracing renderer" "parents" ["root"] "children" ["path_trace_camera"] "properties" {"children" #{#afield/box "path_trace_camera"} "parents" #{#afield/box "root"} "name" "path tracing renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 320.0 110.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "path_trace_renderer_code_.kt" "lines" 9 "characters" 208 "sha256" "b6c2bd22807c33e2c2e4d8717a315d29f7748bf5fc921acfae8e2aad2e0a0ec1"}]}
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

## 2. camera and depth of field

<!-- field-box id="path_trace_camera" index="1" -->

```edn [field-box id="path_trace_camera" role="properties"]
{"id" "path_trace_camera" "index" 1 "name" "camera and depth of field" "parents" ["root" "path_trace_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "path_trace_renderer"} "name" "camera and depth of field" "_tombstone" false "frame" #afield/rect [320.0 180.0 350.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "path_trace_camera_code_.kt" "lines" 41 "characters" 1947 "sha256" "f99b4e57d8e8d558eb9e6c89e565ae5f1138644167b9e1d94117ad20193d2f50"}]}
```

```kotlin [field-property box="path_trace_camera" name="code" path="path_trace_camera_code_.kt"]
import afield.four.fabricated.*
import afield.four.util.Camera
import afield.four.util.keyCamera
import afield.stdlib.linalg.Vector3f
val scene = PathTraceScene()
val grey = scene.material(PathTraceMaterial.diffuse(0.35f, 0.35f, 0.35f))
val light = scene.material(PathTraceMaterial.diffuse(0.7f, 0.7f, 0.7f))
for (z in -8..4) for (x in -7..7) {
    val m = if ((x+z) and 1 == 0) grey else light
    scene.quad(Vector3f(x.toFloat(),0f,z.toFloat()), Vector3f(x.toFloat(),0f,z+1f),
        Vector3f(x+1f,0f,z+1f), Vector3f(x+1f,0f,z.toFloat()), m)
}
val red = scene.material(PathTraceMaterial.plastic(0.65f, 0.035f, 0.015f, 0.25f))
val gold = scene.material(PathTraceMaterial.metal(0.95f, 0.7f, 0.3f, 0.18f))
val blue = scene.material(PathTraceMaterial.plastic(0.025f, 0.12f, 0.6f, 0.3f))
scene.sphere(Vector3f(-1.45f,0.65f,2.3f), 0.65f, red, 48, 24)
scene.sphere(Vector3f(0f,0.65f,0f), 0.65f, gold, 48, 24)
scene.sphere(Vector3f(1.8f,0.65f,-3f), 0.65f, blue, 48, 24)
val camera = Camera(renderer).also {
    it.at.set(0f,2.6f,8f)
    it.target.set(0f,0.65f,0f)
    it.p.fov = 0.62
}
val env = PathTraceEnvironment.fromFile("public/docs/assets/fabricate_demo/venice_sunset_1k.exr")
prop<PathTracingRenderer>(tracer)
tracer = pathTracingHere(scene, env, width=1280, height=720, camera = camera)
tracer.apertureRadius = 0.25f // world units; zero switches depth of field off
tracer.focusDistance = null // keep the camera target in focus
tracer.pixelJitter = 1f // full-pixel antialiasing; zero samples pixel centres
tracer.temporalReuse = true // retain compatible surface history during camera motion
tracer.temporalMaxSamples = 16
tracer.samplesPerFrame = 4
tracer.indirectClamp = 10f
tracer.showEnvironment = false
tracer.exposure = 1.5f
tracer.blitToScreen()
var cameraKeys by keyCamera(renderer = renderer, camera = camera)
frames {
    while (tracer.samples < 1024) { frame() }
    println("camera study converged: ${tracer.samples} samples")
}
```

