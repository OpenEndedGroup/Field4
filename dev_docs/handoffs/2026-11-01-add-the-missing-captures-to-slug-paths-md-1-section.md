# Add the missing captures to slug_paths.md (1 section)

- **Handoff:** `b5ef2020-0a2d-49cc-b8d9-477a86903633` (project)
- **Filed from:** https://openendedgroup.com/field4/slug_paths/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close b5ef2020 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Slug filled paths (`public/docs/slug_paths.md:1`, high, needs-gpu-or-hardware)

- **Point made:** Slug renders a filled FLine as analytic GPU curve coverage whose boundary stays smooth at any size, here a curved outline with a square hole.
- **Capture:** Native-window capture of canonical_graphics lesson 5e (Slug renderer plus Slug filled paths box), like the capture="native-window" fences on chain_petri_sculpture. Best is a far view and a close zoom on the edge, which shows the smoothness claim. This needs the native field-slug helper built and a live Vulkan renderer.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
