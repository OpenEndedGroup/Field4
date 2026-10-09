# Field Document: spin_glass

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="sg_renderer" index="0" -->

```edn [field-box id="sg_renderer" role="properties"]
{"id" "sg_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["sg_update"] "properties" {"children" #{#afield/box "sg_update"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sg_renderer_code_.kt" "lines" 14 "characters" 422 "sha256" "a562d47728e84765b6dc8e3df9c4adffb2b2feb93261ebbd60f6afedf995817c"}]}
```

```kotlin [field-property box="sg_renderer" name="code" path="sg_renderer_code_.kt"]
import afield.four.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
// once: re-running this box keeps the existing window and renderer
once.renderer {
    window = Window("Spin Glass - 2D Edwards-Anderson")
    Renderer(window)
}
println("renderer ready")
// the frame loop runs as this box's coroutine; stopping the box stops rendering
start(renderer::run)
```

## 2. metropolis update

<!-- field-box id="sg_update" index="1" -->

```edn [field-box id="sg_update" role="properties"]
{"id" "sg_update" "index" 1 "name" "metropolis update" "parents" ["root" "sg_renderer"] "children" ["sg_measure"] "properties" {"children" #{#afield/box "sg_measure"} "parents" #{#afield/box "root" #afield/box "sg_renderer"} "name" "metropolis update" "_tombstone" false "frame" #afield/rect [340.0 40.0 300.0 100.0]} "blockProperties" [{"name" "compute" "language" "glsl" "path" "sg_update_compute_.glsl" "lines" 81 "characters" 2130 "sha256" "2f37f71d5e9956c485d968d3cbdf58d384923914ec9b458d460699546062963e"} {"name" "code" "language" "kotlin" "path" "sg_update_code_.kt" "lines" 96 "characters" 3163 "sha256" "bba7f2186bde40cc3a204eecafe003ad8dbf220cde8ae09eee655e475566a2aa"}]}
```

```glsl [field-property box="sg_update" name="compute" path="sg_update_compute_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require
layout(local_size_x = 64) in;

layout(std430, buffer_reference) buffer Spins {
    float s[];
};
layout(std430, buffer_reference) buffer Bonds {
    float j[];
};

layout(push_constant) uniform PushC {
    Spins spins;
    Bonds jRight;
    Bonds jDown;
    uint L;
    uint color;
    uint seed;
    uint mode;
    float temperature;
    float field;
} pc;

uint hash(uint x) {
    x ^= x >> 16;
    x *= 0x7feb352du;
    x ^= x >> 15;
    x *= 0x846ca68bu;
    x ^= x >> 16;
    return x;
}
float rnd(uint v) {
    return float(hash(v)) * (1.0 / 4294967296.0);
}

void main() {
    uint L = pc.L;
    uint N = L * L;
    uint idx = gl_GlobalInvocationID.x;
    if (idx >= N) return;
    uint x = idx % L;
    uint y = idx / L;
    // red-black: this dispatch only touches one colour of the checkerboard,
    // so no two updated spins are ever nearest neighbours in a single pass.
    if (((x + y) & 1u) != pc.color) return;

    uint xl = (x + L - 1u) % L;
    uint xr = (x + 1u) % L;
    uint yu = (y + L - 1u) % L;
    uint yd = (y + 1u) % L;

    float s = pc.spins.s[idx];
    float sl = pc.spins.s[y * L + xl];
    float sr = pc.spins.s[y * L + xr];
    float su = pc.spins.s[yu * L + x];
    float sd = pc.spins.s[yd * L + x];

    // jRight[i] is the bond i--(+x); the left bond is the left neighbour's jRight.
    // jDown[i] is the bond i--(+y); the up bond is the up neighbour's jDown.
    float jl = pc.jRight.j[y * L + xl];
    float jr = pc.jRight.j[idx];
    float ju = pc.jDown.j[yu * L + x];
    float jd = pc.jDown.j[idx];

    float localField = jl * sl + jr * sr + ju * su + jd * sd + pc.field;
    float T = max(pc.temperature, 1e-4);
    float u = rnd(idx * 2654435761u + pc.seed * 40503u + pc.color * 19349663u);

    float ns = s;
    if (pc.mode == 0u) {
        // Metropolis
        float dE = 2.0 * s * localField;
        if (dE <= 0.0 || u < exp(-dE / T)) ns = -s;
    }
    else {
        // heat-bath
        float pPlus = 1.0 / (1.0 + exp(-2.0 * localField / T));
        ns = (u < pPlus) ? 1.0 : -1.0;
    }
    pc.spins.s[idx] = ns;
}
```

```kotlin [field-property box="sg_update" name="code" path="sg_update_code_.kt"]
import afield.four.modules.computeMemoryBarrier
import afield.four.modules.computeTask

val L = 128
val N = L * L
val groups = (N + 63) / 64

// simulation parameters, published so the measure + view boxes can read them
prop<Int>(sgL) = L
prop<Double>(sgTemperature) = 3.0
prop<Double>(sgField) = 0.0
prop<Int>(sgMode) = 0 // 0 = Metropolis, 1 = heat-bath
prop<Int>(sgModel) = 0 // 0 = +/-J bimodal, 1 = Gaussian bonds
prop<Int>(sgSweeps) = 0
prop<Boolean>(sgAnneal) = true

// annealing schedule: temperature swings between Thi and Tlo every `period` sweeps
val Thi = 3.0
val Tlo = 0.04
val period = 6000
val model = sgModel

// in-place lattice state (single, persistent copy — not per-frame)
var spins = defaultBuffer { size = N.floats }.buffer
var jRight = defaultBuffer { size = N.floats }.buffer
var jDown = defaultBuffer { size = N.floats }.buffer

val rng = java.util.Random(20260720L)
/*
random initial spins, then bonds: Gaussian when sgModel == 1, else +/-1.
The bond draw is written out twice because a local `fun` that reads a prop
fails K2 codegen in a box script.
*/
spins.write { for (i in 0 until N) put(if (rng.nextBoolean()) 1f else -1f) }
jRight.write {
    for (i in 0 until N) put(if (model == 1) rng.nextGaussian().toFloat() else if (rng.nextBoolean()) 1f else -1f)
}
jDown.write {
    for (i in 0 until N) put(if (model == 1) rng.nextGaussian().toFloat() else if (rng.nextBoolean()) 1f else -1f)
}

prop<FrameBufferGroup>(sgSpins) = spins
prop<FrameBufferGroup>(sgJRight) = jRight
prop<FrameBufferGroup>(sgJDown) = jDown

var sweep = 0
var seed = 1
var T = Thi

var module = computeModuleHere {}
// a ComputeMesh must be added to its module before x/y/z or push constants are set
val even = ComputeMesh()
val odd = ComputeMesh()

// "tick" runs first each frame: advance the sweep counter + anneal schedule once.
module +=
    "tick" to
        computeTask { _, _, _ ->
            sweep += 1
            seed += 1
            sgSweeps = sweep
            if (sgAnneal) {
                val phase = (sweep % period).toDouble() / period.toDouble()
                T = Tlo + 0.5 * (Thi - Tlo) * (1.0 + Math.cos(2.0 * Math.PI * phase))
                sgTemperature = T
            } else {
                T = sgTemperature
            }
        }
module += "even" to even
module += "barrierE" to computeTask { _, cmd, _ -> computeMemoryBarrier(cmd) }
module += "odd" to odd
module += "barrierO" to computeTask { _, cmd, _ -> computeMemoryBarrier(cmd) }
renderer += "spinGlassUpdate" to module

// both half-sweeps share bindings; they differ only in checkerboard colour and seed
for (m in listOf(even, odd)) {
    m.x = groups
    m.y = 1
    m.z = 1
    m.spins = { spins }
    m.jRight = { jRight }
    m.jDown = { jDown }
    m.L = { L.toUInt() } // uint push members need UInt-returning lambdas
    m.mode = { sgMode.toUInt() }
    m.field = { sgField.toFloat() }
    m.temperature = { T.toFloat() }
}
even.color = { 0u }
odd.color = { 1u }
even.seed = { seed.toUInt() }
odd.seed = {
    (seed + 100003).toUInt()
} // offset so the two passes draw different randoms

println("update launched L=" + L + " N=" + N + " groups=" + groups)
```

## 3. measure (energy / magnetization)

<!-- field-box id="sg_measure" index="2" -->

```edn [field-box id="sg_measure" role="properties"]
{"id" "sg_measure" "index" 2 "name" "measure (energy / magnetization)" "parents" ["root" "sg_update"] "children" ["sg_draw"] "properties" {"children" #{#afield/box "sg_draw"} "parents" #{#afield/box "root" #afield/box "sg_update"} "name" "measure (energy / magnetization)" "_tombstone" false "frame" #afield/rect [670.0 40.0 330.0 100.0]} "blockProperties" [{"name" "compute" "language" "glsl" "path" "sg_measure_compute_.glsl" "lines" 37 "characters" 902 "sha256" "93f4b4399393367312771380d909f55e43ef0dc2df3087fc25bb9bb7c1266bd2"} {"name" "code" "language" "kotlin" "path" "sg_measure_code_.kt" "lines" 28 "characters" 860 "sha256" "8e939596bc25c2b90ccf30c175244996df59da4a1f52da55deef4d38b5027e9d"}]}
```

```glsl [field-property box="sg_measure" name="compute" path="sg_measure_compute_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require
layout(local_size_x = 64) in;

layout(std430, buffer_reference) buffer Spins {
    float s[];
};
layout(std430, buffer_reference) buffer Bonds {
    float j[];
};

layout(push_constant) uniform PushC {
    Spins spins;
    Bonds jRight;
    Bonds jDown;
    uint L;
    float field;
} pc;

{% include "fieldProbe" %}

void main() {
    uint L = pc.L;
    uint N = L * L;
    uint idx = gl_GlobalInvocationID.x;
    if (idx >= N) return;
    uint x = idx % L;
    uint y = idx / L;
    uint xr = (x + 1u) % L;
    uint yd = (y + 1u) % L;
    float s = pc.spins.s[idx];
    float sr = pc.spins.s[y * L + xr];
    float sd = pc.spins.s[yd * L + x];
    // count each bond once (right + down), so sum over sites is the total energy
    float e = -(pc.jRight.j[idx] * s * sr + pc.jDown.j[idx] * s * sd) - pc.field * s;
    fieldProbe(vec2(s, e));
}
```

```kotlin [field-property box="sg_measure" name="code" path="sg_measure_code_.kt"]
import afield.four.modules.computeMemoryBarrier
import afield.four.modules.computeTask

val L = sgL
val N = L * L
val groups = (N + 63) / 64

// call fieldProbeHere BEFORE computeModuleHere so attachFieldProbe wires the SSBO.
// samples >= N keeps every site each frame, so the reported means are exact.
fieldProbeHere(samples = if (N < 262144) N else 262144).display("stats")
displayOpen("stats")

var module = computeModuleHere {}
val m = ComputeMesh()
// leading barrier: make the update box's spin writes visible to this read.
module += "sync" to computeTask { _, cmd, _ -> computeMemoryBarrier(cmd) }
module += "measure" to m
renderer += "spinGlassMeasure" to module

m.x = groups
m.y = 1
m.z = 1
m.spins = { sgSpins }
m.jRight = { sgJRight }
m.jDown = { sgJDown }
m.L = { sgL.toUInt() }
m.field = { sgField.toFloat() }
println("measure launched N=" + N)
```

## 4. lattice view

<!-- field-box id="sg_draw" index="3" -->

```edn [field-box id="sg_draw" role="properties"]
{"id" "sg_draw" "index" 3 "name" "lattice view" "parents" ["root" "sg_measure"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "sg_measure"} "name" "lattice view" "_tombstone" false "frame" #afield/rect [1020.0 40.0 300.0 100.0]} "blockProperties" [{"name" "vertex" "language" "glsl" "path" "sg_draw_vertex_.glsl" "lines" 8 "characters" 245 "sha256" "852162ed2f08d7ffd06e2e62b4d9690cd3c965fe20fffd0ee96aec38bf529865"} {"name" "fragment" "language" "glsl" "path" "sg_draw_fragment_.glsl" "lines" 50 "characters" 1390 "sha256" "4531ab65f9cf7758d4e95ffa3cbfe0f06b25087ceb9e2ba338be9036b9b23f73"} {"name" "code" "language" "kotlin" "path" "sg_draw_code_.kt" "lines" 15 "characters" 487 "sha256" "99dc1cc451724b07167cd8bf0f0e49559f520c2e4465b10e4d6b233a35bd3c3c"}]}
```

```glsl [field-property box="sg_draw" name="vertex" path="sg_draw_vertex_.glsl"]
#version 450
layout(location = 0) out vec2 uv;
vec2 verts[3] = vec2[3](vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0));
void main() {
    vec2 p = verts[gl_VertexIndex];
    uv = 0.5 * (p + vec2(1.0));
    gl_Position = vec4(p, 0.0, 1.0);
}
```

```glsl [field-property box="sg_draw" name="fragment" path="sg_draw_fragment_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require
layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 outColor;

layout(std430, buffer_reference) buffer Spins {
    float s[];
};
layout(std430, buffer_reference) buffer Bonds {
    float j[];
};

layout(push_constant) uniform DrawPC {
    Spins spins;
    Bonds jRight;
    Bonds jDown;
    uint L;
    float aspect;
    uint showFrustration;
} pc;

void main() {
    uint L = pc.L;
    vec2 p = uv;
    // keep the lattice square regardless of window aspect
    if (pc.aspect >= 1.0) {
        p.x = (uv.x - 0.5) * pc.aspect + 0.5;
    }
    else {
        p.y = (uv.y - 0.5) / pc.aspect + 0.5;
    }
    if (p.x < 0.0 || p.x > 1.0 || p.y < 0.0 || p.y > 1.0) {
        outColor = vec4(0.05, 0.05, 0.07, 1.0);
        return;
    }
    uint x = min(uint(p.x * float(L)), L - 1u);
    uint y = min(uint((1.0 - p.y) * float(L)), L - 1u);
    uint idx = y * L + x;
    float s = pc.spins.s[idx];
    vec3 up = vec3(0.98, 0.60, 0.16);
    vec3 dn = vec3(0.14, 0.44, 0.82);
    vec3 col = (s > 0.0) ? up : dn;
    if (pc.showFrustration == 1u) {
        uint xr = (x + 1u) % L;
        uint yd = (y + 1u) % L;
        float plaq = pc.jRight.j[idx] * pc.jDown.j[y * L + xr] * pc.jRight.j[yd * L + x] * pc.jDown.j[idx];
        if (plaq < 0.0) col = mix(col, vec3(1.0, 0.08, 0.08), 0.30);
    }
    outColor = vec4(col, 1.0);
}
```

```kotlin [field-property box="sg_draw" name="code" path="sg_draw_code_.kt"]
var module = moduleHere { primitiveType = triangles }
var m = Mesh()
module += "grid" to m
renderer += "spinGlassDraw" to module

m.spins = { sgSpins }
m.jRight = { sgJRight }
m.jDown = { sgJDown }
m.L = { sgL.toUInt() }
m.aspect = { window.width / window.height.toFloat() }
// set to { 1u } and re-run to overlay frustrated plaquettes
m.showFrustration = { 0u }
// three vertices, no index buffer: the vertex shader makes the fullscreen triangle
m.set(3, 0) {}
println("draw launched")
```

