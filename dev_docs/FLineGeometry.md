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
