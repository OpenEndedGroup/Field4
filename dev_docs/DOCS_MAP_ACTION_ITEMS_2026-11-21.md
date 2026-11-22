# Docs map: action items (2026-11-21)

What the [map of the docs](../public/docs/docs_map.md) says about the site, as things to do. Every
figure comes from `tools/docs_link_graph.py --transcripts --code` on `main` (c3c5f36ee1), whose
`report.md` has the full tables: "Orphans", "Spokes", "Dead ends", "Reach of each section's main
page", "Filed away from its neighbours", "Share code, no link", "Close to a hub, not linked",
"Code moved on", "Last linked" and "Not integrated".

```bash
uv run --project . --group docs python tools/docs_link_graph.py --transcripts --code
```

"Links" below are hand-written page→page links. Links from the changelog and the transcript
gallery are left out because those pages list everything. "Two links apart" counts links in
either direction.

## 1. Connect what is cut off

1. **Link the two isolated pages** from a related page, and give each a link out:
   [DaVinci Resolve: Timelines and Markers](../public/docs/davinci_resolve.md) and
   [Place a world-space marker from binocular gaze](../public/docs/openxr_eye_intersection.md).
2. **Give the 32 orphans a link in**, oldest and largest first:
   [A solid, written down](../public/docs/solid_notation.md),
   [Projecting a distance matrix](../public/docs/projection_examples.md),
   [Filling a solid with TetGen](../public/docs/tetgen.md),
   [FieldML stage by stage](../public/docs/fieldml_stages.md),
   [A pattern as a function generator](../public/docs/marf.md),
   [Proving What a Shader Draws](../public/docs/stacksl_intervals.md),
   [Streaming diffusion](../public/docs/streaming_diffusion.md),
   [Syphon](../public/docs/syphon.md) and
   [Network MIDI](../public/docs/rtp_midi.md). Three groups could be linked as sets:
   - **Media sync:** [RTP-MIDI](../public/docs/rtp_midi.md),
     [SMPTE timecode](../public/docs/smpte_timecode.md) and
     [DaVinci Resolve](../public/docs/davinci_resolve.md). Link them to each other and from
     [Sound](../public/docs/sound.md).
   - **StackSL research:** [Evolving a Shader](../public/docs/stacksl_evolve.md) and
     [Proving What a Shader Draws](../public/docs/stacksl_intervals.md). Link them from
     [StackSL examples](../public/docs/stacksl_examples.md).
   - **Unlinked chain examples:** [Flow islands](../public/docs/chain_flow_islands.md),
     [Stencil bridges](../public/docs/chain_stencil_bridges.md) and
     [Tracked body](../public/docs/chain_tracked_body.md). Link them from
     [Examples that cross several parts of Field](../public/docs/chains.md).
3. **Give single-parent pages a second link in.**
   [Visualizers](../public/docs/visualizers.md) is the only way into
   [ASCII](../public/docs/ascii_shape.md), [audio curves](../public/docs/audio_curves.md),
   [distributions](../public/docs/distributions.md) and [terminal](../public/docs/terminal.md).
   [Spatial artificial life](../public/docs/alife_examples.md) is the only way into
   [Braitenberg](../public/docs/braitenberg_vehicles.md),
   [Evolving Creatures](../public/docs/evolving_creatures.md),
   [Particle Lenia](../public/docs/particle_lenia.md) and
   [Wave Function Collapse](../public/docs/wfc_examples.md). There are 53 spokes in all.
4. **Make [Where to Go Next](../public/docs/staging.md) reachable.** It's the most important
   connecting page on the site (betweenness 0.25), with 66 links out, but only
   [Automating Field](../public/docs/automating_field.md) links to it. Put it in the nav or the
   tour, or move its links into the pages that should carry them.

## 2. Add the missing cross-links

These pairs reach the same specific source files but don't link each other.

5. **Field Audio cluster.** Link these to each other rather than only to
   [Field Audio AI](../public/docs/field_audio_ai.md):
   [Beat and pitch](../public/docs/audio_ai_analysis.md),
   [Corpus microscope](../public/docs/audio_ai_corpus_microscope.md),
   [MuScriptor](../public/docs/audio_ai_muscriptor.md),
   [Speech-reactive spaces](../public/docs/audio_ai_speech_embeddings.md),
   [Gesture board](../public/docs/audio_ai_gesture_board.md),
   [EnCodec](../public/docs/audio_ai_codec.md) and
   [Short-time spectral spaces](../public/docs/audio_ai_spectral_embeddings.md). Seven pairs
   among them share the client and protocol code.
6. **Paint.** [Drawn Strokes Become Paint](../public/docs/chain_drawn_paint.md) should link with
   [Brushes with moving hairs](../public/docs/paint_gestures.md) (similarity 0.84) and with
   [Paint settings, swept](../public/docs/paint_sweeps.md) (0.75).
7. **FieldML.** [FieldML's JVM bridge](../public/docs/fieldml_host_bridge.md) and the
   [FieldML cheatsheet](../public/docs/fieldml_cheatsheet.md) use the same property and rewrite
   machinery as [Running code in a box](../public/docs/running_code.md) and
   [Properties that re-run boxes](../public/docs/reactive_properties.md), but are more than two
   links from both.
8. **Smaller pairs:**
   - [RectSL examples](../public/docs/rectsl_examples.md) and
     [RectSL Inverse Animation](../public/docs/rectsl_inverse_animation.md)
   - [A photograph, closed and mounted](../public/docs/chain_photo_to_solid.md), with
     [Three.js scenes](../public/docs/three_output.md) and
     [Meshes and 3D geometry](../public/docs/mesh_examples.md)
   - [A RectSL animation as a solid](../public/docs/chain_rectsl_spacetime.md), with
     [Three.js scenes](../public/docs/three_output.md)
   - [Body and hand tracking](../public/docs/body_tracking.md) and
     [Stereo video in a room](../public/docs/stereo_video.md)
   - [A Petri net that throws sparks](../public/docs/chain_petri_sparks.md) and the
     [Petri notation cheatsheet](../public/docs/petri_cheatsheet.md)
9. **Give the big hubs onward links.** Only 38% of links (418 of 1,110) have a link back, and
   these hubs mostly receive them, so readers who arrive there stop:
   - [FLine examples](../public/docs/fline_examples.md): 27 in, 5 out
   - [Patterns](../public/docs/pattern_language.md): 18 in, 3 out
   - [Drawing and Notation](../public/docs/drawing_and_notation.md): 17 in, 4 out
   - [Manifold](../public/docs/manifold.md): 16 in, 3 out
   - [Python in a box](../public/docs/python.md): 11 in, 3 out

## 3. Fix the structure

10. **Split Drawing From Data**, or give each sub-area its own landing page. It has 93 pages,
    and 69 are more than two links from its most-linked page,
    [Patterns](../public/docs/pattern_language.md). Every other section's main page reaches
    nearly all of its section.
11. **Pull in Graphics' outliers.** Eight pages are more than two links from
    [Field 4 Graphics](../public/docs/afield_four_graphics.md):
    [Body and hand tracking](../public/docs/body_tracking.md),
    [Chromium](../public/docs/puppet_browser.md),
    [OpenDLSS-NR](../public/docs/open_dlss_nr.md),
    [the gaze marker](../public/docs/openxr_eye_intersection.md),
    [Pressure membranes](../public/docs/pressure_membranes.md),
    [Stereo video](../public/docs/stereo_video.md), [Syphon](../public/docs/syphon.md) and
    [Video particles](../public/docs/particle_video.md).
12. **Re-file or re-link four pages** whose links all go to another section:
    - [How an output panel decides its size](../public/docs/output_panel_sizing.md): filed
      under Internals, links go to Seeing Values
    - [Field's server surface](../public/docs/server_surface.md): Internals, links go to Start
      Here
    - [What a document is on disk](../public/docs/document_on_disk.md): Internals, links go to
      Working in a Document
    - [Sound: from a pattern to something you can hear](../public/docs/sound.md): Start Here,
      links go to Drawing From Data
13. **Pick one way to end a page and apply it.** About 30 pages end with "Where to go next". 64
    end with "API reference", 18 with "Where each piece is documented" and 17 with "See also".
    Start with the 25 pages that link nowhere.

## 4. Look after older pages

14. **Re-link the stalest pages.** Nothing has linked to these in months, though most have been
    edited since:
    - Since May 19: [Boolean networks to FLine](../public/docs/boolean_networks_examples.md),
      [Sorting networks to FLine](../public/docs/sorting_networks_examples.md) and
      [Nano examples](../public/docs/nano_examples.md)
    - Since July/August: [Wave Function Collapse](../public/docs/wfc_examples.md),
      [Graphs that change while you watch](../public/docs/dynamic_graph_systems_examples.md),
      [Points into structure](../public/docs/spatial_geometry_examples.md),
      [Differentiating Functions](../public/docs/autodiff_examples.md) and the
      [Petri cheatsheet](../public/docs/petri_cheatsheet.md)
    - Since September 19: [FieldML examples](../public/docs/fieldml_examples.md), the
      [FieldML cheatsheet](../public/docs/fieldml_cheatsheet.md) and
      [FieldML's JVM bridge](../public/docs/fieldml_host_bridge.md)
    - Since mid-September: [The Semantic Index](../public/docs/semantic_index.md) and
      [Physics: particles, constraints and cloth](../public/docs/physics.md)

    Across the site, the newest link into each page was written under 30 days ago for 104
    pages, 30–60 days ago for 83, and over 60 days ago for 27.
15. **Review the pages the code has moved on from.** About 25 pages were last edited 28–41 days
    before the source they cite. Start with:
    - [Rewriting your own syntax](../public/docs/pattern_rewrite.md)
    - [DoughDSP](../public/docs/dough_dsp_sound.md)
    - [Boolean network music](../public/docs/boolean_network_music_examples.md)
    - [Learn from a shader on the GPU](../public/docs/gpu_shader_learning.md)
    - [Typography as geometry](../public/docs/typography_examples.md) (13 of the 14 files it
      cites have changed)
    - [How completions are ranked](../public/docs/completion_ranking.md)
    - [A Drawn Score, Heard](../public/docs/chain_drawn_score.md)
16. **Attach transcripts to pages.** 122 transcripts name no docs page, including the
    path-tracing series, the motion/ARDY set, the Kratos playgrounds and the `madp_*` set. 109
    pages with code have no transcript behind them.

## 5. Decide on the code nobody documents

17. **Write a page, or mark each as internal**, for the largest public APIs no page or
    transcript uses:
    - Steam Audio spatial: `Simulation.kt`, `AmbisonicsEffects.kt`, `Devices.kt`,
      `AudioBuffer.kt`
    - The Datalog engine
    - Pointing and picking
    - MQTT
    - `modelling.kt`
    - The RectSL and StackSL study code

    Coverage is lowest in examples (10%), app (26%) and tools (26%).

## 6. Keep it from drifting again

18. **When a page lands, add one link to it from an existing page.** Plenty of links get
    written (about 530–560 a month in October and November), but they mostly go from new pages
    to old ones. As a result, 87 pages over a month old have no link from any page written after
    them. A landing guard or a `tools/docs_notes.py` nudge could check for this.
19. **Re-run the report every so often** and track a few numbers:

    | Measure | 2026-11-21 |
    | --- | --- |
    | Orphans | 32 |
    | Drawing From Data pages within two links of its main page | 24 of 93 |
    | Links with a link back | 38% |
    | Pages last linked under 30 days / 30–60 days / over 60 days ago | 104 / 83 / 27 |

## Follow-up on 2026-11-22

The [Drawing From Data overview](../public/docs/drawing_from_data.md) leads with what a reader
already has. It gives each of the existing navigation groups a place to start. The Tour links
to [Where to Go Next](../public/docs/staging.md), and all 42 graphics pages are within two
hand-written links of [Field 4 Graphics](../public/docs/afield_four_graphics.md).

Two examples now carry work across systems.

- [Watch a route change](../public/docs/mqtt.md#watch-a-route-change) publishes an editable
  wiring state over MQTT. Datalog derives connectivity and its proof from the received
  snapshot. `Datalog.replaceFacts` makes removals visible to recursive rules and keeps the
  solved model when the same snapshot arrives again.
- [Point at a face and find its source](../public/docs/chain_polytope_render.md#point-at-a-face-and-find-its-source)
  uses a ray pick to highlight a complete Conway polygon and recover the seed element that
  produced it. The example keeps that identity across different normal policies.

MADP, ARDY/CLoSD and Kratos starting points now link to the sections that explain them. The
landing precheck flags new pages without a link from an existing hand-written page. The
default graph command also works with its code layer switched off.

| Measure | Upstream `6d6af3a42` | Follow-up |
| --- | ---: | ---: |
| Orphans | 34 | 0 |
| Dead ends | 24 | 0 |
| Spokes | 52 | 52 |
| Links with a link back | 458 of 1,140 (40%) | 580 of 1,305 (44%) |
| Drawing From Data pages within two links of Patterns | 24 of 93 | 50 of 94 |
| Drawing From Data pages within two links of the new overview | — | 76 of 94 |
| Graphics pages within two links of Field 4 Graphics | 34 of 42 | 42 of 42 |

The 34 upstream orphans are the updated baseline for this pass. The original audit above
reported 32 at its earlier revision.

### Remaining work

- Add second routes to remaining spokes when a reader has a reason to follow them.
- Use the source drift table to choose examples to re-run. The source timestamp can reflect
  an edit unrelated to the example, so look at the changed calls before recapturing.
- Connect remaining transcripts to the sections that teach them as the examples are reviewed.
### What the API gaps meant

- [Steam Audio scenes](../public/docs/steam_audio_scene.md) now has a runnable direct sound
  comparison with editable screen geometry, listening clips and links to the reflection and
  pathing experiments. The optional acceleration device wrappers are an implementation-level
  topic.
- [Adaptive modelling](../src/jvmMain/kotlin/afield/math/control/modelling.kt) is the controller
  that fits distributions of measured values. Its demonstrated use is
  [fieldProbeAutoControl](../src/jvmMain/kotlin/afield/graphics/shader/shaders.kt), which feeds GPU
  samples into the controller and writes the resulting shader parameters. The
  [Field Probes page](../public/docs/field_probes.md) teaches measurement. A worked example that
  closes the loop and shows the response to a disturbance is still missing.
- [RectSL](../public/docs/rectsl_conversation.md), [StackSL](../public/docs/stacksl_conversation.md)
  and [Stack Auto](../public/docs/stackauto_conversation.md) already have worked studies,
  editable starters and descriptions of the study tools. The report attributed coverage to
  source files directly named or called by pages and transcripts. Most calls into the
  revision, trace and variant machinery go through the box language and its tools. Treat the
  low score as a question about direct Kotlin API coverage.
