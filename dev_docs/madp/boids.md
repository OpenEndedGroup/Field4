# boids → Field·4 (Vulkan)

Source: `~/temp/characters/boids.html` ("boids"). A declarative flocking sketchbook: the whole
program is `const sim = flock(spec)`, a system (background, blend, edges, rocks, suggested
controls) and a set of **kinds**, each a count, speed limits, a field of view, a look, and a list
of **rules**. A rule is a Reynolds steering force (desired velocity at full speed minus the
current velocity, capped at `maxForce`) times a weight; a boid's acceleration is the sum.

The port runs every boid on the GPU in Field's Vulkan renderer and keeps the spec.

## The spec, mapped

The JS spec is an object literal; the Kotlin one is a builder with the same names, in the same
places. The rule constructors are members of the kind builder, not top-level functions, so
`align`, `seek` and `mouse` cannot collide with anything else box code imports.

```kotlin
val sim = BoidSketch(module) {
    background = hexColor("#06080b")
    edges = BoidEdges.WRAP
    trail = 14; fade = 1.0
    kind("starling") {
        count = 800
        maxSpeed = 3.2; minSpeed = 1.6; maxForce = 0.07
        fov = 280.0
        rules(separate(12, 1.7), align(32, 1.1), cohere(44, 0.95), wander(0.15), mouse(-3, 110))
        shape = BoidShape.ARROW; size = 3.2; trailAlpha = 0.45
    }
}
```

| boids.html | Field |
| --- | --- |
| `separate/align/cohere(r, w, {among, fov})` | the same, `among = "all" / "same" / "other" / "hawk" / listOf(...)`, `fov = true/false` |
| `flee(kind, r, w)`, `chase(kind, r, w)`, `eat(kind, reach, rest)` | the same |
| `wander(w, jitter)`, `cruise(speed, w)`, `avoidEdges(margin, w)`, `avoidRocks(w, margin)`, `mouse(w, r)` | the same |
| `seek(p \| t => p, w)`, `orbit(p, r, w, dir)` | the same; a moving point is a Kotlin lambda of `t` (steps), evaluated once a frame |
| `flow((x, y, t) => radians, w)` | `flow(w)`; the angle is the GLSL hook `float flowAngle(vec2 uv, float t)` |
| `rule(r, (b, near, world) => vec, opts)` | `custom(r, weight, among, fov)` (box code reserves `rule` for its rewrite macros); the body is three GLSL hooks, `customStart` / `customNeighbour` / `customForce` |
| `color: b => hsl(...)` | the GLSL hook `vec4 color(Boid b)` (`b.heading`, `b.speed`, `b.n`, `b.r`, `b.cool`, `b.kind`) |
| `shape: arrow / kite / dot / line / none`, `size`, `trail`, `trailAlpha`, `trailWidth`, `spawn` | the same |
| `rocks: [rock(x, y, r)]`, `edges`, `blend: 'lighter'`, `background` | the same |
| `suggested: {fps, steps, trail, fade, scale}` | the same names on the flock, steered live by the controls |
| Inspect (I) and click a boid | `inspect` in the controls, then click a boid in the window |
| metrics card | the same numbers in the box's `metrics` output |

Units stay the page's: pixels (window points), y down, speeds in pixels per **step**, `t` in steps.
A flock steps `steps` times per frame at `fps` frames per second (default 60, as the page), so the
per-step constants carry over unchanged.

Each preset box is a Kotlin spec and a short compute tab: `vec4 color(Boid b)` always, plus
`flowAngle` or the custom-rule hooks when the spec uses them (declared with `#define FLOW_FIELD` /
`#define CUSTOM_RULE` before the include, since GLSL cannot tell whether a function exists).

## The rule table (the shared abstraction)

A flock is two small GPU tables the Kotlin writes from the spec:

- **kinds**: speed limits, drag, `cos(fov / 2)`, the query radius, the **reach** to every other
  kind (the largest radius of any rule of this kind that accepts that kind, squared), where its
  rules start and how many, and the look.
- **rules**: one record each, `(type, radius, weight, among mask, fov flag, four parameters)`.
  `among` is resolved to a bit mask over kinds on the CPU, exactly as `resolveAmong` resolves it.

The step shader is generic: it walks the boid's neighbours **once**, out to its kind's query
radius, keeping a neighbour only inside the reach to that neighbour's kind (as `gather()` does),
works out whether it is in the field of view, and feeds it to every rule of the kind whose radius,
mask and fov flag accept it. Each rule type keeps an accumulator; after the walk each becomes a
steering force and the weighted forces are summed. Adding a rule type is one accumulator and one
`case`, not a new shader.

The grid is `GpuNeighbourGrid` with cells the size of the most populous kind's query radius (as the
page sizes its grid); kinds that look further scan more cells. With `edges: 'wrap'` the walk wraps
across the seam and neighbour offsets are taken on the torus, as `gather()` does.

### `eat`: one GPU thread changing another boid

A hawk that eats a starling has to change the starling. A thread may not write another boid, so
the hawk marks it with an atomic compare-and-swap on an `eaten` flag (one hawk wins any starling),
and the starling respawns at an edge on the next step. The page respawns it in the same step; the
difference is one step.

## Trails: geometry, not accumulation

The page keeps the last 121 positions of every boid and draws them each frame as a polyline of
`trail × kind.trail` samples in four bands, the oldest faintest, then clears (`fade = 1`) or fades
(Ink's `0.01`) the canvas. So the port keeps a 121-sample ring per boid on the GPU, written every
step, and draws each trail as butt-ended quads between consecutive samples, alpha by band, skipping
the segment across a wrap. Round caps would double the alpha at every joint, which a canvas stroke
never does. Bodies are signed-distance polygons (arrow, kite), discs and capsules, so they are
antialiased the way canvas fills are.

Everything is drawn into Fern's `PointAccumulation` (premultiplied sRGB, ALPHA or ADDITIVE blend
for `'lighter'`), faded by `fade` once per frame and shown over the background, as in Particles.

## The inspector

The page refreshes the selected boid's neighbour list and draws its field-of-view wedge, a ring per
rule radius, a line to every neighbour (green: same kind and seen, red: other kind and seen, grey:
not seen) and its velocity. On the GPU the selected boid's own thread already walks exactly that
list, so **it draws the overlay itself**, into the same stroke list Particles uses, with no
readback. Clicking picks the nearest boid on the GPU too: every boid bids its packed distance to
the click with an atomic minimum, and the next frame inspects the winner. Ring labels ("separate
12") are dropped; the rules are in the box's code beside the window.

The metrics (polarization, mean speed, visible flockmates, caught, step) are integer atomic sums the
step writes into a small buffer that the box reads twice a second and shows in its `metrics`
output.

## Shared vs local

Shared, with KDoc and tests: `afield.simulation.particles.BoidSketch` (spec builder, tables,
buffers, step, draw, inspector, metrics), the `boids/flock` GLSL harness, the drawing shaders, and
a `WindowPointer` the flock and `ParticleSketch` both use for the pointer and the window's size in
points. Tests: the shaders compile and declare what the Kotlin binds, every preset compiles, the
tables encode `among`, reach and fov as the page resolves them, and a GPU smoke grades a step
against a transcription of the page's `stepWorld` with every rule type.

Local (`madp_boids`): a renderer box, Murmuration as the lead box, and Hawks, Fireflies, Ink, Bait
Ball, Two Tribes and Conga.

## Dropped, and why

- The Monaco editor, preset menu, AI key, fullscreen, divider: Field's own.
- `scale` (a count multiplier): change `count`.
- `hsl()`'s 4-degree quantisation, which only batched canvas draw calls.
- Ring labels in the inspector, above.
- Reef and Herding are not presets (the brief asks for 4–6); both use only rules the others
  exercise, and Herding needs the pointer, which a capture cannot show.
