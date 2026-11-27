// HeatGeodesics factors massScale·M + t·L built by cotangentLaplacianMatrix; meanCurvature applies L to positions.
val sphere = icosphereMesh(4, 1.0)
val lumpy = sphere.positions.copyOf()
for (v in 0 until sphere.vertexCount) {
    val bump = 1f + 0.08f * kotlin.math.sin(9f * sphere.x(v)) * kotlin.math.cos(7f * sphere.y(v))
    for (d in 0..2) lumpy[3 * v + d] *= bump
}
val ball = TriMesh(lumpy, sphere.indices.copyOf()).withVertexNormals()
val source = ball.nearestVertex(0, 1, 0)
val distance = ball.geodesicDistance(source)
val mean = ball.meanCurvature()
println(
    "farthest point %.3f along the surface; mean curvature %.2f to %.2f".format(distance.max(), mean.min(), mean.max()),
)

three(height = 340) {
    columns(2)
    panel(title = "geodesic distance from the north pole")
    mesh(ball.colouredBy(distance), wireframe = false)
    val rings = ball.evenIsolines(distance, 0.25).flatten()
    lines(
        rings.map { r ->
            r.map { vec(it.x * 1.01f, it.y * 1.01f, it.z * 1.01f) }.toFLine3(vec(1.0, 1.0, 1.0, 0.95), 1.4)
        },
        id = "rings",
        panel = 0,
    )
    panel(title = "mean curvature")
    mesh(ball.colouredBy(mean, -3.0, 4.0), wireframe = false)
}
