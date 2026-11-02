# Add the missing captures to fieldml_host_bridge.md (1 section)

- **Handoff:** `f01e4bf6-dfa3-468e-b71c-889eb634a612` (project)
- **Filed from:** https://openendedgroup.com/field4/fieldml_host_bridge/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close f01e4bf6 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Passing a function (`public/docs/fieldml_host_bridge.md:68`, high, docs-runner)

- **Point made:** A FieldML lambda handed to FLine.transform bends a two-segment line into a sine-warped path, with p typed as Vec3 from the JVM signature.
- **Capture:** Make the snippet a diffstatic_runner fieldml box that ends by displaying the line (e.g. via hostStaticCall to lines/showLines on thisBox) and capture="box" the output: the straight L-shape next to its warped version, proving the callback actually ran per point.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
