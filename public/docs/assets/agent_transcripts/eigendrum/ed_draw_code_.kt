import afield.audio.engine.WavFloatArrayWriter
import afield.audio.modal.*

// Draw a closed shape on the drawing layer (Alt+D, freedraw), then run this box.
// With nothing drawn yet it uses a heart-ish polar curve.
val stroke = notation.strokes.lastOrNull()?.toFLine()
val outline = stroke ?: polygonFLine(polarPolygon(200) { t -> 1 + 0.35 * sin(t) * abs(cos(t)) + 0.15 * cos(3 * t) })
val mesh = drumMeshFitted(outline, 32)
val modes = mesh.modes(12)
val pictures =
    (0 until 4).flatMap { k ->
        modes.modeMesh(k).map { it.copy().apply { transform { v -> v.add(k * 1.15f, 0f, 0f) } } } +
            modes.nodalLines(k).copy().apply {
                transform { v -> v.add(k * 1.15f, 0f, 0f) }
                color = vec(1.0, 1.0, 1.0, 1.0)
            }
    }
lines(pictures, w = 520.0, h = 150.0, fitToSize = true)
val c = mesh.x.indices.filter { !mesh.boundary[it] }.minBy { abs(mesh.x[it] - 0.4) + abs(mesh.y[it] - 0.35) }
display(
    "strike",
    WavFloatArrayWriter.encodeMono16(modes.render(modes.strike(mesh.x[c], mesh.y[c]), fundamental = 130.0), 44100),
)
println(
    if (stroke == null) "no stroke yet: using the polar curve" else "drum from your stroke: ${mesh.nodeCount} nodes",
)
