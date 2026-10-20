# madp Particles → Field·4 (Vulkan)

Source: `~/temp/aaaframe/madp/particles.html`. A 2D force-based particle sketchbook: the world
starts empty, the student paints particles by dragging, and five JS hooks decide what they do.
The port runs every particle on the GPU in Field's Vulkan renderer, and keeps the hooks.

## The hooks, mapped

| madp (JS, per particle on the CPU) | Field (GLSL compute, per particle on the GPU) |
| --- | --- |
| `init()` sets `world.*` | Kotlin: `ParticleSketch(module) { gravity = …; damping = …; background = … }` |
| `spawn(x, y)` returns a patch | `void spawn(vec2 at)` fills the global `p`, already holding the defaults |
| `step()` with `eachPair(r, fn)` | `void eachPair(Particle other, Pair pair)`, called for every neighbour within `pairRadius` (Kotlin) |
| `update(p)` (required) | `void update()` on the global `p` |
| `draw()` overlays | `void draw()`, run by one invocation once a frame |
| `world.time`, `t`, `dt`, `width`, `height`, `mouse` | globals of the same names |
| `force`, `impulse`, `drag`, `limitSpeed`, `bounce`, `wrap` | the same names, acting on `p` |
| `neighbors(r)`, `nearest(r)` | `neighbors(r)` (a count), `nearest(r)` (an index, `-1` for none), `particle(i)` |
| `flock({...})`, `clump({...})` | `flock(radius, separation, alignment, cohesion, maxForce)`, `clump(radius, strength, repelRadius, repelStrength)`; both return the neighbour count |
| `attract`, `springTo`, `oscillate` | `attract(target, strength)`, `springTo(target, rest, stiffness, damping)`, `oscillate(axis, amplitude, frequency, phase)` |
| `spring(a, b, {...})`, `collide(a, b, {...})`, `link(a, b, {...})` | inside `eachPair`: `spring(other, pair, rest, stiffness, damping)`, `collide(other, pair, restitution)`, `link(other, color, width)` |
| `overlay.circle/ring/line/cross` | `circle`, `ring`, `line`, `cross` (from `draw()` or `update()`) |
| `'hsla(…)'` strings | `hsla(h, s, l, a)` / `rgba(r, g, b, a)`, CSS units |
| `noise2D`, `triWave`, `tri01`, `clamp`, `lerp`, `rand`, `randInt` | the same names |
| dragging on the canvas | dragging in the renderer window (`MouseEvents`); `sketch.paint(x0, y0, x1, y1)` from Kotlin |

`p` is a global, the way the JS helpers act on an implicit subject: `force(vec2(24, 0))` pushes
the particle being updated. GLSL has no options objects, so the verbs take positional arguments,
with the JS option names in their KDoc and in the presets' comments.

Coordinates stay the canvas's: pixels, origin top left, y down, `width × height` the window in
points. So every constant in the madp presets (radius 82, speed 190 px/s, `width * 0.5`) carries
over unchanged.

## The frame

The madp frame order is kept and is the teaching point: clear links → gravity → hash → step →
update → semi-implicit Euler → draw. On the GPU, one frame is:

1. **spawn** — one invocation per queued paint position runs `spawn(at)` into the next free slot;
   invocation 0 also clears the stroke list and runs `draw()`.
2. **hash** — `GpuNeighbourGrid` (hash → `GpuRadixSort` → ranges), 72-pixel cells as in madp.
3. **step** — one invocation per particle: `acc = gravity`; `eachPair` over neighbours; `update()`;
   then `vel += acc·dt; vel *= damping; limit maxSpeed; pos += vel·dt`.
4. **draw** — into Fern's `PointAccumulation`: fade, links, dots, overlays; then shown.

Particles live in two buffers that swap each frame. `step` reads every *other* particle from the
previous frame's buffer and writes its own into the next one, which is exactly madp's semantics:
its `neighbors()` and `flock()` read a snapshot taken before `step()`.

## Pairs on the GPU (the hard part)

madp's `eachPair` visits each unordered pair once and writes **both** particles: `spring` pushes
`a` and `b` apart, `collide` moves both and exchanges momentum. A GPU thread cannot safely write
another particle's state (no float atomics in core Vulkan, and they would make the result order
dependent anyway).

The representation chosen: **each pair is visited twice, once from each side, and each side
applies only its own half.** `spring`, `collide` and every other pair force are antisymmetric
(Newton's third law), so "my half of the pair force" is computable from both particles' snapshot
state alone, and summing the halves gives the same total as madp. The student's `eachPair` body is
the same code with `a` read as "me" (`p`) and `b` as `other`. `link` records a line only from the
lower-indexed side, so each pair is drawn once.

Two alternatives were rejected:

- **Springs built once in `init`** (a fixed spring list, as a cloth solver would): madp's presets
  rebuild their pairs every frame from distance (`if (info.dist < 60) spring(…)`) and the spring mesh
  *re-knits* as it moves; a fixed topology would change what the presets do.
- **Links drawn from a separate neighbour query in the line pass**: it would split one piece of
  student code (`if (same group && close) link(…)`) across two shaders.

What differs, and is said in the starting point: madp's pair loop is sequential (Gauss–Seidel:
a collision's position correction is seen by the next pair), the GPU's is simultaneous (Jacobi:
every pair sees the snapshot). Overlapping balls separate over a few frames instead of one; it reads
the same.

Links and overlays go into one **append buffer** (an atomic counter and a record per stroke), drawn
as antialiased quads. A spring mesh draws about 18 links per particle (measured on madp: 13,762
links for 744 particles), so a fixed per-particle slot count would visibly thin the web.

## Drawing: canvas blending, kept

madp draws on a 2D canvas: `fillRect(fadeColor, 1 − trail)` each frame, then links, dots and
overlays with `source-over`, all in sRGB. Fading a premultiplied picture toward transparent and
showing it over the background is algebraically the same as that fill when `fadeColor ==
background` (every preset sets them equal). So the port uses `PointAccumulation` with
`fade = 1 − trail`, alpha ("over") blending, and keeps **colours in sRGB, blended the way a canvas
blends**, with its own display pass that converts to linear only at the end. Blending the same
colours in linear light makes 12% links look three times brighter than madp's.

## Shared vs local

Shared (with KDoc and tests):

- `afield.simulation.particles.ParticleSketch` — buffers, spawn queue, grid, the frame, the draw,
  painting from the renderer window, `reset`, live world settings, buffers reused across re-runs.
- `particles/sketch` GLSL include — the particle struct, the globals, every verb, the harness
  `main()`. Packaged like Fern's `points/` and `flow/`.
- `GpuNeighbourGrid`: a live particle count (`count: () -> Int`), since painting grows it each
  frame; `GpuRadixSort.reserve` so a growing count does not reallocate scratch every frame.
- Tests: the harness and every preset compile and declare what the Kotlin binds (no GPU); a GPU
  smoke grades the verbs against a Kotlin transcription of madp's JS over the same configuration
  with `dt = 0`.

Local (the starting point `particles_sketchbook`): a renderer box, the lead box (boids ribbon) and
preset boxes — spring mesh, predator wake, bounce house, sine garden, pendulum bloom, vortex
brush. Each preset is one Kotlin box (world settings, seed strokes) with its GLSL in the compute tab.

## Dropped, and why

- Monaco, the AI chat, local saves, the preset menu, "Copy Guide": Field's editor, AI4, documents
  and boxes replace them (plan, "Not ported").
- Arbitrary per-particle JS fields: GLSL needs a struct. `Particle` has the fields the presets use
  (`group`, `seed`, `home` = where it was painted) plus a free `vec4 data`.
- `ttl`, `pinned`, per-particle `damping` / `maxSpeed`: no preset uses them. `ttl` would need
  compaction; the buffer is a ring that replaces the oldest particle when full instead.
- `filter` functions on `neighbors`/`flock`: no preset uses them; write the loop instead (the walk
  is shown in the include's comments).
- Reading other particles' *live* state inside `eachPair` (sequential semantics), as above.
