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

```glsl
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
