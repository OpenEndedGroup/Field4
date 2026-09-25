# Field Document: learned_shader

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. renderer

<!-- field-box id="agent_graphics_context" index="0" -->

```edn [field-box id="agent_graphics_context" role="properties"]
{"id" "agent_graphics_context" "index" 0 "name" "renderer" "parents" ["root"] "children" ["learned_shader"] "properties" {"children" #{#afield/box "learned_shader"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_graphics_context_code_.kt" "lines" 16 "characters" 289 "sha256" "48b4c5aeec967fc6ec5dc83b23de5c1e6e911c61acc39c811a8d1eac77f6020a"}]}
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

## 2. learned shader

<!-- field-box id="learned_shader" index="1" -->

```edn [field-box id="learned_shader" role="properties"]
{"id" "learned_shader" "index" 1 "name" "learned shader" "parents" ["root" "agent_graphics_context"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "agent_graphics_context"} "name" "learned shader" "_tombstone" false "frame" #afield/rect [400.0 160.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "learned_shader_code_.kt" "lines" 37 "characters" 1452 "sha256" "c2a2f060c6cc521e4eb804813d85da82a27a811cfcf1c2b4796701056817a7d6"}]}
```

```kotlin [field-property box="learned_shader" name="code" path="learned_shader_code_.kt"]
import afield.controlGroup
import afield.displayOpen
import afield.four.neural.*
import afield.four.modules.fromContextWithNameOrNull

prop<LearnedShader>(lab)
fromContextWithNameOrNull<LearnedShader>("lab")?.close()
var cooperative=renderer.device.supportsCooperativeVector
var active=cooperative
var requestedView=2
var animate=true
var reload=false
lab=LearnedShader(renderer,cooperative=cooperative)
controlGroup("learned shader") {
    checkbox("Cooperative vectors",initialValue=cooperative) { cooperative=it }
    slider("0: Original · 1: Learned · 2: Compare",min=0.0,max=2.0,step=1.0,initialValue=2.0) { requestedView=it.toInt() }
    checkbox("Animate time",initialValue=true) { animate=it }
    button("Reload trained weights") { if(it)reload=true }
}
displayOpen("learned shader")
println("Compare: original / learned / absolute RGB error × 8. Weights describe a 16 → 32 → 32 → 3 network.")
frames {
    var time=0.0
    while(true) {
        if(cooperative!=active || reload) {
            runCatching {
                val next=LearnedShader(renderer,cooperative=cooperative,attach=false)
                lab.close();lab=next.attach();active=cooperative
            }.onFailure { println("Keeping working shader: ${it.message}");cooperative=active }
            reload=false
        }
        lab.view=requestedView
        if(animate)time+=.01
        lab.time=kotlin.math.sin(time).toFloat()
        frame()
    }
}.end { lab.close() }
```

