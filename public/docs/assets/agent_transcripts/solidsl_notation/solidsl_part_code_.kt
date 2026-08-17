import afield.solidsl.*
import afield.manifold.ManifoldLibrary
import afield.stdlib.MeshView
import afield.stdlib.genus
import afield.stdlib.isWatertight
import afield.stdlib.signedVolume

// ---- the three parameters to change ----------------------------------------------
val boreRadius = 0.30   // Push toward 0.55 and the four bores break into each other and
                        // into the plate edge — watch `genus` change rather than the
                        // picture quietly becoming wrong.
val blend = 0.22        // The fillet where the ball meets the post. Set it to 0.0 and
                        // watch the refusal DISAPPEAR: a blend of radius zero is a union,
                        // and a union has an exact CSG form.
val quality = 96        // Segments on the round primitives.
// -----------------------------------------------------------------------------------

val plate = cuboid(3.2, 2.2, 0.42)
val bores = unionOf(
    listOf(-1.1 to -0.7, 1.1 to -0.7, -1.1 to 0.7, 1.1 to 0.7).map { (x, y) ->
        cylinder(2.0, boreRadius, segments = quality).at(x, y, 0.0)
    },
)
val boss = smoothUnion(
    sphere(0.42, segments = quality).at(0.0, 0.0, 0.62),
    cylinder(1.1, 0.26, segments = quality).at(0.0, 0.0, 0.30),
    k = blend,
)
val part = (plate - bores) + boss

run {

// ---- what the expression knows about itself, before anything is meshed ------------
// None of this samples the shape. It is all structural, which is the thing a closure
// cannot offer: `(Vec3) -> Float` can be asked for a value at a point and nothing else.
println("---- the expression, before it is a mesh ----")
println("nodes            ${part.nodeCount()}")
println("bounds           ${part.bounds()}")
println("simplifier       ${part.simplificationReport()}")
println()

println("---- what has no exact CSG form, and why ----")
val refusals = part.allRefusals()
if (refusals.isEmpty()) {
    println("  (nothing — every node lowers exactly; try blend = 0.22 again)")
} else {
    refusals.forEach {
        println("  ${it.node}")
        println("    ${it.why}")
        println("    closable: ${it.closable}")
    }
}
println()

val q = SolidQuality(segments = quality, edgeLengthFraction = 1.0 / 80.0)

if (!ManifoldLibrary.isAvailable) {
    // The honest fallback: the field lowering alone. Everything above still ran, because
    // none of it needed Manifold — the structural answers are free.
    println(ManifoldLibrary.describe())
    println("Showing the field lowering instead: one level set for the WHOLE part,")
    println("including the plate, whose six faces did not need approximating.")
    showMesh(
        part.marchedMesh(q),
        title = "the field lowering — everything marched, including the exact parts",
        caption = "Manifold is not installed. `brew install manifold` and re-run to get " +
            "the hybrid: exact CSG for the plate and its bores, marching only for the blend.",
        view = MeshView(yawDegrees = 34.0, pitchDegrees = 24.0, width = 720, height = 470),
    )
    return@run
}

// ---- the hybrid build: march the SMALLEST subtree that needs it -------------------
val build = part.buildSolid(q)
println("---- the hybrid build ----")
println(build.report())
println()

// Field's own topology, which shares no code with Manifold's.
val mesh = build.solid.toTriMesh()
println("watertight       ${mesh.isWatertight()}")
println("volume           ${"%.6f".format(mesh.signedVolume())}")
println("genus            ${mesh.genus()}")
println("status           ${build.solid.status} — closed however the field behaved")

showMesh(
    build.solid.toTriMesh(normals = true),
    title = "exact where it can be, marched where it cannot",
    caption = "${build.solid.triangleCount} triangles &middot; the plate and its four bores are " +
        "exact CSG &middot; only the blended boss was marched &middot; still one closed solid",
    view = MeshView(yawDegrees = 34.0, pitchDegrees = 24.0, width = 720, height = 470),
)
build.close()

}
