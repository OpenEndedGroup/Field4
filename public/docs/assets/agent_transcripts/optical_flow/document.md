# Field Document: optical_flow

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. renderer

<!-- field-box id="agent_graphics_context" index="0" -->

```edn [field-box id="agent_graphics_context" role="properties"]
{"id" "agent_graphics_context" "index" 0 "name" "renderer" "parents" ["root"] "children" ["optical_flow"] "properties" {"children" #{#afield/box "optical_flow"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_graphics_context_code_.kt" "lines" 16 "characters" 289 "sha256" "48b4c5aeec967fc6ec5dc83b23de5c1e6e911c61acc39c811a8d1eac77f6020a"}]}
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

## 2. optical flow

<!-- field-box id="optical_flow" index="1" -->

```edn [field-box id="optical_flow" role="properties"]
{"id" "optical_flow" "index" 1 "name" "optical flow" "parents" ["root" "agent_graphics_context"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "agent_graphics_context"} "name" "optical flow" "_tombstone" false "frame" #afield/rect [400.0 160.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "optical_flow_code_.kt" "lines" 31 "characters" 1401 "sha256" "6b82ca8eeba837f3c2c4f63ba1baca45dc7a6f5d30131e9708ee41cd77050d66"}]}
```

```kotlin [field-property box="optical_flow" name="code" path="optical_flow_code_.kt"]
import afield.controlGroup
import afield.displayOpen
import afield.four.vision.*
import afield.four.modules.fromContextWithNameOrNull

prop<OpticalFlowDemo>(lab)
fromContextWithNameOrNull<OpticalFlowDemo>("lab")?.close()
check(renderer.device.supportsOpticalFlow) { "This device has no enabled VK_NV_optical_flow; other Field rendering is unaffected." }
lab=OpticalFlowDemo(renderer)
var gain=1f
var damping=.98f
var cost=80f
var deform=true
var reset=false
controlGroup("optical flow") {
    slider("Advection gain",min=0.0,max=3.0,initialValue=1.0) { gain=it.toFloat() }
    slider("Feedback retention",min=0.0,max=.995,initialValue=.98) { damping=it.toFloat() }
    slider("Reject cost above",min=0.0,max=600.0,initialValue=80.0) { cost=it.toFloat() }
    checkbox("Consecutive-frame wiggle",initialValue=true) { deform=it;reset=true }
    button("Reset feedback and motion hints") { if(it)reset=true }
}
displayOpen("optical flow")
println("Top: consecutive frames. Below: motion/cost and advected feedback. Turn off wiggle for the (+12, -8) translation check.")
frames {
    var time=0f
    while(true) {
        lab.dx=if(deform)0f else 12f;lab.dy=if(deform)0f else -8f;lab.displayRange=if(deform)3f else 20f;lab.gain=gain;lab.damping=damping;lab.costThreshold=cost;lab.deformation=deform
        lab.update(time,reset);reset=false;time+=lab.pairStep
        frame()
    }
}.end { lab.close() }
```

