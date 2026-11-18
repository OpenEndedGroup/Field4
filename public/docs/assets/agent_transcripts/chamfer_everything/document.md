# Field Document: chamfer_everything

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. Which parts take it

<!-- field-box id="chamfer_ladder" index="0" -->

```edn [field-box id="chamfer_ladder" role="properties"]
{"id" "chamfer_ladder" "index" 0 "name" "Which parts take it" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Which parts take it" "_tombstone" false "frame" #afield/rect [1080.0 80.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "chamfer_ladder_code_.kt" "lines" 158 "characters" 6654 "sha256" "19003d1d2bbb479a837d4ced85e685a5d6c539b096e9174c40d428a73cc9ddb7"}]}
```

```kotlin [field-property box="chamfer_ladder" name="code" path="chamfer_ladder_code_.kt"]
import afield.geometry.mesh.TriMesh
import afield.geometry.mesh.weld
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
```

## 2. Chamfer everything on one part

<!-- field-box id="chamfer_one_part" index="1" -->

```edn [field-box id="chamfer_one_part" role="properties"]
{"id" "chamfer_one_part" "index" 1 "name" "Chamfer everything on one part" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Chamfer everything on one part" "_tombstone" false "frame" #afield/rect [80.0 80.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "chamfer_one_part_code_.kt" "lines" 97 "characters" 3566 "sha256" "984d78526b94c412deda2ff5a878253ae357f8e52e3685b028b68d38c4b70e68"}]}
```

```kotlin [field-property box="chamfer_one_part" name="code" path="chamfer_one_part_code_.kt"]
import afield.geometry.mesh.weld
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
            }
            .open()
            .close()
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
            }
            .open()
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
```

