// The point of writing shapes as expressions: every combinator is one line, and the
// smooth versions fillet where the surfaces meet, which is what makes this feel like clay
// rather than CSG. Change a k, re-run this box, look again.
val ball = sdfSphere(0.8)
val bar = sdfBox(vec(2.2, 0.34, 0.34))
val sharp = sdfUnion(ball, bar)
val soft = sdfSmoothUnion(ball, bar, k = 0.3)
val drilled = sdfSmoothSubtract(soft, sdfCylinder(0.28, 4.0), k = 0.1)
val twisted = sdfBox(vec(1.2, 1.8, 0.4)).twisted(0.35)

val window = boundsAround(radius = 1.35)
val sharpMesh = marchingCubes(sharp, window, 72)
val softMesh = marchingCubes(soft, window, 72)
val drilledMesh = marchingCubes(drilled, window, 72)
val twistedMesh = marchingCubes(twisted, boundsAround(radius = 1.2), 72)

// A fillet only ever ADDS material, so the smooth union must enclose more than the sharp
// one. That is a property you can assert rather than eyeball.
println("sharp union  volume %.5f".format(sharpMesh.signedVolume()))
println("smooth union volume %.5f  (the fillet adds %.5f)".format(
    softMesh.signedVolume(), softMesh.signedVolume() - sharpMesh.signedVolume()))
println("drilled      ${drilledMesh.summary()}")
println("twisted      ${twistedMesh.summary()}")

// A shell looked at from outside is indistinguishable from the solid, so cut it open with
// a half-space: intersecting with sdfPlane keeps only the half you want, and the wall
// thickness becomes visible. chi = 2 for the half shell, 4 for the closed one (two
// nested surfaces).
val shell = soft.shell(0.07)
val closedShell = marchingCubes(shell, window, 88)
val cutaway = marchingCubes(sdfIntersect(shell, sdfPlane(vec(0, 0, -1), 0.0)), window, 88)
println("closed shell ${closedShell.summary()}")
println("cut open     ${cutaway.summary()}")

showMeshGrid(
    "sdfUnion(sphere, box)" to sharpMesh,
    "sdfSmoothUnion(…, k = 0.3)" to softMesh,
    "…then sdfSmoothSubtract(cylinder)" to drilledMesh,
    "sdfBox(…).twisted(0.35)" to twistedMesh,
    "shell(0.07) — closed, chi = 4" to closedShell,
    "…cut open by sdfPlane" to cutaway,
    columns = 3,
    cellWidth = 300,
)
