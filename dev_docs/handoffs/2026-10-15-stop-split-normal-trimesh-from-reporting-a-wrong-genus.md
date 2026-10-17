# Stop split-normal TriMesh from reporting a wrong genus

- **Handoff:** `a649346f-4674-461d-b654-d544e4e5194f` (project)
- **Filed from:** https://openendedgroup.com/field4/chain_polytope_render/
- **Filed by:** human, 2026-10-15 13:41
- **Close with:** `python tools/docs_notes.py handoff-close a649346f "what happened"`

Rule 25 sharp edge on chain_polytope_render.md, section "One mesh to measure, another to draw":

> `normals = true` splits a vertex per face at every hard edge ... it is also why that mesh shares no edges and reports the wrong genus. Measure on the plain mesh and render the split one. Asking either to do the other's job gets you a true statement about a different mesh rather than an error.

Fix: `genus()` / `isWatertight()` on a mesh produced by `toTriMesh(normals = true)` should either weld by position before measuring or refuse (error) with a message, so the silent wrong answer cannot happen. Delete the section in the same change.
