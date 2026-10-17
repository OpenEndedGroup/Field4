# Label sample vs population conventions on the summary-stats card

- **Handoff:** `ae082bab-45c7-4a18-aee7-4fdb0fdae48c` (project)
- **Filed from:** https://openendedgroup.com/field4/statistics_examples/
- **Filed by:** human, 2026-10-15 13:44
- **Close with:** `python tools/docs_notes.py handoff-close ae082bab "what happened"`

Sharp edge (rule 25) on public/docs/statistics_examples.md, "Summary statistics":

> Read the card's labels carefully, because two of them are inconsistent and the inconsistency is in the source rather than the rendering. `stddev` and `variance` are the **sample** (divide by n−1) forms; `skew` and `kurtosis(ex)` are the **population** (divide by n) forms ... having both on one card is a trap.

Fix in code: make `summaryHtml()` / `summaryStatsBoxToHtml` (afield/math/statistics/stats.kt) label the conventions explicitly (e.g. `sd (n−1)`, `skew (pop.)`) or expose bias-corrected skew/kurtosis and show one consistent convention. Update `StatsSummaryTest` if fields change, recapture summary_card.png, and delete the "trap" paragraph's warning in the same change (keep the NumPy/SciPy mapping).
