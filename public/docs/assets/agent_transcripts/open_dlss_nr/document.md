# Field Document: open_dlss_nr

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="agent_graphics_context" index="0" -->

```edn [field-box id="agent_graphics_context" role="properties"]
{"id" "agent_graphics_context" "index" 0 "name" "renderer" "parents" ["root"] "children" ["open_dlss_nr"] "properties" {"children" #{#afield/box "open_dlss_nr"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_graphics_context_code_.kt" "lines" 13 "characters" 380 "sha256" "b83c0cf580aa02afd9d675494d343094344fc75f3d32f4099f6e58a7db3e0adf"}]}
```

```kotlin [field-property box="agent_graphics_context" name="code" path="agent_graphics_context_code_.kt"]
import afield.graphics.vulkan.util.Options
import afield.app.protocol.Rect
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableVR=false
Options.enableHDR=false
Options.profiling=false
once.renderer {
    window=Window("Field · neural ink",initialDimensions=Rect(40.0,40.0,2304.0,832.0))
    Renderer(window)
}
println("agent graphics renderer ready")
start(renderer::run)
```

## 2. neural ink

<!-- field-box id="open_dlss_nr" index="1" -->

```edn [field-box id="open_dlss_nr" role="properties"]
{"id" "open_dlss_nr" "index" 1 "name" "neural ink" "parents" ["root" "agent_graphics_context"] "children" ["nr_record"] "properties" {"children" #{#afield/box "nr_record"} "parents" #{#afield/box "root" #afield/box "agent_graphics_context"} "name" "neural ink" "_tombstone" false "frame" #afield/rect [400.0 40.0 350.0 150.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "open_dlss_nr_code_.kt" "lines" 55 "characters" 2335 "sha256" "a6e968d243cf4cb7c7a5d1eed00c2a87a974883436d42897418193cb55c4405f"} {"name" "vertex" "language" "glsl" "path" "open_dlss_nr_vertex_.glsl" "lines" 7 "characters" 195 "sha256" "0ca9a483205f4c8f54843729e3a6d077be2ab8791b1327f8abdf8932429363af"} {"name" "fragment" "language" "glsl" "path" "open_dlss_nr_fragment_.glsl" "lines" 52 "characters" 2047 "sha256" "60222eb317cdb66daadd30f266a1900c4df9d21fc3ff3694a70af26a073589ce"}]}
```

```kotlin [field-property box="open_dlss_nr" name="code" path="open_dlss_nr_code_.kt"]
import afield.ui.output.controlGroup
import afield.ui.output.displayOpen
import afield.graphics.render.neural.*

val owner = renderer
prop<OpenDlssNrStudy>(lab)
(thisBox.getPropertyRaw("lab") as? OpenDlssNrStudy)?.close()
// Leave null for the editable fragment shader; try the bundled drawing or your own image.
val imagePath: String? = null
// val imagePath: String? = "public/docs/assets/opendlssnr/ink-input.png"
lab = OpenDlssNrStudy(owner, size=768, imagePath=imagePath,
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
controlGroup("neural ink") {
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
displayOpen("neural ink")
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
layout(location=0) out vec2 uv;
void main() {
    vec2 corner=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);
    uv=vec2(corner.x,1-corner.y);
    gl_Position=vec4(corner*2-1,0,1);
}
```

```glsl [field-property box="open_dlss_nr" name="fragment" path="open_dlss_nr_fragment_.glsl"]
#version 460
layout(location=0) in vec2 uv;
layout(location=0) out vec4 color;
layout(push_constant) uniform Study { vec4 frame; } pc;

float hash(vec2 p) {
    return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);
}
float noise(vec2 p) {
    vec2 i=floor(p), f=fract(p);
    f=f*f*(3-2*f);
    return mix(mix(hash(i),hash(i+vec2(1,0)),f.x),
               mix(hash(i+vec2(0,1)),hash(i+vec2(1)),f.x),f.y);
}
vec3 linearize(vec3 c) {
    return mix(c/12.92,pow((c+.055)/1.055,vec3(2.4)),step(vec3(.04045),c));
}
void main() {
    float time=pc.frame.x;
    vec2 p=(uv-.5)*2;
    float grain=noise(uv*pc.frame.yz*.7);
    vec3 paper=vec3(.88,.85,.77)+.025*(grain-.5);
    float ink=0;
    // Three breathing, imperfect loops. The source is drawn here, in Field, every frame.
    for(int i=0;i<3;i++) {
        float k=float(i);
        vec2 q=p-vec2(.035*sin(time*.6+k*2),.035*cos(time*.7+k));
        q=mat2(cos(k*.8),sin(k*.8),-sin(k*.8),cos(k*.8))*q;
        q.y*=1.05+.16*sin(time*.3+k);
        float a=atan(q.y,q.x), r=length(q);
        float radius=.27+k*.22+.025*sin(a*3+time*.55+k*2)+.018*sin(a*7-time*.3+k);
        float rough=.004*(noise(q*110)-.5)+.006*sin(a*23+k*4);
        float width=.013+.017*(.5+.5*sin(a*2+time*.3+k*1.7));
        float edge=abs(r-radius+rough);
        float body=1-smoothstep(width,width+.005,edge);
        float dry=1-.35*smoothstep(.48,.72,noise(q*160+vec2(k*10)));
        ink=max(ink,body*dry);
        // Loose charcoal echoes around the brush stroke.
        for(int j=1;j<4;j++) {
            float d=float(j)*.017;
            float echo=abs(r-radius-d-.008*sin(a*11+k+time*.2));
            ink=max(ink,(1-smoothstep(.001,.004,echo))*.13);
        }
    }
    // Fixed flecks move with the paper, not with a fresh random seed each frame.
    vec2 cell=floor(uv*85);
    vec2 local=fract(uv*85)-.5;
    float speck=step(.974,hash(cell))*(1-smoothstep(.03,.10,length(local)));
    ink=max(ink,speck*.65);
    vec3 pigment=vec3(.055,.046,.041);
    color=vec4(linearize(mix(paper,pigment,ink)),1);
}
```

## 3. record clip

<!-- field-box id="nr_record" index="2" -->

```edn [field-box id="nr_record" role="properties"]
{"id" "nr_record" "index" 2 "name" "record clip" "parents" ["root" "open_dlss_nr"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "open_dlss_nr"} "name" "record clip" "_tombstone" false "frame" #afield/rect [820.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "nr_record_code_.kt" "lines" 30 "characters" 1151 "sha256" "af4c1279d733a72ab24135e5b9681498bcd9ec50933fd53962c6271e9b9ad4c4"}]}
```

```kotlin [field-property box="nr_record" name="code" path="nr_record_code_.kt"]
import afield.graphics.render.neural.OpenDlssNrStudy
import java.nio.file.Path
import kotlin.io.path.createDirectories

// Run beneath the live study box. The scene remains editable after recording.
val study=lab
check(study.availability.ready) {study.availability.reason}
check(!study.recording) {"A recording is already running"}
val folder=Path.of("build/opendlssnr-study/clip").toAbsolutePath().also {it.createDirectories()}
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

