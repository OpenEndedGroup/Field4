import afield.geometry.spectral.CotangentLaplacian

val sphere = icosphereMesh(subdivisions = 2)
val source = TriMesh(
    FloatArray(sphere.positions.size) { i ->
        val vertex = i / 3
        val x = sphere.x(vertex)
        val y = sphere.y(vertex)
        val z = sphere.z(vertex)
        val ripple = 1.0 + 0.17 * kotlin.math.cos(8 * x.toDouble()) * kotlin.math.sin(6 * y.toDouble())
        (sphere.positions[i] * ripple * if (i % 3 == 2) 0.82 + 0.24 * z else 1.0).toFloat()
    },
    sphere.indices.copyOf(),
).withVertexNormals()
val basis = CotangentLaplacian(source).basis(32)
val positions = basis.positions()
val eigenvalues = basis.eigenvalues
val random = basis.randomField(11) { (1.0 + it.frequency).pow(-0.9) }

var count = 12
var heat = 0.0
var mode = 1
var amplitude = 0.22
var animate = false
var flow = false
controlGroup(columns = 2) {
    slider("Position modes", 2.0, basis.modeCount.toDouble(), 1.0, count.toDouble()) { count = it.toInt() }
    slider("Heat time", 0.0, 0.25, 0.002, 0.0) { heat = it }
    slider("Standing wave", 1.0, basis.modeCount - 1.0, 1.0, 1.0) { mode = it.toInt() }
    slider("Normal displacement", 0.0, 0.8, 0.01, 0.22) { amplitude = it }
    checkbox("Animate intrinsic noise") { animate = it }
    checkbox("Flow along mode contours") { flow = it }
}
displayOpen("controls")
display(
    "spectrum",
    table(
        "Mode" to eigenvalues.indices.toList(),
        "Eigenvalue" to eigenvalues.toList(),
        "Position energy" to eigenvalues.indices.map(positions::energy),
    ).toHTML(),
)
displayClose("spectrum")

val original = DoubleArray(source.positions.size) { source.positions[it].toDouble() }
var tick = 0.0
var previous: List<Any> = emptyList()
var previousFlow = -1
var fieldFlow = basis.flow(basis.mode(mode), perpendicular = true)
var particles = emptyList<afield.geometry.spectral.ManifoldParticle>()
frames {
    while (true) {
        val setting = listOf(count, heat, mode, amplitude, animate, flow)
        if (setting != previous || flow || animate) {
            val changed = previous != setting
            previous = setting
            val reduced = basis.diffuse(basis.truncate(positions, count), heat)
            val reconstructed = basis.reconstructMesh(reduced)
            val standing = basis.mode(mode)
            val displacement =
                if (animate) basis.reconstruct(basis.vibrate(random, tick, damping = 0.006)) else standing
            val displaced = if (flow) source else basis.displaceNormals(displacement, amplitude)
            val scale = standing.maxOf { abs(it) }.coerceAtLeast(1e-12)
            val shaded = displaced.withColors(
                FloatArray(source.vertexCount * 3) { i ->
                    (0.53 + 0.44 * standing[i / 3] / scale).toFloat()
                },
            )
            if (flow && (particles.isEmpty() || mode != previousFlow)) {
                fieldFlow = basis.flow(standing, perpendicular = true)
                particles = List(55) { fieldFlow.start(source.position(it * source.vertexCount / 55)) }
                previousFlow = mode
            }
            if (flow) particles = particles.map { fieldFlow.step(it, 0.04) }
            val dots = FLine().apply {
                if (flow) for (particle in particles) moveTo(fieldFlow.position(particle))
                pointed = true
                stroked = false
                color = vec(1, 1, 1, 1)
                pointSize = 2.0
            }
            val zero = basis.contours(standing).apply {
                color = vec(1, 1, 1, 0.85)
                thickness = 1.65
            }
            val error = basis.reconstructionError(original, reduced)
            three(name = "manifold-harmonics", width = 850, height = 420) {
                background("#080808")
                helpers(false)
                columns(3)
                panel(title = "Source mesh")
                mesh(source, wireframe = true, id = "source")
                panel(title = "Reconstruction", caption = "Area-weighted RMS ${error.f3}")
                mesh(reconstructed, wireframe = false, id = "reconstructed")
                panel(title = "Eigenfunction $mode", caption = "λ = ${eigenvalues[mode].f3}")
                mesh(shaded, wireframe = false, opacity = 0.78, id = "mode")
                lines(listOf(zero, dots), id = "nodal-lines-and-flow")
            }
            if (changed) displayOpen("main")
            tick += 0.12
        }
        delay(125)
    }
}
