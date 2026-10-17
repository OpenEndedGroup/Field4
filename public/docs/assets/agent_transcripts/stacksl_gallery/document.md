# Field Document: stacksl_gallery

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 32 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. window + renderer

<!-- field-box id="renderer" index="0" -->

```edn [field-box id="renderer" role="properties"]
{"id" "renderer" "index" 0 "name" "window + renderer" "parents" ["root"] "children" ["uv-coordinates" "fbm-palette" "soft-ring" "kaleido-twist" "polar-sigmoid" "posterize-shaper" "fm-palette" "definitions" "subroutine-ridges" "oklab-shift" "ryb-paint" "grade-fields" "auto-before" "auto-after" "feedback-trails" "phasor-noise" "radial-waves" "mirror-tiles" "tiled-grid" "hard-threshold" "custom-palette" "sigmoid-punch" "twist-swirl" "spiral-flow" "value-noise" "sample-hold" "word-reference" "stripe-bands" "tonemap-grade" "local-marks" "diffusion-feedback"] "properties" {"children" #{#afield/box "uv-coordinates" #afield/box "fbm-palette" #afield/box "soft-ring" #afield/box "kaleido-twist" #afield/box "polar-sigmoid" #afield/box "posterize-shaper" #afield/box "fm-palette" #afield/box "definitions" #afield/box "subroutine-ridges" #afield/box "oklab-shift" #afield/box "ryb-paint" #afield/box "grade-fields" #afield/box "auto-before" #afield/box "auto-after" #afield/box "feedback-trails" #afield/box "phasor-noise" #afield/box "radial-waves" #afield/box "mirror-tiles" #afield/box "tiled-grid" #afield/box "hard-threshold" #afield/box "custom-palette" #afield/box "sigmoid-punch" #afield/box "twist-swirl" #afield/box "spiral-flow" #afield/box "value-noise" #afield/box "sample-hold" #afield/box "word-reference" #afield/box "stripe-bands" #afield/box "tonemap-grade" #afield/box "local-marks" #afield/box "diffusion-feedback"} "parents" #{#afield/box "root"} "name" "window + renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "renderer_code_.kt" "lines" 13 "characters" 230 "sha256" "e45a2fd654dc7f2bfbe5f9efab4b2ae6f391a0a51afe65caac1ab6642cbd3e02"}]}
```

```kotlin [field-property box="renderer" name="code" path="renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = true

once.renderer {
    window = Window("Field \u2014 StackSL gallery")
    Renderer(window)
}

start(renderer::run)
```

## 2. uv-coordinates

<!-- field-box id="uv-coordinates" index="1" -->

### Narrative

From `code`

A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
slot; this box's `stackSL` property fills it. Edit the stackSL live and the
shader re-binds in place.

[[property stackSL]]

```edn [field-box id="uv-coordinates" role="properties"]
{"id" "uv-coordinates" "index" 1 "name" "uv-coordinates" "parents" ["root" "renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "renderer"} "name" "uv-coordinates" "_tombstone" false "frame" #afield/rect [400.0 170.0 300.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "uv-coordinates_code_.kt" "lines" 18 "characters" 772 "sha256" "d6fbc2aa9eb5a571f36c6f9721ff1aaf978b56295cec491f4adbf29a9f2e759e"} {"name" "fragment" "language" "glsl" "path" "uv-coordinates_fragment_.glsl" "lines" 19 "characters" 506 "sha256" "7a6b03f7f6d7537a3a20920b336c7c9271bb9616f9c6f1a8c0056a733b57a138"} {"name" "stackSL" "language" "stacksl" "path" "uv-coordinates_stackSL_.stacksl" "lines" 1 "characters" 12 "sha256" "cf2b3eea89fb2e78fd9a151568c2c019789d75c0161e0e874ca6ecd9b34d7ff2"} {"name" "vertex" "language" "glsl" "path" "uv-coordinates_vertex_.glsl" "lines" 9 "characters" 271 "sha256" "6f8ea62eb2f0225f87b4294947183d5993e9c7b5a31ce7896a5252da9012bd88"}]}
```

```kotlin [field-property box="uv-coordinates" name="code" path="uv-coordinates_code_.kt"]
// A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
// slot; this box's `stackSL` property fills it. Edit the stackSL live and the
// shader re-binds in place.
//
// [[property stackSL]]

val module = moduleHere { }
val m = Mesh()
module += m named "fullscreen"
m.resize(3) // three vertices, no attributes: the vertex shader makes the triangle

// Every box in this gallery draws the whole window, so they all register under one
// name: launching a box replaces whichever one the window was showing.
renderer += "stacksl-gallery" to module

// Bind this box's StackSL source into the `program` slot (compiles + hot-reloads).
// `stackSL` is null when no box up the graph has one; bind the empty program then.
module.program = stackSL ?: ""
```

```glsl [field-property box="uv-coordinates" name="fragment" path="uv-coordinates_fragment_.glsl"]
#version 450
layout(location = 0) in vec2 fragUV;
layout(location = 0) out vec4 outColor;

float gallery_hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

{{ stack.auto.program }}

void main() {
    vec2 uv = fragUV;
    vec4 stackInput = vec4(uv, gallery_hash12(uv), 1.7500);
    vec3 c = program(stackInput).rgb;
    if (any(isnan(c)) || any(isinf(c))) c = vec3(0.0);
    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
```

```stacksl [field-property box="uv-coordinates" name="stackSL" path="uv-coordinates_stackSL_.stacksl"]
uv saturate
```

```glsl [field-property box="uv-coordinates" name="vertex" path="uv-coordinates_vertex_.glsl"]
#version 450
layout(location = 0) out vec2 fragUV;

void main() {
    // Fullscreen triangle from gl_VertexIndex, no vertex inputs.
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
    fragUV = pos;
}
```

## 3. tiled-grid

<!-- field-box id="tiled-grid" index="2" -->

### Narrative

From `code`

A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
slot; this box's `stackSL` property fills it. Edit the stackSL live and the
shader re-binds in place.

[[property stackSL]]

```edn [field-box id="tiled-grid" role="properties"]
{"id" "tiled-grid" "index" 2 "name" "tiled-grid" "parents" ["root" "renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "renderer"} "name" "tiled-grid" "_tombstone" false "frame" #afield/rect [1120.0 690.0 300.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "tiled-grid_code_.kt" "lines" 18 "characters" 772 "sha256" "d6fbc2aa9eb5a571f36c6f9721ff1aaf978b56295cec491f4adbf29a9f2e759e"} {"name" "fragment" "language" "glsl" "path" "tiled-grid_fragment_.glsl" "lines" 19 "characters" 506 "sha256" "7a6b03f7f6d7537a3a20920b336c7c9271bb9616f9c6f1a8c0056a733b57a138"} {"name" "stackSL" "language" "stacksl" "path" "tiled-grid_stackSL_.stacksl" "lines" 1 "characters" 31 "sha256" "19af1a5e67280731e90c88d1cb28db45eeb0af67d66ff8fba2dcc59701ec8639"} {"name" "vertex" "language" "glsl" "path" "tiled-grid_vertex_.glsl" "lines" 9 "characters" 271 "sha256" "6f8ea62eb2f0225f87b4294947183d5993e9c7b5a31ce7896a5252da9012bd88"}]}
```

```kotlin [field-property box="tiled-grid" name="code" path="tiled-grid_code_.kt"]
// A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
// slot; this box's `stackSL` property fills it. Edit the stackSL live and the
// shader re-binds in place.
//
// [[property stackSL]]

val module = moduleHere { }
val m = Mesh()
module += m named "fullscreen"
m.resize(3) // three vertices, no attributes: the vertex shader makes the triangle

// Every box in this gallery draws the whole window, so they all register under one
// name: launching a box replaces whichever one the window was showing.
renderer += "stacksl-gallery" to module

// Bind this box's StackSL source into the `program` slot (compiles + hot-reloads).
// `stackSL` is null when no box up the graph has one; bind the empty program then.
module.program = stackSL ?: ""
```

```glsl [field-property box="tiled-grid" name="fragment" path="tiled-grid_fragment_.glsl"]
#version 450
layout(location = 0) in vec2 fragUV;
layout(location = 0) out vec4 outColor;

float gallery_hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

{{ stack.auto.program }}

void main() {
    vec2 uv = fragUV;
    vec4 stackInput = vec4(uv, gallery_hash12(uv), 1.7500);
    vec3 c = program(stackInput).rgb;
    if (any(isnan(c)) || any(isinf(c))) c = vec3(0.0);
    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
```

```stacksl [field-property box="tiled-grid" name="stackSL" path="tiled-grid_stackSL_.stacksl"]
uv 3 tile fbm palette saturate
```

```glsl [field-property box="tiled-grid" name="vertex" path="tiled-grid_vertex_.glsl"]
#version 450
layout(location = 0) out vec2 fragUV;

void main() {
    // Fullscreen triangle from gl_VertexIndex, no vertex inputs.
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
    fragUV = pos;
}
```

## 4. posterize-shaper

<!-- field-box id="posterize-shaper" index="3" -->

### Narrative

From `code`

A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
slot; this box's `stackSL` property fills it. Edit the stackSL live and the
shader re-binds in place.

[[property stackSL]]

```edn [field-box id="posterize-shaper" role="properties"]
{"id" "posterize-shaper" "index" 3 "name" "posterize-shaper" "parents" ["root" "renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "renderer"} "name" "posterize-shaper" "_tombstone" false "frame" #afield/rect [760.0 300.0 300.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "posterize-shaper_code_.kt" "lines" 18 "characters" 772 "sha256" "d6fbc2aa9eb5a571f36c6f9721ff1aaf978b56295cec491f4adbf29a9f2e759e"} {"name" "fragment" "language" "glsl" "path" "posterize-shaper_fragment_.glsl" "lines" 19 "characters" 506 "sha256" "7a6b03f7f6d7537a3a20920b336c7c9271bb9616f9c6f1a8c0056a733b57a138"} {"name" "stackSL" "language" "stacksl" "path" "posterize-shaper_stackSL_.stacksl" "lines" 5 "characters" 54 "sha256" "03f3355169b1d27dcf1d7c3162975562a36e4dac3986bd0ba068e0e24ca311d2"} {"name" "vertex" "language" "glsl" "path" "posterize-shaper_vertex_.glsl" "lines" 9 "characters" 271 "sha256" "6f8ea62eb2f0225f87b4294947183d5993e9c7b5a31ce7896a5252da9012bd88"}]}
```

```kotlin [field-property box="posterize-shaper" name="code" path="posterize-shaper_code_.kt"]
// A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
// slot; this box's `stackSL` property fills it. Edit the stackSL live and the
// shader re-binds in place.
//
// [[property stackSL]]

val module = moduleHere { }
val m = Mesh()
module += m named "fullscreen"
m.resize(3) // three vertices, no attributes: the vertex shader makes the triangle

// Every box in this gallery draws the whole window, so they all register under one
// name: launching a box replaces whichever one the window was showing.
renderer += "stacksl-gallery" to module

// Bind this box's StackSL source into the `program` slot (compiles + hot-reloads).
// `stackSL` is null when no box up the graph has one; bind the empty program then.
module.program = stackSL ?: ""
```

```glsl [field-property box="posterize-shaper" name="fragment" path="posterize-shaper_fragment_.glsl"]
#version 450
layout(location = 0) in vec2 fragUV;
layout(location = 0) out vec4 outColor;

float gallery_hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

{{ stack.auto.program }}

void main() {
    vec2 uv = fragUV;
    vec4 stackInput = vec4(uv, gallery_hash12(uv), 1.7500);
    vec3 c = program(stackInput).rgb;
    if (any(isnan(c)) || any(isinf(c))) c = vec3(0.0);
    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
```

```stacksl [field-property box="posterize-shaper" name="stackSL" path="posterize-shaper_stackSL_.stacksl"]
uv 5 * fbm
0.2 0.8 ramp
6 posterize
0.62 bias
palette
```

```glsl [field-property box="posterize-shaper" name="vertex" path="posterize-shaper_vertex_.glsl"]
#version 450
layout(location = 0) out vec2 fragUV;

void main() {
    // Fullscreen triangle from gl_VertexIndex, no vertex inputs.
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
    fragUV = pos;
}
```

## 5. fbm-palette

<!-- field-box id="fbm-palette" index="4" -->

### Narrative

From `code`

A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
slot; this box's `stackSL` property fills it. Edit the stackSL live and the
shader re-binds in place.

[[property stackSL]]

```edn [field-box id="fbm-palette" role="properties"]
{"id" "fbm-palette" "index" 4 "name" "fbm-palette" "parents" ["root" "renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "renderer"} "name" "fbm-palette" "_tombstone" false "frame" #afield/rect [760.0 170.0 300.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fbm-palette_code_.kt" "lines" 18 "characters" 772 "sha256" "d6fbc2aa9eb5a571f36c6f9721ff1aaf978b56295cec491f4adbf29a9f2e759e"} {"name" "fragment" "language" "glsl" "path" "fbm-palette_fragment_.glsl" "lines" 19 "characters" 506 "sha256" "7a6b03f7f6d7537a3a20920b336c7c9271bb9616f9c6f1a8c0056a733b57a138"} {"name" "stackSL" "language" "stacksl" "path" "fbm-palette_stackSL_.stacksl" "lines" 6 "characters" 44 "sha256" "858cb35927c5438a0577343d17a472076579a14c908ad36b5dbd1a63156a221e"} {"name" "vertex" "language" "glsl" "path" "fbm-palette_vertex_.glsl" "lines" 9 "characters" 271 "sha256" "6f8ea62eb2f0225f87b4294947183d5993e9c7b5a31ce7896a5252da9012bd88"}]}
```

```kotlin [field-property box="fbm-palette" name="code" path="fbm-palette_code_.kt"]
// A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
// slot; this box's `stackSL` property fills it. Edit the stackSL live and the
// shader re-binds in place.
//
// [[property stackSL]]

val module = moduleHere { }
val m = Mesh()
module += m named "fullscreen"
m.resize(3) // three vertices, no attributes: the vertex shader makes the triangle

// Every box in this gallery draws the whole window, so they all register under one
// name: launching a box replaces whichever one the window was showing.
renderer += "stacksl-gallery" to module

// Bind this box's StackSL source into the `program` slot (compiles + hot-reloads).
// `stackSL` is null when no box up the graph has one; bind the empty program then.
module.program = stackSL ?: ""
```

```glsl [field-property box="fbm-palette" name="fragment" path="fbm-palette_fragment_.glsl"]
#version 450
layout(location = 0) in vec2 fragUV;
layout(location = 0) out vec4 outColor;

float gallery_hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

{{ stack.auto.program }}

void main() {
    vec2 uv = fragUV;
    vec4 stackInput = vec4(uv, gallery_hash12(uv), 1.7500);
    vec3 c = program(stackInput).rgb;
    if (any(isnan(c)) || any(isinf(c))) c = vec3(0.0);
    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
```

```stacksl [field-property box="fbm-palette" name="stackSL" path="fbm-palette_stackSL_.stacksl"]
uv 5.0 *
time 0.12 *
+
fbm
palette
saturate
```

```glsl [field-property box="fbm-palette" name="vertex" path="fbm-palette_vertex_.glsl"]
#version 450
layout(location = 0) out vec2 fragUV;

void main() {
    // Fullscreen triangle from gl_VertexIndex, no vertex inputs.
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
    fragUV = pos;
}
```

## 6. tonemap-grade

<!-- field-box id="tonemap-grade" index="5" -->

### Narrative

From `code`

A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
slot; this box's `stackSL` property fills it. Edit the stackSL live and the
shader re-binds in place.

[[property stackSL]]

```edn [field-box id="tonemap-grade" role="properties"]
{"id" "tonemap-grade" "index" 5 "name" "tonemap-grade" "parents" ["root" "renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "renderer"} "name" "tonemap-grade" "_tombstone" false "frame" #afield/rect [400.0 1080.0 300.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "tonemap-grade_code_.kt" "lines" 18 "characters" 772 "sha256" "d6fbc2aa9eb5a571f36c6f9721ff1aaf978b56295cec491f4adbf29a9f2e759e"} {"name" "fragment" "language" "glsl" "path" "tonemap-grade_fragment_.glsl" "lines" 19 "characters" 506 "sha256" "7a6b03f7f6d7537a3a20920b336c7c9271bb9616f9c6f1a8c0056a733b57a138"} {"name" "stackSL" "language" "stacksl" "path" "tonemap-grade_stackSL_.stacksl" "lines" 1 "characters" 49 "sha256" "79a15396f3d9d28835dcd234e773f4974af25f937e96534a6e7f1e1f91cff517"} {"name" "vertex" "language" "glsl" "path" "tonemap-grade_vertex_.glsl" "lines" 9 "characters" 271 "sha256" "6f8ea62eb2f0225f87b4294947183d5993e9c7b5a31ce7896a5252da9012bd88"}]}
```

```kotlin [field-property box="tonemap-grade" name="code" path="tonemap-grade_code_.kt"]
// A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
// slot; this box's `stackSL` property fills it. Edit the stackSL live and the
// shader re-binds in place.
//
// [[property stackSL]]

val module = moduleHere { }
val m = Mesh()
module += m named "fullscreen"
m.resize(3) // three vertices, no attributes: the vertex shader makes the triangle

// Every box in this gallery draws the whole window, so they all register under one
// name: launching a box replaces whichever one the window was showing.
renderer += "stacksl-gallery" to module

// Bind this box's StackSL source into the `program` slot (compiles + hot-reloads).
// `stackSL` is null when no box up the graph has one; bind the empty program then.
module.program = stackSL ?: ""
```

```glsl [field-property box="tonemap-grade" name="fragment" path="tonemap-grade_fragment_.glsl"]
#version 450
layout(location = 0) in vec2 fragUV;
layout(location = 0) out vec4 outColor;

float gallery_hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

{{ stack.auto.program }}

void main() {
    vec2 uv = fragUV;
    vec4 stackInput = vec4(uv, gallery_hash12(uv), 1.7500);
    vec3 c = program(stackInput).rgb;
    if (any(isnan(c)) || any(isinf(c))) c = vec3(0.0);
    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
```

```stacksl [field-property box="tonemap-grade" name="stackSL" path="tonemap-grade_stackSL_.stacksl"]
uv 5 * fbm palette 1.7 contrast tonemap saturate
```

```glsl [field-property box="tonemap-grade" name="vertex" path="tonemap-grade_vertex_.glsl"]
#version 450
layout(location = 0) out vec2 fragUV;

void main() {
    // Fullscreen triangle from gl_VertexIndex, no vertex inputs.
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
    fragUV = pos;
}
```

## 7. sigmoid-punch

<!-- field-box id="sigmoid-punch" index="6" -->

### Narrative

From `code`

A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
slot; this box's `stackSL` property fills it. Edit the stackSL live and the
shader re-binds in place.

[[property stackSL]]

```edn [field-box id="sigmoid-punch" role="properties"]
{"id" "sigmoid-punch" "index" 6 "name" "sigmoid-punch" "parents" ["root" "renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "renderer"} "name" "sigmoid-punch" "_tombstone" false "frame" #afield/rect [760.0 820.0 300.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sigmoid-punch_code_.kt" "lines" 18 "characters" 772 "sha256" "d6fbc2aa9eb5a571f36c6f9721ff1aaf978b56295cec491f4adbf29a9f2e759e"} {"name" "fragment" "language" "glsl" "path" "sigmoid-punch_fragment_.glsl" "lines" 19 "characters" 506 "sha256" "7a6b03f7f6d7537a3a20920b336c7c9271bb9616f9c6f1a8c0056a733b57a138"} {"name" "stackSL" "language" "stacksl" "path" "sigmoid-punch_stackSL_.stacksl" "lines" 1 "characters" 38 "sha256" "50dc6a6ce24cce7551eb372b7cdade151acf43a1febb9d9169e230aaf8f29b3c"} {"name" "vertex" "language" "glsl" "path" "sigmoid-punch_vertex_.glsl" "lines" 9 "characters" 271 "sha256" "6f8ea62eb2f0225f87b4294947183d5993e9c7b5a31ce7896a5252da9012bd88"}]}
```

```kotlin [field-property box="sigmoid-punch" name="code" path="sigmoid-punch_code_.kt"]
// A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
// slot; this box's `stackSL` property fills it. Edit the stackSL live and the
// shader re-binds in place.
//
// [[property stackSL]]

val module = moduleHere { }
val m = Mesh()
module += m named "fullscreen"
m.resize(3) // three vertices, no attributes: the vertex shader makes the triangle

// Every box in this gallery draws the whole window, so they all register under one
// name: launching a box replaces whichever one the window was showing.
renderer += "stacksl-gallery" to module

// Bind this box's StackSL source into the `program` slot (compiles + hot-reloads).
// `stackSL` is null when no box up the graph has one; bind the empty program then.
module.program = stackSL ?: ""
```

```glsl [field-property box="sigmoid-punch" name="fragment" path="sigmoid-punch_fragment_.glsl"]
#version 450
layout(location = 0) in vec2 fragUV;
layout(location = 0) out vec4 outColor;

float gallery_hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

{{ stack.auto.program }}

void main() {
    vec2 uv = fragUV;
    vec4 stackInput = vec4(uv, gallery_hash12(uv), 1.7500);
    vec3 c = program(stackInput).rgb;
    if (any(isnan(c)) || any(isinf(c))) c = vec3(0.0);
    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
```

```stacksl [field-property box="sigmoid-punch" name="stackSL" path="sigmoid-punch_stackSL_.stacksl"]
uv 4 * fbm 8 sigmoid palette saturate
```

```glsl [field-property box="sigmoid-punch" name="vertex" path="sigmoid-punch_vertex_.glsl"]
#version 450
layout(location = 0) out vec2 fragUV;

void main() {
    // Fullscreen triangle from gl_VertexIndex, no vertex inputs.
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
    fragUV = pos;
}
```

## 8. soft-ring

<!-- field-box id="soft-ring" index="7" -->

### Narrative

From `code`

A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
slot; this box's `stackSL` property fills it. Edit the stackSL live and the
shader re-binds in place.

[[property stackSL]]

```edn [field-box id="soft-ring" role="properties"]
{"id" "soft-ring" "index" 7 "name" "soft-ring" "parents" ["root" "renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "renderer"} "name" "soft-ring" "_tombstone" false "frame" #afield/rect [1120.0 170.0 300.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "soft-ring_code_.kt" "lines" 18 "characters" 772 "sha256" "d6fbc2aa9eb5a571f36c6f9721ff1aaf978b56295cec491f4adbf29a9f2e759e"} {"name" "fragment" "language" "glsl" "path" "soft-ring_fragment_.glsl" "lines" 19 "characters" 506 "sha256" "7a6b03f7f6d7537a3a20920b336c7c9271bb9616f9c6f1a8c0056a733b57a138"} {"name" "stackSL" "language" "stacksl" "path" "soft-ring_stackSL_.stacksl" "lines" 1 "characters" 32 "sha256" "5c243cb73f6bc721674e28e9bbbc62e230d2a036e7cb8298516375f0a7914305"} {"name" "vertex" "language" "glsl" "path" "soft-ring_vertex_.glsl" "lines" 9 "characters" 271 "sha256" "6f8ea62eb2f0225f87b4294947183d5993e9c7b5a31ce7896a5252da9012bd88"}]}
```

```kotlin [field-property box="soft-ring" name="code" path="soft-ring_code_.kt"]
// A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
// slot; this box's `stackSL` property fills it. Edit the stackSL live and the
// shader re-binds in place.
//
// [[property stackSL]]

val module = moduleHere { }
val m = Mesh()
module += m named "fullscreen"
m.resize(3) // three vertices, no attributes: the vertex shader makes the triangle

// Every box in this gallery draws the whole window, so they all register under one
// name: launching a box replaces whichever one the window was showing.
renderer += "stacksl-gallery" to module

// Bind this box's StackSL source into the `program` slot (compiles + hot-reloads).
// `stackSL` is null when no box up the graph has one; bind the empty program then.
module.program = stackSL ?: ""
```

```glsl [field-property box="soft-ring" name="fragment" path="soft-ring_fragment_.glsl"]
#version 450
layout(location = 0) in vec2 fragUV;
layout(location = 0) out vec4 outColor;

float gallery_hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

{{ stack.auto.program }}

void main() {
    vec2 uv = fragUV;
    vec4 stackInput = vec4(uv, gallery_hash12(uv), 1.7500);
    vec3 c = program(stackInput).rgb;
    if (any(isnan(c)) || any(isinf(c))) c = vec3(0.0);
    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
```

```stacksl [field-property box="soft-ring" name="stackSL" path="soft-ring_stackSL_.stacksl"]
p length 0.5 0.08 pulse white *
```

```glsl [field-property box="soft-ring" name="vertex" path="soft-ring_vertex_.glsl"]
#version 450
layout(location = 0) out vec2 fragUV;

void main() {
    // Fullscreen triangle from gl_VertexIndex, no vertex inputs.
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
    fragUV = pos;
}
```

## 9. polar-sigmoid

<!-- field-box id="polar-sigmoid" index="8" -->

### Narrative

From `code`

A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
slot; this box's `stackSL` property fills it. Edit the stackSL live and the
shader re-binds in place.

[[property stackSL]]

```edn [field-box id="polar-sigmoid" role="properties"]
{"id" "polar-sigmoid" "index" 8 "name" "polar-sigmoid" "parents" ["root" "renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "renderer"} "name" "polar-sigmoid" "_tombstone" false "frame" #afield/rect [400.0 300.0 300.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "polar-sigmoid_code_.kt" "lines" 18 "characters" 772 "sha256" "d6fbc2aa9eb5a571f36c6f9721ff1aaf978b56295cec491f4adbf29a9f2e759e"} {"name" "fragment" "language" "glsl" "path" "polar-sigmoid_fragment_.glsl" "lines" 19 "characters" 506 "sha256" "7a6b03f7f6d7537a3a20920b336c7c9271bb9616f9c6f1a8c0056a733b57a138"} {"name" "stackSL" "language" "stacksl" "path" "polar-sigmoid_stackSL_.stacksl" "lines" 6 "characters" 49 "sha256" "881cd83550cfe22b9f0323889856f1215cfe0532f48e1bcafbde5442fecacc2f"} {"name" "vertex" "language" "glsl" "path" "polar-sigmoid_vertex_.glsl" "lines" 9 "characters" 271 "sha256" "6f8ea62eb2f0225f87b4294947183d5993e9c7b5a31ce7896a5252da9012bd88"}]}
```

```kotlin [field-property box="polar-sigmoid" name="code" path="polar-sigmoid_code_.kt"]
// A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
// slot; this box's `stackSL` property fills it. Edit the stackSL live and the
// shader re-binds in place.
//
// [[property stackSL]]

val module = moduleHere { }
val m = Mesh()
module += m named "fullscreen"
m.resize(3) // three vertices, no attributes: the vertex shader makes the triangle

// Every box in this gallery draws the whole window, so they all register under one
// name: launching a box replaces whichever one the window was showing.
renderer += "stacksl-gallery" to module

// Bind this box's StackSL source into the `program` slot (compiles + hot-reloads).
// `stackSL` is null when no box up the graph has one; bind the empty program then.
module.program = stackSL ?: ""
```

```glsl [field-property box="polar-sigmoid" name="fragment" path="polar-sigmoid_fragment_.glsl"]
#version 450
layout(location = 0) in vec2 fragUV;
layout(location = 0) out vec4 outColor;

float gallery_hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

{{ stack.auto.program }}

void main() {
    vec2 uv = fragUV;
    vec4 stackInput = vec4(uv, gallery_hash12(uv), 1.7500);
    vec3 c = program(stackInput).rgb;
    if (any(isnan(c)) || any(isinf(c))) c = vec3(0.0);
    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
```

```stacksl [field-property box="polar-sigmoid" name="stackSL" path="polar-sigmoid_stackSL_.stacksl"]
p polar
dup y 0.2t + >y
cart
fbm
sigmoid
palette
```

```glsl [field-property box="polar-sigmoid" name="vertex" path="polar-sigmoid_vertex_.glsl"]
#version 450
layout(location = 0) out vec2 fragUV;

void main() {
    // Fullscreen triangle from gl_VertexIndex, no vertex inputs.
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
    fragUV = pos;
}
```

## 10. radial-waves

<!-- field-box id="radial-waves" index="9" -->

### Narrative

From `code`

A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
slot; this box's `stackSL` property fills it. Edit the stackSL live and the
shader re-binds in place.

[[property stackSL]]

```edn [field-box id="radial-waves" role="properties"]
{"id" "radial-waves" "index" 9 "name" "radial-waves" "parents" ["root" "renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "renderer"} "name" "radial-waves" "_tombstone" false "frame" #afield/rect [400.0 690.0 300.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "radial-waves_code_.kt" "lines" 18 "characters" 772 "sha256" "d6fbc2aa9eb5a571f36c6f9721ff1aaf978b56295cec491f4adbf29a9f2e759e"} {"name" "fragment" "language" "glsl" "path" "radial-waves_fragment_.glsl" "lines" 19 "characters" 506 "sha256" "7a6b03f7f6d7537a3a20920b336c7c9271bb9616f9c6f1a8c0056a733b57a138"} {"name" "stackSL" "language" "stacksl" "path" "radial-waves_stackSL_.stacksl" "lines" 1 "characters" 25 "sha256" "e085d4d22327d213016a801fbfe78ebe7148d3f1290c6cfe511a36647751fbe8"} {"name" "vertex" "language" "glsl" "path" "radial-waves_vertex_.glsl" "lines" 9 "characters" 271 "sha256" "6f8ea62eb2f0225f87b4294947183d5993e9c7b5a31ce7896a5252da9012bd88"}]}
```

```kotlin [field-property box="radial-waves" name="code" path="radial-waves_code_.kt"]
// A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
// slot; this box's `stackSL` property fills it. Edit the stackSL live and the
// shader re-binds in place.
//
// [[property stackSL]]

val module = moduleHere { }
val m = Mesh()
module += m named "fullscreen"
m.resize(3) // three vertices, no attributes: the vertex shader makes the triangle

// Every box in this gallery draws the whole window, so they all register under one
// name: launching a box replaces whichever one the window was showing.
renderer += "stacksl-gallery" to module

// Bind this box's StackSL source into the `program` slot (compiles + hot-reloads).
// `stackSL` is null when no box up the graph has one; bind the empty program then.
module.program = stackSL ?: ""
```

```glsl [field-property box="radial-waves" name="fragment" path="radial-waves_fragment_.glsl"]
#version 450
layout(location = 0) in vec2 fragUV;
layout(location = 0) out vec4 outColor;

float gallery_hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

{{ stack.auto.program }}

void main() {
    vec2 uv = fragUV;
    vec4 stackInput = vec4(uv, gallery_hash12(uv), 1.7500);
    vec3 c = program(stackInput).rgb;
    if (any(isnan(c)) || any(isinf(c))) c = vec3(0.0);
    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
```

```stacksl [field-property box="radial-waves" name="stackSL" path="radial-waves_stackSL_.stacksl"]
p ]wr14 palette saturate
```

```glsl [field-property box="radial-waves" name="vertex" path="radial-waves_vertex_.glsl"]
#version 450
layout(location = 0) out vec2 fragUV;

void main() {
    // Fullscreen triangle from gl_VertexIndex, no vertex inputs.
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
    fragUV = pos;
}
```

## 11. local-marks

<!-- field-box id="local-marks" index="10" -->

### Narrative

From `code`

A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
slot; this box's `stackSL` property fills it. Edit the stackSL live and the
shader re-binds in place.

[[property stackSL]]

```edn [field-box id="local-marks" role="properties"]
{"id" "local-marks" "index" 10 "name" "local-marks" "parents" ["root" "renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "renderer"} "name" "local-marks" "_tombstone" false "frame" #afield/rect [760.0 1080.0 300.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "local-marks_code_.kt" "lines" 18 "characters" 772 "sha256" "d6fbc2aa9eb5a571f36c6f9721ff1aaf978b56295cec491f4adbf29a9f2e759e"} {"name" "fragment" "language" "glsl" "path" "local-marks_fragment_.glsl" "lines" 19 "characters" 506 "sha256" "7a6b03f7f6d7537a3a20920b336c7c9271bb9616f9c6f1a8c0056a733b57a138"} {"name" "stackSL" "language" "stacksl" "path" "local-marks_stackSL_.stacksl" "lines" 5 "characters" 54 "sha256" "09a23e4188179827a7b0d6f3f914e65a902b173007f277911656f1b1a77f3bf1"} {"name" "vertex" "language" "glsl" "path" "local-marks_vertex_.glsl" "lines" 9 "characters" 271 "sha256" "6f8ea62eb2f0225f87b4294947183d5993e9c7b5a31ce7896a5252da9012bd88"}]}
```

```kotlin [field-property box="local-marks" name="code" path="local-marks_code_.kt"]
// A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
// slot; this box's `stackSL` property fills it. Edit the stackSL live and the
// shader re-binds in place.
//
// [[property stackSL]]

val module = moduleHere { }
val m = Mesh()
module += m named "fullscreen"
m.resize(3) // three vertices, no attributes: the vertex shader makes the triangle

// Every box in this gallery draws the whole window, so they all register under one
// name: launching a box replaces whichever one the window was showing.
renderer += "stacksl-gallery" to module

// Bind this box's StackSL source into the `program` slot (compiles + hot-reloads).
// `stackSL` is null when no box up the graph has one; bind the empty program then.
module.program = stackSL ?: ""
```

```glsl [field-property box="local-marks" name="fragment" path="local-marks_fragment_.glsl"]
#version 450
layout(location = 0) in vec2 fragUV;
layout(location = 0) out vec4 outColor;

float gallery_hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

{{ stack.auto.program }}

void main() {
    vec2 uv = fragUV;
    vec4 stackInput = vec4(uv, gallery_hash12(uv), 1.7500);
    vec3 c = program(stackInput).rgb;
    if (any(isnan(c)) || any(isinf(c))) c = vec3(0.0);
    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
```

```stacksl [field-property box="local-marks" name="stackSL" path="local-marks_stackSL_.stacksl"]
uv 8 * =c
c fbm
c 0.35 + fbm
0.5 mix
palette saturate
```

```glsl [field-property box="local-marks" name="vertex" path="local-marks_vertex_.glsl"]
#version 450
layout(location = 0) out vec2 fragUV;

void main() {
    // Fullscreen triangle from gl_VertexIndex, no vertex inputs.
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
    fragUV = pos;
}
```

## 12. mirror-tiles

<!-- field-box id="mirror-tiles" index="11" -->

### Narrative

From `code`

A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
slot; this box's `stackSL` property fills it. Edit the stackSL live and the
shader re-binds in place.

[[property stackSL]]

```edn [field-box id="mirror-tiles" role="properties"]
{"id" "mirror-tiles" "index" 11 "name" "mirror-tiles" "parents" ["root" "renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "renderer"} "name" "mirror-tiles" "_tombstone" false "frame" #afield/rect [760.0 690.0 300.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "mirror-tiles_code_.kt" "lines" 18 "characters" 772 "sha256" "d6fbc2aa9eb5a571f36c6f9721ff1aaf978b56295cec491f4adbf29a9f2e759e"} {"name" "fragment" "language" "glsl" "path" "mirror-tiles_fragment_.glsl" "lines" 19 "characters" 506 "sha256" "7a6b03f7f6d7537a3a20920b336c7c9271bb9616f9c6f1a8c0056a733b57a138"} {"name" "stackSL" "language" "stacksl" "path" "mirror-tiles_stackSL_.stacksl" "lines" 1 "characters" 35 "sha256" "424cfbb402d0a5f4822acb224d161c9e5b1858d92ce9c995cb40439fd6e10ea2"} {"name" "vertex" "language" "glsl" "path" "mirror-tiles_vertex_.glsl" "lines" 9 "characters" 271 "sha256" "6f8ea62eb2f0225f87b4294947183d5993e9c7b5a31ce7896a5252da9012bd88"}]}
```

```kotlin [field-property box="mirror-tiles" name="code" path="mirror-tiles_code_.kt"]
// A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
// slot; this box's `stackSL` property fills it. Edit the stackSL live and the
// shader re-binds in place.
//
// [[property stackSL]]

val module = moduleHere { }
val m = Mesh()
module += m named "fullscreen"
m.resize(3) // three vertices, no attributes: the vertex shader makes the triangle

// Every box in this gallery draws the whole window, so they all register under one
// name: launching a box replaces whichever one the window was showing.
renderer += "stacksl-gallery" to module

// Bind this box's StackSL source into the `program` slot (compiles + hot-reloads).
// `stackSL` is null when no box up the graph has one; bind the empty program then.
module.program = stackSL ?: ""
```

```glsl [field-property box="mirror-tiles" name="fragment" path="mirror-tiles_fragment_.glsl"]
#version 450
layout(location = 0) in vec2 fragUV;
layout(location = 0) out vec4 outColor;

float gallery_hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

{{ stack.auto.program }}

void main() {
    vec2 uv = fragUV;
    vec4 stackInput = vec4(uv, gallery_hash12(uv), 1.7500);
    vec3 c = program(stackInput).rgb;
    if (any(isnan(c)) || any(isinf(c))) c = vec3(0.0);
    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
```

```stacksl [field-property box="mirror-tiles" name="stackSL" path="mirror-tiles_stackSL_.stacksl"]
uv 4 * mirror fbm palette saturate
```

```glsl [field-property box="mirror-tiles" name="vertex" path="mirror-tiles_vertex_.glsl"]
#version 450
layout(location = 0) out vec2 fragUV;

void main() {
    // Fullscreen triangle from gl_VertexIndex, no vertex inputs.
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
    fragUV = pos;
}
```

## 13. auto-before

<!-- field-box id="auto-before" index="12" -->

### Narrative

From `code`

A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
slot; this box's `stackSL` property fills it. Edit the stackSL live and the
shader re-binds in place.

[[property stackSL]]

```edn [field-box id="auto-before" role="properties"]
{"id" "auto-before" "index" 12 "name" "auto-before" "parents" ["root" "renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "renderer"} "name" "auto-before" "_tombstone" false "frame" #afield/rect [400.0 560.0 300.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "auto-before_code_.kt" "lines" 18 "characters" 772 "sha256" "d6fbc2aa9eb5a571f36c6f9721ff1aaf978b56295cec491f4adbf29a9f2e759e"} {"name" "fragment" "language" "glsl" "path" "auto-before_fragment_.glsl" "lines" 19 "characters" 506 "sha256" "7a6b03f7f6d7537a3a20920b336c7c9271bb9616f9c6f1a8c0056a733b57a138"} {"name" "stackSL" "language" "stacksl" "path" "auto-before_stackSL_.stacksl" "lines" 1 "characters" 30 "sha256" "d8979753d374066ff1673a35f73797ad374f363bf3c5f2b798aa8de866a11198"} {"name" "vertex" "language" "glsl" "path" "auto-before_vertex_.glsl" "lines" 9 "characters" 271 "sha256" "6f8ea62eb2f0225f87b4294947183d5993e9c7b5a31ce7896a5252da9012bd88"}]}
```

```kotlin [field-property box="auto-before" name="code" path="auto-before_code_.kt"]
// A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
// slot; this box's `stackSL` property fills it. Edit the stackSL live and the
// shader re-binds in place.
//
// [[property stackSL]]

val module = moduleHere { }
val m = Mesh()
module += m named "fullscreen"
m.resize(3) // three vertices, no attributes: the vertex shader makes the triangle

// Every box in this gallery draws the whole window, so they all register under one
// name: launching a box replaces whichever one the window was showing.
renderer += "stacksl-gallery" to module

// Bind this box's StackSL source into the `program` slot (compiles + hot-reloads).
// `stackSL` is null when no box up the graph has one; bind the empty program then.
module.program = stackSL ?: ""
```

```glsl [field-property box="auto-before" name="fragment" path="auto-before_fragment_.glsl"]
#version 450
layout(location = 0) in vec2 fragUV;
layout(location = 0) out vec4 outColor;

float gallery_hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

{{ stack.auto.program }}

void main() {
    vec2 uv = fragUV;
    vec4 stackInput = vec4(uv, gallery_hash12(uv), 1.7500);
    vec3 c = program(stackInput).rgb;
    if (any(isnan(c)) || any(isinf(c))) c = vec3(0.0);
    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
```

```stacksl [field-property box="auto-before" name="stackSL" path="auto-before_stackSL_.stacksl"]
uv 4.0 * fbm palette saturate
```

```glsl [field-property box="auto-before" name="vertex" path="auto-before_vertex_.glsl"]
#version 450
layout(location = 0) out vec2 fragUV;

void main() {
    // Fullscreen triangle from gl_VertexIndex, no vertex inputs.
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
    fragUV = pos;
}
```

## 14. diffusion-feedback

<!-- field-box id="diffusion-feedback" index="13" -->

### Narrative

From `code`

A feedback StackSL box. The fragment shader has a `{{ stack.auto.program }}`
slot, and the program in this box's `stackSL` property samples `source`: the
frame this box drew last time, so the picture feeds back into itself.

A window cannot be read back like that, so this box draws into a ping-pong
target instead. It holds two images, draws into one while `source` reads the
other, and swaps them every frame; `blitToScreen` puts the newest in the window.

[[property stackSL]]

```edn [field-box id="diffusion-feedback" role="properties"]
{"id" "diffusion-feedback" "index" 13 "name" "diffusion-feedback" "parents" ["root" "renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "renderer"} "name" "diffusion-feedback" "_tombstone" false "frame" #afield/rect [1120.0 1080.0 300.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "diffusion-feedback_code_.kt" "lines" 39 "characters" 1770 "sha256" "6ae37b30e2ace587b5d86479b8cec8df13b7ddfa5b960389f676fe0123a3f3a8"} {"name" "fragment" "language" "glsl" "path" "diffusion-feedback_fragment_.glsl" "lines" 23 "characters" 696 "sha256" "b3bd19fe34573fcd02d2e805818e25da08311388465d75194e06acd6d7fe923c"} {"name" "stackSL" "language" "stacksl" "path" "diffusion-feedback_stackSL_.stacksl" "lines" 5 "characters" 71 "sha256" "e18e980bfc936cea82a4cb965734f8ef26df1c511987a5b6cb8ff21e6c5de88b"} {"name" "vertex" "language" "glsl" "path" "diffusion-feedback_vertex_.glsl" "lines" 9 "characters" 271 "sha256" "6f8ea62eb2f0225f87b4294947183d5993e9c7b5a31ce7896a5252da9012bd88"}]}
```

```kotlin [field-property box="diffusion-feedback" name="code" path="diffusion-feedback_code_.kt"]
// A feedback StackSL box. The fragment shader has a `{{ stack.auto.program }}`
// slot, and the program in this box's `stackSL` property samples `source`: the
// frame this box drew last time, so the picture feeds back into itself.
//
// A window cannot be read back like that, so this box draws into a ping-pong
// target instead. It holds two images, draws into one while `source` reads the
// other, and swaps them every frame; `blitToScreen` puts the newest in the window.
//
// [[property stackSL]]

import afield.graphics.vulkan.targets.PingPongRenderToTexture

prop<afield.graphics.vulkan.targets.PingPongRenderToTexture>(target)
prop<afield.graphics.texture.HasImageView>(source)

// `once`, so re-running the box keeps the images and the trails already in them.
once.target {
    PingPongRenderToTexture(renderer) { useDepth = false }
}
source = target.otherImageView

val module = moduleHere { target.apply() }
val m = Mesh()
module += m named "fullscreen"
m.resize(3) // three vertices, no attributes: the vertex shader makes the triangle
target += "fullscreen" to module

// pc.params.x advances per rendered frame. Edit the scale below to change its rate;
// a faster window frame rate also advances more program time per wall-clock second.
val first = renderer.frameCounter
m.params = { vec((renderer.frameCounter - first) * 1.0 / 48, 0.0, 0.0, 0.0) }

// Bind this box's StackSL source into the `program` slot (compiles + hot-reloads).
// `stackSL` is null when no box up the graph has one; bind the empty program then.
module.program = stackSL ?: ""

// Every box in this gallery draws the whole window, so they all register under one
// name: launching a box replaces whichever one the window was showing.
target.blitToScreen(name = "stacksl-gallery")
```

```glsl [field-property box="diffusion-feedback" name="fragment" path="diffusion-feedback_fragment_.glsl"]
#version 450
layout(location = 0) in vec2 fragUV;
layout(location = 0) out vec4 outColor;

// Feedback: `source` is the previous frame (bound by a ping-pong host).
layout(set = 0, binding = 0) uniform sampler2D source;
layout(push_constant) uniform Push { vec4 params; } pc;

float gallery_hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

{{ stack.auto.program }}

void main() {
    vec2 uv = fragUV;
    vec4 stackInput = vec4(uv, gallery_hash12(uv), pc.params.x);
    vec3 c = program(stackInput).rgb;
    if (any(isnan(c)) || any(isinf(c))) c = vec3(0.0);
    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
```

```stacksl [field-property box="diffusion-feedback" name="stackSL" path="diffusion-feedback_stackSL_.stacksl"]
0.004 blur
0.975 *
uv 5 * 0.25t + fbm 0.62 threshold 0.05 *
+
saturate
```

```glsl [field-property box="diffusion-feedback" name="vertex" path="diffusion-feedback_vertex_.glsl"]
#version 450
layout(location = 0) out vec2 fragUV;

void main() {
    // Fullscreen triangle from gl_VertexIndex, no vertex inputs.
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
    fragUV = pos;
}
```

## 15. value-noise

<!-- field-box id="value-noise" index="14" -->

### Narrative

From `code`

A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
slot; this box's `stackSL` property fills it. Edit the stackSL live and the
shader re-binds in place.

[[property stackSL]]

```edn [field-box id="value-noise" role="properties"]
{"id" "value-noise" "index" 14 "name" "value-noise" "parents" ["root" "renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "renderer"} "name" "value-noise" "_tombstone" false "frame" #afield/rect [400.0 950.0 300.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "value-noise_code_.kt" "lines" 18 "characters" 772 "sha256" "d6fbc2aa9eb5a571f36c6f9721ff1aaf978b56295cec491f4adbf29a9f2e759e"} {"name" "fragment" "language" "glsl" "path" "value-noise_fragment_.glsl" "lines" 19 "characters" 506 "sha256" "7a6b03f7f6d7537a3a20920b336c7c9271bb9616f9c6f1a8c0056a733b57a138"} {"name" "stackSL" "language" "stacksl" "path" "value-noise_stackSL_.stacksl" "lines" 1 "characters" 30 "sha256" "d858bed53064799e49aed60aa23a00b955528fe877893bb37d6fb1619acdba66"} {"name" "vertex" "language" "glsl" "path" "value-noise_vertex_.glsl" "lines" 9 "characters" 271 "sha256" "6f8ea62eb2f0225f87b4294947183d5993e9c7b5a31ce7896a5252da9012bd88"}]}
```

```kotlin [field-property box="value-noise" name="code" path="value-noise_code_.kt"]
// A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
// slot; this box's `stackSL` property fills it. Edit the stackSL live and the
// shader re-binds in place.
//
// [[property stackSL]]

val module = moduleHere { }
val m = Mesh()
module += m named "fullscreen"
m.resize(3) // three vertices, no attributes: the vertex shader makes the triangle

// Every box in this gallery draws the whole window, so they all register under one
// name: launching a box replaces whichever one the window was showing.
renderer += "stacksl-gallery" to module

// Bind this box's StackSL source into the `program` slot (compiles + hot-reloads).
// `stackSL` is null when no box up the graph has one; bind the empty program then.
module.program = stackSL ?: ""
```

```glsl [field-property box="value-noise" name="fragment" path="value-noise_fragment_.glsl"]
#version 450
layout(location = 0) in vec2 fragUV;
layout(location = 0) out vec4 outColor;

float gallery_hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

{{ stack.auto.program }}

void main() {
    vec2 uv = fragUV;
    vec4 stackInput = vec4(uv, gallery_hash12(uv), 1.7500);
    vec3 c = program(stackInput).rgb;
    if (any(isnan(c)) || any(isinf(c))) c = vec3(0.0);
    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
```

```stacksl [field-property box="value-noise" name="stackSL" path="value-noise_stackSL_.stacksl"]
uv 6 * noise palette saturate
```

```glsl [field-property box="value-noise" name="vertex" path="value-noise_vertex_.glsl"]
#version 450
layout(location = 0) out vec2 fragUV;

void main() {
    // Fullscreen triangle from gl_VertexIndex, no vertex inputs.
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
    fragUV = pos;
}
```

## 16. word-reference

<!-- field-box id="word-reference" index="15" -->

### Narrative

From `code`

A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
slot; this box's `stackSL` property fills it. Edit the stackSL live and the
shader re-binds in place.

[[property stackSL]]

```edn [field-box id="word-reference" role="properties"]
{"id" "word-reference" "index" 15 "name" "word-reference" "parents" ["root" "renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "renderer"} "name" "word-reference" "_tombstone" false "frame" #afield/rect [1120.0 950.0 300.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "word-reference_code_.kt" "lines" 18 "characters" 772 "sha256" "d6fbc2aa9eb5a571f36c6f9721ff1aaf978b56295cec491f4adbf29a9f2e759e"} {"name" "fragment" "language" "glsl" "path" "word-reference_fragment_.glsl" "lines" 19 "characters" 506 "sha256" "7a6b03f7f6d7537a3a20920b336c7c9271bb9616f9c6f1a8c0056a733b57a138"} {"name" "stackSL" "language" "stacksl" "path" "word-reference_stackSL_.stacksl" "lines" 2 "characters" 66 "sha256" "3ade34dc358cde038a9a2121aa773dc6cd4cdb47426e1587e9ad954ce9d9022a"} {"name" "vertex" "language" "glsl" "path" "word-reference_vertex_.glsl" "lines" 9 "characters" 271 "sha256" "6f8ea62eb2f0225f87b4294947183d5993e9c7b5a31ce7896a5252da9012bd88"}]}
```

```kotlin [field-property box="word-reference" name="code" path="word-reference_code_.kt"]
// A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
// slot; this box's `stackSL` property fills it. Edit the stackSL live and the
// shader re-binds in place.
//
// [[property stackSL]]

val module = moduleHere { }
val m = Mesh()
module += m named "fullscreen"
m.resize(3) // three vertices, no attributes: the vertex shader makes the triangle

// Every box in this gallery draws the whole window, so they all register under one
// name: launching a box replaces whichever one the window was showing.
renderer += "stacksl-gallery" to module

// Bind this box's StackSL source into the `program` slot (compiles + hot-reloads).
// `stackSL` is null when no box up the graph has one; bind the empty program then.
module.program = stackSL ?: ""
```

```glsl [field-property box="word-reference" name="fragment" path="word-reference_fragment_.glsl"]
#version 450
layout(location = 0) in vec2 fragUV;
layout(location = 0) out vec4 outColor;

float gallery_hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

{{ stack.auto.program }}

void main() {
    vec2 uv = fragUV;
    vec4 stackInput = vec4(uv, gallery_hash12(uv), 1.7500);
    vec3 c = program(stackInput).rgb;
    if (any(isnan(c)) || any(isinf(c))) c = vec3(0.0);
    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
```

```stacksl [field-property box="word-reference" name="stackSL" path="word-reference_stackSL_.stacksl"]
: ridge fbm 0.42 0.08 pulse ;
'ridge p 5 * apply palette saturate
```

```glsl [field-property box="word-reference" name="vertex" path="word-reference_vertex_.glsl"]
#version 450
layout(location = 0) out vec2 fragUV;

void main() {
    // Fullscreen triangle from gl_VertexIndex, no vertex inputs.
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
    fragUV = pos;
}
```

## 17. kaleido-twist

<!-- field-box id="kaleido-twist" index="16" -->

### Narrative

From `code`

A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
slot; this box's `stackSL` property fills it. Edit the stackSL live and the
shader re-binds in place.

[[property stackSL]]

```edn [field-box id="kaleido-twist" role="properties"]
{"id" "kaleido-twist" "index" 16 "name" "kaleido-twist" "parents" ["root" "renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "renderer"} "name" "kaleido-twist" "_tombstone" false "frame" #afield/rect [1480.0 170.0 300.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kaleido-twist_code_.kt" "lines" 18 "characters" 772 "sha256" "d6fbc2aa9eb5a571f36c6f9721ff1aaf978b56295cec491f4adbf29a9f2e759e"} {"name" "fragment" "language" "glsl" "path" "kaleido-twist_fragment_.glsl" "lines" 19 "characters" 506 "sha256" "7a6b03f7f6d7537a3a20920b336c7c9271bb9616f9c6f1a8c0056a733b57a138"} {"name" "stackSL" "language" "stacksl" "path" "kaleido-twist_stackSL_.stacksl" "lines" 6 "characters" 49 "sha256" "4fe4765d0dd94c86660342d18c7e5e959ca54324125ed6440fd940f22cc53051"} {"name" "vertex" "language" "glsl" "path" "kaleido-twist_vertex_.glsl" "lines" 9 "characters" 271 "sha256" "6f8ea62eb2f0225f87b4294947183d5993e9c7b5a31ce7896a5252da9012bd88"}]}
```

```kotlin [field-property box="kaleido-twist" name="code" path="kaleido-twist_code_.kt"]
// A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
// slot; this box's `stackSL` property fills it. Edit the stackSL live and the
// shader re-binds in place.
//
// [[property stackSL]]

val module = moduleHere { }
val m = Mesh()
module += m named "fullscreen"
m.resize(3) // three vertices, no attributes: the vertex shader makes the triangle

// Every box in this gallery draws the whole window, so they all register under one
// name: launching a box replaces whichever one the window was showing.
renderer += "stacksl-gallery" to module

// Bind this box's StackSL source into the `program` slot (compiles + hot-reloads).
// `stackSL` is null when no box up the graph has one; bind the empty program then.
module.program = stackSL ?: ""
```

```glsl [field-property box="kaleido-twist" name="fragment" path="kaleido-twist_fragment_.glsl"]
#version 450
layout(location = 0) in vec2 fragUV;
layout(location = 0) out vec4 outColor;

float gallery_hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

{{ stack.auto.program }}

void main() {
    vec2 uv = fragUV;
    vec4 stackInput = vec4(uv, gallery_hash12(uv), 1.7500);
    vec3 c = program(stackInput).rgb;
    if (any(isnan(c)) || any(isinf(c))) c = vec3(0.0);
    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
```

```stacksl [field-property box="kaleido-twist" name="stackSL" path="kaleido-twist_stackSL_.stacksl"]
p 6 kaleido
1.7 twist
3.0 *
fbm
palette
saturate
```

```glsl [field-property box="kaleido-twist" name="vertex" path="kaleido-twist_vertex_.glsl"]
#version 450
layout(location = 0) out vec2 fragUV;

void main() {
    // Fullscreen triangle from gl_VertexIndex, no vertex inputs.
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
    fragUV = pos;
}
```

## 18. oklab-shift

<!-- field-box id="oklab-shift" index="17" -->

### Narrative

From `code`

A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
slot; this box's `stackSL` property fills it. Edit the stackSL live and the
shader re-binds in place.

[[property stackSL]]

```edn [field-box id="oklab-shift" role="properties"]
{"id" "oklab-shift" "index" 17 "name" "oklab-shift" "parents" ["root" "renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "renderer"} "name" "oklab-shift" "_tombstone" false "frame" #afield/rect [760.0 430.0 300.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "oklab-shift_code_.kt" "lines" 18 "characters" 772 "sha256" "d6fbc2aa9eb5a571f36c6f9721ff1aaf978b56295cec491f4adbf29a9f2e759e"} {"name" "fragment" "language" "glsl" "path" "oklab-shift_fragment_.glsl" "lines" 19 "characters" 506 "sha256" "7a6b03f7f6d7537a3a20920b336c7c9271bb9616f9c6f1a8c0056a733b57a138"} {"name" "stackSL" "language" "stacksl" "path" "oklab-shift_stackSL_.stacksl" "lines" 6 "characters" 81 "sha256" "d626c44b66c7cbd2ff1d97e1bdb1061e924c6ec63b4f8b65fcbdcd37ed21c6f2"} {"name" "vertex" "language" "glsl" "path" "oklab-shift_vertex_.glsl" "lines" 9 "characters" 271 "sha256" "6f8ea62eb2f0225f87b4294947183d5993e9c7b5a31ce7896a5252da9012bd88"}]}
```

```kotlin [field-property box="oklab-shift" name="code" path="oklab-shift_code_.kt"]
// A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
// slot; this box's `stackSL` property fills it. Edit the stackSL live and the
// shader re-binds in place.
//
// [[property stackSL]]

val module = moduleHere { }
val m = Mesh()
module += m named "fullscreen"
m.resize(3) // three vertices, no attributes: the vertex shader makes the triangle

// Every box in this gallery draws the whole window, so they all register under one
// name: launching a box replaces whichever one the window was showing.
renderer += "stacksl-gallery" to module

// Bind this box's StackSL source into the `program` slot (compiles + hot-reloads).
// `stackSL` is null when no box up the graph has one; bind the empty program then.
module.program = stackSL ?: ""
```

```glsl [field-property box="oklab-shift" name="fragment" path="oklab-shift_fragment_.glsl"]
#version 450
layout(location = 0) in vec2 fragUV;
layout(location = 0) out vec4 outColor;

float gallery_hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

{{ stack.auto.program }}

void main() {
    vec2 uv = fragUV;
    vec4 stackInput = vec4(uv, gallery_hash12(uv), 1.7500);
    vec3 c = program(stackInput).rgb;
    if (any(isnan(c)) || any(isinf(c))) c = vec3(0.0);
    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
```

```stacksl [field-property box="oklab-shift" name="stackSL" path="oklab-shift_stackSL_.stacksl"]
uv 5.0 * fbm palette
rgb2oklab
time sin 0.05 * 0 0 0 vec4
add
oklab2rgb
saturate
```

```glsl [field-property box="oklab-shift" name="vertex" path="oklab-shift_vertex_.glsl"]
#version 450
layout(location = 0) out vec2 fragUV;

void main() {
    // Fullscreen triangle from gl_VertexIndex, no vertex inputs.
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
    fragUV = pos;
}
```

## 19. custom-palette

<!-- field-box id="custom-palette" index="18" -->

### Narrative

From `code`

A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
slot; this box's `stackSL` property fills it. Edit the stackSL live and the
shader re-binds in place.

[[property stackSL]]

```edn [field-box id="custom-palette" role="properties"]
{"id" "custom-palette" "index" 18 "name" "custom-palette" "parents" ["root" "renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "renderer"} "name" "custom-palette" "_tombstone" false "frame" #afield/rect [400.0 820.0 300.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "custom-palette_code_.kt" "lines" 18 "characters" 772 "sha256" "d6fbc2aa9eb5a571f36c6f9721ff1aaf978b56295cec491f4adbf29a9f2e759e"} {"name" "fragment" "language" "glsl" "path" "custom-palette_fragment_.glsl" "lines" 19 "characters" 506 "sha256" "7a6b03f7f6d7537a3a20920b336c7c9271bb9616f9c6f1a8c0056a733b57a138"} {"name" "stackSL" "language" "stacksl" "path" "custom-palette_stackSL_.stacksl" "lines" 6 "characters" 97 "sha256" "05b8a3becbd5f5d3a8b019db9562addcb27e4678a32e077476d794db8bb7e286"} {"name" "vertex" "language" "glsl" "path" "custom-palette_vertex_.glsl" "lines" 9 "characters" 271 "sha256" "6f8ea62eb2f0225f87b4294947183d5993e9c7b5a31ce7896a5252da9012bd88"}]}
```

```kotlin [field-property box="custom-palette" name="code" path="custom-palette_code_.kt"]
// A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
// slot; this box's `stackSL` property fills it. Edit the stackSL live and the
// shader re-binds in place.
//
// [[property stackSL]]

val module = moduleHere { }
val m = Mesh()
module += m named "fullscreen"
m.resize(3) // three vertices, no attributes: the vertex shader makes the triangle

// Every box in this gallery draws the whole window, so they all register under one
// name: launching a box replaces whichever one the window was showing.
renderer += "stacksl-gallery" to module

// Bind this box's StackSL source into the `program` slot (compiles + hot-reloads).
// `stackSL` is null when no box up the graph has one; bind the empty program then.
module.program = stackSL ?: ""
```

```glsl [field-property box="custom-palette" name="fragment" path="custom-palette_fragment_.glsl"]
#version 450
layout(location = 0) in vec2 fragUV;
layout(location = 0) out vec4 outColor;

float gallery_hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

{{ stack.auto.program }}

void main() {
    vec2 uv = fragUV;
    vec4 stackInput = vec4(uv, gallery_hash12(uv), 1.7500);
    vec3 c = program(stackInput).rgb;
    if (any(isnan(c)) || any(isinf(c))) c = vec3(0.0);
    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
```

```stacksl [field-property box="custom-palette" name="stackSL" path="custom-palette_stackSL_.stacksl"]
uv 3 * fbm
0.5 0.5 0.5 1 vec4
0.5 0.5 0.5 1 vec4
1.0 1.0 1.0 1 vec4
0.0 0.33 0.67 1 vec4
palette
```

```glsl [field-property box="custom-palette" name="vertex" path="custom-palette_vertex_.glsl"]
#version 450
layout(location = 0) out vec2 fragUV;

void main() {
    // Fullscreen triangle from gl_VertexIndex, no vertex inputs.
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
    fragUV = pos;
}
```

## 20. grade-fields

<!-- field-box id="grade-fields" index="19" -->

### Narrative

From `code`

A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
slot; this box's `stackSL` property fills it. Edit the stackSL live and the
shader re-binds in place.

[[property stackSL]]

```edn [field-box id="grade-fields" role="properties"]
{"id" "grade-fields" "index" 19 "name" "grade-fields" "parents" ["root" "renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "renderer"} "name" "grade-fields" "_tombstone" false "frame" #afield/rect [1480.0 430.0 300.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "grade-fields_code_.kt" "lines" 18 "characters" 772 "sha256" "d6fbc2aa9eb5a571f36c6f9721ff1aaf978b56295cec491f4adbf29a9f2e759e"} {"name" "fragment" "language" "glsl" "path" "grade-fields_fragment_.glsl" "lines" 19 "characters" 506 "sha256" "7a6b03f7f6d7537a3a20920b336c7c9271bb9616f9c6f1a8c0056a733b57a138"} {"name" "stackSL" "language" "stacksl" "path" "grade-fields_stackSL_.stacksl" "lines" 6 "characters" 109 "sha256" "b5171b6fc7192589d10a4c5a9b0b84a325600e16501e126c29077327b88182c2"} {"name" "vertex" "language" "glsl" "path" "grade-fields_vertex_.glsl" "lines" 9 "characters" 271 "sha256" "6f8ea62eb2f0225f87b4294947183d5993e9c7b5a31ce7896a5252da9012bd88"}]}
```

```kotlin [field-property box="grade-fields" name="code" path="grade-fields_code_.kt"]
// A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
// slot; this box's `stackSL` property fills it. Edit the stackSL live and the
// shader re-binds in place.
//
// [[property stackSL]]

val module = moduleHere { }
val m = Mesh()
module += m named "fullscreen"
m.resize(3) // three vertices, no attributes: the vertex shader makes the triangle

// Every box in this gallery draws the whole window, so they all register under one
// name: launching a box replaces whichever one the window was showing.
renderer += "stacksl-gallery" to module

// Bind this box's StackSL source into the `program` slot (compiles + hot-reloads).
// `stackSL` is null when no box up the graph has one; bind the empty program then.
module.program = stackSL ?: ""
```

```glsl [field-property box="grade-fields" name="fragment" path="grade-fields_fragment_.glsl"]
#version 450
layout(location = 0) in vec2 fragUV;
layout(location = 0) out vec4 outColor;

float gallery_hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

{{ stack.auto.program }}

void main() {
    vec2 uv = fragUV;
    vec4 stackInput = vec4(uv, gallery_hash12(uv), 1.7500);
    vec3 c = program(stackInput).rgb;
    if (any(isnan(c)) || any(isinf(c))) c = vec3(0.0);
    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
```

```stacksl [field-property box="grade-fields" name="stackSL" path="grade-fields_stackSL_.stacksl"]
uv 6.0 * fbm palette
0.02 0.00 -0.01 0.0 vec4
1.15 1.05 0.95 1.0 vec4
0.85 0.95 1.10 1.0 vec4
grade
saturate
```

```glsl [field-property box="grade-fields" name="vertex" path="grade-fields_vertex_.glsl"]
#version 450
layout(location = 0) out vec2 fragUV;

void main() {
    // Fullscreen triangle from gl_VertexIndex, no vertex inputs.
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
    fragUV = pos;
}
```

## 21. definitions

<!-- field-box id="definitions" index="20" -->

### Narrative

From `code`

A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
slot; this box's `stackSL` property fills it. Edit the stackSL live and the
shader re-binds in place.

[[property stackSL]]

```edn [field-box id="definitions" role="properties"]
{"id" "definitions" "index" 20 "name" "definitions" "parents" ["root" "renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "renderer"} "name" "definitions" "_tombstone" false "frame" #afield/rect [1480.0 300.0 300.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "definitions_code_.kt" "lines" 18 "characters" 772 "sha256" "d6fbc2aa9eb5a571f36c6f9721ff1aaf978b56295cec491f4adbf29a9f2e759e"} {"name" "fragment" "language" "glsl" "path" "definitions_fragment_.glsl" "lines" 19 "characters" 506 "sha256" "7a6b03f7f6d7537a3a20920b336c7c9271bb9616f9c6f1a8c0056a733b57a138"} {"name" "stackSL" "language" "stacksl" "path" "definitions_stackSL_.stacksl" "lines" 11 "characters" 98 "sha256" "38791744841ffeac30cefae6ab289780f8269a2136d7920fb5e00a0a7326a8ce"} {"name" "vertex" "language" "glsl" "path" "definitions_vertex_.glsl" "lines" 9 "characters" 271 "sha256" "6f8ea62eb2f0225f87b4294947183d5993e9c7b5a31ce7896a5252da9012bd88"}]}
```

```kotlin [field-property box="definitions" name="code" path="definitions_code_.kt"]
// A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
// slot; this box's `stackSL` property fills it. Edit the stackSL live and the
// shader re-binds in place.
//
// [[property stackSL]]

val module = moduleHere { }
val m = Mesh()
module += m named "fullscreen"
m.resize(3) // three vertices, no attributes: the vertex shader makes the triangle

// Every box in this gallery draws the whole window, so they all register under one
// name: launching a box replaces whichever one the window was showing.
renderer += "stacksl-gallery" to module

// Bind this box's StackSL source into the `program` slot (compiles + hot-reloads).
// `stackSL` is null when no box up the graph has one; bind the empty program then.
module.program = stackSL ?: ""
```

```glsl [field-property box="definitions" name="fragment" path="definitions_fragment_.glsl"]
#version 450
layout(location = 0) in vec2 fragUV;
layout(location = 0) out vec4 outColor;

float gallery_hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

{{ stack.auto.program }}

void main() {
    vec2 uv = fragUV;
    vec4 stackInput = vec4(uv, gallery_hash12(uv), 1.7500);
    vec3 c = program(stackInput).rgb;
    if (any(isnan(c)) || any(isinf(c))) c = vec3(0.0);
    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
```

```stacksl [field-property box="definitions" name="stackSL" path="definitions_stackSL_.stacksl"]
: warp
  drop uv 6.0 * time + sin
  0.35 *
  uv +
;

: field
  warp fbm
;

field palette saturate
```

```glsl [field-property box="definitions" name="vertex" path="definitions_vertex_.glsl"]
#version 450
layout(location = 0) out vec2 fragUV;

void main() {
    // Fullscreen triangle from gl_VertexIndex, no vertex inputs.
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
    fragUV = pos;
}
```

## 22. sample-hold

<!-- field-box id="sample-hold" index="21" -->

### Narrative

From `code`

A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
slot; this box's `stackSL` property fills it. Edit the stackSL live and the
shader re-binds in place.

[[property stackSL]]

```edn [field-box id="sample-hold" role="properties"]
{"id" "sample-hold" "index" 21 "name" "sample-hold" "parents" ["root" "renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "renderer"} "name" "sample-hold" "_tombstone" false "frame" #afield/rect [760.0 950.0 300.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sample-hold_code_.kt" "lines" 18 "characters" 772 "sha256" "d6fbc2aa9eb5a571f36c6f9721ff1aaf978b56295cec491f4adbf29a9f2e759e"} {"name" "fragment" "language" "glsl" "path" "sample-hold_fragment_.glsl" "lines" 19 "characters" 506 "sha256" "7a6b03f7f6d7537a3a20920b336c7c9271bb9616f9c6f1a8c0056a733b57a138"} {"name" "stackSL" "language" "stacksl" "path" "sample-hold_stackSL_.stacksl" "lines" 1 "characters" 30 "sha256" "2acebe5a9e46259d333e2fc612c2dce03d6db607d3416307d8869335171ba695"} {"name" "vertex" "language" "glsl" "path" "sample-hold_vertex_.glsl" "lines" 9 "characters" 271 "sha256" "6f8ea62eb2f0225f87b4294947183d5993e9c7b5a31ce7896a5252da9012bd88"}]}
```

```kotlin [field-property box="sample-hold" name="code" path="sample-hold_code_.kt"]
// A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
// slot; this box's `stackSL` property fills it. Edit the stackSL live and the
// shader re-binds in place.
//
// [[property stackSL]]

val module = moduleHere { }
val m = Mesh()
module += m named "fullscreen"
m.resize(3) // three vertices, no attributes: the vertex shader makes the triangle

// Every box in this gallery draws the whole window, so they all register under one
// name: launching a box replaces whichever one the window was showing.
renderer += "stacksl-gallery" to module

// Bind this box's StackSL source into the `program` slot (compiles + hot-reloads).
// `stackSL` is null when no box up the graph has one; bind the empty program then.
module.program = stackSL ?: ""
```

```glsl [field-property box="sample-hold" name="fragment" path="sample-hold_fragment_.glsl"]
#version 450
layout(location = 0) in vec2 fragUV;
layout(location = 0) out vec4 outColor;

float gallery_hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

{{ stack.auto.program }}

void main() {
    vec2 uv = fragUV;
    vec4 stackInput = vec4(uv, gallery_hash12(uv), 1.7500);
    vec3 c = program(stackInput).rgb;
    if (any(isnan(c)) || any(isinf(c))) c = vec3(0.0);
    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
```

```stacksl [field-property box="sample-hold" name="stackSL" path="sample-hold_stackSL_.stacksl"]
uv 6 * ]sh10 palette saturate
```

```glsl [field-property box="sample-hold" name="vertex" path="sample-hold_vertex_.glsl"]
#version 450
layout(location = 0) out vec2 fragUV;

void main() {
    // Fullscreen triangle from gl_VertexIndex, no vertex inputs.
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
    fragUV = pos;
}
```

## 23. ryb-paint

<!-- field-box id="ryb-paint" index="22" -->

### Narrative

From `code`

A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
slot; this box's `stackSL` property fills it. Edit the stackSL live and the
shader re-binds in place.

[[property stackSL]]

```edn [field-box id="ryb-paint" role="properties"]
{"id" "ryb-paint" "index" 22 "name" "ryb-paint" "parents" ["root" "renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "renderer"} "name" "ryb-paint" "_tombstone" false "frame" #afield/rect [1120.0 430.0 300.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ryb-paint_code_.kt" "lines" 18 "characters" 772 "sha256" "d6fbc2aa9eb5a571f36c6f9721ff1aaf978b56295cec491f4adbf29a9f2e759e"} {"name" "fragment" "language" "glsl" "path" "ryb-paint_fragment_.glsl" "lines" 19 "characters" 506 "sha256" "7a6b03f7f6d7537a3a20920b336c7c9271bb9616f9c6f1a8c0056a733b57a138"} {"name" "stackSL" "language" "stacksl" "path" "ryb-paint_stackSL_.stacksl" "lines" 1 "characters" 22 "sha256" "53fcdd224ca56855b1bf0896a69e018de6e74ea7df023b6e1c1944fe4ce1e734"} {"name" "vertex" "language" "glsl" "path" "ryb-paint_vertex_.glsl" "lines" 9 "characters" 271 "sha256" "6f8ea62eb2f0225f87b4294947183d5993e9c7b5a31ce7896a5252da9012bd88"}]}
```

```kotlin [field-property box="ryb-paint" name="code" path="ryb-paint_code_.kt"]
// A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
// slot; this box's `stackSL` property fills it. Edit the stackSL live and the
// shader re-binds in place.
//
// [[property stackSL]]

val module = moduleHere { }
val m = Mesh()
module += m named "fullscreen"
m.resize(3) // three vertices, no attributes: the vertex shader makes the triangle

// Every box in this gallery draws the whole window, so they all register under one
// name: launching a box replaces whichever one the window was showing.
renderer += "stacksl-gallery" to module

// Bind this box's StackSL source into the `program` slot (compiles + hot-reloads).
// `stackSL` is null when no box up the graph has one; bind the empty program then.
module.program = stackSL ?: ""
```

```glsl [field-property box="ryb-paint" name="fragment" path="ryb-paint_fragment_.glsl"]
#version 450
layout(location = 0) in vec2 fragUV;
layout(location = 0) out vec4 outColor;

float gallery_hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

{{ stack.auto.program }}

void main() {
    vec2 uv = fragUV;
    vec4 stackInput = vec4(uv, gallery_hash12(uv), 1.7500);
    vec3 c = program(stackInput).rgb;
    if (any(isnan(c)) || any(isinf(c))) c = vec3(0.0);
    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
```

```stacksl [field-property box="ryb-paint" name="stackSL" path="ryb-paint_stackSL_.stacksl"]
uv 0.5 + ryb saturate
```

```glsl [field-property box="ryb-paint" name="vertex" path="ryb-paint_vertex_.glsl"]
#version 450
layout(location = 0) out vec2 fragUV;

void main() {
    // Fullscreen triangle from gl_VertexIndex, no vertex inputs.
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
    fragUV = pos;
}
```

## 24. subroutine-ridges

<!-- field-box id="subroutine-ridges" index="23" -->

### Narrative

From `code`

A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
slot; this box's `stackSL` property fills it. Edit the stackSL live and the
shader re-binds in place.

[[property stackSL]]

```edn [field-box id="subroutine-ridges" role="properties"]
{"id" "subroutine-ridges" "index" 23 "name" "subroutine-ridges" "parents" ["root" "renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "renderer"} "name" "subroutine-ridges" "_tombstone" false "frame" #afield/rect [400.0 430.0 300.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "subroutine-ridges_code_.kt" "lines" 18 "characters" 772 "sha256" "d6fbc2aa9eb5a571f36c6f9721ff1aaf978b56295cec491f4adbf29a9f2e759e"} {"name" "fragment" "language" "glsl" "path" "subroutine-ridges_fragment_.glsl" "lines" 19 "characters" 506 "sha256" "7a6b03f7f6d7537a3a20920b336c7c9271bb9616f9c6f1a8c0056a733b57a138"} {"name" "stackSL" "language" "stacksl" "path" "subroutine-ridges_stackSL_.stacksl" "lines" 8 "characters" 92 "sha256" "62917c21b71486926b7f33fea2db6758f0997315dc64e98e24a6a15f141a9457"} {"name" "vertex" "language" "glsl" "path" "subroutine-ridges_vertex_.glsl" "lines" 9 "characters" 271 "sha256" "6f8ea62eb2f0225f87b4294947183d5993e9c7b5a31ce7896a5252da9012bd88"}]}
```

```kotlin [field-property box="subroutine-ridges" name="code" path="subroutine-ridges_code_.kt"]
// A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
// slot; this box's `stackSL` property fills it. Edit the stackSL live and the
// shader re-binds in place.
//
// [[property stackSL]]

val module = moduleHere { }
val m = Mesh()
module += m named "fullscreen"
m.resize(3) // three vertices, no attributes: the vertex shader makes the triangle

// Every box in this gallery draws the whole window, so they all register under one
// name: launching a box replaces whichever one the window was showing.
renderer += "stacksl-gallery" to module

// Bind this box's StackSL source into the `program` slot (compiles + hot-reloads).
// `stackSL` is null when no box up the graph has one; bind the empty program then.
module.program = stackSL ?: ""
```

```glsl [field-property box="subroutine-ridges" name="fragment" path="subroutine-ridges_fragment_.glsl"]
#version 450
layout(location = 0) in vec2 fragUV;
layout(location = 0) out vec4 outColor;

float gallery_hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

{{ stack.auto.program }}

void main() {
    vec2 uv = fragUV;
    vec4 stackInput = vec4(uv, gallery_hash12(uv), 1.7500);
    vec3 c = program(stackInput).rgb;
    if (any(isnan(c)) || any(isinf(c))) c = vec3(0.0);
    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
```

```stacksl [field-property box="subroutine-ridges" name="stackSL" path="subroutine-ridges_stackSL_.stacksl"]
: ridge
  fbm 0.45 0.08 pulse
;

p 5.0 * 0.2t + ridge
p 7.0 * 0.13t - ridge
0.5 * +
palette
```

```glsl [field-property box="subroutine-ridges" name="vertex" path="subroutine-ridges_vertex_.glsl"]
#version 450
layout(location = 0) out vec2 fragUV;

void main() {
    // Fullscreen triangle from gl_VertexIndex, no vertex inputs.
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
    fragUV = pos;
}
```

## 25. feedback-trails

<!-- field-box id="feedback-trails" index="24" -->

### Narrative

From `code`

A feedback StackSL box. The fragment shader has a `{{ stack.auto.program }}`
slot, and the program in this box's `stackSL` property samples `source`: the
frame this box drew last time, so the picture feeds back into itself.

A window cannot be read back like that, so this box draws into a ping-pong
target instead. It holds two images, draws into one while `source` reads the
other, and swaps them every frame; `blitToScreen` puts the newest in the window.

[[property stackSL]]

```edn [field-box id="feedback-trails" role="properties"]
{"id" "feedback-trails" "index" 24 "name" "feedback-trails" "parents" ["root" "renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "renderer"} "name" "feedback-trails" "_tombstone" false "frame" #afield/rect [1120.0 560.0 300.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "feedback-trails_code_.kt" "lines" 39 "characters" 1772 "sha256" "3c18f08e03b1170ad62685ff3e2f57511e8d66e2d81a400d57f83908ed73c7b3"} {"name" "fragment" "language" "glsl" "path" "feedback-trails_fragment_.glsl" "lines" 23 "characters" 696 "sha256" "b3bd19fe34573fcd02d2e805818e25da08311388465d75194e06acd6d7fe923c"} {"name" "stackSL" "language" "stacksl" "path" "feedback-trails_stackSL_.stacksl" "lines" 5 "characters" 95 "sha256" "fc9d5528c51ed054d52c4b83e6c1c459e479f2c27d310105dea5c25bc3eba6e4"} {"name" "vertex" "language" "glsl" "path" "feedback-trails_vertex_.glsl" "lines" 9 "characters" 271 "sha256" "6f8ea62eb2f0225f87b4294947183d5993e9c7b5a31ce7896a5252da9012bd88"}]}
```

```kotlin [field-property box="feedback-trails" name="code" path="feedback-trails_code_.kt"]
// A feedback StackSL box. The fragment shader has a `{{ stack.auto.program }}`
// slot, and the program in this box's `stackSL` property samples `source`: the
// frame this box drew last time, so the picture feeds back into itself.
//
// A window cannot be read back like that, so this box draws into a ping-pong
// target instead. It holds two images, draws into one while `source` reads the
// other, and swaps them every frame; `blitToScreen` puts the newest in the window.
//
// [[property stackSL]]

import afield.graphics.vulkan.targets.PingPongRenderToTexture

prop<afield.graphics.vulkan.targets.PingPongRenderToTexture>(target)
prop<afield.graphics.texture.HasImageView>(source)

// `once`, so re-running the box keeps the images and the trails already in them.
once.target {
    PingPongRenderToTexture(renderer) { useDepth = false }
}
source = target.otherImageView

val module = moduleHere { target.apply() }
val m = Mesh()
module += m named "fullscreen"
m.resize(3) // three vertices, no attributes: the vertex shader makes the triangle
target += "fullscreen" to module

// pc.params.x advances per rendered frame. Edit the scale below to change its rate;
// a faster window frame rate also advances more program time per wall-clock second.
val first = renderer.frameCounter
m.params = { vec((renderer.frameCounter - first) * 1.667 / 48, 0.0, 0.0, 0.0) }

// Bind this box's StackSL source into the `program` slot (compiles + hot-reloads).
// `stackSL` is null when no box up the graph has one; bind the empty program then.
module.program = stackSL ?: ""

// Every box in this gallery draws the whole window, so they all register under one
// name: launching a box replaces whichever one the window was showing.
target.blitToScreen(name = "stacksl-gallery")
```

```glsl [field-property box="feedback-trails" name="fragment" path="feedback-trails_fragment_.glsl"]
#version 450
layout(location = 0) in vec2 fragUV;
layout(location = 0) out vec4 outColor;

// Feedback: `source` is the previous frame (bound by a ping-pong host).
layout(set = 0, binding = 0) uniform sampler2D source;
layout(push_constant) uniform Push { vec4 params; } pc;

float gallery_hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

{{ stack.auto.program }}

void main() {
    vec2 uv = fragUV;
    vec4 stackInput = vec4(uv, gallery_hash12(uv), pc.params.x);
    vec3 c = program(stackInput).rgb;
    if (any(isnan(c)) || any(isinf(c))) c = vec3(0.0);
    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
```

```stacksl [field-property box="feedback-trails" name="stackSL" path="feedback-trails_stackSL_.stacksl"]
0.0025 0.004 vec2 sample.w
0.94 *
uv y 0.15t fract 0.02 pulse 0.15t palette * 0.8 *
+
saturate
```

```glsl [field-property box="feedback-trails" name="vertex" path="feedback-trails_vertex_.glsl"]
#version 450
layout(location = 0) out vec2 fragUV;

void main() {
    // Fullscreen triangle from gl_VertexIndex, no vertex inputs.
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
    fragUV = pos;
}
```

## 26. fm-palette

<!-- field-box id="fm-palette" index="25" -->

### Narrative

From `code`

A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
slot; this box's `stackSL` property fills it. Edit the stackSL live and the
shader re-binds in place.

[[property stackSL]]

```edn [field-box id="fm-palette" role="properties"]
{"id" "fm-palette" "index" 25 "name" "fm-palette" "parents" ["root" "renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "renderer"} "name" "fm-palette" "_tombstone" false "frame" #afield/rect [1120.0 300.0 300.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fm-palette_code_.kt" "lines" 18 "characters" 772 "sha256" "d6fbc2aa9eb5a571f36c6f9721ff1aaf978b56295cec491f4adbf29a9f2e759e"} {"name" "fragment" "language" "glsl" "path" "fm-palette_fragment_.glsl" "lines" 19 "characters" 506 "sha256" "7a6b03f7f6d7537a3a20920b336c7c9271bb9616f9c6f1a8c0056a733b57a138"} {"name" "stackSL" "language" "stacksl" "path" "fm-palette_stackSL_.stacksl" "lines" 3 "characters" 28 "sha256" "f81e245d6855034cbaf9c14a14de55904d6cf53b19cb4da7ad1fc4eca4d432f4"} {"name" "vertex" "language" "glsl" "path" "fm-palette_vertex_.glsl" "lines" 9 "characters" 271 "sha256" "6f8ea62eb2f0225f87b4294947183d5993e9c7b5a31ce7896a5252da9012bd88"}]}
```

```kotlin [field-property box="fm-palette" name="code" path="fm-palette_code_.kt"]
// A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
// slot; this box's `stackSL` property fills it. Edit the stackSL live and the
// shader re-binds in place.
//
// [[property stackSL]]

val module = moduleHere { }
val m = Mesh()
module += m named "fullscreen"
m.resize(3) // three vertices, no attributes: the vertex shader makes the triangle

// Every box in this gallery draws the whole window, so they all register under one
// name: launching a box replaces whichever one the window was showing.
renderer += "stacksl-gallery" to module

// Bind this box's StackSL source into the `program` slot (compiles + hot-reloads).
// `stackSL` is null when no box up the graph has one; bind the empty program then.
module.program = stackSL ?: ""
```

```glsl [field-property box="fm-palette" name="fragment" path="fm-palette_fragment_.glsl"]
#version 450
layout(location = 0) in vec2 fragUV;
layout(location = 0) out vec4 outColor;

float gallery_hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

{{ stack.auto.program }}

void main() {
    vec2 uv = fragUV;
    vec4 stackInput = vec4(uv, gallery_hash12(uv), 1.7500);
    vec3 c = program(stackInput).rgb;
    if (any(isnan(c)) || any(isinf(c))) c = vec3(0.0);
    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
```

```stacksl [field-property box="fm-palette" name="stackSL" path="fm-palette_stackSL_.stacksl"]
uv 14.0 * time +
fm
palette
```

```glsl [field-property box="fm-palette" name="vertex" path="fm-palette_vertex_.glsl"]
#version 450
layout(location = 0) out vec2 fragUV;

void main() {
    // Fullscreen triangle from gl_VertexIndex, no vertex inputs.
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
    fragUV = pos;
}
```

## 27. spiral-flow

<!-- field-box id="spiral-flow" index="26" -->

### Narrative

From `code`

A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
slot; this box's `stackSL` property fills it. Edit the stackSL live and the
shader re-binds in place.

[[property stackSL]]

```edn [field-box id="spiral-flow" role="properties"]
{"id" "spiral-flow" "index" 26 "name" "spiral-flow" "parents" ["root" "renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "renderer"} "name" "spiral-flow" "_tombstone" false "frame" #afield/rect [1480.0 820.0 300.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "spiral-flow_code_.kt" "lines" 18 "characters" 772 "sha256" "d6fbc2aa9eb5a571f36c6f9721ff1aaf978b56295cec491f4adbf29a9f2e759e"} {"name" "fragment" "language" "glsl" "path" "spiral-flow_fragment_.glsl" "lines" 19 "characters" 506 "sha256" "7a6b03f7f6d7537a3a20920b336c7c9271bb9616f9c6f1a8c0056a733b57a138"} {"name" "stackSL" "language" "stacksl" "path" "spiral-flow_stackSL_.stacksl" "lines" 1 "characters" 32 "sha256" "23b46ead7dae42fc8109772addcf298feca8c6fadf84aaa2de896eedfefd2e23"} {"name" "vertex" "language" "glsl" "path" "spiral-flow_vertex_.glsl" "lines" 9 "characters" 271 "sha256" "6f8ea62eb2f0225f87b4294947183d5993e9c7b5a31ce7896a5252da9012bd88"}]}
```

```kotlin [field-property box="spiral-flow" name="code" path="spiral-flow_code_.kt"]
// A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
// slot; this box's `stackSL` property fills it. Edit the stackSL live and the
// shader re-binds in place.
//
// [[property stackSL]]

val module = moduleHere { }
val m = Mesh()
module += m named "fullscreen"
m.resize(3) // three vertices, no attributes: the vertex shader makes the triangle

// Every box in this gallery draws the whole window, so they all register under one
// name: launching a box replaces whichever one the window was showing.
renderer += "stacksl-gallery" to module

// Bind this box's StackSL source into the `program` slot (compiles + hot-reloads).
// `stackSL` is null when no box up the graph has one; bind the empty program then.
module.program = stackSL ?: ""
```

```glsl [field-property box="spiral-flow" name="fragment" path="spiral-flow_fragment_.glsl"]
#version 450
layout(location = 0) in vec2 fragUV;
layout(location = 0) out vec4 outColor;

float gallery_hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

{{ stack.auto.program }}

void main() {
    vec2 uv = fragUV;
    vec4 stackInput = vec4(uv, gallery_hash12(uv), 1.7500);
    vec3 c = program(stackInput).rgb;
    if (any(isnan(c)) || any(isinf(c))) c = vec3(0.0);
    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
```

```stacksl [field-property box="spiral-flow" name="stackSL" path="spiral-flow_stackSL_.stacksl"]
p 3 spiral fbm palette saturate
```

```glsl [field-property box="spiral-flow" name="vertex" path="spiral-flow_vertex_.glsl"]
#version 450
layout(location = 0) out vec2 fragUV;

void main() {
    // Fullscreen triangle from gl_VertexIndex, no vertex inputs.
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
    fragUV = pos;
}
```

## 28. hard-threshold

<!-- field-box id="hard-threshold" index="27" -->

### Narrative

From `code`

A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
slot; this box's `stackSL` property fills it. Edit the stackSL live and the
shader re-binds in place.

[[property stackSL]]

```edn [field-box id="hard-threshold" role="properties"]
{"id" "hard-threshold" "index" 27 "name" "hard-threshold" "parents" ["root" "renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "renderer"} "name" "hard-threshold" "_tombstone" false "frame" #afield/rect [1480.0 690.0 300.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "hard-threshold_code_.kt" "lines" 18 "characters" 772 "sha256" "d6fbc2aa9eb5a571f36c6f9721ff1aaf978b56295cec491f4adbf29a9f2e759e"} {"name" "fragment" "language" "glsl" "path" "hard-threshold_fragment_.glsl" "lines" 19 "characters" 506 "sha256" "7a6b03f7f6d7537a3a20920b336c7c9271bb9616f9c6f1a8c0056a733b57a138"} {"name" "stackSL" "language" "stacksl" "path" "hard-threshold_stackSL_.stacksl" "lines" 1 "characters" 44 "sha256" "e226f8eb632e78fb113cfa696e48fa604793106f0da210122af62ff7c05e0b5e"} {"name" "vertex" "language" "glsl" "path" "hard-threshold_vertex_.glsl" "lines" 9 "characters" 271 "sha256" "6f8ea62eb2f0225f87b4294947183d5993e9c7b5a31ce7896a5252da9012bd88"}]}
```

```kotlin [field-property box="hard-threshold" name="code" path="hard-threshold_code_.kt"]
// A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
// slot; this box's `stackSL` property fills it. Edit the stackSL live and the
// shader re-binds in place.
//
// [[property stackSL]]

val module = moduleHere { }
val m = Mesh()
module += m named "fullscreen"
m.resize(3) // three vertices, no attributes: the vertex shader makes the triangle

// Every box in this gallery draws the whole window, so they all register under one
// name: launching a box replaces whichever one the window was showing.
renderer += "stacksl-gallery" to module

// Bind this box's StackSL source into the `program` slot (compiles + hot-reloads).
// `stackSL` is null when no box up the graph has one; bind the empty program then.
module.program = stackSL ?: ""
```

```glsl [field-property box="hard-threshold" name="fragment" path="hard-threshold_fragment_.glsl"]
#version 450
layout(location = 0) in vec2 fragUV;
layout(location = 0) out vec4 outColor;

float gallery_hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

{{ stack.auto.program }}

void main() {
    vec2 uv = fragUV;
    vec4 stackInput = vec4(uv, gallery_hash12(uv), 1.7500);
    vec3 c = program(stackInput).rgb;
    if (any(isnan(c)) || any(isinf(c))) c = vec3(0.0);
    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
```

```stacksl [field-property box="hard-threshold" name="stackSL" path="hard-threshold_stackSL_.stacksl"]
uv 5 * fbm 0.45 threshold orange * saturate
```

```glsl [field-property box="hard-threshold" name="vertex" path="hard-threshold_vertex_.glsl"]
#version 450
layout(location = 0) out vec2 fragUV;

void main() {
    // Fullscreen triangle from gl_VertexIndex, no vertex inputs.
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
    fragUV = pos;
}
```

## 29. stripe-bands

<!-- field-box id="stripe-bands" index="28" -->

### Narrative

From `code`

A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
slot; this box's `stackSL` property fills it. Edit the stackSL live and the
shader re-binds in place.

[[property stackSL]]

```edn [field-box id="stripe-bands" role="properties"]
{"id" "stripe-bands" "index" 28 "name" "stripe-bands" "parents" ["root" "renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "renderer"} "name" "stripe-bands" "_tombstone" false "frame" #afield/rect [1480.0 950.0 300.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "stripe-bands_code_.kt" "lines" 18 "characters" 772 "sha256" "d6fbc2aa9eb5a571f36c6f9721ff1aaf978b56295cec491f4adbf29a9f2e759e"} {"name" "fragment" "language" "glsl" "path" "stripe-bands_fragment_.glsl" "lines" 19 "characters" 506 "sha256" "7a6b03f7f6d7537a3a20920b336c7c9271bb9616f9c6f1a8c0056a733b57a138"} {"name" "stackSL" "language" "stacksl" "path" "stripe-bands_stackSL_.stacksl" "lines" 1 "characters" 43 "sha256" "b4d5a47bc490b4bddc01cc9b9112612b90e828ea855a7eea0174cb9e18c64a9c"} {"name" "vertex" "language" "glsl" "path" "stripe-bands_vertex_.glsl" "lines" 9 "characters" 271 "sha256" "6f8ea62eb2f0225f87b4294947183d5993e9c7b5a31ce7896a5252da9012bd88"}]}
```

```kotlin [field-property box="stripe-bands" name="code" path="stripe-bands_code_.kt"]
// A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
// slot; this box's `stackSL` property fills it. Edit the stackSL live and the
// shader re-binds in place.
//
// [[property stackSL]]

val module = moduleHere { }
val m = Mesh()
module += m named "fullscreen"
m.resize(3) // three vertices, no attributes: the vertex shader makes the triangle

// Every box in this gallery draws the whole window, so they all register under one
// name: launching a box replaces whichever one the window was showing.
renderer += "stacksl-gallery" to module

// Bind this box's StackSL source into the `program` slot (compiles + hot-reloads).
// `stackSL` is null when no box up the graph has one; bind the empty program then.
module.program = stackSL ?: ""
```

```glsl [field-property box="stripe-bands" name="fragment" path="stripe-bands_fragment_.glsl"]
#version 450
layout(location = 0) in vec2 fragUV;
layout(location = 0) out vec4 outColor;

float gallery_hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

{{ stack.auto.program }}

void main() {
    vec2 uv = fragUV;
    vec4 stackInput = vec4(uv, gallery_hash12(uv), 1.7500);
    vec3 c = program(stackInput).rgb;
    if (any(isnan(c)) || any(isinf(c))) c = vec3(0.0);
    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
```

```stacksl [field-property box="stripe-bands" name="stackSL" path="stripe-bands_stackSL_.stacksl"]
uv x 18 * sin 0.5 + 0.5 * palette saturate
```

```glsl [field-property box="stripe-bands" name="vertex" path="stripe-bands_vertex_.glsl"]
#version 450
layout(location = 0) out vec2 fragUV;

void main() {
    // Fullscreen triangle from gl_VertexIndex, no vertex inputs.
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
    fragUV = pos;
}
```

## 30. auto-after

<!-- field-box id="auto-after" index="29" -->

### Narrative

From `code`

A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
slot; this box's `stackSL` property fills it. Edit the stackSL live and the
shader re-binds in place.

[[property stackSL]]

```edn [field-box id="auto-after" role="properties"]
{"id" "auto-after" "index" 29 "name" "auto-after" "parents" ["root" "renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "renderer"} "name" "auto-after" "_tombstone" false "frame" #afield/rect [760.0 560.0 300.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "auto-after_code_.kt" "lines" 18 "characters" 772 "sha256" "d6fbc2aa9eb5a571f36c6f9721ff1aaf978b56295cec491f4adbf29a9f2e759e"} {"name" "fragment" "language" "glsl" "path" "auto-after_fragment_.glsl" "lines" 19 "characters" 506 "sha256" "7a6b03f7f6d7537a3a20920b336c7c9271bb9616f9c6f1a8c0056a733b57a138"} {"name" "stackSL" "language" "stacksl" "path" "auto-after_stackSL_.stacksl" "lines" 1 "characters" 33 "sha256" "aefa374559280126449873b6a04cb88cceca900de94bfbf411c87ab2b8737312"} {"name" "vertex" "language" "glsl" "path" "auto-after_vertex_.glsl" "lines" 9 "characters" 271 "sha256" "6f8ea62eb2f0225f87b4294947183d5993e9c7b5a31ce7896a5252da9012bd88"}]}
```

```kotlin [field-property box="auto-after" name="code" path="auto-after_code_.kt"]
// A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
// slot; this box's `stackSL` property fills it. Edit the stackSL live and the
// shader re-binds in place.
//
// [[property stackSL]]

val module = moduleHere { }
val m = Mesh()
module += m named "fullscreen"
m.resize(3) // three vertices, no attributes: the vertex shader makes the triangle

// Every box in this gallery draws the whole window, so they all register under one
// name: launching a box replaces whichever one the window was showing.
renderer += "stacksl-gallery" to module

// Bind this box's StackSL source into the `program` slot (compiles + hot-reloads).
// `stackSL` is null when no box up the graph has one; bind the empty program then.
module.program = stackSL ?: ""
```

```glsl [field-property box="auto-after" name="fragment" path="auto-after_fragment_.glsl"]
#version 450
layout(location = 0) in vec2 fragUV;
layout(location = 0) out vec4 outColor;

float gallery_hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

{{ stack.auto.program }}

void main() {
    vec2 uv = fragUV;
    vec4 stackInput = vec4(uv, gallery_hash12(uv), 1.7500);
    vec3 c = program(stackInput).rgb;
    if (any(isnan(c)) || any(isinf(c))) c = vec3(0.0);
    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
```

```stacksl [field-property box="auto-after" name="stackSL" path="auto-after_stackSL_.stacksl"]
p 6 kaleido fbm palette saturate
```

```glsl [field-property box="auto-after" name="vertex" path="auto-after_vertex_.glsl"]
#version 450
layout(location = 0) out vec2 fragUV;

void main() {
    // Fullscreen triangle from gl_VertexIndex, no vertex inputs.
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
    fragUV = pos;
}
```

## 31. phasor-noise

<!-- field-box id="phasor-noise" index="30" -->

### Narrative

From `code`

A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
slot; this box's `stackSL` property fills it. Edit the stackSL live and the
shader re-binds in place.

[[property stackSL]]

```edn [field-box id="phasor-noise" role="properties"]
{"id" "phasor-noise" "index" 30 "name" "phasor-noise" "parents" ["root" "renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "renderer"} "name" "phasor-noise" "_tombstone" false "frame" #afield/rect [1480.0 560.0 300.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "phasor-noise_code_.kt" "lines" 18 "characters" 772 "sha256" "d6fbc2aa9eb5a571f36c6f9721ff1aaf978b56295cec491f4adbf29a9f2e759e"} {"name" "fragment" "language" "glsl" "path" "phasor-noise_fragment_.glsl" "lines" 19 "characters" 506 "sha256" "7a6b03f7f6d7537a3a20920b336c7c9271bb9616f9c6f1a8c0056a733b57a138"} {"name" "stackSL" "language" "stacksl" "path" "phasor-noise_stackSL_.stacksl" "lines" 1 "characters" 32 "sha256" "89f660beff66db64cd9e977de3c1ad0d54cdc3292db83e6c9bd1683fbbde0e60"} {"name" "vertex" "language" "glsl" "path" "phasor-noise_vertex_.glsl" "lines" 9 "characters" 271 "sha256" "6f8ea62eb2f0225f87b4294947183d5993e9c7b5a31ce7896a5252da9012bd88"}]}
```

```kotlin [field-property box="phasor-noise" name="code" path="phasor-noise_code_.kt"]
// A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
// slot; this box's `stackSL` property fills it. Edit the stackSL live and the
// shader re-binds in place.
//
// [[property stackSL]]

val module = moduleHere { }
val m = Mesh()
module += m named "fullscreen"
m.resize(3) // three vertices, no attributes: the vertex shader makes the triangle

// Every box in this gallery draws the whole window, so they all register under one
// name: launching a box replaces whichever one the window was showing.
renderer += "stacksl-gallery" to module

// Bind this box's StackSL source into the `program` slot (compiles + hot-reloads).
// `stackSL` is null when no box up the graph has one; bind the empty program then.
module.program = stackSL ?: ""
```

```glsl [field-property box="phasor-noise" name="fragment" path="phasor-noise_fragment_.glsl"]
#version 450
layout(location = 0) in vec2 fragUV;
layout(location = 0) out vec4 outColor;

float gallery_hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

{{ stack.auto.program }}

void main() {
    vec2 uv = fragUV;
    vec4 stackInput = vec4(uv, gallery_hash12(uv), 1.7500);
    vec3 c = program(stackInput).rgb;
    if (any(isnan(c)) || any(isinf(c))) c = vec3(0.0);
    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
```

```stacksl [field-property box="phasor-noise" name="stackSL" path="phasor-noise_stackSL_.stacksl"]
p 4 * phacelle palette saturate
```

```glsl [field-property box="phasor-noise" name="vertex" path="phasor-noise_vertex_.glsl"]
#version 450
layout(location = 0) out vec2 fragUV;

void main() {
    // Fullscreen triangle from gl_VertexIndex, no vertex inputs.
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
    fragUV = pos;
}
```

## 32. twist-swirl

<!-- field-box id="twist-swirl" index="31" -->

### Narrative

From `code`

A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
slot; this box's `stackSL` property fills it. Edit the stackSL live and the
shader re-binds in place.

[[property stackSL]]

```edn [field-box id="twist-swirl" role="properties"]
{"id" "twist-swirl" "index" 31 "name" "twist-swirl" "parents" ["root" "renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "renderer"} "name" "twist-swirl" "_tombstone" false "frame" #afield/rect [1120.0 820.0 300.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "twist-swirl_code_.kt" "lines" 18 "characters" 772 "sha256" "d6fbc2aa9eb5a571f36c6f9721ff1aaf978b56295cec491f4adbf29a9f2e759e"} {"name" "fragment" "language" "glsl" "path" "twist-swirl_fragment_.glsl" "lines" 19 "characters" 506 "sha256" "7a6b03f7f6d7537a3a20920b336c7c9271bb9616f9c6f1a8c0056a733b57a138"} {"name" "stackSL" "language" "stacksl" "path" "twist-swirl_stackSL_.stacksl" "lines" 1 "characters" 33 "sha256" "029af66f3e51ce8f489f59ce6e90d0dd08ff8d025976dfc3dd3de63fce253f94"} {"name" "vertex" "language" "glsl" "path" "twist-swirl_vertex_.glsl" "lines" 9 "characters" 271 "sha256" "6f8ea62eb2f0225f87b4294947183d5993e9c7b5a31ce7896a5252da9012bd88"}]}
```

```kotlin [field-property box="twist-swirl" name="code" path="twist-swirl_code_.kt"]
// A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
// slot; this box's `stackSL` property fills it. Edit the stackSL live and the
// shader re-binds in place.
//
// [[property stackSL]]

val module = moduleHere { }
val m = Mesh()
module += m named "fullscreen"
m.resize(3) // three vertices, no attributes: the vertex shader makes the triangle

// Every box in this gallery draws the whole window, so they all register under one
// name: launching a box replaces whichever one the window was showing.
renderer += "stacksl-gallery" to module

// Bind this box's StackSL source into the `program` slot (compiles + hot-reloads).
// `stackSL` is null when no box up the graph has one; bind the empty program then.
module.program = stackSL ?: ""
```

```glsl [field-property box="twist-swirl" name="fragment" path="twist-swirl_fragment_.glsl"]
#version 450
layout(location = 0) in vec2 fragUV;
layout(location = 0) out vec4 outColor;

float gallery_hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

{{ stack.auto.program }}

void main() {
    vec2 uv = fragUV;
    vec4 stackInput = vec4(uv, gallery_hash12(uv), 1.7500);
    vec3 c = program(stackInput).rgb;
    if (any(isnan(c)) || any(isinf(c))) c = vec3(0.0);
    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
```

```stacksl [field-property box="twist-swirl" name="stackSL" path="twist-swirl_stackSL_.stacksl"]
p 5.0 twist fbm palette saturate
```

```glsl [field-property box="twist-swirl" name="vertex" path="twist-swirl_vertex_.glsl"]
#version 450
layout(location = 0) out vec2 fragUV;

void main() {
    // Fullscreen triangle from gl_VertexIndex, no vertex inputs.
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
    fragUV = pos;
}
```

