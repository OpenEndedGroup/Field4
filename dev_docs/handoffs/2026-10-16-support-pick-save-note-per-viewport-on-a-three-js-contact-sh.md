# Support Pick / Save note per viewport on a Three.js contact sheet

- **Handoff:** `db5dd9d1-e140-4cee-812f-849361a3f912` (project)
- **Filed from:** https://openendedgroup.com/field4/three_output/
- **Filed by:** human, 2026-10-16 02:47
- **Close with:** `python tools/docs_notes.py handoff-close db5dd9d1 "what happened"`

Split from 8d60f92a-393b-4146-af7e-55b6a247721b, 2026-10-16. The panel-default half is done: `mesh()` now goes into the last declared `panel()`.

Still open: feature annotation (Pick / Save note) needs a single viewport. `three_output.md` says "Feature annotation currently requires a single viewport, so use a separate single-view scene to review a mesh from a sheet." Picking already resolves the panel under the pointer. Extend the Three.js viewer's annotation flow (threejs_viewer.html and its pick/save-note handlers, plus the Kotlin side that stores notes) to record the panel index with each note and restore it, so annotation works on a contact sheet. Then delete that sentence.
