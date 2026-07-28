// Loop subdivision quadruples the triangles and preserves the Euler characteristic
// exactly, so a watertight input stays watertight at the same genus. The volume falls
// monotonically toward the Loop limit surface — it converges on that, NOT on the sphere
// its control vertices sit on — in geometrically shrinking steps.
var control = icosahedronMesh(0.8)
println("round  tris    volume     step")
var previous = control.signedVolume()
println("%5d %6d %10.5f".format(0, control.triangleCount, previous))
val rounds = ArrayList<TriMesh>()
for (round in 1..3) {
    control = control.loopSubdivide(1)
    rounds.add(control)
    val v = control.signedVolume()
    println("%5d %6d %10.5f %9.5f   chi=%d".format(
        round, control.triangleCount, v, v - previous, control.eulerCharacteristic()))
    previous = v
}

// Smoothing: a Laplacian pass is a diffusion, so it deflates the shape; Taubin's lambda /
// mu pair alternates a shrink with an inflate and holds the volume. Both remove the same
// noise, and the numbers are the whole point.
val rng = java.util.Random(7)
val clean = icosphereMesh(3, 0.8)
val noisy = clean.transformed { p ->
    val s = 1f + (rng.nextFloat() - 0.5f) * 0.18f
    vec(p.x * s, p.y * s, p.z * s)
}.withVertexNormals()
val laplacian = noisy.laplacianSmooth(iterations = 30, lambda = 0.6)
val taubin = noisy.taubinSmooth(iterations = 30, lambda = 0.6, mu = -0.63)
println()
println("clean     volume %.5f".format(clean.signedVolume()))
println("noisy     volume %.5f".format(noisy.signedVolume()))
println("laplacian volume %.5f  <- a diffusion deflates the ball".format(laplacian.signedVolume()))
println("taubin    volume %.5f  <- the lambda/mu pair holds it".format(taubin.signedVolume()))

// QEM decimation: quadric error metric edge collapse with the link condition (so a
// collapse cannot make the mesh non-manifold), a normal-flip veto and a shape veto. The
// deviation is the largest distance either mesh's vertices sit from the other's surface.
val detailed = icosphereMesh(4, 0.8)
println()
println("target    got   deviation   min angle   watertight")
val budgets = ArrayList<Pair<String, TriMesh>>()
for (target in listOf(2000, 600, 150)) {
    val small = detailed.decimate(target)
    val deviation = maxDeviation(detailed, small)
    println("%6d %6d %11.5f %11.1f   %s".format(
        target, small.triangleCount, deviation,
        small.quality().minAngleDegrees, small.isWatertight()))
    budgets.add("decimate($target)" to small)
}

showMeshGrid(
    "icosahedronMesh() control" to icosahedronMesh(0.8),
    "loopSubdivide(1)" to rounds[0],
    "loopSubdivide(3)" to rounds[2],
    "+18% radial noise" to noisy,
    "laplacianSmooth(30) — deflated" to laplacian,
    "taubinSmooth(30) — volume held" to taubin,
    budgets[0].first to budgets[0].second,
    budgets[1].first to budgets[1].second,
    budgets[2].first to budgets[2].second,
    columns = 3,
    cellWidth = 290,
)
