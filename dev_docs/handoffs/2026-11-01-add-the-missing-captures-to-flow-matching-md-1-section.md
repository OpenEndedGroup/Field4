# Add the missing captures to flow_matching.md (1 section)

- **Handoff:** `b6ea5bef-8739-437f-8dff-46811540e39c` (project)
- **Filed from:** https://openendedgroup.com/field4/flow_matching/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close b6ea5bef "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Settings that change the result (`public/docs/flow_matching.md:517`, high, docs-runner)

- **Point made:** tanh gives about four times the radial spread of leaky on the ring, and learning-rate decay drops the spread from 9.2 to 3.6.
- **Capture:** Side-by-side runner captures of the ring samples for activation=tanh vs leaky, and for constant vs decaying learning rate, drawn the same way as the page's earlier ring figure, with the spread printed under each.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
