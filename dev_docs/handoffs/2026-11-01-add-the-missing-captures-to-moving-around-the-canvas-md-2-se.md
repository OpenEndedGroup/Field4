# Add the missing captures to moving_around_the_canvas.md (2 sections)

- **Handoff:** `91e72174-3ded-4f80-88e2-73882ff17667` (project)
- **Filed from:** https://openendedgroup.com/field4/moving_around_the_canvas/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 91e72174 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Framing the selection (`public/docs/moving_around_the_canvas.md:385`, high, docs-runner)

- **Point made:** alt-F fits the selection and its open output panels into the largest window region not covered by the editor/docs panels, zooming only as needed.
- **Capture:** Two ui window captures (like the split-canvas fences on this page): a scattered document with the editor open, before and after running frame-selected via a drive script; the after shot shows the selection framed in the uncovered region beside the editor.

### Showing and hiding layers (`public/docs/moving_around_the_canvas.md:493`, medium, docs-runner)

- **Point made:** The alt-L layers panel lists layers with box counts, eyes, solo and active-layer radio, and hiding a layer clears the canvas down to what you are working on.
- **Capture:** capture="ui" region="window" clipped to the layers FieldPanel (as the bookmarks panel capture does), with a document of three layers and one hidden, so the canvas shows fewer boxes beside the panel.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
