# Drop afield.stdlib imports from the RectSL space-time and polytope-contour fences

- **Handoff:** `edb89165-f34e-4ee4-bf29-6113670b4deb` (project)
- **Filed from:** https://openendedgroup.com/field4/chain_rectsl_spacetime/
- **Filed by:** human, 2026-10-02 18:13
- **Close with:** `python tools/docs_notes.py handoff-close edb89165 "what happened"`

Rule 7 of `dev_docs/agents/DOCS_WRITING_RULES.md`: box code carries no `afield.stdlib.*` imports.

- `public/docs/chain_rectsl_spacetime.md`, the readout fence (`name="chain-rectsl..."`, under
  "What happens where the volume meets the window"): `import afield.stdlib.*` inside the
  `:::static` fold.
- `public/docs/chain_ceres_manifold_contours.md`, the profile and kkC fences: explicit
  `import afield.stdlib.polytope.*` lines (`conway`, `canonicalize`, `triangulate`,
  `relaxWithCeres`, ...). If the polytope package is not in the box preamble, decide whether it
  should be; otherwise fold the imports.

Rewrite the fences, re-run the captures (`FIELD_DOCS_RUNNER_MODE=incremental`), and update
`tools/completion-corpus/rank-carets.tsv` if it pins these fences.
