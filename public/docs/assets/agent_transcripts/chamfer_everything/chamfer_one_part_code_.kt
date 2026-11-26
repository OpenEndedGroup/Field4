import afield.geometry.solid.solidcraft.SolidCraftBuilder
import afield.geometry.solid.solidcraft.SolidCraftEdge
import afield.geometry.solid.solidcraft.SolidCraftLibrary
import afield.geometry.solid.solidcraft.SolidCraftOperation
import afield.geometry.solid.solidcraft.solidCraft

/*
A plate with n × n bosses, each drilled through: outside corners on the rims, inside corners
where the bosses stand on the plate, and round edges at every hole. Change n or the chamfer
size and run again.
*/
val n = 3
val size = "0.5" // mm, along both faces

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

fun picks(some: List<SolidCraftEdge>) = some.map { it.pick }.toTypedArray()

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
    val convex = edges.filter { it.convex }
    val concave = edges.filter { !it.convex }

    // All of them in one chamfer first, which is what fails.
    start = System.nanoTime()
    val together = runCatching {
        solidCraft("plate") {
            plate()
            chamfer(size, *picks(edges))
        }.open().close()
    }
    val togetherTime = millis(start)
    val why = together.exceptionOrNull()?.message?.substringBefore("\n")?.substringAfterLast(": ")

    // Then the outside corners, then the inside ones, as two chamfers.
    start = System.nanoTime()
    val chamfered =
        solidCraft("plate") {
            plate()
            chamfer(size, *picks(convex))
            chamfer(size, *picks(concave))
        }.open()
    val chamferTime = millis(start)
    val after = chamfered.mesh().toTriMesh().weld()
    val faces = chamfered.summary().getJSONArray("bodies").getJSONObject(0).getInt("faces")
    chamfered.close()

    markdown(
        """
        |**$n × $n plate: ${edges.size} edges**, ${convex.size} outside and ${concave.size} inside. Built in $buildTime ms.
        |
        || chamfer $size mm | result | time |
        ||---|---|---|
        || every edge at once | ${if (why == null) "builds" else "{bad:fails}: $why"} | $togetherTime ms |
        || outside edges, then inside ones | {ok:builds}, $faces faces | $chamferTime ms |""".trimMargin(),
        width = 840,
    )
    showMeshGrid(
        "as built" to mesh.toTriMesh().weld(),
        "every edge chamfered" to after,
        columns = 2,
        cellWidth = 420,
        view = MeshView(yawDegrees = 35.0, pitchDegrees = 35.0, distance = 1.9),
    )
}
