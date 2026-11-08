# Field Document: growing_nca

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. growing nca — live (CPU), launch me

<!-- field-box id="nca_live" index="0" -->

### Narrative

From `code`

LAUNCH this box (undo stops it). The same rule, live on the CPU: it grows from a seed, and every
400 steps a disc in a random place is erased so you can watch it heal. The step count and the
error against the target are printed underneath.

```edn [field-box id="nca_live" role="properties"]
{"id" "nca_live" "index" 0 "name" "growing nca — live (CPU), launch me" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "growing nca — live (CPU), launch me" "_tombstone" false "frame" #afield/rect [420.0 200.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "nca_live_code_.kt" "lines" 17 "characters" 655 "sha256" "6206b17dbbed8aa2b80029ba69597230636edfa5f0a957aede6a4f60a9596929"}]}
```

```kotlin [field-property box="nca_live" name="code" path="nca_live_code_.kt"]
/*
LAUNCH this box (undo stops it). The same rule, live on the CPU: it grows from a seed, and every
400 steps a disc in a random place is erased so you can watch it heal. The step count and the
error against the target are printed underneath.
*/
val nca = GrowingNca.pretrained("flower")
val target = GrowingNca.pretrainedTarget("flower")

frames {
    while (true) {
        nca.step(2)
        if (nca.steps % 400 == 200) nca.damage(20.0 + Math.random() * 24, 20.0 + Math.random() * 24, 10.0)
        display("main", nca.png(scale = 6))
        if (nca.steps % 20 == 0) println("step ${nca.steps}, error ${nca.loss(target).f4}")
        frame()
    }
}
```

## 2. renderer

<!-- field-box id="nca_renderer" index="1" -->

```edn [field-box id="nca_renderer" role="properties"]
{"id" "nca_renderer" "index" 1 "name" "renderer" "parents" ["root"] "children" ["nca_gpu"] "properties" {"children" #{#afield/box "nca_gpu"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "nca_renderer_code_.kt" "lines" 12 "characters" 263 "sha256" "c3f4dfacb4a318c3cbc01337c0a4b3817a2cb5b980459192209dd36da8d1f0de"}]}
```

```kotlin [field-property box="nca_renderer" name="code" path="nca_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
once.renderer {
    window = Window("Growing NCA")
    Renderer(window)
}
println("renderer ready")
start(renderer::run)
```

## 3. growing nca (GPU)

<!-- field-box id="nca_gpu" index="2" -->

### Narrative

From `code`

A growing neural cellular automaton on the GPU. Run this box beneath the renderer (`window`) box.

Every cell of a 96 x 96 grid holds 16 numbers and runs the same small network twice a frame: the
`compute` shader (open it with the property switcher) is GrowingNca.computeShader(), whose network
part MlpWeights.glsl wrote. The `vertex` and `fragment` shaders draw the first four numbers, the
cell's colour. Hold the left mouse button over the window to erase cells and watch them grow back.

```edn [field-box id="nca_gpu" role="properties"]
{"id" "nca_gpu" "index" 2 "name" "growing nca (GPU)" "parents" ["root" "nca_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "nca_renderer"} "name" "growing nca (GPU)" "_tombstone" false "frame" #afield/rect [420.0 40.0 340.0 100.0]} "blockProperties" [{"name" "compute" "language" "glsl" "path" "nca_gpu_compute_.glsl" "lines" 100 "characters" 3523 "sha256" "5e8428af895ad9de00e79e08a0925f9fe62f66e932a569d4159c164cc9023992"} {"name" "vertex" "language" "glsl" "path" "nca_gpu_vertex_.glsl" "lines" 12 "characters" 345 "sha256" "c55d55eab8d8b7e6dd2cf26f915903dfb3364de1defda77b9dc14489a101cf01"} {"name" "fragment" "language" "glsl" "path" "nca_gpu_fragment_.glsl" "lines" 31 "characters" 1302 "sha256" "d30ac0135056781a0bd0945a066ceb50998316ee79395725ac8cbc3c397a78f2"} {"name" "code" "language" "kotlin" "path" "nca_gpu_code_.kt" "lines" 101 "characters" 3901 "sha256" "cc87eb231b39216109269488b95d32024e8d28cc05cfc975c378811a3f8aec9b"}]}
```

```glsl [field-property box="nca_gpu" name="compute" path="nca_gpu_compute_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require

// MlpWeights(48->128->16, RELU, 8336 parameters)
// Generated by MlpWeights.glsl(); the weight layout is documented on MlpWeights.
layout(std430, buffer_reference) buffer ncaNetWeights {
    float w[];
};

void ncaNet(ncaNetWeights nw, float x[48], out float y[16]) {
    float h0[128];
    // layer 0: 48 -> 128, relu
    for (int o = 0; o < 128; ++o) {
        float s = nw.w[6144 + o];
        for (int i = 0; i < 48; ++i) s += nw.w[0 + o * 48 + i] * x[i];
        h0[o] = max(s, 0.0);
    }
    // layer 1: 128 -> 16, linear
    for (int o = 0; o < 16; ++o) {
        float s = nw.w[8320 + o];
        for (int i = 0; i < 128; ++i) s += nw.w[6272 + o * 128 + i] * h0[i];
        y[o] = s;
    }
}
layout(local_size_x = 8, local_size_y = 8) in;
layout(std430, buffer_reference) buffer NcaState {
    vec4 v[];
};
layout(push_constant) uniform pcs {
    NcaState cells;
    ncaNetWeights nw;
    uint size;
    uint parity;
    uint seed;
    vec4 brush;
} pc;

float cellAlpha(int x, int y) {
    if (x < 0 || y < 0 || x >= int(pc.size) || y >= int(pc.size)) return 0.0;
    return pc.cells.v[int(pc.parity * pc.size * pc.size * 4u) + (y * int(pc.size) + x) * 4].w;
}
float hash(uvec3 v) {
    v = v * 1664525u + 1013904223u;
    v.x += v.y * v.z;
    v.y += v.z * v.x;
    v.z += v.x * v.y;
    v ^= v >> 16u;
    v.x += v.y * v.z;
    v.y += v.z * v.x;
    v.z += v.x * v.y;
    return float(v.x & 0xffffffu) / 16777216.0;
}

void main() {
    int x = int(gl_GlobalInvocationID.x), y = int(gl_GlobalInvocationID.y), n = int(pc.size);
    if (x >= n || y >= n) return;
    int src = int(pc.parity) * n * n * 4, dst = (1 - int(pc.parity)) * n * n * 4;
    int base = (y * n + x) * 4;
    float s[16];
    for (int k = 0; k < 4; ++k) {
        vec4 q = pc.cells.v[src + base + k];
        s[4*k] = q.x;
        s[4*k+1] = q.y;
        s[4*k+2] = q.z;
        s[4*k+3] = q.w;
    }

    bool pre = false;
    for (int dy = -1; dy <= 1; ++dy) for (int dx = -1; dx <= 1; ++dx) pre = pre || cellAlpha(x + dx, y + dy) > 0.1;

    if (pre && hash(uvec3(x, y, pc.seed)) <= 0.5) {
        float p[48];
        for (int c = 0; c < 48; ++c) p[c] = 0.0;
        for (int dy = -1; dy <= 1; ++dy) for (int dx = -1; dx <= 1; ++dx) {
            int xx = x + dx, yy = y + dy;
            if (xx < 0 || yy < 0 || xx >= n || yy >= n) continue;
            float sx = float(dx) * (dy == 0 ? 2.0 : 1.0) / 8.0;
            float sy = float(dy) * (dx == 0 ? 2.0 : 1.0) / 8.0;
            int nb = (yy * n + xx) * 4;
            for (int k = 0; k < 4; ++k) {
                vec4 q = pc.cells.v[src + nb + k];
                for (int j = 0; j < 4; ++j) {
                    int c = 4 * k + j;
                    p[16 + c] += sx * q[j];
                    p[32 + c] += sy * q[j];
                }
            }
        }
        for (int c = 0; c < 16; ++c) p[c] = s[c];
        float ds[16];
        ncaNet(pc.nw, p, ds);
        for (int c = 0; c < 16; ++c) s[c] += ds[c];
    }
    // The second half of alive masking needs the neighbours' *updated* alpha, which this
    // pass cannot see; the draw pass and the next step's pre-check apply it instead
    // (see dev_docs/GROWING_NCA.md).
    if (!pre) for (int c = 0; c < 16; ++c) s[c] = 0.0;
    if (pc.brush.w > 0.0 && distance(vec2(x, y) + 0.5, pc.brush.xy) < pc.brush.z) for (int c = 0; c < 16; ++c) s[c] = 0.0;
    for (int k = 0; k < 4; ++k) pc.cells.v[dst + base + k] = vec4(s[4*k], s[4*k+1], s[4*k+2], s[4*k+3]);
}
```

```glsl [field-property box="nca_gpu" name="vertex" path="nca_gpu_vertex_.glsl"]
#version 450

// one triangle that covers the whole window; positions come from the vertex index
vec2 fullscreenVertex(uint index) {
    if (index == 0u) return vec2(-1.0, -1.0);
    if (index == 1u) return vec2(3.0, -1.0);
    return vec2(-1.0, 3.0);
}

void main() {
    gl_Position = vec4(fullscreenVertex(uint(gl_VertexIndex)), 0.0, 1.0);
}
```

```glsl [field-property box="nca_gpu" name="fragment" path="nca_gpu_fragment_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require
layout(location = 0) out vec4 uFragColor;

// The simulation's two grids, one after the other: four vec4s per cell, the first being red,
// green, blue, alpha. `shown` picks the grid the last step wrote.
layout(std430, buffer_reference) buffer NcaCells {
    vec4 v[];
};

layout(push_constant) uniform pcs {
    NcaCells state;
    uint shown;
    vec2 viewport;
    uint gridSize;
} pc;

void main() {
    // The grid is drawn as the largest centred square that fits the window, one square per cell.
    float side = min(pc.viewport.x, pc.viewport.y);
    vec2 cell = (gl_FragCoord.xy - 0.5 * (pc.viewport - side)) / side * float(pc.gridSize);
    if (any(lessThan(cell, vec2(0))) || any(greaterThanEqual(cell, vec2(pc.gridSize)))) {
        uFragColor = vec4(0.12, 0.12, 0.12, 1);
        return;
    }
    ivec2 c = ivec2(cell);
    vec4 rgba = clamp(pc.state.v[int(pc.shown * pc.gridSize * pc.gridSize * 4u) + (c.y * int(pc.gridSize) + c.x) * 4], 0.0, 1.0);
    // Colours are premultiplied, so this is "over white", the way the article shows them. The window
    // is sRGB, so the colour is linearised here and comes out as the CPU picture's bytes.
    uFragColor = vec4(pow(clamp(1.0 - rgba.a + rgba.rgb, 0.0, 1.0), vec3(2.2)), 1.0);
}
```

```kotlin [field-property box="nca_gpu" name="code" path="nca_gpu_code_.kt"]
/*
A growing neural cellular automaton on the GPU. Run this box beneath the renderer (`window`) box.

Every cell of a 96 x 96 grid holds 16 numbers and runs the same small network twice a frame: the
`compute` shader (open it with the property switcher) is GrowingNca.computeShader(), whose network
part MlpWeights.glsl wrote. The `vertex` and `fragment` shaders draw the first four numbers, the
cell's colour. Hold the left mouse button over the window to erase cells and watch them grow back.
*/
val nca = GrowingNca.pretrained("flower", size = 96)
val n = nca.size

// Two whole grids in one buffer, four vec4s (16 floats) per cell: each step reads one grid and
// writes the other, and `parity` says which is which.
val cells = defaultBuffer { size = (2 * n * n * 4).vec4s }.buffer
// The network's 8,336 weights, in MlpWeights' layout, which is what the shader's ncaNet reads.
val weights = defaultBuffer { size = ((nca.mlp.flat.size + 3) / 4).vec4s }.buffer
weights.write { put(nca.mlp.flat) }
// Both grids start as the CPU grid: empty except one seed cell in the middle.
cells.write {
    put(nca.state)
    put(nca.state)
}

var step = 0
var brush = vec(0, 0, 0, 0)
// set to false to leave the cutting to the mouse alone
var autoCut = true

/*
Two steps a frame: `forward` reads grid 0 and writes grid 1, `back` reads grid 1 and writes grid 0,
with a barrier between them so the second sees what the first wrote.
*/
val sim = computeModuleHere {}
fun stepMesh(parity: Int): ComputeMesh {
    val m = ComputeMesh(renderer, sim)
    m.cells = { cells }
    m.nw = { weights }
    m.size = { n }
    m.parity = { parity }
    // `seed` gives a fresh random update mask every step. The bindings only read: the
    // compute tasks below advance `step` and set and clear `brush`.
    m.seed = { step }
    m.brush = { brush }
    m.x = (n + 7) / 8
    m.y = (n + 7) / 8
    m.z = 1
    return m
}
/*
The window draws as fast as it can, often thousands of frames a second, which would grow and heal
the flower faster than you can see. So the pair of steps runs only when the clock says it is due:
`stepsPerSecond` of them a second, whatever the frame rate.
*/
var stepsPerSecond = 120.0
val started = System.nanoTime()
var due = false
val forward = stepMesh(0)
val back = stepMesh(1)
sim +=
    "forward" to
        computeTask { inside, cmd, push ->
            due = step < (System.nanoTime() - started) / 1e9 * stepsPerSecond
            if (due) {
                step++
                // with no mouse about, cut a disc out of the flower every 600 steps (5 s)
                if (autoCut && step % 600 == 401)
                    brush = vec(n / 2 + (Math.random() - 0.5) * 16, n / 2 - 4 + (Math.random() - 0.5) * 16, 9.0, 1.0)
                forward.compute(inside, cmd, push)
            }
        }
sim += "between" to computeBarrier()
sim +=
    "back" to
        computeTask { inside, cmd, push ->
            if (due) {
                step++
                back.compute(inside, cmd, push)
                // the cut (or the mouse's disc) has been applied to both steps
                brush = vec(0, 0, 0, 0)
            }
        }
sim += "publish" to computeBarrier(graphics = true)
renderer += "nca-sim" to sim

// The draw pass reads grid 0, which `back` wrote last.
val draw = moduleHere()
val quad = fullscreenTriangle(draw)
draw += "quad" to quad
renderer += "nca-draw" to draw
quad.state = { cells }
quad.shown = { 0 }
quad.viewport = { vec(window.width.toFloat(), window.height.toFloat()) }
quad.gridSize = { n }

// The mouse erases a disc of cells 6 wide, mapped through the same centred square the shader draws.
WindowBrush.install(window, "growing-nca") { fx, fy ->
    val w = window.width.toDouble()
    val h = window.height.toDouble()
    val side = minOf(w, h)
    brush = vec((fx * w - (w - side) / 2) / side * n, (fy * h - (h - side) / 2) / side * n, 6.0, 1.0)
}
```

## 4. growing nca — grow and regrow

<!-- field-box id="nca_strip" index="3" -->

```edn [field-box id="nca_strip" role="properties"]
{"id" "nca_strip" "index" 3 "name" "growing nca — grow and regrow" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "growing nca — grow and regrow" "_tombstone" false "frame" #afield/rect [40.0 200.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "nca_strip_code_.kt" "lines" 40 "characters" 1201 "sha256" "2f7638380c2e4ef983ecbd66079c01da235f194ecb23fce8174db6ddd4deb1da"}]}
```

```kotlin [field-property box="nca_strip" name="code" path="nca_strip_code_.kt"]
import afield.media.image.util.PngEncoder

/*
Grow a flower from one cell, cut a hole in it, and let it grow back: seven snapshots side by side.
Runs on the CPU (no renderer needed); the rule is the one the GPU box below runs.
*/
val nca = GrowingNca.pretrained("flower")
val target = GrowingNca.pretrainedTarget("flower")
val scale = 4
val w = nca.size * scale
val shots = mutableListOf<IntArray>()

fun snap(label: String) {
    shots += nca.argb(scale)
    println("$label: step ${nca.steps}, error ${nca.loss(target).f4}")
}

for (s in [30, 80, 300]) {
    nca.step(s - nca.steps)
    snap("growing")
}
// erase a disc 24 cells across through the top of the flower
nca.damage(32.0, 26.0, 12.0)
snap("damaged")
for (s in [340, 400, 600]) {
    nca.step(s - nca.steps)
    snap("regrowing")
}

// lay the snapshots out in a row, with a 4-pixel grey gap between them
val gap = 4
val stripW = shots.size * (w + gap) - gap
val strip = IntArray(stripW * w) { 0xffdddddd.toInt() }
for ((k, shot) in shots.withIndex()) {
    for (y in 0 until w) {
        val at = y * stripW + k * (w + gap)
        System.arraycopy(shot, y * w, strip, at, w)
    }
}
display("main", PngEncoder.encode(strip, stripW, w))
```

