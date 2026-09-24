# Field Document: kratos_flow_playground

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. Kratos flow playground

<!-- field-box id="kratos_flow" index="0" -->

```edn [field-box id="kratos_flow" role="properties"]
{"id" "kratos_flow" "index" 0 "name" "Kratos flow playground" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Kratos flow playground" "_tombstone" false "frame" #afield/rect [60.0 140.0 380.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kratos_flow_code_.kt" "lines" 169 "characters" 9417 "sha256" "27bcc85378ed013e20217836c3b0fc14d7849ecd7e8bc70065bc03a3dba1ecc1"}]}
```

```kotlin [field-property box="kratos_flow" name="code" path="kratos_flow_code_.kt"]
import afield.frames
import afield.kratos.*
import afield.manifold.ManifoldLibrary
import afield.stdlib.MeshRenderer
import afield.stdlib.MeshView
import afield.stdlib.TriMesh
import afield.stdlib.showMeshGrid
import afield.stdlib.tubeMesh
import java.util.concurrent.atomic.AtomicReference

// Launch (Alt-R). Drag a slider to reshape the body — that part is instant.
// Press Solve to run Kratos; a solve is seconds, not milliseconds, so it is a button.
// Install once: uv sync --project optional/kratos --frozen
//   (or, where Kratos has no wheels: podman build --platform linux/amd64
//    -t field-kratos:10.4.0 optional/kratos)
// SI units: metres, m/s, kg/m³, Pa·s.

val settings = AtomicReference(listOf(0.30, 1.0, 0.05, 3.0))
// Solve once on launch, so the box shows a picture without a click.
val action = AtomicReference("solve")
fun change(index: Int, value: Double) {
    settings.updateAndGet { old -> old.mapIndexed { i, v -> if (i == index) value else v } }
}
controlGroup("controls") {
    slider("Obstacle radius (m)", min=0.15, max=0.45, step=0.01, initialValue=0.30) { change(0,it) }
    slider("Free-stream speed (m/s)", min=0.25, max=3.0, step=0.05, initialValue=1.0) { change(1,it) }
    slider("Viscosity (Pa s)", min=0.01, max=0.20, step=0.005, initialValue=0.05) { change(2,it) }
    slider("Time steps", min=1.0, max=8.0, step=1.0, initialValue=3.0) { change(3,it) }
    button("Solve") { if (it) action.set("solve") }
    button("Cancel") { if (it) action.set("cancel") }
}
displayOpen("controls")

fun safe(value: Any?): String =
    value.toString().replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
// Diagnostics arrive as full-precision doubles; a status line wants three figures.
fun num(value: Any?, digits: Int = 3): String =
    (value as? Number)?.let { "%.${digits}f".format(it.toDouble()) } ?: safe(value)
fun status(text: String) {
    display("status", "<div id='kratos-flow-status' style='padding:12px;max-width:760px'>$text</div>")
    displayOpen("status")
}

fun reynoldsOf(v: List<Double>): Double =
    FlowParameters(velocity=v[1], viscosity=v[2], steps=v[3].toInt()).reynolds(2*v[0])

// A sphere with a rod through it: two primitives and a union, so an edit exercises the
// constructive path rather than one hard-coded shape.
fun body(radius: Double): SimulationGeometry = SimulationGroup("body", SimulationBoolean.UNION, listOf(
    SimulationSphere("ball", 0.0, 0.0, 0.0, radius, segments = 48),
    SimulationCylinder("pin", 0.0, 0.0, -0.55, 0.0, 0.0, 0.55, radius * 0.28, segments = 24)))

status("Starting the optional worker…")

frames {
    if (!ManifoldLibrary.isAvailable) {
        status("Manifold unavailable: ${safe(ManifoldLibrary.describe())}")
        return@frames
    }
    // Whichever launch this machine can run: the local venv, or the container image.
    // Taking the first candidate rather than probing avoids paying a second worker
    // startup just to ask whether the first one would have worked; openAsync says so
    // clearly enough if it cannot.
    val config = KratosConfig.candidates().first()
    val bounds = SimulationBounds(-1.0,-0.6,-0.6, 2.0,0.6,0.6)
    val opening = KratosSession.openAsync(bounds, resolution=8, config=config,
                                          formulation=FlowParameters.FORMULATION)
    var session: KratosSession? = null
    try {
        while (!opening.isDone) frame()
        val worker = opening.get()
        session = worker
        var shown = emptyList<Double>()
        var pending: java.util.concurrent.CompletableFuture<SimulationResult?>? = null
        var resultText = "No solve yet. Press <b>Solve</b>."
        var previous = ""

        while (true) {
            val values = settings.get()
            val command = action.getAndSet("")

            // Geometry preview is Manifold only and costs nothing, so it follows the
            // slider. The solve does not.
            if (values != shown) {
                showMeshGrid("Obstacle · drag to orbit" to body(values[0]).surface(),
                             channel="geometry", cellWidth=380)
                displayOpen("geometry")
                shown = values
            }

            if (command == "cancel") {
                worker.cancel(); pending = null
                resultText = "Cancelled. Press <b>Solve</b> to run again."
            } else if (command == "solve" && pending == null) {
                val parameters = FlowParameters(velocity=values[1], viscosity=values[2],
                                                steps=values[3].toInt())
                pending = worker.solveAsync(body(values[0]), parameters)
                resultText = "Solving ${values[3].toInt()} steps at Re " +
                    "${"%.0f".format(parameters.reynolds(2*values[0]))} on the obstacle…"
            }

            val ready = pending
            if (ready != null && ready.isDone) {
                pending = null
                try {
                    ready.get()?.let { result ->
                        worker.withLatest {
                            val skin = body(shown[0]).surface()
                            // Colour the body by pressure, then add one tube per
                            // streamline coloured by speed, into a single scene.
                            var scene: TriMesh = result.surfaceMesh(skin, "pressure")
                            for (line in result.streamlines(result.inletSeeds(across=7))) {
                                scene += result.surfaceMesh(
                                    tubeMesh(line, radius=0.012, sides=6, samples=140), "velocity")
                            }
                            // Two channels, because the two pictures want different
                            // cameras: the streamlines are a 3D scene to orbit, the
                            // slice is a plane and is only legible face-on.
                            showMeshGrid("Streamlines coloured by speed, body by pressure" to scene,
                                channel="flow", cellWidth=760,
                                view=MeshView(yawDegrees=-24.0, pitchDegrees=16.0, wireframe=false))
                            // CPU here on purpose: the Three viewer keeps its own
                            // camera, and a wake plane is only legible face-on, which
                            // is what MeshView's angles get you on the raster path.
                            showMeshGrid("Mid-plane slice, coloured by speed" to
                                    result.sliceMesh(samples=110, field="velocity"),
                                channel="slice", cellWidth=760, renderer=MeshRenderer.CPU,
                                view=MeshView(yawDegrees=0.0, pitchDegrees=0.0, wireframe=false))
                            displayOpen("flow")
                            displayOpen("slice")
                            val d = result.diagnostics
                            // The extremes over the fluid only. Nodes inside the body
                            // hold extension values the solver never constrained.
                            val (slowest, fastest) = result.range("velocity")
                            resultText = "<span id='kratos-flow-result' " +
                                "data-background='${result.domain.id}' " +
                                "data-revision='${result.revision.simulation}' " +
                                "data-radius='${shown[0]}' data-speedmax='${safe(d["speedMax"])}'>" +
                                "radius ${num(shown[0], 2)} m · ${safe(d["steps"])} steps of " +
                                "${num(d["timeStep"], 2)} s · Re ${num(reynoldsOf(shown), 0)}<br>" +
                                "Speed ${num(slowest)}–${num(fastest)} m/s · " +
                                "pressure ${num(d["pressureMin"], 2)} to ${num(d["pressureMax"], 2)} Pa<br>" +
                                "${safe(d["status"])} · Newton iterations ${safe(d["newtonIterations"])}<br>" +
                                "Active cells ${safe(d["activeElements"])} · cut ${safe(d["cutElements"])} · " +
                                "classification ${num(d["classificationMs"], 1)} ms<br>" +
                                "Newton loop ${num(d["newtonLoopMs"], 0)} ms · " +
                                "round trip ${num(d["roundTripMs"], 0)} ms</span>"
                        }
                    }
                } catch (failure: Exception) {
                    resultText = "Solve failed: ${safe(failure.cause?.message ?: failure.message)}"
                }
            }

            val text = "<b>${worker.state}</b> · ${worker.formulation} · revision ${worker.revision}<br>" +
                "Background ${worker.domain.id} · builds ${worker.domain.buildCount} · " +
                "${worker.domain.nodeCount} nodes · ${worker.domain.cellCount} tetrahedra<br>" +
                resultText +
                "<br><i>The background grid is built once. Changing the radius reclassifies it; " +
                "it never rebuilds.</i>"
            if (text != previous) { status(text); previous = text }
            frame()
        }
    } catch (failure: Exception) {
        status("Worker unavailable: ${safe(failure.cause?.message ?: failure.message)}")
    } finally {
        if (session != null) session!!.close() else opening.thenAccept { it.close() }
    }
}
```

