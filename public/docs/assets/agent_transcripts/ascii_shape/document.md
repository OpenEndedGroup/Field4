# Field Document: ascii_shape

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 10 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="ascii_shape_renderer" index="0" -->

### Narrative

From `notes`

Renderer

```edn [field-box id="ascii_shape_renderer" role="properties"]
{"id" "ascii_shape_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["ascii_shape_gpu_scene"] "properties" {"children" #{#afield/box "ascii_shape_gpu_scene"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 460.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "ascii_shape_renderer_notes_.md" "lines" 3 "characters" 187 "sha256" "7e06df1643fc560a33ea0b242bfd11422f02d3b602223373515c5dd49af67068"} {"name" "code" "language" "kotlin" "path" "ascii_shape_renderer_code_.kt" "lines" 11 "characters" 289 "sha256" "aa633aeaec0b6d32891b0756adb54ed4729e6816be1bb2922f1e9f30475666b3"}]}
```

```markdown [field-property box="ascii_shape_renderer" name="notes" path="ascii_shape_renderer_notes_.md"]
# Renderer

Opens a native window the size of the GPU section's 128 x 48 character grid (on a 2x display). Launch it first, then the scene, match and draw boxes beneath it in that order.
```

```kotlin [field-property box="ascii_shape_renderer" name="code" path="ascii_shape_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
once.renderer {
    window = Window("Shape ASCII", initialDimensions = Rect(80.0, 80.0, 640.0, 456.0))
    Renderer(window)
}
start(renderer::run)
```

## 2. Six circles per cell

<!-- field-box id="ascii_shape_circles" index="1" -->

### Narrative

From `notes`

Six circles per cell

From `code`

Five glyphs as the matcher sees them, 10x up: the cell in gray, its six circles in orange,
and the six just outside it in blue.

```edn [field-box id="ascii_shape_circles" role="properties"]
{"id" "ascii_shape_circles" "index" 1 "name" "Six circles per cell" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Six circles per cell" "_tombstone" false "frame" #afield/rect [380.0 60.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "ascii_shape_circles_notes_.md" "lines" 3 "characters" 188 "sha256" "c05e0d7d7d033ec6dffab6118b8f25c58b1b2894467bc80ac6494af441bace76"} {"name" "code" "language" "kotlin" "path" "ascii_shape_circles_code_.kt" "lines" 39 "characters" 1615 "sha256" "ce77af54d39461a57d7683260935dd63a5cf7b2726dbe82a1d8c5731f2669b93"}]}
```

```markdown [field-property box="ascii_shape_circles" name="notes" path="ascii_shape_circles_notes_.md"]
# Six circles per cell

Five characters as the matcher sees them: six inner sampling circles (orange) give each character its six numbers; the outer ones (blue) feed directional contrast.
```

```kotlin [field-property box="ascii_shape_circles" name="code" path="ascii_shape_circles_code_.kt"]
// Five glyphs as the matcher sees them, 10x up: the cell in gray, its six circles in orange,
// and the six just outside it in blue.
val ascii = ShapeAscii()
val k = 10.0
fun circle(cx: Double, cy: Double, r: Double) = fline {
    for (i in 0..48) {
        val a = 2 * PI * i / 48
        if (i == 0) moveTo(cx + r * cos(a), cy + r * sin(a)) else lineTo(cx + r * cos(a), cy + r * sin(a))
    }
}
val circles = ascii.samplingCircles()
val panels =
    "TL/o@"
        .map { ch ->
            val i = ascii.glyphs.indexOf(ch)
            // pad the cell so the outer circles fit, then draw the circles over it
            val pad = ascii.cellWidth
            val cell = ascii.glyphImg(i).pad(pad, Border.ZERO)
            val inner =
                circles.take(6).map { (x, y, r) ->
                    circle(x + pad, y + pad, r).apply { color = Vec4(1f, 0.55f, 0.1f, 1f) }
                }
            val outer =
                circles.drop(6).map { (x, y, r) ->
                    circle(x + pad, y + pad, r).apply { color = Vec4(0.3f, 0.6f, 1f, 1f) }
                }
            val w = ascii.cellWidth.toDouble()
            val h = ascii.cellHeight.toDouble()
            val box = fline {
                moveTo(pad, pad)
                lineTo(pad + w, pad)
                lineTo(pad + w, pad + h)
                lineTo(pad, pad + h)
                lineTo(pad, pad)
            }
            box.color = Vec4(0.5f, 0.5f, 0.5f, 1f)
            "'$ch'" to cell.drawFLines(inner + outer + box, scale = k, thickness = 2.0)
        }
showImgGrid(*panels.toTypedArray(), columns = 5, cellSide = 200)
```

## 3. Lit sphere

<!-- field-box id="ascii_shape_sphere" index="2" -->

### Narrative

From `notes`

A lit sphere

From `code`

A lit sphere on black, shaded by hand: brightness is the cosine between the surface normal and a light.

```edn [field-box id="ascii_shape_sphere" role="properties"]
{"id" "ascii_shape_sphere" "index" 2 "name" "Lit sphere" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Lit sphere" "_tombstone" false "frame" #afield/rect [720.0 60.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "ascii_shape_sphere_notes_.md" "lines" 3 "characters" 103 "sha256" "ef7878e358710a125a40a2e384f734ace27fd89e2b3bf75c8be67c230a5cde31"} {"name" "code" "language" "kotlin" "path" "ascii_shape_sphere_code_.kt" "lines" 17 "characters" 605 "sha256" "cc76eac49453ed8b47c4fe5e8ca9d91cedaa460eb93d1a926d9bd30588f1c495"}]}
```

```markdown [field-property box="ascii_shape_sphere" name="notes" path="ascii_shape_sphere_notes_.md"]
# A lit sphere

Shading by brightness ramp vs by shape. The method suits light marks on a dark ground.
```

```kotlin [field-property box="ascii_shape_sphere" name="code" path="ascii_shape_sphere_code_.kt"]
// A lit sphere on black, shaded by hand: brightness is the cosine between the surface normal and a light.
val scene =
    Img.generate(300, 240) { x, y ->
        val nx = (x - 150) / 100.0
        val ny = (y - 120) / 100.0
        val r2 = nx * nx + ny * ny
        if (r2 < 1) {
            val nz = sqrt(1 - r2)
            (-0.5 * nx - 0.6 * ny + 0.62 * nz).coerceAtLeast(0.0).toFloat()
        } else 0f
    }
val ascii = ShapeAscii()
showAscii(
    "brightness" to ascii.convert(scene, 40, shape = false),
    "shape, contrast 2" to ascii.convert(scene, 40, contrast = 2.0),
    cellSide = 420,
)
```

## 4. Scene, a lit knot

<!-- field-box id="ascii_shape_gpu_scene" index="3" -->

### Narrative

From `notes`

Scene

```edn [field-box id="ascii_shape_gpu_scene" role="properties"]
{"id" "ascii_shape_gpu_scene" "index" 3 "name" "Scene, a lit knot" "parents" ["root" "ascii_shape_renderer"] "children" ["ascii_shape_gpu_match"] "properties" {"children" #{#afield/box "ascii_shape_gpu_match"} "parents" #{#afield/box "root" #afield/box "ascii_shape_renderer"} "name" "Scene, a lit knot" "_tombstone" false "frame" #afield/rect [380.0 460.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "ascii_shape_gpu_scene_notes_.md" "lines" 3 "characters" 243 "sha256" "c5c504c036c9cda6222485958c58b9b48fdf5e8fcd35d59e6971feb060fb8ff6"} {"name" "vertex" "language" "glsl" "path" "ascii_shape_gpu_scene_vertex_.glsl" "lines" 23 "characters" 761 "sha256" "0dd9c5be87da435949037d607597c80b6f86da8e50d98dc29c7cd479ba8864a7"} {"name" "fragment" "language" "glsl" "path" "ascii_shape_gpu_scene_fragment_.glsl" "lines" 10 "characters" 305 "sha256" "fa3a1d4356b60f91085887c017dad56d9c00fdba54c1d517823707a14f8b21d6"} {"name" "code" "language" "kotlin" "path" "ascii_shape_gpu_scene_code_.kt" "lines" 48 "characters" 1929 "sha256" "dfe8987d224af2326cf60411316b52c13a8c2f2112fa0c622cec2b0007b48ba2"}]}
```

```markdown [field-property box="ascii_shape_gpu_scene" name="notes" path="ascii_shape_gpu_scene_notes_.md"]
# Scene

A lit torus knot drawn into `scene`, an offscreen target 128 x 48 font cells in size, and the `ascii` charset the boxes beneath read. Edit the vertex and fragment shaders and re-run. Docs: ascii_shape.md#on-the-gpu-over-a-live-scene.
```

```glsl [field-property box="ascii_shape_gpu_scene" name="vertex" path="ascii_shape_gpu_scene_vertex_.glsl"]
#version 450
layout(location = 0) in vec3 pos;
layout(location = 1) in vec3 normal;
layout(location = 0) out vec3 fnormal;

layout(push_constant) uniform Push {
    float time;
    float aspect;
} pc;

void main() {
    // turn about y, then x
    float a = pc.time * 0.7;
    float b = pc.time * 0.45;
    mat3 ry = mat3(cos(a), 0.0, -sin(a), 0.0, 1.0, 0.0, sin(a), 0.0, cos(a));
    mat3 rx = mat3(1.0, 0.0, 0.0, 0.0, cos(b), sin(b), 0.0, -sin(b), cos(b));
    vec3 p = rx * ry * pos;
    fnormal = rx * ry * normal;
    // a camera 5 units back looking along +z; +y is up, since Field flips its viewports;
    // depth runs 0..1 from z = 1 to z = 8
    float z = p.z + 5.0;
    gl_Position = vec4(p.x * 2.4 / pc.aspect, p.y * 2.4, (z - 1.0) / 7.0 * z, z);
}
```

```glsl [field-property box="ascii_shape_gpu_scene" name="fragment" path="ascii_shape_gpu_scene_fragment_.glsl"]
#version 450
layout(location = 0) in vec3 fnormal;
layout(location = 0) out vec4 outColor;

void main() {
    // a light up and to the left of the camera
    vec3 l = normalize(vec3(-0.5, 0.6, -0.65));
    float d = max(dot(normalize(fnormal), l), 0.0);
    outColor = vec4(vec3(0.04 + 0.96 * d), 1.0);
}
```

```kotlin [field-property box="ascii_shape_gpu_scene" name="code" path="ascii_shape_gpu_scene_code_.kt"]
import org.lwjgl.vulkan.VK10.*

// The font the characters are matched in, and an offscreen target exactly 128 x 48 of its cells.
// The boxes beneath this one read both.
prop<ShapeAscii>(ascii) = ShapeAscii()
prop<RenderToTexture>(scene) = RenderToTexture {
    width = 128 * ascii.cellWidth
    height = 48 * ascii.cellHeight
    // Plain 8-bit, not sRGB, so a shader reading it and the CPU reading it back see the same numbers.
    format = VK_FORMAT_R8G8B8A8_UNORM
    colorFormats = [VK_FORMAT_R8G8B8A8_UNORM]
    useDepth = true
}
renderer.renderList += "scene" to scene

// The knot is drawn into the scene, not into the window.
var module = moduleHere { +scene }
var knot = Mesh()
module += "knot" to knot
scene += "knot" to module

// The vertex shader turns the knot by `time` and projects it at the scene's shape.
var time = 0f
knot.time = {
    time += 1f / 60
    time
}
knot.aspect = { scene.parameters.width.toFloat() / scene.parameters.height }

// A (2, 3) torus knot: a tube of radius 0.16 around a centre line, 240 steps along it and 16 around.
fun centre(a: Double) =
    vec(0.42 * (2 + cos(3 * a)) * cos(2 * a), 0.42 * (2 + cos(3 * a)) * sin(2 * a), -0.42 * sin(3 * a))
knot.set(240 * 16, 240 * 16 * 2) {
    for (i in 0 until 240) for (j in 0 until 16) {
        val a = 2 * PI * i / 240
        val along = (centre(a + 1e-4) - centre(a - 1e-4)).normalize()
        val side = vec(along.y, -along.x, 0).normalize()
        val up = vec(side.y * along.z, -side.x * along.z, side.x * along.y - side.y * along.x)
        val w = 2 * PI * j / 16
        val out = side * cos(w).toFloat() + up * sin(w).toFloat()
        val p = centre(a) + out * 0.16f
        pos.add(p.x, p.y, p.z)
        normal.add(out.x, out.y, out.z)
        val next = (i + 1) % 240
        tri.add(i * 16 + j, next * 16 + j, next * 16 + (j + 1) % 16)
        tri.add(i * 16 + j, next * 16 + (j + 1) % 16, i * 16 + (j + 1) % 16)
    }
}
```

## 5. Match, one glyph per cell

<!-- field-box id="ascii_shape_gpu_match" index="4" -->

### Narrative

From `notes`

Match

```edn [field-box id="ascii_shape_gpu_match" role="properties"]
{"id" "ascii_shape_gpu_match" "index" 4 "name" "Match, one glyph per cell" "parents" ["root" "ascii_shape_gpu_scene"] "children" ["ascii_shape_gpu_draw"] "properties" {"children" #{#afield/box "ascii_shape_gpu_draw"} "parents" #{#afield/box "root" #afield/box "ascii_shape_gpu_scene"} "name" "Match, one glyph per cell" "_tombstone" false "frame" #afield/rect [720.0 460.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "ascii_shape_gpu_match_notes_.md" "lines" 3 "characters" 193 "sha256" "9d6fc5411a0145a70a811c680d1e3527fbb1d319b5dac1cf7394dd55a14003f4"} {"name" "vertex" "language" "glsl" "path" "ascii_shape_gpu_match_vertex_.glsl" "lines" 10 "characters" 290 "sha256" "c8e40acc0e76e17c38aa5002bfb3bf333785f4b0898f25e68218a27f962be870"} {"name" "fragment" "language" "glsl" "path" "ascii_shape_gpu_match_fragment_.glsl" "lines" 89 "characters" 3479 "sha256" "a77d981f0303155d2fc2a51174d8a3bf5a8ce1223bf6891f6aeb37f04caf8f81"} {"name" "code" "language" "kotlin" "path" "ascii_shape_gpu_match_code_.kt" "lines" 32 "characters" 1198 "sha256" "6927f19725fed96f1efcb5c9c1dc75744982e7d45f49da5deba9438fa60ed5f4"}]}
```

```markdown [field-property box="ascii_shape_gpu_match" name="notes" path="ascii_shape_gpu_match_notes_.md"]
# Match

The fragment shader runs once per character cell: six circle means, the two contrast steps, and the nearest of the glyph vectors, written as an index into `cells`. Edit it and re-run.
```

```glsl [field-property box="ascii_shape_gpu_match" name="vertex" path="ascii_shape_gpu_match_vertex_.glsl"]
#version 450
layout(location = 0) in vec3 pos;
layout(location = 0) out vec2 uv;

void main() {
    // uv runs from 0 to 1 across the target. Field flips its viewports so that +y is up,
    // so uv.y = 0 is the bottom edge.
    uv = pos.xy * 0.5 + 0.5;
    gl_Position = vec4(pos, 1.0);
}
```

```glsl [field-property box="ascii_shape_gpu_match" name="fragment" path="ascii_shape_gpu_match_fragment_.glsl"]
#version 450
// The scene, and every glyph's six numbers: texel (g, 0) holds glyph g's first four, (g, 1) its
// last two.
layout(set = 0, binding = 0) uniform sampler2D sceneTex;
layout(set = 0, binding = 1) uniform sampler2D glyphVectors;

layout(push_constant) uniform Push {
    vec2 cell;
    float contrast;
    float directional;
} pc;

layout(location = 0) out vec4 outColor;

// The six sampling circles, in cell units: two columns of three, the right one a little higher.
// The glyph vectors were measured with these circles, so change them together.
const vec2 CENTRES[6] = vec2[6](
vec2(0.28, 0.20), vec2(0.72, 0.13),
vec2(0.28, 0.53), vec2(0.72, 0.47),
vec2(0.28, 0.87), vec2(0.72, 0.80)
);
// The circles' radius, as a share of the cell's width.
const float RADIUS = 0.24;
// The outer circles sit this many times further from the cell's centre than the inner ones.
const float OUTER_PUSH = 2.6;

// The mean brightness of the scene inside the circle centred at c (cell units) in the cell whose
// top-left pixel is origin. A pixel counts if its centre lies inside the circle.
float circle(ivec2 origin, vec2 c, ivec2 cell, ivec2 size) {
    float r = RADIUS * float(cell.x);
    vec2 centre = c * vec2(cell);
    float sum = 0.0;
    int n = 0;
    for (int y = int(floor(centre.y - r)) - 1; y <= int(centre.y + r) + 1; y++) {
        for (int x = int(floor(centre.x - r)) - 1; x <= int(centre.x + r) + 1; x++) {
            vec2 d = vec2(x, y) + 0.5 - centre;
            if (dot(d, d) <= r * r) {
                vec3 s = texelFetch(sceneTex, clamp(origin + ivec2(x, y), ivec2(0), size - 1), 0).rgb;
                sum += clamp((s.r + s.g + s.b) / 3.0, 0.0, 1.0);
                n++;
            }
        }
    }
    return sum / float(n);
}

void main() {
    // One fragment per cell: this cell's pixels in the scene start here.
    ivec2 cell = ivec2(pc.cell);
    ivec2 size = textureSize(sceneTex, 0);
    ivec2 origin = ivec2(gl_FragCoord.xy) * cell;

    float v[6];
    for (int k = 0; k < 6; k++) v[k] = circle(origin, CENTRES[k], cell, size);

    // Directional contrast: where the circle just outside the cell, in the same direction, is
    // brighter, darken this one.
    if (pc.directional != 1.0) {
        for (int k = 0; k < 6; k++) {
            float outside = circle(origin, 0.5 + (CENTRES[k] - 0.5) * OUTER_PUSH, cell, size);
            float m = max(v[k], outside);
            if (m > 0.0 && v[k] > 0.0) v[k] = pow(v[k] / m, pc.directional) * m;
        }
    }

    // Contrast: the brightest circle stays, the others fall towards zero.
    if (pc.contrast != 1.0) {
        float m = 0.0;
        for (int k = 0; k < 6; k++) m = max(m, v[k]);
        for (int k = 0; k < 6; k++) if (m > 0.0 && v[k] > 0.0) v[k] = pow(v[k] / m, pc.contrast) * m;
    }

    // The glyph whose six numbers are nearest, by squared distance; the first one wins a tie.
    int best = 0;
    float bestDistance = 1e30;
    for (int g = 0; g < textureSize(glyphVectors, 0).x; g++) {
        vec4 a = texelFetch(glyphVectors, ivec2(g, 0), 0);
        vec4 b = texelFetch(glyphVectors, ivec2(g, 1), 0);
        vec4 da = a - vec4(v[0], v[1], v[2], v[3]);
        vec2 db = b.xy - vec2(v[4], v[5]);
        float d = dot(da, da) + dot(db, db);
        if (d < bestDistance) {
            bestDistance = d;
            best = g;
        }
    }
    // The glyph's index, stored in an 8-bit channel.
    outColor = vec4(float(best) / 255.0, 0.0, 0.0, 1.0);
}
```

```kotlin [field-property box="ascii_shape_gpu_match" name="code" path="ascii_shape_gpu_match_code_.kt"]
import org.lwjgl.vulkan.VK10.*

// One texel per character cell; the fragment shader writes the chosen glyph's index into red.
prop<RenderToTexture>(cells) = RenderToTexture {
    width = scene.parameters.width / ascii.cellWidth
    height = scene.parameters.height / ascii.cellHeight
    format = VK_FORMAT_R8G8B8A8_UNORM
    colorFormats = [VK_FORMAT_R8G8B8A8_UNORM]
    useDepth = false
}
// Rendered after the scene it reads, even when the scene box runs again.
renderer.renderList += "cells" to cells.after(scene)

// The shader's two textures, bound by the names it gives them: the scene, and every glyph's six numbers.
prop<RenderToTexture>(sceneTex) = scene
prop<LoadedTexture>(glyphVectors) = ascii.glyphVectorTexture()

var module = moduleHere { +cells }
var m = Mesh()
module += "match" to m
cells += "match" to module

// The shader's push constants: a cell's size in pixels, and the two contrast exponents.
m.cell = { vec(ascii.cellWidth, ascii.cellHeight) }
m.contrast = { 3f }
m.directional = { 3f }

// One triangle covering the whole target, so the fragment shader runs once per texel: once per cell.
m.set(3, 1) {
    pos.add(-1, -1, 0, 3, -1, 0, -1, 3, 0)
    tri.add(0, 1, 2)
}
```

## 6. Draw, glyphs into the window

<!-- field-box id="ascii_shape_gpu_draw" index="5" -->

### Narrative

From `notes`

Draw

From `code`

The glyph chosen for each cell, and every glyph's pixels, bound by the shader's names for them.
`sceneTex`, for the corner, is the match box's.

```edn [field-box id="ascii_shape_gpu_draw" role="properties"]
{"id" "ascii_shape_gpu_draw" "index" 5 "name" "Draw, glyphs into the window" "parents" ["root" "ascii_shape_gpu_match"] "children" ["ascii_shape_gpu_agreement"] "properties" {"children" #{#afield/box "ascii_shape_gpu_agreement"} "parents" #{#afield/box "root" #afield/box "ascii_shape_gpu_match"} "name" "Draw, glyphs into the window" "_tombstone" false "frame" #afield/rect [1060.0 460.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "ascii_shape_gpu_draw_notes_.md" "lines" 3 "characters" 170 "sha256" "3e5e074ddda061d707f8d3efbe8db1418205e5eae85ff538c6073f39c624b914"} {"name" "vertex" "language" "glsl" "path" "ascii_shape_gpu_draw_vertex_.glsl" "lines" 10 "characters" 290 "sha256" "c8e40acc0e76e17c38aa5002bfb3bf333785f4b0898f25e68218a27f962be870"} {"name" "fragment" "language" "glsl" "path" "ascii_shape_gpu_draw_fragment_.glsl" "lines" 37 "characters" 1407 "sha256" "d37f6d45c556c139863e568833108dc3e10e3a4e82392019e740431c6197299b"} {"name" "code" "language" "kotlin" "path" "ascii_shape_gpu_draw_code_.kt" "lines" 20 "characters" 679 "sha256" "54966b4a9c5ce9eae84d41bf475469e0385b5b44f2fc884276e0c081d27ebde8"}]}
```

```markdown [field-property box="ascii_shape_gpu_draw" name="notes" path="ascii_shape_gpu_draw_notes_.md"]
# Draw

The fragment shader looks up each cell's glyph and draws its pixels from the atlas into the window, with the scene in the lower right corner. Edit it and re-run.
```

```glsl [field-property box="ascii_shape_gpu_draw" name="vertex" path="ascii_shape_gpu_draw_vertex_.glsl"]
#version 450
layout(location = 0) in vec3 pos;
layout(location = 0) out vec2 uv;

void main() {
    // uv runs from 0 to 1 across the target. Field flips its viewports so that +y is up,
    // so uv.y = 0 is the bottom edge.
    uv = pos.xy * 0.5 + 0.5;
    gl_Position = vec4(pos, 1.0);
}
```

```glsl [field-property box="ascii_shape_gpu_draw" name="fragment" path="ascii_shape_gpu_draw_fragment_.glsl"]
#version 450
// The chosen glyph for each cell, every glyph's pixels side by side, and the scene itself.
layout(set = 0, binding = 0) uniform sampler2D cellsTex;
layout(set = 0, binding = 1) uniform sampler2D atlasTex;
layout(set = 0, binding = 2) uniform sampler2D sceneTex;

layout(push_constant) uniform Push {
    vec4 ink;
    vec4 background;
    vec2 cell;
} pc;

layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 outColor;

void main() {
    // uv.y runs up the window; the cells and the atlas keep their top row first.
    vec2 p = vec2(uv.x, 1.0 - uv.y);

    // The scene, small, in the lower right corner.
    if (p.x > 0.76 && p.y > 0.76) {
        outColor = vec4(texture(sceneTex, (p - 0.76) / 0.24).rgb, 1.0);
        return;
    }

    // Which cell this pixel is in, and which glyph the match pass chose for it.
    vec2 grid = vec2(textureSize(cellsTex, 0));
    vec2 g = p * grid;
    float glyph = floor(texelFetch(cellsTex, ivec2(g), 0).r * 255.0 + 0.5);

    // Where this pixel falls in that glyph's cell of the atlas; kept half a pixel inside the cell
    // so filtering does not pull in the neighbouring glyph.
    vec2 inCell = clamp(fract(g) * pc.cell, vec2(0.5), pc.cell - 0.5);
    vec2 atlas = vec2(textureSize(atlasTex, 0));
    float ink = texture(atlasTex, (vec2(glyph * pc.cell.x, 0.0) + inCell) / atlas).r;
    outColor = mix(pc.background, pc.ink, ink);
}
```

```kotlin [field-property box="ascii_shape_gpu_draw" name="code" path="ascii_shape_gpu_draw_code_.kt"]
// The glyph chosen for each cell, and every glyph's pixels, bound by the shader's names for them.
// `sceneTex`, for the corner, is the match box's.
prop<RenderToTexture>(cellsTex) = cells
prop<LoadedTexture>(atlasTex) = ascii.glyphAtlasTexture()

var module = moduleHere()
var m = Mesh()
module += "characters" to m
renderer += "characters" to module

m.cell = { vec(ascii.cellWidth, ascii.cellHeight) }
// Field's window is sRGB, which brightens these to #E6E6E6 on #161619.
m.ink = { vec(0.79, 0.79, 0.79, 1) }
m.background = { vec(0.008, 0.008, 0.01, 1) }

// One triangle covering the window.
m.set(3, 1) {
    pos.add(-1, -1, 0, 3, -1, 0, -1, 3, 0)
    tri.add(0, 1, 2)
}
```

## 7. GPU against CPU

<!-- field-box id="ascii_shape_gpu_agreement" index="6" -->

### Narrative

From `notes`

The same characters as the CPU

From `code`

Twice a second: read back one frame's scene with the glyphs the match pass chose for it, convert
the same pixels on the CPU, exhaustively and through the cache, and count the cells that agree.

```edn [field-box id="ascii_shape_gpu_agreement" role="properties"]
{"id" "ascii_shape_gpu_agreement" "index" 6 "name" "GPU against CPU" "parents" ["root" "ascii_shape_gpu_draw"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "ascii_shape_gpu_draw"} "name" "GPU against CPU" "_tombstone" false "frame" #afield/rect [1400.0 460.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "ascii_shape_gpu_agreement_notes_.md" "lines" 3 "characters" 220 "sha256" "82cfa48302062b258e1f1c073384d164a9f06f793f16618a7ef6c6060bcdcfed"} {"name" "code" "language" "kotlin" "path" "ascii_shape_gpu_agreement_code_.kt" "lines" 19 "characters" 913 "sha256" "8bd81d61b3074803de4ed8fbcc9c90e6260f2e5d28338db6e7d022f6273673f1"}]}
```

```markdown [field-property box="ascii_shape_gpu_agreement" name="notes" path="ascii_shape_gpu_agreement_notes_.md"]
# The same characters as the CPU

Reads back one frame's scene with the glyphs the match box chose, twice a second, and converts the same pixels on the CPU, exhaustively and through the cache. Launch after the draw box.
```

```kotlin [field-property box="ascii_shape_gpu_agreement" name="code" path="ascii_shape_gpu_agreement_code_.kt"]
// Twice a second: read back one frame's scene with the glyphs the match pass chose for it, convert
// the same pixels on the CPU, exhaustively and through the cache, and count the cells that agree.
frames {
    while (true) {
        val readback = ascii.readCells(scene, cells)
        while (!readback.isDone) frame()
        val (picture, chars) = readback.get()
        // the match box's contrast and directional
        val exact =
            ascii.convert(picture, chars.cols, contrast = 3.0, directional = 3.0, rows = chars.rows, exact = true)
        val cached = ascii.convert(picture, chars.cols, contrast = 3.0, directional = 3.0, rows = chars.rows)
        display(
            "agreement",
            "GPU = CPU exact on ${(100 * chars.agreement(exact)).f2}% of cells, " +
                "= CPU cached on ${(100 * chars.agreement(cached)).f2}%",
        )
        repeat(30) { frame() }
    }
}
```

## 8. Brightness vs shape

<!-- field-box id="ascii_shape_shapes" index="7" -->

### Narrative

From `notes`

Shape-aware ASCII

From `code`

Three outlines and a filled disc, drawn white on black.

```edn [field-box id="ascii_shape_shapes" role="properties"]
{"id" "ascii_shape_shapes" "index" 7 "name" "Brightness vs shape" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Brightness vs shape" "_tombstone" false "frame" #afield/rect [40.0 60.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "ascii_shape_shapes_notes_.md" "lines" 3 "characters" 295 "sha256" "585043a88bcdf7157ff0fc36201d366bf20c6fc2df00d7a3b6910e1813c35baa"} {"name" "code" "language" "kotlin" "path" "ascii_shape_shapes_code_.kt" "lines" 20 "characters" 757 "sha256" "da75b544190969f410ab2e57cf5a2a22630a79c6677bf83d4cbe33e06384c8d3"}]}
```

```markdown [field-property box="ascii_shape_shapes" name="notes" path="ascii_shape_shapes_notes_.md"]
# Shape-aware ASCII

The same FLine drawing as characters twice: picked by brightness alone (left) and by where the light falls in each cell (right), after Alex Harri's essay https://alexharri.com/blog/ascii-rendering. Run it; try `contrast = 3.0` in the second `convert`. Docs: ascii_shape.md.
```

```kotlin [field-property box="ascii_shape_shapes" name="code" path="ascii_shape_shapes_code_.kt"]
// Three outlines and a filled disc, drawn white on black.
fun ring(cx: Double, cy: Double, r: Double, sides: Int, turn: Double = 0.0) = fline {
    for (i in 0..sides) {
        val a = turn + 2 * PI * i / sides
        if (i == 0) moveTo(cx + r * cos(a), cy + r * sin(a)) else lineTo(cx + r * cos(a), cy + r * sin(a))
    }
    thickness = 6.0
}
val disc = ring(360.0, 100.0, 55.0, 64).apply { filled = true }
val drawing =
    Img.zeros(440, 200, 3)
        .drawFLines(
            [ring(90.0, 100.0, 70.0, 64), ring(210.0, 105.0, 65.0, 3, -PI / 2), disc],
            color = Vec4(1f, 1f, 1f, 1f),
        )
val ascii = ShapeAscii()
showAscii(
    "brightness" to ascii.convert(drawing, 44, shape = false),
    "shape" to ascii.convert(drawing, 44),
)
```

## 9. Live cube in a charGrid

<!-- field-box id="ascii_shape_cube" index="8" -->

### Narrative

From `notes`

Live, in a char grid

From `code`

A wireframe cube turning about two axes, drawn into an Img each tick and matched by shape.

```edn [field-box id="ascii_shape_cube" role="properties"]
{"id" "ascii_shape_cube" "index" 8 "name" "Live cube in a charGrid" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Live cube in a charGrid" "_tombstone" false "frame" #afield/rect [380.0 260.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "ascii_shape_cube_notes_.md" "lines" 3 "characters" 155 "sha256" "8c4881254efea70ccece49ea7a577e8a8ccb16b8ab2c8216c5a3d48649b1f190"} {"name" "code" "language" "kotlin" "path" "ascii_shape_cube_code_.kt" "lines" 30 "characters" 1339 "sha256" "f73c961203f449839e1ea00d371793c09242698b761cbb48d701fc550fe808f2"}]}
```

```markdown [field-property box="ascii_shape_cube" name="notes" path="ascii_shape_cube_notes_.md"]
# Live, in a char grid

A wireframe cube drawn into an Img every tick and matched by shape into a `charGrid` with `drawAscii`. Run to start, stop to stop.
```

```kotlin [field-property box="ascii_shape_cube" name="code" path="ascii_shape_cube_code_.kt"]
// A wireframe cube turning about two axes, drawn into an Img each tick and matched by shape.
val ascii = ShapeAscii()
// Corner i has x, y, z = ±1 from its three bits; an edge joins corners one bit apart.
val corners =
    (0 until 8).map { i ->
        Triple(if (i and 1 == 0) -1.0 else 1.0, if (i and 2 == 0) -1.0 else 1.0, if (i and 4 == 0) -1.0 else 1.0)
    }
val edges = (0 until 8).flatMap { i -> [1, 2, 4].filter { i and it == 0 }.map { i to (i or it) } }

charGrid(cols = 60, rows = 26, glyphs = ascii.glyphs) {
    val a = frame * 0.03
    fun project(p: Triple<Double, Double, Double>): Pair<Double, Double> {
        val (px, py, pz) = p
        val x1 = px * cos(a) - pz * sin(a)
        val z1 = px * sin(a) + pz * cos(a)
        val y1 = py * cos(a * 0.7) - z1 * sin(a * 0.7)
        val z2 = py * sin(a * 0.7) + z1 * cos(a * 0.7)
        val s = 150.0 / (z2 + 4.0)
        return (240 + x1 * s) to (130 + y1 * s)
    }
    val lines = edges.map { (i, j) ->
        fline {
            moveTo(project(corners[i]).first, project(corners[i]).second)
            lineTo(project(corners[j]).first, project(corners[j]).second)
            thickness = 5.0
        }
    }
    val img = Img.zeros(480, 260, 3).drawFLines(lines, color = Vec4(1f, 1f, 1f, 1f))
    drawAscii(ascii.convert(img, COLS, contrast = 3.0, rows = ROWS))
}
```

## 10. Contrast and directional contrast

<!-- field-box id="ascii_shape_contrast" index="9" -->

### Narrative

From `notes`

Sharpening edges

From `code`

Three outlines and a filled disc, drawn white on black.

```edn [field-box id="ascii_shape_contrast" role="properties"]
{"id" "ascii_shape_contrast" "index" 9 "name" "Contrast and directional contrast" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Contrast and directional contrast" "_tombstone" false "frame" #afield/rect [40.0 260.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "ascii_shape_contrast_notes_.md" "lines" 3 "characters" 108 "sha256" "cf81edf71d0b8947592b4e99a6cdea35dc7cc8dcc89f0a90d2e2db36fa4b4d25"} {"name" "code" "language" "kotlin" "path" "ascii_shape_contrast_code_.kt" "lines" 23 "characters" 898 "sha256" "d112be59d741af9a3cca07a0ad6a320363ef092cb8120c5702f67c32a3432fba"}]}
```

```markdown [field-property box="ascii_shape_contrast" name="notes" path="ascii_shape_contrast_notes_.md"]
# Sharpening edges

`contrast` and `directional` are exponents; 1 turns a step off. Compare the three rows.
```

```kotlin [field-property box="ascii_shape_contrast" name="code" path="ascii_shape_contrast_code_.kt"]
// Three outlines and a filled disc, drawn white on black.
fun ring(cx: Double, cy: Double, r: Double, sides: Int, turn: Double = 0.0) = fline {
    for (i in 0..sides) {
        val a = turn + 2 * PI * i / sides
        if (i == 0) moveTo(cx + r * cos(a), cy + r * sin(a)) else lineTo(cx + r * cos(a), cy + r * sin(a))
    }
    thickness = 6.0
}
val disc = ring(360.0, 100.0, 55.0, 64).apply { filled = true }
val drawing =
    Img.zeros(440, 200, 3)
        .drawFLines(
            [ring(90.0, 100.0, 70.0, 64), ring(210.0, 105.0, 65.0, 3, -PI / 2), disc],
            color = Vec4(1f, 1f, 1f, 1f),
        )
val ascii = ShapeAscii()
showAscii(
    "contrast 1" to ascii.convert(drawing, 44),
    "contrast 3" to ascii.convert(drawing, 44, contrast = 3.0),
    "contrast 3, directional 3" to ascii.convert(drawing, 44, contrast = 3.0, directional = 3.0),
    columns = 1,
    cellSide = 600,
)
```

