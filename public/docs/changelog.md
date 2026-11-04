# What's new

What has changed in Field·4 and on this site, newest first. Each entry covers a few days of work:
the features that arrived, linked to the page that describes them, and the pages that are new or
were rewritten. Small fixes and wording changes are left out; the **Changelog** panel on Field's
Welcome screen lists every commit in your own checkout.

## November 2026

<!-- changelog: 9bc6dcb33e..46e37dc010 -->
### Screenshots at the size Field draws them, and a generator graded by Datalog

*4 November 2026*

Captures on every page now display at 1.5× the size Field drew them, so text in a screenshot
reads at about the size of the text around it. A new example page hands a flow-matching
generator's points to a Datalog program, which checks whether they kept the target cloud's shape.
[A Generator, Graded by Datalog](chain_flow_islands.md).

<!-- changelog: b5844b087c..10c40f4a2f -->
### Evolution against random sampling, on the same budget

*3–4 November 2026*

- **Braitenberg vehicles**: a new section evolves vehicle brains on a larger genome and compares
  evolution with random sampling at the same budget.
  [A larger genome](braitenberg_vehicles.md#10-a-larger-genome-does-evolution-beat-random-sampling).
- **ALife search**: the same equal-budget comparison now runs on three genomes.
  [Does search beat random?](alife_examples.md#does-search-beat-random).
- The [chain pages](chains.md) were revised from a first-time reader's notes, and the docs site's
  background is a little lighter.

<!-- changelog: 018942b7e0..6d05fc6514 -->
### Field inside VR

*2 November 2026*

- **Field inside VR**: edit Kotlin, run boxes and move around the canvas with the headset on, in a
  browser panel inside Field's OpenXR scene. Controllers point, scroll and resize it, and a real
  keyboard and mouse work on Quest 3 with WiVRn and on Vision Pro through ALVR.
  [Field inside VR](field_vr_ide.md).
- **Keyframe sweeps** can render chosen blends, including blends past the saved keys.
  [Blending past the saved keys](keyframe_autosweep.md#blending-past-the-saved-keys).
- The [path tracing](path_tracing.md) page and dozens of others gained captures that were missing
  or weak, and SolidCraft moved to v0.30.

<!-- changelog: 3766ef7601..625f3c97ee -->
### Typeset math across the docs, and moving lights for Braitenberg vehicles

*1 November 2026*

- **Math** is now typeset on 37 pages.
- **Braitenberg vehicles** gain moving lights, a price on speed, and evolved wirings.
  [Moving lights](braitenberg_vehicles.md#8-moving-lights),
  [Searching for a wiring](braitenberg_vehicles.md#9-searching-for-a-wiring).
- **Snapping** moved into [Moving around the canvas](moving_around_the_canvas.md#snapping).
- The documentation panel inside Field has back and forward, and many pages gained real captures
  of sound, video and live windows.

<!-- changelog: c1d38b6125..cb85eb334e -->
### A picture of the renderer window in a box

*1 November 2026*

- **`snapshotWindow`** puts a picture of the renderer window in a box's output.
- **FieldML** `frames` bodies can wait on Kotlin `suspend` calls with `hostAwait`.
  [Waiting inside a frames body](fieldml_host_bridge.md#waiting-inside-a-frames-body).
- **Graphics lessons**: every lesson in the bundled graphics curriculum now has its picture, and
  the glTF lessons load the toy car. [Field 4 graphics](afield_four_graphics.md).
- `error(...)` on a box's output is now `displayError(...)`.

## October 2026

<!-- changelog: 32070dc6d4..4fdbc866b6 -->
### Braitenberg vehicles with memory

*31 October 2026*

- **Braitenberg vehicles**: a new page builds vehicles from one neuron up, through four wirings,
  boredom, learning and circling at a set distance. [Braitenberg vehicles](braitenberg_vehicles.md).
- **Offline viewer**: open a document zip in the browser and import it whole into Field.
- **Graphics**: sections that were code only now have captures, including films of the native
  renderer window. [Field 4 graphics](afield_four_graphics.md).
- The [Petri nets](petri_nets.md) page lets you hear its two-place loop.

<!-- changelog: 78f468d57a..38875efe9f -->
### Presets, morphing and MIDI learn for any control group

*31 October 2026*

- **Controls**: a control group gets presets you can morph between, recording into keyframes and
  MIDI learn. Presets are keyframer keys, and a keyframed control is declared as
  `var size by keys.slider(...)`. [Presets and morphing](visualizers.md#presets-and-morphing),
  [MIDI learn](visualizers.md#midi-learn).
- **FieldML** calls Kotlin `suspend` methods with `hostLaunch`.
  [Calling a suspend method](fieldml_host_bridge.md#calling-a-suspend-method).
- **Templates**: `cmd+t` lists personal templates too, in one precedence order.
  [Insert a template](templates.md#insert-a-template).
- The [Patchy](patchy.md), [presentation](presentation_studio.md), [layers](layers.md) and
  [demo document](demo_document.md) pages were revised after a fresh read.

<!-- changelog: 8c5b16429b..0aa8695860 -->
### The starting pages, revised for a first-time reader

*31 October 2026*

The front page, [installation](installation.md), [first steps](first_steps.md), the
[Survival Guide](survivalGuide.md), the [Tour](funFeatures.md) and its stops were revised from a
first-time reader's notes. [A Tour of the Interface](tour_interface.md) now shows every panel with
a real document doing its job, and the [keyframer](keyframer_examples.md) and
[keyframe editor](keyframe_editor.md) pages lead with the task. Box code across the site is now
formatted consistently, and plane-grid figures use a flat card.

<!-- changelog: b528721fdf..7b1a6adf7b -->
### A tour of the interface, and one box's history

*30 October 2026*

- **A Tour of the Interface**: a new page on the shortcuts, editor, palettes, search, bookmarks,
  layers, drawing layer, Macro Recorder, history and publishing. [A Tour of the
  Interface](tour_interface.md).
- **This Box** in the history panel lists one box's code across every version and restores just
  that box. [One box, edit by edit](document_history.md#one-box-edit-by-edit).
- Field's panels share one close button, title and look, and Escape closes them consistently.
- Code fences highlight whole, and comment and blank lines carry no line number, in the docs and
  the editor.

<!-- changelog: d30e5dc465..f2f9ec707f -->
### Templates, generative mocap and a stereo composite

*29–30 October 2026*

- **Templates**: save boxes as a template, insert one with `cmd+t`, and see where a copy came
  from. [Templates](templates.md).
- **Generative Mocap**: open motion recordings, run a live simulated body, write a movement
  score and give it an intention or a route. [Generative Mocap](motion_recordings.md).
- **Stereo RTT composite**: a desktop-first stereo starting point, with or without a headset.
  [Stereo RTT composite](stereo_rtt_composite.md).
- Every value figure on [Visualizers](visualizers.md) is now captured from a real box, and the
  reference gains an [animation](reference/animation.md) page.

<!-- changelog: 551989313d..e6ff71c3dd -->
### Smoother XY pads and sliders, and a tour of your own document

*29 October 2026*

- **Controls**: XY pads take arrow keys, reset and fine drags, a focused slider follows the
  wheel, and a drag survives the box re-rendering. [Handling an XY pad](visualizers.md#handling-an-xy-pad).
- **Interactive guides** can tour your own document.
  [A tour of your own document](interactive_guides.md#a-tour-of-your-own-document).
- **Browse a glTF scene** in the inspector.
  [Browsing a glTF scene](visualizers.md#browsing-a-gltf-scene).
- Output frames arrive in order and display panels are never dropped, re-runs start faster, and
  the reference documents many more members.

<!-- changelog: 9694a78d94..68cf3329a2 -->
### Euler spirals as true clothoids, and an AI4 that reads code more closely

*28 October 2026*

- **Euler spirals** are fitted as true G1 clothoids.
  [Euler spiral in 3D](fline_examples.md#euler-spiral-in-3d-clothoid-ish-connection).
- **AI4** shows its model's progress live, finds lines and near-matches in attachments, can ask what
  a name means at a place in a box's code, and searches the docs literally. Local requests may run
  for 45 minutes. [AI4's built-in tools](ai4_tools.md).
- **Classes in a box** can call `println` and `display`.
- Each document in the [starting-point gallery](agent_transcripts.md) has a web publication that
  opens beside its entry.

<!-- changelog: 7d032fa083..ab7871d434 -->
### Euclid's later books, and web publications with outputs

*28 October 2026*

- **Euclid's Elements** now spans Books I to VI, with I.47, filled areas and every way a
  construction can come out. [Every solution](euclid_elements.md#every-solution).
- **Publish for the web**: package a document as a static site with every box's captured output,
  including interactive Three.js views. [Publishing a document](box_output.md#publishing-a-document).
- **Drawing layer**: text defaults to Iosevka with plain styling, and the drawing has its own row
  in the docked [layers panel](layers.md#the-layers-panel).

<!-- changelog: 4bfc1f37ea..2b556bd9bd -->
### Euclid's Elements, drawn like Byrne's plates

*28 October 2026*

- **Euclid's Elements**: write a proposition as a compass-and-straightedge construction from 2-D
  points, lines, circles and arcs, and see it drawn in the style of Byrne's coloured edition,
  starting with Book I. [Euclid's Elements](euclid_elements.md).
- **Field from your phone**: the Pad tab scrubs a number in the code open on the desktop, and
  tabs can be opened straight from a link. [Field from your phone](mobile_controllers.md).
- Saved documents can be offered as starting points as they are, and the three StackSL
  conversations are now starting points.

<!-- changelog: a59cda19b3..3034df94da -->
### More reference overviews, and fewer surprises when editing on disk

*27 October 2026*

Many more files in the [reference](reference/index.md) gained an overview, and others were
rewritten in plainer language. Editing connections in `metadata.edn` now rewires the open boxes,
and a newer inline copy of a property wins over its file
([The document on disk](document_on_disk.md)). Attaching to a running Field no longer replays old
output over a slider you just dragged, and editing a launched Kotlin box no longer leaks a compiler
each time.

<!-- changelog: dbac721e81..b6cba419c0 -->
### Spreadsheets in a box, and interrupting a box that never returns

*27 October 2026*

- **Spreadsheets**: a sheet in a box whose formulas are Kotlin, with units, vectors and drawings in
  cells, values read from other boxes, and cells solved for from constraints.
  [Spreadsheets](spreadsheets.md), [Solving for cells](spreadsheets.md#solving-for-cells).
- **Runaway boxes**: box code that never returns is interrupted after 30 seconds, or at once with
  **Interrupt Running Box**. [Code that never returns](running_code.md#code-that-never-returns).
- **A pen that sees**: a learned pen that senses the ink around its tip, so it can follow a line,
  stop at one or hatch around a drawing. [A pen that sees](a_pen_that_sees.md).
- **Browse the renderer** in the inspector: its passes, resources and shader stages.
  [Browsing the renderer](visualizers.md#browsing-the-renderer).
- The [Tour](funFeatures.md)'s screenshots were retaken.

<!-- changelog: 9ee3edad07..e69a3a17da -->
### A learned pen that continues your lines

*26–27 October 2026*

- **A learned pen**: a handwriting network that carries on from the end of any `FLine`, as one
  continuation, many alternatives, or a pen that keeps writing live. [A learned pen](learned_pen.md).
- **Teaching a pen**: train pens on corpora of ordered drawings and see what each learned.
  [Teaching a pen](teaching_a_pen.md).
- **AI4 as a coding agent**: AI4 can work on Field's own repository in headless jobs, alongside
  claude and codex. [Working on Field's own repository](ai4_tools.md#working-on-fields-own-repository).
- The [Tour](funFeatures.md) introduces live presentations.

<!-- changelog: a6f6163283..bd31a9d0dd -->
### AI4's built-in tools, documented

*25–26 October 2026*

- **AI4's tools**: a new page shows every built-in tool with real calls: making and running boxes,
  reading output, tracing where a value comes from, changing a box. AI4 can also stop and ask you a
  question. [AI4's built-in tools](ai4_tools.md).
- **A Terminal in a Box** has its own page, with `top` and `df` captures. [A Terminal in a
  Box](terminal.md).
- **Presentations** gain CSS themes, element fades, builds that keep earlier content, repeatable
  cues and search. [Themes and simple builds](presentation_studio.md#themes-and-simple-builds).
- A stop that has not taken effect yet pulses the box's status marker, and how values travel down
  connections is now part of [Running code](running_code.md#property-inheritance-how-children-see-a-parents-values).

<!-- changelog: 6dae66dbc9..682ba82d54 -->
### Javascript boxes in a browser tab

*24–25 October 2026*

- **Javascript in a browser tab**: sketch in a browser from a Javascript box, with errors on their
  lines, drawing and animation, controls and output channels. [Javascript in a browser
  tab](remotejs.md). Kotlin compiled for the browser gets the same printing, errors and controls.
  [Kotlin in the head](kotlin_in_the_head.md#printing-errors-display-and-controls).
- **Lua**: the page was checked against the runtime, recaptured, and now covers the API it had left
  out. [Lua](lua.md).
- **FLine examples**: weak examples were redrawn, and interpolator morphs show as a filmstrip.
  [FLine](fline_examples.md).

<!-- changelog: e03fc1e05b..380f450629 -->
### The demo document, and a Kits library on the welcome page

*23–24 October 2026*

- **The demo document**: one canvas that shows most of what Field does, and the film on the home
  page is a tour of it. [The Demo Document](demo_document.md).
- **Kits** are listed in a compact library on the welcome page and can be restarted.
  [Add a self-contained kit in Welcome](kits.md#add-a-self-contained-kit-in-welcome).
- **AI4 with a local model** fits its context to the model, and its semantic and tutorial code
  search work. [Local AI](local_ai.md).
- **Macros** replay a box's current code, not a recorded copy, and Ceres solvers no longer crash
  on segments that share a point.

<!-- changelog: 77dd603a46..6d8a10eb5a -->
### A renewed offline viewer, and math in the AI chat

*22 October 2026*

- **Offline viewer**: a document opened in the browser without Field now shows box notes as
  Markdown, the drawing layer, zoom, bookmarks and layers, and the site has a gallery of every
  document it ships. The [`offline_viewer_demo`](agent_transcripts.md#offline_viewer_demo) starting
  point is a small example.
- **AI4** typesets math in its chat.
- **Presentations**: slides can be authored in Field with a hierarchical outline, and Kotlin
  guide-based presentations are the default. [Presenting with Field](presentation_studio.md).
- **Pack Boxes** tidies the canvas and can make room for open outputs, and a box's resize handle
  stays the same size on screen at any zoom.

<!-- changelog: cf9cf07817..bba6e43aca -->
### Presenting with Field, local AI models, and layers

*21–22 October 2026*

- **Presenting with Field**: build a talk from slides written in Kotlin and HTML, with themes,
  builds and working material brought on screen. [Presenting with Field](presentation_studio.md).
- **Local AI**: connect Field's AI to a model server on your own machine, such as Qwen through
  llama.cpp. [Local AI](local_ai.md).
- **Layers**: put boxes on layers, switch between them in the layers panel, and give each layer
  its own layout. [Layers](layers.md).
- **GPU boids**: a flock written as a spec of kinds and rules.
  [Studio tools from MADP](chains.md#studio-tools-from-madp).
- Edits that change a value in place, or arrive while a document loads, are now saved.

<!-- changelog: 4108e858d5..0c2afd943c -->
### Interactive evolution, living strokes, and a livelier ALife page

*21 October 2026*

More of the MADP studio tools arrived as starting points: **GA**, breeding pictures from short
colour programs by clicking the ones you like, and **Line**, where each stroke you draw becomes a
moving, sounding object. [Studio tools from MADP](chains.md#studio-tools-from-madp). The
[ALife](alife_examples.md) page now leads with what each system does and shows a real Lenia
creature and animated agents, and the small languages are grouped in the site's navigation.

<!-- changelog: 251f8a08db..5f13b79225 -->
### WebRTC streams in and out of Field

*21 October 2026*

- **WebRTC**: publish a shader as a live stream, and receive anyone's stream as a texture, from
  Field or a browser, with or without a classroom server. [WebRTC Streams](webrtc.md).
- **`charGrid { }`**: a grid of glyphs in an output panel, driven by a program that runs every
  tick, from a plasma to Life to a typewriter.
- **StackSL feedback**: a StackSL program drawn against its own previous frame, and **Tetra**,
  pictures placed in a simplex by their CLIP embeddings.
  [Studio tools from MADP](chains.md#studio-tools-from-madp).
- Box notes render numbered lists and wrapped list items.

<!-- changelog: 57989c2cb6..59c4b645c8 -->
### ShaderToy-style shaders, particles and splats

*20–21 October 2026*

New starting points rebuilt from the MADP studio tools:

- **ShaderTrinket**: a ShaderToy-style `mainImage` in a box, recompiled with errors on their lines,
  with the webcam one line away.
- **Fern** and **Particles**: points that accumulate and fade, iterated function systems, and
  particles you paint into the window with the mouse, steered by four GLSL hooks.
- **Splat shader**: procedural clouds of splats, sorted by depth.

All are on [Studio tools from MADP](chains.md#studio-tools-from-madp). The `geometry { }` builder
draws in a studio look with ambient occlusion and exports GLB
([A studio look](three_output.md#a-studio-look)), and AI4 can build geometry with it.

<!-- changelog: 20a365f9cc..1b7f047dd4 -->
### The canvas as a score, and geometry from triangles

*20 October 2026*

- **Score**: a playhead sweeps the canvas, and each box draws and plays while the playhead is over
  it; drag a box to move it in time. [Score](agent_transcripts.md#madp_score).
- **Geometry**: a model as a recipe of triangles through a transform stack, drawn studio-lit and
  exported as GLB. [Geometry](agent_transcripts.md#madp_geometry).
- **The Tour** now starts with frames and FLines, and [Looking at Data](tour_data.md) was rebuilt
  around Field's own data tools.
- The Klecks integration is gone; [Patchy](patchy.md) is Field's painting editor.

<!-- changelog: c9ae6b8ef7..e02a168b2b -->
### New Tour stops: connecting boxes, GPU graphics and data

*20 October 2026*

- **The Tour** gains three stops: [Connecting boxes](tour_connecting_boxes.md),
  [Graphics on the GPU](tour_gpu_graphics.md) and [Looking at Data](tour_data.md).
- **The Macro Recorder** was rebuilt as a panel with run, rename, delete, step and copy, and it now
  records creating, deleting, connecting and disconnecting boxes.
  [The Macro Recorder](tour_interface.md#the-macro-recorder).
- The [sequence memory](temporal_memory.md) and [Evolving Creatures](evolving_creatures.md) pages
  gained a dance film and a video of an evolved crawler.

<!-- changelog: 195c2e040f..8767a5b642 -->
### Filming the canvas

*19–20 October 2026*

- **Filming the canvas**: fly through your bookmarks, or film the canvas frame by frame to an MP4,
  from a box. [Filming the canvas](filming_the_canvas.md).
- **Saving**: every persistent edit is now autosaved, not only edits to code files, and an
  external edit that Field overrides is kept in `backups/`.
  [When your edit loses](editing_from_outside.md#when-your-edit-loses).
- **Notation** can set frames, groups and styles from code and anchor a mark to a box.
  [Drawing from code](drawing_and_notation.md#drawing-from-code).
- **Sequence memory** continues a recorded dance, and the moving-around guide covers layers and
  Frame Selected. [Moving around the canvas](moving_around_the_canvas.md#framing-the-selection).

<!-- changelog: 63c3361d1f..5f674dab1e -->
### Continuing a gesture, and evolving creatures

*19 October 2026*

- **Sequence memory and forecasting**: remember recorded gestures, recombine them, and continue
  one live, or forecast it with Timestral. [Continuing a
  Gesture](temporal_memory.md).
- **Evolving Creatures**: breed swimmers on a water surface and crawlers on a floor, and watch them
  move in 3-D. [Evolving Creatures](evolving_creatures.md).
- **Flow from old film**: particle tracks become a dense flow, with the camera's weave removed.
  [Flow from old film](vector_field_topology.md#flow-from-old-film).

<!-- changelog: a4a8a50eb9..2c8d93c00a -->
### Mapper graphs, vector field topology and contour trees

*18–19 October 2026*

Three more topology tools, each with an explorer starting point:
[Mapper graphs](mapper.md) draw the shape of a point cloud (blobs, branches, loops) as a small graph, [vector field
topology](vector_field_topology.md) finds and classifies a flow's critical points, and
[merge and contour trees](contour_trees.md) record how a field's islands split and join as you slice it at different heights. Starting
points now link the pages that teach them, and the LP fan becomes a map you can edit
([The fan as a map](lp_curves.md#the-fan-as-a-map)).

<!-- changelog: d852caa660..c37e8a2719 -->
### Vectorizing line drawings, and tracking features through time

*18 October 2026*

Two more optional geometry extensions, both offered in first-run setup and on the welcome page:

- **Polyvector fields** turn a scanned sketch into curves that carry on through crossings.
  [Vectorizing line drawings](polyvector.md).
- **Feature tracking** follows the peaks and pits of a changing field from frame to frame and
  through scale, and reads video files as images. [Tracking features](topology_tracking.md).
- **Chaining them**: ridges to a forest of curves, strokes to smooth curves.
  [Chaining the geometry extensions](geometry_chains.md).
- **SolidCraft**: building a part from code is now part of the main page.
  [Build a part from code](solidcraft.md#build-a-part-from-code).

<!-- changelog: 91eda301f5..2e920c7fc0 -->
### Principal curves through point clouds

*18 October 2026*

A new optional extension fits smooth curves and trees through a point cloud or a photograph, with
width and uncertainty kept apart and stiffness you can adjust live. [Principal
curves](principal_curves.md). The 3-D linear-programming material joined the main page
([In three dimensions](lp_curves.md#in-three-dimensions)), example code was tidied on dozens of
pages, and an error from an AI4 tool no longer derails the rest of the conversation.

<!-- changelog: d366f9f0c5..677a140767 -->
### Morse–Smale complexes, and SolidCraft parts from code

*18 October 2026*

- **Morse–Smale complexes**: read an image as a landscape and get its peaks, pits, passes, ridges
  and basins as geometry; a persistence threshold keeps what matters, and you can adjust it live. [Morse–Smale
  complexes](morse_smale.md).
- **SolidCraft from Kotlin**: build designs with sketches, sweeps along rails, lofts and surfaces.
  [Build a part from code](solidcraft.md#build-a-part-from-code).
- **Linear programs in 3-D**: convex hulls of point sets as polygons and polytopes.
  [A polytope from a point cloud](lp_curves.md#a-polytope-from-a-point-cloud).
- **`solidParts`** takes a panel, so one assembly can be drawn in every panel of a sheet.

<!-- changelog: 8188411d48..3e19bb4fad -->
### Linear programs as curves, and editing a document from outside Field

*18 October 2026*

- **Linear programs as curves**: the routes the barrier, primal-dual, PDHG, ellipsoid and simplex
  methods take towards a polygon's optimum, drawn as curves, fans and animations. [Linear programs as curves](lp_curves.md).
- **Editing from outside**: find a box's files on disk, edit its code or shader in another editor,
  or add a box by writing a file, and see what happens when both sides edit at once.
  [Editing a document from outside Field](editing_from_outside.md).
- **Clojure boxes** get sliders and checkboxes, values from parent boxes, and errors marked on the
  line that raised them. [Sliders and checkboxes](clojure.md#sliders-and-checkboxes).

<!-- changelog: bf2f02f187..e2dd55fe95 -->
### Every file in the reference has an overview

*17–18 October 2026*

Source files across the [reference](reference/index.md) now open with an overview of what they
are for, and names in the descriptions link to their own entries. Example code on the
[FLine](fline_examples.md), [animation time](animation_time_model.md),
[Field probes](field_probes.md) and [spectral](spectral_examples.md) pages was rewritten for
reading, and the beat and pitch analysis figures are real captures.

<!-- changelog: fc0b1f849b..e1a43b84df -->
### Optimal transport, and Python boxes that behave like Kotlin ones

*17 October 2026*

- **Optimal transport**: match two sets one to one, move mass with Sinkhorn, and measure how far
  apart two distributions are. [Optimal Transport](optimal_transport.md).
- **Python boxes** mark an error on the line that raised it and get sliders and checkboxes with
  Python callbacks. [Python output](python.md#output).
- Painting, typography and sorting-network examples were rewritten with the new drawing helpers.

<!-- changelog: 577f3b058c..585f0059f5 -->
### A lattice Boltzmann fluid and a forest fire on the GPU

*17 October 2026*

Two new GPU simulations: a wind-tunnel fluid that leaves a vortex street behind a cylinder
([A fluid: lattice Boltzmann](afield_four_graphics.md#lattice-boltzmann)), and a forest fire with
four kinds of fire ([A forest fire](afield_four_graphics.md#forest-fire)). The
[Gaussian processes](gaussian_processes.md) page was rewritten.

<!-- changelog: 526785e237..997277db1e -->
### History Lens is now Code History

*16 October 2026*

- **Code History**: the per-line history zone in the editor has a new name, and its shortcut is
  `alt+shift+h`. [Code History](history_lens.md#open-it).
- **Kits run on first open**: a document made from a kit starting point runs its roles, in launch
  order, the first time it opens. [Kits and Starting Points](kits.md#publish-it-as-a-starting-point).
- **Corpus microscope**: a neighbour can be placed on the canvas as an audio-clip box.
  [Place a neighbour as a box](audio_ai_corpus_microscope.md#place-a-neighbour-as-a-box).
- **Shader templating** pulls in another property with `{% property "name" %}`; `{% include %}`
  still works. [Shader templating](afield_four_graphics.md#shader-templating).

<!-- changelog: 248d12d019..d75ee7bb44 -->
### Example code rewritten across the site

*16 October 2026*

The example boxes on many pages were rewritten to be shorter and easier to read, with Kotlin's
collection literals and background work that stops with the run that started it. The
[Functions](functions_examples.md), [polytope](polytope_examples.md),
[keyframer](keyframer_examples.md), [Manifold](manifold.md) and
[statistics](statistics_examples.md) pages changed most, and
[Paint settings, swept](paint_sweeps.md) was recaptured throughout. glTF texture transforms are now
applied ([glTF loading](fabricate_renderer.md#gltf-loading-and-animation)), and λ-term reduction
follows Lambdascope, so shared structure reads back correctly
([Reading back a shared answer](interaction_nets_examples.md#reading-back-a-shared-answer)).

<!-- changelog: 87816e0083..3706725663 -->
### Line notes for Kotlin boxes, and background work owned by its run

*16 October 2026*

- **Line notes** now appear in Kotlin, MorphoHDL and Nano boxes, as they do in Field's other
  languages. [Line notes](language_extensions.md#line-notes).
- **`runAsync`** starts background work whose callbacks stop when the box is stopped or re-run.
  [Execution reference](reference/execution.md).
- **Editing files outside Field**: external edits to a document's property files are applied to
  the open document. [The document on disk](document_on_disk.md).
- **First steps**: the live edit is now an assignment you run with Alt+Enter while the drawing
  moves. [First steps](first_steps.md).
- Docs prose is left-aligned and hyphenated, and the
  [StackSL conversation](stacksl_conversation.md) was re-recorded.

<!-- changelog: bce98fe0fc..24da74419a -->
### Timed OSC bundles, and MIDI into Pure Data

*16 October 2026*

- **OSC**: send timed bundles with `sendBundle` and `sendIn`. `osc(port)` says when another program
  already holds the port, and a box's handlers go away when the box is deleted.
  [Bundles and timetags](osc.md#bundles-and-timetags).
- **Pure Data**: `connectMidi` wires hardware MIDI ports to a patch.
  [Messages both ways](pure_data.md#messages-both-ways).
- **Faust**: a `button` is momentary, and `[scale:log]` and `[scale:exp]` set a slider's law.
  [The controls are the program's own](faust.md#the-controls-are-the-programs-own).
- **Clearer errors** in StackSL, Forth and Python boxes. Leave `cycles` out and a Sonic Pi loop is
  as long as the pattern takes to repeat. [Sonic Pi](sonic_pi.md#field-patterns-as-a-sonic-pi-sequencer).

<!-- changelog: 0e9535a9d2..2fc4963519 -->
### Document-wide reactive properties, and many rough edges fixed

*16 October 2026*

- **`rootPropUp`, `rootPropDown`, `rootPropUpDown`** make reactive slots for the whole document.
  [Properties that re-run boxes](reactive_properties.md#a-missing-value-runs-its-supplier).
- **Scrolling over output**: the mouse wheel pans and zooms the canvas through an output panel that
  does not scroll itself.
- **Keyframes**: Simplify moves lanes that share key times together, and saves keep Booleans and
  whole numbers. [Simplifying and smoothing a take](keyframe_editor.md#simplifying-and-smoothing-a-take).
- **Cloth** flattens visibly by default, and runs that diverge say so.
  [Physics](physics.md).

<!-- changelog: 762c0e27c9..f903419088 -->
### Motifs and discords: the matrix profile

*15 October 2026*

A new page on the matrix profile: find the repeated shapes (motifs) and the odd ones out (discords)
in a signal, compare two signals, work across several channels, and find what one set of
recordings has that another lacks. [Motifs, Discords and
Contrast](matrix_profile.md). Clojure boxes now name the symbol that failed to resolve
([When something throws](clojure.md#when-something-throws)).

<!-- changelog: cbc72fb25b..98531900ff -->
### Field's library reorganised by subject

*15 October 2026*

Field's Kotlin library now sits in packages grouped by subject, so an explicit import such as
`afield.paint.*` becomes `afield.graphics.paint.*`; every example on the site was updated. The
[reference](reference/index.md) follows the new layout, lists every source file, opens with a chart
of the code by area, and gains pages for [networking](reference/networking.md),
[lighting](reference/lighting.md) and [examples](reference/examples.md).

<!-- changelog: e28f6f7db8..be48790faa -->
### Properties that re-run boxes, and SolidCraft

*14 October 2026*

- **Reactive properties**: `propDown` reruns the boxes that read a value when it changes, and
  `propUp` runs a missing value's supplier first. Every `prop` form takes a default.
  [Properties that re-run boxes](reactive_properties.md).
- **Faster re-runs**: running an unchanged box again re-evaluates it instead of compiling it again.
- **SolidCraft**: an optional bridge to edit a CAD part's design and see the part in a box.
  [SolidCraft](solidcraft.md).

<!-- changelog: 0230cad876..082bef1e31 -->
### Running `go { }` blocks show on the box

*14 October 2026*

A box with `go { }` jobs still running shows a small badge with their count, and Alt+1 relaunches
just the part of an already-run box under the cursor.
[Calling suspend functions](running_code.md#calling-suspend-functions-with-go). The synth's
`delay()` effect is now `delayEffect()`, so `delay` inside `go { }` waits as expected. Shortcuts
across the docs are spelled ctrl/command and alt/option.

<!-- changelog: 842c543a84..c5b406f5e6 -->
### A live terminal in a box's output

*13 October 2026*

`terminal()` opens a real shell in a box's output panel. Box code can type into it and wait for a
program to start, print something or exit, so a command-line tool becomes something a box drives.
[A Terminal in a Box](terminal.md) walks through it.

<!-- changelog: 0bc084be0d..d57bd1d98b -->
### Patchy, a layered painting editor inside a box

*13 October 2026*

- **Patchy**: an optional layered painting editor that runs in a box's output panel and saves its
  paintings in the Field document. Your Kotlin code and the AI chat can paint on the same canvas
  you paint on by hand. [Patchy](patchy.md).
- **Traffic on the GPU**: the Biham–Middleton–Levine traffic model as a compute-shader lesson, and
  the DLA lesson now grows 300,000 walkers on a 1024×1024 grid.
  [Traffic](afield_four_graphics.md#bml-traffic).
- **`go { }`** calls suspend functions from a box, and stopping the box cancels them.
  [Calling suspend functions](running_code.md#calling-suspend-functions-with-go).

<!-- changelog: 539e1811f8..dccac5a999 -->
### Guides with several steps, and exact curve booleans

*13 October 2026*

- **Interactive Guides, rewritten**: the page now opens with a multi-step guide, then covers moving
  between steps and running code as a step opens or closes. Links in a guide's notes drive it on
  screen. [Interactive Guides](interactive_guides.md).
- **Exact booleans and offsets for `FLine`**: `booleanExact` cuts outlines where they cross and
  keeps the pieces of the curves you drew, and cubic offsets come with joins. [Exact booleans and offsets](fline_examples.md#exact-booleans-and-offsets).
- **Replaying a gesture as paint**: a take recorded on a gesture pad, or its edited keyframe lanes,
  can drive a brush, including tilt and twist. The
  [`gesture_paint_replay`](agent_transcripts.md#gesture_paint_replay) starting point shows it.
- **One workspace per checkout**: `./field.sh` remembers the last workspace for each checkout, not
  one for the whole machine. [Your workspace](installation.md#your-workspace-and-local-server).

<!-- changelog: 556b9c7ed4..2890aec646 -->
### Open Anything searches the documentation

*12 October 2026*

Open Anything (`cmd+p`) now finds documentation pages and starting-point documents as well as
the boxes in your own document. [The command palette and Open
Anything](tour_interface.md#the-command-palette-and-open-anything). The moving-hair brush learned
clumping, liquid bridges between hairs and a scattered splay, and dry bristles that ride the
paper's tooth; [Paint settings, swept](paint_sweeps.md#the-brush) shows each one. Field also moved
to GraalVM 25.4.

<!-- changelog: cc538321d0..0965a584db -->
### Measuring, trimming and crossing `FLine`s, and every paint setting swept

*12 October 2026*

- **Measuring along a line**: ask an `FLine` for the point at a distance and get its position,
  tangent, normal and curvature; trim or split by distance, and find where lines cross, including a single curve that
  crosses itself. [Measuring along a line](fline_examples.md#measuring-along-a-line),
  [Trimming and splitting](fline_examples.md#trimming-and-splitting-by-distance),
  [Crossings](fline_examples.md#crossings-bounds-and-orientation).
- **Paint settings, swept**: a new page that varies one paint, brush or drying setting at a time
  and shows what each does, next to an ordinary brush. [Paint settings, swept](paint_sweeps.md).
- The Electron window names the documentation tab **Documentation**.

<!-- changelog: 1200c54cd6..31839c5e3c -->
### A page on box output, and browsable trees and logs

*11–12 October 2026*

- **Box Output**: a new page on `print`, `display`, output channels and opening, closing and
  clearing panels. [Box Output](box_output.md).
- **`tree()`, `column()` and `logs()`** page through large collections, open data classes and
  arrays, flag cycles, and take a query in their filter box; each row can copy its path.
  [Trees and columns](visualizers.md#trees-and-columns).
- **FieldML** reads and writes reactive inherited properties.
  [FieldML cheatsheet](fieldml_cheatsheet.md).
- [AI documentation polish](ai_doc_polish.md) now shows the drafts card and the docs pass with
  captures.

<!-- changelog: 83ed3ea8ea..4e347c03a7 -->
### MorphoHDL leads with usage, and circuit search gets its own page

*11 October 2026*

The [MorphoHDL](morpho_examples.md) page now starts with how to write and run a circuit, and the
evolutionary circuit search moved to [Searching for MorphoHDL Circuits](morpho_search.md). Pages
across the site link their demo documents as starting points you can open, and the
[spatial geometry](spatial_geometry_examples.md), [ALife](alife_examples.md) and
[starting-point gallery](agent_transcripts.md) pages were tightened. In the Electron window, utility
tabs are capitalised and tinted.

<!-- changelog: 1aa4a08d3d..42664513f2 -->
### Sliders you can type into, and Code History under the line

*11 October 2026*

- **Controls**: sliders take fine Shift-drag, keyboard nudges, a reset, and typed values, with
  tick, default and zero guides; vec2 pads and colour readouts accept typed values too
  ([Handling a slider](visualizers.md#handling-a-slider)).
- **[Code History](history_lens.md)** shows what one line said in earlier saves, under the line in
  the editor, including its first value.
- **SolidSL fitting** uses L-BFGS throughout ([solid notation](solid_notation.md)).
- The [pattern rewriting](pattern_rewrite.md) and [language extensions](language_extensions.md)
  pages were rewritten, and many graphics, AI4 and beginner pages, among them
  [Your first five minutes](first_steps.md), now read more plainly.

<!-- changelog: 769a021d48..56255cf297 -->
### Tuning by Gaussian process

*10–11 October 2026*

- **[Tuning by Gaussian process](gaussian_process_optimization.md)** is a new page: Bayesian
  optimisation when each try is slow, and A/B preference learning when the judge is you.
- **More starting points**: nine more docs demos open from the Welcome page, whose thumbnails now
  expand on hover.
- The [Python](python.md) page was rewritten for users, and boxes can use the polytope, audio AI
  and Loris libraries without importing them.

<!-- changelog: c10cc7e227..776b707f29 -->
### Box imports that tidy themselves

*10 October 2026*

- **Box imports**: Format Kotlin now drops redundant and unused imports, and the editor folds the
  imports away.
- **Drawing with Gaussian processes** gained a closed loop with draggable pins and conditioning
  sliders ([gp_drawing](agent_transcripts.md#gp_drawing)).
- The [StackSL examples](stacksl_examples.md), [RectSL cheatsheet](rectsl_cheatsheet.md) and
  [RectSL inverse animation](rectsl_inverse_animation.md) pages were rewritten to lead with use.

<!-- changelog: 33db386312..3bbc9bdca6 -->
### Gaussian processes, SMPTE timecode, and staged projects

*9–10 October 2026*

- **[Drawing with Gaussian processes](gaussian_processes.md)** is a new page: pin a curve or a
  field at a few points and draw the ways it could pass between them.
- **[SMPTE Timecode](smpte_timecode.md)** is a new page: read and write LTC and MIDI Time Code,
  and run a clock from either.
- **[Start a staged project](staged_projects.md)** is a new page: a starting place for projects
  built in stages, where an early change makes later results stale.
- [A pattern in space](pat_spatial.md) now plays a head-tracked binaural render, and the
  mini-notation examples in [Patterns](pattern_language.md) play real Dirt samples.

<!-- changelog: ba08eaad51..a8d7c607d5 -->
### Kits, qualified sources, and properties that pull and push

*9 October 2026*

- **[Kits and Starting Points](kits.md)** is a new page: package reusable tools and named starting
  points, add them once in Welcome, and make documents from them.
- **[Change a rule without breaking what already works](qualified_sources.md)** is a new page: a
  rule changes only after a replay of the cases it already handles.
- **`propUp`, `propDown` and `propUpDown`** pull missing values upstream and push changes
  downstream, and box-to-box property reads can be
  [drawn as a layer](reactive_properties.md#seeing-who-reads-what).
- **[How Field Is Built](how_field_is_built.md)** is a public tour of the build machinery, and
  [stippling](agent_transcripts.md#stippling) places blue-noise dots that follow an image's tone.

<!-- changelog: 450af53694..e4ca1d89f9 -->
### Builds that AI4 can wait for

*9 October 2026*

- **[Let AI4 Wait for Long Builds](ai4_long_builds.md)** is a new page: AI4 starts a long derived
  build, ends its turn, and resumes when the result is ready.
- **Study builds** gained gates, per-attempt requests, dependencies across runners, resuming from
  an earlier attempt, and pruning ([Studies](studies.md#build-derived-results-without-blocking-edits)).
- Sixteen docs pages now link their demo as a starting point.

<!-- changelog: 4ae05cf74a..f9f8f93a4e -->
### Network MIDI, and phase noise in StackSL

*9 October 2026*

- **[Network MIDI](rtp_midi.md)** is a new page: Field joins AppleMIDI (RTP-MIDI) sessions to
  receive and send notes and controllers over the network.
- **[Phase noise](stacksl_cheatsheet.md#phase-noise)**: StackSL gained `qnoise`, `phnoise` and
  `phrot`, noise whose octaves you can turn by phase.
- AI4's tool descriptions were rewritten, so it reaches an answer in fewer steps.
- The [sorting networks](sorting_networks_examples.md), [dynamic graph](dynamic_graph_systems_examples.md)
  and [wave function collapse](wfc_examples.md) pages were rewritten, and sorting-network plots
  now use Plotly.

<!-- changelog: 8068e9a420..20dfd44ace -->
### Petri nets that throw sparks, and a state space as sculpture

*8–9 October 2026*

- **[A Petri net that throws sparks](chain_petri_sparks.md)** is a new chain page: each firing plays
  a drum sample and throws a burst of GPU particles.
- **[A Deadlock, Sculpted and Traced](chain_petri_sculpture.md)** is a new chain page: every
  reachable state of the dining philosophers becomes a solid, path traced in fog.
- **[Petri nets](petri_nets.md)** was restructured to start with music, then concurrency, drawing
  and limits.
- A box that was never saved is no longer deleted when its document's files change on disk.

<!-- changelog: c4df90f559..00db223586 -->
### Watercolour, and brushes with moving hairs

*8 October 2026*

- **[Watercolour and ink](watercolor.md)** is a new page: absorbent papers, wet-into-wet blooms
  and backruns, tilting the board, and pigments.
- **[Brushes with moving hairs](paint_gestures.md)** is a new page: timed gestures and brushes
  whose hairs move, with knives, rags and spatter in [Paint and pencil](paint_canvas.md).
- **[GPT jams ORCA](orca.md#gpt-jams-orca)**: AI4 plays the ORCA board while it runs.

<!-- changelog: 206262ae26..82195fc1d1 -->
### ORCA in a box, and a watchmaker for shaders

*8 October 2026*

- **[ORCA](orca.md)** is a new page: Hundred Rabbits' livecoding sequencer runs in a box's
  output and sends its notes to your Kotlin; two ORCAs can play a duet with Kotlin between them.
- **[The watchmaker](stacksl_evolve.md#the-watchmaker)**: a language model is shown the target
  and the best programs so far and proposes new ones, alongside a search you can steer and a
  gradient step on a program's numbers.
- **Loris** can now [label, align and morph two sounds](loris.md#label-align-and-morph-two-sounds).
- The [Faust](faust.md) and [Sonic Pi](sonic_pi.md) pages were rewritten more plainly.

<!-- changelog: 57577b1ce2..02f8b76358 -->
### Loris partials, and evolving a shader toward a picture

*7 October 2026*

- **[Loris: partial tracks and resynthesis](loris.md)** is a new page: analyse a sound into
  partials, edit them, and resynthesise, with playable studies.
- **[Evolving a Shader Toward a Picture](stacksl_evolve.md)** is a new page: genetic search over
  StackSL programs, scored by LPIPS perceptual distance.
- **Simplify and Smooth** clean up a dense keyframe curve, such as a shaky gesture take
  ([The keyframe editor](keyframe_editor.md#simplifying-and-smoothing-a-take)).

<!-- changelog: d65e3fadfe..eb239ee9c6 -->
### Reading saved AI4 chats

*7 October 2026*

- **[Reading Saved AI4 Chats](ai4_chat_archive.md)** is a new page: read a saved chat as a tree,
  apply lenses to it, let AI4 write lenses, and browse them in the lens viewer.
- **Meshes inside `moduleHere`**: `moduleHere { +Mesh("name") { ... } }` declares meshes in the
  module block.
- `prop<>` keeps the box's imports, and drag handlers can tell mouse buttons apart and report UVs.

<!-- changelog: 3369bdca7a..f489078115 -->
### Gesture takes become keyframes, and a faster Three.js viewer

*7 October 2026*

- **Gesture takes ↔ keyframe lanes**: bake a recorded gesture into linked x and y lanes and back
  ([The keyframe editor](keyframe_editor.md#from-a-performed-gesture)), with a
  [gesture_to_keyframes](agent_transcripts.md#gesture_to_keyframes) starting point.
- **Three.js geometry** now travels as binary buffers, and changing a style no longer resends the
  geometry.
- **LPIPS** perceptual image distance is available through ONNX Runtime.
- [A pattern in space](pat_spatial.md) was rewritten.

<!-- changelog: db7c99d0ae..2d08e56164 -->
### OSCQuery: controls other tools can find

*7 October 2026*

[OSCQuery](oscquery.md) is a new page: publish a box's parameters so other tools can find and
drive them, publish Field's own controls, and use another program's parameters. `showMesh(path)`
and `showMesh(file)` now take a renderer and say what they draw, and the
[How to make sound](sound_paths.md) page was rewritten.

<!-- changelog: 25d0c6c7f2..b55cadcb5f -->
### MQTT, and a page for talking to AI4

*6–7 October 2026*

- **[MQTT](mqtt.md)** is a new page: read sensor feeds, publish, run an embedded broker, and pass
  messages on to [OSC and DMX](mqtt.md#passing-messages-on-to-osc-and-dmx).
- **[Talking to AI4](voice_input.md)** documents push-to-talk voice input, which now restarts its
  worker after a timeout.
- `mesh(edges = true)` draws a solid's outline in the Three.js viewer, and the
  [polytope examples](polytope_examples.md) were rewritten.

<!-- changelog: 6c6f3f5639..9c2f6444f7 -->
### Linked lanes in the keyframe editor

*6 October 2026*

- **Linked lanes**: select a moment across several keyframe lanes and retime it together
  ([The keyframe editor](keyframe_editor.md#several-curves-at-once)).
- **The AI chat panel** folds each run of tool activity into one line.
- `sourceBlock`, code that keeps its own source, is documented in
  [Running code in a box](running_code.md#sourceblock-and-interpolant-code-that-keeps-its-own-source).
- The [geometry from disk](geometry_from_disk.md) and [solid notation](solid_notation.md) pages
  were rewritten to lead with the common case.

<!-- changelog: 242fd57b7d..472fcd8db5 -->
### Your own key types in the keyframe editor, and a guided first run

*6 October 2026*

- **Keyframe editing**: undo and redo, typed values, copy and paste, nudging, a library of 50 key
  types, and [your own key types](keyframe_editor.md#your-own-key-types) with
  `interpolant("name") { }`, whose shapes follow a drag live.
- **First-run setup** walks through prerequisites with checkboxes for optional features, and
  installs them through winget or pacman where it can ([Installation](installation.md)).
- **[Let AI4 drive a live webpage](ai4_live_iframe.md)** is a new page: a page in a box's output
  that you and AI4 work on together.
- The [transcript gallery](agent_transcripts.md) and [chain pages](chains.md) show visual
  previews, and the Three.js viewer can play a looping shot with explode and X-ray moves.

<!-- changelog: 6bcee2fa42..96c302a91c -->
### The keyframe editor

*6 October 2026*

[The keyframe editor](keyframe_editor.md) is a new page, with videos and a starting point: draw a
value over time, edit the curve, simplify a performed take, and react to edits from code. Edited
curves are now saved, and the panel is named after its curve. Kotlin completion no longer stalls
the connection to the browser, and new editor commands rerun the enclosing block or just a number
([Running code in a box](running_code.md#running-code-around-the-cursor)).

<!-- changelog: 545f13a01c..e2e90c0c49 -->
### Sequencer controls, and a gesture pad that records

*5 October 2026*

- **Sequencer controls**: Max-style multisliders, matrices, range sliders and menus, straight or
  polar, with playheads you can script
  ([Visualizers](visualizers.md#sequencer-controls-multisliders-grids-ranges-and-menus)); the
  [phone controller](mobile_controllers.md) draws them too.
- **`gesture(...)`** is an XY pad that records a take the box replays
  ([Recording a gesture](visualizers.md#recording-a-gesture)).
- Manifold no longer frees a native object while a call is using it.

<!-- changelog: 8a59dafea2..3c47011eb9 -->
### More canvas panes, and AI4's saved states

*5 October 2026*

- **Split canvas**: up to three panes per axis, panes you can
  [unlink](moving_around_the_canvas.md#unlinking-a-pane), and thumbnails in the palette.
- **AI4 saved states**: browse a document's saved states beside the conversation, refer to them in
  chat, and fork them by name ([AI features](aiFeatures.md#browsing-saved-states)); Mermaid
  diagrams in replies preview inline.
- **`flipbox`** switches a block on and off with a checkbox in the editor, and `aside { }` holds
  code an ordinary run skips ([Running code in a box](running_code.md#flipbox-a-block-with-a-checkbox)).

<!-- changelog: e7b57d4ebb..ae858c8d70 -->
### Turntables and linked cameras in the Three.js viewer

*5 October 2026*

- **Three.js viewer**: `turntable()` turns a view for a display, and
  [linked cameras](three_output.md#compare-views-with-linked-cameras) move two views together;
  translucent meshes no longer hide what is behind them.
- **[Subharmonic Synthesis](subharmonic_sound.md#one-input-many-voices)** gained tracked phase
  banks and harmonic analysis.
- The [TetGen](tetgen.md) page was rewritten to lead with meshing, sizing and Kratos, and
  `OneEuroFilter` takes an optional sample rate for offline use.

<!-- changelog: a7edf1ef8b..931ac5e944 -->
### Split the canvas, and your first five minutes

*4–5 October 2026*

- **Split canvas panes**, spreadsheet-style: each pane shows its own part of the document, with
  draggable dividers, the drawing layer in every pane, and boxes that drag across a divider
  ([Moving around the canvas](moving_around_the_canvas.md#splitting-the-canvas)).
- **[Your first five minutes](first_steps.md)** is a new beginner page, starting from the moving
  drawing on the Welcome page, and **[Your code, AI and local history](privacy.md)** says what
  stays on your machine.
- **[Video particles](particle_video.md)** turns a movie into trajectories you can inspect as
  polylines.
- A port that is already in use now tells you how to choose another.

<!-- changelog: 5dac2dd4ab..0f4fba5aae -->
### Reset View, and Field remembers your last document

*4 October 2026*

- **Reset View** (Alt+H) zooms back to 100% and closes every output panel
  ([Moving around the canvas](moving_around_the_canvas.md#panning-and-zooming)).
- Field remembers the last document you opened in each workspace, and new commands control
  whether the editor opens automatically ([Keyboard shortcuts](keybindings.md#canvas)).
- Panning the canvas no longer stops when it crosses an output area, and completion lists package
  paths you have not imported yet.
- [Installation](installation.md) now covers optional first-run Kotlin setup.

<!-- changelog: b9d2a6f52f..a6615b74b5 -->
### Kotlin extensions per project, and output pages that call their box

*4 October 2026*

- **Project-owned Kotlin extensions**: Kotlin you reuse across documents can live in your own
  project, set with `AFIELD_EXTENSION_DIRS`, and boxes can import it
  ([Installation](installation.md#project-owned-kotlin-extensions)).
- **[Connecting an output iframe to its box](output_requests.md)** is a new page: a page in a
  box's output can ask the box's Kotlin to do something with `outputRequests`.
- **AI4 chat** has larger text, shows a live status while a turn waits, and messages you send
  while a turn runs reach that turn. AI4 can
  [inspect your geometry notes](geometry_review.md#ask-ai4-to-inspect-your-notes).
- Output panels shrink back when their content does, and a box can call a local function as a
  statement again.

<!-- changelog: 294a16416e..cf3c41f657 -->
### AI4 paints, and experiments you can branch and rewind

*4 October 2026*

- **[AI4 Paints](ai4_painting.md)** is a new page: ask for a painting, browse the saved ones, and
  keep the brushes a conversation made, with an `ai4_paint_studio` starting point.
- **[Experimenting with AI4](ai4_experiments.md)** is a new page: write a tool mid-conversation,
  [branch an experiment](ai4_experiments.md#branching-an-experiment), and see what AI4 was
  told. AI4 conversations can now be rewound, edited and resent, and a document's chat is saved
  beside it and resumed after a restart.
- **Tool programs**: one call can run a small program over the other tools, shown as a trace in
  the chat panel; `agentExtension { }` adds live-coded hooks
  ([AI features](aiFeatures.md#more-than-a-tool-agentextension)).
- AI4 chat renders Markdown and sends on Enter.

<!-- changelog: 4870010641..b2395cd0fd -->
### Talking to AI4, and stipple transport

*4 October 2026*

- **Push-to-talk**: hold a key and speak to AI4; a local Whisper model transcribes into the chat,
  on macOS, Linux and Windows ([Talking to AI4](voice_input.md)).
- **[Stipple transport](stipple_transport.md)** is a new page: a learned velocity field moves a
  sheet of particles into a stippled image, and can carry a grid or a curve along with them.
- Electron render-target views now work on Linux under native Wayland.

<!-- changelog: 104ed4161a..43ceb2be6f -->
### Values that outlive a run

*3 October 2026*

`prop`, `once` and `cached` now have their own section in
[Running code in a box](running_code.md#prop-once-and-cached-values-that-outlive-a-run):
a typed slot that children inherit, a slot filled the first time, and `cached<T>(keys) { }`, one
value per box for each set of keys. The [LoRA generation](audio_ai_underfit_mancini.md) page now
plays real audio beside a matched baseline, and the [physics](physics.md) and
[interpolants](interpolants_examples.md) pages read more plainly.

<!-- changelog: cc7d3c85e1..71fd12c054 -->
### Computer vision in Python, subharmonics, and graphics in an output area

*3 October 2026*

- **[Computer vision in Python](python_cv.md)** is a new page: `fieldcv` brings OpenCV and
  mediapipe tracking to Python boxes, sharing numpy memory with Field, with a webcam starting point.
- **[Subharmonic Synthesis](subharmonic_sound.md)** is a new page: add a tone a fraction below a
  line that is already playing, with three pitch trackers to choose from.
- **[Graphics in an Output Area](four_in_output_areas.md)**: a render target can draw straight
  into a box's output panel in the Electron app.
- The [Three.js output](three_output.md), [output panel sizing](output_panel_sizing.md) and
  [chart](chart.md) pages were rewritten to lead with use.

<!-- changelog: 6b44baf5e4..25a1f70c57 -->
### Drawn strokes become paint, and frames shared over Syphon

*3 October 2026*

- **[Drawn Strokes Become Paint](chain_drawn_paint.md)** is a new chain page: pen strokes on the
  drawing layer, with their pressure, become oil paint. `notation` now reads and writes per-point
  pen pressure.
- **[Syphon](syphon.md)** is a new page: publish and receive GPU frames with Resolume,
  TouchDesigner, OBS and other Mac apps, with zero-copy receive.
- **[Examples that cross several parts of Field](chains.md)** is a new overview of the chain pages.
- [Snapping](moving_around_the_canvas.md#snapping) gained a palette toggle and row-aware even
  spacing.

<!-- changelog: 93b2ffaf76..f4324b1bcc -->
### A layers panel, Frame Selected, and streaming diffusion

*3 October 2026*

- **Canvas navigation**: a [layers panel](moving_around_the_canvas.md#showing-and-hiding-layers)
  (Alt+L), [Frame Selected](moving_around_the_canvas.md#framing-the-selection) (Alt+F), and
  [bookmarks](moving_around_the_canvas.md#bookmarks) that keep panel sizes and zoom and draw
  minimaps.
- **[Streaming diffusion](streaming_diffusion.md)** is a new page: restyle a rendered texture with
  a text prompt, keeping the images on the GPU.
- **`wiggledBy`** wiggles one line along another, in 2D and 3D
  ([FLine examples](fline_examples.md#wiggling-one-line-along-another-wiggledby)).
- New workspaces no longer get a README copied into them.

<!-- changelog: ee9b802a56..cb2d48275b -->
### Paint and pencil from FLines, and neural image rendering

*2 October 2026*

- **[Paint and pencil from FLines](paint_canvas.md)** is a new page: `PaintCanvas` turns any FLine
  into simulated oil paint, a pencil line, hatching or stippling.
- **[Neural image rendering with OpenDLSS-NR](open_dlss_nr.md)** is a new page: draw into a
  texture, run a network over it and grade the result.
- **`forever { }`** now resolves in a box without colliding with the renderer's own update loop.
- The [FLine examples](fline_examples.md) introduce each signature before its code, and several
  chain pages were rewritten more plainly.

<!-- changelog: 0f5bd5daf1..ecfeec1440 -->
### Csound boxes, and a bookmarks panel

*1–2 October 2026*

- **[Csound](csound.md)** is a new page: `.csd` boxes run with Alt-Enter, with completion and hover
  from the manual, highlighting, line notes, offline render and MIDI.
- **[A Drawn Morph, Heard](chain_drawn_morph.md)** is a new chain page: morph a drawn circle into a
  heart and hear its size as a tune, using the new `matchPoints` point matching.
- **Bookmarks** remember output panels, and a bookmarks panel opens with Alt+B
  ([Moving around the canvas](moving_around_the_canvas.md#the-bookmarks-panel)).
- The [Sound](sound.md) page was rewritten to lead with its two voices.

<!-- changelog: 3426fa5c76..5ced8a8177 -->
### Two-key keyboard chords

*1 October 2026*

- **Chords**: a shortcut can now be two keys in sequence, such as Cmd+K Cmd+S
  ([Keyboard shortcuts](keybindings.md#writing-a-two-key-chord)).
- **`pulseTrain`, `ease` and `tween`** can now be bounded and differentiated
  ([Proving a Function's Range](fn_intervals.md#the-waveform-generators)).
- The [Tour](funFeatures.md) and the [Functions gallery](functions_examples.md) now show real
  captures, and the sound-path comparison plays real samples
  ([How to make sound](sound_paths.md)).
- Electron tabs are named after the page's file.

<!-- changelog: 981de14ad7..a1e0aebc43 -->
### Proving a function's range, and a reorganised site

*1 October 2026*

- **[Proving a Function's Range](fn_intervals.md)** and
  **[Proving What a Shader Draws](stacksl_intervals.md)** are new pages: interval arithmetic gives
  guaranteed bounds for a function, and for every pixel in a rectangle of a StackSL program.
- **The site was regrouped**: [Moving around the canvas](moving_around_the_canvas.md) absorbs
  spatial navigation, the [profiling timeline](profiling_timeline.md) absorbs simulation time and
  frame budgets, and timeseries examples join the [signal examples](spectral_examples.md).
- Repository source paths in the docs link to GitHub, or open from your checkout in Field's docs
  pane, and inline code is highlighted like the reference tables.

<!-- changelog: bdc8b1e43e..40255a5574 -->
### Filled paths on the GPU with Slug

*1 October 2026*

- **[Slug filled paths](slug_paths.md)** is a new page: a filled `FLine` rendered as analytic
  curve coverage on the GPU, with a starter lesson.
- **The canonical graphics lessons** are now a searchable starting point.
- **`capture="ui"` fences** can set the window size and clip to a rectangle; the
  [Survival guide](survivalGuide.md) and [welcome page](installation.md#the-welcome-page) now show
  real captures.
- The [animation time model](animation_time_model.md) and [history overview](history_overview.md)
  were rewritten.

## September 2026

<!-- changelog: 915f16017b..af650c7e6e -->
### Lua boxes, and GPU schemas

*30 September 2026*

- **[Lua in a Box](lua.md)** is a new page: Lua runs in a box through LuaJIT, with completion that
  carries signatures, sliders and checkboxes that call back into the box's Lua, and sound through
  the pattern voice.
- **[GPU schemas](gpu_schema.md)** let one Kotlin data class define a memory layout shared by the
  CPU and the GPU, with GLSL structs and buffer-reference views generated for you.
- Kotlin formatting and completion work again under Kotlin 2.5.

<!-- changelog: 07f4417ecc..8d6f62bba6 -->
### Studies, and asking why a value resolves where it does

*30 September 2026*

- **[Studies](studies.md)** is a new page: develop something that draws and keep a history of each
  accepted change, with a [studies_rings](agent_transcripts.md#studies_rings) starting point.
- **[Why does this value resolve here?](why.md)**: `why.speed` shows which box supplied an
  inherited value, the path to it, and any ties or shadowed ancestors.
- **Notes on the docs**: when you read these pages inside Field you can leave a note on any
  sentence, and notes are worked through page by page ([Notes on the Documentation](docs_notes.md)).
- AI4 can now look at what its boxes show ([AI4's built-in tools](ai4_tools.md#reading-and-looking-at-output)).

<!-- changelog: 30b4114213..7b6af3b960 -->
### Box code on Kotlin 2.5

*30 September 2026*

Field and its box compiler moved to Kotlin 2.5.0-Beta1; `val (a, _) = p` now compiles in a box and
evaluation errors are reported. Spreadsheet formulas compile again and handle empty ranges
correctly, polytope faces and centroids read without index plumbing, and the StackSL WebGL2
preview accepts programs that use `precise`. The [Three.js viewer](three_output.md#optional-viewer-tools)
groups its parts and review controls.

<!-- changelog: 9aba44e019..818686bff1 -->
### A drawn score you can hear, and pressure membranes

*29 September 2026*

- **[A Drawn Score, Heard](chain_drawn_score.md)** is a new chain page: rectangles, a pen stroke
  and a level line on the canvas become a pattern.
- **[Pressure membranes](pressure_membranes.md)**: relax a closed mesh as a pressurised membrane
  with reinforcement, tethers and supports.
- **AI4 in the chat panel** gained box-graph and execution tools on every chat path, and can test
  a candidate edit before applying it ([AI4's built-in tools](ai4_tools.md)).
- Field now exits on Ctrl-C or `kill -INT` even when launched in the background, saving pending
  edits first. KosmicKrisp is now the default Vulkan driver on Apple-silicon Macs running macOS 26.

<!-- changelog: a7f3f884f3..498a59249e -->
### Tools that boxes give the AI, and shader reloads that keep their state

*29 September 2026*

- **Box-authored AI tools**: `aiTool { }` registers a tool the model can call, owned by the run
  that registered it ([Give the AI a tool of your own](aiFeatures.md#give-the-ai-a-tool-of-your-own)).
- **Stopping a box** now releases what is running while keys and controls stay.
- **Shader reloads** compare the old and new interfaces, carry values across by name, and say what
  changed where you are looking.
- StackSL and RectSL studies share one set of tools for comparing and sweeping variants
  ([Studies](studies.md)).

<!-- changelog: d1d8d93c6f..5501e97289 -->
### A drawing layer over the canvas, and notation that box code reads

*29 September 2026*

- **[Drawing and Notation](drawing_and_notation.md)** is a new page: an Excalidraw layer over the
  canvas that box code can read, write and react to, and read by
  [intersection](drawing_and_notation.md#reading-by-intersection) — a vertical line is an instant, a
  horizontal one a pitch.
- **Canvas zoom**: the canvas now zooms, and the drawing layer and background dots follow
  ([Moving around the canvas](moving_around_the_canvas.md#panning-and-zooming)).
- **[Point to geometry, keep the context](geometry_review.md)**: attach a note to a point, face or
  region in the Three.js viewer.
- **[Talking to the StackSL in your shader](stackauto_conversation.md)**: fill a `stack.auto` slot
  in your own GLSL by talking to AI4.
- Inherited values now resolve through one rule everywhere, including the editor hover.

<!-- changelog: 11b5671056..0ded8d0851 -->
### Pure Data patches, and line notes in every language

*28 September 2026*

- **[Pure Data](pure_data.md)** is a new page: Field runs Pd patches through libpd, marks an object
  Pd could not create in red, suggests fixes for typos, and lets Kotlin send to a Pd box.
- **[Line notes](language_extensions.md#line-notes)** show what a line evaluated to in Python,
  Forth, Pd and other languages.
- **[A RectSL scene on a light rig](chain_scene_to_light.md)** is a new chain page: an animation
  played on an LED matrix and read back.
- `stack.auto` slots can be shown and studied inside your own shader, and a box is now stopped
  before it is deleted or replaced.

<!-- changelog: b7ed40d016..397228b1e4 -->
### Server-side completion for StackSL and RectSL, and ray-traced geometry you compute

*27 September 2026*

- **Completion and line notes** for StackSL and RectSL now come from the compiler's whole
  vocabulary, including StackSL written inside Kotlin.
- **Study cards** use Field's sliders, named by what they change, with keys to capture and blend
  settings ([Talking a shader into existence](stacksl_conversation.md)); a `stack.auto` slot's
  numbers live on the GPU with sliders on the box.
- New NVIDIA pages: [compute your ray-traced geometry](computed_ray_geometry.md),
  [learn from a shader on the GPU](gpu_shader_learning.md), and
  [Ray Reconstruction for the path tracer](path_traced_reconstruction.md).
- The AI chat panel was restyled, with highlighted inline code.

<!-- changelog: eeea189dc8..6887fde2eb -->
### Driving lights over Art-Net and sACN, and a page on OSC

*27 September 2026*

- **[Driving Lights: Art-Net and sACN](dmx_lighting.md)** is a new page: a box can drive DMX
  fixtures over the network, or act as a lighting node itself, with a fixture-aware patch that
  draws itself.
- **[OSC and TUIO](osc.md)** is a new page, with a fader that drives a drawing. `{a,b}`
  alternatives in an address now match, TUIO blobs and bundle timetags decode correctly, and an
  exception in a handler now shows up in its box.

<!-- changelog: 307518948c..1b25b7e013 -->
### Talking a shader, or a scene, into existence

*26–27 September 2026*

- **[Talking a shader into existence](stacksl_conversation.md)** and
  **[Talking a scene into existence](rectsl_conversation.md)** are new pages: describe a picture or
  an animation in AI4 chat, the model writes StackSL or RectSL in a box, and a window draws it
  live while you talk.
- GPU buffers now outlive the frames that use them, fixing a crash when a box freed a buffer
  mid-frame. When the renderer stops the boxes that draw into it, it offers to
  [relaunch them](afield_four_graphics.md#stopping-the-renderer).
- The [StackSL gallery](stacksl_examples.md#rendered-gallery) images now have correct colour.

<!-- changelog: 5adda3e43c..c9e1ca48bc -->
### Optical flow on every Vulkan device

*26 September 2026*

- **Portable [optical flow](optical_flow.md#which-backend-runs-where)**: a pyramidal Lucas–Kanade
  backend runs on any Vulkan device, so the optical-flow starting points open on macOS too, with
  an optional forward–backward consistency map.
- **[A function, played and read back](chain_fn_sound_roundtrip.md)** is a new chain page: play a
  curve as loudness and as pitch, analyse the recording, and compare.
- Stopping a renderer now stops and waits for the boxes that depend on it before freeing the GPU.

<!-- changelog: d2de509728..09db63803e -->
### Neural reconstruction, ray-traced direct lighting and learned shaders

*25–26 September 2026*

A set of new graphics pages for NVIDIA hardware and beyond:
[Direct lighting](direct_lighting.md) with RTXDI area lights you can move while Field keeps
drawing, [neural reconstruction](neural_reconstruction.md) with DLAA and DLSS Super Resolution,
[ray reconstruction](ray_reconstruction.md) of the RTXDI samples, and
[how to wire them into a render graph](reconstruction_plumbing.md); [optical flow](optical_flow.md), and
[a learned shader function](learned_shader.md) that trains a small network and runs it per pixel.
The [ray tracing examples](ray_tracing_examples.md) were rewritten, Slang shaders are highlighted,
and many more figures gained descriptive alt text.

<!-- changelog: d72363a900..9b1b4e371c -->
### Builds and docs that work offline

*24–25 September 2026*

- **`./field.sh --offline`** builds from the dependency cache; LWJGL is pinned to a release, so a
  cached build no longer stalls looking for updates ([Installation](installation.md#clone-and-start)).
- **The docs load with no network**: fonts and the code highlighter now ship with Field.
- **Reference tables explain themselves**: across 31 guides, each entry now pairs a highlighted
  example with a sentence on what it does, among them the [RectSL](rectsl_cheatsheet.md),
  [StackSL](stacksl_cheatsheet.md), [FieldML](fieldml_cheatsheet.md) and
  [Petri net](petri_cheatsheet.md) cheatsheets and the [linear algebra API](linear_algebra_api.md).

<!-- changelog: 2011dd79e1..c2c1ced324 -->
### One viewer for functions, sounds and signals

*23 September 2026*

- **A single time-axis viewer** now draws `Fn.plot`, recorded audio and analysis views, with
  device-pixel bins, waveforms up close, a WebGL piano roll for large note counts and optional
  synchronised zooming ([Visualizers](visualizers.md#one-viewer-for-functions-sounds-and-signals)).
- **Certified bounds**: a function's plot can show a guaranteed band behind its sampled envelope,
  computed with interval arithmetic ([Proving a Function's Range](fn_intervals.md#seeing-the-proof)).

<!-- changelog: bfdf6e0658..2d99766407 -->
### Dual quaternion skinning on the GPU, and a twisted limb weighed

*23 September 2026*

- **GPU dual quaternion skinning** can be chosen per instance and avoids the "candy wrapper"
  collapse of linear blending; [skinned_characters](agent_transcripts.md#skinned_characters) shows
  it in one draw call.
- **[A twisted limb, weighed and traced](chain_skinned_volume.md)** is a new chain page measuring
  that collapse as lost volume.
- Saved function conversations show their plots, with collapsible tool traces.

<!-- changelog: 948602d5a9..891b734363 -->
### Talking a function into existence

*22–23 September 2026*

[Talking a function into existence](function_conversation.md) is a new page: a workbench where
you describe in AI4 chat how a set of signals should move, and each accepted change becomes
ordinary Kotlin in a box, with a saved revision you can return to.
AI4 box conversations are now saved with the document and
[resume after a restart](function_conversation.md#the-saved-conversations). Figures across many
pages, including [Visualizers](visualizers.md) and [Petri nets](petri_nets.md), gained
descriptive alt text.

<!-- changelog: bec29b99d6..92963ada0a -->
### Flow grain, and GLSL that is highlighted

*21–22 September 2026*

- **[Flow grain and motion](flow_grain.md)** is a new playground: grain and strokes driven by a
  resident world and motion history for the camera and objects.
- **GLSL fences** in the docs now use a real GLSL grammar.
- **A compute-shader probe bench**, [gpu_probe_bench](agent_transcripts.md#gpu_probe_bench), checks
  a GPU pressure-projection chain cell by cell against a closed form.
- The [ray tracing examples](ray_tracing_examples.md) now match the code that renders them.

<!-- changelog: 2970b47a0b..ccb709dede -->
### Which clock Marf reads, and what a capture can show

*21 September 2026*

- **Field clocks for `marf`**: the default `AUTO` clock and when to choose another are now
  described in [A pattern as a function generator](marf.md#which-clock-the-fields-are-read-in).
- **[What a capture can and cannot show](capture_limits.md)** is a new page on how a capture of
  a visual example can fail, loudly or quietly, and how to tell which happened.
- The band labels on the [pattern particles](chain_pattern_particles.md) chain now mark the
  real centres.

<!-- changelog: 433856b322..b91ee7948f -->
### A λ-term you can hear

*20–21 September 2026*

- **[A λ-term you can hear](chain_lambda_sound.md)** is a new chain page: it reduces a λ-term step
  by step on an interaction net and turns each reaction into a rhythm.
- **GPU skinning**: rigged characters now deform in the vertex shader, so a crowd of 64 renders
  live; see [skinned_characters](agent_transcripts.md#skinned_characters).
- **Between two trained fields**: [Neural particle systems](neural_particles.md#3-between-two-trained-fields)
  now interpolates two trained fields to make a letterform in between.
- A runnable [marf_demo](agent_transcripts.md#marf_demo) document opens the function generator.

<!-- changelog: cbc997c7ed..1856f9311b -->
### Neural particle systems, and patterns as function generators

*19–20 September 2026*

- **[Neural particle systems](neural_particles.md)** is a new guide: a small network learns to move
  300,000 particles into a picture you give it, with a neighbour grid, boids, and a learned
  interaction between particles.
- **[A pattern as a function generator](marf.md)** introduces `marf(...)`, modelled on the Buchla
  248: patterns drive sequences, envelopes, LFOs and trajectories through banks of stored values.

<!-- changelog: e590073b54..4ef3d3aaf7 -->
### Skinned characters, and Kolakoski rhythms

*19 September 2026*

- **Skeletal animation**: bones and weights read from a glTF, a joint palette evaluated at a time,
  and linear-blend and dual-quaternion skinning. Open
  [skinned_characters](agent_transcripts.md#skinned_characters) and drag `time` to bend the rig; the
  path tracer can now render a skinned glTF too.
- **Kolakoski counts, Euclid places**: a self-describing rhythm sets how many hits land, a
  Euclidean one sets where, in [Patterns](pattern_language.md#counting-with-one-placing-with-the-other).
- The welcome document every workspace starts with now describes Field correctly and points at
  documentation that exists.

<!-- changelog: c447ad934a..c5716c9cb8 -->
### FieldML stage by stage

*18–19 September 2026*

[FieldML stage by stage](fieldml_stages.md) is a new page that follows one program from tokens and
syntax tree through types and evaluation to its value, showing where errors and completion
suggestions come from. Sixteen more demo documents became starting points you can open from the
[transcript gallery](agent_transcripts.md), including the
[interaction-net λ-calculus demo](agent_transcripts.md#interaction_nets_lambda).

<!-- changelog: 30562d7166..6fdb9f46d0 -->
### A photograph made into a printable solid

*16–18 September 2026*

- **[A photograph, closed and mounted](chain_photo_to_solid.md)** is a new chain page: depth
  predicted from one photo becomes a closed, mounted object.
- **Fitting a StackSL program**: tune a program's numeric literals until it matches a picture, in
  [StackSL examples](stacksl_examples.md#fitting-a-programs-literals).
- **λ-terms on interaction nets**: a lambda front end parses a term and reduces it on a net, in
  [Interaction Nets examples](interaction_nets_examples.md).
- **A summarised quadtree** over a raster, as one GPU pass chain:
  [four_summary_pyramid](agent_transcripts.md#four_summary_pyramid).

<!-- changelog: 4bcec7ecdf..6d266dfe0f -->
### A pattern placed in space, and keyframes swept overnight

*16 September 2026*

- **[A pattern in space](pat_spatial.md)** is a new page: `patSpatial` tags each event with a
  direction and distance and Steam Audio places it around your head; the listener can
  [follow a tracked head](pat_spatial.md#following-a-head).
- **[Sweeping saved keyframes overnight](keyframe_autosweep.md)** is a new page: a directory of
  keys goes in, an archive of renders comes out.
- **A GPU-driven virtual texture** shows a whole directory of images as one wall:
  [mega_texture_wall](agent_transcripts.md#mega_texture_wall).

<!-- changelog: 995406f0ad..defc94d7ff -->
### Spatial audio with no device, and GPU simulations as pass chains

*14–16 September 2026*

- **Offline spatial audio**: OpenAL can now run in isolated contexts and render spatial sound
  with no audio device, described in
  [How to make sound](sound_paths.md#spatial-audio-with-openal-and-rendering-with-no-device).
- **Persistent passes**: a pass chain can keep state between frames, enough for a stable fluid you
  paint into and a jump-flood distance field
  ([four_simulation_chain](agent_transcripts.md#four_simulation_chain)).
- **The [RectSL cheatsheet](rectsl_cheatsheet.md)** now highlights its fences and leads every
  section with a worked snippet.
- The [codebase reference](reference/patterns.md) now documents the pattern clock and what
  schedules against it.

<!-- changelog: a6e2d246ae..90139464fe -->
### Steam Audio in Field

*14 September 2026*

Steam Audio 4.8.1 is now bound directly from Kotlin, with the left–right handedness of 2D sources
corrected, so binaural placement is available to box code; it is the engine behind
[A pattern in space](pat_spatial.md). The [Languages reference](reference/languages.md) gained
file overviews for Datalog, morpho and the FieldML front end.

<!-- changelog: 814b2a4faa..ba04c45d0c -->
### Walls for scripted alife agents, and a polytope sliced into layers

*14 September 2026*

- **Walls**: agents with their own programs now live in a world with obstacles, swept collision
  and routed seeking, in [Spatial artificial life](alife_examples.md#hitting-a-wall).
- **[A polytope, canonicalised, and sliced into layers](chain_ceres_manifold_contours.md)** is a
  new chain page: a Conway polytope made canonical and cut into a printable layer stack.
- Plot tick labels no longer rotate, and the [solid notation](solid_notation.md) page was
  rewritten to lead with what it does.

<!-- changelog: 3bf15b6dce..2fe5b17b62 -->
### Springs, flow matching, and Clojure boxes

*12–13 September 2026*

- **[Springs, dampers, and a character with mass](movement_examples.md)**: one-line spring
  functions and a steerable movement model, with a playground.
- **[Flow Matching](flow_matching.md)** fits a velocity field that carries one point cloud toward
  another.
- **[Clojure in a box](clojure.md)** documents Clojure boxes, now with highlighting and ranked
  completion.
- **A participating medium** for the path tracer, with your own phase function:
  [Progressive path tracing](path_tracing.md#a-participating-medium).
- **Polyphonic Faust**: a pattern can play a Faust voice at several voices at once
  ([Faust](faust.md#a-pattern-plays-it)).
- New chain pages: [three-dimensional Life](chain_life3d_render.md) and
  [a RectSL animation as a solid](chain_rectsl_spacetime.md); new audio pages on
  [MuScriptor transcription](audio_ai_muscriptor.md) and
  [generating with a LoRA](audio_ai_underfit_mancini.md); and an
  [aperture playground](aperture_playground.md).

<!-- changelog: c339e8cbe2..4e01fe639b -->
### Chains across subsystems

*11–12 September 2026*

The first pages that follow one piece of work through several parts of Field, each hop
checked:

- [A polytope, cut open and traced](chain_polytope_render.md): Conway polytope, Manifold
  Booleans, then the path tracer.
- [Particles, welded and traced](chain_physics_render.md): cloth to a mesh to the path tracer.
- [A cellular automaton you can hear](chain_ca_sound.md): automaton rules read as rhythm and
  pitch.
- [A pattern that moves particles](chain_pattern_particles.md): a rhythm driving a shader's push
  constants, every note on its own frame.

<!-- changelog: d63e87e252..0966263564 -->
### The path tracer samples its lamps, and can be inspected while it runs

*10–11 September 2026*

- **Emitter sampling**: the path tracer samples emissive geometry directly, so small, bright
  lamps leave fewer stray specks, without adding bias. [Sampling the lamps directly](path_tracing.md#sampling-the-lamps-directly).
- **An instrument for a running tracer**, and replacing its integrator without restarting.
  [Reading a running tracer](path_tracing.md#reading-a-running-tracer) and
  [Replacing the integrator](path_tracing.md#replacing-the-integrator-on-a-running-tracer).
- **Reference**: a new [Renderers](reference/renderers.md) page, and the synthesis, pattern and
  audio pages open with guided tours of their files.

<!-- changelog: dbe366ce63..d2d5f49dad -->
### Progressive path tracing

*10 September 2026*

- **Path tracing**, new: a progressive GPU path tracer with glTF import, camera jitter, depth of
  field, depth fog, glass and temporal reuse. [Progressive path tracing](path_tracing.md).
- **`patCondense`** finds the shortest mini-notation that plays a given rhythm.
  [The shortest way to write a rhythm down](pattern_language.md#the-shortest-way-to-write-a-rhythm-down).
- **Charts** clip their marks to the plot and wrap a long note. [Charts as FLines](chart.md).
  The simulation stack has its own [reference page](reference/simulation.md).

<!-- changelog: 9a3977727f..0d71f94878 -->
### Tetrahedral meshing with TetGen

*9 September 2026*

[Filling a solid](tetgen.md) is a new page on TetGen: meshing a solid into tetrahedra, sizing
the cells, checking mesh quality, and passing the result to the Kratos simulator
([Into Kratos](tetgen.md#into-kratos)), which can now run parameter studies.
Patterns gain Kolakoski rhythms beside the Euclidean ones; [Mini-notation](pattern_language.md#mini-notation).

<!-- changelog: daa117503c..9b5a513f43 -->
### Eye tracking, and Petri nets laid out by TALA

*9 September 2026*

- **Eye tracking on OpenXR**, and a marker placed in the world from binocular gaze.
  [Eye tracking on OpenXR](vision.md#eye-tracking-on-openxr) and
  [Place a world-space marker](openxr_eye_intersection.md).
- **TALA layout** for Petri nets, with arcs routed around the nodes, and for laying out boxes.
  [Drawing a net](petri_nets.md#drawing-a-net).
- The Kratos simulator gains structural analysis with quadratic tetrahedra and a structural
  playground.

<!-- changelog: 3a89913637..bc2e841458 -->
### A Tour of Field, and code keyframes that blend

*8 September 2026*

- **The Tour**: the documentation's staging area became a guided Tour, starting at
  [A Tour of the Interface](tour_interface.md), and [Where to Go Next](staging.md) maps the rest.
- **Blending two code keyframes**: Field aligns two versions of a program and interpolates the
  numbers in them. [Blending two programs](keyframer_examples.md#blending-two-programs-not-just-two-numbers).
- An optional Kratos thermal backend, with an interactive playground.

<!-- changelog: 7e5d6c7fad..dbc05a6cb2 -->
### A profiling timeline

*7–8 September 2026*

[Profiling](profiling_timeline.md) is a new page and tool: record a timeline of frames, named
scopes, coroutine resumes and JVM stacks, see a heatmap in the box's gutter, and follow a
stutter down to a line of your code ([From "it stutters" to a line of your code](profiling_timeline.md#from-it-stutters-to-a-line-of-your-code)).
A read-only document now says so in the window, and nothing in it is saved. Many more pages
were tidied.

<!-- changelog: 4c88924b57..d89c54a5ff -->
### Multi-resolution pass chains on the GPU

*7 September 2026*

A post-processing chain can now mix passes at different resolutions and formats, each with
its own inputs, and Field prints the plan, with sizes and memory cost, before it touches the GPU.
The [four_pass_chain](agent_transcripts.md#four_pass_chain) demo blurs a scene at a quarter
size and composites it back at full resolution. The mesh, projection, typography, Sonic Pi, Python, sound and Manifold pages were
rewritten in plainer language.

<!-- changelog: bd11beec88..79895c66fc -->
### Log channels, sinks and the console

*5–7 September 2026*

[Logging](logging.md) is a new page: how log lines reach their sinks, one rule for matching
channels, muting the console without losing lines, blocks, stat lines and the pinned console.
`withSink` brackets adding and removing a sink so it cannot be forgotten. Pages across the site,
from statistics and Python to Faust and Vision Pro, were rewritten in plainer language.

<!-- changelog: 2d6245327f..904afff88c -->
### OpenAI's newest model by default, and plainer pages

*5 September 2026*

The direct OpenAI provider now defaults to gpt-6-astra, and a reply says which model answered;
[Provider and key settings](aiFeatures.md#provider-and-key-settings). Mirrored glTF nodes keep
their mirror on the GPU paths too. The pages on graphics, FieldML, Faust, RectSL, solidsl,
Vision Pro, ONNX, keyframes, physics, StackSL and others were rewritten in plainer language, and
the [Field 4 graphics](afield_four_graphics.md) lessons lost their section numbers.

<!-- changelog: 052f26bf57..4cc5494127 -->
### Evolving artificial life, and switching AI providers without a restart

*4–5 September 2026*

- **Evolution over rule spaces**: cellular automaton and other artificial-life rules become
  genomes, scored by the measures the page already uses.
  [Evolution: searching the rule space](alife_examples.md#11-evolution-searching-the-rule-space).
- **AI providers can be swapped** while Field runs. [Provider and key settings](aiFeatures.md#provider-and-key-settings).
- **`--read-only`** opens a document without writing anything back to its folder, useful for a
  document kept in a repository.
- The pages on completion ranking, frames, linear algebra, graph queries, history, output
  panels, the 3D output, keyboard shortcuts and others were rewritten in plainer language.

<!-- changelog: 9e12d16e5c..07a154e215 -->
### What a document is on disk, and MIDI files

*3–4 September 2026*

- **A Field document on disk**: what one save writes, which file wins when a document loads,
  and what happens when you edit the folder under a running Field.
  [A Field document on disk](document_on_disk.md).
- **MIDI**: read and write Standard MIDI Files, send and receive on live devices, turn patterns
  into MIDI, draw a piano roll as FLines, and render MIDI to a WAV.
- **Keyframe browser** shows your own keys and strikes through a slot the scope no longer
  watches. [Browsing saved keys](keyframer_examples.md#browsing-saved-keys). An output panel you
  drag to a size keeps it.

<!-- changelog: 65c17cf9c4..b680c618ef -->
### `.h2` explores any object, and nesting connects

*1–3 September 2026*

- **`.h2`** now explores a domain object's properties, not just collections, with hand-written
  views for boxes, `Fn`, FLines and exceptions, and drills into a nested value in place.
  [The value visualizer](visualizers.md#the-value-visualizer-what-h-draws). An `.h2` panel takes the
  width of its box, and `.h` on a vec4 shows all four components.
- **Nesting a box connects it** to its container, and the connection survives reloads and history restores.
- **How an output panel picks its size**, for when one comes out the wrong width.
  [Output panel sizing](output_panel_sizing.md).

<!-- changelog: e4279638f3..2d1644cccd -->
### A browser for saved keyframes

*31 August – 1 September 2026*

`keyframeBrowser(dir, scope = …)` shows a folder of saved keys side by side: select a key to see
it, brush a range to filter, read timestamps, and plot vec4 values. With a scope attached,
**Apply** writes a key back. [Browsing saved keys](keyframer_examples.md#browsing-saved-keys).
Comments on a shader's push constants now appear in Kotlin completion.

## August 2026

<!-- changelog: ca6441bb47..a808fb07f7 -->
### Which file a path means

*30 August 2026*

[File management](files_and_paths.md) is a new page on how Field resolves a file name: where a
plain name is looked for, roots of your own, fallback files, globs, and how to see where a path
went. Keyframes gain typed local targets and controls that follow a key, and a `Pair` displays as
the `a to b` that built it.

<!-- changelog: 70fc24a4d6..d103360928 -->
### Datalog in a box

*29–30 August 2026*

- **Datalog**, a deductive database as a Field language: facts, rules and queries over the
  document or any object graph, with `#why` to show the proof of an answer and `#whynot` to say
  why a goal has none. [Datalog](datalog.md), from [Why a fact is there](datalog.md#why-a-fact-is-there).
- **Graph queries** take a `/` path shorthand, globs inside a segment, case-insensitive names and
  `|`. [The slash shorthand](graph_query.md#the-slash-shorthand).
- **StackSL on the CPU**: an interpreter for the GLSL StackSL emits, so a program can run without
  a GPU. The [Languages](reference/languages.md) reference collects Field's small languages.
- A run that succeeds no longer erases the errors it reported, and splats can be live-coded into
  render targets.

<!-- changelog: 7f254d84aa..da1d585b86 -->
### GPU state across frames, and a browser you do not have to render

*28–29 August 2026*

- **GPU state**: a three-box simulation that keeps its state on the GPU, ping-pongs it between
  frames and edits its force law as a string. [GPU state](gpu_state.md).
- **A headless browser tab** you can read as data: text, attributes, counts, tables and waits.
  [A browser you do not have to render](puppet_browser.md#a-browser-you-do-not-have-to-render).
- **Volumetric noise for StackSL**: `noise3`, `fbm3`, `warp3`, `curl3`, `worley3` and 4D variants.
  [Volumetric noise](stacksl_examples.md#volumetric-noise).
- `./field.sh --document <name>` opens a named document directly, and a folded comment stays on
  one row in the editor.

<!-- changelog: 04b81174e4..5f35b543c0 -->
### A web browser inside Field's graphics

*28 August 2026*

The [Puppet Browser](puppet_browser.md) puts a Chromium page into a native window or a VR
panel as a texture, drives it from Kotlin, and can even show Field itself. On Linux, browser
textures travel as shared GPU buffers. Stopping a box runs its `.end { }` block again;
[What happens when you stop](animation_time_model.md#what-happens-when-you-stop).

<!-- changelog: c0262e1ecb..541b3b5765 -->
### Placing things by distance, and splats as data

*27–28 August 2026*

- **MDS and t-SNE** for any distance you can write, including graph distances.
  [Placing Things by Distance](projection_examples.md). `smileMds` now works on Apple Silicon.
- **Speech and spectral embeddings**: plot a recording as a path through an embedding space.
  [Speech embeddings](audio_ai_speech_embeddings.md) and
  [spectral embeddings](audio_ai_spectral_embeddings.md).
- **Gaussian splats** can be read, edited, previewed on the CPU and written back, and a
  sequence of splat files streams as one scrubbable cloud.
  [Missing formats and missing features](geometry_from_disk.md#missing-formats-and-missing-features).

<!-- changelog: efe2f88b85..af230caded -->
### Python in Field, and moving around the canvas

*26–27 August 2026*

- **Python boxes** get a page: the Python root, one shared namespace, output, notebooks as a
  column of boxes, and mixing Python and Kotlin in one document. [Python](python.md).
- **Moving around the canvas** replaces the bookmarks page, with panning, stepping between boxes
  and bookmarks in one place. [Moving Around the Canvas](moving_around_the_canvas.md).
- **solidsl solves for the numbers**: fit a solid's parameters to a point cloud.
  [Solving for the numbers](solid_notation.md#solving-for-the-numbers).
- Compressed PlayCanvas splat files load directly, and a long comment in the editor folds into
  one panel.

<!-- changelog: 730440b808..c0958fad80 -->
### Pointing at geometry, and the Vision Pro page

*26 August 2026*

- **One pointer** for mouse, controller and pinched hand, which tells you which part of the
  geometry you touched. [Hand tracking](vision.md#hand-tracking-on-openxr-and-read-by-the-pointer-layer).
- **Vision Pro**: the page now covers the pose uplink, rendering from the head's own pose and
  where the streaming latency goes. [Where the latency goes](vision.md#where-the-latency-goes).
- **Field's source on the docs site**: a source citation in the docs is now a link to the file.
  The site's navigation was regrouped, and [Running Code in a Box](running_code.md) took over
  from the old pipeline page.

<!-- changelog: 872a1f45d9..744814f9dc -->
### Hand tracking and head-tracked rendering

*25–26 August 2026*

OpenXR hand tracking reports per-joint poses and pinch strength, and a Vision Pro can stream its
head pose back so Field renders from where you are looking.
[Head tracking](vision.md#head-tracking-the-pose-comes-back-up). A chorded launch now joins one
queue, so a later gesture extends the sequence, and the graphics examples use `by keyCamera(...)` for their
cameras again.

<!-- changelog: 4b88514ad3..3177e2ee2d -->
### Cloth, rope and wind, and the graphics lessons extended

*23–24 August 2026*

- **Physics**: the Verlet solver gains colliders, tearing, wind and gusts, and `Strand` for rope.
  [Physics](physics.md), starting from [The two knobs are one knob](physics.md#the-two-knobs-are-one-knob).
- **Graphics on the GPU**: the worked lessons now continue past texturing, through glTF,
  render-to-texture, ping-pong, blur, shader includes and debugging.
  [Field 4 graphics](afield_four_graphics.md).
- **The corpus microscope** indexes an audio corpus and places a neighbour as a box.
  [Corpus microscope](audio_ai_corpus_microscope.md).

<!-- changelog: a59b13e96c..d63869db55 -->
### Charts as FLines

*22 August 2026*

- **`chart()`** draws marks, scales and ticks as FLines, charts a DataFrame's columns, and
  draws distributions and small multiples that share a domain. Plotly is still the first choice
  for interactive plots. [Charts as FLines](chart.md).
- **A physics system in the frame loop**, with a recorded cloth.
  [A physics system in the loop](animation_time_model.md#a-physics-system-in-the-loop).
- The RectSL [musicality section](rectsl_examples.md#musicality-rhythm-as-scene-structure) now
  shows its examples as animations.

<!-- changelog: 1219cbad1b..14b7dc5ce9 -->
### DataFrames in a box

*22 August 2026*

- **DataFrames** get a page: how typed columns survive from one run to the next, and what `.h`
  and `.h2` show for a frame. [DataFrames in a Box](dataframe.md), from
  [`typed()`: the way across](dataframe.md#typed-the-way-across).
- **`.h2` on a list of same-shaped maps** shows a table instead of a column of summaries.
- **Cloth**, a reusable cloth patch for the Verlet solver, with its own debug view.
  [`Cloth`](physics.md#cloth).

<!-- changelog: cefa10472e..be02838b19 -->
### Types every box can name, and DataFrame's compiler plugin

*20–22 August 2026*

A class declared in one box can now be named by every other box in the document. Kotlin
DataFrame's compiler plugin runs in box code, so a DataFrame's columns stay typed across
executions and a DataFrame displays as a first-class value.
[DataFrames in a Box](dataframe.md#typed-the-way-across) shows the result. `facts()` gives a
quick label-and-value summary for your own `toHTML`.

<!-- changelog: b8d8cfe76e..487b9b5f23 -->
### A pattern plays a polyphonic Faust instrument

*20 August 2026*

A pattern can now play a Faust program polyphonically, with notes landing on the exact sample,
and it can play any parameter, the filter cutoff as well as the notes.
[A pattern plays it](faust.md#a-pattern-plays-it). Petri net state spaces are faster to
explore, and the state-graph drawing gives up cleanly on very large graphs
([Checking every possible order](petri_nets.md#checking-every-possible-order)). The
[polytope page](polytope_examples.md) was tidied.

<!-- changelog: 3df5aa0195..ea28eb3c71 -->
### What Field exposes to the network, and FieldML source macros

*19–20 August 2026*

- **Server surface**: a page on what Field listens on, what a website you visit can and cannot
  reach, and how to connect another device. [Server surface](server_surface.md).
- **FieldML source macros**: `prop`, `moduleHere` and other box-code macros work from FieldML,
  enough to build a live Vulkan scene. [Source macros](fieldml_host_bridge.md#source-macros).
- Tables from boxes and from the docs now share one renderer.

<!-- changelog: 3882278d1f..8ad519213c -->
### Frames and the animation loop

*18–19 August 2026*

- **Frames**, a new page on `loop`, `forever` and `frames`, time in seconds, what happens when
  you stop, and the generator layer. [Frames and the Animation Loop](animation_time_model.md).
  Stopping a box now also stops a subprocess it started.
- **FieldML** can satisfy Kotlin `suspend` function types and use named arguments, so it can
  drive a moving Field 4 scene. [Calling a suspend method](fieldml_host_bridge.md#calling-a-suspend-method).
- **RectSL inverse animation** covers headings and the whole gallery, with `atan`, `atan2` and
  `hypot` for function graphs. [Inverse animation](rectsl_inverse_animation.md).

<!-- changelog: ee2d0655b4..48a009490d -->
### How the completion popup decides

*17–18 August 2026*

A new page follows one caret from keystroke to popup: how candidates are ranked and grouped,
how to put your own class's members first with `@CommonMembers`, and how to ask the popup to
show its reasoning. [How the completion popup decides](completion_ranking.md). The AI now
refuses an ambiguous box name instead of editing the first match.

<!-- changelog: 51e6f07dd0..b6a09f6c89 -->
### A page for Field's 3D output

*15–16 August 2026*

[Field's 3D Output](three_output.md) is a new page on the three.js output area: scenes, the
camera, contact sheets, and which renderer draws what. A mesh can now be picked, sectioned,
exploded and measured against an FLine;
[Measuring and sectioning a solid](three_output.md#measuring-and-sectioning-a-solid). The
[solidsl page](solid_notation.md) gains a starting point and figures.

<!-- changelog: 6f994327aa..533918561f -->
### A notation for solids, and depth and CLIP models in a box

*15 August 2026*

- **solidsl**, a written notation for 3D solids with two lowerings, a simplifier and a GLSL
  raymarch backend. [A Solid, Written Down](solid_notation.md).
- **ONNX models**: depth estimation and CLIP run in a box, with cached sessions so a box can
  re-run cheaply. [Depth & CLIP](onnx.md).
- **Mesh repair**: Field says why a mesh is not a solid, shows where, and repairs it with a
  report. [From a file on disk to a solid](geometry_from_disk.md#from-a-file-on-disk-to-a-solid-you-can-cut).
- **Hyperbezier curves**, a drop-in alternative to `cubicTo`.
  [Hyperbezier](fline_examples.md#hyperbezier-curvature-controlled-stand-in-for-a-cubic).
  Meshes now open in the three.js viewer by default.

<!-- changelog: 81b6b704ba..9384444a48 -->
### Robust mesh Booleans with Manifold

*14 August 2026*

Union, difference and intersection of meshes, 2D sections and offsets, solids from a field and
slicing back down to 2D, all through the Manifold library.
[Solids & Booleans (Manifold)](manifold.md) is a new page, starting at
[Booleans](manifold.md#booleans). Invalid input comes back as a status you can read rather than
a crash.

<!-- changelog: e2e32099b4..fcf74f8427 -->
### Field hears pitch, and audio renders feed analysis

*13–14 August 2026*

- **Render, then analyse**: a pattern rendered to audio can go straight into the analysis
  views. [Rendering a Pat pattern and analysing it](audio_render_analysis.md#rendering-a-pat-pattern-and-analysing-it).
- **Synchronized audio analysis** of a recording in several views at once.
  [Audio AI analysis](audio_ai_analysis.md).
- **From audio back to a pattern**: Field tracks the pitch of a render and writes it out as
  notation. [Sound](sound.md#from-audio-back-to-a-pattern).
- The audio stack is in the reference: [Synthesis](reference/synthesis.md) and
  [Patterns](reference/patterns.md).

<!-- changelog: 1510dff59b..fd2f3fa8a8 -->
### Faust boxes, and every way to make a sound

*12–13 August 2026*

- **Faust**: a box can be a Faust signal processor, with errors on the line, completion for the
  standard library and its own controls. Nothing to install. [Faust](faust.md).
- **Sound paths**: one page comparing every way Field makes, sends and receives samples.
  [Making a Sound, Every Path There Is](sound_paths.md).
- **Sonic Pi as a sequencer** for Field's patterns, sample-exact.
  [Field patterns as a Sonic Pi sequencer](sonic_pi.md#field-patterns-as-a-sonic-pi-sequencer).
- **Controls**: sliders take a law (log, taper, bipolar), and a momentary button and a level meter
  join the controls. [Controls](visualizers.md#controls-sliders-toggles-colors-text-and-xy-pads).

<!-- changelog: 7ed6f1e904..6dfcef1315 -->
### Sonic Pi boxes, AI tools of your own, and a 3D inspector

*11 August 2026*

- **Sonic Pi v5**: a box can be a piece of Sonic Pi music, with errors on the line and the line
  that is sounding highlighted. [Sonic Pi](sonic_pi.md).
- **Your own AI tools**: a box can register a tool that Field's AI can call.
  [Give the AI a tool of your own](aiFeatures.md#give-the-ai-a-tool-of-your-own).
- **`threeInspect`** adds measurement, sectioning and an explode scrubber to the 3D output area.
  [The 3D output area as an instrument](fline_examples.md#the-3d-output-area-as-an-instrument-threeinspect).

<!-- changelog: 3c03e1fbb7..0cf554aa09 -->
### Geometry from disk, and figures that know what you clicked

*10 August 2026*

- **Geometry from disk**: OBJ, STL, PLY and glTF files load into a box, render, and write back
  out, with a table of what each format loses. [Geometry From Disk](geometry_from_disk.md).
- **Clicking a figure** tells you which element you hit, and where along it, in both the 2D and
  three.js views. [Where on the element you clicked](fline_examples.md#where-on-the-element-you-clicked).

<!-- changelog: 9653a041c7..4c45b6fde3 -->
### Notes on the documentation pages

*8–9 August 2026*

The documentation shown inside Field gets a layer for leaving notes on a page, which persist and
stay anchored to the text they were written against. [Writing a note](docs_notes.md#writing-a-note).
Value visualizers show physical units, and completion records which suggestions were offered
and taken.

<!-- changelog: db5a09cc3a..bc874d3459 -->
### Sound: from a pattern to audio

*8 August 2026*

- **Sound**, a new page that follows a pattern all the way to audio, using real drum samples.
  [Sound: the Pattern → Audio Path](sound.md).
- **Petri net state space**: deadlock, boundedness and dead transitions answered exactly, with a
  counterexample drawn over the state graph. [Deadlock and starvation](petri_nets.md#deadlock-and-starvation).
- **FieldML callbacks**: a lambda passed to the JVM gets typed parameters and completion.
  [Passing a function](fieldml_host_bridge.md#passing-a-function).
- **Cmd+/ comments code again** when the editor has focus.

<!-- changelog: 5b0003128a..3ca192600e -->
### Petri nets as a sequencer

*7–8 August 2026*

- **Petri nets** that play rhythms, act as patterns, carry tokens with rhythms of their own, and
  drive control signals. [Petri nets](petri_nets.md), with a [cheatsheet](petri_cheatsheet.md).
- **The audio gesture board**: sketch a gesture and generate audio conditioned on a source.
  [Gesture board](audio_ai_gesture_board.md).
- **Patterns render offline** with the real Dirt sample library. [The real samples](sound.md#the-real-samples).

<!-- changelog: e8a6cc41c7..dcfc8be1ed -->
### FieldML calls into the JVM

*7 August 2026*

FieldML boxes can call Kotlin and Java methods, pass functions, and reach Field 4, with
completion on the members of a typed JVM value. [FieldML host bridge](fieldml_host_bridge.md),
from [Calling a method](fieldml_host_bridge.md#calling-a-method). Completion at a dot now ranks
names by where they are declared.

<!-- changelog: 5f5e187230..2f87b91a6d -->
### A FieldML cheatsheet, and FieldML that animates

*6 August 2026*

- **FieldML cheatsheet**: one page of syntax, operators, lists, vectors, lines and JVM interop
  for Field's built-in typed language. [FieldML cheatsheet](fieldml_cheatsheet.md).
- **Iteration, vectors and per-frame animation** in FieldML boxes, described in
  [Animation](fieldml_examples.md#animation) and [Vector arithmetic](fieldml_examples.md#vector-arithmetic).
- **Cmd+C in the editor copies text again**, and a cancelled stream can no longer leave a browser
  tab stuck. Code completion offers more of the right names after a round of fixes.
- The [inverse-animation page](rectsl_inverse_animation.md) now shows its boxes running.

<!-- changelog: b3da1eaa80..01874e0852 -->
### Inverse animation: say where a rectangle should be, solve for the numbers

*5–6 August 2026*

RectSL can now work backwards: declare an unknown in a scene, say where a rectangle should be at
a given moment, and Field fits the value by gradient descent through the animation.
[Inverse animation](rectsl_inverse_animation.md) is a new page, starting at
[Declaring an unknown](rectsl_inverse_animation.md#declaring-an-unknown).
The [polytope page](polytope_examples.md#a-face-can-have-holes) covers faces with holes and
chained Conway operators, and the [loadable demo index](agent_transcripts.md) lists every scene.

<!-- changelog: c3c6fcffd9..868682eab3 -->
### RectSL scenes on the GPU, and rhythm as scene structure

*5 August 2026*

- **Distance fields**: a RectSL scene can be lowered to a GLSL signed distance field and rendered
  live on the GPU. [The scene as a distance field](rectsl_examples.md#the-scene-as-a-distance-field-on-the-gpu).
- **Musical structure**: Tidal-style pattern notation can drive a scene's rhythm.
  [Musicality](rectsl_examples.md#musicality-rhythm-as-scene-structure), with matching entries in
  the [RectSL cheatsheet](rectsl_cheatsheet.md).
- **Polytope faces with holes**, and the whole Conway vocabulary chains.
  [A face can have holes](polytope_examples.md#a-face-can-have-holes).
- **Check for updates** reports more reliably what an update would bring. [Updating](installation.md#updating).

<!-- changelog: 8eef733d43..908db5b5a7 -->
### Rebindable keyboard shortcuts, and a page for the pattern language

*4 August 2026*

- **Keyboard shortcuts** are now listed in full and can be rebound from a file Field writes for
  you. [Keyboard shortcuts](keybindings.md), from [Writing a shortcut](keybindings.md#writing-a-shortcut).
- **Patterns** get their own page: mini-notation, rhythm transforms, groove, per-event
  probability and patterns of patterns, with audio. [Patterns](pattern_language.md). A rhythm can
  also be a node in a function graph, so a fit can tune it:
  [A rhythm as a curve](pattern_language.md#a-rhythm-as-a-curve-and-as-a-graph-you-can-fit).
- **Ceres** builds and loads on Windows and Linux as well as macOS.
- The [Field Audio service reference](field_audio_service_reference.md) lists its operations,
  job states and error codes.

<!-- changelog: 590e1f7812..959a11eefa -->
### Polytopes and the Conway algebra

*2–3 August 2026*

- **Polytopes**, new: build solids from Conway operators, keep track of where every face came
  from, and realise them canonically, in rank 4 as well as 3.
  [Polytopes & the Conway algebra](polytope_examples.md), with
  [Realization](polytope_examples.md#realization-realize-relax-and-canonicalize) using Ceres.
- **Field Audio AI**: variations of a source sound, gestures, and an
  [EnCodec round trip](audio_ai_codec.md). [Field Audio AI](field_audio_ai.md).
- **Audio analysis views** beside recorded audio.
  [Recorded audio](visualizers.md#recorded-audio-waveform-player-playhead-and-navigation).
- **Interpolants on a frame clock**, so an offline render eases the same way every time.
  [Interpolants](interpolants_examples.md#interpolants-easing-a-value-to-a-target).

<!-- changelog: c571db6016..294bf75536 -->
### Typography as geometry

*1 August 2026*

Text becomes geometry: a string turns into contours you can lay on a curve, animate, extrude,
or treat as a distance field or a point cloud. [Typography as geometry](typography_examples.md)
is a new page. Image convolution got faster, and now beats OpenCV on several filters;
[Speed, against OpenCV](imaging_examples.md#speed-against-opencv) has the numbers.

<!-- changelog: 7abe87361c..336772f65c -->
### Keyframes that capture live state, and Markdown in output panels

*31 July – 1 August 2026*

- **Keyframes**: capture a box's live values, save them as history, replay them, and drive a GPU
  uniform from them. [Keyframes](keyframer_examples.md).
- **Markdown output**: `markdown("name", text)` fills a panel the way `display` does.
  [Displaying into a panel](box_output.md#displaying-into-a-panel).
- **Imaging visualizers**: images, edges, corners and flow draw inline.
  [Images, edges, corners and flow](visualizers.md#images-edges-corners-and-flow). `showHeatmap`
  draws a heatmap in one call ([Signal examples](spectral_examples.md)).
- **Searching for MorphoHDL circuits** by generation and evolution. [MorphoHDL in Field](morpho_examples.md).

## July 2026

<!-- changelog: 7cfe49be2e..e539d80643 -->
### Automatic differentiation, dynamic graphs and MorphoHDL

*31 July 2026*

- **Differentiating functions**: reverse- and forward-mode differentiation, exact gradients for
  `Fn` graphs, SGD, Adam and L-BFGS, inverse kinematics and circle packing.
  [Differentiating Functions](autodiff_examples.md).
- **Graphs that change while you watch**: dynamic graphs over moving points, drawn as FLines.
  [Graphs that change](dynamic_graph_systems_examples.md).
- **MorphoHDL** circuits compile and simulate inside Field. [MorphoHDL in Field](morpho_examples.md).
- Dashes from `byDashing` follow the curve instead of cutting across it, `src/personal` code
  reloads without a restart, and one broken language grammar no longer takes the editor down.

<!-- changelog: f02b484c2d..f7d1601b20 -->
### Artificial life, plane geometry and statistics

*29–30 July 2026*

Three new pages:

- [Spatial artificial life](alife_examples.md): Life, cyclic automata, reaction–diffusion,
  Lenia, boids, Physarum, diffusion-limited aggregation, L-systems and evolved rules.
- [Voronoi, contours and clusters](spatial_geometry_examples.md): Delaunay and Voronoi,
  Apollonius diagrams, contours of a field and clustering, with FLine output.
- [Statistics & dependence](statistics_examples.md): summary statistics, histogram bin rules,
  dependence coefficients and rolling correlation.

Plotly legends no longer sit on top of the data, and `showImg` keeps its caption.

<!-- changelog: 5f24f856f4..80b6ef5045 -->
### Wave function collapse, rewritten

*28 July 2026*

The [Wave Function Collapse examples](wfc_examples.md) page was rewritten. Learned
overlapping patterns can now be closed under rotation and reflection with `expandSymmetry`, and
[Neighborhoods beyond von Neumann](wfc_examples.md#neighborhoods-beyond-von-neumann) shows
larger neighbourhoods. Callout boxes on the docs site now render as boxes.

<!-- changelog: bdba74224c..ce8214d6d0 -->
### Triangle meshes and wave function collapse

*26–28 July 2026*

- **[Mesh examples](mesh_examples.md)**: a triangle-mesh library with no outside dependencies,
  for modelling from a field, subdividing, smoothing, decimating and measuring, with an inline
  view of each mesh.
- **[Wave function collapse](wfc_examples.md)**: a worked example, with the solver and its rules
  drawing themselves as you inspect them.
- **Vision Pro geometry** can be previewed on the Mac before it reaches the headset
  ([Vision geometry](vision_geometry.md#previewing-it-on-your-desk)), and the imaging library
  is compared against OpenCV ([Imaging](imaging_examples.md#speed-against-opencv)).
- **Corrections**: `movingAverage` now returns a mean, `tile` reports its true extent, and the rank
  helpers behind rank correlations now sort; the [reference](reference/index.md) documents far more of
  the library.

<!-- changelog: 08d229ff74..7ce6ac5148 -->
### A codebase reference, and an imaging library

*25–26 July 2026*

- **[Reference](reference/index.md)**: every public declaration in Field's library, grouped by
  area, generated from the source and its KDoc.
- **[Imaging examples](imaging_examples.md)**: a CPU imaging and computer-vision library for
  filters, edges, corners, Hough lines, blobs, warps and motion, whose results become FLines.
- **[Vision geometry](vision_geometry.md)**: building meshes that run natively in a Vision Pro
  head, now tried on a real device.
- Typing in the Electron app no longer stalls while canvas history is captured.

<!-- changelog: e3e4ea42bf..bd0ff28513 -->
### Synth building blocks you can hear

*25 July 2026*

[Dough DSP sound](dough_dsp_sound.md) is new: reverb, phaser, a resonant low-pass, envelopes,
distortion and raw oscillators, each with a plot and a recording you can play. Long examples on
the docs pages can now fold their setup code away, so the idea shows first.

<!-- changelog: f2d11c9f18..7156db3cd8 -->
### Rewriting code with patterns

*25 July 2026*

[Pattern rewrite](pattern_rewrite.md) is new: write `rewrite { }` rules that match Kotlin
expressions and replace them before a box compiles, with guards, variadic holes, code that runs
at rewrite time, rules inherited by child boxes, and a small language of
[strategies](pattern_rewrite.md#controlling-where-and-when-rules-fire-strategies) for choosing
where and when rules fire.

<!-- changelog: f08a7ed2ff..a476c7a0ad -->
### Querying object graphs by path

*25 July 2026*

[Querying object graphs](graph_query.md) is new: `graphPaths` searches a live object for values
by pattern, rather as XPath searches a document, with predicates and typed lenses, and
`params()` turns what it finds into numbers you can drive. In
[RectSL](rectsl_cheatsheet.md), every word that changes the scene now starts with `->`, so a
program shows where it mutates; the examples were rewritten to match. RemoteAR and Vision Pro
gained starting points you can open from the welcome page.

<!-- changelog: ffdf4672c6..bc79def962 -->
### An AR head inside a box

*24–25 July 2026*

`remoteAr()` embeds the WebXR head in a box's own output, so you can see and draw into it
without a second device ([AR reference](ar_reference.md#preview-the-head-inside-field)), and
named scene slots are typed. A fresh clone now builds the first time `field.sh` runs
([Installation](installation.md#clone-and-start)).

<!-- changelog: 616027c1cb..17e13ff7b6 -->
### Kotlin in the head, and rewrite rules in a box

*24 July 2026*

- **[Kotlin in the head](kotlin_in_the_head.md)** is new: write a box in Kotlin and run it in a
  browser head as JavaScript, drawing with Three.js, playing Tone.js or driving Metal.
- **Rewrite rules** (`rewrite { from ... to ... }`) can be used inside a box, and a box's rules
  apply to its children ([Pattern rewrite](pattern_rewrite.md#ambient-rules-rules-descend-the-box-graph)).
- RemoteAR scenes gained motion and assets, and Kotlin/JS completion leaves out JVM-only names.

<!-- changelog: 9f335b66ee..d8f9eccb10 -->
### Tone.js music, and ImGui inside VR

*22–23 July 2026*

A Kotlin/JS box marked `// {{ tone }}` drives Tone.js for synths and sequencing
([Kotlin in the head](kotlin_in_the_head.md#bells-with-tonejs)), with launch and stop handled
like any other box. [ImGui as an in-VR panel](imgui_vr_panel.md) is new: an ImGui window
floating in the headset, aimed and clicked with a controller.

<!-- changelog: d66887dd9d..fa1df49a56 -->
### Kotlin boxes that run as JavaScript

*21–22 July 2026*

A box can now be written in Kotlin and compiled to JavaScript for a browser head, with
highlighting and completion and no `fun main()` wrapper
([Kotlin in the head](kotlin_in_the_head.md#turning-a-box-into-a-kotlinjs-box)). The RemoteAR
surface grew vector maths, transforms, cameras and pointer callbacks on FLines. The welcome
page offers the bundled templates as a starting-point group
([Templates](templates.md#templates-starting-points-and-kits)), and a Vision Pro head runs
Metal on its own ([Field on Apple Vision Pro](vision.md#live-metal-programmable-native-graphics)).

<!-- changelog: 0417d970fd..c12a91f7b8 -->
### `display` shows images and plays sound

*21 July 2026*

`display(...)` now accepts PNG, JPEG and GIF bytes as pictures and WAV bytes as a player
([Box output](box_output.md#what-display-accepts)), and `showSignals(...)` plots several signals
together with Plotly. The [Spectral examples](spectral_examples.md) page grew a resonator-bank
example and now renders live in Field. The [AR reference](ar_reference.md) was rewritten for
the updated RemoteJS WebXR head, and pointer input in remote AR works again.

<!-- changelog: 81fc267676..b14f94bc34 -->
### State estimation and spectral analysis

*20–21 July 2026*

- **[State estimation examples](state_estimation_examples.md)**: tracking with an unscented
  Kalman filter and a particle filter, with captured results.
- **[Spectral examples](spectral_examples.md)**: FFTs, spectrograms, mel bands and streaming
  features from a new frequency-domain library.
- [Values over time](interpolants_examples.md) was retitled and tightened, Plotly charts use
  Field's font and slimmer margins in the dark theme, and more chatty log channels are muted by
  default.

<!-- changelog: 2b9b3e7fd5..a410d322be -->
### Bullet-hell patterns in RectSL, and a docs overhaul

*20 July 2026*

- **RectSL** shapes can face the way they travel, live for a window of time, and split
  recursively into sprays
  ([RectSL examples](rectsl_examples.md#reproduction-with-modification-array-primitives)).
- **The docs** were merged and cut: [Values over time](interpolants_examples.md),
  [Linear algebra](linear_algebra.md), [Neighbours and graphs](graph_connectivity_examples.md)
  and [Visualizers](visualizers.md) were rewritten, and
  [Agent transcripts](agent_transcripts.md) lists documents you can load and run.
- **The welcome page** shows a thumbnail of each document's history
  ([Your history with Field](history_overview.md)); the function fitters accept your own
  distance measure, and on Linux the desktop mirror no longer caps a headset's frame rate.

<!-- changelog: 13a8aebc19..40f19da590 -->
### Jupyter notebooks in and out

*19–20 July 2026*

Drop a `.ipynb` notebook on the canvas and it becomes a column of Python boxes; export it back
again ([Python](python.md#a-notebook-is-a-column-of-boxes)). Python output was polished: long
output is truncated, tqdm progress bars draw, and arrays print faithfully
([Python](python.md#output)). Dropping any file now offers a menu suited to its type, Kotlin
completion gained a new K2 backend, and RectSL can draw plots with axes and legends
([RectSL examples](rectsl_examples.md#a-plotted-figure-shared-domain-axes-overlaid-series-legends)).

<!-- changelog: 3a2f2b5e49..25070f0607 -->
### Charts in RectSL, and Python setup from the welcome page

*18–19 July 2026*

RectSL gained colormaps, bar charts, heatmaps and scatter plots
([RectSL examples](rectsl_examples.md#data-visualization)). The welcome page can install JEP,
which Python boxes need, into a virtual environment you choose
([Python](python.md#setting-python-up)). Double-precision matrices display as heatmaps
([Linear algebra](linear_algebra.md#matrices-transforms-precision)), and the
[Field probes](field_probes.md) page now shows its GPU demo running.

<!-- changelog: 29a044310f..01d4ffd447 -->
### How box code runs

*18 July 2026*

- **[Running code in a box](running_code.md)** is new: macros, preambles, inheritance,
  `@exported` and the `//%-` fragment break, in one place.
- **RectSL** gained arrays, a bridge to the Functions library and inline Kotlin
  ([RectSL examples](rectsl_examples.md#inline-kotlin-the-escape-hatch)), live syntax
  highlighting, and readable compile errors.
- **Dragging a box** dims the editor and shows the canvas dots and axes.
- **Pasted operation transcripts** run on their own ([Automating Field](automating_field.md)),
  and the [AR reference](ar_reference.md) was rewritten.

<!-- changelog: 933fdd2d23..ce81c7afa3 -->
### Fitting functions to a target

*17–18 July 2026*

A graph of functions can now be fitted to a target by gradient descent, with `fitBest` trying
several starts ([Functions examples](functions_examples.md#1d-stack-fit-a-graph-to-a-target-differentiable)).
Vectors gained GLSL-style swizzles and operators
([Linear algebra](linear_algebra.md#read-swizzles-reorder-narrow-expand-repeat)), there is a
full family of double-precision matrices, and error suggestions arrive faster.

<!-- changelog: 48b4d88128..bcf9894473 -->
### Open Anything searches the docs by meaning

*16–17 July 2026*

Open Anything now searches the documentation and Field's source by meaning as well as by name
([Tour of the interface](tour_interface.md#the-command-palette-and-open-anything)). Compile and
evaluation errors stay pinned in the terminal, and noisy log channels are muted there by default
([Logging](logging.md#muting-only-affects-the-console)). Connection lines are drawn behind boxes
so a box's frosted background blurs them, the font settled on a semi-condensed Iosevka, and the
generated vector types can be subclassed.

<!-- changelog: 4b9ebe546a..013e31fb38 -->
### Field's own linear algebra

*15–16 July 2026*

- **Vectors and matrices** are now Field's own generated types, with doubles as first-class as
  floats and concise constructors ([Linear algebra](linear_algebra.md),
  [API](linear_algebra_api.md)).
- **Values draw themselves**: vectors and matrices get small geometric thumbnails, numeric
  arrays and lists get sparklines, and functions get an inline plot
  ([Visualizers](visualizers.md#numeric-collections-with-stats)).
- **[Field from your phone](mobile_controllers.md)** is a new page on the phone control surfaces.
- Field's editor font is now a condensed Iosevka, and a macro can be promoted to a wider scope
  ([Automating Field](automating_field.md#operations-as-data)).

<!-- changelog: a644cc16e8..939023594c -->
### Browsing code from the completion panel

*14–15 July 2026*

Names in the documentation beside a completion are now links: follow one to read its source,
and keep going from there ([Browsing code from the completion panel](completion_doc_browser.md#following-a-link)).
**Find similar code** results show their source inline, as do semantic search results
([The semantic index](semantic_index.md)). Rectify, quantize and crush became waveshaping
operations that evolution can use
([Functions examples](functions_examples.md#fold-rectify-quantize-crush-chebyshev-waveshaping)).

<!-- changelog: 3883d558e0..3ef5984c8a -->
### Field on Apple Vision Pro

*14 July 2026*

A native visionOS front end shows FLines in 3D, edits them by hand, and receives Field's
rendered output as a video stream, side-by-side stereo included
([Field on Apple Vision Pro](vision.md)). In the Functions library, `ramp` now draws a straight
value line and the old normalizer is called `remap`; the Chebyshev waveshaper's order is
continuous, so harmonics sweep smoothly. AI completions no longer hold up the ordinary
completion popup.

<!-- changelog: 0a60283fe3..e3a1ca9dfe -->
### Function domains, and a Chebyshev waveshaper

*13–14 July 2026*

[Function domains](functions_domains.md) is a new page on where a function is defined and why
oscillators run forever. A Chebyshev waveshaper joins the function transforms
([Functions examples](functions_examples.md#fold-rectify-quantize-crush-chebyshev-waveshaping)).
The pinned log console can scroll back through earlier lines and shows network statistics
([Logging](logging.md#the-pinned-console)), and the render stream to Vision Pro now works from
Linux with NVIDIA's encoder.

<!-- changelog: 365fe9483a..fd60398648 -->
### A pinned console for busy log channels

*13 July 2026*

Busy log channels can be pinned to a console in the terminal instead of scrolling past
([Logging](logging.md#the-pinned-console)). Oscillators in the Functions library now have
infinite domains by default ([Function domains](functions_domains.md)), the welcome page lists
documents as it finds them instead of waiting for all of them, and the render stream carries a
side-by-side stereo flag.

<!-- changelog: 2b3cd767c2..a31a58e445 -->
### Waveshaping functions

*13 July 2026*

Functions can now be folded, rectified and quantized like an audio signal is waveshaped, and a
new section explains how the transform pipeline fits together
([Functions examples](functions_examples.md#shaping-signals-the-functions-transform-pipeline)).
Completion after an infix operator takes overloads into account, and a render-to-texture target
can be streamed as video from macOS to a headset ([Field on Apple Vision Pro](vision.md#streaming-the-rendered-scene-as-video)).

<!-- changelog: 6af56ef194..3f7317514d -->
### Evolving functions for variety

*12 July 2026*

Evolution over function graphs can search for a varied set of results rather than one best
answer, with a worked example that runs on the CPU
([Functions examples](functions_examples.md#variety-generation-novelty-search-for-a-set-of-gestures)).
Boxes can show transient progress bars ([Box output](box_output.md#one-line-channels)), and
completion after an infix operator now knows the type on its left
([Functions examples](functions_examples.md#shaping-signals-the-functions-transform-pipeline)).

<!-- changelog: febe2f447a..bd734467d4 -->
### Galleries, history pages, and Field as an MCP server

*11 July 2026*

- **Galleries**: rendered stills and animations for [StackSL](stacksl_examples.md#rendered-gallery)
  and [RectSL](rectsl_examples.md).
- **New pages**: [Your history with Field](history_overview.md), [Code history](history_lens.md),
  [the semantic index](semantic_index.md), [Field probes](field_probes.md),
  [Automating Field](automating_field.md) and [AI tool-use history](ai_tool_use_history.md).
- **Error suggestions** carry Apply and Ask AI buttons
  ([AI features](aiFeatures.md#ask-ai-about-a-displayed-error)).
- **MCP**: Field runs as a local MCP server, so outside coding agents can use the same tools as
  its own assistant ([AI features](aiFeatures.md#source-edits-captures-and-launch-receipts)).

<!-- changelog: f91613648d..6792415f6b -->
### Inspecting and rewriting functions as graphs

*9–10 July 2026*

- **Function graphs**: a function can be browsed through its component graph and changed with a
  small rewrite language
  ([Functions examples](functions_examples.md#fn-graph-inspection-and-rewrite-language)); the
  page itself was rewritten around the transform pipeline.
- **Fabricate** moved closer to Three.js in what a scene can say
  ([Fabricate renderer](fabricate_renderer.md)).
- **[Building the docs and running the tests](building_and_testing.md)** is new.
- Reloading a page no longer fetches every static file again, `?nosplash` skips the intro
  animation, and output panels no longer flicker while they size themselves.

<!-- changelog: 3b74a2285e..1cc1234858 -->
### Semantic search over your code

*7–8 July 2026*

With a model configured, Field can index your code and find it from a description of what it
does ([The semantic index](semantic_index.md)). The Fabricate renderer gained a post-effects
stack, a scene graph and render planning ([Fabricate renderer](fabricate_renderer.md)), the
canvas background is drawn with WebGL everywhere, and exceptions thrown inside callbacks are
now reported in the editor.

<!-- changelog: cf0e511b22..d8df42190b -->
### FieldML, and box output on your phone

*3–6 July 2026*

- **FieldML**, a small typed expression language that runs inside Field, with completion
  ([FieldML examples](fieldml_examples.md)).
- **Phone controllers** mirror a box's output and its control groups
  ([Field from your phone](mobile_controllers.md)).
- **Fabricate** frame graphs can be inspected ([Fabricate renderer](fabricate_renderer.md)).
- [Where to go next](staging.md) was rewritten as a map of the docs, and Electron uses
  hardware acceleration on Linux.

<!-- changelog: e934905b3b..2cfe4c6102 -->
### Recorded operations, and a calmer start

*2–3 July 2026*

Operations on a document can now be written down as data and replayed as macros
([Automating Field](automating_field.md#operations-as-data)). GLSL completion is much better,
a formatting command knows which language each property holds, startup no longer floods the
screen with messages, and notifications were redone. The full Iosevka font set now loads.

<!-- changelog: 40efa827c9..d06f16b6e3 -->
### Maven dependencies, and faster starts

*29 June – 1 July 2026*

Boxes can pull libraries from Maven repositories, managed from a panel on the
[welcome page](funFeatures.md#the-welcome-page), which now also shows runloop statistics.
Ahead-of-time caching shortens startup, reading a depth buffer is about a hundred times faster,
Linux memory allocation was fixed, and OpenXR starts more cleanly.

## June 2026

<!-- changelog: 014bdf1a1a..545383c711 -->
### RectSL, and a new completion picker

*26–28 June 2026*

[RectSL](rectsl_examples.md), a small language for animated scenes built from rectangles, arrives with its
[cheatsheet](rectsl_cheatsheet.md), as the first language built on the extension mechanism
([Language extensions](language_extensions.md#rectsl-sketch)). Completion now has its own picker,
grouped by category. Field also listens on a local HTTP port, so scripts on the same machine can
drive it ([Automating Field](automating_field.md#driving-field-from-a-script-or-agent)).

<!-- changelog: 3310b39f36..7ec9d607e8 -->
### Language extensions

*24–26 June 2026*

New languages can be plugged into Field as extensions, with their own completion, embedded
regions and notes on what each line did ([Language extensions](language_extensions.md)). AI
prompts are kept in a document's history, backups no longer repeat themselves, and the KDoc
behind completion was polished.

<!-- changelog: 774be357f6..9a125329c9 -->
### Shader probes, `sourceBlock` and file lookup

*21–23 June 2026*

- **Probes**: `fieldProbe` reads values back from a running shader and `fieldStats` gathers
  statistics on the GPU ([Field Probes](field_probes.md)).
- **`sourceBlock { }`** is a block you call like a function that keeps its own source text
  ([Running code](running_code.md#sourceblock-and-interpolant-code-that-keeps-its-own-source)).
- **`@rootExported` and `@metarootExported`** share a definition across a document or every open
  document ([Running code](running_code.md#exported-promote-what-you-defined)).
- **Files by name**: code can find a file that travels with the document
  ([File management](files_and_paths.md)). Error messages are clearer, and StackSL gained
  function pointers and sampler helpers.

<!-- changelog: e520ca540f..5a3d33028d -->
### Ableton sets, OpenRouter and more StackSL

*20–21 June 2026*

- **Projects and a metaroot**: documents can share a root above them, so open tabs can pass
  values to each other ([Kits](kits.md#lifecycle-and-shared-project-state)).
- **StackSL** gained more words and named local marks
  ([StackSL cheatsheet](stacksl_cheatsheet.md#local-marks)).
- **OpenRouter** joins the AI providers ([AI features](aiFeatures.md#provider-and-key-settings)).
- Render-to-texture targets can be blitted straight to a window, including a debug view that
  leaves the target untouched, and Ableton Live set files can be read into a box.

<!-- changelog: 0bc9cee567..e6771bc7ae -->
### The StackSL cheatsheet

*17–19 June 2026*

The [StackSL cheatsheet](stacksl_cheatsheet.md) is new: every word of the little stack
language for shaders, on one page. StackSL now also runs inside compute shaders. Elsewhere,
controllers can be locked and edit 2D values ([Field from your phone](mobile_controllers.md)),
render-to-texture targets can be saved as JPEG without stalling, granular synthesis improved,
24-bit WAV files load, and document search finds what it should.

<!-- changelog: 0836dcfcbf..4270d5cf0a -->
### OSC from the browser, and AI that runs Kotlin

*14–15 June 2026*

OSC can now reach Field from a web page through a built-in bridge, and [Field's own OSC
editor](osc.md#fields-own-osc-editor) can edit by deltas. The AI assistant can evaluate Kotlin
and check a box's variables while it works ([AI features](aiFeatures.md)). StackSL gained
bitcrushing and other operators, and the `.h` and `.h2` views were tidied
([Visualizers](visualizers.md#the-value-visualizer-what-h-draws)).

<!-- changelog: 9733647673..94af45483a -->
### The desktop app, ray tracing, StackSL and the first docs site

*14 June 2026*

Four months of work arrived at once:

- **A desktop app** with tabs and a [welcome page](funFeatures.md#the-welcome-page), plus the
  [Tour](funFeatures.md), [AI features](aiFeatures.md), [Visualizers](visualizers.md) and
  [Interactive guides](interactive_guides.md) pages.
- **Graphics**: [Field 4 Graphics](afield_four_graphics.md), the
  [Fabricate renderer](fabricate_renderer.md), Vulkan [ray tracing](ray_tracing_examples.md),
  HDR output, Slang shaders and a GPU radix sort.
- **Languages**: [StackSL](stacksl_examples.md) for shaders, [Forth](forth_examples.md) and
  [Nano](nano_examples.md), on the K2 Kotlin compiler.
- **Examples** of [functions](functions_examples.md), [interpolants](interpolants_examples.md),
  [sorting networks](sorting_networks_examples.md), [boolean networks](boolean_networks_examples.md)
  and their [music](boolean_network_music_examples.md), [interaction nets](interaction_nets_examples.md)
  and [colours](colors_examples.md).
- **History**: [document history](document_history.md) and the code [history lens](history_lens.md)
  in the editor, plus Jolt [physics](physics.md), a keyframe editor and spreadsheets.

## February 2026

<!-- changelog: 095e2997bc..b12f338f19 -->
### Graphs on point clouds, curve fitting and cloth

*12–23 February 2026*

- **Graphs**: neighbour searches and graph families over a point cloud, with Voronoi and
  Delaunay ([Neighbours and graphs](graph_connectivity_examples.md)).
- **Fitting** lines and curves to points
  ([FLine examples](fline_examples.md#path-operations-and-fitting)).
- **Physics**: a particle solver with distance constraints and a cloth example
  ([Physics](physics.md#cloth)), grabbable in VR.
- **`@exported`** promotes a definition so connected boxes can use it
  ([Running code](running_code.md#exported-promote-what-you-defined)).
- **Search Documentation & Code** (Cmd+Shift+D) searches the docs and your code
  ([Keyboard shortcuts](keybindings.md#canvas)), and the value visualizer shows more about
  images.

<!-- changelog: 217036fb97..12a7dd67cd -->
### PLY point clouds, ImGui and documentation in completion

*23 January – 10 February 2026*

- **PLY files** load as meshes or point clouds
  ([Geometry from disk](geometry_from_disk.md#ply-colours-point-clouds-and-missing-normals)), and
  glTF models carry their embedded textures.
- **ImGui** panels, in a window or inside VR ([ImGui as an in-VR panel](imgui_vr_panel.md)).
- **Completion shows documentation** for what it offers, and an API reference is generated from
  the source ([Reference](reference/index.md)).
- **Launchers** you can double-click ([Installation](installation.md#clone-and-start)), Kotlin
  2.3, a spatial audio engine, and a NumPy file reader.

## January 2026

<!-- changelog: bc24a144f2..4910d59dad -->
### The first documentation pages

*12–19 January 2026*

Field's documentation site began here: [Installation](installation.md), the
[Survival guide](survivalGuide.md), a [Kotlin overview](kotlin_overview.md) for Python and
JavaScript users, [FLine examples](fline_examples.md), the [diff player](diff-player.md) and
the [AR reference](ar_reference.md). A read-only viewer can now show a document without a
running Field (today's [published documents](box_output.md#publishing-a-document) grew from it),
and stereo output for OpenXR was fixed.

<!-- changelog: 72ee0e96c1..c01a7c7793 -->
### A Three.js viewer for lines

*10–12 January 2026*

FLines can now be shown in an interactive Three.js viewer in a box's output, with orbit
controls for turning the view; [Three.js scenes](three_output.md) describes what grew from it.
Box code can also open, close and clear its own display channels
([Box output](box_output.md#opening-closing-and-removing-panels)), and many more geometry
operations joined FLine.

<!-- changelog: 67da1b9f9d..adc263f121 -->
### Geometry operations on FLines

*10 January 2026*

- **FLine geometry**: arcs, noise, offsets, curve combination and parameterization, over fifty
  operations on drawn lines, all called as methods on the line.
  [FLine examples](fline_examples.md#geometry-operations-simple-complex).
- **`ImageReducer`** reduces an image with a compute shader, once or every frame
  ([reference](reference/graphics.md#imagereducer-kt-imagereducer)).
- **Side-by-side stereo** output for OpenXR, and a stand-in headset for working without one.

<!-- changelog: 5652ccfabd..a457b316b8 -->
### Cmd Cmd to move between boxes, and AI completion

*4–9 January 2026*

- **Double-tap Cmd** to step between boxes from the keyboard
  ([Moving around the canvas](moving_around_the_canvas.md#cmd-cmd-and-hold)).
- **AI completion** of Kotlin as you type ([AI features](aiFeatures.md#ai-assisted-completion)).
- **Action links** in guide notes run code when clicked
  ([Interactive guides](interactive_guides.md#add-callbacks-and-live-template-values)).
- **A first standard renderer**, the quick path to a lit scene, with a set of standard shapes
  ([Fabricate renderer](fabricate_renderer.md)); `autorun` boxes run when a document opens, and
  layers got a palette ([Layers](layers.md#the-layers-panel)).

<!-- changelog: e054a15bff..8045538940 -->
### Layers

*2–3 January 2026*

Boxes can be put on [layers](layers.md), and switching layers shows and hides them with an
animation, each layer keeping its own box positions. Also new: the
[documentation browser](funFeatures.md#documentation-browser) inside Field, browsing a glTF
scene in a box's output ([Visualizers](visualizers.md#browsing-a-gltf-scene)), and names for
saved versions in [document history](document_history.md).

<!-- changelog: dc149d7423..de2870b7b2 -->
### Document history

*2 January 2026*

Every save now becomes a version you can browse and restore from a timeline of the document's
past ([Document history](document_history.md#get-back-an-earlier-version)). Saving now happens
in the background. Guide chats can be shared
and kept across sessions ([Interactive guides](interactive_guides.md#per-slide-chat)), and the
renderer's profiler now covers OpenXR headsets.

<!-- changelog: 1455df01fe..07e6007254 -->
### Snapping boxes while you drag

*2 January 2026*

Boxes snap to each other's edges and centres as you drag or resize them, with cyan guides
showing what they lined up with; Caps Lock turns snapping off
([Moving around the canvas](moving_around_the_canvas.md#snapping)). Push constants can now be
interpolated, and VR input sources were reworked so headset input is smoother.

<!-- changelog: 770e583ffd..5c2cd6e8c5 -->
### Interactive guides with callbacks and chat

*1–2 January 2026*

- **Guide callbacks**: links in a guide note can run code, and note text can fill in live values
  from the document ([Interactive guides](interactive_guides.md#add-callbacks-and-live-template-values)).
- **Per-slide chat**: a step can carry its own discussion
  ([Interactive guides](interactive_guides.md#per-slide-chat)).
- **Template descriptions** show in the insert menu
  ([Templates](templates.md#describe-a-template)), and YAML metadata gets completion.
- Double-tapping Shift opens **Open Anything** reliably
  ([Tour of the interface](tour_interface.md#the-command-palette-and-open-anything)), and pasting
  nested boxes keeps them in place.

## December 2025

<!-- changelog: f58dadcd02..ce51b4974e -->
### Search across a document

*30 December 2025*

A document-wide search finds text in every box ([Tour of the interface](tour_interface.md#search-boxes)).
Images can be attached to an AI chat, the editor shows whether each box is running, sliders and
other controls work inside output panels ([Visualizers](visualizers.md#controls-sliders-toggles-colors-text-and-xy-pads)),
and dragging boxes stays smooth when the frame rate is low.

<!-- changelog: 0b4f1e9913..d76a3e98a9 -->
### The value visualizer and controls in output

*29 December 2025*

- **Value visualizer**: values print as compact, readable views, with cycles detected
  ([Visualizers](visualizers.md#the-value-visualizer-what-h-draws)).
- **Controls**: sliders, 2D pads, checkboxes, text fields and colour pickers in a box's output,
  each calling back into your code
  ([Visualizers](visualizers.md#controls-sliders-toggles-colors-text-and-xy-pads)).
- **Slideshow annotations**: lines drawn from a note to a box or a line of code
  ([Interactive guides](interactive_guides.md#annotations-and-code-callouts)), and slides can
  show images from the workspace.
- **Output panels**: Alt-click a panel's arrow to remove it, Shift-click to show it alone.

<!-- changelog: 8e03c9bcb7..43d14a5616 -->
### Slideshows, bookmarks and logging

*28–29 December 2025*

- **Slideshows**: `slideShow { step(...) }` walks through a document step by step with notes
  over the canvas ([Interactive guides](interactive_guides.md)).
- **Bookmarks** save a view of the canvas to come back to with a function key
  ([Moving around the canvas](moving_around_the_canvas.md#bookmarks)).
- **Logging** built into Field, with channels and a browser for the log
  ([Logging](logging.md)), and the running renderer can be browsed like any other value
  ([Visualizers](visualizers.md#browsing-the-renderer)).
- Templates can come from a remote server, keep their nested boxes, and land where the mouse is
  ([Templates](templates.md#insert-a-template)); video plays with synchronised sound.

<!-- changelog: 138b344eb4..8975eb46e6 -->
### Templates, and editing a document from outside Field

*27–28 December 2025*

- **Save as template**: save boxes and insert them again in any document
  ([Templates](templates.md#save-boxes-as-a-template)), including from a shared template server
  ([Templates](templates.md#share-templates)).
- **External edits**: change a box's code in your own editor and Field picks it up
  ([Editing a document from outside Field](editing_from_outside.md)).
- **Video textures**: play a video file onto the GPU
  ([reference](reference/graphics.md#videotexture-kt-videotexture)).
- Export the selection or the whole document, import a `metadata.edn`, and Alt-double-click a
  symbol to run it.

<!-- changelog: 2478e14ae9..179d6832fd -->
### Nesting boxes, and OpenXR for headsets

*25–27 December 2025*

Boxes can now nest inside other boxes ([Tour](funFeatures.md#nesting)), and VR moved to
OpenXR ([Field inside VR](field_vr_ide.md)). GLSL got completion in the editor, the AI chat was
reworked, and boxes written in languages other than Kotlin run more smoothly.

<!-- changelog: 8f9c20ca71..bb2645613a -->
### AI chat, and documents as EDN

*17–23 December 2025*

An AI chat arrived inside Field, with streamed replies ([AI features](aiFeatures.md#chat-about-one-box)).
Documents are now saved as EDN, a `metadata.edn` plus a file per large property
([What a document is on disk](document_on_disk.md)). Boxes can be copied and pasted
([Keyboard shortcuts](keybindings.md#canvas)), and OSC support grew.

<!-- changelog: 6021971d06..965ead245d -->
### Render to texture, compute particles and OSC

*30 November – 17 December 2025*

- **Render to texture**, including ping-pong between two targets
  ([Field 4 Graphics](afield_four_graphics.md#render-to-texture)).
- **Compute shaders and particles**
  ([Field 4 Graphics](afield_four_graphics.md#compute-particles-then-draw-them)), several
  windows at once, and multi-texture glTF models.
- **OSC** in and out of a box, with handlers that run when a message arrives ([OSC](osc.md)).
- **Shader templating** ([Field 4 Graphics](afield_four_graphics.md#shader-templating)).

## November 2025

<!-- changelog: 1c720ef071..abf4257c7d -->
### Browsing values, and a keyboard camera

*12–26 November 2025*

Values can be browsed as trees and columns in a box's output
([Visualizers](visualizers.md#trees-and-columns)), and a camera you fly with the keyboard
joined the graphics library ([Field 4 Graphics](afield_four_graphics.md#keyboard-camera)).
Stereo headsets render both eyes in one pass, and render targets can be layered.

<!-- changelog: 910c2c6a66..5a80af9e4c -->
### Mesh shaders, glTF and Linux

*4–10 November 2025*

The renderer gained mesh shaders and glTF model loading
([Field 4 Graphics](afield_four_graphics.md#loading-a-gltf-model)), plus a first VR target.
Field now runs on Linux as well as macOS and Windows
([Installation](installation.md#linux-prerequisites)), and completion works on Windows.

<!-- changelog: 6bfab59db0..e07338a989 -->
### Field·4 begins

*11 June – 3 November 2025*

Field·4 starts as a Kotlin live-coding environment with a browser canvas of boxes,
a code editor, a Vulkan renderer, AI hooks and Python boxes, built for macOS and Windows.
The [Tour](funFeatures.md) and [Installation](installation.md) describe what it became.
