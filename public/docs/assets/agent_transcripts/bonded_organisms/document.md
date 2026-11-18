# Field Document: bonded_organisms

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="bonded_renderer" index="0" -->

```edn [field-box id="bonded_renderer" role="properties"]
{"id" "bonded_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["bonded_world"] "properties" {"children" #{#afield/box "bonded_world"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "bonded_renderer_code_.kt" "lines" 13 "characters" 443 "sha256" "7daceac6185b5129daede12d275199318de60655b5ca640e0ca82138348abd21"}]}
```

```kotlin [field-property box="bonded_renderer" name="code" path="bonded_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
// once: re-running this box keeps the existing window and renderer
once.renderer {
    window = Window("Bonded organisms", initialDimensions = Rect(80.0, 80.0, 960.0, 600.0))
    Renderer(window)
}
// the frame loop runs as this box's coroutine; stopping the box stops rendering
start(renderer::run)
```

## 2. organisms

<!-- field-box id="bonded_world" index="1" -->

### Narrative

From `code`

Each genome is a body, one character per segment: M muscle, L and R muscles that also
curve the body left or right (so it swims in circles), S stiff, W weak (tears first).
Edit them and re-run this box: the world is rebuilt and uploaded again.

```edn [field-box id="bonded_world" role="properties"]
{"id" "bonded_world" "index" 1 "name" "organisms" "parents" ["root" "bonded_renderer"] "children" ["bonded_draw" "bonded_check"] "properties" {"children" #{#afield/box "bonded_draw" #afield/box "bonded_check"} "parents" #{#afield/box "root" #afield/box "bonded_renderer"} "name" "organisms" "_tombstone" false "frame" #afield/rect [360.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "bonded_world_code_.kt" "lines" 77 "characters" 2700 "sha256" "6760a967e389ffb9df70f81bdc4a2ff2c12087ff35ae54002224ea6c8958fd65"} {"name" "compute" "language" "glsl" "path" "bonded_world_compute_.glsl" "lines" 104 "characters" 3679 "sha256" "1a53e2c32438d2883a33cf53e1ef007b9ec7504f70b3f007007bca9e9403ceea"}]}
```

```kotlin [field-property box="bonded_world" name="code" path="bonded_world_code_.kt"]
/*
Each genome is a body, one character per segment: M muscle, L and R muscles that also
curve the body left or right (so it swims in circles), S stiff, W weak (tears first).
Edit them and re-run this box: the world is rebuilt and uploaded again.
*/
val genomes = ["SMMMMMMMMMMS", "SLLLLLLLLLS", "SMMMMMMMMMMMMMMS", "SRRRRRRRRS"]

val makeWorld = {
    BondedWorld(1400).apply {
        params.wallX = 30f
        params.wallY = 18f
        // where each one starts and the way it heads (radians): the straight worms cross the
        // world, the L and R worms turn
        val starts = [
            Triple(-22f, -12f, 0f),
            Triple(-16f, 8f, 0f),
            Triple(21f, 8f, PI.toFloat()),
            Triple(16f, -2f, PI.toFloat()),
        ]
        genomes.forEachIndexed { k, g ->
            val (sx, sy, heading) = starts[k]
            grow(g, sx, sy, angle = heading)
        }
        scatter(500, -29f, -17f, 29f, 17f)
    }
}
val world = makeWorld()
prop<BondedWorld>(bonded) = world
prop<() -> BondedWorld>(bondedFactory) = makeWorld

var module = computeModuleHere {}
var m = ComputeMesh()
module += "mainMesh" to m
// several steps a frame, each reading what the last one wrote: raise it to speed the worms up
val clock = BondedClock()
clock.stepsPerFrame = 2
renderer.computeList += "bondedStep" to clock.stepping(module)

// two halves of cells (the shader reads one and writes the other) and the bond slots
var cells = defaultBuffer { size = (world.capacity * 4).vec4s }.buffer
var bonds = defaultBuffer { size = (world.capacity * 6).vec4s }.buffer
val upload = {
    cells.write { put(world.cellFloats()) }
    bonds.write { put(world.bondFloats()) }
    clock.restart()
}
upload()
prop<FrameBufferGroup>(bondedCells) = cells
prop<FrameBufferGroup>(bondedBonds) = bonds
prop<BondedClock>(bondedClock) = clock
prop<() -> Unit>(bondedUpload) = upload

// push constants, in the order the compute tab declares them
val p = world.params
m.cells = { cells }
m.bonds = { bonds }
m.tick = { clock.tick(renderer.frameCounter) }
m.count = { world.count }
m.capacity = { world.capacity }
m.dt = { p.dt }
m.stiffness = { p.stiffness }
m.bondDamping = { p.bondDamping }
m.dragNormal = { p.dragNormal }
m.dragTangent = { p.dragTangent }
m.omega = { p.omega }
m.repulsionRadius = { p.repulsionRadius }
m.repulsionStiffness = { p.repulsionStiffness }
m.energyDiffusion = { p.energyDiffusion }
m.muscleCost = { p.muscleCost }
m.fullEnergy = { p.fullEnergy }
m.wallX = { p.wallX }
m.wallY = { p.wallY }
m.wallStiffness = { p.wallStiffness }
m.x = (world.capacity + 63) / 64
m.y = 1
m.z = 1
println("${world.organisms.size} organisms, ${world.count} cells, ${world.bondCount()} bonds")
```

```glsl [field-property box="bonded_world" name="compute" path="bonded_world_compute_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require
layout(local_size_x = 64) in;

// BondedWorld.step, one invocation per cell. Every bond is stored at both its ends,
// so a cell only ever writes itself.
struct Cell {
    vec4 pv; // world position xy, velocity zw
    vec4 e; // energy, cell kind, organism id, padding
}; // (x, y, vx, vy), (energy, kind, owner, 0)
struct Bonds {
    vec4 partner;
    vec4 rest;
    vec4 amp;
    vec4 phase;
    vec4 limit;
    vec4 axis;
};
layout(std430, buffer_reference) buffer CellBuf {
    Cell c[];
};
layout(std430, buffer_reference) buffer BondBuf {
    Bonds b[];
};

layout(push_constant) uniform P {
    CellBuf cells; // two capacity-sized halves; step parity selects the source
    BondBuf bonds; // four neighbour slots per cell; -1 marks an empty slot
    float tick; // the step number; -1 freezes the world
    uint count;
    uint capacity; // stride between the two cell halves
    float dt; // simulated seconds per step
    float stiffness;
    float bondDamping;
    float dragNormal;
    float dragTangent;
    float omega;
    float repulsionRadius;
    float repulsionStiffness;
    float energyDiffusion;
    float muscleCost;
    float fullEnergy;
    float wallX;
    float wallY;
    float wallStiffness;
} pc;

void main() {
    uint i = gl_GlobalInvocationID.x;
    if (i >= pc.count || pc.tick < 0.0) return;
    uint k = uint(pc.tick);
    uint src = (k & 1u) * pc.capacity;
    uint dst = (1u - (k & 1u)) * pc.capacity;
    float t = pc.tick * pc.dt;
    Cell me = pc.cells.c[src + i];
    vec2 xi = me.pv.xy, vi = me.pv.zw;
    float ei = me.e.x;
    vec2 f = vec2(0);
    mat2 M = mat2(0);
    float nb = 0.0, de = 0.0, cost = 0.0;
    Bonds bd = pc.bonds.b[i];
    for (int s = 0; s < 4; s++) {
        int j = int(bd.partner[s]);
        if (j < 0) continue;
        Cell o = pc.cells.c[src + uint(j)];
        vec2 d = o.pv.xy - xi;
        float len = max(length(d), 1e-6);
        vec2 u = d / len;
        // a muscle's stroke shrinks as its two cells run out of energy
        float scale = clamp(0.5 * (ei + o.e.x) / pc.fullEnergy, 0.0, 1.0);
        float r = bd.rest[s] * (1.0 + bd.amp[s] * scale * sin(pc.omega * t + bd.phase[s]));
        // both ends see the same numbers, so both break the bond on the same step
        if (abs(len - r) / r > bd.limit[s]) {
            pc.bonds.b[i].partner[s] = -1.0;
            continue;
        }
        float rv = dot(o.pv.zw - vi, u);
        f += (pc.stiffness * (len - r) + pc.bondDamping * rv) * u;
        // rails set the drag axis; rungs and braces do not
        M += bd.axis[s] * outerProduct(u, u);
        nb += bd.axis[s];
        de += pc.energyDiffusion * (o.e.x - ei);
        cost += abs(bd.amp[s]) * scale;
    }
    float rr = pc.repulsionRadius;
    for (uint j = 0u; j < pc.count; j++) {
        if (j == i) continue;
        vec2 d = pc.cells.c[src + j].pv.xy - xi;
        float d2 = dot(d, d);
        if (d2 >= rr * rr || d2 < 1e-12) continue;
        float len = sqrt(d2);
        f += -pc.repulsionStiffness * (rr - len) * d / len;
    }
    if (pc.wallX > 0.0) {
        f.x -= pc.wallStiffness * (max(xi.x - pc.wallX, 0.0) + min(xi.x + pc.wallX, 0.0));
        f.y -= pc.wallStiffness * (max(xi.y - pc.wallY, 0.0) + min(xi.y + pc.wallY, 0.0));
    }
    // resistive-force drag: sideways to the bonds costs more than along them
    if (nb > 0.0) M /= nb;
    f += -pc.dragNormal * vi + (pc.dragNormal - pc.dragTangent) * (M * vi);
    vec2 nv = vi + f * pc.dt;
    pc.cells.c[dst + i].pv = vec4(xi + nv * pc.dt, nv);
    pc.cells.c[dst + i].e = vec4(max(ei + (de - pc.muscleCost * cost) * pc.dt, 0.0), me.e.yzw);
}
```

## 3. GPU against CPU

<!-- field-box id="bonded_check" index="2" -->

### Narrative

From `code`

Restarts the GPU world, lets it take exactly 600 steps, freezes it, reads the cells back
and compares them with a CPU BondedWorld built by the same factory and stepped 600 times.
Then lets it run on.

```edn [field-box id="bonded_check" role="properties"]
{"id" "bonded_check" "index" 2 "name" "GPU against CPU" "parents" ["root" "bonded_world"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "bonded_world"} "name" "GPU against CPU" "_tombstone" false "frame" #afield/rect [680.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "bonded_check_code_.kt" "lines" 32 "characters" 1113 "sha256" "8460304106799289f7c386b6bb294a4c1e2605c82d6f8ff5af8d61123df20206"}]}
```

```kotlin [field-property box="bonded_check" name="code" path="bonded_check_code_.kt"]
/*
Restarts the GPU world, lets it take exactly 600 steps, freezes it, reads the cells back
and compares them with a CPU BondedWorld built by the same factory and stepped 600 times.
Then lets it run on.
*/
val n = 600
val clock = bondedClock
clock.freezeAt = 0
frames {
    repeat(4) { frame() }
    bondedUpload()
    clock.freezeAt = n
    while (clock.step < n) frame()
    repeat(4) { frame() }
    val gpu = FloatArray(bonded.capacity * 16)
    bondedCells.read { get(gpu) }
    val cpu = bondedFactory()
    cpu.run(n)
    val half = clock.latestHalf * bonded.capacity
    var worst = 0.0
    var travel = 0.0
    val start = bondedFactory()
    for (i in 0 until cpu.count) {
        val o = (half + i) * 8
        worst = maxOf(worst, Math.hypot((gpu[o] - cpu.x[i]).toDouble(), (gpu[o + 1] - cpu.y[i]).toDouble()))
        travel = maxOf(travel, Math.hypot((cpu.x[i] - start.x[i]).toDouble(), (cpu.y[i] - start.y[i]).toDouble()))
    }
    println(
        "after $n steps: largest GPU-CPU distance $worst, largest distance a cell moved $travel, bonds ${cpu.bondCount()}",
    )
    clock.freezeAt = -1
}
```

## 4. draw

<!-- field-box id="bonded_draw" index="3" -->

### Narrative

From `code`

Cells as discs and bonds as thin quads, read straight from the GPU buffers.
30 vertices per cell: 6 for its disc, 6 for each of its four bond slots.

```edn [field-box id="bonded_draw" role="properties"]
{"id" "bonded_draw" "index" 3 "name" "draw" "parents" ["root" "bonded_world"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "bonded_world"} "name" "draw" "_tombstone" false "frame" #afield/rect [360.0 160.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "bonded_draw_code_.kt" "lines" 17 "characters" 557 "sha256" "c4318d0ab55b29765476d1dc0eab54767c55c70f9aa31ee5f08975137f8e3062"} {"name" "vertex" "language" "glsl" "path" "bonded_draw_vertex_.glsl" "lines" 76 "characters" 2540 "sha256" "0689234ab664ebdb86b2f010f225800c8684b3b31896ce047bd6694252cc912b"} {"name" "fragment" "language" "glsl" "path" "bonded_draw_fragment_.glsl" "lines" 15 "characters" 390 "sha256" "3f5b70f68e47e38ca487241956d6a2a1b20ca8355f123521b22281282cebe9cb"}]}
```

```kotlin [field-property box="bonded_draw" name="code" path="bonded_draw_code_.kt"]
// Cells as discs and bonds as thin quads, read straight from the GPU buffers.
// 30 vertices per cell: 6 for its disc, 6 for each of its four bond slots.
var module = moduleHere { primitiveType = triangles }
var m = Mesh()
module += "mainMesh" to m
renderer += "bondedDraw" to module

m.cells = { bondedCells }
m.bonds = { bondedBonds }
m.latest = { bondedClock.latestHalf }
m.capacity = { bonded.capacity }
m.viewX = { 0f }
m.viewY = { 0f }
m.viewHalfHeight = { 19f }
m.aspect = { window.width / window.height.toFloat() }
m.set(bonded.count * 30, 0) {}
m
```

```glsl [field-property box="bonded_draw" name="vertex" path="bonded_draw_vertex_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require
struct Cell {
    vec4 pv; // world position xy, velocity zw
    vec4 e; // energy, cell kind, organism id, padding
};
struct Bonds {
    vec4 partner;
    vec4 rest;
    vec4 amp;
    vec4 phase;
    vec4 limit;
    vec4 axis;
};
layout(std430, buffer_reference) buffer CellBuf {
    Cell c[];
};
layout(std430, buffer_reference) buffer BondBuf {
    Bonds b[];
};
layout(push_constant) uniform P {
    CellBuf cells; // two capacity-sized halves; step parity selects the source
    BondBuf bonds; // four neighbour slots per cell; -1 marks an empty slot
    uint latest; // most recently written cell half, 0 or 1
    uint capacity; // stride between the two cell halves
    float viewX;
    float viewY;
    float viewHalfHeight; // visible world units above and below the view centre
    float aspect;
} pc;
layout(location = 0) out vec2 tc;
layout(location = 1) out vec4 col;
layout(location = 2) flat out int isBond;

const vec2 corner[6] = vec2[](vec2(0, 0), vec2(1, 0), vec2(1, 1), vec2(0, 0), vec2(1, 1), vec2(0, 1));
vec3 hue(float h) {
    return clamp(abs(fract(h + vec3(0, 2, 1) / 3.0) * 6.0 - 3.0) - 1.0, 0.0, 1.0);
}

void main() {
    int ci = gl_VertexIndex / 30, r = gl_VertexIndex % 30;
    uint base = pc.latest * pc.capacity;
    Cell me = pc.cells.c[base + uint(ci)];
    vec2 q = corner[r % 6];
    float kind = me.e.y;
    // free matter is grey; each organism gets its own hue, muscles brightest
    vec3 c = kind > 2.5 ? vec3(0.32, 0.36, 0.42) : hue(me.e.z * 0.27 + 0.03);
    vec2 world;
    if (r < 6) {
        float size = kind > 2.5 ? 0.4 : 0.75;
        world = me.pv.xy + (q - 0.5) * size;
        isBond = 0;
        float bright = kind > 2.5 ? 0.7 : (kind > 0.5 ? 1.0 : 0.55) * (0.35 + 0.65 * clamp(me.e.x, 0.0, 1.0));
        col = vec4(c * bright, 1.0);
    }
    else {
        int s = (r - 6) / 6;
        int j = int(pc.bonds.b[ci].partner[s]);
        isBond = 1;
        tc = q;
        col = vec4(c * 0.5, 1.0);
        // each bond is drawn once, from its lower-numbered end
        if (j <= ci) {
            gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
            return;
        }
        vec2 a = me.pv.xy, b = pc.cells.c[base + uint(j)].pv.xy;
        vec2 d = b - a;
        vec2 n = normalize(vec2(-d.y, d.x)) * 0.07;
        world = a + d * q.x + n * (q.y * 2.0 - 1.0);
    }
    tc = q;
    vec2 ndc = (world - vec2(pc.viewX, pc.viewY)) / pc.viewHalfHeight;
    ndc.x /= pc.aspect;
    gl_Position = vec4(ndc.x, -ndc.y, 0.0, 1.0);
}
```

```glsl [field-property box="bonded_draw" name="fragment" path="bonded_draw_fragment_.glsl"]
#version 450
layout(location = 0) in vec2 tc;
layout(location = 1) in vec4 col;
layout(location = 2) flat in int isBond;
layout(location = 0) out vec4 outColor;
void main() {
    if (isBond == 1) {
        outColor = col;
        return;
    }
    float d = length(tc - vec2(0.5));
    float a = 1.0 - smoothstep(0.42, 0.5, d);
    if (a < 0.01) discard;
    outColor = vec4(col.rgb, a);
}
```

