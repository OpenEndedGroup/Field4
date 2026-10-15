# Drop afield.stdlib imports and history comments from the polytope_examples fences, and recapture

- **Handoff:** `6cc2b6de-62df-42a8-a321-8c7c133c3db5` (project)
- **Filed from:** https://openendedgroup.com/field4/polytope_examples/
- **Filed by:** human, 2026-10-07 00:13
- **Close with:** `python tools/docs_notes.py handoff-close 6cc2b6de "what happened"`

`public/docs/polytope_examples.md`: every runner fence starts with `import afield.geometry.polytope.*`, and several also import `afield.geometry.mesh.TriMesh`, `afield.math.linalg.Vec3` or `afield.geometry.mesh.MeshView` (DOCS_WRITING_RULES rule 7). Make the polytope package resolve in a box without the import, or fold it, then recapture.

Fix these at the same time, since each needs a fence edit and a recapture:
- The operator-chaining fence's comment says "Before the thirteen were named on both receivers, none of them compiled" (history in a fence). The spectral fence has "The three routes were prose pretending to be a table". The rank-4 fence and the face-with-a-hole fence carry comments about what the page claims ("Caught, not described"). Cut them.
- The hero fence opens the page with about 80 lines, including a hand-written `hsv` helper (rule 9). Fold the helper, or use a stdlib colour function if one exists.
- `isCombinatoriallyIsomorphicTo` only handles rank-3 polyhedra by signature matching. Consider renaming it (rule 11), or let it accept any rank.
