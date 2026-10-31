# Field Document: threebox

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="tb_renderer" index="0" -->

```edn [field-box id="tb_renderer" role="properties"]
{"id" "tb_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["tb_compute"] "properties" {"children" #{#afield/box "tb_compute"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "tb_renderer_code_.kt" "lines" 12 "characters" 267 "sha256" "5adb4383d6451544289629b5958e7ac4767f497841dc5b8c0797e2c4f4ea53af"}]}
```

```kotlin [field-property box="tb_renderer" name="code" path="tb_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

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
{"id" "tb_compute" "index" 1 "name" "compute particles" "parents" ["root" "tb_renderer"] "children" ["tb_draw"] "properties" {"children" #{#afield/box "tb_draw"} "parents" #{#afield/box "root" #afield/box "tb_renderer"} "name" "compute particles" "_tombstone" false "frame" #afield/rect [340.0 40.0 300.0 100.0]} "blockProperties" [{"name" "compute" "language" "glsl" "path" "tb_compute_compute_.glsl" "lines" 32 "characters" 959 "sha256" "817c9f667f61952741d442d986e0004318c9bfef7c510faf1d5fb933751e40ab"} {"name" "code" "language" "kotlin" "path" "tb_compute_code_.kt" "lines" 28 "characters" 1154 "sha256" "f3d046be8037154031c0c6c163dd6b3c162f3181b7fde1f88172684cdaa88bc1"}]}
```

```glsl [field-property box="tb_compute" name="compute" path="tb_compute_compute_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require
layout(local_size_x = 64) in;
layout(std430, buffer_reference) buffer inData {
    vec4 a[];
};
layout(std430, buffer_reference) buffer outData {
    vec4 a[];
};
layout(push_constant) uniform dat {
    inData inData;
    outData outData;
    float something;
    uint particleCount;
} pc;
{% property "fieldProbe" %}
void main() {
    uint idx = gl_GlobalInvocationID.x;
    if (idx >= pc.particleCount) return;
    vec4 seed = pc.inData.a[idx]; // xy position, zw modulation and size.
    float phaseZ = pc.something * seed.z;
    float phaseW = pc.something * seed.w;
    // The y cosine alone uses the slow phase.
    float slowPhaseZ = phaseZ * 0.03;
    vec2 moved = vec2(
    seed.x * cos(phaseZ) + seed.y * sin(phaseW),
    -seed.x * sin(phaseW) + seed.y * cos(slowPhaseZ)
    );
    pc.outData.a[idx] = vec4(moved, seed.zw);
    vec2 delta = moved - seed.xy;
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
m.particleCount = { N }
// The live renderer uses monotonic time; frame-synchronous docs capture replaces this
// with frameIndex/fps, so an already-running multi-box scene still restarts at movie t=0.
m.something = { (AnimationClock.nowSeconds() * 0.6).f }
// Full coverage makes the published probe card independent of GPU workgroup ordering;
// summaryHtml canonicalizes the scalar display order before plotting it.
fieldProbeHere(samples = 100000).display("probe")
displayOpen("probe") // mark the channel open so the card starts expanded when it arrives
prop<FrameBufferGroup>(outputPositions) = outData
m.x = (N + 63) / 64
m.y = 1
m.z = 1
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
{"id" "tb_draw" "index" 2 "name" "draw particles" "parents" ["root" "tb_compute"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "tb_compute"} "name" "draw particles" "_tombstone" false "frame" #afield/rect [640.0 40.0 300.0 100.0]} "blockProperties" [{"name" "vertex" "language" "glsl" "path" "tb_draw_vertex_.glsl" "lines" 19 "characters" 647 "sha256" "2c8e11634cdf55e1666a96f3bd1411949e77074c7a19dbac24f48c5380aa07f1"} {"name" "fragment" "language" "glsl" "path" "tb_draw_fragment_.glsl" "lines" 10 "characters" 312 "sha256" "739c6e5b6ad0ac8fd506d6926fe7af6eac86d734807a1a8c121ca6d00585c25e"} {"name" "code" "language" "kotlin" "path" "tb_draw_code_.kt" "lines" 9 "characters" 273 "sha256" "60e8d9bb841f8f308e594ff0e9e44b06c4eb33880e976247aef79e5945573461"}]}
```

```glsl [field-property box="tb_draw" name="vertex" path="tb_draw_vertex_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require
layout(std430, buffer_reference) buffer inData {
    vec4 pos[];
};
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
m.aspectRatio = { window.width / window.height.toFloat() }
m.set(N * 2 * 3, 0) {}
println("draw launched")
```

