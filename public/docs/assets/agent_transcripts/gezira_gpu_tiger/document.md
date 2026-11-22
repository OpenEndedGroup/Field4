# Field Document: gezira_gpu_tiger

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="ggt_renderer" index="0" -->

```edn [field-box id="ggt_renderer" role="properties"]
{"id" "ggt_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["ggt_tiger" "ggt_compare"] "properties" {"children" #{#afield/box "ggt_tiger" #afield/box "ggt_compare"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ggt_renderer_code_.kt" "lines" 13 "characters" 362 "sha256" "1638203dd7db11ca924a66126b565b5cf06924f260d6ea90a7d8b989d6730297"}]}
```

```kotlin [field-property box="ggt_renderer" name="code" path="ggt_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
Options.vsync = false
once.renderer {
    window = Window("the tiger, through Gezira on the GPU", initialDimensions = Rect(80.0, 80.0, 900.0, 900.0))
    Renderer(window)
}
println("renderer ready")
start(renderer::run)
```

## 2. tiger on the gpu

<!-- field-box id="ggt_tiger" index="1" -->

```edn [field-box id="ggt_tiger" role="properties"]
{"id" "ggt_tiger" "index" 1 "name" "tiger on the gpu" "parents" ["root" "ggt_renderer"] "children" ["ggt_draw"] "properties" {"children" #{#afield/box "ggt_draw"} "parents" #{#afield/box "root" #afield/box "ggt_renderer"} "name" "tiger on the gpu" "_tombstone" false "frame" #afield/rect [40.0 200.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ggt_tiger_code_.kt" "lines" 36 "characters" 1213 "sha256" "626982ad1642dc0c109cf1f23494e2357d96538116e30a5b4595185b0b22d206"}]}
```

```kotlin [field-property box="ggt_tiger" name="code" path="ggt_tiger_code_.kt"]
import afield.graphics.nile.*
import afield.graphics.vulkan.util.GpuProbe

val N = 900
val doc = GeziraSvg.parse(GeziraSvg.tiger())
// Every fill and every stroke outline, in paint order, uploaded once.
val paths = GeziraGpuScene.paths(doc.lines)
val fit = doc.fit(N, N)
val probe = GpuProbe(renderer, "gezira-gpu-tiger")
val scene = GeziraGpuScene(probe, paths, N, N, transform = fit)

prop<FrameBufferGroup>(tigerImage) = scene.image
prop<Int>(tigerSize) = N
println("${paths.size} paths, ${scene.curveCount} curves")

// Turn and breathe about the middle: only the six numbers of the transform change per frame.
var frames = 0
var last = System.nanoTime()
loop {
    val t = frames / 60.0
    val zoom = 1.0 + 0.35 * Math.sin(t * 0.7)
    val about =
        SvgTransform.translate(N / 2.0, N / 2.0) *
            SvgTransform.rotate(12.0 * Math.sin(t * 0.5)) *
            SvgTransform.scale(zoom) *
            SvgTransform.translate(-N / 2.0, -N / 2.0)
    scene.transform = about * fit
    frames++
    if (frames % 120 == 0) {
        val now = System.nanoTime()
        display("rate", "%.1f ms a frame".format((now - last) / 1e6 / 120))
        last = now
    }
    frame()
}
    .end { probe.close() }
```

## 3. gpu beside cpu

<!-- field-box id="ggt_compare" index="2" -->

```edn [field-box id="ggt_compare" role="properties"]
{"id" "ggt_compare" "index" 2 "name" "gpu beside cpu" "parents" ["root" "ggt_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "ggt_renderer"} "name" "gpu beside cpu" "_tombstone" false "frame" #afield/rect [460.0 200.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ggt_compare_code_.kt" "lines" 44 "characters" 1615 "sha256" "a9977b7778aad1be34ca7dad48a23a4041c61381c866ec4a01f624236938f97b"}]}
```

```kotlin [field-property box="ggt_compare" name="code" path="ggt_compare_code_.kt"]
import afield.graphics.nile.*
import afield.graphics.vulkan.util.GpuProbe

val n = 300
val svg = GeziraSvg.tiger()
val doc = GeziraSvg.parse(svg)
val probe = GpuProbe(renderer, "gezira-gpu-tiger-compare")
val scene = GeziraGpuScene(probe, GeziraGpuScene.paths(doc.lines), n, n, transform = doc.fit(n, n))

frames {
    try {
        probe.arm(scene.image)
        while (probe.stillWaiting()) frame()
        // a written image is not a finished chain: let all five stages run whole
        while (probe.stageList.any { !it.valid }) frame()
        repeat(4) { frame() }
        val gpu = scene.read()
        val cpu = GeziraSvg.render(doc, n, n).canvas.rgba
        var worst = 0.0
        var sum = 0.0
        for (i in gpu.indices) {
            val d = Math.abs(gpu[i] - cpu[i]).toDouble()
            worst = maxOf(worst, d)
            sum += d
        }
        println("gpu vs cpu: largest %.4f, mean %.2e per channel".format(worst, sum / gpu.size))
        val diff = GeziraSvg.difference(gpu, cpu, 10.0)

        // Three panels side by side: the GPU, the CPU, and the difference times ten.
        val gap = 12
        val w = 3 * n + 2 * gap
        val sheet = FloatArray(4 * w * n)
        for ((k, img) in listOf(gpu, cpu, diff).withIndex()) {
            for (y in 0 until n) for (x in 0 until n) {
                val s = 4 * (y * n + x)
                val d = 4 * (y * w + k * (n + gap) + x)
                for (c in 0..3) sheet[d + c] = img[s + c]
            }
        }
        display("gpu | cpu | difference x10", GeziraSvg.png(sheet, w, n))
    } finally {
        probe.close()
    }
}
```

## 4. draw the tiger

<!-- field-box id="ggt_draw" index="3" -->

```edn [field-box id="ggt_draw" role="properties"]
{"id" "ggt_draw" "index" 3 "name" "draw the tiger" "parents" ["root" "ggt_tiger"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "ggt_tiger"} "name" "draw the tiger" "_tombstone" false "frame" #afield/rect [40.0 360.0 340.0 100.0]} "blockProperties" [{"name" "vertex" "language" "glsl" "path" "ggt_draw_vertex_.glsl" "lines" 12 "characters" 339 "sha256" "fa8b34a0fd5d23cb8b092ff1b8d0f95ceb03612f45fa7b06e485ed11da365bcc"} {"name" "fragment" "language" "glsl" "path" "ggt_draw_fragment_.glsl" "lines" 28 "characters" 823 "sha256" "e41394c64ec8e7c8ca7917af2078674b7b6fcdad241037d2d8ada26c3bbf3e8e"} {"name" "code" "language" "kotlin" "path" "ggt_draw_code_.kt" "lines" 9 "characters" 284 "sha256" "0ced633297fc1d878edf192183418adfa8a211162c83feb64f943b0578b187fb"}]}
```

```glsl [field-property box="ggt_draw" name="vertex" path="ggt_draw_vertex_.glsl"]
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

```glsl [field-property box="ggt_draw" name="fragment" path="ggt_draw_fragment_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require

// The scene's image: premultiplied rgba per pixel, row-major, y down.
layout(std430, buffer_reference) buffer Image {
    vec4 p[];
};

layout(push_constant) uniform Push {
    Image image;
    int n;
    int win;
} pc;

layout(location = 0) out vec4 outColor;

float srgbToLinear(float c) {
    return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4);
}

void main() {
    // the image fills the window's width, whatever its pixel density
    ivec2 q = ivec2(gl_FragCoord.xy * float(pc.n) / float(max(pc.win, 1)));
    vec4 c = q.x < pc.n && q.y < pc.n ? pc.image.p[q.y * pc.n + q.x] : vec4(0.0);
    // over white
    vec3 col = c.rgb + (1.0 - c.a) * vec3(1.0);
    outColor = vec4(srgbToLinear(col.r), srgbToLinear(col.g), srgbToLinear(col.b), 1.0);
}
```

```kotlin [field-property box="ggt_draw" name="code" path="ggt_draw_code_.kt"]
var drawModule = moduleHere { primitiveType = triangles }
renderer += "gezira-tiger-draw" to drawModule
val quad = fullscreenTriangle(drawModule)
drawModule += "quad" to quad

quad.image = { tigerImage }
quad.n = { tigerSize }
quad.win = { window.width }
println("drawing the tiger")
```

