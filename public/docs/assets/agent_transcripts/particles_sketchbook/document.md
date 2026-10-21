# Field Document: particles_sketchbook

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 8 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="particles_renderer" index="0" -->

```edn [field-box id="particles_renderer" role="properties"]
{"id" "particles_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["particles_boids" "particles_spring_mesh" "particles_predator_wake" "particles_bounce_house" "particles_sine_garden" "particles_pendulum_bloom" "particles_vortex_brush"] "properties" {"children" #{#afield/box "particles_boids" #afield/box "particles_spring_mesh" #afield/box "particles_predator_wake" #afield/box "particles_bounce_house" #afield/box "particles_sine_garden" #afield/box "particles_pendulum_bloom" #afield/box "particles_vortex_brush"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "particles_renderer_code_.kt" "lines" 13 "characters" 437 "sha256" "e7ac53752b8a02d34e7ed91297929a51cc87b1e23323034630496cd01f0e5cc3"}]}
```

```kotlin [field-property box="particles_renderer" name="code" path="particles_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
// once: re-running this box keeps the existing window and renderer
once.renderer {
    window = Window("Particles", initialDimensions = Rect(80.0, 80.0, 1100.0, 760.0))
    Renderer(window)
}
// the frame loop runs as this box's coroutine; stopping the box stops rendering
start(renderer::run)
```

## 2. vortex brush

<!-- field-box id="particles_vortex_brush" index="1" -->

### Narrative

From `code`

Vortex brush: a drifting vortex field that tightens into rings and loosens back out.
Move the pointer over the renderer window and the vortex centre follows it; mouse and
mouseInside are the pointer, in the same pixels as everything else.

```edn [field-box id="particles_vortex_brush" role="properties"]
{"id" "particles_vortex_brush" "index" 1 "name" "vortex brush" "parents" ["root" "particles_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "particles_renderer"} "name" "vortex brush" "_tombstone" false "frame" #afield/rect [40.0 1120.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "particles_vortex_brush_code_.kt" "lines" 25 "characters" 755 "sha256" "dcbe9f6e1d2fdd0b2a6e89bd02adec93786cabf07ea3b88646ed1a96b4c3c1a8"} {"name" "compute" "language" "glsl" "path" "particles_vortex_brush_compute_.glsl" "lines" 44 "characters" 1178 "sha256" "9197001c6e3002df58a3a425a9204cf4bff0432b50f52ac14ce771301c350005"}]}
```

```kotlin [field-property box="particles_vortex_brush" name="code" path="particles_vortex_brush_code_.kt"]
/*
Vortex brush: a drifting vortex field that tightens into rings and loosens back out.
Move the pointer over the renderer window and the vortex centre follows it; mouse and
mouseInside are the pointer, in the same pixels as everything else.
*/
var module = computeModuleHere {}

val sketch = ParticleSketch(module) {
    background = hexColor("#050613")
    particleRadius = 2.7
    damping = 0.996
    maxSpeed = 190.0
    trail = 0.24
}
showControls(sketch)

// eight arcs around the middle; drag in the window for more
if (sketch.isEmpty) {
    for (k in 0 until 8) {
        sketch.paint((0..40).map { i ->
            val a = k * PI / 4 + i / 40.0 * 2.2
            vec(550 + cos(a) * (70 + k * 30), 380 + sin(a) * (70 + k * 30))
        })
    }
}
```

```glsl [field-property box="particles_vortex_brush" name="compute" path="particles_vortex_brush_compute_.glsl"]
#version 450
#include "particles/sketch"

// p.group is the particle's band, which ring it settles into; p.data.x its phase.

void spawn(vec2 at) {
    p.pos = at;
    p.vel = vec2(rand(-18.0, 18.0), rand(-18.0, 18.0));
    p.radius = 2.0 + rand(0.0, 1.4);
    p.group = randInt(0, 4);
    p.data.x = rand(0.0, TAU);
    p.color = hsla(float(randInt(215, 300)), 95.0, 74.0, 0.92);
}

void eachPair(Particle other, Pair pair) {
}

void update() {
    vec2 anchor = mouseInside
        ? mouse
        : vec2(width * 0.5 + cos(t * 0.23) * 120.0, height * 0.5 + sin(t * 0.31) * 88.0);

    vec2 away = p.pos - anchor;
    float dist = length(away);
    if (dist == 0.0) dist = 1.0;
    vec2 n = away / dist;
    vec2 tangent = vec2(-n.y, n.x);
    float band = float(p.group);
    float phase = p.data.x;

    float targetRadius = 48.0 + band * 24.0 + tri01(t * 0.18 + phase) * 118.0;
    springTo(anchor, targetRadius, 2.8, 0.16);
    force(tangent * (42.0 + band * 7.0));
    oscillate(n, 8.0, 0.36, phase);

    drag(0.004);
    limitSpeed(178.0);
    wrap(28.0);

    p.color = hsla(mod(230.0 + band * 14.0 + dist * 0.2 + t * 22.0, 360.0), 95.0, 74.0, 0.9);
}

void draw() {
}
```

## 3. boids ribbon

<!-- field-box id="particles_boids" index="2" -->

### Narrative

From `code`

Particles: paint them into the renderer window by dragging, and four hooks in this box's
compute tab decide what they do. Each hook runs on the GPU, once for every particle:

spawn(at)              a particle is painted at `at`: set up p
eachPair(other, pair)  p and each neighbour within pairRadius: springs, collisions, links
update()               p's own behaviour: forces, steering, colour
draw()                 overlays, once a frame

Every frame runs in the same order: gravity, the neighbour grid, eachPair, update, then
vel += acc * dt, damping, the speed limit and pos += vel * dt. The other particles are
read as they were at the top of the frame; only p changes.

This one is a cruising school: flocking, a noise flow and a speed floor keep it alive.
The world settings below are the demo's init().

```edn [field-box id="particles_boids" role="properties"]
{"id" "particles_boids" "index" 2 "name" "boids ribbon" "parents" ["root" "particles_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "particles_renderer"} "name" "boids ribbon" "_tombstone" false "frame" #afield/rect [40.0 200.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "particles_boids_code_.kt" "lines" 38 "characters" 1442 "sha256" "40aa111df0971e7c6735207103b18530596a11067f04d72abe8907852f345bc5"} {"name" "compute" "language" "glsl" "path" "particles_boids_compute_.glsl" "lines" 55 "characters" 1700 "sha256" "7fc2a3ee44febe54a8ca0373483c808fe23c42af67fb11799b617e36dc161eaf"}]}
```

```kotlin [field-property box="particles_boids" name="code" path="particles_boids_code_.kt"]
/*
Particles: paint them into the renderer window by dragging, and four hooks in this box's
compute tab decide what they do. Each hook runs on the GPU, once for every particle:

  spawn(at)              a particle is painted at `at`: set up p
  eachPair(other, pair)  p and each neighbour within pairRadius: springs, collisions, links
  update()               p's own behaviour: forces, steering, colour
  draw()                 overlays, once a frame

Every frame runs in the same order: gravity, the neighbour grid, eachPair, update, then
vel += acc * dt, damping, the speed limit and pos += vel * dt. The other particles are
read as they were at the top of the frame; only p changes.

This one is a cruising school: flocking, a noise flow and a speed floor keep it alive.
The world settings below are the demo's init().
*/
var module = computeModuleHere {}

val sketch = ParticleSketch(module) {
    background = hexColor("#03060a")
    particleColor = rgba(185, 255, 232, 0.92)
    particleRadius = 2.5
    damping = 0.998
    maxSpeed = 190.0
    pairRadius = 30.0 // eachPair reaches this far
    trail = 0.22
}
showControls(sketch)

// The world starts empty; these loops are there so it isn't. Drag in the window for more.
if (sketch.isEmpty) {
    for (k in 0 until 5) {
        sketch.paint((0..40).map { i ->
            val a = i / 40.0 * 2 * PI
            vec(170 + k * 190 + cos(a) * 55, 380 + sin(a) * 150)
        })
    }
}
```

```glsl [field-property box="particles_boids" name="compute" path="particles_boids_compute_.glsl"]
#version 450
#include "particles/sketch"

// p is the particle being worked on; force(), flock(), wrap() ... act on it.
// p.group picks one of three schools; links only join a school to itself.

void spawn(vec2 at) {
    float angle = rand(0.0, TAU);
    float speed = rand(70.0, 120.0);
    p.pos = at + vec2(rand(-6.0, 6.0), rand(-6.0, 6.0));
    p.vel = vec2(cos(angle), sin(angle)) * speed;
    p.radius = 2.0 + rand(0.0, 1.2);
    p.color = hsla(float(randInt(150, 210)), 92.0, 72.0, 0.95);
    p.group = randInt(0, 2);
}

void eachPair(Particle other, Pair pair) {
    if (p.group == other.group && pair.dist < 20.0) {
        link(other, rgba(120.0, 255.0, 210.0, 0.12), 1.0);
    }
}

void update() {
    // radius, separation, alignment, cohesion, maxForce
    int neighbours = flock(82.0, 2.2, 0.82, 0.52, 125.0);

    float flow = noise2D(
        p.pos.x * 0.004 + t * 0.18 + float(p.group) * 7.3,
        p.pos.y * 0.004 - t * 0.14 + p.seed * 0.002
    );
    float flowAngle = flow * TAU + float(p.group) * 1.4;
    vec2 along = vec2(cos(flowAngle), sin(flowAngle));

    vec2 fromCentre = p.pos - vec2(width, height) * 0.5;
    float dist = length(fromCentre);
    if (dist == 0.0) dist = 1.0;
    vec2 swirl = vec2(-fromCentre.y, fromCentre.x) / dist;

    force(along * 24.0 + swirl * 10.0);

    // a speed floor: anything slower than 78 is pushed back up to cruising
    float speed = length(p.vel);
    if (speed < 78.0) {
        force((along + swirl) * (78.0 - speed) * 0.9);
    }

    drag(0.002);
    limitSpeed(175.0);
    wrap(16.0);

    p.color = hsla(mod(155.0 + float(p.group) * 28.0 + float(neighbours) * 7.0 + t * 18.0, 360.0), 92.0, 72.0, 0.94);
}

void draw() {
}
```

## 4. pendulum bloom

<!-- field-box id="particles_pendulum_bloom" index="3" -->

### Narrative

From `code`

Pendulum bloom: home tethers and neighbour springs turn the painted field into a
hanging fabric. Every particle remembers where it was painted (p.home); springs join
particles that were painted close together, at the distance they were painted apart.
Paint loose curtains or bunches.

```edn [field-box id="particles_pendulum_bloom" role="properties"]
{"id" "particles_pendulum_bloom" "index" 3 "name" "pendulum bloom" "parents" ["root" "particles_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "particles_renderer"} "name" "pendulum bloom" "_tombstone" false "frame" #afield/rect [1240.0 660.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "particles_pendulum_bloom_code_.kt" "lines" 26 "characters" 791 "sha256" "0bde869daf7721ce93cf13f126d7930749ebc9ea0f633928e3047c7d91648044"} {"name" "compute" "language" "glsl" "path" "particles_pendulum_bloom_compute_.glsl" "lines" 46 "characters" 1289 "sha256" "95af5655f0ad8773094ad38c3a48cc6f21f4913a841f0f7ea5cdcfda8a367283"}]}
```

```kotlin [field-property box="particles_pendulum_bloom" name="code" path="particles_pendulum_bloom_code_.kt"]
/*
Pendulum bloom: home tethers and neighbour springs turn the painted field into a
hanging fabric. Every particle remembers where it was painted (p.home); springs join
particles that were painted close together, at the distance they were painted apart.
Paint loose curtains or bunches.
*/
var module = computeModuleHere {}

val sketch = ParticleSketch(module) {
    background = hexColor("#081016")
    gravity = vec(0, 42)
    particleRadius = 3.0
    damping = 0.993
    maxSpeed = 120.0
    pairRadius = 58.0
    trail = 0.2
}
showControls(sketch)

// curtains hanging from the top; drag in the window for more
if (sketch.isEmpty) {
    for (k in 0 until 9) {
        val x = 230.0 + k * 80
        sketch.paint((0..16).map { i -> vec(x + sin(i * 0.4 + k) * 14, 110 + i * 26) })
    }
}
```

```glsl [field-property box="particles_pendulum_bloom" name="compute" path="particles_pendulum_bloom_compute_.glsl"]
#version 450
#include "particles/sketch"

void spawn(vec2 at) {
    p.pos = at;
    p.vel = vec2(rand(-8.0, 8.0), rand(-8.0, 8.0));
    p.radius = 2.3 + rand(0.0, 1.8);
    p.color = hsla(float(randInt(175, 230)), 88.0, 74.0, 0.92);
}

void eachPair(Particle other, Pair pair) {
    float homeDist = length(p.home - other.home);
    if (homeDist < 32.0) {
        spring(other, pair, homeDist, 3.8, 0.24);
        link(other, rgba(140.0, 220.0, 255.0, 0.16), 1.0);
    }
}

bool showAnchor() {
    return int(mod(floor(p.home.x * 0.04) + floor(p.home.y * 0.04), 5.0)) == 0;
}

void update() {
    // one particle in thirty is tied back to where it was painted
    if (p.id % 30u == 9u) {
        springTo(p.home, 18.0, 7.2, 0.34);
    }

    oscillate(vec2(1.0, 0.0), 26.0, 0.1, p.home.y * 0.004 + p.seed * 0.002);
    oscillate(vec2(0.0, 1.0), 6.0, 0.16, p.home.x * 0.004 + p.seed * 0.001);

    drag(0.008);
    limitSpeed(104.0);
    bounce(0.92);

    p.color = hsla(mod(190.0 + p.home.x * 0.04 + t * 24.0, 360.0), 90.0, 74.0, 0.92);

    // an overlay per particle: the scaffold the fabric breathes around
    if (showAnchor()) {
        line(p.home, p.pos, rgba(150.0, 220.0, 255.0, 0.14), 1.0);
        circle(p.home, 1.6, rgba(220.0, 245.0, 255.0, 0.78));
    }
}

void draw() {
}
```

## 5. spring mesh

<!-- field-box id="particles_spring_mesh" index="4" -->

### Narrative

From `code`

Spring mesh: nearby particles knit themselves into a spring web and local clumps. The
web lasts a few seconds before clumping pulls it into balls, as it does in the demo;
drag a long stroke through the window to watch it knit again.

Springs live in eachPair because a spring is about two particles. The GPU sees every
pair twice, once from each side, and spring() applies only p's half, so the two halves
add up to the whole spring. link() draws the thread once, from one side.

```edn [field-box id="particles_spring_mesh" role="properties"]
{"id" "particles_spring_mesh" "index" 4 "name" "spring mesh" "parents" ["root" "particles_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "particles_renderer"} "name" "spring mesh" "_tombstone" false "frame" #afield/rect [640.0 200.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "particles_spring_mesh_code_.kt" "lines" 27 "characters" 946 "sha256" "05e24860585668008bcea788cfbc2ca2571be089396a806d77542071af8d3ad1"} {"name" "compute" "language" "glsl" "path" "particles_spring_mesh_compute_.glsl" "lines" 28 "characters" 687 "sha256" "e0fd4285e7dccc92391734ad45bfcdc0bf1bad59431de6769c5bc5cf65bf1176"}]}
```

```kotlin [field-property box="particles_spring_mesh" name="code" path="particles_spring_mesh_code_.kt"]
/*
Spring mesh: nearby particles knit themselves into a spring web and local clumps. The
web lasts a few seconds before clumping pulls it into balls, as it does in the demo;
drag a long stroke through the window to watch it knit again.

Springs live in eachPair because a spring is about two particles. The GPU sees every
pair twice, once from each side, and spring() applies only p's half, so the two halves
add up to the whole spring. link() draws the thread once, from one side.
*/
var module = computeModuleHere {}

val sketch = ParticleSketch(module) {
    background = hexColor("#041015")
    particleRadius = 2.8
    damping = 0.992
    maxSpeed = 110.0
    pairRadius = 84.0
    trail = 0.18
}
showControls(sketch)

// a woven block of strokes; drag in the window for more
if (sketch.isEmpty) {
    for (k in 0 until 12) {
        sketch.paint((0..20).map { i -> vec(250 + 600 * i / 20.0, 170 + 36 * k + sin(i * 0.5 + k) * 20) })
    }
}
```

```glsl [field-property box="particles_spring_mesh" name="compute" path="particles_spring_mesh_compute_.glsl"]
#version 450
#include "particles/sketch"

void spawn(vec2 at) {
    p.pos = at + vec2(rand(-3.0, 3.0), rand(-3.0, 3.0));
    p.vel = vec2(rand(-18.0, 18.0), rand(-18.0, 18.0));
    p.radius = 2.0 + rand(0.0, 1.5);
    p.color = hsla(float(randInt(165, 205)), 88.0, 70.0, 0.92);
}

void eachPair(Particle other, Pair pair) {
    if (pair.dist < 60.0) {
        // restLength, stiffness, damping
        spring(other, pair, 30.0, 4.8, 0.18);
        link(other, rgba(115.0, 240.0, 255.0, 0.16), 1.0);
    }
}

void update() {
    // radius, strength, repelRadius, repelStrength
    clump(96.0, 12.0, 18.0, 50.0);
    drag(0.026);
    limitSpeed(90.0);
    bounce(0.92);
}

void draw() {
}
```

## 6. predator wake

<!-- field-box id="particles_predator_wake" index="5" -->

### Narrative

From `code`

Predator wake: an invisible hunter cuts through the flock and the school shears around
it. The hunter is a shared agent: no particle owns it, every particle's update() asks
where it is, and draw() shows it. Paint a few separated groups and let it stir them up.

```edn [field-box id="particles_predator_wake" role="properties"]
{"id" "particles_predator_wake" "index" 5 "name" "predator wake" "parents" ["root" "particles_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "particles_renderer"} "name" "predator wake" "_tombstone" false "frame" #afield/rect [1240.0 200.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "particles_predator_wake_code_.kt" "lines" 26 "characters" 884 "sha256" "5a6d8b34fc23786a8c44f7bc1f66262e6d35f73f95755eeb32b39a3181d25231"} {"name" "compute" "language" "glsl" "path" "particles_predator_wake_compute_.glsl" "lines" 62 "characters" 1870 "sha256" "5199bc9c3d47514741918697aaec1af98f2d84e475df6814c603a45c5f6b6948"}]}
```

```kotlin [field-property box="particles_predator_wake" name="code" path="particles_predator_wake_code_.kt"]
/*
Predator wake: an invisible hunter cuts through the flock and the school shears around
it. The hunter is a shared agent: no particle owns it, every particle's update() asks
where it is, and draw() shows it. Paint a few separated groups and let it stir them up.
*/
var module = computeModuleHere {}

val sketch = ParticleSketch(module) {
    background = hexColor("#061009")
    particleRadius = 2.4
    damping = 0.9985
    maxSpeed = 210.0
    pairRadius = 28.0
    trail = 0.18
}
showControls(sketch)

// small spirals scattered across the window; drag in the window for more
if (sketch.isEmpty) {
    val random = kotlin.random.Random(7)
    repeat(40) {
        val x = 60 + random.nextDouble() * 980
        val y = 60 + random.nextDouble() * 640
        sketch.paint((0..12).map { i -> vec(x + cos(i.toDouble()) * 30 * i / 12, y + sin(i.toDouble()) * 30 * i / 12) })
    }
}
```

```glsl [field-property box="particles_predator_wake" name="compute" path="particles_predator_wake_compute_.glsl"]
#version 450
#include "particles/sketch"

void spawn(vec2 at) {
    float angle = rand(0.0, TAU);
    float speed = rand(60.0, 110.0);
    p.pos = at + vec2(rand(-5.0, 5.0), rand(-5.0, 5.0));
    p.vel = vec2(cos(angle), sin(angle)) * speed;
    p.radius = 2.0 + rand(0.0, 1.0);
    p.group = randInt(0, 3);
    p.color = hsla(float(randInt(80, 140)), 95.0, 72.0, 0.94);
}

void eachPair(Particle other, Pair pair) {
    if (p.group == other.group && pair.dist < 18.0) {
        link(other, rgba(160.0, 255.0, 150.0, 0.1), 1.0);
    }
}

vec2 hunterAt() {
    return vec2(width * 0.5 + cos(t * 0.47) * 170.0, height * 0.5 + sin(t * 0.71) * 130.0);
}

void update() {
    vec2 hunter = hunterAt();

    // radius, separation, alignment, cohesion, maxForce
    int neighbours = flock(88.0, 2.4, 0.86, 0.48, 130.0);

    vec2 away = p.pos - hunter;
    float dist = length(away);
    if (dist == 0.0) dist = 1.0;
    vec2 n = away / dist;
    vec2 tangent = vec2(-n.y, n.x);

    if (dist < 210.0) {
        force(n * (210.0 - dist) * 1.9 + tangent * 26.0);
    }

    float drift = noise2D(p.seed * 0.004 + t * 0.25, float(p.group) * 0.7 + t * 0.09) * TAU;
    force(vec2(cos(drift), sin(drift)) * 20.0);

    float speed = length(p.vel);
    if (speed < 92.0) {
        force((tangent + n * 0.2) * (92.0 - speed));
    }

    drag(0.0015);
    limitSpeed(190.0);
    wrap(24.0);

    p.color = hsla(mod(95.0 + float(p.group) * 18.0 + float(neighbours) * 4.0 + t * 16.0, 360.0), 95.0, 72.0, 0.94);
}

void draw() {
    vec2 hunter = hunterAt();
    float pulse = 8.0 + tri01(t * 0.8) * 12.0;
    ring(hunter, 18.0 + pulse, rgba(255.0, 120.0, 120.0, 0.25), 2.0);
    ring(hunter, 8.0 + tri01(t * 1.3 + 0.2) * 4.0, rgba(255.0, 200.0, 160.0, 0.7), 2.0);
    cross(hunter, 8.0, rgba(255.0, 240.0, 220.0, 0.9), 1.5);
    circle(hunter, 2.8, rgba(255.0, 245.0, 225.0, 0.95));
}
```

## 7. sine garden

<!-- field-box id="particles_sine_garden" index="6" -->

### Narrative

From `code`

Sine garden: soft orbits, neighbour clumping and sinusoidal forcing layered together.
Sinusoidal motion needs no special case; oscillate() is just another force. No pairs
here, so pairRadius stays 0 and eachPair never runs.

```edn [field-box id="particles_sine_garden" role="properties"]
{"id" "particles_sine_garden" "index" 6 "name" "sine garden" "parents" ["root" "particles_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "particles_renderer"} "name" "sine garden" "_tombstone" false "frame" #afield/rect [640.0 660.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "particles_sine_garden_code_.kt" "lines" 25 "characters" 746 "sha256" "1b753245143817b2dfaa0261e36aeb882e1981c628981f67256133536cb7b29c"} {"name" "compute" "language" "glsl" "path" "particles_sine_garden_compute_.glsl" "lines" 33 "characters" 859 "sha256" "8a941292e3a07372e5861f2c6112a5c4a18a1cb5ff08814a00ee500a5a7a8bd4"}]}
```

```kotlin [field-property box="particles_sine_garden" name="code" path="particles_sine_garden_code_.kt"]
/*
Sine garden: soft orbits, neighbour clumping and sinusoidal forcing layered together.
Sinusoidal motion needs no special case; oscillate() is just another force. No pairs
here, so pairRadius stays 0 and eachPair never runs.
*/
var module = computeModuleHere {}

val sketch = ParticleSketch(module) {
    background = hexColor("#021017")
    particleRadius = 2.6
    damping = 0.994
    maxSpeed = 120.0
    trail = 0.3
}
showControls(sketch)

// a ring and two diagonals; drag in the window for more
if (sketch.isEmpty) {
    sketch.paint((0..60).map { i ->
        val a = i / 60.0 * 2 * PI
        vec(550 + cos(a) * 160, 380 + sin(a) * 160)
    })
    sketch.paint(250.0, 150.0, 850.0, 610.0)
    sketch.paint(850.0, 150.0, 250.0, 610.0)
}
```

```glsl [field-property box="particles_sine_garden" name="compute" path="particles_sine_garden_compute_.glsl"]
#version 450
#include "particles/sketch"

void spawn(vec2 at) {
    p.pos = at;
    p.vel = vec2(rand(-12.0, 12.0), rand(-12.0, 12.0));
    p.radius = 2.0 + rand(0.0, 1.6);
    p.color = hsla(float(randInt(170, 240)), 95.0, 74.0, 0.92);
}

void eachPair(Particle other, Pair pair) {
}

void update() {
    vec2 centre = vec2(width, height) * 0.5;
    float rest = 110.0 + noise2D(p.seed * 0.01, t * 0.2) * 70.0;

    // target, restLength, stiffness, damping
    springTo(centre, rest, 1.8, 0.15);
    // axis, amplitude, frequency, phase
    oscillate(vec2(cos(p.seed), sin(p.seed)), 24.0, 0.48, p.seed * 0.03);
    // radius, strength, repelRadius, repelStrength
    clump(56.0, 6.0, 12.0, 35.0);

    drag(0.024);
    limitSpeed(110.0);
    wrap(18.0);

    p.color = hsla(mod(185.0 + p.seed * 0.08 + t * 28.0, 360.0), 95.0, 72.0, 0.9);
}

void draw() {
}
```

## 8. bounce house

<!-- field-box id="particles_bounce_house" index="7" -->

### Narrative

From `code`

Bounce house: gravity, wall bounces and soft ball collisions. Drag to pour in more mass.

Gravity is a world setting, added to every particle before anything else. Collisions
are pairwise, in eachPair; the walls are each particle's own business, in update().

```edn [field-box id="particles_bounce_house" role="properties"]
{"id" "particles_bounce_house" "index" 7 "name" "bounce house" "parents" ["root" "particles_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "particles_renderer"} "name" "bounce house" "_tombstone" false "frame" #afield/rect [40.0 660.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "particles_bounce_house_code_.kt" "lines" 25 "characters" 827 "sha256" "75c315b6b1558bce66a5eb3ec04e4d8fec1cac4d9b4ca6ea7260ee5de55da876"} {"name" "compute" "language" "glsl" "path" "particles_bounce_house_compute_.glsl" "lines" 24 "characters" 577 "sha256" "e1829207893a93e844ff427a1d24a010ed4207e8b80db98cecab28e7daa16b15"}]}
```

```kotlin [field-property box="particles_bounce_house" name="code" path="particles_bounce_house_code_.kt"]
/*
Bounce house: gravity, wall bounces and soft ball collisions. Drag to pour in more mass.

Gravity is a world setting, added to every particle before anything else. Collisions
are pairwise, in eachPair; the walls are each particle's own business, in update().
*/
var module = computeModuleHere {}

val sketch = ParticleSketch(module) {
    background = hexColor("#09060a")
    gravity = vec(0, 420)
    damping = 0.999
    particleRadius = 5.0
    pairRadius = 22.0 // the two largest balls touch at 20
    trail = 0.08
}
showControls(sketch)

// six pours across the top; drag in the window for more
if (sketch.isEmpty) {
    for (k in 0 until 6) {
        sketch.paint(120.0 + k * 160, 80.0 + (k % 2) * 60, 260.0 + k * 160, 200.0 - (k % 2) * 60)
        sketch.paint(120.0 + k * 160, 260.0, 260.0 + k * 160, 300.0)
    }
}
```

```glsl [field-property box="particles_bounce_house" name="compute" path="particles_bounce_house_compute_.glsl"]
#version 450
#include "particles/sketch"

void spawn(vec2 at) {
    float r = 4.0 + rand(0.0, 6.0);
    p.pos = at + vec2(rand(-4.0, 4.0), rand(-4.0, 4.0));
    p.vel = vec2(rand(-120.0, 120.0), rand(-40.0, 40.0));
    p.radius = r;
    p.mass = r * 0.7;
    p.color = hsla(float(randInt(10, 50)), 90.0, 64.0, 0.96);
}

void eachPair(Particle other, Pair pair) {
    collide(other, pair, 0.94); // restitution
}

void update() {
    drag(0.001);
    bounce(0.88);
    p.color = hsla(18.0 + p.radius * 6.0 + min(50.0, abs(p.vel.y) * 0.06), 90.0, 64.0, 0.96);
}

void draw() {
}
```

