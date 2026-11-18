import afield.geometry.mesh.TriMesh
import afield.geometry.mesh.weld
import afield.geometry.solid.solidcraft.SolidCraftBuilder
import afield.geometry.solid.solidcraft.SolidCraftLibrary
import afield.geometry.solid.solidcraft.SolidCraftOperation
import afield.geometry.solid.solidcraft.solidCraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/*
The same parts, every edge in one fillet: first at 1 mm, then as round as each will go. A radius
too large for the faces around an edge is refused, and the refusal names the largest that fits,
so one fillet asked for at 10 mm finds it. The evaluations run off Field's main loop, inside
go { }, so the table fills in part by part.
*/
val radius = 1.0 // mm
val tooLarge = 10.0 // mm, more than any of these parts can take

class Part(val name: String, val build: SolidCraftBuilder.() -> Unit)

fun SolidCraftBuilder.block() = extrude(sketch { rectangle(0.0, 0.0, 40.0, 30.0) }, "10")

val parts = listOf(
    Part("block") { block() },
    Part("block with a hole") {
        block()
        extrude(sketch { circle(20.0, 15.0, 5.0) }, "40", SolidCraftOperation.CUT, symmetric = true)
    },
    Part("hex nut") {
        val r = 10.0
        val hexagon =
            (0 until 6).flatMap { k ->
                listOf(r * kotlin.math.cos(k * Math.PI / 3), r * kotlin.math.sin(k * Math.PI / 3))
            }
        extrude(sketch { polygon(*hexagon.toDoubleArray()) }, "8")
        extrude(sketch { circle(0.0, 0.0, 5.0) }, "40", SolidCraftOperation.CUT, symmetric = true)
    },
    Part("pyramid") {
        loft(
            listOf(sketch { rectangle(-10.0, -10.0, 10.0, 10.0) }),
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
) +
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

// One try: the time, and either the mesh or SolidCraft's reason for refusing.
class Attempt(val millis: Long, val mesh: TriMesh?, val why: String?) {
    override fun toString() =
        if (mesh != null) "✓ $millis ms" else "{bad:✗} ${why.orEmpty().substringAfterLast(": ").take(48)}"
}

fun attempt(name: String, build: SolidCraftBuilder.() -> Unit): Attempt {
    val start = System.nanoTime()
    return try {
        solidCraft(name) { build() }
            .open()
            .use { design ->
                Attempt((System.nanoTime() - start) / 1_000_000, design.mesh(tolerance = 0.02).toTriMesh().weld(), null)
            }
    } catch (e: IllegalStateException) {
        Attempt((System.nanoTime() - start) / 1_000_000, null, e.message.orEmpty().substringBefore("\n"))
    }
}

val largest = Regex("""largest radius that fits is ([0-9.]+) mm""")

run {
    if (!SolidCraftLibrary.isAvailable) {
        markdown("SolidCraft is optional. Run `./gradlew buildSolidCraftNative`, restart Field, then run this box.")
        println(SolidCraftLibrary.describe())
        return@run
    }

    val title = "**Every edge in one fillet.** Times include building the part.\n\n"
    val header =
        "| part | edges | r = $radius mm | largest r that fits | found in | at the largest r |\n|---|---|---|---|---|---|\n"
    val rows = StringBuilder()
    val roundest = ArrayList<Pair<String, TriMesh>>()

    go {
        val started = System.nanoTime()
        for (part in parts) {
            val row = runInterruptible(Dispatchers.Default) {
                val edges =
                    solidCraft(part.name) { part.build(this) }
                        .open()
                        .use { it.mesh().edges() }
                        .map { it.pick }
                        .toTypedArray()
                val plain = attempt(part.name) {
                    part.build(this)
                    fillet("$radius", *edges)
                }
                val asked = attempt(part.name) {
                    part.build(this)
                    fillet("$tooLarge", *edges)
                }
                val fits = asked.why?.let { largest.find(it)?.groupValues?.get(1) }
                val roundestTry = when {
                    asked.mesh != null -> asked
                    fits != null ->
                        attempt(part.name) {
                            part.build(this)
                            fillet(fits, *edges)
                        }
                    else -> null
                }
                val best =
                    if (asked.mesh != null) "$tooLarge mm or more"
                    else
                        fits?.let { "$it mm" } ?: "{bad:✗} ${asked.why.orEmpty().substringAfterLast(": ").take(40)}"
                roundestTry?.mesh?.let { roundest += "${part.name}, r = ${fits ?: tooLarge}" to it }
                "| ${part.name} | ${edges.size} | $plain | $best | ${asked.millis} ms | ${roundestTry ?: "—"} |\n"
            }
            rows.append(row)
            markdown("$title$header$rows", width = 1000)
        }
        showMeshGrid(
            *roundest.toTypedArray(),
            columns = 4,
            cellWidth = 250,
            view = MeshView(yawDegrees = 35.0, pitchDegrees = 35.0, distance = 2.0),
        )
        val seconds = (System.nanoTime() - started) / 1e9
        markdown("$title$header$rows\nAll ${parts.size} parts in ${seconds.f0} s.", width = 1000)
    }
}
