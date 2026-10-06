# Field Document: photo_mount_path_trace

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_session" "fileManagerAccessHistory"]}
```

## 1. path tracing renderer

<!-- field-box id="chain_renderer" index="0" -->

```edn [field-box id="chain_renderer" role="properties"]
{"id" "chain_renderer" "index" 0 "name" "path tracing renderer" "parents" ["root"] "children" ["photo_mount_trace"] "properties" {"children" #{#afield/box "photo_mount_trace"} "parents" #{#afield/box "root"} "name" "path tracing renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 220.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "chain_renderer_code_.kt" "lines" 9 "characters" 218 "sha256" "404e4cf711b0b112a27d48dff027acff33570d440178b677fb1aa915b8c21b59"}]}
```

```kotlin [field-property box="chain_renderer" name="code" path="chain_renderer_code_.kt"]
import afield.four.util.Options
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field · photo_mount_path_trace")
    Renderer(window)
}
start(renderer::run)
```

## 2. photo_mount_path_trace

<!-- field-box id="photo_mount_trace" index="1" -->

```edn [field-box id="photo_mount_trace" role="properties"]
{"id" "photo_mount_trace" "index" 1 "name" "photo_mount_path_trace" "parents" ["root" "chain_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "chain_renderer"} "name" "photo_mount_path_trace" "_tombstone" false "frame" #afield/rect [320.0 140.0 300.0 70.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "photo_mount_trace_code_.kt" "lines" 36 "characters" 1585 "sha256" "7db74bbc7dab3be9a6934a8ef496dfa125fe8d0dbbb82e36d26f37eaf2ad746d"}]}
```

```kotlin [field-property box="photo_mount_trace" name="code" path="photo_mount_trace_code_.kt"]
import afield.four.fabricated.*
import afield.four.util.Camera
import afield.manifold.*
import afield.stdlib.linalg.Vector3f
import afield.stdlib.polytope.*
import afield.examples.chain.PhotoToSolidChain
import java.io.File
prop<PathTracingRenderer>(tracer)
require(ManifoldLibrary.isAvailable) { ManifoldLibrary.describe() }
val scene = PathTraceScene()
val mounted = PhotoToSolidChain.mount()
val relief = scene.material(PathTraceMaterial.plastic(0.67f, 0.42f, 0.19f, 0.4f))
val frameMaterial = scene.material(PathTraceMaterial.plastic(0.18f, 0.22f, 0.27f, 0.28f))
scene.importTriMesh(mounted.relief, relief)
scene.importTriMesh(mounted.frame, frameMaterial)
println(mounted.summary())
val floor = scene.material(PathTraceMaterial.plastic(0.55f, 0.56f, 0.58f, 0.55f))
scene.quad(Vector3f(-6f, -0.16f, -6f), Vector3f(6f, -0.16f, -6f), Vector3f(6f, -0.16f, 6f), Vector3f(-6f, -0.16f, 6f), floor)
val environment = PathTraceEnvironment.fromFile(File("public/docs/assets/fabricate_demo/venice_sunset_1k.exr").absolutePath)
val camera = Camera(renderer).also {
    it.at.set(2.2f, 2.25f, 2.6f)
    it.target.set(0f, 0.1f, 0f)
    it.p.fov = 0.52
}
tracer = PathTracingRenderer(renderer, scene, environment, 1000, 614, camera).install()
tracer.samplesPerFrame = 4
tracer.maxBounces = 8
tracer.exposure = 1.35f
tracer.environmentRotation = 2.1f
tracer.indirectClamp = 10f
tracer.blitToScreen()
println("${tracer.backend}: ${tracer.triangleCount} triangles")
frames {
    while (tracer.samples < 900) { frame() }
    println("photo_mount_trace study converged: ${tracer.samples} samples")
}
```

