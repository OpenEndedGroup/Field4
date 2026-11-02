# Add the missing captures to solid_notation.md (1 section)

- **Handoff:** `88d22f8b-b472-4dd0-a178-756e74dbe877` (project)
- **Filed from:** https://openendedgroup.com/field4/solid_notation/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 88d22f8b "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### The same source as a shader (`public/docs/solid_notation.md:281`, high, needs-gpu-or-hardware)

- **Point made:** toRaymarchFragment() hands back a complete sphere-traced fragment shader from the same SolidExpr, ready to paste into a pipeline.
- **Capture:** A box under the renderer that uses scene.toRaymarchFragment() as its fragment shader on a fullscreen quad, captured from the real Vulkan window, ideally beside the marched/exact mesh of the same scene from earlier on the page.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
