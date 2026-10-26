// A marched gyroid and its open rims, as one three.js scene in the output panel.
val surface = marchingCubes(sdfGyroid(4.0, 0.3), boundsAround(radius = 1.0), 56)
val rims =
    surface.toFLines(MeshLines.BOUNDARY).map { ring ->
        ring.color = vec(1.0, 0.45, 0.12, 1.0)
        ring.thickness = 2.6
        ring
    }
three(channel = "gyroid", height = 420, width = 640) {
    mesh(surface, wireframe = false)
    lines(rims)
    camera(yaw = 32.0, pitch = 20.0, padding = 1.02)
}
