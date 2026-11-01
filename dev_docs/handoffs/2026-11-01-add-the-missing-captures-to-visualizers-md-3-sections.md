# Add the missing captures to visualizers.md (3 sections)

- **Handoff:** `be734220-f823-4211-ac63-145a8556b80c` (project)
- **Filed from:** https://openendedgroup.com/field4/visualizers/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close be734220 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Browsing a glTF scene (`public/docs/visualizers.md:1271`, high, docs-runner)

- **Point made:** column(...)/tree(...) over a GltfSupport.loadModel scene lists its node hierarchy, transforms, meshes, materials and animations.
- **Capture:** A diffstatic_runner screenshot (capture="box" open-outputs="true") of the existing snippet loading dev_docs/agents/fixtures/path_tracing/transforms.gltf, showing the column browser expanded to the root node and a mesh. Right now the only capture in the section is the unrelated logs(...) demo that follows.

### Recording a slider into keyframes (`public/docs/visualizers.md:1815`, medium, needs-capture-system-work)

- **Point made:** recordToggle turns a slider performance into a thinned keyframe curve that opens under the panel, and playControl plays it back into the slider.
- **Capture:** A box video: press 'rec size', drag the size slider in a sweep, press it again, then show the keyframe curve with its handful of keys appearing under the panel. The drag needs a drive script, like the one the Handling a slider video uses.

### Shared time cursors and transport (`public/docs/visualizers.md:2043`, medium, docs-runner)

- **Point made:** Players and time-based views that share a syncGroup move one cursor together, with per-component offsets.
- **Capture:** A UI video of two audio players (or a player and a time view) in the same syncGroup with an offset: start playback on one and watch both playheads move in lockstep. Use capture="ui" with video= and wait for playback.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
