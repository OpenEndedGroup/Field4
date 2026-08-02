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

## Files

- `hello_display.yaml`: smallest useful blank-canvas transcript; creates one box, displays HTML, and can be undone.
- `hello_display_capture.yaml`: same as `hello_display.yaml` but paired with the backdoor capture-command chain (`wait-output` / `output` / `screenshot-box`) documented under "Capturing output and screenshots" — the worked example of capturing a macro's output and a cropped screenshot.
- `polytope_conway_demo.yaml`: one deliberately compact **polytope review box**. It marks two opposite cube faces with different height attributes, selects and applies `kis` only to those faces, converts the authoritative polygon faces to separate `FLine`s, and ends in the on-canvas `threeLines(...)` viewer. Orbit and hover the result; clicking a face reports its output id, transform role, source elements, and resolved height. Its inspection output also exercises the registered data-class visualizers for the transform and its polygon-to-triangle provenance.
- `two_box_context.yaml`: creates two boxes, connects them, and runs child code that depends on graph context.
- `graphics_renderer_launch.yaml`: creates the reusable `agent_graphics_context` renderer box, launches it with `start(renderer::run)`, prints `agent graphics renderer ready`, and waits three Runloop frames. Run this before child `afield.four` graphics transcripts.
- `afield_four_traditional_triangle.yaml`: creates a graphics child box for the stable backdoor graphics parent `agent_graphics_context` and draws a native Vulkan triangle.
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

The harness classifies each transcript into exactly one bucket:

- **EXECUTE** (pure-compute; no `Window`/`Renderer`): replayed in full through the real
  `OperationTranscript.runMacro` path — the same path an agent uses — and asserted to emit **zero
  error-kind output** and `run.success`. A box compile error does not throw and does not clear
  `run.success`; it surfaces only as an output line with `kind == "error"`, which the harness scans
  for. Current EXECUTE demos: `fn_differentiable_fit_demo`, `linalg_thumbnails_demo`,
  `hello_display`, `hello_display_capture`, `two_box_context`, `vision_metal_cube`.
- **COMPILE-ONLY** (open a GLFW `Window`/`Renderer`, or a box that depends on a running renderer's
  published context — unsafe to spin a render loop headless in unattended CI): the box graph is built
  with the real create/connect apply path, then each box's Kotlin `code` is run through the real
  macro expander (`applyMacros`), the real preamble machinery (`checkPreambleAndExecute` — which only
  evaluates property-delegate declarations, never a `Window`) and the real K2 compiler
  (`compileSnippetAndWait`) **with evaluation skipped**. This still catches the fn-fit class of error
  (unresolved refs, ambiguous overloads). GLSL shader properties (`vertex`/`fragment`/`compute`) are
  not Kotlin and are not compiled here — they are exercised by the graphics render smokes. Current
  COMPILE-ONLY demos: `graphics_renderer_launch`, `afield_four_traditional_triangle`,
  `recstl_playground`, `threebox`, `imgui_vr_panel`.

**Adding a demo:** drop the `*.yaml` here, then add its `name` (the file's stem) to exactly one of
`EXECUTE` or `COMPILE_ONLY` in `DemoTranscriptExecSmokeTest.kt`. An unclassified transcript fails the
test on purpose. Choose EXECUTE when the boxes are pure compute and safe to run to completion
headless; choose COMPILE-ONLY when a box constructs a `Window`/`Renderer` or launches a render loop.

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
the committed bundle is out of date**, so a transcript edit without a regenerate is caught in CI.

## Growth Rules

Keep examples small and agent-useful:

- prefer stable ids over generated ids;
- use event waits such as `wait-output` where possible, and reserve fixed `wait` entries for launch-dependent timing;
- for native graphics, launch `graphics_renderer_launch.yaml` first, run `wait-native-renderer agent_graphics_context --frames 3`, and then launch child boxes;
- keep browser capture in the backdoor's capture commands (`wait-output` / `output` / `screenshot-box` / `native-screenshot`), run after the macro — never as macro ops;
- add a short comment in this README when an example depends on hardware, Vulkan, audio, or external services.
