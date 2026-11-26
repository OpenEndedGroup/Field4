import afield.geometry.solid.solidcraft.SolidCraftBuilder
import afield.geometry.solid.solidcraft.SolidCraftEdge
import afield.geometry.solid.solidcraft.SolidCraftLibrary
import afield.geometry.solid.solidcraft.SolidCraftOperation
import afield.geometry.solid.solidcraft.solidCraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/*
Parts in rising order of trouble, each chamfered on every edge three ways: one chamfer, outside
edges then inside ones as two chamfers, and one fillet of the same size for comparison. The
evaluations run off Field's main loop, inside go { }, so the table fills in part by part.
*/
val size = "1" // mm

class Part(val name: String, val build: SolidCraftBuilder.() -> Unit)

fun SolidCraftBuilder.block() = extrude(sketch { rectangle(0.0, 0.0, 40.0, 30.0) }, "10")

val parts = [
    Part("block") { block() },
    Part("block with a hole") {
        block()
        extrude(sketch { circle(20.0, 15.0, 5.0) }, "40", SolidCraftOperation.CUT, symmetric = true)
    },
    Part("hex nut") {
        val r = 10.0
        val hexagon =
            (0 until 6).flatMap { k ->
                [r * kotlin.math.cos(k * Math.PI / 3), r * kotlin.math.sin(k * Math.PI / 3)]
            }
        extrude(sketch { polygon(*hexagon.toDoubleArray()) }, "8")
        extrude(sketch { circle(0.0, 0.0, 5.0) }, "40", SolidCraftOperation.CUT, symmetric = true)
    },
    Part("pyramid") {
        loft(
            [sketch { rectangle(-10.0, -10.0, 10.0, 10.0) }],
            apex = sketch(offsetPlane("XY", "20")) { point(0.0, 0.0) },
        )
    },
    Part("stepped shaft") {
        revolve(sketch { polygon(0.0, 0.0, 8.0, 0.0, 8.0, 10.0, 5.0, 10.0, 5.0, 25.0, 0.0, 25.0) }, "y")
    },
    Part("block with a pocket") {
        block()
        extrude(sketch(offsetPlane("XY", "6")) { rectangle(10.0, 8.0, 30.0, 22.0) }, "10", SolidCraftOperation.CUT)
    },
    Part("L bracket, two holes") {
        extrude(
            sketch("XZ") { polygon(0.0, 0.0, 40.0, 0.0, 40.0, 6.0, 6.0, 6.0, 6.0, 30.0, 0.0, 30.0) },
            "20",
            symmetric = true,
        )
        extrude(sketch { circle(25.0, 0.0, 3.0) }, "40", SolidCraftOperation.CUT, symmetric = true)
        extrude(sketch("YZ") { circle(0.0, 18.0, 3.0) }, "40", SolidCraftOperation.CUT, symmetric = true)
    },
] +
        (1..4).map { n ->
            Part("plate, $n × $n bosses") {
                extrude(sketch { rectangle(0.0, 0.0, 10.0 * n + 10, 10.0 * n + 10) }, "4")
                val bosses = sketch(offsetPlane("XY", "4")) {
                    for (i in 0 until n) for (j in 0 until n) rectangle(
                        7.0 + 10 * i,
                        7.0 + 10 * j,
                        13.0 + 10 * i,
                        13.0 + 10 * j,
                    )
                }
                extrude(bosses, "4", SolidCraftOperation.JOIN)
            }
        }

fun picks(some: List<SolidCraftEdge>) = some.map { it.pick }.toTypedArray()

// One try: the time, and either the mesh or SolidCraft's reason for refusing.
class Attempt(val millis: Long, val mesh: TriMesh?, val why: String?) {
    override fun toString() = if (mesh != null) "✓ $millis ms" else "✗ $why ($millis ms)"
}

fun attempt(name: String, build: SolidCraftBuilder.() -> Unit): Attempt {
    val start = System.nanoTime()
    return try {
        solidCraft(name) { build() }
            .open()
            .use { design ->
                val mesh = design.mesh().toTriMesh().weld()
                Attempt((System.nanoTime() - start) / 1_000_000, mesh, null)
            }
    } catch (e: IllegalStateException) {
        val reason = e.message.orEmpty().substringAfter("SolidCraft: ").substringBefore("\n")
        val short = when {
            "Vertex Computation Failure" in reason -> "corner vertex failed"
            "meets a corner of this kind" in reason -> "corner not supported"
            else -> reason.take(60)
        }
        Attempt((System.nanoTime() - start) / 1_000_000, null, short)
    }
}

run {
    if (!SolidCraftLibrary.isAvailable) {
        markdown("SolidCraft is optional. Run `./gradlew buildSolidCraftNative`, restart Field, then run this box.")
        println(SolidCraftLibrary.describe())
        return@run
    }

    val header =
        "| part | edges (out / in) | built | one chamfer | out, then in | one fillet |\n|---|---|---|---|---|---|\n"
    val rows = StringBuilder()
    val results = ArrayList<Pair<String, TriMesh>>()

    go {
        val started = System.nanoTime()
        for (part in parts) {
            val row = runInterruptible(Dispatchers.Default) {
                val built = attempt(part.name) { part.build(this) }
                val edges = solidCraft(part.name) { part.build(this) }.open().use { it.mesh().edges() }
                val convex = edges.filter { it.convex }
                val concave = edges.filter { !it.convex }
                val once = attempt(part.name) {
                    part.build(this)
                    chamfer(size, *picks(edges))
                }
                val twice =
                    if (concave.isEmpty()) null
                    else
                        attempt(part.name) {
                            part.build(this)
                            chamfer(size, *picks(convex))
                            chamfer(size, *picks(concave))
                        }
                val fillet = attempt(part.name) {
                    part.build(this)
                    fillet(size, *picks(edges))
                }
                val chamfered = once.mesh ?: twice?.mesh
                if (chamfered != null) results += part.name to chamfered
                else fillet.mesh?.let { results += "${part.name}, filleted instead" to it }
                "| ${part.name} | ${edges.size} (${convex.size} / ${concave.size}) | ${built.millis} ms | $once | ${twice ?: "—"} | $fillet |\n"
            }
            rows.append(row)
            markdown("**Every edge, $size mm.** Times include building the part.\n\n$header$rows", width = 1040)
        }
        showMeshGrid(
            *results.toTypedArray(),
            columns = 4,
            cellWidth = 260,
            view = MeshView(yawDegrees = 35.0, pitchDegrees = 35.0, distance = 2.0),
        )
        val seconds = (System.nanoTime() - started) / 1e9
        markdown(
            "**Every edge, $size mm.** Times include building the part.\n\n$header$rows\nAll ${parts.size} parts in ${seconds.f0} s.",
            width = 1040,
        )
    }
}
