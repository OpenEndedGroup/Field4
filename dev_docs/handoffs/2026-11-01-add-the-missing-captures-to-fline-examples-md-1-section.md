# Add the missing captures to fline_examples.md (1 section)

- **Handoff:** `f7340d68-16ee-48a9-9b56-80ddbc3c7de4` (project)
- **Filed from:** https://openendedgroup.com/field4/fline_examples/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close f7340d68 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Circle fitting + subdivision (`public/docs/fline_examples.md:2945`, medium, docs-runner)

- **Point made:** fitCircle returns a centre, radius and error, and bySubdividing adds segments without changing the shape. On a page where every other operation has a picture, this section has only an API table.
- **Capture:** A diffstatic_runner screenshot fence like its neighbours: a noisy, roughly circular FLine with its fitted circle overlaid (radius and error drawn as a label), next to a cubic shape shown pointed=true at levels 0, 1 and 2, so the extra nodes sit on an unchanged curve.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
