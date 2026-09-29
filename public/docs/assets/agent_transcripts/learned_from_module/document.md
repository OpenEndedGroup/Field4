# Field Document: learned_from_module

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. renderer

<!-- field-box id="agent_graphics_context" index="0" -->

```edn [field-box id="agent_graphics_context" role="properties"]
{"id" "agent_graphics_context" "index" 0 "name" "renderer" "parents" ["root"] "children" ["learned_from_module"] "properties" {"children" #{#afield/box "learned_from_module"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_graphics_context_code_.kt" "lines" 16 "characters" 289 "sha256" "48b4c5aeec967fc6ec5dc83b23de5c1e6e911c61acc39c811a8d1eac77f6020a"}]}
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

## 2. learn from this module

<!-- field-box id="learned_from_module" index="1" -->

```edn [field-box id="learned_from_module" role="properties"]
{"id" "learned_from_module" "index" 1 "name" "learn from this module" "parents" ["root" "agent_graphics_context"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "agent_graphics_context"} "name" "learn from this module" "_tombstone" false "frame" #afield/rect [400.0 160.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "learned_from_module_code_.kt" "lines" 74 "characters" 3270 "sha256" "0886f195688e4bf4a9f3a9207eee19c7917346a48e34a5cea7ff5c6972b1d911"} {"name" "vertex" "language" "glsl" "path" "learned_from_module_vertex_.glsl" "lines" 5 "characters" 119 "sha256" "f5f928935456f7b96462d2257491aa9db6746aea0bb37e84df323e8399a26dc9"} {"name" "fragment" "language" "glsl" "path" "learned_from_module_fragment_.glsl" "lines" 16 "characters" 687 "sha256" "a0e56c4a78f4ac918a03ff4647fec40d26360c47d3b31a881190e1305d02d860"}]}
```

```kotlin [field-property box="learned_from_module" name="code" path="learned_from_module_code_.kt"]
import afield.controlGroup
import afield.displayOpen
import afield.four.neural.*
import afield.four.modules.fromContextWithNameOrNull
import org.lwjgl.vulkan.VK13.*
import java.io.File

prop<ShaderLearningView>(lab)
fromContextWithNameOrNull<ShaderLearningView>("lab")?.close()
val source = RenderToTexture(renderer) {
    width=1920; height=1080; useDepth=false
    format=VK_FORMAT_R32G32B32A32_SFLOAT
}
val teacher = moduleHere { source.apply(); depthTest=false; depthWrite=false; blend=false }
val mesh = Mesh(renderer, teacher)
mesh.resize(3,0,teacher)
mesh.push.setMembers(teacher.current.fi.pushConstants.single().members)
teacher += "fullscreen" to mesh
source += "teacher" to teacher
val setTime: (Float)->Unit = { t -> mesh.push["state"]=vec(source.width,source.height,t,0) }
lab = ShaderLearningView(renderer,source,setSourceParameter=setTime)
var capture=false
var requestedView=0
var animate=true
var trainingJob: ShaderTrainingJob?=null
var reported=false
var steps=12000
controlGroup("learn this module") {
    button("Capture shader and train") { if(it)capture=true }
    slider("Training steps",min=1000.0,max=30000.0,step=1000.0,initialValue=12000.0) { steps=it.toInt() }
    slider("0: Source · 1: Learned · 2: Compare",min=0.0,max=2.0,step=1.0,initialValue=0.0) { requestedView=it.toInt() }
    checkbox("Animate parameter",initialValue=true) { animate=it }
}
displayOpen("learn this module")
println("Edit this box's fragment shader, run the box, then Capture shader and train. The source stays visible while training.")
val owner=renderer // Retain ownership even if the parent renderer box stops first.
frames {
    var phase=0.0
    while(true) {
        if(capture) {
            capture=false
            if(trainingJob?.result?.isDone!=false) {
                runCatching {
                    val run=File("build/shader-learning/module-${System.currentTimeMillis()}")
                    val dataset=ShaderTraining.capture(renderer,source,File(run,"dataset"),setParameter=setTime)
                    trainingJob=ShaderTraining.trainAsync(dataset,File(run,"model"),steps)
                    reported=false
                    println("Captured actual GPU RGB. Training log: ${trainingJob!!.log}")
                }.onFailure { println("Capture failed: ${it.message}") }
            }
        }
        val activeJob=trainingJob
        if(activeJob!=null && activeJob.result.isDone && !reported) {
            reported=true
            runCatching {
                val model=activeJob.result.join()
                lab.load(model)
                println("LEARNED MODULE READY: $model — choose view 1 or 2; weights.json records held-out error.")
                File("build/shader-learning/latest-model.txt").writeText(model.absolutePath)
            }.onFailure { println("Keeping the working view: ${it.message}") }
        }
        lab.view=requestedView
        if(animate)phase+=.01
        lab.parameter=kotlin.math.sin(phase).toFloat()
        frame()
    }
}.end {
    trainingJob?.close(); lab.close()
    owner.cleanup+=Runnable {
        teacher.current.cleanup()
        source.imageViewArray.forEach { it.imageView.cleanup() }
        source.colorImageRefs.forEach { it?.cleanup() }; source.defaultSampler?.cleanup()
    }
}
```

```glsl [field-property box="learned_from_module" name="vertex" path="learned_from_module_vertex_.glsl"]
#version 460
void main() {
    vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);
    gl_Position=vec4(p*2-1,0,1);
}
```

```glsl [field-property box="learned_from_module" name="fragment" path="learned_from_module_fragment_.glsl"]
#version 460
layout(push_constant) uniform P { vec4 state; } p;
layout(location=0) out vec4 color;
void main() {
    // This is the teacher. Edit it here; there is no Python version to keep in sync.
    vec2 xy=gl_FragCoord.xy/p.state.xy*2-1;
    float t=p.state.z;
    vec3 rgb=.5+.22*cos(vec3(xy.x*2+t,xy.y*2-t*.7,xy.x+xy.y+t*.5)+vec3(0,2,4));
    for(int k=1;k<=96;k++) {
        float a=float(k)*.61803399;
        rgb+=.028/float(k)*sin(vec3(xy.x*cos(a)+xy.y*sin(a))*3+t+vec3(0,2,4)+a);
    }
    // Two hard edges: a deliberately discontinuous target for the small network.
    float stripe=step(-.12,xy.x)*(1-step(.12,xy.x));
    color=vec4(mix(rgb,vec3(.95,.04,.8),stripe),1);
}
```

