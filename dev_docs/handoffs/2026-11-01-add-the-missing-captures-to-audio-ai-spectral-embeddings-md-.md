# Add the missing captures to audio_ai_spectral_embeddings.md (1 section)

- **Handoff:** `44c0f1f9-0c19-420e-8041-9a60a5d07313` (project)
- **Filed from:** https://openendedgroup.com/field4/audio_ai_spectral_embeddings/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 44c0f1f9 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Temporal pooling and projection (`public/docs/audio_ai_spectral_embeddings.md:248`, medium, docs-runner)

- **Point made:** MDS preserves global distances while t-SNE reveals local state changes, and poolMillis/hopMillis independently change how smooth and dense the trajectory is.
- **Capture:** Two plot captures of the same recording side by side, MDS vs t-SNE (or 40 ms vs 200 ms pool), using the spectral_spaces fence that is already at the top of the page.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
