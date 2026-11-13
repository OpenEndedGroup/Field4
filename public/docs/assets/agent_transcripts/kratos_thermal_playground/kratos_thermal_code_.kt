import afield.geometry.solid.manifold.ManifoldLibrary
import afield.simulation.kratos.*
import java.util.concurrent.atomic.AtomicReference

fun makeBody(armEnd: Double): SimulationCSG = SimulationCSG(
    "body",
    SimulationBox("base", SimulationBounds(-0.73, -0.63, -0.52, 0.23, 0.53, 0.62)),
    SimulationBox("arm", SimulationBounds(-0.13, -0.32, -0.42, armEnd, 0.33, 0.42)),
)

/*
Launch (Alt-R). Drag controls in the output or on /controllers.
Install once: uv sync --project optional/kratos --frozen
SI units: metres, W/(m K), W/m³, kelvin. Stop closes the worker.
*/
val settings = AtomicReference([.71, 2.0, 100.0, 293.15])
val action = AtomicReference("")
fun change(index: Int, value: Double) {
    settings.updateAndGet { old -> old.mapIndexed { i, v -> if (i == index) value else v } }
}
controlGroup("controls") {
    slider("Arm end x (m)", min = .30, max = .85, step = .01, initialValue = .71) { change(0, it) }
    slider("Conductivity (W/m K)", min = .5, max = 8.0, step = .1, initialValue = 2.0) { change(1, it) }
    slider("Heat source (W/m³)", min = 0.0, max = 300.0, step = 5.0, initialValue = 100.0) { change(2, it) }
    slider("Skin temperature (K)", min = 273.15, max = 323.15, step = 1.0, initialValue = 293.15) { change(3, it) }
    button("Solve now") { if (it) action.set("solve") }
    button("Cancel pending") { if (it) action.set("cancel") }
}
displayOpen("controls")
fun status(text: String) {
    markdown("status", text, width = 720)
}
status("Starting optional worker… Drag sliders for automatic previews. Stop and relaunch to restart.")

frames {
    if (!ManifoldLibrary.isAvailable) {
        status("Manifold unavailable: ${mdEscape(ManifoldLibrary.describe())}")
        return@frames
    }
    val probing = Kratos.probeAny()
    while (!probing.isDone) frame()
    val availability = probing.get()
    if (!availability.available) {
        status(
            "Worker unavailable: ${mdEscape(availability.capability)}\n\n${mdEscape(Kratos.howToInstall(availability.config))}",
        )
        return@frames
    }
    val config = availability.config
    val opening = KratosSession.openAsync(
        SimulationBounds(-1.0, -1.0, -1.0, 1.0, 1.0, 1.0),
        resolution = 12,
        config = config,
    )
    var session: KratosSession? = null
    try {
        while (!opening.isDone) frame()
        val worker = opening.get()
        session = worker
        println(worker.capabilities)
        var submitted = emptyList<Double>()
        var skin = makeBody(settings.get()[0]).surface()
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
                val body = makeBody(values[0])
                if (geometryChanged) {
                    skin = body.surface()
                    showMeshGrid("Authored solid · drag to orbit" to skin, channel = "geometry", cellWidth = 360)
                    displayOpen("geometry")
                }
                val edit = when {
                    geometryChanged -> SimulationEdit.GEOMETRY
                    values[1] != submitted[1] -> SimulationEdit.MATERIAL
                    values[3] != submitted[3] -> SimulationEdit.BOUNDARY
                    else -> SimulationEdit.LOAD
                }
                pending = worker.solveAsync(
                    body,
                    ThermalParameters(
                        conductivity = values[1],
                        source = values[2],
                        boundaryTemperature = values[3],
                    ),
                    edit = edit,
                    preview = command != "solve",
                )
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
                            val changed =
                                first.distance.indices.count { (first.distance[it] > 0) != (result.distance[it] > 0) }
                            val delta =
                                first.temperature.indices.maxOf { abs(first.temperature[it] - result.temperature[it]) }
                            val visualStart = System.nanoTime()
                            showMeshGrid(
                                "First solve · interior temperature" to first.sliceMesh(),
                                "Current solve · interior temperature" to result.sliceMesh(),
                                channel = "temperature",
                                cellWidth = 360,
                                view = MeshView(yawDegrees = 0.0, pitchDegrees = 0.0, wireframe = false),
                            )
                            displayOpen("temperature")
                            val visualMs = (System.nanoTime() - visualStart) / 1e6
                            val d = result.diagnostics
                            fun ms(key: String): String = (d[key] as? Number)?.f1 ?: "?"
                            val minimum = (d["temperatureMin"] as? Number)?.f2 ?: "?"
                            val maximum = (d["temperatureMax"] as? Number)?.f2 ?: "?"
                            resultText =
                                """
                                **Result revision ${result.revision}** · arm ${submitted[0]} m

                                - Changed classification vs first: $changed nodes · max ΔT ${delta.f4} K
                                - Temperature ${minimum}–${maximum} K
                                - Classification ${ms("classificationMs")} ms (${mdEscape(d["classificationScope"])}) · model ${ms("modelUpdateMs")} ms
                                - Assembly ${ms("assemblyMs")} ms · solve ${ms("solveMs")} ms
                                - Transfer out/in ${ms("geometryTransferMs")}/${ms("resultTransferMs")} ms · view ${visualMs.f1} ms
                                - Relative residual ${mdEscape(d["relativeLinearResidual"])}

                                Blue → red: each slice uses its own temperature range; grey means no active support.""".trimIndent()
                        }
                    }
                } catch (failure: Exception) {
                    resultText =
                        "Solve failed: ${mdEscape(failure.cause?.message ?: failure.message)}. Adjust controls and retry."
                }
            }
            val text =
                """
                **${mdEscape(worker.state)}** · requested revision ${worker.revision}

                Background ${worker.domain.id} · builds ${worker.domain.buildCount}
                ${worker.domain.nodeCount} nodes · ${worker.domain.cellCount} tetrahedra · fixed resolution 12

                $resultText

                Old result views remain visible while a new solve is pending. Stop releases the worker.""".trimIndent()
            if (text != previousStatus) {
                status(text)
                previousStatus = text
            }
            frame()
        }
    } catch (cancelled: java.util.concurrent.CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        status(
            "Worker unavailable: ${mdEscape(failure.cause?.message ?: failure.message)}\n\n" +
                mdEscape(Kratos.howToInstall(config)),
        )
    } finally {
        if (session != null) session!!.close() else opening.thenAccept { it.close() }
    }
}
