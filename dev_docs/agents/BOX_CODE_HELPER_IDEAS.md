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
  `graphics/render/StandardDrawing.kt`. `circle` was added 2026-10-07. [keyframer_worked_example, frames_lifecycle]
- **Mouse drags:** `mouse.onDrag("left", name) { ev -> ... }` needs no `onDown` beside it, and
  `ev.uv` is the position as a fraction of the window (both 2026-10-07). [four_simulation_chain]
- **Meshes inside the module block:** `renderer += moduleHere { +Mesh("my mesh") { set(3, 3) { ... } } }`
  (2026-10-07; `Parameters.Mesh` and `unaryPlus`, attached after the module is built). The
  triangle starting point uses it. [afield_four_traditional_triangle]
- **Labels from names:** `showMeshGrid(cube, ball)` labels its panels "cube" and "ball". Any function
  annotated `@LabelsArgumentsFromSource` gets the same rewrite (`runtime/macros/LabelledArguments.kt`).
- **Reading a structure by name:** `structure["radius"]` (`Structure.get`) returns the binding
  if there is one and the plain value otherwise, which is what `s.bindings["radius"]?.invoke()`
  writes out by hand. [keyframer_worked_example]
- **Push constants:** `moduleHere` generates `var Mesh.<name>` (and the compute equivalents) for
  every push-constant member, so `step.band0 = { ... }` is already the form; numbered members
  do not need a `bind("band", 4)`. [pattern_particles]

## Approved

- **Approved 2026-10-16:** arrange captured interaction-net stages with
  `showInteractionStages(stage("Before", beforeLines, beforeSummary), ...)`, reusing
  the existing overview renderer. Keep rewriting, scheduling and snapshot capture explicit
  in examples; later mutations must not alter earlier snapshots. Verify all five captures.
  [interaction_nets_examples; first-sweep decision 12/12]
- **Approved 2026-10-16:** `bestTimeMs(repetitions = 5, warmup = 2) { ... }` performs
  untimed warmups and returns the shortest measured duration in milliseconds. Preserve
  explicit repetition/warmup counts and ordinary exception propagation. Verify call counts
  and minimum selection without asserting wall-clock performance.
  [imaging_vector_kernels; first-sweep decision 11/12]
- **Approved with adjustment 2026-10-16:** `someFline.grid(bounds, nx, ny)` appends
  disconnected grid lines, like `rect`, and returns the FLine for chaining. `nx` and `ny`
  count cells (therefore `nx + 1` vertical and `ny + 1` horizontal lines). Styling stays on
  the caller's FLine. Replaces the proposed standalone `samplingGridFLine` factory.
  [spatial_geometry_examples; first-sweep decision 10/12]
- **Approved 2026-10-16:** public midpoint slice sampling, illustrated as
  `solid.sampleSlices(n) { z, section -> ... }`. Sample evenly spaced layer midpoints,
  close each native section after its callback (including exceptions), and collect callback
  results. Keep integration math explicit; document that native sections cannot escape
  their callback for later use. Verify heights, areas, contours and native cleanup.
  [chain_ceres_manifold_contours; first-sweep decision 9/12]
- **Approved 2026-10-16; implementation reserved for house-machine agents:** move
  versioned ToolPool note storage, identity validation and pagination into typed library
  methods. Marc will take this back to the house-machine agents; do not implement it in
  this readability pass. Method names remain illustrative.
  [tool-pool/tools.kt; first-sweep decision 8/12]
- **Approved 2026-10-16:** opt into run-owned OSC handlers with
  `osc(port).on(address, runOwned = true) { message -> ... }`. Stop, rerun and retirement
  release that registration by identity, so old cleanup cannot remove a replacement.
  Omitting the option preserves existing persistent behavior; the shared server stays open.
  This syntax is agreed for now; reuse existing run ownership machinery.
  [osc; first-sweep decision 7/12]
- **Approved 2026-10-16:** consolidate Field Audio submit/watch/await/inspect plumbing
  using the shared async operation mechanism from decision 5. Reuse existing rendered-audio
  analysis cancellation/connection cleanup where applicable; keep the requested operation
  explicit. This is an application of decision 5, not an independent ownership mechanism.
  Concrete helper spelling remains subject to the decision-5 API rehearsal.
  [audio_ai_spectral_embeddings, audio_ai_corpus_microscope; first-sweep decision 6/12]
- **Approved 2026-10-16:** a shared run-owned adapter/convention for long-running async
  client/server operations, initially exercised by Loris and the audio AI examples. Publish
  progress/results only while the originating run is current; report failures through its
  box; request supported worker cancellation on stop/rerun. Treat stale-output suppression
  and actual worker cancellation separately. Preserve reusable clients and cached results;
  operation ownership must not imply closing a shared client or deleting cached work.
  Marc expects this pattern to grow beyond audio AI. Rehearse concrete API syntax before
  applying it across examples.
  [loris_partial_explorer, audio AI examples; first-sweep decision 5/12]
- **Approved with adjustment 2026-10-16:** `syphonClient(name)` reuses the open client
  cached under that name; create and cache a client only when none exists or the previous
  client has been closed. This replaces the proposed allocate-on-every-call/run-ownership
  change. Automatic close-on-rerun was not approved by this decision. Verify same-name
  identity, recreation after close and concurrent lookup/creation.
  [syphon_bridge; first-sweep decision 4/12]
- **Approved 2026-10-16:** add `wav(normalizePeak = ...)` on `FaustPatternRender`, encoding
  its existing samples through the existing stereo WAV encoder. Keep normalization explicit
  and preserve the original level when omitted.
  [faust_polyphony; first-sweep decision 3/12]
- **Approved 2026-10-16:** expose the existing `WallSpec.worldToClip()` operation publicly
  for RectSL live rendering. Preserve centered and top-left origin semantics.
  [rectsl_gallery; first-sweep decision 2/12]
- **Approved 2026-10-16:** return a public callable interpolant type from `smoothTo` and
  `inertialTo`, exposing the existing `startAt` operation without casts. Preserve `q()` use,
  explicit restart/Alt-Enter behavior and uniform-binding compatibility.
  [interpolants_examples; first-sweep decision 1/12]
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
- `markdownAppend("channel", line)`, with a maximum size that trims the earliest lines.
  Implementation follow-up: add the bounded append operation beside `Box.markdown` in
  `ui/output/MarkdownOutput.kt`; verify cancellation and rerun ordering before replacing the
  cleanup example's per-launch `notes` list. [frames_lifecycle]
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

- **Table layout in the formatter:** preserve authored row groups in
  `dataFrameOf("name", "age")("Alice", 15, ...)` literals. The second rehearsal keeps each
  name/age pair on one line after reviewing the mechanical formatting result. This is a
  formatting convention to review, alongside the existing assignment/lambda layout rules.
  [dataframe_typed_columns]

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
