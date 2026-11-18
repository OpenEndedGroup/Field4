import afield.geometry.path.FLine
import afield.graphics.render.color
import afield.graphics.render.thickness
import afield.io.drawingon.DrawingOnNetworkConfig
import afield.io.drawingon.DrawingOnNetworkStroke
import afield.io.drawingon.fitDrawingOnNetwork
import afield.math.linalg.vec
import afield.ui.output.controlGroup
import afield.ui.visualization.lines
import kotlin.math.cos
import kotlin.math.sin

// Paste into a Field box. The fixed fixture works without an iPad.
fun stroke(id: String, vararg points: Pair<Int, Int>, tool: String = "freehand") =
    DrawingOnNetworkStroke(id, "plan", tool, points.map { (x, y) -> vec(x, y, 0) })

fun arch(id: String, drift: Double) =
    DrawingOnNetworkStroke(id, "plan", "freehand", (0..40).map { i ->
        val angle = Math.PI * i / 40
        vec(132 + (42 + drift * sin(angle * 6)) * cos(angle), 168 - (42 + drift * sin(angle * 6)) * sin(angle), 0)
    })

val strokes = listOf(
    stroke("wall", 30 to 45, 65 to 44, 110 to 46, 155 to 45),
    stroke("wall retrace", 155 to 45, 110 to 45, 65 to 45, 30 to 45),
    stroke("other wall face", 30 to 49, 155 to 49),
    stroke("wall after door", 173 to 45, 233 to 45, tool = "line"),
    stroke("partition", 77 to 12, 78 to 43),
    stroke("crossing annotation", 115 to 28, 132 to 67),
    arch("arch", 0.0),
    arch("arch retrace", 0.8),
    stroke("column", 210 to 120, 227 to 120, tool = "circle")
)

var fitTolerance = 1.25
var retraceTolerance = 2.0
var joinTolerance = 5.0

fun drawWorkout() {
    val graph = fitDrawingOnNetwork(
        strokes,
        DrawingOnNetworkConfig(fitTolerance.toFloat(), retraceTolerance.toFloat(), joinTolerance.toFloat())
    )
    fun raw(stroke: DrawingOnNetworkStroke): FLine {
        val path = FLine().moveTo(stroke.points.first())
        stroke.points.drop(1).forEach { path.lineTo(it) }
        return path
    }
    val bounds = FLine().moveTo(0, 0).lineTo(265, 195).apply { color = vec(1, 1, 1, 0) }
    val measured = strokes.map { raw(it).apply { color = vec(1, 1, 1, 0.55) } }
    val grouped = graph.clusters.flatMapIndexed { index, group ->
        val hue = index * 2.4
        val colorValue = vec(0.65 + 0.32 * cos(hue), 0.65 + 0.32 * cos(hue + 2.1), 0.65 + 0.32 * cos(hue + 4.2), 1)
        group.strokes.map { raw(it).apply { color = colorValue } }
    }
    val clean = graph.toFLines(showNodes = true).onEach { it.color = vec(1, 1, 1, 0.9); it.thickness = 1.4 }
    lines(measured + bounds.copy(), channel = "raw strokes", w = 420.0, h = 180.0, fitToSize = true)
    lines(grouped + bounds.copy(), channel = "retrace clusters", w = 420.0, h = 180.0, fitToSize = true)
    lines(clean + bounds.copy(), channel = "fitted network", w = 420.0, h = 180.0, fitToSize = true)
}
drawWorkout()

controlGroup {
    slider("Fit tolerance", 0.2, 5.0, step = 0.05, initialValue = fitTolerance) { fitTolerance = it; drawWorkout() }
    slider("Retrace tolerance", 0.0, 8.0, step = 0.1, initialValue = retraceTolerance) { retraceTolerance = it; drawWorkout() }
    slider("Join tolerance", 0.0, 12.0, step = 0.1, initialValue = joinTolerance) { joinTolerance = it; drawWorkout() }
}
