# Add the missing captures to tetgen.md (1 section)

- **Handoff:** `1a08771b-7053-4523-819f-ac2ac88d001a` (project)
- **Filed from:** https://openendedgroup.com/field4/tetgen/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 1a08771b "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Into Kratos (`public/docs/tetgen.md:140`, medium, needs-capture-system-work)

- **Point made:** 4-node tets lock in bending (62% of the Euler–Bernoulli tip deflection even at 1,080 elements), while 10-node elements come within 5% at thirty.
- **Capture:** A real Kratos structural.bodyFitted solve of the cantilever, meshed with order=1 and with order=2, rendered as deformed shapes coloured by displacement side by side, or as a computed tip-deflection plot against the analytic line.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
