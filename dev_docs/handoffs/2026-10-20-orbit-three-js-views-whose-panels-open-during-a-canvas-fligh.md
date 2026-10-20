# Orbit three.js views whose panels open during a canvas flight

- **Handoff:** `e64e9f7e-6a3d-4670-8633-755de8f592f7` (project)
- **Filed from:** https://openendedgroup.com/field4/filming_the_canvas/
- **Filed by:** human, 2026-10-20 11:09
- **Close with:** `python tools/docs_notes.py handoff-close e64e9f7e "what happened"`

Rule 25 sharp edge on `public/docs/filming_the_canvas.md` ("Limits"):

> Only three.js views turn. A view whose output panel opens during the flight stays still.

Fix in the canvas flight orbit code: pick up three.js views whose panels open mid-flight (re-scan per leg or on panel open). Delete the second sentence from the page in the same change.
