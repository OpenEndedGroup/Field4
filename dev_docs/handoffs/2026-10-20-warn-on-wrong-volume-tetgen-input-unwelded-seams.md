# Warn on wrong-volume TetGen input (unwelded seams)

- **Handoff:** `8d3fa920-8667-4428-be3c-bb8706327bb3` (project)
- **Filed from:** https://openendedgroup.com/field4/tetgen/
- **Filed by:** human, 2026-10-20 11:11
- **Close with:** `python tools/docs_notes.py handoff-close 8d3fa920 "what happened"`

Sharp edge (rule 25) on public/docs/tetgen.md, "Input surfaces":

> The coincident-corner cube is not a failure either: ... a mesh off disk with an unwelded or over-welded seam produces a plausible solid of the wrong size, and nothing in this path will tell you. Compare `mesh.volume()` against `Solid.volume` before you trust a result.

Fix in code: have `Tetgen.tetrahedralize` (or a `TriMesh`-from-disk path) detect coincident/unwelded vertices and warn, or compare the tet volume with the input surface's signed volume and log a mismatch. Delete the paragraph's warning in the same change.
