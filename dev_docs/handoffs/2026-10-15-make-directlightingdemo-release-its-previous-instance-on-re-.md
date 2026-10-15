# Make DirectLightingDemo release its previous instance on re-run

- **Handoff:** `94dfb816-850b-4f9d-903d-4aeee1488d00` (project)
- **Filed from:** https://openendedgroup.com/field4/direct_lighting/
- **Filed by:** human, 2026-10-15 13:48
- **Close with:** `python tools/docs_notes.py handoff-close 94dfb816 "what happened"`

`direct_lighting.md` says:

> Close `study` before replacing it; the starting point already handles replacement on relaunch.

The fence above it (`val study = DirectLightingDemo(renderer, 1280, 720)`) does not close anything, so a reader who re-runs it leaks the previous study. This is a workaround paragraph (rule 25). Fix in code: make `DirectLightingDemo` relaunch-safe for box code (release the previous instance owned by the same box on re-run, e.g. via the box lifecycle release policy), then delete the sentence from `public/docs/direct_lighting.md` in the same change.
