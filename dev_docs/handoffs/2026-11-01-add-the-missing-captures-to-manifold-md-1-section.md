# Add the missing captures to manifold.md (1 section)

- **Handoff:** `bba78381-e56c-42a6-8640-c3d17bd5efb1` (project)
- **Filed from:** https://openendedgroup.com/field4/manifold/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close bba78381 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Approximate a cubic with an explicit error bound (`public/docs/manifold.md:542`, low, docs-runner)

- **Point made:** flatten(maximumDeviation) subdivides a CubicBezier3D until each chord stays within the bound.
- **Capture:** A box drawing of one cubic flattened at two or three tolerances (for example 1.0, 0.1 and 0.01), with the vertex count of each polyline, showing the subdivision get denser.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
