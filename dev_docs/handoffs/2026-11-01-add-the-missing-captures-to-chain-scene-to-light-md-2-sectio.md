# Add the missing captures to chain_scene_to_light.md (2 sections)

- **Handoff:** `0d950d81-e6e7-4522-bba2-b3680e192c1d` (project)
- **Filed from:** https://openendedgroup.com/field4/chain_scene_to_light/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 0d950d81 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### From a scene to an image (`public/docs/chain_scene_to_light.md:84`, medium, docs-runner)

- **Point made:** RectSLImager renders a scene on the CPU, and as-typed frames differ visibly from linear frames.
- **Capture:** A runner capture of scene.frame(1.5,256,128) next to the linear=true frame, displayed through PngEncoder in the box output.

### Running it live (`public/docs/chain_scene_to_light.md:254`, medium, needs-capture-system-work)

- **Point made:** Box 1 sends the scene over sACN and box 2 redraws the rig from what arrives on the loopback.
- **Capture:** A window capture of the chain_scene_to_light transcript with the sender's look and the receiver's reconstructed rig side by side after both are launched.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
