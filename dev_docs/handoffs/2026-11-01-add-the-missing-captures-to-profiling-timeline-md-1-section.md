# Add the missing captures to profiling_timeline.md (1 section)

- **Handoff:** `e8e8e397-fe19-4c74-8f77-45d6e3408b67` (project)
- **Filed from:** https://openendedgroup.com/field4/profiling_timeline/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close e8e8e397 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Coroutine resumes (`public/docs/profiling_timeline.md:562`, high, docs-runner)

- **Point made:** `loop { work; delay(16) }` produces two resumes per turn, one that does the work and one that is empty, so percentiles taken over resumes cover a population that is half empty.
- **Capture:** A timeline capture (the same capture="ui" clip-selector="#profile-timeline" fence the page already uses) of a box running exactly that loop, zoomed so the alternating work and near-zero resumes are visible in its lane.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
