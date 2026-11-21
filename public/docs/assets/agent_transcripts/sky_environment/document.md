# Field Document: sky_environment

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="agent_graphics_context" index="0" -->

```edn [field-box id="agent_graphics_context" role="properties"]
{"id" "agent_graphics_context" "index" 0 "name" "renderer" "parents" ["root"] "children" ["sky_environment_scene" "sky_path_trace"] "properties" {"children" #{#afield/box "sky_environment_scene" #afield/box "sky_path_trace"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_graphics_context_code_.kt" "lines" 15 "characters" 275 "sha256" "e90393b044932ad7e022f5505bec768466f0d819aaa553c877bfe061d981c6d2"}]}
```

```kotlin [field-property box="agent_graphics_context" name="code" path="agent_graphics_context_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false
Options.profiling = false

once.renderer {
    window = Window("Field")
    Renderer(window)
}

println("agent graphics renderer ready")
start(renderer::run)
```

## 2. path traced under the sky

<!-- field-box id="sky_path_trace" index="1" -->

```edn [field-box id="sky_path_trace" role="properties"]
{"id" "sky_path_trace" "index" 1 "name" "path traced under the sky" "parents" ["root" "agent_graphics_context"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "agent_graphics_context"} "name" "path traced under the sky" "_tombstone" false "frame" #afield/rect [380.0 180.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sky_path_trace_code_.kt" "lines" 41 "characters" 1733 "sha256" "e4c907a3e02667df1448a3afd483e4982d5c4e786386945d2938f2af1f5129bb"}]}
```

```kotlin [field-property box="sky_path_trace" name="code" path="sky_path_trace_code_.kt"]
import afield.graphics.scene.fabricated.*
import afield.graphics.scene.fabricated.PathTraceEnvironment
import afield.graphics.vulkan.util.Camera

// Launch this instead of the fabricate box: both draw to the renderer's window.
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

val environment = PathTraceEnvironment.fromAtmosphere(sunElevation = 4.0, sunAzimuth = -150.0)
tracer = PathTracingRenderer(
    renderer,
    scene,
    environment,
    width = 1280,
    height = 720,
    camera = camera,
).install()
tracer.maxBounces = 5
tracer.indirectClamp = 10f // the sun seen through a mirror (a caustic) cannot be light-sampled
tracer.exposure = 1.5f
println("${tracer.backend}: ${tracer.triangleCount} triangles")
tracer.blitToScreen()
frames {
    while (tracer.samples < 1024) frame()
    println("atmosphere path trace converged: ${tracer.samples} samples")
}
```

## 3. sky environment

<!-- field-box id="sky_environment_scene" index="2" -->

```edn [field-box id="sky_environment_scene" role="properties"]
{"id" "sky_environment_scene" "index" 2 "name" "sky environment" "parents" ["root" "agent_graphics_context"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "agent_graphics_context"} "name" "sky environment" "_tombstone" false "frame" #afield/rect [40.0 180.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sky_environment_scene_code_.kt" "lines" 36 "characters" 1548 "sha256" "7e2d9ecb63a4a5b2e041d44cae6a172731ef8f4f2710bbe14434e58efa31ad23"}]}
```

```kotlin [field-property box="sky_environment_scene" name="code" path="sky_environment_scene_code_.kt"]
import afield.graphics.scene.fabricated.*
import afield.graphics.sky.SkyEnvironment

val sky = SkyEnvironment(renderer)
val bundle = StandardRenderer.build(
    renderer,
    StandardRenderer.Config(environmentTexture = sky.texture, environmentStrength = 1f),
)
bundle.showEnvironmentBackground(true)
bundle.scene.camera.at.set(0f, 1.2f, -6.5f)
bundle.scene.camera.target.set(0f, 0f, 0f)

fun torusNode(name: String, x: Float, color: Vector4f, metallic: Float, roughness: Float) {
    val material = FabricateMaterial(baseColor = color, metallic = metallic, roughness = roughness)
    val node = bundle.sceneGraph.mesh(name = name, material = material) {
        transform.set(Matrix4f().translate(x, 0f, 0f).rotateX(0.9f))
    }
    val mesh = bundle.registerTestTorus(name = name, slices = 48, stacks = 48, renderer = renderer)
    mesh.bindNode(node)
    mesh.applyFabricateMaterial(material)
}
torusNode("chrome", -2.3f, Vector4f(0.92f, 0.92f, 0.94f, 1f), 1f, 0.05f)
torusNode("white", 0f, Vector4f(0.85f, 0.85f, 0.85f, 1f), 0f, 0.6f)
torusNode("red", 2.3f, Vector4f(0.75f, 0.12f, 0.08f, 1f), 0f, 0.35f)

sky.setSun(elevation = 20.0, azimuth = 140.0, wait = true)
sky.drive(bundle)
bundle.applyScenePlan()
bundle.attachTo(renderer)
bundle.present(renderer = renderer)

slider("Sun elevation (degrees)", min = -10.0, max = 90.0, initialValue = sky.sunElevation) { sky.setSun(it) }
slider("Sun azimuth (degrees)", min = -180.0, max = 180.0, initialValue = sky.sunAzimuth) {
    sky.setSun(sky.sunElevation, it)
}
println("sky environment ready")
```

