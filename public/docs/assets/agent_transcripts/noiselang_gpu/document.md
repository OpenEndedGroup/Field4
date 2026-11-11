# Field Document: noiselang_gpu

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="agent_graphics_context" index="0" -->

```edn [field-box id="agent_graphics_context" role="properties"]
{"id" "agent_graphics_context" "index" 0 "name" "renderer" "parents" ["root"] "children" ["noiselang_gpu_dists"] "properties" {"children" #{#afield/box "noiselang_gpu_dists"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_graphics_context_code_.kt" "lines" 15 "characters" 275 "sha256" "e90393b044932ad7e022f5505bec768466f0d819aaa553c877bfe061d981c6d2"}]}
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

## 2. distributions (run me first)

<!-- field-box id="noiselang_gpu_dists" index="1" -->

### Narrative

From `code` tags: `#include`

The distributions the shader beneath this box draws from. distHere and gpHere make them
includable by this box's shaders and its descendants': `#include "dist/ring"`,
`#include "dist/arc"`, `#include "gp/ridge"`. Edit one and run this box, then re-run the
shader box: its next compile includes the new graph.

```edn [field-box id="noiselang_gpu_dists" role="properties"]
{"id" "noiselang_gpu_dists" "index" 1 "name" "distributions (run me first)" "parents" ["root" "agent_graphics_context"] "children" ["noiselang_gpu"] "properties" {"children" #{#afield/box "noiselang_gpu"} "parents" #{#afield/box "root" #afield/box "agent_graphics_context"} "name" "distributions (run me first)" "_tombstone" false "frame" #afield/rect [400.0 40.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "noiselang_gpu_dists_code_.kt" "lines" 24 "characters" 1129 "sha256" "dedff760ce283f1d000ea7945cafaee1fdc6f20e66e67b9d440da8387748f946"}]}
```

```kotlin [field-property box="noiselang_gpu_dists" name="code" path="noiselang_gpu_dists_code_.kt"]
/*
The distributions the shader beneath this box draws from. distHere and gpHere make them
includable by this box's shaders and its descendants': `#include "dist/ring"`,
`#include "dist/arc"`, `#include "gp/ridge"`. Edit one and run this box, then re-run the
shader box: its next compile includes the new graph.
*/
import afield.math.distribution.*

// a ring: radius about 1, any angle; drawn jointly, so x and y share the same radius and angle
val radius = normal(1, 0.08)
val angle = uniform(0, 2 * Math.PI)
val ring = [radius * angle.cos(), radius * angle.sin()]
distHere("ring", ring)

// the same ring, given x + y > 0.5: a posterior the shader draws by weighted reservoir sampling
val arc = ring.given(ring[0] + ring[1] gt 0.5)
distHere("arc", arc, posteriorPool = 16)

// a Gaussian process through five readings: each `curve` number is one whole random function
val xs = doubleArrayOf(0.5, 2.0, 3.5, 6.0, 7.5)
val ys = doubleArrayOf(0.2, 1.1, 0.4, 0.9, -0.3)
gpHere("ridge", GpShaderModel.of(xs, ys, lengthScale = 1.0, noiseStd = 0.1))

println("P(x + y > 0.5) on the ring = ${P(ring[0] + ring[1] gt 0.5, n = 50_000)}")
```

## 3. distributions in a shader

<!-- field-box id="noiselang_gpu" index="2" -->

### Narrative

From `code`

Distributions drawn in a fragment shader: three panels, a ring, the ring given x + y > 0.5, and
a Gaussian process through five readings. The graphs are in the box above; the fragment
property includes them.

```edn [field-box id="noiselang_gpu" role="properties"]
{"id" "noiselang_gpu" "index" 2 "name" "distributions in a shader" "parents" ["root" "noiselang_gpu_dists"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "noiselang_gpu_dists"} "name" "distributions in a shader" "_tombstone" false "frame" #afield/rect [400.0 180.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "noiselang_gpu_code_.kt" "lines" 13 "characters" 406 "sha256" "c94e1715efb0c1b9a1a26776d6b527610b44e6cc843c4ae76b44319d6b2ec9de"} {"name" "vertex" "language" "glsl" "path" "noiselang_gpu_vertex_.glsl" "lines" 8 "characters" 257 "sha256" "5e1454a95ae92889d518e7fe40701f239953bc9cca43d2160eda79632fbae195"} {"name" "fragment" "language" "glsl" "path" "noiselang_gpu_fragment_.glsl" "lines" 64 "characters" 2568 "sha256" "9c2692e4d8720635a1ab909b730c26a3f31823a1a234b3ad650401639e42878b"}]}
```

```kotlin [field-property box="noiselang_gpu" name="code" path="noiselang_gpu_code_.kt"]
/*
Distributions drawn in a fragment shader: three panels, a ring, the ring given x + y > 0.5, and
a Gaussian process through five readings. The graphs are in the box above; the fragment
property includes them.
*/
val shader = moduleHere {
    depthTest = false
    depthWrite = false
    blend = false
}
val quad = fullscreenTriangle(shader)
shader += "quad" to quad
renderer += "noiselang-gpu" to shader
```

```glsl [field-property box="noiselang_gpu" name="vertex" path="noiselang_gpu_vertex_.glsl"]
#version 450
// one triangle that covers the whole window; uv is 0..1 across it
layout(location = 0) out vec2 uv;
void main() {
    vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    uv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
```

```glsl [field-property box="noiselang_gpu" name="fragment" path="noiselang_gpu_fragment_.glsl"]
#version 450
#include "dist/ring"
#include "dist/arc"
#include "gp/ridge"

layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 color;

const int POINTS = 320;
const vec3 BLUE = vec3(0.0, 0.67, 1.0);
const vec3 ORANGE = vec3(1.0, 0.67, 0.0);

// the same POINTS draws at every pixel (seeded by the draw's number), splatted where they land
float splats(vec2 p, bool conditioned, float r) {
    float acc = 0.0;
    for (int k = 0; k < POINTS; k++) {
        uint s = nlSeed(uint(k), conditioned ? 23u : 11u);
        float v[2];
        if (conditioned) {
            if (!arc_try(s, v)) continue;
        }
        else ring(s, v);
        vec2 d = p - vec2(v[0], v[1]);
        acc += exp(-dot(d, d) / (2.0 * r * r));
    }
    return min(acc, 1.0);
}

void main() {
    // the window's size in pixels, from how fast uv changes across one pixel
    vec2 size = 1.0 / fwidth(uv);
    float W = size.x, H = size.y, panelW = W / 3.0;
    int panel = int(gl_FragCoord.x / panelW);
    vec2 local = vec2(gl_FragCoord.x - float(panel) * panelW, gl_FragCoord.y);
    float scale = min(panelW, H);
    vec3 c = vec3(0.0);
    if (panel < 2) {
        // a square view of [-1.5, 1.5] in the middle of the panel, y up
        vec2 p = (local - 0.5 * vec2(panelW, H)) / scale * 3.0;
        p.y = -p.y;
        float px = 3.0 / scale; // one pixel, in data units
        if (panel == 1 && abs(p.x + p.y - 0.5) < 0.7 * px * 1.4142) c = vec3(0.35); // the line x + y = 0.5
        c = max(c, (panel == 0 ? BLUE : ORANGE) * splats(p, panel == 1, 2.2 * px));
    }
    else {
        // the Gaussian process on x in [0, 8], y in [-1.5, 2.5]
        vec2 lo = vec2(0.0, -1.5), hi = vec2(8.0, 2.5);
        vec2 q = lo + (vec2(local.x, H - local.y) / vec2(panelW, H)) * (hi - lo);
        vec2 px = (hi - lo) / vec2(panelW, H);
        float m = ridge_mean(q.x), sd = ridge_sd(q.x);
        if (abs(q.y - m) < 2.0 * sd) c = vec3(0.0, 0.006, 0.014); // the window is sRGB: dark values stay dark only if they are very small
        for (int k = 0; k < 20; k++) {
            float f = ridge(q.x, uint(k));
            c = max(c, BLUE * 0.55 * exp(-pow((q.y - f) / (1.2 * px.y), 2.0)));
        }
        c = max(c, vec3(exp(-pow((q.y - m) / (1.1 * px.y), 2.0))));
        for (int i = 0; i < ridge_N; i++) {
            vec2 d = (q - vec2(ridge_POINTS[i], ridge_VALUES[i])) / px;
            if (dot(d, d) < 49.0) c = ORANGE;
        }
    }
    if (abs(gl_FragCoord.x - panelW) < 0.75 || abs(gl_FragCoord.x - 2.0 * panelW) < 0.75) c = vec3(0.25);
    color = vec4(c, 1.0);
}
```

