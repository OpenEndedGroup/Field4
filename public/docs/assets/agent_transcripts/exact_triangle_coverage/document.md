# Field Document: exact_triangle_coverage

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. coverage on the CPU

<!-- field-box id="coverage_cpu" index="0" -->

```edn [field-box id="coverage_cpu" role="properties"]
{"id" "coverage_cpu" "index" 0 "name" "coverage on the CPU" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "coverage on the CPU" "_tombstone" false "frame" #afield/rect [40.0 200.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "coverage_cpu_code_.kt" "lines" 53 "characters" 2103 "sha256" "65aa5748b962feb545725802bfbe7504ecc635ca8db2da3e135c8bf4c0c2ccdb"}]}
```

```kotlin [field-property box="coverage_cpu" name="code" path="coverage_cpu_code_.kt"]
import afield.graphics.render.*

// Eight triangles on a 32 x 24 grid, y down, divided into the unit square TriangleSplat uses.
fun tri(
    ax: Double,
    ay: Double,
    bx: Double,
    by: Double,
    cx: Double,
    cy: Double,
    r: Double,
    g: Double,
    b: Double,
    a: Double = 1.0,
) = CoverageTriangle(ax / 32, ay / 24, bx / 32, by / 24, cx / 32, cy / 24, r, g, b, a)

val scene = listOf(
    tri(2.0, 20.0, 14.0, 3.0, 20.0, 18.0, 1.0, 0.55, 0.1),
    tri(18.0, 8.0, 31.0, 11.0, 24.0, 22.0, 0.2, 0.8, 0.4, 0.7),
    tri(3.0, 2.0, 30.0, 4.0, 30.0, 4.35, 0.3, 0.6, 1.0),
    tri(6.0, 22.0, 28.0, 21.6, 28.0, 22.0, 1.0, 1.0, 1.0),
) +
        (0 until 4).map { i ->
            // Four triangles of 0.08 pixels each, tucked between pixel centres.
            val x = 2.0 + 2.0 * i
            tri(x + 0.05, 5.05, x + 0.45, 5.1, x + 0.1, 5.45, 1.0, 1.0, 1.0)
        }

val point = TriangleSplat.render(scene, 32, 24, CoverageSampling.POINT)
val exact = TriangleSplat.render(scene, 32, 24, CoverageSampling.EXACT)
val reference = TriangleSplat.render(scene, 512, 384, CoverageSampling.EXACT)
showImgGrid(
    "point-sampled, 32 x 24" to point.image.upscaleNearest(16),
    "exact coverage, 32 x 24" to exact.image.upscaleNearest(16),
    "exact coverage, 512 x 384" to reference.image,
    columns = 3,
    cellSide = 512,
)
println("point-sampled: ${point.visible} of ${scene.size} triangles reach a pixel")
println("exact:         ${exact.visible} of ${scene.size} triangles reach a pixel")

// Culling: 20,000 triangles, most far smaller than a pixel, with a soft window (sigma = 1).
val rng = kotlin.random.Random(1)
val dust = List(20000) {
    val s = 0.0004 + 0.01 * rng.nextDouble() * rng.nextDouble()
    val x = rng.nextDouble()
    val y = rng.nextDouble()
    CoverageTriangle(x, y, x + s, y + s * rng.nextDouble(), x + s * rng.nextDouble(), y + s, 0.9, 0.8, 0.6, 0.8)
}
val culledDust = TriangleSplat.render(dust, 128, 128, sigma = 1.0)
println(
    "culling at 128 px: ${culledDust.culled} of ${dust.size} culled, ${culledDust.drawn} drawn, ${culledDust.visible} visible",
)
```

## 2. renderer

<!-- field-box id="agent_graphics_context" index="1" -->

```edn [field-box id="agent_graphics_context" role="properties"]
{"id" "agent_graphics_context" "index" 1 "name" "renderer" "parents" ["root"] "children" ["coverage_gpu"] "properties" {"children" #{#afield/box "coverage_gpu"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_graphics_context_code_.kt" "lines" 15 "characters" 275 "sha256" "e90393b044932ad7e022f5505bec768466f0d819aaa553c877bfe061d981c6d2"}]}
```

```kotlin [field-property box="agent_graphics_context" name="code" path="agent_graphics_context_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false
Options.profiling = false

once.renderer {
    window = Window("Field")
    Renderer(window)
}

println("agent graphics renderer ready")
start(renderer::run)
```

## 3. coverage on the GPU

<!-- field-box id="coverage_gpu" index="2" -->

### Narrative

From `code` tags: `#include`

Exact triangle coverage in a fragment shader. The window is cut into fat pixels; the left half
samples each fat pixel at its centre, the right half integrates each triangle over it exactly
with #include "coverage/triangle.glsl". The triangles and the compositing are in this box's
fragment property.

```edn [field-box id="coverage_gpu" role="properties"]
{"id" "coverage_gpu" "index" 2 "name" "coverage on the GPU" "parents" ["root" "agent_graphics_context"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "agent_graphics_context"} "name" "coverage on the GPU" "_tombstone" false "frame" #afield/rect [400.0 40.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "coverage_gpu_code_.kt" "lines" 25 "characters" 956 "sha256" "78bb89fd0612e2775244426493e3ade790eea8b4c59e38a4e3b6a22a05ec1e8d"} {"name" "vertex" "language" "glsl" "path" "coverage_gpu_vertex_.glsl" "lines" 6 "characters" 227 "sha256" "8f3504d8801a4398f2518ca9de09a3f9f4b72e4eb43044567049caf473214b5a"} {"name" "fragment" "language" "glsl" "path" "coverage_gpu_fragment_.glsl" "lines" 57 "characters" 2142 "sha256" "d6be772c7435b4b7d72cfea6e6764e241dc85a4cff1cc876567b58223188b80f"}]}
```

```kotlin [field-property box="coverage_gpu" name="code" path="coverage_gpu_code_.kt"]
/*
Exact triangle coverage in a fragment shader. The window is cut into fat pixels; the left half
samples each fat pixel at its centre, the right half integrates each triangle over it exactly
with #include "coverage/triangle.glsl". The triangles and the compositing are in this box's
fragment property.
*/
val pass = moduleHere {
    depthTest = false
    depthWrite = false
    blend = false
}
val quad = fullscreenTriangle(pass)
pass += "quad" to quad
renderer += "exact-coverage" to pass

var pixelSize = 0.0
var sigma = 0.0
quad.viewport = { vec(window.width.toFloat(), window.height.toFloat(), pixelSize, sigma) }

controlGroup("Coverage") {
    slider("Fat pixel size (0 = fit the scene)", min = 0.0, max = 64.0, initialValue = pixelSize) { pixelSize = it }
    slider("Window sharpness sigma", min = 0.0, max = 4.0, initialValue = sigma) { sigma = it }
}
displayOpen("Coverage")
println("exact coverage on the GPU: left point-sampled, right exact")
```

```glsl [field-property box="coverage_gpu" name="vertex" path="coverage_gpu_vertex_.glsl"]
#version 450
// one triangle that covers the whole window; positions come from the vertex index
void main() {
    vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
```

```glsl [field-property box="coverage_gpu" name="fragment" path="coverage_gpu_fragment_.glsl"]
#version 450
#include "coverage/triangle.glsl"

layout(location = 0) out vec4 color;

layout(push_constant) uniform pcs {
    vec4 viewport; // width, height, fat pixel size (0 = fit), window sigma
} pc;

// The same eight triangles as the CPU box, on a 32 x 24 grid of fat pixels, y down.
const int N = 8;
const vec2 P[24] = vec2[](
vec2(2, 20), vec2(14, 3), vec2(20, 18),
vec2(18, 8), vec2(31, 11), vec2(24, 22),
vec2(3, 2), vec2(30, 4), vec2(30, 4.35),
vec2(6, 22), vec2(28, 21.6), vec2(28, 22),
vec2(2.05, 5.05), vec2(2.45, 5.1), vec2(2.1, 5.45),
vec2(4.05, 5.05), vec2(4.45, 5.1), vec2(4.1, 5.45),
vec2(6.05, 5.05), vec2(6.45, 5.1), vec2(6.1, 5.45),
vec2(8.05, 5.05), vec2(8.45, 5.1), vec2(8.1, 5.45)
);
const vec4 C[8] = vec4[](
vec4(1.0, 0.55, 0.1, 1.0), vec4(0.2, 0.8, 0.4, 0.7), vec4(0.3, 0.6, 1.0, 1.0), vec4(1.0),
vec4(1.0), vec4(1.0), vec4(1.0), vec4(1.0)
);

void main() {
    float w = pc.viewport.x;
    float h = pc.viewport.y;
    float halfW = floor(w * 0.5);
    float k = pc.viewport.z > 0.5 ? pc.viewport.z : floor(min(halfW / 33.0, h / 25.0));
    k = max(k, 1.0);
    float sigma = pc.viewport.w;

    bool exact = gl_FragCoord.x >= halfW;
    // Centre the 32 x 24 scene in each half.
    vec2 origin = floor(vec2(halfW - 32.0 * k, h - 24.0 * k) * 0.5);
    vec2 local = gl_FragCoord.xy - vec2(exact ? halfW + origin.x : origin.x, origin.y);
    vec2 cell = floor(local / k);

    vec3 c = vec3(0.0);
    for (int i = 0; i < N; i++) {
        vec2 a = P[3 * i];
        vec2 b = P[3 * i + 1];
        vec2 d = P[3 * i + 2];
        float cover = exact
        ? tcWindowCoverage(a, b, d, sigma, cell, cell + 1.0)
        : tcWindow(a, b, d, sigma, cell + 0.5);
        c = mix(c, C[i].rgb, clamp(cover * C[i].a, 0.0, 1.0));
    }
    // A faint outline on each fat pixel, and a bright divider between the halves.
    vec2 f = local - cell * k;
    if (k >= 6.0 && (f.x < 1.0 || f.y < 1.0)) c = mix(c, vec3(0.25), 0.35);
    if (abs(gl_FragCoord.x - halfW) < 1.5) c = vec3(1.0);
    // The window target is sRGB: decode so the colours show as the CPU figure's stored values.
    color = vec4(pow(c, vec3(2.2)), 1.0);
}
```

