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
