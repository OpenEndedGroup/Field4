# Add the missing captures to rectsl_cheatsheet.md (3 sections)

- **Handoff:** `20e51325-befc-4cb8-b907-4dd0793ec3e9` (project)
- **Filed from:** https://openendedgroup.com/field4/rectsl_cheatsheet/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 20e51325 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### `pat`: one copy per event (`public/docs/rectsl_cheatsheet.md:744`, high, docs-runner)

- **Point made:** Sixteen kick copies each drop from y=5.5, flash and march across the wall, restarting at each bar, and a second rect reads atom/dur/param, all described only in prose.
- **Capture:** Short video (or a time-strip of frames) of the example scene rendered through RectSL's real renderer, as rectsl_examples.md does for its scenes.

### Rhythm in space (`public/docs/rectsl_cheatsheet.md:965`, high, docs-runner)

- **Point made:** strip turns "bd [sd sd] hh*4" into a ruler of one wide, two half and four quarter-width marks, a static visual claim with no picture.
- **Capture:** Screenshot of the example scene at one instant, rendered by RectSL.

### Patterns as values (`public/docs/rectsl_cheatsheet.md:806`, medium, docs-runner)

- **Point made:** Seven rects move under seven pattern kinds (smooth sine, the same sine stepped by segment 8, held rests, rounded saw, Perlin), and the difference between easing and jumping is the whole point.
- **Capture:** Video of the seven-rect scene, or an x-over-time trace per rect, from the real RectSL evaluation.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
