// The carved boss from the mesh page, with its geodesic spiral toolpath.
val relief =
    heightfieldMesh(28, 28, 2.0, 2.0) { x, z ->
        val r = kotlin.math.sqrt(x * x + z * z)
        0.5f * kotlin.math.exp(-r * r * 2.2f) + 0.05f * kotlin.math.cos(r * 14f) * kotlin.math.exp(-r * 1.5f)
    }
val fromEdge = relief.geodesicDistanceFromBoundary()
val spiral = spiralFromRings(relief.evenIsolines(fromEdge, 0.08).map { level -> level.maxBy { it.size } })
val carved = vectorScene {
    mesh(relief, color = vec(0.86, 0.84, 0.8, 1.0), solid = false)
    polyline(spiral, width = 0.01, color = vec(0.9, 0.3, 0.15, 1.0))
}

// A ring, measured along its surface from one point: the far rings pass behind the tube.
val ring = torusMesh(36, 14, 1.0, 0.4)
val fromPoint = ring.geodesicDistance(ring.nearestVertex(1.4, 0, 0))
val rings = ring.evenIsolines(fromPoint, 0.12).flatten()
val ringed = vectorScene {
    mesh(ring.colouredBy(fromPoint))
    for (r in rings) polyline(r, width = 0.012, color = vec(1.0, 1.0, 1.0, 1.0))
}

val t0 = System.nanoTime()
val left = carved.render(yaw = 0.45, pitch = -0.75, scale = 150.0, shading = Shading.FLAT)
val right = ringed.render(yaw = 0.3, pitch = -0.6, scale = 120.0).map { it + vec(380.0, 0.0) }
println(
    "${relief.triangleCount + ring.triangleCount} triangles, ${left.size + right.size} FLines, ordered in ${(System.nanoTime() - t0) / 1_000_000} ms",
)
lines(left + right, w = 680.0, h = 300.0, fitToSize = true)
