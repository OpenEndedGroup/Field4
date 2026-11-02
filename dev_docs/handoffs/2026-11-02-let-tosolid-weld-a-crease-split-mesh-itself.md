# Let toSolid weld a crease-split mesh itself

- **Handoff:** `14f7a393-c87d-4101-bfff-e4cc57de2b0a` (project)
- **Filed from:** https://openendedgroup.com/field4/manifold/
- **Filed by:** human, 2026-11-02 10:31
- **Close with:** `python tools/docs_notes.py handoff-close 14f7a393 "what happened"`

`public/docs/manifold.md`, "Two meshes from `toTriMesh`", ends on a trap:

> `topology()` reads the indices as they are, and `toSolid` needs the plain form: Manifold refuses a
> crease-split mesh. Ask for the plain form when the mesh goes back into Manifold, and `weld()` a
> shaded one if you need to.

Fix in code: have `TriMesh.toSolid()` weld by position when the mesh carries normals (as `isWatertight()` already does), so a `toTriMesh(normals = true)` result round-trips. Delete those sentences in the same change.
