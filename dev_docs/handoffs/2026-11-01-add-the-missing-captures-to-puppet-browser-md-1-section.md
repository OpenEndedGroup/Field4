# Add the missing captures to puppet_browser.md (1 section)

- **Handoff:** `d94107bf-d5d4-4689-aed2-d7d203093104` (project)
- **Filed from:** https://openendedgroup.com/field4/puppet_browser/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close d94107bf "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Pages as data (`public/docs/puppet_browser.md:198`, medium, needs-capture-system-work)

- **Point made:** A headless browser reads a page's table into columns a Plotly or FLine box can plot, and the reader never sees a page turned into data.
- **Capture:** Use a runner box capture (requires browser-bridge) that loads a small local HTML table page headlessly and displays readings.column/numbers as a table plus a small FLine bar plot of depth.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
