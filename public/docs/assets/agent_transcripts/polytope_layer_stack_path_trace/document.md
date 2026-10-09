# Field Document: polytope_layer_stack_path_trace

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. path tracing renderer

<!-- field-box id="chain_renderer" index="0" -->

```edn [field-box id="chain_renderer" role="properties"]
{"id" "chain_renderer" "index" 0 "name" "path tracing renderer" "parents" ["root"] "children" ["layer_stack_trace"] "properties" {"children" #{#afield/box "layer_stack_trace"} "parents" #{#afield/box "root"} "name" "path tracing renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 220.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "chain_renderer_code_.kt" "lines" 9 "characters" 227 "sha256" "d6bb0208b7ccdc3eab42da36d02b0b1ee2bd13d37c2272b61cf0da6951d2a9b4"}]}
```

```kotlin [field-property box="chain_renderer" name="code" path="chain_renderer_code_.kt"]
import afield.four.util.Options
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field · polytope_layer_stack_path_trace")
    Renderer(window)
}
start(renderer::run)
```

## 2. polytope_layer_stack_path_trace

<!-- field-box id="layer_stack_trace" index="1" -->

```edn [field-box id="layer_stack_trace" role="properties"]
{"id" "layer_stack_trace" "index" 1 "name" "polytope_layer_stack_path_trace" "parents" ["root" "chain_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "chain_renderer"} "name" "polytope_layer_stack_path_trace" "_tombstone" false "frame" #afield/rect [320.0 140.0 300.0 70.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "layer_stack_trace_code_.kt" "lines" 48 "characters" 2234 "sha256" "b87b77eb3a82ecbe1570b9dcbff905a47b8242eaa16b2e01ec05a3f5a922844f"}]}
```

```kotlin [field-property box="layer_stack_trace" name="code" path="layer_stack_trace_code_.kt"]
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
Solid.scope {
    val mesh = conway("gC").polyhedron.canonicalize().polyhedron.triangulate(normals = NormalPolicy.Flat).mesh.weld(1e-6)
    val original = mesh.toSolid()
    val scaled = original.scaled(1.0 / Math.cbrt(original.volume))
    val bounds = scaled.bounds
    val solid = scaled.translated(-bounds.center.x.toDouble(), -bounds.center.y.toDouble(), -bounds.min.z.toDouble())
    val height = solid.bounds.max.z.toDouble()
    val count = 48
    val dz = height / count
    repeat(count) { i ->
        val t = i.toFloat() / (count - 1)
        val material = scene.material(PathTraceMaterial.plastic(0.08f + 0.8f*t, 0.65f - 0.4f*t, 0.8f - 0.72f*t, 0.32f))
        val slab = solid.slice(dz * (i + 0.5)).use { it.extruded(dz * 0.92) }
            .translated(0.0, 0.0, dz * i).rotated(-90.0, 0.0, 0.0)
        scene.importTriMesh(slab.toTriMesh(normals = true, minSharpAngle = 20.0), material)
    }
    println("48 canonical gC layers, source volume ${solid.volume}")
}
val floor = scene.material(PathTraceMaterial.plastic(0.55f, 0.56f, 0.58f, 0.55f))
scene.quad(Vector3f(-6f, -0.03f, -6f), Vector3f(6f, -0.03f, -6f), Vector3f(6f, -0.03f, 6f), Vector3f(-6f, -0.03f, 6f), floor)
val environment = PathTraceEnvironment.fromFile(File("public/docs/assets/fabricate_demo/venice_sunset_1k.exr").absolutePath)
val camera = Camera(renderer).also {
    it.at.set(2.45f, 2.8f, 3.6f)
    it.target.set(0f, 0.45f, 0f)
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
    println("layer_stack_trace study converged: ${tracer.samples} samples")
}
```

