# Field Document: rhino_path_trace

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "watchdog" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. path tracing renderer

<!-- field-box id="rhino_trace_renderer" index="0" -->

```edn [field-box id="rhino_trace_renderer" role="properties"]
{"id" "rhino_trace_renderer" "index" 0 "name" "path tracing renderer" "parents" ["root"] "children" ["rhino_path_trace"] "properties" {"children" #{#afield/box "rhino_path_trace"} "parents" #{#afield/box "root"} "name" "path tracing renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 220.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rhino_trace_renderer_code_.kt" "lines" 10 "characters" 228 "sha256" "6cc4c9ecd83835a261dd6fcb979dc60223259f35724a98d21cd16ae88b047777"}]}
```

```kotlin [field-property box="rhino_trace_renderer" name="code" path="rhino_trace_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field · Rhino to path tracer")
    Renderer(window)
}
start(renderer::run)
```

## 2. a .3dm, path traced

<!-- field-box id="rhino_path_trace" index="1" -->

```edn [field-box id="rhino_path_trace" role="properties"]
{"id" "rhino_path_trace" "index" 1 "name" "a .3dm, path traced" "parents" ["root" "rhino_trace_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "rhino_trace_renderer"} "name" "a .3dm, path traced" "_tombstone" false "frame" #afield/rect [320.0 140.0 280.0 70.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rhino_path_trace_code_.kt" "lines" 71 "characters" 2874 "sha256" "a4ec59021b1031e8e97ad820c9e28910e7487690b4c3050d9088760a14d4f613"}]}
```

```kotlin [field-property box="rhino_path_trace" name="code" path="rhino_path_trace_code_.kt"]
import afield.geometry.rhino.*
import afield.graphics.scene.fabricated.PathTraceEnvironment
import afield.graphics.scene.fabricated.PathTraceMaterial
import afield.graphics.scene.fabricated.PathTraceScene
import afield.graphics.scene.fabricated.PathTracingRenderer
import afield.graphics.vulkan.util.Camera
import java.io.File

prop<PathTracingRenderer>(tracer)

// Start here when changing render cost, lighting or convergence.
val samplesPerFrame = 2
val maxBounces = 6
val exposure = 1.3f
val environmentRotation = 2.1f
val targetSamples = 600

// The reader is an optional native library; this says how to build it when it is absent.
check(Rhino3dm.isAvailable) { Rhino3dm.describe() }

// ---- the file ------------------------------------------------------------------
// Swap this for Rhino3dm.load("~/Downloads/model.3dm") to trace your own.
val dir = java.nio.file.Files.createTempDirectory("rhino-trace").toFile()
val model = Rhino3dm.load(Rhino3dm.writeNurbsSample(File(dir, "save_small.3dm")))
dir.deleteRecursively()
println(model)

// ---- the scene -----------------------------------------------------------------
// The fixture is in model units of about 50 across; a twentieth puts it near 2.5.
val scene = PathTraceScene()
val added = scene.rhino(model, scale = 0.05f)
println("scene: ${added.objects} objects, ${added.triangleCount} triangles, ${added.degenerateCount} dropped")
println("       ${added.materials.values.toSet().size} materials, one per display colour")
println("model.mesh(): ${model.mesh().triangleCount} triangles")

val floor = scene.material(PathTraceMaterial.plastic(0.55f, 0.56f, 0.58f, 0.55f))
val low = added.boundsMin.y - 0.01f
val reach = 8f
scene.quad(
    Vector3f(-reach, low, -reach),
    Vector3f(reach, low, -reach),
    Vector3f(reach, low, reach),
    Vector3f(-reach, low, reach),
    floor,
)

// ---- the render ----------------------------------------------------------------
val environment = PathTraceEnvironment.fromFile(
    File("public/docs/assets/fabricate_demo/venice_sunset_1k.exr").absolutePath,
)
val c = added.center
val span = maxOf(added.size.x, added.size.z)
val camera = Camera(renderer).also {
    it.at.set(c.x + span * 0.55f, c.y + span * 0.55f, c.z + span * 1.25f)
    it.target.set(c.x, c.y - added.size.y * 0.15f, c.z)
    it.p.fov = 0.6
}
// 1000 x 614 is the window's own aspect, so the blit fills it rather than letterboxing.
tracer = PathTracingRenderer(renderer, scene, environment, 1000, 614, camera).install()
tracer.samplesPerFrame = samplesPerFrame
tracer.maxBounces = maxBounces
tracer.exposure = exposure
tracer.environmentRotation = environmentRotation
tracer.blitToScreen()
println("${tracer.backend}: ${tracer.triangleCount} triangles")
frames {
    while (tracer.samples < targetSamples) {
        frame()
    }
    println("rhino trace converged: ${tracer.samples} samples")
}
```

