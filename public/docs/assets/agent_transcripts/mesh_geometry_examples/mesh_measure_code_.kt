// Discrete curvature: the angle deficit for Gaussian, the cotangent Laplacian's magnitude
// for mean, both divided by the mixed Voronoi area. On a sphere of radius r they are
// 1/r^2 and 1/r; on a torus the Gaussian curvature has both signs, positive on the
// outside and negative in the hole, which is why a torus is the shape to look at.
val r = 0.7
val ball = icosphereMesh(4, r)
println("sphere radius $r:")
println("  Gaussian curvature mean %.5f against 1/r^2 = %.5f".format(
    ball.gaussianCurvature().average(), 1.0 / (r * r)))
println("  mean curvature     mean %.5f against 1/r   = %.5f".format(
    ball.meanCurvature().average(), 1.0 / r))

// Gauss-Bonnet is a global identity: the integral of Gaussian curvature over a closed
// surface is 2 pi times its Euler characteristic. No local bug survives it.
for ((label, m) in listOf("icosphere" to ball, "torus" to torusMesh(64, 32, 0.6, 0.24),
                          "cube" to boxMesh())) {
    val k = m.gaussianCurvature()
    val areas = m.mixedVertexAreas()
    var total = 0.0
    for (v in 0 until m.vertexCount) total += k[v] * areas[v]
    println("Gauss-Bonnet on the %-10s integral %10.6f against 2 pi chi = %10.6f".format(
        label, total, 2.0 * PI * m.eulerCharacteristic()))
}

// The BVH turns every useful query from O(n) into O(log n). Each answer below has an
// analytic counterpart, printed beside it.
val tree = ball.bvh()
val hit = tree.raycast(vec(0, 0, -5), vec(0, 0, 1))
println()
println("ray from 5 units out at a sphere of radius $r hits at ${hit?.distance} " +
    "(analytic ${5.0 - r})")
val near = tree.closestPoint(vec(3, 0, 0))
println("closest surface point to (3,0,0) is ${near?.point} at ${near?.distance} " +
    "(analytic ${3.0 - r})")
println("contains (0,0,0)      = ${tree.contains(vec(0, 0, 0))}   (inside)")
println("contains (0.9r,0,0)   = ${tree.contains(vec(0.9 * r, 0, 0))}   (inside)")
println("contains (1.1r,0,0)   = ${tree.contains(vec(1.1 * r, 0, 0))}   (outside)")
println("signedDistance (2,0,0) = %.5f (analytic %.5f)".format(
    tree.signedDistance(vec(2, 0, 0)), 2.0 - r))

// And a convex hull, with no dependencies: 200 points on a squashed ellipsoid.
val rand = java.util.Random(19)
val cloud = List(200) {
    val n = vec(rand.nextGaussian(), rand.nextGaussian(), rand.nextGaussian()).normalize()
    vec(n.x * 0.8f, n.y * 0.45f, n.z * 0.6f)
}
val hull = convexHullMesh(cloud)
println()
println("convex hull of 200 points: ${hull.summary()}")

val torus = torusMesh(80, 40, 0.6, 0.24)
showMeshGrid(
    "gaussianCurvature() — red out, blue in the hole"
        to torus.coloredByScalar(torus.gaussianCurvature(), "coolwarm"),
    "meanCurvature()" to torus.coloredByScalar(torus.meanCurvature(), "viridis"),
    "convexHullMesh(200 points)" to hull.withColor(0.85, 0.62, 0.32),
    columns = 3,
    cellWidth = 300,
)
