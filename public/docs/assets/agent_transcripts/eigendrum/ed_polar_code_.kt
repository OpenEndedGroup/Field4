import afield.audio.modal.*

// r(θ) = 1 + 0.3 cos 5θ: a five-lobed drum. Left, the triangle mesh;
// right, its sixth mode coloured by displacement, nodal lines in white.
val mesh = polarDrumMesh(0.06) { t -> 1 + 0.3 * cos(5 * t) }
val modes = mesh.modes(8)
val wire = mesh.wireframe().apply {
    color = vec(0.8, 0.8, 0.8, 0.6)
    thickness = 0.5
}
val shape = modes.modeMesh(5).map { it.copy().apply { transform { v -> v.add(3f, 0f, 0f) } } }
val nodes = modes.nodalLines(5).copy().apply {
    transform { v -> v.add(3f, 0f, 0f) }
    color = vec(1.0, 1.0, 1.0, 1.0)
    thickness = 1.5
}
lines(listOf(wire) + shape + listOf(nodes), w = 520.0, h = 260.0, fitToSize = true)
println("${mesh.nodeCount} nodes, ${mesh.triangleCount} triangles, ratios ${modes.ratios().map { it.f3 }}")
