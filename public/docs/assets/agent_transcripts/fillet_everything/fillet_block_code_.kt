import afield.geometry.solid.solidcraft.SolidCraftLibrary
import afield.geometry.solid.solidcraft.solidCraft

/*
A 40 × 30 × 10 block with every edge rounded. Where three rounds meet, the rolling ball leaves
an eighth of a sphere, so the whole part has a closed-form volume to check SolidCraft against.
At r = 5, half the block's depth, the rounds on its top and bottom meet.
*/
val (a, b, c) = Triple(40.0, 30.0, 10.0)
val radii = [1.0, 2.0, 4.0, 5.0] // mm

fun roundedBlock(r: Double): Double {
    val (x, y, z) = Triple(a - 2 * r, b - 2 * r, c - 2 * r)
    return x * y * z + 2 * r * (x * y + y * z + z * x) + PI * r * r * (x + y + z) + 4.0 / 3 * PI * r * r * r
}

run {
    if (!SolidCraftLibrary.isAvailable) {
        markdown("SolidCraft is optional. Run `./gradlew buildSolidCraftNative`, restart Field, then run this box.")
        println(SolidCraftLibrary.describe())
        return@run
    }

    val edges =
        solidCraft("block") { extrude(sketch { rectangle(0.0, 0.0, a, b) }, "$c") }
            .open()
            .use { it.mesh().edges() }
            .map { it.pick }
            .toTypedArray()

    val rows = StringBuilder(
        "| radius | SolidCraft volume | rounded block | difference | faces | time |\n|---|---|---|---|---|---|\n",
    )
    val panels = ArrayList<Pair<String, TriMesh>>()
    val refusals = StringBuilder()
    for (r in radii) {
        val start = System.nanoTime()
        val design = runCatching {
            solidCraft("block") {
                extrude(sketch { rectangle(0.0, 0.0, a, b) }, "$c")
                fillet("$r", *edges)
            }.open()
        }
        val millis = (System.nanoTime() - start) / 1_000_000
        val part = design.getOrNull()
        if (part == null) {
            val why = design.exceptionOrNull()?.message.orEmpty().substringBefore("\n").substringAfterLast(": ")
            rows.append("| $r mm | {bad:refused} | | | | $millis ms |\n")
            refusals.append("\n\nAt $r mm: $why.")
            continue
        }
        part.use {
            val body = part.summary().getJSONArray("bodies").getJSONObject(0)
            val volume = body.getDouble("volume")
            val exact = roundedBlock(r)
            rows.append(
                "| $r mm | ${volume.f4} | ${exact.f4} | ${"%.1e".format(volume - exact)} | ${body.getInt("faces")} | $millis ms |\n",
            )
            panels += "r = $r" to part.mesh(tolerance = 0.02).toTriMesh().weld()
        }
    }

    markdown(
        "**Every edge of a $a × $b × $c block, rounded.** Rolling-ball corners are eighths of a sphere.\n\n$rows$refusals",
        width = 820,
    )
    showMeshGrid(
        *panels.toTypedArray(),
        columns = panels.size,
        cellWidth = 270,
        view = MeshView(yawDegrees = 35.0, pitchDegrees = 30.0, distance = 2.0),
    )
}
