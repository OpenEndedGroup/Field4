# Make polytope canonicalize() scale-invariant

- **Handoff:** `df28942f-28b3-4ca0-bb79-0aa7b5b1d7d2` (project)
- **Filed from:** https://openendedgroup.com/field4/polytope_examples/; also affects chain_ceres_manifold_contours.md
- **Filed by:** human, 2026-10-16 09:16
- **Close with:** `python tools/docs_notes.py handoff-close df28942f "what happened"`

Decided 2026-10-16 (Marc, handoff be644426; see `dev_docs/handoffs/2026-10-16-decide-cube-scale-unit-circumradius-changes-kkc-canonicalize.md`): make polytope `canonicalize()` scale-invariant before deciding `cube()`'s scale.

Evidence: with `cube()` at unit circumradius instead of side 2, kkC canonicalize ends with signed volume +372.5 instead of negative (no longer inside out), spread 1.674 instead of 1.930, and 28 Ceres iterations instead of 12. The outcome depends on the seed's scale.

Work:

1. Normalise on entry: centre the polytope and scale it to unit circumradius (or unit mean edge, whichever the objective's tolerances assume). Run the existing canonicalize, then map back to the input's centre and scale. Do the same for any sibling realization entry points that take an unnormalised seed (the aC realization).
2. Add a test that canonicalize(P) and canonicalize(k·P) agree up to scale for several k, including the side-2 cube vs the unit cube on kkC.
3. Recapture polytope_examples.md and chain_ceres_manifold_contours.md. If kkC is no longer turned inside out at either scale, rewrite the chain page's middle-row paragraph around what actually happens, using measured numbers, never invented ones.
4. Then put the cube question back to Marc (unit circumradius like the other Platonic seeds, or keep side 2); it should be cosmetic by then.
