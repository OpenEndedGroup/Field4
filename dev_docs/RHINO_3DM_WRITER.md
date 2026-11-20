# Rhino .3dm writer

`Rhino3dm.save` (`src/jvmMain/kotlin/afield/geometry/rhino/RhinoWriter.kt`) writes `.3dm` files
through the same openNURBS wrapper the reader uses. User-facing docs:
`public/docs/rhino_files.md#writing-a-3dm-for-rhino`. Demo: `dev_docs/agents/transcripts/rhino_save.yaml`.

## Native ABI

`native/field-opennurbs/src/field_opennurbs_write.{h,cpp}`, kept apart from the reader's
`field_opennurbs.cpp/.h` so reader-side work merges cleanly. A handle-based builder over
`ONX_Model`: `writer_new(unitSystem)`, `add_layer(name, argb, parent, visible)`,
`attributes(layer, hasColor, argb, name, visible)` (applies to every following object),
`add_mesh`, `add_nurbs_curve`, `add_path` (the reader's move/line/cubic encoding), `add_points`,
`add_text`, `write(path, version)`, `writer_free`. CMake picks the file up with one
`target_sources` line.

## Exactness

- `add_path` turns each subpath into a degree-1 NURBS polyline (all lines) or an `ON_PolyCurve`
  of degree-1 runs and degree-3 Bézier NURBS spans. The reader converts degree-1 spans to lines
  and degree-3 Béziers to cubics with the same control points, so an `FLine` comes back with
  identical nodes; `close()` comes back as an explicit `lineTo` to the subpath start.
- Field's `Vec3` and `TriMesh` are float; they go out as exact doubles and come back as the same floats.
- Lost on rewrite of a read `RhinoModel`: Brep/extrusion surfaces (written as their render
  meshes, without edges), hatches (boundary curves), blocks (flattened members), text alignment,
  `RhinoSkipped` objects.

## Tests

`src/jvmTest/kotlin/afield/geometry/rhino/Rhino3dmWriterTest.kt`: layers (tree, colour,
visibility), every kind's count, by-layer and by-object colours, `FLine` nodes identical to 1e-12,
empty openNURBS log, and the reader's sample surviving load, save, load. Skips (passing)
when the wrapper is not built, like the reader's tests.
