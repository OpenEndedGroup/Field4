// A ScalarField3D is just (Vec3) -> Float. marchingCubes samples it once per grid corner,
// shares one vertex per crossed grid edge, and resolves the ambiguous four-crossing face
// with the asymptotic decider — which is a pure function of that face's four corner
// values, so the two cubes either side of the face always agree. That agreement is why
// the output is closed; plain marching cubes leaks exactly there.
val exact = 4.0 / 3.0 * PI
println("resolution   tris    volume     relative error")
for (resolution in listOf(12, 24, 48, 64)) {
    val m = marchingCubes(sdfSphere(1.0), boundsAround(radius = 1.4), resolution)
    val error = abs(m.signedVolume() - exact) / exact
    println("%10d %7d %10.6f %14.5f   watertight=%s chi=%d".format(
        resolution, m.triangleCount, m.signedVolume(), error,
        m.isWatertight(), m.eulerCharacteristic()))
}
println("the analytic answer is 4/3 pi = $exact")

val coarse = marchingCubes(sdfSphere(1.0), boundsAround(radius = 1.4), 16)
val fine = marchingCubes(sdfSphere(1.0), boundsAround(radius = 1.4), 64)

// Drilling straight through a solid adds a handle, so the genus goes from 0 to 1. That is
// the topology layer telling you something a picture cannot.
val drilled = marchingCubes(
    sdfSubtract(sdfSphere(1.0), sdfCylinder(0.3, 4.0)),
    boundsAround(radius = 1.3), 64,
)
println()
println("drilled sphere: ${drilled.summary()}")

// A gyroid is a triply-periodic minimal surface: one connected sheet with many handles,
// so its Euler characteristic is strongly negative. The marcher does not mind that the
// gyroid is not a distance function at all — it only ever looks at signs.
val gyroid = marchingCubes(sdfGyroid(4.0, 0.3), boundsAround(radius = 1.0), 56)
println("gyroid: ${gyroid.summary()}")

showMeshGrid(
    "sdfSphere marched at 16" to coarse,
    "...at 64" to fine,
    "sdfSubtract(sphere, cylinder) — genus 1" to drilled,
    "sdfGyroid(4.0, 0.3)" to gyroid,
    columns = 2,
    cellWidth = 380,
)
