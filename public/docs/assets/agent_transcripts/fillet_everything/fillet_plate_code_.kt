import afield.geometry.mesh.weld
import afield.geometry.solid.solidcraft.SolidCraftBuilder
import afield.geometry.solid.solidcraft.SolidCraftLibrary
import afield.geometry.solid.solidcraft.SolidCraftOperation
import afield.geometry.solid.solidcraft.solidCraft

/*
The plate of bosses that one chamfer cannot take, rounded in one fillet: outside corners on the
rims, inside corners where the bosses stand on the plate, and round edges at every hole. Change
n or the radius and run again.
*/
val n = 3
val radius = "0.5" // mm

fun SolidCraftBuilder.plate() {
    val side = 10.0 * n + 10
    extrude(sketch { rectangle(0.0, 0.0, side, side) }, "4")
    val bosses = sketch(offsetPlane("XY", "4")) {
        for (i in 0 until n) for (j in 0 until n) rectangle(
            7.0 + 10 * i,
            7.0 + 10 * j,
            13.0 + 10 * i,
            13.0 + 10 * j,
        )
    }
    extrude(bosses, "4", SolidCraftOperation.JOIN)
    val holes = sketch { for (i in 0 until n) for (j in 0 until n) circle(10.0 + 10 * i, 10.0 + 10 * j, 1.5) }
    extrude(holes, "40", SolidCraftOperation.CUT, symmetric = true)
}

fun millis(start: Long) = (System.nanoTime() - start) / 1_000_000

run {
    if (!SolidCraftLibrary.isAvailable) {
        markdown("SolidCraft is optional. Run `./gradlew buildSolidCraftNative`, restart Field, then run this box.")
        println(SolidCraftLibrary.describe())
        return@run
    }

    // Every edge, read off the evaluated part's mesh where two of its faces meet.
    var start = System.nanoTime()
    val asBuilt = solidCraft("plate") { plate() }.open()
    val buildTime = millis(start)
    val mesh = asBuilt.mesh()
    asBuilt.close()
    val edges = mesh.edges()
    val outside = edges.count { it.convex }

    // All of them, outside and inside alike, in one fillet.
    start = System.nanoTime()
    val rounded =
        solidCraft("plate") {
                plate()
                fillet(radius, *edges.map { it.pick }.toTypedArray())
            }
            .open()
    val filletTime = millis(start)
    val after = rounded.mesh(tolerance = 0.02).toTriMesh().weld()
    val faces = rounded.summary().getJSONArray("bodies").getJSONObject(0).getInt("faces")
    rounded.close()

    markdown(
        "**$n × $n plate: ${edges.size} edges**, $outside outside and ${edges.size - outside} inside, in one $radius mm fillet: " +
            "{ok:builds}, $faces faces, in $filletTime ms. The plate itself builds in $buildTime ms.",
        width = 840,
    )
    showMeshGrid(
        "as built" to mesh.toTriMesh().weld(),
        "every edge filleted" to after,
        columns = 2,
        cellWidth = 420,
        view = MeshView(yawDegrees = 35.0, pitchDegrees = 35.0, distance = 1.9),
    )
}
