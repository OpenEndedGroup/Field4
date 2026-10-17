# Make showMesh take MeshView.height on the Three.js path

- **Handoff:** `9c45fb5e-893e-4e62-aca6-6c8be37d2714` (project)
- **Filed from:** https://openendedgroup.com/field4/three_output/
- **Filed by:** human, 2026-10-15 13:43
- **Close with:** `python tools/docs_notes.py handoff-close 9c45fb5e "what happened"`

Sharp edge (rule 25) on three_output.md, "Two size knobs that are easy to confuse":

> `MeshView.width` / `MeshView.height` size the **raster** on the CPU path, and on the GPU path `view.width` becomes the iframe's width in pixels; `showMesh`'s own `height` parameter, default `430`, sizes the **iframe**, and `MeshView.height` is ignored on that path. To get a GPU figure the same shape as a CPU one, pass `height = view.height` explicitly.

Fix in code: when `showMesh` is given a `MeshView` and no explicit `height`, take `view.height` on the Three.js path too (width already follows the view). Delete the paragraph in the same change.
