# Field Document: fillet_everything

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. The plate one chamfer cannot take

<!-- field-box id="fillet_plate" index="0" -->

```edn [field-box id="fillet_plate" role="properties"]
{"id" "fillet_plate" "index" 0 "name" "The plate one chamfer cannot take" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "The plate one chamfer cannot take" "_tombstone" false "frame" #afield/rect [1080.0 80.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fillet_plate_code_.kt" "lines" 74 "characters" 2745 "sha256" "ff4fd7c94143bd83ed8383196499616d31adb1dfbf32585df0be4d9f81a28730"}]}
```

```kotlin [field-property box="fillet_plate" name="code" path="fillet_plate_code_.kt"]
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
```

## 2. Every edge of a block, checked

<!-- field-box id="fillet_block" index="1" -->

```edn [field-box id="fillet_block" role="properties"]
{"id" "fillet_block" "index" 1 "name" "Every edge of a block, checked" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Every edge of a block, checked" "_tombstone" false "frame" #afield/rect [80.0 80.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fillet_block_code_.kt" "lines" 77 "characters" 2968 "sha256" "af78d6cc35d602a33589f1ed5f66db19a029e1a1edeaf0371ed4dd771df32860"}]}
```

```kotlin [field-property box="fillet_block" name="code" path="fillet_block_code_.kt"]
import afield.geometry.mesh.TriMesh
import afield.geometry.mesh.weld
import afield.geometry.solid.solidcraft.SolidCraftLibrary
import afield.geometry.solid.solidcraft.solidCraft
import kotlin.math.PI

/*
A 40 × 30 × 10 block with every edge rounded. Where three rounds meet, the rolling ball leaves
an eighth of a sphere, so the whole part has a closed-form volume to check SolidCraft against.
At r = 5, half the block's depth, the rounds on its top and bottom meet.
*/
val (a, b, c) = Triple(40.0, 30.0, 10.0)
val radii = listOf(1.0, 2.0, 4.0, 5.0) // mm

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
                }
                .open()
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
```

## 3. How round can it go

<!-- field-box id="fillet_ladder" index="2" -->

```edn [field-box id="fillet_ladder" role="properties"]
{"id" "fillet_ladder" "index" 2 "name" "How round can it go" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "How round can it go" "_tombstone" false "frame" #afield/rect [2080.0 80.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fillet_ladder_code_.kt" "lines" 157 "characters" 6545 "sha256" "c0696ef2e7a5bd3e9c612b541a13d63a37cd08762656a515f12f32eba68f0faf"}]}
```

```kotlin [field-property box="fillet_ladder" name="code" path="fillet_ladder_code_.kt"]
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
```

