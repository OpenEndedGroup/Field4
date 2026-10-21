# Field Document: fern_iterated_systems

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 5 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="fern_renderer" index="0" -->

```edn [field-box id="fern_renderer" role="properties"]
{"id" "fern_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["fern_barnsley" "fern_lorenz" "fern_flame" "fern_sierpinski"] "properties" {"children" #{#afield/box "fern_barnsley" #afield/box "fern_lorenz" #afield/box "fern_flame" #afield/box "fern_sierpinski"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fern_renderer_code_.kt" "lines" 13 "characters" 431 "sha256" "f73c9dcfbbf105447b5fa9ae40feb7ef5321e8eba8febc9ad88d4ce2ee7bf2d0"}]}
```

```kotlin [field-property box="fern_renderer" name="code" path="fern_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
// once: re-running this box keeps the existing window and renderer
once.renderer {
    window = Window("Fern", initialDimensions = Rect(80.0, 80.0, 760.0, 900.0))
    Renderer(window)
}
// the frame loop runs as this box's coroutine; stopping the box stops rendering
start(renderer::run)
```

## 2. Lorenz attractor

<!-- field-box id="fern_lorenz" index="1" -->

### Narrative

From `code`

The Lorenz attractor. Each particle starts near the others and is carried along the
Lorenz flow, a continuous rule, unlike the fern's jumps. The trajectories never settle
and never repeat, yet every one of them traces the same two-lobed shape. The 3D path is
projected to the screen in project(), and coloured by height.

```edn [field-box id="fern_lorenz" role="properties"]
{"id" "fern_lorenz" "index" 1 "name" "Lorenz attractor" "parents" ["root" "fern_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "fern_renderer"} "name" "Lorenz attractor" "_tombstone" false "frame" #afield/rect [640.0 200.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fern_lorenz_code_.kt" "lines" 19 "characters" 600 "sha256" "f36cb4b97f2bc1c3cd44c95ad46ace13001d3eee165cce4c1e2dda226e5b18de"} {"name" "compute" "language" "glsl" "path" "fern_lorenz_compute_.glsl" "lines" 34 "characters" 831 "sha256" "af353dae06ecafc3a65f0e8d882d086400dc7c6dd124b603199e76f1dfb22cb9"}]}
```

```kotlin [field-property box="fern_lorenz" name="code" path="fern_lorenz_code_.kt"]
/*
The Lorenz attractor. Each particle starts near the others and is carried along the
Lorenz flow, a continuous rule, unlike the fern's jumps. The trajectories never settle
and never repeat, yet every one of them traces the same two-lobed shape. The 3D path is
projected to the screen in project(), and coloured by height.
*/
var module = computeModuleHere {}

val lorenz = IteratedSystem(module) {
    viewport(-33.0, 33.0, -6.0, 57.0)
    background = hexColor("#020305")
    particles = 12_000
    steps = 2
    dt = 0.006
    fade = 0.08
    fps = 30.0
    exposure = 0.3
}
showControls(lorenz)
```

```glsl [field-property box="fern_lorenz" name="compute" path="fern_lorenz_compute_.glsl"]
#version 450
#include "points/system"

State initParticle(uint index, uint count) {
    vec3 p = vec3(-12.0 + random() * 24.0, -12.0 + random() * 24.0, 14.0 + random() * 18.0);
    return State(p, 0.0, vec4(0.0));
}

void step(inout State s, float dt) {
    float sigma = 10.0;
    float rho = 28.0;
    float beta = 8.0 / 3.0;
    vec3 p = s.pos;
    vec3 d = vec3(sigma * (p.y - p.x), p.x * (rho - p.z) - p.y, p.x * p.y - beta * p.z);
    s.pos += d * dt;
}

vec2 project(State s) {
    float angle = 0.55;
    return vec2(s.pos.x * cos(angle) - s.pos.y * sin(angle) * 0.28, s.pos.z + s.pos.y * 0.34);
}

bool shouldDraw(State s) {
    return true;
}

vec4 color(State s) {
    float hue = 180.0 + mod(s.pos.z * 3.2 + s.age * 0.08, 140.0);
    return hsla(hue, 92.0, 68.0, 0.42);
}

float pointSize(State s) {
    return 1.05;
}
```

## 3. Sierpinski triangle

<!-- field-box id="fern_sierpinski" index="2" -->

### Narrative

From `code`

The smallest chaos game: three corners, and each step moves the particle halfway
towards one of them, picked at random. Nothing in the rule mentions holes, but the
points only ever land on the Sierpinski triangle. Colour shows which corner the last
step went towards, so each third of the triangle is a half-size copy of the whole.

```edn [field-box id="fern_sierpinski" role="properties"]
{"id" "fern_sierpinski" "index" 2 "name" "Sierpinski triangle" "parents" ["root" "fern_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "fern_renderer"} "name" "Sierpinski triangle" "_tombstone" false "frame" #afield/rect [640.0 660.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fern_sierpinski_code_.kt" "lines" 17 "characters" 592 "sha256" "a1d2665ba9d22eefa8710c62b8fe3af29fbf9c2f054b10bf33ca8b767d1f91c8"} {"name" "compute" "language" "glsl" "path" "fern_sierpinski_compute_.glsl" "lines" 37 "characters" 800 "sha256" "be24fc3e7d2faa1e20235bb058fe448fd71b1f94d1a92f579adce8b3bca3e4d9"}]}
```

```kotlin [field-property box="fern_sierpinski" name="code" path="fern_sierpinski_code_.kt"]
/*
The smallest chaos game: three corners, and each step moves the particle halfway
towards one of them, picked at random. Nothing in the rule mentions holes, but the
points only ever land on the Sierpinski triangle. Colour shows which corner the last
step went towards, so each third of the triangle is a half-size copy of the whole.
*/
var module = computeModuleHere {}

val sierpinski = IteratedSystem(module) {
    viewport(-1.05, 1.05, -0.95, 1.05)
    background = hexColor("#030204")
    particles = 150_000
    fade = 0.05
    fps = 24.0
    exposure = 0.5
}
showControls(sierpinski)
```

```glsl [field-property box="fern_sierpinski" name="compute" path="fern_sierpinski_compute_.glsl"]
#version 450
#include "points/system"

// data.x: the corner the last step went towards

const vec2 corner[3] = vec2[](vec2(0.0, 1.0), vec2(-0.866, -0.5), vec2(0.866, -0.5));

State initParticle(uint index, uint count) {
    return State(vec3(random() - 0.5, random() - 0.5, 0.0), 0.0, vec4(0.0));
}

void step(inout State s, float dt) {
    int k = min(int(random() * 3.0), 2);
    s.pos.xy = (s.pos.xy + corner[k]) * 0.5;
    s.data.x = float(k);
}

vec2 project(State s) {
    return s.pos.xy;
}

bool shouldDraw(State s) {
    return s.age > 8.0;
}

vec4 color(State s) {
    vec4 palette[3] = vec4[](
        rgba(255, 184, 108, 0.35),
        rgba(139, 233, 253, 0.35),
        rgba(255, 121, 198, 0.35)
    );
    return palette[int(s.data.x)];
}

float pointSize(State s) {
    return 1.0;
}
```

## 4. flame-style IFS

<!-- field-box id="fern_flame" index="3" -->

### Narrative

From `code`

A flame-style IFS. The same weighted chaos game as the fern, but each affine map is
followed by a nonlinear variation (swirl, handkerchief, spherical), which bends the
straight-edged copies into curls. That is the step from an IFS to a fractal flame.
Flames are shown as log density, so the tone map is LOG: sparse threads and dense
knots stay visible together.

```edn [field-box id="fern_flame" role="properties"]
{"id" "fern_flame" "index" 3 "name" "flame-style IFS" "parents" ["root" "fern_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "fern_renderer"} "name" "flame-style IFS" "_tombstone" false "frame" #afield/rect [40.0 660.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fern_flame_code_.kt" "lines" 20 "characters" 655 "sha256" "54742fc653e2ee787b45a538fe737aa8f7f342b6c895d808e00956e7bc77f19e"} {"name" "compute" "language" "glsl" "path" "fern_flame_compute_.glsl" "lines" 50 "characters" 1242 "sha256" "ebb9e662dfc42e91b0cb13b025e02bdff82e810faf9227ca64d1313f7d20c323"}]}
```

```kotlin [field-property box="fern_flame" name="code" path="fern_flame_code_.kt"]
/*
A flame-style IFS. The same weighted chaos game as the fern, but each affine map is
followed by a nonlinear variation (swirl, handkerchief, spherical), which bends the
straight-edged copies into curls. That is the step from an IFS to a fractal flame.
Flames are shown as log density, so the tone map is LOG: sparse threads and dense
knots stay visible together.
*/
var module = computeModuleHere {}

val flame = IteratedSystem(module) {
    viewport(-1.95, 1.95, -0.3, 2.9)
    background = hexColor("#040103")
    particles = 250_000
    steps = 2
    fade = 0.04
    fps = 24.0
    toneMap = PointToneMap.LOG
    exposure = 1.0
}
showControls(flame)
```

```glsl [field-property box="fern_flame" name="compute" path="fern_flame_compute_.glsl"]
#version 450
#include "points/system"

// data.x: the colour mix, carried from step to step; data.y: which map made this point

State initParticle(uint index, uint count) {
    return State(vec3(0.0), 0.0, vec4(random(), 0.0, 0.0, 0.0));
}

void step(inout State s, float dt) {
    vec2 p = s.pos.xy;
    float r = random();
    float tint;
    if (r < 0.5) {
        p = swirl(affine(p, 0.72, 0.07, -0.05, 0.72, 0.0, 0.55));
        tint = 0.08;
        s.data.y = 0.0;
    } else if (r < 0.82) {
        p = handkerchief(affine(p, 0.43, -0.45, 0.48, 0.45, 0.22, 0.92));
        tint = 0.42;
        s.data.y = 1.0;
    } else if (r < 0.94) {
        p = spherical(affine(p, -0.38, 0.34, 0.36, 0.42, -0.28, 1.18));
        tint = 0.68;
        s.data.y = 2.0;
    } else {
        p = linear(affine(p, 0.18, 0.0, 0.0, 0.18, 0.0, 0.14));
        tint = 0.94;
        s.data.y = 3.0;
    }
    s.pos.xy = p;
    s.data.x = s.data.x * 0.84 + tint * 0.16;
}

vec2 project(State s) {
    return s.pos.xy;
}

bool shouldDraw(State s) {
    return s.age > 12.0;
}

vec4 color(State s) {
    float m = s.data.x;
    return hsla(20.0 + m * 300.0, 100.0, 68.0, 0.18 + m * 0.26);
}

float pointSize(State s) {
    return s.data.y == 3.0 ? 0.7 : 1.0;
}
```

## 5. Barnsley fern

<!-- field-box id="fern_barnsley" index="4" -->

### Narrative

From `code`

The Barnsley fern. Four affine maps, each picked at random with its own weight: every
particle plays the "chaos game" — pick a map, apply it, plot the point — and the points
pile up on the one shape the four maps together send onto itself. The rule is in this
box's compute tab; edit it and the shader reloads.

Low fade keeps the whole leaf; low fps shows it building up at random.

```edn [field-box id="fern_barnsley" role="properties"]
{"id" "fern_barnsley" "index" 4 "name" "Barnsley fern" "parents" ["root" "fern_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "fern_renderer"} "name" "Barnsley fern" "_tombstone" false "frame" #afield/rect [40.0 200.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fern_barnsley_code_.kt" "lines" 20 "characters" 642 "sha256" "465350835ba6b09747ec8601dc4cc9e52a769a222658aa11312db7f4c3daec9e"} {"name" "compute" "language" "glsl" "path" "fern_barnsley_compute_.glsl" "lines" 50 "characters" 1341 "sha256" "f0e03c524d4dc2951538a9dd57f13ba63d625d61cbaca0da29b8e1535dee9b1d"}]}
```

```kotlin [field-property box="fern_barnsley" name="code" path="fern_barnsley_code_.kt"]
/*
The Barnsley fern. Four affine maps, each picked at random with its own weight: every
particle plays the "chaos game" — pick a map, apply it, plot the point — and the points
pile up on the one shape the four maps together send onto itself. The rule is in this
box's compute tab; edit it and the shader reloads.

Low fade keeps the whole leaf; low fps shows it building up at random.
*/
var module = computeModuleHere {}

val fern = IteratedSystem(module) {
    viewport(-3.1, 3.1, 0.0, 10.4)
    background = hexColor("#010301")
    particles = 200_000
    steps = 1
    fade = 0.06
    fps = 24.0
    exposure = 0.25
}
showControls(fern)
```

```glsl [field-property box="fern_barnsley" name="compute" path="fern_barnsley_compute_.glsl"]
#version 450
#include "points/system"

// data.x: which of the four maps made this point

State initParticle(uint index, uint count) {
    return State(vec3(0.0), 0.0, vec4(0.0));
}

// each map in the IFS-table form: x' = a x + b y + e, y' = c x + d y + f
void step(inout State s, float dt) {
    vec2 p = s.pos.xy;
    float r = random();
    if (r < 0.01) {
        s.pos.xy = affine(p, 0.0, 0.0, 0.0, 0.16, 0.0, 0.0);      // the stem
        s.data.x = 0.0;
    } else if (r < 0.86) {
        s.pos.xy = affine(p, 0.85, 0.04, -0.04, 0.85, 0.0, 1.6);  // the next leaflet up
        s.data.x = 1.0;
    } else if (r < 0.93) {
        s.pos.xy = affine(p, 0.2, -0.26, 0.23, 0.22, 0.0, 1.6);   // the left leaflet
        s.data.x = 2.0;
    } else {
        s.pos.xy = affine(p, -0.15, 0.28, 0.26, 0.24, 0.0, 0.44); // the right leaflet
        s.data.x = 3.0;
    }
}

vec2 project(State s) {
    return s.pos.xy;
}

bool shouldDraw(State s) {
    // the first few steps are still on their way to the fern
    return s.age > 8.0;
}

vec4 color(State s) {
    vec4 palette[4] = vec4[](
        rgba(140, 255, 190, 0.35),
        rgba(94, 255, 140, 0.40),
        rgba(195, 255, 140, 0.32),
        rgba(70, 220, 120, 0.34)
    );
    return palette[int(s.data.x)];
}

float pointSize(State s) {
    return s.data.x == 0.0 ? 0.7 : 1.15;
}
```

