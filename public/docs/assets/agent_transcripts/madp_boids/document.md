# Field Document: madp_boids

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 8 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="boids_renderer" index="0" -->

```edn [field-box id="boids_renderer" role="properties"]
{"id" "boids_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["boids_murmuration" "boids_hawks" "boids_fireflies" "boids_ink" "boids_bait_ball" "boids_two_tribes" "boids_conga"] "properties" {"children" #{#afield/box "boids_murmuration" #afield/box "boids_hawks" #afield/box "boids_fireflies" #afield/box "boids_ink" #afield/box "boids_bait_ball" #afield/box "boids_two_tribes" #afield/box "boids_conga"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "boids_renderer_code_.kt" "lines" 13 "characters" 432 "sha256" "25810699dc864025b83e7b8730693c08f6533d04ded7e6aaaf98179db35355a7"}]}
```

```kotlin [field-property box="boids_renderer" name="code" path="boids_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
// once: re-running this box keeps the existing window and renderer
once.renderer {
    window = Window("Boids", initialDimensions = Rect(80.0, 80.0, 960.0, 720.0))
    Renderer(window)
}
// the frame loop runs as this box's coroutine; stopping the box stops rendering
start(renderer::run)
```

## 2. two tribes

<!-- field-box id="boids_two_tribes" index="1" -->

### Narrative

From `notes`

Two tribes

From `code`

Two tribes: everyone separates from everyone, but alignment and cohesion only count your own
colour, and the other colour is mildly repellent. Mixed flocks sort themselves out within a few
hundred steps. align and cohere see only their own kind unless told otherwise.

```edn [field-box id="boids_two_tribes" role="properties"]
{"id" "boids_two_tribes" "index" 1 "name" "two tribes" "parents" ["root" "boids_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "boids_renderer"} "name" "two tribes" "_tombstone" false "frame" #afield/rect [1240.0 660.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "boids_two_tribes_notes_.md" "lines" 5 "characters" 263 "sha256" "59aceca9ccccd15b433fef36e66c1c43d88b515df26e8990500bdfcb0b886f19"} {"name" "code" "language" "kotlin" "path" "boids_two_tribes_code_.kt" "lines" 47 "characters" 1260 "sha256" "86256116c2a8dd1358bf48ef4a4d0aa5cd30425414d90b82f7653bd25f18f883"} {"name" "compute" "language" "glsl" "path" "boids_two_tribes_compute_.glsl" "lines" 10 "characters" 225 "sha256" "935f960fc6de3de263df5acbb5809201bc4e718a05e36de059d31328d6120b28"}]}
```

```markdown [field-property box="boids_two_tribes" name="notes" path="boids_two_tribes_notes_.md"]
# Two tribes

Everyone keeps their distance from everyone (`separate(..., among = "all")`), but alignment and
cohesion only count their own colour, and the other colour is mildly repellent. A mixed flock
sorts itself into red and blue within a few hundred steps.
```

```kotlin [field-property box="boids_two_tribes" name="code" path="boids_two_tribes_code_.kt"]
/*
Two tribes: everyone separates from everyone, but alignment and cohesion only count your own
colour, and the other colour is mildly repellent. Mixed flocks sort themselves out within a few
hundred steps. align and cohere see only their own kind unless told otherwise.
*/
var module = computeModuleHere {}

val sim = BoidSketch(module) {
    background = hexColor("#0a0808")
    steps = 2
    trail = 16
    kind("red") {
        count = 450
        maxSpeed = 2.8
        minSpeed = 1.2
        maxForce = 0.07
        fov = 300.0
        rules(
            separate(12, 1.6, among = "all"),
            align(32, 1.0), // align and cohere default to "same"
            cohere(44, 0.9),
            flee("blue", 36, 1.1),
            wander(0.1),
        )
        shape = BoidShape.ARROW
        size = 3.0
        trailAlpha = 0.4
    }
    kind("blue") {
        count = 450
        maxSpeed = 2.8
        minSpeed = 1.2
        maxForce = 0.07
        fov = 300.0
        rules(
            separate(12, 1.6, among = "all"),
            align(32, 1.0),
            cohere(44, 0.9),
            flee("red", 36, 1.1),
            wander(0.1),
        )
        shape = BoidShape.ARROW
        size = 3.0
        trailAlpha = 0.4
    }
}
showControls(sim)
```

```glsl [field-property box="boids_two_tribes" name="compute" path="boids_two_tribes_compute_.glsl"]
#version 450
#include "boids/flock"

const int RED = 0;
const int BLUE = 1;

vec4 color(Boid b) {
    if (b.kind == RED) return hsl(4.0, 80.0, 48.0 + float(b.n) * 0.5);
    return hsl(212.0, 80.0, 50.0 + float(b.n) * 0.5);
}
```

## 3. conga

<!-- field-box id="boids_conga" index="2" -->

### Narrative

From `notes`

Conga

From `code`

Conga: no alignment, no cohesion. One hand-written rule: find the closest boid ahead of you and
aim just behind it. Chains form, merge and chase their own tails. The rule's body is the three
custom hooks in the compute tab; custom(46, 1.6) says how far it looks and how much it counts.

```edn [field-box id="boids_conga" role="properties"]
{"id" "boids_conga" "index" 2 "name" "conga" "parents" ["root" "boids_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "boids_renderer"} "name" "conga" "_tombstone" false "frame" #afield/rect [40.0 1120.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "boids_conga_notes_.md" "lines" 6 "characters" 379 "sha256" "4c785ff959f11794e6f3dea6d65c105fdd08966577429099e9042f55e5fc2737"} {"name" "code" "language" "kotlin" "path" "boids_conga_code_.kt" "lines" 28 "characters" 767 "sha256" "867a1c7896df76537981401ff49eeb4d1fac6b4bae52be00e3d059f2481a9b9f"} {"name" "compute" "language" "glsl" "path" "boids_conga_compute_.glsl" "lines" 32 "characters" 830 "sha256" "4e6deafff74279202cf322df631f0fa9a0ccb422a4aa89cd4569783c31c62576"}]}
```

```markdown [field-property box="boids_conga" name="notes" path="boids_conga_notes_.md"]
# Conga

No alignment, no cohesion: one hand-written rule. Each dancer finds the closest boid ahead of it
and aims just behind it, so chains form, merge and chase their own tails. The rule is
`custom(46, 1.6)` in the code and three functions in the compute tab: `customStart` before the
neighbours, `customNeighbour` for each one in reach, and `customForce`, the force to apply.
```

```kotlin [field-property box="boids_conga" name="code" path="boids_conga_code_.kt"]
/*
Conga: no alignment, no cohesion. One hand-written rule: find the closest boid ahead of you and
aim just behind it. Chains form, merge and chase their own tails. The rule's body is the three
custom hooks in the compute tab; custom(46, 1.6) says how far it looks and how much it counts.
*/
var module = computeModuleHere {}

val sim = BoidSketch(module) {
    background = hexColor("#0b0710")
    steps = 2
    trail = 24
    kind("dancer") {
        count = 360
        maxSpeed = 2.6
        minSpeed = 1.2
        maxForce = 0.08
        fov = 200.0
        rules(
            custom(46, 1.6),
            separate(7, 2.0),
            wander(0.35),
        )
        shape = BoidShape.ARROW
        size = 3.0
        trailAlpha = 0.5
    }
}
showControls(sim)
```

```glsl [field-property box="boids_conga" name="compute" path="boids_conga_compute_.glsl"]
#version 450
#define CUSTOM_RULE
#include "boids/flock"

// The custom rule, in three parts: start, every neighbour in reach, then the force.
// Here: the closest boid ahead, and a point 9 pixels behind it along its heading.

float bestDist;
vec2 bestAim;
bool found;

void customStart(int rule, Boid me) {
    bestDist = 1e9;
    found = false;
}

void customNeighbour(int rule, Boid me, Boid other, vec2 d, float dist) {
    vec2 ahead = vec2(cos(me.heading), sin(me.heading));
    if (dot(d, ahead) > 0.0 && dist < bestDist) {
        bestDist = dist;
        bestAim = d - vec2(cos(other.heading), sin(other.heading)) * 9.0;
        found = true;
    }
}

vec2 customForce(int rule, Boid me) {
    return found ? steer(me, bestAim) : vec2(0.0);
}

vec4 color(Boid b) {
    return hsl(280.0 + float(b.n) * 25.0, 75.0, 65.0);
}
```

## 4. bait ball

<!-- field-box id="boids_bait_ball" index="3" -->

### Narrative

From `notes`

Bait ball

From `code`

Bait ball: sardines circle a point in a tight mill. Three sharks cruise through; the ball blows
open where they pass and re-forms behind them. orbit() circles a point given as fractions of
the window, at a radius in pixels.

```edn [field-box id="boids_bait_ball" role="properties"]
{"id" "boids_bait_ball" "index" 3 "name" "bait ball" "parents" ["root" "boids_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "boids_renderer"} "name" "bait ball" "_tombstone" false "frame" #afield/rect [640.0 660.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "boids_bait_ball_notes_.md" "lines" 6 "characters" 310 "sha256" "3d8b9ea07067fddb4c997ead04d91520fa7667142c2ceea197f1965a3b3ca573"} {"name" "code" "language" "kotlin" "path" "boids_bait_ball_code_.kt" "lines" 47 "characters" 1242 "sha256" "f0ea1e6f2ad52df552cda83b6ebb855407a0faeeb4791c5eb6382323b06a2248"} {"name" "compute" "language" "glsl" "path" "boids_bait_ball_compute_.glsl" "lines" 10 "characters" 223 "sha256" "69e860072ee2490be04021ef5d5c4629f97bf3f0ab63fba355515584646c3812"}]}
```

```markdown [field-property box="boids_bait_ball" name="notes" path="boids_bait_ball_notes_.md"]
# Bait ball

Sardines `orbit` the middle of the window in a tight mill. Three sharks lap wider, cut through and
`chase` what is near; the sardines `flee`, so the ball blows open where a shark passes and closes
behind it. `orbit(x, y, radius, weight, dir)` circles a point at a radius in pixels, `dir` 1 or
-1.
```

```kotlin [field-property box="boids_bait_ball" name="code" path="boids_bait_ball_code_.kt"]
/*
Bait ball: sardines circle a point in a tight mill. Three sharks cruise through; the ball blows
open where they pass and re-forms behind them. orbit() circles a point given as fractions of
the window, at a radius in pixels.
*/
var module = computeModuleHere {}

val sim = BoidSketch(module) {
    background = hexColor("#02101a")
    blend = BoidBlend.LIGHTER
    trail = 20
    kind("sardine") {
        count = 700
        maxSpeed = 3.2
        minSpeed = 1.6
        maxForce = 0.09
        fov = 280.0
        rules(
            orbit(0.5, 0.5, 80, 1.0),
            separate(8, 1.6),
            align(22, 0.9),
            cohere(30, 0.5),
            flee("shark", 70, 5),
        )
        shape = BoidShape.LINE
        size = 4.0
        trailAlpha = 0.25
    }
    kind("shark") {
        count = 3
        maxSpeed = 3.0
        minSpeed = 2.0
        maxForce = 0.035
        rules(
            orbit(0.5, 0.5, 130, 0.8), // a wide lap that clips the ball
            chase("sardine", 90, 0.7),
            separate(140, 1.0, among = "same"),
            wander(0.5),
        )
        shape = BoidShape.KITE
        size = 10.0
        trail = 2.0
        trailAlpha = 0.4
        trailWidth = 2.0
    }
}
showControls(sim)
```

```glsl [field-property box="boids_bait_ball" name="compute" path="boids_bait_ball_compute_.glsl"]
#version 450
#include "boids/flock"

const int SARDINE = 0;
const int SHARK = 1;

vec4 color(Boid b) {
    if (b.kind == SHARK) return hex(0xa9c2d2u);
    return hsl(195.0 + b.r * 25.0, 50.0, 45.0 + b.speed * 6.0, 0.85);
}
```

## 5. murmuration

<!-- field-box id="boids_murmuration" index="4" -->

### Narrative

From `notes`

Boids

From `code`

Murmuration: the three original rules. Keep your distance, match your neighbours' heading,
drift toward their centre. Colour is heading, so turning waves show as colour waves. The pointer
is a hawk: move it over the window and the flock parts around it.

The flock is this box's code: kinds of boid, each a count, speed limits, a field of view and a
list of rules. A rule is a Reynolds steering force times a weight, and a boid's acceleration is
their sum. How the boids look is the compute tab's color().

```edn [field-box id="boids_murmuration" role="properties"]
{"id" "boids_murmuration" "index" 4 "name" "murmuration" "parents" ["root" "boids_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "boids_renderer"} "name" "murmuration" "_tombstone" false "frame" #afield/rect [40.0 200.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "boids_murmuration_notes_.md" "lines" 73 "characters" 4426 "sha256" "8bee0c1f6cbc3a906fbbf3b33e089dcdc75aca4bd465079ffcdbf86fdcc0ef23"} {"name" "code" "language" "kotlin" "path" "boids_murmuration_code_.kt" "lines" 35 "characters" 1157 "sha256" "20042b5067d3fea48d70f4a81dcc4547e265b16fc9e9020e7478feac7e39a39a"} {"name" "compute" "language" "glsl" "path" "boids_murmuration_compute_.glsl" "lines" 9 "characters" 285 "sha256" "71e02af25422044e07b413cdb84de2a190feee85d2ebb8e35ac69582d167d697"}]}
```

```markdown [field-property box="boids_murmuration" name="notes" path="boids_murmuration_notes_.md"]
# Boids

A flocking sketchbook. Each flock is this box's code: **kinds** of boid, each a count, speed
limits, a field of view, a look, and a list of **rules**. A rule is a Reynolds steering force,
the velocity it wants (at full speed, toward whatever the rule cares about) minus the velocity it
has, capped at `maxForce`, and then multiplied by the rule's weight. A boid's acceleration is the
sum of its rules. Every boid runs on the GPU, so a thousand cost about what ten do.

Launch a box below to switch flocks; launch this one to come back. Running a box starts its flock
afresh. The controls under each box are the page's toolbar: **fps** and **steps** (steps per
frame), **trail** (how many steps of history each trail shows), **fade** (1 clears the window
every frame, 0.01 keeps nearly everything), **pause**, **inspect** and **reset**. The **metrics**
card below them shows polarization (0: every way, 1: all one way), mean speed, visible
flockmates and the step, twice a second.

## This one: murmuration

The three original rules, `separate`, `align` and `cohere`, plus a little `wander`, and the pointer
as a hawk (`mouse` with a negative weight). Colour is heading, so a flock that turns changes colour
as it goes. Try the weights: they are the whole character of a flock.

## The rules

| | |
| --- | --- |
| neighbours | `separate(radius, weight)`, `align(radius, weight)`, `cohere(radius, weight)` |
| other kinds | `flee("hawk", radius, weight)`, `chase("starling", radius, weight)`, `eat("starling", reach, rest)` |
| on its own | `wander(weight, jitter)`, `cruise(speed, weight)` |
| places | `seek(x, y, weight)` or `seek(weight) { t -> vec(x, y) }`, `orbit(x, y, radius, weight, dir)`, `flow(weight)` |
| obstacles | `avoidEdges(margin, weight)`, `avoidRocks(weight, margin)`, with `rock(x, y, r)` on the flock |
| the pointer | `mouse(weight, radius)`: positive attracts, negative repels |
| your own | `custom(radius, weight)`, written in the compute tab (see **conga**) |

Points are fractions of the window (`0.5, 0.5` is the middle); radii and margins are pixels;
speeds are pixels per step and `t` is the step count.

## Who sees whom

A rule that looks at neighbours sees the boids within its radius, and only some of them:

- **among**: `separate` sees every kind, `align` and `cohere` only their own (`among = "same"`);
  `"all"`, `"other"`, a kind's name or `listOf(...)` say otherwise. `flee`, `chase` and `eat` see
  the kind they name.
- **fov**: a kind's field of view in degrees. `align` and `cohere` only count neighbours inside
  it (`fov = false` to see all round); `separate` and `flee` notice everything.

Each step every boid finds its neighbours once, out to its largest rule radius, and hands each one
to every rule that accepts it; then the forces are added, `drag` and the `[minSpeed, maxSpeed]`
clamp applied, and the boid moves. Every other boid is read as it was at the start of the step.

## Looks

`shape` (`ARROW`, `KITE`, `DOT`, `LINE`, `NONE`), `size`, and the trail's `trail` (a multiple of
the flock's), `trailAlpha` and `trailWidth` are on the kind; `background`, `blend` (`LIGHTER` adds
light) and `edges` (`WRAP` or `BOUNCE`) on the flock. Colour is the compute tab's
`vec4 color(Boid b)`, from `b.heading`, `b.speed`, `b.n` (flockmates in view), `b.r` (0 to 1, the
boid's own), `b.cool` (resting after eating) and `b.kind` (kinds count from 0 in the order the code
declares them), with `hsl(h, s, l, a)` and `hex(0xrrggbb)` as on a web page.

## Inspect

Tick **inspect** and click a boid in the window: its field of view is shaded, each rule's radius
is a dashed ring, and a line goes to every neighbour it can see, green for its own kind, red for
others, grey for the ones behind it.

??? aside "How this differs from the boids page"
    The spec, the rules, their defaults and their constants are the page's; the step is checked
    against a transcription of the page's JavaScript (`four.boid-sketch.smoke`). The spec is
    Kotlin rather than an object literal, so options are named arguments (`among = "same"`), and
    the page's `rule(r, fn)` is `custom` because box code keeps `rule` for its rewrite macros. The
    page's per-boid lambdas become GLSL in the compute tab (`color`, `flowAngle`, the custom
    hooks), and a moving `seek` point is evaluated once a frame. When a hawk eats, the starling
    respawns on its next step rather than the same one.
```

```kotlin [field-property box="boids_murmuration" name="code" path="boids_murmuration_code_.kt"]
/*
Murmuration: the three original rules. Keep your distance, match your neighbours' heading,
drift toward their centre. Colour is heading, so turning waves show as colour waves. The pointer
is a hawk: move it over the window and the flock parts around it.

The flock is this box's code: kinds of boid, each a count, speed limits, a field of view and a
list of rules. A rule is a Reynolds steering force times a weight, and a boid's acceleration is
their sum. How the boids look is the compute tab's color().
*/
var module = computeModuleHere {}

val sim = BoidSketch(module) {
    background = hexColor("#06080b")
    edges = BoidEdges.WRAP
    trail = 14
    fade = 1.0
    kind("starling") {
        count = 800
        maxSpeed = 3.2
        minSpeed = 1.6
        maxForce = 0.07
        fov = 280.0 // degrees; boids can't see directly behind
        rules(
            separate(12, 1.7),
            align(32, 1.1),
            cohere(44, 0.95),
            wander(0.15),
            mouse(-3, 110), // negative weight: flee the pointer
        )
        shape = BoidShape.ARROW
        size = 3.2
        trailAlpha = 0.45
    }
}
showControls(sim)
```

```glsl [field-property box="boids_murmuration" name="compute" path="boids_murmuration_compute_.glsl"]
#version 450
#include "boids/flock"

// A boid's colour, sRGB with straight alpha. b.heading, b.speed, b.n (flockmates in view),
// b.r (0..1, its own), b.cool (rest after eating) and b.kind are there to use.

vec4 color(Boid b) {
    return hsl(deg(b.heading) + 190.0, 65.0, 62.0);
}
```

## 6. hawks

<!-- field-box id="boids_hawks" index="5" -->

### Narrative

From `notes`

Hawks

From `code`

Hawks: two hawks chase the nearest starling and eat it on contact, then rest a while. Starlings
flee anything hawk-shaped within 90 pixels; the flock splits and heals around each pass. Rules
see other kinds by name: flee("hawk", ...), chase("starling", ...).

```edn [field-box id="boids_hawks" role="properties"]
{"id" "boids_hawks" "index" 5 "name" "hawks" "parents" ["root" "boids_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "boids_renderer"} "name" "hawks" "_tombstone" false "frame" #afield/rect [640.0 200.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "boids_hawks_notes_.md" "lines" 7 "characters" 433 "sha256" "48922411bc72db8d54867897d5e9ddd9d5a06fae5ff2fb5e3e1028d88fca83b9"} {"name" "code" "language" "kotlin" "path" "boids_hawks_code_.kt" "lines" 46 "characters" 1234 "sha256" "55ea12b99cd18913dee081f2fbe38de9a93e2eb980fdb57d20efaac9254b6e46"} {"name" "compute" "language" "glsl" "path" "boids_hawks_compute_.glsl" "lines" 10 "characters" 251 "sha256" "07c4386bff7f910a87172e8352ebe26766558717825f811d7b4703a5798ad5e2"}]}
```

```markdown [field-property box="boids_hawks" name="notes" path="boids_hawks_notes_.md"]
# Hawks

Predator and prey. Two hawks `chase` the nearest starling, leading its motion, and `eat` one on
contact; then they rest (`b.cool` counts down, and the hawk dims) before hunting again. The
eaten respawn at an edge, and the metrics card counts them. Starlings `flee` anything hawk-shaped
within 90 pixels, so the flock splits around each pass and heals behind it. Tick **inspect** and
click a starling to see what it notices.
```

```kotlin [field-property box="boids_hawks" name="code" path="boids_hawks_code_.kt"]
/*
Hawks: two hawks chase the nearest starling and eat it on contact, then rest a while. Starlings
flee anything hawk-shaped within 90 pixels; the flock splits and heals around each pass. Rules
see other kinds by name: flee("hawk", ...), chase("starling", ...).
*/
var module = computeModuleHere {}

val sim = BoidSketch(module) {
    background = hexColor("#07070a")
    trail = 12
    kind("starling") {
        count = 700
        maxSpeed = 3.0
        minSpeed = 1.4
        maxForce = 0.08
        fov = 300.0
        rules(
            separate(11, 1.8),
            align(30, 1.0),
            cohere(42, 0.9),
            flee("hawk", 90, 4.5),
            wander(0.1),
        )
        shape = BoidShape.ARROW
        size = 2.8
        trailAlpha = 0.35
    }
    kind("hawk") {
        count = 2
        maxSpeed = 3.7
        minSpeed = 2.2
        maxForce = 0.05
        rules(
            chase("starling", 260, 1.3),
            eat("starling", 7, 90), // reach 7 px, then rest 90 steps
            separate(90, 1.5, among = "same"),
            wander(0.5),
        )
        shape = BoidShape.ARROW
        size = 7.0
        trail = 3.0
        trailAlpha = 0.8
        trailWidth = 1.5
    }
}
showControls(sim)
```

```glsl [field-property box="boids_hawks" name="compute" path="boids_hawks_compute_.glsl"]
#version 450
#include "boids/flock"

const int STARLING = 0;
const int HAWK = 1;

vec4 color(Boid b) {
    if (b.kind == HAWK) return b.cool > 0.0 ? hex(0x7a4038u) : hex(0xff6b5au);
    return hsl(210.0, 20.0 + float(b.n), 50.0 + float(b.n) * 0.4);
}
```

## 7. ink

<!-- field-box id="boids_ink" index="6" -->

### Narrative

From `notes`

Ink

From `code`

Ink: boids ride a slowly changing noise field and barely flock at all. No bodies are drawn and
fade is near zero, so the window keeps every stroke; reset in the controls starts a new page.
The field is flowAngle() in the compute tab, a function of position and time.

```edn [field-box id="boids_ink" role="properties"]
{"id" "boids_ink" "index" 6 "name" "ink" "parents" ["root" "boids_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "boids_renderer"} "name" "ink" "_tombstone" false "frame" #afield/rect [40.0 660.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "boids_ink_notes_.md" "lines" 6 "characters" 387 "sha256" "f5cf4c746e6ceb2ecd952a082bf931382c2aa080d0c737f6f8780bbc30b4d513"} {"name" "code" "language" "kotlin" "path" "boids_ink_code_.kt" "lines" 27 "characters" 728 "sha256" "caf22d40366e11c0bceb691bf40531162ba19c260576593a6b50c5f46dd108a2"} {"name" "compute" "language" "glsl" "path" "boids_ink_compute_.glsl" "lines" 12 "characters" 337 "sha256" "fa80716d27f1b3d979894a25fe77151b6af9e5ebc1198021d226592c55d49d8a"}]}
```

```markdown [field-property box="boids_ink" name="notes" path="boids_ink_notes_.md"]
# Ink

The boids ride a slowly changing flow field and barely flock at all. `flow(1.0)` steers each one
along `flowAngle(uv, t)` in the compute tab, the page's value noise turned into an angle. No bodies
are drawn, and `fade = 0.01` keeps nearly every stroke, so the window becomes a page of ink;
**reset** starts a new one. Change the noise's scale (2.2) for finer or broader currents.
```

```kotlin [field-property box="boids_ink" name="code" path="boids_ink_code_.kt"]
/*
Ink: boids ride a slowly changing noise field and barely flock at all. No bodies are drawn and
fade is near zero, so the window keeps every stroke; reset in the controls starts a new page.
The field is flowAngle() in the compute tab, a function of position and time.
*/
var module = computeModuleHere {}

val sim = BoidSketch(module) {
    background = hexColor("#efe9dc")
    trail = 6
    fade = 0.01
    kind("ink") {
        count = 900
        maxSpeed = 1.8
        minSpeed = 0.8
        maxForce = 0.09
        rules(
            flow(1.0),
            separate(8, 0.9),
            align(18, 0.25),
        )
        shape = BoidShape.NONE
        trailAlpha = 0.8
        trailWidth = 0.8
    }
}
showControls(sim)
```

```glsl [field-property box="boids_ink" name="compute" path="boids_ink_compute_.glsl"]
#version 450
#define FLOW_FIELD
#include "boids/flock"

// The heading, in radians, at uv (0..1 across the window) and step t.
float flowAngle(vec2 uv, float t) {
    return noise(uv.x * 2.2, uv.y * 2.2, t * 0.0015) * TAU * 2.0;
}

vec4 color(Boid b) {
    return b.r < 0.85 ? hsl(220.0, 45.0, 22.0, 0.35) : hsl(8.0, 75.0, 48.0, 0.5);
}
```

## 8. fireflies

<!-- field-box id="boids_fireflies" index="7" -->

### Narrative

From `notes`

Fireflies

From `code`

Fireflies: two swarms each chase a point running a Lissajous curve. Weak alignment and a lot of
wander keep them loose; additive blending turns overlapping trails into light. The point is a
function of t, the step count, evaluated every frame.

```edn [field-box id="boids_fireflies" role="properties"]
{"id" "boids_fireflies" "index" 7 "name" "fireflies" "parents" ["root" "boids_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "boids_renderer"} "name" "fireflies" "_tombstone" false "frame" #afield/rect [1240.0 200.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "boids_fireflies_notes_.md" "lines" 5 "characters" 277 "sha256" "bc8e1469122d3f5ca30695121222e26c5c5c4f0931c6d68537b99815ccf96e2a"} {"name" "code" "language" "kotlin" "path" "boids_fireflies_code_.kt" "lines" 45 "characters" 1267 "sha256" "8efa186059310bae6c0d551a07040600d06d30922f48a23d37f55fdbfbbf8edf"} {"name" "compute" "language" "glsl" "path" "boids_fireflies_compute_.glsl" "lines" 10 "characters" 228 "sha256" "b6044f9e7ee18e8eb50a052b70eff1c26830fb5f4f3e75dca4db4c5c4ba3761c"}]}
```

```markdown [field-property box="boids_fireflies" name="notes" path="boids_fireflies_notes_.md"]
# Fireflies

Two swarms, each seeking a point that runs its own Lissajous curve: `seek(0.7) { t -> ... }` is a
point as a function of the step count. Weak alignment and a lot of wander keep them loose, and
`blend = LIGHTER` adds light where trails cross, so dense places glow.
```

```kotlin [field-property box="boids_fireflies" name="code" path="boids_fireflies_code_.kt"]
/*
Fireflies: two swarms each chase a point running a Lissajous curve. Weak alignment and a lot of
wander keep them loose; additive blending turns overlapping trails into light. The point is a
function of t, the step count, evaluated every frame.
*/
var module = computeModuleHere {}

val sim = BoidSketch(module) {
    background = hexColor("#040306")
    blend = BoidBlend.LIGHTER
    trail = 48
    kind("gold") {
        count = 220
        maxSpeed = 2.6
        minSpeed = 0.8
        maxForce = 0.05
        rules(
            seek(0.7) { t -> vec(0.5 + 0.32 * cos(t * 0.0082), 0.5 + 0.3 * sin(t * 0.0131)) },
            separate(10, 1.2),
            align(24, 0.25),
            wander(0.9, 0.5),
        )
        shape = BoidShape.DOT
        size = 1.4
        trailAlpha = 0.5
        trailWidth = 1.2
    }
    kind("green") {
        count = 220
        maxSpeed = 2.6
        minSpeed = 0.8
        maxForce = 0.05
        rules(
            seek(0.7) { t -> vec(0.5 + 0.3 * sin(t * 0.0107), 0.5 + 0.32 * cos(t * 0.0073)) },
            separate(10, 1.2),
            align(24, 0.25),
            wander(0.9, 0.5),
        )
        shape = BoidShape.DOT
        size = 1.4
        trailAlpha = 0.5
        trailWidth = 1.2
    }
}
showControls(sim)
```

```glsl [field-property box="boids_fireflies" name="compute" path="boids_fireflies_compute_.glsl"]
#version 450
#include "boids/flock"

const int GOLD = 0;
const int GREEN = 1;

vec4 color(Boid b) {
    if (b.kind == GOLD) return hsl(38.0 + b.r * 18.0, 100.0, 58.0, 0.9);
    return hsl(110.0 + b.r * 30.0, 90.0, 55.0, 0.9);
}
```

