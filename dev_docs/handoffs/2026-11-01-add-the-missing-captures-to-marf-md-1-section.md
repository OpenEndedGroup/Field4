# Add the missing captures to marf.md (1 section)

- **Handoff:** `34f7ea43-ff71-4c70-b907-c9e17e123de6` (project)
- **Filed from:** https://openendedgroup.com/field4/marf/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 34f7ea43 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### A reader's two outputs: a signal and triggers (`public/docs/marf.md:164`, high, docs-runner)

- **Point made:** Two readers of one bank used as x and y coordinates trace a path that stays coherent without being synchronised, and the fline/lines() code that draws this path has no output.
- **Capture:** Make the second fence a diffstatic_runner capture="box" of the lines(path, ...) output, so the box draws the real path. An audio= render of the gain/pan voice would also fit, but the path is the punchline.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
