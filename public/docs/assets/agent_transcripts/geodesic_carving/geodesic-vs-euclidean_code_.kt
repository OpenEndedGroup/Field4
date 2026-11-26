// A sheet with a deep fold down the middle: the far wall is close through the air and far along the stone.
val shell = heightfieldMesh(90, 60, 3.0, 2.0) { x, _ -> -1.1f * kotlin.math.exp(-(x * x) / 0.12f) }
val source = shell.nearestVertex(-0.9, 0, 0)

val straight = shell.euclideanDistance(source)
val alongSurface = shell.geodesicDistance(source)
println(
    "vertex across the fold: straight %.3f, along the surface %.3f"
        .format(
            straight[shell.nearestVertex(0.9, 0, 0)],
            alongSurface[shell.nearestVertex(0.9, 0, 0)],
        ),
)

// Same colour scale and the same 0.15 ring spacing on both, so the bands compare directly.
// The rings are lifted a hair off the sheet so the surface does not hide them.
val top = alongSurface.max()
val view = ThreeCameraSpec(yawDegrees = 20.0, pitchDegrees = 40.0)
three(height = 360) {
    columns(2)
    for ((i, panelled) in ["straight-line distance" to straight, "geodesic distance" to alongSurface].withIndex()) {
        val (title, d) = panelled
        panel(title = title, camera = view)
        mesh(shell.colouredBy(d, 0.0, top), wireframe = false, cullBackFaces = false)
        val rings = shell.evenIsolines(d, 0.15).flatten()
        lines(
            rings.map { r -> r.map { vec(it.x, it.y + 0.01f, it.z) }.toFLine3(vec(1.0, 1.0, 1.0, 0.95), 1.4) },
            id = "rings$i",
            panel = i,
        )
    }
}
