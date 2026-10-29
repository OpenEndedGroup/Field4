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

From `notes`

Vortex brush

From `code`

Vortex brush: a drifting vortex field that tightens into rings and loosens back out.
Move the pointer over the renderer window and the vortex centre follows it; mouse and
mouseInside are the pointer, in the same pixels as everything else.

```edn [field-box id="particles_vortex_brush" role="properties"]
{"id" "particles_vortex_brush" "index" 1 "name" "vortex brush" "parents" ["root" "particles_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "particles_renderer"} "name" "vortex brush" "_tombstone" false "frame" #afield/rect [40.0 1120.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "particles_vortex_brush_notes_.md" "lines" 5 "characters" 282 "sha256" "5ced5ad6fef8634c00743707cf9731508eaeb91f57a06b186d77b361dca48506"} {"name" "code" "language" "kotlin" "path" "particles_vortex_brush_code_.kt" "lines" 27 "characters" 790 "sha256" "81c66b5d7baba3767819645e2ce3368393ac02008ce4ec8f8649ff2429a4a136"} {"name" "compute" "language" "glsl" "path" "particles_vortex_brush_compute_.glsl" "lines" 44 "characters" 1170 "sha256" "e3522a7fca84c76517dd0906a384a82dac5c21682735ea823d5ccfc96600604c"}]}
```

```markdown [field-property box="particles_vortex_brush" name="notes" path="particles_vortex_brush_notes_.md"]
# Vortex brush

Particles settle into rings about a centre: their band (`p.group`) sets the radius, and a
tangential force spins them round. The centre drifts on its own, and follows the pointer while it
is over the window. Move slowly through the window to drag the vortex around.
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
        sketch.paint(
            (0..40).map { i ->
                val a = k * PI / 4 + i / 40.0 * 2.2
                vec(550 + cos(a) * (70 + k * 30), 380 + sin(a) * (70 + k * 30))
            },
        )
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

From `notes`

Particles

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
{"id" "particles_boids" "index" 2 "name" "boids ribbon" "parents" ["root" "particles_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "particles_renderer"} "name" "boids ribbon" "_tombstone" false "frame" #afield/rect [40.0 200.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "particles_boids_notes_.md" "lines" 85 "characters" 4943 "sha256" "186387f0bfe81dfaecb6c9334334d56e11a8392848a1fbc46ef525fc6f8af5b5"} {"name" "code" "language" "kotlin" "path" "particles_boids_code_.kt" "lines" 40 "characters" 1477 "sha256" "89c2ed280a9f1789190107607d3a151c3d4fc8d6477d0e206adba020ba707bdf"} {"name" "compute" "language" "glsl" "path" "particles_boids_compute_.glsl" "lines" 55 "characters" 1692 "sha256" "9dee72db57521ab74224621398df07de50cdf86d28956af6840a94c1285eb63d"}]}
```

````markdown [field-property box="particles_boids" name="notes" path="particles_boids_notes_.md"]
# Particles

A sketchbook for particle systems. The window starts empty: **drag in the renderer window to
paint particles**, and the four hooks in this box's compute tab decide what they do. Every
particle runs the same GLSL on the GPU, once a frame, so ten thousand of them cost about what ten
do.

Launch a preset box below to switch to it; launch this one to come back. Re-running a box keeps
the particles you have painted and lets the new code take them over, so edit, re-run, and watch
the same crowd change its mind. **reset** in the controls empties the window, **trail** sets how
long the motion blur lasts, and **pause** holds everything still while you paint.

## The four hooks

```diffstatic {data-lang=glsl}
void spawn(vec2 at)                       // a particle is painted at `at`: set up p
void eachPair(Particle other, Pair pair)  // p and one neighbour within pairRadius
void update()                             // p's own behaviour, once a frame
void draw()                               // overlays, once a frame
```

`p` is the particle being worked on. The verbs act on it: `force(vec2(0.0, 40.0))` pushes `p`
down, `flock(...)` steers it with its neighbours, `wrap(16.0)` brings it back in at the other
edge. Coordinates are the window's, in pixels, with the origin at the top left and y pointing
down; `width` and `height` are the window's size, `t` is the time in seconds, `mouse` is the
pointer.

## Local, pairwise and shared

The hooks are three kinds of agent:

- **Local** behaviour lives in `update()`: forces, drag, the speed limit, the walls, the colour.
  It only reads `p` (and, through `flock` and `clump`, its neighbours).
- **Pairwise** behaviour lives in `eachPair()`: a spring, a collision, a thread drawn between two
  particles. `pairRadius` in this box's code sets how far apart two particles can be and still
  meet. Every pair is met twice, once from each side, and `spring` and `collide` apply only `p`'s
  half, so the two halves add up to the whole spring.
- **Shared** agents belong to no particle: a hunter, a vortex centre, the pointer. Write them as a
  function of `t` (see `hunterAt()` in **predator wake**) that every `update()` asks, and show
  them in `draw()`.

## The order of a frame

Every frame runs in the same order, and most surprises are about the order:

1. new paint becomes particles (`spawn`);
2. every particle starts with `world.gravity` as its acceleration;
3. the neighbour grid is rebuilt;
4. `eachPair`, then `update`, for every particle;
5. `vel += acc * dt`, `vel *= damping`, the speed limit, `pos += vel * dt`;
6. the picture fades by `1 - trail`, then links, dots and overlays are drawn.

While `eachPair` and `update` run, every *other* particle is read as it was at the top of the
frame. Only `p` changes. So the order in which particles are visited never matters.

## The verbs

| | |
| --- | --- |
| forces | `force(f)`, `impulse(i)`, `drag(amount)`, `limitSpeed(max)`, `bounce(restitution)`, `wrap(pad)` |
| steering | `attract(target, strength)`, `springTo(target, rest, stiffness, damping)`, `oscillate(axis, amplitude, frequency, phase)` |
| neighbours | `flock(radius, separation, alignment, cohesion, maxForce)`, `clump(radius, strength, repelRadius, repelStrength)`, `neighbors(radius)`, `nearest(radius)`, `particle(i)` |
| pairs | `spring(other, pair, rest, stiffness, damping)`, `collide(other, pair, restitution)`, `link(other, color, width)` |
| overlays | `line(a, b, color, width)`, `ring(centre, radius, color, width)`, `circle(centre, radius, color)`, `cross(centre, size, color, width)` |
| numbers | `rand(lo, hi)`, `randInt(lo, hi)`, `noise2D(x, y)`, `triWave(x)`, `tri01(x)`, `lerp(a, b, x)` |
| colour | `hsla(h, s, l, a)` and `rgba(r, g, b, a)`, in CSS units |

A particle has `pos`, `vel`, `acc`, `home` (where it was painted), `color`, `radius`, `mass`,
`seed` (0 to 1000), `age`, `id`, a free `int group` and a free `vec4 data`. The world settings
(`background`, `damping`, `maxSpeed`, `gravity`, `pairRadius`, `trail`, ...) are in this box's
code.

## This one: boids ribbon

Three schools (`p.group`) flock along a slowly turning noise field, with a gentle swirl about the
centre and a speed floor that keeps them cruising. Threads join neighbours of the same school.
Try painting one long stroke across the window and watching it break into schools, or change the
`flock` weights: separation pushes apart, alignment matches heading, cohesion pulls together.

??? aside "How this differs from the madp demo"
    The hooks, the verbs and their constants are madp's, run on the GPU. GLSL has no option
    objects, so the verbs take their options in order. The world steps 60 times a second, as
    madp's browser does, because `drag`, `damping` and the trail are per-frame amounts. A
    collision's push lands after the last pair rather than during the loop, so a pile settles the
    same way whichever side it is visited from.
````

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
        sketch.paint(
            (0..40).map { i ->
                val a = i / 40.0 * 2 * PI
                vec(170 + k * 190 + cos(a) * 55, 380 + sin(a) * 150)
            },
        )
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

From `notes`

Pendulum bloom

From `code`

Pendulum bloom: home tethers and neighbour springs turn the painted field into a
hanging fabric. Every particle remembers where it was painted (p.home); springs join
particles that were painted close together, at the distance they were painted apart.
Paint loose curtains or bunches.

```edn [field-box id="particles_pendulum_bloom" role="properties"]
{"id" "particles_pendulum_bloom" "index" 3 "name" "pendulum bloom" "parents" ["root" "particles_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "particles_renderer"} "name" "pendulum bloom" "_tombstone" false "frame" #afield/rect [1240.0 660.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "particles_pendulum_bloom_notes_.md" "lines" 6 "characters" 383 "sha256" "6236744c105140264fb1cbb1ceab26760971ff016ea2bc5bdf5ec7742960c2ad"} {"name" "code" "language" "kotlin" "path" "particles_pendulum_bloom_code_.kt" "lines" 26 "characters" 791 "sha256" "0bde869daf7721ce93cf13f126d7930749ebc9ea0f633928e3047c7d91648044"} {"name" "compute" "language" "glsl" "path" "particles_pendulum_bloom_compute_.glsl" "lines" 46 "characters" 1289 "sha256" "95af5655f0ad8773094ad38c3a48cc6f21f4913a841f0f7ea5cdcfda8a367283"}]}
```

```markdown [field-property box="particles_pendulum_bloom" name="notes" path="particles_pendulum_bloom_notes_.md"]
# Pendulum bloom

Every particle remembers where it was painted (`p.home`). Springs join particles that were
painted close together, at the distance they were painted apart, so the painted shape becomes a
fabric; one particle in thirty is also tied back to its home, and those tethers are drawn as
overlays. Paint loose curtains from the top of the window and let gravity take them.
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

From `notes`

Spring mesh

From `code`

Spring mesh: nearby particles knit themselves into a spring web and local clumps. The
web lasts a few seconds before clumping pulls it into balls, as it does in the demo;
drag a long stroke through the window to watch it knit again.

Springs live in eachPair because a spring is about two particles. The GPU sees every
pair twice, once from each side, and spring() applies only p's half, so the two halves
add up to the whole spring. link() draws the thread once, from one side.

```edn [field-box id="particles_spring_mesh" role="properties"]
{"id" "particles_spring_mesh" "index" 4 "name" "spring mesh" "parents" ["root" "particles_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "particles_renderer"} "name" "spring mesh" "_tombstone" false "frame" #afield/rect [640.0 200.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "particles_spring_mesh_notes_.md" "lines" 6 "characters" 366 "sha256" "feb0bc471a520d9732aa1334345a99b0b22a0208da4d2f90c1be7afaed657e78"} {"name" "code" "language" "kotlin" "path" "particles_spring_mesh_code_.kt" "lines" 27 "characters" 946 "sha256" "05e24860585668008bcea788cfbc2ca2571be089396a806d77542071af8d3ad1"} {"name" "compute" "language" "glsl" "path" "particles_spring_mesh_compute_.glsl" "lines" 28 "characters" 687 "sha256" "e0fd4285e7dccc92391734ad45bfcdc0bf1bad59431de6769c5bc5cf65bf1176"}]}
```

```markdown [field-property box="particles_spring_mesh" name="notes" path="particles_spring_mesh_notes_.md"]
# Spring mesh

Neighbours closer than 60 pixels are joined by springs that want to be 30 long, and `clump` pulls
each particle toward the middle of its crowd. The web knits for a few seconds, then clumping wins
and it draws itself into balls. Drag a long stroke through the window to watch it knit again, or
lower the clump strength in `update()` and the web lasts.
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

From `notes`

Predator wake

From `code`

Predator wake: an invisible hunter cuts through the flock and the school shears around
it. The hunter is a shared agent: no particle owns it, every particle's update() asks
where it is, and draw() shows it. Paint a few separated groups and let it stir them up.

```edn [field-box id="particles_predator_wake" role="properties"]
{"id" "particles_predator_wake" "index" 5 "name" "predator wake" "parents" ["root" "particles_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "particles_renderer"} "name" "predator wake" "_tombstone" false "frame" #afield/rect [1240.0 200.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "particles_predator_wake_notes_.md" "lines" 6 "characters" 312 "sha256" "7f74f9c7361843e72b465d219359a406e8b0355fe65f0b99d012a98501cc91b7"} {"name" "code" "language" "kotlin" "path" "particles_predator_wake_code_.kt" "lines" 26 "characters" 884 "sha256" "5a6d8b34fc23786a8c44f7bc1f66262e6d35f73f95755eeb32b39a3181d25231"} {"name" "compute" "language" "glsl" "path" "particles_predator_wake_compute_.glsl" "lines" 62 "characters" 1870 "sha256" "5199bc9c3d47514741918697aaec1af98f2d84e475df6814c603a45c5f6b6948"}]}
```

```markdown [field-property box="particles_predator_wake" name="notes" path="particles_predator_wake_notes_.md"]
# Predator wake

A hunter that no particle owns: `hunterAt()` is a function of `t`, every `update()` asks it where
it is and flees, and `draw()` shows it. The schools shear around it and close up behind. Paint a
few separate groups and let it stir them up; make the hunter follow `mouse` to chase them
yourself.
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

From `notes`

Sine garden

From `code`

Sine garden: soft orbits, neighbour clumping and sinusoidal forcing layered together.
Sinusoidal motion needs no special case; oscillate() is just another force. No pairs
here, so pairRadius stays 0 and eachPair never runs.

```edn [field-box id="particles_sine_garden" role="properties"]
{"id" "particles_sine_garden" "index" 6 "name" "sine garden" "parents" ["root" "particles_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "particles_renderer"} "name" "sine garden" "_tombstone" false "frame" #afield/rect [640.0 660.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "particles_sine_garden_notes_.md" "lines" 5 "characters" 296 "sha256" "09c1ee8de95762625b480a4200819e27b200aac190640c23352727572ea4cf59"} {"name" "code" "language" "kotlin" "path" "particles_sine_garden_code_.kt" "lines" 27 "characters" 773 "sha256" "12779848b9b60c8081c08a131c067cfb15d383bd830b1ec664cc2cedd7373de9"} {"name" "compute" "language" "glsl" "path" "particles_sine_garden_compute_.glsl" "lines" 33 "characters" 859 "sha256" "8a941292e3a07372e5861f2c6112a5c4a18a1cb5ff08814a00ee500a5a7a8bd4"}]}
```

```markdown [field-property box="particles_sine_garden" name="notes" path="particles_sine_garden_notes_.md"]
# Sine garden

No pairs here: `pairRadius` stays 0 and `eachPair` never runs. Each particle is held on a soft
spring to a ring about the centre whose radius drifts with noise, pushed along its own axis by
`oscillate`, and drawn toward its neighbours by `clump`. A sinusoid is just another force.
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
    sketch.paint(
        (0..60).map { i ->
            val a = i / 60.0 * 2 * PI
            vec(550 + cos(a) * 160, 380 + sin(a) * 160)
        },
    )
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

From `notes`

Bounce house

From `code`

Bounce house: gravity, wall bounces and soft ball collisions. Drag to pour in more mass.

Gravity is a world setting, added to every particle before anything else. Collisions
are pairwise, in eachPair; the walls are each particle's own business, in update().

```edn [field-box id="particles_bounce_house" role="properties"]
{"id" "particles_bounce_house" "index" 7 "name" "bounce house" "parents" ["root" "particles_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "particles_renderer"} "name" "bounce house" "_tombstone" false "frame" #afield/rect [40.0 660.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "particles_bounce_house_notes_.md" "lines" 5 "characters" 293 "sha256" "eb2aa165f09f9d45ba9a788d288fc2b110e1f2270b89c2eac977cc23132caf23"} {"name" "code" "language" "kotlin" "path" "particles_bounce_house_code_.kt" "lines" 25 "characters" 827 "sha256" "75c315b6b1558bce66a5eb3ec04e4d8fec1cac4d9b4ca6ea7260ee5de55da876"} {"name" "compute" "language" "glsl" "path" "particles_bounce_house_compute_.glsl" "lines" 24 "characters" 577 "sha256" "e1829207893a93e844ff427a1d24a010ed4207e8b80db98cecab28e7daa16b15"}]}
```

```markdown [field-property box="particles_bounce_house" name="notes" path="particles_bounce_house_notes_.md"]
# Bounce house

Gravity is a world setting, added to every particle before anything else runs. Collisions are
pairwise, in `eachPair`; the walls are each particle's own business, in `update()`. Drag to pour
in more balls. Mass grows with the radius, so the big ones push the small ones aside.
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

