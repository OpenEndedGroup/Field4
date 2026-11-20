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
- Grid: a line at every distinct knot in the trims' parameter box, each span halved until the sag at
  1/4, 1/2, 3/4 along 7 cross lines is under the tolerance (default 1e-3 of the CV bounding-box
  diagonal), at least once for degree > 1, at most 8 levels.
- Cells no trim segment's bounding box touches are tested by their centre and kept as two triangles;
  the rest are intersected with the trim region (`java.awt.geom.Area`, even-odd) and the pieces
  earcut (`FloatEarcut`) in cell-local coordinates.
- Triangles are wound to agree with the evaluated normals (`m_bRev` flips them); zero-area triangles
  dropped. Vertices within 1e-6 of the Brep size are welded when their normals agree (dot > 0.9).

## Known limits

Trims on the two sides of an edge are sampled independently, so small T-junction cracks between
faces are possible. The grid is a tensor grid per face, which over-refines near a sphere's poles.
Block members are not evaluated. Tests: `src/jvmTest/kotlin/afield/geometry/rhino/BrepTessellatorTest.kt`.
