# screenshot-box: wait for depth-of-field to settle on the selected box

- **Handoff:** `5f0b16d3-1e86-47a7-ab88-fce36d5e4117` (project)
- **Filed from:** https://openendedgroup.com/field4/capture_limits/
- **Filed by:** human, 2026-10-15 13:51
- **Close with:** `python tools/docs_notes.py handoff-close 5f0b16d3 "what happened"`

Rule 25 sharp edge. The page: "`screenshot-box` selects the box, but the canvas's depth-of-field can lag ... The reliable recipe is `editor-open <uid> --open-outputs`, settle, then `screenshot-box --preserve-selection`. Two consecutive `screenshot-box` calls on the same box are not reliable."

Fix in `afield/tools/docs/screenshot.kt` prep script: after selecting, wait until the focus/blur transition has completed before shooting, so repeats are reliable. Delete that bullet in the same change.
