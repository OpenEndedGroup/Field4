# Field Document: path_tracing_gltf

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. path tracing renderer

<!-- field-box id="path_trace_renderer" index="0" -->

```edn [field-box id="path_trace_renderer" role="properties"]
{"id" "path_trace_renderer" "index" 0 "name" "path tracing renderer" "parents" ["root"] "children" ["path_trace_gltf"] "properties" {"children" #{#afield/box "path_trace_gltf"} "parents" #{#afield/box "root"} "name" "path tracing renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 320.0 110.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "path_trace_renderer_code_.kt" "lines" 10 "characters" 220 "sha256" "d9a523d21baae9bd403e31097aeb6360849ac2629258b04f0145cc3d54c4d2a9"}]}
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

## 2. textured glTF path tracing

<!-- field-box id="path_trace_gltf" index="1" -->

```edn [field-box id="path_trace_gltf" role="properties"]
{"id" "path_trace_gltf" "index" 1 "name" "textured glTF path tracing" "parents" ["root" "path_trace_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "path_trace_renderer"} "name" "textured glTF path tracing" "_tombstone" false "frame" #afield/rect [320.0 180.0 350.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "path_trace_gltf_code_.kt" "lines" 43 "characters" 1512 "sha256" "76c60567dc21891e682d1c6e7e14de477821050d759b4e5a72f4cc411484f463"}]}
```

```kotlin [field-property box="path_trace_gltf" name="code" path="path_trace_gltf_code_.kt"]
import afield.graphics.scene.fabricated.*
import afield.graphics.vulkan.util.Camera

val scene = PathTraceScene()
val model = scene.importGltf("public/docs/assets/fabricate_demo/ToyCar.glb", Matrix4f().scale(20f))
val center = Vector3f(model.boundsMin).add(model.boundsMax).mul(0.5f)
val radius = model.boundsMin.distance(model.boundsMax) * 0.5f
val floor = scene.material(PathTraceMaterial.diffuse(0.35f, 0.35f, 0.35f))
val y = model.boundsMin.y - radius * 0.003f
val d = radius * 4f
scene.quad(
    Vector3f(center.x - d, y, center.z - d),
    Vector3f(center.x - d, y, center.z + d),
    Vector3f(center.x + d, y, center.z + d),
    Vector3f(center.x + d, y, center.z - d),
    floor,
)
val camera = Camera(renderer).also {
    it.target.set(center)
    it.at.set(center).add(radius * 1.7f, radius * 1.05f, radius * 2f)
    it.p.fov = 0.55
}
val tracer =
    PathTracingRenderer(
            renderer,
            scene,
            PathTraceEnvironment.fromFile("public/docs/assets/fabricate_demo/venice_sunset_1k.exr"),
            width = 1280,
            height = 720,
            camera = camera,
        )
        .install()
tracer.maxBounces = 8
tracer.samplesPerFrame = 4
tracer.rayEpsilon = (radius * 0.00001f).coerceAtLeast(0.0000001f)
tracer.showEnvironment = false
tracer.indirectClamp = 10f
tracer.exposure = 1.5f
tracer.blitToScreen()
frames {
    while (tracer.samples < 1024) frame()
    println("glTF study converged: ${tracer.samples} samples; ${model.triangleCount} imported triangles")
}
```

