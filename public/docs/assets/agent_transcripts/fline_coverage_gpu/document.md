# Field Document: fline_coverage_gpu

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="fcg_renderer" index="0" -->

```edn [field-box id="fcg_renderer" role="properties"]
{"id" "fcg_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["fcg_fill"] "properties" {"children" #{#afield/box "fcg_fill"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fcg_renderer_code_.kt" "lines" 12 "characters" 334 "sha256" "cfa7203341f4bb122f4e84d60d37840c2a7fbd98aaa372bf585fe03970361346"}]}
```

```kotlin [field-property box="fcg_renderer" name="code" path="fcg_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
once.renderer {
    window = Window("outlines filled into a buffer", initialDimensions = Rect(80.0, 80.0, 1000.0, 360.0))
    Renderer(window)
}
println("renderer ready")
start(renderer::run)
```

## 2. fill FLines into the mask buffer

<!-- field-box id="fcg_fill" index="1" -->

```edn [field-box id="fcg_fill" role="properties"]
{"id" "fcg_fill" "index" 1 "name" "fill FLines into the mask buffer" "parents" ["root" "fcg_renderer"] "children" ["fcg_draw"] "properties" {"children" #{#afield/box "fcg_draw"} "parents" #{#afield/box "root" #afield/box "fcg_renderer"} "name" "fill FLines into the mask buffer" "_tombstone" false "frame" #afield/rect [40.0 200.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fcg_fill_code_.kt" "lines" 100 "characters" 3520 "sha256" "d2b61c6212f6a64c35f60f5c43c26693423303cfd9c5bf3ec8006a8d3d27ce1f"}]}
```

```kotlin [field-property box="fcg_fill" name="code" path="fcg_fill_code_.kt"]
import afield.graphics.nile.FillRule
import afield.media.image.*

val W = 160
val H = 100

// Two floats per cell: .x is coverage under non-zero, .y under even-odd. Only the CPU writes it.
var mask = defaultBuffer { size = (W * H).vec2s }.buffer

// The shapes, in cell coordinates.
val pentagram = FLine()
for (k in 0..5) {
    val a = -Math.PI / 2 + k * 4 * Math.PI / 5
    val x = 30.0 + 26.0 * Math.cos(a)
    val y = 52.0 + 26.0 * Math.sin(a)
    if (k == 0) pentagram.moveTo(x, y) else pentagram.lineTo(x, y)
}
val circles = [FLine().circle(78.0, 40.0, 18.0), FLine().circle(94.0, 52.0, 18.0)]
// Outer and inner squares wound the same way: a hole under even-odd only.
val framed = FLine().rect(118.0, 22.0, 36.0, 36.0).rect(128.0, 32.0, 16.0, 16.0)
val wave = FLine().moveTo(64.0, 86.0)
wave.cubicTo(84.0, 56.0, 104.0, 116.0, 124.0, 86.0)
wave.cubicTo(134.0, 70.0, 108.0, 70.0, 116.0, 92.0)
val drawn = mutableListOf<FLine>()
var pending = false

// Draw everything into channel 0 under non-zero and channel 1 under even-odd.
fun paintMask(canvas: BufferCanvas) {
    canvas.fill(canvas.bounds, 0f)
    for ((channel, rule) in [0 to FillRule.NON_ZERO, 1 to FillRule.EVEN_ODD]) {
        canvas.fill(pentagram, 1f, rule, channel = channel)
        canvas.fill(circles, 1f, rule, channel = channel)
        canvas.fill(framed, 1f, rule, channel = channel)
        for (line in drawn) canvas.fill(line, 1f, rule, channel = channel)
        canvas.stroke(wave, width = 3.0, value = 1f, channel = channel)
    }
}
val expected = FloatArray(2 * W * H)
fun upload() {
    paintMask(bufferCanvas(expected, width = W, channels = 2, height = H))
    mask.mapAndWrite { paintMask(bufferCanvas(this, width = W, channels = 2, height = H)) }
}
upload()

prop<FrameBufferGroup>(fcMask) = mask
prop<Int>(fcWidth) = W
prop<Int>(fcHeight) = H

// The drawing layer: a frame stands for the grid, two world units per cell.
notation {
    frame("mask", frame.x + frame.w + 460, frame.y, 2.0 * W, 2.0 * H)
}
val cells = notation.space("mask", x = 0.0..W.toDouble(), y = 0.0..H.toDouble())

// A freehand stroke becomes one more closed outline; fill closes it.
notation.onStroke { s ->
    val pts = s.points.map { cells.toSpace(it) }
    if (pts.size < 3) return@onStroke
    val line = FLine()
    line.moveTo(pts[0].x, pts[0].y)
    for (p in pts.drop(1)) line.lineTo(p.x, p.y)
    drawn += line
    pending = true
}
// An ellipse clears the hand-drawn outlines.
val handled = mutableSetOf<String>()
notation.onChange { e ->
    for (m in e.added + e.updated) {
        if (m.id in handled || m.kind != MarkType.ELLIPSE) continue
        handled += m.id
        drawn.clear()
        pending = true
    }
}

val shown = FloatArray(2 * W * H)
var nextShow = 0L
frames {
    while (frame()) {
        val f = renderer.frameCounter
        if (pending) {
            pending = false
            upload()
        }
        if (f >= nextShow) {
            nextShow = f + 240
            mask.read { get(0, shown, 0, shown.size) }
            var worst = 0f
            for (i in shown.indices) worst = maxOf(worst, Math.abs(shown[i] - expected[i]))
            val view = bufferCanvas(shown, width = W, channels = 2, height = H)
            view.display = intArrayOf(0, 1, 1)
            view.zoom = 2
            display(
                "mask",
                view.toHTML() +
                    "<pre>red: non-zero, cyan: even-odd, renderer frame $f\nmax |mask buffer - CPU coverage| = $worst</pre>",
            )
        }
    }
}
```

## 3. draw the mask, non-zero left, even-odd right

<!-- field-box id="fcg_draw" index="2" -->

```edn [field-box id="fcg_draw" role="properties"]
{"id" "fcg_draw" "index" 2 "name" "draw the mask, non-zero left, even-odd right" "parents" ["root" "fcg_fill"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "fcg_fill"} "name" "draw the mask, non-zero left, even-odd right" "_tombstone" false "frame" #afield/rect [440.0 200.0 300.0 100.0]} "blockProperties" [{"name" "vertex" "language" "glsl" "path" "fcg_draw_vertex_.glsl" "lines" 12 "characters" 339 "sha256" "fa8b34a0fd5d23cb8b092ff1b8d0f95ceb03612f45fa7b06e485ed11da365bcc"} {"name" "fragment" "language" "glsl" "path" "fcg_draw_fragment_.glsl" "lines" 34 "characters" 1086 "sha256" "2a34ef530a8ee46b910970d6d6fcc7223a03a3b5efea28b3c08811b3b3460562"} {"name" "code" "language" "kotlin" "path" "fcg_draw_code_.kt" "lines" 11 "characters" 430 "sha256" "9eb686573dd002db7a6e54087bbb570d01a95e082a5887fb4a32008aa4aa616f"}]}
```

```glsl [field-property box="fcg_draw" name="vertex" path="fcg_draw_vertex_.glsl"]
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

```glsl [field-property box="fcg_draw" name="fragment" path="fcg_draw_fragment_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require

// Two floats per cell: coverage under non-zero, then under even-odd.
layout(std430, buffer_reference) buffer Mask {
    vec2 a[];
};

layout(push_constant) uniform Push {
    Mask mask;
    int w;
    int h;
    int zoom;
} pc;

layout(location = 0) out vec4 outColor;

float srgbToLinear(float c) {
    return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4);
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy) / max(pc.zoom, 1);
    // the left copy reads channel 0, the right copy channel 1, with a one-cell gap
    int rule = p.x >= pc.w + 1 ? 1 : 0;
    ivec2 cell = ivec2(p.x - rule * (pc.w + 1), p.y);
    if (cell.x < 0 || cell.x >= pc.w || cell.y >= pc.h) discard;
    vec2 m = pc.mask.a[cell.y * pc.w + cell.x];
    float c = clamp(rule == 0 ? m.x : m.y, 0.0, 1.0);
    vec3 back = vec3(0.06, 0.07, 0.11);
    vec3 ink = rule == 0 ? vec3(1.0, 0.62, 0.25) : vec3(0.35, 0.85, 1.0);
    vec3 col = mix(back, ink, c);
    outColor = vec4(srgbToLinear(col.r), srgbToLinear(col.g), srgbToLinear(col.b), 1.0);
}
```

```kotlin [field-property box="fcg_draw" name="code" path="fcg_draw_code_.kt"]
var drawModule = moduleHere { primitiveType = triangles }
renderer += "fline-coverage-draw" to drawModule
val quad = fullscreenTriangle(drawModule)
drawModule += "quad" to quad

quad.mask = { fcMask }
quad.w = { fcWidth }
quad.h = { fcHeight }
// as many window pixels per cell as fit both copies side by side
quad.zoom = { maxOf(1, minOf(window.width / (2 * fcWidth + 1), window.height / fcHeight)) }
println("drawing the mask")
```

