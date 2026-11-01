# Add the missing captures to paint_canvas.md (2 sections)

- **Handoff:** `84dbdeca-868f-4346-b540-4e26b2a7816f` (project)
- **Filed from:** https://openendedgroup.com/field4/paint_canvas/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 84dbdeca "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Brushes that stay dirty (`public/docs/paint_canvas.md:416`, high, docs-runner)

- **Point made:** A brush keeps the paint it picks up and carries it into its next stroke, so a sky brush dragged through earth paints a dirtier horizon; a saved toolbox comes back just as dirty.
- **Capture:** Runner screenshot of the box: one canvas with the clean first sky stroke, a stroke through wet earth colour, then the second stroke with the same brush showing the carried colour. A third panel could show the same stroke after PaintToolbox.save/load to prove the restore. canvas.carried(brush) could be printed under it. Use the same diffstatic_runner screenshot pattern as the page's other sections.

### The canvas itself (`public/docs/paint_canvas.md:440`, medium, docs-runner)

- **Point made:** Grounds (Bare, Oil, OilRedGround, Linen/primed, RawCotton/RawLinen), wet vs dried render and PaintLight raking vs gallery each change how the same paint looks, but none of them is shown.
- **Capture:** Runner screenshot: a grid with one identical thin stroke and one impasto stroke on each PaintGround, plus a row showing the same canvas as render(), render(wet = true), PaintLight.Raking and PaintLight.Gallery. stats could be printed as text under it.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
