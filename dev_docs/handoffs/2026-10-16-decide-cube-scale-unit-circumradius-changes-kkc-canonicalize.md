# Decide cube() scale: unit circumradius changes kkC canonicalize outcome

- **Handoff:** `be644426-6b18-43cc-a4a2-268738a17f0e` (project)
- **Filed from:** https://openendedgroup.com/field4/polytope_examples/; also affects chain_ceres_manifold_contours.md
- **Filed by:** human, 2026-10-16 04:41
- **Close with:** `python tools/docs_notes.py handoff-close be644426 "what happened"`

Refiled from the code batch (original: "Give cube() unit circumradius like the other Platonic seeds").

Tried on 2026-10-16: `cube()` with vertices at `(±1/√3, ±1/√3, ±1/√3)`. The polytope tests needed two
expectations rescaled, and that part is easy. The captures are not:

- `chain_ceres_manifold_contours.md`: with the unit cube, `kkC.canonicalize()` **no longer turns
  kkC inside out**. Signed volume is +372.5 (the page's argument depends on a negative one), with 470
  crossing face pairs and 1 − 2·overlap = 1.23. The page's middle-row paragraph would have to be
  rewritten around a different failure, or use a different example.
- `polytope_examples.md`, kkC canonicalize: spread 1.674 instead of 1.930; Ceres needs 28 iterations
  instead of 12. The realization (aC) figure: edge RMS 2.715e-01, planarity 1.103e-01, 7 iterations
  on "improvement" instead of 5 on "gradient".

So canonicalize()'s outcome depends on the seed's scale. That is a finding in its own right:
canonicalization should be scale-invariant.

Decision for Marc: (a) change the cube and rewrite the chain page's inside-out example, (b) keep side
2 as deliberate and reduce the paragraph to one sentence, or (c) first make canonicalize
scale-invariant (normalise on entry), which would make the seed scale moot.

## Decision, 2026-10-16 (Marc)

**(c) First make `canonicalize()` scale-invariant**: normalise on entry, restore scale on exit. The cube's scale is then a separate, cosmetic choice, to revisit once canonicalize no longer depends on it. Filed as a code handoff.
