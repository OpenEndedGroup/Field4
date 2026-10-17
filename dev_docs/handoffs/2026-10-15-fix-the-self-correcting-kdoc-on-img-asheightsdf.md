# Fix the self-correcting KDoc on Img.asHeightSdf

- **Handoff:** `d475200d-9fd2-44e6-b295-1b0f7c193a95` (project)
- **Filed from:** https://openendedgroup.com/field4/mesh_examples/
- **Filed by:** human, 2026-10-15 13:44
- **Close with:** `python tools/docs_notes.py handoff-close d475200d "what happened"`

On public/docs/mesh_examples.md (reference table, Fields / marching cubes), the row for `Img.asHeightSdf` reads "An Img as a 3D field, one z-slice per… no — this is the *2D* bridge: ...". The text comes from KDoc in src/jvmMain/kotlin/afield/geometry/mesh/mesh_sdf.kt ("one z-slice per… no —"). Rewrite the KDoc to state what it is ("The image as a 3D height field: value at (x, z) minus y, independent of y ...") and regenerate the page's reference table.
