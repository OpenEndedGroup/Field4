import afield.graphics.FLine
import afield.manifold.JoinType
import afield.manifold.ManifoldLibrary
import afield.manifold.Solid
import afield.stdlib.MeshView
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

run {

if (!ManifoldLibrary.isAvailable) {
    println(ManifoldLibrary.describe())
    // Deliberately no fenced code block in this string. A triple-backtick run anywhere in a
    // box's code — including in a comment like this one — makes the materialized document.md
    // wrap that property in a FOUR-backtick fence, and the completion corpus's document
    // parser only reads three, so the box silently drops out of tools/completion-corpus.
    // Inline code costs nothing and keeps it in.
    markdown("main", """
        ### This box needs Manifold

        It is an optional native dependency and nothing here vendors it. On macOS, install it
        with `brew install manifold`; on Linux use your package manager or build Manifold with
        `-DMANIFOLD_CBIND=ON`.

        Then run this box again. The binding finds `libmanifoldc` on its own; set
        `-Dafield.manifold.library=<path>` if you built your own.
    """.trimIndent())
    return@run
}

// ---- the shape, as a function ----------------------------------------------------
// Positive inside, which is Manifold's convention. This is a gyroid-thickened torus: the
// kind of shape that only exists as a field, and that plain marching cubes turns into a
// triangle soup wherever the surface nearly touches itself.
val majorRadius = 22.0
val minorRadius = 8.0
val gyroid = 0.55           // 0.0 is a plain torus; push toward 1.0 and it perforates.

fun field(x: Double, y: Double, z: Double): Double {
    val q = hypot(x, y) - majorRadius
    val tube = minorRadius - sqrt(q * q + z * z)
    val s = 0.32
    val g = sin(s * x) * cos(s * y) + sin(s * y) * cos(s * z) + sin(s * z) * cos(s * x)
    return tube - gyroid * minorRadius * kotlin.math.abs(g) * 0.5
}
// ----------------------------------------------------------------------------------

val bounds = boundsOf(
    vec(-(majorRadius + minorRadius + 2), -(majorRadius + minorRadius + 2), -(minorRadius + 2)),
    vec(majorRadius + minorRadius + 2, majorRadius + minorRadius + 2, minorRadius + 2),
)

// The upcall. `field` is called by Manifold's own worker threads — measured at ~2x the
// sequential path on this machine — which is why it must be thread-safe, and is: it reads
// three captured doubles and allocates nothing. Pass parallel = false for one that is not.
val start = System.nanoTime()
val solid = Solid.levelSet(bounds, edgeLength = 1.1, parallel = true, field = ::field)
val marchMs = (System.nanoTime() - start) / 1e6

println("level set: ${"%.0f".format(marchMs)} ms")
println("status     ${solid.status}   — a valid closed manifold, whatever the field did")
println("genus      ${solid.genus}")
println("volume     ${"%.1f".format(solid.volume)} mm^3")
println("mesh       ${solid.triangleCount} triangles")

// Field's own topology code, which shares nothing with Manifold, agrees the surface is
// closed. Note the plain mesh — `normals = true` splits
// vertices at creases for shading, which is right for the picture and would make this
// check read false for a reason that has nothing to do with the surface.
val mesh = solid.toTriMesh()
val shaded = solid.toTriMesh(normals = true)
println("watertight by Field's own reckoning: ${mesh.isWatertight()}")
println("euler characteristic ${mesh.eulerCharacteristic()} = 2 - 2g, so g = ${mesh.genus()}")

// ---- and now slice it, the way a printer would ------------------------------------
// slice() is the operation that makes a solid manufacturable rather than merely
// renderable. Each layer comes back as a Section — a 2D region with holes — which offsets,
// so the inner line here is the path a nozzle of that width would actually walk.
val layers = 13
val zLow = -minorRadius + 0.5
val zHigh = minorRadius - 0.5
val nozzle = 0.9

val outlines = mutableListOf<FLine>()
val paths = mutableListOf<FLine>()
var totalContours = 0
for (i in 0 until layers) {
    val z = zLow + (zHigh - zLow) * i / (layers - 1.0)
    Solid.scope {
        val layer = solid.slice(z)
        totalContours += layer.contourCount.toInt()
        // Draw each layer at its own height, so the stack reads as a stack.
        outlines += layer.toFLines(z = z).onEach {
            it.color = vec(0.55, 0.78, 0.95, 0.55)
            it.thickness = 1.0
        }
        paths += layer.offset(-nozzle / 2, JoinType.ROUND, circularSegments = 24)
            .toFLines(z = z).onEach {
                it.color = vec(1.0, 0.72, 0.35, 0.85)
                it.thickness = 1.6
            }
        Unit
    }
}

println()
println("$layers layers between z=${"%.1f".format(zLow)} and ${"%.1f".format(zHigh)}, " +
    "$totalContours contours in total")
println("orange = the ${nozzle}mm nozzle path, offset inward from each layer's outline")

showMesh(
    shaded,
    channel = "solid",
    title = "A gyroid-thickened torus, marched from a Kotlin lambda",
    caption = "${solid.triangleCount} triangles &middot; genus ${solid.genus} &middot; " +
        "${"%.0f".format(marchMs)} ms across Manifold's thread pool &middot; " +
        "guaranteed closed, so it can be cut",
    view = MeshView(yawDegrees = 24.0, pitchDegrees = 52.0, width = 700, height = 430),
)
display("slices", outlines + paths)
solid.close()

}
