# Field Document: kratos_structural_playground

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. Kratos structural playground

<!-- field-box id="kratos_structural" index="0" -->

```edn [field-box id="kratos_structural" role="properties"]
{"id" "kratos_structural" "index" 0 "name" "Kratos structural playground" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Kratos structural playground" "_tombstone" false "frame" #afield/rect [60.0 220.0 300.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kratos_structural_code_.kt" "lines" 225 "characters" 12151 "sha256" "485a0531fe89b215678c6557376b6638439947318944529db8a24d04f44f49bd"}]}
```

```kotlin [field-property box="kratos_structural" name="code" path="kratos_structural_code_.kt"]
import afield.frames
import afield.kratos.*
import afield.manifold.ManifoldLibrary
import afield.stdlib.MeshView
import afield.stdlib.TriMesh
import afield.stdlib.showMeshGrid
import afield.tetgen.Tetgen
import afield.tetgen.TetgenLibrary
import java.util.concurrent.atomic.AtomicReference

// Launch (Alt-R). Drag a slider to reshape the bracket — that part is instant.
// Press Solve to mesh and run Kratos; a solve is seconds, so it is a button.
// Install once: uv sync --project optional/kratos --frozen
//   (or, where Kratos has no wheels: podman build --platform linux/amd64
//    -t field-kratos:10.4.0 optional/kratos)
//   plus: python3 tools/build_tetgen_native.py
// SI units: metres, newtons, pascals. +Y is up, which is also the mesh viewer's up.

// arm length, element size, element order, tip load (kN), deflection exaggeration
val settings = AtomicReference(listOf(0.24, 0.025, 2.0, 3.0, 300.0))
// "compare" on launch, not "solve": the first thing this box should put on screen is both
// orders side by side, because that comparison is the whole point of it. It costs two
// worker starts, so it is also the slowest thing here — the status line narrates.
val action = AtomicReference("compare")
fun change(index: Int, value: Double) {
    settings.updateAndGet { old -> old.mapIndexed { i, v -> if (i == index) value else v } }
}
controlGroup("controls") {
    slider("Arm length (m)", min=0.12, max=0.36, step=0.01, initialValue=0.24) { change(0,it) }
    slider("Element size (m)", min=0.015, max=0.05, step=0.005, initialValue=0.025) { change(1,it) }
    slider("Element order (1 linear, 2 quadratic)", min=1.0, max=2.0, step=1.0, initialValue=2.0) { change(2,it) }
    slider("Tip load (kN, downward)", min=0.0, max=8.0, step=0.25, initialValue=3.0) { change(3,it) }
    slider("Deflection exaggeration", min=0.0, max=800.0, step=25.0, initialValue=300.0) { change(4,it) }
    button("Solve") { if (it) action.set("solve") }
    button("Compare orders") { if (it) action.set("compare") }
    button("Cancel") { if (it) action.set("cancel") }
}
displayOpen("controls")

fun safe(value: Any?): String =
    value.toString().replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
// Diagnostics arrive as full-precision doubles; a status line wants three figures.
fun num(value: Any?, digits: Int = 3): String =
    (value as? Number)?.let { "%.${digits}f".format(it.toDouble()) } ?: safe(value)
fun mm(value: Any?): String = num(1000.0 * ((value as? Number)?.toDouble() ?: 0.0), 3)
fun status(text: String) {
    display("status", "<div id='kratos-structural-status' style='padding:12px;max-width:820px'>$text</div>")
    displayOpen("status")
}

// Steel, and a bracket 60 mm square in section, standing in +Y because that is the mesh
// viewer's up. The re-entrant corner where the arm meets the post is the whole reason
// this route is body-fitted: a level set rounds that edge by ~0.4 of a background cell at
// every resolution, and it is exactly where the stress peaks.
val SECTION = 0.06
val POST = 0.30
fun bracket(arm: Double): SimulationGeometry = SimulationCSG("bracket",
    SimulationBox("post", SimulationBounds(0.0, 0.0, 0.0, SECTION, POST, SECTION)),
    SimulationBox("arm", SimulationBounds(0.0, POST-SECTION, 0.0, arm, POST, SECTION)))

fun loads(arm: Double, kilonewtons: Double) = StructuralParameters(
    youngModulus = 2.1e11, poissonRatio = 0.3, density = 7850.0,
    gravity = Triple(0.0, -9.81, 0.0),
    // The foot of the post is fully fixed; the load hangs off the end face of the arm.
    clamp = SimulationBounds(-1.0, -1e-6, -1.0, 1.0, 1e-6, 1.0),
    loadRegion = SimulationBounds(arm-1e-6, -1.0, -1.0, arm+1e-6, 1.0, 1.0),
    load = Triple(0.0, -1000.0 * kilonewtons, 0.0))

status("Checking the optional environment…")

frames {
    if (!ManifoldLibrary.isAvailable) {
        status("Manifold unavailable: ${safe(ManifoldLibrary.describe())}"); return@frames
    }
    if (!TetgenLibrary.isAvailable) {
        status("TetGen unavailable: ${safe(TetgenLibrary.describe())}"); return@frames
    }
    val config = KratosConfig.candidates().first()
    var session: KratosSession? = null
    var meshed: Triple<Double, Double, Int>? = null
    var cells = 0
    var shownGeometry = -1.0
    var pending: java.util.concurrent.CompletableFuture<SimulationResult?>? = null
    // Element orders still to solve. "Solve" queues one, "Compare orders" queues both.
    val queue = ArrayDeque<Int>()
    // Finished results by nodes-per-tetrahedron, so the two orders accumulate on screen
    // rather than replacing each other. A result holds its own mesh and arrays, so it
    // stays drawable after its session is closed.
    val results = LinkedHashMap<Int, SimulationResult>()
    var shape = Pair(-1.0, -1.0)
    var drawnFor = Triple(-1.0, -1, 0)
    var resultText = "Meshing…"
    var previous = ""

    try {
        while (true) {
            val values = settings.get()
            val command = action.getAndSet("")

            // Geometry preview is Manifold only and costs nothing, so it follows the
            // slider. Meshing and solving do not.
            if (values[0] != shownGeometry) {
                showMeshGrid("Bracket · drag to orbit" to bracket(values[0]).surface(),
                             channel="geometry", cellWidth=380,
                             view=MeshView(yawDegrees=-28.0, pitchDegrees=12.0))
                displayOpen("geometry")
                shownGeometry = values[0]
            }
            // A different part is a different answer, so old results stop being comparable.
            if (Pair(values[0], values[1]) != shape) {
                shape = Pair(values[0], values[1]); results.clear()
            }

            when (command) {
                "cancel" -> {
                    session?.cancel(); pending = null; queue.clear()
                    resultText = "Cancelled. Press <b>Solve</b> to run again."
                }
                "solve" -> queue.addLast(values[2].toInt())
                "compare" -> { queue.clear(); queue.addLast(1); queue.addLast(2) }
            }

            if (pending == null && queue.isNotEmpty()) {
                val order = queue.removeFirst()
                val key = Triple(values[0], values[1], order)
                if (key != meshed) {
                    // A body-fitted formulation solves the mesh it was given, so changing
                    // the geometry or the discretisation means a new mesh and therefore a
                    // new session. That is the invariant the embedded formulations keep
                    // and this one does not; meshing is ~0.2 s, which is what makes
                    // paying it per edit affordable. `order = 2` asks for 10-node
                    // tetrahedra, which is what stops the element locking in bending.
                    session?.close(); session = null
                    status("Meshing at h = ${num(values[1], 3)} m, order $order…")
                    val built = Tetgen.tetrahedralize(bracket(values[0]).surface(),
                                                      targetEdgeLength = values[1], order = order)
                    cells = built.cellCount
                    status("Meshed to $built. Starting a worker…")
                    val opening = KratosSession.openMeshAsync(built, config)
                    while (!opening.isDone) frame()
                    session = opening.get()
                    meshed = key
                }
                pending = session!!.solveAsync(loads(values[0], values[3]))
                resultText = "Solving $cells tetrahedra at order $order" +
                    (if (queue.isEmpty()) "…" else " (${queue.size} more queued)…")
            }

            val ready = pending
            if (ready != null && ready.isDone) {
                pending = null
                try {
                    ready.get()?.let { results[it.domain.nodesPerCell] = it }
                } catch (failure: Exception) {
                    queue.clear()
                    resultText = "Solve failed: ${safe(failure.cause?.message ?: failure.message)}"
                }
            }

            // Redraw when the set of results changes or the exaggeration slider moves, so
            // the scale is a display control and never costs a solve.
            val stamp = Triple(values[4], results.size, results.keys.sum())
            if (results.isNotEmpty() && stamp != drawnFor) {
                drawnFor = stamp
                val scale = values[4]
                val panels = ArrayList<Pair<String, TriMesh>>()
                for (width in listOf(4, 10)) results[width]?.let {
                    panels += "${if (width == 4) "4-node" else "10-node"} deflected " +
                        "x${scale.toInt()} · ${mm(it.diagnostics["displacementMax"])} mm" to
                        it.deformedMesh(displacementScale = scale)
                }
                panels += "Undeformed, von Mises" to
                    results.values.last().boundaryMesh("vonMises")
                showMeshGrid(*panels.toTypedArray(), channel="stress",
                             columns=panels.size, cellWidth=340,
                             view=MeshView(yawDegrees=-28.0, pitchDegrees=12.0, wireframe=false))
                displayOpen("stress")

                val newest = results.values.last()
                val d = newest.diagnostics
                val comparison = listOf(4, 10).mapNotNull { w ->
                    results[w]?.let {
                        "${if (w == 4) "4-node" else "10-node"} " +
                            "${mm(it.diagnostics["displacementMax"])} mm"
                    }
                }.joinToString(" · ")
                val ratio = results[4]?.let { linear -> results[10]?.let { quad ->
                    " — the linear element reaches " + num(100.0 *
                        (linear.diagnostics["displacementMax"] as Number).toDouble() /
                        (quad.diagnostics["displacementMax"] as Number).toDouble(), 0) +
                        "% of the quadratic one's deflection on the same mesh size"
                } } ?: ""
                resultText = "<span id='kratos-structural-result' " +
                    "data-background='${newest.domain.id}' " +
                    "data-nodespercell='${newest.domain.nodesPerCell}' " +
                    "data-arm='${values[0]}' " +
                    "data-displacementmax='${safe(d["displacementMax"])}'>" +
                    "${safe(d["element"])} · ${safe(d["nodeCount"])} nodes · " +
                    "${safe(d["cellCount"])} tetrahedra<br>" +
                    "Peak von Mises ${num(1e-6 * (d["vonMisesMax"] as Number).toDouble(), 1)} MPa · " +
                    "clamped ${safe(d["clampedNodes"])} · loaded ${safe(d["loadedNodes"])} · " +
                    "residual ${safe(d["relativeLinearResidual"])}<br>" +
                    "Assembly ${num(d["assemblyMs"], 0)} ms · solve ${num(d["solveMs"], 0)} ms · " +
                    "stress ${num(d["stressRecoveryMs"], 0)} ms<br>" +
                    "<b>Same load, same element size: $comparison</b>$ratio</span>"
            }

            val worker = session
            val head = if (worker == null) "<b>no session</b>" else
                "<b>${worker.state}</b> · ${worker.formulation} · revision ${worker.revision}<br>" +
                "Mesh ${worker.domain.id} · ${worker.domain.nodeCount} nodes · " +
                "${worker.domain.cellCount} tetrahedra of ${worker.domain.nodesPerCell} nodes"
            val text = head + "<br>" + resultText +
                "<br><i>Changing the load reuses this mesh and this worker. Changing the arm, " +
                "the element size or the order cannot: a body-fitted mesh is the geometry, so " +
                "it is remeshed and a new session opened.</i>"
            if (text != previous) { status(text); previous = text }
            frame()
        }
    } catch (failure: Exception) {
        status("Unavailable: ${safe(failure.cause?.message ?: failure.message)}")
    } finally {
        session?.close()
    }
}
```

