# Decide how much of the watchmaker trial report stays on stacksl_evolve

- **Handoff:** `3dcbf9ae-4704-4d94-bc2c-83d361a59588` (project)
- **Filed from:** https://openendedgroup.com/field4/stacksl_evolve/
- **Filed by:** human, 2026-10-10 12:10
- **Close with:** `python tools/docs_notes.py handoff-close 3dcbf9ae "what happened"`

`stacksl_evolve.md` carries a full experiment report in the middle of the user-facing page: "How well it works" (a six-row comparison table of trial arms, including one-pass and pre-second-look arms that no setting reproduces), a median-over-time figure, a held-out figure, and "What it costs" with token counts from one dated trial run. These are the trial's findings (`StackSLWatchmakerTrial`, 2026-10-08), not how to use the watchmaker.

Decide: keep a short "how well it works" (one sentence + the held-out figure, the `watchmaker()` and no-model rows only), and move the full table, the over-time figure and the cost numbers into a `??? aside` or `dev_docs/`? The numbers are typed by hand from a committed trial JSON, so they also go stale with any rerun (rule 20).
