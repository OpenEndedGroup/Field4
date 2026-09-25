# Field Document: direct_lighting

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. renderer

<!-- field-box id="agent_graphics_context" index="0" -->

```edn [field-box id="agent_graphics_context" role="properties"]
{"id" "agent_graphics_context" "index" 0 "name" "renderer" "parents" ["root"] "children" ["direct_light_lab"] "properties" {"children" #{#afield/box "direct_light_lab"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_graphics_context_code_.kt" "lines" 16 "characters" 289 "sha256" "48b4c5aeec967fc6ec5dc83b23de5c1e6e911c61acc39c811a8d1eac77f6020a"}]}
```

```kotlin [field-property box="agent_graphics_context" name="code" path="agent_graphics_context_code_.kt"]
import afield.four.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

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

## 2. direct lighting

<!-- field-box id="direct_light_lab" index="1" -->

```edn [field-box id="direct_light_lab" role="properties"]
{"id" "direct_light_lab" "index" 1 "name" "direct lighting" "parents" ["root" "agent_graphics_context"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "agent_graphics_context"} "name" "direct lighting" "_tombstone" false "frame" #afield/rect [400.0 160.0 340.0 90.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "direct_light_lab_code_.kt" "lines" 85 "characters" 4006 "sha256" "50f734ae28e65243651b7fb24ba6b4083be7c285a1ebfd01011797a990e43746"}]}
```

```kotlin [field-property box="direct_light_lab" name="code" path="direct_light_lab_code_.kt"]
import afield.controlGroup
import afield.displayOpen
import afield.four.lighting.*
import afield.four.fabricated.rtxdiSettings
import afield.stdlib.linalg.Vector3f
import kotlin.math.*
import afield.four.modules.fromContextWithNameOrNull
import afield.four.util.keyCamera

prop<DirectLightingDemo>(lab)
// Re-executing this box replaces the experiment at a frame boundary.
fromContextWithNameOrNull<DirectLightingDemo>("lab")?.close()
lab = DirectLightingDemo(renderer, 1280, 720)
var cameraControl by keyCamera(renderer, lab.bundle.scene.camera)

val restir = lab.bundle.rtxdiSettings()
val sdkReady = lab.visibility.available && runCatching {
    lab.lighting.enableRtxdi(RtxdiProgram.load(), restir).getOrThrow()
}.onFailure { println("Optional RTXDI unavailable: ${it.message}") }.isSuccess
var useRtxdi = sdkReady
var reference = false
var temporal = true
var neighbors = 8
var lightCount = 16
var reset = false
var source = false
var shadows = lab.visibility.available
var animate = true
var budget = 16
var reconstruct = true
var weight = 1f
var power = 60f
var phase = 0.0
var diagnostic = 0
controlGroup("lighting") {
    checkbox("Original renderer", initialValue = source) { source = it }
    checkbox("Ray-query shadows", initialValue = shadows) { shadows = it }
    if (sdkReady) {
        checkbox("RTXDI", initialValue = useRtxdi) { useRtxdi = it }
        checkbox("Reference estimator", initialValue = reference) { reference = it }
        checkbox("Reconstruct lighting", initialValue = reconstruct) { reconstruct = it }
        checkbox("Temporal reuse", initialValue = temporal) { temporal = it }
        slider("Spatial neighbors", min = 0.0, max = 16.0, step = 1.0, initialValue = 8.0) { neighbors = it.toInt() }
        slider("Area lights", min = 1.0, max = 128.0, step = 1.0, initialValue = 16.0) { lightCount = it.toInt() }
        button("Reset history") { if (it) reset = true }
    }
    checkbox("Animate light", initialValue = animate) { animate = it }
    slider("Samples", min = 1.0, max = 128.0, step = 1.0, initialValue = budget.toDouble()) { budget = it.toInt() }
    slider("Effect weight", initialValue = 1.0) { weight = it.toFloat() }
    slider("Radiance", min = 0.0, max = 150.0, initialValue = 60.0) { power = it.toFloat() }
    slider("Light phase", min = 0.0, max = 6.283, initialValue = 0.0) { phase = it }
    slider("View (0: lit, 9: raw)", min = 0.0, max = if (sdkReady) 9.0 else 4.0, step = 1.0, initialValue = 0.0) { diagnostic = it.toInt() }
}
displayOpen("lighting")
println("Direct lighting ready: use the lighting controls and arrow-key camera.")
// Controls publish values; Vulkan changes happen here, between frames.
frames {
    var seconds = 0.0
    while (true) {
        restir.denoisePasses = if (reconstruct) 3 else 0
        restir.temporal = temporal
        restir.spatialSamples = neighbors
        restir.shadows = shadows
        if (reset) { restir.resetHistory(); reset = false }
        if (sdkReady) restir.commitLights(List(lightCount) { i ->
            val a = i * 2.0 * PI / lightCount + seconds + phase
            IdentifiedAreaLight(100 + i, AreaEmitter(
                Vector3f((cos(a)*3.0 + sin(seconds + phase)*1.5).toFloat(), 4.5f, (sin(a)*2.0).toFloat()),
                Vector3f(0.6f,0f,0f), Vector3f(0f,0f,0.6f), Vector3f(power*3f/lightCount)))
        })
        lab.lighting.mode = if (source) DirectLighting.Mode.SOURCE
            else if (useRtxdi && sdkReady) {
                if (reference) DirectLighting.Mode.RTXDI_REFERENCE else DirectLighting.Mode.RTXDI
            }
            else if (shadows && lab.visibility.available) DirectLighting.Mode.RAY_QUERY
            else DirectLighting.Mode.UNSHADOWED
        lab.lighting.view = DirectLighting.View.entries[diagnostic]
        lab.lighting.samples = budget
        lab.lighting.effectWeight = weight
        lab.lighting.emitter.radiance.set(power)
        lab.animate(seconds + phase)
        if (animate) seconds += 1.0 / 60.0
        frame()
    }
}
```

