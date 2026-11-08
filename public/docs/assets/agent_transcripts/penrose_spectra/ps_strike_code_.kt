import afield.audio.modal.*

// A Penrose patch and a square lattice as spring networks with equal stiffness, so E = 0 rings
// at 220 Hz in both. Each row, top to bottom: strike energy against pitch for one struck vertex.
val penrose = PenroseSpectraDocs.penrose()
val square = PenroseSpectraDocs.square()
val pm = penrose.springModes(PenroseSpectraDocs.STIFFNESS)
val sm = square.springModes(PenroseSpectraDocs.STIFFNESS)
val strikes = [
    Triple("Penrose, most confined weight", pm, PenroseSpectraDocs.strikeVertex(penrose, pm, true)),
    Triple("Penrose, seven-edged vertex", pm, PenroseSpectraDocs.strikeVertex(penrose, pm, false)),
    Triple("square lattice, centre", sm, square.nearest(square.centre.first, square.centre.second)),
]
val out = ArrayList<FLine>()
val lo = 120.0
val hi = 340.0
strikes.forEachIndexed { row, (_, modes, v) ->
    val a = modes.strikeVertex(v)
    val f = modes.frequencies(220.0)
    val bins = DoubleArray(110)
    for (k in a.indices) bins[((f[k] - lo) / (hi - lo) * bins.size).toInt().coerceIn(0, bins.size - 1)] += a[k]
    val y0 = row * 1.3
    bins.forEachIndexed { b, e ->
        if (e > 0)
            out +=
                fline {
                    moveTo(b * 0.05, y0)
                    lineTo(b * 0.05, y0 - sqrt(e))
                }
                    .apply {
                        color = vec(1.0, 0.55, 0.15, 1.0)
                        thickness = 3.0
                    }
    }
    out +=
        fline {
            moveTo((220.0 - lo) / (hi - lo) * 5.5, y0 + 0.05)
            lineTo((220.0 - lo) / (hi - lo) * 5.5, y0 + 0.2)
        }
            .apply {
                color = vec(1.0, 1.0, 1.0, 1.0)
                thickness = 2.0
            }
}
lines(out, w = 600.0, h = 300.0, fitToSize = true)
markdown(
    "measure",
    strikes.joinToString(" · ") { (name, modes, v) -> "$name: %.1f%% at 220 Hz".format(100 * modes.zeroShare(v)) },
)
