# Helper ideas from the box-code revision pass

Each agent in the revision pass (`BOX_CODE_REVISION_PASS.md`) reports patterns a helper,
convention or default could remove typing for. Marc decided on the first batch on 2026-10-07;
the transcript that showed each pattern is in brackets. New proposals go under **Open**.

## Already in Field: the pass should use them, not propose them

- **FLine from points:** `breakNext()` (the next `lineTo`/`cubicTo` becomes a `moveTo`) and
  `data("m l+", points)` replace the `if (i == 0) moveTo(...) else lineTo(...)` loops.
  [keyframer_worked_example, mesh_geometry_examples]
- **Rectangles and circles:** `FLine.rect(x, y, w, h)`, the free `rect(x, y, w, h)`, and
  `FLine.circle(cx, cy, r)` (four cubics; `segments =` for a polygon), all in
  `stdlib/StandardDrawing.kt`. `circle` was added 2026-10-07. [keyframer_worked_example, frames_lifecycle]
- **Mouse drags:** `mouse.onDrag("left", name) { ev -> ... }` needs no `onDown` beside it, and
  `ev.uv` is the position as a fraction of the window (both 2026-10-07). [four_simulation_chain]
- **Meshes inside the module block:** `renderer += moduleHere { +Mesh("my mesh") { set(3, 3) { ... } } }`
  (2026-10-07; `Parameters.Mesh` and `unaryPlus`, attached after the module is built). The
  triangle starting point uses it. [afield_four_traditional_triangle]
- **Labels from names:** `showMeshGrid(cube, ball)` labels its panels "cube" and "ball". Any function
  annotated `@LabelsArgumentsFromSource` gets the same rewrite (`macros/LabelledArguments.kt`).
- **Reading a structure by name:** `structure["radius"]` (`Structure.get`) returns the binding
  if there is one and the plain value otherwise, which is what `s.bindings["radius"]?.invoke()`
  writes out by hand. [keyframer_worked_example]
- **Push constants:** `moduleHere` generates `var Mesh.<name>` (and the compute equivalents) for
  every push-constant member, so `step.band0 = { ... }` is already the form; numbered members
  do not need a `bind("band", 4)`. [pattern_particles]

## Approved

- `x.f3` (and siblings) for `"%.3f".format(x)` in templates. [statistics_examples, mesh_geometry_examples]
- `frame` available inside `frames { }`. [frames_lifecycle, simdverlet_world]
- `uint` push members accepting an `Int` lambda: `{ L }` for `{ L.toUInt() }`. [spin_glass, pattern_particles]
- `computeBarrier()`, `fullscreenTriangle()`, `bindPush(m1, m2) { ... }`. [spin_glass]
- `prop<BoidSettings>(rule)` shared settings, `wanderingPoint(...)`, `boids.show()`,
  `written.settings { ... }`. [learned_boids]
- A `top` projection for `showPlane`. [simdverlet_world]
- `scope.Key("camera" to pose)` vararg pairs, `key.get<CameraPose>("camera")`,
  `CameraPose.lookAt(from)` defaulting to the origin, `key.versionFile(path, 0)`. [keyframer_worked_example]
- `compare("label", measured, analytic)`, `m.integrate(field)`,
  `randomPoints(48, seed = 31, bounds = ...)`. [mesh_geometry_examples]
- `StatsDependence.all(x, y)` with `caption()`, `permutationNull(x, y, reps, seed) { a, b -> ... }`,
  `RollingPearson(w)`. [statistics_examples]
- `markdownAppend("channel", line)`, with a maximum size that trims the earliest lines. [frames_lifecycle]
- **Bug, fixed 2026-10-07:** `prop<>` dropped the box's own imports, forcing
  `prop<afield.graphics.scene.fabricated.StandardRenderer.Bundle>`; six transcripts now use the short name.
  [four_simulation_chain]

## Declined

- `m.dispatch(N)` reading `local_size_x`: too much magic. [spin_glass, pattern_particles]
- `addPassChain` attaching and presenting itself; `afield.graphics.scene.fabricated` as a default import.
  [four_simulation_chain]
- `m.set { }` sizing itself from what it appends: boxes barely use vertex attributes now; data
  lives in SSBOs. [afield_four_traditional_triangle]
- `sim.addCloth(..., plane = XZ, centred = true)`, `Pins.border`: the explicit `position` and
  `pinned` lambdas are preferred. [simdverlet_world]
- `SphereCollider(..., friction =, margin =)` constructor parameters; `rope.end.mass`. [simdverlet_world]

## Open

(none yet)

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
