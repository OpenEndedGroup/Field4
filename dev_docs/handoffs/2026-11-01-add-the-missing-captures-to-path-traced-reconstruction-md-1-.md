# Add the missing captures to path_traced_reconstruction.md (1 section)

- **Handoff:** `453e16ea-39c5-4692-80a8-fdf44a161a85` (project)
- **Filed from:** https://openendedgroup.com/field4/path_traced_reconstruction/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 453e16ea "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Put a material shader in the box (`public/docs/path_traced_reconstruction.md:54`, high, needs-gpu-or-hardware)

- **Point made:** A GLSL material hook paints the floor with blue/orange stripes that carry through direct and indirect light and reflections, but the reader never sees the striped floor.
- **Capture:** Native renderer window capture of the RR Starting Point after the compute hook is installed: the striped floor, ideally next to a second capture with the frequency changed from 8.0 or the roughness from .3. Use a linux-nvidia capture-profile like the page's other figures.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
