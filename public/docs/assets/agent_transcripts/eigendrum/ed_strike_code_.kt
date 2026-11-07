import afield.audio.engine.WavFloatArrayWriter
import afield.audio.modal.*

// A square drum struck on the centre, which lies on a nodal line of every mode with m or n even,
// and off-centre. The bars are how strongly each of the 16 modes rings.
val modes = drumMesh(doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0), 0.05).modes(16)
val spots = [0.5 to 0.5, 0.31 to 0.22]
val out = ArrayList<FLine>()
spots.forEachIndexed { s, (px, py) ->
    val dx = s * 2.4f
    out += modes.modeMesh(1).map { it.copy().apply { transform { v -> v.add(dx, 0f, 0f) } } }
    out +=
        modes.nodalLines(1).copy().apply {
            transform { v -> v.add(dx, 0f, 0f) }
            color = vec(1.0, 1.0, 1.0, 1.0)
            thickness = 1.5
        }
    out +=
        fline { circle(px + dx, py, 0.04) }
            .apply {
                color = vec(1.0, 0.85, 0.3, 1.0)
                filled = true
            }
    val amps = modes.strike(px, py)
    val peak = amps.maxOf { abs(it) }
    amps.forEachIndexed { k, a ->
        out +=
            fline {
                moveTo(dx + k * 0.06, -0.15)
                lineTo(dx + k * 0.06, -0.15 - 0.6 * abs(a) / peak)
            }
                .apply {
                    color = vec(1.0, 0.85, 0.3, 1.0)
                    thickness = 3.0
                }
    }
    display("strike ${s + 1}", WavFloatArrayWriter.encodeMono16(modes.render(amps, fundamental = 120.0), 44100))
}
lines(out, w = 520.0, h = 300.0, fitToSize = true)
