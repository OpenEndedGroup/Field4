# Field Document: open_dlss_nr_cubes

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. renderer

<!-- field-box id="agent_graphics_context" index="0" -->

```edn [field-box id="agent_graphics_context" role="properties"]
{"id" "agent_graphics_context" "index" 0 "name" "renderer" "parents" ["root"] "children" ["open_dlss_nr"] "properties" {"children" #{#afield/box "open_dlss_nr"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_graphics_context_code_.kt" "lines" 13 "characters" 358 "sha256" "c4e456234ec75530ebbe09f9662c3d9fbcaae2491dbae893c8e015574657ce7b"}]}
```

```kotlin [field-property box="agent_graphics_context" name="code" path="agent_graphics_context_code_.kt"]
import afield.four.util.Options
import afield.Rect
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableVR=false
Options.enableHDR=false
Options.profiling=false
once.renderer {
    window=Window("Field · neural cubes",initialDimensions=Rect(40.0,40.0,2304.0,832.0))
    Renderer(window)
}
println("agent graphics renderer ready")
start(renderer::run)
```

## 2. neural cubes

<!-- field-box id="open_dlss_nr" index="1" -->

```edn [field-box id="open_dlss_nr" role="properties"]
{"id" "open_dlss_nr" "index" 1 "name" "neural cubes" "parents" ["root" "agent_graphics_context"] "children" ["nr_record"] "properties" {"children" #{#afield/box "nr_record"} "parents" #{#afield/box "root" #afield/box "agent_graphics_context"} "name" "neural cubes" "_tombstone" false "frame" #afield/rect [400.0 40.0 350.0 150.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "open_dlss_nr_code_.kt" "lines" 55 "characters" 2275 "sha256" "2f35def5aacf0c76e226301d406c066aa992542025467d65d9e27b400beae870"} {"name" "vertex" "language" "glsl" "path" "open_dlss_nr_vertex_.glsl" "lines" 49 "characters" 2564 "sha256" "9cadad82e7d4007c8c6d03b388a58d63a954b8b3ff5eef34b0565904739ca41f"} {"name" "fragment" "language" "glsl" "path" "open_dlss_nr_fragment_.glsl" "lines" 25 "characters" 1353 "sha256" "61c506ca6ed9fc5844bb716b59da41f8ef6c8ad1464d19e0747882a0d7c51b11"}]}
```

```kotlin [field-property box="open_dlss_nr" name="code" path="open_dlss_nr_code_.kt"]
import afield.controlGroup
import afield.displayOpen
import afield.four.neuralrendering.*
import afield.four.modules.fromContext
import afield.four.modules.fromContextWithNameOrNull
import afield.stdlib.linalg.Vector4f

val owner = fromContext<Renderer>()
prop<OpenDlssNrStudy>(lab)
fromContextWithNameOrNull<OpenDlssNrStudy>("lab")?.close()
lab = OpenDlssNrStudy(owner, size=768, name="nr-cubes", geometry=true,
    vertex=thisBox.getPropertyRaw("vertex") as String,
    fragment=thisBox.getPropertyRaw("fragment") as String)
println(lab.availability.reason)
var style=1
var tone=0f
var structure=10f
var intensity=1f
var animate=false
var reload=false
var red=.65f
var green=1.1f
var blue=1.4f
controlGroup("neural cubes") {
    if(lab.availability.ready) {
        slider("Network style",min=0.0,max=2.0,step=1.0,initialValue=1.0) {style=it.toInt()}
        slider("Tone",min=-10.0,max=30.0,initialValue=0.0) {tone=it.toFloat()}
        slider("Structure",min=-10.0,max=30.0,initialValue=10.0) {structure=it.toFloat()}
        slider("NR intensity (0 = source)",min=0.0,max=1.0,initialValue=1.0) {intensity=it.toFloat()}
    }
    checkbox("Animate source",initialValue=false) {animate=it}
    slider("Grade red",min=0.0,max=2.0,initialValue=.65) {red=it.toFloat()}
    slider("Grade green",min=0.0,max=2.0,initialValue=1.1) {green=it.toFloat()}
    slider("Grade blue",min=0.0,max=2.0,initialValue=1.4) {blue=it.toFloat()}
    button("Reload edited shaders") {if(it)reload=true}
}
displayOpen("neural cubes")
println("SOURCE | NR | GRADE · 768 × 768 per panel. Edit fragment, then reload. Run record clip for six seconds of GPU frames.")
val owned=lab
frames {
    try {
        while(!owner.isClosed) {
            owned.controls=OpenDlssNrControls(style=style,tone=tone,structure=structure,intensity=intensity)
            owned.grade=Vector4f(red,green,blue,1f)
            owned.animate=animate && !owned.recording
            if(reload) {
                owned.reload(thisBox.getPropertyRaw("vertex") as String,
                    thisBox.getPropertyRaw("fragment") as String)
                    .onFailure {println("Keeping working shaders: ${it.message}")}
                reload=false
            }
            frame()
        }
    } finally {owned.close()}
}
```

```glsl [field-property box="open_dlss_nr" name="vertex" path="open_dlss_nr_vertex_.glsl"]
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
    if(id==1){
        float a=time*.55;
        p.xz=mat2(cos(a),sin(a),-sin(a),cos(a))*p.xz;
        return p*vec3(.85,.7,.85)+vec3(-1.5,.7,-.5);
    }
    if(id==2){
        float a=-time*.4;
        p.xz=mat2(cos(a),sin(a),-sin(a),cos(a))*p.xz;
        return p*vec3(.7,.9,.7)+vec3(1.4+sin(time)*.6,.9,-.4);
    }
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

```glsl [field-property box="open_dlss_nr" name="fragment" path="open_dlss_nr_fragment_.glsl"]
#version 460
layout(push_constant) uniform Scene { vec4 sizeJitter; vec4 times; vec4 settings; } pc;
layout(location=0) in vec3 world;
layout(location=1) in vec3 normal;
layout(location=2) in vec4 nowClip;
layout(location=3) in vec4 oldClip;
layout(location=4) flat in int objectId;
layout(location=0) out vec4 color;
void main(){
    if(objectId<0){color=vec4(.014,.022,.04,1);return;}
    vec3 n=normalize(normal);
    vec3 base=objectId==0?vec3(.23):objectId==1?vec3(.05,.27,.65):objectId==2?vec3(.8,.14,.045):vec3(.9,.68,.21);
    if(objectId<3){vec2 st=world.xz*9;float checkers=mod(floor(st.x)+floor(st.y),2);base*=mix(.22,1,checkers);}
    vec3 light=normalize(vec3(sin(pc.times.x)*4,5,3)-world);
    float diffuse=.2+.8*abs(dot(n,light));
    float specular=pow(max(0,abs(dot(n,normalize(light+normalize(vec3(5,3.7,5)-world))))),96);
    color=vec4(clamp(base*diffuse+specular*1.8,0,1),1);
    if(pc.settings.x==1){
        color.rgb=objectId==0?vec3(.04,.08,.16):base;
        // Deliberately changing appearance has no geometric correspondence. It is visible in
        // comparisons, but not represented as fictional velocity or an undocumented vendor mask.
        if(objectId==1)color.rgb=.45+.4*cos(world*8+pc.times.x*vec3(2,3,5));
        if(objectId>=3)color.rgb=mix(vec3(.1,.8,.6),vec3(.9,.08,.3),step(0,sin(pc.times.x*1.7)));
    }
}
```

## 3. record clip

<!-- field-box id="nr_record" index="2" -->

```edn [field-box id="nr_record" role="properties"]
{"id" "nr_record" "index" 2 "name" "record clip" "parents" ["root" "open_dlss_nr"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "open_dlss_nr"} "name" "record clip" "_tombstone" false "frame" #afield/rect [820.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "nr_record_code_.kt" "lines" 31 "characters" 1215 "sha256" "8a7ebfccfb789268cbe155e3e4cca4159545dbedcd3b285771955b9d977f7724"}]}
```

```kotlin [field-property box="nr_record" name="code" path="nr_record_code_.kt"]
import afield.four.neuralrendering.OpenDlssNrStudy
import afield.four.modules.fromContext
import java.nio.file.Path
import kotlin.io.path.createDirectories

// Run beneath the live study box. The scene remains editable after recording.
val study=fromContext<OpenDlssNrStudy>()
check(study.availability.ready) {study.availability.reason}
check(!study.recording) {"A recording is already running"}
val folder=Path.of("build/opendlssnr-cubes/clip").toAbsolutePath().also {it.createDirectories()}
val controls=study.controls.copy()
val grade=study.grade.toString()
val previousTime=study.time
study.recording=true
study.animate=false
frames {
    try {
        for(i in 0 until 180) {
            check(study.controls==controls && study.grade.toString()==grade) {"Controls changed during capture; record again"}
            study.time=i/30f
            val saved=study.saveFrame(folder.resolve("frame-%03d.png".format(i)))
            while(!saved.isDone)frame()
            saved.join()
            if(i%30==0)println("Recorded $i / 180")
        }
        println("NR STUDY RECORD DONE: 180 frames at 30 fps, 2304 × 832; $folder")
    } finally {
        study.time=previousTime
        study.recording=false
    }
}
```

