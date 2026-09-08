import afield.frames
import afield.kratos.*
import afield.manifold.ManifoldLibrary
import afield.stdlib.MeshView
import afield.stdlib.showMeshGrid
import kotlin.math.abs

// Launch this box once (Alt-R). Worker startup and solves yield to Field's frame loop.
// Conductivity: W/(m K); source: W/m³; embedded skin temperature: K.
val material = ThermalParameters(conductivity=2.0, source=100.0, boundaryTemperature=293.15)
val original = KratosDemo.body(.71)
val edited = KratosDemo.body(.43)
check(ManifoldLibrary.isAvailable) { ManifoldLibrary.describe() }
val originalSkin = original.surface()
val editedSkin = edited.surface()
showMeshGrid("Original cuboid assembly" to originalSkin, "Resized arm" to editedSkin, channel="geometry", cellWidth=360)

frames {
    val opening = KratosSession.openAsync(SimulationBounds(-1.0,-1.0,-1.0,1.0,1.0,1.0),resolution=12)
    var session: KratosSession? = null
    try {
        while (!opening.isDone) frame()
        val worker = opening.get()
        session = worker
        println(worker.capabilities)
        val first = worker.solveAsync(original,material)
        while (!first.isDone) frame()
        val a = first.get()!!
        // Two immediate edits: only the newest preview is allowed to publish.
        val obsolete = worker.solveAsync(KratosDemo.body(.61),material,preview=true)
        val second = worker.solveAsync(edited,material,preview=true)
        while (!second.isDone || !obsolete.isDone) frame()
        check(obsolete.get() == null)
        val b = second.get()!!
        check(a.domain === b.domain && b.domain.buildCount == 1)
        val changed = a.distance.indices.count { (a.distance[it]>0) != (b.distance[it]>0) }
        val delta = a.temperature.indices.maxOf { abs(a.temperature[it]-b.temperature[it]) }
        check(changed>0 && delta>0)
        val visualStart = System.nanoTime()
        worker.withLatest {
            showMeshGrid("Original: interior temperature" to a.sliceMesh(), "Resized: interior temperature" to it.sliceMesh(),
                channel="temperature", cellWidth=360, view=MeshView(yawDegrees=0.0,pitchDegrees=0.0,wireframe=false))
        }
        val visualMs = (System.nanoTime()-visualStart)/1e6
        // Prescribed surface temperatures are nearly constant. Interior slices show the physics.
        println("FIRST ${a.diagnostics}")
        println("SECOND ${b.diagnostics}")
        display("diagnostics", "<div id='kratos-complete' style='padding:12px;color:#cde'>" +
            "Embedded conduction · same background ${b.domain.id}<br>" +
            "${b.domain.nodeCount} nodes · ${b.domain.cellCount} tetrahedra · build count 1<br>" +
            "Changed classification: $changed nodes · maximum change: ${"%.4f".format(delta)} K<br>" +
            "Range after edit: ${"%.3f".format(b.diagnostics["temperatureMin"])}–${"%.3f".format(b.diagnostics["temperatureMax"])} K<br>" +
            "Blue = minimum; red = maximum (each panel uses its own range).<br>" +
            "Stale result rejected · visualization submission ${"%.1f".format(visualMs)} ms<br>" +
            "Assembly ${b.diagnostics["assemblyMs"]} ms · solve ${b.diagnostics["solveMs"]} ms<br>" +
            "Relative linear residual ${b.diagnostics["relativeLinearResidual"]}</div>")
        println("KRATOS_DEMO_COMPLETE noRemesh=true staleRejected=true")
    } finally {
        if (session != null) session!!.close()
        else opening.thenAccept { it.close() }
    }
}
