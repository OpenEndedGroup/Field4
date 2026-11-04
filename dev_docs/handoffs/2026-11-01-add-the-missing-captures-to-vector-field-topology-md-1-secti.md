# Add the missing captures to vector_field_topology.md (1 section)

- **Handoff:** `79028652-b76c-4ce4-ba59-461293c55412` (project)
- **Filed from:** https://openendedgroup.com/field4/vector_field_topology/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 79028652 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Following a flow through time (`public/docs/vector_field_topology.md:199`, high, docs-runner)

- **Point made:** FeatureTracker can follow the swirls (sinks, sources, saddles) of a flow through time, but the reader never sees a single track.
- **Capture:** Run the tracker over the eagle film flow frames already used on the page and draw the matched feature tracks as FLines over a darkened frame (or a short video of tracked points), screenshot through the runner like eagle_bird.png.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
