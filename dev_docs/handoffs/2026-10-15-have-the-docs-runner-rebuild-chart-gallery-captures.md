# Have the docs runner rebuild chart gallery captures

- **Handoff:** `737cc92d-c4a9-428d-b0e5-ee7d2292f927` (project)
- **Filed from:** https://openendedgroup.com/field4/chart/
- **Filed by:** human, 2026-10-15 13:43
- **Close with:** `python tools/docs_notes.py handoff-close 737cc92d "what happened"`

`public/docs/chart.md`, aside "Agents: where the pictures come from", says:

> The docs runner does not rebuild them, so re-run that command after a change.

The chart-gallery figures are produced by the `visualization.chart-gallery` FIELD_TEST, outside the docs runner, so a change to `Chart` leaves the page's figures stale unless someone remembers. Fix: make the docs runner (or a `capture-profile`/hook in `public/docs_hooks.py`) regenerate them, or have a guard fail when chart sources are newer than the committed PNGs. Delete that sentence from the aside in the same change.
