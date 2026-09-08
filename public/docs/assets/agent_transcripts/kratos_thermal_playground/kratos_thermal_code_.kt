import afield.frames
import afield.kratos.*
import afield.manifold.ManifoldLibrary
import afield.stdlib.MeshView
import afield.stdlib.showMeshGrid
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

// Launch (Alt-R). Drag controls in the output or on /controllers.
// Install once: uv sync --project optional/kratos --frozen
// SI units: metres, W/(m K), W/m³, kelvin. Stop closes the worker.
val settings = AtomicReference(listOf(.71, 2.0, 100.0, 293.15))
val action = AtomicReference("")
fun change(index: Int, value: Double) {
    settings.updateAndGet { old -> old.mapIndexed { i, v -> if (i == index) value else v } }
}
controlGroup("controls") {
    slider("Arm end x (m)", min=.30, max=.85, step=.01, initialValue=.71) { change(0,it) }
    slider("Conductivity (W/m K)", min=.5, max=8.0, step=.1, initialValue=2.0) { change(1,it) }
    slider("Heat source (W/m³)", min=0.0, max=300.0, step=5.0, initialValue=100.0) { change(2,it) }
    slider("Skin temperature (K)", min=273.15, max=323.15, step=1.0, initialValue=293.15) { change(3,it) }
    button("Solve now") { if (it) action.set("solve") }
    button("Cancel pending") { if (it) action.set("cancel") }
}
displayOpen("controls")
fun safe(value: Any?): String = value.toString().replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
fun status(text: String) {
    display("status", "<div id='kratos-playground-status' style='padding:12px;max-width:720px'>$text</div>")
    displayOpen("status")
}
status("Starting optional worker… Drag sliders for automatic previews. Stop and relaunch to restart.")

frames {
    if (!ManifoldLibrary.isAvailable) {
        status("Manifold unavailable: ${safe(ManifoldLibrary.describe())}")
        return@frames
    }
    val opening = KratosSession.openAsync(SimulationBounds(-1.0,-1.0,-1.0,1.0,1.0,1.0), resolution=12)
    var session: KratosSession? = null
    try {
        while (!opening.isDone) frame()
        val worker = opening.get()
        session = worker
        println(worker.capabilities)
        var submitted = emptyList<Double>()
        var skin = KratosDemo.body(settings.get()[0]).surface()
        var pending: java.util.concurrent.CompletableFuture<ThermalResult?>? = null
        var baseline: ThermalResult? = null
        var resultText = "No solution yet."
        var previousStatus = ""
        while (true) {
            val values = settings.get()
            val command = action.getAndSet("")
            if (command == "cancel") {
                worker.cancel()
                pending = null
                submitted = values
                resultText = "Cancelled pending work. Move a slider or press Solve now to continue."
            } else if (values != submitted || command == "solve") {
                val geometryChanged = submitted.isEmpty() || values[0] != submitted[0]
                val body = KratosDemo.body(values[0])
                if (geometryChanged) {
                    skin = body.surface()
                    showMeshGrid("Authored solid · drag to orbit" to skin, channel="geometry", cellWidth=360)
                    displayOpen("geometry")
                }
                val edit = when {
                    geometryChanged -> SimulationEdit.GEOMETRY
                    values[1] != submitted[1] -> SimulationEdit.MATERIAL
                    values[3] != submitted[3] -> SimulationEdit.BOUNDARY
                    else -> SimulationEdit.LOAD
                }
                pending = worker.solveAsync(body, ThermalParameters(conductivity=values[1], source=values[2],
                    boundaryTemperature=values[3]), edit=edit, preview=command != "solve")
                submitted = values
            }
            val ready = pending
            if (ready != null && ready.isDone) {
                pending = null
                try {
                    ready.get()?.let { result ->
                        worker.withLatest {
                            val first = baseline ?: result.also { baseline = it }
                            check(first.domain === result.domain && result.domain.buildCount == 1)
                            val changed = first.distance.indices.count { (first.distance[it]>0) != (result.distance[it]>0) }
                            val delta = first.temperature.indices.maxOf { abs(first.temperature[it]-result.temperature[it]) }
                            val visualStart = System.nanoTime()
                            showMeshGrid("First solve · interior temperature" to first.sliceMesh(),
                                "Current solve · interior temperature" to result.sliceMesh(), channel="temperature", cellWidth=360,
                                view=MeshView(yawDegrees=0.0,pitchDegrees=0.0,wireframe=false))
                            displayOpen("temperature")
                            val visualMs = (System.nanoTime()-visualStart)/1e6
                            val d = result.diagnostics
                            resultText = "<span id='kratos-result' data-background='${result.domain.id}' " +
                                "data-revision='${result.revision.simulation}' data-arm='${submitted[0]}' " +
                                "data-changed='$changed' data-delta='$delta'>" +
                                "Result revision ${result.revision} · arm ${submitted[0]} m<br>" +
                                "Changed classification vs first: $changed nodes · max ΔT ${"%.4f".format(delta)} K<br>" +
                                "Temperature ${safe(d["temperatureMin"])}–${safe(d["temperatureMax"])} K<br>" +
                                "Classification ${safe(d["classificationMs"])} ms (${safe(d["classificationScope"])}) · " +
                                "model ${safe(d["modelUpdateMs"])} ms<br>Assembly ${safe(d["assemblyMs"])} ms · solve ${safe(d["solveMs"])} ms<br>" +
                                "Transfer out/in ${safe(d["geometryTransferMs"])}/${safe(d["resultTransferMs"])} ms · view ${"%.1f".format(visualMs)} ms<br>" +
                                "Relative residual ${safe(d["relativeLinearResidual"])}<br>" +
                                "Blue → red: each slice uses its own temperature range; grey means no active support.</span>"
                        }
                    }
                } catch (failure: Exception) {
                    resultText = "Solve failed: ${safe(failure.cause?.message ?: failure.message)}. Adjust controls and retry."
                }
            }
            val text = "<b>${worker.state}</b> · requested revision ${worker.revision}<br>" +
                "Background ${worker.domain.id} · builds ${worker.domain.buildCount}<br>" +
                "${worker.domain.nodeCount} nodes · ${worker.domain.cellCount} tetrahedra · fixed resolution 12<br>" +
                resultText + "<br>Old result views remain visible while a new solve is pending. Stop releases the worker."
            if (text != previousStatus) { status(text); previousStatus = text }
            frame()
        }
    } catch (failure: Exception) {
        status("Worker unavailable: ${safe(failure.cause?.message ?: failure.message)}<br>" +
            "Install with <code>uv sync --project optional/kratos --frozen</code>, then launch again. " +
            "For another environment set AFIELD_KRATOS_PYTHON before starting Field.")
    } finally {
        if (session != null) session!!.close() else opening.thenAccept { it.close() }
    }
}
