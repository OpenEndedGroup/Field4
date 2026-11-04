# Add the missing captures to output_panel_sizing.md (1 section)

- **Handoff:** `34280888-f626-4f71-a7f7-7f42a8a87e3f` (project)
- **Filed from:** https://openendedgroup.com/field4/output_panel_sizing/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 34280888 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### A panel follows its content back down (`public/docs/output_panel_sizing.md:220`, medium, docs-runner)

- **Point made:** A panel grows as soon as its content grows, waits half a second after the content shrinks before shrinking, and never auto-sizes past 80% of the window. All of this is described and none of it is shown.
- **Capture:** A runner video (capture="box", execution="launch", around 6 s) of a box that shows tall content, then short content, then a wide table in one panel, one frame per second, so the reader watches the panel grow, pause and shrink back. A three-frame screenshot strip would also work. The output-panel-shrink UI regression already drives the same sequence and could serve as the drive script.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
