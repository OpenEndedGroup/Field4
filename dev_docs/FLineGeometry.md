# FLineGeometry

FLineGeometry provides extension functions on `FLine` for computational geometry:
2D/3D transforms, offsetting, smoothing, intersections, curvature, decomposition,
tangent/normal ops, boolean ops, geometric queries, path ops, curve fitting,
pattern generation, arcs, noise, curve combination, and the `ParameterFunction`
system. Each function is documented via KDoc directly on its declaration in
`src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt`.

For single-contour `FLine` morphing (`FLineInterpolator`, `interpolatorTo`,
`byInterpolatingTo`), see KDoc in
`src/jvmMain/kotlin/afield/graphics/FLineInterpolation.kt`.

Anything that spaces by *distance along the curve* — `Cursor.setD`, `midPoint`,
`fractionAlong`, `sampleByNumber`, `sampleByDistance`, `byDashing`,
`byArcLengthParameterizing`, `FLineSamplers.byDistance` — goes through
`PathFlattener`. See [arc_length_spacing.md](arc_length_spacing.md) for how that
works, the class of curve it got wrong until 2026-07-31, and the measured
before/after.

Filling an `FLine` goes somewhere else entirely: `FLine.tessellate`
(`src/jvmMain/kotlin/afield/graphics/FLine.kt:1019#fun tessellate`) flattens the line into rings and hands them to
`PlanarTessellation` (`src/jvmMain/kotlin/afield/graphics/PlanarTessellation.kt:170#fun tessellate`), which clusters
them into planes, decides which rings are holes in which *within* a plane, and triangulates each
group with `FloatEarcut` on an orientation-preserving projected frame. It is the only triangulator:
the polytope adapters' `EarcutFaceTriangulator` is a second adapter over the same code, and gained
hole support by becoming one. See
[fline_fill_orientation.md](fline_fill_orientation.md) for why the projected frame decides which way
every resulting triangle faces, the three ways it got that wrong, what "coplanar" means here and what
its tolerance is, and the rendered before/after pairs.
