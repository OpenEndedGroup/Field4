# Give MDS a real fit-quality figure instead of a proportion that always sums to 1

- **Handoff:** `02001d2c-5136-465e-805b-0e8b8d60fde5` (project)
- **Filed from:** https://openendedgroup.com/field4/projection_examples/
- **Filed by:** human, 2026-10-15 13:44
- **Close with:** `python tools/docs_notes.py handoff-close 02001d2c "what happened"`

`public/docs/projection_examples.md` has to warn, twice (the paragraph after the edit-distance figure, and the "Sharp edges" bullet), that `proportion` on `SmileMdsProjection` / `SmileMdsProximityProjection` always sums to 1:

> And `proportion` sums to **1.0000** here, which is not the reassurance it looks like. It is the retained eigenvalues divided by *their own* L1 norm ... so it sums to 1 whether you kept 2 components or 15. ... The stress figure above is the number that answers that, and you have to compute it yourself.

> - **`proportion` always sums to 1.** See the first fence. It is not an explained-variance figure.

The name reads as explained variance and is not. Fix in `src/jvmMain/kotlin/afield/math/statistics/smile_mds.kt`: rename (e.g. `retainedShare`, keeping a deprecated `proportion` alias) and/or add a real fit-quality field computed by the call (`stress1`, and the plane/input distance correlation) so readers stop computing stress by hand. Delete the two paragraphs above (and simplify the fence that computes stress, via a code handoff of its own if needed) in the same change.
