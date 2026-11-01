# Add the missing captures to flow_grain.md (1 section)

- **Handoff:** `4a1be5a2-076e-46a2-865d-3e14779f608d` (project)
- **Filed from:** https://openendedgroup.com/field4/flow_grain/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 4a1be5a2 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Motion history for the camera and for objects (`public/docs/flow_grain.md:74`, low, needs-gpu-or-hardware)

- **Point made:** Camera and object motion history drive the motion view (flowLook.w = 3/4), where the moving marker differs from the stationary world, but only the colour view is shown.
- **Capture:** Native-window capture through the flow_grain.yaml macro with flowLook.w set to 4 (amplified displacement), placed beside the existing flow.png.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
