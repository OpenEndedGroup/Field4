# Add the missing captures to topology_tracking.md (1 section)

- **Handoff:** `51219c67-1df6-4b23-9113-1a7d662daf9a` (project)
- **Filed from:** https://openendedgroup.com/field4/topology_tracking/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 51219c67 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Comparing fields by their topology (`public/docs/topology_tracking.md:126`, medium, docs-runner)

- **Point made:** persistenceDiagramDistance scores identical fields 0 and small shape changes little, and the code shows no computed numbers.
- **Capture:** Make the fence a runner capture over a few computed fields (the same hills, the hills moved slightly, a different field). Show a small table of exact and entropic distances, or the two persistence diagrams plotted with their distance.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
