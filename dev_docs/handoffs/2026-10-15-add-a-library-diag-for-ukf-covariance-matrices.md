# Add a library diag() for UKF covariance matrices

- **Handoff:** `814db563-b743-4f6f-b3a5-5a52f5ce6841` (project)
- **Filed from:** https://openendedgroup.com/field4/state_estimation_examples/
- **Filed by:** human, 2026-10-15 13:44
- **Close with:** `python tools/docs_notes.py handoff-close 814db563 "what happened"`

`public/docs/state_estimation_examples.md` ("A tiny helper: `diag`") says:

> Every example defines a one-liner for diagonal matrices — there is no library `diag`, and one line is short enough to keep in the box

Every UKF example re-declares `fun diag(vararg values: Double): Matrix`. That is a missing API, not a page fact.

Fix: add a `diag(vararg Double): Matrix` (or `Matrix.diag`) beside `typealias Matrix` in `src/jvmMain/kotlin/afield/math/estimation/pf.kt`, drop the local definitions from the page's fences (and any transcripts/smokes that mirror them, recapturing as needed), and delete or rewrite the "A tiny helper" section in the same change.
