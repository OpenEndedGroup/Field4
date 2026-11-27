# Field Document: fline_gpu_layers

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 8 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "watchdog" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="flg_renderer" index="0" -->

```edn [field-box id="flg_renderer" role="properties"]
{"id" "flg_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["flg_layers"] "properties" {"children" #{#afield/box "flg_layers"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "flg_renderer_code_.kt" "lines" 14 "characters" 241 "sha256" "ca6299d039c6b09e5e843846bdb83f4f9a35eafd7b27d84d3902dbc6bc3cb3b2"}]}
```

```kotlin [field-property box="flg_renderer" name="code" path="flg_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false
Options.profiling = false

once.renderer {
    window = Window("FLine layers")
    Renderer(window)
}

start(renderer::run)
```

## 2. FLine layers

<!-- field-box id="flg_layers" index="1" -->

### Narrative

From `code`

FLine layers: the parent of the "strokes", "fills" and "points" boxes.

It owns an FLineLayers — `lines`, a mutable list of FLines, and three per-frame GPU
buffers they are packed into — and the Camera the three draw boxes share. Both are
published to every box beneath this one. Any box beneath it draws by changing
`flineLayers.lines`; the loop at the bottom uploads that list once a frame.

The upload goes by content, not by object: a line packs to vertices once, and a line
with the same points and style next frame (even a new FLine, as spatial rules make)
reuses them. Each swapchain image's buffer also remembers what it holds where, and
skips copying a span that is already there. The "upload" output says what each upload
did. Needs the renderer box as its parent.

```edn [field-box id="flg_layers" role="properties"]
{"id" "flg_layers" "index" 1 "name" "FLine layers" "parents" ["root" "flg_renderer"] "children" ["flg_strokes" "flg_fills" "flg_points" "flg_stroke_lines" "flg_strokes_joined" "flg_scene"] "properties" {"children" #{#afield/box "flg_strokes" #afield/box "flg_fills" #afield/box "flg_points" #afield/box "flg_stroke_lines" #afield/box "flg_strokes_joined" #afield/box "flg_scene"} "parents" #{#afield/box "root" #afield/box "flg_renderer"} "name" "FLine layers" "_tombstone" false "frame" #afield/rect [40.0 200.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "flg_layers_code_.kt" "lines" 41 "characters" 1545 "sha256" "0eb31fd5eb056a2efa59d8640739359fc80a49b60e123878c0061f7baa79dcb6"}]}
```

```kotlin [field-property box="flg_layers" name="code" path="flg_layers_code_.kt"]
/*
FLine layers: the parent of the "strokes", "fills" and "points" boxes.

It owns an FLineLayers — `lines`, a mutable list of FLines, and three per-frame GPU
buffers they are packed into — and the Camera the three draw boxes share. Both are
published to every box beneath this one. Any box beneath it draws by changing
`flineLayers.lines`; the loop at the bottom uploads that list once a frame.

The upload goes by content, not by object: a line packs to vertices once, and a line
with the same points and style next frame (even a new FLine, as spatial rules make)
reuses them. Each swapchain image's buffer also remembers what it holds where, and
skips copying a span that is already there. The "upload" output says what each upload
did. Needs the renderer box as its parent.
*/
import afield.graphics.vulkan.util.Camera

prop<FLineLayers>(flineLayers)
prop<Camera>(flineCamera)

// `once`: the draw boxes hold these buffers' addresses, so re-running this box keeps them.
once.flineLayers { FLineLayers(renderer, samplesPerCubic = 16) }

// Looking down +z at a 600 × 360 drawing, centred on it. Lines in pixel-like units, y down.
once.flineCamera {
    Camera(renderer).apply {
        at = vec(300, 180, -260)
        target = vec(300, 180, 0)
        up = vec(0, -1, 0)
    }
}

// WASD/arrows move, E/C rise and fall, R resets, while the renderer window has focus.
var kc by keyCamera(camera = flineCamera)

var frame = 0
loop {
    flineLayers.upload()

    frame++
    if (frame % 30 == 0) markdown("upload", "`${flineLayers.stats}`")
}
```

## 3. strokes

<!-- field-box id="flg_strokes" index="2" -->

### Narrative

From `code`

strokes: every stroked line in the parent's FLineLayers, in one draw.

The vertex shader reads the stroke layer by buffer address and turns each segment (six
vertices) into a screen-space rectangle `thickness` pixels wide; the fragment shader cuts
a capsule out of it. Open them with the property switcher (`f1` or `ctrl-P`). Needs
"FLine layers" as its parent.

```edn [field-box id="flg_strokes" role="properties"]
{"id" "flg_strokes" "index" 2 "name" "strokes" "parents" ["root" "flg_layers"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "flg_layers"} "name" "strokes" "_tombstone" false "frame" #afield/rect [40.0 360.0 200.0 100.0]} "blockProperties" [{"name" "vertex" "language" "glsl" "path" "flg_strokes_vertex_.glsl" "lines" 90 "characters" 3181 "sha256" "44063a363104ecc8851efa66beaba4bec0b3c9e8cd901c26a248758fc4e751a6"} {"name" "fragment" "language" "glsl" "path" "flg_strokes_fragment_.glsl" "lines" 20 "characters" 764 "sha256" "bb8aaf7551509978bd6b225ffc6a1233f6c64ae4d4c26f9410807867abdc7a9a"} {"name" "code" "language" "kotlin" "path" "flg_strokes_code_.kt" "lines" 37 "characters" 1198 "sha256" "4bc68a38f1098cfb3a62544ea74d722eab057d0345f285c9c00715e511c37a3c"}]}
```

```glsl [field-property box="flg_strokes" name="vertex" path="flg_strokes_vertex_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require

// One FLineLayers vertex, 32 bytes. In the stroke layer, vertex i and i + 1 make a
// segment unless i + 1 starts a new run, which it says with a negative w.
struct FLineVertex {
    vec4 position; // xyz: world position; w: thickness in pixels, negated on a run's first vertex
    vec4 color; // straight-alpha RGBA, 0..1
};

layout(std430, buffer_reference, buffer_reference_align = 16) readonly buffer FLineVertices {
    FLineVertex v[];
};

layout(push_constant) uniform StrokePC {
    mat4 p; // projection
    mat4 m; // model-view
    FLineVertices vertices;
    uint count; // vertices in the layer
    vec2 viewport; // framebuffer size, pixels
    float widthScale; // multiplies every thickness
} pc;

layout(location = 0) out vec4 color;
layout(location = 1) out vec2 local; // pixels: x along the segment from its start, y across it
layout(location = 2) flat out vec2 lengthRadius; // pixels: segment length, half the stroke width

// Two triangles over a unit square; x runs along the segment, y across it.
const vec2 corners[6] = vec2[6](vec2(0, 0), vec2(1, 0), vec2(1, 1), vec2(0, 0), vec2(1, 1), vec2(0, 1));

void collapse() {
    gl_Position = vec4(0, 0, 2, 1); // outside the clip volume: the triangle is dropped
    color = vec4(0);
    local = vec2(0);
    lengthRadius = vec2(0);
}

void main() {
    uint s = uint(gl_VertexIndex) / 6u;
    vec2 corner = corners[gl_VertexIndex % 6];
    if (s + 1u >= pc.count) {
        collapse();
        return;
    }

    FLineVertex a = pc.vertices.v[s];
    FLineVertex b = pc.vertices.v[s + 1u];
    // b begins the next run.
    if (b.position.w < 0.0) {
        collapse();
        return;
    }

    // Clip both ends, then pull an end that is behind the camera onto the near side.
    mat4 pm = pc.p * pc.m;
    vec4 ca = pm * vec4(a.position.xyz, 1);
    vec4 cb = pm * vec4(b.position.xyz, 1);
    const float nearW = 1e-4;
    if (ca.w < nearW && cb.w < nearW) {
        collapse();
        return;
    }
    if (ca.w < nearW) ca = mix(ca, cb, (nearW - ca.w) / (cb.w - ca.w));
    if (cb.w < nearW) cb = mix(cb, ca, (nearW - cb.w) / (ca.w - cb.w));

    // Into pixels.
    vec2 sa = (ca.xy / ca.w * 0.5 + 0.5) * pc.viewport;
    vec2 sb = (cb.xy / cb.w * 0.5 + 0.5) * pc.viewport;
    vec2 d = sb - sa;
    float len = length(d);
    vec2 along = len > 1e-6 ? d / len : vec2(1, 0);
    vec2 across = vec2(-along.y, along.x);

    // A rectangle around the segment, one radius past each end for the round caps, plus a
    // pixel for the antialiased edge.
    float radius = 0.5 * max(abs(a.position.w), abs(b.position.w)) * pc.widthScale;
    float extent = radius + 1.0;
    float u = mix(-extent, len + extent, corner.x);
    float v = mix(-extent, extent, corner.y);
    vec2 pixel = sa + along * u + across * v;

    // Depth and colour follow the nearest point on the segment.
    float k = len > 1e-6 ? clamp(u / len, 0.0, 1.0) : 0.0;
    vec4 c = mix(ca, cb, k);

    gl_Position = vec4(pixel / pc.viewport * 2.0 - 1.0, c.z / c.w, 1.0);
    color = mix(a.color, b.color, k);
    local = vec2(u, v);
    lengthRadius = vec2(len, radius);
}
```

```glsl [field-property box="flg_strokes" name="fragment" path="flg_strokes_fragment_.glsl"]
#version 450

layout(location = 0) in vec4 color;
layout(location = 1) in vec2 local;
layout(location = 2) flat in vec2 lengthRadius;

layout(location = 0) out vec4 fragColor;

void main() {
    // Distance in pixels from the segment (a capsule, so joins and ends come out round).
    float len = lengthRadius.x;
    float radius = max(lengthRadius.y, 0.5); // thinner than a pixel draws a pixel, fainter
    float x = clamp(local.x, 0.0, len);
    float d = length(vec2(local.x - x, local.y));

    float coverage = clamp(radius + 0.5 - d, 0.0, 1.0) * min(1.0, lengthRadius.y / 0.5);
    if (coverage <= 0.0) discard;
    // Translucent strokes overlap at their joins, so a join reads a little darker there.
    fragColor = vec4(color.rgb, color.a * coverage);
}
```

```kotlin [field-property box="flg_strokes" name="code" path="flg_strokes_code_.kt"]
/*
strokes: every stroked line in the parent's FLineLayers, in one draw.

The vertex shader reads the stroke layer by buffer address and turns each segment (six
vertices) into a screen-space rectangle `thickness` pixels wide; the fragment shader cuts
a capsule out of it. Open them with the property switcher (`f1` or `ctrl-P`). Needs
"FLine layers" as its parent.
*/
val layers = flineLayers

var module = moduleHere {
    primitiveType = triangles
    depthWrite = false
}
var m = Mesh()
module += m named "strokes"
// Registered under the name every stroke box uses, so launching another one replaces
// this drawing rather than adding to it.
renderer += "fline strokes" to module

// Push constants, matched by name to StrokePC.
m.p = flineCamera.p
m.m = flineCamera.m
m.vertices = { layers.strokes.buffer }
m.count = { layers.strokes.count }
m.viewport = {
    val extent = renderer.swapChain.swapChainExtent
    vec(extent.width(), extent.height())
}
var widthScale = 1.0f
m.widthScale = { widthScale }

// Six vertices for every segment, i.e. for every vertex but the last. The shader drops
// the ones that would join two runs.
loop {
    m.resize(6 * maxOf(0, layers.strokes.count - 1))
}
```

## 4. fills

<!-- field-box id="flg_fills" index="3" -->

### Narrative

From `code`

fills: every filled line in the parent's FLineLayers, as fans in one draw. A line's
`fillHint` picks how much tessellation it gets (once per distinct content): HOLES, the
full tessellator; NO_HOLES, each subpath alone; STAR, a fan about each subpath's centroid
with no tessellator. Every stored vertex is one triangle slot, so the draw is three
vertices per stored vertex. Needs "FLine layers" as its parent.

```edn [field-box id="flg_fills" role="properties"]
{"id" "flg_fills" "index" 3 "name" "fills" "parents" ["root" "flg_layers"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "flg_layers"} "name" "fills" "_tombstone" false "frame" #afield/rect [260.0 360.0 200.0 100.0]} "blockProperties" [{"name" "vertex" "language" "glsl" "path" "flg_fills_vertex_.glsl" "lines" 47 "characters" 1576 "sha256" "1e9afeef2dc05afd15535c74572c995295adfe3b1e1e6e35daf885f4fd21ca71"} {"name" "fragment" "language" "glsl" "path" "flg_fills_fragment_.glsl" "lines" 8 "characters" 131 "sha256" "06158e9779553c8e03d98e1c3ee6f760db208732cee08ea56b9e54f322285c96"} {"name" "code" "language" "kotlin" "path" "flg_fills_code_.kt" "lines" 26 "characters" 803 "sha256" "7cb84fad480baaa6feef7474a8064126987d5b8966a1f9b8783fc155c963d1de"}]}
```

```glsl [field-property box="flg_fills" name="vertex" path="flg_fills_vertex_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require

// One FLineLayers vertex, 32 bytes. The fill layer is fans, restarted in the vertex data:
// w is how many vertices back this vertex's fan centre is, and 0 marks a centre.
struct FLineVertex {
    vec4 position; // xyz: world position; w: distance back to the fan centre, 0 at a centre
    vec4 color; // straight-alpha RGBA, 0..1 (the line's colour)
};

layout(std430, buffer_reference, buffer_reference_align = 16) readonly buffer FLineVertices {
    FLineVertex v[];
};

layout(push_constant) uniform FillPC {
    mat4 p; // projection
    mat4 m; // model-view
    FLineVertices vertices;
    uint count; // vertices in the layer
} pc;

layout(location = 0) out vec4 color;

void main() {
    // One triangle slot per stored vertex k: (centre, v[k], v[k + 1]). A centre, or the last
    // vertex of a fan (whose next is the following centre), has no triangle.
    uint k = uint(gl_VertexIndex) / 3u;
    uint corner = uint(gl_VertexIndex) % 3u;
    if (k + 1u >= pc.count) {
        gl_Position = vec4(0, 0, 2, 1);
        color = vec4(0);
        return;
    }

    FLineVertex here = pc.vertices.v[k];
    FLineVertex next = pc.vertices.v[k + 1u];
    uint back = uint(here.position.w);
    if (back == 0u || next.position.w == 0.0) {
        gl_Position = vec4(0, 0, 2, 1);
        color = vec4(0);
        return;
    }

    FLineVertex vertex = corner == 0u ? pc.vertices.v[k - back] : (corner == 1u ? here : next);
    gl_Position = pc.p * pc.m * vec4(vertex.position.xyz, 1);
    color = vertex.color;
}
```

```glsl [field-property box="flg_fills" name="fragment" path="flg_fills_fragment_.glsl"]
#version 450

layout(location = 0) in vec4 color;
layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = color;
}
```

```kotlin [field-property box="flg_fills" name="code" path="flg_fills_code_.kt"]
/*
fills: every filled line in the parent's FLineLayers, as fans in one draw. A line's
`fillHint` picks how much tessellation it gets (once per distinct content): HOLES, the
full tessellator; NO_HOLES, each subpath alone; STAR, a fan about each subpath's centroid
with no tessellator. Every stored vertex is one triangle slot, so the draw is three
vertices per stored vertex. Needs "FLine layers" as its parent.
*/
val layers = flineLayers

var module = moduleHere {
    primitiveType = triangles
    depthWrite = false
}
var m = Mesh()
module += m named "fills"
renderer += "fline fills" to module

// Push constants, matched by name to FillPC.
m.p = flineCamera.p
m.m = flineCamera.m
m.vertices = { layers.fills.buffer }
m.count = { layers.fills.count }

loop {
    m.resize(3 * layers.fills.count)
}
```

## 5. points

<!-- field-box id="flg_points" index="4" -->

### Narrative

From `code`

points: a dot at every node of every pointed line in the parent's FLineLayers, six
vertices each, in one draw. Needs "FLine layers" as its parent.

```edn [field-box id="flg_points" role="properties"]
{"id" "flg_points" "index" 4 "name" "points" "parents" ["root" "flg_layers"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "flg_layers"} "name" "points" "_tombstone" false "frame" #afield/rect [480.0 360.0 200.0 100.0]} "blockProperties" [{"name" "vertex" "language" "glsl" "path" "flg_points_vertex_.glsl" "lines" 46 "characters" 1591 "sha256" "a4965f067f5e273d037c9e45f0e2ef7f7b7bc61f4601f351bc8c7c8f21712264"} {"name" "fragment" "language" "glsl" "path" "flg_points_fragment_.glsl" "lines" 13 "characters" 343 "sha256" "64acd6954b018c1c97f3604caab04c35b859931501396883b5d62ed62a6b900a"} {"name" "code" "language" "kotlin" "path" "flg_points_code_.kt" "lines" 29 "characters" 706 "sha256" "aaee5c1dcb861a599d3912ac0dd513cc2be90892776870f4506573db04f6a4f0"}]}
```

```glsl [field-property box="flg_points" name="vertex" path="flg_points_vertex_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require

// One FLineLayers vertex, 32 bytes. The point layer has one per node of a pointed line.
struct FLineVertex {
    vec4 position; // xyz: world position; w: dot radius in pixels (pointSize * 3)
    vec4 color; // straight-alpha RGBA, 0..1
};

layout(std430, buffer_reference, buffer_reference_align = 16) readonly buffer FLineVertices {
    FLineVertex v[];
};

layout(push_constant) uniform PointPC {
    mat4 p; // projection
    mat4 m; // model-view
    FLineVertices vertices;
    uint count; // vertices in the layer
    vec2 viewport; // framebuffer size, pixels
    float sizeScale; // multiplies every radius
} pc;

layout(location = 0) out vec4 color;
layout(location = 1) out vec2 local; // pixels from the dot's centre
layout(location = 2) flat out float radius; // pixels

const vec2 corners[6] = vec2[6](vec2(-1, -1), vec2(1, -1), vec2(1, 1), vec2(-1, -1), vec2(1, 1), vec2(-1, 1));

void main() {
    uint i = uint(gl_VertexIndex) / 6u;
    vec2 corner = corners[gl_VertexIndex % 6];
    FLineVertex vertex = pc.vertices.v[min(i, max(pc.count, 1u) - 1u)];

    vec4 clip = pc.p * pc.m * vec4(vertex.position.xyz, 1);
    if (i >= pc.count || clip.w <= 0.0) {
        gl_Position = vec4(0, 0, 2, 1);
        color = vec4(0);
        return;
    }

    // A square one pixel bigger than the dot, in pixels, about the projected centre.
    radius = vertex.position.w * pc.sizeScale;
    local = corner * (radius + 1.0);
    gl_Position = clip + vec4(local / pc.viewport * 2.0 * clip.w, 0, 0);
    color = vertex.color;
}
```

```glsl [field-property box="flg_points" name="fragment" path="flg_points_fragment_.glsl"]
#version 450

layout(location = 0) in vec4 color;
layout(location = 1) in vec2 local;
layout(location = 2) flat in float radius;

layout(location = 0) out vec4 fragColor;

void main() {
    float coverage = clamp(radius + 0.5 - length(local), 0.0, 1.0);
    if (coverage <= 0.0) discard;
    fragColor = vec4(color.rgb, color.a * coverage);
}
```

```kotlin [field-property box="flg_points" name="code" path="flg_points_code_.kt"]
/*
points: a dot at every node of every pointed line in the parent's FLineLayers, six
vertices each, in one draw. Needs "FLine layers" as its parent.
*/
val layers = flineLayers

var module = moduleHere {
    primitiveType = triangles
    depthWrite = false
}
var m = Mesh()
module += m named "points"
renderer += "fline points" to module

// Push constants, matched by name to PointPC.
m.p = flineCamera.p
m.m = flineCamera.m
m.vertices = { layers.points.buffer }
m.count = { layers.points.count }
m.viewport = {
    val extent = renderer.swapChain.swapChainExtent
    vec(extent.width(), extent.height())
}
var sizeScale = 1.0f
m.sizeScale = { sizeScale }

loop {
    m.resize(6 * layers.points.count)
}
```

## 6. stroke lines

<!-- field-box id="flg_stroke_lines" index="5" -->

### Narrative

From `code`

stroke lines: the stroke layer as hardware lines, one pixel wide whatever the lines'
`thickness` (Apple's Vulkan drivers have no wide lines), two vertices per segment, in one
draw. Launch it to replace "strokes" with hairlines, or to see what the line rasteriser costs.
Needs "FLine layers" as its parent.

```edn [field-box id="flg_stroke_lines" role="properties"]
{"id" "flg_stroke_lines" "index" 5 "name" "stroke lines" "parents" ["root" "flg_layers"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "flg_layers"} "name" "stroke lines" "_tombstone" false "frame" #afield/rect [40.0 520.0 200.0 100.0]} "blockProperties" [{"name" "vertex" "language" "glsl" "path" "flg_stroke_lines_vertex_.glsl" "lines" 39 "characters" 1344 "sha256" "87ff30eae97280e3806fc0db719f1d885e9b389ade4e2c40c85950b0ca5497c6"} {"name" "fragment" "language" "glsl" "path" "flg_stroke_lines_fragment_.glsl" "lines" 8 "characters" 131 "sha256" "06158e9779553c8e03d98e1c3ee6f760db208732cee08ea56b9e54f322285c96"} {"name" "code" "language" "kotlin" "path" "flg_stroke_lines_code_.kt" "lines" 27 "characters" 853 "sha256" "c74142084cef4232308379f65110405aa62e164ca8c44439925b30895f622597"}]}
```

```glsl [field-property box="flg_stroke_lines" name="vertex" path="flg_stroke_lines_vertex_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require

// One FLineLayers vertex, 32 bytes. In the stroke layer, vertex i and i + 1 make a
// segment unless i + 1 starts a new run, which it says with a negative w.
struct FLineVertex {
    vec4 position; // xyz: world position; w: thickness, negated on a run's first vertex (unused here)
    vec4 color; // straight-alpha RGBA, 0..1
};

layout(std430, buffer_reference, buffer_reference_align = 16) readonly buffer FLineVertices {
    FLineVertex v[];
};

layout(push_constant) uniform LinePC {
    mat4 p; // projection
    mat4 m; // model-view
    FLineVertices vertices;
    uint count; // vertices in the layer
} pc;

layout(location = 0) out vec4 color;

void main() {
    // A line list: two vertices per segment, segment s joining v[s] and v[s + 1]. A segment
    // into a run's first vertex is the restart: both its ends go to the same point outside
    // the clip volume, and the line is dropped.
    uint s = uint(gl_VertexIndex) / 2u;
    uint end = uint(gl_VertexIndex) % 2u;
    if (s + 1u >= pc.count || pc.vertices.v[s + 1u].position.w < 0.0) {
        gl_Position = vec4(0, 0, 2, 1);
        color = vec4(0);
        return;
    }

    FLineVertex vertex = pc.vertices.v[s + end];
    gl_Position = pc.p * pc.m * vec4(vertex.position.xyz, 1);
    color = vertex.color;
}
```

```glsl [field-property box="flg_stroke_lines" name="fragment" path="flg_stroke_lines_fragment_.glsl"]
#version 450

layout(location = 0) in vec4 color;
layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = color;
}
```

```kotlin [field-property box="flg_stroke_lines" name="code" path="flg_stroke_lines_code_.kt"]
/*
stroke lines: the stroke layer as hardware lines, one pixel wide whatever the lines'
`thickness` (Apple's Vulkan drivers have no wide lines), two vertices per segment, in one
draw. Launch it to replace "strokes" with hairlines, or to see what the line rasteriser costs.
Needs "FLine layers" as its parent.
*/
val layers = flineLayers

var module = moduleHere {
    primitiveType = lines
    depthWrite = false
}
var m = Mesh()
module += m named "stroke lines"
// Registered under the name every stroke box uses, so launching another one replaces
// this drawing rather than adding to it.
renderer += "fline strokes" to module

// Push constants, matched by name to LinePC.
m.p = flineCamera.p
m.m = flineCamera.m
m.vertices = { layers.strokes.buffer }
m.count = { layers.strokes.count }

loop {
    m.resize(2 * maxOf(0, layers.strokes.count - 1))
}
```

## 7. spatial rules source

<!-- field-box id="flg_scene" index="6" -->

### Narrative

From `code`

spatial rules source: a sample scene for "FLine layers". Seeds wander; nearby seeds bond;
triangles of bonds fill in. Every frame, the world's step() output becomes the parent's
list of lines. Needs "FLine layers" as its parent.

```edn [field-box id="flg_scene" role="properties"]
{"id" "flg_scene" "index" 6 "name" "spatial rules source" "parents" ["root" "flg_layers"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "flg_layers"} "name" "spatial rules source" "_tombstone" false "frame" #afield/rect [360.0 200.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "flg_scene_code_.kt" "lines" 59 "characters" 2054 "sha256" "8fab44648f05301f9ab405defb41076632e699504a4329d175370fb6a84d5d5b"}]}
```

```kotlin [field-property box="flg_scene" name="code" path="flg_scene_code_.kt"]
/*
spatial rules source: a sample scene for "FLine layers". Seeds wander; nearby seeds bond;
triangles of bonds fill in. Every frame, the world's step() output becomes the parent's
list of lines. Needs "FLine layers" as its parent.
*/
import afield.data.graph.SpatialLifetime.MATCH

val world = SpatialRuleWorld()
val w = 600.0
val h = 360.0
val ink = vec(0.85, 0.9, 1.0, 1.0)
val skin = vec(0.3, 0.5, 0.9, 0.2)

fun zigzag(u: Double) = asin(sin(u)) * 2 / PI

fun wander(i: Int, t: Double) = vec(
    w / 2 + 0.45 * w * zigzag((0.06 + 0.12 * (i * 0.618 % 1.0)) * t + 5.2 * i),
    h / 2 + 0.45 * h * zigzag((0.06 + 0.12 * (i * 0.382 % 1.0)) * t + 2.4 * i),
    0,
)

world.source("drifting seeds") {
    val seeds = 40 { i -> point("seed", wander(i, 0.0)) }
    while (true) {
        for ((i, seed) in seeds.withIndex()) move(seed, wander(i, age))
        // All the seeds as one pointed line: the points layer draws a dot at each node.
        draw(
            spatialStroke(seeds.map { it.pos }, ink).apply {
                stroked = false
                pointed = true
                pointSize = 1.2
            },
        )
        nextFrame()
    }
}

world.rules("bonds") {
    onEnter("bond nearby seeds", points("seed").pairsWithin(90.0, leave = 110.0)) { (a, b) ->
        val bond = edge("bond", a, b, lifetime = MATCH)
        whileLive { draw(spatialStroke(bond, ink, width = 1.5, opacity = 0.7)) }
        during(0.8) { t -> draw(spatialStroke(bond, ink, width = 1.5, opacity = 0.7 * (1 - t))) }
    }
}

world.rules("membranes") {
    onEnter("fill a triangle", edges("bond").triangles()) { triangle ->
        // A triangle is star-convex: STAR fills it as a fan, with no tessellator.
        whileLive {
            draw(spatialFill(triangle, skin, opacity = min(1.0, age / 0.5)).apply { fillHint = FillHint.STAR })
        }
    }
}

// Each frame's lines replace the parent's list; its loop uploads them.
loop {
    flineLayers.lines.clear()
    flineLayers.lines += world.step(AnimationClock.nowSeconds())
}.end { world.close() }
```

## 8. strokes, joined

<!-- field-box id="flg_strokes_joined" index="7" -->

### Narrative

From `code`

strokes, joined: the stroke layer as in "strokes", but the fragment shader antialiases each
run analytically as one shape and draws each of its pixels once, so translucent strokes
keep an even tone through their joins and along dense curves. It reads the stroke layer in
both shaders; NEIGHBOURS in the fragment shader sets how far along a run it looks.
Launch it to replace "strokes". Needs "FLine layers" as its parent.

```edn [field-box id="flg_strokes_joined" role="properties"]
{"id" "flg_strokes_joined" "index" 7 "name" "strokes, joined" "parents" ["root" "flg_layers"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "flg_layers"} "name" "strokes, joined" "_tombstone" false "frame" #afield/rect [260.0 520.0 200.0 100.0]} "blockProperties" [{"name" "vertex" "language" "glsl" "path" "flg_strokes_joined_vertex_.glsl" "lines" 88 "characters" 3082 "sha256" "ab32994d61318ba1cef34a0c0204623632a98619f589d9c7409f8202dd9f878f"} {"name" "fragment" "language" "glsl" "path" "flg_strokes_joined_fragment_.glsl" "lines" 94 "characters" 3837 "sha256" "1547881931cd90ecfd5c5267dd51e50eb0bceceaca228d3106925b2bc6442daa"} {"name" "code" "language" "kotlin" "path" "flg_strokes_joined_code_.kt" "lines" 34 "characters" 1167 "sha256" "0946d853317976286cb4e836e1bc1cd1105a9095a9ab1bc5792cffb062d4b466"}]}
```

```glsl [field-property box="flg_strokes_joined" name="vertex" path="flg_strokes_joined_vertex_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require

// One FLineLayers vertex, 32 bytes. In the stroke layer, vertex i and i + 1 make a
// segment unless i + 1 starts a new run, which it says with a negative w.
struct FLineVertex {
    vec4 position; // xyz: world position; w: thickness in pixels, negated on a run's first vertex
    vec4 color; // straight-alpha RGBA, 0..1
};

layout(std430, buffer_reference, buffer_reference_align = 16) readonly buffer FLineVertices {
    FLineVertex v[];
};

// The fragment shader declares the same block: it reads the neighbouring segments too.
layout(push_constant) uniform StrokePC {
    mat4 p; // projection
    mat4 m; // model-view
    FLineVertices vertices;
    uint count; // vertices in the layer
    vec2 viewport; // framebuffer size, pixels
    float widthScale; // multiplies every thickness
} pc;

layout(location = 0) out vec4 color;
layout(location = 1) flat out uint segment;
// This vertex in the same pixel space the fragment shader projects the neighbours into.
// (Not gl_FragCoord: the renderer's viewport may be flipped, so its y can run the other way.)
layout(location = 2) noperspective out vec2 pixelPosition;

const vec2 corners[6] = vec2[6](vec2(0, 0), vec2(1, 0), vec2(1, 1), vec2(0, 0), vec2(1, 1), vec2(0, 1));

void collapse() {
    gl_Position = vec4(0, 0, 2, 1);
    color = vec4(0);
    segment = 0u;
    pixelPosition = vec2(0);
}

void main() {
    uint s = uint(gl_VertexIndex) / 6u;
    vec2 corner = corners[gl_VertexIndex % 6];
    if (s + 1u >= pc.count) {
        collapse();
        return;
    }

    FLineVertex a = pc.vertices.v[s];
    FLineVertex b = pc.vertices.v[s + 1u];
    // b begins the next run.
    if (b.position.w < 0.0) {
        collapse();
        return;
    }

    mat4 pm = pc.p * pc.m;
    vec4 ca = pm * vec4(a.position.xyz, 1);
    vec4 cb = pm * vec4(b.position.xyz, 1);
    const float nearW = 1e-4;
    if (ca.w < nearW && cb.w < nearW) {
        collapse();
        return;
    }
    if (ca.w < nearW) ca = mix(ca, cb, (nearW - ca.w) / (cb.w - ca.w));
    if (cb.w < nearW) cb = mix(cb, ca, (nearW - cb.w) / (ca.w - cb.w));

    vec2 sa = (ca.xy / ca.w * 0.5 + 0.5) * pc.viewport;
    vec2 sb = (cb.xy / cb.w * 0.5 + 0.5) * pc.viewport;
    vec2 d = sb - sa;
    float len = length(d);
    vec2 along = len > 1e-6 ? d / len : vec2(1, 0);
    vec2 across = vec2(-along.y, along.x);

    // The rectangle only has to cover what this segment might own: its own capsule, plus a
    // pixel for the antialiased edge. The fragment shader decides ownership.
    float radius = 0.5 * max(abs(a.position.w), abs(b.position.w)) * pc.widthScale;
    float extent = radius + 1.0;
    float u = mix(-extent, len + extent, corner.x);
    float v = mix(-extent, extent, corner.y);
    vec2 pixel = sa + along * u + across * v;

    float k = len > 1e-6 ? clamp(u / len, 0.0, 1.0) : 0.0;
    vec4 c = mix(ca, cb, k);
    gl_Position = vec4(pixel / pc.viewport * 2.0 - 1.0, c.z / c.w, 1.0);
    color = mix(a.color, b.color, k);
    segment = s;
    pixelPosition = pixel;
}
```

```glsl [field-property box="flg_strokes_joined" name="fragment" path="flg_strokes_joined_fragment_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require

// Analytic antialiasing of a whole run, blended once per pixel.
//
// The stroke of a run is the union of its segments' capsules, and the distance to a union
// is the smallest distance to any part of it. So each fragment measures its signed
// distance (distance minus that segment's radius, in pixels) to its own segment and to the
// NEIGHBOURS segments either side in the same run. Only the nearest segment draws the
// pixel (ties go to the earlier one), at the coverage the union's distance gives. Every
// pixel inside the run is drawn once, so translucent strokes do not darken where segments
// overlap, and the edge is antialiased from the union, not from one capsule.
//
// A run that comes back on itself further away than NEIGHBOURS segments still overlaps
// there. Raising it costs two more segment tests per pixel per step.
#define NEIGHBOURS 4

// Two segments see the same pixel at slightly different positions (each interpolates it
// over its own quad, and at thousands of pixels a float is only good to about 1e-3), so a
// tie is a tie within this many pixels; the earlier segment takes it. Without it a few
// pixels are drawn by both segments (a bright speck) or by neither (a hole).
#define TIE 1e-2

struct FLineVertex {
    vec4 position; // xyz: world position; w: thickness in pixels, negated on a run's first vertex
    vec4 color; // straight-alpha RGBA, 0..1
};

layout(std430, buffer_reference, buffer_reference_align = 16) readonly buffer FLineVertices {
    FLineVertex v[];
};

layout(push_constant) uniform StrokePC {
    mat4 p;
    mat4 m;
    FLineVertices vertices;
    uint count;
    vec2 viewport;
    float widthScale;
} pc;

layout(location = 0) in vec4 color;
layout(location = 1) flat in uint segment;
layout(location = 2) noperspective in vec2 pixelPosition;

layout(location = 0) out vec4 fragColor;

// A vertex in the vertex shader's pixel space; false when it is behind the camera.
bool toPixels(mat4 pm, vec3 p, out vec2 pixel) {
    vec4 c = pm * vec4(p, 1);
    pixel = (c.xy / c.w * 0.5 + 0.5) * pc.viewport;
    return c.w > 1e-4;
}

// Signed distance, in pixels, from q to segment i's capsule: negative inside it.
float segmentDistance(mat4 pm, uint i, vec2 q) {
    FLineVertex a = pc.vertices.v[i];
    FLineVertex b = pc.vertices.v[i + 1u];
    vec2 sa;
    vec2 sb;
    if (!toPixels(pm, a.position.xyz, sa) || !toPixels(pm, b.position.xyz, sb)) return 1e9;
    vec2 ab = sb - sa;
    float t = clamp(dot(q - sa, ab) / max(dot(ab, ab), 1e-12), 0.0, 1.0);
    float radius = 0.5 * max(abs(a.position.w), abs(b.position.w)) * pc.widthScale;
    return length(q - (sa + t * ab)) - radius;
}

void main() {
    mat4 pm = pc.p * pc.m;
    vec2 q = pixelPosition;
    uint s = segment;
    float own = segmentDistance(pm, s, q);

    // Earlier segments in this run: stop at the run's start. An earlier one as near (to within
    // TIE) wins.
    for (uint k = 1u; k <= uint(NEIGHBOURS) && k <= s; k++) {
        uint i = s - k;
        if (pc.vertices.v[i + 1u].position.w < 0.0) break; // segment i would end at our run's start
        if (segmentDistance(pm, i, q) <= own + TIE) discard;
    }
    // Later segments in this run: stop where the next run starts. Only a nearer one (by more
    // than TIE) wins.
    for (uint k = 1u; k <= uint(NEIGHBOURS); k++) {
        uint i = s + k;
        if (i + 1u >= pc.count || pc.vertices.v[i + 1u].position.w < 0.0) break;
        if (segmentDistance(pm, i, q) < own - TIE) discard;
    }

    // Box-filtered coverage of a straight edge at that distance; a stroke thinner than a
    // pixel comes out fainter rather than thinner.
    float coverage = clamp(0.5 - own, 0.0, 1.0);
    if (coverage <= 0.0) discard;
    fragColor = vec4(color.rgb, color.a * coverage);
}
```

```kotlin [field-property box="flg_strokes_joined" name="code" path="flg_strokes_joined_code_.kt"]
/*
strokes, joined: the stroke layer as in "strokes", but the fragment shader antialiases each
run analytically as one shape and draws each of its pixels once, so translucent strokes
keep an even tone through their joins and along dense curves. It reads the stroke layer in
both shaders; NEIGHBOURS in the fragment shader sets how far along a run it looks.
Launch it to replace "strokes". Needs "FLine layers" as its parent.
*/
val layers = flineLayers

var module = moduleHere {
    primitiveType = triangles
    depthWrite = false
}
var m = Mesh()
module += m named "strokes, joined"
// Registered under the name every stroke box uses, so launching another one replaces
// this drawing rather than adding to it.
renderer += "fline strokes" to module

// Push constants, matched by name to StrokePC (declared in both shaders).
m.p = flineCamera.p
m.m = flineCamera.m
m.vertices = { layers.strokes.buffer }
m.count = { layers.strokes.count }
m.viewport = {
    val extent = renderer.swapChain.swapChainExtent
    vec(extent.width(), extent.height())
}
var widthScale = 1.0f
m.widthScale = { widthScale }

loop {
    m.resize(6 * maxOf(0, layers.strokes.count - 1))
}
```

