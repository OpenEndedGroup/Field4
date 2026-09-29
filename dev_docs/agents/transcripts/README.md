# Agent Transcript Corpus

This directory is a small, curated corpus of `OperationTranscript` YAML macros for agents. These are examples to copy, run, and adapt when automating Field documents through the backdoor.
For the consolidated testing strategy, start with `dev_docs/testing/TESTING_STRATEGY.md`.

Server macros are the one semantic-automation layer: they change Field state — boxes, properties, frames, graph connections, execution, launch, stop, and undo. Capture (screenshots, output-event collection) is a transport concern, so it lives in the backdoor's own commands (`wait-output`, `output`, `screenshot-box`, `native-screenshot`), run *after* the macro against the boxes it created. The backdoor never grows a second durable op language of its own.

## Running One

From the repository root:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 \
  --command 'macro write agent_hello_display --file dev_docs/agents/transcripts/hello_display.yaml' \
  --command 'macro run agent_hello_display'
```

For multi-box construction macros, run without `--selection`. The macro runner maps the first source box to a selection target, which is useful for single-box edit macros but not for blank-canvas construction. These corpus files use stable box ids intentionally.

Use `macro undo <name>` to reverse supported operations:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 \
  --command 'macro undo agent_hello_display'
```

## Capturing output and screenshots

Capture is not a macro op. Run the macro, then chain the backdoor's own capture commands against the boxes it created (referenced by their stable ids). Use `--with-playwright` so the automation browser is attached before capture, making display output visible to the screenshot step:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --with-playwright --port 9477 \
  --command 'macro write agent_hello_display_capture --file dev_docs/agents/transcripts/hello_display_capture.yaml' \
  --command 'macro run agent_hello_display_capture' \
  --command 'wait-output agent_hello_display_capture --kind display --contains "hello from OperationTranscript"' \
  --command 'output agent_hello_display_capture --kind all' \
  --command 'screenshot-box agent_hello_display_capture build/agent-transcripts/hello-display.png --padding 18'
```

`wait-output` blocks until the box emits matching output; `output` returns the collected events in the JSON result; `screenshot-box` writes a cropped PNG (`--no-pan` / `--no-open-outputs` tune the framing). For native graphics windows use `native-screenshot <path>` instead. Run `wait-output` before `screenshot-box` when the shot should include rendered display output — a `macro run` reply can arrive before the browser has painted the output panel.

## Supported Macro Ops

These are `OperationTranscript` ops, applied by `macro run` server-side. `dev_docs/agents/OPERATION_TRANSCRIPT.md` is the canonical vocabulary; the most useful ops for this corpus:

- `create`: create or revive a box by stable id, set `name`, `frame`, and optional `properties`.
- `delete` / `tombstone`: tombstone a box; undo revives it.
- `edit` / `set` / `set-string`: set text-like properties with `from` and `to`.
- `move` / `frame` / `set-rect`: update a box frame.
- `connect` / `disconnect`: change parent-child graph topology.
- `execute`: run a code fragment once through the awaited execution path (output is captured into the run result). If the op carries no `code`, it falls back to the box's stored `code` (or the `property` you name), so a `create` that sets code followed by a bare `execute` runs that stored code.
- `launch`: **the primary execution verb** — launch code as a running job (renderer/compute/draw boxes are all launched); undo stops it. Kotlin-only launch groups use the awaited batch path. Mixed or non-Kotlin groups are dispatched individually and do not have that same first-frame guarantee. Like `execute`, a bare `launch` with no inline `code` runs the box's stored `code` (or named `property`). `launch` fails **loud**: if the target box is absent or its resolved property has no code, the run returns `success=false` with the reason in `errors`/`message`.
- `stop`: stop a running job.
- `wait`: sleep for `millis`, `ms`, or `seconds`; `frames` waits for real `Runloop.frame` advancement.
- `await` / `sync` / `settle` / `wait-pending`: the implemented Field-state barrier. It advances `Runloop` frames until no box reports a currently executing fragment, accounts for tracked launch startup, then settles extra frames. Write `wait: pending`. Runtime-specific work that Field does not track still needs an explicit event wait; the transcript layer does not invent acknowledgements.
- `save` / `save-document` / `save-as`: persist the current (transient) document's live boxes to a `metadata.edn` document at `path` (aliases: `to`, `fn`, `document`). Tombstoned boxes and the synthetic root are excluded; box preambles are regenerated in the snapshot. Awaits the background write (30s cap) so the document folder exists on disk when the op returns. This is how an agent turns an assembled scene into a loadable document.

Capture (screenshots, output collection) is **not** a macro op — it is done with the backdoor's own commands after the macro runs. See "Capturing output and screenshots" above.

## Graphics Topology Rule

`afield.four` examples get their `Renderer` from graph topology, not from global state. The renderer box must be launched first, and each graphics example box must be connected as a child of that renderer box before it runs.

Use this sequence for new graphics automation:

1. Run `graphics_renderer_launch.yaml`.
2. Wait for renderer liveness with `wait-native-renderer agent_graphics_context --frames 3`.
3. Create the example box with a stable id.
4. Connect `agent_graphics_context` as parent and the example box as child.
5. In example code, resolve `val renderer: Renderer = fromContext()` at top level before calling `start(...)` or launching suspend work.
6. Pass that renderer explicitly into async helpers that render or capture.

If a graphics example reports `must pass in a value for context parameter afield.four.Renderer`, treat it as a broken parent-child setup or a renderer lookup that happened after coroutine context was lost. Fix the transcript topology or capture the renderer before `start(...)`; do not add sleeps or window-system screenshot fallbacks.

For renderer boxes, keep `start(renderer::run)` as the final expression of the launched code. Field uses the returned `Frames` object as the running job; adding logging or setup after `start(...)` can leave a constructed renderer whose frame counter never advances.

### Where the worked graphics examples are

This corpus holds the two *halves* of the topology rule, not a library of graphics techniques. For the techniques — cameras, compute shaders and compute particles, texturing, glTF, render-to-texture, ping-pong buffers, blur kernels, shader `#include` and Lygia, printf and texture-probe debugging, a GPU radix sort, Slang, DLA — read the `canonical_graphics` corpus, a snapshot of a real Field document with ~33 lessons that ran against a live renderer:

- `public/docs/assets/canonical_graphics/LESSONS.md` — the index: lesson name → Kotlin and shader files, the parent chain each lesson needs, and a reverse index from Field API symbol to the lessons that use it. Generated from the corpus and guarded, so it cannot drift from the boxes.
- `public/docs/afield_four_graphics.md` — the same curriculum as prose, with captured native-window images.

Those boxes are read-only reference: they are a saved document, not transcripts. Adapting one into a `create` + `connect` + `launch` transcript is the normal way to build a new graphics scene, and the parent chain recorded in the index is what the `connect` ops need to reproduce — several lessons are producer/consumer pairs (`5. RTT` renders into a texture that `5. draw rtt` samples), so connecting only to the renderer is not enough.

## Files

- `profiling_timeline.yaml`: three editable CPU/allocation workloads plus a capture box. Replay to
  get a checked, latched three-second capture in **Show Profiling Timeline**. Workers stop at the
  end; brush, zoom, inspect a resume, or explore JVM classes and sampled box lines in **Hot code**.
  Flip the vector box's `reuse` flag and
  relaunch the workloads to compare allocation with Field's in-place linalg operations.

- `hello_display.yaml`: smallest useful blank-canvas transcript; creates one box, displays HTML, and can be undone.
- `hello_display_capture.yaml`: same as `hello_display.yaml` but paired with the backdoor capture-command chain (`wait-output` / `output` / `screenshot-box`) documented under "Capturing output and screenshots" — the worked example of capturing a macro's output and a cropped screenshot.
- `polytope_conway_demo.yaml`: one deliberately compact **polytope review box**. It marks two opposite cube faces with different height attributes, selects and applies `kis` only to those faces, converts the authoritative polygon faces to separate `FLine`s, and ends in the on-canvas `threeLines(...)` viewer. Orbit and hover the result; clicking a face reports its output id, transform role, source elements, and resolved height. Its inspection output also exercises the registered data-class visualizers for the transform and its polygon-to-triangle provenance.
- `polytope_conway_gallery.yaml`: the same algebra as **four small boxes, one question each** — the demo set `dev_docs/POLYTOPE_ALGEBRA.md` §6 asks for, and the loadable companion to `public/docs/polytope_examples.md`. (1) an editable Conway-expression contact sheet whose captions are read off the topology, so retyping the expression list rebuilds everything; (2) a topology-only dual whose missing coordinates are shown by two *real* caught exceptions before a realization policy is applied; (3) a selective **and** non-uniform `kis` whose deployed scalar is painted onto pickable output faces; (4) a realization before/after leaving the solver's own diagnostics as the box's final value. Each box names the one line to change. Pure CPU.
- `inertial_movement.yaml`: three pure-CPU `loop { }` boxes on interrupted motion, shortest first —
  no Window, no Renderer, all **launched** rather than executed. `inertial_hello` is five
  declarations and a `showSignals` call: the least you can write and see `inertialTo` work.
  `inertial_vs_restarting` adds a `startAt` per frame, so the restarted arm and the re-solving one
  are one line apart. `movement_playground` chases an XY pad through `MovementState` on live
  sliders, against a ghost with the smoothing off. Worth copying for the shape as much as the
  subject: no `AtomicReference` (the Framer runs one coroutine at a time, so control callbacks and
  the loop share plain `var`s), `showSignals` for anything that is a signal over time and `FLine`s
  only for the path, which is geometry. The library is `afield/stdlib/movement.kt` and `inertialTo`
  in `afield/stdlib/interpolants.kt`.
- `two_box_context.yaml`: creates two boxes, connects them, and runs child code that depends on graph context.
- `graphics_renderer_launch.yaml`: creates the reusable `agent_graphics_context` renderer box, launches it with `start(renderer::run)`, prints `agent graphics renderer ready`, and waits three Runloop frames. Run this before child `afield.four` graphics transcripts.
- `field_ui_browser_texture.yaml`: self-contained recursive graphics proof and puppet-browser starting point. A hidden Electron window opens the same Field document, transfers accelerated offscreen IOSurfaces into the JVM, and a Vulkan mesh samples the stable resident texture. The native window's mouse and keyboard control Chromium, `browserControl` publishes the full navigation/input/JavaScript/CDP interface for experimentation, and the launch performs a page-to-Field-to-JavaScript promise round trip. Start Field normally, then run the renderer box before the connected panel box.
- `youtube_mouse_keyboard_browser.yaml`: the direct desktop-input puppet-browser starting point. It opens a full YouTube watch page into a matching 1280×720 native window and routes that window's mouse motion/buttons/wheel plus physical keyboard, modifiers, and text input straight into Chromium. There is no OpenXR or controller stand-in: click the EU consent controls, use the search field, and operate the player as an ordinary browser. Electron keeps page audio live.
- `youtube_kaleidoscope.yaml`: the deliberately excessive native-compositing proof. It accepts the EU consent page and starts a real YouTube video through the puppet JavaScript bridge, then samples the fully composited Chromium tab across 224 independently animated Vulkan triangles. The vertex stage opens moving cracks; the fragment stage folds, mirrors and chromatically separates a different region of the browser texture in every shard. Its public walkthrough and live docs capture are in `public/docs/puppet_browser.md`.
- `youtube_vr_browser.yaml`: the product-facing puppet-browser starting point. It opens a full YouTube watch page at 1280x720, maps it without distortion onto a 1.6m × 0.9m quad 1.7m ahead, and renders the scene once per eye. The OpenXR controller ray handles the EU consent screen and player controls, its thumbstick/trackpad scrolls, physical keyboard events remain available, and Electron keeps Chromium audio live. Without a headset, the same scene is mirrored side by side and the mouse stands in for the controller.
- `splat_sequence_streaming.yaml`: plays a directory of PlayCanvas `.compressed.ply` frames as 4D video — fifteen sources a frame in a ring around the viewer, decoded straight into one GPU buffer, with a scrubbable play head. Needs a graphics parent, so run `graphics_renderer_launch.yaml` first; needs your own frames, and says how to make them if the directory is missing.
- `mega_texture_wall.yaml`: a directory of images as one instanced wall, behind a *real* virtual texture — the fragment shader measures its own screen footprint from the UV derivatives, asks for that resolution through a feedback buffer, and the CPU decodes, caches and evicts to serve it. The CPU is told nothing about the scene; fly in and the 15×7 base pages are replaced by full 1920×798 source frames ahead of you, fly out and they are evicted. Each arrival fades in over the page it replaces, or over the image's mean colour if it had none (`config.crossfadeFrames`). Worth reading for the *shape* as much as the subject: the wall's geometry is arithmetic on `gl_InstanceIndex` with no vertex buffer at all, the shaders are box properties with `{{ mega.all }}` interpolated into them, and the Kotlin is five lines plus a camera. Needs a graphics parent, so run `graphics_renderer_launch.yaml` first, and point the `folder(...)` line at your own images. Flip `showResidentLevel` in the fragment shader and reload (alt-r) to watch residency directly. The library is `afield/four/texture/mega/`.
- `afield_four_traditional_triangle.yaml`: creates a graphics child box for the stable backdoor graphics parent `agent_graphics_context` and draws a native Vulkan triangle.
- `keyframe_autosweep.yaml`: a self-contained overnight sweep of a keyframe space — a renderer, a rig whose live state is ten `KeyframeScope` lanes, the sweep itself (twelve renders at ~8,300/hour, each a random blend of two or three saved keys, sometimes extrapolated past the hull), and a morning box that reads the archive back with no renderer at all. Every render leaves a directory holding a still, a short mp4, the blended key as ordinary key JSON, and a manifest of which keys went in at what weight. Builds its own `Window` + `Renderer`, so run it on its own rather than under `graphics_renderer_launch.yaml`. Companion page `public/docs/keyframe_autosweep.md`; the same layer with assertions on the pixels is `tools/run_field_tests.py --id keyframe.sweep.smoke`.
- `iosurface_ring_roundtrip_smoke.yaml`: macOS, either Vulkan driver — both IOSurface paths end to end. `FieldRTTStreamer` renders a known colour into the real `field-encode` ring, the box checks each ring IOSurface on the CPU, then hands slot 0 to `MacExternalBrowserSurface` as a Mach port and checks the resident image. Builds its own `Window` + `Renderer`; needs `native/field-encode` built (`swift build -c release`). Prints `iosurface-ring PASS`. Run it once with `AFIELD_VULKAN_DRIVER=moltenvk` as well as on the default KosmicKrisp, since the two take different routes (`dev_docs/KOSMICKRISP.md`).
- `linalg_thumbnails_demo.yaml`: four blank-canvas boxes that evaluate a `vec2`, `vec3`, `mat3`, and `mat4`; on execute each box's output shows the terse text head plus the inline SVG thumbnail `ValueVisualizer` draws (auto-scaled arrow for vectors, isometric for `vec3`, diverging heatmap grid for matrices).
- `wfc_worked_example.yaml`: eight independent pure-CPU **Wave Function Collapse** boxes, one per section of `public/docs/wfc_examples.md` — hand-authored adjacency (`wfcRules { }`) with both phases pinned via `solveWithRetries`' `prepare` block, the simple-tiled model learned from a generated terrain image (printing the band histogram that shows the learned weights dropping the water bands, and the sand-boost that restores them), an eight-tile edge-socket circuit set, the overlapping model with and without the `forceTile` ground constraint, von Neumann vs Moore on diagonal stripes (473 breaks against 0), the collapse sampled through `step()` into `waveToImg` / `possibilityCountImg` frames, and both `FloatArray` paths (`sliceIntoUniqueTiles2D` / `sliceIntoOverlappingPatterns2D`) ending in iso-contour `FLine`s, and the solver's own inline `ToHTML` view of a 45%-collapsed solver beside a contradicted one (three one-pixel-per-cell panels — possibilities left, entropy in nats, tiles decided — each captioned with its colour scale). No Window, no Renderer; every box prints the numbers the docs page quotes.
- `alife_worked_example.yaml`: **spatial artificial life** as twenty boxes, two per section of `public/docs/alife_examples.md`. Ten measuring boxes, pure CPU, each printing the numbers the page quotes — Conway's Life against the glider's exact `(1, 1)` displacement and the R-pentomino's textbook peak, rule 90 against Pascal's triangle mod 2 from Kummer's theorem, a cyclic CA against its closed-form advance fraction, Gray–Scott's regimes separated by isoperimetric ratio with two controls that pattern not at all, Lenia's discrete limit bit-identical to the integer Life stepper, boids and Physarum against twins with one interaction switched off, DLA's mass dimension against an Eden cluster and a uniform sprinkle, the Koch snowflake's exact closure/length/area identities, and the three bridges out. Then a band of ten `*_moving` boxes: the same systems as time evolutions through `Box.animateEvolution`, each a schedule (steps, seconds, ramp) plus a way to draw the state, with the step counter burned into every frame. Those ten must be **launched**, not executed — a `Frames` that is only built never runs, so executing one is a compile check and nothing more. No `Window`, no `Renderer`; EXECUTE-bucket in `examples.demo-transcript.exec`.
- `dynamic_graph_systems_examples.yaml`: **time-varying point-and-edge systems** as six boxes, one per section of `public/docs/dynamic_graph_systems_examples.md`. Four measuring boxes, pure CPU, each printing the numbers the page quotes and `DynamicGraphSystemsTest` asserts — all six `DynamicGraphSystem`s over one 220-point disc separated by the exact fraction of their edges that close a triangle (0.0 % for flowline advection, 100 % for the local tangent fan and for alpha-event collapse) together with each system's peak weight against the shared ceiling; both decay laws against their closed forms at 1, 4 and 120 steps plus `ttl` expiring an edge that is still at its ceiling; hysteresis against an `rExit == rEnter` control arm that creates 1.80× as many edges and loses 7.9× as many; and a `minWeight` cut of 1.2 recovering exactly `[240, 22, 22, 22]` — the background mesh and the three co-moving clusters — with zero edges crossing a group boundary. Then two `animateEvolution` boxes, `dgs_breathing` and `dgs_morphogenesis`, which must be **launched**, not executed: a `Frames` that is only built never runs. The breathing one is the family's clearest demonstration — the cloud never moves, so every edge that appears is the anisotropic metric turning, and the control panel beside it has its clock pinned at zero and holds exactly 590 edges for all 420 steps. No `Window`, no `Renderer`; EXECUTE-bucket in `examples.demo-transcript.exec`.

- `autodiff_worked_example.yaml`: **automatic differentiation** as five pure-CPU boxes, one per layer of the AD stack. `autodiff_basics` differentiates one expression three ways that must agree (reverse-mode tape, second-order forward-mode `Dual2`, and a central-difference oracle) and plots f, f' and f'' from a single sweep, plus the exact 2x2 Rosenbrock Hessian. `autodiff_optimizers` walks the same Rosenbrock valley with SGD+momentum, Adam and L-BFGS and plots log10 loss - the line-searched method plunges past 1e-24 in ~42 iterations while the first-order ones are still crawling near 1e-3. `autodiff_fn_fit` is the Field-specific bridge: the SAME `fitGradient()` call once with the historic finite-difference gradient and once with `gradient = FnGradientMode.EXACT`, which re-interprets the `Fn` graph in AD arithmetic - same optimiser, lower RMSE, a quarter of the loss evaluations - and asks `exactFitRefusal` why a saw-based seed is refused. `autodiff_ik` solves inverse kinematics on a four-link `FLine` chain and shows the rest pose, the solved pose and a smoothness-penalised pose in three panels sharing one window. `autodiff_pack` packs twelve discs by descending a `relu(overlap)^2` hinge, before and after. No `Window`, no `Renderer`; EXECUTE-bucket in `examples.demo-transcript.exec`. Committed captures of all five box outputs live in `public/docs/assets/autodiff/`; the layer itself is documented in `dev_docs/autodiff.md`.
- `recstl_playground.yaml`: a one-stop, drag-in **RectSL playground**. Builds a "window + renderer" parent box and a child "recstl" box connected beneath it; the child compiles a `#rectsl` snippet, draws it live in the on-screen window, and publishes a `controlGroup("transport")` in its output area — `play`/`loop` checkboxes, a `speed` slider, and a `time` scrub slider. Edit the `rectsl` string and re-run the child to change it live; check `play` to loop it, or scrub `time` while paused to freeze on a frame. Requires a GPU/Vulkan window (like `threebox.yaml`); capture its window with a follow-on `native-screenshot <path>` (`--with-playwright`). Because it starts with `name:`/`operations:`, dropping the file onto a Field document is recognized as a transcript and run (write-then-run) — this is the intended "drag in to get started" path.
- `threebox.yaml`: self-contained capstone. One transcript builds a three-box particle system on a blank canvas — a renderer box (owns `Window` + `Renderer`, pumps the loop), a compute box (simulates particles into a `buffer_reference` buffer and probes speed via `{% include "fieldProbe" %}`, publishing the buffer as `prop<FrameBufferGroup>(outputPositions)`), and a draw box (reads `outputPositions` and renders). Requires a GPU/Vulkan window. Demonstrates cross-box property publish/read and the `await` barrier between the three launches; the transcript itself produces no image artifact (no screenshot step) — capture its native window with a follow-on `native-screenshot <path>` backdoor command (`--with-playwright`).
- `vision_metal_cube.yaml`: one device-facing box that publishes the animated indexed cube to
  Field4Vision's autonomous Compositor Services renderer. Open **Live Metal** in the visionOS app,
  run the transcript, and watch Field output for `vision-metal shader: Shader installed`. The program
  is retained and replayed when the native client connects, so the transcript may run before or after
  opening the immersive space. A visionOS device or simulator is required for the native image.
- `remotear_start.yaml`: the minimal Kotlin/JS RemoteAR starting place. It creates a KotlinJS
  language root and an editable child with the `// {{ remoteAR }}` profile, launches an asset-free
  animated cube after the runtime topology settles, demonstrates `onStart` / `onFrame` / `onStop`,
  and cleans up on relaunch or Stop. Open
  `/extensions/remote_ar/index.html?debug=1` on the browser or headset to see it.
- `visionos_start.yaml`: the matching Kotlin/JS visionOS starting place. It creates a KotlinJS
  language root and an editable child with the `// {{ visionmetal }}` profile, waits for the runtime
  topology to settle, updates named uniforms on the native compositor clock, and includes a complete
  editable Metal shader in the child's `fragment` property.
  Open **Live Metal** in Field4Vision to see it on a visionOS device or simulator.
- `spin_glass.yaml`: a five-box **2D Edwards-Anderson spin glass**, Vulkan-compute accelerated, on a blank canvas — a renderer box, a "metropolis update" compute box (checkerboard / red-black Monte Carlo over Ising spins with random `+/-J` or Gaussian bonds and cosine simulated annealing, updating in-place `buffer_reference` buffers it publishes as props), a "measure" compute box (probes `vec2(spin, energy)` for every site via `{% include "fieldProbe" %}`, so the stats card's x-mean is the magnetization and its y-mean the energy per spin), and a "lattice view" draw box (a fullscreen triangle whose fragment shader reads the shared spins buffer and colours each cell). Requires a GPU/Vulkan window. Demonstrates a red-black compute update split into two dispatches per frame with a `computeMemoryBarrier` task between them, per-frame simulation state advanced from a leading `computeTask` "tick", and a `renderer -> update -> measure -> view` publish/read chain. Produces no image artifact itself — capture its native window with a follow-on `native-screenshot <path>` backdoor command (`--with-playwright`).

- `alife_script.yaml`: **a per-agent behaviour layer for ALife** — sequencing, waiting on simulation
  time, named phases, bounded concurrent sub-behaviours, and steering drives composed from whatever is
  active. Three pure-CPU boxes, a fixed clock and a seeded world, so a run reproduces across JVMs.
- `alife_walls.yaml`: **obstacles, swept collision and routing** for `alife_script`'s `ScriptWorld`.
  One barrier under three `WallResponse`s with the contact counts printed, routed against direct seek
  on a maze, and wall-following. Swept rather than discrete, because a discrete test lets a fast agent
  step through a thin wall between frames.
- `chart_clip.yaml`: **a figure that does not fit inside itself** — the same plot with `clip` on and
  off, and the bounds behind the difference read off the drawn geometry. A panel fits the geometry's
  own bounds, so anything outside the frame shrinks everything else rather than being cropped.
- `chart_primitive.yaml`: **`Chart`, the FLine plot primitive**, one box per thing it is for: one call,
  a DataFrame charting itself, marks that hover and click, and `Chart.expect` drawn as a band with the
  breaking data recoloured. Everything lowers to `List<FLine>` — glyph outlines and SVG, not a raster.
- `completion_rank_at_scale.yaml`: **ranking over the recorded 578-caret corpus**, calling the real
  classifier in process. Box 3 shells out to `tools/rank_weight_lab.py` and wants a spec file a jvmTest
  drops.
- `frame_history_reprojection.yaml`: **temporal reprojection with a debug view that can catch it being
  wrong** — four modes, of which "previous UV" is the only one that shows a constant offset and
  "availability" is the one whose thin red band proves the rule is running. Needs a GPU and a window.
- `keyframe_key_discovery.yaml`: **whether the keyframe browser can show you your own keys** — six
  keys from a real session, browsed under the pattern that session used (which finds two of six, and
  now says so) and under the default. No GPU; each box writes to a fresh temp dir.
- `logging_layer.yaml`: **channels, sinks and the three different rules that select on a channel**,
  read off the live `LogDispatcher`, `ConsoleMute` and `LogStream` rather than described. One word
  selects three different sets of lines, and box 3 makes a real subscriber prove it.
- `midi_clip.yaml`: **a Standard MIDI File written and read back**, plus what ports this machine can
  see and an audition through them. Pure CPU; no device needed to run it.
- `path_tracer_instrument.yaml`: **`display("output", tracer)`**, which until 2026-09-11 produced 376
  bytes of wrapper `<div>`. Six boxes including an integrator swapped live and put back. Needs a GPU.
- `path_tracing_emitters.yaml`: **two lamps in the dark**, with `emitterSampling` flipped every 256
  samples so the two estimators are always compared at the same budget. Needs a GPU.
- `path_tracing_medium.yaml`: **shafts of light through haze**, with `mediumDensity` cycling through
  four values over fixed geometry. Needs a GPU.
- `kolakoski_euclid.yaml`: **Kolakoski counts, Euclid places** — the word says how many hits a group
  gets, `bjorklund` says where inside it they sit. A flat bar of twelve onsets rolled against
  `euclid(12,24)` at the same density, the same rule run recursively as a branching tree whose level
  sizes are the word's own partial sums, and a `.wav` player pinned to the canvas through
  `customization`. Sample-backed through `showDirtAudio`; falls back to `patVoice` with no library.
- `pattern_condense.yaml`: **the shortest way to write a rhythm down** — `patCondense` accepts a
  candidate only by re-parsing and playing it, which is why box 1 draws pairs rather than printing
  strings. Includes the loop closed from audio: samples in, `60(3,8)` out.
- `python_cells.yaml`: **Python cells under a Kotlin language root**. The root stays Kotlin — the
  toggle swaps a root's *children*. Needs an interpreter with JEP's native library found before Field
  started, which is why it is compile-only here.
- `read_only_session.yaml`: **read-only mode from the inside** — a `saved{}` counter that cannot count,
  and what the flag refuses rather than silently drops. The reason every demo folder says `--read-only`.
- `certified_envelope.yaml`: **a bound no feature can slip between**. An hour-long signal with a
  10 ms glitch that the signal viewer's sampled envelope misses by 0.4956, the same viewer with its
  opt-in certified band (interval arithmetic over `FnFormulas`) catching it, `certifiedRange` with
  witnesses, and what refuses: a `fn { }` lambda, by name.
- `solidsl_inverse.yaml`: **a Solid you can differentiate**, and therefore solve. The exact gradient
  checked against central differences on the spot, an inverse fit recovering three badly-seeded
  parameters, and the failure mode that presents as a converged optimisation: a child swallowed under a
  hard `Union` is dead, because a pointwise `min` hands the whole derivative to whichever child is
  nearest.
- `interaction_nets_lambda.yaml`: **λ-calculus on interaction nets**, three pure-CPU boxes. A term is
  compiled to a net, reduced by local rules, and read back, with every answer checked against a
  capture-avoiding substitution evaluator that shares nothing with it — 25 of 25 named closed terms
  agree, and agree under four different schedulers. One box draws a single reduction as five panels
  whose agent counts go *up* at the duplication rule, which is the whole argument for nets: copying is
  explicit work rather than something β hides. No window, no GPU, no setup. The library is
  `afield/stdlib/lambdaterms.kt` and `afield/stdlib/interactionnets.kt`; the write-up is
  `dev_docs/INTERACTION_NETS_LAMBDA.md`.
- `confluence_grid.yaml`: the same subject in **3D and twenty at once** — five terms by four
  `ActivationOrder`s, all reducing on one beat, in a Three.js viewer in the box's own output panel. One
  pure-CPU box. Strong confluence means a row's four cells take the same *number* of reactions and end
  on an isomorphic graph, so the demo has to lay them out by a structural invariant or the picture
  claims the opposite of the theorem: the final pose is a three-round Weisfeiler-Leman refinement and
  nothing in it reads a node id. Worth copying for two mechanics as much as for the subject — a box
  that animates with its own sleep loop rather than `frames { }` (which gets zero ticks without a
  `Window`/`Renderer`), and repeated `three(channel = …)` calls that stream geometry over the socket
  the first call opened instead of remounting the iframe.
- `four_summary_pyramid.yaml`: a **summarised quadtree over a raster** as one pass chain — three
  moving blobs, a 2x2 reduction pyramid that carries mergeable statistics rather than colour, and an
  overlay that draws each cell's centroid and 1-sigma covariance ellipse. A 2x2 reduction already
  *is* a complete quadtree, so the interesting part is what a cell carries: weight, value mean and
  variance, occupancy, and the spatial moments that give every cell an orientation and an
  anisotropy. Requires a GPU/Vulkan window. Its companion is `four_morton_quadtree.yaml`, and the
  measured version is `tools/run_field_tests.py --id four.quadtree-chain.smoke`.
- `four_morton_quadtree.yaml`: the same three planes of statistics produced from a **moving point
  set** instead of a raster — Morton codes, a sort, node ranges found by bracketing prefixes in the
  sorted array, and then literally the same reduction ladder. Worth reading for the two inversions a
  fragment chain forces: a sort becomes a rank-then-gather because a fragment shader cannot scatter,
  and boundary detection becomes a per-node binary search because it cannot scan or compact either.
  Rebuilt from scratch every frame. Requires a GPU/Vulkan window.

- `imgui_vr_panel.yaml`: **Dear ImGui as an in-VR panel**. A renderer box plus a child box that runs `ImGuiVrPanelDemo` (`afield.examples.four`): an ImGui panel rendered to a texture, mapped onto a world-space `ImGuiPanel` quad, drawn once per eye by `SideBySideStereoTarget`, packed side-by-side, and blitted to the window. The mouse drives the mock controller ray (a real controller in XR); the hit is converted to panel UV and injected into ImGui. On Windows/Linux the same packed image feeds a headset via `OpenXRHmdTarget.copySideBySideToEyes`; macOS previews on the desktop. Requires a GPU/Vulkan window — capture with a follow-on `native-screenshot <path>` (`--with-playwright`). Functional coverage of the pipeline is the `examples.four.imgui-vr-panel-smoke` FIELD_TEST.

## Verified in CI — the executing harness

Every transcript in this directory is exercised end-to-end by the FIELD_TEST
`examples.demo-transcript.exec`
(`src/jvmMain/kotlin/afield/examples/DemoTranscriptExecSmokeTest.kt`, run with
`python3 tools/run_field_tests.py --id examples.demo-transcript.exec`). **"Shipping a demo
transcript" now means its box bodies actually execute (or compile) in CI, not merely that the
`code` string round-trips.** This exists because on 2026-07-18 `fn_differentiable_fit_demo.yaml`
shipped with two compile errors in its box body (`Domain(...)` unresolved; an ambiguous `plot(...)`)
and merged — nothing in CI ran the box Kotlin. (`OperationTranscriptMacroSmokeTest` only *applies*
ops and diffs the `code` string.)

The harness classifies each transcript into exactly one of three buckets. The sets in
`DemoTranscriptExecSmokeTest.kt` are the only enumeration — this file used to list their members and
went stale, so read them there:

- **EXECUTE** (pure-compute; no `Window`/`Renderer`): replayed in full through the real
  `OperationTranscript.runMacro` path — the same path an agent uses — and asserted to emit **zero
  error-kind output** and `run.success`. A box compile error does not throw and does not clear
  `run.success`; it surfaces only as an output line with `kind == "error"`, which the harness scans
  for.
- **COMPILE-ONLY** (open a GLFW `Window`/`Renderer`, or a box that depends on a running renderer's
  published context — unsafe to spin a render loop headless in unattended CI): the box graph is built
  with the real create/connect apply path, then each box's Kotlin `code` is run through the real
  macro expander (`applyMacros`), the real preamble machinery (`checkPreambleAndExecute` — which only
  evaluates property-delegate declarations, never a `Window`) and the real K2 compiler
  (`compileSnippetAndWait`) **with evaluation skipped**. This still catches the fn-fit class of error
  (unresolved refs, ambiguous overloads). GLSL shader properties (`vertex`/`fragment`/`compute`) are
  not Kotlin and are not compiled here — they are exercised by the graphics render smokes.
- **KOTLINJS** (the box body is Kotlin for a browser or visionOS head, not JVM Kotlin): compiled
  through the real `KotlinToJsCompiler` via the head profile the box's `// {{ marker }}` selects — the
  identical path a live box takes — and asserted to carry no error diagnostics.

**Adding a demo:** drop the `*.yaml` here, then add its `name` (the file's stem) to exactly one of
`EXECUTE`, `COMPILE_ONLY` or `KOTLINJS` in `DemoTranscriptExecSmokeTest.kt`. An unclassified
transcript fails the test on purpose. Choose EXECUTE when the boxes are pure compute and safe to run
to completion headless; choose COMPILE-ONLY when a box constructs a `Window`/`Renderer` or launches a
render loop; choose KOTLINJS when the body is shipped to a head.

Then **write its entry on the public index**, `public/docs/agent_transcripts.md`, under the section
whose `verified-by` marker matches the bucket you chose. `checkAgentTranscriptIndex` fails the build
until you do, and the classification you pick here is what it checks the page against — see
`dev_docs/agent_transcript_index.md` for the contract and for why that page is hand-written rather
than generated.

## Starting points and the docs gallery

Each transcript here is also **materialized into an openable Field document** so it can surface as a
"Starting point" on the welcome screen (and its search) and in the public
[Agent transcript gallery](../../../public/docs/agent_transcripts.md). The materializer
(`src/jvmMain/kotlin/afield/examples/AgentTranscriptMaterializer.kt`) builds each transcript's box
graph with the structural ops only (no `launch`/`execute`, so no GPU) and saves a pristine document
into `public/docs/assets/agent_transcripts/<name>/`. Transcripts stay the single source of truth —
**never add a `save` op to a transcript** to make this happen; the materializer performs the save out
of band, and its output is deterministic (an async-injected empty `preamble` is stripped, and the
per-launch `history_metrics.edn` is dropped) so the committed bundle does not churn.

The materialized documents are committed. Regenerate them after changing a transcript:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json \
  --command 'run-jvm-main afield.examples.AgentTranscriptMaterializer --arg '"$PWD"'/public/docs/assets/agent_transcripts'
```

The FIELD_TEST `examples.agent-transcripts.materialize` re-materializes to a temp dir and **fails if
the committed bundle is out of date** — or absent entirely — so a transcript edit without a regenerate
is caught in CI. `./gradlew checkAgentTranscripts` is the same guard inside `check`.

A transcript that is *not* a self-contained scene makes a broken starting point, so it is listed in
`AgentTranscriptMaterializer.EXCLUDE` and no document is generated for it. Its index entry then must
not offer an `Open ↗` link, and should say in prose why it is agent-only; the index guard checks both
directions.

## Growth Rules

Keep examples small and agent-useful:

- prefer stable ids over generated ids;
- use event waits such as `wait-output` where possible, and reserve fixed `wait` entries for launch-dependent timing;
- for native graphics, launch `graphics_renderer_launch.yaml` first, run `wait-native-renderer agent_graphics_context --frames 3`, and then launch child boxes;
- keep browser capture in the backdoor's capture commands (`wait-output` / `output` / `screenshot-box` / `native-screenshot`), run after the macro — never as macro ops;
- add a short comment in this README when an example depends on hardware, Vulkan, audio, or external services.
