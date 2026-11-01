# Add the missing captures to direct_lighting.md (1 section)

- **Handoff:** `2b40498e-f871-4a22-900f-5241879a1b04` (project)
- **Filed from:** https://openendedgroup.com/field4/direct_lighting/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 2b40498e "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Edit the scene (`public/docs/direct_lighting.md:84`, medium, needs-gpu-or-hardware)

- **Point made:** The one-light study and the RTXDI multi-light variant animate a lit scene with soft shadows whose softness follows the emitter's edge lengths, but this section shows no frame of what the code produces.
- **Capture:** Native window capture of the DirectLightingDemo box, comparing RAY_QUERY with a small and a large emitter, or RAY_QUERY against RTXDI with temporal and spatial reuse.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
