# Add the missing captures to chart.md (2 sections)

- **Handoff:** `4f85f488-e481-4326-a302-489f50c92aa7` (project)
- **Filed from:** https://openendedgroup.com/field4/chart/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 4f85f488 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Keeping a figure inside its frame (`public/docs/chart.md:167`, high, docs-runner)

- **Point made:** clip cuts lines, area fills, bars and points to plotRect and wraps titles, which only makes sense next to a before/after picture.
- **Capture:** Add the clip=false vs clip=true pair with an explicit yDomain from the visualization.chart-gallery FIELD_TEST (the pairs already exist in dev_docs/chart_clip_and_note.md), committed to assets/chart_gallery.

### From a DataFrame (`public/docs/chart.md:147`, medium, docs-runner)

- **Point made:** DataFrame.chart() picks the axes for you and gives bars of age per name, but the auto-chosen result is not shown.
- **Capture:** Add people.chart() to the chart-gallery FIELD_TEST and commit the PNG like the other gallery figures.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
