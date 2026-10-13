# Drop afield.stdlib imports from the lambda-sound and skinned-volume fences

- **Handoff:** `faadca14-57de-4dbb-89a5-52c1721d2c79` (project)
- **Filed from:** https://openendedgroup.com/field4/chain_lambda_sound/
- **Filed by:** human, 2026-10-02 21:30
- **Close with:** `python tools/docs_notes.py handoff-close faadca14 "what happened"`

Three runner fences import from `afield.stdlib`, which DOCS_WRITING_RULES.md rule 7 forbids in box code:

- `public/docs/chain_lambda_sound.md`, fences `chain-lambda-one` and `chain-lambda-sweep`: `import afield.data.graph.ActivationOrder`, and the fully qualified `afield.data.graph.ActivationOrder` in the sweep's `sortedWith` type argument.
- `public/docs/chain_skinned_volume.md`, fence `chain-skinned-volume-readout`: `import afield.geometry.mesh.isWatertight` and `import afield.geometry.mesh.signedVolume`.

Make these names resolve in a box without the import (or re-export them where the box preamble already reaches), then drop the imports, update `tools/completion-corpus/rank-carets.tsv` for the changed fence bytes, and recapture the three figures with the docs runner. Same shape as handoff edb89165.
