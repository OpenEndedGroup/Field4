# Make toSolid reject inside-out meshes instead of documenting it

- **Handoff:** `08b65ccf-64a9-4666-bd57-dc681cd1e6a9` (project)
- **Filed from:** https://openendedgroup.com/field4/manifold/
- **Filed by:** human, 2026-10-15 13:44
- **Close with:** `python tools/docs_notes.py handoff-close 08b65ccf "what happened"`

public/docs/manifold.md carries this paragraph, which exists only to warn about a trap:

> **Manifold accepts an inside-out mesh.** A closed, consistently-but-inwardly wound surface is a
> legal manifold that reports a *negative* volume — so `status` is `NO_ERROR`, and the next boolean
> computes the complement of what you meant. `isValidSolid()` cannot catch that; `diagnose()` reports
> it as `INVERTED_ORIENTATION`, and `repairToSolid()`'s second winding pass fixes it. Manifold likewise
> accepts zero-area triangles, pinch vertices, and disjoint components — whose volumes it sums.

Fix in code (afield.geometry.solid.manifold / afield.geometry.mesh): have `TriMesh.isValidSolid()`
and the default `toSolid()` check report INVERTED_ORIENTATION (signed volume < 0) as blocking, or
have `toSolid()` flip a consistently inverted mesh and say so; decide whether zero-area triangles,
pinch vertices and disjoint components should also be flagged by the pre-flight. Then delete (or
reduce to one sentence about the behaviour) that paragraph in manifold.md in the same change.
