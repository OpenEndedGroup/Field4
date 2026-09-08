# Staging Area

These pages are useful, but they are still moving. Some are smoke-tested examples. Some are notes from teaching documents. Some are the first public pass over systems that are still changing shape.

The order here is meant to help you choose where to go next.

## Controlling and automating Field

- [Mobile Controllers](mobile_controllers.md) turns `/controllers` into a phone-sized remote for live controls, outputs, AI4 chat, Open Anything selection, and Monaco editing.
- [Automating Field](automating_field.md) covers macros, the operation-transcript format, and the MCP bridge that let a script (or an agent) drive Field the same way a person does.
- [Agent Transcript Gallery](agent_transcripts.md) collects agent-built documents as reproducible starting points, each materialized from a recorded transcript.

## Field systems

- [Apple Vision Pro (WiP)](vision.md) is the in-progress native visionOS front end: the projection model of docks, islands, and tethers, and a stereo dock as the day-one feature.
- [Fabricate Renderer](fabricate_renderer.md) covers the `StandardRenderer` bundle, scene graph, render planning, frame graph, post stack, glTF import, material response, shadows, and ambient occlusion.
- [Ray Tracing Examples](ray_tracing_examples.md) walks the Vulkan ray tracing pipeline: storage image, BLAS and TLAS, hit groups, procedural intersection, and one reflected bounce. Its four figures are placeholder cards, because macOS exposes no ray tracing extension.
- [afield.four Graphics](afield_four_graphics.md) exercises the lower-level renderer path, through the transcript-driven renderer parent described at the foot of this page.
- [Field Probes](field_probes.md) instruments live shaders: the CPU read-back `fieldProbe` and the GPU-resident `fieldStats`, both demonstrated on one compute-shader particle system.
- [Interactive Guides](interactive_guides.md) shows how boxes can teach a document: slide notes, selections, callbacks, templated values, per-slide chat (and its optional chat server), and callout lines.

Not here yet: a guide to the low-level Vulkan renderer internals, especially device setup, synchronization, descriptor ownership, and shader reflection. Fabricate Renderer is the entry point for the fabricated layer above them, and afield.four Graphics for the layer below.

## Small languages

- [FieldML Cheatsheet](fieldml_cheatsheet.md) and [FieldML Examples](fieldml_examples.md) cover Field's small ML-flavoured expression language, from its prelude of numbers, lists and vectors to boxes that generate and animate drawings.
- [Forth Examples](forth_examples.md) covers Field's small stack language, its output helpers, and its bridge into JVM objects.
- [Python in a Box](python.md) is the odd one out here — not small, and not Field's. CPython is embedded in-process through JEP, so a Python cell shares a JVM with your Kotlin: it can import `java.lang`, reach its own `Box`, and have `fig.show()` land in that box instead of a browser.
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

## How these pages are built

Most of the examples here are captures. A page carries a runnable fence, the docs build launches Field through the backdoor, and the box output, editor region, browser window or native `afield.four` frame comes back into the page as an image or a short video.

Graphics examples get their renderer the way a Field document would. The runner replays `dev_docs/agents/transcripts/graphics_renderer_launch.yaml` to build and launch a renderer box, creates the example box, and connects the renderer as its parent, so `fromContext<Renderer>()` resolves against a live renderer instead of a sleep long enough to find one. The capture then goes through Field's own renderer screenshot path, which means it exercises the box graph, context lookup, Kotlin execution, Vulkan startup and screenshot plumbing on every docs build.

[Building the Docs & Running the Tests](building_and_testing.md) has the build command and its four capture modes. Read what it says about ports before you start a build: the runner insists on owning the port it is handed, and asks whatever Field is already answering there to shut down, including the one you are working in. [Code Diff Player](diff-player.md) lists the attributes a single capture fence can carry, and [Automating Field](automating_field.md) covers the transcript format the graphics captures replay.
