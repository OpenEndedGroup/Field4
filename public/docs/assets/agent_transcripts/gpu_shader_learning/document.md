# Field Document: gpu_shader_learning

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. renderer

<!-- field-box id="agent_graphics_context" index="0" -->

```edn [field-box id="agent_graphics_context" role="properties"]
{"id" "agent_graphics_context" "index" 0 "name" "renderer" "parents" ["root"] "children" ["gpu_shader_learning"] "properties" {"children" #{#afield/box "gpu_shader_learning"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_graphics_context_code_.kt" "lines" 17 "characters" 321 "sha256" "79dab431bdab018f5a13ebcb55d1eaf22c276161949fb54c70dbe0d69aa149fa"}]}
```

```kotlin [field-property box="agent_graphics_context" name="code" path="agent_graphics_context_code_.kt"]
import afield.four.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableRayTracing = true
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

## 2. gpu shader learning

<!-- field-box id="gpu_shader_learning" index="1" -->

```edn [field-box id="gpu_shader_learning" role="properties"]
{"id" "gpu_shader_learning" "index" 1 "name" "gpu shader learning" "parents" ["root" "agent_graphics_context"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "agent_graphics_context"} "name" "gpu shader learning" "_tombstone" false "frame" #afield/rect [400.0 160.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "gpu_shader_learning_code_.kt" "lines" 63 "characters" 2918 "sha256" "4c061ddc05366c00beba68d05fd5e0c43cce1474452175b1008945403f45ac3b"} {"name" "vertex" "language" "glsl" "path" "gpu_shader_learning_vertex_.glsl" "lines" 5 "characters" 119 "sha256" "f5f928935456f7b96462d2257491aa9db6746aea0bb37e84df323e8399a26dc9"} {"name" "fragment" "language" "glsl" "path" "gpu_shader_learning_fragment_.glsl" "lines" 16 "characters" 687 "sha256" "a0e56c4a78f4ac918a03ff4647fec40d26360c47d3b31a881190e1305d02d860"}]}
```

```kotlin [field-property box="gpu_shader_learning" name="code" path="gpu_shader_learning_code_.kt"]
import afield.controlGroup
import afield.displayOpen
import afield.four.neural.*
import afield.four.modules.fromContextWithNameOrNull
import org.lwjgl.vulkan.VK13.*
import java.io.File

prop<GpuShaderLearningView>(lab)
fromContextWithNameOrNull<GpuShaderLearningView>("lab")?.close()
check(renderer.device.supportsCooperativeVectorTraining) {
    "GPU learning needs enabled cooperative-vector training. learned_from_module provides offline training."
}
val source=RenderToTexture(renderer) {
    width=1920;height=1080;useDepth=false;format=VK_FORMAT_R32G32B32A32_SFLOAT
}
val teacher=moduleHere { source.apply();depthTest=false;depthWrite=false;blend=false }
val mesh=Mesh(renderer,teacher)
mesh.resize(3,0,teacher)
mesh.push.setMembers(teacher.current.fi.pushConstants.single().members)
teacher += "fullscreen" to mesh
source += "teacher" to teacher
lab=GpuShaderLearningView(renderer,source) { t -> mesh.push["state"]=vec(source.width,source.height,t,0) }.install()
var learn=true
var view=2
var reset=false
var save=false
var iterations=8
controlGroup("GPU shader learning") {
    checkbox("Learn on GPU",initialValue=true) { learn=it }
    slider("Updates per frame",min=1.0,max=32.0,step=1.0,initialValue=8.0) { iterations=it.toInt() }
    slider("0: Source · 1: Learned · 2: Compare",min=0.0,max=2.0,step=1.0,initialValue=2.0) { view=it.toInt() }
    button("Reset model and optimizer") { if(it)reset=true }
    button("Save frozen weights") { if(it)save=true }
}
displayOpen("GPU shader learning")
println("Edit fragment; training samples actual linear RGB. Freeze learning and choose Learned to stop the teacher pass. Compare is source / learned / error × 8.")
val owner=renderer
frames {
    var phase=0
    var sourceHash=ShaderTraining.sourceDigest(source)
    while(true) {
        val hash=ShaderTraining.sourceDigest(source)
        if(hash!=sourceHash) { sourceHash=hash;reset=true;println("Teacher changed; restarting the fit.") }
        if(reset){lab.model.reset();reset=false}
        lab.learning=learn;lab.iterationsPerFrame=iterations;lab.view=view
        lab.parameter=if(learn)(phase%13)/6f-1 else kotlin.math.sin(phase*.018f)
        if(save) {
            save=false
            runCatching {
                val directory=File("build/shader-learning/gpu-${System.currentTimeMillis()}")
                lab.model.save(directory,sourceHash,"Fullscreen module; camera, textures and other controls fixed; normalized time varies")
                println("FROZEN MODEL SAVED: $directory — load weights.f16 and weights.json with LearnedShader.")
            }.onFailure { println("Save failed: ${it.message}") }
        }
        phase++;frame()
    }
}.end {
    lab.close()
    owner.cleanup+=Runnable {
        teacher.current.cleanup();source.imageViewArray.forEach { it.imageView.cleanup() }
        source.colorImageRefs.forEach { it?.cleanup() };source.defaultSampler?.cleanup()
    }
}
```

```glsl [field-property box="gpu_shader_learning" name="vertex" path="gpu_shader_learning_vertex_.glsl"]
#version 460
void main() {
    vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);
    gl_Position=vec4(p*2-1,0,1);
}
```

```glsl [field-property box="gpu_shader_learning" name="fragment" path="gpu_shader_learning_fragment_.glsl"]
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

