# Rename the audio-patch-seam sound path id

- **Handoff:** `52270e2f-0492-429e-9e2e-b3e55c52dae7` (project)
- **Filed from:** https://openendedgroup.com/field4/sound_paths/
- **Filed by:** human, 2026-10-07 06:10
- **Close with:** `python tools/docs_notes.py handoff-close 52270e2f "what happened"`

`sound_paths.md` (and `SoundPathMap.kt`, its guard and smoke) name the default output path
`audio-patch-seam`. "Seam" is on the banned-vocabulary list in DOCS_WRITING_RULES rule 5, and a
reader meets the id in the opening "Which one to pick" list ("the `audio-patch-seam` does the rest").
Rule 11: fix the name, not the explanation.

Do: rename the path id (suggest `audio-patch`, or `box-audio`) in `SoundPathMap.PATHS`, the marker
`seam-stereo-node`, the page, and any docs that cite it; regenerate whatever the smoke prints.
