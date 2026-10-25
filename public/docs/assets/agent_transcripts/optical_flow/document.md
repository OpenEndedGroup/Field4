# Field Document: optical_flow

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="agent_graphics_context" index="0" -->

```edn [field-box id="agent_graphics_context" role="properties"]
{"id" "agent_graphics_context" "index" 0 "name" "renderer" "parents" ["root"] "children" ["optical_flow"] "properties" {"children" #{#afield/box "optical_flow"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_graphics_context_code_.kt" "lines" 16 "characters" 299 "sha256" "db2fbe76f4bfbd4a9afe212fe7ab2ca48a7e6d35fea5cf93ca547a065409ecb3"}]}
```

```kotlin [field-property box="agent_graphics_context" name="code" path="agent_graphics_context_code_.kt"]
import afield.graphics.vulkan.util.Options

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

## 2. optical flow

<!-- field-box id="optical_flow" index="1" -->

```edn [field-box id="optical_flow" role="properties"]
{"id" "optical_flow" "index" 1 "name" "optical flow" "parents" ["root" "agent_graphics_context"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "agent_graphics_context"} "name" "optical flow" "_tombstone" false "frame" #afield/rect [400.0 160.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "optical_flow_code_.kt" "lines" 53 "characters" 1918 "sha256" "0aedc1dca9c02f813d5e0251fbea5efb9a1dc1b29ee3e4de9cfb1555ffcdcfd6"}]}
```

```kotlin [field-property box="optical_flow" name="code" path="optical_flow_code_.kt"]
import afield.media.vision.*

prop<OpticalFlowDemo>(lab)
val runScope = requireNotNull(RunScope.current()) { "Run this example in a Field box." }
// AUTO: the VK_NV_optical_flow engine where the device has one, portable pyramidal Lucas–Kanade everywhere else.
val ownedLab = runScope.own(OpticalFlowDemo(renderer, backend = OpticalFlowBackend.AUTO))
lab = ownedLab
println("Optical flow backend: ${ownedLab.flow.backend}")
var gain = 1f
var damping = .98f
// The two backends report cost on different scales, so the rejection threshold starts at the demo's per-backend
// default.
var cost = ownedLab.costThreshold
var deform = true
var reset = false
controlGroup("optical flow") {
    slider("Advection gain", min = 0.0, max = 3.0, initialValue = 1.0) { gain = it.toFloat() }
    slider("Feedback retention", min = 0.0, max = .995, initialValue = .98) { damping = it.toFloat() }
    slider(
        "Reject cost above",
        min = 0.0,
        max = 6.0 * ownedLab.costDisplayRange,
        initialValue = ownedLab.costThreshold.toDouble(),
    ) {
        cost = it.toFloat()
    }
    checkbox("Consecutive-frame wiggle", initialValue = true) {
        deform = it
        reset = true
    }
    button("Reset feedback and motion hints") { if (it) reset = true }
}
displayOpen("optical flow")
println(
    "Top: consecutive frames. Below: motion/cost and advected feedback. Turn off wiggle for the (+12, -8) translation check.",
)
frames {
    var time = 0f
    while (true) {
        ownedLab.dx = if (deform) 0f else 12f
        ownedLab.dy = if (deform) 0f else -8f
        ownedLab.displayRange = if (deform) 3f else 20f
        ownedLab.gain = gain
        ownedLab.damping = damping
        ownedLab.costThreshold = cost
        ownedLab.deformation = deform
        ownedLab.update(time, reset)
        reset = false
        time += ownedLab.pairStep
        frame()
    }
}
    .end { ownedLab.close() }
```

