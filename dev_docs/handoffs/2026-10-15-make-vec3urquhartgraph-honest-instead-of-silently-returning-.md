# Make vec3UrquhartGraph honest instead of silently returning a spanning tree

- **Handoff:** `233002d2-b0bf-41b2-aeaa-a48ff729d168` (project)
- **Filed from:** https://openendedgroup.com/field4/graph_connectivity_examples/
- **Filed by:** human, 2026-10-15 13:46
- **Close with:** `python tools/docs_notes.py handoff-close 233002d2 "what happened"`

Rule 25 sharp edge on `public/docs/graph_connectivity_examples.md` ("3D graph families"):

> There is no 3D Urquhart graph, because Field has no 3D Delaunay triangulation
> to remove edges from. `vec3UrquhartGraph(...)` returns the spanning tree
> instead.

A function named for one graph that returns another is a misleading name. Fix in
`src/jvmMain/kotlin/afield/data/graph/graph_connectivity.kt`: either implement it
(3D Delaunay via TetGen, which Field already builds, then drop the longest edge of
each tetrahedron face/triangle), or remove/deprecate `vec3UrquhartGraph` so the
call fails at compile time. Delete the paragraph above in the same change.
