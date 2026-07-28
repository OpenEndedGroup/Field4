# Agent transcript gallery

Field ships a small, curated corpus of **operation transcripts** — short YAML macros that build a
whole Field document (boxes, code, graph wiring) from a blank canvas. They are the worked examples
an agent copies, runs, and adapts when [automating Field](automating_field.md), and every one of
them is exercised end-to-end in CI, so the scenes below are known to build and run, not just to
round-trip as text.

These are the same scenes offered as **Starting points** on the [welcome screen](history_overview.md):
each transcript is materialized into an openable document, so you can search for it there and open an
editable copy in your workspace. This page is the illustrated index.

## Running one

Any transcript can be replayed into a running Field with the agent backdoor or the `field_run_transcript`
MCP tool — see [Automating Field](automating_field.md) for the full mechanism. In short:

```diffstatic {data-lang=bash}
uv run --project . --group backdoor python tools/field_backdoor.py --json \
  --command 'macro write hello_display --file dev_docs/agents/transcripts/hello_display.yaml' \
  --command 'macro run hello_display'
```

The source corpus lives in the repository at `dev_docs/agents/transcripts/`, with a companion
`README.md` covering the op vocabulary, capture commands, and the graphics-topology rule.

## Pure-compute scenes

These build and run headless — no window, no GPU. Open one to get the code in front of you, then run
its box to see the output (a plot, an inline value thumbnail, a printed line).

**hello_display** — Minimal blank-canvas macro that creates one runnable output box.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/hello_display/metadata.edn)

**hello_display_capture** — The same single display box, paired with the backdoor capture commands
(`wait-output` / `output` / `screenshot-box`) that collect its output events and a cropped
screenshot. Capture is a transport concern, so it lives in backdoor commands, not in the macro.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/hello_display_capture/metadata.edn)

**two_box_context** — Creates a parent/child pair and verifies the child inherits graph context from
its parent.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/two_box_context/metadata.edn)

**linalg_thumbnails_demo** — Each box evaluates one `afield.stdlib.linalg` value; on execute its
output shows the terse text head plus a small inline SVG thumbnail the `ValueVisualizer` draws — an
auto-scaled arrow for vectors (isometric for `vec3`), a diverging heatmap grid for matrices.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/linalg_thumbnails_demo/metadata.edn)

**fn_differentiable_fit_demo** — A self-contained demo of the differentiable fitter
(`afield.stdlib.fitBest` / `fitGradient`), the gradient-descent counterpart to the genetic
`evolve()`. The target is a product of two oscillators with a deliberately-wrong seed; a single
`fitGradient` stalls in a frequency local-minimum, so the demo uses `fitBest` (jittered restarts) to
land in the global basin. On execute it plots the target and the fitted curve and returns an RMSE
before→after summary with a loss-trajectory sparkline.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/fn_differentiable_fit_demo/metadata.edn)

**wfc_worked_example** — The seven sections of the [Wave Function Collapse worked
example](wfc_examples.md) as seven independent boxes: adjacency authored by hand with `wfcRules { }`
and both phases pinned with `forceTile`; the simple-tiled model learned from an image, printing the
band histogram that shows its learned weights losing the water bands entirely; an eight-tile
edge-socket set whose traces always connect; the overlapping model with and without the classic
ground constraint; von Neumann vs Moore on a source built so the choice provably matters (473
diagonal breaks against 0); and the collapse itself sampled through `step()` into wave and
possibility-count frames; and both `FloatArray` paths, whose synthesised heightfield ends up as
iso-contour `FLine`s. Every box is pure CPU, runs on its own, and prints the numbers the docs page
quotes.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/wfc_worked_example/metadata.edn)

## visionOS device scenes

These publish to Field4Vision's autonomous Live Metal renderer. Open **Live Metal** in the visionOS
app, then run the box; the program is retained and replayed if the native client connects later. A
visionOS device or simulator is required for the image, but each one builds and compiles in CI —
including the Kotlin→JavaScript compile of the box body through the real head profile. See
[Building geometry in the headset](vision_geometry.md) for the walkthrough these are drawn from.

**visionos_start** — The smallest complete Live Metal program, and the place to start: a cube built in
the headset by `geometry { }`, a Metal shader in the box's `msl` property, and one uniform animated on
the compositor clock.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/visionos_start/metadata.edn)

**vision_geometry_shapes** — A tour of the geometry builder's shape primitives: cube, sphere, plane
and tube side by side in one mesh, with a shader that reads the uv the shapes write as a checker.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/vision_geometry_shapes/metadata.edn)

**vision_geometry_heightfield** — The builder at its raw level: a ripple height field assembled vertex
by vertex, with normals from finite differences of the same height function and per-vertex colour
carrying relief into the shader. The shape most procedural meshes take.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/vision_geometry_heightfield/metadata.edn)

**vision_geometry_animated** — Rebuilding the mesh every frame, which is what separates building
geometry in the headset from shipping it over the wire: a swept tube whose spine and radius both move.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/vision_geometry_animated/metadata.edn)

**vision_metal_cube** — The non-Kotlin/JS route, for comparison: JVM Kotlin publishes the shader, the
script and an explicit request for the head's built-in cube in one `visionMetal(...)` call.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/vision_metal_cube/metadata.edn)

**vision_geometry_desk_preview** — The one on this list that needs **no headset at all**: both boxes
build a Live Metal `geometry { … }` for real — compiled to JavaScript and executed — and draw the
resulting mesh into their own output. The second is the same mesh wound backwards, so you can see what
an inside-out mesh looks like and how the preview tells you.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/vision_geometry_desk_preview/metadata.edn)

## Graphics scenes

These are self-contained scenes that build their own `Window` + `Renderer` and, when run, open a
**native Vulkan window** — so the live image is a desktop window, not an embedded frame. Rather than
ship a synthesized still, the gallery lets you open each scene and run it yourself; capture its window
with a follow-on `native-screenshot` backdoor command (with `--with-playwright`). They also build and
compile in CI.

(Two building-block transcripts are intentionally *not* here: `graphics_renderer_launch` is a bare
renderer with nothing to draw, and `afield_four_traditional_triangle` is a child box that gets its
`Renderer` from that parent via graph context — neither is a self-contained scene on its own. Both
still live in the corpus under `dev_docs/agents/transcripts/` for agents composing scenes.)

**threebox** — A self-contained renderer + compute + draw particle system: a renderer box that owns
the `Window`/`Renderer` and pumps the loop, a compute box that simulates 100k particles into a
`buffer_reference` buffer it publishes as a prop, and a draw box that reads that buffer and renders.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/threebox/metadata.edn)

**spin_glass** — A 2D Edwards–Anderson spin glass, Vulkan-compute accelerated. A renderer box, a
"metropolis update" compute box (checkerboard / red-black Monte Carlo over Ising spins with
cosine simulated annealing, updating in-place buffers), a "measure" compute box (probes
`vec2(spin, energy)` per site so the stats card's x-mean is the magnetization and y-mean the energy
per spin), and a "lattice view" draw box whose fragment shader colours each cell. Demonstrates a
`renderer → update → measure → view` publish/read chain and a two-dispatch red-black update with a
`computeMemoryBarrier` between passes.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/spin_glass/metadata.edn)

**recstl_playground** — A one-stop [RectSL](rectsl_examples.md) playground: a Window + Renderer, a
hackable `#rectsl` snippet, and transport controls (play / loop / speed / time-scrub) published in
the box's output area. Edit the `rectsl` string and re-run to change it live.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/recstl_playground/metadata.edn)

**rectsl_dataviz** — A loadable demo of the RectSL data-visualization family: a viridis colour-bar
legend built with `array` + `colormap`, and a bar chart whose heights and colours are both bound to a
numeric list via `data` / `normalize` / `bars`, animated in with a staggered eased `->alpha`.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/rectsl_dataviz/metadata.edn)

**rectsl_plot_axes** — A loadable demo of the RectSL plotting layer: a shared coordinate `->domain` +
`->frame` with `axes` (rules, nice-number ticks, numeric labels drawn as rect numerals, gridlines) and
three series overlaid in the one domain — an `area`, a `line`, and `points` — plus a `colorbar` and a
discrete swatch `legend`.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/rectsl_plot_axes/metadata.edn)
