# Add the missing captures to studies.md (1 section)

- **Handoff:** `257e2fc5-7de8-4ac8-b830-8a04c702b6e6` (project)
- **Filed from:** https://openendedgroup.com/field4/studies/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 257e2fc5 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Build derived results without blocking edits (`public/docs/studies.md:322`, high, docs-runner)

- **Point made:** StudyBuilds adds a persistent attempt history and an explicit Build button through showStudyBuilds("reports"), but in 125 lines the reader never sees that panel, its progress line or the attempt list.
- **Capture:** Run the 'reports' stage against a small SourceStudy in a docs runner box with capture="box" open-outputs, wait-output for the attempt to finish, then screenshot the showStudyBuilds panel with its Build button, the finished attempt and the summary.txt product.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
