# Remove the afield.stdlib import from SolidSL fitting fences and recapture

- **Handoff:** `2326f887-3f41-4944-afc6-d99f33b66bd1` (project)
- **Filed from:** https://openendedgroup.com/field4/solid_notation/
- **Filed by:** human, 2026-10-06 21:15
- **Close with:** `python tools/docs_notes.py handoff-close 2326f887 "what happened"`

`public/docs/solid_notation.md` has two runner fences that break DOCS_WRITING_RULES rule 7:

- the "When only half the model can fit" fence (`assets/solid_notation/partial.png`) and the convergence filmstrip fence (`assets/solid_notation/converging.png`) carry `import afield.stdlib.OptimizeMethod`.

Make `OptimizeMethod` reachable without an `afield.stdlib.*` import in box code (default imports, or a re-export from `afield.solidsl`), drop the import from both fences, and recapture both figures with `FIELD_DOCS_RUNNER_MODE=always ... --page solid_notation.md`. Check the convergence numbers quoted in the prose still match (they are pinned by `SolidSLConvergenceFilmstripTest` / `SolidSLPartialRecoveryTest`).
