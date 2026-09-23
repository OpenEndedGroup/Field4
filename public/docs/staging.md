# Where to Go Next

The [Tour](funFeatures.md) this page ends is an arc under *Start Here*, meant to be read in order. This is the map for afterwards: what is in each section, and which page inside it to open first.

Three of the pages below want a caveat before you open them. Two are about maturity: [Apple Vision Pro](vision.md) is a front end still being built and says so, and [Ray Tracing Examples](ray_tracing_examples.md) walks a real Vulkan pipeline but renders its four figures as placeholder cards, because macOS exposes no ray-tracing extension. The third is about *where*: [Geometry in the Headset](vision_geometry.md) is written for code running inside that visionOS app, and only its geometry half can be previewed on a desk.

## Working in a document

The document itself — moving around it, finding things in it, and getting at what it used to say.

- [Spatial Navigation](spatial_navigation.md) is the one to read first: `Cmd Cmd`-and-hold to see every box an arrow key can reach, and nine function keys that jump to saved views. It is how you stop reaching for the mouse.
- [Where Field Looks for a File](files_and_paths.md) is the shortest page here and saves the most time: `"data.csv".file` resolves inside the document folder first and the workspace project second, so a path keeps working after the document is moved or handed to somebody else.
- [Your History with Field](history_overview.md) is the map of a genuinely awkward area — Field keeps several kinds of history, each with its own lifetime, and none of them is a complete record. [Document History](document_history.md) is whole-document recovery from saved versions; the [History Lens](history_lens.md) answers the question you actually have more often, which is what *this line* said a few saves ago, without leaving the editor.
- [Snapping](snapping.md) is a toggle rather than a mode, for a moment of layout cleanup.
- [The Semantic Index](semantic_index.md) is the second of the two searches that `/welcome` runs over your workspace as you type.
- [Simulation Time](simulation_clock.md) is for a behaviour that waits, turns, fires and waits again as ordinary coroutine code, against a clock you advance yourself rather than the wall.
- [Profiling Timeline](profiling_timeline.md) and [Holding a Frame Budget](frame_budget.md) are the pair to open when a piece stops keeping up: the panel that records what each box cost, and what its numbers mean — three instruments, three clocks, three populations, and no obligation to agree.

## Seeing values

What happens when a box ends on a value, which is most of what working in Field feels like.

- [Visualizers & Controls](visualizers.md) is the one to read first: `.h`, the output panel, sliders and the value visualizer that draws anything you hand it.
- [Interactive Guides](interactive_guides.md) shows how boxes can teach a document — slide notes, selections, callbacks, templated values, per-slide chat and callout lines.
- [Charts as FLines](chart.md) and [DataFrames in a Box](dataframe.md) cover the two shapes tabular data usually arrives in.

## Drawing from data

- [Functions Examples](functions_examples.md) turns `Functions` stacks into graphs and parametric FLines, and [Functions Domains](functions_domains.md) covers the domain algebra that governs how an `Fn` is sampled.
- [Values Over Time](interpolants_examples.md) draws interpolants, `KeyframeScope` parameter and code morphs, rolling correlations, and Kalman and particle-filter estimation, all as FLines or live plots.
- [Timeseries Examples](timeseries_examples.md) keeps raw signals next to filtered and event-detected ones.
- [Colors Examples](colors_examples.md) compares colour helpers on actual geometry.

The geometry, sound and audio-AI groups in that section are larger than this list suggests; open the section itself for the full set.

## Networks and dynamics

- [Sorting Networks Examples](sorting_networks_examples.md) draws fixed comparator plans, disorder decay, and annealing histories.
- [Interaction Nets Examples](interaction_nets_examples.md) shows reducer rules as before-and-after geometry.
- [Boolean Networks Examples](boolean_networks_examples.md) connects wiring diagrams, state spaces, attractors and rhythm grids, and [Boolean Network Music Examples](boolean_network_music_examples.md) maps those steps into `NoteProcess` and live Dirt triggers.
- [Neighbors & Graphs Examples](graph_connectivity_examples.md) draws nearest-neighbour queries, geometric graph families, 3D graph viewers and pathing overlays, all on one point cloud.

## Small languages

- [FieldML Cheatsheet](fieldml_cheatsheet.md) and [FieldML Examples](fieldml_examples.md) cover Field's small ML-flavoured expression language, from its prelude of numbers, lists and vectors to boxes that generate and animate drawings.
- [StackSL Cheatsheet](stacksl_cheatsheet.md) and [StackSL Examples](stacksl_examples.md) document the concatenative stack language and its worked drawings, and [RectSL Cheatsheet](rectsl_cheatsheet.md) and [RectSL Examples](rectsl_examples.md) do the same for the rectangle-stack drawing language.
- [ForthField Examples](forth_examples.md) covers Field's own Forth, its output helpers, and its bridge into JVM objects. [Nano Examples](nano_examples.md) covers the tiny assembly-like machine and its RAM and register visualizers.
- [Python in a Box](python.md) and [Clojure in a Box](clojure.md) are the odd ones out: neither is small, and neither is Field's. Both are hosted the same way — in Field's own process, so a guest box and a Kotlin box are two objects in one JVM. CPython is embedded through JEP, so a Python cell can import `java.lang`, reach its own `Box`, and have `fig.show()` land in that box instead of a browser; Clojure 1.11.3 is simply `clojure.jar` on the classpath, with one adapter file between it and Field's box-execution contract.

That section ends with the three pages about making your own, in ascending order of how much machinery they ask for. [Bridging Another Language](morpho_examples.md) brings one in from outside. [Rewriting Your Own Syntax](pattern_rewrite.md) is the cheapest route of all and involves no language at all: a `rewrite { … }` block in a box states a claim about the *shape* of your source, and rewrites it before the compiler sees it. [Language Extensions](language_extensions.md) is how any of them earns highlighting, diagnostics and a context lens inside the editor.

## Graphics, GPU and headsets

- [afield.four Graphics](afield_four_graphics.md) exercises the lower-level renderer path, through the transcript-driven renderer parent described at the foot of this page.
- [Fabricate Renderer](fabricate_renderer.md) covers the `StandardRenderer` bundle: scene graph, render planning, frame graph, post stack, glTF import, material response, shadows and ambient occlusion.
- [Aperture Playground](aperture_playground.md) is the worked multi-pass example: eight boxes carrying four light bands, two reductions, two aperture filters and a composite, with every shader stage in the document and no backend class hiding any of it. It is where to go to see defocus spread a line rather than just dim it.
- [Field Probes](field_probes.md) instruments live shaders, with the CPU read-back `fieldProbe` and the GPU-resident `fieldStats` both demonstrated on one compute-shader particle system.
- [Apple Vision Pro](vision.md) is the native visionOS front end being built: the projection model of docks, islands and tethers, with a stereo dock as the day-one feature.
- [Kotlin in the Head](kotlin_in_the_head.md) is the compile route into that headset and into an ordinary browser tab: a box whose Kotlin is cross-compiled to JavaScript in-process and shipped over Field's own WebSocket, so `data class`es, operator overloading and compile-time type errors survive into a THREE.js scene, a Tone.js engine, or a native Metal shader.

Not here yet: a guide to the low-level Vulkan renderer internals, especially device setup, synchronization, descriptor ownership and shader reflection. Fabricate Renderer is the entry point for the fabricated layer above them, and afield.four Graphics for the layer below.

## AI and agents

- [AI Overview](aiFeatures.md) is the one to read first: every AI feature works from where you already are, so the page is organised by what you are asking for — a conversation about a box, an edit at one spot, AI candidates mixed into `ctrl-space`, or a function body filled in from its signature.
- [Agent Transcript Gallery](agent_transcripts.md) collects agent-built documents as reproducible starting points, each materialized from a recorded transcript and openable in your own workspace.
- [AI Tool-Use History](ai_tool_use_history.md) is the structured trace of what a send actually did, for when a tool call goes wrong, and [AI Documentation Polish](ai_doc_polish.md) covers the generated documentation that fills in for a completion entry whose source carries no useful KDoc.

Two pages you might expect here are on the Tour instead, because both are about reaching a running document rather than about Field's own AI: [Automating Field](automating_field.md) — macros, the operation-transcript format, and the MCP bridge that let a script or an agent drive Field the way a person does — and [Field from your phone](mobile_controllers.md), which turns `/controllers` into a phone-sized remote for live controls, outputs, AI4 chat, Open Anything selection and Monaco editing.

## Reference, and internals

Two sections that are not worked examples, and are read for different reasons.

[What's Already in the Box](reference/index.md) is generated from the KDoc in `src/`, not written: every public declaration in Field's standard library, grouped the same way the editor's autocomplete groups it, and — where a page has earned one by calling enough of a file's entry points — a *Used in* link back to the page here that works it. It is the fastest way to find out whether something already exists. Beside it, *Libraries & APIs* holds three companions to that tree: [Linear Algebra](linear_algebra.md) for the vocabulary underneath movement, cameras and meshes, the [Generated Vector API](linear_algebra_api.md) emitted from the same model that emits those vector types, and [Querying Object Graphs](graph_query.md) for the times you want every cutoff in a rig rather than one you can name.

*Internals* is the other extreme: hand-written prose about mechanism, for when you are extending Field or working out why it did something, rather than writing a box. [What Field Exposes](server_surface.md) is the one to start from — the HTTP and WebSocket surface your own editor is already a client of — and [What a Document Is on Disk](document_on_disk.md) is the one to read before editing a document by hand, because it says which of the files in that directory wins when Field loads.

## How these pages are built

Most of the examples here are captures rather than mock-ups. A page carries a runnable fence, the docs build launches Field through the backdoor, and the box output, editor region, browser window or native `afield.four` frame comes back into the page as an image or a short video. Graphics examples get a live renderer the way a document would, so a picture on one of these pages came through the same box graph, the same context lookup and the same Vulkan startup your own code will.

[Building the Docs & Running the Tests](building_and_testing.md) has the build command and its four capture modes. Read what it says about ports before you start a build: the runner insists on owning the port it is handed, and asks whatever Field is already answering there to shut down, including the one you are working in. [Code Diff Player](diff-player.md) lists the attributes a single capture fence can carry, and [Automating Field](automating_field.md) covers the transcript format the graphics captures replay.
