# Field Document: path_traced_reconstruction

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="agent_graphics_context" index="0" -->

```edn [field-box id="agent_graphics_context" role="properties"]
{"id" "agent_graphics_context" "index" 0 "name" "renderer" "parents" ["root"] "children" ["path_traced_reconstruction"] "properties" {"children" #{#afield/box "path_traced_reconstruction"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_graphics_context_code_.kt" "lines" 17 "characters" 332 "sha256" "f6d44c51bceafe38b76c3155a6a00e129dbebbff1c01db27a846eea5bd4da524"}]}
```

```kotlin [field-property box="agent_graphics_context" name="code" path="agent_graphics_context_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableRayTracing = true
Options.enableVR = false
Options.enableHDR = false
Options.profiling = false

once.renderer {
    window = Window("Field")
    Renderer(window)
}

println("agent graphics renderer ready")
start(renderer::run)
```

## 2. path traced reconstruction

<!-- field-box id="path_traced_reconstruction" index="1" -->

```edn [field-box id="path_traced_reconstruction" role="properties"]
{"id" "path_traced_reconstruction" "index" 1 "name" "path traced reconstruction" "parents" ["root" "agent_graphics_context"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "agent_graphics_context"} "name" "path traced reconstruction" "_tombstone" false "frame" #afield/rect [400.0 160.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "path_traced_reconstruction_code_.kt" "lines" 60 "characters" 2553 "sha256" "1ec07ef0c0bc9f0df9034986e30bbf212c9a2fbb6d556d5b1b17a09d0b3d70dc"}]}
```

```kotlin [field-property box="path_traced_reconstruction" name="code" path="path_traced_reconstruction_code_.kt"]
import afield.graphics.scene.fabricated.*
import afield.media.reconstruction.*

prop<PathTracedReconstruction>(lab)
fromContextWithNameOrNull<PathTracedReconstruction>("lab")?.close()
NgxSupport.rayReconstructionAvailability(renderer.device).getOrThrow()
val scene = PathTraceScene().apply {
    val floor = material(PathTraceMaterial.diffuse(.45f, .5f, .55f))
    val red = material(PathTraceMaterial.plastic(.65f, .055f, .025f, .22f))
    val gold = material(PathTraceMaterial.metal(.95f, .7f, .3f, .12f))
    val blue = material(PathTraceMaterial.metal(.3f, .5f, .8f, .5f))
    val light = material(PathTraceMaterial.emissive(12f, 8f, 5f))
    quad(Vector3f(-15f, 0f, -15f), Vector3f(-15f, 0f, 15f), Vector3f(15f, 0f, 15f), Vector3f(15f, 0f, -15f), floor)
    sphere(Vector3f(-1.7f, .8f, 0f), .8f, red, 48, 24)
    sphere(Vector3f(0f, .8f, 0f), .8f, gold, 48, 24)
    sphere(Vector3f(1.7f, .8f, 0f), .8f, blue, 48, 24)
    quad(Vector3f(-2f, 4f, -1f), Vector3f(0f, 4f, -1f), Vector3f(0f, 4f, 1f), Vector3f(-2f, 4f, 1f), light)
}
val camera = Camera(renderer).also {
    it.at.set(3.5f, 2.4f, 7.5f)
    it.target.set(0f, .65f, 0f)
    it.p.fov = .62
}
// Keep the tracer uninstalled: lab records trace -> raw guides -> NGX in one ordered pass.
val tracer = PathTracingRenderer(renderer, scene, PathTraceEnvironment.constant(.08f, .1f, .15f), 1920, 1080, camera)
lab = PathTracedReconstruction(tracer).install()
var view = 2
var move = false
var cut = false
var samples = 1
controlGroup("path-traced reconstruction") {
    slider("0: Raw · 1: Progressive · 2: RR", min = 0.0, max = 2.0, step = 1.0, initialValue = 2.0) {
        view = it.toInt()
    }
    slider("Samples per frame", min = 1.0, max = 8.0, step = 1.0, initialValue = 1.0) { samples = it.toInt() }
    checkbox("Move camera", initialValue = false) { move = it }
    button("Cut camera and reset") { if (it) cut = true }
}
displayOpen("path-traced reconstruction")
println(
    "1920 × 1080. RR receives each frame's raw multi-bounce lighting and measured surface guides. Opaque surfaces; pinhole camera.",
)
frames {
    var phase = 0.0
    while (true) {
        lab.view = PathTracedReconstruction.View.entries[view]
        tracer.samplesPerFrame = samples
        if (move) {
            phase += .015
            camera.at.x = (3.5 + kotlin.math.sin(phase) * 1.2).toFloat()
        }
        if (cut) {
            camera.at.z = if (camera.at.z > 7) 6f else 7.5f
            lab.reset("camera cut")
            cut = false
        }
        frame()
    }
}
    .end { lab.close() }
```

