# Field Document: path_tracing_subsurface

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "watchdog" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. path tracing renderer

<!-- field-box id="pt_sss_renderer" index="0" -->

```edn [field-box id="pt_sss_renderer" role="properties"]
{"id" "pt_sss_renderer" "index" 0 "name" "path tracing renderer" "parents" ["root"] "children" ["pt_sss_row"] "properties" {"children" #{#afield/box "pt_sss_row"} "parents" #{#afield/box "root"} "name" "path tracing renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 320.0 110.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pt_sss_renderer_code_.kt" "lines" 10 "characters" 229 "sha256" "307a3935a2282a21514e8871e26093deb4549a44004b34d3a6229e9c736e9d11"}]}
```

```kotlin [field-property box="pt_sss_renderer" name="code" path="pt_sss_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field · subsurface scattering")
    Renderer(window)
}
start(renderer::run)
```

## 2. subsurface remaps

<!-- field-box id="pt_sss_row" index="1" -->

```edn [field-box id="pt_sss_row" role="properties"]
{"id" "pt_sss_row" "index" 1 "name" "subsurface remaps" "parents" ["root" "pt_sss_renderer"] "children" ["pt_sss_controls"] "properties" {"children" #{#afield/box "pt_sss_controls"} "parents" #{#afield/box "root" #afield/box "pt_sss_renderer"} "name" "subsurface remaps" "_tombstone" false "frame" #afield/rect [320.0 180.0 380.0 130.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pt_sss_row_code_.kt" "lines" 49 "characters" 1839 "sha256" "eda8efe2b14e1b4146d690fb1c02caf23fe3ed21bd27bdc66927dd1fb716a7a6"}]}
```

```kotlin [field-property box="pt_sss_row" name="code" path="pt_sss_row_code_.kt"]
import afield.graphics.scene.fabricated.*
import afield.graphics.vulkan.util.Camera

prop<PathTracingRenderer>(tracer)
prop<Vector3f>(skin)
prop<Vector3f>(radius)
prop<List<Int>>(remapped)

// Linear-light skin colour: what the surface should look like after all the scattering.
skin = Vector3f(0.75f, 0.45f, 0.33f)
// How far each channel travels under the surface, in world units; red goes furthest.
radius = Vector3f(0.36f, 0.14f, 0.08f)

val scene = PathTraceScene()
val floor = scene.material(PathTraceMaterial.diffuse(0.18f, 0.18f, 0.18f))
scene.quad(Vector3f(-20f, 0f, -20f), Vector3f(-20f, 0f, 20f), Vector3f(20f, 0f, 20f), Vector3f(20f, 0f, -20f), floor)
val target = scene.material(PathTraceMaterial(skin.x, skin.y, skin.z, roughness = 0.3f))
scene.sphere(Vector3f(-4.4f, 0.95f, 0f), 0.95f, target, 64, 32)
remapped =
    AlbedoRemap.entries.mapIndexed { i, remap ->
        val m = scene.material(PathTraceMaterial.subsurface(skin, radius, remap))
        scene.sphere(Vector3f(-2.2f + 2.2f * i, 0.95f, 0f), 0.95f, m, 64, 32)
        m
    }
val camera = Camera(renderer).also {
    it.at.set(0f, 2.4f, 10.5f)
    it.target.set(0f, 0.9f, 0f)
    it.p.fov = 0.6
}
tracer = PathTracingRenderer(
    renderer,
    scene,
    PathTraceEnvironment.fromFile("public/docs/assets/fabricate_demo/venice_sunset_1k.exr"),
    width = 1280,
    height = 400,
    camera = camera,
    name = "subsurface-remaps",
).install()
tracer.maxBounces = 10
tracer.samplesPerFrame = 2
tracer.exposure = 1.4f
// Long red walks occasionally return with a large weight; clamp them as specks, not energy.
tracer.indirectClamp = 1f
tracer.blitToScreen()
println("left to right: diffuse target, ${AlbedoRemap.entries.joinToString()}")
frames {
    while (tracer.samples < 1024) frame()
    println("subsurface study converged: ${tracer.samples} samples")
}
```

## 3. subsurface controls

<!-- field-box id="pt_sss_controls" index="2" -->

```edn [field-box id="pt_sss_controls" role="properties"]
{"id" "pt_sss_controls" "index" 2 "name" "subsurface controls" "parents" ["root" "pt_sss_row"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "pt_sss_row"} "name" "subsurface controls" "_tombstone" false "frame" #afield/rect [320.0 340.0 380.0 130.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pt_sss_controls_code_.kt" "lines" 23 "characters" 859 "sha256" "c2cf2a46b92b4fcc739231b0e8b1029bbe439bcc4d8df63d5ad0cf7cd2e850cd"}]}
```

```kotlin [field-property box="pt_sss_controls" name="code" path="pt_sss_controls_code_.kt"]
import afield.graphics.scene.fabricated.*

var scale = 1.0
var choice = AlbedoRemap.REFERENCE
// Rewrite the four remapped spheres in place; the scene and its BVH stay as they are.
fun apply() {
    val r = Vector3f(radius).mul(scale.toFloat())
    AlbedoRemap.entries.forEachIndexed { i, remap ->
        val use = if (i == AlbedoRemap.entries.lastIndex) choice else remap
        tracer.setMaterial(remapped[i], PathTraceMaterial.subsurface(skin, r, use))
    }
    println("radius x $scale, right-hand sphere through $choice")
}
controlGroup("subsurface") {
    slider("radius", min = 0.1, max = 4.0, step = 0.05, initialValue = 1.0, law = ControlLaw.LOGARITHMIC) {
        scale = it
        apply()
    }
    slider("remap", min = 0.0, max = 3.0, step = 1.0, initialValue = 3.0) {
        choice = AlbedoRemap.entries[it.toInt()]
        apply()
    }
}
```

