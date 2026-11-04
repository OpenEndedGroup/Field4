# Add the missing captures to boolean_networks_examples.md (1 section)

- **Handoff:** `0462b601-cae7-42b7-817b-7293b42ff4cb` (project)
- **Filed from:** https://openendedgroup.com/field4/boolean_networks_examples/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 0462b601 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Boolean attractors as drum grids (`public/docs/boolean_networks_examples.md:198`, medium, docs-runner)

- **Point made:** A Boolean limit cycle is already a rhythm, and gating it gives kick/snare/hat triggers.
- **Capture:** The drum-grid picture exists. What is missing is an audio= render of the triggers played through Field's sample voices (Dirt-Samples), so the reader hears the rhythm.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
