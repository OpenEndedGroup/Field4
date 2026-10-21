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
