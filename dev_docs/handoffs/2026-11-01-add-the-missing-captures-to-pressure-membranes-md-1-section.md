# Add the missing captures to pressure_membranes.md (1 section)

- **Handoff:** `4ca6e68b-483e-4cae-9ae3-b5955231b8f1` (project)
- **Filed from:** https://openendedgroup.com/field4/pressure_membranes/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 4ca6e68b "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### A complete small example (`public/docs/pressure_membranes.md:12`, high, docs-runner)

- **Point made:** An octahedron inflated to 1000 Pa relaxes into a pressurized membrane, but the page shows neither the inflated shape nor the printed solver reason/residual; it has no images at all.
- **Capture:** Convert the plain diffstatic fence into a diffstatic_runner capture="box" fence that prints solution.reason and residual, and draws Solution.positions before and after as a TriMesh card (or FLine wireframe), so the reader sees the octahedron rounding out. Better still, use a finer icosphere to show it approaching a sphere.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
