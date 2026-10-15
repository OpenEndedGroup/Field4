# Where to Go Next

The [Tour](funFeatures.md) this page ends is an arc under *Start Here*, meant to be read in order. This is the map for afterwards: what is in each section, and which page inside it to open first.

## Chains across subsystems

Each of these follows one object through several parts of Field. They are the quickest way to see how the sections below fit together before you pick one to learn.

- [A Polytope, Cut Open and Traced](chain_polytope_render.md) is the one to read first: a polytope built with Conway operators, cut with an exact Boolean, then path-traced.
- [A Cellular Automaton You Can Hear](chain_ca_sound.md) goes the other way, from a Wolfram rule to the shortest pattern notation that plays it, rendered to a `.wav`.

The rest of the section crosses physics, patterns, RectSL, photographs, λ-terms and lighting; open it for the full set.

## Working in a document

The document itself: moving around it, finding things in it, and getting back what it used to say.

- [Moving Around the Canvas](moving_around_the_canvas.md) is the one to read first: panning and zooming, `Cmd Cmd`-and-hold to see every box an arrow key can reach, and the function keys that jump to saved views.
- [File Management](files_and_paths.md) is short and saves a lot of time: `"data.csv".file` resolves inside the document folder first and the workspace project second, so a path keeps working after the document is moved or handed to somebody else.
- [Your History with Field](history_overview.md) says which of Field's kinds of history holds what, and which tool opens each. [Document History](document_history.md) is whole-document recovery from saved versions; the [History Lens](history_lens.md) shows what *this line* said a few saves ago, without leaving the editor.
- [Snapping](snapping.md) is a toggle rather than a mode, for a moment of layout cleanup.
- [The Semantic Index](semantic_index.md) is the second of the two searches that `/welcome` runs over your workspace as you type.
- [Profiling](profiling_timeline.md) is the page to open when a piece stops keeping up. It covers the panel that records what each box cost, what its numbers mean, and [simulation time](profiling_timeline.md#simulation-time), which runs coroutine code against a clock you advance yourself instead of the wall clock.

## Seeing values

What happens when a box ends on a value, which is most of what working in Field feels like.

- [Visualizers & Controls](visualizers.md) is the one to read first: `.h`, the output panel, sliders and the value visualizer that draws anything you hand it.
- [Interactive Guides](interactive_guides.md) shows how boxes can walk a reader through a document with slide notes, selections, callbacks, templated values, per-slide chat and callout lines.
- [Charts as FLines](chart.md) and [DataFrames in a Box](dataframe.md) cover the two shapes tabular data usually arrives in.

## Drawing from data

- [Linear Algebra](linear_algebra.md) comes first because everything after it uses it: the vector, matrix and quaternion vocabulary underneath movement, cameras, meshes and spatial data.
- [Functions Examples](functions_examples.md) turns `Functions` stacks into graphs and parametric FLines, and [Functions Domains](functions_domains.md) covers the domain algebra that governs how an `Fn` is sampled.
- [Values Over Time](interpolants_examples.md) draws interpolants, `KeyframeScope` parameter and code morphs, rolling correlations, and Kalman and particle-filter estimation, all as FLines or live plots.
- [Signal Examples](spectral_examples.md) keeps raw signals next to filtered and event-detected ones, then turns to what they are made of in frequency: spectra, spectrograms, mel bands and resonators.
- [Colors Examples](colors_examples.md) compares colour helpers on actual geometry.

The geometry, sound and audio-AI groups in that section are larger than this list suggests; open the section itself for the full set.

## Networks and dynamics

- [Sorting Networks Examples](sorting_networks_examples.md) draws fixed comparator plans, disorder decay, and annealing histories.
- [Interaction Nets Examples](interaction_nets_examples.md) shows reducer rules as before-and-after geometry.
- [Boolean Networks Examples](boolean_networks_examples.md) connects wiring diagrams, state spaces, attractors and rhythm grids, and [Boolean Network Music Examples](boolean_network_music_examples.md) maps those steps into `NoteProcess` and live Dirt triggers.
- [Neighbors & Graphs Examples](graph_connectivity_examples.md) draws nearest-neighbour queries, geometric graph families, 3D graph viewers and pathing overlays, all on one point cloud.

## Small languages

- [Querying Object Graphs](graph_query.md) and [Datalog in a Box](datalog.md) are a pair, read in that order: the first is for the times you want every cutoff in a rig rather than one you can name, and the second answers the recursive questions a path pattern cannot, such as what runs downstream of a box.
- [FieldML Cheatsheet](fieldml_cheatsheet.md) and [FieldML Examples](fieldml_examples.md) cover Field's small ML-flavoured expression language, from its prelude of numbers, lists and vectors to boxes that generate and animate drawings.
- [StackSL Cheatsheet](stacksl_cheatsheet.md) and [StackSL Examples](stacksl_examples.md) document the concatenative stack language and its worked drawings, and [RectSL Cheatsheet](rectsl_cheatsheet.md) and [RectSL Examples](rectsl_examples.md) do the same for the rectangle-stack drawing language.
- [ForthField Examples](forth_examples.md) covers Field's own Forth, its output helpers, and its bridge into JVM objects. [Nano Examples](nano_examples.md) covers the tiny assembly-like machine and its RAM and register visualizers.
- [Python in a Box](python.md) and [Clojure in a Box](clojure.md) are the odd ones out: neither is small, and neither is Field's. Both run inside Field's own process, so a Python cell can import `java.lang`, reach its own `Box`, and have `fig.show()` draw into that box instead of a browser.

The section ends with pages about making your own. [Rewriting Your Own Syntax](pattern_rewrite.md) is the lightest: a `rewrite { … }` block in a box matches a *shape* in your source and rewrites it before the compiler sees it. [Bridging Another Language](morpho_examples.md) brings a language in from outside. [Language Extensions](language_extensions.md) is how any of them earns highlighting, diagnostics and a context lens inside the editor.

## Graphics, GPU and headsets

- [Field 4 Graphics](afield_four_graphics.md) exercises the lower-level renderer path, through the transcript-driven renderer parent described at the foot of this page.
- [Fabricate Renderer](fabricate_renderer.md) covers the `StandardRenderer` bundle: scene graph, render planning, frame graph, post stack, glTF import, material response, shadows and ambient occlusion.
- [Aperture Playground](aperture_playground.md) is the worked multi-pass example: light bands, reductions, aperture filters and a composite, each in its own box, with every shader stage in the document. It shows defocus spreading a line rather than just dimming it.
- [Field Probes](field_probes.md) instruments live shaders, with the CPU read-back `fieldProbe` and the GPU-resident `fieldStats` both demonstrated on one compute-shader particle system.
- [Apple Vision Pro](vision.md) is the native visionOS front end, still being built: docks, islands and tethers, starting with a stereo dock. [Geometry in the Headset](vision_geometry.md) is written for code running inside that app; only its geometry half can be previewed on your Mac.
- [Ray Tracing Examples](ray_tracing_examples.md) walks through `VK_KHR_ray_tracing_pipeline` programs, from a raygen shader writing into a storage image to procedural spheres reflecting a traced floor. They need a GPU and driver with hardware ray tracing; the [path tracer](path_tracing.md) has a compute backend for machines without it.
- [Kotlin in the Head](kotlin_in_the_head.md) is the compile route into that headset and into an ordinary browser tab: a box whose Kotlin is cross-compiled to JavaScript in-process and shipped over Field's own WebSocket, so `data class`es, operator overloading and compile-time type checking carry over into a THREE.js scene, a Tone.js engine, or a native Metal shader.

Not here yet: a guide to the low-level Vulkan renderer internals, especially device setup, synchronization, descriptor ownership and shader reflection. Fabricate Renderer is the entry point for the fabricated layer above them, and Field 4 Graphics for the layer below.

## AI and agents

- [AI Overview](aiFeatures.md) is the one to read first. It is organised by what you are asking for: a conversation about a box, an edit at one spot, AI candidates mixed into `ctrl-space`, or a function body filled in from its signature.
- [Agent Transcript Gallery](agent_transcripts.md) collects agent-built documents as reproducible starting points, each materialized from a recorded transcript and openable in your own workspace.
- [AI Tool-Use History](ai_tool_use_history.md) is the structured trace of what a send actually did, for when a tool call goes wrong, and [AI Documentation Polish](ai_doc_polish.md) covers the generated documentation that fills in for a completion entry whose source carries no useful KDoc.
- [Notes on the Documentation](docs_notes.md) turns notes you leave on these pages into a queue that agents work through.

Two pages you might expect here are on the Tour instead, because both are about reaching a running document rather than about Field's own AI: [Automating Field](automating_field.md), on the macros, transcript format and MCP bridge that let a script or an agent drive Field the way a person does, and [Field from your phone](mobile_controllers.md), which turns `/controllers` into a phone-sized remote for live controls, outputs, AI4 chat, Open Anything selection and Monaco editing.

## Reference, and internals

Neither section is a worked example. The reference is for looking something up; the internals are for understanding how Field works underneath.

[What's Already in the Box](reference/index.md) lists every public declaration in Field's standard library, grouped the way the editor's autocomplete groups it, with *Used in* links back to the pages that use it. It is the fastest way to find out whether something already exists. Beside it is the [Generated Vector API](linear_algebra_api.md), emitted from the same model that emits Field's vector types.

*Internals* is the other extreme: hand-written prose about mechanism, for when you are extending Field or working out why it did something, rather than writing a box. [What Field Exposes](server_surface.md) is the one to start from: the HTTP and WebSocket surface your own editor already talks to. [What a Document Is on Disk](document_on_disk.md) is the one to read before editing a document by hand, because it says which file in that folder wins when Field loads.

## How these pages are built

The pictures and videos on these pages come from running each page's code in Field when the docs are built, so an example pasted into a box gives the same result.

[Building the Docs & Running the Tests](building_and_testing.md) has the build command and its capture modes. Read what it says about ports before you start a build: the runner shuts down whatever Field is answering on its port, including the one you are working in. [Code Diff Player](diff-player.md) lists the attributes a single capture fence can carry, and [Automating Field](automating_field.md) covers the transcript format the graphics captures replay.
