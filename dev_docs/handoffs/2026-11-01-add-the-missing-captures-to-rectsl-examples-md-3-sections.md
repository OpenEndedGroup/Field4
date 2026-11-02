# Add the missing captures to rectsl_examples.md (3 sections)

- **Handoff:** `797a5481-5bda-4a63-9690-721bf20f7014` (project)
- **Filed from:** https://openendedgroup.com/field4/rectsl_examples/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 797a5481 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Kolakoski rhythms (`public/docs/rectsl_examples.md:1117`, high, docs-runner)

- **Point made:** Kolakoski run and gap readings make two different rhythms from the same word, and the figure is prefix-stable, so it can grow without the existing hits moving.
- **Capture:** Run the three-row rectsl program through the same RectSL video/poster pipeline the neighbouring gallery entries use (mp4 plus png, like rectsl-radial-bloom). The reader should see the lit slots, the amber run row and the red gap row playing in time. If possible, add a second render at steps=32 under the 16-step one to show the prefix stability the section claims.

### A plotted figure: shared domain, axes, overlaid series, legends (`public/docs/rectsl_examples.md:1262`, high, docs-runner)

- **Point made:** The plotting layer overlays area, line and points series in one shared domain, with axes, numeral tick labels made of rects, a colorbar and a legend.
- **Capture:** Render both programs (the three-series figure and the hand-set ->xdomain/->margins plot) to a still PNG with the RectSL renderer, as the gallery does. The rect-drawn numerals and overlaid series need to be seen before a reader will believe them.

### Heatmap of a computed field (`public/docs/rectsl_examples.md:1211`, medium, docs-runner)

- **Point made:** linspace, data-map with sin and heat tile a rippling turbo-coloured field that fades in with a stagger.
- **Capture:** Render the program as an mp4 plus poster through the RectSL pipeline used elsewhere on the page. Its siblings have no output either: Colormap legend (1177), whose text says this 'is how you actually *see* a colormap' but shows no image of it; Bar chart from data (1194); and Scatter plot (1229). One strip with all seven colormap ramps plus the bar, heat and scatter renders would cover the whole Data visualization section.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
