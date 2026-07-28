// A TriMesh is flat FloatArray attribute storage plus an IntArray of triangle indices.
// Every generator returns the MINIMAL closed mesh for its shape, which is a much stronger
// guarantee than "it renders": the Euler characteristic V - E + F is exactly 2 for any
// closed genus-0 solid and 0 for a torus, and that single integer catches a duplicated
// seam, a missing cap or an unwelded weld — all of which still look plausible in a render.
val cube = boxMesh(1, 1, 1)
val ball = icosphereMesh(3, 0.6)
val ring = torusMesh(48, 24, 0.6, 0.22)
val pill = capsuleMesh(32, 8, 0.3, 0.5)
val horn = coneMesh(32, 0.45, 0.9)
val d12 = dodecahedronMesh(0.6)

for ((label, m) in listOf("cube" to cube, "icosphere" to ball, "torus" to ring,
                          "capsule" to pill, "cone" to horn, "dodecahedron" to d12)) {
    val t = m.topology()
    println("%-13s %5d verts %5d tris  chi=%2d genus=%s watertight=%s  volume=%.5f".format(
        label, m.vertexCount, m.triangleCount, t.eulerCharacteristic, t.genus,
        t.isWatertight, m.signedVolume()))
}

// A unit cube is exactly 1 and 6; a sphere of radius r is 4/3 pi r^3 and 4 pi r^2. The
// sphere is an inscribed polyhedron, so it is always a little under.
println()
println("unit cube      volume ${cube.signedVolume()}  area ${cube.surfaceArea()}")
println("icosphere(3)   volume %.6f against 4/3 pi r^3 = %.6f".format(
    ball.signedVolume(), 4.0 / 3.0 * PI * 0.6 * 0.6 * 0.6))
println("icosphere(3)   area   %.6f against 4 pi r^2   = %.6f".format(
    ball.surfaceArea(), 4.0 * PI * 0.6 * 0.6))

// The generators are already welded, so weld() finds nothing to merge and returns the
// identical object. flatShaded() is the opposite: three unshared vertices per triangle,
// which is what a renderer wants and what makes the mesh no longer topologically closed.
println()
println("boxMesh().weld() is the same object: ${cube.weld(1e-6) === cube}")
val faceted = cube.flatShaded()
println("flatShaded(): ${faceted.vertexCount} verts, watertight ${faceted.isWatertight()}")
println("...welded back: ${faceted.weld(1e-5).vertexCount} verts, " +
    "watertight ${faceted.weld(1e-5).isWatertight()}")

showMeshGrid(
    "boxMesh() — 8 verts, not 24" to cube,
    "icosphereMesh(3)" to ball,
    "torusMesh(48, 24) — genus 1" to ring,
    "capsuleMesh(32, 8)" to pill,
    "coneMesh(32)" to horn,
    "dodecahedronMesh() — dual of the icosahedron" to d12,
    columns = 3,
    cellWidth = 300,
)
