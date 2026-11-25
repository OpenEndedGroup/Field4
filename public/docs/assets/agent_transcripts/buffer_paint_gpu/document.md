# Field Document: buffer_paint_gpu

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "watchdog" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="bpg_renderer" index="0" -->

```edn [field-box id="bpg_renderer" role="properties"]
{"id" "bpg_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["bpg_sim"] "properties" {"children" #{#afield/box "bpg_sim"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "bpg_renderer_code_.kt" "lines" 12 "characters" 264 "sha256" "4c83f494b0aca0e17c76bf11d825d1ef8383df8b43bf86a7bce1eba1e0d6ea5a"}]}
```

```kotlin [field-property box="bpg_renderer" name="code" path="bpg_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
once.renderer {
    window = Window("buffer paint")
    Renderer(window)
}
println("renderer ready")
start(renderer::run)
```

## 2. Gray-Scott on the GPU, paintable

<!-- field-box id="bpg_sim" index="1" -->

```edn [field-box id="bpg_sim" role="properties"]
{"id" "bpg_sim" "index" 1 "name" "Gray-Scott on the GPU, paintable" "parents" ["root" "bpg_renderer"] "children" ["bpg_draw"] "properties" {"children" #{#afield/box "bpg_draw"} "parents" #{#afield/box "root" #afield/box "bpg_renderer"} "name" "Gray-Scott on the GPU, paintable" "_tombstone" false "frame" #afield/rect [40.0 200.0 340.0 100.0]} "blockProperties" [{"name" "compute" "language" "glsl" "path" "bpg_sim_compute_.glsl" "lines" 34 "characters" 1128 "sha256" "e324a6cb0095446f1193cd39fd233b95320b8eeb2ef6d91cf8ae7118fa4eeb2b"} {"name" "code" "language" "kotlin" "path" "bpg_sim_code_.kt" "lines" 158 "characters" 5980 "sha256" "9081b463c259bd70eef6ce364ac5c1d163f67df4ae72d75837e0c8a29f2d15e6"}]}
```

```glsl [field-property box="bpg_sim" name="compute" path="bpg_sim_compute_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require
layout(local_size_x = 8, local_size_y = 8) in;

// One vec2 per cell, row-major: .x is u (the feed chemical), .y is v (the one that patterns).
layout(std430, buffer_reference) buffer State {
    vec2 a[];
};

layout(push_constant) uniform dat {
    State state; // written this frame
    State prevState; // read this frame
    int w;
    int h;
    int run; // 0 while the CPU is editing: the shader then touches neither buffer
    float feed;
    float kill;
} pc;

vec2 at(ivec2 p) {
    p = (p + ivec2(pc.w, pc.h)) % ivec2(pc.w, pc.h); // the grid wraps
    return pc.prevState.a[p.y * pc.w + p.x];
}

void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= pc.w || p.y >= pc.h || pc.run == 0) return;
    vec2 c = at(p);
    vec2 lap = at(p + ivec2(1, 0)) + at(p - ivec2(1, 0)) + at(p + ivec2(0, 1)) + at(p - ivec2(0, 1)) - 4.0 * c;
    float uvv = c.x * c.y * c.y;
    float u = c.x + 0.2 * lap.x - uvv + pc.feed * (1.0 - c.x);
    float v = c.y + 0.1 * lap.y + uvv - (pc.feed + pc.kill) * c.y;
    pc.state.a[p.y * pc.w + p.x] = vec2(u, v);
}
```

```kotlin [field-property box="bpg_sim" name="code" path="bpg_sim_code_.kt"]
import afield.media.image.*

var simModule = computeModuleHere {}
var sim = ComputeMesh()
simModule += "step" to sim
renderer += "buffer-paint-gs" to simModule

val W = 256
val H = 256

// Host-visible, one buffer each (not one per swapchain image): the CPU maps exactly the
// memory the shader reads.
var stateA = defaultBuffer { size = (W * H).vec2s }.buffer
var stateB = defaultBuffer { size = (W * H).vec2s }.buffer

// The canvas view of a mapped buffer: two floats per cell, wrapping like the shader's grid.
fun canvasOver(memory: java.nio.FloatBuffer) =
    bufferCanvas(memory, width = W, channels = 2, height = H, wrapX = true, wrapY = true)

// Both halves start as u = 1, v = 0 with one seed, because the shader reads either.
for (target in [stateA, stateB]) {
    target.mapAndWrite {
        val c = canvasOver(this)
        c.fill(c.bounds, floatArrayOf(1f, 0f))
        c.paint(128.0, 128.0, radius = 6.0, value = floatArrayOf(0.5f, 0.25f), hardness = 1.0)
    }
}

/*
Editing the live state. An edit is queued; the loop below sets `hold`, which makes the
shader return before writing, waits until every frame that might still be writing has
finished, maps buffer A, runs the edits on a BufferCanvas over that mapping, copies A into
B, and lets the simulation go again. The next dispatch evolves from the edited cells.
*/
var hold = false
var heldAt = 0L
val pending = mutableListOf<BufferCanvas.() -> Unit>()
fun edit(op: BufferCanvas.() -> Unit) {
    pending += op
}

sim.state = { if (renderer.frameCounter % 2L == 0L) stateA else stateB }
sim.prevState = { if (renderer.frameCounter % 2L == 0L) stateB else stateA }
sim.w = { W }
sim.h = { H }
sim.run = { if (hold) 0 else 1 }
sim.feed = { 0.037f }
sim.kill = { 0.06f }
sim.x = W / 8
sim.y = H / 8
sim.z = 1

prop<FrameBufferGroup>(bpStateA) = stateA
prop<FrameBufferGroup>(bpStateB) = stateB
prop<Int>(bpWidth) = W
prop<Int>(bpHeight) = H

/*
The drawing layer. A frame on the canvas stands for the buffer, two world units per cell,
and a space maps it to cell coordinates. Gestures become edits.
*/
val pad = 2.0 * W
notation {
    frame("gpu buffer", frame.x + frame.w + 460, frame.y, pad, 2.0 * H)
}
val cells = notation.space("gpu buffer", x = 0.0..W.toDouble(), y = 0.0..H.toDouble())
fun cellRect(m: Mark): CellRect {
    val a = cells.toSpace(vec(m.bounds.x, m.bounds.y))
    val b = cells.toSpace(vec(m.bounds.x + m.bounds.w, m.bounds.y + m.bounds.h))
    return CellRect(a.x.toInt(), a.y.toInt(), maxOf(1, (b.x - a.x).toInt()), maxOf(1, (b.y - a.y).toInt()))
}
val log = mutableListOf<String>()
fun note(s: String) {
    log += s
    markdown("gestures", log.takeLast(8).joinToString("\n\n"))
}

// A freehand stroke paints: v = 0.25 under the brush, which the reaction grows.
notation.onStroke { s ->
    val path = s.points.map { cells.toSpace(it).let { p -> p.x.toDouble() to p.y.toDouble() } }
    edit { stroke(path, radius = 3.0, value = floatArrayOf(0.5f, 0.25f), opacity = 0.8) }
    note("stroke: painted ${path.size} points")
}
// A rectangle blurs what is under it, an ellipse erases it back to u = 1, v = 0.
val handled = mutableSetOf<String>()
notation.onChange { e ->
    for (m in e.added + e.updated) {
        if (m.id in handled || m.box != null) continue
        val r = cellRect(m)
        if (r.w < 3 || r.h < 3) continue
        when (m.kind) {
            MarkType.RECTANGLE -> {
                handled += m.id
                edit { blur(r, sigma = 3.0) }
                note("rectangle: blurred $r")
            }
            MarkType.ELLIPSE -> {
                handled += m.id
                edit { fill(r, floatArrayOf(1f, 0f)) }
                note("ellipse: erased $r")
            }
            else -> {}
        }
    }
}
// An arrow from inside a rectangle copies that rectangle to the arrow's end.
notation.onArrow { a ->
    val start = a.start ?: return@onArrow
    val end = a.end ?: return@onArrow
    val source = notation.marks.firstOrNull {
        it.kind == MarkType.RECTANGLE &&
            start.x in it.bounds.x..(it.bounds.x + it.bounds.w) &&
            start.y in it.bounds.y..(it.bounds.y + it.bounds.h)
    } ?: return@onArrow
    val r = cellRect(source)
    val to = cells.toSpace(end)
    edit { clone(r, to.x.toInt() - r.w / 2, to.y.toInt() - r.h / 2) }
    note("arrow: copied $r to (${to.x.toInt()}, ${to.y.toInt()})")
}

// Edits from code go through the same queue: two seeds that will meet.
edit { paint(60.0, 60.0, radius = 5.0, value = floatArrayOf(0.5f, 0.25f), hardness = 1.0) }
edit { paint(200.0, 190.0, radius = 5.0, value = floatArrayOf(0.5f, 0.25f), hardness = 1.0) }

val inFlight = renderer.numImages + 2L
val shown = FloatArray(W * H * 2)
var nextShow = 0L
frames {
    while (frame()) {
        val f = renderer.frameCounter
        if (pending.isNotEmpty() && !hold) {
            hold = true
            heldAt = f
        }
        if (hold && f >= heldAt + inFlight) {
            val ops = pending.toList()
            pending.clear()
            stateA.mapAndWrite {
                val c = canvasOver(this)
                ops.forEach { c.it() }
                get(0, shown, 0, shown.size)
            }
            stateB.mapAndWrite { put(0, shown, 0, shown.size) }
            hold = false
        }
        if (!hold && f >= nextShow) {
            nextShow = f + 120
            // The half the last completed frame wrote; at worst a frame or two stale.
            val latest = if ((f - 1) % 2L == 0L) stateA else stateB
            latest.read { get(0, shown, 0, shown.size) }
            val view = bufferCanvas(shown, width = W, channels = 2)
            view.display = intArrayOf(1, 1, 1) // show v, the second float of each cell
            view.map = ValueMap.Linear(0.0, 0.5)
            val row60 = (48..72 step 4).joinToString(" ") { x -> view[x, 60, 1].f3 }
            display("state", view.toHTML() + "<pre>renderer frame $f\nv along row 60, x = 48..72 step 4: $row60</pre>")
        }
    }
}
```

## 3. draw the buffer, 1 cell = 1 pixel

<!-- field-box id="bpg_draw" index="2" -->

```edn [field-box id="bpg_draw" role="properties"]
{"id" "bpg_draw" "index" 2 "name" "draw the buffer, 1 cell = 1 pixel" "parents" ["root" "bpg_sim"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "bpg_sim"} "name" "draw the buffer, 1 cell = 1 pixel" "_tombstone" false "frame" #afield/rect [440.0 200.0 300.0 100.0]} "blockProperties" [{"name" "vertex" "language" "glsl" "path" "bpg_draw_vertex_.glsl" "lines" 12 "characters" 339 "sha256" "fa8b34a0fd5d23cb8b092ff1b8d0f95ceb03612f45fa7b06e485ed11da365bcc"} {"name" "fragment" "language" "glsl" "path" "bpg_draw_fragment_.glsl" "lines" 33 "characters" 1099 "sha256" "a7cec652eee65401135a20f3364389443d5ea5ab222b15a689d79c8049d782af"} {"name" "code" "language" "kotlin" "path" "bpg_draw_code_.kt" "lines" 11 "characters" 423 "sha256" "3a140b1cf701ac87dc071bce55d3d514d1194e95f6165de7b185e8228bd1a25a"}]}
```

```glsl [field-property box="bpg_draw" name="vertex" path="bpg_draw_vertex_.glsl"]
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

```glsl [field-property box="bpg_draw" name="fragment" path="bpg_draw_fragment_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require

layout(std430, buffer_reference) buffer State {
    vec2 a[];
};

layout(push_constant) uniform Push {
    State state;
    int w;
    int h;
    int zoom; // whole framebuffer pixels per cell
} pc;

layout(location = 0) out vec4 outColor;

// The swapchain is sRGB, so it encodes what this shader writes. Writing the decoded value
// makes the stored byte the map's own value, m * 255, with no transfer curve on top.
float srgbToLinear(float c) {
    return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4);
}

void main() {
    ivec2 cell = ivec2(gl_FragCoord.xy) / max(pc.zoom, 1);
    if (cell.x >= pc.w || cell.y >= pc.h) {
        discard; // outside the buffer: the renderer's clear colour shows
    }
    // v over 0..0.5, the same explicit map as the box's readback view; marked, not clamped
    float m = pc.state.a[cell.y * pc.w + cell.x].y / 0.5;
    if (m > 1.0) outColor = vec4(1.0, 0.19, 0.13, 1.0);
    else if (m < 0.0) outColor = vec4(0.13, 0.31, 1.0, 1.0);
    else outColor = vec4(vec3(srgbToLinear(m)), 1.0);
}
```

```kotlin [field-property box="bpg_draw" name="code" path="bpg_draw_code_.kt"]
var drawModule = moduleHere { primitiveType = triangles }
renderer += "buffer-paint-draw" to drawModule
val quad = fullscreenTriangle(drawModule)
drawModule += "quad" to quad

// The half the simulation is writing this frame, by the same parity it uses.
quad.state = { if (renderer.frameCounter % 2L == 0L) bpStateA else bpStateB }
quad.w = { bpWidth }
quad.h = { bpHeight }
quad.zoom = { 1 }
println("drawing the buffer")
```

