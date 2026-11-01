# Add the missing captures to ai4_painting.md (1 section)

- **Handoff:** `9c2650f5-a853-40f1-b46e-88c3982bf8d0` (project)
- **Filed from:** https://openendedgroup.com/field4/ai4_painting/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 9c2650f5 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Browsing the saved paintings (`public/docs/ai4_painting.md:50`, medium, needs-capture-system-work)

- **Point made:** The States tree holds a node per completed painting, with Reference/Compare controls. The panel is described but never shown.
- **Capture:** A window capture of the States tree for a Studio conversation with two or three paintings, one earlier node selected. It needs a recorded conversation and a drive script.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
