# Add the missing captures to keyframe_autosweep.md (1 section)

- **Handoff:** `dc214121-4c8f-40c2-bb87-a9d93ff21487` (project)
- **Filed from:** https://openendedgroup.com/field4/keyframe_autosweep/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close dc214121 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Blending past the saved keys (`public/docs/keyframe_autosweep.md:359`, medium, needs-gpu-or-hardware)

- **Point made:** Extrapolated blends produce looks nobody authored, beyond the hull of the saved keys, but no render shows what gain 1.0 or 1.5 looks like next to a convex blend.
- **Capture:** A contact-sheet capture from the same GPU sweep code as the existing contact-sheet.png: one convex blend, then extrapolated renders at gain 1.0 and 1.5 with the same pole, each captioned with its weights and extrapolationGain from the manifest.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
