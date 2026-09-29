# Field Document: neural_reconstruction

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. renderer

<!-- field-box id="agent_graphics_context" index="0" -->

```edn [field-box id="agent_graphics_context" role="properties"]
{"id" "agent_graphics_context" "index" 0 "name" "renderer" "parents" ["root"] "children" ["neural_reconstruction"] "properties" {"children" #{#afield/box "neural_reconstruction"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_graphics_context_code_.kt" "lines" 16 "characters" 289 "sha256" "48b4c5aeec967fc6ec5dc83b23de5c1e6e911c61acc39c811a8d1eac77f6020a"}]}
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

## 2. neural reconstruction

<!-- field-box id="neural_reconstruction" index="1" -->

```edn [field-box id="neural_reconstruction" role="properties"]
{"id" "neural_reconstruction" "index" 1 "name" "neural reconstruction" "parents" ["root" "agent_graphics_context"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "agent_graphics_context"} "name" "neural reconstruction" "_tombstone" false "frame" #afield/rect [400.0 160.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "neural_reconstruction_code_.kt" "lines" 61 "characters" 3009 "sha256" "4e6f2af54ff830ded2ffb901b04acf1d71a25504c93df6103e62f857e81b9960"} {"name" "vertex" "language" "glsl" "path" "neural_reconstruction_vertex_.glsl" "lines" 41 "characters" 2372 "sha256" "6a509b1e0537b8c5c5db23c515e5a0bd7fa0676ed78e2c7b33ce2210efd66a38"} {"name" "fragment" "language" "glsl" "path" "neural_reconstruction_fragment_.glsl" "lines" 31 "characters" 1604 "sha256" "f4c71150e7f5dd16c2bb5e576fd5862c4f5df297eb59e14bba54c58f179b5399"}]}
```

```kotlin [field-property box="neural_reconstruction" name="code" path="neural_reconstruction_code_.kt"]
import afield.controlGroup
import afield.displayOpen
import afield.four.reconstruction.*
import afield.four.modules.fromContextWithNameOrNull

prop<ReconstructionDemo>(lab)
fromContextWithNameOrNull<ReconstructionDemo>("lab")?.close()
val available = NgxSupport.availability(renderer.device)
available.onFailure { println("Neural reconstruction unavailable: ${it.message}; showing the source.") }
val modes = listOf(null, ReconstructionMode.DLAA, ReconstructionMode.QUALITY, ReconstructionMode.PERFORMANCE,
    ReconstructionMode.ULTRA_PERFORMANCE)
val smallest = if(available.isSuccess) DlssReconstruction.recommend(renderer,ReconstructionSize(1920,1080),
    ReconstructionMode.ULTRA_PERFORMANCE).smallestFor(ReconstructionSize(1920,1080)) else null
var requested = if (available.isSuccess) 1 else 0
var active = requested
var abstractScene = false
var animate = true
var camera = true
var cut = false
var reload = false
lab = ReconstructionDemo(renderer, modes[active])
controlGroup("reconstruction") {
    if (available.isSuccess) {
        slider("0: Source · 1: DLAA · 2: Quality · 3: Performance · 4: Ultra", min=0.0, max=4.0, step=1.0, initialValue=1.0) { requested=it.toInt() }
    }
    checkbox("Abstract colors and particles", initialValue=false) { abstractScene=it }
    checkbox("Animate", initialValue=true) { animate=it }
    checkbox("Move camera", initialValue=true) { camera=it }
    button("Camera cut / reset") { if(it)cut=true }
    button("Reload edited shaders") { if(it)reload=true }
}
displayOpen("reconstruction")
if(smallest!=null)println("Ultra minimum input: ${smallest.width} × ${smallest.height}. Mode 4 uses the SDK recommendation.")
println("1920 × 1080 output. Edit this box's vertex / fragment shaders, then reload.")
frames {
    var previousAbstract = abstractScene
    var previousCamera = camera
    while(true) {
        if(requested!=active) {
            runCatching {
                val next = ReconstructionDemo(renderer, modes[requested], attach=false)
                next.time=lab.time
                lab.close()
                lab=next.attach()
                active=requested
                println("${modes[active] ?: "Source"}: ${lab.inputSize.width} × ${lab.inputSize.height} → 1920 × 1080")
            }.onFailure { println("Keeping current view: ${it.message}");requested=active }
        }
        if(previousAbstract!=abstractScene || previousCamera!=camera)lab.reset("scene controls")
        previousAbstract=abstractScene;previousCamera=camera
        lab.abstractScene=abstractScene;lab.animate=animate;lab.cameraMotion=camera
        if(cut) { lab.time+=4f;lab.reset("camera cut");cut=false }
        if(reload) {
            val vertexSource = thisBox.getPropertyRaw("vertex") as String
            val fragmentSource = thisBox.getPropertyRaw("fragment") as String
            lab.reload(vertexSource,fragmentSource).onFailure { println("Keeping working shaders: ${it.message}") }
            reload=false
        }
        frame()
    }
}
```

```glsl [field-property box="neural_reconstruction" name="vertex" path="neural_reconstruction_vertex_.glsl"]
#version 460
layout(push_constant) uniform Scene { vec4 sizeJitter; vec4 times; vec4 settings; } pc;
layout(location=0) out vec3 world;
layout(location=1) out vec3 normal;
layout(location=2) out vec4 nowClip;
layout(location=3) out vec4 oldClip;
layout(location=4) flat out int objectId;
const int indices[36]=int[](0,2,1,1,2,3,4,5,6,5,7,6,0,1,4,1,5,4,2,6,3,3,6,7,0,4,2,2,4,6,1,3,5,3,7,5);
vec3 corner(int i){return vec3((i&1)==0?-1:1,(i&2)==0?-1:1,(i&4)==0?-1:1);}
vec3 objectPoint(int id,vec3 p,float time){
    if(id>=39){float k=float(id-39);return p*(pc.settings.x==1?.035:0)+vec3(sin(k*2.4+time)*3,1.4+sin(k*.8+time*.7),cos(k*1.7+time)*2);}
    if(id==0)return p*vec3(5,.06,4)+vec3(0,-.08,0);
    if(id==1)return p*vec3(.85,.7,.85)+vec3(-1.5,.7,-.5);
    if(id==2)return p*vec3(.7,.9,.7)+vec3(1.4+sin(time)*.6,.9,-.4);
    int n=id-3;
    if(n<18){p*=vec3(.015,1.2,.015);p.xy=mat2(.8,.6,-.6,.8)*p.xy;return p+vec3(float(n)*.19-1.7,1.7,1.4);}
    p*=vec3(.015,1.2,.015);p.xy=mat2(.8,-.6,.6,.8)*p.xy;return p+vec3(float(n-18)*.19-1.7,1.7,1.45);
}
vec4 project(vec3 p,float t){
    float a=pc.settings.y==1?t*.18:0;
    vec3 eye=vec3(5*sin(a)+5*cos(a),3.7,5*cos(a)-5*sin(a));
    vec3 f=normalize(vec3(0,.8,0)-eye),r=normalize(cross(f,vec3(0,1,0))),u=cross(r,f);
    vec3 q=p-eye;float z=dot(q,f);float near=.1,far=100;
    return vec4(dot(q,r)*1.9*pc.sizeJitter.y/pc.sizeJitter.x,dot(q,u)*1.9,far/(far-near)*z-far*near/(far-near),z);
}
void main(){
    if(gl_VertexIndex<3){
        vec2 xy=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2)*2-1;
        gl_Position=vec4(xy,.999999,1);objectId=-1;world=vec3(0);normal=vec3(0,1,0);nowClip=oldClip=gl_Position;return;
    }
    objectId=(gl_VertexIndex-3)/36;int v=(gl_VertexIndex-3)%36;
    float t=pc.times.x,old=pc.times.y;
    vec3 p=corner(indices[v]);world=objectPoint(objectId,p,t);
    int tri=(v/3)*3;vec3 a=objectPoint(objectId,corner(indices[tri]),t),b=objectPoint(objectId,corner(indices[tri+1]),t),c=objectPoint(objectId,corner(indices[tri+2]),t);
    normal=normalize(cross(b-a,c-a));
    nowClip=project(world,t);oldClip=project(objectPoint(objectId,p,old),old);
    nowClip.x+=2*pc.times.z/pc.sizeJitter.x*nowClip.w;
    oldClip.x+=2*pc.times.w/pc.sizeJitter.x*oldClip.w;
    gl_Position=nowClip;
    gl_Position.xy+=vec2(2*pc.sizeJitter.z/pc.sizeJitter.x,-2*pc.sizeJitter.w/pc.sizeJitter.y)*gl_Position.w;
}
```

```glsl [field-property box="neural_reconstruction" name="fragment" path="neural_reconstruction_fragment_.glsl"]
#version 460
layout(push_constant) uniform Scene { vec4 sizeJitter; vec4 times; vec4 settings; } pc;
layout(location=0) in vec3 world;
layout(location=1) in vec3 normal;
layout(location=2) in vec4 nowClip;
layout(location=3) in vec4 oldClip;
layout(location=4) flat in int objectId;
layout(location=0) out vec4 color;
layout(location=1) out float depth;
layout(location=2) out vec2 motion;
void main(){
    if(objectId<0){color=vec4(.014,.022,.04,1);depth=1;motion=vec2(0);return;}
    vec3 n=normalize(normal);
    vec3 base=objectId==0?vec3(.23):objectId==1?vec3(.05,.27,.65):objectId==2?vec3(.8,.14,.045):vec3(.9,.68,.21);
    if(objectId<3){vec2 st=world.xz*9;float checkers=mod(floor(st.x)+floor(st.y),2);base*=mix(.22,1,checkers);}
    vec3 light=normalize(vec3(sin(pc.times.x)*4,5,3)-world);
    float diffuse=.2+.8*abs(dot(n,light));
    float specular=pow(max(0,abs(dot(n,normalize(light+normalize(vec3(5,3.7,5)-world))))),96);
    color=vec4(base*diffuse+specular*1.8,1);
    if(pc.settings.x==1){
        color.rgb=objectId==0?vec3(.04,.08,.16):base;
        // Deliberately changing appearance has no geometric correspondence. It is visible in
        // comparisons, but not represented as fictional velocity or an undocumented vendor mask.
        if(objectId==1)color.rgb=.45+.4*cos(world*8+pc.times.x*vec3(2,3,5));
        if(objectId>=3)color.rgb=mix(vec3(.1,.8,.6),vec3(.9,.08,.3),step(0,sin(pc.times.x*1.7)));
    }
    vec2 uv=nowClip.xy/nowClip.w*vec2(.5,-.5)+.5;
    vec2 prev=oldClip.xy/oldClip.w*vec2(.5,-.5)+.5;
    motion=(prev-uv)*pc.sizeJitter.xy;
    depth=gl_FragCoord.z;
}
```

