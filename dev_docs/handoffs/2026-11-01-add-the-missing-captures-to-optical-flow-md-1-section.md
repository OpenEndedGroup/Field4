# Add the missing captures to optical_flow.md (1 section)

- **Handoff:** `ed3aa297-c64d-4f57-b8fb-5f94bef4f967` (project)
- **Filed from:** https://openendedgroup.com/field4/optical_flow/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close ed3aa297 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### A forward–backward confidence map (`public/docs/optical_flow.md:97`, medium, needs-gpu-or-hardware)

- **Point made:** consistency=true produces a forward-backward confidence map texture, which the page describes but never shows.
- **Capture:** A GPU render of flow.consistencyMap next to the motion field for the starting-point images, from the portable backend on the M1, like the existing macOS figures.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
