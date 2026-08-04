# Field Document: threebox

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. renderer

<!-- field-box id="tb_renderer" index="0" -->

```edn [field-box id="tb_renderer" role="properties"]
{"id" "tb_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["tb_compute"] "properties" {"children" #{#afield/box "tb_compute"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 160.0 90.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "tb_renderer_code_.kt" "lines" 11 "characters" 255 "sha256" "cce6bf0ac8d8e5636e37d3a3298738dd70f5c6e6086bc4a0a02ac4f3a33650aa"}]}
```

```kotlin [field-property box="tb_renderer" name="code" path="tb_renderer_code_.kt"]
import afield.four.util.Options
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
once.renderer {
    window = Window("Field three-box")
    Renderer(window)
}
println("renderer ready")
start(renderer::run)
```

## 2. compute particles

<!-- field-box id="tb_compute" index="1" -->

```edn [field-box id="tb_compute" role="properties"]
{"id" "tb_compute" "index" 1 "name" "compute particles" "parents" ["root" "tb_renderer"] "children" ["tb_draw"] "properties" {"children" #{#afield/box "tb_draw"} "parents" #{#afield/box "root" #afield/box "tb_renderer"} "name" "compute particles" "_tombstone" false "frame" #afield/rect [240.0 40.0 160.0 90.0]} "blockProperties" [{"name" "compute" "language" "glsl" "path" "tb_compute_compute_.glsl" "lines" 19 "characters" 827 "sha256" "9c82bf06b67873abd0d572107d848171860960c70d20ff7c1bea18029da0b0b7"} {"name" "code" "language" "kotlin" "path" "tb_compute_code_.kt" "lines" 23 "characters" 964 "sha256" "07b5f1e26af78a8614bf539bc3d7f3489e0748c5601853096a27d1bf0eb7d37e"}]}
```

```glsl [field-property box="tb_compute" name="compute" path="tb_compute_compute_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require
layout(local_size_x = 64) in;
layout(std430, buffer_reference) buffer inData { vec4 a[]; };
layout(std430, buffer_reference) buffer outData { vec4 a[]; };
layout(push_constant) uniform dat {
    inData inData;
    outData outData;
    float something;
} pc;
{% include "fieldProbe" %}
void main() {
    uint idx = gl_GlobalInvocationID.x;
    pc.outData.a[idx].x = pc.inData.a[idx].x*cos(pc.something*pc.inData.a[idx].z) + pc.inData.a[idx].y*sin(pc.something*pc.inData.a[idx].w);
    pc.outData.a[idx].y = -pc.inData.a[idx].x*sin(pc.something*pc.inData.a[idx].w) + pc.inData.a[idx].y*cos(pc.something*pc.inData.a[idx].z*0.03);
    pc.outData.a[idx].zw = pc.inData.a[idx].zw;
    vec2 delta = pc.outData.a[idx].xy - pc.inData.a[idx].xy;
    fieldProbe(length(delta));
}
```

```kotlin [field-property box="tb_compute" name="code" path="tb_compute_code_.kt"]
var module = computeModuleHere {}
var m = ComputeMesh()
module += "step" to m
renderer += "compute" to module
val N = 100000
var inData = defaultBuffer { size = N.vec4s }.buffer
var outData = defaultBuffer { size = N.vec4s }.buffer
m.inData = { inData }
m.outData = { outData }
// The live renderer uses monotonic time; frame-synchronous docs capture replaces this
// with frameIndex/fps, so an already-running multi-box scene still restarts at movie t=0.
m.something = { (AnimationClock.nowSeconds() * 0.6).f }
fieldProbeHere(samples = 4096).display("probe")
displayOpen("probe")   // mark the channel open so the card starts expanded when it arrives
prop<FrameBufferGroup>(outputPositions) = outData
m.x = N / 64; m.y = 1; m.z = 1
val seeded = kotlin.random.Random(0x4f1e1d)
inData.write {
    for (i in 0 until N) {
        put(vec(seeded.nextDouble(), seeded.nextDouble(), seeded.nextDouble(), seeded.nextDouble()))
    }
}
println("compute launched, N=" + N)
```

## 3. draw particles

<!-- field-box id="tb_draw" index="2" -->

```edn [field-box id="tb_draw" role="properties"]
{"id" "tb_draw" "index" 2 "name" "draw particles" "parents" ["root" "tb_compute"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "tb_compute"} "name" "draw particles" "_tombstone" false "frame" #afield/rect [440.0 40.0 160.0 90.0]} "blockProperties" [{"name" "vertex" "language" "glsl" "path" "tb_draw_vertex_.glsl" "lines" 17 "characters" 643 "sha256" "0d85756a2e212600c96f1f99aa3a4ed39a26169880a2d89c9cc1f87d174ae514"} {"name" "fragment" "language" "glsl" "path" "tb_draw_fragment_.glsl" "lines" 10 "characters" 312 "sha256" "739c6e5b6ad0ac8fd506d6926fe7af6eac86d734807a1a8c121ca6d00585c25e"} {"name" "code" "language" "kotlin" "path" "tb_draw_code_.kt" "lines" 9 "characters" 267 "sha256" "1177cb983bc71460b886db49c592c2bc76d06703433390fcabda1d9da76d55c9"}]}
```

```glsl [field-property box="tb_draw" name="vertex" path="tb_draw_vertex_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require
layout(std430, buffer_reference) buffer inData { vec4 pos[]; };
layout(location = 0) out vec2 tc;
layout(push_constant) uniform push {
    inData inData;
    float aspectRatio;
} pc;
const vec3[6] part = vec3[6](vec3(0,0,0),vec3(1,0,0),vec3(1,1,0),vec3(0,0,0),vec3(1,1,0),vec3(0,1,0));
void main() {
    float scale = 1.0 * pc.inData.pos[gl_VertexIndex/6].w;
    vec3 p = (part[gl_VertexIndex%6]-vec3(0.5,0.5,0))*scale;
    p.xy += pc.inData.pos[gl_VertexIndex/6].xy*10.0;
    p.xy *= vec2(1/pc.aspectRatio, 1);
    gl_Position = vec4(p*0.04, 1);
    tc = part[gl_VertexIndex%6].xy;
}
```

```glsl [field-property box="tb_draw" name="fragment" path="tb_draw_fragment_.glsl"]
#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 0) in vec2 tc;
void main() {
    float d = max(0.0, 0.5-length(tc-vec2(0.5)));
    float w = fwidth(d);
    d = smoothstep(0.1, 0.1+w, d)*smoothstep(0.14+w, 0.14, d);
    uFragColor = vec4(1.0, 0.8, 0.4, d*0.2);
    if (d<0.01) discard;
}
```

```kotlin [field-property box="tb_draw" name="code" path="tb_draw_code_.kt"]
var module = moduleHere { primitiveType = triangles }
var m = Mesh()
module += "mesh" to m
renderer += "draw" to module
val N = 100000
m.inData = { outputPositions }
m.aspectRatio = { window.width/window.height.toFloat() }
m.set(N*2*3, 0) {}
println("draw launched")
```

