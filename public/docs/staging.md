# Staging Area

These pages are useful, but they are still moving. Some are smoke-tested examples. Some are notes from teaching documents. Some are the first public pass over systems that are still changing shape.

The order here is meant to help you choose where to go next.

## Reproducible docs and transcript automation

The examples in this staging area are increasingly built from the same machinery that automates Field itself. A docs page can contain a runnable code block, the docs build can launch Field through the backdoor, and the resulting box output, editor region, window, or native graphics frame can be captured back into the page.

For simple examples, the runner creates a scratch box, runs the code, waits for output, and clips a browser screenshot. For graphics examples, the runner now uses a reusable `OperationTranscript` path:

- launch a renderer box from a YAML transcript;
- wait until the native renderer window can advance and capture frames;
- create the example box;
- connect the renderer box as the example's parent, so `fromContext<Renderer>()` works the same way it does in a normal Field document;
- run or launch the example;
- capture the native `afield.four` window through Field's own renderer screenshot path.

That topology step matters. A graphics box is not supposed to find a renderer by accident or by sleeping long enough. It should inherit a live renderer from its parent, just like user-authored Field graphs do. This means docs captures double as real integration tests for the box graph, context lookup, Kotlin execution, Vulkan renderer startup, and screenshot plumbing.

For graphics captures, the docs runner also takes ownership of the configured `FIELD_DOCS_RUNNER_PORT`. If a stale Field process is already serving that port, the runner asks it to shut down and starts a fresh one before it launches the transcript. That keeps renderer topology from accumulating across repeated docs builds.

Native graphics screenshots can opt into background trimming with `trim-native=true` on the runner fence. Use it for `capture="native-window"` examples where the renderer produces a small subject inside a large black window.

The docs build is a uv one-liner from the repository root. `uv` supplies the
Python dependency environment, `tools/build_zensical_docs.py` preprocesses the
runnable Field fences into `public/.zensical-generated/`, writes
`public/zensical.generated.yml`, and then invokes Zensical.

Use this when every executable runner capture should be regenerated:

```diffstatic {data-lang=bash}
FIELD_DOCS_RUNNER_MODE=always FIELD_DOCS_RUNNER_PORT=9477 uv run --project . --group docs python tools/build_zensical_docs.py --clean
```

Use this for a fast HTML-only rebuild from existing captures:

```diffstatic {data-lang=bash}
FIELD_DOCS_RUNNER_MODE=never uv run --project . --group docs python tools/build_zensical_docs.py --clean
```

Do not install Zensical packages by hand for the normal docs flow;
install `uv` if it is missing, then run the command above. Use `incremental`
while writing normal docs, `strict` when runner changes should invalidate
cached captures, and `never` when you only want to rebuild the site HTML from
already-generated assets.

The practical test matrix is:

| Situation | Command | What it checks |
| --- | --- | --- |
| HTML-only docs build | `FIELD_DOCS_RUNNER_MODE=never uv run --project . --group docs python tools/build_zensical_docs.py --clean` from the repository root | Markdown, navigation, links, and checked-in/generated assets, without launching Field. |
| Normal docs authoring | `FIELD_DOCS_RUNNER_MODE=incremental FIELD_DOCS_RUNNER_PORT=9477 uv run --project . --group docs python tools/build_zensical_docs.py` from the repository root | Changed captures are run through Field; unchanged captures use the cache. |
| Runner or transcript changes | `FIELD_DOCS_RUNNER_MODE=strict FIELD_DOCS_RUNNER_PORT=9477 uv run --project . --group docs python tools/build_zensical_docs.py` from the repository root | Capture behavior changes invalidate the cache. |
| Full recapture | `FIELD_DOCS_RUNNER_MODE=always FIELD_DOCS_RUNNER_PORT=9477 uv run --project . --group docs python tools/build_zensical_docs.py --clean` from the repository root | Every runner-managed screenshot and video is regenerated from a live Field session; static reference assets remain owned by their component-specific workflows. |
| Offline asset ownership | `uv run python tools/check_offline_assets.py` from the repository root | Monaco, Iosevka, and offline viewer sources are present; generated public copies are not checked in twice. |

At the end of a build, the runner prints a compact summary with regenerated captures, skipped captures, failures, server starts/restarts, and elapsed time. That line is the first place to look when a docs build feels suspiciously fast, slow, or quiet.

Reusable transcript examples live under `dev_docs/agents/transcripts/`. The most important graphics one is `graphics_renderer_launch.yaml`; it builds and launches the renderer parent that native graphics docs attach their examples to. The public result is modest but important: when a page shows a rendered triangle, buffer example, material test, or ray-traced image, that image was produced by Field during the docs build rather than pasted in by hand.

## Controlling and automating Field

- [Mobile Controllers](mobile_controllers.md) turns `/controllers` into a phone-sized remote for live controls, outputs, AI4 chat, Open Anything selection, and Monaco editing.
- [Automating Field](automating_field.md) covers macros, the operation-transcript format, and the MCP bridge that let a script (or an agent) drive Field the same way a person does.
- [Agent Transcript Gallery](agent_transcripts.md) collects agent-built documents as reproducible starting points, each materialized from a recorded transcript.

## Field systems

- [Apple Vision Pro (WiP)](vision.md) is the in-progress native visionOS front end: the projection model of docks, islands, and tethers, and a stereo dock as the day-one feature.
- [Fabricate Renderer](fabricate_renderer.md) covers the `StandardRenderer` bundle, scene graph, render planning, frame graph, post stack, glTF import, material response, shadows, and ambient occlusion.
- [Ray Tracing Examples](ray_tracing_examples.md) uses the same graphics-context capture route for Vulkan ray tracing examples.
- [afield.four Graphics](afield_four_graphics.md) exercises the lower-level renderer path using the transcript-driven renderer parent described above.
- [Field Probes](field_probes.md) instruments live shaders: the CPU read-back `fieldProbe` and the GPU-resident `fieldStats`, both demonstrated on one compute-shader particle system.
- [Interactive Guides](interactive_guides.md) shows how boxes can teach a document: slide notes, selections, callbacks, templated values, per-slide chat (and its optional chat server), and callout lines.

## Small languages

- [FieldML Cheatsheet](fieldml_cheatsheet.md) and [FieldML Examples](fieldml_examples.md) cover Field's small ML-flavoured expression language, from its prelude of numbers, lists and vectors to boxes that generate and animate drawings.
- [Forth Examples](forth_examples.md) covers Field's small stack language, its output helpers, and its bridge into JVM objects.
- [StackSL Cheatsheet](stacksl_cheatsheet.md) and [StackSL Examples](stacksl_examples.md) document the concatenative stack language and its worked drawings.
- [Language Extensions](language_extensions.md) explains how these small languages plug into the editor — highlighting, flow, and completion.
- [RectSL Cheatsheet](rectsl_cheatsheet.md) and [RectSL Examples](rectsl_examples.md) cover the rectangle-stack drawing language, from its operator vocabulary to animated worked examples.
- [Nano Examples](nano_examples.md) covers the tiny assembly-like machine and its RAM/register visualizers.

## Drawing from data

- [Functions Examples](functions_examples.md) turns `Functions` stacks into graphs and parametric FLines.
- [Functions Domains](functions_domains.md) covers the domain/interval algebra that governs how an `Fn` is constructed and sampled.
- [Values Over Time](interpolants_examples.md) draws interpolants, `KeyframeScope` parameter/code morphs, rolling correlations, and Kalman/particle-filter estimation — all as FLines or live plots.
- [Timeseries Examples](timeseries_examples.md) keeps raw signals next to filtered and event-detected signals.
- [Colors Examples](colors_examples.md) compares color helpers on actual geometry.
- [Neighbors & Graphs Examples](graph_connectivity_examples.md) draws nearest-neighbor queries, geometric graph families, 3D graph viewers, and pathing overlays on one point cloud.

## Networks and dynamics

- [Sorting Networks Examples](sorting_networks_examples.md) draws fixed comparator plans, disorder decay, and annealing histories.
- [Interaction Nets Examples](interaction_nets_examples.md) shows reducer rules as before/after geometry.
- [Boolean Networks Examples](boolean_networks_examples.md) connects wiring diagrams, state spaces, attractors, and rhythm grids.
- [Boolean Network Music Examples](boolean_network_music_examples.md) maps Boolean network steps into `NoteProcess` and live Dirt triggers.

Missing from this section for now: a full guide to the low-level Vulkan renderer internals, especially device setup, synchronization, descriptor ownership, and shader reflection. The Fabricate page is now the user-facing entry point for the fabricated layer; `afield.four Graphics` remains the lower-level examples page.
