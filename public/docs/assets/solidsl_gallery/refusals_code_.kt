import afield.solidsl.*
import afield.manifold.ManifoldLibrary
import afield.stdlib.MeshView
import afield.stdlib.isWatertight

// ---- a part that is half CSG and half field -------------------------------------------
// A plate you could machine, unioned with a blob you could not: the fillet where the ball
// meets the post lies on NEITHER of them, so no sequence of unions, intersections and
// differences can produce it. The exact lowering says so instead of guessing.
val fillet = 0.28

val machinable = cuboid(3.2, 3.2, 0.5) - unionOf(
    listOf(-1.1 to -1.1, 1.1 to -1.1, -1.1 to 1.1, 1.1 to 1.1).map { (x, y) ->
        cylinder(2.0, 0.22, segments = 128).at(x, y, 0.0)
    },
)
val organic = smoothUnion(
    sphere(0.62, segments = 128).at(0.0, 0.0, 0.95),
    cylinder(1.6, 0.28, segments = 128).at(0.0, 0.0, 0.45),
    k = fillet,
)
val part = machinable + organic
// ---------------------------------------------------------------------------------------

run {

println("---- what this expression refuses, and why ----")
part.allRefusals().forEach {
    println("  ${it.node}")
    println("    ${it.why}")
    println("    closable: ${it.closable}${if (it.missing.isNotBlank()) " — needs ${it.missing}" else ""}")
}
if (part.allRefusals().isEmpty()) println("  (nothing — every node has an exact CSG form)")
println()

// There are two flavours here, and flattening them would lose the interesting one.
println("The catalogue, in full — try each of these:")
listOf<Pair<String, SolidExpr>>(
    "smoothUnion(a, b, k)" to smoothUnion(sphere(1.0), cuboid(1.0, 1.0, 1.0), k = 0.2),
    "gyroid()" to gyroid(),
    "sphere(1).rounded(0.1)" to sphere(1.0).rounded(0.1),
    "sphere(1).shelled(0.05)" to sphere(1.0).shelled(0.05),
).forEach { (label, e) ->
    println("  %-24s %s".format(label, e.exactRefusal()!!.why.take(96) + "…"))
}
println()
println("The last two are the interesting ones. They are NOT 'we did not get to it': an")
println("exact-looking answer is available — Minkowski sum with a sphere — and it is the")
println("WRONG answer, systematically, by up to that sphere's sagitta, in a direction that")
println("does not shrink as the march refines. Filing that beside a smooth blend would")
println("flatten a real distinction, so the reason says which kind it is.")
println()

if (!ManifoldLibrary.isAvailable) {
    println(ManifoldLibrary.describe())
    return@run
}

// ---- the hybrid: the level set applied at the SMALLEST subtree that needs it -----------
// A naive fallback asks "does the whole tree lower exactly?" and marches everything if the
// answer is no — so one smooth blend anywhere costs a hundred-thousand-triangle
// approximation of a plate that was perfectly exact. This walks down instead.
val q = SolidQuality(segments = 128, edgeLengthFraction = 1.0 / 90.0)
val build = part.buildSolid(q)
println("---- the hybrid build ----")
println(build.report())
println()

// What the naive version would have cost, measured rather than asserted.
val raw = part.bounds()!!
val edge = maxOf(raw.size.x, raw.size.y, raw.size.z) / 90.0
val naive = afield.manifold.Solid.levelSet(raw.expandedBy(2.0 * edge), edge, parallel = true) { x, y, z ->
    part.toLevelSetField().at(x, y, z)
}
println("hybrid  ${build.solid.triangleCount} triangles")
println("naive   ${naive.triangleCount} triangles for the same shape at the same edge length")
println("        — ${"%.2f".format(naive.triangleCount.toDouble() / build.solid.triangleCount)}x, and " +
    "the plate's six faces stay six faces")
println()

val mesh = build.solid.toTriMesh()
println("Field's own topology, sharing nothing with Manifold: watertight ${mesh.isWatertight()}")
println("status ${build.solid.status} — guaranteed closed however the field behaved")

showMesh(
    build.solid.toTriMesh(normals = true),
    channel = "hybrid",
    title = "exact where it can be, marched where it cannot",
    caption = "${build.solid.triangleCount} triangles &middot; the plate and its four bores are exact CSG; " +
        "only the blended post was marched &middot; the whole thing is still one guaranteed-closed solid",
    view = MeshView(yawDegrees = 36.0, pitchDegrees = 22.0, width = 720, height = 470),
)
naive.close()
build.close()

}
