# Let mesh() follow the last panel() and allow Pick on contact sheets

- **Handoff:** `8d60f92a-393b-4146-af7e-55b6a247721b` (project)
- **Filed from:** https://openendedgroup.com/field4/three_output/
- **Filed by:** human, 2026-10-15 13:43
- **Close with:** `python tools/docs_notes.py handoff-close 8d60f92a "what happened"`

Sharp edge (rule 25) on three_output.md, Contact sheets:

> Calling `panel` does not change which viewport the next mesh goes into: the default stays `0`.
> ... Feature annotation currently requires a single viewport, so use a separate single-view scene to review a mesh from a sheet.

Two fixes in ThreeSceneBuilder / the viewer: (1) make `mesh(...)` default to the most recently declared `panel(...)` (or make `panel { }` a scoped block), so the ordering trap goes away; (2) support Pick / Save note per viewport on a contact sheet (picking already resolves the panel under the pointer). Delete the sentences in the same change.
