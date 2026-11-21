# Rhino Brep and SubD tessellation

How `Rhino3dm.load` turns a Brep face without a saved render mesh, and a SubD, into triangles. The
user-facing description is `public/docs/rhino_files.md` § "Breps without meshes".

## Native half (`native/field-opennurbs/src/field_opennurbs_brep.{h,cpp}`)

A second handle over the same file (`field_opennurbs_breps_open`), separate from the reader so the
reader's file stays untouched. Per top-level Brep, and per extrusion through `BrepForm`, one packed
double array (layout in the header): per face the saved-mesh flag, `m_bRev`, the NURBS form
(`ON_Surface::GetNurbForm`; openNURBS's two superfluous end knots restored so the knot vector is the
textbook clamped one; CVs homogeneous) and each loop's trims sampled (1 point per span for lines, 64
for curves) and mapped into the NURBS form's parameters with
`GetNurbFormParameterFromSurfaceParameter` (a revolved sphere's surface and NURBS parameters differ).

Public openNURBS has no SubD limit mesher, but it does ship `ON_SubD::GlobalSubdivide` and the
vertex limit point and normal (`ON_SubDVertex::SurfacePoint`, `SurfaceNormal(face, ...)`). A copy of
each SubD is subdivided `subdLevel` times and every corner of every face is emitted at its limit
point, with the normal for that face's sector so creases keep two normals.

`field_opennurbs_write_brep_fixture` writes the test fixture (sphere, capped cylinder, plate with a
hole, a box with and without saved meshes, smooth and creased SubD boxes).

## Kotlin half (`src/jvmMain/kotlin/afield/geometry/rhino/BrepTessellator.kt`)

- `NurbsSurface.evaluate`: rational de Boor with first derivatives (The NURBS Book A2.3, eq. 4.20);
  where `Su x Sv` vanishes (poles) the normal is taken 1e-5 of the domain inwards.
- Cells: one root per knot span of the trims' parameter box (`quadtree`), each split in u, v or both
  while the surface strays from the bilinear patch through the cell corners by more than the
  tolerance (default 1e-3 of the CV bounding-box diagonal): u-sag is measured at the u-midpoints of
  the v-edges and at 1/4, 3/4 across the middle, v-sag likewise, and the centre catches twist.
  At least one split per direction of degree > 1, at most 12 levels. Cells outside the trims are
  dropped before refining.
- Cells no trim segment's bounding box touches are tested by their centre and kept as two triangles;
  the rest are intersected with the trim region (`java.awt.geom.Area`, even-odd) and the pieces
  earcut (`FloatEarcut`) in cell-local coordinates.
- Triangles are wound to agree with the evaluated normals (`m_bRev` flips them); zero-area triangles
  dropped.
- Stitch (`stitch`, 2026-11-21): vertices joined by position alone (1e-6 of the Brep size); then
  any edge used by one triangle with another open edge's vertex within `2 * tolerance` of it (and
  within a quarter of its length, strictly between its ends) is split at that vertex, its triangle
  becoming a fan; repeated until nothing changes (24 passes max). This closes both the cracks
  between faces (independently sampled trims) and the quadtree's hanging vertices. Then vertices
  are welded when their normals agree (dot > 0.9) for shading. `BrepTessellator.lastStitch` holds
  the counts.
- Measured (2026-11-21): openNURBS fixture capped cylinder 800 open edges before stitch, 0 after
  (480 splits, 3 passes, 2268 triangles); sphere and boxes 0 before and after (flat faces close by
  themselves: `Area` drops collinear trim points). JVM-built fixtures (`BrepWatertightTest`):
  cylinder with 48/61/37-point circles 386 -> 0 (1630 triangles, area -0.10%, volume -0.36%);
  block bored with a radius-2 hole 134 -> 0 (728 triangles, volume +0.09%).

## Known limits

Shared edges are matched geometrically (no edge topology crosses the FFM boundary), so a vertex of
another face within `2 * tolerance` of an open edge is taken as on it: features thinner than that
could be stitched wrongly. Faces are still meshed one at a time; trims crossing the cells add
vertices along an edge that the neighbour only gets through the stitch. The stitch walks the
spatial hash in steps of `2 * tolerance`, so a very small explicit tolerance on a large model is
slow. A non-manifold edge (three faces) is left alone.
Block members are not evaluated. Tests: `src/jvmTest/kotlin/afield/geometry/rhino/BrepTessellatorTest.kt`.
