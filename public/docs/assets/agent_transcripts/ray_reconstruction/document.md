# Field Document: ray_reconstruction

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. renderer

<!-- field-box id="agent_graphics_context" index="0" -->

```edn [field-box id="agent_graphics_context" role="properties"]
{"id" "agent_graphics_context" "index" 0 "name" "renderer" "parents" ["root"] "children" ["ray_reconstruction"] "properties" {"children" #{#afield/box "ray_reconstruction"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_graphics_context_code_.kt" "lines" 16 "characters" 289 "sha256" "48b4c5aeec967fc6ec5dc83b23de5c1e6e911c61acc39c811a8d1eac77f6020a"}]}
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

## 2. ray reconstruction

<!-- field-box id="ray_reconstruction" index="1" -->

```edn [field-box id="ray_reconstruction" role="properties"]
{"id" "ray_reconstruction" "index" 1 "name" "ray reconstruction" "parents" ["root" "agent_graphics_context"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "agent_graphics_context"} "name" "ray reconstruction" "_tombstone" false "frame" #afield/rect [400.0 160.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ray_reconstruction_code_.kt" "lines" 29 "characters" 1142 "sha256" "175752270f3c54c42370544cf349c8a51f44ae71bd1e893726b8a82a7de09e9f"}]}
```

```kotlin [field-property box="ray_reconstruction" name="code" path="ray_reconstruction_code_.kt"]
import afield.controlGroup
import afield.displayOpen
import afield.four.reconstruction.*
import afield.four.modules.fromContextWithNameOrNull

prop<RayReconstructionDemo>(lab)
fromContextWithNameOrNull<RayReconstructionDemo>("lab")?.close()
NgxSupport.rayReconstructionAvailability(renderer.device).getOrThrow()
lab = RayReconstructionDemo(renderer)
var requested = 3
var animate = true
var camera = true
var cut = false
controlGroup("ray reconstruction") {
    slider("0: Raw · 1: Spatial · 2: Spatial + DLAA · 3: RR", min=0.0,max=3.0,step=1.0,initialValue=3.0) { requested=it.toInt() }
    checkbox("Move light",initialValue=true) { animate=it }
    checkbox("Move camera",initialValue=true) { camera=it }
    button("Cut / reset history") { if(it)cut=true }
}
displayOpen("ray reconstruction")
println("1920 × 1080. RR consumes raw RTXDI. The spatial filter and DLAA are alternative paths.")
frames {
    while(true) {
        lab.path=RayReconstructionDemo.Path.entries[requested]
        lab.animate=animate;lab.cameraMotion=camera
        if(cut){lab.time+=4;lab.reset("live cut");cut=false}
        frame()
    }
}.end { lab.close() }
```

