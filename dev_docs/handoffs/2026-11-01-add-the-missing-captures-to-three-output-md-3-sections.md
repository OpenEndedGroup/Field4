# Add the missing captures to three_output.md (3 sections)

- **Handoff:** `524e19a7-0c41-4e90-b96b-69201e323bfc` (project)
- **Filed from:** https://openendedgroup.com/field4/three_output/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 524e19a7 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### A studio look (`public/docs/three_output.md:256`, high, docs-runner)

- **Point made:** studio() gives a model ACES tone mapping, soft-shadowed key light, rim light, ambient occlusion and a dark gridded ground, which is a purely visual claim, and the section has a code fence but no picture.
- **Capture:** Turn the fence into a diffstatic_runner with capture="box" open-outputs, using a real marched vase (or any library mesh) and a metallic ThreeSurfaceMaterial. A plain three{} view of the same mesh beside it would show the difference.

### Turning views for a display (`public/docs/three_output.md:159`, medium, needs-capture-system-work)

- **Point made:** A six-shape contact sheet turns at 15 degrees a second, every panel about its own centre, and the reader sees neither the sheet nor the motion.
- **Capture:** Run the existing six-shape threeGrid fence as a runner capture (capture="box"). Best is a short MP4 or a two-frame strip, a few seconds apart, that shows the turn. That needs video/strip support in the box capture, or a drive script.

### See through surfaces (`public/docs/three_output.md:221`, medium, needs-capture-system-work)

- **Point made:** X-ray reveals the geometry behind filled surfaces, with edges kept legible, and this visual mode is described but never shown.
- **Capture:** Use a runner capture of a box holding an inner mesh inside an outer shell (for example the drilled block from the section example), with a drive script that clicks X-ray and sets the Surface slider before screenshot-box.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
