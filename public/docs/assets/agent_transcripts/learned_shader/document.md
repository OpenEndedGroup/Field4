# Field Document: learned_shader

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. renderer

<!-- field-box id="agent_graphics_context" index="0" -->

```edn [field-box id="agent_graphics_context" role="properties"]
{"id" "agent_graphics_context" "index" 0 "name" "renderer" "parents" ["root"] "children" ["learned_shader" "learned_material"] "properties" {"children" #{#afield/box "learned_shader" #afield/box "learned_material"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_graphics_context_code_.kt" "lines" 16 "characters" 289 "sha256" "48b4c5aeec967fc6ec5dc83b23de5c1e6e911c61acc39c811a8d1eac77f6020a"}]}
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

## 3. editable learned material

<!-- field-box id="learned_material" index="2" -->

```edn [field-box id="learned_material" role="properties"]
{"id" "learned_material" "index" 2 "name" "editable learned material" "parents" ["root" "agent_graphics_context"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "agent_graphics_context"} "name" "editable learned material" "_tombstone" false "frame" #afield/rect [400.0 340.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "learned_material_code_.kt" "lines" 35 "characters" 1477 "sha256" "7e9f164605c934e4607f6329cfb69cece416f1daabb7d4b055b637f7bb852f83"} {"name" "vertex" "language" "glsl" "path" "learned_material_vertex_.glsl" "lines" 7 "characters" 175 "sha256" "1e43f7ca1494c90d2072d80eb62e4118246e249203b45177e5d04315758f8538"} {"name" "fragment" "language" "glsl" "path" "learned_material_fragment_.glsl" "lines" 12 "characters" 437 "sha256" "bc8a710aa843e8bb6e8b66e697d8ed3e2fe8680ac3af0677f7f5c2804bf3a0c9"}]}
```

```kotlin [field-property box="learned_material" name="code" path="learned_material_code_.kt"]
import afield.four.neural.LearnedShader
import afield.four.modules.fromContextWithNameOrNull

// A separate scene: stop the original learned-shader box before launching this one.
// Edit this box's fragment property while it runs; the network stays allocated.
prop<AutoCloseable>(materialSession)
fromContextWithNameOrNull<AutoCloseable>("materialSession")?.close()
val owner=renderer
val passName="editable learned material"
val learned=LearnedShader(owner,name=passName,attach=false)
learned.view=1
learned.setParameter(.25f)
prop<RenderToTexture>(learnedImage)=learned.output
val material=moduleHere { depthTest=false; depthWrite=false; blend=false }
val triangle=Mesh(owner,material)
triangle.resize(3,0,material)
material += "fullscreen" to triangle

// Compute and resolve run before this ordinary fragment shader samples their RTT.
owner.renderList.preTargets[passName]=learned
owner.renderList.modules[passName]=material
var closed=false
materialSession=AutoCloseable {
    if(!closed) {
        closed=true
        owner.renderList.modules.remove(passName,material)
        if(!owner.isClosed) {
            learned.close()
            owner.cleanup+=Runnable { material.current.cleanup() }
        }
    }
}
val ownedSession=materialSession
println("Edit fragment: the learnedImage texture comes from the unchanged network. Stop the original comparison box to avoid rendering it too.")
frames { while(!closed && !owner.isClosed)frame() }.end { ownedSession.close() }
```

```glsl [field-property box="learned_material" name="vertex" path="learned_material_vertex_.glsl"]
#version 460
layout(location=0) out vec2 uv;
void main() {
    vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);
    uv=vec2(p.x,1-p.y);
    gl_Position=vec4(p*2-1,0,1);
}
```

```glsl [field-property box="learned_material" name="fragment" path="learned_material_fragment_.glsl"]
#version 460
layout(set=0,binding=0) uniform sampler2D learnedImage;
layout(location=0) in vec2 uv;
layout(location=0) out vec4 color;
void main() {
    // The left half is the model's RGB; the right is an editable treatment.
    vec2 st=vec2(fract(uv.x*2),uv.y);
    vec3 base=texture(learnedImage,st).rgb;
    vec3 treatment=mix(base,base.gbr,.65);
    treatment*=.85+.15*sin(st.y*160);
    color=vec4(uv.x<.5 ? base : treatment,1);
}
```

