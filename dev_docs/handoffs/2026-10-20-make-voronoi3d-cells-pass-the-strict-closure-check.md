# Make voronoi3d cells pass the strict closure check

- **Handoff:** `be4605e5-6d77-46df-b694-07e093e128d8` (project)
- **Filed from:** https://openendedgroup.com/field4/spatial_geometry_examples/
- **Filed by:** human, 2026-10-20 11:09
- **Close with:** `python tools/docs_notes.py handoff-close be4605e5 "what happened"`

Rule 25 sharp edge on `public/docs/spatial_geometry_examples.md` (3D Voronoi section):

> **Limits.** This is a `Float` clipper and the guarantee is not absolute. Over a sweep,
> **4 of 465 bounded cells** and **12 of 447 clipped cells** fail the strict closure check
> (every edge walked by exactly two faces in opposite directions). ... If you are building a mesh from the faces, use
> `VoronoiCell3.toTriMesh(shrink)` / `VoronoiLattice.toTriMesh(...)` from Meshes & 3D Geometry, ... where the bridge does its own repair.

Fix in code: make `voronoi3d` (`src/jvmMain/kotlin/afield/geometry/spatial/voronoi.kt`) produce closed cells (e.g. Double clipping or a repair pass so faces are watertight without the mesh bridge), tighten the `<=` pins in `VoronoiFaceTopologyTest.kt` to zero, and delete the "Limits" paragraph in the same change (keep the pointer to `toTriMesh` only as a normal next-step link).
