# Decide whether pitched chain pages may keep a synth voice

- **Handoff:** `18d28c98-be06-4044-a1c1-701ddee4affe` (crosscutting)
- **Filed from:** https://openendedgroup.com/field4/chain_drawn_score/
- **Filed by:** human, 2026-10-03 00:00
- **Close with:** `python tools/docs_notes.py handoff-close 18d28c98 "what happened"`

`chain_drawn_score.md` and `chain_drawn_morph.md` play their readings through `patVoice` (one triangle oscillator). DOCS_WRITING_RULES.md rule 15 asks for real material (the pinned Dirt-Samples), but both pages are pitched: the score page pitch-tracks its own render with YIN and needs a clean, detached, single-pitch voice so every onset and pitch is found.

Question for Marc: is a plain synth voice acceptable on pitched chain pages where the analysis depends on it, or should these use a pitched sample instrument (and the listen-back measurements be re-derived against it)? If the latter, this becomes a captures + code handoff for both pages (DrawnScoreDocs, DrawnMorphDocs renderers).
