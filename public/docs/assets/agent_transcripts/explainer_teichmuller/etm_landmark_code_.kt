import afield.geometry.conformal.*
import afield.media.image.util.PngEncoder

/*
A square whose edges stay put while one point inside is dragged somewhere else.
Left: the harmonic map (the solver with μ = 0). Right: the Teichmüller map, found by
the quasi-conformal iteration. Colour is |μ| on each triangle.
*/
val mesh = PlanarMesh.grid(20)
val solver = LinearBeltramiSolver(mesh)
val pinned = mesh.nearest(0.3, 0.5)
var toX = 0.6
var toY = 0.55

fun draw() {
    val fixed = mesh.boundaryIdentity() + (pinned to doubleArrayOf(toX, toY))
    val harmonic = harmonicMap(mesh, fixed, solver)
    val teich = teichmullerMap(mesh, fixed, iterations = 150, solver = solver)
    val picture = MeshPicture(640, 320, -0.05, -0.05, 2.15, 1.05)
    for ((offset, m) in [0.0 to harmonic, 1.1 to teich]) {
        val shifted = DoubleArray(mesh.vertexCount) { m.u[it] + offset }
        picture.mesh(
            mesh,
            shifted,
            m.v,
            IntArray(mesh.triangleCount) { MeshPicture.distortionColor(m.moduli[it], 0.5) },
        )
        picture.dot(mesh.x[pinned] + offset, mesh.y[pinned], 3.0, 0xFFFFFFFF.toInt())
        picture.dot(toX + offset, toY, 5.0, 0xFFFF9933.toInt())
    }
    display("maps", PngEncoder.encode(picture.pixels, 640, 320))
    markdown(
        "readout",
        "harmonic, left: |μ| from ${harmonic.minModulus.f2} to **${harmonic.maxModulus.f2}**, ${harmonic.flipped} triangles turned over · " +
            "Teichmüller, right: |μ| from ${teich.minModulus.f2} to **${teich.maxModulus.f2}**, K = ${teich.dilatation.f2} after ${teich.iterations} iterations",
    )
}

draw()
controlGroup {
    slider("landmark x", 0.15, 0.85, initialValue = toX) {
        toX = it
        draw()
    }
    slider("landmark y", 0.15, 0.85, initialValue = toY) {
        toY = it
        draw()
    }
}
