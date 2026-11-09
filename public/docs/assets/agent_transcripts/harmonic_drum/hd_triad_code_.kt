import afield.audio.engine.WavFloatArrayWriter
import afield.audio.modal.*

// Bend your last drawn stroke (or the heart) into a uniform drum whose three lowest
// modes sound a major triad, 4:5:6.
val stroke = notation.strokes.lastOrNull()?.toFLine()
val start = if (stroke != null) PolarShape.fromLine(stroke, 3) else PolarShape.fromPolygon(EigendrumDocs.heart, 3)
val design = designShape([4.0, 5.0, 6.0], start = start, h = 0.08)
val modes = polarDrumMesh(0.05) { 1.0 }.polarMorphed(design.shape).modes(12)
val out = ArrayList<FLine>()
out +=
    design.start.outline().apply {
        color = vec(0.6, 0.6, 0.6, 1.0)
        thickness = 2.0
    }
out +=
    design.shape.outline().apply {
        color = vec(1.0, 0.85, 0.3, 1.0)
        thickness = 2.0
    }
for (k in 0 until 3) {
    val dx = (k + 1) * 2.8f
    out += modes.modeMesh(k).map { it.copy().apply { transform { v -> v.add(dx, 0f, 0f) } } }
    out +=
        modes.nodalLines(k).copy().apply {
            transform { v -> v.add(dx, 0f, 0f) }
            color = vec(1.0, 1.0, 1.0, 1.0)
        }
}
lines(out, w = 520.0, h = 140.0, fitToSize = true)
println("start ${design.startFit}, fitted ${design.fit}; ratios ${modes.ratios().take(3).map { (4 * it).f3 }}")
display(
    "strike",
    WavFloatArrayWriter.encodeMono16(modes.render(modes.strike(0.3, 0.25, 0.04), fundamental = 220.0), 44100),
)
