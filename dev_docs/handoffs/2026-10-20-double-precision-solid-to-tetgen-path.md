# Double-precision Solid to TetGen path

- **Handoff:** `c19411f5-5bd7-415b-b3d5-9c796d94e98f` (project)
- **Filed from:** https://openendedgroup.com/field4/tetgen/
- **Filed by:** human, 2026-10-20 11:11
- **Close with:** `python tools/docs_notes.py handoff-close c19411f5 "what happened"`

Sharp edge (rule 25) on public/docs/tetgen.md, "Where precision is lost: `TriMesh` stores float32":

> `TriMesh` stores positions as `Float`, so `Solid.toTriMesh()` narrows every coordinate on the way in ... If you need better than 1 part in 10⁹ on a curved body, the bridge is where to spend the effort, not the mesher.

Fix in code: give the Manifold -> TetGen path a double-precision route (e.g. `Tetgen.tetrahedralize(solid: Solid)` reading Manifold's double positions directly). Rewrite/delete the section in the same change.
