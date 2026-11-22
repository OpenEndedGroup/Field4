# Field Document: outline_field_gpu

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="ofg_renderer" index="0" -->

```edn [field-box id="ofg_renderer" role="properties"]
{"id" "ofg_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["ofg_sim"] "properties" {"children" #{#afield/box "ofg_sim"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ofg_renderer_code_.kt" "lines" 12 "characters" 271 "sha256" "4a712802a51931a985e5dba51855d833b354a088b51743d76f5afff53653f516"}]}
```

```kotlin [field-property box="ofg_renderer" name="code" path="ofg_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
once.renderer {
    window = Window("drawn outline field")
    Renderer(window)
}
println("renderer ready")
start(renderer::run)
```

## 2. Gray-Scott inside a drawn outline

<!-- field-box id="ofg_sim" index="1" -->

```edn [field-box id="ofg_sim" role="properties"]
{"id" "ofg_sim" "index" 1 "name" "Gray-Scott inside a drawn outline" "parents" ["root" "ofg_renderer"] "children" ["ofg_draw" "ofg_star"] "properties" {"children" #{#afield/box "ofg_draw" #afield/box "ofg_star"} "parents" #{#afield/box "root" #afield/box "ofg_renderer"} "name" "Gray-Scott inside a drawn outline" "_tombstone" false "frame" #afield/rect [40.0 200.0 340.0 100.0]} "blockProperties" [{"name" "compute" "language" "glsl" "path" "ofg_sim_compute_.glsl" "lines" 48 "characters" 1596 "sha256" "aa1dc3b2f3bfaee1665918b055cc1c59dcb3fc4d893849a5a7ed26375c36feb5"} {"name" "code" "language" "kotlin" "path" "ofg_sim_code_.kt" "lines" 116 "characters" 3857 "sha256" "4ce2131eb3c8c841f8557e49cd3f22e74b12eb5168a67670fbae6f40e79c11dd"}]}
```

```glsl [field-property box="ofg_sim" name="compute" path="ofg_sim_compute_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require
layout(local_size_x = 8, local_size_y = 8) in;

// One vec2 per cell, row-major: .x is u (the feed chemical), .y is v (the one that patterns).
layout(std430, buffer_reference) buffer State {
    vec2 a[];
};
// One float per cell: the signed distance to the drawn outline, in cells, negative inside.
layout(std430, buffer_reference) buffer Wall {
    float d[];
};

layout(push_constant) uniform dat {
    State state; // written this frame
    State prevState; // read this frame
    Wall wall;
    int w;
    int h;
    int run; // 0 while the CPU is editing the state
    float feed;
    float kill;
} pc;

vec2 at(ivec2 p) {
    p = clamp(p, ivec2(0), ivec2(pc.w - 1, pc.h - 1));
    return pc.prevState.a[p.y * pc.w + p.x];
}

void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= pc.w || p.y >= pc.h || pc.run == 0) return;
    int i = p.y * pc.w + p.x;
    float d = pc.wall.d[i];
    if (d > 0.0) {
        pc.state.a[i] = vec2(1.0, 0.0); // outside the drawing: held empty
        return;
    }
    // within a few cells of the wall the kill rate rises, so stripes run along the edge
    float edge = smoothstep(-6.0, 0.0, d);
    vec2 c = at(p);
    vec2 lap = at(p + ivec2(1, 0)) + at(p - ivec2(1, 0)) + at(p + ivec2(0, 1)) + at(p - ivec2(0, 1)) - 4.0 * c;
    float uvv = c.x * c.y * c.y;
    float kill = mix(pc.kill, pc.kill + 0.004, edge);
    float u = c.x + 0.2 * lap.x - uvv + pc.feed * (1.0 - c.x);
    float v = c.y + 0.1 * lap.y + uvv - (pc.feed + kill) * c.y;
    pc.state.a[i] = vec2(u, v);
}
```

```kotlin [field-property box="ofg_sim" name="code" path="ofg_sim_code_.kt"]
import afield.geometry.mesh.signedDistance2D
import afield.media.image.*

var simModule = computeModuleHere {}
var sim = ComputeMesh()
simModule += "step" to sim
renderer += "outline-field-gs" to simModule

val W = 256
val H = 256

var stateA = defaultBuffer { size = (W * H).vec2s }.buffer
var stateB = defaultBuffer { size = (W * H).vec2s }.buffer
// The field the shader reads: one float per cell, written by the CPU, never by the GPU.
var wall = defaultBuffer { size = (W * H).floats }.buffer

// The coded outline, in cell coordinates: a ring and a bar, one closed FLine each.
val ring = FLine().circle(96.0, 120.0, 70.0)
val bar = FLine().rect(150.0, 150.0, 90.0, 60.0)
val drawn = mutableListOf<FLine>()
var pendingWalls = false

// FLines -> exact signed distance -> one float per cell in the mapped wall buffer.
var field = listOf(ring, bar).signedDistance2D()
fun uploadWalls() {
    field = (listOf(ring, bar) + drawn).signedDistance2D()
    wall.mapAndWrite { bufferCanvas(this, width = W, height = H).sample(field, x0 = 0.0, y0 = 0.0, cell = 1.0) }
}
uploadWalls()

for (target in [stateA, stateB]) {
    target.mapAndWrite {
        val c = bufferCanvas(this, width = W, channels = 2, height = H)
        c.fill(c.bounds, floatArrayOf(1f, 0f))
        for ((x, y) in [96.0 to 120.0, 60.0 to 90.0, 200.0 to 180.0]) {
            c.paint(x, y, radius = 6.0, value = floatArrayOf(0.5f, 0.25f), hardness = 1.0)
        }
    }
}

sim.state = { if (renderer.frameCounter % 2L == 0L) stateA else stateB }
sim.prevState = { if (renderer.frameCounter % 2L == 0L) stateB else stateA }
sim.wall = { wall }
sim.w = { W }
sim.h = { H }
sim.run = { 1 }
sim.feed = { 0.037f }
sim.kill = { 0.06f }
sim.x = W / 8
sim.y = H / 8
sim.z = 1

prop<FrameBufferGroup>(ofStateA) = stateA
prop<FrameBufferGroup>(ofStateB) = stateB
prop<FrameBufferGroup>(ofWall) = wall
prop<Int>(ofWidth) = W
prop<Int>(ofHeight) = H
// Other boxes add outlines from code through this, the same path a drawn stroke takes.
prop<(FLine) -> Unit>(addOutline) = { line: FLine ->
    drawn += line
    pendingWalls = true
}

// The drawing layer: a frame stands for the grid, two world units per cell.
notation {
    frame("walls", frame.x + frame.w + 460, frame.y, 2.0 * W, 2.0 * H)
}
val cells = notation.space("walls", x = 0.0..W.toDouble(), y = 0.0..H.toDouble())

// A freehand stroke becomes one more closed outline (signedDistance2D closes it).
notation.onStroke { s ->
    val pts = s.points.map { cells.toSpace(it) }
    if (pts.size < 3) return@onStroke
    val line = FLine()
    line.moveTo(pts[0].x, pts[0].y)
    for (p in pts.drop(1)) line.lineTo(p.x, p.y)
    drawn += line
    pendingWalls = true
}
// An ellipse clears the hand-drawn outlines.
val handled = mutableSetOf<String>()
notation.onChange { e ->
    for (m in e.added + e.updated) {
        if (m.id in handled || m.kind != MarkType.ELLIPSE) continue
        handled += m.id
        drawn.clear()
        pendingWalls = true
    }
}

val shownWall = FloatArray(W * H)
var nextShow = 0L
frames {
    while (frame()) {
        val f = renderer.frameCounter
        if (pendingWalls) {
            pendingWalls = false
            uploadWalls()
        }
        if (f >= nextShow) {
            nextShow = f + 240
            wall.read { get(0, shownWall, 0, shownWall.size) }
            var worst = 0.0
            for (y in 0 until H) for (x in 0 until W) {
                worst = maxOf(worst, Math.abs(shownWall[y * W + x] - field(x + 0.5, y + 0.5)))
            }
            val view = bufferCanvas(shownWall, width = W)
            view.map = ValueMap.Signed(40.0)
            display(
                "field",
                view.toHTML() +
                    "<pre>outlines: ${2 + drawn.size}, renderer frame $f\nmax |wall buffer - CPU field| = ${worst.f3} cells</pre>",
            )
        }
    }
}
```

## 3. add a star outline from code

<!-- field-box id="ofg_star" index="2" -->

### Narrative

From `code`

A five-pointed star in cell coordinates, closed back to its first point. Running this
box rewrites the wall buffer while the simulation runs; the pattern grows into it.

```edn [field-box id="ofg_star" role="properties"]
{"id" "ofg_star" "index" 2 "name" "add a star outline from code" "parents" ["root" "ofg_sim"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "ofg_sim"} "name" "add a star outline from code" "_tombstone" false "frame" #afield/rect [440.0 340.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ofg_star_code_.kt" "lines" 12 "characters" 469 "sha256" "75e05381287b4ff0469209287547c79d526762f4133863ccca4bc7e7c450e709"}]}
```

```kotlin [field-property box="ofg_star" name="code" path="ofg_star_code_.kt"]
// A five-pointed star in cell coordinates, closed back to its first point. Running this
// box rewrites the wall buffer while the simulation runs; the pattern grows into it.
val star = FLine()
for (k in 0..10) {
    val a = Math.PI / 2 + k * Math.PI / 5
    val r = if (k % 2 == 0) 50.0 else 22.0
    val x = 70.0 + r * Math.cos(a)
    val y = 205.0 - r * Math.sin(a)
    if (k == 0) star.moveTo(x, y) else star.lineTo(x, y)
}
addOutline(star)
println("added a star")
```

## 4. draw state and walls, 1 cell = 2 pixels

<!-- field-box id="ofg_draw" index="3" -->

```edn [field-box id="ofg_draw" role="properties"]
{"id" "ofg_draw" "index" 3 "name" "draw state and walls, 1 cell = 2 pixels" "parents" ["root" "ofg_sim"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "ofg_sim"} "name" "draw state and walls, 1 cell = 2 pixels" "_tombstone" false "frame" #afield/rect [440.0 200.0 300.0 100.0]} "blockProperties" [{"name" "vertex" "language" "glsl" "path" "ofg_draw_vertex_.glsl" "lines" 12 "characters" 339 "sha256" "fa8b34a0fd5d23cb8b092ff1b8d0f95ceb03612f45fa7b06e485ed11da365bcc"} {"name" "fragment" "language" "glsl" "path" "ofg_draw_fragment_.glsl" "lines" 35 "characters" 991 "sha256" "2186d8f3eda7d2c076e3988487a370ec054774ecf6ca6da02a24fd0b884327a8"} {"name" "code" "language" "kotlin" "path" "ofg_draw_code_.kt" "lines" 11 "characters" 374 "sha256" "6a5f0c16e40dfd97c2232424a5a7ae2573a8290bab6b65dbadfad2cf01da3c99"}]}
```

```glsl [field-property box="ofg_draw" name="vertex" path="ofg_draw_vertex_.glsl"]
#version 450

// one triangle that covers the window; positions come from the vertex index
vec2 fullscreenVertex(uint index) {
    if (index == 0u) return vec2(-1.0, -1.0);
    if (index == 1u) return vec2(3.0, -1.0);
    return vec2(-1.0, 3.0);
}

void main() {
    gl_Position = vec4(fullscreenVertex(uint(gl_VertexIndex)), 0.0, 1.0);
}
```

```glsl [field-property box="ofg_draw" name="fragment" path="ofg_draw_fragment_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require

layout(std430, buffer_reference) buffer State {
    vec2 a[];
};
layout(std430, buffer_reference) buffer Wall {
    float d[];
};

layout(push_constant) uniform Push {
    State state;
    Wall wall;
    int w;
    int h;
    int zoom;
} pc;

layout(location = 0) out vec4 outColor;

float srgbToLinear(float c) {
    return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4);
}

void main() {
    ivec2 cell = ivec2(gl_FragCoord.xy) / max(pc.zoom, 1);
    if (cell.x >= pc.w || cell.y >= pc.h) discard;
    int i = cell.y * pc.w + cell.x;
    float v = clamp(pc.state.a[i].y / 0.5, 0.0, 1.0);
    float d = pc.wall.d[i];
    vec3 c = vec3(v);
    if (d > 0.0) c = mix(vec3(0.10, 0.12, 0.18), vec3(0.03), clamp(d / 30.0, 0.0, 1.0)); // outside: dark blue fading
    if (abs(d) < 0.75) c = vec3(1.0, 0.55, 0.1); // the outline itself, orange
    outColor = vec4(srgbToLinear(c.r), srgbToLinear(c.g), srgbToLinear(c.b), 1.0);
}
```

```kotlin [field-property box="ofg_draw" name="code" path="ofg_draw_code_.kt"]
var drawModule = moduleHere { primitiveType = triangles }
renderer += "outline-field-draw" to drawModule
val quad = fullscreenTriangle(drawModule)
drawModule += "quad" to quad

quad.state = { if (renderer.frameCounter % 2L == 0L) ofStateA else ofStateB }
quad.wall = { ofWall }
quad.w = { ofWidth }
quad.h = { ofHeight }
quad.zoom = { 2 }
println("drawing state and walls")
```

