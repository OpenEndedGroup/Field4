# Helper ideas from the box-code revision pass

Each agent in the revision pass (`BOX_CODE_REVISION_PASS.md`) reports patterns a helper,
convention or default could remove typing for. These are proposals for a human; none is
implemented. Grouped by theme; the transcript that showed the pattern is in brackets.

## Several transcripts asked for the same thing

- **FLine from points or a function.** `if (i == 0) moveTo(...) else lineTo(...)` loops are
  everywhere: `fline.through(points)`, `FLine.parametric(0..120) { t -> vec(...) }`.
  [keyframer_worked_example, mesh_geometry_examples]
- **FLine shape builders.** Hand-rolled circles (28- and 48-segment loops) and four-`lineTo`
  rectangles: `circle(cx, cy, r)`, `disc(cx, cy, r, color)`, `rect(x0, y0, x1, y1, filled =)`.
  [keyframer_worked_example, frames_lifecycle]
- **Number formatting in templates.** `"%.3f".format(x)` dozens of times per box: `x.f3` or
  `x.fmt(3)`, or a box-level `printf("...", a, b)`. [statistics_examples, mesh_geometry_examples]
- **Elapsed time and throttled frames.** Every animated box writes `started` / `nextDraw` /
  `AnimationClock.nowSeconds() - started` / `var frames = 0; frames++`: an `elapsed` (and
  `frame`) available inside `frames`/`animate`, and `animate(every = 1.0 / 30) { t -> ... }`.
  [frames_lifecycle, simdverlet_world]

- **Compute dispatch from the shader.** `(N + 63) / 64` and hand-set `x/y/z`: `m.dispatch(N)`
  reading `local_size_x`. [spin_glass, pattern_particles]
- **`uint` push members taking an `Int` lambda**, so `{ L.toUInt() }` is `{ L }`.
  [spin_glass, pattern_particles]

## One transcript each

- `prop<>` drops the box's imports when it rewrites the code, forcing
  `prop<afield.four.fabricated.StandardRenderer.Bundle>`; keeping them would allow the short name.
  This is a bug more than a helper. [four_simulation_chain]
- `MouseEvents(renderer).onDrag(...)` fires only if an empty `onDown` is also registered; and
  `ev.uv` instead of dividing by the window size. [four_simulation_chain]
- `addPassChain` attaching and presenting itself, instead of `bundle.attachTo(renderer);
  bundle.present(renderer = renderer)` closing every chain box; `afield.four.fabricated` as a
  default import. [four_simulation_chain]
- `computeBarrier()` instead of `computeTask { _, cmd, _ -> computeMemoryBarrier(cmd) }`;
  `fullscreenTriangle()`; `bindPush(m1, m2) { ... }` for meshes sharing push lambdas. [spin_glass]
- `step.bind("band", 4) { ... }` for numbered push constants; a frame-time push helper.
  [pattern_particles]
- Shared settings: `prop<BoidSettings>(rule)` so trained and written flocks type the weights
  once; `wanderingPoint(...)` for the driver both flock boxes repeat; `boids.show()`;
  `written.settings { ... }`. [learned_boids]
- One nested `renderer += moduleHere { +Mesh("my mesh") { ... } }`; `m.set { }` sizing itself
  from what it appends; `vertices(3) { pos(...); col(...) }` instead of the bare `3 { }`.
  [afield_four_traditional_triangle]

- `sim.addCloth(22, 22, spacing = 0.22f, plane = XZ, centred = true)` instead of a `position`
  lambda that repeats the spacing; `pinned = Pins.border` / `Pins.row(0)`. [simdverlet_world]
- `SphereCollider(..., friction = 0.75f, margin = 0.02f)` constructor parameters instead of
  `.also { it.friction = ... }`; `rope.end.mass = 24f` instead of
  `sim.state.invMass!![rope[15]] = 1f / 24f`. [simdverlet_world]
- A `top` projection for `showPlane` instead of a hand-written top-view mapping. [simdverlet_world]
- `scope.Key("camera" to pose)` with vararg pairs instead of `mutableMapOf(...)`; a typed
  `key.get<CameraPose>("camera")` instead of a cast; `CameraPose.lookAt(from)` defaulting the
  target to the origin. [keyframer_worked_example]
- `structure[name]` (or `Structure.value(name)`) returning a bound value, instead of
  `s.bindings[k]?.invoke()`. [keyframer_worked_example]
- `key.versionFile(path, 0)` instead of hard-coding `look.json.versions/000000.json`.
  [keyframer_worked_example]
- `compare("label", measured, analytic)` printing value, reference and relative error.
  [mesh_geometry_examples]
- `showMeshGrid(cube, ball, ...)` taking labels from the `val` names; `m.integrate(field)`;
  `randomPoints(48, seed = 31, bounds = ...)`. [mesh_geometry_examples]
- `StatsDependence.all(x, y)` returning every coefficient with a `caption()`;
  `permutationNull(x, y, reps, seed) { a, b -> ... }`; a `RollingPearson(w)`.
  [statistics_examples]
- `markdownAppend("channel", line)` for an output channel that only accumulates.
  [frames_lifecycle]

## Found along the way (not helpers)

Each of these is filed as a handoff in `dev_docs/handoffs/` (2026-10-05).

- `simdverlet_world`'s `description:` cites `friction = 0.9` for the sphere; the box sets 0.75
  (0.9 is the floor), and its quoted numbers may be from an earlier 10 x 10 sheet.
- `frames_lifecycle`'s `description:` still carries history; the pass only edits code blocks.
- `four_simulation_chain`'s description asks the reader to change `dissipation` on `fluid-dye`;
  the code's knob is `dyeDissipation`.
- `learned_boids`'s description suggests separation `0.0015 / 3 / 200`; both boxes use `0.02f`.
  It also says re-running the training box catches the learned flock up, but that flock is built
  in `once.learned`, so it probably does not until `once` is reset (unchecked).
- `public/docs/afield_four_graphics.md` embeds the triangle's code in its first fence; it still
  shows the removed `renderer` lookup and none of the new comments.
