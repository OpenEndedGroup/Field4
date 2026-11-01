# Add the missing captures to linear_algebra.md (2 sections)

- **Handoff:** `15e45099-d74e-4edb-b14d-2e1a4eb1e6aa` (project)
- **Filed from:** https://openendedgroup.com/field4/linear_algebra/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 15e45099 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Cross product: “which way is perpendicular?” (`public/docs/linear_algebra.md:262`, low, docs-runner)

- **Point made:** The cross product gives the perpendicular direction, a spatial claim made without a figure, while neighbouring sections use the isometric arrow renderer.
- **Capture:** Draw a, b and a.cross(b) with the existing isometric vec3 arrow display (as in the matrix_algebra transformed-point figure) in a runner box capture.

### Interpolation: `lerp` and `fma` (`public/docs/linear_algebra.md:281`, low, docs-runner)

- **Point made:** lerp moves a point along the segment between two vectors, but unlike the swizzle and arithmetic sections (which have captured vector figures) this section shows no output.
- **Capture:** Change the fence to a diffstatic_runner box capture in the style of assets/vector_algebra/algebra.png, drawing a, b and lerp(a, b, t) for several t values, including t outside 0..1.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
