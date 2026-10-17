# Make polytope canonical diagnostics catch negative volume and self-intersection

- **Handoff:** `5d64e193-b74a-44f6-bd24-ad0da16e4aca` (project)
- **Filed from:** https://openendedgroup.com/field4/chain_ceres_manifold_contours/
- **Filed by:** human, 2026-10-15 13:42
- **Close with:** `python tools/docs_notes.py handoff-close 5d64e193 "what happened"`

Rule 25 sharp edge on `public/docs/chain_ceres_manifold_contours.md`, section "`kkC`, and the solver that reaches it":

> In the bottom row, `relaxWithCeres(RelaxationPlan.canonicalConditions())` converges ... Every canonical diagnostic reports success, but the layer stack does not agree ... So the surface passes through itself. ... The `riemann` and `overlap` lines in the box are a self-intersection test you can run on any solid, which the canonical diagnostics do not include.

And in the first section: "`canonicalize()` turns `kkC` inside out: its signed volume goes negative ... A negative volume is reason enough to refuse the result, so the box does."

Diagnostics that report success on a self-intersecting result are a sharp edge. Fix in code: have the canonical diagnostics (and/or `canonicalize()` / `relaxWithCeres`) check signed volume and self-intersection (e.g. the fill-rule disagreement test the page builds by hand) and report failure, so a box need not refuse the result itself. Then rewrite the section as "the diagnostics catch this" rather than "the diagnostics miss this" in the same change. Decide with Marc whether the hand-built test stays as a teaching example.
