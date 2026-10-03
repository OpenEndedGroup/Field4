# Drop afield.stdlib imports from the fn_intervals fences

- **Handoff:** `fe35bc93-38d3-48ca-995e-51211aac2ca8` (project)
- **Filed from:** https://openendedgroup.com/field4/fn_intervals/
- **Filed by:** human, 2026-10-03 22:22
- **Close with:** `python tools/docs_notes.py handoff-close fe35bc93 "what happened"`

All three fences on `public/docs/fn_intervals.md` (`fn-intervals-three-calls`, `fn-intervals-picture`, `fn-intervals-oscillators`) open with `import afield.stdlib.Functions` and, in the first, `import afield.stdlib.enclose`, `certifiedRange`, `certifiedNeverExceeds`. The writing rules say box code carries no `afield.stdlib.*` imports. Check whether the box preamble already resolves `Functions` and these extension functions; if it does, drop the imports and recapture; if not, make them resolve in a box (the preamble) and then drop them. Update `tools/completion-corpus/rank-carets.tsv` if it pins these fences.
