# Add the missing captures to reconstruction_plumbing.md (1 section)

- **Handoff:** `bc5c0c59-bff2-44b7-9ead-71f0fc27e6dd` (project)
- **Filed from:** https://openendedgroup.com/field4/reconstruction_plumbing/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close bc5c0c59 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Write a small G-buffer from `moduleHere()` (`public/docs/reconstruction_plumbing.md:40`, medium, needs-gpu-or-hardware)

- **Point made:** One raster module writes several attachments (color, normals, motion and so on) at once.
- **Capture:** Real texture-probe captures of each colour attachment of the RenderToTexture, laid out as a strip.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
