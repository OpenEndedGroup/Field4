# Add the missing captures to ar_reference.md (1 section)

- **Handoff:** `31905db8-3f34-4e79-9539-20f12aa52258` (project)
- **Filed from:** https://openendedgroup.com/field4/ar_reference/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 31905db8 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### FLine graphics (`public/docs/ar_reference.md:86`, medium, docs-runner)

- **Point made:** Assigning an FLine into stage creates filled/stroked Three.js geometry in the RemoteJS head.
- **Capture:** Screenshot of the in-Field head preview (the same mechanism as the remote-ar screenshots earlier on the page) after running the filled-triangle snippet.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
