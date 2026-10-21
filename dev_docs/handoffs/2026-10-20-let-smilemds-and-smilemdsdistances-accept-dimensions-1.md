# Let smileMds and smileMdsDistances accept dimensions = 1

- **Handoff:** `41e842e3-afe9-4b85-ba0e-f09b7a2163a8` (project)
- **Filed from:** https://openendedgroup.com/field4/projection_examples/
- **Filed by:** human, 2026-10-20 11:10
- **Close with:** `python tools/docs_notes.py handoff-close 41e842e3 "what happened"`

Rule 25 sharp edge on `projection_examples.md` ("Sharp edges" list):

> **`dimensions` must be at least 2**, and all four entry points say so themselves rather than
> letting Smile's own message through (... `require(dimensions in 2 until vectors.size)` ...). If you
> want one number per thing, fit two and use the first column.

The page documents a workaround the code could do itself. Fix: in
`src/jvmMain/kotlin/afield/math/statistics/smile_mds.kt`, accept `dimensions = 1` for the MDS
entry points (fit two internally and return the first column, or call Smile with k = 1 if it
works), and decide whether t-SNE should too. Delete this bullet from the page in the same change.
