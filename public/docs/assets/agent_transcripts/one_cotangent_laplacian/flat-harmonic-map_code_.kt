import afield.geometry.conformal.harmonicMap
import afield.geometry.conformal.toPlanarMesh

// planeMesh is a TriMesh; toPlanarMesh hands it to the conformal solvers, whose μ = 0 case is the same cotangent L.
val square = planeMesh(24, 24, 2.0, 2.0)
val planar = square.toPlanarMesh()
val disc =
    planar.boundary.associateWith { i ->
        val r = kotlin.math.hypot(planar.x[i], planar.y[i])
        doubleArrayOf(planar.x[i] / r, planar.y[i] / r)
    }
val map = harmonicMap(planar, disc)
println("${planar.vertexCount} vertices; mean |μ| of the harmonic map %.3f".format(map.meanModulus))

val positions = FloatArray(square.vertexCount * 3)
for (i in 0 until square.vertexCount) {
    positions[3 * i] = map.u[i].toFloat()
    positions[3 * i + 1] = map.v[i].toFloat()
}
val mapped = TriMesh(positions, square.indices.copyOf())
// A checkerboard on the square's own coordinates shows where each cell went.
val checker =
    DoubleArray(square.vertexCount) { i -> kotlin.math.sin(6.0 * square.x(i)) * kotlin.math.sin(6.0 * square.y(i)) }
val top = ThreeCameraSpec(yawDegrees = 0.0, pitchDegrees = 0.0)
three(height = 340) {
    columns(2)
    panel(title = "planeMesh(24, 24)", camera = top)
    mesh(square.colouredBy(checker, bands = 2), wireframe = false)
    panel(title = "harmonicMap onto the unit disc", camera = top)
    mesh(mapped.colouredBy(checker, bands = 2), wireframe = false)
}
