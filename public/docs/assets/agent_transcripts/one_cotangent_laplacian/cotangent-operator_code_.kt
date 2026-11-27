// A lumpy icosphere, then the three pieces every cotangent caller now shares.
val sphere = icosphereMesh(4, 1.0)
val lumpy = sphere.positions.copyOf()
for (v in 0 until sphere.vertexCount) {
    val bump = 1f + 0.08f * kotlin.math.sin(9f * sphere.x(v)) * kotlin.math.cos(7f * sphere.y(v))
    for (d in 0..2) lumpy[3 * v + d] *= bump
}
val ball = TriMesh(lumpy, sphere.indices.copyOf()).withVertexNormals()
// Fine noise on top of the lumps, for the smoother to take away.
val rough = ball.positions.copyOf()
for (i in rough.indices) rough[i] += 0.025f * kotlin.math.sin(37f * i)
val noisy = TriMesh(rough, ball.indices.copyOf()).withVertexNormals()
val topo = ball.topology()
val weights = topo.cotangentWeights()
val mass = ball.barycentricVertexAreas()
val laplacian = topo.cotangentLaplacianMatrix(weights)

// L annihilates constants, and the lumped mass adds up to the surface area.
var rowSumMax = 0.0
val rowSums = DoubleArray(ball.vertexCount)
for (col in 0 until laplacian.numCols) for (k in laplacian.col_idx[col] until laplacian.col_idx[col + 1]) rowSums[
    laplacian.nz_rows[k]] += laplacian.nz_values[k]
for (s in rowSums) rowSumMax = maxOf(rowSumMax, kotlin.math.abs(s))
println("${topo.edgeCount} edge weights, ${weights.count { it < 0 }} negative (obtuse pairs)")
println("sum of mass %.5f, surface area %.5f, max |L·1| %.2e".format(mass.sum(), ball.surfaceArea(), rowSumMax))

// The same weights drive the smoother: cotangent Taubin against the lumpy input.
val smoothed = noisy.taubinSmooth(20, cotangent = true)
three(height = 340) {
    columns(2)
    panel(title = "lumpy sphere with fine noise")
    mesh(noisy, wireframe = false)
    panel(title = "taubinSmooth(20, cotangent = true)")
    mesh(smoothed, wireframe = false)
}
