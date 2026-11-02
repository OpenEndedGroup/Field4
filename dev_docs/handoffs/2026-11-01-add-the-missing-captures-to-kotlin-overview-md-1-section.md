# Add the missing captures to kotlin_overview.md (1 section)

- **Handoff:** `f38a41bc-ee8c-4d4c-8d4f-190bb36ed884` (project)
- **Filed from:** https://openendedgroup.com/field4/kotlin_overview/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close f38a41bc "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Editing a running box (`public/docs/kotlin_overview.md:43`, medium, needs-capture-system-work)

- **Point made:** Changing a var and pressing Alt/Option-Enter on that line changes a running loop without restarting it.
- **Capture:** Short video (video= attribute) of a box running a loop that prints or draws using speed, with a drive script editing 0.5 to 2.0 and sending Alt-Enter on that line, so the output visibly speeds up mid-run.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
